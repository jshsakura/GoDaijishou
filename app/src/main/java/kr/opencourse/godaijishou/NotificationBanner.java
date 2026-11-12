package kr.opencourse.godaijishou;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Heads-up banner on the second screen. Runs inside system_server.
 *
 * While the top display is unavailable (top panel off), SystemUI still draws heads-up
 * notifications there, so the user only feels the vibration. Whenever a notification
 * alerts (buzz/beep/blink decided by NotificationManagerService), a small FT-style banner
 * is shown at the top of the second screen instead: tap opens it there, swipe up or wait
 * to dismiss.
 */
final class NotificationBanner {

    // LG overrides buzzBeepBlinkLocked in its subclass; hook whichever class declares it last.
    private static final String NMS = "com.android.server.notification.NotificationManagerService";
    private static final String NMS_LG = "com.android.server.notification.NotificationManagerServiceEx";
    private static final String NOTIFICATION_RECORD = "com.android.server.notification.NotificationRecord";
    private static final String LISTENERS = NMS + "$NotificationListeners";
    // Posts/removals come in bursts (group updates); count once after they settle.
    private static final long COUNT_DEBOUNCE_MS = 300;
    // WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY (hidden): above apps and system bars.
    private static final int TYPE_OVERLAY = 2020;

    private static final long SHOW_MS = 4000;
    private static final long SLIDE_MS = 200;
    private static final int MARGIN_DP = 10;

    private static WindowManager windowManager;
    private static View view;
    private static volatile Object service; // NotificationManagerService
    private static final Runnable countRunnable = NotificationBanner::count;
    private static final Runnable hideRunnable = NotificationBanner::slideOut;

    private NotificationBanner() {
    }

