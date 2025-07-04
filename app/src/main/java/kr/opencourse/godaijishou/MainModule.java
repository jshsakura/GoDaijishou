package kr.opencourse.godaijishou;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context; // Context import
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils; // TextUtils import
import android.view.Display;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences; // XSharedPreferences import
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainModule implements IXposedHookLoadPackage {

    private static final String TARGET_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final int SECOND_SCREEN_DISPLAY_ID = 4;
    private static final String TAG = "GodaijishouRedirector";

    // Provider를 찾기 위한 주소(Uri)
    private static final Uri SETTINGS_URI = Uri.parse("content://kr.opencourse.godaijishou.provider/settings");

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_LAUNCHER_PACKAGE.equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Activity launcherActivity = (Activity) param.thisObject;
                Display display = launcherActivity.getWindowManager().getDefaultDisplay();

                if (display == null || display.getDisplayId() != SECOND_SCREEN_DISPLAY_ID) return;

                final Context appContext = launcherActivity.getApplicationContext();
                String targetPackage = null;
                String targetActivity = null;

                // --- ContentProvider를 통해 설정 값 가져오기 ---
                Cursor cursor = null;
                try {
                    cursor = appContext.getContentResolver().query(SETTINGS_URI, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        targetPackage = cursor.getString(cursor.getColumnIndexOrThrow("launcher_package"));
                        targetActivity = cursor.getString(cursor.getColumnIndexOrThrow("launcher_activity"));
                    }
                } catch (Exception e) {
                    XposedBridge.log("Error querying ContentProvider: " + e.getMessage());
                } finally {
                    if (cursor != null) cursor.close();
                }
                // ---------------------------------------------------

                if (TextUtils.isEmpty(targetPackage)) return;

                if (!isPackageInstalled(targetPackage, appContext.getPackageManager())) return;

                Intent launcherIntent = new Intent().setComponent(new ComponentName(targetPackage, targetActivity))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(SECOND_SCREEN_DISPLAY_ID);

                try {
                    launcherActivity.startActivity(launcherIntent, options.toBundle());
                } finally {
                    launcherActivity.finish();
                }
            }
        });
    }

    private boolean isPackageInstalled(String packageName, PackageManager packageManager) {
        try {
            packageManager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}