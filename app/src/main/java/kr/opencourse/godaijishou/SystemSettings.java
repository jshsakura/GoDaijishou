package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.Settings;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Module settings cache shared by the system_server hooks (rotation, power menu).
 *
 * - Hooks only read the volatile flags below (no IPC, no allocation).
 * - Values are read from the app's provider on a background thread, once at startup
 *   and again only when the provider notifies a change.
 * - The provider cannot be read before the first user unlock, so it is read once more
 *   on USER_UNLOCKED / BOOT_COMPLETED. No polling.
 */
final class SystemSettings {

    /** Whether the top display should be treated as unusable right now. */
    static boolean topUnavailable() {
        return noTopScreen || (topScreenOff && secondScreenOn);
    }

    static volatile boolean forceRotation = false;
    static volatile boolean powerMenuOnSecondScreen = false;
    // "Bottom screen only": power the top panel off while the swivel is open.
    static volatile boolean topScreenOff = false;
    // "No top screen": top panel always off, every launch goes to the second screen.
    static volatile boolean noTopScreen = false;
    // USB connections come up as charging only.
    static volatile boolean usbChargeOnly = false;
    // Volume overlay drawn by system_server.
    static volatile boolean volumeOverlay = false;
    // Status label (Wi-Fi, mobile data, notifications, battery) on the second screen.
    static volatile boolean batteryStrip = false;
    // Hide the status label while the second screen shows a fullscreen app.
    static volatile boolean labelHideFullscreen = false;
    // Current performance profile (PerfProfile.*), for the panel's highlight.
    static volatile int perfProfile = PerfProfile.NORMAL;
    // Report no fingerprint sensor (FingerprintHook).
    static volatile boolean fingerprintOff = false;
    // Swivel state, kept current by a display listener so hooks never query DisplayManager
    // (the rotation hooks run under the WindowManager lock).
    static volatile boolean secondScreenOn = false;

    private static volatile boolean initialized = false;
    // The settings have been read at least once this boot.
    private static volatile boolean loaded = false;
    private static ContentResolver resolver;
    private static Handler handler;
    private static Context systemContext;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private SystemSettings() {
    }

    /** Idempotent; safe to call from any hook. */
    static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        try {
            systemContext = context;
            resolver = context.getContentResolver();
            HandlerThread thread = new HandlerThread("GoDaijishou-settings", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());

            // Privileged actions requested by the app (power, screenshot, LSPosed).
            // Each feature installs on its own: one failing (e.g. a service not up yet this
            // early in boot) must not take the settings observer and the others down with it.
            safely(() -> SystemActions.register(context, handler));
            // Volume overlay (drawn here, so the app needs no overlay permission).
            safely(() -> SystemVolumeOverlay.install(context, handler));
            // Status label on the second screen (same: drawn here, no permission).
            safely(() -> StatusLabel.install(context, handler));
            // USB charging only: each new connection comes up without file transfer.
            context.registerReceiver(new BroadcastReceiver() {
                private boolean wasConnected;

                @Override
                public void onReceive(Context c, Intent intent) {
                    boolean connected = intent.getBooleanExtra("connected", false);
                    if (connected && !wasConnected) SystemActions.chargeOnlyUsb(context);
                    wasConnected = connected;
                }
            }, new IntentFilter("android.hardware.usb.action.USB_STATE"), null, handler);

            resolver.registerContentObserver(SettingsProvider.CONTENT_URI, false,
                    new ContentObserver(handler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            reload();
                        }
                    });

