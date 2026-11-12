package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Status label: a small translucent pill floating in the top-right corner of the second
 * screen (which has no status bar) showing Wi-Fi, mobile data (LTE/5G), the notification count
 * and the battery percentage. Drawn by system_server (no permission), never touchable or
 * focusable, small enough to sit over games and fullscreen apps unnoticed.
 *
 * Every field below is written and read on the settings thread only.
 */
final class StatusLabel {

    // WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY (hidden): above apps and system bars.
    private static final int TYPE_OVERLAY = 2020;
    private static final float HEIGHT_DP = 16f;
    private static final float RIGHT_MARGIN_DP = 8f;
    // The window is not touchable any more: no slack under the pill.
    private static final float TOUCH_SLACK_DP = 0f;
    // Pull-down grip over the battery: a little taller than the pill, and the finger has to
    // travel this far down (mostly vertically) before the list opens.
    private static final float GRIP_SLACK_DP = 6f;
    private static final float PULL_DP = 14f;
    private static final float PAD_DP = 6.5f;
    private static final float GAP_DP = 5f;
    // Pill floating just below the top edge.
    private static final float TOP_MARGIN_DP = 1f;
    private static final float ICON_DP = 10f;
    private static final float TEXT_SP = 10.5f;
    /** Colors for one system theme. Battery colors: muted, still readable on the background. */
    private static final class Palette {
        final int background, outline, ink, dim, bell, good, mid, low, warm, hot;
        final int bubbleInk = 0xFF1A1A1E;

        Palette(int background, int outline, int ink, int dim, int bell, int good, int mid, int low,
                int warm, int hot) {
            this.background = background;
            this.outline = outline;
            this.ink = ink;
            this.dim = dim;
            this.bell = bell;
            this.good = good;
            this.mid = mid;
            this.low = low;
            this.warm = warm;
            this.hot = hot;
        }
    }

    // FT ground / ink, well see-through, with pastel battery colors.
    // The hairline outline keeps the pill's shape visible on black backgrounds.
    private static final Palette DARK = new Palette(
            0x8C121216, 0x40FFFFFF, 0xE6ECECF0, 0x52ECECF0, 0xE6FFC23D,
            0xFF8FD1AE, 0xFFEBCB80, 0xFFE89A92,
            0xFFF2A65A, 0xFFF26B5E);
    // Inverted for light mode; battery colors deepened so they hold up on a light tab.
    private static final Palette LIGHT = new Palette(
            0x99F4F4F7, 0x24000000, 0xE61A1A1E, 0x521A1A1E, 0xFFC68A10,
            0xFF3E9C6D, 0xFFB0821A, 0xFFC65A50,
            0xFFD0771C, 0xFFCC3B30);
    private static final int HIGH = 50;
    private static final int LOW = 20;
    // Heat warning: battery temperature in tenths of a degree, or the system thermal status.
    private static final int WARM_TENTHS = 400; // 40.0 °C
    private static final int HOT_TENTHS = 450;  // 45.0 °C
    private static final int HEAT_NONE = 0, HEAT_WARM = 1, HEAT_HOT = 2;

    private static Context system;
    private static Handler handler;
    private static WindowManager windowManager;
    private static LabelView view;
    // Invisible strip over the battery only: a downward swipe there opens the notification
    // list. The label itself takes no touches, so app buttons under it (close X etc.) work.
    private static View grip;

    private static int battery = -1;       // 0..100, -1 until the first battery broadcast
    private static boolean charging = false;
    private static int wifiLevel = -1;     // 0..3, -1 = no Wi-Fi
    private static boolean mobile = false; // a cellular data network is up
    private static int notifications = 0;
    private static int batteryTenths = 0;  // battery temperature, tenths of °C
    private static int thermalStatus = 0;  // PowerManager.THERMAL_STATUS_*
    // Whether the second screen's top app hides the status bar; written from the WM hook.
    private static volatile boolean fullscreen = false;
    private static volatile Object secondPolicy; // DisplayPolicy of the second screen, once found

    private StatusLabel() {
    }

