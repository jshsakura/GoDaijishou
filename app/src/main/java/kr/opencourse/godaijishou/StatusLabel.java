package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
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
    // Pull-down distance before the list opens.
    private static final float PULL_DP = 14f;
    // Heat warning: battery temperature in tenths of a degree, or the system thermal status.
    private static final int WARM_TENTHS = 400; // 40.0 °C
    private static final int HOT_TENTHS = 450;  // 45.0 °C
    private static final int HEAT_NONE = 0, HEAT_WARM = 1, HEAT_HOT = 2;

    private static Context system;
    private static Handler handler;
    private static WindowManager windowManager;
    private static View view;
    // StripView as loaded from the installed APK (see stripClass()); reloaded on app updates.
    private static Class<?> stripClass;
    private static String stripSource;
    private static java.lang.reflect.Method setState, hitsPill, gripBottom;
    // Invisible strip along the top right: a tap on the pill or a downward pull opens the
    // notification list. Other taps are passed on, so app buttons under it (close X etc.) work.
    private static View grip;
    // Horizontal span of the grip on the second screen (display pixels), for the swipe guard.
    private static volatile int gripLeft = Integer.MAX_VALUE;
    private static volatile int gripRight = Integer.MAX_VALUE;
    // Look and position the windows were built with; a change rebuilds them.
    private static int builtStyle = -1, builtPos = -1;

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

        // App updated: draw with the new StripView right away, no reboot.
        IntentFilter replaced = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        replaced.addDataScheme("package");
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                android.net.Uri data = intent.getData();
                if (data == null || !BuildConfig.APPLICATION_ID.equals(data.getSchemeSpecificPart())) return;
                stripClass = null;
                if (view != null) detach();
                apply();
            }
        }, replaced, null, h);

        // Setting toggled or swivel opened/closed.
        SystemSettings.addListener(StatusLabel::apply);
        h.post(StatusLabel::apply);
    }

    /**
     * Tracks whether the second screen's top app is fullscreen (hides the status bar), the
     * same flag the system uses for its own status bar. finishPostLayoutPolicyLw runs after
     * every layout under the WindowManager lock, so the hook only compares and hands off.
     */
    /**
     * A pull from the top edge is also the system's "swipe from top": on the second screen it
     * goes to LG's own panel (Control Assistant), which then slid down behind our list.
     * Swipes that start on the grip are ours; everywhere else they reach LG as before.
     */
    static void installSwipeGuard(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.android.server.wm.DisplayPolicy$1", classLoader,
                "onSwipeFromTopWithEvent", android.view.MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View g = grip;
                        if (g == null || g.getVisibility() != View.VISIBLE) return;
                        android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                        if (ev == null || ev.getX() < gripLeft || ev.getX() >= gripRight) return;
                        int display;
                        try {
                            display = (Integer) XposedHelpers.callMethod(ev, "getDisplayId");
                        } catch (Throwable t) {
                            return;
                        }
                        if (display == MainModule.SECOND_SCREEN_DISPLAY_ID) param.setResult(null);
                    }
                });
    }

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
                                ModuleLog.w(t);
                                return;
                            }
                            secondPolicy = policy;
                        }
                        boolean now;
                        try {
                            now = XposedHelpers.getBooleanField(policy, "mTopIsFullscreen");
                        } catch (Throwable t) {
                            ModuleLog.w(t);
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

    // The window has a fixed size (see StripView.onMeasure); only the drawing changes.
    private static void refresh() {
        View v = view;
        if (v == null || setState == null) return;
        android.os.Bundle state = new android.os.Bundle();
        state.putInt(StripView.BATTERY, battery);
        state.putBoolean(StripView.CHARGING, charging);
        state.putInt(StripView.WIFI, wifiLevel);
        state.putString(StripView.MOBILE, mobile ? mobileLabel() : null);
        state.putInt(StripView.COUNT, notifications);
        state.putInt(StripView.HEAT, heat());
        try {
            setState.invoke(v, state);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /**
     * StripView from the APK that is installed now, in a class loader of its own (parent: the
     * boot class loader, so it shares nothing with the module code loaded at boot). An app
     * update installs a new APK path, and the next call loads the new drawing from it.
     * Falls back to the copy loaded at boot if that fails.
     */
    private static Class<?> stripClass() {
        try {
            String apk = system.getPackageManager()
                    .getApplicationInfo(BuildConfig.APPLICATION_ID, 0).sourceDir;
            if (stripClass == null || !apk.equals(stripSource)) {
                ClassLoader loader = new dalvik.system.PathClassLoader(apk, View.class.getClassLoader());
                bindStrip(loader.loadClass(StripView.class.getName()));
                stripSource = apk;
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
            if (stripClass == null) {
                try {
                    bindStrip(StripView.class);
                } catch (Throwable fatal) {
                    ModuleLog.w(fatal);
                }
            }
        }
        return stripClass;
    }

    private static void bindStrip(Class<?> cls) throws NoSuchMethodException {
        setState = cls.getMethod("setState", android.os.Bundle.class);
        hitsPill = cls.getMethod("hitsPill", float.class, float.class);
        gripBottom = cls.getMethod("gripBottom");
        stripClass = cls;
    }

    private static void apply() {
        boolean want = SystemSettings.batteryStrip
                && SystemSettings.secondScreenOn;
        if (want) {
            registerNetwork();
            registerThermal();
        }
        if (view != null && (builtStyle != SystemSettings.stripStyle || builtPos != SystemSettings.stripPos)) {
            detach(); // look or position changed: rebuild below
        }
        if (want && view == null) attach();
        else if (!want && view != null) detach();
        if (view != null) {
            boolean hidden = SystemSettings.labelHideFullscreen && fullscreen;
            view.setVisibility(hidden ? View.GONE : View.VISIBLE);
            // The pull-down spot works on every screen, fullscreen apps included (only the
            // label hides there); never on the lock screen, which must not show notifications.
            if (grip != null) grip.setVisibility(locked() ? View.GONE : View.VISIBLE);
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
            ModuleLog.w(ignored);
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
            ModuleLog.w(ignored);
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

            builtStyle = SystemSettings.stripStyle;
            builtPos = SystemSettings.stripPos;
            boolean line = builtStyle == AppPreferences.STRIP_STYLE_LINE;
            Class<?> cls = stripClass();
            if (cls == null) return;
            // Drawing only: never touchable, so whatever sits under it keeps its taps.
            // The thin line spans the width; the pill window hugs its side (or the middle).
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    line ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | horizontalGravity(builtPos);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("GoDaijishou:StatusLabel");

            View v = (View) cls.getConstructor(Context.class, int.class, int.class)
                    .newInstance(ui, builtStyle, builtPos);
            windowManager.addView(v, lp);
            view = v;
            refresh();
            attachGrip(ui, v, density);
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
            // Never let the label disturb system_server.
        }
    }

    private static int horizontalGravity(int pos) {
        if (pos == AppPreferences.STRIP_POS_LEFT) return Gravity.START;
        if (pos == AppPreferences.STRIP_POS_CENTER) return Gravity.CENTER_HORIZONTAL;
        return Gravity.END;
    }

    // Pull-down strip along the top edge: the third of the screen that holds the label,
    // as tall as the label plus a few dp. A tap on the pill or a downward pull opens the list.
    // Any other tap is handed on to whatever is underneath (an app's close button keeps
    // working): the strip steps aside for a moment and the tap is replayed at the same spot.
    private static void attachGrip(Context ui, View v, float density) {
        try {
            int screen = ui.getResources().getDisplayMetrics().widthPixels;
            int width = screen / 3;
            int left = builtPos == AppPreferences.STRIP_POS_LEFT ? 0
                    : builtPos == AppPreferences.STRIP_POS_CENTER ? (screen - width) / 2 : screen - width;
            gripLeft = left;
            gripRight = left + width;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    width,
                    (int) gripBottom.invoke(v),
                    TYPE_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | horizontalGravity(builtPos);
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
                        // The same touch is a pull from the top edge for the system too: keep
                        // the stock shade from expanding while this strip owns the finger.
                        holdStockShade(g.getContext(), true);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dy = ev.getRawY() - down[1];
                        float dx = Math.abs(ev.getRawX() - down[0]);
                        if (Math.abs(dy) > tapSlop || dx > tapSlop) moved[0] = true;
                        if (!fired[0] && dy > pull && dy > dx * 1.5f) {
                            fired[0] = true;
                            if (!NotificationPanel.isOpen()) NotificationPanel.toggle();
                            collapseStockShade(g.getContext());
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (!fired[0] && !moved[0]) {
                            if (hits(v, down[0], down[1])) {
                                if (!NotificationPanel.isOpen()) NotificationPanel.toggle();
                            } else {
                                passTap(g, lp, down[0], down[1]);
                            }
                        }
                        releaseStockShade(g);
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        releaseStockShade(g);
                        return true;
                    default:
                        return true;
                }
            });
            windowManager.addView(g, lp);
            grip = g;
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
            grip = null;
        }
    }

    private static boolean hits(View v, float x, float y) {
        try {
            return (boolean) hitsPill.invoke(v, x, y);
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }

    // StatusBarManager.DISABLE_EXPAND (hidden): the stock shade can't be pulled down.
    private static final int DISABLE_EXPAND = 0x00010000;

    private static void holdStockShade(Context context, boolean hold) {
        try {
            Object sbm = context.getSystemService("statusbar");
            if (sbm != null) XposedHelpers.callMethod(sbm, "disable", hold ? DISABLE_EXPAND : 0);
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
        }
    }

    // Collapse whatever slipped through, then let the stock shade work again once its own
    // animation would have started.
    private static void releaseStockShade(View g) {
        collapseStockShade(g.getContext());
        g.postDelayed(() -> {
            collapseStockShade(g.getContext());
            holdStockShade(g.getContext(), false);
        }, 250);
    }

    private static void collapseStockShade(Context context) {
        try {
            Object sbm = context.getSystemService("statusbar");
            if (sbm != null) XposedHelpers.callMethod(sbm, "collapsePanels");
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
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
                ModuleLog.w(ignored);
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
            ModuleLog.w(t);
            return false;
        }
    }

    private static void detach() {
        NotificationPanel.close();
        gripLeft = Integer.MAX_VALUE;
        gripRight = Integer.MAX_VALUE;
        View g = grip;
        grip = null;
        if (g != null) {
            try {
                windowManager.removeView(g);
            } catch (RuntimeException ignored) {
            }
        }
        View v = view;
        view = null;
        if (v == null) return;
        try {
            windowManager.removeView(v);
        } catch (RuntimeException ignored) {
        }
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
            ModuleLog.w(t);
            return "LTE";
        }
    }
}
