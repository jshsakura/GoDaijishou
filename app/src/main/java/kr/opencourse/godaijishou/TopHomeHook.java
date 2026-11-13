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
 * Not in SystemUI: told "closed", it drops the second screen's gesture watchers that hand
 * edge swipes to the top screen (BottomGestureHook).
 *
 * With the swivel open LG's home, status bar, lock screen and quick settings switch to swivel
 * mode (the clock and app wheel, the swivel shade, the lock screen on the second screen). They
 * learn the swivel state from two LG classes only, PostureManager and DisplayManagerHelper:
 * getSwivelState() (2 = open) and state callbacks (100/101 opening/open, 200/201
 * closing/closed).
 *
 * While the top screen is mirrored to the second screen (TopMirror sets a global flag), these
 * processes are told the swivel is closed, so the top screen behaves like the folded phone.
 * The system server (displays, rotation, power) is not touched; clearing the flag replays
 * "open".
 */
final class TopHomeHook {

    static final String TARGET_PACKAGE = "com.lge.launcher3";
    /** Settings.Global flag, 1 while the top screen is shown on the second screen. */
    static final String KEY_TOP_VIEW = "godaijishou_top_view";
    /** Settings.Global, "scale,left,top,width,height": the top screen on the second one. */
    static final String KEY_TOP_MAP = "godaijishou_top_view_map";

    private static final String POSTURE = "com.lge.systemservice.core.PostureManager";
    private static final String DISPLAY_HELPER = "com.lge.display.DisplayManagerHelper";
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
                if (topView && Integer.valueOf(STATE_OPEN).equals(param.getResult())) {
                    param.setResult(STATE_CLOSED);
                }
            }
        });
        XC_MethodHook register = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) callbacks.add(param.args[0]);
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
        // getSwivelState() alone (no callback yet) must also start watching the flag.
        XposedBridge.hookAllConstructors(source, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (manager.get() == null) manager = new java.lang.ref.WeakReference<>(param.thisObject);
                observe();
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
