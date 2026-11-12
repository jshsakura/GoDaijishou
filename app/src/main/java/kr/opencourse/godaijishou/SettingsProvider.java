// SettingsProvider.java
package kr.opencourse.godaijishou;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import androidx.annotation.NonNull;

import java.util.Objects;

public class SettingsProvider extends ContentProvider {

    // Authority other processes (the hooks) use to find this provider
    private static final String AUTHORITY = "kr.opencourse.godaijishou.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = Objects.requireNonNull(getContext());
        // The system_server hook (RotationHook) reads settings here on every boot.
        // Recording that lets the settings screen tell, at no extra cost, whether
        // the hook is loaded in this boot (used for the reboot notice).
        if (Binder.getCallingUid() == Process.SYSTEM_UID) {
            AppPreferences.markSystemHookSeen(context);
        }
        SharedPreferences prefs = AppPreferences.settings(context);

        String targetPackage = prefs.getString(AppPreferences.KEY_LAUNCHER_PACKAGE, null);
        String targetActivity = prefs.getString(AppPreferences.KEY_LAUNCHER_ACTIVITY, null);
        String forceRotation = prefs.getBoolean(AppPreferences.KEY_FORCE_ROTATION, false) ? "1" : "0";
        String powerMenu = prefs.getBoolean(AppPreferences.KEY_POWER_MENU_SECOND_SCREEN, false) ? "1" : "0";
        String topOff = prefs.getBoolean(AppPreferences.KEY_TOP_SCREEN_OFF, false) ? "1" : "0";
        String volume = prefs.getBoolean(AppPreferences.KEY_VOLUME_OVERLAY, false) ? "1" : "0";
        String noTop = prefs.getBoolean(AppPreferences.KEY_NO_TOP_SCREEN, false) ? "1" : "0";
        String usbPin = prefs.getBoolean(AppPreferences.KEY_USB_DEBUG_PIN, false) ? "1" : "0";
        String batteryStrip = prefs.getBoolean(AppPreferences.KEY_BATTERY_STRIP, false) ? "1" : "0";
        String labelHideFullscreen = prefs.getBoolean(AppPreferences.KEY_LABEL_HIDE_FULLSCREEN, false) ? "1" : "0";
        String hideSwivel = prefs.getBoolean(AppPreferences.KEY_HIDE_SWIVEL_WARNINGS, false) ? "1" : "0";
        String slimPill = prefs.getBoolean(AppPreferences.KEY_SLIM_NAV_PILL, false) ? "1" : "0";
        String imeStrip = prefs.getBoolean(AppPreferences.KEY_HIDE_IME_NAV_STRIP, false) ? "1" : "0";
        String fingerprintOff = prefs.getBoolean(AppPreferences.KEY_FINGERPRINT_OFF, false) ? "1" : "0";
        String perfProfile = String.valueOf(prefs.getInt(AppPreferences.KEY_PERF_PROFILE, PerfProfile.NORMAL));

        MatrixCursor cursor = new MatrixCursor(new String[]{
                AppPreferences.KEY_LAUNCHER_PACKAGE,
                AppPreferences.KEY_LAUNCHER_ACTIVITY,
                AppPreferences.KEY_FORCE_ROTATION,
                AppPreferences.KEY_POWER_MENU_SECOND_SCREEN,
                AppPreferences.KEY_TOP_SCREEN_OFF,
                AppPreferences.KEY_NO_TOP_SCREEN,
                AppPreferences.KEY_USB_DEBUG_PIN,
                AppPreferences.KEY_VOLUME_OVERLAY,
                AppPreferences.KEY_BATTERY_STRIP,
                AppPreferences.KEY_LABEL_HIDE_FULLSCREEN,
                AppPreferences.KEY_PERF_PROFILE,
                AppPreferences.KEY_HIDE_SWIVEL_WARNINGS,
                AppPreferences.KEY_SLIM_NAV_PILL,
                AppPreferences.KEY_HIDE_IME_NAV_STRIP,
                AppPreferences.KEY_FINGERPRINT_OFF
        });
        cursor.addRow(new Object[]{targetPackage, targetActivity, forceRotation, powerMenu, topOff, noTop, usbPin, volume,
                batteryStrip, labelHideFullscreen, perfProfile, hideSwivel, slimPill, imeStrip, fingerprintOff});
        return cursor;
    }

    // Read-only provider: the methods below are unused.
    @Override
    public String getType(@NonNull Uri uri) { return null; }
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
