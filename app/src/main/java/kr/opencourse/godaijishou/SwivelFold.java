package kr.opencourse.godaijishou;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * An LG app as the folded phone's while the second screen's panel is the main display (main
 * screen mode, DisplaySwap). Installed in LG's camera (scope: com.lge.camera): with the
 * swivel open it lays itself out for the swivel (RatioCalcFullVisionSwivel) and crashed on
 * the swapped 1080x1240 main display. Not in SystemUI: a folded SystemUI over LG's swivel lock
 * left the phone locked.
 *
 * LG's apps learn the swivel state from PostureManager and DisplayManagerHelper only:
 * getSwivelState() (2 = open) and state callbacks (100/101 opening/open, 200/201
 * closing/closed). While swapped they are told "closed", and the swap starting or ending is
 * replayed to their callbacks as the swivel closing or opening. Unswapped (second screen
 * mode) nothing changes.
 */
final class SwivelFold {

    static final String CAMERA = "com.lge.camera";

    /** Settings.Global, 1 while swapped (written by DisplaySwap in system_server). */
    static final String KEY_SWAPPED = "godaijishou_swapped";

    private static final String POSTURE = "com.lge.systemservice.core.PostureManager";
    private static final String DISPLAY_HELPER = "com.lge.display.DisplayManagerHelper";
    private static final int STATE_OPEN = 2, STATE_CLOSED = 0;
    private static final int OPENING = 100, OPENED = 101, CLOSING = 200, CLOSED = 201;

    private static final Set<Object> callbacks = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Class<?>> hookedCallbacks = Collections.synchronizedSet(new java.util.HashSet<>());
    private static volatile boolean folded;
    private static boolean observing;
    private static java.lang.ref.WeakReference<Object> manager = new java.lang.ref.WeakReference<>(null);

    private SwivelFold() {
    }

    static void install(ClassLoader cl) {
        // The camera also builds swivel-only parts (its drum UI, sized from the second screen)
        // just for being a swivel model: while folded it is not one, so none are built.
        Class<?> model = XposedHelpers.findClassIfExists("com.lge.camera.constants.ModelProperties", cl);
        if (model != null) {
            XposedBridge.hookAllMethods(model, "isSwivelModel", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    observe();
                    if (folded) param.setResult(false);
                }
            });
        }
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
                observe();
                if (folded && Integer.valueOf(STATE_OPEN).equals(param.getResult())) param.setResult(STATE_CLOSED);
            }
        });
        XC_MethodHook register = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) {
                    callbacks.add(param.args[0]);
                    rewrite(param.args[0].getClass());
                }
                manager = new java.lang.ref.WeakReference<>(param.thisObject);
                observe();
            }
        };
        XC_MethodHook unregister = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0) callbacks.remove(param.args[0]);
            }
        };
        XposedBridge.hookAllMethods(source, "registerPostureStateCallback", register);
        XposedBridge.hookAllMethods(source, "registerSwivelStateCallback", register);
        XposedBridge.hookAllMethods(source, "unregisterPostureStateCallback", unregister);
        XposedBridge.hookAllMethods(source, "unregisterSwivelStateCallback", unregister);
        XposedBridge.hookAllConstructors(source, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (manager.get() == null) manager = new java.lang.ref.WeakReference<>(param.thisObject);
                observe();
            }
        });
    }

    // While folded, LG's own "opening/open" (sent at registration too) arrives as "closing/closed".
    private static void rewrite(Class<?> callback) {
        for (Class<?> c = callback; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!hookedCallbacks.add(c)) return;
            try {
                XposedBridge.hookAllMethods(c, "onSwivelStateChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (replaying || !folded) return;
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

    private static volatile boolean replaying;

    private static synchronized void observe() {
        if (observing) return;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        observing = true;
        Handler main = new Handler(Looper.getMainLooper());
        try {
            app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY_SWAPPED), false,
                    new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange) {
                            apply(app);
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        // Read at once (already swapped at boot): answers from here on are folded.
        folded = Settings.Global.getInt(app.getContentResolver(), KEY_SWAPPED, 0) == 1;
        if (folded) main.post(() -> replay(true));
    }

    private static void apply(Application app) {
        boolean want = Settings.Global.getInt(app.getContentResolver(), KEY_SWAPPED, 0) == 1;
        if (want == folded) return;
        folded = want;
        if (!want && !reallyOpen()) return; // the swivel really is closed: LG said so already
        replay(want);
    }

    // The swivel moving, as LG's service would send it.
    private static void replay(boolean fold) {
        if (!fold && !reallyOpen()) return;
        int[] events = fold ? new int[] {CLOSING, CLOSED} : new int[] {OPENING, OPENED};
        Object[] targets;
        synchronized (callbacks) {
            targets = callbacks.toArray();
        }
        replaying = true;
        try {
            for (Object cb : targets) {
                for (int event : events) {
                    try {
                        XposedHelpers.callMethod(cb, "onSwivelStateChanged", event);
                    } catch (Throwable t) {
                        ModuleLog.w(t);
                    }
                }
            }
        } finally {
            replaying = false;
        }
    }

    private static boolean reallyOpen() {
        Object m = manager.get();
        if (m == null) return true;
        boolean was = folded;
        try {
            folded = false; // ask LG itself
            return (int) XposedHelpers.callMethod(m, "getSwivelState") == STATE_OPEN;
        } catch (Throwable t) {
            return true;
        } finally {
            folded = was;
        }
    }
}
