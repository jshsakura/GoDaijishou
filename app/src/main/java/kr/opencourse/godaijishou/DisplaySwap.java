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
    private static Object topAddress, bottomAddress; // the panels as the system first had them
    private static boolean everSwapped;
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
                        if (o != null) param.setResult(o);
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
                    TIMEOUT.run();
                }
            }, f, null, h);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
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
        if (!swapped && !everSwapped) return; // nothing to restore
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
                override = layout;
                everSwapped = true;
                int state = XposedHelpers.getIntField(m, "mDeviceState");
                XposedHelpers.callMethod(m, "setDeviceStateLocked", state, false);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

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
