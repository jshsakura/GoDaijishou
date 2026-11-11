package kr.opencourse.godaijishou;

import android.app.ActivityOptions;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * While the top display is unavailable ("no top screen", or "bottom screen only" with the
 * swivel open), new activities that would open on the top display go to the second
 * screen instead:
 * USB debugging prompts, permission dialogs, settings screens, apps without a preferred
 * display, etc. Runs inside system_server.
 *
 * LaunchParamsController.calculate() is where the system picks the task display area for
 * a launch. Any result pointing at the top display is moved, even when the caller asked
 * for the top display explicitly. Only home activities stay, so the top home never
 * replaces the second screen home.
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
                        if (!SystemSettings.topUnavailable()) return;
                        try {
                            redirect(param);
                        } catch (Throwable ignored) {
                            // Leave the system's choice as is.
                        }
                    }
                });
    }

    private static void redirect(XC_MethodHook.MethodHookParam param) {
        Object activity = param.args[2];
        if (activity != null && (boolean) XposedHelpers.callMethod(activity, "isActivityTypeHome")) return;

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
}
