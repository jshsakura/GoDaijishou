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

    static void install(ClassLoader cl) {
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

    /** Swaps (the second screen's panel as display 0) or restores. */
    static void set(boolean swapped) {
        Object m = mapper, lock = syncRoot;
        if (m == null || lock == null) return;
        // Restore only from a swap: every screen-off asks, and re-laying out an unswapped phone
        // for nothing would only risk it.
        if (!swapped && !DisplaySwap.swapped) return;
        if (swapped && DisplaySwap.swapped) return;
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
        // SystemUI used to follow through SwivelFold (folded lock screen); dropped, the swivel
        // lock got stuck. MainScreen unswaps before the lock screen instead.
        // (SystemUI is not told: SwivelFold is not installed, see MainModule.)
        if (!swapped) {
            verifyAttempts = 0;
            Handler h = SystemSettings.handler();
            if (h != null) {
                h.removeCallbacks(VERIFY);
                h.postDelayed(VERIFY, VERIFY_DELAY_MS);
            }
        }
    }

    private static void publish(boolean on) {
        try {
            Context c = SystemSettings.context();
            if (c != null) Settings.Global.putInt(c.getContentResolver(), SwivelFold.KEY_SWAPPED, on ? 1 : 0);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
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
