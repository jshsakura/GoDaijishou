package kr.opencourse.godaijishou;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Xposed entry point: installs only the hook that matches the loaded package.
 * (Called only in processes checked in the LSPosed scope.)
 *
 *   com.lge.secondlauncher        -> LauncherHook  (second home -> chosen launcher)
 *   com.retroarch(.aarch64/.ra32) -> RetroArchHook (black screen recovery on return)
 *   com.android.systemui          -> NavBarHook (slim pill, keyboard bottom strip),
 *                                    LockWallpaperHook (swivel lock wallpaper rotation)
 *   any keyboard app              -> NavBarHook (keyboard bottom strip)
 *   android (system framework)    -> RotationHook  (rotation forced by the auto-rotate toggle)
 *                                    PowerMenuHook (power menu on the second screen)
 *                                    LaunchRedirectHook (top-display launches -> second screen)
 *                                    NotificationBanner (heads-up on the second screen)
 *                                    (SystemSettings also installs the volume overlay and status label)
 */
public class MainModule implements IXposedHookLoadPackage {

    static final int SECOND_SCREEN_DISPLAY_ID = 4;
    // system_server's class loader (services classes such as LocalServices); null elsewhere.
    static volatile ClassLoader systemLoader;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        ClassLoader cl = lpparam.classLoader;
        if (LauncherHook.TARGET_PACKAGE.equals(pkg)) {
            safely(LauncherHook::install);
        } else if (SwivelWarningHook.TARGET_PACKAGE.equals(pkg)) {
            safely(() -> SwivelWarningHook.install(cl));
        } else if (TopHomeHook.TARGET_PACKAGE.equals(pkg)) {
            safely(() -> TopHomeHook.install(cl));
        } else if (RetroArchHook.isTarget(pkg)) {
            safely(RetroArchHook::install);
        } else if (NavBarHook.SYSTEMUI.equals(pkg)) {
            safely(() -> NavBarHook.installSystemUi(cl));
            safely(() -> LockWallpaperHook.install(cl));
            safely(() -> BottomGestureHook.install(cl));
        } else if ("android".equals(pkg)) {
            systemLoader = cl;
            // Each system hook is independent: one missing method on a firmware must not
            // keep the others from installing.
            safely(() -> RotationHook.install(cl));
            safely(() -> PowerMenuHook.install(cl));
            safely(() -> LaunchRedirectHook.install(cl));
            safely(() -> NotificationBanner.install(cl));
            safely(() -> StatusLabel.installFullscreenHook(cl));
            safely(() -> StatusLabel.installSwipeGuard(cl));
            safely(() -> FingerprintHook.install(cl));
            safely(() -> TouchBypassHook.install(cl));
            safely(() -> DisplaySwap.install(cl));
            safely(() -> SafetyEscape.install(cl));
        } else if (!BuildConfig.APPLICATION_ID.equals(pkg)) {
            // Anything else in the scope is a keyboard app: only the bottom strip option.
            safely(NavBarHook::installIme);
        }
    }

    private static void safely(Runnable install) {
        try {
            install.run();
        } catch (Throwable ignored) {
            ModuleLog.install(ignored);
            // That feature stays off; the rest of the module keeps working.
        }
    }
}
