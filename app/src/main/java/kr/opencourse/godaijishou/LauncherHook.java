package kr.opencourse.godaijishou;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Display;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Second screen home redirect (runs in the LG second launcher process).
 * When the LG second launcher shows on the second screen (display 4), launches the chosen launcher on top of it.
 */
final class LauncherHook {

    static final String TARGET_PACKAGE = "com.lge.secondlauncher";

    // Backoff against duplicate launches and heat
    // If redirects repeat in a short time (launchers bouncing each other), the pause
    // grows automatically to stop CPU spikes and heat. No effect on normal use.
    private static volatile long lastLaunchTime = 0;
    private static volatile int rapidCount = 0;
    private static final long LAUNCH_COOLDOWN_MS = 500;      // base cooldown
    private static final long BACKOFF_WINDOW_MS = 15000;     // relaunch within this window counts as a repeat
    private static final int BACKOFF_THRESHOLD = 3;          // allowed consecutive repeats
    private static final long BACKOFF_COOLDOWN_MS = 10000;   // pause once repeats are detected

    // Settings cache: queried once, then refreshed only on change via ContentObserver.
    // -> no cross-process query per lifecycle callback, so hook overhead is ~0
    private static volatile String cachedTargetPackage = null;
    private static volatile boolean cacheInitialized = false;

    private LauncherHook() {
    }

    static void install() {
        // onResume alone covers every redirect point (first launch and returning via back).
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                maybeRedirect((Activity) param.thisObject);
            }
        });
    }

    private static void maybeRedirect(Activity launcherActivity) {
        // 1) Cheapest check first: return immediately unless on the second screen.
        //    Most onResume callbacks exit here (no allocation, IPC or logging).
        Display display = launcherActivity.getWindowManager().getDefaultDisplay();
        if (display == null || display.getDisplayId() != MainModule.SECOND_SCREEN_DISPLAY_ID) return;

        final Context appContext = launcherActivity.getApplicationContext();

        // 2) Read the setting from the cache (queried once + observer registered).
        ensureCacheInitialized(appContext);
        final String targetPackage = cachedTargetPackage;
        if (TextUtils.isEmpty(targetPackage)) return;  // nothing chosen -> keep the stock launcher

        // 3) Cooldown + backoff against duplicate launches / loops (very cheap)
        //    Only actual launches are counted. Counting skipped resumes let launcher
        //    resumes pile up during a game session, suppressing the redirect for 10s
        //    on return home and leaving the user on the LG second launcher.
        long now = System.currentTimeMillis();
        long sinceLast = now - lastLaunchTime;
        long cooldown = (rapidCount >= BACKOFF_THRESHOLD) ? BACKOFF_COOLDOWN_MS : LAUNCH_COOLDOWN_MS;
        if (sinceLast < cooldown) return;
        rapidCount = (sinceLast < BACKOFF_WINDOW_MS) ? rapidCount + 1 : 0;
        lastLaunchTime = now;

        try {
            // getLaunchIntentForPackage returns null when not installed / no launcher activity,
            // so no separate install check IPC (getPackageInfo) is needed.
            Intent launcherIntent = appContext.getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (launcherIntent == null) {
                showToast(launcherActivity, targetPackage + " 실행 불가(미설치?). 기본 런처 사용.");
                return;
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            }
            // CLEAR_TASK + finish() removes the second screen's home (the LG launcher).
            // Unlike the main display, a secondary display does not restore its home fallback,
            // so returning from RetroArch etc. ends on a black screen with no home to show.
            // -> Keep the LG second launcher alive and put the target launcher on top of it.
            //   (REORDER_TO_FRONT: bring an existing instance forward, leave running app tasks alone)
            launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            ActivityOptions options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(MainModule.SECOND_SCREEN_DISPLAY_ID);
            launcherActivity.startActivity(launcherIntent, options.toBundle());
            // No finish(): keep the second home as a fallback to avoid a black screen on return
        } catch (Exception e) {
            showToast(launcherActivity, targetPackage + " 실행 실패. 기본 런처 사용.");
        }
    }

    /**
     * Initializes the settings cache once and registers a ContentObserver.
     * Later changes refresh the cache through the observer, so there is no IPC per lifecycle callback.
     */
    private static void ensureCacheInitialized(final Context context) {
        if (cacheInitialized) return;
        synchronized (LauncherHook.class) {
            if (cacheInitialized) return;

            cachedTargetPackage = queryTargetPackage(context);
            try {
                ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        cachedTargetPackage = queryTargetPackage(context);
                    }
                };
                context.getContentResolver().registerContentObserver(
                        SettingsProvider.CONTENT_URI, false, observer);
            } catch (Exception ignored) {
                // If registration fails, the cache keeps working with the initial value.
            }
            cacheInitialized = true;
        }
    }

    private static String queryTargetPackage(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(SettingsProvider.CONTENT_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(AppPreferences.KEY_LAUNCHER_PACKAGE));
            }
        } catch (Exception ignored) {
            // Query failed -> null, safely falls back to the default launcher
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private static void showToast(final Context context, final String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
