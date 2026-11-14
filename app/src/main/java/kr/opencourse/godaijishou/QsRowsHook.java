package kr.opencourse.godaijishou;

import android.content.res.Configuration;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Quick settings in one row on a short screen (scope: com.android.systemui).
 *
 * LG's quick settings take their row count from a resource made for the tall main screen
 * (TileLayout.updateMaxRows). On the second screen's panel as the main display (main screen
 * mode, about 520 dp tall) those rows, the brightness slider and the media row did not fit:
 * the expanded panel stopped part way with its rows drawn over each other. Under 600 dp tall
 * the grid keeps one row of full-height tiles (two ran into the brightness slider, which LG
 * places on its own); the other tiles go on the next pages, as LG pages them anyway.
 */
final class QsRowsHook {

    private static final int SHORT_SCREEN_DP = 600;
    private static final int SHORT_ROWS = 1;

    private QsRowsHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> tileLayout = XposedHelpers.findClass("com.android.systemui.qs.TileLayout", cl);
        XposedBridge.hookAllMethods(tileLayout, "updateMaxRows", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    Configuration c = v.getResources().getConfiguration();
                    if (c.screenHeightDp <= 0 || c.screenHeightDp >= SHORT_SCREEN_DP) return;
                    int rows = XposedHelpers.getIntField(v, "mRows");
                    if (rows <= SHORT_ROWS) return;
                    XposedHelpers.setIntField(v, "mRows", SHORT_ROWS);
                    param.setResult(true); // the row count changed: LG lays the pages out again
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
        // LG grows the tiles while the panel opens (changeTileHeight); on a short screen it
        // never opens all the way, so the cells stayed short and the second row sat on the
        // first one's labels. Never shorter than a tile is.
        XposedBridge.hookAllMethods(tileLayout, "changeTileHeight", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    if (!isShort(v) || param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    int resId = XposedHelpers.getIntField(v, "mCellHeightResId");
                    if (resId == 0) return;
                    int natural = v.getResources().getDimensionPixelSize(resId);
                    if ((Integer) param.args[0] < natural) param.args[0] = natural;
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
    }

    private static boolean isShort(View v) {
        int h = v.getResources().getConfiguration().screenHeightDp;
        return h > 0 && h < SHORT_SCREEN_DP;
    }
}
