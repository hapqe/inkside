package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.SeekBar;

/**
 * Material 3 (expressive) slider on top of {@link SeekBar}, so it drops in wherever a
 * SeekBar was: same progress, max and listener. Drawn in full here — a thick rounded
 * track, the active part in primary, a vertical bar handle with a gap cut around it,
 * and a stop dot at the far end. The handle narrows while held and the fill glides
 * after the finger, with a tick of haptics per step.
 */
final class Material3Slider extends SeekBar {
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    private int colorActive = 0xFFBAC3FF;
    private int colorInactive = 0xFF31303A;

    /** Where the handle is drawn (0..1); glides toward the real progress. */
    private float shown = -1f;
    /** 0 idle → 1 held: narrows the handle. */
    private float held;
    private boolean dragging;
    private long lastFrame;
    private int lastTickProgress = -1;

    Material3Slider(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setThumb(null);
        setProgressDrawable(null);
        setBackground(null);
        setSplitTrack(false);
    }

    void applyColors(int active, int inactive) {
        colorActive = active;
        colorInactive = inactive;
        invalidate();
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int want = Math.round(dp(44)) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(Math.max(w, Math.round(dp(120))), widthMeasureSpec),
                resolveSize(want, heightMeasureSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        boolean r = super.onTouchEvent(event);
        postInvalidateOnAnimation();
        return r;
    }

    @Override
    public synchronized void setProgress(int progress) {
        super.setProgress(progress);
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        int max = Math.max(1, getMax() - getMin());
        float target = (getProgress() - getMin()) / (float) max;
        if (shown < 0f) shown = target;
        // Critically damped follow, like the undo scrubber's thumb.
        shown += (target - shown) * (1f - (float) Math.exp(-dt * 30f));
        float heldTarget = dragging ? 1f : 0f;
        held += (heldTarget - held) * (1f - (float) Math.exp(-dt * 18f));

        if (dragging && getProgress() != lastTickProgress) {
            if (lastTickProgress >= 0) performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
            lastTickProgress = getProgress();
        } else if (!dragging) {
            lastTickProgress = -1;
        }

        float left = getPaddingLeft() + dp(2);
        float right = getWidth() - getPaddingRight() - dp(2);
        float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        float trackH = Math.min(dp(16), (getHeight() - getPaddingTop() - getPaddingBottom()) * 0.4f);
        float handleW = dp(4) - dp(2) * held;
        float handleH = Math.min(dp(44), getHeight() - getPaddingTop() - getPaddingBottom());
        float gap = dp(6);
        float x = left + (right - left) * shown;
        float r = trackH / 2f;
        float inner = dp(2);
        boolean enabled = isEnabled();

        // Active track: from the start to just before the handle.
        activePaint.setColor(enabled ? colorActive : (colorActive & 0x00FFFFFF) | 0x61000000);
        float aEnd = x - handleW / 2f - gap;
        if (aEnd > left) {
            rect.set(left, cy - trackH / 2f, aEnd, cy + trackH / 2f);
            drawTrack(c, rect, r, Math.min(inner, r), activePaint);
        }
        // Inactive track: after the handle to the end.
        inactivePaint.setColor(colorInactive);
        float iStart = x + handleW / 2f + gap;
        if (iStart < right) {
            rect.set(iStart, cy - trackH / 2f, right, cy + trackH / 2f);
            drawTrack(c, rect, Math.min(inner, r), r, inactivePaint);
            // Stop indicator where the track ends.
            if (right - iStart > dp(12)) {
                stopPaint.setColor(activePaint.getColor());
                c.drawCircle(right - r, cy, dp(2), stopPaint);
            }
        }
        // Handle.
        handlePaint.setColor(activePaint.getColor());
        rect.set(x - handleW / 2f, cy - handleH / 2f, x + handleW / 2f, cy + handleH / 2f);
        c.drawRoundRect(rect, handleW / 2f, handleW / 2f, handlePaint);

        if (Math.abs(target - shown) > 0.0005f || Math.abs(heldTarget - held) > 0.01f) {
            postInvalidateOnAnimation();
        } else {
            shown = target;
            held = heldTarget;
            lastFrame = 0;
        }
    }

    /** Rounded track with its own radius at the outer end and a small one by the handle. */
    private final android.graphics.Path path = new android.graphics.Path();

    private void drawTrack(Canvas c, RectF r, float leftR, float rightR, Paint p) {
        path.reset();
        path.addRoundRect(r, new float[] {leftR, leftR, rightR, rightR, rightR, rightR, leftR, leftR},
                android.graphics.Path.Direction.CW);
        c.drawPath(path, p);
    }

    private float dp(float v) {
        return v * density;
    }
}