    static void install(Context context, Handler h) {
        system = context;
        handler = h;
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (raw < 0 || scale <= 0) return;
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                battery = Math.round(raw * 100f / scale);
                batteryTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                refresh();
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, h);
        // Dark/light mode switches arrive as a configuration change.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                refresh();
            }
        }, new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), null, h);

        // Lock screen: the pull-down strip steps aside (it sat over the PIN / pattern pad, and
        // a lock screen may ignore touches on a window that something covers).
        IntentFilter lock = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        lock.addAction(Intent.ACTION_USER_PRESENT);
        lock.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                apply();
            }
        }, lock, null, h);

        // Setting toggled or swivel opened/closed.
        SystemSettings.addListener(StatusLabel::apply);
        h.post(StatusLabel::apply);
    }

    /**
     * Tracks whether the second screen's top app is fullscreen (hides the status bar), the
     * same flag the system uses for its own status bar. finishPostLayoutPolicyLw runs after
     * every layout under the WindowManager lock, so the hook only compares and hands off.
     */
    static void installFullscreenHook(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.android.server.wm.DisplayPolicy", classLoader,
                "finishPostLayoutPolicyLw", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!SystemSettings.labelHideFullscreen || handler == null) return;
                        Object policy = param.thisObject;
                        // Identity check first; the id lookup only runs for other displays
                        // (and once more if the second screen's policy is ever recreated).
                        if (secondPolicy != policy) {
                            try {
                                if ((int) XposedHelpers.callMethod(policy, "getDisplayId")
                                        != MainModule.SECOND_SCREEN_DISPLAY_ID) return;
                            } catch (Throwable t) {
                                return;
                            }
                            secondPolicy = policy;
                        }
                        boolean now;
                        try {
                            now = XposedHelpers.getBooleanField(policy, "mTopIsFullscreen");
                        } catch (Throwable t) {
                            return;
                        }
                        if (now != fullscreen) {
                            fullscreen = now;
                            handler.post(StatusLabel::apply);
                        }
                    }
                });
    }

    /** From NotificationBanner's listener hooks; any thread. */
    static void setNotificationCount(int count) {
        if (handler == null) return;
        handler.post(() -> {
            notifications = count;
            refresh();
        });
    }

    // The window has a fixed size (see LabelView.onMeasure); only the drawing changes.
    private static void refresh() {
        if (view != null) view.invalidate();
    }

    private static void apply() {
        boolean want = SystemSettings.batteryStrip
                && (SystemSettings.secondScreenOn || SystemSettings.noTopScreen);
        if (want) {
            registerNetwork();
            registerThermal();
        }
        if (want && view == null) attach();
        else if (!want && view != null) detach();
        if (view != null) {
            boolean hidden = SystemSettings.labelHideFullscreen && fullscreen;
            view.setVisibility(hidden ? View.GONE : View.VISIBLE);
            if (grip != null) grip.setVisibility(hidden || locked() ? View.GONE : View.VISIBLE);
            if (hidden) NotificationPanel.close();
        }
    }

    // Registered on first use, not at install: install runs very early in boot, before the
    // connectivity service is up. Settings are only read after boot, so this is late enough.
    private static boolean networkRegistered = false;

    private static void registerNetwork() {
        if (networkRegistered) return;
        try {
            ConnectivityManager cm = system.getSystemService(ConnectivityManager.class);
            WifiManager wifi = system.getSystemService(WifiManager.class);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                            int rssi = caps.getSignalStrength();
                            int max = Math.max(1, wifi.getMaxSignalLevel());
                            int level = rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED
                                    ? max : wifi.calculateSignalLevel(rssi);
                            wifiLevel = Math.round(3f * level / max);
                            refresh();
                        }

                        @Override
                        public void onLost(Network network) {
                            wifiLevel = -1;
                            refresh();
                        }
                    }, handler);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            mobile = true;
                            refresh();
                        }

                        @Override
                        public void onLost(Network network) {
                            mobile = false;
                            refresh();
                        }
                    }, handler);
            networkRegistered = true;
        } catch (Throwable ignored) {
            // Retried on the next apply().
        }
    }

    private static boolean thermalRegistered = false;

    // The system's own heat level (CPU/skin sensors), on top of the battery temperature.
    private static void registerThermal() {
        if (thermalRegistered) return;
        try {
            android.os.PowerManager pm = system.getSystemService(android.os.PowerManager.class);
            pm.addThermalStatusListener(handler::post, status -> {
                thermalStatus = status;
                refresh();
            });
            thermalRegistered = true;
        } catch (Throwable ignored) {
            // Retried on the next apply().
        }
    }

    private static int heat() {
        if (thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE || batteryTenths >= HOT_TENTHS) {
            return HEAT_HOT;
        }
        if (thermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE || batteryTenths >= WARM_TENTHS) {
            return HEAT_WARM;
        }
        return HEAT_NONE;
    }

    private static void attach() {
        try {
            Display display = system.getSystemService(DisplayManager.class)
                    .getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
            if (display == null) return;
            windowManager = system.createDisplayContext(display).getSystemService(WindowManager.class);
            Context ui = system.createPackageContext(BuildConfig.APPLICATION_ID, 0).createDisplayContext(display);
            float density = ui.getResources().getDisplayMetrics().density;

            // Drawing only: never touchable, so whatever sits under it keeps its taps.
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("GoDaijishou:StatusLabel");

            LabelView v = new LabelView(ui);
            windowManager.addView(v, lp);
            view = v;
            attachGrip(ui, v, density);
        } catch (Throwable ignored) {
            // Never let the label disturb system_server.
        }
    }

    // Pull-down strip along the top edge: the right third of the screen (it holds the label),
    // as tall as the label plus a few dp. Only a downward pull opens the list. A plain tap is
    // handed on to whatever is underneath (an app's close button keeps working): the strip
    // steps aside for a moment and the tap is replayed at the same spot.
    private static void attachGrip(Context ui, LabelView v, float density) {
        try {
            int width = ui.getResources().getDisplayMetrics().widthPixels / 3;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    width,
                    Math.round(v.top + v.height + GRIP_SLACK_DP * density),
                    TYPE_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("GoDaijishou:StatusLabelGrip");
            View g = new View(ui);
            final float pull = PULL_DP * density;
            final float tapSlop = android.view.ViewConfiguration.get(ui).getScaledTouchSlop();
            final float[] down = new float[2];
            final boolean[] fired = new boolean[1];
            final boolean[] moved = new boolean[1];
            g.setOnTouchListener((x, ev) -> {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        down[0] = ev.getRawX();
                        down[1] = ev.getRawY();
                        fired[0] = false;
                        moved[0] = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dy = ev.getRawY() - down[1];
                        float dx = Math.abs(ev.getRawX() - down[0]);
                        if (Math.abs(dy) > tapSlop || dx > tapSlop) moved[0] = true;
                        if (!fired[0] && dy > pull && dy > dx * 1.5f) {
                            fired[0] = true;
                            if (!NotificationPanel.isOpen()) NotificationPanel.toggle();
                            // The same pull is a swipe from the top edge for the system too,
                            // which drops the stock shade: put it back up, now and once more
                            // when its own animation has started.
                            collapseStockShade(g.getContext());
                            g.postDelayed(() -> collapseStockShade(g.getContext()), 250);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (!fired[0] && !moved[0]) passTap(g, lp, down[0], down[1]);
                        return true;
                    default:
                        return true;
                }
            });
            windowManager.addView(g, lp);
            grip = g;
        } catch (Throwable ignored) {
            grip = null;
        }
    }

    private static void collapseStockShade(Context context) {
        try {
            Object sbm = context.getSystemService("statusbar");
            if (sbm != null) XposedHelpers.callMethod(sbm, "collapsePanels");
        } catch (Throwable ignored) {
        }
    }

    // Replays a tap under the strip: strip untouchable, tap injected on this display, strip back.
    private static void passTap(View g, WindowManager.LayoutParams lp, float x, float y) {
        try {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(g, lp);
        } catch (RuntimeException e) {
            return;
        }
        final int display = g.getDisplay() != null ? g.getDisplay().getDisplayId() : MainModule.SECOND_SCREEN_DISPLAY_ID;
        g.postDelayed(() -> {
            try {
                long t = android.os.SystemClock.uptimeMillis();
                inject(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0), display);
                inject(android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, x, y, 0), display);
            } catch (Throwable ignored) {
            }
            g.postDelayed(() -> {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                try {
                    if (g.isAttachedToWindow()) windowManager.updateViewLayout(g, lp);
                } catch (RuntimeException ignored) {
                }
            }, 150);
        }, 32);
    }

    private static void inject(android.view.MotionEvent ev, int display) throws Exception {
        ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        XposedHelpers.callMethod(ev, "setDisplayId", display);
        Object im = XposedHelpers.callStaticMethod(Class.forName("android.hardware.input.InputManager"), "getInstance");
        XposedHelpers.callMethod(im, "injectInputEvent", ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
        ev.recycle();
    }

    private static boolean locked() {
        try {
            android.app.KeyguardManager km = system.getSystemService(android.app.KeyguardManager.class);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void detach() {
        NotificationPanel.close();
        View g = grip;
        grip = null;
        if (g != null) {
            try {
                windowManager.removeView(g);
            } catch (RuntimeException ignored) {
            }
        }
        LabelView v = view;
        view = null;
        try {
            windowManager.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    // The window's own configuration: WindowManager pushes dark/light switches to it.
    private static Palette palette(Resources res) {
        int night = res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }

    private static String mobileLabel() {
        try {
            TelephonyManager tm = system.getSystemService(TelephonyManager.class);
            switch (tm.getDataNetworkType()) {
                case TelephonyManager.NETWORK_TYPE_NR:
                    return "5G";
                case TelephonyManager.NETWORK_TYPE_LTE:
                    return "LTE";
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                    return "H";
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    return "3G";
                default:
                    return "LTE";
            }
        } catch (Throwable t) {
            return "LTE";
        }
    }

    /**
     * Draws ( [flame] [Wi-Fi] [LTE] [battery%] ) (n). The pill's right end and the battery
     * are fixed; Wi-Fi and LTE sit left of the battery, and the rare heat flame goes on the
     * far left, so it only grows the pill's outer end. The notification count is a small
     * separate butter circle in its own fixed spot right of the pill, number only, and
     * nothing at all when there are none.
     */
    private static final class LabelView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final Path flame = new Path();
        private final RectF rect = new RectF();
        private final RectF oval = new RectF();
        private final float pad;
        private final float gap;
        private final float radius;
        private final float icon;
        private final float rightMargin;
        private final float slack;
        private final float top;
        private final float height;

        LabelView(Context ui) {
            super(ui);
            float density = ui.getResources().getDisplayMetrics().density;
            float scaled = ui.getResources().getDisplayMetrics().scaledDensity;
            pad = PAD_DP * density;
            gap = GAP_DP * density;
            radius = HEIGHT_DP * density / 2f;
            top = TOP_MARGIN_DP * density;
            icon = ICON_DP * density;
            rightMargin = RIGHT_MARGIN_DP * density;
            slack = TOUCH_SLACK_DP * density;
            height = HEIGHT_DP * density;
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(Math.max(1f, 0.75f * density));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setStrokeWidth(Math.max(1f, 1.1f * density));
            text.setTextSize(TEXT_SP * scaled);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        // The battery slot (glyph + "100%"-wide number) sits at a fixed spot on the right;
        // the other items are added or dropped on its left, so the battery never moves.
        private float batterySlot() {
            return batteryGlyphWidth() + gap * 0.5f + text.measureText("100%");
        }

        private float batteryGlyphWidth() {
            return icon * 1.25f;
        }

        // Mobile data label in a fixed-width slot, so LTE <-> 5G doesn't shift anything.
        private float mobileSlot() {
            return Math.max(text.measureText("LTE"), text.measureText("5G"));
        }

        // Width of the pill's content for the current state.
        private float contentWidth(String mobileText) {
            float content = batterySlot();
            if (wifiLevel >= 0) content += gap + icon;
            if (mobileText != null) content += gap + mobileSlot();
            if (heat() != HEAT_NONE) content += gap + icon * 0.75f;
            return content;
        }

        // Widest possible pill: everything shown.
        private float maxPillWidth() {
            return pad * 2 + batterySlot() + gap + icon + gap + mobileSlot() + gap + icon * 0.75f;
        }

        // Count circle: the pill's height, widening only for two digits.
        private float bubbleWidth(String count) {
            return Math.max(height, text.measureText(count) + pad * 1.2f);
        }

        private float bubbleGap() {
            return gap * 0.7f;
        }

        private String mobileText() {
            return mobile ? mobileLabel() : null;
        }

        private String countText() {
            return notifications > 0 ? String.valueOf(Math.min(notifications, 99)) : null;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            float w = maxPillWidth() + bubbleGap() + bubbleWidth("99") + rightMargin;
            setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(top + height + slack));
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            invalidate(); // dark/light switch
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (battery < 0) return;
            Palette p = palette(getResources());
            bg.setColor(p.background);
            String mobileText = mobileText();
            String countText = countText();
            String batteryText = battery + "%";

            float h = height;
            canvas.translate(0, top);
            float right = getWidth() - rightMargin - bubbleWidth("99") - bubbleGap();
            float left = right - pad * 2 - contentWidth(mobileText);
            rect.set(left, 0, right, h);
            shape.reset();
            shape.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.drawPath(shape, bg);
            outline.setColor(p.outline);
            canvas.drawPath(shape, outline);

            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = (h - fm.ascent - fm.descent) / 2f;
            float cy = h / 2f;

            // Battery: number right-aligned in its fixed slot, glyph at the slot's left edge.
            int level = charging || battery >= HIGH ? p.good : battery >= LOW ? p.mid : p.low;
            float x = right - pad;
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(level);
            canvas.drawText(batteryText, x, baseline, text);
            text.setTextAlign(Paint.Align.LEFT);
            x -= batterySlot();
            drawBattery(canvas, x, cy, level, p);

            // Then right to left: LTE, Wi-Fi, and the heat flame on the far left.
            if (mobileText != null) {
                x -= gap + mobileSlot();
                text.setColor(p.ink);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(mobileText, x + mobileSlot() / 2f, baseline, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
            if (wifiLevel >= 0) {
                x -= gap + icon;
                drawWifi(canvas, x, cy, p);
            }
            int heat = heat();
            if (heat != HEAT_NONE) {
                x -= gap + icon * 0.75f;
                drawFlame(canvas, x, cy, icon * 0.75f, heat == HEAT_HOT ? p.hot : p.warm);
            }

            // Notification count: number only, in its own circle after the pill.
            if (countText != null) {
                float bLeft = right + bubbleGap();
                rect.set(bLeft, 0, bLeft + bubbleWidth(countText), h);
                ink.setColor(p.bell);
                canvas.drawRoundRect(rect, h / 2f, h / 2f, ink);
                text.setColor(p.bubbleInk);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(countText, rect.centerX(), baseline, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
        }

        // Three arcs over a dot; arcs above the signal level are drawn dim.
        private void drawWifi(Canvas canvas, float x, float cy, Palette p) {
            float cx = x + icon / 2f;
            float base = cy + icon * 0.42f;
            ink.setColor(p.ink);
            canvas.drawCircle(cx, base - icon * 0.05f, icon * 0.09f, ink);
            for (int i = 1; i <= 3; i++) {
                float r = icon * 0.24f * i + icon * 0.04f;
                oval.set(cx - r, base - r, cx + r, base + r);
                arc.setColor(i <= wifiLevel ? p.ink : p.dim);
                canvas.drawArc(oval, 225, 90, false, arc);
            }
        }

        // Battery glyph: outlined body filled to the level, small nub on the right.
        private void drawBattery(Canvas canvas, float x, float cy, int levelColor, Palette p) {
            float w = batteryGlyphWidth() - icon * 0.12f;
            float h = icon * 0.62f;
            float r = h * 0.25f;
            oval.set(x, cy - h / 2f, x + w, cy + h / 2f);
            arc.setColor(p.ink);
            canvas.drawRoundRect(oval, r, r, arc);
            float inset = arc.getStrokeWidth() * 1.3f;
            ink.setColor(levelColor);
            float fillRight = x + inset + (w - inset * 2) * (battery / 100f);
            canvas.drawRect(x + inset, cy - h / 2f + inset, fillRight, cy + h / 2f - inset, ink);
            ink.setColor(p.ink);
            canvas.drawRect(x + w, cy - h * 0.2f, x + w + icon * 0.12f, cy + h * 0.2f, ink);
        }

        // Flame: a teardrop with a pointed tip and a round base.
        private void drawFlame(Canvas canvas, float x, float cy, float size, int color) {
            float w = size;
            float h = size * 1.25f;
            float top = cy - h / 2f;
            float bottom = cy + h / 2f;
            float cx = x + w / 2f;
            flame.reset();
            flame.moveTo(cx + w * 0.08f, top);
            flame.cubicTo(cx + w * 0.62f, top + h * 0.38f, x + w * 1.02f, top + h * 0.62f, cx + w * 0.38f, bottom - h * 0.08f);
            flame.cubicTo(cx + w * 0.2f, bottom + h * 0.02f, cx - w * 0.2f, bottom + h * 0.02f, cx - w * 0.38f, bottom - h * 0.08f);
            flame.cubicTo(x - w * 0.02f, top + h * 0.6f, cx - w * 0.36f, top + h * 0.36f, cx + w * 0.08f, top);
            flame.close();
            ink.setColor(color);
            canvas.drawPath(flame, ink);
        }
    }
}
