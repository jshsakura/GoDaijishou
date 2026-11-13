package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Handler;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * iPhone-style vertical volume capsule, drawn by system_server itself so the app needs no
 * overlay permission. Shown briefly on the screen in use whenever a user-facing volume
 * changes. Apps that consume the volume keys (camera shutter, games) change no volume, so
 * nothing appears there. Never focusable or touchable.
 */
final class SystemVolumeOverlay {

    // Hidden framework broadcast and extras (stable since API 1, sent on every volume change).
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    // WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY (hidden): above apps and system bars.
    private static final int TYPE_VOLUME_OVERLAY = 2020;

    private static final long SHOW_DURATION_MS = 1500;
    private static final long SLIDE_MS = 180;
    private static final int WIDTH_DP = 40;
    private static final int HEIGHT_DP = 140;
    private static final int EDGE_MARGIN_DP = 12;
    private static final int SLIDE_DP = 24;

    private static Context system;
    private static Handler handler;
    private static AudioManager audio;
    private static DisplayManager displays;
    private static WindowManager windowManager;
    private static VolumePillView view;
    private static int viewDisplayId = -1;
    private static float density = 1f;
    private static final Runnable hideRunnable = SystemVolumeOverlay::slideOut;

    private SystemVolumeOverlay() {
    }

    static void install(Context context, Handler h) {
        system = context;
        handler = h;
        audio = context.getSystemService(AudioManager.class);
        displays = context.getSystemService(DisplayManager.class);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                // Cheapest check first. A bottom screen mode feature: the stock screen mode
                // keeps the phone's own volume panel.
                if (!SystemSettings.volumeOverlay || SystemSettings.original()) return;
                int stream = intent.getIntExtra(EXTRA_STREAM_TYPE, -1);
                int value = intent.getIntExtra(EXTRA_STREAM_VALUE, -1);
                if (value < 0 || !isUserFacingStream(stream)) return;
                try {
                    show(stream, value);
                } catch (Throwable ignored) {
                    ModuleLog.w(ignored);
                    // Never let the overlay disturb system_server.
                }
            }
        }, new IntentFilter(ACTION_VOLUME_CHANGED), null, h);
    }

    // One key press also broadcasts alias streams (TTS, accessibility, DTMF); only the
    // streams a user actually adjusts should drive the overlay.
    private static boolean isUserFacingStream(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC:
            case AudioManager.STREAM_RING:
            case AudioManager.STREAM_NOTIFICATION:
            case AudioManager.STREAM_ALARM:
            case AudioManager.STREAM_VOICE_CALL:
                return true;
            default:
                return false;
        }
    }

    private static int targetDisplayId() {
        // The screen in use: in original mode the top screen (shown on the second one).
        return SystemSettings.secondScreenOn && SystemSettings.secondScreenMode()
                ? MainModule.SECOND_SCREEN_DISPLAY_ID : Display.DEFAULT_DISPLAY;
    }

    private static void show(int stream, int value) throws Exception {
        int displayId = targetDisplayId();
        if (view != null && viewDisplayId != displayId) remove();
        boolean fresh = view == null;
        if (fresh && !attach(displayId)) return;

        int max = Math.max(1, audio.getStreamMaxVolume(stream));
        view.setIcon(value == 0 ? R.drawable.ic_ft_volume_off : R.drawable.ic_ft_volume);
        view.setLevel(value / (float) max, !fresh);
        view.animate().alpha(1f).translationX(0f)
                .setDuration(SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator(2f))
                .withEndAction(null)
                .start();

        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, SHOW_DURATION_MS);
    }

    private static boolean attach(int displayId) throws Exception {
        Display display = displays.getDisplay(displayId);
        if (display == null) return false;
        // Window from the system context on that display; view resources from this module's package.
        windowManager = system.createDisplayContext(display).getSystemService(WindowManager.class);
        Context moduleContext = system.createPackageContext(BuildConfig.APPLICATION_ID, 0)
                .createDisplayContext(display);
        density = moduleContext.getResources().getDisplayMetrics().density;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(WIDTH_DP), dp(HEIGHT_DP),
                TYPE_VOLUME_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.x = dp(EDGE_MARGIN_DP);
        lp.setTitle("GoDaijishou:Volume");

        VolumePillView v = new VolumePillView(moduleContext);
        v.setAlpha(0f);
        v.setTranslationX(dp(SLIDE_DP));
        windowManager.addView(v, lp);
        view = v;
        viewDisplayId = displayId;
        return true;
    }

    private static void slideOut() {
        if (view == null) return;
        view.animate().alpha(0f).translationX(dp(SLIDE_DP))
                .setDuration(SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(SystemVolumeOverlay::remove)
                .start();
    }

    private static void remove() {
        handler.removeCallbacks(hideRunnable);
        VolumePillView v = view;
        if (v == null) return;
        view = null;
        viewDisplayId = -1;
        v.animate().cancel();
        try {
            windowManager.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private static int dp(int value) {
        return Math.round(value * density);
    }
}
