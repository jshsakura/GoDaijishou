package kr.opencourse.godaijishou;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;

/**
 * The battery strip's drawing: ( [flame] [Wi-Fi] [LTE] [battery%] ) (n) as a floating pill, or
 * the same items on one thin band across the top.
 *
 * Hot-swappable: StatusLabel (system_server) loads this class straight from the installed APK
 * in a class loader of its own and reloads it whenever the app is updated, so a new look shows
 * up without a reboot. That only works while the class stays self-contained:
 *  - framework classes only (no Xposed, no other module class, no R / BuildConfig fields
 *    beyond compile-time constants),
 *  - the host only talks to it through public methods with framework types:
 *    the (Context, int style, int pos) constructor, setState(Bundle), hitsPill(float, float)
 *    and gripBottom(); keep those signatures.
 */
public final class StripView extends View {

    // State keys (see setState); the host writes them, this class only reads.
    public static final String BATTERY = "battery";     // 0..100, -1 = unknown yet
    public static final String CHARGING = "charging";
    public static final String WIFI = "wifi";           // 0..3, -1 = no Wi-Fi
    public static final String MOBILE = "mobile";       // "LTE" / "5G" / ..., null = none
    public static final String COUNT = "count";         // notifications
    public static final String HEAT = "heat";           // 0 none, 1 warm, 2 hot

    private static final int STYLE_LINE = 1;            // AppPreferences.STRIP_STYLE_LINE
    private static final int POS_LEFT = 0, POS_CENTER = 1; // AppPreferences.STRIP_POS_*

    private static final float HEIGHT_DP = 16f;
    private static final float RIGHT_MARGIN_DP = 8f;
    private static final float GRIP_SLACK_DP = 6f;
    private static final float PAD_DP = 6.5f;
    private static final float GAP_DP = 5f;
    private static final float TOP_MARGIN_DP = 1f;
    private static final float ICON_DP = 10f;
    private static final float TEXT_SP = 10.5f;
    private static final int HIGH = 50;
    private static final int LOW = 20;
    private static final int HEAT_NONE = 0, HEAT_HOT = 2;

    /** Colors for one system theme. Battery colors: muted, still readable on the background. */
    private static final class Palette {
        final int background, outline, ink, dim, bell, good, mid, low, warm, hot;
        final int bubbleInk = 0xFF1A1A1E;

        Palette(int background, int outline, int ink, int dim, int bell, int good, int mid, int low,
                int warm, int hot) {
            this.background = background;
            this.outline = outline;
            this.ink = ink;
            this.dim = dim;
            this.bell = bell;
            this.good = good;
            this.mid = mid;
            this.low = low;
            this.warm = warm;
            this.hot = hot;
        }
    }

    // FT ground / ink, well see-through, with pastel battery colors.
    // The hairline outline keeps the pill's shape visible on black backgrounds.
    private static final Palette DARK = new Palette(
            0x8C121216, 0x40FFFFFF, 0xE6ECECF0, 0x52ECECF0, 0xE6FFC23D,
            0xFF8FD1AE, 0xFFEBCB80, 0xFFE89A92,
            0xFFF2A65A, 0xFFF26B5E);
    // Inverted for light mode; battery colors deepened so they hold up on a light tab.
    private static final Palette LIGHT = new Palette(
            0x99F4F4F7, 0x24000000, 0xE61A1A1E, 0x521A1A1E, 0xFFC68A10,
            0xFF3E9C6D, 0xFFB0821A, 0xFFC65A50,
            0xFFD0771C, 0xFFCC3B30);

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path flame = new Path();
    private final RectF rect = new RectF();
    private final RectF oval = new RectF();
    private final float pad, gap, radius, icon, rightMargin, top, height, gripSlack;
    private final boolean line; // thin full-width band instead of the floating pill
    private final int pos;

    private int battery = -1;
    private boolean charging;
    private int wifiLevel = -1;
    private String mobileText;
    private int notifications;
    private int heat;

