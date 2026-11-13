package kr.opencourse.godaijishou;

import android.app.ActivityOptions;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * While the top display is unavailable ("no top screen", or "bottom screen only" with the
 * swivel open), dialog activities that would open on the top display go to the second
 * screen instead: USB debugging prompts, permission requests and the like. Full-screen
 * apps keep the display the system picked. Runs inside system_server.
 *
 * LaunchParamsController.calculate() is where the system picks the task display area for
 * a launch.
 */
final class LaunchRedirectHook {

    private static final int TOP_DISPLAY_ID = 0;

    private LaunchRedirectHook() {
    }

    static void install(ClassLoader classLoader) {
        Class<?> launchParams = XposedHelpers.findClass(
                "com.android.server.wm.LaunchParamsController$LaunchParams", classLoader);
        XposedHelpers.findAndHookMethod(
                "com.android.server.wm.LaunchParamsController", classLoader,
                "calculate",
                "com.android.server.wm.Task",
                "android.content.pm.ActivityInfo$WindowLayout",
                "com.android.server.wm.ActivityRecord",
                "com.android.server.wm.ActivityRecord",
                ActivityOptions.class,
                "com.android.server.wm.ActivityStarter$Request",
                int.class,
                launchParams,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // Cheapest checks first: feature off or swivel closed -> untouched.
                        boolean toTop = TopMirror.isShowing();
                        if (!toTop && !SystemSettings.sendToBottom()) return;
                        try {
                            if (toTop) redirectToTop(param);
                            else redirect(param);
                        } catch (Throwable ignored) {
                            ModuleLog.w(ignored);
                            // Leave the system's choice as is.
                        }
                    }
                });
    }

    private static void redirect(XC_MethodHook.MethodHookParam param) {
        Object activity = param.args[2];
        if (activity == null || !isDialog(activity)) return;

        Object result = param.args[7];
        Object area = XposedHelpers.getObjectField(result, "mPreferredTaskDisplayArea");
        Object controller = param.thisObject;
        Object atms = XposedHelpers.getObjectField(controller, "mService");
        Object root = XposedHelpers.getObjectField(atms, "mRootWindowContainer");

        int displayId = area != null
                ? (int) XposedHelpers.callMethod(area, "getDisplayId")
                : TOP_DISPLAY_ID; // no preference -> the system would use the default display
        if (displayId != TOP_DISPLAY_ID) return;

        Object second = XposedHelpers.callMethod(root, "getDisplayContent", MainModule.SECOND_SCREEN_DISPLAY_ID);
        if (second == null) return;
        Object secondArea = XposedHelpers.callMethod(second, "getDefaultTaskDisplayArea");
        if (secondArea != null) {
            XposedHelpers.setObjectField(result, "mPreferredTaskDisplayArea", secondArea);
        }
    }

    /**
     * Original mode: the second screen shows the top screen, so anything opening on the
     * second screen (an app's own choice, LG's swivel apps) would be hidden under it. Every
     * launch bound there goes to the top screen instead; homes stay where they are.
     */
    private static void redirectToTop(XC_MethodHook.MethodHookParam param) {
        Object activity = param.args[2];
        if (activity != null) {
            try {
                if ((boolean) XposedHelpers.callMethod(activity, "isActivityTypeHomeOrRecents")) return;
            } catch (Throwable ignored) {
            }
        }
        Object result = param.args[7];
        Object area = XposedHelpers.getObjectField(result, "mPreferredTaskDisplayArea");
        if (area == null) return; // the default display already
        if ((int) XposedHelpers.callMethod(area, "getDisplayId") != MainModule.SECOND_SCREEN_DISPLAY_ID) return;
        Object atms = XposedHelpers.getObjectField(param.thisObject, "mService");
        Object root = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
        Object top = XposedHelpers.callMethod(root, "getDisplayContent", TOP_DISPLAY_ID);
        if (top == null) return;
        Object topArea = XposedHelpers.callMethod(top, "getDefaultTaskDisplayArea");
        if (topArea != null) XposedHelpers.setObjectField(result, "mPreferredTaskDisplayArea", topArea);
    }

    // Floating or translucent windows (permission requests, USB debugging prompts) don't
    // cover their parent. Full-screen apps such as the camera or RetroArch manage their own
    // display, and moving them made LG's services and the apps themselves shut them down.
    private static boolean isDialog(Object activity) {
        try {
            return !(boolean) XposedHelpers.callMethod(activity, "occludesParent");
        } catch (Throwable noMethod) {
            ModuleLog.w(noMethod);
            return !XposedHelpers.getBooleanField(activity, "fullscreen");
        }
    }
}
