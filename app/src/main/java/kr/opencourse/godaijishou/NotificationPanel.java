package kr.opencourse.godaijishou;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * Notification list on the second screen, opened by tapping the status label. Runs inside
 * system_server: the shade is SystemUI's and lives on the top display, which may be off.
 *
 * Tap a card to open it on the second screen, swipe it sideways to dismiss, "Clear all"
 * clears every clearable one. Dismissals go through NotificationDelegate, the same path
 * SystemUI uses, so apps see a normal user dismissal. Settings thread only.
 */
final class NotificationPanel {

    // WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY (hidden): above apps and system bars.
    private static final int TYPE_OVERLAY = 2020;
    private static final int SCRIM = 0x99000000;
    private static final long ANIM_MS = 180;
    // The list never takes more than this share of the screen, so there is always empty
    // scrim below it to tap and close.
    private static final float MAX_HEIGHT_RATIO = 0.7f;
    // NotificationStats.DISMISSAL_SHADE / DISMISS_SENTIMENT_NEUTRAL.
    private static final int DISMISSAL_SHADE = 3;
    private static final int SENTIMENT_NEUTRAL = 1;
    private static final String VISIBILITY = "com.android.internal.statusbar.NotificationVisibility";

    private static WindowManager windowManager;
    private static FrameLayout root;
    private static LinearLayout list;
    private static Context ui;

    private NotificationPanel() {
    }

    /** One notification as shown in the panel, copied out under the notification lock. */
    private static final class Entry {
        String key;
        String pkg;
        int userId;
        CharSequence title;
        CharSequence text;
        PendingIntent contentIntent;
        boolean clearable;
    }

    static void toggle() {
        if (root != null) close();
        else open();
    }

    /** From NotificationBanner after posts/removals; rebuilds the list while open. */
    static void onNotificationsChanged() {
        if (root != null) fill();
    }

