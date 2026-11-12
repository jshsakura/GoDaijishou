package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Applies the performance profile: on request from the notification panel, at boot, and when battery saver flips. */
public class PerfProfileReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs = AppPreferences.settings(context);
                int profile = prefs.getInt(AppPreferences.KEY_PERF_PROFILE, PerfProfile.NORMAL);
                if (PerfProfile.ACTION_SET.equals(intent.getAction()) && intent.hasExtra(PerfProfile.EXTRA_PROFILE)) {
                    profile = intent.getIntExtra(PerfProfile.EXTRA_PROFILE, profile);
                    prefs.edit().putInt(AppPreferences.KEY_PERF_PROFILE, profile).commit();
                    // Lets system_server pick up the new value (panel highlight).
                    context.getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
                }
                PerfProfile.apply(profile);
            } finally {
                result.finish();
            }
        }, "GoDaijishou-perf").start();
    }
}
