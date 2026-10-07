package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating card shown while three fingers slide up or down: the recent documents as a
 * list, the one the fingers point at highlighted. Lifting the fingers opens it. The
 * vertical counterpart of {@link UndoScrubView}, and like it purely visual: the host
 * feeds it {@link #drag} and asks {@link #selected} at the end.
 */
final class DocSwitcherView extends View {
    private static final float CARD_W_DP = 360f;
    private static final float ROW_H_DP = 56f;
    private static final float PAD_DP = 8f;
    /** Finger travel per row (dp). */
    private static final float STEP_DP = 44f;
    private static final long ENTER_MS = 240L;
    private static final long EXIT_MS = 160L;
    private static final PathInterpolator EASE_OUT = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final PathInterpolator EASE_IN = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final RectF tmp = new RectF();
    private final Drawable docIcon;
    private final float density;

    private int colorSurface = 0xFF31303A;
    private int colorOnSurface = 0xFFE6E1E9;
    private int colorOnSurfaceVariant = 0xFFCAC4D0;
    private int colorPrimary = 0xFFBAC3FF;
    private int colorPrimaryContainer = 0xFF3F51B5;
    private int colorOnPrimaryContainer = 0xFFE8EAF6;

    private final List<String> docs = new ArrayList<>();
    private int current;
    private int index;
    /** Highlight position drawn, easing toward {@link #index}. */
    private float shownIndex;
    private boolean showing;
    private boolean exiting;
    private long phaseStartMs;
    private long lastFrameMs;
    private float anchorX, anchorY;
    /** Opened by sliding up: the list runs upward from the fingers (newest at the bottom). */
    private boolean upward;

    DocSwitcherView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        docIcon = context.getDrawable(R.drawable.ic_description).mutate();
        cardPaint.setStyle(Paint.Style.FILL);
        pillPaint.setStyle(Paint.Style.FILL);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titlePaint.setTextSize(dp(15));
        subPaint.setTextSize(dp(12));
        dotPaint.setStyle(Paint.Style.FILL);
        // The card's soft shadow is drawn with a shadow layer, which needs software.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    void setColors(int surface, int onSurface, int onSurfaceVariant, int primary,
                   int primaryContainer, int onPrimaryContainer) {
        colorSurface = surface | 0xFF000000;
        colorOnSurface = onSurface;
        colorOnSurfaceVariant = onSurfaceVariant;
        colorPrimary = primary;
        colorPrimaryContainer = primaryContainer | 0xFF000000;
        colorOnPrimaryContainer = onPrimaryContainer;
    }

    boolean isShowing() {
        return showing && !exiting;
    }

    /** Shows {@code list} near (x, y), the open document ({@code current}) highlighted. */
    void begin(float x, float y, List<String> list, int current, boolean up) {
        upward = up;
        docs.clear();
        docs.addAll(list);
        this.current = Math.max(0, Math.min(current, docs.size() - 1));
        index = this.current;
        shownIndex = index;
        anchorX = x;
        anchorY = y;
        showing = true;
        exiting = false;
        phaseStartMs = SystemClock.uptimeMillis();
        lastFrameMs = phaseStartMs;
        setVisibility(VISIBLE);
        invalidate();
    }

    /** Fingers moved {@code dy} px since the start: down goes further down the list. */
    void drag(float dy) {
        if (!isShowing() || docs.isEmpty()) return;
        // Sliding further the way it opened goes further back in the list.
        float along = upward ? -dy : dy;
        int next = Math.max(0, Math.min(docs.size() - 1, current + Math.round(along / dp(STEP_DP))));
        if (next != index) {
            index = next;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
        }
    }

    /** The document picked, or null when it is still the open one. */
    String selected() {
        if (docs.isEmpty() || index == current) return null;
        return docs.get(index);
    }

    void end() {
        if (!showing || exiting) return;
        exiting = true;
        phaseStartMs = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!showing) return;
        long now = SystemClock.uptimeMillis();
        float t;
        if (exiting) {
            t = 1f - EASE_IN.getInterpolation(Math.min(1f, (now - phaseStartMs) / (float) EXIT_MS));
            if (t <= 0f) {
                showing = false;
                exiting = false;
                setVisibility(GONE);
                return;
            }
        } else {
            t = EASE_OUT.getInterpolation(Math.min(1f, (now - phaseStartMs) / (float) ENTER_MS));
        }
        float dt = Math.min(0.05f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        shownIndex += (index - shownIndex) * Math.min(1f, dt * 18f);

        int n = docs.size();
        float w = Math.min(dp(CARD_W_DP), getWidth() - dp(32));
        float h = n * dp(ROW_H_DP) + 2 * dp(PAD_DP);
        float left = Math.max(dp(16), Math.min(getWidth() - w - dp(16), anchorX - w / 2f));
        // The open document's row sits at the fingers, so the list unrolls around them.
        float top = anchorY - dp(PAD_DP) - row(current) * dp(ROW_H_DP) - dp(ROW_H_DP) / 2f;
        top = Math.max(dp(16), Math.min(getHeight() - h - dp(16), top));
        card.set(left, top, left + w, top + h);

        c.save();
        c.scale(0.94f + 0.06f * t, 0.94f + 0.06f * t, card.centerX(), anchorY);
        int alpha = Math.round(255 * t);
        cardPaint.setColor(colorSurface);
        cardPaint.setAlpha(alpha);
        if (SketchStyle.on) {
            SketchStyle.drawCard(c, card, dp(24), cardPaint, alpha, getResources().getDisplayMetrics().density);
        } else {
            cardPaint.setShadowLayer(dp(16), 0, dp(4), (Math.round(0x55 * t) << 24));
            c.drawRoundRect(card, dp(24), dp(24), cardPaint);
            cardPaint.clearShadowLayer();
        }

        // Highlight pill under the pointed-at row.
        float rowTop = card.top + dp(PAD_DP);
        float shownRow = upward ? (n - 1 - shownIndex) : shownIndex;
        tmp.set(card.left + dp(PAD_DP), rowTop + shownRow * dp(ROW_H_DP),
                card.right - dp(PAD_DP), rowTop + (shownRow + 1) * dp(ROW_H_DP));
        pillPaint.setColor(colorPrimaryContainer);
        pillPaint.setAlpha(alpha);
        c.drawRoundRect(tmp, dp(16), dp(16), pillPaint);

        for (int i = 0; i < n; i++) {
            float y0 = rowTop + row(i) * dp(ROW_H_DP);
            boolean sel = i == index;
            int fg = sel ? colorOnPrimaryContainer : colorOnSurface;
            int sub = sel ? (colorOnPrimaryContainer & 0x00FFFFFF) | 0xB3000000 : colorOnSurfaceVariant;
            float x = card.left + dp(PAD_DP) + dp(14);
            int ic = Math.round(dp(20));
            int iy = Math.round(y0 + (dp(ROW_H_DP) - ic) / 2f);
            docIcon.setBounds(Math.round(x), iy, Math.round(x) + ic, iy + ic);
            docIcon.setTint(sel ? colorOnPrimaryContainer : colorOnSurfaceVariant);
            docIcon.setAlpha(alpha);
            docIcon.draw(c);
            float tx = x + ic + dp(14);
            float maxW = card.right - dp(PAD_DP) - dp(28) - tx;
            String path = docs.get(i);
            titlePaint.setColor(fg);
            titlePaint.setAlpha(alpha);
            subPaint.setColor(sub);
            subPaint.setAlpha(Math.round(alpha * ((sub >>> 24) / 255f)));
            String name = TextUtils.ellipsize(title(path), new android.text.TextPaint(titlePaint), maxW,
                    TextUtils.TruncateAt.END).toString();
            String where = TextUtils.ellipsize(folder(path), new android.text.TextPaint(subPaint), maxW,
                    TextUtils.TruncateAt.START).toString();
            c.drawText(name, tx, y0 + dp(ROW_H_DP) / 2f - dp(2), titlePaint);
            c.drawText(where, tx, y0 + dp(ROW_H_DP) / 2f + dp(15), subPaint);
            if (i == current) {
                // A dot marks the document that is open now.
                dotPaint.setColor(sel ? colorOnPrimaryContainer : colorPrimary);
                dotPaint.setAlpha(alpha);
                c.drawCircle(card.right - dp(PAD_DP) - dp(18), y0 + dp(ROW_H_DP) / 2f, dp(4), dotPaint);
            }
        }
        c.restore();
        if (showing) postInvalidateOnAnimation();
    }

    /** Where list item {@code i} is drawn, top row 0. */
    private int row(int i) {
        return upward ? docs.size() - 1 - i : i;
    }

    private static String title(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
    }

    private static String folder(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "Library";
    }

    private float dp(float v) {
        return v * density;
    }
}
