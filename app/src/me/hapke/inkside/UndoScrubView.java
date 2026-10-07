package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Floating card shown while sliding across undo/redo: a history track with one dot
 * per step, a thumb that follows the finger or pen, and a label saying how far back
 * or forward the canvas now is. Slide left to undo, right to redo.
 *
 * <p>Purely visual and never takes touches — the host feeds it {@link #drag} and
 * applies the steps it returns, so the canvas previews each step live.
 */
final class UndoScrubView extends View {
    /** Width of one history step on the track (dp) — not the input step size. */
    private static final float DOT_SPACING_DP = 18f;
    private static final float CARD_W_DP = 320f;
    private static final float CARD_H_DP = 84f;
    /** Gap between the anchor (button or pen) and the card's near edge (dp). */
    private static final float ANCHOR_GAP_DP = 30f;
    private static final long ENTER_MS = 260L;
    private static final long EXIT_MS = 170L;
    /** Material emphasized decelerate / accelerate. */
    private static final PathInterpolator EASE_OUT =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final PathInterpolator EASE_IN =
            new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final RectF tmp = new RectF();
    private final Drawable undoIcon;
    private final Drawable redoIcon;
    private final float density;

    private int colorSurface = 0xFF31303A;
    private int colorOnSurface = 0xFFE6E1E9;
    private int colorOnSurfaceVariant = 0xFFCAC4D0;
    private int colorPrimary = 0xFFBAC3FF;
    private int colorOnPrimary = 0xFF1A2478;
    private int colorOutline = 0xFF49454F;

    private boolean showing;
    private boolean exiting;
    private long phaseStartMs;
    private float anchorX;
    private float anchorY;
    private boolean below;

    private int undoCount;
    private int redoCount;
    /** Input distance per history step, px. */
    private float stepPx;
    /** Continuous thumb position in steps (negative = undo), rubber-banded past the ends. */
    private float pos;
    /** Where the thumb is drawn; eases toward {@link #pos} (and the snapped step on exit). */
    private float shownPos;
    /** Track scroll in steps, so long histories slide under a window. */
    private float scroll;
    private int step;
    private boolean pinned;
    private float labelPop;
    private long lastFrameMs;

    UndoScrubView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        undoIcon = context.getDrawable(R.drawable.ic_undo).mutate();
        redoIcon = context.getDrawable(R.drawable.ic_redo).mutate();
        cardPaint.setStyle(Paint.Style.FILL);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(dp(2));
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStrokeWidth(dp(4));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setTextSize(dp(15));
        subPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setTextSize(dp(11));
    }

    void setColors(int surface, int onSurface, int onSurfaceVariant, int primary,
                   int onPrimary, int outline) {
        colorSurface = surface | 0xFF000000;
        colorOnSurface = onSurface;
        colorOnSurfaceVariant = onSurfaceVariant;
        colorPrimary = primary;
        colorOnPrimary = onPrimary;
        colorOutline = outline;
        invalidate();
    }

    boolean isScrubbing() {
        return showing && !exiting;
    }

    /**
     * Pops the card up next to ({@code anchorX}, {@code anchorY}) in this view's
     * coordinates. {@code preferBelow} puts it under the anchor (for the top toolbar);
     * otherwise above, clear of the hand holding the pen.
     */
    void begin(float anchorX, float anchorY, boolean preferBelow,
               int undoCount, int redoCount, float stepPx) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.undoCount = Math.max(0, undoCount);
        this.redoCount = Math.max(0, redoCount);
        this.stepPx = Math.max(1f, stepPx);
        float h = dp(CARD_H_DP) + dp(ANCHOR_GAP_DP);
        below = preferBelow
                ? anchorY + h < getHeight() || anchorY - h < 0
                : anchorY - h < 0 && anchorY + h < getHeight();
        pos = 0f;
        shownPos = 0f;
        scroll = 0f;
        step = 0;
        pinned = false;
        labelPop = 0f;
        showing = true;
        exiting = false;
        phaseStartMs = SystemClock.uptimeMillis();
        lastFrameMs = phaseStartMs;
        setVisibility(VISIBLE);
        postInvalidateOnAnimation();
    }

    /**
     * The finger or pen is {@code dx} px from where the slide started. Returns the
     * step the canvas should now be at: negative = that many undos, positive = redos.
     */
    int drag(float dx) {
        if (!isScrubbing()) return step;
        float raw = dx / stepPx;
        float lo = -undoCount;
        float hi = redoCount;
        // Past either end the thumb resists instead of stopping dead.
        boolean over = raw < lo || raw > hi;
        if (raw < lo) raw = lo - rubber(lo - raw);
        else if (raw > hi) raw = hi + rubber(raw - hi);
        pos = raw;
        int next = Math.round(Math.max(lo, Math.min(hi, raw)));
        if (next != step) {
            step = next;
            labelPop = 1f;
            performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
        }
        if (over && !pinned) performHapticFeedback(HapticFeedbackConstants.REJECT);
        pinned = over;
        postInvalidateOnAnimation();
        return step;
    }

    /** Slide finished: the thumb settles on its step and the card drops away. */
    void end() {
        if (!showing || exiting) return;
        exiting = true;
        pos = step;
        phaseStartMs = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    private float rubber(float over) {
        return 0.6f * (1f - 1f / (over * 0.55f + 1f));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!showing) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;

        float t = Math.min(1f, (now - phaseStartMs)
                / (float) (exiting ? EXIT_MS : ENTER_MS));
        float appear;
        float scale;
        if (exiting) {
            float e = EASE_IN.getInterpolation(t);
            appear = 1f - e;
            scale = 1f - 0.06f * e;
        } else {
            float e = EASE_OUT.getInterpolation(t);
            appear = Math.min(1f, t * 2.2f);
            // A touch of overshoot so the card lands rather than just appears.
            scale = 0.82f + 0.18f * e + 0.035f * (float) Math.sin(Math.PI * e);
        }
        if (exiting && t >= 1f) {
            showing = false;
            exiting = false;
            setVisibility(GONE);
            return;
        }

        // Critically damped follow: fast enough to feel attached, smooth between steps.
        float k = 1f - (float) Math.exp(-dt * 28f);
        shownPos += (pos - shownPos) * k;
        float halfSteps = (dp(CARD_W_DP) / 2f - dp(40)) / dp(DOT_SPACING_DP);
        float keep = halfSteps * 0.55f;
        float wantScroll = scroll;
        if (shownPos - wantScroll > keep) wantScroll = shownPos - keep;
        if (shownPos - wantScroll < -keep) wantScroll = shownPos + keep;
        scroll += (wantScroll - scroll) * (1f - (float) Math.exp(-dt * 14f));
        labelPop *= (float) Math.exp(-dt * 9f);

        float w = Math.min(dp(CARD_W_DP), getWidth() - dp(32));
        float h = dp(CARD_H_DP);
        float cx = Math.max(dp(16) + w / 2f, Math.min(getWidth() - dp(16) - w / 2f, anchorX));
        float top = below ? anchorY + dp(ANCHOR_GAP_DP) : anchorY - dp(ANCHOR_GAP_DP) - h;
        card.set(cx - w / 2f, top, cx + w / 2f, top + h);

        int save = c.save();
        float slide = (1f - appear) * dp(10) * (below ? -1f : 1f);
        c.translate(0, slide);
        // Grow out of the side facing the anchor.
        float pivotX = Math.max(card.left + h / 2f, Math.min(card.right - h / 2f, anchorX));
        c.scale(scale, scale, pivotX, below ? card.top : card.bottom);
        int alpha = Math.round(255 * appear);

        cardPaint.setColor(colorSurface);
        cardPaint.setAlpha(alpha);
        if (SketchStyle.on) {
            SketchStyle.drawCard(c, card, h / 2.4f, cardPaint, alpha, getResources().getDisplayMetrics().density);
        } else {
            cardPaint.setShadowLayer(dp(14), 0, dp(4), (Math.round(0x55 * appear) << 24));
            c.drawRoundRect(card, h / 2.4f, h / 2.4f, cardPaint);
            cardPaint.clearShadowLayer();
        }

        drawHeader(c, cx, alpha);
        drawTrack(c, cx, w, alpha);
        c.restoreToCount(save);

        if (exiting || Math.abs(pos - shownPos) > 0.002f || Math.abs(wantScroll - scroll) > 0.002f
                || labelPop > 0.01f || t < 1f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawHeader(Canvas c, float cx, int alpha) {
        float rowY = card.top + dp(26);
        float iconSize = dp(22);
        float pad = dp(22);
        tintIcon(undoIcon, step < 0 ? colorPrimary : colorOnSurfaceVariant,
                undoCount == 0 ? alpha / 3 : alpha);
        undoIcon.setBounds(Math.round(card.left + pad), Math.round(rowY - iconSize / 2f),
                Math.round(card.left + pad + iconSize), Math.round(rowY + iconSize / 2f));
        undoIcon.draw(c);
        tintIcon(redoIcon, step > 0 ? colorPrimary : colorOnSurfaceVariant,
                redoCount == 0 ? alpha / 3 : alpha);
        redoIcon.setBounds(Math.round(card.right - pad - iconSize), Math.round(rowY - iconSize / 2f),
                Math.round(card.right - pad), Math.round(rowY + iconSize / 2f));
        redoIcon.draw(c);

        String label;
        String sub;
        if (step < 0) {
            label = "Undo " + (-step) + (step == -1 ? " step" : " steps");
            sub = (-step) + " of " + undoCount + " back";
        } else if (step > 0) {
            label = "Redo " + step + (step == 1 ? " step" : " steps");
            sub = step + " of " + redoCount + " forward";
        } else {
            label = "Slide";
            sub = null;
        }
        float pop = 1f + 0.12f * labelPop;
        int save = c.save();
        c.scale(pop, pop, cx, rowY);
        labelPaint.setColor(step != 0 ? colorOnSurface : colorOnSurfaceVariant);
        labelPaint.setAlpha(alpha);
        // Alone, the label sits where the two lines would be centred.
        c.drawText(label, cx, rowY + (sub == null ? dp(12) : dp(1)), labelPaint);
        c.restoreToCount(save);
        if (sub == null) return;
        subPaint.setColor(colorOnSurfaceVariant);
        subPaint.setAlpha(alpha * 3 / 4);
        c.drawText(sub, cx, rowY + dp(15), subPaint);
    }

    private void drawTrack(Canvas c, float cx, float w, int alpha) {
        float y = card.bottom - dp(20);
        float left = card.left + dp(18);
        float right = card.right - dp(18);
        float spacing = dp(DOT_SPACING_DP);
        c.save();
        c.clipRect(left - dp(10), y - dp(14), right + dp(10), y + dp(14));

        // Fade the track out toward the card's edges so a long history reads as "more".
        int line = colorOutline;
        trackPaint.setShader(new LinearGradient(left, 0, right, 0,
                new int[]{line & 0x00FFFFFF, line, line, line & 0x00FFFFFF},
                new float[]{0f, 0.14f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        trackPaint.setAlpha(alpha);
        float x0 = xFor(-undoCount, cx, spacing);
        float x1 = xFor(redoCount, cx, spacing);
        c.drawLine(Math.max(left, x0), y, Math.min(right, x1), y, trackPaint);

        // The stretch being undone or redone, from the start point to the thumb.
        float xStart = xFor(0, cx, spacing);
        float xThumb = xFor(shownPos, cx, spacing);
        fillPaint.setColor(colorPrimary);
        fillPaint.setAlpha(Math.round(alpha * 0.55f));
        if (Math.abs(xThumb - xStart) > 0.5f) c.drawLine(xStart, y, xThumb, y, fillPaint);

        int first = (int) Math.floor(scroll - (right - left) / 2f / spacing) - 1;
        int last = (int) Math.ceil(scroll + (right - left) / 2f / spacing) + 1;
        first = Math.max(first, -undoCount);
        last = Math.min(last, redoCount);
        for (int i = first; i <= last; i++) {
            float x = xFor(i, cx, spacing);
            float edge = Math.min(x - left, right - x) / dp(40);
            float fade = Math.max(0f, Math.min(1f, edge));
            boolean inSpan = step < 0 ? i >= step && i <= 0 : i <= step && i >= 0;
            dotPaint.setColor(inSpan ? colorPrimary : colorOnSurfaceVariant);
            dotPaint.setAlpha(Math.round(alpha * fade * (inSpan ? 1f : 0.7f)));
            if (i == 0) {
                // Where this slide started: a small bar so "back to start" is findable.
                float hh = dp(7);
                tmp.set(x - dp(1.5f), y - hh, x + dp(1.5f), y + hh);
                c.drawRoundRect(tmp, dp(1.5f), dp(1.5f), dotPaint);
            } else {
                c.drawCircle(x, y, dp(inSpan ? 3f : 2.5f), dotPaint);
            }
        }
        c.restore();

        // Thumb, drawn unclipped so it can lean past the ends while rubber-banding.
        float stretch = Math.abs(pos - Math.max(-undoCount, Math.min(redoCount, pos)));
        float r = dp(10) * (1f + 0.25f * labelPop) * (1f - 0.12f * Math.min(1f, stretch * 3f));
        dotPaint.setColor(colorPrimary);
        dotPaint.setAlpha(alpha);
        dotPaint.setShadowLayer(dp(4), 0, dp(1), (alpha / 3) << 24);
        c.drawCircle(xThumb, y, r, dotPaint);
        dotPaint.clearShadowLayer();
        dotPaint.setColor(colorOnPrimary);
        dotPaint.setAlpha(alpha);
        c.drawCircle(xThumb, y, dp(3), dotPaint);
    }

    private float xFor(float stepPos, float cx, float spacing) {
        return cx + (stepPos - scroll) * spacing;
    }

    private void tintIcon(Drawable d, int color, int alpha) {
        d.setTint(color);
        d.setAlpha(alpha);
    }

    private float dp(float v) {
        return v * density;
    }
}
