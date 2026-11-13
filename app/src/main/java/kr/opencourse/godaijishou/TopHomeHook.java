package kr.opencourse.godaijishou;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.database.ContentObserver;
import android.net.Uri;
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
 * The top screen as a phone while it is shown on the second screen (scope: com.lge.launcher3).
 *
 * With the swivel open LG's home (and its gesture navigation, quickstep) switch to swivel
 * mode: the clock and app wheel instead of the home screen, no gestures. Both learn the swivel
 * state from LG's PostureManager only: getSwivelState() (2 = open) and state callbacks
 * (100/101 opening/open, 200/201 closing/closed).
 *
 * While the top screen is mirrored to the second screen (TopMirror sets a global flag), this
 * process alone is told the swivel is closed, so the top screen behaves like the folded
 * phone. Nothing else in the system is touched; clearing the flag replays "open".
 */
final class TopHomeHook {

    static final String TARGET_PACKAGE = "com.lge.launcher3";
    /** Settings.Global flag, 1 while the top screen is shown on the second screen. */
    static final String KEY_TOP_VIEW = "godaijishou_top_view";

    private static final String POSTURE = "com.lge.systemservice.core.PostureManager";
    private static final int STATE_OPEN = 2;
    private static final int STATE_CLOSED = 0;
    private static final int OPENING = 100, OPENED = 101, CLOSING = 200, CLOSED = 201;

    private static final Set<Object> callbacks = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean topView;
    private static boolean observing;
    private static java.lang.ref.WeakReference<Object> manager = new java.lang.ref.WeakReference<>(null);

    private TopHomeHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> posture = XposedHelpers.findClass(POSTURE, cl);
        XposedBridge.hookAllMethods(posture, "getSwivelState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (topView && Integer.valueOf(STATE_OPEN).equals(param.getResult())) {
                    param.setResult(STATE_CLOSED);
                }
            }
        });
        XposedBridge.hookAllMethods(posture, "registerPostureStateCallback", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) callbacks.add(param.args[0]);
                manager = new java.lang.ref.WeakReference<>(param.thisObject);
                observe();
            }
        });
        XposedBridge.hookAllMethods(posture, "unregisterPostureStateCallback", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0) callbacks.remove(param.args[0]);
            }
        });
    }

    // Watch the flag once there is an application to read it with.
    private static synchronized void observe() {
        if (observing) return;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        observing = true;
        Handler main = new Handler(Looper.getMainLooper());
        Uri uri = Settings.Global.getUriFor(KEY_TOP_VIEW);
        ContentObserver observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                apply(app);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(uri, false, observer);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        main.post(() -> apply(app));
    }

    private static void apply(Application app) {
        boolean want = Settings.Global.getInt(app.getContentResolver(), KEY_TOP_VIEW, 0) == 1;
        if (want == topView) return;
        topView = want;
        // Back from the top view: say "open" only if the swivel still is (closing it for real
        // ends the top view, and LG already told this process).
        if (!want && !reallyOpen()) return;
        // Replay the swivel moving, as LG's service would send it.
        int[] events = want ? new int[] {CLOSING, CLOSED} : new int[] {OPENING, OPENED};
        Object[] targets;
        synchronized (callbacks) {
            targets = callbacks.toArray();
        }
        for (Object cb : targets) {
            for (int event : events) {
                try {
                    XposedHelpers.callMethod(cb, "onSwivelStateChanged", event);
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        }
    }

    private static boolean reallyOpen() {
        Object m = manager.get();
        if (m == null) return true;
        try {
            return (int) XposedHelpers.callMethod(m, "getSwivelState") == STATE_OPEN;
        } catch (Throwable t) {
            return true;
        }
    }
}
