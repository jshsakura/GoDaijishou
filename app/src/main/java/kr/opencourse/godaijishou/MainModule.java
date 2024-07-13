package kr.opencourse.godaijishou;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

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

            android.content.Context context = (android.content.Context) param.thisObject;

            // Daijishou 앱이 설치되어 있는지 확인
            if (isPackageInstalled(DAIJISHOU_PACKAGE, context.getPackageManager())) {
                // Daijishou 앱을 시작하는 인텐트를 생성하고 실행합니다.
                Intent daijishouIntent = new Intent();
                daijishouIntent.setComponent(new ComponentName(DAIJISHOU_PACKAGE, DAIJISHOU_MAIN_ACTIVITY));
                daijishouIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    context.startActivity(daijishouIntent);
                    XposedBridge.log("MainModule: Launched Daijishou app from " + methodName);
                } catch (Exception e) {
                    XposedBridge.log("MainModule: Failed to launch Daijishou app");
                    XposedBridge.log(e);
                    showToast(context, "Failed to launch Daijishou. Please check if it's installed correctly.");
                }
            } else {
                XposedBridge.log("MainModule: Daijishou app is not installed");
                showToast(context, "Daijishou app is not installed. Please install it first.");
            }
        }
    }

    private boolean isPackageInstalled(String packageName, PackageManager packageManager) {
        try {
            packageManager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showToast(final android.content.Context context, final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}