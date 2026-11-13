package kr.opencourse.godaijishou;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.widget.FrameLayout;

import de.robv.android.xposed.XposedHelpers;

/**
 * "Original" mode: with the swivel open, the second screen is the top screen, the phone
 * itself (runs in system_server, settings thread only).
 *
 * The top panel is off (TopPanel). The top screen is laid out at the second screen's size
 * (forced display size: 1:1, edge to edge, no scaling) and WindowManager mirrors it
 * (mirrorDisplay, what magnification and casting build on) into a layer hung under a black
 * window covering the second screen. Apps keep running natively on the top screen; only
 * their picture is composed on the second one. The mirror carries the top screen's input
 * windows along, so touches reach its apps directly. LG's home is told the swivel is closed
 * (TopHomeHook): the normal home instead of the swivel home. The second screen's own
 * gestures hand edge swipes to the top screen's (BottomGestureHook). Notifications and
 * dialogs stay on the top screen (SystemSettings.sendToBottom).
 *
 * Follows the setting and the swivel: on while "bottom screen only" is on in original mode
 * and the swivel is open. The screen sleeping (the second screen goes off too) keeps it;
 * only the swivel really closing (LG's posture service) ends it, undoing everything. The
 * top screen rotating (a landscape app) is fitted again, letterboxed.
 *
 * Android 11 and later (mirrorDisplay); hidden API through reflection, so the same code runs
 * on 11, 12 and 13. Windows that forbid capture (FLAG_SECURE) stay black, as in a screenshot.
 */
final class TopMirror {

    private static final int TOP = Display.DEFAULT_DISPLAY;

    private static Context system;
    private static Handler handler;
    private static WindowManager windowManager;
    private static MirrorView view;
    private static SurfaceControl mirror;
    private static DisplayManager.DisplayListener listener;
    private static boolean sizeForced;
    private static String lastMap;
    // The mirror could not be set up: never leave a black screen; try again only after the
    // mode is turned off and on (or the next boot).
    private static boolean failed;

    // Read by hooks on other threads (activity starts, notifications).
    private static volatile boolean showing;

    private TopMirror() {
    }

    static boolean isShowing() {
        return showing;
    }

    /** Re-evaluates on every settings or swivel change. */
    static void apply() {
        boolean wanted = SystemSettings.topScreenOff
                && SystemSettings.screenMode == AppPreferences.SCREEN_MODE_ORIGINAL;
        if (!wanted) {
            failed = false;
            if (view != null) hide();
            return;
        }
        if (failed) return;
        // Second screen off: asleep, or the swivel closing (the listener below tells them apart).
        if (SystemSettings.secondScreenOn && view == null) show();
    }

