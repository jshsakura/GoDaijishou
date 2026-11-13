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
 * Power menu on the second screen. Runs inside system_server.
 *
 * GlobalActions.showDialog() is the single entry point for the system power menu
 * (power key long press, accessibility, etc.). When the feature is enabled and the
 * swivel is open, our PowerMenuActivity is launched on the second screen instead.
 * In every other case, or if launching fails, the stock menu is shown as usual.
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
                        // Original mode: the top screen's own menu (it shows below anyway).
                        if (!SystemSettings.powerMenuOnSecondScreen || SystemSettings.original()) return;
                        try {
                            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (!isSecondScreenOn(context)) return;
                            if (launchMenu(context)) param.setResult(null);
                        } catch (Throwable ignored) {
                            ModuleLog.w(ignored);
                            // Fall through to the stock menu.
                        }
                    }
                });
    }

    private static boolean isSecondScreenOn(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm != null ? dm.getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID) : null;
        return display != null && display.getState() == Display.STATE_ON;
    }

    private static boolean launchMenu(Context context) {
        try {
            Intent intent = new Intent()
                    .setClassName(BuildConfig.APPLICATION_ID, MENU_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(MainModule.SECOND_SCREEN_DISPLAY_ID)
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
