package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Applies the performance profile: on request from the notification panel, at boot, and when battery saver flips. */
public class PerfProfileReceiver extends BroadcastReceiver {

    // After BOOT_COMPLETED (i.e. after the first unlock), let start-up settle before capping.
    private static final long BOOT_SETTLE_MS = 20_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // Clocks reset to stock at every boot: leave them there through boot and the first
        // unlock (credential check, storage unlock, app start-up).
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        // BOOT_COMPLETED is ordered: release it right away rather than holding every other
        // app's boot receiver for the settle delay.
        PendingResult result = boot ? null : goAsync();
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs = AppPreferences.settings(context);
                int profile = prefs.getInt(AppPreferences.KEY_PERF_PROFILE, PerfProfile.NORMAL);
                if (PerfProfile.ACTION_SET.equals(action) && intent.hasExtra(PerfProfile.EXTRA_PROFILE)) {
                    profile = intent.getIntExtra(PerfProfile.EXTRA_PROFILE, profile);
                    prefs.edit().putInt(AppPreferences.KEY_PERF_PROFILE, profile).commit();
                    // Lets system_server pick up the new value (panel highlight).
                    context.getContentResolver().notifyChange(SettingsProvider.CONTENT_URI, null);
                }
                // Normal is stock: only an explicit switch to it (undoing eco / turbo) writes
                // anything; boot and battery saver re-applies leave LG's clocks alone.
                boolean explicit = PerfProfile.ACTION_SET.equals(action) && intent.hasExtra(PerfProfile.EXTRA_PROFILE);
                if (profile == PerfProfile.NORMAL && !explicit) return;
                if (boot) {
                    try {
                        Thread.sleep(BOOT_SETTLE_MS);
                    } catch (InterruptedException ignored) {
                    }
                }
                PerfProfile.apply(profile);
            } finally {
                if (result != null) result.finish();
            }
        }, "GoDaijishou-perf").start();
    }
}