    static void install(ClassLoader classLoader) {
        Class<?> record = XposedHelpers.findClass(NOTIFICATION_RECORD, classLoader);
        Class<?> target;
        try {
            target = XposedHelpers.findClass(NMS_LG, classLoader);
            target.getDeclaredMethod("buzzBeepBlinkLocked", record);
        } catch (Throwable notLg) {
            ModuleLog.w(notLg);
            target = XposedHelpers.findClass(NMS, classLoader);
        }
        XposedHelpers.findAndHookMethod(target, "buzzBeepBlinkLocked", record, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (service == null) service = param.thisObject;
                // Runs under the notification lock: cheap checks, copy the data, hand off.
                if (!SystemSettings.topUnavailable() || SystemSettings.handler() == null) return;
                try {
                    Item item = Item.from(param.args[0], (Integer) param.getResult());
                    if (item != null) SystemSettings.handler().post(() -> show(item));
                } catch (Throwable ignored) {
                    ModuleLog.w(ignored);
                }
            }
        });
        installCount(classLoader);
    }

    // --- Notification count for the status label ---

    // Every post and removal goes through the listener dispatch, so hooking it keeps the
    // count current without polling. The count itself is taken later on the settings thread.
    private static void installCount(ClassLoader classLoader) {
        XC_MethodHook onChange = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (SystemSettings.handler() == null) return;
                if (service == null) {
                    try {
                        service = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    } catch (Throwable t) {
                        ModuleLog.w(t);
                        return;
                    }
                }
                if (!SystemSettings.batteryStrip) return;
                SystemSettings.handler().removeCallbacks(countRunnable);
                SystemSettings.handler().postDelayed(countRunnable, COUNT_DEBOUNCE_MS);
            }
        };
        Class<?> listeners = XposedHelpers.findClass(LISTENERS, classLoader);
        XposedBridge.hookAllMethods(listeners, "notifyPostedLocked", onChange);
        XposedBridge.hookAllMethods(listeners, "notifyRemovedLocked", onChange);
    }

    // Notifications a user would clear: no ongoing/no-clear ones, no group summaries.
    private static void count() {
        Object nms = service;
        if (nms == null) return;
        int count = 0;
        try {
            Object lock = XposedHelpers.getObjectField(nms, "mNotificationLock");
            synchronized (lock) {
                List<?> list = (List<?>) XposedHelpers.getObjectField(nms, "mNotificationList");
                for (Object record : list) {
                    StatusBarNotification sbn =
                            (StatusBarNotification) XposedHelpers.callMethod(record, "getSbn");
                    Notification n = sbn.getNotification();
                    if (sbn.isClearable() && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0) count++;
                }
            }
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
            return;
        }
        StatusLabel.setNotificationCount(count);
        NotificationPanel.onNotificationsChanged();
    }

    /** NotificationManagerService, once the first notification event has been seen. */
    static Object service() {
        return service;
    }

    /** What the banner shows, copied out of the NotificationRecord. */
    private static final class Item {
        String pkg;
        CharSequence title;
        CharSequence text;
        PendingIntent contentIntent;

        static Item from(Object record, int alertFlags) {
            if ((boolean) XposedHelpers.callMethod(record, "isIntercepted")) return null; // DND
            int importance = (int) XposedHelpers.callMethod(record, "getImportance");
            // Alerted (vibrate/sound/light) or important enough for a heads-up.
            if (alertFlags == 0 && importance < NotificationManager.IMPORTANCE_HIGH) return null;
            StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.callMethod(record, "getSbn");
            Notification n = sbn.getNotification();
            if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
            if (BuildConfig.APPLICATION_ID.equals(sbn.getPackageName())) return null;
            Item item = new Item();
            item.pkg = sbn.getPackageName();
            Bundle extras = n.extras;
            item.title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            item.text = big != null ? big : extras.getCharSequence(Notification.EXTRA_TEXT);
            if (TextUtils.isEmpty(item.title) && TextUtils.isEmpty(item.text)) return null;
            item.contentIntent = n.contentIntent;
            return item;
        }
    }

    // --- Window (settings thread only) ---

    private static void show(Item item) {
        if (!SystemSettings.topUnavailable()) return;
        try {
            remove();
            Context system = SystemSettings.context();
            Display display = system.getSystemService(DisplayManager.class)
                    .getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
            if (display == null) return;
            windowManager = system.createDisplayContext(display).getSystemService(WindowManager.class);
            Context ui = system.createPackageContext(BuildConfig.APPLICATION_ID, 0).createDisplayContext(display);

            View banner = build(ui, system, item);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.setTitle("GoDaijishou:Notification");

            float density = ui.getResources().getDisplayMetrics().density;
            banner.setAlpha(0f);
            banner.setTranslationY(-48 * density);
            windowManager.addView(banner, lp);
            view = banner;
            banner.animate().alpha(1f).translationY(0f)
                    .setDuration(SLIDE_MS)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
            SystemSettings.handler().removeCallbacks(hideRunnable);
            SystemSettings.handler().postDelayed(hideRunnable, SHOW_MS);
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
            // Never let the banner disturb system_server.
        }
    }

    private static View build(Context ui, Context system, Item item) {
        float density = ui.getResources().getDisplayMetrics().density;
        LinearLayout card = card(ui, system, item.pkg, item.title, item.text);

        LinearLayout frame = new LinearLayout(ui);
        int margin = Math.round(MARGIN_DP * density);
        frame.setPadding(margin, margin, margin, margin);
        frame.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bindGestures(card, frame, item, ViewConfiguration.get(ui).getScaledTouchSlop());
        return frame;
    }

    /** FT notification card (icon, app name, title, text); shared with NotificationPanel. */
    static LinearLayout card(Context ui, Context system, String pkg, CharSequence title, CharSequence body) {
        return card(ui, system, pkg, title, body, false);
    }

    /** Same card; pinned (non-clearable) ones get a small pin after the app name. */
    static LinearLayout card(Context ui, Context system, String pkg, CharSequence title, CharSequence body,
                             boolean pinned) {
        float density = ui.getResources().getDisplayMetrics().density;
        int pad = Math.round(11 * density);

        LinearLayout card = new LinearLayout(ui);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.getColor(R.color.ft_surface));
        bg.setCornerRadius(16 * density);
        bg.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
        card.setBackground(bg);
        card.setElevation(8 * density);

        ImageView icon = new ImageView(ui);
        int iconSize = Math.round(28 * density);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(Math.round(12 * density));
        icon.setImageDrawable(appIcon(system, pkg));
        card.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(ui);
        texts.setOrientation(LinearLayout.VERTICAL);
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView app = text(ui, appLabel(system, pkg), 11, R.color.ft_secondary, false, 1);
        if (pinned) {
            Drawable pin = ui.getDrawable(R.drawable.ic_ft_pin);
            if (pin != null) {
                pin = pin.mutate();
                pin.setTint(ui.getColor(R.color.ft_secondary)); // quiet: just a marker
                int size = Math.round(11 * density);
                pin.setBounds(0, 0, size, size);
                app.setCompoundDrawablesRelative(null, null, pin, null);
                app.setCompoundDrawablePadding(Math.round(4 * density));
            }
        }
        texts.addView(app, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (!TextUtils.isEmpty(title)) texts.addView(text(ui, title, 14, R.color.ft_ink, true, 1));
        if (!TextUtils.isEmpty(body)) texts.addView(text(ui, body, 13, R.color.ft_secondary, false, 2));
        return card;
    }

    private static TextView text(Context ui, CharSequence value, int sp, int color, boolean bold, int lines) {
        TextView tv = new TextView(ui);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ui.getColor(color));
        tv.setMaxLines(lines);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    // Tap opens the notification on the second screen; dragging up dismisses.
    private static void bindGestures(View card, View frame, Item item, int slop) {
        final float[] downY = new float[1];
        final boolean[] dragging = new boolean[1];
        card.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = e.getRawY();
                    dragging[0] = false;
                    SystemSettings.handler().removeCallbacks(hideRunnable);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - downY[0];
                    if (dy < -slop) dragging[0] = true;
                    if (dragging[0]) frame.setTranslationY(Math.min(0f, dy));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging[0]) {
                        if (frame.getTranslationY() < -frame.getHeight() / 3f) slideOut();
                        else {
                            frame.animate().translationY(0f).setDuration(SLIDE_MS).start();
                            SystemSettings.handler().postDelayed(hideRunnable, SHOW_MS);
                        }
                    } else {
                        open(item);
                        remove();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    frame.animate().translationY(0f).setDuration(SLIDE_MS).start();
                    SystemSettings.handler().postDelayed(hideRunnable, SHOW_MS);
                    return true;
                default:
                    return false;
            }
        });
    }

    private static void open(Item item) {
        if (item.contentIntent == null) return;
        try {
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(MainModule.SECOND_SCREEN_DISPLAY_ID)
                    .toBundle();
            item.contentIntent.send(SystemSettings.context(), 0, null, null, null, null, options);
        } catch (Throwable ignored) {
            ModuleLog.w(ignored);
        }
    }

    private static Drawable appIcon(Context system, String pkg) {
        try {
            return system.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static CharSequence appLabel(Context system, String pkg) {
        try {
            PackageManager pm = system.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static void slideOut() {
        View v = view;
        if (v == null) return;
        v.animate().alpha(0f).translationY(-v.getHeight())
                .setDuration(SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(NotificationBanner::remove)
                .start();
    }

    private static void remove() {
        if (SystemSettings.handler() != null) SystemSettings.handler().removeCallbacks(hideRunnable);
        View v = view;
        if (v == null) return;
        view = null;
        v.animate().cancel();
        try {
            windowManager.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }
}
