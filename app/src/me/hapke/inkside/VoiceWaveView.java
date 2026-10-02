package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * Little live voice indicator shown while dictating: a handful of rounded bars that
 * follow the microphone level (with a gentle idle ripple so it never looks frozen).
 * Used by the main chat composer and the instant chat, so both look the same.
 */
final class VoiceWaveView extends View {
    private static final int BARS = 5;
    private static final float[] SHAPE = {0.55f, 0.8f, 1f, 0.8f, 0.55f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float level;
    private float shown;
    private boolean running;

    VoiceWaveView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        paint.setColor(0xFFBAC3FF);
    }

    void setColor(int argb) {
        paint.setColor(argb);
        invalidate();
    }

    /** 0..1 microphone level; smoothed while drawing. */
    void setLevel(float v) {
        level = Math.max(0f, Math.min(1f, v));
    }

    void setRunning(boolean on) {
        running = on;
        if (!on) {
            level = 0f;
            shown = 0f;
        }
        setVisibility(on ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.round(30 * density);
        int h = Math.round(24 * density);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) return;
        // Rise fast, fall slow — reads as speech rather than flicker.
        shown += (level - shown) * (level > shown ? 0.5f : 0.15f);
        float w = getWidth();
        float h = getHeight();
        float barW = 3f * density;
        float gap = (w - BARS * barW) / (BARS - 1);
        float minH = barW;
        float t = SystemClock.uptimeMillis() / 1000f;
        for (int i = 0; i < BARS; i++) {
            float idle = 0.12f + 0.08f * (float) Math.sin(t * 5.5f + i * 1.3f);
            float amp = Math.max(idle, shown * SHAPE[i]
                    * (0.8f + 0.2f * (float) Math.sin(t * 9f + i * 2.1f)));
            float bh = Math.max(minH, amp * h);
            float x = i * (barW + gap);
            float top = (h - bh) / 2f;
            canvas.drawRoundRect(x, top, x + barW, top + bh, barW / 2f, barW / 2f, paint);
        }
        postInvalidateOnAnimation();
    }
}
