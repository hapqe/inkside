package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Saturation/value field with a slim hue track underneath.
 *
 * <p>Self-contained (no library, no bitmaps). Modern look: a softly rounded field
 * with a hairline edge, a thin pill-shaped hue track, and thumbs filled with the
 * colour they point at, ringed in white with a soft shadow. Shaders are cached and
 * only rebuilt when the size or hue changes.
 */
final class ColorPickerView extends View {

    interface OnColorChanged {
        void onColor(int argb);
    }

    private static final float TRACK_DP = 14f;
    private static final float THUMB_DP = 13f;
    private static final float GAP_DP = 18f;
    private static final float FIELD_RADIUS_DP = 16f;

    private final float[] hsv = {0f, 1f, 1f};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF square = new RectF();
    private final RectF bar = new RectF();
    private final int[] hueRamp = new int[7];
    private final float density;

    private Shader fieldShader;
    private float fieldShaderHue = -1f;
    private Shader hueShader;

    private OnColorChanged listener;
    /** Which control the active gesture grabbed, so a drag cannot jump between them. */
    private boolean draggingBar;

    ColorPickerView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < hueRamp.length; i++) {
            hueRamp[i] = Color.HSVToColor(new float[] {i * 60f % 360f, 1f, 1f});
        }
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, density));
        edge.setColor(0x33FFFFFF);
        thumbRing.setStyle(Paint.Style.STROKE);
        thumbRing.setColor(0xFFFFFFFF);
        thumbShadow.setColor(0x55000000);
        // Soft shadow under the thumbs (software layer needed for the blur).
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        thumbShadow.setMaskFilter(new android.graphics.BlurMaskFilter(
                3f * density, android.graphics.BlurMaskFilter.Blur.NORMAL));
        setClickable(true);
    }

    void setListener(OnColorChanged l) {
        listener = l;
    }

    /** Hairline around the field, tuned to the surface it sits on. */
    void setEdgeColor(int argb) {
        edge.setColor(argb);
        invalidate();
    }

    void setColor(int argb) {
        Color.colorToHSV(argb, hsv);
        invalidate();
    }

    int getColor() {
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float thumb = THUMB_DP * density;        // thumbs may not be clipped at the edges
        float track = TRACK_DP * density;
        float gap = GAP_DP * density;
        float below = thumb - track / 2f;
        bar.set(thumb, h - below - track, w - thumb, h - below);
        float inset = thumb / 2f;
        square.set(inset, inset, w - inset, Math.max(inset + 1f, bar.top - gap));
        fieldShaderHue = -1f;
        hueShader = new LinearGradient(bar.left, 0, bar.right, 0, hueRamp, null, Shader.TileMode.CLAMP);
    }

    private Shader fieldShader() {
        if (fieldShader == null || fieldShaderHue != hsv[0]) {
            Shader sat = new LinearGradient(square.left, 0, square.right, 0,
                    0xFFFFFFFF, Color.HSVToColor(new float[] {hsv[0], 1f, 1f}), Shader.TileMode.CLAMP);
            Shader val = new LinearGradient(0, square.top, 0, square.bottom,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            fieldShader = new ComposeShader(sat, val, PorterDuff.Mode.SRC_OVER);
            fieldShaderHue = hsv[0];
        }
        return fieldShader;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = FIELD_RADIUS_DP * density;
        fill.setShader(fieldShader());
        canvas.drawRoundRect(square, r, r, fill);
        canvas.drawRoundRect(square, r, r, edge);

        fill.setShader(hueShader);
        canvas.drawRoundRect(bar, bar.height() / 2f, bar.height() / 2f, fill);
        fill.setShader(null);

        float px = square.left + hsv[1] * square.width();
        float py = square.top + (1f - hsv[2]) * square.height();
        drawThumb(canvas, px, py, THUMB_DP * density, getColor());
        drawThumb(canvas, bar.left + (hsv[0] / 360f) * bar.width(), bar.centerY(),
                THUMB_DP * density, Color.HSVToColor(new float[] {hsv[0], 1f, 1f}));
    }

    private void drawThumb(Canvas canvas, float cx, float cy, float radius, int color) {
        canvas.drawCircle(cx, cy + density, radius, thumbShadow);
        thumbFill.setColor(color | 0xFF000000);
        canvas.drawCircle(cx, cy, radius - 1.5f * density, thumbFill);
        thumbRing.setStrokeWidth(3f * density);
        canvas.drawCircle(cx, cy, radius - 1.5f * density, thumbRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draggingBar = y >= bar.top - GAP_DP * density / 2f;
                getParent().requestDisallowInterceptTouchEvent(true);
                apply(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                apply(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                apply(x, y);
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void apply(float x, float y) {
        if (draggingBar) {
            hsv[0] = clamp((x - bar.left) / Math.max(1f, bar.width())) * 359.9f;
        } else {
            hsv[1] = clamp((x - square.left) / Math.max(1f, square.width()));
            hsv[2] = 1f - clamp((y - square.top) / Math.max(1f, square.height()));
        }
        invalidate();
        if (listener != null) listener.onColor(getColor());
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
