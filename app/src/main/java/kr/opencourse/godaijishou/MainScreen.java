package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Main screen mode: the second screen's panel is the phone's main display (DisplaySwap), so
 * the lock screen, status bar, shade, home, brightness and every app run on it natively
 * (system_server, settings thread only).
 *
 * Never a way to get stuck:
 * - A newly applied mode is a trial: the swapped screen asks to keep it, and without a tap
 *   within 20 seconds everything goes back (as when a display resolution is changed). Only a
 *   kept mode is swapped again at the next boots.
 * - Volume up + down (SafetyEscape) restores at once, also across reboots, until the mode is
 *   applied again in the app.
 * - A restore is checked (DisplaySwap): second screen touches back, or a reboot.
 * - Picked without the hooks it needs (SystemSettings.mainReady), a trial does not start.
 */
final class MainScreen {

    // Settings.Global: the mode epoch (AppPreferences.KEY_MODE_EPOCH) the user kept.
    private static final String KEY_KEPT = "godaijishou_main_kept";
    private static final int CONFIRM_SECONDS = 20;

    private static boolean trial;
    // The swap is this mode's own (not the adb test switch): only then does it undo it.
    private static boolean ownSwap;
    private static int trialEpoch;
    private static int secondsLeft;
    private static WindowManager confirmWm;
    private static LinearLayout confirmView;
    private static TextView countView;

    private MainScreen() {
    }

    /** Re-evaluates on every settings or display change. */
    static void apply() {
        boolean picked = SystemSettings.screenMode == AppPreferences.SCREEN_MODE_ORIGINAL;
        if (!picked || TopMirror.isEscaped()) {
            endTrial();
            if (ownSwap && DisplaySwap.isSwapped()) DisplaySwap.set(false);
            ownSwap = false;
            return;
        }
        int epoch = SystemSettings.modeEpoch;
        if (epoch < 0) return; // the app's settings not read yet this boot
        watchPresent();
        watchBrightness();
        // Locked or asleep: unswapped. LG's lock screen takes the unlock on the second screen
        // only (its swivel lock), and swapped it never let go; so the swap is for the unlocked
        // phone, undone when the screen goes off and done again on USER_PRESENT.
        if (!present()) {
            if (trial) {
                escape(); // locked during the trial: not kept
            } else if (ownSwap && DisplaySwap.isSwapped()) {
                DisplaySwap.set(false);
                ownSwap = false;
                sleepSecondScreenSoon();
            }
            return;
        }
        if (kept() == epoch) {
            if (!DisplaySwap.isSwapped()) {
                DisplaySwap.set(true);
                bringAppsLater();
            }
            syncBrightness();
            ownSwap = DisplaySwap.isSwapped();
            return;
        }
        if (trial || !SystemSettings.mainReady) return;
        startTrial(epoch);
    }

    // --- Brightness: swapped, the main display's brightness never reached the second
    // screen's panel (LG drives that panel's backlight on its own, and only as display 4).
    // While swapped the main display's brightness is put on the panel directly. ---

    private static final long BRIGHTNESS_SETTLE_MS = 150; // the display's own update first
    private static boolean brightnessWatched;

