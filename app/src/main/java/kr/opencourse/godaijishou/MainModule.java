package kr.opencourse.godaijishou;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.widget.Toast;

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

    // 중복 실행 방지 + 발열 방지용 백오프
    // 짧은 시간에 리다이렉트가 계속 반복되면(런처끼리 튕겨내는 오실레이션) 쉬는 간격을
    // 자동으로 늘려 CPU 폭주/발열을 막는다. 정상 사용(가끔 홈 누르기)에는 영향 없음.
    private static volatile long lastLaunchTime = 0;
    private static volatile int rapidCount = 0;
    private static final long LAUNCH_COOLDOWN_MS = 500;      // 기본 쿨다운
    private static final long BACKOFF_WINDOW_MS = 15000;     // 이 안에서 재실행되면 '반복'으로 간주
    private static final int  BACKOFF_THRESHOLD = 3;         // 연속 반복 허용 횟수
    private static final long BACKOFF_COOLDOWN_MS = 10000;   // 반복 감지 시 쉬는 간격

    // --- 설정값 캐시 ---
    // 생명주기마다 ContentProvider를 쿼리하지 않도록, 최초 1회만 읽고
    // 이후에는 설정이 바뀔 때만 ContentObserver로 갱신한다. → 후킹 콜백의 부하 ≈ 0
    private static volatile String cachedTargetPackage = null;
    private static volatile boolean cacheInitialized = false;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_LAUNCHER_PACKAGE.equals(lpparam.packageName)) return;

        // 런처 '이동' 지점은 onResume 한 곳이면 충분하다.
        // (최초 실행 시에도 onCreate 다음 onResume가 반드시 호출되고,
        //  뒤로가기로 돌아온 경우에도 onResume가 호출된다.)
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                maybeRedirect((Activity) param.thisObject);
            }
        });
    }

    private void maybeRedirect(Activity launcherActivity) {
        // 1) 가장 싼 체크 먼저: 세컨드 스크린(디스플레이 4)이 아니면 즉시 반환.
        //    대부분의 onResume 콜백은 여기서 곧바로 빠져나가므로 부하가 거의 없다.
        Display display = launcherActivity.getWindowManager().getDefaultDisplay();
        if (display == null || display.getDisplayId() != SECOND_SCREEN_DISPLAY_ID) return;

        final Context appContext = launcherActivity.getApplicationContext();

        // 2) 설정값은 캐시에서 읽는다. 최초 1회만 쿼리하고 Observer를 등록한다.
        ensureCacheInitialized(appContext);
        final String targetPackage = cachedTargetPackage;

        // 선택한 런처가 없으면 기본 세컨드 런처를 그대로 사용
        if (TextUtils.isEmpty(targetPackage)) return;

        // 3) 쿨다운 + 백오프로 중복 실행/무한 루프(발열) 방지 (초저비용)
        long now = System.currentTimeMillis();
        long sinceLast = now - lastLaunchTime;
        // 직전 실행과 가까우면 반복으로 보고 카운트 증가, 충분히 지났으면 리셋
        if (sinceLast < BACKOFF_WINDOW_MS) {
            rapidCount++;
        } else {
            rapidCount = 0;
        }
        long cooldown = (rapidCount >= BACKOFF_THRESHOLD) ? BACKOFF_COOLDOWN_MS : LAUNCH_COOLDOWN_MS;
        if (sinceLast < cooldown) return;
        lastLaunchTime = now;

        // 4) 선택한 런처가 설치되지 않았으면 fallback
        if (!isPackageInstalled(targetPackage, appContext.getPackageManager())) {
            showToast(appContext, targetPackage + " is not installed. Using default launcher.");
            log(targetPackage + " not installed, using default launcher");
            return;
        }

        try {
            // Android 13 호환: getLaunchIntentForPackage 사용
            Intent launcherIntent = appContext.getPackageManager().getLaunchIntentForPackage(targetPackage);

            if (launcherIntent != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                }
                launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(SECOND_SCREEN_DISPLAY_ID);
                launcherActivity.startActivity(launcherIntent, options.toBundle());
                launcherActivity.finish();
                log("Successfully launched: " + targetPackage);
            } else {
                showToast(appContext, "Cannot launch " + targetPackage + ". Using default launcher.");
                log("getLaunchIntentForPackage returned null for " + targetPackage + ", using default");
            }
        } catch (Exception e) {
            showToast(appContext, "Failed to launch " + targetPackage + ". Using default launcher.");
            log("Failed to launch " + targetPackage + ": " + e.getMessage());
        }
    }

    /**
     * 설정값 캐시를 최초 1회만 초기화하고 ContentObserver를 등록한다.
     * 이후 설정 변경은 Observer가 캐시를 갱신하므로 생명주기마다 IPC가 발생하지 않는다.
     */
    private void ensureCacheInitialized(final Context context) {
        if (cacheInitialized) return;
        synchronized (MainModule.class) {
            if (cacheInitialized) return;

            cachedTargetPackage = queryTargetPackage(context);

            try {
                ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        cachedTargetPackage = queryTargetPackage(context);
                        log("Settings changed, cache updated: " + cachedTargetPackage);
                    }
                };
                context.getContentResolver().registerContentObserver(SETTINGS_URI, false, observer);
            } catch (Exception e) {
                log("Failed to register ContentObserver: " + e.getMessage());
            }

            cacheInitialized = true;
            log("Cache initialized: " + cachedTargetPackage);
        }
    }

    private String queryTargetPackage(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(SETTINGS_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow("launcher_package"));
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " Error querying ContentProvider: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private void showToast(final Context context, final String message) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
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
