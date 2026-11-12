package kr.opencourse.godaijishou;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
    // Panel mode: current notifications, or the channels that are turned off.
    private static boolean showingBlocked = false;
    private static android.widget.ImageView blockedToggle;
    // Undo for the last "never show": a single icon under the list for a few seconds.
    private static FrameLayout undoSlot;
    private static final long UNDO_MS = 4000;
    private static final Runnable hideUndo = () -> {
        if (undoSlot != null) undoSlot.removeAllViews();
    };

    private NotificationPanel() {
    }

    /** One notification as shown in the panel, copied out under the notification lock. */
    private static final class Entry {
        String key;
        String pkg;
        int userId;
        int uid;
        String channelId;
        CharSequence title;
        CharSequence text;
        PendingIntent contentIntent;
        boolean clearable;
        Notification.Action[] actions;
    }

    // Apps with several notifications show as one card until opened.
    private static final java.util.Set<String> expanded = new java.util.HashSet<>();
    private static final int MAX_ACTIONS = 3;

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

            undoSlot = new FrameLayout(ui);
            column.addView(undoSlot, new LinearLayout.LayoutParams(
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
            showingBlocked = false;
            expanded.clear();
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

        // Performance profile: one small round button showing the current one; each tap
        // cycles eco (green leaf) -> normal (grey gauge) -> performance (red bolt).
        android.widget.ImageView profile = new android.widget.ImageView(ui);
        int pp = Math.round(6 * density);
        profile.setPadding(pp, pp, pp, pp);
        styleProfile(profile, density);
        profile.setOnClickListener(v -> {
            int next = (indexOf(SystemSettings.perfProfile) + 1) % PROFILES.length;
            SystemSettings.perfProfile = PROFILES[next][0];
            styleProfile(profile, density);
            PerfProfile.request(SystemSettings.context(), SystemSettings.perfProfile);
        });
        int profileSize = Math.round(30 * density);
        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(profileSize, profileSize);
        profileLp.setMarginEnd(Math.round(8 * density));
        row.addView(profile, profileLp);

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

        int buttonSize = Math.round(30 * density);
        // Turned-off notifications: switches the list to them (and back).
        blockedToggle = iconButton(R.drawable.ic_ft_bell_off, false, density);
        blockedToggle.setOnClickListener(v -> {
            showingBlocked = !showingBlocked;
            styleIconButton(blockedToggle, R.drawable.ic_ft_bell_off, showingBlocked, density);
            fill();
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        toggleLp.setMarginEnd(Math.round(8 * density));
        row.addView(blockedToggle, toggleLp);

        // Close button: always reachable, whatever the list covers.
        android.widget.ImageView close = iconButton(R.drawable.ic_ft_close, false, density);
        close.setOnClickListener(v -> close());
        row.addView(close, new LinearLayout.LayoutParams(buttonSize, buttonSize));

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

    // {profile, icon, color}, in cycling order.
    private static final int[][] PROFILES = {
            {PerfProfile.ECO, R.drawable.ic_ft_leaf, R.color.ft_good},
            {PerfProfile.NORMAL, R.drawable.ic_ft_gauge, R.color.ft_secondary},
            {PerfProfile.PERFORMANCE, R.drawable.ic_ft_bolt, R.color.ft_error},
    };

    private static int indexOf(int profile) {
        for (int i = 0; i < PROFILES.length; i++) if (PROFILES[i][0] == profile) return i;
        return 1; // normal
    }

    // Current profile's icon in its color on the usual outlined circle.
    private static void styleProfile(android.widget.ImageView button, float density) {
        int[] def = PROFILES[indexOf(SystemSettings.perfProfile)];
        int color = ui.getColor(def[2]);
        android.graphics.drawable.Drawable icon = ui.getDrawable(def[1]);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(color);
            button.setImageDrawable(icon);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(ui.getColor(R.color.ft_surface));
        bg.setStroke(Math.max(1, Math.round(density)), color);
        button.setBackground(bg);
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

    // Round icon button: outlined, or filled butter when active.
    private static android.widget.ImageView iconButton(int iconRes, boolean active, float density) {
        android.widget.ImageView button = new android.widget.ImageView(ui);
        int pad = Math.round(6 * density);
        button.setPadding(pad, pad, pad, pad);
        styleIconButton(button, iconRes, active, density);
        return button;
    }

    private static void styleIconButton(android.widget.ImageView button, int iconRes, boolean active, float density) {
        android.graphics.drawable.Drawable icon = ui.getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ui.getColor(active ? R.color.ft_on_butter : R.color.ft_ink));
            button.setImageDrawable(icon);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (active) {
            bg.setColor(ui.getColor(R.color.ft_butter));
        } else {
            bg.setColor(ui.getColor(R.color.ft_surface));
            bg.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
        }
        button.setBackground(bg);
    }

    // Rebuilds the list for the current mode.
    private static void fill() {
        if (list == null) return;
        list.removeAllViews();
        if (showingBlocked) {
            fillBlocked();
            return;
        }
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
        // Group by app, keeping newest-first order (the first entry of a group is its newest).
        java.util.LinkedHashMap<String, List<Entry>> groups = new java.util.LinkedHashMap<>();
        for (Entry e : entries) {
            groups.computeIfAbsent(e.pkg + "|" + e.userId, k -> new ArrayList<>()).add(e);
        }
        for (java.util.Map.Entry<String, List<Entry>> g : groups.entrySet()) {
            List<Entry> members = g.getValue();
            if (members.size() == 1) {
                addCard(members.get(0), members, 0, null, false, gap, slop, density);
            } else if (expanded.contains(g.getKey())) {
                for (int i = 0; i < members.size(); i++) {
                    Entry e = members.get(i);
                    addCard(e, java.util.Collections.singletonList(e),
                            i == 0 ? members.size() : 0, g.getKey(), true, gap, slop, density);
                }
            } else {
                // Collapsed: the newest card stands for the app; swipes act on all of them.
                addCard(members.get(0), members, members.size(), g.getKey(), false, gap, slop, density);
            }
        }
    }

    // One card in its swipe slot. count > 0 adds the group's count pill (tap: fold/unfold).
    private static void addCard(Entry shown, List<Entry> targets, int count, String groupKey,
                                boolean open, int gap, int slop, float density) {
        boolean pinned = true;
        for (Entry t : targets) pinned &= !t.clearable;
        LinearLayout card = NotificationBanner.card(ui, SystemSettings.context(), shown.pkg, shown.title,
                shown.text, pinned);
        card.setElevation(0);
        if (targets.size() == 1) addActions(card, shown, density);
        if (count > 0) {
            TextView pill = countPill(count, open, density);
            pill.setOnClickListener(v -> {
                if (!expanded.remove(groupKey)) expanded.add(groupKey);
                fill();
            });
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(26 * density));
            pillLp.setMarginStart(Math.round(10 * density));
            card.addView(pill, pillLp);
        }
        // Behind the card: what the current swipe direction will do.
        FrameLayout slot = new FrameLayout(ui);
        android.widget.ImageView hint = new android.widget.ImageView(ui);
        int hp = Math.round(20 * density);
        hint.setPadding(hp, 0, hp, 0);
        hint.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        hint.setVisibility(View.INVISIBLE);
        slot.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        slot.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = gap;
        list.addView(slot, lp);
        bindGestures(card, hint, shown, targets, slop, density);
    }

    // Count of a folded app: filled butter when folded, outlined once unfolded.
    private static TextView countPill(int count, boolean open, float density) {
        TextView pill = new TextView(ui);
        pill.setText(String.valueOf(Math.min(count, 99)));
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(Math.round(26 * density));
        int p = Math.round(8 * density);
        pill.setPadding(p, 0, p, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(13 * density);
        if (open) {
            bg.setColor(ui.getColor(R.color.ft_surface));
            bg.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
            pill.setTextColor(ui.getColor(R.color.ft_secondary));
        } else {
            bg.setColor(ui.getColor(R.color.ft_butter));
            pill.setTextColor(ui.getColor(R.color.ft_on_butter));
        }
        pill.setBackground(bg);
        return pill;
    }

    // The notification's own buttons (mark read, play/pause...). Ones that need typing
    // (reply) are left out: the panel has no keyboard.
    private static void addActions(LinearLayout card, Entry e, float density) {
        if (e.actions == null || e.actions.length == 0) return;
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int shown = 0;
        for (int i = 0; i < e.actions.length && shown < MAX_ACTIONS; i++) {
            Notification.Action action = e.actions[i];
            if (action == null || action.actionIntent == null || TextUtils.isEmpty(action.title)) continue;
            if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) continue;
            TextView chip = new TextView(ui);
            chip.setText(action.title);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(ui.getColor(R.color.ft_ink));
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER);
            int ph = Math.round(10 * density);
            chip.setPadding(ph, 0, ph, 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(13 * density);
            bg.setColor(ui.getColor(R.color.ft_surface));
            bg.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
            chip.setBackground(bg);
            int index = i;
            chip.setOnClickListener(v -> runAction(e, index, action));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(28 * density), 1f);
            if (shown > 0) lp.setMarginStart(Math.round(6 * density));
            row.addView(chip, lp);
            shown++;
        }
        if (shown == 0) return;
        LinearLayout texts = (LinearLayout) card.getChildAt(1);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(8 * density);
        texts.addView(row, rowLp);
    }

    // Fires the button like the shade does; screens it opens go to the second screen.
    private static void runAction(Entry e, int index, Notification.Action action) {
        boolean opensScreen = false;
        try {
            opensScreen = action.actionIntent.isActivity();
            Bundle options = ActivityOptions.makeBasic()
                    .setLaunchDisplayId(MainModule.SECOND_SCREEN_DISPLAY_ID)
                    .toBundle();
            action.actionIntent.send(SystemSettings.context(), 0, null, null, null, null, options);
            Object d = delegate();
            if (d != null) {
                XposedHelpers.callMethod(d, "onNotificationActionClick", Process.SYSTEM_UID, Process.myPid(),
                        e.key, index, action, visibility(e.key), false);
            }
        } catch (Throwable ignored) {
        }
        if (opensScreen) close();
    }

    // Tap opens. Swipe right: dismiss (clearable only). Swipe left: never show this kind
    // again (turns the notification's channel off) and dismiss. Past a third of the width
    // the action runs; the hint behind the card shows which one.
    private static void bindGestures(View card, android.widget.ImageView hint, Entry e, List<Entry> targets,
                                     int slop, float density) {
        final float[] downX = new float[1];
        final boolean[] dragging = new boolean[1];
        card.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getRawX();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = allowed(targets, ev.getRawX() - downX[0]);
                    if (Math.abs(dx) > slop) {
                        dragging[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        v.setTranslationX(dx);
                        showHint(hint, dx, density);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging[0]) {
                        float moved = v.getTranslationX();
                        if (Math.abs(moved) > v.getWidth() / 3f) {
                            boolean block = moved < 0;
                            v.animate().translationX(Math.signum(moved) * v.getWidth()).alpha(0f)
                                    .setDuration(ANIM_MS)
                                    .withEndAction(() -> {
                                        if (block) block(targets);
                                        for (Entry t : targets) dismiss(t);
                                    }).start();
                        } else {
                            v.animate().translationX(0f).setDuration(ANIM_MS)
                                    .withEndAction(() -> hint.setVisibility(View.INVISIBLE)).start();
                        }
                    } else {
                        click(e);
                        close();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().translationX(0f).setDuration(ANIM_MS)
                            .withEndAction(() -> hint.setVisibility(View.INVISIBLE)).start();
                    return true;
                default:
                    return false;
            }
        });
    }

    // Pinned notifications can't be dismissed, but can still be turned off (swipe left).
    private static float allowed(List<Entry> targets, float dx) {
        if (dx <= 0) return dx;
        for (Entry t : targets) if (t.clearable) return dx;
        return 0;
    }

    // Icon and color only: grey trash for dismiss (right), red bell-off for never show (left).
    private static void showHint(android.widget.ImageView hint, float dx, float density) {
        if (dx == 0) {
            hint.setVisibility(View.INVISIBLE);
            ((View) hint.getParent()).setBackground(null);
            return;
        }
        boolean block = dx < 0;
        hint.setVisibility(View.VISIBLE);
        android.graphics.drawable.Drawable icon = ui.getDrawable(block ? R.drawable.ic_ft_bell_off : R.drawable.ic_ft_trash);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ui.getColor(block ? R.color.ft_error : R.color.ft_secondary));
            int size = Math.round(22 * density);
            icon.setBounds(0, 0, size, size);
            hint.setImageDrawable(icon);
        }
        // Icon sits on the side the card uncovers.
        hint.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hint.getLayoutParams();
        lp.gravity = (block ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        hint.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * density);
        bg.setColor(ui.getColor(block ? R.color.ft_red_tint : R.color.ft_surface));
        ((View) hint.getParent()).setBackground(bg);
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
                    e.uid = sbn.getUid();
                    e.channelId = n.getChannelId();
                    e.title = title;
                    e.text = text;
                    e.contentIntent = n.contentIntent;
                    e.clearable = sbn.isClearable();
                    e.actions = n.actions;
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

    // Turns off the notification's channel ("never show this kind again"), like long-press
    // > turn off in the shade. Goes through the service binder; system_server may call it.
    private static void block(List<Entry> targets) {
        List<Runnable> restores = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Entry e : targets) {
            if (e.channelId == null || !done.add(e.pkg + "|" + e.uid + "|" + e.channelId)) continue;
            try {
                Object binder = binder();
                NotificationChannel channel = (NotificationChannel) XposedHelpers.callMethod(binder,
                        "getNotificationChannelForPackage", e.pkg, e.uid, e.channelId, null, false);
                if (channel == null) continue;
                int previous = channel.getImportance();
                channel.setImportance(NotificationManager.IMPORTANCE_NONE);
                XposedHelpers.callMethod(binder, "updateNotificationChannelForPackage", e.pkg, e.uid, channel);
                restores.add(() -> unblock(e.pkg, e.uid, e.channelId, previous));
            } catch (Throwable ignored) {
            }
        }
        if (!restores.isEmpty()) showUndo(restores);
    }

    private static Object binder() {
        return XposedHelpers.getObjectField(NotificationBanner.service(), "mService");
    }

    /** Turns a channel back on, at the given importance (or the app's own default). */
    private static void unblock(String pkg, int uid, String channelId, int importance) {
        try {
            Object binder = binder();
            NotificationChannel channel = (NotificationChannel) XposedHelpers.callMethod(binder,
                    "getNotificationChannelForPackage", pkg, uid, channelId, null, false);
            if (channel == null) return;
            if (importance <= NotificationManager.IMPORTANCE_NONE) importance = originalImportance(channel);
            channel.setImportance(importance);
            XposedHelpers.callMethod(binder, "updateNotificationChannelForPackage", pkg, uid, channel);
        } catch (Throwable ignored) {
        }
    }

    // What the app itself asked for when it created the channel; default if unknown.
    private static int originalImportance(NotificationChannel channel) {
        try {
            int original = (int) XposedHelpers.callMethod(channel, "getOriginalImportance");
            if (original > NotificationManager.IMPORTANCE_NONE) return original;
        } catch (Throwable ignored) {
        }
        return NotificationManager.IMPORTANCE_DEFAULT;
    }

    // One round butter undo icon under the list for a few seconds.
    private static void showUndo(List<Runnable> restores) {
        if (undoSlot == null) return;
        float density = ui.getResources().getDisplayMetrics().density;
        undoSlot.removeAllViews();
        android.widget.ImageView undo = iconButton(R.drawable.ic_ft_undo, true, density);
        int size = Math.round(36 * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        lp.topMargin = Math.round(4 * density);
        undoSlot.addView(undo, lp);
        undo.setOnClickListener(v -> {
            for (Runnable r : restores) r.run();
            undoSlot.removeAllViews();
        });
        SystemSettings.handler().removeCallbacks(hideUndo);
        SystemSettings.handler().postDelayed(hideUndo, UNDO_MS);
    }

    // --- Turned-off channels ---

    private static final class Blocked {
        String pkg;
        int uid;
        String channelId;
        CharSequence name;
    }

    // Every channel at importance NONE, across installed apps. Cheap per-app count first,
    // channel list only for apps that have blocked ones.
    private static List<Blocked> blockedChannels() {
        List<Blocked> out = new ArrayList<>();
        try {
            Object binder = binder();
            android.content.pm.PackageManager pm = SystemSettings.context().getPackageManager();
            for (android.content.pm.ApplicationInfo app : pm.getInstalledApplications(0)) {
                try {
                    int count = (int) XposedHelpers.callMethod(binder, "getBlockedChannelCount", app.packageName, app.uid);
                    if (count <= 0) continue;
                    Object slice = XposedHelpers.callMethod(binder, "getNotificationChannelsForPackage",
                            app.packageName, app.uid, false);
                    List<?> channels = (List<?>) XposedHelpers.callMethod(slice, "getList");
                    for (Object o : channels) {
                        NotificationChannel c = (NotificationChannel) o;
                        if (c.getImportance() != NotificationManager.IMPORTANCE_NONE) continue;
                        Blocked b = new Blocked();
                        b.pkg = app.packageName;
                        b.uid = app.uid;
                        b.channelId = c.getId();
                        b.name = c.getName();
                        out.add(b);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // Rows: app card with the channel name; bell button on the right turns it back on.
    private static void fillBlocked() {
        float density = ui.getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        List<Blocked> blocked = blockedChannels();
        if (blocked.isEmpty()) {
            android.widget.ImageView empty = new android.widget.ImageView(ui);
            android.graphics.drawable.Drawable icon = ui.getDrawable(R.drawable.ic_ft_bell_off);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(ui.getColor(R.color.ft_line));
                empty.setImageDrawable(icon);
            }
            int p = Math.round(24 * density);
            empty.setPadding(p, p, p, p);
            list.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(84 * density)));
            return;
        }
        for (Blocked b : blocked) {
            LinearLayout card = NotificationBanner.card(ui, SystemSettings.context(), b.pkg, b.name, null);
            card.setElevation(0);
            android.widget.ImageView on = iconButton(R.drawable.ic_ft_bell, true, density);
            int size = Math.round(32 * density);
            LinearLayout.LayoutParams onLp = new LinearLayout.LayoutParams(size, size);
            onLp.setMarginStart(Math.round(10 * density));
            card.addView(on, onLp);
            on.setOnClickListener(v -> {
                unblock(b.pkg, b.uid, b.channelId, NotificationManager.IMPORTANCE_NONE);
                fill();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            list.addView(card, lp);
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
