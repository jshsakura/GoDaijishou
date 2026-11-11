package kr.opencourse.godaijishou;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.widget.Toast;

/**
 * Toasts on the screen in use. A toast follows the display of the context it is created
 * with, and an application context belongs to the top display, which is off (or absent)
 * while the swivel is open. Route those to the second screen instead.
 */
final class Toasts {

    private Toasts() {
    }

    static void show(Context context, int resId) {
        Toast.makeText(displayContext(context), resId, Toast.LENGTH_SHORT).show();
    }

    private static Context displayContext(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        if (dm == null) return context;
        int id = Screens.targetDisplayId(context, dm);
        if (id == Display.DEFAULT_DISPLAY) return context;
        Display target = dm.getDisplay(id);
        return target != null ? context.createDisplayContext(target) : context;
    }
}
