package kr.opencourse.godaijishou;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.view.Window;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainModule implements IXposedHookLoadPackage {

    private static final String TARGET_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final int SECOND_SCREEN_DISPLAY_ID = 4;

    // [실험적] RetroArch 검정화면 복구 대상 패키지 (공식/64비트/32비트 배포판)
    private static final String[] RETROARCH_PACKAGES = {
            "com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"
    };

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
        for (String pkg : RETROARCH_PACKAGES) {
            if (pkg.equals(lpparam.packageName)) {
                hookRetroArchSurfaceRecovery();
                return;
            }
        }

        if (!TARGET_LAUNCHER_PACKAGE.equals(lpparam.packageName)) return;

        // 런처 '이동' 지점은 onResume 하나면 충분하다(최초 실행·뒤로가기 복귀 모두 커버).
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                maybeRedirect((Activity) param.thisObject);
            }
        });
    }

    // ─── RetroArch 세컨드 스크린 검정화면 복구 ───
    // 증상: 세컨드 스크린의 RetroArch를 벗어났다 돌아오면 소리만 나고 화면이 검게 남음.
    //       (렌더링 스레드는 살아있지만 EGL 서피스가 죽은 서피스에 연결된 상태)
    // 접근: RetroArch는 NativeActivity 기반이라 서피스를 윈도우에서 직접 받는다
    //       (Window.takeSurface). 윈도우 포맷을 바꾸면 WindowManager가 서피스를
    //       파기·재생성하면서 네이티브 쪽에 surfaceDestroyed→surfaceCreated가
    //       다시 전달되고, 그 경로에서 EGL 서피스를 새로 잡게 된다.
    // 트리거: 세컨드 스크린(디스플레이 4)에서 onResume 또는 윈도우 포커스 획득.
    //        (화면 간 전환은 onStop 없이 포커스만 오가는 경우가 있어 둘 다 잡는다)

    private static volatile boolean surfaceFormatToggle = false;
    private static volatile long lastRecoveryTime = 0;
    private static final long RECOVERY_DEDUP_MS = 1000; // resume+포커스 중복 발동 방지

    private void hookRetroArchSurfaceRecovery() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                maybeRecoverSurface((Activity) param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged",
                boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if ((Boolean) param.args[0]) {
                    maybeRecoverSurface((Activity) param.thisObject);
                }
            }
        });

        // 게임 실행 중 프론트엔드에서 다른 게임을 고르면, 살아있는 인스턴스가
        // onNewIntent로 새 게임을 받고도 이전 콘텐츠를 그대로 보여준다.
        // (RetroArch 1.16.0 이후 프론트엔드의 kill-before-launch가 깨진
        //  알려진 문제: Daijishou #703)
        // → 새 콘텐츠 인텐트는 기존 인스턴스가 처리하지 못하게 막고,
        //   같은 인텐트를 AlarmManager에 예약해둔 뒤 프로세스를 내린다.
        //   (알람은 시스템에 남으므로 800ms 뒤 항상 '새 프로세스'로 게임이 뜬다.
        //    새 프로세스는 onCreate 경로로 인텐트를 받으므로 루프는 생기지 않는다)
        XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class,
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                Intent intent = (Intent) param.args[0];
                if (intent == null || !intent.hasExtra("ROM")) return; // 콘텐츠 실행 인텐트만
                try {
                    Intent relaunch = new Intent(intent);
                    relaunch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    int displayId = displayIdOf(activity);
                    if (displayId >= 0) opts.setLaunchDisplayId(displayId);
                    PendingIntent pi = PendingIntent.getActivity(
                            activity.getApplicationContext(), 0, relaunch,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                            opts.toBundle());
                    AlarmManager am = (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
                    am.setExact(AlarmManager.RTC, System.currentTimeMillis() + 800, pi);
                } catch (Throwable t) {
                    // 재실행 예약에 실패하면 차단하지 않고 기존 동작으로 둔다.
                    return;
                }
                param.setResult(null);
                System.exit(0);
            }
        });

        // 게임(액티비티)을 정상 종료하면 프로세스도 함께 내린다.
        // 서피스가 죽은 프로세스가 백그라운드에 살아남았다가 다음 게임 실행 때
        // 재사용되며 검정화면·이전 게임 재실행 증상을 일으키는 것을 차단.
        // (isFinishing == 사용자가 콘텐츠를 닫고 나간 경우라 SRAM 저장은 이미 끝난 뒤)
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!activity.isFinishing()) return; // 회전 등 재생성이면 유지
                // 종료 브로드캐스트 등 마무리 작업이 끝날 시간을 준 뒤 프로세스 종료
                new Handler(Looper.getMainLooper()).postDelayed(() -> System.exit(0), 500);
            }
        });
    }

    private static int displayIdOf(Activity activity) {
        try {
            Display display = activity.getWindowManager().getDefaultDisplay();
            return display != null ? display.getDisplayId() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void maybeRecoverSurface(final Activity activity) {
        if (displayIdOf(activity) != SECOND_SCREEN_DISPLAY_ID) return;

        long now = System.currentTimeMillis();
        if (now - lastRecoveryTime < RECOVERY_DEDUP_MS) return;
        lastRecoveryTime = now;

        // 정상 resume 경로가 끝난 뒤에 서피스를 재생성해야 네이티브 쪽이
        // 준비된 상태에서 콜백을 받는다. 약간의 지연 후 실행.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Window window = activity.getWindow();
                if (window == null) return;
                // 같은 포맷으로는 재생성이 일어나지 않으므로 32비트 포맷
                // 두 가지를 번갈아 지정해 매번 강제 재생성한다.
                surfaceFormatToggle = !surfaceFormatToggle;
                window.setFormat(surfaceFormatToggle
                        ? PixelFormat.RGBA_8888 : PixelFormat.TRANSLUCENT);
            } catch (Throwable ignored) {
                // 어떤 실패도 RetroArch 동작에 영향을 주지 않는다.
            }
        }, 300);
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
        //    반복 카운트는 '실제 실행'만 센다. 스킵된 resume까지 세면 게임 세션 중
        //    런처 resume이 쌓여, 정작 홈으로 복귀했을 때 리다이렉트가 10초간 억제되어
        //    LG 세컨드 런처에 머무르는 문제가 있었다.
        long now = System.currentTimeMillis();
        long sinceLast = now - lastLaunchTime;
        long cooldown = (rapidCount >= BACKOFF_THRESHOLD) ? BACKOFF_COOLDOWN_MS : LAUNCH_COOLDOWN_MS;
        if (sinceLast < cooldown) return;
        rapidCount = (sinceLast < BACKOFF_WINDOW_MS) ? rapidCount + 1 : 0;
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
