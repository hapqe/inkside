package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Text selection on top of a PDF page: highlight, two drag handles and a small
 * Copy / Add to chat bar. It sits over the canvas and only keeps a touch when that
 * touch lands on a handle or the bar; anything else clears the selection and falls
 * through to the canvas as usual.
 */
final class PdfTextSelectionView extends FrameLayout {
    interface Host {
        DocumentPages.TextSelection select(float wx0, float wy0, float wx1, float wy1);

        float[] toScreen(float wx, float wy);

        float[] toWorld(float sx, float sy);

        boolean cameraMoving();

        void copyText(String text);

        void addTextToChat(String text);
    }

    private final Host host;
    private final float density;
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearLayout bar;
    private final TextView copyBtn;
    private final TextView chatBtn;
    private final GradientDrawable barBg = new GradientDrawable();

    private DocumentPages.TextSelection sel;
    /** Selection ends in world coordinates: a = start, b = end. */
    private float ax, ay, bx, by;
    /** 0 none, 1 start handle, 2 end handle. */
    private int dragging;
    private float dragDx, dragDy;
    private int onBar = 0xFF000000;

    PdfTextSelectionView(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        density = ctx.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);

        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));
        barBg.setCornerRadius(dp(999));
        bar.setBackground(barBg);
        bar.setElevation(dp(8));
        bar.setVisibility(GONE);
        copyBtn = barButton("Copy", () -> {
            if (sel != null) host.copyText(sel.text);
            clear();
        });
        chatBtn = barButton("Add to chat", () -> {
            if (sel != null) host.addTextToChat(sel.text);
            clear();
        });
        bar.addView(copyBtn);
        bar.addView(chatBtn);
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
    }

    void applyColors(int highlightArgb, int handleArgb, int barSurface, int barText) {
        highlight.setColor(highlightArgb);
        handlePaint.setColor(handleArgb);
        barBg.setColor(barSurface);
        onBar = barText;
        copyBtn.setTextColor(barText);
        chatBtn.setTextColor(barText);
        invalidate();
    }

    /** Hides "Add to chat" when the AI features are off. */
    void setChatEnabled(boolean on) {
        chatBtn.setVisibility(on ? VISIBLE : GONE);
    }

    boolean hasSelection() {
        return sel != null;
    }

    /** Long-press: select the word under the point. False when there is no text there. */
    boolean selectWordAt(float wx, float wy) {
        DocumentPages.TextSelection s = host.select(wx, wy, wx, wy);
        if (s == null) return false;
        RectF first = s.rects.get(0);
        RectF last = s.rects.get(s.rects.size() - 1);
        ax = first.left + 0.01f;
        ay = first.centerY();
        bx = last.right - 0.01f;
        by = last.centerY();
        sel = s;
        bringToFront();
        invalidate();
        return true;
    }

    void clear() {
        if (sel == null && dragging == 0) return;
        sel = null;
        dragging = 0;
        bar.setVisibility(GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sel == null) return;
        for (RectF r : sel.rects) {
            float[] tl = host.toScreen(r.left, r.top);
            float[] br = host.toScreen(r.right, r.bottom);
            canvas.drawRect(Math.min(tl[0], br[0]), Math.min(tl[1], br[1]),
                    Math.max(tl[0], br[0]), Math.max(tl[1], br[1]), highlight);
        }
        float[] s = startHandle();
        float[] e = endHandle();
        drawHandle(canvas, s[0], s[1], s[2]);
        drawHandle(canvas, e[0], e[1], e[2]);
        boolean moving = host.cameraMoving();
        if (moving) postInvalidateOnAnimation();
        positionBar(moving || dragging != 0);
    }

    /** {x, y of the line bottom, line height} for the start handle. */
    private float[] startHandle() {
        RectF r = sel.rects.get(0);
        float[] top = host.toScreen(r.left, r.top);
        float[] bottom = host.toScreen(r.left, r.bottom);
        return new float[]{bottom[0], bottom[1], Math.abs(bottom[1] - top[1])};
    }

    private float[] endHandle() {
        RectF r = sel.rects.get(sel.rects.size() - 1);
        float[] top = host.toScreen(r.right, r.top);
        float[] bottom = host.toScreen(r.right, r.bottom);
        return new float[]{bottom[0], bottom[1], Math.abs(bottom[1] - top[1])};
    }

    private void drawHandle(Canvas canvas, float x, float y, float lineH) {
        handlePaint.setStrokeWidth(2f * density);
        canvas.drawLine(x, y - lineH, x, y, handlePaint);
        canvas.drawCircle(x, y + dp(7), dp(7), handlePaint);
    }

    private void positionBar(boolean hide) {
        if (sel == null || hide) {
            if (bar.getVisibility() != GONE) bar.setVisibility(GONE);
            return;
        }
        RectF first = sel.rects.get(0);
        float[] tl = host.toScreen(first.left, first.top);
        if (bar.getVisibility() != VISIBLE) bar.setVisibility(VISIBLE);
        bar.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int bw = bar.getMeasuredWidth();
        int bh = bar.getMeasuredHeight();
        int x = Math.round(tl[0]);
        int y = Math.round(tl[1]) - bh - dp(12);
        if (y < dp(8)) {
            float[] e = endHandle();
            y = Math.round(e[1]) + dp(24);
        }
        x = Math.max(dp(8), Math.min(x, getWidth() - bw - dp(8)));
        y = Math.max(dp(8), Math.min(y, getHeight() - bh - dp(8)));
        LayoutParams lp = (LayoutParams) bar.getLayoutParams();
        if (lp.leftMargin != x || lp.topMargin != y) {
            lp.leftMargin = x;
            lp.topMargin = y;
            bar.setLayoutParams(lp);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (sel == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float[] s = startHandle();
                float[] e = endHandle();
                float grab = dp(28);
                float ds = dist(ev.getX(), ev.getY(), s[0], s[1] + dp(7));
                float de = dist(ev.getX(), ev.getY(), e[0], e[1] + dp(7));
                if (Math.min(ds, de) > grab) {
                    // Somewhere else: let the canvas have it, selection goes away.
                    clear();
                    return false;
                }
                dragging = de <= ds ? 2 : 1;
                float[] h = dragging == 1 ? s : e;
                // Aim at the middle of the line, not under the finger.
                dragDx = h[0] - ev.getX();
                dragDy = (h[1] - h[2] / 2f) - ev.getY();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging == 0) return false;
                float[] w = host.toWorld(ev.getX() + dragDx, ev.getY() + dragDy);
                float wx = w[0];
                float wy = w[1];
                DocumentPages.TextSelection next = dragging == 1
                        ? host.select(wx, wy, bx, by)
                        : host.select(ax, ay, wx, wy);
                if (next != null && next.pageIndex == sel.pageIndex) {
                    sel = next;
                    if (dragging == 1) {
                        ax = wx;
                        ay = wy;
                    } else {
                        bx = wx;
                        by = wy;
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging == 0) return false;
                dragging = 0;
                invalidate();
                return true;
            default:
                return dragging != 0;
        }
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        return (float) Math.hypot(x0 - x1, y0 - y1);
    }

    private TextView barButton(String label, Runnable onTap) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(onBar);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setOnClickListener(v -> onTap.run());
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
