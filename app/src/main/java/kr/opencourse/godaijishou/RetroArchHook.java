package kr.opencourse.godaijishou;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * RetroArch second screen black screen recovery (runs in the RetroArch process).
 *
 * Symptom: leaving RetroArch on the second screen and coming back leaves audio over a black screen.
 *          (the render thread is alive but its EGL surface is bound to a dead surface)
 * Approach: RetroArch is NativeActivity-based and takes its surface straight from the window
 *          (Window.takeSurface). Changing the window format makes WindowManager destroy and
 *          recreate the surface, delivering surfaceDestroyed -> surfaceCreated to native code
 *          again, which re-creates the EGL surface.
 * Trigger: onResume or window focus gain on the second screen (display 4).
 *          (switching between screens can move focus without onStop, so both are handled)
 *
 * Note: process force-kill approaches (relaunch on game switch, cleanup on exit) were
 *       tried and dropped. The broken frontend kill-before-launch since RetroArch 1.16.0
 *       (known issue, Daijishou #703) is for the frontend to handle at selection time;
 *       working around it in the module only makes the switch timing awkward.
 */
final class RetroArchHook {

    // official / 64-bit / 32-bit builds
    private static final String[] PACKAGES = {
            "com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32"
    };

    private static volatile boolean surfaceFormatToggle = false;
    private static volatile long lastRecoveryTime = 0;
    private static final long RECOVERY_DEDUP_MS = 1000; // dedupe resume + focus firing together

    private RetroArchHook() {
    }

    static boolean isTarget(String packageName) {
        for (String pkg : PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        return false;
    }

    static void install() {
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
    }

    private static int displayIdOf(Activity activity) {
        try {
            Display display = activity.getWindowManager().getDefaultDisplay();
            return display != null ? display.getDisplayId() : -1;
        } catch (Throwable t) {
            ModuleLog.w(t);
            return -1;
        }
    }

    private static void maybeRecoverSurface(final Activity activity) {
        if (displayIdOf(activity) != MainModule.SECOND_SCREEN_DISPLAY_ID) return;

        long now = System.currentTimeMillis();
        if (now - lastRecoveryTime < RECOVERY_DEDUP_MS) return;
        lastRecoveryTime = now;

        // Recreate the surface after the normal resume path finishes so native code
        // receives the callbacks in a ready state. Run after a short delay.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                Window window = activity.getWindow();
                if (window == null) return;
                // Setting the same format does not recreate the surface, so alternate
                // between two 32-bit formats to force a recreation every time.
                // Both must be opaque: TRANSLUCENT leaves the game as a translucent layer,
                // forcing SurfaceFlinger to alpha-blend (GPU composition) every frame,
                // which costs load and heat for the whole session.
                surfaceFormatToggle = !surfaceFormatToggle;
                window.setFormat(surfaceFormatToggle
                        ? PixelFormat.RGBA_8888 : PixelFormat.RGBX_8888);
            } catch (Throwable ignored) {
                ModuleLog.w(ignored);
                // No failure here may affect RetroArch.
            }
        }, 300);
    }
}
