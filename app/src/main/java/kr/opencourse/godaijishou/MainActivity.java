package kr.opencourse.godaijishou;

import android.Manifest;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class MainActivity extends AppCompatActivity {
    // Intent category that opens the LSPosed manager (including the parasitic manager)
    private static final String LSPOSED_LAUNCH_CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER";
    private static final String LSPOSED_MANAGER_PACKAGE = "org.lsposed.manager";
    private static final String LSPOSED_HOST_PACKAGE = "com.android.shell";
    private static final String LSPOSED_HOST_ACTIVITY = "com.android.shell.BugreportWarningActivity";

    private SharedPreferences prefs;
    private SwitchMaterial topBlackoutSwitch;
    private SwitchMaterial forceRotationSwitch;
    private SwitchMaterial powerMenuSwitch;
    private SwitchMaterial volumeOverlaySwitch;
    private SwitchMaterial batteryStripSwitch;
    private SwitchMaterial labelHideFullscreenSwitch;
    private SwitchMaterial noTopScreenSwitch;
    private View rebootNotice;

    // Prevents the listener from firing when setChecked() only syncs the UI.
    private boolean syncingSwitch = false;

    // Second screen home candidates (null package = stock LG launcher, no redirect)
    private static final String[] LAUNCHER_PACKAGES = {
            "com.magneticchen.daijishou", "org.es_de.frontend", "org.pegasus_frontend.android", null
    };
    private static final int[] LAUNCHER_NAMES = {
            R.string.launcher_daijishou, R.string.launcher_esde, R.string.launcher_pegasus, R.string.launcher_stock
    };
    private static final int[] LAUNCHER_ICONS = {
            R.drawable.ic_gamepad, R.drawable.ic_esde, R.drawable.ic_pegasus, R.drawable.ic_ft_home
    };
    private final View[] launcherRows = new View[LAUNCHER_PACKAGES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        drawBehindBars();

        prefs = AppPreferences.settings(this);

        buildLauncherRows();
        topBlackoutSwitch = findViewById(R.id.switch_top_blackout);
        forceRotationSwitch = findViewById(R.id.switch_force_rotation);
        rebootNotice = findViewById(R.id.notice_reboot);
        TextView version = findViewById(R.id.tv_version);
        version.setText("v" + BuildConfig.VERSION_NAME);
        version.setOnClickListener(v -> openReleases()); // GitHub mark: the project's releases

        // Yellow trailing dot, like the freetv logo
        SpannableString logo = new SpannableString(getString(R.string.app_title) + ".");
        logo.setSpan(new ForegroundColorSpan(getColor(R.color.ft_butter)),
                logo.length() - 1, logo.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView logoView = findViewById(R.id.tv_logo);
        logoView.setText(logo);
        // Baloo 2 is a variable font. Building the typeface with the weight axis set is the
        // reliable way to get ExtraBold (800); a font-family XML ignores the variation.
        try {
            logoView.setTypeface(new Typeface.Builder(getAssets(), "fonts/baloo2.ttf")
                    .setFontVariationSettings("'wght' 800")
                    .build());
        } catch (RuntimeException ignored) {
            // Keep the default font if the asset cannot be loaded.
        }

        // Every switch is backed by a system_server hook; the whole row toggles it.
        bindSystemHookSwitch(R.id.row_top_blackout, topBlackoutSwitch, AppPreferences.KEY_TOP_SCREEN_OFF);
        volumeOverlaySwitch = findViewById(R.id.switch_volume_overlay);
        bindSystemHookSwitch(R.id.row_volume_overlay, volumeOverlaySwitch, AppPreferences.KEY_VOLUME_OVERLAY);
        batteryStripSwitch = findViewById(R.id.switch_battery_strip);
        bindSystemHookSwitch(R.id.row_battery_strip, batteryStripSwitch, AppPreferences.KEY_BATTERY_STRIP);
        labelHideFullscreenSwitch = findViewById(R.id.switch_label_hide_fullscreen);
        bindSystemHookSwitch(R.id.row_label_hide_fullscreen, labelHideFullscreenSwitch,
                AppPreferences.KEY_LABEL_HIDE_FULLSCREEN);
        noTopScreenSwitch = findViewById(R.id.switch_no_top_screen);
        bindSystemHookSwitch(R.id.row_no_top_screen, noTopScreenSwitch, AppPreferences.KEY_NO_TOP_SCREEN);
        bindSystemHookSwitch(R.id.row_force_rotation, forceRotationSwitch, AppPreferences.KEY_FORCE_ROTATION);
        powerMenuSwitch = findViewById(R.id.switch_power_menu);
        bindSystemHookSwitch(R.id.row_power_menu, powerMenuSwitch, AppPreferences.KEY_POWER_MENU_SECOND_SCREEN);

        findViewById(R.id.row_lsposed).setOnClickListener(v -> openLsposed());
        findViewById(R.id.btn_notice_lsposed).setOnClickListener(v -> openLsposed());
        buildUsbModeButtons();
        buildChoice(R.id.strip_style_buttons, AppPreferences.KEY_BATTERY_STRIP_STYLE,
                AppPreferences.STRIP_STYLE_PILL,
                new int[]{R.string.strip_style_pill, R.string.strip_style_line});
        buildChoice(R.id.strip_pos_buttons, AppPreferences.KEY_BATTERY_STRIP_POS,
                AppPreferences.STRIP_POS_RIGHT,
                new int[]{R.string.strip_pos_left, R.string.strip_pos_center, R.string.strip_pos_right});
        bindSystemHookSwitch(R.id.row_debug_log, findViewById(R.id.switch_debug_log), AppPreferences.KEY_DEBUG_LOG);

        // These need an extra LSPosed scope: turning one on explains which, unless that
        // hook is already known to be loaded this boot.
        bindScopedSwitch(R.id.row_hide_swivel_warnings, findViewById(R.id.switch_hide_swivel_warnings),
                AppPreferences.KEY_HIDE_SWIVEL_WARNINGS, R.string.scope_popupcamera, null);
        bindScopedSwitch(R.id.row_slim_nav_pill, findViewById(R.id.switch_slim_nav_pill),
                AppPreferences.KEY_SLIM_NAV_PILL, R.string.scope_systemui, "systemui");
        bindScopedSwitch(R.id.row_hide_ime_nav_strip, findViewById(R.id.switch_hide_ime_nav_strip),
                AppPreferences.KEY_HIDE_IME_NAV_STRIP, R.string.scope_systemui_keyboard, null);
        bindScopedSwitch(R.id.row_fingerprint_off, findViewById(R.id.switch_fingerprint_off),
                AppPreferences.KEY_FINGERPRINT_OFF, R.string.scope_system, "android");
        buildPerfProfileButtons();

        findViewById(R.id.row_reboot).setOnClickListener(v -> confirmReboot());
        findViewById(R.id.btn_notice_reboot).setOnClickListener(v -> confirmReboot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRebootNotice();
        updateStatus();
        updateLauncherRows(); // reflect apps installed/removed while we were away
        UpdateCheck.run(this, this::showUpdate);
        if (perfButtons != null) stylePerfButtons(perfButtons); // may have changed in the panel
    }

    // The list runs on under the gesture bar instead of stopping above it (the bar area was a
    // separate band that made the last card look cut off). The content keeps its padding plus
    // the bars' size, so nothing ends up under the status label or the gesture pill.
    private void drawBehindBars() {
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        android.view.ViewGroup scroll = (android.view.ViewGroup) ((android.view.ViewGroup)
                findViewById(android.R.id.content)).getChildAt(0);
        scroll.setClipToPadding(false);
        View column = scroll.getChildAt(0);
        final int top = column.getPaddingTop(), bottom = column.getPaddingBottom();
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            column.setPadding(column.getPaddingLeft(), top + bars.top, column.getPaddingRight(), bottom + bars.bottom);
            return insets;
        });
    }

    // Header subtitle: whether the hooks loaded this boot. Everything here is an LSPosed
    // hook, so "not running" means LSPosed scope/reboot; tapping it opens LSPosed.
    private android.animation.ValueAnimator dotPulse;

    private void stopDotPulse() {
        if (dotPulse != null) {
            dotPulse.cancel();
            dotPulse = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDotPulse();
    }

    private void updateStatus() {
        TextView status = findViewById(R.id.tv_status);
        boolean active = AppPreferences.isSystemHookActive(this)
                || AppPreferences.isHookActive(this, "systemui");
        int tint = getColor(active ? R.color.ft_good : R.color.ft_pending);
        status.setText(active ? R.string.status_active : R.string.status_inactive);
        status.setTextColor(getColor(active ? R.color.ft_secondary : R.color.ft_pending));
        android.graphics.drawable.Drawable dot = getDrawable(R.drawable.ic_ft_dot);
        if (dot != null) {
            dot = dot.mutate();
            dot.setTint(tint);
            status.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        }
        // Running: the dot breathes slowly (only while visible; stopped in onPause).
        stopDotPulse();
        if (active && dot != null) {
            final android.graphics.drawable.Drawable d = dot;
            dotPulse = android.animation.ValueAnimator.ofInt(255, 70);
            dotPulse.setDuration(1100);
            dotPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            dotPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            dotPulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            dotPulse.addUpdateListener(a -> {
                d.setAlpha((Integer) a.getAnimatedValue());
                status.invalidate();
            });
            dotPulse.start();
        }
        status.setOnClickListener(active ? null : v -> openLsposed());
        status.setClickable(!active);
    }

    // Newer release on GitHub: the version pill turns butter and opens the release page.
    private void showUpdate(String version) {
        if (isFinishing()) return;
        TextView pill = findViewById(R.id.tv_version);
        pill.setText(getString(R.string.update_available, version));
        pill.setTextColor(getColor(R.color.ft_on_butter));
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_ft_download);
        if (arrow != null) {
            arrow = arrow.mutate();
            arrow.setTint(getColor(R.color.ft_on_butter));
            int size = Math.round(15 * getResources().getDisplayMetrics().density);
            arrow.setBounds(0, 0, size, size);
            pill.setCompoundDrawablesRelative(arrow, null, null, null);
        }
        pill.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.ft_butter)));
        pill.setCompoundDrawableTintList(null); // the download arrow carries its own tint
    }

    private void openReleases() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES_PAGE)));
        } catch (Exception ignored) {
            // no browser
        }
    }

    // --- Performance profile ---

    // {profile, icon, color, label}: eco green leaf, normal grey gauge, turbo red bolt.
    private static final int[][] PERF_PROFILES = {
            {PerfProfile.ECO, R.drawable.ic_ft_leaf, R.color.ft_good, R.string.perf_eco},
            {PerfProfile.NORMAL, R.drawable.ic_ft_gauge, R.color.ft_secondary, R.string.perf_normal},
            {PerfProfile.PERFORMANCE, R.drawable.ic_ft_bolt, R.color.ft_error, R.string.perf_turbo},
    };

    private TextView[] perfButtons;
    // Selected fills, in PERF_PROFILES order: deep green, neutral grey, deep red.
    private static final int[] SELECTED_FILL = {0xFF23885A, 0xFF55555F, 0xFFC9443A};

    // Three equal segments across the card: icon + name.
    private void buildPerfProfileButtons() {
        android.widget.LinearLayout box = findViewById(R.id.perf_profile_buttons);
        float density = getResources().getDisplayMetrics().density;
        TextView[] buttons = new TextView[PERF_PROFILES.length];
        perfButtons = buttons;
        for (int i = 0; i < PERF_PROFILES.length; i++) {
            int[] def = PERF_PROFILES[i];
            TextView b = new TextView(this);
            b.setText(def[3]);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(android.view.Gravity.CENTER);
            b.setCompoundDrawablePadding(Math.round(6 * density));
            buttons[i] = b;
            // The segment is the cell; icon + name sit together in its middle
            // (a drawableStart on a full-width TextView hugs the left edge).
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            cell.addView(b, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
            cell.setClickable(true);
            cell.setContentDescription(getString(def[3]));
            cell.setOnClickListener(v -> {
                prefs.edit().putInt(AppPreferences.KEY_PERF_PROFILE, def[0]).commit();
                // The notification panel (system_server) shows the same choice.
                getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
                stylePerfButtons(buttons);
                // Root shell: off the main thread. Magisk asks once for the grant.
                new Thread(() -> {
                    boolean ok = PerfProfile.apply(def[0]);
                    if (!ok) runOnUiThread(() -> android.widget.Toast.makeText(this,
                            R.string.main_perf_profile_no_root, android.widget.Toast.LENGTH_LONG).show());
                }, "GoDaijishou-perf").start();
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, Math.round(44 * density), 1f);
            if (i > 0) lp.setMarginStart(Math.round(8 * density));
            box.addView(cell, lp);
        }
        stylePerfButtons(buttons);
    }

    // Two segments: file transfer (MTP) / charging only. The system_server hook makes the
    // choice the default USB configuration and applies it on each connection.
    private void buildUsbModeButtons() {
        android.widget.LinearLayout box = findViewById(R.id.usb_mode_buttons);
        float density = getResources().getDisplayMetrics().density;
        int[] labels = {R.string.usb_mode_mtp, R.string.usb_mode_charge};
        int[] icons = {R.drawable.ic_ft_folder, R.drawable.ic_ft_plug};
        TextView[] buttons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final boolean chargeOnly = i == 1;
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(android.view.Gravity.CENTER);
            b.setCompoundDrawablePadding(Math.round(4 * density));
            b.setTag(icons[i]);
            buttons[i] = b;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            cell.addView(b, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
            cell.setClickable(true);
            cell.setContentDescription(getString(labels[i]));
            cell.setOnClickListener(v -> {
                prefs.edit().putBoolean(AppPreferences.KEY_USB_CHARGE_ONLY, chargeOnly).commit();
                getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
                styleUsbModeButtons(buttons);
            });
            int pad = Math.round(11 * density);
            cell.setPadding(pad, 0, pad, 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * density));
            if (i > 0) lp.setMarginStart(Math.round(6 * density));
            box.addView(cell, lp);
        }
        styleUsbModeButtons(buttons);
    }

    // Compact text segments for an int setting (value = segment index), like the USB row.
    // The system_server hook rebuilds the battery bar when the provider reports the change.
    private void buildChoice(int boxId, String key, int def, int[] labels) {
        android.widget.LinearLayout box = findViewById(boxId);
        float density = getResources().getDisplayMetrics().density;
        TextView[] buttons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int value = i;
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(android.view.Gravity.CENTER);
            buttons[i] = b;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            cell.addView(b, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
            cell.setClickable(true);
            cell.setContentDescription(getString(labels[i]));
            cell.setOnClickListener(v -> {
                prefs.edit().putInt(key, value).commit();
                getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
                styleChoice(buttons, prefs.getInt(key, def));
            });
            int pad = Math.round(12 * density);
            cell.setPadding(pad, 0, pad, 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(34 * density));
            if (i > 0) lp.setMarginStart(Math.round(6 * density));
            box.addView(cell, lp);
        }
        styleChoice(buttons, prefs.getInt(key, def));
    }

    private void styleChoice(TextView[] buttons, int selected) {
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < buttons.length; i++) {
            boolean on = i == selected;
            buttons[i].setTextColor(getColor(on ? R.color.ft_on_butter : R.color.ft_ink));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(12 * density);
            if (on) {
                bg.setColor(getColor(R.color.ft_butter));
            } else {
                bg.setColor(getColor(R.color.ft_surface));
                bg.setStroke(Math.max(1, Math.round(density)), getColor(R.color.ft_line));
            }
            ((View) buttons[i].getParent()).setBackground(bg);
        }
    }

    // Selected: butter fill with dark ink. Other: outlined.
    private void styleUsbModeButtons(TextView[] buttons) {
        float density = getResources().getDisplayMetrics().density;
        boolean chargeOnly = prefs.getBoolean(AppPreferences.KEY_USB_CHARGE_ONLY, false);
        for (int i = 0; i < buttons.length; i++) {
            boolean on = (i == 1) == chargeOnly;
            int ink = getColor(on ? R.color.ft_on_butter : R.color.ft_ink);
            android.graphics.drawable.Drawable icon = getDrawable((Integer) buttons[i].getTag()).mutate();
            icon.setTint(ink);
            int size = Math.round(15 * density);
            icon.setBounds(0, 0, size, size);
            buttons[i].setCompoundDrawablesRelative(icon, null, null, null);
            buttons[i].setTextColor(ink);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(12 * density);
            if (on) {
                bg.setColor(getColor(R.color.ft_butter));
            } else {
                bg.setColor(getColor(R.color.ft_surface));
                bg.setStroke(Math.max(1, Math.round(density)), getColor(R.color.ft_line));
            }
            ((View) buttons[i].getParent()).setBackground(bg);
        }
    }

    // Selected: filled with its color, dark ink. Others: outlined, icon and name in that color.
    private void stylePerfButtons(TextView[] buttons) {
        float density = getResources().getDisplayMetrics().density;
        int current = prefs.getInt(AppPreferences.KEY_PERF_PROFILE, PerfProfile.NORMAL);
        for (int i = 0; i < buttons.length; i++) {
            int[] def = PERF_PROFILES[i];
            boolean on = current == def[0];
            int color = getColor(def[2]);
            // Selected: white on a deeper shade of its color (the app is dark), so white reads
            // well on all three, grey "normal" included.
            int ink = on ? 0xFFFFFFFF : color;
            android.graphics.drawable.Drawable icon = getDrawable(def[1]).mutate();
            icon.setTint(ink);
            int size = Math.round(18 * density);
            icon.setBounds(0, 0, size, size);
            buttons[i].setCompoundDrawablesRelative(icon, null, null, null);
            buttons[i].setTextColor(on ? ink : getColor(R.color.ft_ink));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(14 * density);
            if (on) {
                bg.setColor(SELECTED_FILL[i]);
            } else {
                bg.setColor(getColor(R.color.ft_surface));
                bg.setStroke(Math.max(1, Math.round(density)), getColor(R.color.ft_line));
            }
            ((View) buttons[i].getParent()).setBackground(bg);
        }
    }

    // --- Reboot notice ---

    // A switch backed by a system_server hook: saved in the provider-exposed prefs and
    // applied immediately through the hook's ContentObserver when the hook is loaded.
    private void bindSystemHookSwitch(int rowId, SwitchMaterial sw, String key) {
        findViewById(rowId).setOnClickListener(v -> sw.toggle());
        sw.setChecked(prefs.getBoolean(key, false));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).commit();
            getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
            updateRebootNotice();
        });
    }

    // hookName: "android" = system framework, other names report through the provider;
    // null = can't be detected (the dialog shows on every switch-on).
    private void bindScopedSwitch(int rowId, SwitchMaterial sw, String key, int scopeText, String hookName) {
        bindSystemHookSwitch(rowId, sw, key);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).commit();
            getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
            updateRebootNotice();
            if (!isChecked) return;
            boolean loaded = hookName != null && ("android".equals(hookName)
                    ? AppPreferences.isSystemHookActive(this)
                    : AppPreferences.isHookActive(this, hookName));
            if (!loaded) explainScope(scopeText);
        });
    }

    private void explainScope(int scopeText) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scope_dialog_title)
                .setMessage(getString(R.string.scope_dialog_message, getString(scopeText)))
                .setPositiveButton(R.string.scope_dialog_open, (d, w) -> openLsposed())
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    // Show the notice when a system_server feature is on but the hook is not loaded
    // in this boot (scope not checked, or checked but not rebooted yet).
    private void updateRebootNotice() {
        boolean needsSystemHook = forceRotationSwitch.isChecked() || powerMenuSwitch.isChecked()
                || noTopScreenSwitch.isChecked() || topBlackoutSwitch.isChecked()
                || volumeOverlaySwitch.isChecked() || batteryStripSwitch.isChecked();
        boolean needReboot = (needsSystemHook || prefs.getBoolean(AppPreferences.KEY_FINGERPRINT_OFF, false))
                && !AppPreferences.isSystemHookActive(this);
        // SystemUI features: same notice while the SystemUI hook hasn't reported this boot.
        if (prefs.getBoolean(AppPreferences.KEY_SLIM_NAV_PILL, false)
                && !AppPreferences.isHookActive(this, "systemui")) {
            needReboot = true;
        }
        // Updated since this boot with changed hook code: system_server and SystemUI still run
        // the code loaded at boot, so ask for a reboot. Updates that only touch this screen or
        // the battery strip's look (StripView, reloaded live) need none.
        boolean updated = !needReboot && AppPreferences.isSystemHookActive(this)
                && AppPreferences.isHookOutdated(this);
        ((TextView) findViewById(R.id.notice_reboot_text)).setText(
                updated ? R.string.notice_reboot_updated : R.string.notice_reboot_desc);
        rebootNotice.setVisibility(needReboot || updated ? View.VISIBLE : View.GONE);
    }

    private void confirmReboot() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reboot_confirm_title)
                .setMessage(R.string.reboot_confirm_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.row_reboot_title, (d, w) -> requestSystemAction(SystemActions.OP_RESTART))
                .show();
    }

    // Privileged actions run in the system_server hook (no root in the app). Without the
    // hook loaded nothing would happen, so say so instead.
    private void requestSystemAction(String op) {
        if (!AppPreferences.isSystemHookActive(this)) {
            Toast.makeText(this, R.string.toast_system_hook_needed, Toast.LENGTH_LONG).show();
            return;
        }
        int displayId = getDisplay() != null ? getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        SystemActions.send(this, op, displayId);
    }

    // --- Open LSPosed ---

    // The parasitic LSPosed manager lives inside this com.android.shell activity; launching
    // it with the LAUNCH_MANAGER category makes LSPosed show the manager UI. A normal app may
    // not start that activity (not exported to us), so it goes through root or the hook.
    private void openLsposed() {
        // 1) Standalone manager app installed (non-parasitic mode)
        Intent app = getPackageManager().getLaunchIntentForPackage(LSPOSED_MANAGER_PACKAGE);
        if (app != null && tryStart(app)) return;

        // 2) Root: the documented `am start` way. LSPosed only exists on a rooted phone,
        //    and this still works when the module itself is off (the case that needs LSPosed).
        int displayId = getDisplay() != null ? getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        String cmd = "am start --display " + displayId
                + " -c " + LSPOSED_LAUNCH_CATEGORY
                + " -n " + LSPOSED_HOST_PACKAGE + "/" + LSPOSED_HOST_ACTIVITY + "\n";
        new Thread(() -> {
            boolean ok = runAsRoot(cmd);
            runOnUiThread(() -> {
                if (ok || isFinishing()) return;
                // 3) No root grant: the system_server hook can open it if it is loaded
                if (AppPreferences.isSystemHookActive(this)) {
                    SystemActions.send(this, SystemActions.OP_OPEN_LSPOSED, displayId);
                } else {
                    Toast.makeText(this, R.string.toast_lsposed_open_failed, Toast.LENGTH_LONG).show();
                }
            });
        }, "open-lsposed").start();
    }

    // Runs one command through su; false when there is no root or it was denied.
    private static boolean runAsRoot(String cmd) {
        try {
            java.lang.Process su = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (java.io.OutputStream in = su.getOutputStream()) {
                in.write((cmd + "exit $?\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String out = new String(su.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return su.waitFor() == 0 && !out.contains("Error");
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // --- Second screen home (launcher choice) ---

    private void buildLauncherRows() {
        ViewGroup list = findViewById(R.id.launcher_list);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < LAUNCHER_PACKAGES.length; i++) {
            View row = inflater.inflate(R.layout.item_ft_choice, list, false);
            ((ImageView) row.findViewById(R.id.choice_icon)).setImageResource(LAUNCHER_ICONS[i]);
            ((TextView) row.findViewById(R.id.choice_title)).setText(LAUNCHER_NAMES[i]);
            final int index = i;
            row.setOnClickListener(v -> selectLauncher(index));
            list.addView(row);
            launcherRows[i] = row;
        }
    }

    private void updateLauncherRows() {
        String selected = prefs.getString(AppPreferences.KEY_LAUNCHER_PACKAGE, null);
        for (int i = 0; i < LAUNCHER_PACKAGES.length; i++) {
            String pkg = LAUNCHER_PACKAGES[i];
            View row = launcherRows[i];
            boolean isSelected = pkg == null ? selected == null : pkg.equals(selected);
            // Check and install label share the right edge: the selected row shows the check.
            row.findViewById(R.id.choice_check).setVisibility(isSelected ? View.VISIBLE : View.GONE);
            TextView desc = row.findViewById(R.id.choice_desc);
            TextView tag = row.findViewById(R.id.choice_tag);
            boolean installed = pkg == null || isInstalled(pkg);
            // Install state goes to the label on the right; the stock row has none.
            if (pkg == null && !isSelected) {
                tag.setVisibility(View.GONE);
            } else {
                // Selected: keep the label's footprint (invisible) so the check sits where
                // the labels of the other rows are.
                tag.setVisibility(isSelected ? View.INVISIBLE : View.VISIBLE);
                tag.setText(installed ? R.string.launcher_installed : R.string.launcher_not_installed);
                tag.setTextColor(getColor(installed ? R.color.ft_good : R.color.ft_secondary));
                tag.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        getColor(installed ? R.color.ft_green_tint : R.color.ft_line)));
            }
            // Under the title: the target package, small and quiet (stock: LG's second home,
            // after its one-line description).
            String target = pkg != null ? pkg : LauncherHook.TARGET_PACKAGE;
            android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
            if (pkg == null) text.append(getString(R.string.launcher_stock_desc)).append('\n');
            int start = text.length();
            text.append(target);
            int end = text.length();
            text.setSpan(new android.text.style.RelativeSizeSpan(0.9f), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.TypefaceSpan("monospace"), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            desc.setTextColor(getColor(R.color.ft_secondary));
            desc.setText(text);
            // Dim launchers that are not installed so the usable ones stand out.
            row.setAlpha(installed ? 1f : 0.45f);
        }
    }

    private void selectLauncher(int index) {
        String pkg = LAUNCHER_PACKAGES[index];
        String name = getString(LAUNCHER_NAMES[index]);
        if (pkg != null && !isInstalled(pkg)) {
            Toast.makeText(this, getString(R.string.toast_app_not_installed, name), Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (pkg != null) {
            editor.putString(AppPreferences.KEY_LAUNCHER_PACKAGE, pkg);
            editor.putString(AppPreferences.KEY_LAUNCHER_NAME, name);
        } else {
            // The rotation flag shares this file, so remove only the launcher keys instead of clear().
            editor.remove(AppPreferences.KEY_LAUNCHER_PACKAGE);
            editor.remove(AppPreferences.KEY_LAUNCHER_NAME);
        }
        editor.remove(AppPreferences.KEY_LAUNCHER_ACTIVITY); // legacy key
        editor.commit();
        // Notify so the second launcher process refreshes its cache (ContentObserver) immediately
        getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
        updateLauncherRows();
        showHomeOnSecondScreen(pkg);
    }

    // Apply the choice right away: bring the chosen home to the second screen instead of
    // waiting for the next time the user goes home there. No-op while the swivel is closed.
    private void showHomeOnSecondScreen(String pkg) {
        DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm == null || !Screens.isSecondScreenOn(dm)) {
            android.util.Log.w("GoDaijishou", "showHome: second screen off");
            return;
        }
        Intent home;
        if (pkg != null) {
            home = getPackageManager().getLaunchIntentForPackage(pkg);
            if (home == null) return;
        } else {
            home = stockSecondHome();
            if (home == null) {
                android.util.Log.w("GoDaijishou", "showHome: no LG home intent");
                return;
            }
        }
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        home.putExtra(LauncherHook.EXTRA_REFRESH, true);
        try {
            startActivity(home, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(MainModule.SECOND_SCREEN_DISPLAY_ID).toBundle());
        } catch (RuntimeException e) {
            // The setting is saved either way; it applies on the next home press.
            android.util.Log.w("GoDaijishou", "showHome failed", e);
        }
    }

    // LG's second home as an explicit component, so it starts at once like the others
    // (an implicit SECONDARY_HOME intent may not resolve for an ordinary app).
    // LG's second home declares no HOME/SECONDARY_HOME filter of its own (the system starts it
    // by component), only MAIN + LAUNCHER_APP. Prefer the all-apps home the system uses.
    private static final String LG_SECOND_HOME = "com.lge.secondlauncher.AllAppsLauncherExtension";

    private Intent stockSecondHome() {
        Intent probe = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LAUNCHER_APP")
                .setPackage(LauncherHook.TARGET_PACKAGE);
        java.util.List<android.content.pm.ResolveInfo> found =
                getPackageManager().queryIntentActivities(probe, 0);
        if (found == null || found.isEmpty()) return null;
        android.content.pm.ActivityInfo pick = found.get(0).activityInfo;
        for (android.content.pm.ResolveInfo r : found) {
            if (LG_SECOND_HOME.equals(r.activityInfo.name)) pick = r.activityInfo;
        }
        return new Intent(Intent.ACTION_MAIN).setClassName(pick.packageName, pick.name);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
