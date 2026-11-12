package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.provider.Settings;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Rotation forcing (merged from GoRotation90). Runs inside system_server.
 *
 * When enabled in settings, the auto-rotate toggle acts as a rotation trigger:
 *   auto-rotate OFF -> locked to landscape (ROTATION_90)
 *   auto-rotate ON  -> locked to portrait (ROTATION_0)
 * Main display: while the swivel is closed, apps that fix their own orientation keep it.
 * Second screen: always, whatever the app requests.
 *
 * The rotation hooks run while WindowManager's global lock is held, so they only read
 * a volatile flag (no IPC, no allocation) and return immediately when disabled.
 */
final class RotationHook {

    private static volatile boolean setupDone = false;
    private static volatile Object windowManagerService;

    private RotationHook() {
    }

    static void install(ClassLoader classLoader) {
        // Force the computed rotation itself (also snaps back to portrait in free-rotation mode).
        XposedHelpers.findAndHookMethod(
                "com.android.server.wm.DisplayRotation", classLoader,
                "rotationForOrientation", int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!setupDone) ensureSetup(param.thisObject);
                        // Second screen: the toggle decides, whatever the app asks for.
                        if (SystemSettings.forceRotation && isSecondScreen(param.thisObject)) {
                            param.setResult(forcedRotation());
                            return;
                        }
                        if (!shouldForce(param.thisObject)) return;
                        // Respect windows that lock their own orientation (camera, lock screen,
                        // portrait-only apps); force only free-orientation ones, like the OS would.
                        if (isFixedOrientation((Integer) param.args[0])) return;
                        param.setResult(forcedRotation());
                    }
                });

        // Keep the locked-rotation baseline consistent with the forced value.
        XposedHelpers.findAndHookMethod(
                "com.android.server.wm.DisplayRotation", classLoader,
                "updateRotationUnchecked", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!setupDone) ensureSetup(param.thisObject);
                        boolean second = SystemSettings.forceRotation && isSecondScreen(param.thisObject);
                        if (!second && !shouldForce(param.thisObject)) return;
                        try {
                            XposedHelpers.setIntField(param.thisObject, "mUserRotation", forcedRotation());
                        } catch (Throwable ignored) {
                        }
                    }
                });

        // No per-app fixed rotation while forcing. Android rotates a launching app with a
        // different orientation through a transform instead of the display, and links the
        // wallpaper to it. Going home and locking again left that transform on the wallpaper:
        // a portrait lock screen over a landscape background. With one forced rotation the
        // transform has nothing to do, so a fixed-orientation app rotates the display instead.
        XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.android.server.wm.DisplayContent", classLoader),
                "handleTopActivityLaunchingInDifferentOrientation",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!SystemSettings.forceRotation) return;
                        Object displayRotation;
                        try {
                            displayRotation = XposedHelpers.getObjectField(param.thisObject, "mDisplayRotation");
                        } catch (Throwable t) {
                            return;
                        }
                        if (shouldForce(displayRotation)) param.setResult(false);
                    }
                });
    }

    // Only the main display, and only while the swivel is closed. Every display has its own
    // DisplayRotation, and in swivel mode LG manages the main display orientation itself;
    // forcing those rotated the second screen and the swivel-mode lock screen.
    private static boolean shouldForce(Object displayRotation) {
        if (!SystemSettings.forceRotation || SystemSettings.secondScreenOn) return false;
        try {
            return XposedHelpers.getBooleanField(displayRotation, "isDefaultDisplay");
        } catch (Throwable t) {
            return false;
        }
    }

    // Apps on the second screen ask for their own orientation (LG's touchpad asks for
    // landscape, photo viewers follow the sensor). With forcing on, the second screen follows
    // the auto-rotate toggle like the main one, so one setting decides for every screen.
    private static boolean isSecondScreen(Object displayRotation) {
        try {
            Object displayContent = XposedHelpers.getObjectField(displayRotation, "mDisplayContent");
            return (int) XposedHelpers.callMethod(displayContent, "getDisplayId")
                    == MainModule.SECOND_SCREEN_DISPLAY_ID;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isFixedOrientation(int orientation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_BEHIND:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                return false;
            default:
                return true;
        }
    }

    private static int forcedRotation() {
        // Served from the SettingsProvider client-side cache, so a synchronous read is cheap.
        return Settings.System.getInt(SystemSettings.resolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                ? Surface.ROTATION_0 : Surface.ROTATION_90;
    }

    private static synchronized void ensureSetup(Object displayRotation) {
        if (setupDone) return;
        setupDone = true;
        try {
            Context context = (Context) XposedHelpers.getObjectField(displayRotation, "mContext");
            windowManagerService = XposedHelpers.getObjectField(displayRotation, "mService");
            SystemSettings.init(context);

            // Rotation forcing toggled or swivel opened/closed -> re-evaluate rotation.
            SystemSettings.addListener(RotationHook::triggerRotationUpdate);

            // Auto-rotate toggled -> re-evaluate rotation right away (only when enabled).
            SystemSettings.resolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false,
                    new ContentObserver(SystemSettings.handler()) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (SystemSettings.forceRotation) triggerRotationUpdate();
                        }
                    });
        } catch (Throwable ignored) {
            // Setup failed: rotation behavior stays untouched.
        }
    }

    private static void triggerRotationUpdate() {
        try {
            XposedHelpers.callMethod(windowManagerService, "updateRotation", true, false);
        } catch (Throwable ignored) {
        }
    }
}