    public StripView(Context ui, int style, int pos) {
        super(ui);
        this.line = style == STYLE_LINE;
        this.pos = pos;
        float density = ui.getResources().getDisplayMetrics().density;
        float scaled = ui.getResources().getDisplayMetrics().scaledDensity;
        pad = PAD_DP * density;
        gap = GAP_DP * density;
        radius = HEIGHT_DP * density / 2f;
        top = line ? 0 : TOP_MARGIN_DP * density;
        icon = ICON_DP * density;
        rightMargin = RIGHT_MARGIN_DP * density;
        height = HEIGHT_DP * density;
        gripSlack = GRIP_SLACK_DP * density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Math.max(1f, 0.75f * density));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(Math.max(1f, 1.1f * density));
        text.setTextSize(TEXT_SP * scaled);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    /** New readings; the window keeps its size, only the drawing changes. */
    public void setState(Bundle s) {
        battery = s.getInt(BATTERY, -1);
        charging = s.getBoolean(CHARGING);
        wifiLevel = s.getInt(WIFI, -1);
        mobileText = s.getString(MOBILE);
        notifications = s.getInt(COUNT);
        heat = s.getInt(HEAT);
        invalidate();
    }

    /** Bottom edge of the pull-down grip, in pixels from the top of the screen. */
    public int gripBottom() {
        return Math.round(top + height + gripSlack);
    }

    /** A screen point on the pill or its count bubble. */
    public boolean hitsPill(float rawX, float rawY) {
        int[] at = new int[2];
        getLocationOnScreen(at);
        float right = pillRight();
        float left = right - pad * 2 - contentWidth();
        float x = rawX - at[0];
        float y = rawY - at[1];
        float bubbleRight = countText() != null ? right + bubbleGap() + bubbleWidth(countText()) : right;
        return x >= left && x <= bubbleRight && y <= gripBottom();
    }

    // The battery slot (glyph + "100%"-wide number) sits at a fixed spot on the right;
    // the other items are added or dropped on its left, so the battery never moves.
    private float batterySlot() {
        return batteryGlyphWidth() + gap * 0.5f + text.measureText("100%");
    }

    private float batteryGlyphWidth() {
        return icon * 1.25f;
    }

    // Mobile data label in a fixed-width slot, so LTE <-> 5G doesn't shift anything.
    private float mobileSlot() {
        return Math.max(text.measureText("LTE"), text.measureText("5G"));
    }

    private float contentWidth() {
        float content = batterySlot();
        if (wifiLevel >= 0) content += gap + icon;
        if (mobileText != null) content += gap + mobileSlot();
        if (heat != HEAT_NONE) content += gap + icon * 0.75f;
        return content;
    }

    private float maxPillWidth() {
        return pad * 2 + batterySlot() + gap + icon + gap + mobileSlot() + gap + icon * 0.75f;
    }

    // Count circle: the pill's height, widening only for two digits.
    private float bubbleWidth(String count) {
        return Math.max(height, text.measureText(count) + pad * 1.2f);
    }

    private float bubbleGap() {
        return gap * 0.7f;
    }

    private String countText() {
        return notifications > 0 ? String.valueOf(Math.min(notifications, 99)) : null;
    }

