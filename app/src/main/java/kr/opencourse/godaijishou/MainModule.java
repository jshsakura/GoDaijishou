package kr.opencourse.godaijishou;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Display;
import android.widget.Toast;

import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainModule implements IXposedHookLoadPackage {

    private static final String TARGET_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final int SECOND_SCREEN_DISPLAY_ID = 4;
    private static final String TAG = "GodaijishouRedirector";

    // Provider를 찾기 위한 주소(Uri)
    private static final Uri SETTINGS_URI = Uri.parse("content://kr.opencourse.godaijishou.provider/settings");

    // 중복 실행 방지
    private static volatile long lastLaunchTime = 0;
    private static final long LAUNCH_COOLDOWN_MS = 500;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_LAUNCHER_PACKAGE.equals(lpparam.packageName)) return;

        // onCreate: 초기 실행
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                launchSelectedLauncher((Activity) param.thisObject);
            }
        });

        // onResume: 뒤로가기로 돌아왔을 때 다시 실행
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                launchSelectedLauncher((Activity) param.thisObject);
            }
        });
    }

    private void launchSelectedLauncher(Activity launcherActivity) {
        Display display = launcherActivity.getWindowManager().getDefaultDisplay();
        if (display == null || display.getDisplayId() != SECOND_SCREEN_DISPLAY_ID) return;

        final Context appContext = launcherActivity.getApplicationContext();

        // ContentProvider에서 설정 값 가져오기
        String targetPackage = null;
        Cursor cursor = null;
        try {
            cursor = appContext.getContentResolver().query(SETTINGS_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                targetPackage = cursor.getString(cursor.getColumnIndexOrThrow("launcher_package"));
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " Error querying ContentProvider: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }

        // 선택한 런처가 없으면 기본 런처 사용 (fallback)
        if (TextUtils.isEmpty(targetPackage)) {
            log("No launcher selected, using default");
            return; // 기본 세컨드 런처 사용
        }

        // 이미 선택한 런처가 포그라운드에서 실행 중이면 무시 (무한루프 방지)
        if (isLauncherInForeground(appContext, targetPackage)) return;

        // 쿨다운 체크
        long now = System.currentTimeMillis();
        if (now - lastLaunchTime < LAUNCH_COOLDOWN_MS) return;
        lastLaunchTime = now;

        // 선택한 런처가 설치되지 않았으면 fallback
        if (!isPackageInstalled(targetPackage, appContext.getPackageManager())) {
            showToast(appContext, targetPackage + " is not installed. Using default launcher.");
            log(targetPackage + " not installed, using default launcher");
            return; // 기본 세컨드 런처 사용
        }

        try {
            // Android 13 호환: getLaunchIntentForPackage 사용
            Intent launcherIntent = appContext.getPackageManager().getLaunchIntentForPackage(targetPackage);

            if (launcherIntent != null) {
                // Android 13 호환성
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                }
                launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(SECOND_SCREEN_DISPLAY_ID);
                launcherActivity.startActivity(launcherIntent, options.toBundle());
                launcherActivity.finish();
                log("Successfully launched: " + targetPackage);
            } else {
                // fallback: 기본 런처 사용
                showToast(appContext, "Cannot launch " + targetPackage + ". Using default launcher.");
                log("getLaunchIntentForPackage returned null for " + targetPackage + ", using default");
            }
        } catch (Exception e) {
            // fallback: 기본 런처 사용
            showToast(appContext, "Failed to launch " + targetPackage + ". Using default launcher.");
            log("Failed to launch " + targetPackage + ": " + e.getMessage());
        }
    }

    private void showToast(final Context context, final String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }

    private boolean isLauncherInForeground(Context context, String packageName) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return false;

        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (packageName.equals(process.processName)) {
                return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            }
        }
        return false;
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