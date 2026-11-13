package kr.opencourse.godaijishou;

import android.graphics.Rect;

import java.lang.reflect.Array;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Main screen mode: the second screen's touch panel becomes the top screen's (system_server).
 *
 * The input reader places each touch panel on a display through the viewports the display
 * manager hands it. While the top screen is shown on the second screen, the second screen's
 * viewport is handed over as the top screen's: same panel, but its touches are the top
 * screen's, at the spot where the top screen is drawn on the panel. Everything that reads
 * touches (apps, the lock screen, home/back/status bar gestures) then gets them natively, as
 * from the top screen's own panel. Nothing is injected; leaving the mode hands the viewport
 * back.
 */
final class TouchBypassHook {

    private static final String NATIVE = "com.android.server.input.NativeInputManagerService$NativeImpl";

    // The display manager pushes viewports on its own thread, set() on the settings one:
    // one at a time, so a newer push is never overwritten by an older copy.
    private static final Object lock = new Object();
    private static Object nativeImpl;
    private static Object lastViewports;
    // Where the top screen sits on the second one; null: not redirected.
    private static volatile float[] place;

    private TouchBypassHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClass(NATIVE, cl);
        XposedBridge.hookAllMethods(c, "setDisplayViewports", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 1 || !(param.args[0] instanceof Object[])) return;
                synchronized (lock) {
                    if (!replaying) {
                        nativeImpl = param.thisObject;
                        lastViewports = param.args[0];
                    }
                    try {
                        param.args[0] = redirect((Object[]) param.args[0]);
                    } catch (Throwable t) {
                        ModuleLog.w(t); // hand them over as they are
                    }
                }
            }
        });
    }

    /** Top screen shown at scale/left/top with size w x h on the second screen; null ends it. */
    static void set(float[] newPlace) {
        synchronized (lock) {
            float[] old = place;
            place = newPlace;
            if (old == null && newPlace == null) return; // nothing to undo
            if (nativeImpl == null || lastViewports == null) return;
            replaying = true;
            try {
                // The display manager's latest viewports again, through the hook above.
                XposedHelpers.callMethod(nativeImpl, "setDisplayViewports", lastViewports);
            } catch (Throwable t) {
                ModuleLog.w(t);
            } finally {
                replaying = false;
            }
        }
    }

    private static boolean replaying;

    private static Object[] redirect(Object[] viewports) {
        float[] p = place;
        if (p == null) return viewports;
        Object[] out = (Object[]) Array.newInstance(viewports.getClass().getComponentType(), viewports.length);
        for (int i = 0; i < viewports.length; i++) {
            Object vp = viewports[i];
            out[i] = vp;
            if (vp == null || XposedHelpers.getIntField(vp, "displayId") != MainModule.SECOND_SCREEN_DISPLAY_ID) {
                continue;
            }
            Object copy = XposedHelpers.callMethod(vp, "makeCopy");
            float scale = p[0], left = p[1], top = p[2], w = p[3], h = p[4];
            XposedHelpers.setIntField(copy, "displayId", android.view.Display.DEFAULT_DISPLAY);
            XposedHelpers.setIntField(copy, "orientation", 0);
            ((Rect) XposedHelpers.getObjectField(copy, "logicalFrame")).set(0, 0, Math.round(w), Math.round(h));
            ((Rect) XposedHelpers.getObjectField(copy, "physicalFrame")).set(Math.round(left), Math.round(top),
                    Math.round(left + w * scale), Math.round(top + h * scale));
            out[i] = copy;
        }
        return out;
    }
}
