package kr.opencourse.godaijishou;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * iPhone-style vertical volume capsule: a frosted dark pill that fills from the bottom,
 * with the speaker icon inside near the bottom. The fill level animates smoothly.
 */
final class VolumePillView extends View {

    private static final int TRACK_COLOR = 0xE62A2A30;
    private static final int FILL_COLOR = 0xFFF2F2F5;
    private static final int ICON_ON_FILL = 0xFF2A2A30;
    private static final int ICON_ON_TRACK = 0xFFF2F2F5;
    private static final long LEVEL_ANIM_MS = 140;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final int iconSize;
    private final int iconBottomMargin;

    private Drawable icon;
    private float level = 0f; // 0..1, currently drawn
    private ValueAnimator animator;

    VolumePillView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        iconSize = Math.round(18 * density);
        iconBottomMargin = Math.round(11 * density);
        trackPaint.setColor(TRACK_COLOR);
        fillPaint.setColor(FILL_COLOR);
    }

    void setIcon(int resId) {
        icon = getContext().getDrawable(resId);
        if (icon != null) icon = icon.mutate();
        invalidate();
    }

    void setLevel(float target, boolean animate) {
        target = Math.max(0f, Math.min(1f, target));
        if (animator != null) animator.cancel();
        if (!animate) {
            level = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(level, target);
        animator.setDuration(LEVEL_ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            level = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        bounds.set(0, 0, w, h);
        clip.reset();
        float r = w / 2f;
        clip.addRoundRect(bounds, r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(bounds, trackPaint);
        float fillTop = bounds.bottom - bounds.height() * level;
        canvas.drawRect(bounds.left, fillTop, bounds.right, bounds.bottom, fillPaint);
        canvas.restore();

        if (icon != null) {
            int left = (getWidth() - iconSize) / 2;
            int bottom = getHeight() - iconBottomMargin;
            int top = bottom - iconSize;
            // Dark icon while the fill covers it, light icon on the bare track.
            boolean covered = fillTop <= top + iconSize / 2f;
            icon.setTint(covered ? ICON_ON_FILL : ICON_ON_TRACK);
            icon.setBounds(left, top, left + iconSize, bottom);
            icon.draw(canvas);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
