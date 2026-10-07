package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * A clock-face dial for picking a number of hours, in the app's own theme colours (the
 * platform time picker only takes the static theme's): 1–12 on the outer ring, 13–24 on
 * the inner one. Tap or drag; the handle follows.
 */
final class DurationDialView extends View {
    interface Listener {
        void onHours(int hours);
    }

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelInner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int colorFace, colorPrimary, colorOnPrimary, colorText, colorTextInner;
    private int hours = 5;
    private Listener listener;

    DurationDialView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        face.setStyle(Paint.Style.FILL);
        handle.setStyle(Paint.Style.FILL);
        hand.setStrokeWidth(2f * density);
        hand.setStrokeCap(Paint.Cap.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(16f * density);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelInner.setTextAlign(Paint.Align.CENTER);
        labelInner.setTextSize(13f * density);
    }

    void setColors(int faceColor, int primary, int onPrimary, int text, int textInner) {
        colorFace = faceColor | 0xFF000000;
        colorPrimary = primary;
        colorOnPrimary = onPrimary;
        colorText = text;
        colorTextInner = textInner;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setHours(int h) {
        hours = Math.max(1, Math.min(24, h));
        invalidate();
    }

    int getHours() {
        return hours;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), Math.round(256 * density));
        setMeasuredDimension(size, size);
    }

    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f;
    }

    /** Where hour {@code h} sits: outer ring for 1–12, inner for 13–24. */
    private float[] spot(int h) {
        float r = radius();
        boolean inner = h > 12;
        int pos = h % 12;
        double a = Math.toRadians(pos * 30 - 90);
        float ring = inner ? r * 0.52f : r * 0.80f;
        return new float[]{getWidth() / 2f + (float) Math.cos(a) * ring,
                getHeight() / 2f + (float) Math.sin(a) * ring};
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
        face.setColor(colorFace);
        c.drawCircle(cx, cy, r, face);
        float[] p = spot(hours);
        hand.setColor(colorPrimary);
        c.drawLine(cx, cy, p[0], p[1], hand);
        handle.setColor(colorPrimary);
        c.drawCircle(cx, cy, 3.5f * density, handle);
        float knob = (hours > 12 ? 17f : 20f) * density;
        c.drawCircle(p[0], p[1], knob, handle);
        for (int h = 1; h <= 24; h++) {
            float[] q = spot(h);
            Paint pt = h > 12 ? labelInner : label;
            pt.setColor(h == hours ? colorOnPrimary : (h > 12 ? colorTextInner : colorText));
            float base = q[1] - (pt.descent() + pt.ascent()) / 2f;
            c.drawText(String.valueOf(h), q[0], base, pt);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_MOVE && a != MotionEvent.ACTION_UP) {
            return true;
        }
        float dx = e.getX() - getWidth() / 2f, dy = e.getY() - getHeight() / 2f;
        double deg = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        if (deg < 0) deg += 360;
        int pos = (int) Math.round(deg / 30) % 12;
        boolean inner = Math.hypot(dx, dy) < radius() * 0.66f;
        int h = inner ? (pos == 0 ? 24 : pos + 12) : (pos == 0 ? 12 : pos);
        if (h != hours) {
            hours = h;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
            if (listener != null) listener.onHours(h);
        }
        return true;
    }
}
