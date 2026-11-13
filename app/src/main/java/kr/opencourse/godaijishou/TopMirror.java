package kr.opencourse.godaijishou;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import de.robv.android.xposed.XposedHelpers;

/**
 * The top screen, live, on the second screen (runs in system_server; toggled from the
 * notification panel).
 *
 * WindowManager mirrors display 0 into a SurfaceControl (mirrorDisplay, what screen
 * magnification and casting build on). That layer is hung under a black window on the second
 * screen, scaled to fit with the top screen's aspect ratio kept (black bars around it). The
 * top panel may be powered off ("top screen off" only blanks the panel; apps there keep
 * drawing). Windows that forbid capture (FLAG_SECURE) stay black, as in any screenshot.
 *
 * Touch: the mirror carries the top screen's input windows along (scaled), so touches on the
 * picture reach the top apps directly, multi-touch included. Where firmware doesn't, touches
 * that fall through to the black window are mapped and injected instead. The mirrored windows
 * also cover the black bars (an activity's input sink spans the display), so back and close
 * sit in a small window of their own above the mirror, centred in the widest bar.
 *
 * Both windows are TYPE_APPLICATION_OVERLAY, under the battery bar and the notification
 * panel (system overlays), so those keep working on top. Settings thread only.
 */
final class TopMirror {

    private static final int TOP = Display.DEFAULT_DISPLAY;
    private static final float BUTTON_DP = 44f;
    private static final float CORNER_DP = 18f;

    private static Context system;
    private static Handler handler;
    private static WindowManager windowManager;
    private static MirrorView view;
    private static android.widget.LinearLayout controls;
    private static WindowManager.LayoutParams controlsLp;
    private static SurfaceControl mirror;
    private static DisplayManager.DisplayListener topListener;

    private TopMirror() {
    }

    // Read by hooks on other threads (activity starts, notifications).
    private static volatile boolean showing;

    static boolean isShowing() {
        return showing;
    }

    static void toggle() {
        if (view != null) hide();
        else show();
    }