    private static void watchBrightness() {
        if (brightnessWatched) return;
        Context c = SystemSettings.context();
        Handler h = SystemSettings.handler();
        if (c == null || h == null) return;
        // The display's own brightness events: also the slider while it is dragged (a
        // temporary brightness, written to the setting only when let go).
        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                if (id == Display.DEFAULT_DISPLAY) syncBrightness();
            }
        };
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(c.getSystemService(DisplayManager.class),
                    "registerDisplayListener", new Class<?>[] {DisplayManager.DisplayListener.class,
                            Handler.class, long.class},
                    listener, h, EVENT_FLAG_DISPLAY_BRIGHTNESS);
            brightnessWatched = true;
            return;
        } catch (Throwable t) {
            ModuleLog.w(t); // no brightness events here: the setting, when the slider is let go
        }
        try {
            c.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
                    new android.database.ContentObserver(h) {
                        @Override
                        public void onChange(boolean selfChange) {
                            h.removeCallbacks(APPLY_BRIGHTNESS);
                            h.postDelayed(APPLY_BRIGHTNESS, BRIGHTNESS_SETTLE_MS);
                        }
                    });
            brightnessWatched = true;
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static final long EVENT_FLAG_DISPLAY_BRIGHTNESS = 1L << 3; // DisplayManager, hidden

    private static final Runnable APPLY_BRIGHTNESS = MainScreen::syncBrightness;

    private static void syncBrightness() {
        if (!DisplaySwap.isSwapped()) return;
        try {
            Context c = SystemSettings.context();
            DisplayManager dm = c.getSystemService(DisplayManager.class);
            float b = (float) de.robv.android.xposed.XposedHelpers.callMethod(dm, "getBrightness",
                    Display.DEFAULT_DISPLAY);
            if (Float.isNaN(b) || b < 0f) return;
            // Brightness to backlight, as this panel's config maps it (nearly linear).
            float backlight = Math.min(1f, BACKLIGHT_MIN + (1f - BACKLIGHT_MIN) * b);
            long id = SystemActions.physicalId(c, Display.DEFAULT_DISPLAY);
            if (id < 0) return;
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Object token = de.robv.android.xposed.XposedHelpers.callStaticMethod(sc, "getPhysicalDisplayToken", id);
            if (token == null) return;
            de.robv.android.xposed.XposedHelpers.callStaticMethod(sc, "setDisplayBrightness", token, backlight);
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static final float BACKLIGHT_MIN = 0.035433073f; // the panel's lowest backlight

    private static void bringAppsLater() {
        Handler h = SystemSettings.handler();
        if (h != null) h.postDelayed(MainScreen::bringAppsToMain, 500); // once the layout settled
    }

    /**
     * Swapped, display 4 is the top panel and off: apps left there from the second screen
     * (opened again they came back there, unseen) move to the main display. Homes stay.
     */
    private static void bringAppsToMain() {
        try {
            Object atm = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityTaskManager"), "getService");
            java.util.List<?> tasks = (java.util.List<?>) de.robv.android.xposed.XposedHelpers.callMethod(
                    atm, "getAllRootTaskInfosOnDisplay", MainModule.SECOND_SCREEN_DISPLAY_ID);
            if (tasks == null) return;
            for (Object info : tasks) {
                try {
                    Object config = de.robv.android.xposed.XposedHelpers.getObjectField(info, "configuration");
                    Object wc = de.robv.android.xposed.XposedHelpers.getObjectField(config, "windowConfiguration");
                    int type = (int) de.robv.android.xposed.XposedHelpers.callMethod(wc, "getActivityType");
                    if (type != 1 /* standard: not home, recents, assistant */) continue;
                    int id = de.robv.android.xposed.XposedHelpers.getIntField(info, "taskId");
                    de.robv.android.xposed.XposedHelpers.callMethod(atm, "moveRootTaskToDisplay", id,
                            Display.DEFAULT_DISPLAY);
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    /**
     * Unswapped while asleep, the second screen's panel comes back as display 4 in a display
     * group of its own, and a new group starts awake: its lock screen shone while the phone
     * slept, so its touches were dropped. Once the layout settled, every group sleeps again
     * (goToSleep sleeps them all); the power key wakes the phone as usual.
     */
    private static void sleepSecondScreenSoon() {
        Handler h = SystemSettings.handler();
        if (h == null) return;
        h.postDelayed(() -> {
            try {
                Context c = SystemSettings.context();
                android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
                if (pm == null || pm.isInteractive()) return; // woken meanwhile: leave it
                de.robv.android.xposed.XposedHelpers.callMethod(pm, "goToSleep",
                        android.os.SystemClock.uptimeMillis());
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }, 500);
    }

    private static boolean presentWatched;

    private static void watchPresent() {
        if (presentWatched) return;
        Context c = SystemSettings.context();
        Handler h = SystemSettings.handler();
        if (c == null || h == null) return;
        try {
            c.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    apply();
                }
            }, filter(android.content.Intent.ACTION_USER_PRESENT, android.content.Intent.ACTION_SCREEN_OFF),
                    null, h);
            presentWatched = true;
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static android.content.IntentFilter filter(String... actions) {
        android.content.IntentFilter f = new android.content.IntentFilter();
        for (String a : actions) f.addAction(a);
        return f;
    }

    private static boolean present() {
        try {
            Context c = SystemSettings.context();
            android.app.KeyguardManager km = c.getSystemService(android.app.KeyguardManager.class);
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            return km != null && !km.isKeyguardLocked() && pm != null && pm.isInteractive();
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }

    /** Volume up + down. */
    static void escape() {
        endTrial();
        ownSwap = false;
        TopMirror.escape();
        DisplaySwap.restoreNow();
    }

    // --- Trial ---

    private static void startTrial(int epoch) {
        Handler h = SystemSettings.handler();
        if (h == null) return;
        DisplaySwap.set(true);
        bringAppsLater();
        ownSwap = DisplaySwap.isSwapped();
        syncBrightness();
        if (!DisplaySwap.isSwapped()) { // could not swap: stay as it is, don't try again
            TopMirror.escape();
            return;
        }
        trial = true;
        trialEpoch = epoch;
        secondsLeft = CONFIRM_SECONDS;
        // Ask once the swapped display has settled.
        h.postDelayed(MainScreen::showConfirm, 1_000);
        h.postDelayed(TICK, 1_000);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!trial) return;
            if (--secondsLeft <= 0) {
                // Nobody kept it (the screen or its touches may not work): back as it was.
                escape();
                return;
            }
            updateCount();
            Handler h = SystemSettings.handler();
            if (h != null) h.postDelayed(this, 1_000);
        }
    };

    private static void keep() {
        if (!trial) return;
        try {
            Settings.Global.putInt(SystemSettings.context().getContentResolver(), KEY_KEPT, trialEpoch);
        } catch (Throwable t) {
            ModuleLog.w(t);
            return; // not stored: let the trial run out rather than swap at every boot unasked
        }
        // LG's home goes 4x4 once, the next time it shows (TopHomeHook); never again after.
        try {
            android.content.ContentResolver r = SystemSettings.context().getContentResolver();
            if (Settings.Global.getInt(r, TopHomeHook.KEY_GRID_ONCE, 0) == 0) {
                Settings.Global.putInt(r, TopHomeHook.KEY_GRID_ONCE, 1);
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
        endTrial();
    }

    private static void endTrial() {
        trial = false;
        Handler h = SystemSettings.handler();
        if (h != null) h.removeCallbacks(TICK);
        removeConfirm();
    }

    private static int kept() {
        try {
            return Settings.Global.getInt(SystemSettings.context().getContentResolver(), KEY_KEPT, -1);
        } catch (Throwable t) {
            ModuleLog.w(t);
            return -1;
        }
    }

    // --- The "keep this screen?" card, on the swapped main display ---

    private static void showConfirm() {
        if (!trial || confirmView != null) return;
        try {
            Context system = SystemSettings.context();
            Display main = system.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (main == null) return;
            Context ui = system.createDisplayContext(main);
            Resources app = system.createPackageContext(BuildConfig.APPLICATION_ID, 0).getResources();
            float dp = ui.getResources().getDisplayMetrics().density;

            LinearLayout card = new LinearLayout(ui);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = Math.round(24 * dp);
            card.setPadding(pad, pad, pad, pad);
            // Fixed width: the countdown changing its text never resizes (and redraws) the window.
            card.setMinimumWidth(Math.round(300 * dp));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0202024);
            bg.setCornerRadius(24 * dp);
            card.setBackground(bg);

            TextView title = new TextView(ui);
            title.setText(text(app, "main_confirm_title", "Keep this as the main screen?"));
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            countView = new TextView(ui);
            countView.setTextColor(0xFFBBBBBB);
            countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            countView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cl.topMargin = Math.round(8 * dp);
            card.addView(countView, cl);

            TextView keep = new TextView(ui);
            keep.setText(text(app, "main_confirm_keep", "Keep"));
            keep.setTextColor(0xFF1A1A1A);
            keep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            keep.setGravity(Gravity.CENTER);
            int kp = Math.round(14 * dp);
            keep.setPadding(kp * 3, kp, kp * 3, kp);
            GradientDrawable kb = new GradientDrawable();
            kb.setColor(0xFFFFC83D);
            kb.setCornerRadius(16 * dp);
            keep.setBackground(kb);
            keep.setOnClickListener(v -> keep());
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            kl.topMargin = Math.round(20 * dp);
            card.addView(keep, kl);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    // System error layer: above the lock screen and apps, and touchable (a secure
                    // system overlay is forced untouchable: taps went through to the app below).
                    2010 /* TYPE_SYSTEM_ERROR */,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            lp.setTitle("GoDaijishou:MainConfirm");

            countText = text(app, "main_confirm_count", "Back to the second screen in %d s");
            confirmWm = ui.getSystemService(WindowManager.class);
            confirmView = card;
            updateCount();
            confirmWm.addView(card, lp);
        } catch (Throwable t) {
            ModuleLog.w(t);
            confirmView = null; // the trial still runs out and restores
        }
    }

    private static String countText = "Back to the second screen in %d s";

    private static void updateCount() {
        TextView v = countView;
        if (v == null) return;
        try {
            v.setText(String.format(countText, secondsLeft));
        } catch (RuntimeException e) {
            v.setText(String.valueOf(secondsLeft));
        }
    }

    private static void removeConfirm() {
        LinearLayout v = confirmView;
        confirmView = null;
        countView = null;
        if (v == null || confirmWm == null) return;
        try {
            confirmWm.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private static String text(Resources r, String name, String fallback) {
        try {
            int id = r.getIdentifier(name, "string", BuildConfig.APPLICATION_ID);
            return id == 0 ? fallback : r.getString(id);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
