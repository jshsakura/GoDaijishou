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
    private static final String TAG = "Custom Target Launcher";
    private static final String LG_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final String TARGET_PACKAGE = "org.es_de.frontend";
    private static final String TARGET_MAIN_ACTIVITY = TARGET_PACKAGE + ".MainActivity";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!LG_LAUNCHER_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        try {
            hookLauncherMethods(lpparam.classLoader);
        } catch (Throwable t) {
            log("Failed to hook LG Second Launcher: " + t.getMessage());
        }
    }

    private void hookLauncherMethods(ClassLoader classLoader) {
        Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", classLoader);

        XposedHelpers.findAndHookMethod(activityClass, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                launchDaijishou((android.content.Context) param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                launchDaijishou((android.content.Context) param.thisObject);
            }
        });
    }

    private void launchDaijishou(android.content.Context context) {
        if (!context.getClass().getName().startsWith(LG_LAUNCHER_PACKAGE)) {
            return;
        }

        if (!isPackageInstalled(TARGET_PACKAGE, context.getPackageManager())) {
            showToast(context, "Custom Launcher app is not installed. Please install it first.");
            return;
        }

        Intent daijishouIntent = new Intent()
                .setComponent(new ComponentName(TARGET_PACKAGE, TARGET_MAIN_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(daijishouIntent);
        } catch (Exception e) {
            log("Failed to launch Custom Launcher app: " + e.getMessage());
            showToast(context, "Failed to launch Custom Launcher. Please check if it's installed correctly.");
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
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }

    private void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}