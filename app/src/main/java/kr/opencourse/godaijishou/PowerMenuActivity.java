package kr.opencourse.godaijishou;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;


/**
 * Power menu shown on the second screen. Launched by PowerMenuHook from system_server
 * in place of the stock global actions dialog.
 *
 * Actions are carried out by the system_server hook (SystemActions); the app needs no root.
 */
public class PowerMenuActivity extends Activity {

    private static final int BLUR_RADIUS = 60;
    private static final long SCREENSHOT_DELAY_MS = 450; // let the menu fade out first

    private static final long ENTER_MS = 260;
    private static final long ITEM_STAGGER_MS = 35;
    private static final long EXIT_MS = 140;
    private static final long PRESS_MS = 90;
    private static final float PRESS_SCALE = 0.9f;

    private View card;
    private boolean closing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_power_menu);
        applyGlassBackdrop();

        card = findViewById(R.id.power_card);

        // Tap outside the card dismisses the menu, like the stock dialog.
        findViewById(R.id.power_root).setOnClickListener(v -> close(null));
        card.setOnClickListener(v -> { /* swallow */ });

        bind(R.id.btn_power_off, () -> runAndClose(SystemActions.OP_POWER_OFF));
        bind(R.id.btn_restart, () -> runAndClose(SystemActions.OP_RESTART));
        bind(R.id.btn_recovery, () -> runAndClose(SystemActions.OP_RECOVERY));
        bind(R.id.btn_screenshot, this::takeScreenshot);

        animateIn();
        // Start with focus on the first action for gamepad navigation.
        findViewById(R.id.btn_power_off).requestFocus();
    }

    @Override
    public void onBackPressed() {
        close(null);
    }

    // Blur whatever is behind the menu (Android 12+ cross-window blur). Falls back to a
    // plain dim when the device has blur turned off.
    private void applyGlassBackdrop() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        boolean blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && getSystemService(WindowManager.class).isCrossWindowBlurEnabled();
        if (blur) {
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(BLUR_RADIUS);
            lp.dimAmount = 0.25f;
        } else {
            lp.dimAmount = 0.6f;
        }
        getWindow().setAttributes(lp);
        getWindow().getDecorView().setBackgroundColor(Color.TRANSPARENT);
    }

    // --- Motion ---

    // Card pops in with a slight overshoot; tiles rise in one after another.
    private void animateIn() {
        card.setAlpha(0f);
        card.setScaleX(0.88f);
        card.setScaleY(0.88f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(new OvershootInterpolator(1.4f))
                .start();

        float rise = 14 * getResources().getDisplayMetrics().density;
        ViewGroup row = (ViewGroup) card;
        for (int i = 0; i < row.getChildCount(); i++) {
            View item = row.getChildAt(i);
            item.setAlpha(0f);
            item.setTranslationY(rise);
            item.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60 + i * ITEM_STAGGER_MS)
                    .setDuration(ENTER_MS)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
        }
    }

    // Card shrinks and fades, then the action (if any) runs and the activity finishes.
    private void close(Runnable after) {
        if (closing) return;
        closing = true;
        card.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .setStartDelay(0)
                .setDuration(EXIT_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (after != null) after.run();
                    finish();
                    overridePendingTransition(0, android.R.anim.fade_out);
                })
                .start();
    }

    // Press: tile sinks slightly; release: springs back. Click adds a light haptic tick.
    @SuppressLint("ClickableViewAccessibility")
    private void bind(int id, Runnable action) {
        View tile = findViewById(id);
        tile.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                            .setStartDelay(0).setDuration(PRESS_MS)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setStartDelay(0).setDuration(PRESS_MS * 3)
                            .setInterpolator(new OvershootInterpolator(3f)).start();
                    break;
            }
            return false; // keep the ripple and click handling
        });
        tile.setOnClickListener(v -> {
            if (closing) return;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
        });
    }

    // --- Actions ---

    // Actions are performed by the system_server hook (this menu is only ever shown by it).
    private void runAndClose(String op) {
        final Context app = getApplicationContext();
        final int displayId = displayId();
        close(() -> SystemActions.send(app, op, displayId));
    }

    // Captures the screen this menu was shown on, after the menu has closed.
    private void takeScreenshot() {
        final Context app = getApplicationContext();
        final int displayId = displayId();
        close(() -> new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SystemActions.send(app, SystemActions.OP_SCREENSHOT, displayId);
            Toasts.show(app, R.string.toast_screenshot_saved);
        }, SCREENSHOT_DELAY_MS));
    }

    private int displayId() {
        return getDisplay() != null ? getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
    }

    @Override
    protected void onUserLeaveHint() {
        // Close when the user navigates away (home key etc.), like the stock menu.
        finish();
    }
}
