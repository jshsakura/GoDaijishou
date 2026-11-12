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
 *   keyboard's own NavigationBarController: it is told there is no bottom inset and its frame
 *   is hidden. SystemUI is left alone (LG places the gesture pill from the bar's height).
 *   Takes effect when the keyboard restarts (or after a reboot).
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
        installFrameSwap();
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
                // LG centers the pill mFullgestureNavHeight/2 above the bottom. With the shorter
                // bar (keyboard strip option) that is above the window: the pill vanished.
                // Center it in the bar it actually has.
                try {
                    Object full = XposedHelpers.getAdditionalInstanceField(v, "gdj_full");
                    int now = XposedHelpers.getIntField(v, "mFullgestureNavHeight");
                    if (!(full instanceof Integer) || (now != (Integer) full && now != v.getHeight())) {
                        full = now;
                        XposedHelpers.setAdditionalInstanceField(v, "gdj_full", now);
                    }
                    int stock = (Integer) full;
                    boolean shortBar = imeStripOff() && v.getHeight() > 0 && v.getHeight() < stock;
                    XposedHelpers.setIntField(v, "mFullgestureNavHeight", shortBar ? v.getHeight() : stock);
                } catch (Throwable ignored) {
                }
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

    // Keyboard process: the keyboard draws that strip itself (NavigationBarController$Impl,
    // a NavigationBarFrame sized to the system's bottom inset). Answer "no bottom inset" and
    // hide the frame: no strip, no buttons, keys down to the gesture area. Back still closes
    // the keyboard.
    static void installIme() {
        Class<?> impl;
        try {
            impl = Class.forName("android.inputmethodservice.NavigationBarController$Impl");
        } catch (Throwable t) {
            return;
        }
        XposedBridge.hookAllMethods(impl, "getSystemInsets", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (imeStripOff()) param.setResult(android.graphics.Insets.NONE);
            }
        });
        XC_MethodHook hideFrame = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!imeStripOff()) return;
                try {
                    Object frame = XposedHelpers.getObjectField(param.thisObject, "mNavigationBarFrame");
                    if (frame instanceof View) ((View) frame).setVisibility(View.GONE);
                } catch (Throwable ignored) {
                }
            }
        };
        XposedBridge.hookAllMethods(impl, "installNavigationBarFrameIfNecessary", hideFrame);
        XposedBridge.hookAllMethods(impl, "onViewInitialized", hideFrame);
        XposedBridge.hookAllMethods(impl, "onWindowShown", hideFrame);
    }

    // SystemUI: the bar's frame (what the keyboard sits on) = the gesture bar's own height.
    private static void installFrameSwap() {
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
                if (imeStripOff()) param.args[0] = bar;
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
