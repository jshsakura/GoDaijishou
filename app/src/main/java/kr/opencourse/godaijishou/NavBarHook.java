package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bottom of the screen, two independent options.
 *
 * Slim gesture pill (scope: com.android.systemui)
 *   The home pill is drawn thinner (2/3) and narrower (2/3), in the stock color and spot
 *   (LG's NavigationHandle size fields are scaled; its own drawing does the rest).
 *   Only the drawing changes; the gesture area and the gestures themselves are the system's.
 *
 * Keyboard bottom strip off (scope: com.android.systemui + the keyboard app)
 *   With gesture navigation the keyboard sits on a strip as tall as the old 3-button bar,
 *   holding the "hide keyboard" and switcher buttons. The strip's height is the framework
 *   dimen navigation_bar_frame_height; in the keyboard (which draws that strip) it is answered
 *   with 0. Not in SystemUI: LG places the gesture pill from that height, and a smaller value
 *   pushed the pill out of view. Read once per process: after a keyboard restart / reboot.
 */
final class NavBarHook {

    static final String SYSTEMUI = "com.android.systemui";
    private static final String HANDLE = "com.android.systemui.navigationbar.gestural.NavigationHandle";

    private static final float THICKNESS = 2f / 3f;
    private static final float WIDTH = 2f / 3f;

    private static final Set<View> handles = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler main = new Handler(Looper.getMainLooper());

    // Settings, read from our provider (null = not read yet).
    private static volatile Boolean slimPill;
    private static volatile Boolean imeStripOff;
    private static boolean observing = false;

    private NavBarHook() {
    }

    // --- SystemUI ---

    static void installSystemUi(ClassLoader cl) {
        Class<?> handle = XposedHelpers.findClass(HANDLE, cl);
        XposedBridge.hookAllConstructors(handle, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                handles.add(v);
                observe(v.getContext());
                reportLoaded(v.getContext());
            }
        });
        // LG draws the pill from its own size fields (mHandleWidth / mHandleMaxWidth /
        // mHandleHeight / mRadius): scale those just before each draw and let LG's onDraw
        // place and color it as usual. Originals are kept, so turning the option off restores.
        XposedHelpers.findAndHookMethod(handle, "onDraw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View v = (View) param.thisObject;
                boolean slim = slimPill(v.getContext());
                for (String f : SIZE_FIELDS) {
                    try {
                        Object orig = XposedHelpers.getAdditionalInstanceField(v, "gdj_" + f);
                        float now = XposedHelpers.getFloatField(v, f);
                        float scaled = orig instanceof Float ? (Float) orig * (f.equals("mHandleHeight")
                                || f.equals("mRadius") ? THICKNESS : WIDTH) : Float.NaN;
                        // Remember the stock value (again whenever LG reset it to a new one).
                        if (!(orig instanceof Float) || (now != (Float) orig && now != scaled)) {
                            orig = now;
                            XposedHelpers.setAdditionalInstanceField(v, "gdj_" + f, now);
                        }
                        float base = (Float) orig;
                        float factor = f.equals("mHandleHeight") || f.equals("mRadius") ? THICKNESS : WIDTH;
                        XposedHelpers.setFloatField(v, f, slim ? base * factor : base);
                    } catch (Throwable ignored) {
                        // Field not on this build: that dimension stays stock.
                    }
                }
            }
        });
    }

    private static final String[] SIZE_FIELDS = {"mHandleWidth", "mHandleMaxWidth", "mHandleHeight", "mRadius"};

    // Tells the app "the SystemUI hook is loaded this boot" (the app warns when it isn't).
    private static boolean reported = false;

    private static void reportLoaded(Context context) {
        if (reported) return;
        reported = true;
        new Thread(() -> {
            try (android.database.Cursor c = context.getContentResolver().query(
                    SettingsProvider.CONTENT_URI, null, "hook:systemui", null, null)) {
                // nothing to read
            } catch (Throwable ignored) {
            }
        }, "GoDaijishou-report").start();
    }

    // --- Keyboard strip (SystemUI and keyboard processes) ---

    // In the keyboard's own process the strip goes completely (the keys reach the bottom
    // edge); SystemUI keeps the gesture bar's height so the pill still has its place.
    private static volatile boolean keyboardProcess = false;

    static void installIme() {
        keyboardProcess = true;
        installImeStrip();
    }

    private static void installImeStrip() {
        Resources sys = Resources.getSystem();
        final int frame = sys.getIdentifier("navigation_bar_frame_height", "dimen", "android");
        final int frameLand = sys.getIdentifier("navigation_bar_frame_height_landscape", "dimen", "android");
        final int bar = sys.getIdentifier("navigation_bar_height", "dimen", "android");
        if (frame == 0 || bar == 0) return;
        XC_MethodHook swap = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int id = (Integer) param.args[0];
                if (id != frame && (frameLand == 0 || id != frameLand)) return;
                if (!imeStripOff()) return;
                // The gesture bar's own height: the strip collapses to the pill's area.
                if (keyboardProcess) {
                    // No strip at all under the keys.
                    param.setResult(param.method.getName().equals("getDimension") ? (Object) 0f : (Object) 0);
                } else {
                    param.args[0] = bar;
                }
            }
        };
        for (String name : new String[]{"getDimensionPixelSize", "getDimensionPixelOffset", "getDimension"}) {
            try {
                XposedHelpers.findAndHookMethod(Resources.class, name, int.class, swap);
            } catch (Throwable ignored) {
            }
        }
    }

    // --- Settings ---

    private static boolean slimPill(Context context) {
        Boolean v = slimPill;
        if (v == null) {
            read(context);
            v = slimPill;
        }
        return v != null && v;
    }

    private static boolean imeStripOff() {
        Boolean v = imeStripOff;
        if (v == null) {
            Context c = currentContext();
            if (c != null) read(c);
            v = imeStripOff;
        }
        return v != null && v;
    }

    private static Context currentContext() {
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication");
            return (Context) thread;
        } catch (Throwable t) {
            return null;
        }
    }

    // Written by our system_server side (SystemSettings) into Settings.Global: readable from
    // any process, from the first frame of boot (it survives reboots).
    private static void read(Context context) {
        try {
            String v = android.provider.Settings.Global.getString(context.getContentResolver(),
                    SystemSettings.NAVBAR_KEY);
            slimPill = v != null && v.length() > 0 && v.charAt(0) == '1';
            imeStripOff = v != null && v.length() > 1 && v.charAt(1) == '1';
        } catch (Throwable ignored) {
            // Settings not reachable yet: ask again next time.
        }
    }

    // The pill option follows the switch at once (redraw); the strip needs a restart.
    private static void observe(Context context) {
        if (observing) return;
        observing = true;
        try {
            context.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(SystemSettings.NAVBAR_KEY), false,
                    new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange) {
                            new Thread(() -> {
                                read(context);
                                main.post(() -> {
                                    for (View v : handles.toArray(new View[0])) {
                                        if (v == null) continue;
                                        v.invalidate();
                                    }
                                });
                            }, "GoDaijishou-navbar").start();
                        }
                    });
        } catch (Throwable ignored) {
            observing = false;
        }
    }
}
