package kr.opencourse.godaijishou;

import android.content.Context;

import java.util.Collections;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * LG's home is always the folded phone's home, never the swivel home (scope: com.lge.launcher3).
 * Not in SystemUI: told "closed", it drops the second screen's gesture watchers.
 *
 * With the swivel open LG's home switches to the swivel home (the clock and app wheel made for
 * the top screen turned sideways). It learns the swivel state from LG's PostureManager and
 * DisplayManagerHelper only: getSwivelState() (2 = open) and state callbacks (100/101
 * opening/open, 200/201 closing/closed), the first of which LG's service sends right when the
 * callback registers. Here getSwivelState() always answers "closed" and every "opening/open"
 * reaching a callback arrives as "closing/closed". The second screen runs its own launcher
 * (com.lge.secondlauncher) and is not affected; nor is the system server (displays, rotation,
 * power).
 */
final class TopHomeHook {

    static final String TARGET_PACKAGE = "com.lge.launcher3";
    static final String HOOK_NAME = "lghome";
    /** Settings.Global flag, 1 while the top screen is shown on the second screen. */
    static final String KEY_TOP_VIEW = "godaijishou_top_view";
    /** Settings.Global, "scale,left,top,width,height": the top screen on the second one. */
    static final String KEY_TOP_MAP = "godaijishou_top_view_map";

    private static final String POSTURE = "com.lge.systemservice.core.PostureManager";
    private static final String DISPLAY_HELPER = "com.lge.display.DisplayManagerHelper";
    private static final int STATE_OPEN = 2;
    private static final int STATE_CLOSED = 0;
    private static final int OPENING = 100, OPENED = 101, CLOSING = 200, CLOSED = 201;

    // Callback classes whose onSwivelStateChanged is already rewritten.
    private static final Set<Class<?>> hookedCallbacks = Collections.synchronizedSet(new java.util.HashSet<>());

    private TopHomeHook() {
    }

    static void install(ClassLoader cl) {
        // Tells the app (and through it system_server) that LG's home is hooked this boot:
        // main screen mode needs it and falls back to second screen mode without it.
        XposedBridge.hookAllMethods(android.app.Application.class, "onCreate", new XC_MethodHook() {
            private boolean reported;

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (reported) return;
                reported = true;
                Context app = (Context) param.thisObject;
                new Thread(() -> {
                    try (android.database.Cursor c = app.getContentResolver().query(
                            SettingsProvider.CONTENT_URI, null, "hook:" + HOOK_NAME, null, null)) {
                        // nothing to read
                    } catch (Throwable t) {
                        ModuleLog.w(t);
                    }
                }, "GoDaijishou-report").start();
            }
        });
        installGridOnce(cl);
        for (String name : new String[] {POSTURE, DISPLAY_HELPER}) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) continue;
            try {
                hookSource(c);
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    }

    private static void hookSource(Class<?> source) {
        XposedBridge.hookAllMethods(source, "getSwivelState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Integer.valueOf(STATE_OPEN).equals(param.getResult())) param.setResult(STATE_CLOSED);
            }
        });
        // Every callback handed in gets its "open" events turned into "closed" ones.
        XC_MethodHook register = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) folded(param.args[0].getClass());
            }
        };
        XposedBridge.hookAllMethods(source, "registerPostureStateCallback", register);
        XposedBridge.hookAllMethods(source, "registerSwivelStateCallback", register);
    }

    // --- Home grid 4x4, once ---

    /** Settings.Global, 1: switch LG's home grid to 4x4 once (set when main screen mode is kept). */
    static final String KEY_GRID_ONCE = "godaijishou_grid_once";
    private static final String GRID_MANAGER = "com.lge.launcher3.dynamicgrid.DynamicGridManager";
    private static final String DONE_PREFS = "wingcustom_hook", DONE_KEY = "grid_4x4_done";
    private static volatile boolean gridDone;

    /**
     * LG's default 5x5 grid is cramped on the second screen's panel as the main display. The
     * first time main screen mode is kept, the next time the home shows it switches to 4x4
     * through LG's own grid change (what its settings' grid picker runs, items moved along).
     * Only ever once: a grid picked afterwards is the user's.
     */
    private static void installGridOnce(ClassLoader cl) {
        Class<?> launcher = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        Class<?> manager = XposedHelpers.findClassIfExists(GRID_MANAGER, cl);
        if (launcher == null || manager == null) return;
        XposedBridge.hookAllMethods(launcher, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (gridDone) return;
                try {
                    android.app.Activity a = (android.app.Activity) param.thisObject;
                    if (android.provider.Settings.Global.getInt(a.getContentResolver(), KEY_GRID_ONCE, 0) != 1) return;
                    android.content.SharedPreferences p = a.getSharedPreferences(DONE_PREFS, Context.MODE_PRIVATE);
                    gridDone = true;
                    if (p.getBoolean(DONE_KEY, false)) return;
                    p.edit().putBoolean(DONE_KEY, true).apply(); // first: never twice, even on a failure
                    a.getWindow().getDecorView().post(() -> toFourByFour(manager, a));
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
    }

    private static void toFourByFour(Class<?> managerClass, android.app.Activity a) {
        try {
            Object m = XposedHelpers.newInstance(managerClass, a);
            XposedHelpers.callMethod(m, "init");
            java.util.List<?> grids = (java.util.List<?>) XposedHelpers.getObjectField(m, "mGrids");
            int current = XposedHelpers.getIntField(m, "mSelectedGridIndex");
            if (grids == null) return;
            for (int i = 0; i < grids.size(); i++) {
                Object g = grids.get(i);
                if (!(g instanceof int[])) continue;
                int[] cr = (int[]) g; // {columns, rows}
                if (cr.length >= 2 && cr[0] == 4 && cr[1] == 4) {
                    if (i != current) XposedHelpers.callMethod(m, "runDynamicGrid", i);
                    return;
                }
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static void folded(Class<?> callback) {
        for (Class<?> c = callback; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!hookedCallbacks.add(c)) return; // this class (and its parents) done already
            try {
                XposedBridge.hookAllMethods(c, "onSwivelStateChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                        int s = (Integer) param.args[0];
                        if (s == OPENING) param.args[0] = CLOSING;
                        else if (s == OPENED) param.args[0] = CLOSED;
                    }
                });
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    }
}
