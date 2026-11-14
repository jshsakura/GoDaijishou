package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The screen mode picked with volume up + down (MainScreen.toggle, system_server), written
 * where the app keeps it, so the app and the phone always show the same mode. Only the
 * system can send it (DEVICE_POWER, as for the performance profile).
 */
public class ModeSwitchReceiver extends BroadcastReceiver {

    static final String ACTION = BuildConfig.APPLICATION_ID + ".SET_SCREEN_MODE";
    static final String EXTRA_MODE = "mode";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction()) || !intent.hasExtra(EXTRA_MODE)) return;
        int mode = intent.getIntExtra(EXTRA_MODE, AppPreferences.SCREEN_MODE_SECOND);
        if (mode != AppPreferences.SCREEN_MODE_SECOND && mode != AppPreferences.SCREEN_MODE_ORIGINAL) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                SharedPreferences prefs = AppPreferences.settings(context);
                if (prefs.getInt(AppPreferences.KEY_SCREEN_MODE, AppPreferences.SCREEN_MODE_SECOND) == mode) return;
                // Main screen mode needs its hooks, as when applied in the app.
                if (mode == AppPreferences.SCREEN_MODE_ORIGINAL && !AppPreferences.mainModeReady(context)) return;
                prefs.edit().putInt(AppPreferences.KEY_SCREEN_MODE, mode)
                        .putInt(AppPreferences.KEY_MODE_EPOCH, prefs.getInt(AppPreferences.KEY_MODE_EPOCH, 0) + 1)
                        .commit();
                context.getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
            } catch (Throwable t) {
                ModuleLog.w(t);
            } finally {
                result.finish();
            }
        }).start();
    }
}
