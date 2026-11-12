package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Performance profiles: CPU/GPU clock caps against heat, chosen in the app or the notification
 * panel. Clock files are root-only (even system_server can't write them), so the app applies
 * them through Magisk's su; the panel in system_server only sends a broadcast. Re-applied at boot and whenever battery
 * saver flips (LG's saver rewrites clocks on its own).
 *
 * ECO: every core and the GPU capped hard, for the least battery and heat; normal throttling.
 * NORMAL: a little under stock, normal throttling.
 * PERFORMANCE: stock max, raised minimums, LG thermal mitigation relaxed.
 *
 * SD765G (lito): policy0 = 6 little cores (1.8 GHz), policy6 = big (2.2), policy7 = prime (2.4),
 * GPU 625 MHz. Targets are picked from each policy's own frequency table at apply time:
 * ECO 1.36 / 1.09 / 1.09 GHz, NORMAL 1.80 / 1.90 / 2.00 GHz, PERFORMANCE 1.80 / 2.21 / 2.40 GHz.
 */
final class PerfProfile {

    static final int NORMAL = 0;
    static final int ECO = 1;
    static final int PERFORMANCE = 2;

    static final String ACTION_SET = BuildConfig.APPLICATION_ID + ".SET_PERF_PROFILE";
    static final String EXTRA_PROFILE = "profile";

    // LG thermald mitigation switch (true = mitigation off, i.e. less throttling).
    private static final String THROTTLE_PROP = "persist.vendor.lge.sys.thermald_miti_off";
    private static final long STOCK = 9_999_999_999L;

    private PerfProfile() {
    }

    /** From system_server: ask the app to apply a profile (null = re-apply the saved one). */
    static void request(Context system, Integer profile) {
        try {
            Intent intent = new Intent(ACTION_SET)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            if (profile != null) intent.putExtra(EXTRA_PROFILE, profile.intValue());
            system.sendBroadcastAsUser(intent, Process.myUserHandle());
        } catch (Throwable ignored) {
        }
    }

    /** App side, background thread: writes the clocks as root. */
    static boolean apply(int profile) {
        // Max kHz per cluster (little, big, prime), GPU max Hz, min kHz (little, big+prime; 0 = stock).
        long littleMax, bigMax, primeMax, gpuMax, littleMin, bigMin;
        switch (profile) {
            case ECO:
                littleMax = 1_400_000; bigMax = 1_100_000; primeMax = 1_100_000; gpuMax = 300_000_000L;
                littleMin = 0; bigMin = 0;
                break;
            case PERFORMANCE: // a notch above stock: same top clocks (the chip's own table, no
                // overclock), higher floors so it ramps up at once, thermal mitigation relaxed
                littleMax = STOCK; bigMax = STOCK; primeMax = STOCK; gpuMax = STOCK;
                littleMin = 1_000_000; bigMin = 1_200_000;
                break;
            default: // NORMAL: exactly stock (every cluster and the GPU back to their full range)
                littleMax = STOCK; bigMax = STOCK; primeMax = STOCK; gpuMax = STOCK;
                littleMin = 0; bigMin = 0;
                break;
        }
        String script = ""
                // Largest table entry <= target (lowest entry if none).
                + "pick() { best=''; lo=''; for f in $1; do { [ -z \"$lo\" ] || [ $f -lt $lo ]; } && lo=$f;"
                + " [ $f -le $2 ] && { [ -z \"$best\" ] || [ $f -gt $best ]; } && best=$f; done;"
                + " echo ${best:-$lo}; }\n"
                + "cpu() { p=/sys/devices/system/cpu/cpufreq/policy$1; [ -d $p ] || return;"
                + " t=$(cat $p/scaling_available_frequencies);"
                + " cat $p/cpuinfo_min_freq > $p/scaling_min_freq;"
                + " pick \"$t\" $2 > $p/scaling_max_freq;"
                + " [ $3 -gt 0 ] && pick \"$t\" $3 > $p/scaling_min_freq; }\n"
                + "cpu 0 " + littleMax + " " + littleMin + "\n"
                + "cpu 6 " + bigMax + " " + bigMin + "\n"
                + "cpu 7 " + primeMax + " " + bigMin + "\n"
                + "g=/sys/class/kgsl/kgsl-3d0\n"
                + "[ -d $g ] && pick \"$(cat $g/gpu_available_frequencies)\" " + gpuMax + " > $g/max_gpuclk\n"
                // LG thermal mitigation: relaxed only in performance, stock (on) otherwise.
                // persist.* lives on in /data/property even after the module that set it is gone,
                // so write the persistent copy too (-p), not only the runtime value.
                + "if command -v resetprop >/dev/null; then rp='resetprop -p'; else rp=setprop; fi\n"
                + "$rp " + THROTTLE_PROP + " " + (profile == PERFORMANCE ? "true" : "false") + "\n";
        try {
            java.lang.Process su = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (OutputStream in = su.getOutputStream()) {
                in.write(script.getBytes(StandardCharsets.UTF_8));
                in.write("exit 0\n".getBytes(StandardCharsets.UTF_8));
            }
            su.getInputStream().readAllBytes();
            return su.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
