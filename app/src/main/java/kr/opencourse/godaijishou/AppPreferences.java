package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * SharedPreferences file names, keys and helpers.
 *
 * Every feature switch lives in PREF_FILE_NAME and is read by the hooks through
 * SettingsProvider; the app itself draws nothing over other apps.
 */
public class AppPreferences {
    public static final String PREF_FILE_NAME = "Godaijishou_prefs";
    public static final String KEY_LAUNCHER_PACKAGE = "launcher_package";
    public static final String KEY_LAUNCHER_ACTIVITY = "launcher_activity";
    public static final String KEY_LAUNCHER_NAME = "launcher_name";
    // Rotation forcing (system_server hook).
    public static final String KEY_FORCE_ROTATION = "force_rotation";
    // Power menu on the second screen (system_server hook).
    public static final String KEY_POWER_MENU_SECOND_SCREEN = "power_menu_second_screen";
    // "Turn off top screen" (second screen mode): power the top panel off while the swivel is open (system_server hook).
    public static final String KEY_TOP_SCREEN_OFF = "top_screen_off";
    // "No top screen": top panel always off, everything goes to the second screen (system_server hook).
    // Retired: folded into KEY_TOP_SCREEN_OFF (see migrateNoTopScreen).
    private static final String KEY_NO_TOP_SCREEN = "no_top_screen";
    // Volume overlay drawn by the system_server hook.
    public static final String KEY_VOLUME_OVERLAY = "volume_overlay";
    // Status label on the second screen (system_server hook). Key kept from the battery strip.
    public static final String KEY_BATTERY_STRIP = "battery_strip";
    // Hide the status label while the second screen's top app is fullscreen (system_server hook).
    public static final String KEY_LABEL_HIDE_FULLSCREEN = "label_hide_fullscreen";
    // Battery bar look: STRIP_STYLE_PILL (floating pill) or STRIP_STYLE_LINE (thin full-width line).
    public static final String KEY_BATTERY_STRIP_STYLE = "battery_strip_style";
    public static final int STRIP_STYLE_PILL = 0, STRIP_STYLE_LINE = 1;
    // Where its content (and the pull-down spot) sits: STRIP_POS_LEFT / CENTER / RIGHT.
    public static final String KEY_BATTERY_STRIP_POS = "battery_strip_pos";
    public static final int STRIP_POS_LEFT = 0, STRIP_POS_CENTER = 1, STRIP_POS_RIGHT = 2;
    // With the swivel open: the second screen as its own screen (SCREEN_MODE_SECOND), or as
    // the top screen itself, the phone as when folded (SCREEN_MODE_ORIGINAL, TopMirror).
    public static final String KEY_SCREEN_MODE = "screen_mode";
    public static final int SCREEN_MODE_SECOND = 0, SCREEN_MODE_ORIGINAL = 1;
    // Bumped on every mode Apply: a volume up + down escape lasts until the next one.
    public static final String KEY_MODE_EPOCH = "mode_epoch";
    // Skip LG's swivel-mode camera notices (scope com.lge.popupcamera).
    public static final String KEY_HIDE_SWIVEL_WARNINGS = "hide_swivel_warnings";
    // Thinner, narrower gesture pill that hides until the bottom edge is touched (scope SystemUI).
    public static final String KEY_SLIM_NAV_PILL = "slim_nav_pill";
    // Collapse the strip under the keyboard (hide-keyboard / switcher buttons) (scope SystemUI + keyboard).
    public static final String KEY_HIDE_IME_NAV_STRIP = "hide_ime_nav_strip";
    // Report no fingerprint sensor, so nothing offers fingerprint / passkeys (system_server hook).
    public static final String KEY_FINGERPRINT_OFF = "fingerprint_off";
    // CPU/GPU performance profile (PerfProfile.NORMAL / ECO / PERFORMANCE), applied by the app as root.
    public static final String KEY_PERF_PROFILE = "perf_profile";
    // USB mode on connection: false = file transfer (MTP), true = charging only (system_server hook).
    public static final String KEY_USB_CHARGE_ONLY = "usb_charge_only";
    // Write the hooks' swallowed failures to the LSPosed log (every hooked process).
    public static final String KEY_DEBUG_LOG = "debug_log";

