package kr.opencourse.godaijishou;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.database.Cursor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Swivel warnings off (scope: com.lge.popupcamera). LG's pop-up camera service covers the
 * screens with a notice whenever a camera opens in swivel mode ("camera may look cropped in
 * swivel mode...", "swivel down to use the camera"). Both are Presentations of its own
 * (SwivelCoverPresentation on the main screen, ...Sub on the second one): when the setting is
 * on, they still open but stay invisible and let every touch through. The camera itself keeps working underneath.
 */
final class SwivelWarningHook {

    static final String TARGET_PACKAGE = "com.lge.popupcamera";
    private static final String COVER_PREFIX = "com.lge.popupcamera.component.SwivelCover";

    private SwivelWarningHook() {
    }

    // Ignored rather than blocked: LG builds and shows its cover as usual (its own state
    // and the later dismiss stay intact), and right after show() the window is made
    // invisible, untouchable and unfocusable, before it is ever drawn. Skipping show()
    // itself left ...Sub.show() filling a layout that never got inflated (the pop-up
    // camera service crashed).
    static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Dialog dialog = (Dialog) param.thisObject;
                    if (!dialog.getClass().getName().startsWith(COVER_PREFIX)) return;
                    if (!enabled(dialog.getContext())) return;
                    Window window = dialog.getWindow();
                    if (window == null) return;
                    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.alpha = 0f;
                    window.setAttributes(lp);
                    window.getDecorView().setVisibility(View.INVISIBLE);
                } catch (Throwable t) {
                    ModuleLog.w(t); // never disturb the camera service
                }
            }
        });
    }

    // Read at show time: rare (camera start in swivel mode), and always current.
    private static boolean enabled(Context context) {
        try (Cursor c = context.getContentResolver().query(SettingsProvider.CONTENT_URI, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return false;
            int idx = c.getColumnIndex(AppPreferences.KEY_HIDE_SWIVEL_WARNINGS);
            return idx >= 0 && "1".equals(c.getString(idx));
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }
}
