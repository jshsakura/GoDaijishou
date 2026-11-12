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
    // Cards whose button bar is pulled open (by notification key); closed by default.
    private static final java.util.Set<String> actionsOpen = new java.util.HashSet<>();
    private static ScrollView scrollView;
    private static LinearLayout panelColumn;
    // Per card: what a tap on its right end does (fold the group / open the buttons).
    private static final java.util.Map<View, Runnable> rightTap = new java.util.WeakHashMap<>();

    static boolean isOpen() {
        return root != null;
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
            // A tap on empty space (below the cards, the bottom strip) closes, like outside.
            column.setOnClickListener(v -> close());

            ScrollView scroll = new ScrollView(ui);
            scroll.setVerticalScrollBarEnabled(false);
            // Cards scroll up under the header band and disappear behind it.
            scroll.setClipToPadding(false);
            scrollView = scroll;
            panelColumn = column;
            list = new LinearLayout(ui);
            list.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(list);
            // The list fills the screen: a tap on the empty space under the cards closes
            // (the ScrollView used to swallow it).
            scroll.setFillViewport(true);
            list.setOnClickListener(v -> close());
            // Wraps its cards, capped by the screen height (then it scrolls); the header sits
            // on top of it and the list starts below the header.
            FrameLayout stack = new FrameLayout(ui);
            stack.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View head = header(density);
            stack.addView(head, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            int headerSpace = Math.round((32 + 8 + 8) * density); // band + gap
            scroll.setPadding(0, headerSpace, 0, 0);
            // Undo floats over the bottom of the list for a few seconds.
            undoSlot = new FrameLayout(ui);
            FrameLayout.LayoutParams undoLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            undoLp.bottomMargin = Math.round(8 * density);
            stack.addView(undoSlot, undoLp);
            // The panel takes the whole screen; the list scrolls inside it.
            column.addView(stack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Keep clear of the second screen's navigation bar (and anything at the top).
            scrim.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                // The bottom edge is the system's home gesture: a swipe starting there goes to
                // the app behind / home, never to us. Keep the list out of it.
                android.graphics.Insets gestures = insets.getInsets(
                        android.view.WindowInsets.Type.mandatorySystemGestures());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, gestures.bottom));
                return insets;
            });
            FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP);
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
            actionsOpen.clear();
            fill();
            scrim.setAlpha(0f);
            column.setTranslationY(-48 * density);
            column.setAlpha(0f);
            windowManager.addView(scrim, lp);
            scrim.requestFocus();
            scrim.animate().alpha(1f).setDuration(ANIM_MS).start();
            column.animate().translationY(0f).alpha(1f).setDuration(260)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        } catch (Throwable ignored) {
            root = null;
        }
    }

    // Header floating over the list: [bell Notifications]  [clear all]  [profile | blocked | close].
    // Each group is its own pill on the panel color, so cards scrolling up slide under them.
    private static View header(float density) {
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pillHeight = Math.round(32 * density);
        // Solid band behind the pills: cards passing under it are hidden, never seen through.
        GradientDrawable band = new GradientDrawable();
        band.setColor(ui.getColor(R.color.ft_ground));
        band.setCornerRadius(18 * density);
        row.setBackground(band);
        int bandPad = Math.round(4 * density);
        row.setPadding(bandPad, bandPad, bandPad, bandPad);

        TextView title = new TextView(ui);
        title.setText(R.string.notifications_title);
        title.setTextColor(ui.getColor(R.color.ft_ink));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        withIcon(title, R.drawable.ic_ft_bell, ui.getColor(R.color.ft_butter), 15, density);
        int tp = Math.round(12 * density);
        title.setPadding(tp, 0, tp, 0);
        title.setBackground(pill(density, false));
        row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillHeight));

        // Clock beside the title: big, tabular digits, follows the 12/24h setting.
        android.widget.TextClock clock = new android.widget.TextClock(ui);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(ui.getColor(R.color.ft_ink));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        clock.setFontFeatureSettings("tnum");
        clock.setLetterSpacing(0.02f);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, pillHeight);
        clockLp.setMarginStart(Math.round(10 * density));
        row.addView(clock, clockLp);

        row.addView(new View(ui), new LinearLayout.LayoutParams(0, 1, 1f));

        clearButton = chip(density);
        clearButton.setText(R.string.notifications_clear_all);
        clearButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clearButton.setPadding(tp, 0, tp, 0);
        clearButton.setOnClickListener(x -> {
            clearAll();
            close();
        });
        styleClear(false, density);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillHeight);
        clearLp.setMarginEnd(Math.round(6 * density));
        row.addView(clearButton, clearLp);

        // Tool pill: small icon buttons side by side.
        LinearLayout tools = new LinearLayout(ui);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        int inset = Math.round(3 * density);
        tools.setPadding(inset, 0, inset, 0);
        tools.setBackground(pill(density, false));
        int buttonSize = pillHeight - inset * 2;

        // Performance profile: shows the current one; each tap cycles
        // eco (green leaf) -> normal (grey gauge) -> turbo (red bolt).
        android.widget.ImageView profile = toolButton(density);
        styleProfile(profile, density);
        profile.setOnClickListener(v -> {
            int next = (indexOf(SystemSettings.perfProfile) + 1) % PROFILES.length;
            SystemSettings.perfProfile = PROFILES[next][0];
            styleProfile(profile, density);
            PerfProfile.request(SystemSettings.context(), SystemSettings.perfProfile);
        });
        tools.addView(profile, new LinearLayout.LayoutParams(buttonSize, buttonSize));

        // Turned-off notifications: switches the list to them (and back).
        blockedToggle = toolButton(density);
        styleTool(blockedToggle, R.drawable.ic_ft_bell_off, false, density);
        blockedToggle.setOnClickListener(v -> {
            showingBlocked = !showingBlocked;
            styleTool(blockedToggle, R.drawable.ic_ft_bell_off, showingBlocked, density);
            fill();
        });
        tools.addView(blockedToggle, new LinearLayout.LayoutParams(buttonSize, buttonSize));

        // Close: always reachable, whatever the list covers.
        android.widget.ImageView close = toolButton(density);
        styleTool(close, R.drawable.ic_ft_close, false, density);
        close.setOnClickListener(v -> close());
        tools.addView(close, new LinearLayout.LayoutParams(buttonSize, buttonSize));
        row.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillHeight));

        // The header drags the panel up too, like the handle.
        row.setOnTouchListener(dragToClose(density, false));
        return row;
    }

    private static TextView clearButton;

    // Rounded pill on the panel color with a hairline; filled butter when active.
    private static GradientDrawable pill(float density, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(100 * density);
        if (active) {
            bg.setColor(ui.getColor(R.color.ft_butter));
        } else {
            bg.setColor(ui.getColor(R.color.ft_surface));
            bg.setStroke(Math.max(1, Math.round(density)), ui.getColor(R.color.ft_line));
        }
        return bg;
    }

    // Clear all: butter only when something can be cleared, otherwise muted and inert.
    private static void styleClear(boolean clearable, float density) {
        if (clearButton == null) return;
        int ink = ui.getColor(clearable ? R.color.ft_on_butter : R.color.ft_secondary);
        clearButton.setTextColor(ink);
        withIcon(clearButton, R.drawable.ic_ft_clear_all, ink, 14, density);
        clearButton.setBackground(pill(density, clearable));
        clearButton.setEnabled(clearable);
        clearButton.setAlpha(clearable ? 1f : 0.6f);
    }

    private static android.widget.ImageView toolButton(float density) {
        android.widget.ImageView b = new android.widget.ImageView(ui);
        int p = Math.round(5 * density);
        b.setPadding(p, p, p, p);
        return b;
    }

    // Tool icon: ink on the pill, or ink on a small butter circle when active.
    private static void styleTool(android.widget.ImageView button, int iconRes, boolean active, float density) {
        android.graphics.drawable.Drawable icon = ui.getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ui.getColor(active ? R.color.ft_on_butter : R.color.ft_ink));
            button.setImageDrawable(icon);
        }
        if (active) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(ui.getColor(R.color.ft_butter));
            button.setBackground(bg);
        } else {
            button.setBackground(null);
        }
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

    // Current profile's icon in its own color, on the tool pill.
    private static void styleProfile(android.widget.ImageView button, float density) {
        int[] def = PROFILES[indexOf(SystemSettings.perfProfile)];
        android.graphics.drawable.Drawable icon = ui.getDrawable(def[1]);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ui.getColor(def[2]));
            button.setImageDrawable(icon);
        }
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

    // One smooth motion for the list: cards move, grow and fade at the same time
    // (the default transition fades out, then moves, then fades in — it looks jerky).
    private static void smoothly() {
        if (list == null) return;
        android.transition.TransitionSet set = new android.transition.TransitionSet()
                .setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER)
                .addTransition(new android.transition.ChangeBounds())
                .addTransition(new android.transition.Fade())
                .setDuration(260)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
        ViewGroup scene = scrollView != null ? scrollView : list;
        android.transition.TransitionManager.beginDelayedTransition(scene, set);
    }

    // The header drags the panel up: it follows the finger; past a quarter (or a flick)
    // it goes, otherwise it springs back.
    private static View.OnTouchListener dragToClose(float density, boolean tapCloses) {
        final float[] downY = new float[1];
        final boolean[] dragging = new boolean[1];
        final android.view.VelocityTracker[] tracker = new android.view.VelocityTracker[1];
        final int slop = android.view.ViewConfiguration.get(ui).getScaledTouchSlop();
        return (v, ev) -> {
            LinearLayout column = panelColumn;
            View scrim = root;
            if (column == null || scrim == null) return true;
            if (tracker[0] == null) tracker[0] = android.view.VelocityTracker.obtain();
            tracker[0].addMovement(ev);
            float dy = Math.min(0f, ev.getRawY() - downY[0]);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = ev.getRawY();
                    dragging[0] = false;
                    column.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging[0] && -dy > slop) dragging[0] = true;
                    if (dragging[0]) {
                        column.setTranslationY(dy);
                        float h = Math.max(1, column.getHeight());
                        scrim.setAlpha(Math.max(0f, 1f + dy / h));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    tracker[0].computeCurrentVelocity(1000);
                    float vy = tracker[0].getYVelocity();
                    tracker[0].recycle();
                    tracker[0] = null;
                    boolean cancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                    if (!dragging[0]) {
                        if (!cancel && tapCloses) close();
                        return true;
                    }
                    float h = Math.max(1, column.getHeight());
                    if (!cancel && (-dy > h / 4f || vy < -1200 * density / 2.625f)) {
                        close();
                    } else {
                        column.animate().translationY(0f).setDuration(220)
                                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
                        scrim.animate().alpha(1f).setDuration(220).start();
                    }
                    return true;
                }
                default:
                    return true;
            }
        };
    }

    // Rebuilds the list for the current mode.
    private static void fill() {
        if (list == null) return;
        list.removeAllViews();
        if (showingBlocked) {
            styleClear(false, ui.getResources().getDisplayMetrics().density);
            fillBlocked();
            return;
        }
        float density = ui.getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        List<Entry> entries = snapshot();
        boolean anyClearable = false;
        for (Entry e : entries) anyClearable |= e.clearable;
        styleClear(anyClearable, density);
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
        if (count > 0) {
            TextView pill = countPill(count, open, density);
            Runnable toggleGroup = () -> {
                if (!expanded.remove(groupKey)) expanded.add(groupKey);
                smoothly();
                fill();
            };
            pill.setOnClickListener(v -> toggleGroup.run());
            rightTap.put(card, toggleGroup);
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(26 * density));
            pillLp.setMarginStart(Math.round(10 * density));
            card.addView(pill, pillLp);
        }
        if (targets.size() == 1) addActions(card, shown, density);
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

    // The notification's own buttons (mark read, disable, play/pause...): one full-width bar
    // across the card's bottom, edge to edge, split by full-height hairlines, quiet ink.
    // Ones that need typing (reply) are left out: there is no keyboard here.
    private static void addActions(LinearLayout card, Entry e, float density) {
        if (e.actions == null || e.actions.length == 0) return;
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int line = ui.getColor(R.color.ft_line);
        int hair = Math.max(1, Math.round(density));
        int shown = 0;
        for (int i = 0; i < e.actions.length && shown < MAX_ACTIONS; i++) {
            Notification.Action action = e.actions[i];
            if (action == null || action.actionIntent == null || TextUtils.isEmpty(action.title)) continue;
            if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) continue;
            if (shown > 0) {
                View split = new View(ui);
                split.setBackgroundColor(line);
                row.addView(split, new LinearLayout.LayoutParams(hair, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            TextView button = new TextView(ui);
            android.graphics.drawable.Drawable actionIcon = actionIcon(action, density);
            if (actionIcon != null) {
                // The app's own icon says it shorter (close widget, refresh...); name for a11y.
                button.setCompoundDrawablesRelative(null, actionIcon, null, null);
                button.setContentDescription(action.title);
            } else {
                button.setText(action.title);
            }
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(ui.getColor(R.color.ft_ink));
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setGravity(Gravity.CENTER);
            int ph = Math.round(8 * density);
            button.setPadding(ph, 0, ph, 0);
            android.graphics.drawable.StateListDrawable press = new android.graphics.drawable.StateListDrawable();
            press.addState(new int[]{android.R.attr.state_pressed},
                    new android.graphics.drawable.ColorDrawable(ui.getColor(R.color.ft_press)));
            button.setBackground(press);
            int index = i;
            button.setOnClickListener(v -> runAction(e, index, action));
            row.addView(button, new LinearLayout.LayoutParams(0, Math.round(42 * density), 1f));
            shown++;
        }
        if (shown == 0) return;

        // Card becomes: [icon | texts | pill | chevron] / (full-width bar, pulled open).
        android.widget.ImageView chevron = new android.widget.ImageView(ui);
        android.graphics.drawable.Drawable chev = ui.getDrawable(R.drawable.ic_ft_chevron);
        if (chev != null) {
            chev = chev.mutate();
            chev.setTint(ui.getColor(R.color.ft_secondary));
            chevron.setImageDrawable(chev);
        }
        LinearLayout content = new LinearLayout(ui);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        while (card.getChildCount() > 0) {
            View child = card.getChildAt(0);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            card.removeViewAt(0);
            content.addView(child, lp);
        }
        int chevSize = Math.round(18 * density);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(chevSize, chevSize);
        chevLp.setMarginStart(Math.round(6 * density));
        content.addView(chevron, chevLp);
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The bar ignores the card's side and bottom padding: it runs to the card's edges and
        // the card's rounded outline clips its bottom corners.
        LinearLayout box = new LinearLayout(ui);
        box.setOrientation(LinearLayout.VERTICAL);
        View divider = new View(ui);
        divider.setBackgroundColor(line);
        box.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hair));
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.setMarginStart(-card.getPaddingStart());
        boxLp.setMarginEnd(-card.getPaddingEnd());
        boxLp.topMargin = card.getPaddingTop();
        card.addView(box, boxLp);
        card.setClipToOutline(true);
        card.setTag(new Object[]{e.key, box, chevron, card.getPaddingBottom()});
        showActions(card, actionsOpen.contains(e.key), false);
        chevron.setOnClickListener(v -> toggleActions(card));
        if (!rightTap.containsKey(card)) rightTap.put(card, () -> toggleActions(card));
    }

    // Opens/closes a card's button bar; the chevron points down when closed, up when open.
    private static void showActions(View card, boolean open, boolean animate) {
        Object[] tag = (Object[]) card.getTag();
        View box = (View) tag[1];
        View chevron = (View) tag[2];
        if (animate) smoothly();
        box.setVisibility(open ? View.VISIBLE : View.GONE);
        float turn = open ? -90f : 90f;
        if (animate) {
            chevron.animate().rotation(turn).setDuration(260)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        } else {
            chevron.setRotation(turn);
        }
        int basePad = (int) tag[3];
        ((LinearLayout) card).setPadding(card.getPaddingLeft(), card.getPaddingTop(), card.getPaddingRight(),
                open ? 0 : basePad);
    }

    private static void toggleActions(View card) {
        if (!(card.getTag() instanceof Object[])) return;
        String key = (String) ((Object[]) card.getTag())[0];
        boolean open = !actionsOpen.remove(key);
        if (open) actionsOpen.add(key);
        showActions(card, open, true);
    }

    // The action's icon from the app, tinted butter; null if it has none or it can't load.
    private static android.graphics.drawable.Drawable actionIcon(Notification.Action action, float density) {
        try {
            android.graphics.drawable.Icon icon = action.getIcon();
            if (icon == null) return null;
            android.graphics.drawable.Drawable d = icon.loadDrawable(SystemSettings.context());
            if (d == null || d.getIntrinsicWidth() <= 0) return null;
            d = d.mutate();
            d.setTint(ui.getColor(R.color.ft_ink));
            int size = Math.round(18 * density);
            d.setBounds(0, 0, size, size);
            return d;
        } catch (Throwable t) {
            return null;
        }
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
        final float[] downY = new float[1];
        final boolean[] dragging = new boolean[1];
        final boolean[] pulled = new boolean[1];
        // Long press opens/closes the card's buttons too (like expanding in the system shade).
        final Runnable longPress = () -> {
            pulled[0] = true;
            card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            toggleActions(card);
        };
        card.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() != MotionEvent.ACTION_DOWN && (dragging[0] || pulled[0]
                    || Math.hypot(ev.getRawX() - downX[0], ev.getRawY() - downY[0]) > slop)) {
                v.removeCallbacks(longPress);
            }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getRawX();
                    downY[0] = ev.getRawY();
                    dragging[0] = false;
                    pulled[0] = false;
                    if (v.getTag() instanceof Object[]) {
                        v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float raw = ev.getRawX() - downX[0];
                    boolean reveal = raw > 0 && revealsActions(v, targets);
                    // Pinned card with buttons: a short, resisted right pull that opens them.
                    float dx = reveal ? Math.min(raw * 0.5f, v.getWidth() / 4f) : allowed(targets, raw);
                    float dy = ev.getRawY() - downY[0];
                    // Pull down opens the card's buttons, pull up closes them; only while the
                    // list can't scroll that way, so normal scrolling keeps working.
                    if (!dragging[0] && !pulled[0]
                            && Math.abs(dy) > slop * 1.5f && Math.abs(dy) > Math.abs(ev.getRawX() - downX[0])) {
                        boolean hasActions = v.getTag() instanceof Object[];
                        boolean open = hasActions && actionsOpen.contains((String) ((Object[]) v.getTag())[0]);
                        boolean down = dy > 0;
                        ScrollView sv = scrollView;
                        if (sv != null && !sv.canScrollVertically(down ? -1 : 1)) {
                            if (hasActions && down != open) {
                                pulled[0] = true;
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                                toggleActions(v);
                            }
                            // Otherwise it's just a scroll that hit the end: never closes the panel
                            // (closing is the header swipe, ✕ or back).
                        }
                    }
                    if (pulled[0]) return true;
                    if (Math.abs(dx) > slop) {
                        dragging[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        v.setTranslationX(dx);
                        showHint(hint, dx, density, reveal);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.removeCallbacks(longPress);
                    if (pulled[0]) return true;
                    if (dragging[0]) {
                        float moved = v.getTranslationX();
                        if (moved > 0 && revealsActions(v, targets)) {
                            if (moved > v.getWidth() / 6f) toggleActions(v);
                            v.animate().translationX(0f).setDuration(ANIM_MS)
                                    .withEndAction(() -> showHint(hint, 0, density, false)).start();
                        } else if (Math.abs(moved) > v.getWidth() / 3f) {
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
                        // The right end of the card (count / chevron) is a wide target of its
                        // own: folds or unfolds instead of opening the app.
                        Runnable right = rightTap.get(v);
                        if (right != null && ev.getX() > v.getWidth() - 80 * density) {
                            right.run();
                        } else {
                            click(e);
                            close();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(longPress);
                    v.animate().translationX(0f).setDuration(ANIM_MS)
                            .withEndAction(() -> hint.setVisibility(View.INVISIBLE)).start();
                    return true;
                default:
                    return false;
            }
        });
    }

    // Pinned notifications can't be dismissed, but can still be turned off (swipe left).
    // Right swipe on a card nothing can dismiss, but that has buttons: opens/closes them.
    private static boolean revealsActions(View card, List<Entry> targets) {
        if (!(card.getTag() instanceof Object[])) return false;
        for (Entry t : targets) if (t.clearable) return false;
        return true;
    }

    private static float allowed(List<Entry> targets, float dx) {
        if (dx <= 0) return dx;
        for (Entry t : targets) if (t.clearable) return dx;
        return 0;
    }

    // Icon and color only: grey trash for dismiss (right), red bell-off for never show (left).
    private static void showHint(android.widget.ImageView hint, float dx, float density) {
        showHint(hint, dx, density, false);
    }

    // reveal: butter chevron (opens the card's buttons) instead of the trash.
    private static void showHint(android.widget.ImageView hint, float dx, float density, boolean reveal) {
        if (dx == 0) {
            hint.setVisibility(View.INVISIBLE);
            ((View) hint.getParent()).setBackground(null);
            return;
        }
        boolean block = dx < 0;
        hint.setVisibility(View.VISIBLE);
        android.graphics.drawable.Drawable icon = ui.getDrawable(block ? R.drawable.ic_ft_bell_off
                : reveal ? R.drawable.ic_ft_chevron : R.drawable.ic_ft_trash);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ui.getColor(block ? R.color.ft_error : reveal ? R.color.ft_butter : R.color.ft_secondary));
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

    // Folds up into the top edge while the scrim fades, then the window goes.
    static void close() {
        FrameLayout v = root;
        if (v == null) return;
        root = null;
        list = null;
        LinearLayout column = panelColumn;
        panelColumn = null;
        scrollView = null;
        Runnable remove = () -> {
            try {
                windowManager.removeView(v);
            } catch (RuntimeException ignored) {
            }
        };
        if (column == null || !v.isAttachedToWindow()) {
            remove.run();
            return;
        }
        v.setEnabled(false);
        column.animate().cancel();
        v.animate().cancel();
        // Slides up off the top from wherever a drag left it, like pushing a shade back.
        column.animate().translationY(-column.getHeight()).setDuration(240)
                .setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)).start();
        v.animate().alpha(0f).setDuration(240).withEndAction(remove).start();
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
