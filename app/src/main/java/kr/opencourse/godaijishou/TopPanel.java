package kr.opencourse.godaijishou;

import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.view.Display;

import de.robv.android.xposed.XposedHelpers;

/**
 * Powers the top (main) panel off while the swivel is open and "bottom screen only" is on.
 * Runs inside system_server, on the SystemSettings thread.
 *
 * Unlike the black overlay, nothing is composed or lit on the top panel at all. Android
 * still treats display 0 as on; only the panel power is cut through SurfaceControl, the
 * same way screen mirroring tools turn a device screen off. When the system turns the
 * panel back on (wake, display state change), it is turned off again on that event.
 */
final class TopPanel {

    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;

    private static boolean panelForcedOff = false;

    private TopPanel() {
    }

    /** Re-evaluates and applies the panel state. Called on settings / display events. */
    static void apply(DisplayManager dm) {
        Display top = dm.getDisplay(Display.DEFAULT_DISPLAY);
        boolean topOn = top != null && top.getState() == Display.STATE_ON;
        boolean wantOff = SystemSettings.topUnavailable() && topOn;

        if (wantOff) {
            // Re-apply on every relevant event: the system may have powered it back on.
            setPowerMode(POWER_MODE_OFF);
            panelForcedOff = true;
        } else if (panelForcedOff) {
            panelForcedOff = false;
            // Hand the panel back only if the system wants it on; otherwise leave it to the system.
            if (topOn) setPowerMode(POWER_MODE_NORMAL);
        }
    }

    private static void setPowerMode(int mode) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            // Resolve the panel behind logical display 0 exactly; never guess the "primary" one.
            long id = SystemActions.physicalId(SystemSettings.context(), Display.DEFAULT_DISPLAY);
            if (id < 0) return;
            IBinder token = (IBinder) XposedHelpers.callStaticMethod(sc, "getPhysicalDisplayToken", id);
            if (token != null) {
                XposedHelpers.callStaticMethod(sc, "setDisplayPowerMode", token, mode);
            }
        } catch (Throwable ignored) {
            // Unsupported: the app falls back to the black overlay.
        }
    }
}
