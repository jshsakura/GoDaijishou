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
    private SwitchMaterial usbChargeOnlySwitch;
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

        prefs = AppPreferences.settings(this);

        buildLauncherRows();
        topBlackoutSwitch = findViewById(R.id.switch_top_blackout);
        forceRotationSwitch = findViewById(R.id.switch_force_rotation);
        rebootNotice = findViewById(R.id.notice_reboot);
        ((TextView) findViewById(R.id.tv_version)).setText("v" + BuildConfig.VERSION_NAME);

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
        usbChargeOnlySwitch = findViewById(R.id.switch_usb_charge_only);
        bindSystemHookSwitch(R.id.row_usb_charge_only, usbChargeOnlySwitch, AppPreferences.KEY_USB_CHARGE_ONLY);

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
        updateLauncherRows(); // reflect apps installed/removed while we were away
        if (perfButtons != null) stylePerfButtons(perfButtons); // may have changed in the panel
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
                || volumeOverlaySwitch.isChecked() || batteryStripSwitch.isChecked()
                || usbChargeOnlySwitch.isChecked();
        boolean needReboot = (needsSystemHook || prefs.getBoolean(AppPreferences.KEY_FINGERPRINT_OFF, false))
                && !AppPreferences.isSystemHookActive(this);
        // SystemUI features: same notice while the SystemUI hook hasn't reported this boot.
        if (prefs.getBoolean(AppPreferences.KEY_SLIM_NAV_PILL, false)
                && !AppPreferences.isHookActive(this, "systemui")) {
            needReboot = true;
        }
        rebootNotice.setVisibility(needReboot ? View.VISIBLE : View.GONE);
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

    // The parasitic LSPosed manager lives inside this com.android.shell activity.
    // Launching it with the LAUNCH_MANAGER category makes LSPosed show the manager UI.
    private void openLsposed() {
        // 1) Standalone manager app installed (non-parasitic mode)
        Intent app = getPackageManager().getLaunchIntentForPackage(LSPOSED_MANAGER_PACKAGE);
        if (app != null && tryStart(app)) return;

        // 2) Launch the parasitic manager directly
        Intent parasitic = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(LSPOSED_HOST_PACKAGE, LSPOSED_HOST_ACTIVITY))
                .addCategory(LSPOSED_LAUNCH_CATEGORY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(parasitic)) return;

        // 3) Blocked for a normal app: ask the system_server hook to open it
        requestSystemAction(SystemActions.OP_OPEN_LSPOSED);
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
            row.findViewById(R.id.choice_check).setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            TextView desc = row.findViewById(R.id.choice_desc);
            boolean installed = pkg == null || isInstalled(pkg);
            if (pkg == null) {
                desc.setText(R.string.launcher_stock_desc);
                desc.setTextColor(getColor(R.color.ft_secondary));
            } else if (installed) {
                desc.setText(R.string.launcher_installed);
                desc.setTextColor(getColor(R.color.ft_good));
            } else {
                desc.setText(R.string.launcher_not_installed);
                desc.setTextColor(getColor(R.color.ft_secondary));
            }
            // Target package under the status, small and quiet (stock: LG's second home).
            String target = pkg != null ? pkg : LauncherHook.TARGET_PACKAGE;
            android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(desc.getText());
            text.append('\n');
            int start = text.length();
            text.append(target);
            int end = text.length();
            text.setSpan(new ForegroundColorSpan(getColor(R.color.ft_secondary)), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.RelativeSizeSpan(0.9f), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.TypefaceSpan("monospace"), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        if (dm == null || !Screens.isSecondScreenOn(dm)) return;
        Intent home;
        if (pkg != null) {
            home = getPackageManager().getLaunchIntentForPackage(pkg);
            if (home == null) return;
        } else {
            home = stockSecondHome();
            if (home == null) return;
        }
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        home.putExtra(LauncherHook.EXTRA_REFRESH, true);
        try {
            startActivity(home, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(MainModule.SECOND_SCREEN_DISPLAY_ID).toBundle());
        } catch (RuntimeException ignored) {
            // The setting is saved either way; it applies on the next home press.
        }
    }

    // LG's second home as an explicit component, so it starts at once like the others
    // (an implicit SECONDARY_HOME intent may not resolve for an ordinary app).
    private Intent stockSecondHome() {
        Intent probe = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .setPackage(LauncherHook.TARGET_PACKAGE);
        java.util.List<android.content.pm.ResolveInfo> found =
                getPackageManager().queryIntentActivities(probe, 0);
        if (found != null && !found.isEmpty()) {
            android.content.pm.ActivityInfo a = found.get(0).activityInfo;
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_SECONDARY_HOME)
                    .setClassName(a.packageName, a.name);
        }
        return getPackageManager().getLaunchIntentForPackage(LauncherHook.TARGET_PACKAGE);
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