    // Right end of the pill for the chosen position. On the right the count circle keeps a
    // fixed slot after it, so the battery never moves; on the left the pill starts at the
    // margin; in the middle the pill and its circle are centred together.
    private float pillRight() {
        float pill = pad * 2 + contentWidth();
        if (pos == POS_LEFT) return rightMargin + pill;
        if (pos == POS_CENTER) {
            String count = countText();
            float bubble = count != null ? bubbleGap() + bubbleWidth(count) : 0;
            return (getWidth() - pill - bubble) / 2f + pill;
        }
        return getWidth() - rightMargin - bubbleWidth("99") - bubbleGap();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float h = top + height;
        if (line) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) Math.ceil(h));
            return;
        }
        float w = maxPillWidth() + bubbleGap() + bubbleWidth("99") + rightMargin;
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        invalidate(); // dark/light switch
    }

    private Palette palette() {
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (battery < 0) return;
        Palette p = palette();
        bg.setColor(p.background);
        String countText = countText();

        float h = height;
        canvas.translate(0, top);
        float right = pillRight();
        float left = right - pad * 2 - contentWidth();
        outline.setColor(p.outline);
        if (line) {
            // One thin band across the top edge, a hairline under it.
            canvas.drawRect(0, 0, getWidth(), h, bg);
            canvas.drawLine(0, h, getWidth(), h, outline);
        } else {
            rect.set(left, 0, right, h);
            shape.reset();
            shape.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.drawPath(shape, bg);
            canvas.drawPath(shape, outline);
        }

        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = (h - fm.ascent - fm.descent) / 2f;
        float cy = h / 2f;

        // Battery: number right-aligned in its fixed slot, glyph at the slot's left edge.
        int level = charging || battery >= HIGH ? p.good : battery >= LOW ? p.mid : p.low;
        float x = right - pad;
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(level);
        canvas.drawText(battery + "%", x, baseline, text);
        text.setTextAlign(Paint.Align.LEFT);
        x -= batterySlot();
        drawBattery(canvas, x, cy, level, p);

        // Then right to left: LTE, Wi-Fi, and the heat flame on the far left.
        if (mobileText != null) {
            x -= gap + mobileSlot();
            text.setColor(p.ink);
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(mobileText, x + mobileSlot() / 2f, baseline, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        if (wifiLevel >= 0) {
            x -= gap + icon;
            drawWifi(canvas, x, cy, p);
        }
        if (heat != HEAT_NONE) {
            x -= gap + icon * 0.75f;
            drawFlame(canvas, x, cy, icon * 0.75f, heat == HEAT_HOT ? p.hot : p.warm);
        }

        // Notification count: number only, in its own circle after the pill.
        if (countText != null) {
            float bLeft = right + bubbleGap();
            rect.set(bLeft, 0, bLeft + bubbleWidth(countText), h);
            ink.setColor(p.bell);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, ink);
            text.setColor(p.bubbleInk);
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(countText, rect.centerX(), baseline, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    // Three arcs over a dot; arcs above the signal level are drawn dim.
    private void drawWifi(Canvas canvas, float x, float cy, Palette p) {
        float cx = x + icon / 2f;
        float base = cy + icon * 0.42f;
        ink.setColor(p.ink);
        canvas.drawCircle(cx, base - icon * 0.05f, icon * 0.09f, ink);
        for (int i = 1; i <= 3; i++) {
            float r = icon * 0.24f * i + icon * 0.04f;
            oval.set(cx - r, base - r, cx + r, base + r);
            arc.setColor(i <= wifiLevel ? p.ink : p.dim);
            canvas.drawArc(oval, 225, 90, false, arc);
        }
    }

    // Battery glyph: outlined body filled to the level, small nub on the right.
    private void drawBattery(Canvas canvas, float x, float cy, int levelColor, Palette p) {
        float w = batteryGlyphWidth() - icon * 0.12f;
        float h = icon * 0.62f;
        float r = h * 0.25f;
        oval.set(x, cy - h / 2f, x + w, cy + h / 2f);
        arc.setColor(p.ink);
        canvas.drawRoundRect(oval, r, r, arc);
        float inset = arc.getStrokeWidth() * 1.3f;
        ink.setColor(levelColor);
        float fillRight = x + inset + (w - inset * 2) * (battery / 100f);
        canvas.drawRect(x + inset, cy - h / 2f + inset, fillRight, cy + h / 2f - inset, ink);
        ink.setColor(p.ink);
        canvas.drawRect(x + w, cy - h * 0.2f, x + w + icon * 0.12f, cy + h * 0.2f, ink);
    }

    // Flame: a teardrop with a pointed tip and a round base.
    private void drawFlame(Canvas canvas, float x, float cy, float size, int color) {
        float w = size;
        float h = size * 1.25f;
        float top = cy - h / 2f;
        float bottom = cy + h / 2f;
        float cx = x + w / 2f;
        flame.reset();
        flame.moveTo(cx + w * 0.08f, top);
        flame.cubicTo(cx + w * 0.62f, top + h * 0.38f, x + w * 1.02f, top + h * 0.62f, cx + w * 0.38f, bottom - h * 0.08f);
        flame.cubicTo(cx + w * 0.2f, bottom + h * 0.02f, cx - w * 0.2f, bottom + h * 0.02f, cx - w * 0.38f, bottom - h * 0.08f);
        flame.cubicTo(x - w * 0.02f, top + h * 0.6f, cx - w * 0.36f, top + h * 0.36f, cx + w * 0.08f, top);
        flame.close();
        ink.setColor(color);
        canvas.drawPath(flame, ink);
    }
}
