package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The second screen's panel as the phone's main display (system_server, Android 13).
 *
 * Android 13 swaps which panel is the main display (display 0) the way foldables swap inner
 * and outer screens: the display manager applies a layout (panel to logical display) per
 * device state. The Wing has no layouts; while swapped this hands the display manager one
 * where the second screen's panel is display 0 and the top panel is display 4, disabled.
 * Display 0 then is that panel natively: its size, density, vsync, touch panel, lock screen.
 * Nothing is written anywhere: a reboot always starts unswapped.
 *
 * Test switch for now: Settings.Global godaijishou_swap_test = 1 swaps, 0 restores. It
 * restores by itself after 30 seconds and whenever the screen goes off.
 */
final class DisplaySwap {

    static final String KEY_TEST = "godaijishou_swap_test";
    /** Settings.Global: the remote emergency exit (any non-zero value restores at once). */
    static final String KEY_ESCAPE = "godaijishou_escape";
    private static final long TEST_MILLIS = 30_000;

    private static Object mapper;   // LogicalDisplayMapper
    private static Object syncRoot; // DisplayManagerService.SyncRoot (the display manager's lock)
    private static volatile Object override; // Layout handed out while not null
    // The layout in force before the latest set(): resetLayoutLocked compares it with the new
    // one to find the displays that change (enabled or disabled). Handing it the new layout
    // twice hides that display 4 comes back, leaving it disabled with no touch viewport.
    private static Object previous;
    private static boolean previousIsStock; // previous is the firmware's own (no override)
    private static boolean inReset; // inside resetLayoutLocked (under the display lock)
    private static int resetGets;
    private static Object topAddress, bottomAddress; // the panels as the system first had them
    private static volatile boolean swapped; // the second screen's panel is display 0 now
    private static boolean watching;

    private DisplaySwap() {
    }

    private static boolean resyncing;
    // Display events up to this uptime are checked for a stale window size (set() arms it).
    private static volatile long resyncUntil;
    private static final long RESYNC_WINDOW_MS = 3_000;

