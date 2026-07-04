package kr.opencourse.godaijishou;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainModule implements IXposedHookLoadPackage {

    private static final String TARGET_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final int SECOND_SCREEN_DISPLAY_ID = 4;

    // Provider를 찾기 위한 주소(Uri)
    private static final Uri SETTINGS_URI = Uri.parse("content://kr.opencourse.godaijishou.provider/settings");

    // 중복 실행 + 발열 방지용 백오프
    // 짧은 시간에 리다이렉트가 반복되면(런처끼리 튕겨내는 오실레이션) 쉬는 간격을
    // 자동으로 늘려 CPU 폭주/발열을 막는다. 정상 사용에는 영향 없음.
    private static volatile long lastLaunchTime = 0;
    private static volatile int rapidCount = 0;
    private static final long LAUNCH_COOLDOWN_MS = 500;      // 기본 쿨다운
    private static final long BACKOFF_WINDOW_MS = 15000;     // 이 안에서 재실행되면 '반복'으로 간주
    private static final int  BACKOFF_THRESHOLD = 3;         // 연속 반복 허용 횟수
    private static final long BACKOFF_COOLDOWN_MS = 10000;   // 반복 감지 시 쉬는 간격

    // 설정값 캐시: 최초 1회만 쿼리하고, 이후에는 ContentObserver로 변경 시에만 갱신.
    // → 생명주기마다 크로스-프로세스 쿼리가 없어 후킹 콜백의 부하 ≈ 0
    private static volatile String cachedTargetPackage = null;
    private static volatile boolean cacheInitialized = false;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_LAUNCHER_PACKAGE.equals(lpparam.packageName)) return;

        // 런처 '이동' 지점은 onResume 하나면 충분하다(최초 실행·뒤로가기 복귀 모두 커버).
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                maybeRedirect((Activity) param.thisObject);
            }
        });
    }

    private void maybeRedirect(Activity launcherActivity) {
        // 1) 가장 싼 체크 먼저: 세컨드 스크린이 아니면 즉시 반환.
        //    대부분의 onResume 콜백은 여기서 곧바로 빠져나간다(할당·IPC·로그 없음).
        Display display = launcherActivity.getWindowManager().getDefaultDisplay();
        if (display == null || display.getDisplayId() != SECOND_SCREEN_DISPLAY_ID) return;

        final Context appContext = launcherActivity.getApplicationContext();

        // 2) 설정값은 캐시에서 읽는다(최초 1회만 쿼리 + Observer 등록).
        ensureCacheInitialized(appContext);
        final String targetPackage = cachedTargetPackage;
        if (TextUtils.isEmpty(targetPackage)) return;  // 선택 없음 → 기본 런처 사용

        // 3) 쿨다운 + 백오프로 중복 실행/무한 루프(발열) 방지 (초저비용)
        long now = System.currentTimeMillis();
        long sinceLast = now - lastLaunchTime;
        rapidCount = (sinceLast < BACKOFF_WINDOW_MS) ? rapidCount + 1 : 0;
        long cooldown = (rapidCount >= BACKOFF_THRESHOLD) ? BACKOFF_COOLDOWN_MS : LAUNCH_COOLDOWN_MS;
        if (sinceLast < cooldown) return;
        lastLaunchTime = now;

        try {
            // getLaunchIntentForPackage는 미설치/런처없음이면 null을 준다.
            // → 별도의 설치 여부 확인 IPC(getPackageInfo)가 필요 없다.
            Intent launcherIntent = appContext.getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (launcherIntent == null) {
                showToast(appContext, targetPackage + " 실행 불가(미설치?). 기본 런처 사용.");
                return;
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            }
            // CLEAR_TASK + finish()를 쓰면 세컨드 스크린의 '홈'(LG 런처)이 사라진다.
            // 보조 디스플레이는 기본 화면과 달리 홈 폴백이 자동 복구되지 않으므로,
            // RetroArch 등에서 돌아올 때 표시할 홈이 없어 검정화면이 된다.
            // → LG 세컨드 런처를 살려두고 그 위에 대상 런처만 올린다.
            //   (REORDER_TO_FRONT: 이미 떠 있는 인스턴스를 앞으로, 실행 중 앱 태스크는 건드리지 않음)
            launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(SECOND_SCREEN_DISPLAY_ID);
            launcherActivity.startActivity(launcherIntent, options.toBundle());
            // finish() 제거: 세컨드 홈을 폴백용으로 유지해 복귀 시 검정화면을 방지
        } catch (Exception e) {
            showToast(appContext, targetPackage + " 실행 실패. 기본 런처 사용.");
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
                    }
                };
                context.getContentResolver().registerContentObserver(SETTINGS_URI, false, observer);
            } catch (Exception ignored) {
                // Observer 등록 실패해도 캐시는 최초 값으로 동작한다.
            }
            cacheInitialized = true;
        }
    }

    private String queryTargetPackage(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(SETTINGS_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow("launcher_package"));
            }
        } catch (Exception ignored) {
            // 쿼리 실패 시 null → 기본 런처로 안전하게 폴백
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
}
