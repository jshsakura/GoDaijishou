package kr.opencourse.godaijishou;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

public class MainModule implements IXposedHookLoadPackage {
    private static final String LG_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final String DAIJISHOU_PACKAGE = "com.magneticchen.daijishou";
    private static final String DAIJISHOU_MAIN_ACTIVITY = DAIJISHOU_PACKAGE + ".activities.MainActivity";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("MainModule: handleLoadPackage called for " + lpparam.packageName);

        if (!LG_LAUNCHER_PACKAGE.equals(lpparam.packageName)) {
            XposedBridge.log("MainModule: Not the LG Second Launcher package, ignoring.");
            return;
        }

        XposedBridge.log("MainModule: Attempting to hook LG Second Launcher");

        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader);

            // onCreate 후킹
            XposedBridge.hookAllMethods(activityClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    handleActivityMethod(param, "onCreate");
                }
            });

            // onResume 후킹
            XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    handleActivityMethod(param, "onResume");
                }
            });

            XposedBridge.log("MainModule: Successfully hooked LG Second Launcher");
        } catch (Throwable t) {
            XposedBridge.log("MainModule: Failed to hook LG Second Launcher");
            XposedBridge.log(t);
        }
    }

    private void handleActivityMethod(XC_MethodHook.MethodHookParam param, String methodName) {
        String className = param.thisObject.getClass().getName();
        XposedBridge.log("MainModule: " + methodName + " called for class: " + className);

        if (className.startsWith(LG_LAUNCHER_PACKAGE)) {
            XposedBridge.log("MainModule: LG Second Launcher Activity detected in " + methodName);

            // Daijishou 앱을 시작하는 인텐트를 생성하고 실행합니다.
            Intent daijishouIntent = new Intent();
            daijishouIntent.setComponent(new ComponentName(DAIJISHOU_PACKAGE, DAIJISHOU_MAIN_ACTIVITY));
            daijishouIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 현재 컨텍스트를 사용하여 Daijishou 앱을 시작합니다.
            ((android.content.Context) param.thisObject).startActivity(daijishouIntent);

            XposedBridge.log("MainModule: Launched Daijishou app from " + methodName);
        }
    }
}