    private static void open() {
        try {
            Context system = SystemSettings.context();
            Display display = system.getSystemService(DisplayManager.class)
                    .getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
            if (display == null) return;
            windowManager = system.createDisplayContext(display).getSystemService(WindowManager.class);
            ui = system.createPackageContext(BuildConfig.APPLICATION_ID, 0).createDisplayContext(display);
            float density = ui.getResources().getDisplayMetrics().density;
            int margin = Math.round(10 * density);

            // Scrim: tap outside the list or press back to close.
            FrameLayout scrim = new FrameLayout(ui);
            scrim.setBackgroundColor(SCRIM);
            scrim.setOnClickListener(v -> close());
            // Swipe up anywhere outside the list closes, like pushing a shade away.
            final float[] scrimDownY = new float[1];
            scrim.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) scrimDownY[0] = ev.getRawY();
                if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        && scrimDownY[0] - ev.getRawY() > 24 * density) {
                    close();
                    return true;
                }
                return false; // keep the tap-to-close click
            });
            scrim.setFocusableInTouchMode(true);
            scrim.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    close();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_BACK;
            });

            LinearLayout column = new LinearLayout(ui);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(margin, margin, margin, margin);
            column.setClickable(true); // taps between cards don't close
            column.addView(header(density));

            final int maxHeight = Math.round(ui.getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_RATIO);
            ScrollView scroll = new ScrollView(ui) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
                }
            };
            scroll.setVerticalScrollBarEnabled(false);
            list = new LinearLayout(ui);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list);
            // Wraps its cards, capped by the screen height (then it scrolls).
            column.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            column.addView(handle(density));

            FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            scrim.addView(column, columnLp);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    TYPE_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("GoDaijishou:NotificationPanel");

            root = scrim;
            fill();
            scrim.setAlpha(0f);
            column.setTranslationY(-24 * density);
            windowManager.addView(scrim, lp);
            scrim.requestFocus();
            scrim.animate().alpha(1f).setDuration(ANIM_MS).start();
            column.animate().translationY(0f).setDuration(ANIM_MS)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        } catch (Throwable ignored) {
            root = null;
        }
    }

    // Grab bar under the list: swipe it up (or tap it) to close.
    private static View handle(float density) {
        FrameLayout area = new FrameLayout(ui);
        int h = Math.round(28 * density);
        View bar = new View(ui);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.getColor(R.color.ft_secondary));
        bg.setCornerRadius(100 * density);
        bar.setBackground(bg);
        bar.setAlpha(0.6f);
        area.addView(bar, new FrameLayout.LayoutParams(
                Math.round(40 * density), Math.round(4 * density), Gravity.CENTER));
        final float[] downY = new float[1];
        area.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) downY[0] = ev.getRawY();
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) close(); // swipe up or tap
            return true;
        });
        area.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        return area;
    }

    private static View header(float density) {
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(6 * density);
        row.setPadding(pad, 0, pad, pad);

        TextView title = new TextView(ui);
        title.setText(R.string.notifications_title);
        title.setTextColor(ui.getColor(R.color.ft_ink));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        withIcon(title, R.drawable.ic_ft_bell, ui.getColor(R.color.ft_butter), 18, density);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Battery saver toggle: the quick settings live on the top display, which may be off.
        TextView saver = chip(density);
        saver.setText(R.string.notifications_battery_saver);
        styleSaver(saver, density); // also sets its icon in the matching color
        saver.setOnClickListener(x -> {
            try {
                PowerManager pm = SystemSettings.context().getSystemService(PowerManager.class);
                // System API (hidden from the SDK); system_server holds DEVICE_POWER.
                XposedHelpers.callMethod(pm, "setPowerSaveModeEnabled", !pm.isPowerSaveMode());
            } catch (Throwable ignored) {
            }
            styleSaver(saver, density);
        });
        LinearLayout.LayoutParams saverLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saverLp.setMarginEnd(Math.round(8 * density));
        row.addView(saver, saverLp);

        TextView clear = chip(density);
        clear.setText(R.string.notifications_clear_all);
        clear.setTextColor(ui.getColor(R.color.ft_on_butter));
        withIcon(clear, R.drawable.ic_ft_clear_all, ui.getColor(R.color.ft_on_butter), 15, density);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.getColor(R.color.ft_butter));
        bg.setCornerRadius(100 * density);
        clear.setBackground(bg);
        clear.setOnClickListener(x -> {
            clearAll();
            close();
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearLp.setMarginEnd(Math.round(8 * density));
        row.addView(clear, clearLp);

        // Close button: always reachable, whatever the list covers.
        android.widget.ImageView close = new android.widget.ImageView(ui);
        android.graphics.drawable.Drawable x = ui.getDrawable(R.drawable.ic_ft_close);
        if (x != null) {
            x = x.mutate();
            x.setTint(ui.getColor(R.color.ft_ink));
            close.setImageDrawable(x);
        }
        int closeSize = Math.round(30 * density);
        int closePad = Math.round(6 * density);
        close.setPadding(closePad, closePad, closePad, closePad);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(ui.getColor(R.color.ft_surface));
        closeBg.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
        close.setBackground(closeBg);
        close.setOnClickListener(v -> close());
        row.addView(close, new LinearLayout.LayoutParams(closeSize, closeSize));

        // Swiping the header up also closes.
        final float[] downY = new float[1];
        row.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY[0] = ev.getRawY();
                return true;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    && downY[0] - ev.getRawY() > 24 * density) {
                close();
            }
            return true;
        });
        return row;
    }

    private static TextView chip(float density) {
        TextView chip = new TextView(ui);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        int h = Math.round(12 * density), v = Math.round(6 * density);
        chip.setPadding(h, v, h, v);
        return chip;
    }

    // On: filled butter like an active toggle. Off: outlined.
    private static void styleSaver(TextView chip, float density) {
        boolean on = false;
        try {
            on = SystemSettings.context().getSystemService(PowerManager.class).isPowerSaveMode();
        } catch (Throwable ignored) {
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(100 * density);
        int ink;
        if (on) {
            bg.setColor(ui.getColor(R.color.ft_butter));
            ink = ui.getColor(R.color.ft_on_butter);
        } else {
            bg.setColor(ui.getColor(R.color.ft_surface));
            bg.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
            ink = ui.getColor(R.color.ft_ink);
        }
        chip.setTextColor(ink);
        withIcon(chip, R.drawable.ic_ft_battery_saver, ink, 15, density);
        chip.setBackground(bg);
    }

    // Leading icon in the text's color.
    private static void withIcon(TextView view, int iconRes, int color, int sizeDp, float density) {
        android.graphics.drawable.Drawable d = ui.getDrawable(iconRes);
        if (d == null) return;
        d = d.mutate();
        d.setTint(color);
        int size = Math.round(sizeDp * density);
        d.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(d, null, null, null);
        view.setCompoundDrawablePadding(Math.round(5 * density));
        view.setGravity(Gravity.CENTER_VERTICAL);
    }

    // Rebuilds the cards from the current notification list.
    private static void fill() {
        if (list == null) return;
        list.removeAllViews();
        float density = ui.getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        List<Entry> entries = snapshot();
        if (entries.isEmpty()) {
            TextView empty = new TextView(ui);
            empty.setText(R.string.notifications_empty);
            empty.setTextColor(ui.getColor(R.color.ft_secondary));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setGravity(Gravity.CENTER);
            android.graphics.drawable.Drawable icon = ui.getDrawable(R.drawable.ic_ft_bell);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(ui.getColor(R.color.ft_line));
                int size = Math.round(36 * density);
                icon.setBounds(0, 0, size, size);
                empty.setCompoundDrawablesRelative(null, icon, null, null);
                empty.setCompoundDrawablePadding(Math.round(10 * density));
            }
            int p = Math.round(24 * density);
            empty.setPadding(p, p, p, p);
            list.addView(empty);
            return;
        }
        int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
        for (Entry e : entries) {
            LinearLayout card = NotificationBanner.card(ui, SystemSettings.context(), e.pkg, e.title, e.text);
            card.setElevation(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            list.addView(card, lp);
            bindGestures(card, e, slop);
        }
    }

    // Tap opens; horizontal swipe past a third of the width dismisses (clearable only).
    private static void bindGestures(View card, Entry e, int slop) {
        final float[] downX = new float[1];
        final boolean[] dragging = new boolean[1];
        card.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getRawX();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downX[0];
                    if (e.clearable && Math.abs(dx) > slop) {
                        dragging[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        v.setTranslationX(dx);
                        v.setAlpha(1f - Math.min(1f, Math.abs(dx) / v.getWidth()));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging[0]) {
                        float moved = v.getTranslationX();
                        if (Math.abs(moved) > v.getWidth() / 3f) {
                            v.animate().translationX(Math.signum(moved) * v.getWidth()).alpha(0f)
                                    .setDuration(ANIM_MS).withEndAction(() -> dismiss(e)).start();
                        } else {
                            v.animate().translationX(0f).alpha(1f).setDuration(ANIM_MS).start();
                        }
                    } else {
                        click(e);
                        close();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().translationX(0f).alpha(1f).setDuration(ANIM_MS).start();
                    return true;
                default:
                    return false;
            }
        });
    }

    static void close() {
        FrameLayout v = root;
        if (v == null) return;
        root = null;
        list = null;
        try {
            windowManager.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    // --- NotificationManagerService access ---

    private static List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>();
        Object nms = NotificationBanner.service();
        if (nms == null) return out;
        try {
            Object lock = XposedHelpers.getObjectField(nms, "mNotificationLock");
            synchronized (lock) {
                List<?> records = (List<?>) XposedHelpers.getObjectField(nms, "mNotificationList");
                for (Object record : records) {
                    StatusBarNotification sbn =
                            (StatusBarNotification) XposedHelpers.callMethod(record, "getSbn");
                    Notification n = sbn.getNotification();
                    if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) continue;
                    Entry e = new Entry();
                    e.key = sbn.getKey();
                    e.pkg = sbn.getPackageName();
                    e.userId = sbn.getUserId();
                    e.title = title;
                    e.text = text;
                    e.contentIntent = n.contentIntent;
                    e.clearable = sbn.isClearable();
                    out.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        // Newest first, like the shade.
        Collections.reverse(out);
        return out;
    }

    private static Object delegate() {
        Object nms = NotificationBanner.service();
        return nms == null ? null : XposedHelpers.getObjectField(nms, "mNotificationDelegate");
    }

    private static Object visibility(String key) throws ClassNotFoundException {
        Class<?> cls = Class.forName(VISIBILITY);
        return XposedHelpers.callStaticMethod(cls, "obtain", key, 0, 1, true);
    }

    // Opens on the second screen and reports the click (handles auto-cancel like the shade).
    private static void click(Entry e) {
        try {
            if (e.contentIntent != null) {
                Bundle options = ActivityOptions.makeBasic()
                        .setLaunchDisplayId(MainModule.SECOND_SCREEN_DISPLAY_ID)
                        .toBundle();
                e.contentIntent.send(SystemSettings.context(), 0, null, null, null, null, options);
            }
            Object d = delegate();
            if (d != null) {
                XposedHelpers.callMethod(d, "onNotificationClick",
                        Process.SYSTEM_UID, Process.myPid(), e.key, visibility(e.key));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void dismiss(Entry e) {
        try {
            Object d = delegate();
            if (d != null) {
                XposedHelpers.callMethod(d, "onNotificationClear",
                        Process.SYSTEM_UID, Process.myPid(), e.pkg, e.userId, e.key,
                        DISMISSAL_SHADE, SENTIMENT_NEUTRAL, visibility(e.key));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearAll() {
        try {
            Object d = delegate();
            int user = (int) XposedHelpers.callStaticMethod(
                    android.app.ActivityManager.class, "getCurrentUser");
            if (d != null) XposedHelpers.callMethod(d, "onClearAll", Process.SYSTEM_UID, Process.myPid(), user);
        } catch (Throwable ignored) {
        }
    }
}
