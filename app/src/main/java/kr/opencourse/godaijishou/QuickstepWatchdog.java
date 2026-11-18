package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Quickstep's swipe area against the real screen (scope: com.lge.launcher3).
 *
 * Home and recents swipes sometimes stopped working until a reboot. Quickstep decides whether a
 * touch can start a swipe from a region it computed for the display, and nothing in it notices
 * when that region goes stale: the screen stays dead to swipes while every app still works.
 *
 * A touch on the very bottom strip of the main display, away from the corners, is inside that
 * region on any healthy phone: the gesture area is at least this tall in every rotation. When
 * quickstep says no to two such touches in a row, its region is wrong, whatever made it so,
 * and the process is restarted to compute it again (SystemUI's fallback covers the gap). This
 * is geometry only: the lock screen, the shade, pinned apps or a game in full screen change
 * whether a swipe is acted on, never whether it is in the region, so none of them count.
 *
 * At most one restart per RESTART_GAP_MS, across processes: if the region still disagrees
 * after a restart, the check is wrong for this phone, and it stops instead of looping.
 */
final class QuickstepWatchdog {

    private static final String TOUCH_SERVICE = "com.android.quickstep.TouchInteractionService";
    private static final int MISSES_TO_RESTART = 2;
    private static final long RESTART_GAP_MS = 10 * 60_000L;
    private static final String STAMP = "wingcustom_quickstep_restart";

    private static int misses;
    private static volatile boolean off;

    private QuickstepWatchdog() {
    }

    static void install(ClassLoader cl) {
        Class<?> service = XposedHelpers.findClassIfExists(TOUCH_SERVICE, cl);
        if (service == null) return;
        XposedBridge.hookAllMethods(service, "onInputEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (off || param.args.length == 0 || !(param.args[0] instanceof MotionEvent)) return;
                MotionEvent ev = (MotionEvent) param.args[0];
                if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                try {
                    check((Context) param.thisObject, ev);
                } catch (Throwable t) {
                    off = true; // this firmware differs: leave quickstep alone
                    ModuleLog.w(t);
                }
            }
        });
    }

    private static void check(Context service, MotionEvent ev) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        if (ev.getDisplayId() != Display.DEFAULT_DISPLAY || ev.getPointerCount() != 1
                || ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return;
        if (!onBottomStrip(service, ev)) return;
        Object state = XposedHelpers.getObjectField(service, "mDeviceState");
        if (state == null) return;
        boolean inRegion = (boolean) XposedHelpers.callMethod(state, "isInSwipeUpTouchRegion", ev);
        if (inRegion) {
            misses = 0;
            return;
        }
        misses++;
        ModuleLog.i("quickstep: bottom touch outside its swipe area (" + misses + ")");
        if (misses < MISSES_TO_RESTART) return;
        misses = 0;
        restart(service);
    }

    // The middle of the bottom strip, half as tall as the system's gesture area.
    private static boolean onBottomStrip(Context c, MotionEvent ev) {
        Display d = c.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return false;
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        float strip = Math.max(6f * m.density, gestureHeight() / 2f);
        float x = ev.getX(), y = ev.getY();
        return y >= m.heightPixels - strip && y < m.heightPixels
                && x > m.widthPixels * 0.2f && x < m.widthPixels * 0.8f;
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private static float gestureHeight() {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier("navigation_bar_gesture_height", "dimen", "android");
        return id == 0 ? 0f : r.getDimension(id);
    }

    private static void restart(Context service) {
        File stamp = new File(service.getApplicationContext().getFilesDir(), STAMP);
        long now = SystemClock.elapsedRealtime();
        long last = read(stamp);
        // A stamp from before this boot is later than now (the clock started again).
        if (last >= 0 && last <= now && now - last < RESTART_GAP_MS) {
            off = true;
            ModuleLog.i("quickstep: still outside after a restart, watchdog off");
            return;
        }
        try {
            Files.write(stamp.toPath(), Long.toString(now).getBytes(StandardCharsets.US_ASCII));
        } catch (Throwable t) {
            off = true; // no stamp, no loop guard: do not restart
            ModuleLog.w(t);
            return;
        }
        ModuleLog.i("quickstep: swipe area is stale, restarting");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static long read(File f) {
        try {
            return Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim());
        } catch (Throwable t) {
            return -1;
        }
    }
}
