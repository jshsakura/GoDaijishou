package kr.opencourse.godaijishou;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The second screen's gestures become the top screen's while it shows the top screen
 * (scope: com.android.systemui).
 *
 * The second screen's pill (LG's NavigationHandle watches swipes itself) sees every touch on
 * that screen first, the mirror on top of it included. While the top view flag is set, a
 * touch that starts at an edge (left, right, bottom, top) is taken from the second screen
 * (pilfered: the mirrored apps get a cancel) and played, whole, into the top screen, mapped
 * to its coordinates. The top screen's own gesture handlers get it as a finger there: back
 * with its arrow, home, recents, all theirs. Other touches go through the mirror untouched.
 * The second screen's back gesture stays out and its pill is not drawn meanwhile.
 */
final class BottomGestureHook {

    private static final String GESTURAL = "com.android.systemui.navigationbar.gestural.";

    private static final Set<View> handles = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean topView;
    // The top screen's picture is up (on the lock screen it is not: gestures stay below).
    private static volatile boolean pictureUp;
    // Where the top screen sits on the second one: scale, left, top (TopMirror publishes it).
    private static volatile float mapScale = 1f, mapLeft, mapTop;
    private static volatile int topW, topH;
    // The gesture being played into the top screen. LG keeps several pill watchers on the
    // second screen, each seeing every touch (on their own threads): the first to see a
    // gesture's down plays it, the others only let it go by.
    private static final Object lock = new Object();
    private static Object owner;
    private static long ownerDownTime = -1;
    private static boolean forwarding;
    private static final float SIDE_DP = 20f, BOTTOM_DP = 24f;
    private static boolean observing;

    private BottomGestureHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> handle = XposedHelpers.findClass(GESTURAL + "NavigationHandle", cl);
        Class<?> receiver = XposedHelpers.findClass(GESTURAL + "NavigationHandle$SysUiInputEventReceiver", cl);
        Class<?> back = XposedHelpers.findClass(GESTURAL + "EdgeBackGestureHandler", cl);

        XposedBridge.hookAllMethods(handle, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                handles.add((View) param.thisObject);
                observe();
            }
        });
        XposedBridge.hookAllMethods(handle, "onDraw", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (topView && pictureUp && onSecondScreen(((View) param.thisObject).getContext())) {
                    param.setResult(null);
                }
            }
        });
        // The pill's own swipe watcher: edge gestures go to the top screen, the rest passes.
        XposedBridge.hookAllMethods(receiver, "onInputEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(topView && pictureUp) && !forwarding) return;
                try {
                    View h = (View) XposedHelpers.getObjectField(param.thisObject, "this$0");
                    if (!onSecondScreen(h.getContext())) return;
                    InputEvent event = (InputEvent) param.args[0];
                    if (event instanceof MotionEvent) {
                        synchronized (lock) {
                            forward(param.thisObject, h, (MotionEvent) event);
                        }
                    }
                    XposedHelpers.callMethod(param.thisObject, "finishInputEvent", event, false);
                    param.setResult(null);
                } catch (Throwable t) {
                    synchronized (lock) {
                        if (owner == param.thisObject) forwarding = false;
                    }
                    ModuleLog.w(t); // let the system handle it as usual
                }
            }
        });
        // Back swipe from the edges: "not handled", so it is neither taken nor started.
        XposedBridge.hookAllMethods(back, "onInputEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(topView && pictureUp)) return; // locked: the second screen's own
                try {
                    if (XposedHelpers.getIntField(param.thisObject, "mDisplayId")
                            == MainModule.SECOND_SCREEN_DISPLAY_ID) {
                        param.setResult(false);
                    }
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
    }

    private static void forward(Object receiver, View handle, MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (ev.getDownTime() == ownerDownTime) return; // another watcher has this one
            ownerDownTime = ev.getDownTime();
            owner = receiver;
            forwarding = topView && pictureUp && atEdge(handle, ev.getX(), ev.getY());
            if (forwarding) {
                Object monitor = XposedHelpers.getObjectField(handle, "mInputMonitor");
                if (monitor != null) XposedHelpers.callMethod(monitor, "pilferPointers");
            }
        }
        if (!forwarding || receiver != owner) return;
        MotionEvent copy = MotionEvent.obtain(ev);
        try {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(-mapLeft, -mapTop);
            m.postScale(1f / mapScale, 1f / mapScale);
            copy.transform(m);
            // A swipe from the black bars beside a letterboxed top screen starts at its edge.
            if (topW > 0 && topH > 0 && copy.getPointerCount() == 1) {
                float dx = Math.max(0, Math.min(topW - 1, copy.getX())) - copy.getX();
                float dy = Math.max(0, Math.min(topH - 1, copy.getY())) - copy.getY();
                if (dx != 0 || dy != 0) copy.offsetLocation(dx, dy);
            }
            copy.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            XposedHelpers.callMethod(copy, "setDisplayId", android.view.Display.DEFAULT_DISPLAY);
            Object im = XposedHelpers.callStaticMethod(
                    Class.forName("android.hardware.input.InputManager"), "getInstance");
            XposedHelpers.callMethod(im, "injectInputEvent", copy, 0 /* async */);
        } catch (Throwable t) {
            ModuleLog.w(t);
        } finally {
            copy.recycle();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) forwarding = false;
    }

    private static boolean atEdge(View handle, float x, float y) {
        android.util.DisplayMetrics dm = handle.getResources().getDisplayMetrics();
        android.graphics.Point size = new android.graphics.Point();
        handle.getDisplay().getRealSize(size);
        float side = SIDE_DP * dm.density, bottom = BOTTOM_DP * dm.density;
        // Top too: pulling down there is the top screen's status bar (not LG's second-screen
        // control panel).
        return x < side || x > size.x - side || y > size.y - bottom || y < bottom;
    }

    private static boolean onSecondScreen(Context context) {
        if (context == null) return false;
        try {
            return (int) XposedHelpers.callMethod(context, "getDisplayId") == MainModule.SECOND_SCREEN_DISPLAY_ID;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void observe() {
        if (observing) return;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        observing = true;
        Handler main = new Handler(Looper.getMainLooper());
        ContentObserver observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read(app);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(TopHomeHook.KEY_TOP_VIEW), false, observer);
            app.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(TopHomeHook.KEY_TOP_MAP), false, observer);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        read(app);
    }

    private static void read(Application app) {
        topView = Settings.Global.getInt(app.getContentResolver(), TopHomeHook.KEY_TOP_VIEW, 0) == 1;
        // "scale,left,top,width,height" of the top screen on the second one.
        try {
            String map = Settings.Global.getString(app.getContentResolver(), TopHomeHook.KEY_TOP_MAP);
            pictureUp = map != null && !map.isEmpty();
            if (pictureUp) {
                String[] p = map.split(",");
                mapScale = Float.parseFloat(p[0]);
                mapLeft = Float.parseFloat(p[1]);
                mapTop = Float.parseFloat(p[2]);
                topW = Integer.parseInt(p[3]);
                topH = Integer.parseInt(p[4]);
            }
        } catch (Throwable t) {
            pictureUp = false;
            mapScale = 1f;
            mapLeft = mapTop = 0;
        }
        for (View h : handles.toArray(new View[0])) {
            if (h != null) h.postInvalidate(); // the pill shows or hides
        }
    }
}