    // App-only state, kept apart from the provider-exposed settings.
    private static final String MODE_PREF_FILE_NAME = "Godaijishou_mode_prefs";
    private static final String KEY_SYSTEM_HOOK_BOOT = "system_hook_boot";
    private static final String KEY_SYSTEM_HOOK_REV = "system_hook_rev";
    private static final long BOOT_MATCH_TOLERANCE_MS = 10_000;

    private static volatile boolean migrated = false;

    /**
     * Settings live in device-protected storage, so the system_server hooks can read them
     * right after boot, before the first unlock (rotation, label and the rest apply from the
     * lock screen on). Older installs kept them in credential storage: moved over once.
     */
    static android.content.SharedPreferences prefs(Context context, String name) {
        Context dp = context.isDeviceProtectedStorage() ? context : context.createDeviceProtectedStorageContext();
        if (!migrated) {
            android.os.UserManager um = context.getSystemService(android.os.UserManager.class);
            if (um != null && um.isUserUnlocked()) {
                try {
                    dp.moveSharedPreferencesFrom(context, PREF_FILE_NAME);
                    dp.moveSharedPreferencesFrom(context, MODE_PREF_FILE_NAME);
                } catch (RuntimeException ignored) {
                }
                migrated = true;
            }
        }
        return dp.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static android.content.SharedPreferences settings(Context context) {
        return prefs(context, PREF_FILE_NAME);
    }

    // Wall-clock time of the current boot; stable within a few ms for the same boot.
    private static long currentBootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** Records that the system_server hook read the settings during this boot. */
    static void markSystemHookSeen(Context context, String rev) {
        prefs(context, MODE_PREF_FILE_NAME).edit()
                .putLong(KEY_SYSTEM_HOOK_BOOT, currentBootTime())
                .putString(KEY_SYSTEM_HOOK_REV, rev)
                .apply();
    }

    /** Whether the running hook was built from different code than this APK (needs a reboot). */
    public static boolean isHookOutdated(Context context) {
        String rev = prefs(context, MODE_PREF_FILE_NAME).getString(KEY_SYSTEM_HOOK_REV, null);
        return !BuildConfig.HOOK_REV.equals(rev);
    }

    /** Whether the system_server hook is loaded in this boot (switches apply without a reboot). */
    public static boolean isSystemHookActive(Context context) {
        long seen = prefs(context, MODE_PREF_FILE_NAME)
                .getLong(KEY_SYSTEM_HOOK_BOOT, 0);
        return Math.abs(seen - currentBootTime()) < BOOT_MATCH_TOLERANCE_MS;
    }

    // Same for hooks in other processes (e.g. "systemui"): each reports once per boot.
    static void markHookSeen(Context context, String name) {
        prefs(context, MODE_PREF_FILE_NAME)
                .edit().putLong(KEY_SYSTEM_HOOK_BOOT + "_" + name, currentBootTime()).apply();
    }

    // Provider column: main screen mode can run (every hook it needs is loaded this boot).
    static final String COL_MAIN_READY = "main_ready";

    /**
     * Main screen mode needs system_server, SystemUI (gestures) and LG's home (normal home, not
     * the swivel one). Without any of them it falls back to second screen mode.
     */
    public static boolean mainModeReady(Context context) {
        return isSystemHookActive(context) && isHookActive(context, "systemui")
                && isHookActive(context, TopHomeHook.HOOK_NAME);
    }

    public static boolean isHookActive(Context context, String name) {
        long seen = prefs(context, MODE_PREF_FILE_NAME)
                .getLong(KEY_SYSTEM_HOOK_BOOT + "_" + name, 0);
        return Math.abs(seen - currentBootTime()) < BOOT_MATCH_TOLERANCE_MS;
    }

    /**
     * "No top screen" was folded into "bottom screen only" (the top screen is shown on demand
     * by mirroring now): an old "no top screen" choice carries over as "bottom screen only".
     */
    public static void migrateNoTopScreen(Context context) {
        SharedPreferences p = settings(context);
        if (!p.contains(KEY_NO_TOP_SCREEN)) return;
        SharedPreferences.Editor e = p.edit();
        if (p.getBoolean(KEY_NO_TOP_SCREEN, false)) e.putBoolean(KEY_TOP_SCREEN_OFF, true);
        e.remove(KEY_NO_TOP_SCREEN).apply();
    }
}
