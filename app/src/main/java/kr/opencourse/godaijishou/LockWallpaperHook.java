package kr.opencourse.godaijishou;

import android.graphics.Bitmap;
import android.view.Surface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Lock screen wallpaper on the second screen (runs in SystemUI).
 *
 * In swivel mode LG turns the lock wallpaper by the main display's rotation
 * (LockscreenWallpaper.rotateBitmapForSwivel): landscape 270 -> 0 degrees, 90 -> 180 degrees.
 * With the main panel off, the main display stays portrait (0), which no stock swivel ever
 * reports, and LG turns the picture 270 degrees: the background showed sideways after the
 * first trip home (the first lock screen after boot skips this step and looked right).
 * A portrait value is answered as the swivel landscape one; real landscape values pass as is.
 */
final class LockWallpaperHook {

    private LockWallpaperHook() {
    }

    static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.LockscreenWallpaper", cl,
                "rotateBitmapForSwivel", Bitmap.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int rotation = (Integer) param.args[1];
                        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
                            param.args[1] = Surface.ROTATION_270;
                        }
                    }
                });
    }
}
