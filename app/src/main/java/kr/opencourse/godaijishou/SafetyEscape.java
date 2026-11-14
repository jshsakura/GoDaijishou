package kr.opencourse.godaijishou;

import android.os.Handler;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Screen mode keys, needing no picture and no touch (system_server):
 *
 * - Volume up + down pressed together: main screen <-> second screen (MainScreen.toggle).
 * - Volume down held 10 seconds: the emergency exit. Always the plain second screen with its
 *   own touches, never the main screen, and kept so (also across reboots) until main screen
 *   mode is switched on again (MainScreen.escape).
 *
 * The keys still do what they always do; this only watches them.
 */
final class SafetyEscape {

    private static final long EMERGENCY_HOLD_MS = 10_000;

    private static volatile boolean upDown, downDown;

    private SafetyEscape() {
    }

    static void install(ClassLoader cl) {
        XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", cl),
                "interceptKeyBeforeQueueing", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object a = param.args.length > 0 ? param.args[0] : null;
                        if (!(a instanceof KeyEvent)) return;
                        KeyEvent e = (KeyEvent) a;
                        int code = e.getKeyCode();
                        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return;
                        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                        boolean bothBefore = upDown && downDown;
                        if (code == KeyEvent.KEYCODE_VOLUME_UP) upDown = down;
                        else downDown = down;
                        if (!bothBefore && upDown && downDown) post(MainScreen::toggle);
                        watchHold(code, down, e.getRepeatCount());
                    }
                });
    }

    // Volume down alone, held: the emergency exit fires after 10 s unless let go (or volume up
    // joins in, which is the switch instead).
    private static void watchHold(int code, boolean down, int repeat) {
        Handler h = SystemSettings.handler();
        if (h == null) return;
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN && down && repeat == 0 && !upDown) {
            h.removeCallbacks(EMERGENCY);
            h.postDelayed(EMERGENCY, EMERGENCY_HOLD_MS);
        } else if (!downDown || upDown) {
            h.removeCallbacks(EMERGENCY);
        }
    }

    private static final Runnable EMERGENCY = () -> {
        if (downDown && !upDown) MainScreen.escape();
    };

    private static void post(Runnable r) {
        Handler h = SystemSettings.handler();
        if (h != null) h.post(r);
    }
}
