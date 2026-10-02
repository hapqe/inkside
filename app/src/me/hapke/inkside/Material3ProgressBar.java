package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * Material 3 linear progress indicator: a rounded active bar in primary, a gap, the
 * remaining track, and a stop dot at the end. Determinate progress glides to each new
 * value instead of jumping; indeterminate runs two bars across the track.
 */
final class Material3ProgressBar extends View {
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private boolean indeterminate;
    private float progress;
    private float shown;
    private long lastFrame;
    private long startMs = SystemClock.uptimeMillis();

    Material3ProgressBar(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        active.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeCap(Paint.Cap.ROUND);
    }

    void applyColors(int activeColor, int trackColor) {
        active.setColor(activeColor);
        track.setColor(trackColor);
        invalidate();
    }

    /** 0..1; leaves indeterminate mode. */
    void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        if (indeterminate) {
            indeterminate = false;
            shown = 0f;
        }
        postInvalidateOnAnimation();
    }

    void setIndeterminate(boolean on) {
        if (indeterminate == on) return;
        indeterminate = on;
        startMs = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(getDefaultSize(Math.round(dp(120)), widthSpec),
                resolveSize(Math.round(dp(8)), heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = Math.min(dp(4), getHeight());
        active.setStrokeWidth(h);
        track.setStrokeWidth(h);
        float cy = getHeight() / 2f;
        float left = h / 2f;
        float right = getWidth() - h / 2f;
        float span = right - left;
        float gap = dp(4) + h;

        long now = SystemClock.uptimeMillis();
        if (indeterminate) {
            // Two bars chase across the track (M3 indeterminate linear, simplified).
            float t = ((now - startMs) % 1800L) / 1800f;
            float[][] bars = {
                    {ease(clamp01(t / 0.75f)) , ease(clamp01((t - 0.2f) / 0.6f))},
                    {ease(clamp01((t - 0.45f) / 0.55f)), ease(clamp01((t - 0.6f) / 0.4f))},
            };
            float lastEnd = left;
            for (float[] b : bars) {
                float head = left + span * b[0];
                float tail = left + span * b[1];
                if (head - tail < 1f) continue;
                if (tail - gap > lastEnd) c.drawLine(lastEnd, cy, tail - gap, cy, track);
                c.drawLine(tail, cy, head, cy, active);
                lastEnd = head + gap;
            }
            if (lastEnd < right) c.drawLine(lastEnd, cy, right, cy, track);
            postInvalidateOnAnimation();
            return;
        }

        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        shown += (progress - shown) * (1f - (float) Math.exp(-dt * 10f));
        float x = left + span * shown;
        if (shown > 0.001f) c.drawLine(left, cy, x, cy, active);
        if (x + gap < right) {
            c.drawLine(x + gap, cy, right, cy, track);
            // Stop indicator at the end of the track.
            c.drawCircle(right, cy, h / 2f, active);
        }
        if (Math.abs(progress - shown) > 0.001f) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float ease(float t) {
        return Motion.STANDARD.getInterpolation(t);
    }

    private float dp(float v) {
        return v * density;
    }
}
