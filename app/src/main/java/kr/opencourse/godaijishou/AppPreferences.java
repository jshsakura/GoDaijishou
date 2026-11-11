package kr.opencourse.godaijishou;

import android.content.Context;
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
    // "Bottom screen only": power the top panel off while the swivel is open (system_server hook).
    public static final String KEY_TOP_SCREEN_OFF = "top_screen_off";
    // "No top screen": top panel always off, everything goes to the second screen (system_server hook).
    public static final String KEY_NO_TOP_SCREEN = "no_top_screen";
    // Volume overlay drawn by the system_server hook.
    public static final String KEY_VOLUME_OVERLAY = "volume_overlay";
    // Keep USB debugging (adb) on across reboots (system_server hook).
    public static final String KEY_USB_DEBUG_PIN = "usb_debug_pin";

    // App-only state, kept apart from the provider-exposed settings.
    private static final String MODE_PREF_FILE_NAME = "Godaijishou_mode_prefs";
    private static final String KEY_SYSTEM_HOOK_BOOT = "system_hook_boot";
    private static final long BOOT_MATCH_TOLERANCE_MS = 10_000;

    // Wall-clock time of the current boot; stable within a few ms for the same boot.
    private static long currentBootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** Records that the system_server hook read the settings during this boot. */
    static void markSystemHookSeen(Context context) {
        context.getSharedPreferences(MODE_PREF_FILE_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_SYSTEM_HOOK_BOOT, currentBootTime()).apply();
    }

    /** Whether the system_server hook is loaded in this boot (switches apply without a reboot). */
    public static boolean isSystemHookActive(Context context) {
        long seen = context.getSharedPreferences(MODE_PREF_FILE_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_SYSTEM_HOOK_BOOT, 0);
        return Math.abs(seen - currentBootTime()) < BOOT_MATCH_TOLERANCE_MS;
    }

    public static boolean isNoTopScreen(Context context) {
        return context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_NO_TOP_SCREEN, false);
    }
}