    private static void show() {
        try {
            system = SystemSettings.context();
            handler = SystemSettings.handler();
            DisplayManager dm = system.getSystemService(DisplayManager.class);
            Display second = dm.getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
            if (second == null || dm.getDisplay(TOP) == null) return;
            windowManager = system.createDisplayContext(second).getSystemService(WindowManager.class);
            Context ui = system.createPackageContext(BuildConfig.APPLICATION_ID, 0).createDisplayContext(second);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            // The whole screen, the second screen's gesture bar strip too (its pill and
            // gestures stand aside meanwhile, BottomGestureHook).
            lp.setFitInsetsTypes(0);
            lp.setTitle("GoDaijishou:TopMirror");

            MirrorView v = new MirrorView(ui);
            view = v;
            windowManager.addView(v, lp);
            showing = true;
            addControls(ui); // after the mirror window, so above it and its mirrored windows
            setTopView(true);

            // The top screen rotating or changing size: fit again. The swivel closing (second
            // screen off or gone): nothing to show it on, close.
            topListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) {
                    if (id == MainModule.SECOND_SCREEN_DISPLAY_ID) hide();
                }
                @Override public void onDisplayChanged(int id) {
                    if (view == null) return;
                    if (id == TOP) {
                        view.requestLayout();
                    } else if (id == MainModule.SECOND_SCREEN_DISPLAY_ID) {
                        Display d = dm.getDisplay(id);
                        if (d == null || d.getState() == Display.STATE_OFF) hide();
                    }
                }
            };
            dm.registerDisplayListener(topListener, handler);
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
            if (topListener != null && system != null) {
                system.getSystemService(DisplayManager.class).unregisterDisplayListener(topListener);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        topListener = null;
        releaseMirror();
        restoreTop();
        android.widget.LinearLayout c = controls;
        controls = null;
        if (c != null) {
            try {
                windowManager.removeView(c);
            } catch (RuntimeException ignored) {
            }
        }
        if (v != null) {
            try {
                windowManager.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void addControls(Context ui) {
        float density = ui.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(ui);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int size = Math.round(BUTTON_DP * density), gap = Math.round(12 * density);
        ImageView back = roundButton(ui, R.drawable.ic_ft_back, density);
        back.setContentDescription("Back");
        back.setOnClickListener(b -> sendBack());
        ImageView close = roundButton(ui, R.drawable.ic_ft_close, density);
        close.setContentDescription(ui.getString(android.R.string.cancel));
        close.setOnClickListener(b -> hide());
        android.widget.LinearLayout.LayoutParams first = new android.widget.LinearLayout.LayoutParams(size, size);
        android.widget.LinearLayout.LayoutParams second = new android.widget.LinearLayout.LayoutParams(size, size);
        second.topMargin = gap;
        box.addView(back, first);
        box.addView(close, second);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("GoDaijishou:TopMirrorControls");
        controls = box;
        controlsLp = lp;
        windowManager.addView(box, lp);
    }

    private static ImageView roundButton(Context ui, int icon, float density) {
        ImageView b = new ImageView(ui);
        android.graphics.drawable.Drawable d = ui.getDrawable(icon);
        if (d != null) {
            d = d.mutate();
            d.setTint(ui.getColor(R.color.ft_ink));
            b.setImageDrawable(d);
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(ui.getColor(R.color.ft_surface));
        ring.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
        b.setBackground(ring);
        int p = Math.round(11 * density);
        b.setPadding(p, p, p, p);
        return b;
    }

    // Lay the buttons out down the side bar, or across the bottom bar when the top screen
    // is landscape, centred in that bar.
    private static void placeControls(boolean side, float bar, float barX, float barY) {
        android.widget.LinearLayout c = controls;
        if (c == null || controlsLp == null) return;
        try {
            int orientation = side ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL;
            if (c.getOrientation() != orientation) {
                c.setOrientation(orientation);
                for (int i = 1; i < c.getChildCount(); i++) {
                    android.widget.LinearLayout.LayoutParams p =
                            (android.widget.LinearLayout.LayoutParams) c.getChildAt(i).getLayoutParams();
                    int gap = Math.max(p.topMargin, p.leftMargin);
                    p.topMargin = side ? gap : 0;
                    p.leftMargin = side ? 0 : gap;
                }
            }
            c.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            // Edge to edge (the top screen sized to this one): no bar to hold them, the top
            // screen's own back and home work there.
            int thickness = side ? c.getMeasuredWidth() : c.getMeasuredHeight();
            c.setVisibility(bar >= thickness ? View.VISIBLE : View.GONE);
            int x = Math.round(barX - c.getMeasuredWidth() / 2f);
            int y = Math.round(barY - c.getMeasuredHeight() / 2f);
            if (controlsLp.x == x && controlsLp.y == y) return;
            controlsLp.x = x;
            controlsLp.y = y;
            windowManager.updateViewLayout(c, controlsLp);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // Back for the top screen's app.
    private static void sendBack() {
        long now = android.os.SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            try {
                KeyEvent key = new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                        android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                        InputDevice.SOURCE_KEYBOARD);
                XposedHelpers.callMethod(key, "setDisplayId", TOP);
                injectEvent(key);
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    }

    // --- The top screen as a phone, sized to the second screen ---

    private static boolean sizeForced;

    /**
     * Tells LG's home that the swivel is "closed" (TopHomeHook), so the top screen behaves
     * like the folded phone, and remembers it in a global flag that is also the marker for
     * undoing the size below (cleared on hide and at every boot).
     */
    private static void setTopView(boolean on) {
        try {
            android.provider.Settings.Global.putInt(system.getContentResolver(),
                    TopHomeHook.KEY_TOP_VIEW, on ? 1 : 0);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /** The top screen laid out at the second screen's size: shown 1:1, edge to edge. */
    private static void fitTop(int w, int h) {
        if (w <= 0 || h <= 0) return;
        try {
            Object wms = XposedHelpers.callStaticMethod(
                    Class.forName("android.view.WindowManagerGlobal"), "getWindowManagerService");
            // In the top screen's natural (portrait) orientation; it rotates on its own.
            XposedHelpers.callMethod(wms, "setForcedDisplaySize", TOP, Math.min(w, h), Math.max(w, h));
            sizeForced = true;
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static void restoreTop() {
        if (sizeForced) {
            sizeForced = false;
            clearTopSize();
        }
        if (system != null) setTopView(false);
    }

    private static String lastMap;

    // For the second screen's gestures, which play edge swipes into the top screen.
    private static void publishMap(float scale, float left, float top, int w, int h) {
        String map = scale + "," + left + "," + top + "," + w + "," + h;
        if (map.equals(lastMap)) return;
        lastMap = map;
        try {
            android.provider.Settings.Global.putString(system.getContentResolver(),
                    TopHomeHook.KEY_TOP_MAP, map);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static void clearTopSize() {
        try {
            Object wms = XposedHelpers.callStaticMethod(
                    Class.forName("android.view.WindowManagerGlobal"), "getWindowManagerService");
            XposedHelpers.callMethod(wms, "clearForcedDisplaySize", TOP);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /**
     * At boot: a top view left over from before (crash, power loss) must not keep the top
     * screen at the second screen's size. The flag says whether it was ours to undo.
     */
    static void resetAfterBoot(Context context) {
        try {
            android.content.ContentResolver r = context.getContentResolver();
            if (android.provider.Settings.Global.getInt(r, TopHomeHook.KEY_TOP_VIEW, 0) == 1) {
                clearTopSize();
                android.provider.Settings.Global.putInt(r, TopHomeHook.KEY_TOP_VIEW, 0);
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

    /** Black window holding the mirrored layer; maps stray touches back to the top screen. */
    private static final class MirrorView extends FrameLayout {
        private final Matrix toTop = new Matrix();
        private float scale = 1f, left, top, shownW, shownH;
        private int srcW, srcH;
        private boolean tracking; // the current gesture started on the picture

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
            if (view != this || mirror != null || getRootSurfaceControl() == null) return;
            try {
                Object wms = XposedHelpers.callStaticMethod(
                        Class.forName("android.view.WindowManagerGlobal"), "getWindowManagerService");
                SurfaceControl out = SurfaceControl.class.getDeclaredConstructor().newInstance();
                if (!(boolean) XposedHelpers.callMethod(wms, "mirrorDisplay", TOP, out)) return;
                mirror = out;
                SurfaceControl.Transaction t = getRootSurfaceControl().buildReparentTransaction(out);
                if (t == null) return;
                place(t);
                t.setLayer(out, 1).setVisibility(out, true).apply();
            } catch (Throwable e) {
                ModuleLog.w(e);
            }
        }

        // Fit the top screen (its current, rotated size) inside this window, centred.
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            Point size = new Point();
            Display d = getContext().getSystemService(DisplayManager.class).getDisplay(TOP);
            if (d != null) d.getRealSize(size);
            int w = r - l, h = b - t;
            if (!sizeForced && view == this) fitTop(w, h); // relayouts once the size applies
            if (size.x <= 0 || size.y <= 0) return;
            srcW = size.x;
            srcH = size.y;
            scale = Math.min((float) w / size.x, (float) h / size.y);
            shownW = size.x * scale;
            shownH = size.y * scale;
            left = (w - shownW) / 2f;
            top = (h - shownH) / 2f;
            toTop.reset();
            toTop.setTranslate(-left, -top);
            toTop.postScale(1f / scale, 1f / scale);
            publishMap(scale, left, top, size.x, size.y);
            boolean side = left >= top;
            if (side) placeControls(true, left, left + shownW + left / 2f, h / 2f);
            else placeControls(false, top, w / 2f, top + shownH + top / 2f);
            if (mirror != null) {
                SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                place(tx);
                tx.apply();
            }
        }

        private void place(SurfaceControl.Transaction t) {
            t.setPosition(mirror, left, top);
            t.setScale(mirror, scale, scale);
            // Rounded corners, like a screen of its own. Cosmetic: skipped if unavailable.
            try {
                XposedHelpers.callMethod(t, "setWindowCrop", mirror, srcW, srcH);
                float radius = CORNER_DP * getResources().getDisplayMetrics().density / scale;
                XposedHelpers.callMethod(t, "setCornerRadius", mirror, radius);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                tracking = ev.getX() >= left && ev.getX() < left + shownW
                        && ev.getY() >= top && ev.getY() < top + shownH;
            }
            if (!tracking) return true;
            MotionEvent copy = MotionEvent.obtain(ev);
            copy.transform(toTop);
            inject(copy);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) tracking = false;
            return true;
        }
    }

    private static void inject(MotionEvent ev) {
        try {
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            XposedHelpers.callMethod(ev, "setDisplayId", TOP);
            injectEvent(ev);
        } catch (Throwable t) {
            ModuleLog.w(t);
        } finally {
            ev.recycle();
        }
    }

    private static void injectEvent(android.view.InputEvent ev) throws Exception {
        Object im = XposedHelpers.callStaticMethod(Class.forName("android.hardware.input.InputManager"), "getInstance");
        XposedHelpers.callMethod(im, "injectInputEvent", ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
    }
}
