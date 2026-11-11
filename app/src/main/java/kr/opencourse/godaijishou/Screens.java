package kr.opencourse.godaijishou;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** Which screen the user is looking at, from the app side. */
final class Screens {

    private Screens() {
    }

    static boolean isSecondScreenOn(DisplayManager dm) {
        Display second = dm.getDisplay(MainModule.SECOND_SCREEN_DISPLAY_ID);
        return second != null && second.getState() == Display.STATE_ON;
    }

    /** The second screen when it is on or there is no top screen, otherwise the main one. */
    static int targetDisplayId(Context context, DisplayManager dm) {
        return AppPreferences.isNoTopScreen(context) || isSecondScreenOn(dm)
                ? MainModule.SECOND_SCREEN_DISPLAY_ID : Display.DEFAULT_DISPLAY;
    }
}