    private static void show() {
        try {
            system = SystemSettings.context();
            handler = SystemSettings.handler();
            if (system == null || handler == null) return;
            DisplayManager dm = system.getSystemService(DisplayManager.class);
            Display second = dm.getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
            if (second == null || dm.getDisplay(TOP) == null) return;
            windowManager = system.createDisplayContext(second).getSystemService(WindowManager.class);
            Context ui = system.createDisplayContext(second);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            // The whole screen, the gesture bar strip too (that pill stands aside meanwhile).
            lp.setFitInsetsTypes(0);
            lp.setTitle("GoDaijishou:TopMirror");

            MirrorView v = new MirrorView(ui);
            view = v;
            windowManager.addView(v, lp);
            showing = true;
            setTopView(true);

            listener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) {
                    if (id == MainModule.SECOND_SCREEN_DISPLAY_ID) hide();
                }
                @Override public void onDisplayChanged(int id) {
                    if (view == null) return;
                    if (id == TOP) {
                        view.requestLayout(); // rotated: fit again
                    } else if (id == MainModule.SECOND_SCREEN_DISPLAY_ID) {
                        Display d = dm.getDisplay(id);
                        // LG's posture may still be settling: look a moment later.
                        if (d == null || d.getState() == Display.STATE_OFF) {
                            handler.postDelayed(TopMirror::endIfSwivelClosed, 600);
                        }
                    }
                }
            };
            dm.registerDisplayListener(listener, handler);
        } catch (Throwable t) {
            ModuleLog.w(t);
            hide();
        }
    }

    static void hide() {
        MirrorView v = view;
        view = null;
        showing = false;
        try {
            if (listener != null && system != null) {
                system.getSystemService(DisplayManager.class).unregisterDisplayListener(listener);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        listener = null;
        releaseMirror();
        if (sizeForced) {
            sizeForced = false;
            clearTopSize();
        }
        if (system != null) setTopView(false);
        lastMap = null;
        if (v != null) {
            try {
                windowManager.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void endIfSwivelClosed() {
        if (view == null) return;
        Display d = system.getSystemService(DisplayManager.class)
                .getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
        if (d != null && d.getState() != Display.STATE_OFF) return; // back on already
        Boolean open = swivelOpen();
        if (open == null || !open) hide(); // unknown: the safe side, the top screen as it was
    }

    /** LG's posture service: is the swivel open? null when it can't be asked. */
    private static Boolean swivelOpen() {
        try {
            // LG's framework classes live with system_server's, not on the boot path.
            Class<?> lgContext = XposedHelpers.findClassIfExists(
                    "com.lge.systemservice.core.LGContext", system.getClassLoader());
            if (lgContext == null) lgContext = Class.forName("com.lge.systemservice.core.LGContext");
            Object lg = XposedHelpers.newInstance(lgContext, system);
            Object posture = XposedHelpers.callMethod(lg, "getLGSystemService", "postureservice");
            if (posture == null) return null;
            return (int) XposedHelpers.callMethod(posture, "getSwivelState") == 2;
        } catch (Throwable t) {
            ModuleLog.w(t);
            return null;
        }
    }

    // --- Top screen state shared with the other processes ---

    /**
     * Tells LG's home and SystemUI (TopHomeHook, BottomGestureHook) that the top screen is in
     * use here. Also the marker for undoing the forced size after a crash (resetAfterBoot).
     */
    private static void setTopView(boolean on) {
        try {
            Settings.Global.putInt(system.getContentResolver(), TopHomeHook.KEY_TOP_VIEW, on ? 1 : 0);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // Where the top screen sits here, for the edge gestures played into it.
    private static void publishMap(float scale, float left, float top, int w, int h) {
        String map = scale + "," + left + "," + top + "," + w + "," + h;
        if (map.equals(lastMap)) return;
        lastMap = map;
        try {
            Settings.Global.putString(system.getContentResolver(), TopHomeHook.KEY_TOP_MAP, map);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /** The top screen laid out at the second screen's size: shown 1:1, edge to edge. */
    private static void fitTop(int w, int h) {
        if (w <= 0 || h <= 0) return;
        try {
            // In the top screen's natural (portrait) orientation; it rotates on its own.
            XposedHelpers.callMethod(wms(), "setForcedDisplaySize", TOP, Math.min(w, h), Math.max(w, h));
            sizeForced = true;
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static void clearTopSize() {
        try {
            XposedHelpers.callMethod(wms(), "clearForcedDisplaySize", TOP);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static Object wms() throws ClassNotFoundException {
        return XposedHelpers.callStaticMethod(
                Class.forName("android.view.WindowManagerGlobal"), "getWindowManagerService");
    }

    /**
     * At boot: a top view left over from before (crash, power loss) must not keep the top
     * screen at the second screen's size. The flag says whether it was ours to undo.
     */
    static void resetAfterBoot(Context context) {
        if (view != null) return; // already showing again this boot: its size is current
        try {
            android.content.ContentResolver r = context.getContentResolver();
            if (Settings.Global.getInt(r, TopHomeHook.KEY_TOP_VIEW, 0) == 1) {
                clearTopSize();
                Settings.Global.putInt(r, TopHomeHook.KEY_TOP_VIEW, 0);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static void releaseMirror() {
        SurfaceControl m = mirror;
        mirror = null;
        if (m == null) return;
        try {
            new SurfaceControl.Transaction().reparent(m, null).apply();
            m.release();
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /** Black window holding the mirrored layer. */
    private static final class MirrorView extends FrameLayout {
        private final Matrix toTop = new Matrix();
        private float scale = 1f, left, top, shownW, shownH;
        private boolean tracking; // the current gesture started on the picture
        private int attempts;

        MirrorView(Context ui) {
            super(ui);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // The window's surface exists once it has drawn: hang the mirror under it then.
            post(this::attachMirror);
        }

        private void attachMirror() {
            if (view != this || mirror != null) return;
            try {
                // This window's own layer (ViewRootImpl's; public only from Android 12 on).
                Object root = XposedHelpers.callMethod(this, "getViewRootImpl");
                SurfaceControl parent = root == null ? null
                        : (SurfaceControl) XposedHelpers.callMethod(root, "getSurfaceControl");
                if (parent == null || !parent.isValid()) {
                    if (++attempts > 30) giveUp(); // no surface within 3 s
                    else postDelayed(this::attachMirror, 100);
                    return;
                }
                SurfaceControl out = SurfaceControl.class.getDeclaredConstructor().newInstance();
                if (!(boolean) XposedHelpers.callMethod(wms(), "mirrorDisplay", TOP, out)) {
                    giveUp();
                    return;
                }
                mirror = out;
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.reparent(out, parent);
                place(t);
                t.setLayer(out, 1).setVisibility(out, true).apply();
            } catch (Throwable e) {
                ModuleLog.w(e);
                giveUp();
            }
        }

        private void giveUp() {
            failed = true;
            hide(); // the second screen as usual rather than black
        }

        // Fit the top screen (its current, rotated size) inside this window, centred.
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            if (!sizeForced && view == this) fitTop(w, h); // relayouts once the size applies
            Point size = new Point();
            Display d = getContext().getSystemService(DisplayManager.class).getDisplay(TOP);
            if (d != null) d.getRealSize(size);
            if (size.x <= 0 || size.y <= 0 || w <= 0 || h <= 0) return;
            scale = Math.min((float) w / size.x, (float) h / size.y);
            shownW = size.x * scale;
            shownH = size.y * scale;
            left = (w - shownW) / 2f;
            top = (h - shownH) / 2f;
            toTop.reset();
            toTop.setTranslate(-left, -top);
            toTop.postScale(1f / scale, 1f / scale);
            publishMap(scale, left, top, size.x, size.y);
            if (mirror != null) {
                SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                place(tx);
                tx.apply();
            }
        }

        private void place(SurfaceControl.Transaction t) {
            t.setPosition(mirror, left, top);
            XposedHelpers.callMethod(t, "setMatrix", mirror, scale, 0f, 0f, scale);
        }

        // Touches the mirrored windows don't take (firmware without input mirroring): played
        // into the top screen.
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                tracking = ev.getX() >= left && ev.getX() < left + shownW
                        && ev.getY() >= top && ev.getY() < top + shownH;
            }
            if (!tracking) return true;
            MotionEvent copy = MotionEvent.obtain(ev);
            try {
                copy.transform(toTop);
                copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                XposedHelpers.callMethod(copy, "setDisplayId", TOP);
                Object im = XposedHelpers.callStaticMethod(
                        Class.forName("android.hardware.input.InputManager"), "getInstance");
                XposedHelpers.callMethod(im, "injectInputEvent", copy, 0 /* async */);
            } catch (Throwable e) {
                ModuleLog.w(e);
            } finally {
                copy.recycle();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) tracking = false;
            return true;
        }
    }
}
