package me.hapke.inkside;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;

/**
 * Material 3 switch (no Material Components dependency).
 * Spec: 52×32 track, 2dp outline when off, handle 16→24 (28 pressed), drag + spring.
 */
final class Material3Switch extends View {
    interface OnCheckedChangeListener {
        void onCheckedChanged(Material3Switch v, boolean checked);
    }

    private static final PathInterpolator M3_EMPHASIZED =
            new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stateLayerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    private boolean checked;
    private float progress; // 0 = off, 1 = on
    private float pressExtra; // 0..1 → handle grows toward 28dp
    private boolean dragging;
    private float touchDownX;
    private float dragStartProgress;
    private boolean moved;
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    private int colorPrimary;
    private int colorOnPrimary;
    private int colorSurfaceContainerHighest;
    private int colorOutline;
    private int colorOnSurface;

    Material3Switch(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        outlinePaint.setStyle(Paint.Style.STROKE);
        stateLayerPaint.setStyle(Paint.Style.FILL);
    }

    void applyColors(int primary, int onPrimary, int surfaceContainerHighest,
                     int outline, int onSurface) {
        colorPrimary = primary;
        colorOnPrimary = onPrimary;
        colorSurfaceContainerHighest = surfaceContainerHighest;
        // Off reads as "off", not "disabled": the outline and handle sit well toward the text
        // colour instead of the faint divider colour callers pass (M3's outline role).
        colorOutline = lerpColor(outline | 0xFF000000, onSurface | 0xFF000000, 0.6f);
        colorOnSurface = onSurface;
        invalidate();
    }

    boolean isChecked() {
        return checked;
    }

    void setChecked(boolean value) {
        setChecked(value, false);
    }

    void setChecked(boolean value, boolean animate) {
        if (checked == value && !animate) {
            progress = value ? 1f : 0f;
            invalidate();
            return;
        }
        checked = value;
        if (animate) {
            animateTo(value ? 1f : 0f);
        } else {
            cancelAnim();
            progress = value ? 1f : 0f;
            invalidate();
        }
    }

    void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    private void toggleFromUser() {
        boolean next = !checked;
        checked = next;
        animateTo(next ? 1f : 0f);
        if (listener != null) listener.onCheckedChanged(this, next);
    }

    private void setFromUser(boolean value) {
        if (checked == value) {
            animateTo(value ? 1f : 0f);
            return;
        }
        checked = value;
        animateTo(value ? 1f : 0f);
        if (listener != null) listener.onCheckedChanged(this, value);
    }

    private void animateTo(float target) {
        cancelAnim();
        float from = progress;
        if (Math.abs(from - target) < 0.001f) {
            progress = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(from, target);
        animator.setDuration(200);
        animator.setInterpolator(M3_EMPHASIZED);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void cancelAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Visual 52×32; min touch 48×48.
        int w = (int) dp(52);
        int h = Math.max((int) dp(48), (int) dp(32));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float trackW = dp(52);
        float trackH = dp(32);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        trackRect.set(cx - trackW / 2f, cy - trackH / 2f, cx + trackW / 2f, cy + trackH / 2f);

        // Track fill
        int trackColor = lerpColor(colorSurfaceContainerHighest, colorPrimary, progress);
        trackPaint.setColor(trackColor);
        float radius = trackH / 2f;
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

        // Outline only when mostly off (M3: 2dp outline, outline color)
        float outlineAlpha = 1f - progress;
        if (outlineAlpha > 0.02f) {
            outlinePaint.setStrokeWidth(dp(2));
            outlinePaint.setColor(withAlpha(colorOutline, outlineAlpha));
            float inset = dp(1);
            RectF outline = new RectF(
                    trackRect.left + inset, trackRect.top + inset,
                    trackRect.right - inset, trackRect.bottom - inset);
            canvas.drawRoundRect(outline, radius - inset, radius - inset, outlinePaint);
        }

        // Handle size: 16 off → 24 on, + press to 28
        float handleBase = lerp(dp(16), dp(24), progress);
        float handleSize = handleBase + (dp(28) - handleBase) * pressExtra;
        float pad = (trackH - handleSize) / 2f;
        float travel = trackW - handleSize - pad * 2f;
        float hx = trackRect.left + pad + travel * progress;
        float hy = cy - handleSize / 2f;

        // State layer (40dp) centered on handle
        if (pressExtra > 0.01f || isPressed()) {
            float layer = dp(40);
            stateLayerPaint.setColor(withAlpha(
                    checked || progress > 0.5f ? colorPrimary : colorOnSurface,
                    0.12f * Math.max(pressExtra, isPressed() ? 1f : 0f)));
            canvas.drawCircle(hx + handleSize / 2f, cy, layer / 2f, stateLayerPaint);
        }

        handlePaint.setColor(lerpColor(colorOutline, colorOnPrimary, progress));
        canvas.drawOval(hx, hy, hx + handleSize, hy + handleSize, handlePaint);

        // M3 "with icon": a check grows inside the handle as it turns on.
        float iconT = Math.max(0f, (progress - 0.4f) / 0.6f);
        if (iconT > 0.01f) {
            if (checkIcon == null) checkIcon = getContext().getDrawable(R.drawable.ic_check).mutate();
            float s = dp(16) * iconT;
            float icx = hx + handleSize / 2f;
            checkIcon.setTint(colorPrimary);
            checkIcon.setAlpha(Math.round(255 * iconT));
            checkIcon.setBounds(Math.round(icx - s / 2f), Math.round(cy - s / 2f),
                    Math.round(icx + s / 2f), Math.round(cy + s / 2f));
            checkIcon.draw(canvas);
        }
    }

    private android.graphics.drawable.Drawable checkIcon;

    /** Flip as if the user tapped the switch (animates and notifies the listener). */
    void toggle() {
        toggleFromUser();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        final float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                moved = false;
                touchDownX = event.getX();
                dragStartProgress = progress;
                pressExtra = 1f;
                setPressed(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                float dx = event.getX() - touchDownX;
                if (Math.abs(dx) > slop) moved = true;
                if (moved) {
                    cancelAnim();
                    float travel = dp(52) - dp(24);
                    progress = clamp01(dragStartProgress + dx / travel);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                pressExtra = 0f;
                dragging = false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (moved) {
                        setFromUser(progress >= 0.5f);
                    } else {
                        toggleFromUser();
                    }
                } else {
                    animateTo(checked ? 1f : 0f);
                }
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int lerpColor(int a, int b, float t) {
        t = clamp01(t);
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
