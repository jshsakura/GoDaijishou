package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Asks GitHub for the latest release (at most every 6 hours) and reports it when it is newer
 * than this build. One anonymous GET to the public releases API; nothing about the device is sent.
 */
final class UpdateCheck {

    static final String RELEASES_PAGE = "https://github.com/jshsakura/GoDaijishou/releases/latest";
    private static final String API = "https://api.github.com/repos/jshsakura/GoDaijishou/releases/latest";
    private static final long INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final String PREFS = "update_check";

    interface Listener {
        void onNewer(String version);
    }

    private UpdateCheck() {
    }

    static void run(Context context, Listener listener) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String known = prefs.getString("latest", null);
        if (known != null && isNewer(known, BuildConfig.VERSION_NAME)) listener.onNewer(known);
        if (System.currentTimeMillis() - prefs.getLong("checked_at", 0) < INTERVAL_MS) return;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String latest = fetch();
            if (latest == null) return;
            prefs.edit().putString("latest", latest).putLong("checked_at", System.currentTimeMillis()).apply();
            if (isNewer(latest, BuildConfig.VERSION_NAME)) main.post(() -> listener.onNewer(latest));
        }, "GoDaijishou-update").start();
    }

    private static String fetch() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "GoDaijishou");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                String tag = new JSONObject(out.toString("UTF-8")).optString("tag_name", "");
                tag = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                return tag.isEmpty() ? null : tag;
            }
        } catch (Throwable t) {
            return null; // offline or rate-limited: try again next time
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // "1.10" > "1.9"; missing parts count as 0; anything non-numeric ends the comparison.
    static boolean isNewer(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = part(x, i), q = part(y, i);
            if (p < 0 || q < 0) return false;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int part(String[] s, int i) {
        if (i >= s.length) return 0;
        try {
            return Integer.parseInt(s[i].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
