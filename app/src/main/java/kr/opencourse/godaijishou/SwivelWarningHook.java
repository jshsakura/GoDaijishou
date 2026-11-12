package kr.opencourse.godaijishou;

import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Swivel warnings off (scope: com.lge.popupcamera). LG's pop-up camera service covers the
 * screens with a notice whenever a camera opens in swivel mode ("camera may look cropped in
 * swivel mode...", "swivel down to use the camera"). Both are Presentations of its own
 * (SwivelCoverPresentation on the main screen, ...Sub on the second one): when the setting is
 * on, their show() is skipped. The camera itself keeps working underneath.
 */
final class SwivelWarningHook {

    static final String TARGET_PACKAGE = "com.lge.popupcamera";
    private static final String COVER_PREFIX = "com.lge.popupcamera.component.SwivelCover";

    private SwivelWarningHook() {
    }

    static void install() {
        XposedHelpers.findAndHookMethod(Dialog.class, "show", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!param.thisObject.getClass().getName().startsWith(COVER_PREFIX)) return;
                if (enabled(((Dialog) param.thisObject).getContext())) param.setResult(null);
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
