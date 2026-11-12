package kr.opencourse.godaijishou;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Display;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;

/**
 * Privileged actions the app asks system_server to perform, so the app itself never needs
 * root: power off, restart, recovery, screenshot of a display, opening the LSPosed manager
 * and keeping USB debugging on.
 *
 * The app sends an explicit broadcast to package "android". The receiver is registered with
 * a signature-level permission, so only an app signed with the same key can trigger it.
 */
final class SystemActions {

    static final String ACTION = "kr.opencourse.godaijishou.action.SYSTEM";
    static final String PERMISSION = "kr.opencourse.godaijishou.permission.SYSTEM_ACTION";
    static final String EXTRA_OP = "op";
    static final String EXTRA_DISPLAY = "display";

    static final String OP_POWER_OFF = "power_off";
    static final String OP_RESTART = "restart";
    static final String OP_RECOVERY = "recovery";
    static final String OP_SCREENSHOT = "screenshot";
    static final String OP_OPEN_LSPOSED = "open_lsposed";

    private static final String LSPOSED_HOST = "com.android.shell";
    private static final String LSPOSED_HOST_ACTIVITY = "com.android.shell.BugreportWarningActivity";
    private static final String LSPOSED_CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER";

    private SystemActions() {
    }

    // --- App side ---

    /** Sends an action to the system_server hook. */
    static void send(Context context, String op, int displayId) {
        context.sendBroadcast(new Intent(ACTION)
                .setPackage("android")
                .putExtra(EXTRA_OP, op)
                .putExtra(EXTRA_DISPLAY, displayId));
    }

    // --- system_server side ---

    static void register(Context context, Handler handler) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    perform(context, intent.getStringExtra(EXTRA_OP),
                            intent.getIntExtra(EXTRA_DISPLAY, Display.DEFAULT_DISPLAY));
                } catch (Throwable ignored) {
                    // A failed action must never disturb system_server.
                }
            }
        }, new IntentFilter(ACTION), PERMISSION, handler);
    }

    private static void perform(Context context, String op, int displayId) throws Throwable {
        if (op == null) return;
        PowerManager pm = context.getSystemService(PowerManager.class);
        switch (op) {
            case OP_POWER_OFF:
                XposedHelpers.callMethod(pm, "shutdown", false, "userrequested", false);
                break;
            case OP_RESTART:
                pm.reboot(null);
                break;
            case OP_RECOVERY:
                pm.reboot("recovery");
                break;
            case OP_SCREENSHOT:
                screenshot(context, displayId);
                break;
            case OP_OPEN_LSPOSED:
                openLsposed(context, displayId);
                break;
            default:
                break;
        }
    }

    /** Keeps adb enabled while the pin is on (LG drops it after a reboot). */
    static void enforceUsbDebug(Context context) {
        if (!SystemSettings.usbDebugPinned) return;
        try {
            if (Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void openLsposed(Context context, int displayId) throws Throwable {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(LSPOSED_HOST, LSPOSED_HOST_ACTIVITY))
                .addCategory(LSPOSED_CATEGORY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        UserHandle current = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT");
        XposedHelpers.callMethod(context, "startActivityAsUser",
                new Class<?>[]{Intent.class, Bundle.class, UserHandle.class}, intent, options, current);
    }

    /** Physical panel id of a logical display (DisplayAddress.Physical). */
    static long physicalId(Context context, int displayId) {
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) return -1;
        Object address = XposedHelpers.callMethod(display, "getAddress");
        return (long) XposedHelpers.callMethod(address, "getPhysicalDisplayId");
    }

    // Capture runs on the settings thread (one fast GPU readback). Encoding the PNG is the slow
    // part, so it runs on its own normal-priority thread instead of the background-priority one.
    private static void screenshot(Context context, int displayId) throws Throwable {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        IBinder token = (IBinder) XposedHelpers.callStaticMethod(sc, "getPhysicalDisplayToken",
                physicalId(context, displayId));
        Class<?> builderClass = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Object builder = XposedHelpers.newInstance(builderClass, token);
        Object args = XposedHelpers.callMethod(builder, "build");
        Object buffer = XposedHelpers.callStaticMethod(sc, "captureDisplay", args);
        if (buffer == null) return;
        Bitmap hw = (Bitmap) XposedHelpers.callMethod(buffer, "asBitmap");
        if (hw == null) return;
        Thread worker = new Thread(() -> {
            try {
                save(context, hw);
            } catch (Throwable ignored) {
            } finally {
                hw.recycle();
            }
        }, "GoDaijishou-screenshot");
        worker.setPriority(Thread.NORM_PRIORITY);
        worker.start();
    }

    private static void save(Context context, Bitmap hw) throws Exception {
        Bitmap bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
        long now = System.currentTimeMillis();
        String name = "Screenshot_" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now)) + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        // Google Photos sorts by DATE_TAKEN; without it the shot lands at a wrong date.
        values.put(MediaStore.Images.Media.DATE_TAKEN, now);
        values.put(MediaStore.Images.Media.DATE_ADDED, now / 1000);
        values.put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000);
        values.put(MediaStore.Images.Media.WIDTH, bitmap.getWidth());
        values.put(MediaStore.Images.Media.HEIGHT, bitmap.getHeight());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots");
        // Hidden from the gallery until the file is complete.
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out != null) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }
}