            // Settings are in device-protected storage: readable from LOCKED_BOOT_COMPLETED on,
            // so rotation and the rest apply on the lock screen right after boot. The screen
            // events retry until the first read succeeds (the provider can't start any earlier).
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    String action = intent.getAction();
                    boolean retryOnly = Intent.ACTION_SCREEN_ON.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action);
                    if (!retryOnly || !loaded) reload();
                }
            }, filter, null, handler);

            // LG's battery saver rewrites clocks when it flips: put the profile back.
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    PerfProfile.request(context, null);
                }
            }, new IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), null, handler);

            DisplayManager dm = context.getSystemService(DisplayManager.class);
            // Top panel power follows settings, the swivel state and display 0 state changes.
            addListener(() -> TopPanel.apply(dm));
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId == MainModule.SECOND_SCREEN_DISPLAY_ID) updateSecondScreen(dm);
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId == MainModule.SECOND_SCREEN_DISPLAY_ID) updateSecondScreen(dm);
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == MainModule.SECOND_SCREEN_DISPLAY_ID) updateSecondScreen(dm);
                    else if (displayId == Display.DEFAULT_DISPLAY) TopPanel.apply(dm);
                }
            }, handler);

            handler.post(() -> updateSecondScreen(dm));
            handler.post(SystemSettings::loadCache);
            handler.post(SystemSettings::reload);
        } catch (Throwable ignored) {
            // Init failed: every feature stays disabled and system behavior is untouched.
        }
    }

    private static void safely(Runnable install) {
        try {
            install.run();
        } catch (Throwable ignored) {
            // That feature stays off; the rest keeps working.
        }
    }

    /** Background handler for hook work that must stay off the caller's thread. */
    static Handler handler() {
        return handler;
    }

    static Context context() {
        return systemContext;
    }

    static ContentResolver resolver() {
        return resolver;
    }

    /** Runs on the settings thread after any setting or the swivel state changed. */
    static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private static void updateSecondScreen(DisplayManager dm) {
        Display display = dm.getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
        boolean on = display != null && display.getState() == Display.STATE_ON;
        if (!loaded) reload(); // second screen coming up early in boot: try the settings again
        if (on != secondScreenOn) {
            secondScreenOn = on;
            for (Runnable l : listeners) l.run();
        }
    }

    private static void reload() {
        Cursor cursor = null;
        try {
            cursor = resolver.query(SettingsProvider.CONTENT_URI, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return; // keep current values
            loaded = true;
            boolean rotation = readFlag(cursor, AppPreferences.KEY_FORCE_ROTATION);
            boolean powerMenu = readFlag(cursor, AppPreferences.KEY_POWER_MENU_SECOND_SCREEN);
            boolean topOff = readFlag(cursor, AppPreferences.KEY_TOP_SCREEN_OFF);
            boolean noTop = readFlag(cursor, AppPreferences.KEY_NO_TOP_SCREEN);
            boolean chargeOnly = readFlag(cursor, AppPreferences.KEY_USB_CHARGE_ONLY);
            boolean volume = readFlag(cursor, AppPreferences.KEY_VOLUME_OVERLAY);
            boolean strip = readFlag(cursor, AppPreferences.KEY_BATTERY_STRIP);
            boolean hideFullscreen = readFlag(cursor, AppPreferences.KEY_LABEL_HIDE_FULLSCREEN);
            fingerprintOff = readFlag(cursor, AppPreferences.KEY_FINGERPRINT_OFF);
            boolean changed = rotation != forceRotation || powerMenu != powerMenuOnSecondScreen
                    || topOff != topScreenOff || noTop != noTopScreen
                    || chargeOnly != usbChargeOnly || volume != volumeOverlay
                    || strip != batteryStrip || hideFullscreen != labelHideFullscreen;
            forceRotation = rotation;
            powerMenuOnSecondScreen = powerMenu;
            topScreenOff = topOff;
            noTopScreen = noTop;
            usbChargeOnly = chargeOnly;
            volumeOverlay = volume;
            batteryStrip = strip;
            labelHideFullscreen = hideFullscreen;
            int idx = cursor.getColumnIndex(AppPreferences.KEY_PERF_PROFILE);
            if (idx >= 0) {
                try {
                    perfProfile = Integer.parseInt(cursor.getString(idx));
                } catch (RuntimeException ignored) {
                }
            }
            saveCache();
            saveNavBar(readFlag(cursor, AppPreferences.KEY_SLIM_NAV_PILL),
                    readFlag(cursor, AppPreferences.KEY_HIDE_IME_NAV_STRIP));
            if (changed) {
                for (Runnable l : listeners) l.run();
            }
        } catch (Throwable ignored) {
            // Provider not readable yet (e.g. before unlock): keep current values.
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // Last read settings, kept in Settings.Global (system_server's own store) so the next boot
    // starts with them before the app's provider can run: the second screen comes up with the
    // right rotation from the first frame, not only from LOCKED_BOOT_COMPLETED on.
    private static final String CACHE_KEY = "godaijishou_flags";
    private static final String[] CACHE_ORDER = {"rotation", "powerMenu", "topOff", "noTop", "chargeOnly",
            "volume", "strip", "hideFullscreen", "fingerprintOff"};

    private static void saveCache() {
        try {
            boolean[] f = {forceRotation, powerMenuOnSecondScreen, topScreenOff, noTopScreen,
                    usbChargeOnly, volumeOverlay, batteryStrip, labelHideFullscreen, fingerprintOff};
            StringBuilder b = new StringBuilder();
            for (boolean v : f) b.append(v ? '1' : '0');
            b.append(':').append(perfProfile);
            String value = b.toString();
            if (!value.equals(Settings.Global.getString(resolver, CACHE_KEY))) {
                Settings.Global.putString(resolver, CACHE_KEY, value);
            }
        } catch (Throwable ignored) {
        }
    }

    // Bottom-bar options for SystemUI and the keyboard apps (NavBarHook). Settings.Global is
    // readable by every app; our provider isn't visible to a keyboard (package visibility).
    static final String NAVBAR_KEY = "godaijishou_navbar";

    private static void saveNavBar(boolean slimPill, boolean imeStripOff) {
        try {
            String value = (slimPill ? "1" : "0") + (imeStripOff ? "1" : "0");
            if (!value.equals(Settings.Global.getString(resolver, NAVBAR_KEY))) {
                Settings.Global.putString(resolver, NAVBAR_KEY, value);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Applies the cached settings; only until the real ones have been read. */
    private static void loadCache() {
        if (loaded) return;
        try {
            String value = Settings.Global.getString(resolver, CACHE_KEY);
            if (value == null || value.indexOf(':') < 8) return;
            forceRotation = value.charAt(0) == '1';
            powerMenuOnSecondScreen = value.charAt(1) == '1';
            topScreenOff = value.charAt(2) == '1';
            noTopScreen = value.charAt(3) == '1';
            usbChargeOnly = value.charAt(4) == '1';
            volumeOverlay = value.charAt(5) == '1';
            batteryStrip = value.charAt(6) == '1';
            labelHideFullscreen = value.charAt(7) == '1';
            fingerprintOff = value.length() > 8 && value.charAt(8) == '1';
            int colon = value.indexOf(':');
            if (colon > 0) perfProfile = Integer.parseInt(value.substring(colon + 1));
            for (Runnable l : listeners) l.run();
        } catch (Throwable ignored) {
            // Settings store not up yet this early: the provider read comes later anyway.
        }
    }

    private static boolean readFlag(Cursor cursor, String column) {
        int idx = cursor.getColumnIndex(column);
        return idx >= 0 && "1".equals(cursor.getString(idx));
    }
}
