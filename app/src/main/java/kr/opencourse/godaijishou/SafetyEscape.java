package kr.opencourse.godaijishou;

import android.os.Handler;
import android.view.KeyEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Way out that needs no picture and no touch (system_server): volume up and volume down
 * held together end the main screen mirror and any display swap at once, so the second
 * screen is the plain second screen again with its own touches.
 *
 * The mirror stays off (also across reboots) until main screen mode is picked again in the
 * app. The keys still do what they always do; this only watches them.
 */
final class SafetyEscape {

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
                        if (!bothBefore && upDown && downDown) trigger();
                    }
                });
    }

    private static void trigger() {
        Handler h = SystemSettings.handler();
        if (h == null) return;
        h.post(() -> {
            MainScreen.escape();
        });
    }
}