    static void install(ClassLoader cl) {
        installTopNeverMirrors(cl);
        XposedBridge.hookAllConstructors(
                XposedHelpers.findClass("com.android.server.display.LogicalDisplayMapper", cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mapper = param.thisObject;
                        for (Object a : param.args) {
                            if (a != null && a.getClass().getName().endsWith("$SyncRoot")) syncRoot = a;
                        }
                    }
                });
        XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.android.server.display.DeviceStateToLayoutMap", cl),
                "get", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object o = override;
                        if (o == null) return;
                        // resetLayoutLocked asks for the "from" layout first, then the "to" one.
                        if (inReset && resetGets++ == 0) {
                            if (!previousIsStock) param.setResult(previous);
                            return; // stock: the firmware's own layout
                        }
                        param.setResult(o);
                    }
                });
        XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.android.server.display.LogicalDisplayMapper", cl),
                "resetLayoutLocked", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        inReset = true;
                        resetGets = 0;
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        inReset = false;
                    }
                });
        // A display coming back (or changed) with a stale size is put right in the same call,
        // before its next frame (the posted resyncs came a few frames late). Only just after a
        // swap or restore: any other display event (a rotation, a refresh rate) costs one read.
        XC_MethodHook resyncNow = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (android.os.SystemClock.uptimeMillis() > resyncUntil) return;
                if (resyncing) return; // our own onDisplayChanged
                resyncing = true;
                try {
                    RESYNC_WINDOWS.run();
                } finally {
                    resyncing = false;
                }
            }
        };
        Class<?> root = XposedHelpers.findClass("com.android.server.wm.RootWindowContainer", cl);
        XposedBridge.hookAllMethods(root, "onDisplayAdded", resyncNow);
        XposedBridge.hookAllMethods(root, "onDisplayChanged", resyncNow);
        try {
            installDarkWhileAsleep(cl);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        try {
            installNoBacklightFight(cl);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /**
     * Swapped, the second screen's panel is display 0's device as well: the main display's
     * controller sets its backlight (through its own brightness-to-backlight curve) and LG's
     * second screen modulator, which re-sends its own value whenever the device's last
     * backlight is not the one it sent (forceUpdate). The two never agree, so the modulator
     * looped: about 15000 updates and log lines a second, system_server above 150 % CPU, a hot
     * phone. While swapped the modulator is told the device holds what it last sent.
     */
    private static void installNoBacklightFight(ClassLoader cl) {
        Class<?> state = XposedHelpers.findClassIfExists("com.android.server.display.SecondaryDisplayPowerState", cl);
        if (state == null) return;
        XposedHelpers.findField(state, "mPhotonicModulator");
        hookActual(state, "getActualBrightness", "mActualBacklight");
        hookActual(state, "getActualSdrBrightness", "mActualSdrBacklight");
        // Both getters are tiny and compiled into the modulator's loop (inlined), where a hook
        // is never reached: the loop is run from its bytecode instead (LSPosed's deoptimize).
        try {
            Class<?> modulator = XposedHelpers.findClass(state.getName() + "$SecondaryPhotonicModulator",
                    state.getClassLoader());
            java.lang.reflect.Method deopt = XposedBridge.class.getMethod("deoptimizeMethod",
                    java.lang.reflect.Member.class);
            deopt.invoke(null, modulator.getDeclaredMethod("run"));
        } catch (Throwable t) {
            ModuleLog.w("modulator deoptimize", t);
        }
    }

    private static void hookActual(Class<?> state, String method, String field) {
        XposedBridge.hookAllMethods(state, method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!swapped) return;
                try {
                    Object modulator = XposedHelpers.getObjectField(param.thisObject, "mPhotonicModulator");
                    if (modulator != null) param.setResult(XposedHelpers.getFloatField(modulator, field));
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
    }

    private static final int WAKEFULNESS_AWAKE = 1;
    private static volatile Object powerManager; // PowerManagerService

    /**
     * Going to sleep in main screen mode, LG's second screen power service follows the main
     * display's dozing and restore requests and turns the second screen's panel on for a
     * moment (it builds its swivel lock screen then, so that turn-on must stay): the panel the
     * user just switched off lit up and faded out. While the phone is not awake the second
     * screen's panel is turned on with its backlight off. A wake-up makes the phone awake first.
     */
    private static void installDarkWhileAsleep(ClassLoader cl) {
        Class<?> pms = XposedHelpers.findClassIfExists("com.android.server.power.PowerManagerService", cl);
        Class<?> state = XposedHelpers.findClassIfExists("com.android.server.display.SecondaryDisplayPowerState", cl);
        if (pms == null || state == null) return;
        XposedBridge.hookAllConstructors(pms, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                powerManager = param.thisObject;
            }
        });
        XposedBridge.hookAllMethods(state, "setScreenBrightness", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object p = powerManager;
                if (p == null || param.args.length == 0 || !(param.args[0] instanceof Float)) return;
                if ((Float) param.args[0] <= 0f) return;
                if (XposedHelpers.getIntField(p, "mWakefulnessRaw") != WAKEFULNESS_AWAKE) param.args[0] = 0f;
            }
        });
    }

    /** Starts the test switch (once the system context and handler exist). */
    static void watch() {
        if (watching) return;
        Context c = SystemSettings.context();
        Handler h = SystemSettings.handler();
        if (c == null || h == null) return;
        watching = true;
        try {
            Settings.Global.putInt(c.getContentResolver(), KEY_TEST, 0); // never swapped at boot
            c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY_TEST), false,
                    new ContentObserver(h) {
                        @Override
                        public void onChange(boolean selfChange) {
                            boolean on = Settings.Global.getInt(c.getContentResolver(), KEY_TEST, 0) == 1;
                            set(on);
                            h.removeCallbacks(TIMEOUT);
                            if (on) h.postDelayed(TIMEOUT, TEST_MILLIS);
                        }
                    });
            // The remote emergency exit: writing anything but 0 restores the phone's own
            // displays (locked, black, any state), then clears itself. The value changing
            // is the trigger, so writing it again always fires.
            Settings.Global.putInt(c.getContentResolver(), KEY_ESCAPE, 0);
            c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY_ESCAPE), false,
                    new ContentObserver(h) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (Settings.Global.getInt(c.getContentResolver(), KEY_ESCAPE, 0) != 0) {
                                Settings.Global.putInt(c.getContentResolver(), KEY_ESCAPE, 0);
                                restoreNow();
                            }
                        }
                    });
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            c.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Only the adb test switch ends with the screen; main screen mode stays.
                    if (Settings.Global.getInt(c.getContentResolver(), KEY_TEST, 0) == 1) TIMEOUT.run();
                }
            }, f, null, h);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /** Whether display 0 is the second screen's panel right now (TopPanel keeps its hands off). */
    /**
     * Display 0's display group assigned again (SwivelPosture.installDefaultGroup decides),
     * then the usual update, which tells the power manager. Groups are otherwise assigned only
     * when a display's info changes.
     */
    static void regroup() {
        Object m = mapper, lock = syncRoot;
        if (m == null || lock == null || !swapped) return;
        try {
            synchronized (lock) {
                Object display = XposedHelpers.callMethod(m, "getDisplayLocked", 0);
                if (display == null) return;
                XposedHelpers.callMethod(m, "assignDisplayGroupLocked", display);
                XposedHelpers.callMethod(m, "updateLogicalDisplaysLocked");
            }
        } catch (Throwable t) {
            ModuleLog.w("regroup", t);
        }
    }

    /** Whether the device is display 0's panel (caller holds the display manager's lock). */
    static boolean isDisplayZeroDevice(Object device) {
        Object m = mapper;
        if (m == null || device == null) return false;
        Object display = XposedHelpers.callMethod(m, "getDisplayLocked", 0);
        return display != null && XposedHelpers.callMethod(display, "getPrimaryDisplayDeviceLocked") == device;
    }

    static boolean isSwapped() {
        return swapped;
    }

    /** Restores at once (SafetyEscape); settings thread. */
    static void restoreNow() {
        Handler h = SystemSettings.handler();
        if (h != null) h.removeCallbacks(TIMEOUT);
        TIMEOUT.run();
    }

    private static final Runnable TIMEOUT = () -> {
        try {
            Context c = SystemSettings.context();
            if (c != null) Settings.Global.putInt(c.getContentResolver(), KEY_TEST, 0);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        set(false);
    };

    // After the last window resync, so the new process starts on the settled panel.
    private static final long RESTART_HOME_MS = 900;

    /**
     * LG's home process holds the gesture engine (quickstep): home and recents swipes on
     * display 0, with a swipe area computed for the panel it started on. It never learned of a
     * panel change reliably (no display event with another home in front), and a stale area
     * left the swipes dead until a reboot. The switch is made here, so the restart is too:
     * once per real panel change, whatever the home app is, and nothing in between.
     */
    private static final Runnable RESTART_HOME = () -> {
        try {
            android.app.ActivityManager am = SystemSettings.context()
                    .getSystemService(android.app.ActivityManager.class);
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (!TopHomeHook.TARGET_PACKAGE.equals(p.processName)) continue;
                ModuleLog.i("swap: restarting LG home for the new panel");
                android.os.Process.killProcess(p.pid);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    };

    /** Swaps (the second screen's panel as display 0) or restores. */
    static void set(boolean swapped) {
        Object m = mapper, lock = syncRoot;
        if (m == null || lock == null) return;
        // Restore only from a swap: every screen-off asks, and re-laying out an unswapped phone
        // for nothing would only risk it.
        if (!swapped && !DisplaySwap.swapped) return;
        if (swapped && DisplaySwap.swapped) return;
        MainScreen.quietTransitions(1500);
        try {
            synchronized (lock) {
                if (topAddress == null && !readAddresses(m)) return;
                Object layout = XposedHelpers.newInstance(
                        XposedHelpers.findClass("com.android.server.display.layout.Layout",
                                m.getClass().getClassLoader()));
                Object main = swapped ? bottomAddress : topAddress;
                Object other = swapped ? topAddress : bottomAddress;
                XposedHelpers.callMethod(layout, "createDisplayLocked", main, true, true, -1);
                // Display 4 keeps its number; off while the top panel is behind it.
                XposedHelpers.callMethod(layout, "createDisplayLocked", other, false, !swapped,
                        MainModule.SECOND_SCREEN_DISPLAY_ID);
                previousIsStock = override == null;
                previous = override;
                override = layout;
                DisplaySwap.swapped = swapped;
                int state = XposedHelpers.getIntField(m, "mDeviceState");
                XposedHelpers.callMethod(m, "setDeviceStateLocked", state, false);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        // LG's camera follows (SwivelFold): the folded phone's camera while swapped. Written
        // outside the display lock. (SystemUI does not follow: see MainModule.)
        publish(DisplaySwap.swapped);
        resyncUntil = android.os.SystemClock.uptimeMillis() + RESYNC_WINDOW_MS;
        Handler rh = SystemSettings.handler();
        if (rh != null) {
            rh.removeCallbacks(RESYNC_WINDOWS);
            // At once, then again as the window manager catches up with the change.
            rh.post(RESYNC_WINDOWS);
            for (int ms = 150; ms <= 750; ms += 150) rh.postDelayed(RESYNC_WINDOWS, ms);
            rh.removeCallbacks(BLANK_TOP);
            rh.postDelayed(BLANK_TOP, 800);
            if (DisplaySwap.swapped == swapped) { // the panel really changed
                rh.removeCallbacks(RESTART_HOME);
                rh.postDelayed(RESTART_HOME, RESTART_HOME_MS);
            }
        }
        if (!swapped) {
            verifyAttempts = 0;
            Handler h = SystemSettings.handler();
            if (h != null) {
                h.removeCallbacks(VERIFY);
                h.postDelayed(VERIFY, VERIFY_DELAY_MS);
            }
        }
    }

    /**
     * The window manager keeps a display's size from when it learned it. Display 4 goes away
     * on the swap and comes back on the restore; when its old window state was still being
     * torn down, the window manager took it back as it was, with the top panel's 1080x1920 it
     * had for a moment, and the second screen drew a squashed, homeless frame on its
     * 1080x1240 panel. After every layout change a display whose window size is not its
     * panel's is told it changed, so the window manager reads the real size again. One that
     * already has it is left alone: every needless change is one more relayout on screen.
     */
    private static final Runnable RESYNC_WINDOWS = () -> {
        try {
            Object wms = XposedHelpers.callStaticMethod(Class.forName("android.os.ServiceManager"),
                    "getService", "window");
            Object root = XposedHelpers.getObjectField(wms, "mRoot");
            Object dmi = XposedHelpers.getObjectField(wms, "mDisplayManagerInternal");
            Object real = XposedHelpers.newInstance(Class.forName("android.view.DisplayInfo"));
            for (int id : new int[] {android.view.Display.DEFAULT_DISPLAY, MainModule.SECOND_SCREEN_DISPLAY_ID}) {
                // Swapped, display 4 is the top panel, disabled: its window state is never
                // updated, so it never matches and would be "changed" again on every event.
                if (swapped && id == MainModule.SECOND_SCREEN_DISPLAY_ID) continue;
                Object dc = XposedHelpers.callMethod(root, "getDisplayContent", id);
                if (dc == null) continue;
                XposedHelpers.callMethod(dmi, "getNonOverrideDisplayInfo", id, real); // the panel, not what WM told it
                if (XposedHelpers.getIntField(dc, "mInitialDisplayWidth") == XposedHelpers.getIntField(real, "logicalWidth")
                        && XposedHelpers.getIntField(dc, "mInitialDisplayHeight") == XposedHelpers.getIntField(real, "logicalHeight")) continue;
                XposedHelpers.callMethod(root, "onDisplayChanged", id);
            }
            // The panel's rotation in this same pass, not a frame later.
            XposedHelpers.callMethod(wms, "updateRotation", false, false);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    };

    private static void publish(boolean on) {
        try {
            Context c = SystemSettings.context();
            if (c != null) Settings.Global.putInt(c.getContentResolver(), SwivelFold.KEY_SWAPPED, on ? 1 : 0);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        SwivelPosture.swapChanged();
    }

    /**
     * At boot, before SystemUI starts: never swapped yet, whatever the last boot left (a
     * folded SystemUI over an unswapped phone would keep the second screen's lock shut).
     */
    static void clearAtBoot(Context c) {
        try {
            if (Settings.Global.getInt(c.getContentResolver(), SwivelFold.KEY_SWAPPED, 0) != 0) {
                Settings.Global.putInt(c.getContentResolver(), SwivelFold.KEY_SWAPPED, 0);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // --- After a restore: display 4 must be enabled again, or its touches go nowhere ---

    private static final long VERIFY_DELAY_MS = 2_000;
    private static final int PHASE_ENABLED = 1; // LogicalDisplay.DISPLAY_PHASE_ENABLED
    private static int verifyAttempts;

    private static final Runnable VERIFY = new Runnable() {
        @Override
        public void run() {
            if (swapped) return;
            Object m = mapper, lock = syncRoot;
            if (m == null || lock == null) return;
            boolean ok;
            try {
                synchronized (lock) {
                    Object d = XposedHelpers.callMethod(m, "getDisplayLocked",
                            MainModule.SECOND_SCREEN_DISPLAY_ID, true);
                    ok = d != null && (int) XposedHelpers.callMethod(d, "getPhase") == PHASE_ENABLED;
                    if (!ok && d != null && verifyAttempts == 0) {
                        XposedHelpers.callMethod(d, "setPhase", PHASE_ENABLED);
                        XposedHelpers.callMethod(m, "updateLogicalDisplaysLocked");
                    }
                }
            } catch (Throwable t) {
                ModuleLog.w(t);
                ok = false;
            }
            if (ok) return;
            Handler h = SystemSettings.handler();
            if (verifyAttempts++ == 0 && h != null) {
                h.postDelayed(this, VERIFY_DELAY_MS); // look again after the nudge
                return;
            }
            // Still no second screen to touch: a reboot always starts unswapped.
            try {
                Context c = SystemSettings.context();
                c.getSystemService(android.os.PowerManager.class).reboot("godaijishou-display-recover");
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    };

    /**
     * Swapped, the top panel sits behind display 4, which is disabled and has no content, so
     * the display manager let it mirror display 0: both panels then showed layer stack 0.
     * Input works out a layer stack's touch transform from its first display, the top panel
     * (never rotated), so on the sideways bottom panel every touch landed turned by 90 degrees
     * (the lock screen's 0 key typed 4). Swapped, the top panel shows nothing instead.
     *
     * Caught where every layer stack is set (LG's display manager has its own configure
     * code), and checked once more after each swap in case no configure reached the top
     * panel. One compare per call (rare: display configuration only), a write only if wrong.
     */
    private static volatile Object topDevice;

    private static void installTopNeverMirrors(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.server.display.DisplayDevice", cl,
                "setLayerStackLocked", android.view.SurfaceControl.Transaction.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isTop(param.thisObject)) return;
                        topDevice = param.thisObject;
                        if (swapped && (int) param.args[1] == android.view.Display.DEFAULT_DISPLAY) {
                            param.args[1] = BLANK_LAYER_STACK;
                        }
                    }
                });
    }

    private static boolean isTop(Object device) {
        Object top = topAddress;
        if (top == null) return false;
        if (device == topDevice) return true;
        try {
            Object info = XposedHelpers.callMethod(device, "getDisplayDeviceInfoLocked");
            return top.equals(XposedHelpers.getObjectField(info, "address"));
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }

    // After a swap: the top panel still on layer stack 0 (no configure reached it) is blanked.
    private static final Runnable BLANK_TOP = () -> {
        Object m = mapper, lock = syncRoot;
        if (!swapped || m == null || lock == null) return;
        try {
            synchronized (lock) {
                // Swapped, display 4 is the top panel.
                Object logical = XposedHelpers.callMethod(m, "getDisplayLocked", MainModule.SECOND_SCREEN_DISPLAY_ID, true);
                Object d = logical == null ? topDevice : XposedHelpers.callMethod(logical, "getPrimaryDisplayDeviceLocked");
                if (d == null) return;
                if (XposedHelpers.getIntField(d, "mCurrentLayerStack") != android.view.Display.DEFAULT_DISPLAY) return;
                android.view.SurfaceControl.Transaction t = new android.view.SurfaceControl.Transaction();
                XposedHelpers.callMethod(d, "setLayerStackLocked", t, BLANK_LAYER_STACK);
                t.apply();
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    };

    private static final int BLANK_LAYER_STACK = -1; // LogicalDisplay.BLANK_LAYER_STACK

    // The panels behind display 0 (top) and display 4 (second screen), before any swap.
    private static boolean readAddresses(Object m) {
        Object top = address(m, android.view.Display.DEFAULT_DISPLAY);
        Object bottom = address(m, MainModule.SECOND_SCREEN_DISPLAY_ID);
        if (top == null || bottom == null) return false;
        topAddress = top;
        bottomAddress = bottom;
        return true;
    }

    private static Object address(Object m, int displayId) {
        try {
            Object logical = XposedHelpers.callMethod(m, "getDisplayLocked", displayId, true);
            if (logical == null) return null;
            Object device = XposedHelpers.callMethod(logical, "getPrimaryDisplayDeviceLocked");
            if (device == null) return null;
            Object info = XposedHelpers.callMethod(device, "getDisplayDeviceInfoLocked");
            return XposedHelpers.getObjectField(info, "address");
        } catch (Throwable t) {
            ModuleLog.w(t);
            return null;
        }
    }
}
