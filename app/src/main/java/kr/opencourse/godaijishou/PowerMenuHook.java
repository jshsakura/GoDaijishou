package kr.opencourse.godaijishou;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.Display;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Power menu on the bottom panel, in both screen modes. Runs inside system_server.
 *
 * GlobalActions.showDialog() is the single entry point for the system power menu
 * (power key long press, accessibility, etc.). Our PowerMenuActivity is launched on the
 * display the bottom panel is now instead. If that display is off or launching fails, the
 * stock menu is shown as usual.
 */
final class PowerMenuHook {

    private static final String MENU_ACTIVITY = "kr.opencourse.godaijishou.PowerMenuActivity";

    private PowerMenuHook() {
    }

    static void install(ClassLoader classLoader) {
        // Load module settings early so the flag is ready before the first long press.
        XposedHelpers.findAndHookMethod(
                "com.android.server.policy.PhoneWindowManager", classLoader,
                "systemReady",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            SystemSettings.init((Context) XposedHelpers.getObjectField(param.thisObject, "mContext"));
                        } catch (Throwable ignored) {
                            ModuleLog.w(ignored);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                "com.android.server.policy.GlobalActions", classLoader,
                "showDialog", boolean.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Ours in both modes, on the bottom panel: display 0 while swapped
                        // (main screen), display 4 otherwise (second screen, and main screen
                        // mode while locked or asleep, when the stock menu would go to the top).
                        int display = DisplaySwap.isSwapped() ? Display.DEFAULT_DISPLAY
                                : MainModule.SECOND_SCREEN_DISPLAY_ID;
                        try {
                            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (!isOn(context, display)) return;
                            if (launchMenu(context, display)) param.setResult(null);
                        } catch (Throwable ignored) {
                            ModuleLog.w(ignored);
                            // Fall through to the stock menu.
                        }
                    }
                });
    }

    private static boolean isOn(Context context, int displayId) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm != null ? dm.getDisplay(displayId) : null;
        return display != null && display.getState() == Display.STATE_ON;
    }

    private static boolean launchMenu(Context context, int displayId) {
        try {
            Intent intent = new Intent()
                    .setClassName(BuildConfig.APPLICATION_ID, MENU_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle();
            UserHandle current = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT");
            XposedHelpers.callMethod(context, "startActivityAsUser",
                    new Class<?>[]{Intent.class, Bundle.class, UserHandle.class},
                    intent, options, current);
            return true;
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }
}
