package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The notification shade takes a rotation while it is hidden (scope: com.android.systemui).
 *
 * The shade's window stays added but hidden, and a hidden window gets its new configuration
 * only when it is next drawn: the first pull-down after a rotation. LG's panel rebuilds itself
 * when its orientation, smallest width or rotation changed, so it rebuilt in the middle of
 * that swipe and dropped it (the pull-down "did nothing" after turning the screen). When the
 * main display turns, the hidden shade's views get the process's new configuration at once
 * (it arrives right away, the window's does not): the panel rebuilds there, unseen, and finds
 * nothing changed when it is pulled. A few checks per rotation; nothing otherwise.
 */
final class ShadeRotationHook {

    private static final String CONTROLLER = "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl";
    private static final String CONTAINER = "com.android.systemui.statusbar.phone.NotificationsQuickSettingsContainer";

    private static volatile View shade;
    private static int lastRotation = -1;
    private static boolean listening;

    private ShadeRotationHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> controller = XposedHelpers.findClass(CONTROLLER, cl);
        XposedHelpers.findField(controller, "mNotificationShadeView");
        XposedBridge.hookAllMethods(controller, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object v = XposedHelpers.getObjectField(param.thisObject, "mNotificationShadeView");
                if (!(v instanceof View)) return;
                shade = (View) v;
                ModuleLog.i("shade: attached");
                listen(shade.getContext());
            }
        });
        installWidthFit(cl);
    }

    /**
     * LG sets the quick settings' and notifications' widths only in the shade container's
     * onConfigurationChanged (notification_panel_width: 644 dp when the screen is at least
     * 650 dp wide and turned, otherwise match_parent). Turned on the top screen in second screen
     * mode, the panels got a fixed 644 dp; back in main screen mode on the 1240 px panel, that
     * width stayed and the shade ran off both sides (clock, tiles and notifications cut). A
     * panel never needs more than the container's width: one wider is a width left from another
     * screen and gets LG's own narrow-screen value again. Field checks per measure only.
     *
     * The container does not override onMeasure: the hook sits on the ConstraintLayout one it
     * inherits and returns at once for every other layout.
     */
    private static void installWidthFit(ClassLoader cl) {
        Class<?> container = XposedHelpers.findClassIfExists(CONTAINER, cl);
        if (container == null) return;
        java.lang.reflect.Method measure = null;
        for (Class<?> c = container; c != null && c != View.class && measure == null; c = c.getSuperclass()) {
            try {
                measure = c.getDeclaredMethod("onMeasure", int.class, int.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (measure == null) return;
        XposedBridge.hookMethod(measure, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject.getClass() != container) return;
                try {
                    int room = View.MeasureSpec.getSize((Integer) param.args[0]);
                    if (room <= 0) return;
                    ViewGroup g = (ViewGroup) param.thisObject;
                    for (int i = g.getChildCount() - 1; i >= 0; i--) {
                        ViewGroup.LayoutParams lp = g.getChildAt(i).getLayoutParams();
                        if (lp == null || lp.width <= room) continue;
                        ModuleLog.i("shade: panel width " + lp.width + " fitted to " + room);
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    }
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
    }

    private static void listen(Context context) {
        if (listening) return;
        listening = true;
        Handler main = new Handler(Looper.getMainLooper());
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) lastRotation = d.getRotation();
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY) return;
                Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (display == null) return;
                int rotation = display.getRotation();
                if (rotation == lastRotation) return;
                lastRotation = rotation;
                ModuleLog.i("shade: main display turned to " + rotation);
                relayout();
            }
        }, main);
    }

    // The hidden shade's views told about the new orientation now, once the process has it.
    private static int tries;

    private static void relayout() {
        View v = shade;
        if (v == null) return;
        v.removeCallbacks(APPLY);
        tries = 0;
        APPLY.run();
    }

    private static final Runnable APPLY = ShadeRotationHook::apply;

    private static void apply() {
        View v = shade;
        if (v == null || v.isShown()) return; // shown: it follows the rotation as it is
        try {
            Display d = v.getContext().getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return;
            android.graphics.Point size = new android.graphics.Point();
            d.getRealSize(size);
            int want = size.x > size.y ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
            Configuration c = new Configuration(v.getContext().getResources().getConfiguration());
            if (c.orientation != want) {
                // The process's configuration is a moment behind the display: look again.
                if (++tries <= 8) v.postDelayed(APPLY, 100);
                return;
            }
            v.dispatchConfigurationChanged(c);
            ModuleLog.i("shade: configuration applied while hidden");
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }
}
