package me.hapke.inkside;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Page-based PDF document editor: finger pans/zooms the page stack, stylus draws.
 * Annotations (ink, images, text) live in app data; the PDF file is the page substrate.
 */
public class CodeCanvasView extends View {
    public enum Tool { PENCIL, ERASER, LASSO, TEXT }

    public interface Listener {
        /** Canvas content changed — host should persist. */
        void onContentChanged();

        /** The plus on the empty canvas (no document open) was tapped: make a new document. */
        default void onEmptyCreateRequested() {}

        /** Finger long-press on canvas — host shows Paste / Delete Page menu. */
        void onCanvasLongPress(float worldX, float worldY, float screenX, float screenY);

        /** Selection set changed (lasso finalize / clear / delete / tap). */
        void onSelectionChanged(boolean hasSelection);

        /** Selection or viewport moved — host should reposition floating actions. */
        void onSelectionLayoutChanged();

        /** Pan/zoom began or ended — host should disable floating actions mid-gesture. */
        default void onNavigationChanged(boolean navigating) {}

        /** Lasso region closed (may be empty inside). */
        void onLassoRegionChanged(boolean hasRegion);

        default void onTextFieldEditRequested(String id) {}

        /** A touch landed outside the field being edited — commit and close the editor. */
        default void onTextFieldEditDismissRequested() {}

        /** A text field was dragged open on the canvas (not a degenerate rect). */
        default void onTextFieldRectCreated() {}

        /** Text field content changed and may need LaTeX bitmap re-render. */
        default void onTextFieldNeedsLatexRender(String id) {}

        /**
         * User scrolled past the last page — host should grow the PDF (if blank-owned)
         * and call {@link #appendDocumentPage()}.
         */
        default void onRequestAppendPage() {}

        /**
         * User scrolled past the first page — host should prepend a blank page
         * and call {@link #prependDocumentPage()}.
         */
        default void onRequestPrependPage() {}

        /**
         * Double-tap on a placed element. The host may hand touches to the element so
         * its own controls work; until then the element behaves like any other image.
         */
        default void onLiveArtifactActivated(CanvasImage img) {}

        /**
         * Pen button was pressed, then the stylus moved in hover (no tip contact).
         * Host should supply favorites and call {@link #openFavoritesRadial}.
         */
        default void onFavoritesRadialOpenRequested(float x, float y) {}

        /** Tip pressed on a radial icon (short tap). */
        default void onFavoritesRadialPicked(String favoriteId) {}

        /** Tip long-pressed on a radial icon — host should remove it from favorites. */
        default void onFavoritesRadialRemoveRequested(String favoriteId) {}

        /**
         * Tip slid sideways off the radial's undo or redo icon: the host shows the
         * history scrubber at ({@code x}, {@code y}) in this view's coordinates.
         */
        default void onUndoScrubStart(float x, float y) {}

        /** The tip is {@code dx} px sideways from where the slide started. */
        default void onUndoScrubDrag(float dx) {}

        /** Tip lifted — the scrub ends at whatever step it reached. */
        default void onUndoScrubEnd() {}

        /** Whether undo / redo have anything to do changed. */
        default void onHistoryChanged(boolean canUndo, boolean canRedo) {}

        /**
         * Stylus barrel button edge. {@code primary} is {@link MotionEvent#BUTTON_STYLUS_PRIMARY};
         * otherwise secondary. {@code down} is true on press, false on release.
         */
        default void onStylusButton(boolean primary, boolean down) {}
    }

    private static final float ERASE_RADIUS_PX = 28f;
    private static final float MAX_IMAGE_WORLD = 520f;
    /** Gap left between auto-placed canvas objects. */
    private static final float PLACEMENT_GAP = 96f;

    private final Matrix viewMatrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] tmp = new float[2];
    private final float[] navTmp = new float[2];
    private final float[] worldTmp = new float[2];
    private final float[] matrixValues = new float[9];
    private final RectF tmpRect = new RectF();
    private final RectF visibleWorld = new RectF();
    private DashPathEffect lassoDash;
    private DashPathEffect selectionDash;
    private float cachedDashScale = -1f;
    private boolean inverseDirty = true;
    private float cachedViewScale = 1f;
    private boolean contentDirty;

    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int sceneBgColor = 0xFF12141A;
    private static volatile ThemeConfig.CodeStyle currentCodeStyle = ThemeConfig.CODE_STYLES[0];
    /** Selection / lasso chrome — kept in sync with the app theme. */
    private int chromeAccent = 0xFFC4B5FD;
    private boolean chromeLight;
    private final Paint erasePreviewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Drawn under the preview ring so it stays visible on white paper. */
    private final Paint eraseHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** The reserved slot an artifact's live overlay sits in. */
    private final Paint webSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lassoPath = new Path();
    private final Path strokeDrawPath = new Path();
    /** Smoothed brush width for the in-progress stroke (EMA). */
    private float inkSmoothedWidth = -1f;
    /**
     * Ephemeral tip of the in-progress stroke. Under {@link #MIN_SAMPLE_DIST_PX} the
     * pen still moves; we must not rewrite the last committed sample (that collapsed
     * curves into straight chords) — keep the tip here for live drawing only.
     */
    private boolean inkHasTip;
    private float inkTipX, inkTipY, inkTipW;
    /** Pointer id that owns the current ink/erase/lasso stylus gesture. */
    private int inkPointerId = -1;
    /** Stylus hover tracking for the favorites radial (drawn in-canvas). */
    private boolean hoverInside;
    private float lastHoverX;
    private float lastHoverY;
    private float prevHoverX;
    private float prevHoverY;
    private long prevHoverMs;
    private float hoverVelX;
    private float hoverVelY;
    /** Short hover trail for robust flick direction on a quick button tap. */
    private static final int HOVER_TRAIL = 64;
    private final float[] hoverTrailX = new float[HOVER_TRAIL];
    private final float[] hoverTrailY = new float[HOVER_TRAIL];
    private final long[] hoverTrailT = new long[HOVER_TRAIL];
    private int hoverTrailHead;
    private int hoverTrailCount;
    /** Primary-button gesture timing for direction sampling. */
    private static final long FAVORITES_PRE_PRESS_MS = 200L;
    /** Drop the last N ms before release so the button-up jolt doesn't count. */
    private static final long FAVORITES_END_TRIM_MS = 50L;
    /** Hover + button hold before the radial is shown (quick flicks stay invisible). */
    private static final long FAVORITES_SHOW_DELAY_MS = 300L;
    private long favoritesGesturePressMs;
    private float favoritesGestureCx;
    private float favoritesGestureCy;
    private float favoritesPrePressDirX;
    private float favoritesPrePressDirY;
    private boolean favoritesPrePressDirValid;
    private Runnable favoritesFinalizeRunnable;
    private Runnable favoritesShowRunnable;
    private final RadialFavoritesPainter favoritesRadial;
    /**
     * Tip contacted the screen — do not open the favorites radial until the
     * primary button is released (or tip lifts while the button is up).
     */
    private boolean favoritesTipBlocked;
    /** When the stylus was last seen, for palm rejection. */
    private long lastStylusAtMs;
    /** Finger input is ignored for this long after the stylus lifts. */
    private static final long PALM_WINDOW_MS = 900L;
    private boolean palmRejection = true;
    /** Hover-flick favorites + radial on primary pen hold. Off → hold is eraser only. */
    private boolean quickFavoritesEnabled = true;

    public boolean isPalmRejection() {
        return palmRejection;
    }

    public void setPalmRejection(boolean on) {
        palmRejection = on;
    }

    /** A thin outline around pen strokes (and text) that are close to the page colour. */
    private boolean penOutline;

    public boolean isPenOutline() {
        return penOutline;
    }

    public void setPenOutline(boolean on) {
        if (penOutline == on) return;
        penOutline = on;
        PenOutline.enabled = on;
        PenOutline.paper = this::getThemePaperColor;
        markSceneDirty();
        invalidate();
    }

    private boolean needsPenOutline(Stroke s) {
        return penOutline && s.brush == BRUSH_INK && PenOutline.needed(s.color);
    }

    public boolean isShapeSnapEnabled() {
        return shapeSnapEnabled;
    }

    /** Hold the pen still at the end of a stroke to turn it into a clean shape. */
    public void setShapeSnapEnabled(boolean on) {
        shapeSnapEnabled = on;
        if (!on) cancelShapeHold();
    }

    public boolean isQuickFavoritesEnabled() {
        return quickFavoritesEnabled;
    }

    public void setQuickFavoritesEnabled(boolean on) {
        quickFavoritesEnabled = on;
        if (!on) {
            cancelFavoritesRadial();
            releaseFavoritesRadialGesture();
            clearPenEraseArm();
        }
    }

    /**
     * True when this finger touch should be dropped because the hand it belongs to is
     * resting while the stylus writes. Writing on a tablet means a palm lands on the
     * glass constantly; without this it pans the canvas out from under the nib.
     */
    private boolean rejectAsPalm(MotionEvent event, int toolType) {
        if (!palmRejection) return false;
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            return false;
        }
        // A cancel has to get through whatever else is true: it is the event that
        // clears the ink state, and swallowing it left activeStroke set forever, after
        // which this method rejected every finger touch and the page could not be
        // panned again until the pen was used.
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) return false;
        if (activeStroke != null || inkPointerId >= 0) {
            // Same trap by a slower route: if the pen stroke was abandoned rather than
            // lifted, nothing will ever clear it. Treat a long-stale stroke as gone —
            // but only when a fresh touch starts, never mid-gesture: a pen held still
            // sends no events, and cutting its stroke off would be worse than the trap.
            boolean fresh = event.getActionMasked() == MotionEvent.ACTION_DOWN;
            if (fresh && System.currentTimeMillis() - lastStylusAtMs > INK_ABANDON_MS) {
                abandonInkStroke();
                return false;
            }
            return true;
        }
        return System.currentTimeMillis() - lastStylusAtMs < PALM_WINDOW_MS;
    }

    /** No pen event for this long with a stroke still open = the stroke is never coming back. */
    private static final long INK_ABANDON_MS = 2_000L;

    // ---- Shape snapping ---------------------------------------------------------------

    private boolean shapeSnapEnabled = true;
    /** Pen still this long, mid-stroke, snaps the stroke to a shape. */
    private static final long SHAPE_HOLD_MS = 500L;
    /** Screen dp the pen may wander and still count as held still. */
    private static final float SHAPE_HOLD_SLOP_DP = 5f;
    /** Smallest stroke (screen dp across) worth snapping. */
    private static final float SHAPE_MIN_DP = 22f;
    private static final long SHAPE_MORPH_MS = 180L;
    private static final int SHAPE_MORPH_POINTS = 128;
    private final Runnable shapeHoldRunnable = this::trySnapShape;
    private Stroke shapeHoldStroke;
    private float shapeHoldX;
    private float shapeHoldY;
    /** The shape as snapped; resizing always starts from it, so nothing drifts. */
    private ShapeRecognizer.Shape snapBase;
    private float snapHandleX;
    private float snapHandleY;
    private float snapWidth;
    /** Raw stroke resampled, morphing into the shape just after it snaps. */
    private float[] morphFromX;
    private float[] morphFromY;
    private long morphStartMs;
    private Stroke morphStroke;

    /** Start (or restart) the still-pen timer at this screen point. */
    private void armShapeHold(Stroke stroke, float sx, float sy) {
        removeCallbacks(shapeHoldRunnable);
        if (!shapeSnapEnabled || snapBase != null) return;
        shapeHoldStroke = stroke;
        shapeHoldX = sx;
        shapeHoldY = sy;
        postDelayed(shapeHoldRunnable, SHAPE_HOLD_MS);
    }

    /** Pen moved: only real movement restarts the timer, not the jitter of holding still. */
    private void trackShapeHold(Stroke stroke, float sx, float sy) {
        if (!shapeSnapEnabled || snapBase != null) return;
        float slop = SHAPE_HOLD_SLOP_DP * getResources().getDisplayMetrics().density;
        float dx = sx - shapeHoldX;
        float dy = sy - shapeHoldY;
        if (stroke != shapeHoldStroke || dx * dx + dy * dy > slop * slop) {
            armShapeHold(stroke, sx, sy);
        }
    }

    private void cancelShapeHold() {
        removeCallbacks(shapeHoldRunnable);
        shapeHoldStroke = null;
        snapBase = null;
        morphFromX = null;
        morphFromY = null;
    }

    private void trySnapShape() {
        Stroke s = activeStroke;
        if (s == null || s != shapeHoldStroke || snapBase != null || !shapeSnapEnabled) return;
        int n = s.samples.size();
        if (n < 4) return;
        float[] xs = new float[n];
        float[] ys = new float[n];
        float[] ws = new float[n];
        for (int i = 0; i < n; i++) {
            Sample p = s.samples.get(i);
            xs[i] = p.x;
            ys[i] = p.y;
            ws[i] = p.width;
        }
        float dens = getResources().getDisplayMetrics().density;
        float minSize = SHAPE_MIN_DP * dens / Math.max(0.01f, viewScale());
        ShapeRecognizer.Shape shape = ShapeRecognizer.recognize(xs, ys, n, minSize);
        if (shape == null) return;
        java.util.Arrays.sort(ws);
        snapWidth = ws[n / 2];
        float[][] from = ShapeRecognizer.resample(xs, ys, n, SHAPE_MORPH_POINTS);
        morphFromX = from != null ? from[0] : null;
        morphFromY = from != null ? from[1] : null;
        morphStartMs = android.os.SystemClock.uptimeMillis();
        snapBase = shape;
        // Resizing is measured from where the pen is now.
        Sample last = s.samples.get(n - 1);
        snapHandleX = inkHasTip ? inkTipX : last.x;
        snapHandleY = inkHasTip ? inkTipY : last.y;
        inkHasTip = false;
        applyShape(s, shape);
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        fireLiveStroke();
        postInvalidateOnAnimation();
    }

    /** Pen moved while a snapped shape is held: reshape it to follow. */
    private void resizeSnappedShape(float worldX, float worldY) {
        if (snapBase == null || activeStroke == null) return;
        applyShape(activeStroke,
                ShapeRecognizer.resized(snapBase, snapHandleX, snapHandleY, worldX, worldY));
        fireLiveStroke();
        postInvalidateOnAnimation();
    }

    /** Replace the stroke's samples with the shape's outline at an even width. */
    private void applyShape(Stroke s, ShapeRecognizer.Shape shape) {
        s.samples.clear();
        float w = snapWidth;
        // Close enough together for the eraser and lasso, which test samples.
        float spacing = Math.max(2f, w * 0.8f);
        int curve = 48;
        if (!shape.hasCorners()) {
            double perim = 2 * Math.PI * Math.sqrt((shape.hu * shape.hu + shape.hv * shape.hv) / 2);
            curve = (int) Math.max(48, Math.min(720, perim / spacing));
        }
        float[] o = ShapeRecognizer.outline(shape, curve);
        int k = o.length / 2;
        if (!shape.hasCorners()) {
            for (int i = 0; i < k; i++) s.samples.add(new Sample(o[2 * i], o[2 * i + 1], w));
            s.recomputeBounds();
            return;
        }
        // Closed outlines start mid-edge, so every real corner is an interior vertex.
        float[] seq;
        if (shape.isClosed()) {
            int m = k - 1;
            seq = new float[2 * (m + 2)];
            float mx = (o[0] + o[2]) / 2;
            float my = (o[1] + o[3]) / 2;
            seq[0] = mx;
            seq[1] = my;
            for (int i = 1; i <= m; i++) {
                seq[2 * i] = o[2 * (i % m)];
                seq[2 * i + 1] = o[2 * (i % m) + 1];
            }
            seq[2 * (m + 1)] = mx;
            seq[2 * (m + 1) + 1] = my;
        } else {
            seq = o;
        }
        // Midpoint smoothing rounds a corner as far as its neighbours sit from it:
        // a sample just either side keeps corners crisp.
        float eps = Math.max(0.2f, w * 0.3f);
        int segs = seq.length / 2 - 1;
        for (int i = 0; i < segs; i++) {
            float px = seq[2 * i], py = seq[2 * i + 1];
            float qx = seq[2 * i + 2], qy = seq[2 * i + 3];
            float len = (float) Math.hypot(qx - px, qy - py);
            if (len < 1e-4f) continue;
            float ex = (qx - px) / len;
            float ey = (qy - py) / len;
            float near = Math.min(eps, len / 3f);
            if (i == 0) s.samples.add(new Sample(px, py, w));
            else s.samples.add(new Sample(px + ex * near, py + ey * near, w));
            for (float d = spacing; d < len - spacing * 0.5f; d += spacing) {
                s.samples.add(new Sample(px + ex * d, py + ey * d, w));
            }
            if (i < segs - 1) s.samples.add(new Sample(qx - ex * near, qy - ey * near, w));
            s.samples.add(new Sample(qx, qy, w));
        }
        s.recomputeBounds();
    }

    /**
     * Draws the stroke part-way between how it was drawn and the shape it snapped to.
     * Returns false once the morph is over (or there is none).
     */
    private boolean drawShapeMorph(Canvas canvas, Stroke s) {
        if (snapBase == null || morphFromX == null) return false;
        long el = android.os.SystemClock.uptimeMillis() - morphStartMs;
        if (el >= SHAPE_MORPH_MS) {
            morphFromX = null;
            morphFromY = null;
            return false;
        }
        int n = s.samples.size();
        if (n < 2) return false;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = s.samples.get(i).x;
            ys[i] = s.samples.get(i).y;
        }
        if (snapBase.isClosed()) alignLoop(xs, ys, morphFromX, morphFromY);
        float[][] to = ShapeRecognizer.resample(xs, ys, n, SHAPE_MORPH_POINTS);
        if (to == null) return false;
        float t = SHAPE_MORPH_EASE.getInterpolation(el / (float) SHAPE_MORPH_MS);
        if (morphStroke == null) morphStroke = new Stroke(s.color, s.colorName);
        morphStroke.color = s.color;
        morphStroke.brush = s.brush;
        morphStroke.samples.clear();
        for (int i = 0; i < SHAPE_MORPH_POINTS; i++) {
            morphStroke.samples.add(new Sample(
                    morphFromX[i] + (to[0][i] - morphFromX[i]) * t,
                    morphFromY[i] + (to[1][i] - morphFromY[i]) * t, snapWidth));
        }
        if (s.brush != BRUSH_INK) drawEffectStroke(canvas, morphStroke, strokePaint, true);
        else drawStrokeSegments(canvas, morphStroke, strokePaint, strokeDrawPath);
        postInvalidateOnAnimation();
        return true;
    }

    private static final android.view.animation.PathInterpolator SHAPE_MORPH_EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * Rotate (and if needed reverse) a closed loop so it starts nearest where the hand
     * drawing began and runs the same way round — otherwise the morph would swirl.
     */
    private static void alignLoop(float[] xs, float[] ys, float[] refX, float[] refY) {
        int n = xs.length - 1; // last point repeats the first
        if (n < 3) return;
        double a1 = 0, a2 = 0;
        for (int i = 0; i < n; i++) a1 += xs[i] * ys[i + 1] - xs[i + 1] * ys[i];
        for (int i = 0; i < refX.length - 1; i++) a2 += refX[i] * refY[i + 1] - refX[i + 1] * refY[i];
        float[] bx = new float[n];
        float[] by = new float[n];
        for (int i = 0; i < n; i++) {
            int j = (a1 >= 0) == (a2 >= 0) ? i : (n - i) % n;
            bx[i] = xs[j];
            by[i] = ys[j];
        }
        int start = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float dx = bx[i] - refX[0];
            float dy = by[i] - refY[0];
            float d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                start = i;
            }
        }
        for (int i = 0; i <= n; i++) {
            xs[i] = bx[(start + i) % n];
            ys[i] = by[(start + i) % n];
        }
    }

    /** Drop a stroke the pen never finished, keeping what it had drawn. */
    private void abandonInkStroke() {
        cancelShapeHold();
        if (activeStroke != null && !activeStroke.samples.isEmpty()) {
            Stroke done = activeStroke;
            done.recomputeBounds();
            recordUndoPoint();
            inkStrokes.add(done);
            commitStrokeToBackdrop(done);
            post(this::notifyContentChanged);
        }
        drawBaseline = null;
        activeStroke = null;
        clearInkPointer();
        flushPendingPdfReady();
        invalidate();
    }
    /** Min spacing between stored samples, in screen pixels. */
    private static final float MIN_SAMPLE_DIST_PX = 0.75f;
    private static final float WIDTH_SMOOTH = 0.4f;
    /**
     * A stroke's first samples take on the width it settles into by its
     * {@code START_SETTLE_SAMPLES}th sample. Pen-down pressure is unreliable (it often
     * spikes), and drawn as it came it left a fat dot at the start of every stroke.
     */
    private static final int START_SETTLE_SAMPLES = 4;
    /**
     * How closely the rendered curve tracks the pen, 0..1.
     *
     * <p>Each segment is a quadratic through the midpoints of neighbouring samples.
     * At 0 the sample is only a control point, so the curve is pulled toward it but
     * never reaches it — that rounding is what reads as stabilisation. At 1 the
     * control point is placed so the curve passes exactly through the sample.
     */
    private float strokeFollow = 0.85f;

    /** 0 = follows the pen exactly, 1 = maximum smoothing. */
    public float getStabilization() {
        return 1f - strokeFollow;
    }

    public void setStabilization(float amount) {
        strokeFollow = 1f - Math.max(0f, Math.min(1f, amount));
        strokeGeomVersion++;
        markSceneDirty();
        invalidate();
    }

    /**
     * How much stylus pressure varies stroke width. 0 = constant width,
     * 1 = full pressure response.
     */
    private float pressureSensitivity = 1f;

    public float getPressureSensitivity() {
        return pressureSensitivity;
    }

    public void setPressureSensitivity(float amount) {
        pressureSensitivity = Math.max(0f, Math.min(1f, amount));
    }

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final OverScroller panScroller;
    private VelocityTracker panVelocityTracker;
    private boolean panFlinging;
    /**
     * Document camera — ScrollView-style: screen = world * camScale - (camScrollX, camScrollY).
     * Fling drives absolute scroll positions via {@link OverScroller} (same as framework ScrollView).
     */
    private float camScale = 1f;
    private float camScrollX = 0f;
    private float camScrollY = 0f;
    /** Platform friction — native ScrollView feel. */
    private static final float PAN_FLING_FRICTION = 1f;
    private float lastFocusX, lastFocusY;
    private boolean navigating;
    private boolean suppressFingerNav;
    private boolean fingerPanArmed;
    /** True after DOWN interrupted a coast — tap settles; only touch-slop arms a new pan. */
    private boolean interruptedPanFling;
    /** Scale at the start of the current pinch, and whether it has actually changed. */
    private float pinchStartScale = 1f;
    private boolean pinchChangedScale;
    private float fingerDownX, fingerDownY;
    private final Runnable fingerLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            // Only a real pan cancels long-press — beginNavigation() freezes the
            // backdrop without setting fingerPanArmed.
            if (fingerPanArmed || suppressFingerNav) return;
            if (movingSelection || transformingSelection || drawingLasso) return;
            suppressFingerNav = true;
            setNavigating(false);
            endNavigation();
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            float[] w = screenToWorld(fingerDownX, fingerDownY);
            if (listener != null) {
                listener.onCanvasLongPress(w[0], w[1], fingerDownX, fingerDownY);
            }
        }
    };

    private final List<Stroke> inkStrokes = new ArrayList<>();
    private final List<CanvasImage> images = new ArrayList<>();
    private final List<CanvasTextField> textFields = new ArrayList<>();
    /** Placed artifacts are images with a livePath — same selection, same everything. */
    public List<CanvasImage> getLiveImages() {
        List<CanvasImage> out = new ArrayList<>();
        for (CanvasImage img : images) {
            if (img.isLive()) out.add(img);
        }
        return out;
    }

    /** CSS pixels per world unit inside a placed element. */
    public static final float WEB_CSS_PER_WORLD = 2.4f;

    private static final float WEB_BLOCK_PAGE_FRACTION = 0.38f;

    private float defaultLiveWidth() {
        float pageW = document.pageWidth > 1f ? document.pageWidth : 595f;
        return Math.max(180f, pageW * WEB_BLOCK_PAGE_FRACTION);
    }

    /** Place an artifact at the middle of what is on screen. */
    public CanvasImage addLiveArtifact(String path) {
        float[] c = getViewCenterWorld();
        float w = defaultLiveWidth();
        CanvasImage img = new CanvasImage(null, c[0], c[1], w, w * 0.66f);
        img.livePath = path;
        recordUndoPoint();
        images.add(img);
        markSceneDirty();
        invalidate();
        notifyContentChanged();
        notifySelectionLayout();
        return img;
    }

    /**
     * Lock a placed element to the shape its content actually wants.
     *
     * <p>Called once the host has measured the artifact. The top edge stays put, so a
     * fit does not appear to jump the element up the page.
     */
    public void setLiveAspect(CanvasImage img, float heightOverWidth) {
        if (img == null || !img.isLive()) return;
        if (!(heightOverWidth > 0.05f) || heightOverWidth > 6f) return;
        img.liveAspect = heightOverWidth;
        float want = img.width * heightOverWidth;
        if (Math.abs(want - img.height) < 0.5f) return;
        img.setCenter(img.cx, img.cy + (want - img.height) * 0.5f);
        img.setSize(img.width, want);
        markSceneDirty();
        invalidate();
        notifyContentChanged();
        notifySelectionLayout();
    }

    /**
     * Put every placed element back on its own aspect ratio.
     *
     * <p>A slot whose proportions drift away from the page inside it just crops or pads
     * the page, so the ratio is not the user's to change — resizing decides how much of
     * the artifact is on screen, nothing else.
     */
    private void enforceLiveAspects() {
        for (CanvasImage img : images) {
            if (img == null || !img.isLive() || img.liveAspect <= 0f) continue;
            float want = img.width * img.liveAspect;
            if (Math.abs(want - img.height) > 0.5f) img.setSize(img.width, want);
        }
    }

    /** The completed tap that a second one could pair with, or null. */
    private CanvasImage lastLiveTapImg;
    private long lastLiveTapMs;
    private float lastLiveTapX;
    private float lastLiveTapY;
    /** The press in flight, held until its release says whether it was a tap. */
    private CanvasImage pendingLiveTapImg;
    private long pendingLiveTapMs;
    private float pendingLiveTapX;
    private float pendingLiveTapY;
    /** True from the entering tap until its UP, so no pan starts underneath. */
    private boolean swallowingLiveEnter;
    private static final float LIVE_TAP_SLOP = 48f;

    private CanvasImage liveArtifactAt(float screenX, float screenY) {
        float[] w = screenToWorld(screenX, screenY);
        for (int i = images.size() - 1; i >= 0; i--) {
            CanvasImage img = images.get(i);
            if (img.isLive() && img.contains(w[0], w[1])) return img;
        }
        return null;
    }

    /**
     * Second finger tap inside the same placed element — hand it to the host.
     *
     * @return true when this press completes a double tap and the host was told
     */
    private boolean liveArtifactDoubleTap(float screenX, float screenY) {
        CanvasImage hit = liveArtifactAt(screenX, screenY);
        pendingLiveTapImg = hit;
        pendingLiveTapMs = System.currentTimeMillis();
        pendingLiveTapX = screenX;
        pendingLiveTapY = screenY;
        if (hit == null) {
            lastLiveTapImg = null;
            return false;
        }
        boolean second = hit == lastLiveTapImg
                && pendingLiveTapMs - lastLiveTapMs
                        <= android.view.ViewConfiguration.getDoubleTapTimeout()
                && Math.abs(screenX - lastLiveTapX) < LIVE_TAP_SLOP
                && Math.abs(screenY - lastLiveTapY) < LIVE_TAP_SLOP;
        if (!second) return false;
        lastLiveTapImg = null;
        pendingLiveTapImg = null;
        if (listener != null) listener.onLiveArtifactActivated(hit);
        return true;
    }

    /**
     * Decide whether the press that just ended counts as the first of a double tap.
     *
     * <p>Only a real tap does. Pairing two presses regardless meant two quick drags
     * that happened to start in the same place — scrolling the page twice, say —
     * dropped the user inside the element.
     */
    private void noteLiveTapRelease(boolean up, float screenX, float screenY) {
        CanvasImage img = pendingLiveTapImg;
        pendingLiveTapImg = null;
        if (img == null) return;
        long now = System.currentTimeMillis();
        boolean tap = up
                && now - pendingLiveTapMs <= android.view.ViewConfiguration.getTapTimeout() * 2L
                && Math.abs(screenX - pendingLiveTapX) < LIVE_TAP_SLOP
                && Math.abs(screenY - pendingLiveTapY) < LIVE_TAP_SLOP;
        if (!tap) {
            lastLiveTapImg = null;
            return;
        }
        lastLiveTapImg = img;
        lastLiveTapMs = now;
        lastLiveTapX = screenX;
        lastLiveTapY = screenY;
    }

    /** Screen rect for a placed element, so the host can sit its WebView on it. */
    public RectF getImageScreenRect(CanvasImage img) {
        if (img == null) return null;
        RectF r = new RectF(img.bounds());
        viewMatrix.mapRect(r);
        return r;
    }
    private Stroke activeStroke;
    /** Field currently open in the in-place editor; its text is drawn by the overlay instead. */
    private CanvasTextField editingTextField;

    /** Open PDF document (null / closed = empty state). */
    private final DocumentPages document = new DocumentPages();
    private boolean appendPageRequested;
    private boolean prependPageRequested;
    /** 0..1 fill for the overscroll “add page” ring. */
    private float overscrollCharge;
    /** True = charge above first page (prepend); false = below last page (append). */
    private boolean overscrollAtTop;
    /** True while animating the page stack back after an incomplete overscroll. */
    private boolean overscrollSnapping;
    private android.animation.ValueAnimator overscrollSnapAnimator;
    /** Visual rubber-band travel after the slack zone (screen px). */
    private static final float OVERSCROLL_MAX_PX = 620f;
    /** Pull past slack needed to fill the ring / commit a page. */
    private static final float OVERSCROLL_CHARGE_PX = 480f;
    /** Ring full: the indicator shows "ready", and releasing now adds a page. */
    private static final float OVERSCROLL_READY = 0.999f;
    /** Ready state already announced with a haptic tick for this pull. */
    private boolean overscrollReadyFelt;
    /** Resting inset above first / below last page (dp). */
    private static final float DOCUMENT_REST_PAD_DP = 52f;
    /** Extra pull past rest before the add-page ring starts charging (dp). */
    private static final float OVERSCROLL_SLACK_DP = 48f;
    private final Paint overscrollRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overscrollFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overscrollPlusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF overscrollArcRect = new RectF();
    private final float[] overscrollEdgePts = new float[2];
    /** Reused by {@link #panCameraBy} to avoid allocations during drag. */
    private final float[] panScrollRangeTmp = new float[4];
    private int pagePaperColor = 0xFFFFFFFF;
    private final Paint emptyHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int inkColor = 0xFF4C8DFF;
    private String inkName = "blue";
    private float baseThicknessPx = 4.5f;
    private boolean eraseMode = false;
    private Tool currentTool = Tool.PENCIL;
    private float eraseX = Float.NaN, eraseY = Float.NaN;
    private Listener listener;

    private boolean selectInk = true;
    private boolean selectImages = true;
    /** Lasso-only target filters (independent from eraser). */
    private boolean lassoSelectInk = true;
    private boolean lassoSelectImages = true;
    /** Eraser-only target filters (independent from lasso). */
    private boolean lassoSelectHighlighter = true;
    private boolean lassoSelectText = true;
    private boolean eraseSelectInk = true;
    private boolean eraseSelectImages = true;
    /** Highlighter strokes are a target of their own, apart from other ink. */
    private boolean eraseSelectHighlighter = true;
    /** Text boxes are off by default: a sweep through notes should not take typed text. */
    private boolean eraseSelectText = false;
    /** Eraser circle radius on screen, px (zoom-independent). */
    private float eraseRadiusPx = ERASE_RADIUS_PX;
    private boolean imagePasteArmed = false;

    private final List<float[]> lassoPoints = new ArrayList<>();
    private boolean drawingLasso;
    private final RectF lassoRegionBounds = new RectF();
    private boolean hasLassoRegion;

    private boolean drawingTextRect;
    private float textRectStartX, textRectStartY, textRectEndX, textRectEndY;

    private final List<Stroke> selectedStrokes = new ArrayList<>();
    private final List<CanvasImage> selectedImages = new ArrayList<>();
    private final List<CanvasTextField> selectedTextFields = new ArrayList<>();

    private boolean movingSelection;
    private boolean transformingSelection;
    private float selLastScreenX, selLastScreenY;

    private enum SelGesture { NONE, MOVE, ROTATE, SCALE, RESIZE }
    private SelGesture selGesture = SelGesture.NONE;
    private int resizeHandle = -1;
    private final RectF resizeStartUnion = new RectF();
    private float resizeAnchorX, resizeAnchorY;
    private float gimbalPivotX, gimbalPivotY;
    private float gimbalPrevAngle;
    private float gimbalPrevDist;
    private final Paint gimbalFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gimbalStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** During move/rotate/scale: selected items drawn in overlay, backdrop omits them. */
    private boolean selOverlayActive;
    /** Oriented selection chrome for strokes/images (box + gimbals rotate with content). */
    private boolean selFrameValid;
    private float selFrameRotDeg;
    private float selFramePivotX, selFramePivotY;
    private final RectF selFrameLocalBounds = new RectF();

    private static final int MAX_UNDO = 60;
    private final ArrayDeque<ContentSnap> undoStack = new ArrayDeque<>();
    private final ArrayDeque<ContentSnap> redoStack = new ArrayDeque<>();
    private ContentSnap gestureStartSnap;
    private ContentSnap drawBaseline;
    private ContentSnap eraseBaseline;
    private boolean eraseDidRemove;
    /** World-space dirty region accumulated during a live erase gesture. */
    private final RectF eraseDirtyWorld = new RectF();
    private boolean eraseDirtyValid;
    private final Rect eraseDirtyScreen = new Rect();
    private final float[] eraseMapPts = new float[8];
    /** When set, drawSceneWorld culls to this world rect instead of the full viewport. */
    private RectF sceneCullOverride;

    /** Screen-space cache of the static scene for low-latency stylus drawing. */
    private Bitmap sceneBackdrop;
    /**
     * Back buffer for full scene rebuilds. Erasing {@link #sceneBackdrop} in place while
     * the GPU is still scanning it out (previous frame) flashes the whole canvas —
     * especially visible on stylus hover/touch edges that force a redraw. Rebuild into
     * this buffer, then swap.
     */
    private Bitmap sceneBackdropBack;
    private boolean sceneBackdropDirty = true;
    private boolean sceneBackdropReady = false;
    private final Paint backdropPaint = new Paint();
    private final Canvas sceneBackdropCanvas = new Canvas();

    /** Screen-space snapshot transformed during pan/zoom instead of redrawing the world.
     * Uses {@link #sceneBackdrop} directly (no second bitmap copy) so pan starts without hitch. */
    private boolean navSnapshotActive;
    private final Matrix navStartViewMatrix = new Matrix();
    private final Matrix navSnapshotMatrix = new Matrix();
    private final Matrix navInverseStart = new Matrix();
    /** No FILTER_BITMAP — bilinear on a large overscan bitmap every pinch frame is costly. */
    private final Paint navSnapshotPaint = new Paint();
    /**
     * No overscan: the scene bitmap matches the viewport. Pan/zoom redraws live
     * (Lineage-style simplicity) instead of sliding a huge cached freeze.
     */
    private static final float OVERSCAN_FRAC = 0.45f;
    /**
     * Vertical margin, which is the one that matters: scrolling is what exposes new
     * ground. 0.45 left ~340px of the bitmap cap unused on this screen, so the freeze
     * ran out and had to be rebuilt sooner than it needed to.
     */
    private static final float OVERSCAN_FRAC_Y = 0.64f;
    private static final int MAX_BACKDROP_DIM = 4096;
    /** Screen-space offset of the overscanned bitmap's origin from the viewport's. */
    private int overscanX;
    private int overscanY;
    private float navStartTransX;
    private float navStartTransY;
    private float navStartScale = 1f;
    /**
     * Share of the overscan margin the view may drift before the freeze is redrawn.
     * At 1.0 the redraw only started once the margin was used up, so every fast
     * scroll showed bare background for the ~60ms the redraw took, and pages, ink
     * and images popped in each time it landed.
     */
    private static final float NAV_SLIDE_FRAC = 0.3f;
    /** How far ahead of the scroll a mid-gesture freeze is drawn, in ms of motion. */
    private static final float NAV_LEAD_MS = 120f;
    /** Lead cap as a share of the margin, so the current screen stays covered. */
    private static final float NAV_LEAD_MAX_FRAC = 0.7f;
    /** Smoothed camera velocity during navigation, screen px per ms. */
    private float navVelX;
    private float navVelY;
    private float navLastTransX;
    private float navLastTransY;
    private long navLastMoveMs;
    /** PDF page landed while ink/erase was active — apply after the tip lifts. */
    private boolean pendingPdfReady;
    private static final long PDF_READY_COALESCE_MS = 200L;
    private final Runnable coalescePdfReadyRunnable = this::flushCoalescedPdfReady;
    private boolean pdfReadyCoalescePosted;
    private final java.util.concurrent.ExecutorService navRebuildExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cc-nav-rebuild");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private volatile boolean navRebuildInFlight;
    /**
     * Set when the frozen scene's content (not just its camera) is out of date, so the
     * next redraw must be a full one rather than a shift plus the newly exposed strip.
     */
    private boolean navFreezeFullDirty = true;
    /**
     * GPU copy of {@link #sceneBackdrop}, made on the rebuild thread, blitted while
     * scrolling. Drawing the 47MB software freeze made the render thread upload it
     * first — a janky frame after every mid-scroll redraw. Valid only while
     * {@link #sceneBackdropHwFor} is the current backdrop; dropped when a gesture
     * starts, since in-place edits (committed ink) do not reach it.
     */
    private Bitmap sceneBackdropHw;
    private Bitmap sceneBackdropHwFor;
    /** Backdrop the rebuild thread is copying from; never recycled out from under it. */
    private volatile Bitmap navRebuildSource;
    private final float[] navSrcVals = new float[9];
    private final float[] navDstVals = new float[9];
    /** True from the moment the rebuild task starts until it actually exits. */
    private volatile boolean navRebuildBusy;
    private boolean navRebuildSkipSelected;
    private int navRebuildGen;
    /** Draw state owned exclusively by the rebuild thread — see {@link SceneKit}. */
    private final SceneKit navKit = new SceneKit();
    /**
     * Scroll redraws split into horizontal bands drawn on this many threads. One
     * thread took 60-170ms on a page of dense handwriting — slower than a fling
     * exposes new ground — while the other cores sat idle.
     */
    private static final int NAV_BANDS = 4;
    private final SceneKit[] navBandKits = {navKit, new SceneKit(), new SceneKit(), new SceneKit()};
    private final Canvas[] navBandCanvases = {new Canvas(), new Canvas(), new Canvas(), new Canvas()};
    private final java.util.concurrent.ExecutorService navBandPool =
            java.util.concurrent.Executors.newFixedThreadPool(NAV_BANDS - 1, r -> {
                Thread t = new Thread(r, "cc-nav-band");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    /** Text fields and images share draw state internally; one draws at a time. */
    private final Object sharedItemDrawLock = new Object();
    private Bitmap navBuildBitmap;
    private final Canvas navBuildCanvas = new Canvas();
    private final Matrix navRebuildMatrix = new Matrix();
    private final Matrix navRebuildInverse = new Matrix();
    private final float[] navRebuildPts = new float[4];
    private final RectF navRebuildCull = new RectF();
    /** True between scale begin/end — pinch path owns focus pan. */
    private boolean pinchScaling;

    /** Frozen screen-space blit of the selection during MOVE/RESIZE (avoids redrawing every frame). */
    private Bitmap selDragBitmap;
    private final Canvas selDragCanvas = new Canvas();
    private final Paint selDragPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float[] selDragMapPts = new float[8];
    private boolean selDragBitmapReady;
    private float selDragScreenDx;
    private float selDragScreenDy;
    /** True when sceneBackdrop was last built with selected items omitted. */
    private boolean sceneBackdropOmitsSelection;

    /**
     * Everything a scene draw mutates while it runs: paints, the scratch path, the
     * cull rect, and the object lists it walks.
     *
     * <p>The UI thread and the background rebuild draw the same scene at the same
     * time. They used to share the view's {@code strokePaint} / {@code strokeDrawPath}
     * / {@code visibleWorld} fields, so one thread's {@code setColor} landed on the
     * other's {@code drawPath} and ink came out in other strokes' colours until the
     * next clean repaint. Each thread now draws through its own kit.
     */
    private static final class SceneKit {
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Path path = new Path();
        final RectF cull = new RectF();
        final RectF itemBounds = new RectF();
        /** Snapshots taken on the UI thread, so the walk cannot trip over an edit. */
        List<Stroke> strokes = new ArrayList<>();
        List<CanvasTextField> texts = new ArrayList<>();
        List<CanvasImage> imgs = new ArrayList<>();
        final Set<Stroke> skipStrokes = new HashSet<>();
        final Set<CanvasTextField> skipTexts = new HashSet<>();
        final Set<CanvasImage> skipImages = new HashSet<>();
        CanvasTextField skipEditing;
        /** Leave out the document's pages (paper + PDF): the export's annotation layer. */
        boolean skipDocument;
    }

    /**
     * A finished stroke as a handful of reusable paths, one per run of segments whose
     * widths agree within {@link #GEOM_WIDTH_TOLERANCE}.
     *
     * <p>Drawing segment by segment — reset a scratch path, one drawPath per sample —
     * meant thousands of draw calls for a page of handwriting, none of which the GPU
     * could cache across frames. Every scroll-time redraw paid that in full, which
     * made scrolling handwritten documents sluggish. Built once, read-only after, so
     * the UI and rebuild threads can both draw it.
     */
    private static final class StrokeGeom {
        final Path[] paths;
        final float[] widths;
        /** {@link #strokeGeomVersion} it was built under — smoothing changes the curve. */
        final int version;
        /** Effect brushes: a filled shape (calligraphy ribbon, sparkle stars). */
        Path fill;
        /** Effect brushes: point batches, one per size, drawn with drawPoints. */
        float[][] dots;
        float[] dotSizes;
        int[] dotAlphas;
        /** Rainbow: line segments x0,y0,x1,y1… and a colour per segment. */
        float[] segs;
        int[] segColors;

        StrokeGeom(Path[] paths, float[] widths, int version) {
            this.paths = paths;
            this.widths = widths;
            this.version = version;
        }
    }

    private static final float GEOM_WIDTH_TOLERANCE = 0.04f;
    /** Bumped when stroke curve settings change so cached geometry is rebuilt. */
    private volatile int strokeGeomVersion;

    private static final class Sample {
        float x, y, width;

        Sample(float x, float y, float width) {
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }

    private static final class Stroke {
        final List<Sample> samples = new ArrayList<>();
        /** Changeable afterwards (recolouring a selection); undo snapshots keep it. */
        int color;
        String colorName;
        final RectF bounds = new RectF();
        /**
         * Serialised form, built once and dropped on any geometry change. Rebuilding
         * every stroke's JSON on the UI thread each autosave (400ms after each stroke)
         * grew with the page and stalled the next stroke. Never mutated once built,
         * so the background writer can serialise it safely.
         */
        org.json.JSONObject json;
        /** Cached draw geometry; see {@link StrokeGeom}. Dropped on any geometry change. */
        volatile StrokeGeom geom;
        /** Brush it was drawn with: {@link #BRUSH_INK} or one of the effect brushes. */
        int brush = BRUSH_INK;
        /** Left out of the presentation slide; still shown on the tablet. */
        boolean presentHidden;

        Stroke(int color, String colorName) {
            this.color = color;
            this.colorName = colorName;
        }

        boolean hit(float x, float y, float radius) {
            if (!bounds.isEmpty()) {
                float pad = radius + 8f;
                if (x < bounds.left - pad || x > bounds.right + pad
                        || y < bounds.top - pad || y > bounds.bottom + pad) {
                    return false;
                }
            }
            for (Sample p : samples) {
                float dx = p.x - x;
                float dy = p.y - y;
                float hitR = radius + p.width * 0.5f;
                if (dx * dx + dy * dy <= hitR * hitR) return true;
            }
            return false;
        }

        void recomputeBounds() {
            json = null;
            geom = null;
            if (samples.isEmpty()) {
                bounds.setEmpty();
                return;
            }
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float half = 0.5f;
            for (Sample p : samples) {
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                half = Math.max(half, p.width * 0.5f);
            }
            // A glow reaches well past the line itself.
            half *= brushReach(brush);
            // Include the brush so the box always has area. Sample-only bounds are
            // zero-area for a dot (and zero-height for a perfectly straight line),
            // which reads as isEmpty() everywhere else: such a stroke was never
            // culled, never shifted by a page insert, and contributed nothing to a
            // selection box or to the erase dirty region.
            bounds.set(minX - half, minY - half, maxX + half, maxY + half);
        }

        void translate(float dx, float dy) {
            json = null;
            geom = null;
            for (Sample p : samples) {
                p.x += dx;
                p.y += dy;
            }
            if (!bounds.isEmpty()) bounds.offset(dx, dy);
        }

        Stroke duplicate() {
            Stroke n = new Stroke(color, colorName);
            n.brush = brush;
            n.presentHidden = presentHidden;
            for (Sample p : samples) {
                n.samples.add(new Sample(p.x, p.y, p.width));
            }
            n.recomputeBounds();
            return n;
        }

        void rotateScaleAround(float pivotX, float pivotY, float cos, float sin, float scale) {
            for (Sample p : samples) {
                float dx = (p.x - pivotX) * scale;
                float dy = (p.y - pivotY) * scale;
                p.x = pivotX + cos * dx - sin * dy;
                p.y = pivotY + sin * dx + cos * dy;
                p.width *= scale;
            }
            recomputeBounds();
        }
    }

    public CodeCanvasView(Context context) {
        this(context, null);
    }

    public CodeCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        favoritesRadial = new RadialFavoritesPainter(context);
        favoritesRadial.setDensity(getResources().getDisplayMetrics().density);
        favoritesRadial.setRedraw(this::invalidate);
        radialTipLongPress = this::onRadialTipLongPress;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        // No default focus ring / theme foreground — hover and tip-down flip those
        // states and were flashing a full-canvas highlight on stylus proximity.
        setBackground(null);
        if (Build.VERSION.SDK_INT >= 23) setForeground(null);
        if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);

        gutterPaint.setColor(0xFF6B7385);
        gutterPaint.setTextSize(22f);
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setTextAlign(Paint.Align.RIGHT);

        titlePaint.setColor(0xFF9AA3B5);
        titlePaint.setTextSize(22f);

        paperPaint.setColor(0xFF0E1016);
        paperPaint.setStyle(Paint.Style.FILL);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        webSlotPaint.setStyle(Paint.Style.FILL);
        webSlotPaint.setColor(0x14FFFFFF);
        gridPaint.setColor(0x14FFFFFF);
        gridPaint.setStrokeWidth(1f);

        erasePreviewPaint.setStyle(Paint.Style.STROKE);
        eraseHaloPaint.setStyle(Paint.Style.STROKE);

        lassoPaint.setStyle(Paint.Style.STROKE);
        lassoPaint.setStrokeWidth(2f);
        lassoPaint.setPathEffect(new DashPathEffect(new float[]{14f, 10f}, 0f));

        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(2f);
        selectionPaint.setPathEffect(new DashPathEffect(new float[]{16f, 12f}, 0f));

        selectionFillPaint.setStyle(Paint.Style.STROKE);
        selectionFillPaint.setStrokeWidth(6f);

        gimbalFillPaint.setStyle(Paint.Style.FILL);
        gimbalStrokePaint.setStyle(Paint.Style.STROKE);
        gimbalStrokePaint.setStrokeWidth(2f);

        applyCodeStyle(ThemeConfig.CODE_STYLES[0]);
        applyAppTheme(ThemeConfig.APP_THEMES[0]);

        document.setPageReadyListener(() -> post(this::onDocumentPageReady));

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (transformingSelection || movingSelection) return false;
                // Cancel any page-add charge — zoom must not create pages.
                if (overscrollSnapAnimator != null) {
                    overscrollSnapAnimator.cancel();
                    overscrollSnapAnimator = null;
                }
                overscrollSnapping = false;
                overscrollCharge = 0f;
                appendPageRequested = false;
                prependPageRequested = false;
                stopPanFling();
                pinchScaling = true;
                pinchStartScale = camScale;
                beginNavigation();
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                if (listener != null) listener.onNavigationChanged(true);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (transformingSelection || movingSelection) return false;
                float factor = detector.getScaleFactor();
                float fx = detector.getFocusX();
                float fy = detector.getFocusY();
                float scaleFx = documentFitsHorizontally() ? getWidth() * 0.5f : fx;
                // Pan with focus, then zoom about focus (ScrollView-style camera).
                panCameraBy(scaleFx - lastFocusX, fy - lastFocusY);
                float worldX = (scaleFx + camScrollX) / camScale;
                float worldY = (fy + camScrollY) / camScale;
                // Deeper than the old 8x: the page cache now renders past that, so
                // the extra zoom shows more detail rather than more blur.
                camScale = Math.max(0.15f, Math.min(14f, camScale * factor));
                if (pinchStartScale > 0.01f
                        && Math.abs(camScale / pinchStartScale - 1f) > 0.02f) {
                    pinchChangedScale = true;
                }
                camScrollX = worldX * camScale - scaleFx;
                camScrollY = worldY * camScale - fy;
                applyCamera();
                clampDocumentCamera();
                lastFocusX = documentFitsHorizontally() ? getWidth() * 0.5f : fx;
                lastFocusY = fy;
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                pinchScaling = false;
                if (!navigating && listener != null) listener.onNavigationChanged(false);
                if (!navigating) {
                    removeCallbacks(freezeNavigationRunnable);
                    endNavigation();
                }
            }
        });
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        ViewConfiguration vc = ViewConfiguration.get(context);
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        panScroller = new OverScroller(context);
        panScroller.setFriction(ViewConfiguration.getScrollFriction() * PAN_FLING_FRICTION);
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                // Long-press is handled via fingerLongPressRunnable so movement can cancel it.
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!document.isOpen()) {
                    if (emptyButtonHit(e.getX(), e.getY()) && listener != null) listener.onEmptyCreateRequested();
                    return true;
                }
                float[] w = screenToWorld(e.getX(), e.getY());
                float wx = w[0], wy = w[1];
                // Content blocks are chat-like rendered cards — tap selects/moves, no EditText.
                CanvasTextField sole = getSoleSelectedTextField();
                if (sole != null && sole.contains(wx, wy)) {
                    if (!sole.contentBlock && listener != null) {
                        listener.onTextFieldEditRequested(sole.id);
                    }
                    return true;
                }
                if (tapSelectAt(wx, wy)) return true;
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float[] w = screenToWorld(e.getX(), e.getY());
                // Double-tap a text field to edit without selecting first (not content blocks).
                for (int i = textFields.size() - 1; i >= 0; i--) {
                    CanvasTextField tf = textFields.get(i);
                    if (tf.contains(w[0], w[1])) {
                        selectOnlyTextField(tf);
                        if (!tf.contentBlock && listener != null) {
                            listener.onTextFieldEditRequested(tf.id);
                        }
                        return true;
                    }
                }
                return false;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);
        // Double-tap-drag "quick scale" feels like accidental zoom while paging.
        scaleDetector.setQuickScaleEnabled(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTool(Tool tool) {
        if (tool == null) tool = Tool.PENCIL;
        currentTool = tool;
        eraseMode = (tool == Tool.ERASER);
        activeStroke = null;
        eraseX = eraseY = Float.NaN;
        drawingLasso = false;
        drawingTextRect = false;
        lassoPoints.clear();
        if (tool != Tool.LASSO) {
            // keep selection until explicitly cleared / new lasso
        }
        invalidate();
    }

    public Tool getTool() {
        return currentTool;
    }

    public void setSelectInk(boolean v) {
        if (currentTool == Tool.ERASER) eraseSelectInk = v;
        else lassoSelectInk = v;
        selectInk = v;
    }

    public void setSelectImages(boolean v) {
        if (currentTool == Tool.ERASER) eraseSelectImages = v;
        else lassoSelectImages = v;
        selectImages = v;
    }

    /** What the lasso picks up: pen strokes, highlighter strokes, text boxes, images. */
    public boolean isLassoInk() {
        return lassoSelectInk;
    }

    public void setLassoInk(boolean v) {
        lassoSelectInk = v;
    }

    public boolean isLassoHighlighter() {
        return lassoSelectHighlighter;
    }

    public void setLassoHighlighter(boolean v) {
        lassoSelectHighlighter = v;
    }

    public boolean isLassoText() {
        return lassoSelectText;
    }

    public void setLassoText(boolean v) {
        lassoSelectText = v;
    }

    public boolean isLassoImages() {
        return lassoSelectImages;
    }

    public void setLassoImages(boolean v) {
        lassoSelectImages = v;
    }

    /** What the eraser takes: pen strokes, highlighter strokes, text boxes, images. */
    public boolean isEraseInk() {
        return eraseSelectInk;
    }

    public void setEraseInk(boolean v) {
        eraseSelectInk = v;
    }

    public boolean isEraseHighlighter() {
        return eraseSelectHighlighter;
    }

    public void setEraseHighlighter(boolean v) {
        eraseSelectHighlighter = v;
    }

    public boolean isEraseText() {
        return eraseSelectText;
    }

    public void setEraseText(boolean v) {
        eraseSelectText = v;
    }

    public boolean isEraseImages() {
        return eraseSelectImages;
    }

    public void setEraseImages(boolean v) {
        eraseSelectImages = v;
    }

    public float getEraseRadiusPx() {
        return eraseRadiusPx;
    }

    public void setEraseRadiusPx(float px) {
        eraseRadiusPx = Math.max(4f, Math.min(160f, px));
    }

    public boolean isSelectInk() {
        return currentTool == Tool.ERASER ? eraseSelectInk : lassoSelectInk;
    }

    public boolean isSelectImages() {
        return currentTool == Tool.ERASER ? eraseSelectImages : lassoSelectImages;
    }

    public void requestImagePasteArm() {
        imagePasteArmed = true;
    }

    public boolean isImagePasteArmed() {
        return imagePasteArmed;
    }

    public void clearImagePasteArm() {
        imagePasteArmed = false;
    }

    /**
     * Items the backdrop does not show yet, drawn live on top until a redraw that
     * includes them lands: ones just deselected while the backdrop still leaves them
     * out (it omits the selection, which is drawn live), and strokes finished while
     * the backdrop was waiting for a redraw. They are drawn live on top until a redraw that
     * includes them lands, so ending a selection needs no blocking redraw — and the
     * pen can start a stroke at once without the items vanishing under it.
     */
    private final List<Stroke> deselectedStrokes = new ArrayList<>();
    private final List<CanvasImage> deselectedImages = new ArrayList<>();
    private final List<CanvasTextField> deselectedTextFields = new ArrayList<>();

    private boolean hasDeselectedPending() {
        return !deselectedStrokes.isEmpty() || !deselectedImages.isEmpty()
                || !deselectedTextFields.isEmpty();
    }

    private void clearDeselectedPending() {
        deselectedStrokes.clear();
        deselectedImages.clear();
        deselectedTextFields.clear();
    }

    /** Draws {@link #deselectedStrokes} &c. in screen space over the blitted backdrop. */
    private void drawDeselectedPending(Canvas canvas) {
        if (!hasDeselectedPending()) return;
        canvas.save();
        canvas.concat(viewMatrix);
        // Undo or the eraser may have removed one since; only draw what still exists.
        for (CanvasTextField tf : deselectedTextFields) {
            if (textFields.contains(tf)) tf.draw(canvas, false);
        }
        for (Stroke st : deselectedStrokes) {
            if (inkStrokes.contains(st)) drawStroke(canvas, st);
        }
        for (CanvasImage img : deselectedImages) {
            if (!img.isLive() && images.contains(img)) img.draw(canvas, imagePaint);
        }
        canvas.restore();
    }

    public void clearSelection() {
        boolean hadOverlay = selOverlayActive || hasSelection();
        if (sceneBackdropReady && sceneBackdropOmitsSelection && hasSelection()) {
            deselectedStrokes.addAll(selectedStrokes);
            deselectedImages.addAll(selectedImages);
            deselectedTextFields.addAll(selectedTextFields);
        }
        selectedStrokes.clear();
        selectedImages.clear();
        selectedTextFields.clear();
        clearLassoRegion();
        movingSelection = false;
        transformingSelection = false;
        selGesture = SelGesture.NONE;
        selOverlayActive = false;
        selFrameValid = false;
        releaseSelDragBitmap();
        if (hadOverlay) markSceneDirty();
        invalidate();
        notifySelectionChanged();
    }

    public void clearLassoRegion() {
        hasLassoRegion = false;
        lassoRegionBounds.setEmpty();
        if (listener != null) listener.onLassoRegionChanged(false);
    }

    public boolean hasLassoRegion() {
        return hasLassoRegion && !lassoRegionBounds.isEmpty();
    }

    /** Bounds for screenshot: selection union, else lasso region. */
    public RectF getCaptureBoundsWorld() {
        RectF r = selectionBoundsWorld();
        if (r != null) return r;
        if (hasLassoRegion()) return new RectF(lassoRegionBounds);
        return null;
    }

    /** Render current view crop of world bounds to PNG bytes. */
    public byte[] captureRegionPng(RectF worldBounds, int maxPx) {
        if (worldBounds == null || worldBounds.isEmpty()) return null;
        ensureInverse();
        float[] tl = {worldBounds.left, worldBounds.top};
        float[] br = {worldBounds.right, worldBounds.bottom};
        viewMatrix.mapPoints(tl);
        viewMatrix.mapPoints(br);
        int x = Math.round(Math.min(tl[0], br[0]));
        int y = Math.round(Math.min(tl[1], br[1]));
        int w = Math.round(Math.abs(br[0] - tl[0]));
        int h = Math.round(Math.abs(br[1] - tl[1]));
        if (w < 2 || h < 2) return null;
        float scale = 1f;
        if (Math.max(w, h) > maxPx) scale = maxPx / (float) Math.max(w, h);
        int bw = Math.max(2, Math.round(w * scale));
        int bh = Math.max(2, Math.round(h * scale));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(scale, scale);
        c.translate(-x, -y);
        draw(c);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 92, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    public boolean hasDocument() {
        return document.isOpen();
    }

    private long documentBytesHash;

    /** CRC of the PDF bytes currently open — lets callers skip no-op reloads. */
    public long getDocumentBytesHash() {
        return document.isOpen() ? documentBytesHash : 0L;
    }

    static long hashBytes(byte[] bytes) {
        if (bytes == null) return 0L;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        return (crc.getValue() << 20) ^ bytes.length;
    }

    /** A stroke or erase is under the pen right now. */
    public boolean isInkInProgress() {
        return activeStroke != null || !Float.isNaN(eraseX);
    }

    public String getDocumentPath() {
        return document.path;
    }

    public int getDocumentPageCount() {
        return document.pageCount;
    }

    public boolean isBlankOwnedDocument() {
        return document.blankOwned;
    }

    /**
     * Bind a PDF substrate. Clears prior document renderer; annotation lists are
     * left to the caller (import or clear).
     */
    public void openDocument(android.content.Context ctx, String path, byte[] pdfBytes,
                             int annotationPageCount, boolean blankOwned) throws Exception {
        openDocument(ctx, path, pdfBytes, annotationPageCount, blankOwned, 0);
    }

    public void openDocument(android.content.Context ctx, String path, byte[] pdfBytes,
                             int annotationPageCount, boolean blankOwned, int blankPrefix)
            throws Exception {
        // Keep the camera when this is the document we just restored a camera for, not
        // only when it is already open. Switching documents (and every app start) used
        // to land here with the old path still in `document`, so the matrix restored a
        // moment earlier by importCanvasState was thrown away by fitDocumentInView.
        boolean keepView = path != null
                && (document.isOpen() && path.equals(document.path) || path.equals(restoredViewPath));
        restoredViewPath = "";
        float[] matrixBackup = null;
        if (keepView) {
            matrixBackup = new float[9];
            viewMatrix.getValues(matrixBackup);
        }
        document.open(ctx, path, pdfBytes, annotationPageCount, blankOwned, blankPrefix);
        documentBytesHash = hashBytes(pdfBytes);
        searchHits.clear();
        document.applyTheme(chromeLight);
        appendPageRequested = false;
        prependPageRequested = false;
        overscrollCharge = 0f;
        // Force ARGB_8888 backdrop for PDF text (may still be RGB_565 from empty canvas).
        if (sceneBackdrop != null && !sceneBackdrop.isRecycled()
                && sceneBackdrop.getConfig() != Bitmap.Config.ARGB_8888) {
            recycleBackdropBitmap(sceneBackdrop);
            sceneBackdrop = null;
            releaseBackdropHw();
            sceneBackdropReady = false;
            releaseSceneBackdropBack();
        }
        if (keepView && matrixBackup != null) {
            viewMatrix.setValues(matrixBackup);
            syncCameraFromMatrix();
            markMatrixDirty();
        } else {
            fitDocumentInView();
        }
        markSceneDirty();
        invalidate();
    }

    /** The PDF's own page behind document page {@code index}, or −1 for an app-only page. */
    public int documentFilePageFor(int index) {
        return document.filePageFor(index);
    }

    /** Page size in PDF points, as the pages are shown: {width, height}. */
    public float[] documentPageSize() {
        return new float[] {document.pageWidth, document.pageHeight};
    }

    /** Brings document page {@code index} to the top of the view. */
    public void revealDocumentPage(int index) {
        if (!document.isOpen() || index < 0 || index >= document.pageCount) return;
        RectF page = document.pageBounds(index);
        stopPanFling();
        camScrollY = page.top * camScale - documentRestPadPx();
        applyCamera();
        clampDocumentCamera();
        markMatrixDirty();
        markSceneDirty();
        invalidate();
    }

    /**
     * Centres page {@code index} vertically, so the "current page" (the page under the
     * middle of the view, which the presentation shows) is exactly that page.
     */
    public void centerDocumentPage(int index) {
        if (!document.isOpen() || index < 0 || index >= document.pageCount) return;
        RectF page = document.pageBounds(index);
        stopPanFling();
        camScrollY = page.centerY() * camScale - getHeight() / 2f;
        applyCamera();
        clampDocumentCamera();
        markMatrixDirty();
        markSceneDirty();
        invalidate();
    }

    public int getDocumentBlankPrefix() {
        return document.blankPrefix;
    }

    public void closeDocument() {
        searchHits.clear();
        document.close();
        document.resetLooks();
        appendPageRequested = false;
        prependPageRequested = false;
        overscrollCharge = 0f;
        inkStrokes.clear();
        images.clear();
        textFields.clear();
        undoStack.clear();
        redoStack.clear();
        historyChanged();
        clearSelection();
        activeStroke = null;
        viewMatrix.reset();
        markMatrixDirty();
        markSceneDirty();
        invalidate();
    }

    /** Grow page stack by one (after host rewrote blank PDF or for virtual pages). */
    public void appendDocumentPage() {
        if (!document.isOpen()) return;
        document.appendVirtualPage();
        appendPageRequested = false;
        overscrollCharge = 0f;
        markSceneDirty();
        invalidate();
        notifyContentChanged();
    }

    /**
     * Insert a blank page above the first. Shifts annotations and the camera so
     * existing content stays visually fixed.
     */
    public void prependDocumentPage() {
        if (!document.isOpen()) return;
        float stride = document.pageHeight + DocumentPages.PAGE_GAP;
        shiftDocumentContent(0f, stride);
        // Keep the viewport visually fixed: content moved down by stride.
        camScrollY += stride * camScale;
        applyCamera();
        document.prependVirtualPage();
        prependPageRequested = false;
        overscrollCharge = 0f;
        markSceneDirty();
        invalidate();
        notifyContentChanged();
    }

    /** Page index under a world Y (clamped). */
    public int documentPageIndexAt(float worldY) {
        if (!document.isOpen()) return -1;
        return document.pageIndexAt(worldY);
    }

    public boolean canDeleteDocumentPage(int index) {
        if (!document.isOpen() || document.pageCount <= 1) return false;
        if (index < 0 || index >= document.pageCount) return false;
        if (document.blankOwned) return true;
        // Imported PDFs: only virtual prefix/suffix blanks.
        return index < document.blankPrefix
                || index >= document.blankPrefix + document.filePageCount;
    }

    /**
     * Delete the page at {@code index}: drop annotations on it, shift later content up,
     * and shrink the page stack. Host should rewrite blank-owned PDFs afterward.
     */
    public boolean deleteDocumentPage(int index) {
        if (!canDeleteDocumentPage(index)) return false;
        float stride = document.pageHeight + DocumentPages.PAGE_GAP;
        float pageTop = index * stride;
        float pageBottom = pageTop + document.pageHeight;
        float gap = DocumentPages.PAGE_GAP;
        removeContentInYRange(pageTop - gap * 0.5f, pageBottom + gap * 0.5f);
        shiftContentBelowY(pageBottom + gap * 0.5f, -stride);
        if (camScrollY > pageTop * camScale) {
            camScrollY = Math.max(0f, camScrollY - stride * camScale);
            applyCamera();
        }
        document.removePageAt(index);
        clearSelection();
        markSceneDirty();
        invalidate();
        notifyContentChanged();
        // The document got shorter: if the view now sits past its end, spring back —
        // left there, the next small scroll read as a pull and added a page.
        float[] range = new float[4];
        scrollRange(range);
        if (camScrollY > range[3] + 0.5f || camScrollY < range[2] - 0.5f) {
            overscrollAtTop = camScrollY < range[2];
            overscrollCharge = 0f;
            post(this::startOverscrollSnapBack);
        }
        return true;
    }

    private void removeContentInYRange(float y0, float y1) {
        float top = Math.min(y0, y1);
        float bottom = Math.max(y0, y1);
        for (int i = inkStrokes.size() - 1; i >= 0; i--) {
            Stroke s = inkStrokes.get(i);
            if (s.bounds.isEmpty()) s.recomputeBounds();
            if (!s.bounds.isEmpty() && s.bounds.bottom >= top && s.bounds.top <= bottom) {
                inkStrokes.remove(i);
            }
        }
        for (int i = images.size() - 1; i >= 0; i--) {
            CanvasImage img = images.get(i);
            if (img.cy >= top && img.cy <= bottom) images.remove(i);
        }
        for (int i = textFields.size() - 1; i >= 0; i--) {
            CanvasTextField tf = textFields.get(i);
            if (tf.cy >= top && tf.cy <= bottom) textFields.remove(i);
        }
        undoStack.clear();
        redoStack.clear();
        historyChanged();
    }

    /**
     * Rearranges the annotations to a new page order. {@code order[j]} is the old
     * page that becomes page j, or −1 for a new blank page; an old page listed twice
     * is duplicated, one left out is deleted with everything on it. Items belong to
     * the page their middle is on. The PDF itself is the host's to rewrite and
     * reopen; this moves only what the app draws. Undo history is cleared, as for
     * any other page change.
     */
    public void applyPageOrder(int[] order) {
        if (!document.isOpen() || order == null || order.length == 0) return;
        final float stride = document.pageHeight + DocumentPages.PAGE_GAP;
        final int oldCount = document.pageCount;
        List<List<Stroke>> strokesBy = new ArrayList<>();
        List<List<CanvasImage>> imagesBy = new ArrayList<>();
        List<List<CanvasTextField>> textsBy = new ArrayList<>();
        for (int i = 0; i < oldCount; i++) {
            strokesBy.add(new ArrayList<>());
            imagesBy.add(new ArrayList<>());
            textsBy.add(new ArrayList<>());
        }
        for (Stroke st : inkStrokes) {
            if (st.bounds.isEmpty()) st.recomputeBounds();
            strokesBy.get(document.pageIndexAt(st.bounds.centerY())).add(st);
        }
        for (CanvasImage img : images) imagesBy.get(document.pageIndexAt(img.cy)).add(img);
        for (CanvasTextField tf : textFields) textsBy.get(document.pageIndexAt(tf.cy)).add(tf);

        // Where each old page lands first; later listings are copies.
        int[] firstAt = new int[oldCount];
        java.util.Arrays.fill(firstAt, -1);
        for (int j = 0; j < order.length; j++) {
            int i = order[j];
            if (i >= 0 && i < oldCount && firstAt[i] < 0) firstAt[i] = j;
        }
        List<Stroke> nextStrokes = new ArrayList<>();
        List<CanvasImage> nextImages = new ArrayList<>();
        List<CanvasTextField> nextTexts = new ArrayList<>();
        // Copies first, from the originals where they still are.
        for (int j = 0; j < order.length; j++) {
            int i = order[j];
            if (i < 0 || i >= oldCount || firstAt[i] == j) continue;
            float dy = (j - i) * stride;
            for (Stroke st : strokesBy.get(i)) {
                Stroke n = st.duplicate();
                n.translate(0f, dy);
                nextStrokes.add(n);
            }
            for (CanvasImage img : imagesBy.get(i)) {
                if (img.bitmap == null || img.bitmap.isRecycled()) continue;
                CanvasImage dup = new CanvasImage(img.bitmap, img.cx, img.cy + dy,
                        img.width, img.height);
                dup.presentHidden = img.presentHidden;
                dup.rotationDeg = img.rotationDeg;
                nextImages.add(dup);
            }
            for (CanvasTextField tf : textsBy.get(i)) {
                nextTexts.add(tf.duplicate(0f, dy));
            }
        }
        // Then the originals move to their first place; pages left out drop theirs.
        for (int i = 0; i < oldCount; i++) {
            if (firstAt[i] < 0) continue;
            float dy = (firstAt[i] - i) * stride;
            for (Stroke st : strokesBy.get(i)) {
                st.translate(0f, dy);
                nextStrokes.add(st);
            }
            for (CanvasImage img : imagesBy.get(i)) {
                img.setCenter(img.cx, img.cy + dy);
                nextImages.add(img);
            }
            for (CanvasTextField tf : textsBy.get(i)) {
                tf.cy += dy;
                nextTexts.add(tf);
            }
        }
        inkStrokes.clear();
        inkStrokes.addAll(nextStrokes);
        images.clear();
        images.addAll(nextImages);
        textFields.clear();
        textFields.addAll(nextTexts);
        document.remapPageLooks(order);
        undoStack.clear();
        redoStack.clear();
        historyChanged();
        clearSelection();
        markSceneDirty();
        invalidate();
        notifyContentChanged();
    }

    private void shiftContentBelowY(float yThreshold, float dy) {
        if (dy == 0f) return;
        for (Stroke s : inkStrokes) {
            if (s.bounds.isEmpty()) s.recomputeBounds();
            if (!s.bounds.isEmpty() && s.bounds.top >= yThreshold) s.translate(0f, dy);
        }
        for (CanvasImage img : images) {
            if (img.cy >= yThreshold) img.setCenter(img.cx, img.cy + dy);
        }
        for (CanvasTextField tf : textFields) {
            if (tf.cy >= yThreshold) tf.cy += dy;
        }
        undoStack.clear();
        redoStack.clear();
        historyChanged();
    }

    private void shiftDocumentContent(float dx, float dy) {
        if (dx == 0f && dy == 0f) return;
        for (Stroke s : inkStrokes) s.translate(dx, dy);
        for (CanvasImage img : images) img.setCenter(img.cx + dx, img.cy + dy);
        for (CanvasTextField tf : textFields) {
            tf.cx += dx;
            tf.cy += dy;
        }
        undoStack.clear();
        redoStack.clear();
        historyChanged();
    }

    public void setDocumentPageCount(int n) {
        document.setPageCount(n);
        markSceneDirty();
        invalidate();
    }

    private void fitDocumentInView() {
        if (!document.isOpen()) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float margin = documentRestPadPx();
        float scale = (w - 2f * margin) / document.pageWidth;
        camScale = Math.max(0.15f, Math.min(3f, scale));
        float contentW = document.pageWidth * camScale;
        camScrollX = -(w - contentW) * 0.5f;
        camScrollY = -margin;
        applyCamera();
    }

    /** Rebuild viewMatrix from ScrollView-style camera (scale + scroll). */
    private void applyCamera() {
        viewMatrix.reset();
        viewMatrix.postScale(camScale, camScale);
        viewMatrix.postTranslate(-camScrollX, -camScrollY);
        inverseDirty = true;
        viewMatrix.getValues(matrixValues);
        float sx = matrixValues[Matrix.MSCALE_X];
        float shy = matrixValues[Matrix.MSKEW_Y];
        float scale = (float) Math.hypot(sx, shy);
        cachedViewScale = scale < 0.01f ? 0.01f : scale;
        fireSceneChanged(false);
        if (navSnapshotActive) {
            trackNavVelocity(matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y]);
            updateNavSnapshotTransform();
            maybeSlideNavSnapshot();
        }
        // Overlays live in the host's coordinate space, so they have to follow every
        // camera change, not just the end of a gesture.
        notifySelectionLayout();
    }

    /** Pull camScale/camScroll* from the current viewMatrix (after restore/import). */
    private void syncCameraFromMatrix() {
        viewMatrix.getValues(matrixValues);
        float sx = matrixValues[Matrix.MSCALE_X];
        float shy = matrixValues[Matrix.MSKEW_Y];
        camScale = (float) Math.hypot(sx, shy);
        if (camScale < 0.01f) camScale = 0.01f;
        // screen = world * scale + trans  =>  scroll = -trans
        camScrollX = -matrixValues[Matrix.MTRANS_X];
        camScrollY = -matrixValues[Matrix.MTRANS_Y];
        cachedViewScale = camScale;
        inverseDirty = true;
    }

    private void panCameraBy(float dxScreen, float dyScreen) {
        if (documentFitsHorizontally()) dxScreen = 0f;
        camScrollX -= dxScreen;
        if (document.isOpen() && !pinchScaling && !panFlinging && !overscrollSnapping) {
            scrollRange(panScrollRangeTmp);
            float minY = panScrollRangeTmp[2];
            float maxY = panScrollRangeTmp[3];
            camScrollY = resistScrollY(camScrollY, dyScreen, minY, maxY);
        } else {
            camScrollY -= dyScreen;
        }
        applyCamera();
    }

    /**
     * Apply finger dy to scroll Y with progressive resistance past the rest edges.
     * Slack past rest is free; resistance grows only in the charge zone.
     */
    private float resistScrollY(float scrollY, float dyScreen, float minY, float maxY) {
        float delta = -dyScreen;
        float next = scrollY + delta;
        float slack = overscrollSlackPx();
        float maxPast = slack + OVERSCROLL_MAX_PX;
        if (next > maxY || scrollY > maxY) {
            if (delta <= 0f) return scrollY + delta;
            if (scrollY <= maxY) {
                float past = next - maxY;
                return maxY + past; // entering slack at full speed
            }
            float over = scrollY - maxY;
            float resist = over <= slack ? 1f : overscrollPullResistance(over - slack);
            return Math.min(maxY + maxPast, scrollY + delta * resist);
        }
        if (next < minY || scrollY < minY) {
            if (delta >= 0f) return scrollY + delta;
            if (scrollY >= minY) {
                float past = minY - next;
                return minY - past;
            }
            float over = minY - scrollY;
            float resist = over <= slack ? 1f : overscrollPullResistance(over - slack);
            return Math.max(minY - maxPast, scrollY + delta * resist);
        }
        return next;
    }

    /** 1 at the charge-zone start → ~0.25 at the rubber-band cap (quadratic). */
    private static float overscrollPullResistance(float overPxInChargeZone) {
        float t = Math.max(0f, Math.min(1f, overPxInChargeZone / OVERSCROLL_MAX_PX));
        return 1f - 0.75f * t * t;
    }

    private float documentRestPadPx() {
        return dp(DOCUMENT_REST_PAD_DP);
    }

    private float overscrollSlackPx() {
        return dp(OVERSCROLL_SLACK_DP);
    }

    /** Clamp pan and charge the overscroll “add page” ring past the first/last page. */
    private void clampDocumentCamera() {
        if (!document.isOpen() || overscrollSnapping) return;
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        float[] range = new float[4];
        scrollRange(range);
        float minX = range[0], maxX = range[1], minY = range[2], maxY = range[3];

        // Pinch / fling: hard clamp to rest range — never rubber-band or add pages.
        if (pinchScaling || panFlinging) {
            boolean hit = false;
            if (camScrollX < minX) { camScrollX = minX; hit = true; }
            if (camScrollX > maxX) { camScrollX = maxX; hit = true; }
            if (camScrollY < minY) { camScrollY = minY; hit = true; }
            if (camScrollY > maxY) { camScrollY = maxY; hit = true; }
            if (hit) {
                applyCamera();
                if (panFlinging) panScroller.forceFinished(true);
            } else if (documentFitsHorizontally() && Math.abs(camScrollX - minX) > 0.5f) {
                camScrollX = minX;
                applyCamera();
            }
            if (overscrollCharge > 0f) {
                overscrollCharge = 0f;
                if (!panFlinging) invalidate();
            }
            return;
        }

        // Horizontal lock / clamp.
        if (camScrollX < minX) camScrollX = minX;
        if (camScrollX > maxX) camScrollX = maxX;

        float rawPastTop = minY - camScrollY;
        float rawPastBottom = camScrollY - maxY;
        float slack = overscrollSlackPx();
        float maxPast = slack + OVERSCROLL_MAX_PX;

        if ((rawPastBottom > 0.5f || rawPastTop > 0.5f) && (navigating || navSnapshotActive)) {
            boolean atTop = rawPastTop > rawPastBottom;
            float rawPast = atTop ? rawPastTop : rawPastBottom;
            if (rawPast > maxPast) {
                rawPast = maxPast;
                camScrollY = atTop ? minY - rawPast : maxY + rawPast;
                applyCamera();
            }
            overscrollAtTop = atTop;
            float chargeable = Math.max(0f, rawPast - slack);
            overscrollCharge = Math.min(1f, chargeable / OVERSCROLL_CHARGE_PX);
            // Only show readiness while dragging; the page is added on release (see
            // finishOverscrollOnRelease). Pulling out and back in one motion adds nothing.
            boolean ready = overscrollCharge >= OVERSCROLL_READY;
            if (ready && !overscrollReadyFelt) performHapticFeedback(
                    android.view.HapticFeedbackConstants.CLOCK_TICK);
            overscrollReadyFelt = ready;
            invalidate();
        } else if (!navigating && !navSnapshotActive && !panFlinging
                && !appendPageRequested && !prependPageRequested
                && (rawPastBottom > 0.5f || rawPastTop > 0.5f)) {
            startOverscrollSnapBack();
        } else {
            // Never hard-jump while a snap should run — only clamp when already at rest.
            if (camScrollY < minY - 0.5f || camScrollY > maxY + 0.5f) {
                if (!appendPageRequested && !prependPageRequested) {
                    startOverscrollSnapBack();
                    return;
                }
            }
            if (camScrollY < minY) camScrollY = minY;
            if (camScrollY > maxY) camScrollY = maxY;
            applyCamera();
            if (overscrollCharge > 0f && rawPastBottom <= 0.5f && rawPastTop <= 0.5f) {
                overscrollCharge = 0f;
                invalidate();
            }
        }
    }

    /** Fire prepend/append once the ring is full during a finger drag. */
    private void maybeCommitOverscrollPage() {
        if (overscrollCharge < 1f) return;
        if (overscrollAtTop) {
            if (prependPageRequested) return;
            overscrollCharge = 0f;
            prependPageRequested = true;
            if (listener != null) listener.onRequestPrependPage();
        } else {
            if (appendPageRequested) return;
            overscrollCharge = 0f;
            appendPageRequested = true;
            if (listener != null) listener.onRequestAppendPage();
        }
    }

    /**
     * On finger-up: commit if the ring was nearly full, otherwise snap back.
     * @return true if a page was requested
     */
    private boolean finishOverscrollOnRelease() {
        if (!document.isOpen()) return false;
        if (appendPageRequested || prependPageRequested) return false;
        overscrollReadyFelt = false;
        // Only when the indicator said "ready" at the moment of release.
        if (overscrollCharge < OVERSCROLL_READY) return false;
        overscrollCharge = 1f;
        maybeCommitOverscrollPage();
        return appendPageRequested || prependPageRequested;
    }

    private float contentWidthPx() {
        return document.isOpen() ? document.pageWidth * camScale : 0f;
    }

    private float contentHeightPx() {
        return document.isOpen() ? document.documentBottom() * camScale : 0f;
    }

    /** Rest scroll range — fling is hard-clamped here (no add-page overscroll). */
    private void scrollRange(float[] outMinMax /* minX,maxX,minY,maxY */) {
        int vw = getWidth();
        int vh = getHeight();
        float contentW = contentWidthPx();
        float contentH = contentHeightPx();
        float restTop = documentRestPadPx();
        float restBottom = vh - documentRestPadPx();
        if (contentW <= vw + 0.5f) {
            float cx = -(vw - contentW) * 0.5f;
            outMinMax[0] = cx;
            outMinMax[1] = cx;
        } else {
            outMinMax[0] = 0f;
            outMinMax[1] = contentW - vw;
        }
        float minY = -restTop;
        float maxY = contentH - restBottom;
        if (maxY < minY) maxY = minY;
        outMinMax[2] = minY;
        outMinMax[3] = maxY;
    }

    /** True when the page is not wider than the viewport (horizontal pan disabled). */
    /** True when the page fits the canvas width, so it is centred rather than scrolled. */
    public boolean documentFitsWidth() {
        return documentFitsHorizontally();
    }

    private boolean documentFitsHorizontally() {
        if (!document.isOpen()) return true;
        int vw = getWidth();
        if (vw <= 0) return true;
        return contentWidthPx() <= vw + 0.5f;
    }

    /** Animate the document back to its resting edge and clear the ring. */
    private void startOverscrollSnapBack() {
        if (!document.isOpen() || overscrollSnapping || pinchScaling || panFlinging) return;
        float[] range = new float[4];
        scrollRange(range);
        float minY = range[2], maxY = range[3];
        float pastTop = minY - camScrollY;
        float pastBottom = camScrollY - maxY;
        boolean atTop = overscrollAtTop || pastTop > pastBottom;
        float need = atTop ? pastTop : pastBottom;
        if (need <= 0.5f && overscrollCharge <= 0.01f) {
            overscrollCharge = 0f;
            camScrollY = atTop ? minY : maxY;
            applyCamera();
            return;
        }
        if (overscrollSnapAnimator != null) {
            overscrollSnapAnimator.cancel();
            overscrollSnapAnimator = null;
        }
        overscrollSnapping = true;
        overscrollAtTop = atTop;
        // Keep / create freeze — do not full-redraw (that hitches).
        if (!navSnapshotActive) {
            beginNavigation();
        }
        final float startCharge = overscrollCharge;
        final float startScroll = camScrollY;
        final float endScroll = atTop ? minY : maxY;
        float dist = Math.abs(endScroll - startScroll);
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        overscrollSnapAnimator = anim;
        // Longer travel → slightly longer settle; keep it soft, not snappy.
        long duration = (long) Math.max(300, Math.min(520, 260 + dist * 0.65f));
        anim.setDuration(duration);
        anim.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 0.8f, 0.2f, 1f));
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            // Ease charge down a bit faster than position so the ring clears early.
            float chargeT = Math.min(1f, t * 1.25f);
            camScrollY = startScroll + (endScroll - startScroll) * t;
            applyCamera();
            overscrollCharge = startCharge * (1f - chargeT);
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                overscrollSnapping = false;
                overscrollCharge = 0f;
                overscrollSnapAnimator = null;
                appendPageRequested = false;
                prependPageRequested = false;
                navSnapshotActive = false;
                camScrollY = endScroll;
                applyCamera();
                sceneBackdropDirty = true;
                post(warmBackdropRunnable);
                invalidate();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                overscrollSnapping = false;
                overscrollSnapAnimator = null;
            }
        });
        anim.start();
    }

    private void cancelOverscrollSnap() {
        if (overscrollSnapAnimator != null) {
            overscrollSnapAnimator.cancel();
            overscrollSnapAnimator = null;
        }
        overscrollSnapping = false;
    }

    /**
     * Screen-space ring that fills while overscrolling past the first/last page.
     * Anchored just above the first page or just below the last page.
     */
    private void drawOverscrollCharge(Canvas canvas, int w, int h) {
        if (overscrollCharge <= 0.01f || !document.isOpen()) return;
        overscrollEdgePts[0] = document.pageWidth * 0.5f;
        overscrollEdgePts[1] = overscrollAtTop ? 0f : document.documentBottom();
        viewMatrix.mapPoints(overscrollEdgePts);
        float cx = overscrollEdgePts[0];
        float gap = dp(40);
        float cy = overscrollAtTop
                ? overscrollEdgePts[1] - gap
                : overscrollEdgePts[1] + gap;
        cy = Math.max(dp(24), Math.min(h - dp(24), cy));
        cx = Math.max(dp(24), Math.min(w - dp(24), cx));
        float r = dp(20);

        boolean ready = overscrollCharge >= OVERSCROLL_READY;
        if (ready) {
            // Ready: a filled disc says "let go now to add a page".
            r *= 1.12f;
            overscrollRingPaint.setStyle(Paint.Style.FILL);
            overscrollRingPaint.setColor(chromeAccent);
            canvas.drawCircle(cx, cy, r, overscrollRingPaint);
            overscrollPlusPaint.setStyle(Paint.Style.FILL);
            overscrollPlusPaint.setColor(0xFFFFFFFF);
            float arm = r * 0.42f;
            float thick = dp(2.8f);
            canvas.drawRoundRect(cx - arm, cy - thick * 0.5f, cx + arm, cy + thick * 0.5f,
                    thick, thick, overscrollPlusPaint);
            canvas.drawRoundRect(cx - thick * 0.5f, cy - arm, cx + thick * 0.5f, cy + arm,
                    thick, thick, overscrollPlusPaint);
            return;
        }
        overscrollRingPaint.setStyle(Paint.Style.STROKE);
        overscrollRingPaint.setStrokeWidth(dp(3));
        overscrollRingPaint.setColor((chromeAccent & 0x00FFFFFF) | 0x55000000);
        canvas.drawCircle(cx, cy, r, overscrollRingPaint);

        overscrollFillPaint.setStyle(Paint.Style.STROKE);
        overscrollFillPaint.setStrokeWidth(dp(3.5f));
        overscrollFillPaint.setStrokeCap(Paint.Cap.ROUND);
        overscrollFillPaint.setColor(chromeAccent);
        overscrollArcRect.set(cx - r, cy - r, cx + r, cy + r);
        float sweep = 360f * Math.min(1f, overscrollCharge);
        canvas.drawArc(overscrollArcRect, -90f, sweep, false, overscrollFillPaint);

        int plusAlpha = Math.round(90f + 165f * Math.min(1f, overscrollCharge));
        overscrollPlusPaint.setStyle(Paint.Style.FILL);
        overscrollPlusPaint.setColor((chromeAccent & 0x00FFFFFF) | (plusAlpha << 24));
        float arm = r * 0.38f;
        float thick = dp(2.4f);
        canvas.drawRoundRect(cx - arm, cy - thick * 0.5f, cx + arm, cy + thick * 0.5f,
                thick, thick, overscrollPlusPaint);
        canvas.drawRoundRect(cx - thick * 0.5f, cy - arm, cx + thick * 0.5f, cy + arm,
                thick, thick, overscrollPlusPaint);
    }

    private float documentPastRestBottom() {
        if (!document.isOpen()) return 0f;
        float[] range = new float[4];
        scrollRange(range);
        return Math.max(0f, camScrollY - range[3]);
    }

    private float documentPastRestTop() {
        if (!document.isOpen()) return 0f;
        float[] range = new float[4];
        scrollRange(range);
        return Math.max(0f, range[2] - camScrollY);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void addTextField(float worldX, float worldY) {
        recordUndoPoint();
        CanvasTextField tf = new CanvasTextField(worldX, worldY);
        textFields.add(tf);
        clearSelection();
        selectedTextFields.add(tf);
        selOverlayActive = false;
        recomputeSelectionFrame();
        markSceneDirty();
        notifySelectionChanged();
        invalidate();
    }

    public void addTextFieldRect(float left, float top, float width, float height) {
        if (width < 12f || height < 12f) return;
        recordUndoPoint();
        float cx = left + width * 0.5f;
        float cy = top + height * 0.5f;
        CanvasTextField tf = new CanvasTextField(cx, cy);
        tf.width = Math.max(80f, width);
        tf.height = Math.max(56f, height);
        tf.userSized = true;
        tf.invalidateLayout();
        textFields.add(tf);
        clearSelection();
        selectedTextFields.add(tf);
        selOverlayActive = false;
        recomputeSelectionFrame();
        markSceneDirty();
        notifySelectionChanged();
        invalidate();
    }

    /** Place a text field with initial content (e.g. chat drag-drop). */
    public CanvasTextField addTextFieldWithText(String text, float worldX, float worldY) {
        return addPlainTextField(text, worldX, worldY);
    }

    /** Widest a dropped text box starts; longer lines wrap. */
    static final float DROPPED_TEXT_MAX_W = 560f;

    /**
     * Dropped text as an ordinary text box — exactly like text typed on the canvas, no
     * card behind it — with its top-left corner at the drop point and as wide as its
     * longest line allows.
     */
    public CanvasTextField addPlainTextField(String text, float worldX, float worldY) {
        recordUndoPoint();
        CanvasTextField tf = new CanvasTextField(worldX, worldY);
        // Just text: dropped chat text is never rendered as LaTeX/markdown.
        tf.plainOnly = true;
        String t = text == null ? "" : text;
        android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(tf.textSize);
        tp.setTypeface(Typeface.SANS_SERIF);
        float widest = 0f;
        for (String line : t.split("\n", -1)) {
            widest = Math.max(widest, android.text.Layout.getDesiredWidth(line, tp));
        }
        tf.width = Math.max(60f, Math.min(DROPPED_TEXT_MAX_W, (float) Math.ceil(widest) + 4f));
        tf.setText(t);
        tf.cx = worldX + tf.width * 0.5f;
        tf.cy = worldY + tf.height * 0.5f;
        textFields.add(tf);
        clearSelection();
        selectedTextFields.add(tf);
        selOverlayActive = false;
        recomputeSelectionFrame();
        markSceneDirty();
        notifySelectionChanged();
        invalidate();
        maybeRequestLatexRender(tf);
        return tf;
    }

    /**
     * Chat-like content block: markdown/LaTeX rendered to a bitmap (same pipeline as chat).
     * Not an inline free-flow EditText.
     */
    public CanvasTextField addContentBlock(String text, String id, float worldX, float worldY, float width) {
        recordUndoPoint();
        CanvasTextField tf = new CanvasTextField(worldX, worldY);
        if (id != null && !id.isEmpty()) tf.id = id;
        tf.contentBlock = true;
        tf.width = width > 40f ? width : 480f;
        if (text != null && !text.isEmpty()) {
            tf.setText(text);
        } else {
            tf.setText("");
        }
        textFields.add(tf);
        clearSelection();
        selectedTextFields.add(tf);
        selOverlayActive = false;
        recomputeSelectionFrame();
        markSceneDirty();
        notifySelectionChanged();
        invalidate();
        maybeRequestLatexRender(tf);
        return tf;
    }

    public boolean removeTextFieldById(String id) {
        CanvasTextField tf = findTextField(id);
        if (tf == null) return false;
        recordUndoPoint();
        textFields.remove(tf);
        selectedTextFields.remove(tf);
        if (editingTextField == tf) editingTextField = null;
        tf.clearLatexBitmap();
        recomputeSelectionFrame();
        markSceneDirty();
        notifySelectionChanged();
        invalidate();
        return true;
    }

    /**
     * Ids assigned when the bridge pushed a canvas batch
     * ({@code cc} + timestamp + {@code -} + seq + {@code .} + index).
     */
    static boolean isAgentPlacedId(String id) {
        if (id == null || id.length() < 6) return false;
        return id.matches("^cc[a-z0-9]+-\\d+\\.\\d+$");
    }

    /** Remove every agent-placed note still on the canvas. @return count removed */
    public int removeAgentPlacedContent() {
        int removed = 0;
        for (int i = textFields.size() - 1; i >= 0; i--) {
            CanvasTextField tf = textFields.get(i);
            if (!isAgentPlacedId(tf.id)) continue;
            textFields.remove(i);
            selectedTextFields.remove(tf);
            if (editingTextField == tf) editingTextField = null;
            tf.clearLatexBitmap();
            removed++;
        }
        if (removed > 0) {
            recomputeSelectionFrame();
            markSceneDirty();
            notifySelectionChanged();
            invalidate();
        }
        return removed;
    }

    /**
     * World centre for an anchor relative to an existing object (note id, or
     * workspace path for an image).
     */
    public float[] resolveAnchorPoint(String relativeTo, String position) {
        RectF b = findAnchorBounds(relativeTo);
        float[] center = getViewCenterWorld();
        if (b == null) return center;
        float gap = 48f;
        String pos = position == null ? "below" : position;
        switch (pos) {
            case "above":
                return new float[]{b.centerX(), b.top - gap};
            case "left":
                return new float[]{b.left - gap, b.centerY()};
            case "right":
                return new float[]{b.right + gap, b.centerY()};
            case "below":
            default:
                return new float[]{b.centerX(), b.bottom + gap};
        }
    }

    private RectF findAnchorBounds(String relativeTo) {
        if (relativeTo == null || relativeTo.isEmpty()) return null;
        CanvasTextField tf = findTextField(relativeTo);
        if (tf != null) return new RectF(tf.bounds());
        return null;
    }

    public void setTextFieldLatexBitmap(String id, Bitmap bmp, float worldPerPx) {
        CanvasTextField tf = findTextField(id);
        if (tf == null) return;
        tf.setLatexBitmap(bmp, worldPerPx);
        markSceneDirty();
        invalidate();
    }

    public CanvasTextField findTextField(String id) {
        if (id == null || id.isEmpty()) return null;
        for (CanvasTextField tf : textFields) {
            if (id.equals(tf.id)) return tf;
        }
        return null;
    }

    public void updateTextField(String id, String text) {
        CanvasTextField tf = findTextField(id);
        if (tf == null) return;
        recordUndoPoint();
        tf.setText(text);
        markSceneDirty();
        invalidate();
        maybeRequestLatexRender(tf);
    }

    public void updateTextFieldStyle(String id, String fontFamily, int typefaceStyle, float textSize, int color) {
        CanvasTextField tf = findTextField(id);
        if (tf == null) return;
        recordUndoPoint();
        tf.setStyle(fontFamily, typefaceStyle, textSize, color);
        markSceneDirty();
        invalidate();
        maybeRequestLatexRender(tf);
    }

    private void maybeRequestLatexRender(CanvasTextField tf) {
        if (tf == null || listener == null) return;
        if (tf.text == null || tf.text.isEmpty()) return;
        // Content blocks always go through the chat markdown+KaTeX bitmap pipeline.
        if (tf.wantsRender()) {
            listener.onTextFieldNeedsLatexRender(tf.id);
        }
    }

    /** Re-render LaTeX/markdown bitmaps for content blocks and math fields. */
    public void refreshAllLatexTextFields() {
        if (listener == null) return;
        for (CanvasTextField tf : textFields) {
            if (tf.text == null || tf.text.isEmpty()) continue;
            if (tf.wantsRender()) {
                listener.onTextFieldNeedsLatexRender(tf.id);
            }
        }
    }

    /**
     * Marks a field as being edited in place. The canvas stops drawing its text so the
     * overlay editor is the only thing rendering it; the box itself keeps drawing.
     */
    public void setEditingTextField(String id) {
        CanvasTextField next = id == null ? null : findTextField(id);
        if (editingTextField == next) return;
        editingTextField = next;
        markSceneDirty();
        invalidate();
    }

    public boolean isEditingTextField() {
        return editingTextField != null;
    }

    public CanvasTextField getEditingTextField() {
        return editingTextField;
    }

    /**
     * Applies text mid-edit: reflows the box but records no undo point, so a single
     * edit session collapses to one undo step taken when editing begins.
     */
    public void setTextFieldTextLive(String id, String text) {
        CanvasTextField tf = findTextField(id);
        if (tf == null) return;
        tf.setText(text);
        // The field being edited is omitted from the backdrop, so its text changing
        // cannot affect it. Rebuilding the overscanned scene on every keystroke was
        // the whole source of the typing lag.
        if (tf != editingTextField) markSceneDirty();
        invalidate();
        notifySelectionLayout();
    }

    /** Takes the undo snapshot for an about-to-start edit session. */
    public void recordTextFieldEditUndo() {
        recordUndoPoint();
    }

    /** Screen-space rect of a text field, or null if it is gone. */
    public RectF getTextFieldScreenRect(String id) {
        CanvasTextField tf = findTextField(id);
        if (tf == null) return null;
        RectF out = new RectF();
        RectF world = new RectF(tf.bounds());
        mapWorldRectToScreen(world, out);
        return out;
    }

    /** Current world→screen scale factor, for sizing overlay UI to match canvas content. */
    public float getViewScale() {
        return viewScale();
    }

    /** Id of the sole selected text field, or null. */
    public String getSoleSelectedTextFieldId() {
        if (selectedTextFields.size() != 1) return null;
        return selectedTextFields.get(0).id;
    }

    public CanvasTextField getSoleSelectedTextField() {
        if (selectedTextFields.size() != 1) return null;
        return selectedTextFields.get(0);
    }

    /** Map view/screen coordinates to world space. */
    public float[] worldFromScreen(float screenX, float screenY) {
        return screenToWorld(screenX, screenY);
    }

    /** World-space center of the current viewport. */
    // ---- Presentation mode: the current page as a slide for a second screen --------

    /** Told when what a slide shows may have changed: {@code content} false = camera only. */
    public interface SceneChangedHook {
        void changed(boolean content);
    }

    private SceneChangedHook sceneChangedHook;

    public void setSceneChangedHook(SceneChangedHook hook) {
        sceneChangedHook = hook;
    }

    private void fireSceneChanged(boolean content) {
        if (sceneChangedHook != null) sceneChangedHook.changed(content);
    }

    /** The page under the middle of the view, or −1 with no document. */
    public int currentPageIndex() {
        if (!document.isOpen()) return -1;
        float[] c = getViewCenterWorld();
        return document.pageIndexAt(c[1]);
    }

    /**
     * Renders page {@code index} — paper, PDF, ink, notes, images — as large as fits
     * {@code maxW}×{@code maxH}, off the UI thread, and hands the bitmap to
     * {@code done} on the UI thread (null if it could not be made).
     */
    public void renderPageForPresentation(int index, int maxW, int maxH,
                                          java.util.function.Consumer<Bitmap> done) {
        renderPage(index, maxW, maxH, false, done);
    }

    /**
     * Same render for any caller. {@code includeHidden} keeps elements marked "hidden
     * in presentation" (the agent's screenshots see everything the tablet shows). The
     * stroke being drawn right now is included, so a slide follows the pen live.
     */
    public void renderPage(int index, int maxW, int maxH, boolean includeHidden,
                           java.util.function.Consumer<Bitmap> done) {
        if (!document.isOpen() || index < 0 || index >= document.pageCount) {
            done.accept(null);
            return;
        }
        final RectF page = document.pageBounds(index);
        final float scale = Math.min(maxW / page.width(), maxH / page.height());
        final int bw = Math.max(1, Math.round(page.width() * scale));
        final int bh = Math.max(1, Math.round(page.height() * scale));
        final int bg = sceneBgColor;
        final SceneKit snap = new SceneKit();
        snapshotSceneInto(snap, false);
        if (activeStroke != null && !activeStroke.samples.isEmpty()) {
            // A private copy: the live stroke keeps growing on the UI thread.
            snap.strokes.add(activeStroke.duplicate());
        }
        if (!includeHidden) {
            // Elements marked "hidden in presentation" stay on the tablet, not on the slide.
            for (Stroke s : snap.strokes) if (s.presentHidden) snap.skipStrokes.add(s);
            for (CanvasImage img : snap.imgs) if (img.presentHidden) snap.skipImages.add(img);
            for (CanvasTextField tf : snap.texts) if (tf.presentHidden) snap.skipTexts.add(tf);
        }
        try {
            bandPool.execute(() -> {
                Bitmap out = null;
                try {
                    out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(out);
                    c.drawColor(bg);
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    m.postTranslate(-page.left * scale, -page.top * scale);
                    SceneKit kit = new SceneKit();
                    shareSceneKit(kit, snap);
                    drawSceneWorld(c, /*includeOverlays*/ false, /*skipSelected*/ false,
                            m, scale, new RectF(page), kit);
                } catch (Throwable t) {
                    if (out != null) out.recycle();
                    out = null;
                }
                final Bitmap result = out;
                post(() -> done.accept(result));
            });
        } catch (RuntimeException rejected) {
            done.accept(null);
        }
    }

    /** Search hits on the page, in world units; drawn over the page until cleared. */
    private final List<RectF> searchHits = new ArrayList<>();
    private final Paint searchHitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Highlights a search hit and brings it into view. {@code filePage} is the PDF's
     * own page index; {@code rects} are {x, y, w, h} as fractions of that page as
     * shown. The highlight stays until {@link #clearSearchHits()}.
     */
    public void showSearchHit(int filePage, List<float[]> rects) {
        if (!document.isOpen()) return;
        int index = filePage + document.blankPrefix;
        if (index < 0 || index >= document.pageCount) return;
        RectF page = document.pageBounds(index);
        searchHits.clear();
        RectF union = null;
        for (float[] r : rects) {
            if (r == null || r.length < 4) continue;
            RectF w = new RectF(
                    page.left + r[0] * page.width(),
                    page.top + r[1] * page.height(),
                    page.left + (r[0] + r[2]) * page.width(),
                    page.top + (r[1] + r[3]) * page.height());
            searchHits.add(w);
            if (union == null) union = new RectF(w);
            else union.union(w);
        }
        revealWorldRect(union != null ? union : new RectF(page.left, page.top,
                page.right, page.top + page.height() * 0.25f));
        markSceneDirty();
        invalidate();
    }

    public void clearSearchHits() {
        if (searchHits.isEmpty()) return;
        searchHits.clear();
        invalidate();
    }

    /** Moves the camera, zoom unchanged, so {@code r} sits a third of the way down the view. */
    private void revealWorldRect(RectF r) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || r == null) return;
        stopPanFling();
        camScrollY = r.centerY() * camScale - h * 0.33f;
        if (!documentFitsHorizontally()) {
            camScrollX = r.centerX() * camScale - w * 0.5f;
        }
        applyCamera();
        clampDocumentCamera();
        markMatrixDirty();
        markSceneDirty();
        invalidate();
    }

    private void drawSearchHits(Canvas canvas, float scale) {
        if (searchHits.isEmpty()) return;
        searchHitPaint.setStyle(Paint.Style.FILL);
        searchHitPaint.setColor(0x66FFC400);
        float pad = 1.5f / Math.max(0.05f, scale);
        for (RectF r : searchHits) {
            canvas.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad,
                    3f, 3f, searchHitPaint);
        }
    }

    /** An export's annotation layer: one PDF page per document page. */
    public static final class ExportLayer {
        /** The PDF; null if it could not be made. */
        public final byte[] pdf;
        /** Per page: the PDF page it lies over, or −1 for a page only the app has. */
        public final int[] filePages;
        public final String error;

        ExportLayer(byte[] pdf, int[] filePages, String error) {
            this.pdf = pdf;
            this.filePages = filePages;
            this.error = error;
        }
    }

    /**
     * Draws every page's ink, text and images — as vectors where they are vectors —
     * into a PDF, one page per document page at the document's page size, off the
     * UI thread. App-only pages get their paper and ruling too; PDF pages leave the
     * paper out so the layer can go over the original. {@code done} runs on the UI
     * thread.
     */
    public void exportAnnotationLayer(java.util.function.Consumer<ExportLayer> done) {
        exportAnnotationLayer(new ExportOptions(), null, done);
    }

    /** What goes into an export. Defaults: every page, everything on it. */
    public static final class ExportOptions {
        /** Document page indices to export, in order; null = all. */
        public int[] pages;
        public boolean ink = true;
        public boolean text = true;
        public boolean images = true;
        /** Paper colour and ruling on pages only the app has, ruling on the others. */
        public boolean pageLook = true;
        /** Items hidden from presenting still go into the file. */
        public boolean presentHidden = true;
    }

    /**
     * The export's annotation layer with {@code opts} applied. {@code progress} (may
     * be null) hears, on the UI thread, how many pages are drawn so far and of how many.
     */
    public void exportAnnotationLayer(ExportOptions opts,
                                      java.util.function.BiConsumer<Integer, Integer> progress,
                                      java.util.function.Consumer<ExportLayer> done) {
        if (!document.isOpen() || document.pageCount <= 0) {
            done.accept(new ExportLayer(null, new int[0], "no document open"));
            return;
        }
        int[] chosen = opts.pages;
        if (chosen == null) {
            chosen = new int[document.pageCount];
            for (int i = 0; i < chosen.length; i++) chosen[i] = i;
        }
        final int[] order = chosen;
        final int count = order.length;
        if (count == 0) {
            done.accept(new ExportLayer(null, new int[0], "no pages chosen"));
            return;
        }
        final float pageW = document.pageWidth;
        final float pageH = document.pageHeight;
        final int themePaper = pagePaperColor;
        final int[] filePages = new int[count];
        for (int i = 0; i < count; i++) filePages[i] = document.filePageFor(order[i]);
        final RectF[] bounds = new RectF[count];
        for (int i = 0; i < count; i++) bounds[i] = new RectF(document.pageBounds(order[i]));
        final SceneKit kit = new SceneKit();
        snapshotSceneInto(kit, false);
        kit.skipEditing = null;
        kit.skipDocument = true;
        // Live content (web views) is an overlay on the tablet; it has no page form.
        for (CanvasImage img : kit.imgs) {
            if (img.isLive() || !opts.images || (!opts.presentHidden && img.presentHidden)) {
                kit.skipImages.add(img);
            }
        }
        for (Stroke st : kit.strokes) {
            if (!opts.ink || (!opts.presentHidden && st.presentHidden)) kit.skipStrokes.add(st);
        }
        for (CanvasTextField tf : kit.texts) {
            if (!opts.text || (!opts.presentHidden && tf.presentHidden)) kit.skipTexts.add(tf);
        }
        final boolean pageLook = opts.pageLook;
        Thread t = new Thread(() -> {
            ExportLayer result;
            android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
            try {
                int pw = Math.max(1, Math.round(pageW));
                int ph = Math.max(1, Math.round(pageH));
                for (int i = 0; i < count; i++) {
                    android.graphics.pdf.PdfDocument.Page page = pdf.startPage(
                            new android.graphics.pdf.PdfDocument.PageInfo.Builder(pw, ph, i + 1).create());
                    Canvas c = page.getCanvas();
                    c.scale(pw / pageW, ph / pageH);
                    if (pageLook) {
                        document.drawForExport(c, order[i], themePaper);
                    } else if (filePages[i] < 0) {
                        // A page only the app has still needs paper under the ink.
                        c.drawColor(0xFFFFFFFF);
                    }
                    Matrix m = new Matrix();
                    m.setTranslate(-bounds[i].left, -bounds[i].top);
                    RectF cull = new RectF(bounds[i]);
                    drawSceneWorld(c, /*includeOverlays*/ false, /*skipSelected*/ false,
                            m, 1f, cull, kit);
                    pdf.finishPage(page);
                    if (progress != null) {
                        final int drawn = i + 1;
                        post(() -> progress.accept(drawn, count));
                    }
                }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                pdf.writeTo(out);
                result = new ExportLayer(out.toByteArray(), filePages, null);
            } catch (Throwable e) {
                android.util.Log.w("CodeCanvasView", "export layer failed", e);
                result = new ExportLayer(null, filePages,
                        e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                pdf.close();
            }
            final ExportLayer r = result;
            post(() -> done.accept(r));
        }, "cc-export-layer");
        t.start();
    }

    public float[] getViewCenterWorld() {
        ensureInverse();
        worldTmp[0] = getWidth() * 0.5f;
        worldTmp[1] = getHeight() * 0.5f;
        inverse.mapPoints(worldTmp);
        return new float[]{worldTmp[0], worldTmp[1]};
    }

    /** True when everything selected is hidden from the presentation. */
    public boolean selectionPresentHidden() {
        if (!hasSelection()) return false;
        for (Stroke s : selectedStrokes) if (!s.presentHidden) return false;
        for (CanvasImage img : selectedImages) if (!img.presentHidden) return false;
        for (CanvasTextField tf : selectedTextFields) if (!tf.presentHidden) return false;
        return true;
    }

    /**
     * Hides the selection from the presentation slide, or — if all of it is hidden
     * already — shows it again. The tablet keeps showing it either way. One undo step.
     */
    public boolean togglePresentHidden() {
        if (!hasSelection()) return false;
        boolean hide = !selectionPresentHidden();
        recordUndoPoint();
        for (Stroke s : selectedStrokes) {
            s.presentHidden = hide;
            s.json = null;
        }
        for (CanvasImage img : selectedImages) img.presentHidden = hide;
        for (CanvasTextField tf : selectedTextFields) tf.presentHidden = hide;
        notifyContentChanged();  // the slide re-renders from this
        return hide;
    }

    /** True when the selection holds handwriting (strokes) or text that can be recoloured. */
    /** True when the selection holds handwriting. */
    public boolean selectionHasInk() {
        return !selectedStrokes.isEmpty();
    }

    public boolean selectionCanRecolor() {
        if (!selectedStrokes.isEmpty()) return true;
        for (CanvasTextField tf : selectedTextFields) {
            if (!tf.contentBlock) return true;
        }
        return false;
    }

    /**
     * Recolours the selected handwriting — and plain text — in place; one undo step.
     * The selection stays, so another colour can be tried straight away.
     */
    public boolean recolorSelection(int color, String colorName) {
        if (!selectionCanRecolor()) return false;
        recordUndoPoint();
        for (Stroke s : selectedStrokes) {
            s.color = color;
            s.colorName = colorName;
            s.json = null;   // saved form carries the colour
        }
        for (CanvasTextField tf : selectedTextFields) {
            if (tf.contentBlock) continue;
            tf.setStyle(tf.fontFamily, tf.typefaceStyle, tf.textSize, color);
            maybeRequestLatexRender(tf);
        }
        releaseSelDragBitmap();
        markSceneDirty();
        invalidate();
        notifyContentChanged();
        return true;
    }

    public boolean hasActiveSelection() {
        return hasSelection();
    }

    /** True while the user is moving/resizing/rotating the selection. */
    public boolean isSelectionGestureActive() {
        return selGesture != SelGesture.NONE;
    }

    /** Duplicate selected ink + images + text fields (offset). */
    public void copySelection() {
        if (!hasSelection()) return;
        recordUndoPoint();
        float scale = viewScale();
        float ox = 48f / scale;
        float oy = 48f / scale;

        List<Stroke> newStrokes = new ArrayList<>();
        List<CanvasImage> newImages = new ArrayList<>();
        List<CanvasTextField> newTextFields = new ArrayList<>();

        for (Stroke s : new ArrayList<>(selectedStrokes)) {
            Stroke dup = s.duplicate();
            dup.translate(ox, oy);
            inkStrokes.add(dup);
            newStrokes.add(dup);
        }

        for (CanvasImage img : new ArrayList<>(selectedImages)) {
            if (img.bitmap == null || img.bitmap.isRecycled()) continue;
            CanvasImage dup = new CanvasImage(img.bitmap, img.cx + ox, img.cy + oy, img.width, img.height);
            dup.presentHidden = img.presentHidden;
            dup.rotationDeg = img.rotationDeg;
            images.add(dup);
            newImages.add(dup);
        }

        for (CanvasTextField tf : new ArrayList<>(selectedTextFields)) {
            CanvasTextField dup = tf.duplicate(ox, oy);
            textFields.add(dup);
            newTextFields.add(dup);
        }

        if (newStrokes.isEmpty() && newImages.isEmpty() && newTextFields.isEmpty()) return;

        selectedStrokes.clear();
        selectedImages.clear();
        selectedTextFields.clear();
        selectedStrokes.addAll(newStrokes);
        selectedImages.addAll(newImages);
        selectedTextFields.addAll(newTextFields);
        // The copies sit (ox, oy) away from the originals: move the selection box with
        // them, keeping its rotation and size, instead of leaving it on the originals.
        if (selFrameValid) {
            selFramePivotX += ox;
            selFramePivotY += oy;
        } else {
            recomputeSelectionFrame();
        }
        markSceneDirty();
        notifyContentChanged();
        notifySelectionChanged();
        invalidate();
    }

    // ---- Canvas clipboard: cut / copy / paste of handwriting (and anything else selected) ----

    private final List<Stroke> clipStrokes = new ArrayList<>();
    private final List<CanvasImage> clipImages = new ArrayList<>();
    private final List<CanvasTextField> clipTextFields = new ArrayList<>();
    private final RectF clipBounds = new RectF();

    public boolean hasCanvasClipboard() {
        return !clipStrokes.isEmpty() || !clipImages.isEmpty() || !clipTextFields.isEmpty();
    }

    /** Snapshot the selection into the canvas clipboard (independent copies). */
    public boolean copySelectionToClipboard() {
        if (!hasSelection()) return false;
        clipStrokes.clear();
        clipImages.clear();
        clipTextFields.clear();
        RectF union = null;
        for (Stroke s : selectedStrokes) {
            Stroke dup = s.duplicate();
            clipStrokes.add(dup);
            if (dup.bounds.isEmpty()) continue;
            if (union == null) union = new RectF(dup.bounds);
            else union.union(dup.bounds);
        }
        for (CanvasImage img : selectedImages) {
            if (img.bitmap == null || img.bitmap.isRecycled()) continue;
            CanvasImage dup = new CanvasImage(img.bitmap, img.cx, img.cy, img.width, img.height);
            dup.presentHidden = img.presentHidden;
            dup.rotationDeg = img.rotationDeg;
            clipImages.add(dup);
            RectF b = dup.bounds();
            if (union == null) union = new RectF(b);
            else union.union(b);
        }
        for (CanvasTextField tf : selectedTextFields) {
            CanvasTextField dup = tf.duplicate(0f, 0f);
            clipTextFields.add(dup);
            RectF b = dup.bounds();
            if (union == null) union = new RectF(b);
            else union.union(b);
        }
        if (union == null) clipBounds.setEmpty();
        else clipBounds.set(union);
        return hasCanvasClipboard();
    }

    /** Copy the selection to the canvas clipboard, then remove it (one undo step). */
    public boolean cutSelection() {
        if (!copySelectionToClipboard()) return false;
        deleteSelection();
        return true;
    }

    /**
     * Paste the canvas clipboard centered on a world point. The pasted items become the
     * selection so they can be moved straight away; the clipboard stays for repeat pastes.
     */
    public boolean pasteClipboardAt(float wx, float wy) {
        if (!hasCanvasClipboard()) return false;
        recordUndoPoint();
        float dx = clipBounds.isEmpty() ? 0f : wx - clipBounds.centerX();
        float dy = clipBounds.isEmpty() ? 0f : wy - clipBounds.centerY();
        clearSelection();
        for (Stroke s : clipStrokes) {
            Stroke dup = s.duplicate();
            dup.translate(dx, dy);
            inkStrokes.add(dup);
            selectedStrokes.add(dup);
        }
        for (CanvasImage img : clipImages) {
            if (img.bitmap == null || img.bitmap.isRecycled()) continue;
            CanvasImage dup = new CanvasImage(img.bitmap, img.cx + dx, img.cy + dy, img.width, img.height);
            dup.presentHidden = img.presentHidden;
            dup.rotationDeg = img.rotationDeg;
            images.add(dup);
            selectedImages.add(dup);
        }
        for (CanvasTextField tf : clipTextFields) {
            CanvasTextField dup = tf.duplicate(dx, dy);
            textFields.add(dup);
            selectedTextFields.add(dup);
        }
        selOverlayActive = false;
        recomputeSelectionFrame();
        markSceneDirty();
        notifyContentChanged();
        notifySelectionChanged();
        invalidate();
        return true;
    }

    /** Delete currently selected ink / images / text fields. */
    public void deleteSelection() {
        if (!hasSelection()) return;
        recordUndoPoint();
        for (Stroke s : new ArrayList<>(selectedStrokes)) {
            inkStrokes.remove(s);
        }
        for (CanvasImage img : new ArrayList<>(selectedImages)) {
            images.remove(img);
        }
        for (CanvasTextField tf : new ArrayList<>(selectedTextFields)) {
            textFields.remove(tf);
        }
        clearSelection();
        markSceneDirty();
        notifyContentChanged();
    }

    private void notifySelectionChanged() {
        if (listener != null) listener.onSelectionChanged(hasSelection());
        notifySelectionLayout();
    }

    private void notifySelectionLayout() {
        if (listener != null) listener.onSelectionLayoutChanged();
    }

    /**
     * Screen-space top-center of the current selection / lasso region.
     * Returns null when nothing is selected.
     */
    public float[] getSelectionToolbarAnchorScreen() {
        RectF world = overlayBoundsWorld();
        if (world == null || world.isEmpty()) return null;
        float[] pts = {world.centerX(), world.top};
        viewMatrix.mapPoints(pts);
        return pts;
    }

    /** Finger tap selection for text fields and images (any tool). */
    private boolean tapSelectAt(float wx, float wy) {
        for (int i = textFields.size() - 1; i >= 0; i--) {
            CanvasTextField tf = textFields.get(i);
            if (tf.contains(wx, wy)) {
                selectOnlyTextField(tf);
                return true;
            }
        }
        for (int i = images.size() - 1; i >= 0; i--) {
            CanvasImage img = images.get(i);
            if (img.contains(wx, wy)) {
                selectOnlyImage(img);
                return true;
            }
        }
        if (hasSelection() || hasLassoRegion()) {
            clearSelection();
            return true;
        }
        return false;
    }

    private void selectOnlyTextField(CanvasTextField tf) {
        selectedStrokes.clear();
        selectedImages.clear();
        selectedTextFields.clear();
        clearLassoRegion();
        selectedTextFields.add(tf);
        movingSelection = false;
        transformingSelection = false;
        selGesture = SelGesture.NONE;
        selOverlayActive = false;
        recomputeSelectionFrame();
        markSceneDirty();
        invalidate();
        notifySelectionChanged();
    }

    private void selectOnlyImage(CanvasImage img) {
        selectedStrokes.clear();
        selectedImages.clear();
        selectedTextFields.clear();
        clearLassoRegion();
        selectedImages.add(img);
        movingSelection = false;
        transformingSelection = false;
        selGesture = SelGesture.NONE;
        selOverlayActive = false;
        selFrameValid = false;
        markSceneDirty();
        invalidate();
        notifySelectionChanged();
    }

    public void addImageBitmap(Bitmap bmp) {
        float[] center = screenToWorld(getWidth() * 0.5f, getHeight() * 0.5f);
        addImageBitmapAt(bmp, center[0], center[1]);
    }

    public void addImageBitmapAt(Bitmap bmp, float worldX, float worldY) {
        addImageBitmapAt(bmp, worldX, worldY, null);
    }

    public void addImageBitmapAt(Bitmap bmp, float worldX, float worldY, String sourcePath) {
        if (bmp == null || bmp.isRecycled()) return;
        CanvasImage existing = sourcePath != null ? findImageByPath(sourcePath) : null;
        if (existing != null) {
            existing.bitmap = bmp;
            existing.markDirty();
            markSceneDirty();
            invalidate();
            if (listener != null) listener.onContentChanged();
            return;
        }
        recordUndoPoint();
        float bw = bmp.getWidth();
        float bh = bmp.getHeight();
        float scale = 1f;
        float maxDim = Math.max(bw, bh);
        if (maxDim > MAX_IMAGE_WORLD) scale = MAX_IMAGE_WORLD / maxDim;
        float w = bw * scale;
        float h = bh * scale;
        CanvasImage img = new CanvasImage(bmp, worldX, worldY, w, h);
        if (sourcePath != null && !sourcePath.isEmpty()) {
            img.sourcePath = sourcePath;
            img.vizPath = sourcePath;
        }
        images.add(img);
        imagePasteArmed = false;
        clearSelection();
        selectedImages.add(images.get(images.size() - 1));
        markSceneDirty();
        invalidate();
        if (listener != null) listener.onContentChanged();
    }

    static String normalizeImagePath(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        if (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        if (p.startsWith(".artifacts/")) p = p.substring(".artifacts/".length());
        return p;
    }

    static String imageBasename(String path) {
        String n = normalizeImagePath(path);
        if (n.isEmpty()) return "";
        int slash = n.lastIndexOf('/');
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    static boolean imagePathsMatch(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String na = normalizeImagePath(a);
        String nb = normalizeImagePath(b);
        if (na.equals(nb)) return true;
        String ba = imageBasename(a);
        String bb = imageBasename(b);
        return !ba.isEmpty() && ba.equalsIgnoreCase(bb);
    }

    private CanvasImage findImageByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        for (CanvasImage img : images) {
            if (pathsEqualNormalized(path, img.vizPath) || pathsEqualNormalized(path, img.sourcePath)) {
                return img;
            }
        }
        return null;
    }

    private static boolean pathsEqualNormalized(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || normalizeImagePath(a).equals(normalizeImagePath(b));
    }

    /** Untracked image nearest to (ax, ay), or sole untracked image on canvas. */
    private CanvasImage findUntrackedImageNear(float ax, float ay) {
        CanvasImage best = null;
        float bestDist = Float.MAX_VALUE;
        int untracked = 0;
        CanvasImage only = null;
        for (CanvasImage img : images) {
            boolean hasPath = (img.vizPath != null && !img.vizPath.isEmpty())
                    || (img.sourcePath != null && !img.sourcePath.isEmpty());
            if (hasPath) continue;
            untracked++;
            only = img;
            if (!Float.isNaN(ax) && !Float.isNaN(ay)) {
                float dx = img.cx - ax;
                float dy = img.cy - ay;
                float d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = img;
                }
            }
        }
        if (best != null) return best;
        if (untracked == 1) return only;
        return null;
    }

    private void applyBitmapToImage(CanvasImage existing, Bitmap bmp, String path) {
        Bitmap old = existing.bitmap;
        existing.bitmap = bmp;
        // Keep user's size and rotation; only swap pixels (and path metadata).
        if (path != null && !path.isEmpty()) {
            existing.vizPath = path;
            existing.sourcePath = path;
        }
        existing.markDirty();
        if (old != null && old != bmp && !old.isRecycled()) {
            old.recycle();
        }
        markSceneDirty();
        invalidate();
        if (listener != null) listener.onContentChanged();
    }

    /** Place or refresh a workspace/agent image; keeps position when the file updates. */
    public void addOrUpdateVizImage(String path, Bitmap bmp) {
        addOrUpdateVizImage(path, bmp, Float.NaN, Float.NaN, true);
    }

    public void addOrUpdateVizImage(String path, Bitmap bmp, float anchorX, float anchorY) {
        addOrUpdateVizImage(path, bmp, anchorX, anchorY, true);
    }

    /**
     * @param allowCreate if false, only update an existing matched/untracked image
     * @return true if an image was updated or created
     */
    public boolean addOrUpdateVizImage(
            String path, Bitmap bmp, float anchorX, float anchorY, boolean allowCreate) {
        if (path == null || path.isEmpty() || bmp == null || bmp.isRecycled()) return false;

        CanvasImage byPath = findImageByPath(path);
        if (byPath != null) {
            applyBitmapToImage(byPath, bmp, path);
            return true;
        }
        CanvasImage untracked = findUntrackedImageNear(anchorX, anchorY);
        if (untracked != null) {
            applyBitmapToImage(untracked, bmp, path);
            return true;
        }

        if (!allowCreate) return false;
        recordUndoPoint();
        float bw = bmp.getWidth();
        float bh = bmp.getHeight();
        float scale = 1f;
        float maxDim = Math.max(bw, bh);
        if (maxDim > MAX_IMAGE_WORLD) scale = MAX_IMAGE_WORLD / maxDim;
        float w = bw * scale;
        float h = bh * scale;
        float cx;
        float cy;
        if (!Float.isNaN(anchorX) && !Float.isNaN(anchorY)) {
            cx = anchorX;
            cy = anchorY;
        } else {
            float[] center = nextVizPlacement(w, h);
            cx = center[0];
            cy = center[1];
        }
        CanvasImage img = new CanvasImage(bmp, cx, cy, w, h);
        img.vizPath = path;
        img.sourcePath = path;
        images.add(img);
        markSceneDirty();
        invalidate();
        if (listener != null) listener.onContentChanged();
        return true;
    }

    /** All workspace paths tied to canvas images (for refresh after script runs). */
    public List<String> getTrackedImagePaths() {
        List<String> out = new ArrayList<>();
        for (CanvasImage img : images) {
            if (img.vizPath != null && !img.vizPath.isEmpty()) out.add(img.vizPath);
            if (img.sourcePath != null && !img.sourcePath.isEmpty()
                    && !out.contains(img.sourcePath)) {
                out.add(img.sourcePath);
            }
        }
        return out;
    }

    private float[] nextVizPlacement(float width, float height) {
        float maxRight = -Float.MAX_VALUE;
        float minTop = Float.MAX_VALUE;
        float maxBottom = -Float.MAX_VALUE;
        for (CanvasImage img : images) {
            RectF b = img.bounds();
            maxRight = Math.max(maxRight, b.right);
            minTop = Math.min(minTop, b.top);
            maxBottom = Math.max(maxBottom, b.bottom);
        }
        if (maxRight <= -Float.MAX_VALUE) {
            return screenToWorld(getWidth() * 0.5f, getHeight() * 0.5f);
        }
        float cy = (minTop + maxBottom) * 0.5f;
        return new float[]{maxRight + PLACEMENT_GAP + width * 0.5f, cy};
    }

    public void setInk(int argb, String name) {
        inkColor = argb;
        inkName = name;
        currentTool = Tool.PENCIL;
        eraseMode = false;
        activeStroke = null;
        eraseX = eraseY = Float.NaN;
        invalidate();
    }

    public void setEraseMode(boolean erase) {
        eraseMode = erase;
        currentTool = erase ? Tool.ERASER : Tool.PENCIL;
        activeStroke = null;
        eraseX = eraseY = Float.NaN;
        invalidate();
    }

    public void setBaseThicknessPx(float px) {
        baseThicknessPx = Math.max(1f, Math.min(24f, px));
    }

    /** 0 = follow the app theme. */
    public int getPageBackground() {
        return document.paperOverride;
    }

    public String getPageStyle() {
        return document.pageStyle == null ? DocumentPages.STYLE_BLANK : document.pageStyle;
    }

    /** Page index at the centre of the viewport — what "this page" means. */
    public int getCurrentPageIndex() {
        if (!document.isOpen()) return 0;
        float[] c = getViewCenterWorld();
        return document.pageIndexAt(c[1]);
    }

    public int getPageBackgroundAt(int index) {
        return document.paperForPage(index);
    }

    public String getPageStyleAt(int index) {
        return document.styleForPage(index);
    }

    /** How far a page-look change reaches. */
    public enum PageLookScope { CURRENT, ALL, DEFAULT }

    public void applyPageLook(int argb, String style, PageLookScope scope) {
        applyPageLook(argb, style, getPageRuleScale(), scope);
    }

    /**
     * True when page styling may be offered for the open document: either the app
     * created it, or it already carries a look from before the app tracked that.
     */
    public boolean canStylePages() {
        return document.blankOwned || document.hasLook();
    }

    /** Ruling spacing of the page in view, as a multiple of the base step. */
    public float getPageRuleScale() {
        return document.ruleScaleForPage(getCurrentPageIndex());
    }

    public void applyPageLook(int argb, String style, float ruleScale, PageLookScope scope) {
        switch (scope) {
            case CURRENT:
                document.setPageLook(getCurrentPageIndex(), argb, style, ruleScale);
                break;
            case ALL:
                document.setAllPagesLook(argb, style, ruleScale);
                break;
            default:
                document.setDefaultLook(argb, style, ruleScale);
                break;
        }
        markSceneDirty();
        invalidate();
        notifyContentChanged();
    }

    /** Paper colour + ruling for this document's pages. */
    public void setPageBackground(int argb, String style) {
        applyPageLook(argb, style, PageLookScope.ALL);
    }

    public void applyCanvasBackground(int argb) {
        sceneBgColor = argb;
        markSceneDirty();
        invalidate();
    }

    /** Sync selection chrome, content blocks and canvas with the app theme. */
    public void applyAppTheme(ThemeConfig.AppTheme theme) {
        if (theme == null) theme = ThemeConfig.APP_THEMES[0];
        chromeAccent = theme.primary;
        chromeLight = theme.light;
        lassoPaint.setColor(theme.primary);
        selectionPaint.setColor(theme.primary);
        selectionFillPaint.setColor((theme.primary & 0x00FFFFFF) | 0x55000000);
        gimbalFillPaint.setColor(theme.primary);
        gimbalStrokePaint.setColor(theme.light ? 0xFFFFFFFF : theme.onSurface);
        // Two-tone ring. A single theme-coloured circle disappeared against a white
        // page on the dark themes, which is exactly where the eraser gets used.
        erasePreviewPaint.setColor(0xE6202024);
        eraseHaloPaint.setColor(0xB3FFFFFF);
        CanvasTextField.applyChromeTheme(theme);
        applyCanvasBackground(theme.canvasBg);
        // Follow the theme's code paper so dark appearances actually darken pages
        // (the old cream fallback left every dark theme looking like dawn paper).
        pagePaperColor = theme.code != null ? theme.code.paper
                : (theme.light ? 0xFFFAFAFA : 0xFF1A1A1E);
        document.applyTheme(theme.light);
        emptyHintPaint.setColor(theme.onSurfaceVariant);
        emptyHintPaint.setTextAlign(Paint.Align.CENTER);
        emptyHintPaint.setTextSize(16f);
        emptyButtonFill = theme.primaryContainer;
        emptyButtonIcon = theme.onPrimaryContainer;
        markSceneDirty();
        invalidate();
    }

    /**
     * Paper used when a page has no look chosen (0): the theme's for a document the
     * app made, the PDF's own white for an imported one.
     */
    public int getThemePaperColor() {
        return document.isOpen() ? document.defaultPaper(pagePaperColor) : pagePaperColor;
    }

    private void applySelectionStrokeColor() {
        selectionPaint.setColor(chromeAccent);
    }

    public void applyCodeStyle(ThemeConfig.CodeStyle style) {
        if (style == null) style = ThemeConfig.CODE_STYLES[0];
        currentCodeStyle = style;
        paperPaint.setColor(style.paper);
        gutterPaint.setColor(style.gutter);
        titlePaint.setColor(style.title);
        gridPaint.setColor(style.grid);
        markSceneDirty();
        invalidate();
    }

    public String getCodeStyleId() {
        return currentCodeStyle != null ? currentCodeStyle.id : "material";
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String p = path.trim();
        while (p.startsWith("./")) p = p.substring(2);
        return p;
    }

    public float getBaseThicknessPx() {
        return baseThicknessPx;
    }

    public int getInkColor() {
        return inkColor;
    }

    public String getInkName() {
        return inkName;
    }

    /** Snapshot annotations + open document for persistence. */
    public org.json.JSONObject exportCanvasState() throws Exception {
        org.json.JSONObject root = new org.json.JSONObject();
        if (document.isOpen()) {
            root.put("documentPath", document.path);
            root.put("pageCount", document.pageCount);
            root.put("blankOwned", document.blankOwned);
            root.put("blankPrefix", document.blankPrefix);
            root.put("pageBg", document.paperOverride);
            root.put("plainPaper", document.plainImport);
            root.put("pageStyle", getPageStyle());
            root.put("pageRuleScale", document.ruleScale);
            root.put("pageLooks", document.looksToJson());
        }
        org.json.JSONArray strokesJson = new org.json.JSONArray();
        for (Stroke s : inkStrokes) {
            strokesJson.put(strokeToJson(s));
        }
        root.put("strokes", strokesJson);

        org.json.JSONArray imagesJson = new org.json.JSONArray();
        for (CanvasImage img : images) {
            if (img == null) continue;
            // A live element has no bitmap — its content is the artifact file. The
            // old bitmap-only guard dropped those on every save.
            boolean hasPixels = img.bitmap != null && !img.bitmap.isRecycled();
            if (hasPixels || img.isLive()) {
                imagesJson.put(img.toJson());
            }
        }
        root.put("images", imagesJson);

        org.json.JSONArray textFieldsJson = new org.json.JSONArray();
        for (CanvasTextField tf : textFields) textFieldsJson.put(tf.toJson());
        root.put("textFields", textFieldsJson);

        float[] m = new float[9];
        viewMatrix.getValues(m);
        org.json.JSONArray matrix = new org.json.JSONArray();
        for (float v : m) matrix.put(v);
        root.put("matrix", matrix);
        root.put("baseThicknessPx", baseThicknessPx);
        root.put("inkColor", inkColor);
        root.put("inkName", inkName);
        root.put("eraseMode", eraseMode);
        root.put("selectInk", selectInk);
        root.put("selectImages", selectImages);
        root.put("lassoSelectInk", lassoSelectInk);
        root.put("lassoSelectImages", lassoSelectImages);
        root.put("eraseSelectInk", eraseSelectInk);
        root.put("eraseSelectImages", eraseSelectImages);
        root.put("eraseSelectHighlighter", eraseSelectHighlighter);
        root.put("lassoSelectHighlighter", lassoSelectHighlighter);
        root.put("lassoSelectText", lassoSelectText);
        root.put("eraseSelectText", eraseSelectText);
        return root;
    }

    /** Restore strokes, images, text fields and viewport. */
    /**
     * Document whose saved camera was just restored by importCanvasState, so the
     * openDocument that follows knows not to re-fit over it.
     */
    private String restoredViewPath = "";
    /** True once a saved camera has been restored, so first layout must not re-fit. */
    private boolean cameraRestored;

    private static boolean hasEntries(org.json.JSONObject root, String key) {
        org.json.JSONArray a = root.optJSONArray(key);
        return a != null && a.length() > 0;
    }

    public void importCanvasState(org.json.JSONObject root) throws Exception {
        document.paperOverride = root.optInt("pageBg", 0);
        // Saved before this default existed and already drawn on the theme's paper: keep
        // that look. Otherwise an imported PDF stays as uploaded.
        document.plainImport = root.has("plainPaper")
                ? root.optBoolean("plainPaper", true)
                : !(hasEntries(root, "strokes") || hasEntries(root, "images") || hasEntries(root, "textFields"));
        document.pageStyle = root.optString("pageStyle", DocumentPages.STYLE_BLANK);
        document.ruleScale = DocumentPages.clampRuleScale(
                (float) root.optDouble("pageRuleScale", 1.0));
        document.looksFromJson(root.optJSONObject("pageLooks"));
        // Sessions written before placed artifacts became images. Held aside because
        // the image list is cleared below, then appended once it has been rebuilt.
        List<CanvasImage> legacyLive = new ArrayList<>();
        org.json.JSONArray legacyWeb = root.optJSONArray("webBlocks");
        if (legacyWeb != null) {
            for (int i = 0; i < legacyWeb.length(); i++) {
                org.json.JSONObject o = legacyWeb.optJSONObject(i);
                if (o == null) continue;
                String lp = o.optString("path", "");
                if (lp.isEmpty()) continue;
                CanvasImage img = new CanvasImage(null,
                        (float) o.optDouble("cx", 0), (float) o.optDouble("cy", 0),
                        (float) o.optDouble("width", 226), (float) o.optDouble("height", 149));
                img.livePath = lp;
                legacyLive.add(img);
            }
        }
        inkStrokes.clear();
        images.clear();
        textFields.clear();
        undoStack.clear();
        redoStack.clear();
        historyChanged();
        drawBaseline = null;
        eraseBaseline = null;
        gestureStartSnap = null;
        clearSelection();
        activeStroke = null;
        // Sessions saved when code lived on the canvas still carry "sheets" and
        // "scripts"; only the ink nested under a sheet is worth migrating.
        org.json.JSONArray sheetsJson = root.optJSONArray("sheets");
        if (sheetsJson != null) {
            for (int i = 0; i < sheetsJson.length(); i++) {
                org.json.JSONObject sj = sheetsJson.optJSONObject(i);
                if (sj == null) continue;
                org.json.JSONArray legacy = sj.optJSONArray("strokes");
                if (legacy == null) continue;
                for (int j = 0; j < legacy.length(); j++) {
                    Stroke s = strokeFromJson(legacy.getJSONObject(j));
                    if (s != null) inkStrokes.add(s);
                }
            }
        }
        org.json.JSONArray strokesJson = root.optJSONArray("strokes");
        if (strokesJson != null) {
            for (int i = 0; i < strokesJson.length(); i++) {
                Stroke s = strokeFromJson(strokesJson.getJSONObject(i));
                if (s != null) inkStrokes.add(s);
            }
        }
        if (removeDuplicateStartDots()) post(this::notifyContentChanged);
        org.json.JSONArray imagesJson = root.optJSONArray("images");
        if (imagesJson != null) {
            for (int i = 0; i < imagesJson.length(); i++) {
                CanvasImage img = CanvasImage.fromJson(imagesJson.getJSONObject(i));
                if (img != null) images.add(img);
            }
        }
        images.addAll(legacyLive);
        org.json.JSONArray textFieldsJson = root.optJSONArray("textFields");
        if (textFieldsJson != null) {
            for (int i = 0; i < textFieldsJson.length(); i++) {
                CanvasTextField tf = CanvasTextField.fromJson(textFieldsJson.getJSONObject(i));
                if (tf == null) continue;
                // Drop historical agent-placed notes (batch ids like ccmu8x…-1.0).
                if (isAgentPlacedId(tf.id)) continue;
                textFields.add(tf);
            }
        }
        org.json.JSONArray matrix = root.optJSONArray("matrix");
        if (matrix != null && matrix.length() == 9) {
            float[] m = new float[9];
            for (int i = 0; i < 9; i++) m[i] = (float) matrix.getDouble(i);
            viewMatrix.setValues(m);
            syncCameraFromMatrix();
            markMatrixDirty();
            restoredViewPath = root.optString("documentPath", "");
            cameraRestored = true;
        } else if (document.isOpen()) {
            fitDocumentInView();
        } else {
            camScale = 1f;
            camScrollX = -40f;
            camScrollY = -40f;
            applyCamera();
        }
        if (root.has("baseThicknessPx")) {
            baseThicknessPx = (float) root.optDouble("baseThicknessPx", baseThicknessPx);
        }
        if (root.has("inkColor")) inkColor = root.optInt("inkColor", inkColor);
        if (root.has("inkName")) inkName = root.optString("inkName", inkName);
        eraseMode = root.optBoolean("eraseMode", false);
        currentTool = eraseMode ? Tool.ERASER : Tool.PENCIL;
        selectInk = root.optBoolean("selectInk", true);
        selectImages = root.optBoolean("selectImages", true);
        lassoSelectInk = root.optBoolean("lassoSelectInk", selectInk);
        lassoSelectImages = root.optBoolean("lassoSelectImages", selectImages);
        eraseSelectInk = root.optBoolean("eraseSelectInk", selectInk);
        eraseSelectImages = root.optBoolean("eraseSelectImages", selectImages);
        eraseSelectHighlighter = root.optBoolean("eraseSelectHighlighter", eraseSelectInk);
        eraseSelectText = root.optBoolean("eraseSelectText", false);
        lassoSelectHighlighter = root.optBoolean("lassoSelectHighlighter", lassoSelectInk);
        lassoSelectText = root.optBoolean("lassoSelectText", true);
        markSceneDirty();
        notifyContentChanged();
        invalidate();
        // LaTeX boxes are drawn from a rendered bitmap that is not saved: bring them back.
        post(this::refreshAllLatexTextFields);
    }

    private static org.json.JSONObject strokeToJson(Stroke s) throws Exception {
        if (s.json != null) return s.json;
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("color", s.color);
        o.put("colorName", s.colorName);
        o.put("packed", packSamples(s.samples));
        if (s.brush != BRUSH_INK) o.put("brush", BRUSH_KEYS[s.brush]);
        if (s.presentHidden) o.put("presentHidden", true);
        s.json = o;
        return o;
    }

    /**
     * A stroke's samples as base64 of little-endian float32 x, y, width triples.
     *
     * <p>They used to be saved as {@code [[x,y,w],...]}: a JSONArray and three boxed
     * Doubles per sample, ~130 bytes each on the heap, held for every stroke of every
     * document and parsed back the same way. Heavily inked sessions ran the 512MB heap
     * out on load and on save. Packed, a sample is 16 characters of one string.
     */
    static String packSamples(List<Sample> samples) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(samples.size() * 12)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (Sample p : samples) {
            buf.putFloat(p.x);
            buf.putFloat(p.y);
            buf.putFloat(p.width);
        }
        return android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP);
    }

    static void unpackSamples(String packed, List<Sample> out) {
        byte[] raw = android.util.Base64.decode(packed, android.util.Base64.NO_WRAP);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (buf.remaining() >= 12) {
            float x = buf.getFloat(), y = buf.getFloat(), w = buf.getFloat();
            if (Float.isNaN(x) || Float.isNaN(y)) continue;
            out.add(new Sample(x, y, Float.isNaN(w) || w <= 0f ? 4f : w));
        }
    }

    /**
     * Drops the stray one-point strokes an earlier build left under the start of real
     * strokes (see the cancelled-touch note in the ink handler): a single sample that is
     * exactly the first sample of the stroke right after it, same colour and brush. Two
     * separate touches never land on identical coordinates, so real dots are safe.
     *
     * @return true when something was removed
     */
    private boolean removeDuplicateStartDots() {
        boolean removed = false;
        for (int i = inkStrokes.size() - 2; i >= 0; i--) {
            Stroke dot = inkStrokes.get(i);
            Stroke next = inkStrokes.get(i + 1);
            if (dot.samples.size() != 1 || next.samples.size() < 2) continue;
            if (dot.color != next.color || dot.brush != next.brush) continue;
            Sample a = dot.samples.get(0);
            Sample b = next.samples.get(0);
            if (Math.abs(a.x - b.x) > 1e-3f || Math.abs(a.y - b.y) > 1e-3f) continue;
            inkStrokes.remove(i);
            removed = true;
        }
        return removed;
    }

    private static Stroke strokeFromJson(org.json.JSONObject o) throws Exception {
        Stroke s = new Stroke(o.optInt("color", 0xFF4C8DFF), o.optString("colorName", "blue"));
        s.brush = brushFromKey(o.optString("brush", ""));
        s.presentHidden = o.optBoolean("presentHidden", false);
        // Glow strokes from before there were several effect brushes.
        if (s.brush == BRUSH_INK && o.optBoolean("glow", false)) s.brush = BRUSH_GLOW;
        String packed = o.optString("packed", "");
        org.json.JSONArray samples = packed.isEmpty() ? o.optJSONArray("samples") : null;
        if (!packed.isEmpty()) {
            unpackSamples(packed, s.samples);
        } else if (samples != null) {
            for (int i = 0; i < samples.length(); i++) {
                org.json.JSONArray pt = samples.getJSONArray(i);
                float x = (float) pt.getDouble(0);
                float y = (float) pt.getDouble(1);
                float w = pt.length() > 2 ? (float) pt.getDouble(2) : 4f;
                s.samples.add(new Sample(x, y, w));
            }
        }
        s.recomputeBounds();
        return s.samples.isEmpty() ? null : s;
    }

    public void undoStroke() {
        undo();
    }

    /** Steps {@link #undo} can still take. */
    public int undoDepth() {
        return undoStack.size();
    }

    /** Steps {@link #redo} can still take. */
    public int redoDepth() {
        return redoStack.size();
    }

    public void undo() {
        activeStroke = null;
        eraseX = eraseY = Float.NaN;
        if (undoStack.isEmpty()) return;
        ContentSnap prev = undoStack.removeLast();
        if (prev.full) {
            redoStack.addLast(captureContent());
            restoreContent(prev);
        } else {
            redoStack.addLast(snapshotSelectionFrom(prev));
            restoreGeometry(prev);
            notifyContentChanged();
        }
        historyChanged();
    }

    public void redo() {
        activeStroke = null;
        eraseX = eraseY = Float.NaN;
        if (redoStack.isEmpty()) return;
        ContentSnap next = redoStack.removeLast();
        if (next.full) {
            undoStack.addLast(captureContent());
            restoreContent(next);
        } else {
            undoStack.addLast(snapshotSelectionFrom(next));
            restoreGeometry(next);
            notifyContentChanged();
        }
        historyChanged();
    }

    private boolean historyReported;
    private boolean reportedCanUndo;
    private boolean reportedCanRedo;

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Tell the host when undo or redo becomes (un)available — not on every step. */
    private void historyChanged() {
        boolean u = !undoStack.isEmpty();
        boolean r = !redoStack.isEmpty();
        if (historyReported && u == reportedCanUndo && r == reportedCanRedo) return;
        historyReported = true;
        reportedCanUndo = u;
        reportedCanRedo = r;
        if (listener != null) listener.onHistoryChanged(u, r);
    }

    private ContentSnap snapshotSelectionFrom(ContentSnap keys) {
        ContentSnap snap = new ContentSnap();
        snap.full = false;
        for (StrokeSnap st : keys.strokes) snap.strokes.add(new StrokeSnap(st.stroke));
        for (ImgSnap im : keys.images) snap.images.add(new ImgSnap(im.img));
        for (TextFieldSnap tf : keys.textFields) snap.textFields.add(new TextFieldSnap(tf.field));
        return snap;
    }

    private void recordUndoPoint() {
        undoStack.addLast(captureContent());
        redoStack.clear();
        historyChanged();
        while (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
    }

    private void markSceneDirty() {
        if (selGesture != SelGesture.NONE) return;
        // Keep sceneBackdropReady so pan/drag can still freeze/blit stale pixels.
        // Clearing ready forced a full-world redraw on the next gesture start (= hitch).
        sceneBackdropDirty = true;
        navFreezeFullDirty = true;
        resetBands();
        // Any rebuild already drawing is now of stale content — discard its result.
        navRebuildGen++;
        if (navSnapshotActive) {
            // Content changed mid-nav — drop the snapshot and resume full redraws.
            cancelNavRefreshCallbacks();
            navSnapshotActive = false;
        }
    }

    private void cancelNavRefreshCallbacks() {
        removeCallbacks(freezeNavigationRunnable);
        removeCallbacks(warmBackdropRunnable);
    }

    /** Freeze the current scene backdrop for pan/zoom blitting (no pixel copy). */
    private void captureNavFromBackdrop() {
        if (sceneBackdrop == null || sceneBackdrop.isRecycled()) return;
        navStartViewMatrix.set(backdropMatrix);
        navSnapshotMatrix.reset();
        backdropMatrix.getValues(matrixValues);
        navStartTransX = matrixValues[Matrix.MTRANS_X];
        navStartTransY = matrixValues[Matrix.MTRANS_Y];
        float scale = matrixValues[Matrix.MSCALE_X];
        navStartScale = scale > 0.01f ? scale : 1f;
    }

    /** Rebuild scene backdrop at current viewMatrix and freeze it for navigation. */
    private void refreshNavSnapshot() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        rebuildSceneBackdropNow(backdropSkipSelected());
        if (sceneBackdrop == null) return;
        captureNavFromBackdrop();
        updateNavSnapshotTransform();
    }

    /**
     * When the freeze drifts toward the overscan edge, rebuild on a background thread.
     * The UI keeps blitting the old freeze until the new one is ready (no hitch).
     */
    private void maybeSlideNavSnapshot() {
        if (!navSnapshotActive || sceneBackdrop == null || sceneBackdrop.isRecycled()) return;
        // A plain scroll is carried by the bands; the freeze only follows zooms.
        if (document.isOpen() && !pinchScaling) return;
        if (navRebuildInFlight || navRebuildBusy) return;
        viewMatrix.getValues(matrixValues);
        float dx = matrixValues[Matrix.MTRANS_X] - navStartTransX;
        float dy = matrixValues[Matrix.MTRANS_Y] - navStartTransY;
        float scale = cachedViewScale > 0.01f ? cachedViewScale : 1f;
        float scaleRatio = scale / Math.max(navStartScale, 0.01f);
        boolean zoomOut = scaleRatio > 1.22f || scaleRatio < 0.82f;
        float triggerX = overscanX * NAV_SLIDE_FRAC;
        float triggerY = overscanY * NAV_SLIDE_FRAC;
        if (!zoomOut && Math.abs(dx) < triggerX && Math.abs(dy) < triggerY) return;
        requestNavRebuildAsync();
    }

    private void trackNavVelocity(float tx, float ty) {
        long now = android.os.SystemClock.uptimeMillis();
        long dt = now - navLastMoveMs;
        if (navLastMoveMs > 0 && dt > 0 && dt < 100) {
            float vx = (tx - navLastTransX) / dt;
            float vy = (ty - navLastTransY) / dt;
            navVelX += (vx - navVelX) * 0.4f;
            navVelY += (vy - navVelY) * 0.4f;
        } else if (dt >= 100) {
            navVelX = 0f;
            navVelY = 0f;
        }
        navLastTransX = tx;
        navLastTransY = ty;
        navLastMoveMs = now;
    }

    private void resetNavVelocity() {
        navVelX = 0f;
        navVelY = 0f;
        navLastMoveMs = 0L;
    }

    private void beginNavigation() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureSceneBackdrop(w, h);
        if (sceneBackdrop == null || sceneBackdrop.isRecycled()) return;
        // Already frozen for this gesture — keep the same blit (e.g. pan → fling handoff).
        if (navSnapshotActive) return;
        resetNavVelocity();
        // Prefer freezing the current pixels even when dirty. A sync full rebuild
        // here was the hitch after draw→scroll (pageReady had marked dirty).
        if (sceneBackdropReady && backdropMatches(w, h)) {
            captureNavFromBackdrop();
            updateNavSnapshotTransform();
        } else {
            refreshNavSnapshot();
        }
        navSnapshotActive = true;
    }

    private void endNavigation() {
        removeCallbacks(freezeNavigationRunnable);
        // Invalidate in-flight background rebuilds so they don't swap onto a settled scene.
        navRebuildGen++;
        navRebuildInFlight = false;
        boolean moved = !viewMatrix.equals(navStartViewMatrix);
        boolean willSnap = document.isOpen()
                && !appendPageRequested && !prependPageRequested
                && (overscrollCharge > 0.01f
                || documentPastRestBottom() > 0.5f
                || documentPastRestTop() > 0.5f);
        // Keep the freeze alive through overscroll snap so ink is not redrawn every frame.
        if (!willSnap) {
            navSnapshotActive = false;
            // Rest on whole pixels: the settled redraw can then shift the last freeze
            // and fill in a strip instead of redrawing everything (~160ms with a page
            // of handwriting, which held up the next fling).
            if (document.isOpen()) {
                camScrollX = Math.round(camScrollX);
                camScrollY = Math.round(camScrollY);
                applyCamera();
            }
        }
        if (document.isOpen()) {
            if (!willSnap) {
                sceneBackdropDirty = true;
                post(warmBackdropRunnable);
            }
            notifySelectionLayout();
            if (willSnap) {
                startOverscrollSnapBack();
            } else {
                postDelayed(() -> {
                    appendPageRequested = false;
                    prependPageRequested = false;
                }, 500);
            }
            invalidate();
            return;
        }
        navSnapshotActive = false;
        if (!moved) return;
        // Mark dirty but keep ready so the next pan can freeze immediately.
        sceneBackdropDirty = true;
        post(warmBackdropRunnable);
        notifySelectionLayout();
    }

    private void requestNavRebuildAsync() {
        if (navRebuildInFlight || navRebuildBusy) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || sceneBackdrop == null || sceneBackdrop.isRecycled()) return;

        navRebuildInFlight = true;
        final int gen = ++navRebuildGen;
        final Matrix freezeMatrix = new Matrix(viewMatrix);
        if (navSnapshotActive && !pinchScaling) {
            // Draw the freeze where the view is heading, not where it was: by the time
            // the ~60ms redraw lands the camera has moved on, and centering it on the
            // old position spent half the margin behind the scroll.
            // A held finger reports no moves; stale velocity would lead a still camera
            // and re-trigger this rebuild forever.
            boolean moving = android.os.SystemClock.uptimeMillis() - navLastMoveMs < 50L;
            if (moving) {
                float leadX = clampLead(navVelX * NAV_LEAD_MS, overscanX);
                float leadY = clampLead(navVelY * NAV_LEAD_MS, overscanY);
                freezeMatrix.postTranslate(leadX, leadY);
            }
        }
        final boolean skip = backdropSkipSelected();
        navRebuildSkipSelected = skip;
        final int bgColor = sceneBgColor;
        final int ox = overscanX;
        final int oy = overscanY;
        final int bw = sceneBackdrop.getWidth();
        final int bh = sceneBackdrop.getHeight();

        // Mid-scroll the scene only moved: shift the current freeze by whole pixels and
        // draw just the newly exposed strip (plus pages whose pixels landed). Redrawing
        // the whole 4096px overscan took 120-250ms with a page of handwriting, far too
        // slow to keep ahead of a fling.
        Bitmap src = null;
        int shiftX = 0;
        int shiftY = 0;
        if (!pinchScaling && !navFreezeFullDirty
                && sceneBackdropReady && sceneBackdropOmitsSelection == skip) {
            backdropMatrix.getValues(navSrcVals);
            freezeMatrix.getValues(navDstVals);
            boolean sameScale = navSrcVals[Matrix.MSCALE_X] == navDstVals[Matrix.MSCALE_X]
                    && navSrcVals[Matrix.MSCALE_Y] == navDstVals[Matrix.MSCALE_Y]
                    && navSrcVals[Matrix.MSKEW_X] == navDstVals[Matrix.MSKEW_X]
                    && navSrcVals[Matrix.MSKEW_Y] == navDstVals[Matrix.MSKEW_Y];
            if (sameScale) {
                float fdx = navDstVals[Matrix.MTRANS_X] - navSrcVals[Matrix.MTRANS_X];
                float fdy = navDstVals[Matrix.MTRANS_Y] - navSrcVals[Matrix.MTRANS_Y];
                shiftX = Math.round(fdx);
                shiftY = Math.round(fdy);
                // Settled, the result must sit exactly at the camera (ink is drawn into
                // it in place), so only a whole-pixel move qualifies. Mid-gesture the
                // freeze may land anywhere near the camera.
                boolean exact = Math.abs(fdx - shiftX) < 0.01f && Math.abs(fdy - shiftY) < 0.01f;
                if ((navSnapshotActive || exact)
                        && Math.abs(shiftX) < bw / 2 && Math.abs(shiftY) < bh / 2) {
                    if (navSnapshotActive) {
                        // Land exactly on whole pixels so the copied area lines up.
                        navSrcVals[Matrix.MTRANS_X] += shiftX;
                        navSrcVals[Matrix.MTRANS_Y] += shiftY;
                        freezeMatrix.setValues(navSrcVals);
                    }
                    src = sceneBackdrop;
                }
            }
        }
        if (src == null) navFreezeFullDirty = false;
        // A full redraw covers every page anyway; drain either way.
        final List<RectF> landed = new ArrayList<>(freezeLandedPending);
        freezeLandedPending.clear();
        if (document.isOpen()) {
            List<RectF> fresh = document.drainLandedPages();
            landed.addAll(fresh);
            refreshBandsForPages(fresh);
        }
        final Bitmap source = src;
        final int sx = shiftX;
        final int sy = shiftY;
        navRebuildSource = source;
        final boolean wantHw = true;

        freezeMatrix.getValues(matrixValues);
        final float freezeTransX = matrixValues[Matrix.MTRANS_X];
        final float freezeTransY = matrixValues[Matrix.MTRANS_Y];
        final float freezeScale = cachedViewScale > 0.01f ? cachedViewScale : 1f;
        // Build at the displayed depth. Swapping an RGB_565 buffer into an ARGB_8888
        // backdrop made ensureSceneBackdrop throw the whole thing away on the next
        // settled frame and redraw the world — a visible reload after every gesture.
        final Bitmap.Config cfg = sceneBackdrop.getConfig() != null
                ? sceneBackdrop.getConfig()
                : Bitmap.Config.ARGB_8888;
        snapshotSceneInto(navKit, skip);
        for (int i = 1; i < NAV_BANDS; i++) shareSceneKit(navBandKits[i], navKit);

        navRebuildBusy = true;
        try {
            submitNavRebuild(gen, bw, bh, bgColor, ox, oy, w, h,
                    freezeMatrix, freezeTransX, freezeTransY, freezeScale, cfg,
                    source, sx, sy, landed, wantHw);
        } catch (RuntimeException rejected) {
            navRebuildBusy = false;
            navRebuildInFlight = false;
            navRebuildSource = null;
            navFreezeFullDirty = true;
        }
    }

    private static float clampLead(float lead, int margin) {
        float max = margin * NAV_LEAD_MAX_FRAC;
        return Math.max(-max, Math.min(max, lead));
    }

    private void submitNavRebuild(int gen, int bw, int bh, int bgColor, int ox, int oy,
                                  int w, int h, Matrix freezeMatrix,
                                  float freezeTransX, float freezeTransY, float freezeScale,
                                  Bitmap.Config cfg, Bitmap source, int sx, int sy,
                                  List<RectF> landed, boolean wantHw) {
        navRebuildExecutor.execute(() -> {
            Bitmap built = null;
            try {
                built = ensureNavBuildBitmap(bw, bh, cfg);
                navBuildCanvas.setBitmap(built);
                if (source != null && !source.isRecycled()) {
                    navBuildCanvas.drawBitmap(source, sx, sy, null);
                    List<RectF> regions = new ArrayList<>();
                    if (sy > 0) regions.add(new RectF(0, 0, bw, sy));
                    if (sy < 0) regions.add(new RectF(0, bh + sy, bw, bh));
                    if (sx > 0) regions.add(new RectF(0, 0, sx, bh));
                    if (sx < 0) regions.add(new RectF(bw + sx, 0, bw, bh));
                    for (RectF page : landed) {
                        RectF r = new RectF(page);
                        freezeMatrix.mapRect(r);
                        r.offset(ox, oy);
                        r.inset(-2f, -2f);
                        if (r.intersect(0, 0, bw, bh)) regions.add(r);
                    }
                    navBuildCanvas.setBitmap(null);
                    drawRegionsParallel(built, regions, bgColor, freezeMatrix, freezeScale, ox, oy);
                } else {
                    navBuildCanvas.setBitmap(null);
                    List<RectF> all = new ArrayList<>();
                    all.add(new RectF(0, 0, bw, bh));
                    drawRegionsParallel(built, all, bgColor, freezeMatrix, freezeScale, ox, oy);
                }
                final Bitmap result = built;
                built = null;
                // Upload here, off the render thread.
                Bitmap hwCopy = null;
                if (wantHw) {
                    try {
                        hwCopy = result.copy(Bitmap.Config.HARDWARE, false);
                    } catch (Throwable ignored) {
                    }
                }
                final Bitmap hw = hwCopy;
                post(() -> applyNavRebuildResult(
                        gen, result, hw, freezeMatrix, freezeTransX, freezeTransY, freezeScale));
            } catch (Throwable t) {
                if (built != null && built != navBuildBitmap) {
                    try { built.recycle(); } catch (Exception ignored) {}
                }
                post(() -> {
                    navRebuildInFlight = false;
                    navFreezeFullDirty = true;
                });
            } finally {
                navRebuildSource = null;
                navRebuildBusy = false;
            }
        });
    }

    /**
     * Copies the theme paints and the current object lists into a kit, on the UI
     * thread, so the rebuild thread never reads live canvas state while it draws.
     */
    private void snapshotSceneInto(SceneKit kit, boolean skipSelected) {
        kit.stroke.set(strokePaint);
        kit.paper.set(paperPaint);
        kit.grid.set(gridPaint);
        kit.title.set(titlePaint);
        kit.image.set(imagePaint);
        kit.strokes = new ArrayList<>(inkStrokes);
        kit.texts = new ArrayList<>(textFields);
        kit.imgs = new ArrayList<>(images);
        kit.skipStrokes.clear();
        kit.skipTexts.clear();
        kit.skipImages.clear();
        if (skipSelected) {
            kit.skipStrokes.addAll(selectedStrokes);
            kit.skipTexts.addAll(selectedTextFields);
            kit.skipImages.addAll(selectedImages);
        }
        kit.skipEditing = editingTextField;
    }

    /** Band kits read the same snapshot lists; paints and scratch state are their own. */
    private static void shareSceneKit(SceneKit dst, SceneKit src) {
        dst.stroke.set(src.stroke);
        dst.paper.set(src.paper);
        dst.grid.set(src.grid);
        dst.title.set(src.title);
        dst.image.set(src.image);
        dst.strokes = src.strokes;
        dst.texts = src.texts;
        dst.imgs = src.imgs;
        dst.skipStrokes.clear();
        dst.skipStrokes.addAll(src.skipStrokes);
        dst.skipTexts.clear();
        dst.skipTexts.addAll(src.skipTexts);
        dst.skipImages.clear();
        dst.skipImages.addAll(src.skipImages);
        dst.skipEditing = src.skipEditing;
    }

    /**
     * Draws the scene into {@code regions} (bitmap space) of {@code target}, each split
     * into horizontal bands spread over {@link #NAV_BANDS} threads. Bands have whole-
     * pixel edges and never overlap, so the threads write disjoint pixels.
     */
    private void drawRegionsParallel(Bitmap target, List<RectF> regions, int bgColor,
                                     Matrix worldMatrix, float scale, int ox, int oy)
            throws Exception {
        List<android.graphics.Rect> bands = new ArrayList<>();
        for (RectF rf : regions) {
            android.graphics.Rect r = new android.graphics.Rect();
            rf.roundOut(r);
            if (!r.intersect(0, 0, target.getWidth(), target.getHeight())) continue;
            int k = r.height() >= 192 ? NAV_BANDS : 1;
            for (int j = 0; j < k; j++) {
                int top = r.top + Math.round(r.height() * (j / (float) k));
                int bottom = j == k - 1 ? r.bottom : r.top + Math.round(r.height() * ((j + 1) / (float) k));
                if (bottom > top) bands.add(new android.graphics.Rect(r.left, top, r.right, bottom));
            }
        }
        java.util.List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
        for (int w = 1; w < NAV_BANDS; w++) {
            final int worker = w;
            pending.add(navBandPool.submit(() -> {
                drawBands(worker, target, bands, bgColor, worldMatrix, scale, ox, oy);
                return null;
            }));
        }
        Throwable failure = null;
        try {
            drawBands(0, target, bands, bgColor, worldMatrix, scale, ox, oy);
        } catch (Throwable t) {
            failure = t;
        }
        // Always wait: the target must not be handed on while a band still draws.
        for (java.util.concurrent.Future<?> f : pending) {
            try {
                f.get();
            } catch (Throwable t) {
                if (failure == null) failure = t;
            }
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure != null) throw new RuntimeException(failure);
    }

    private void drawBands(int worker, Bitmap target, List<android.graphics.Rect> bands,
                           int bgColor, Matrix worldMatrix, float scale, int ox, int oy) {
        Canvas c = navBandCanvases[worker];
        SceneKit kit = navBandKits[worker];
        c.setBitmap(target);
        try {
            for (int i = worker; i < bands.size(); i += NAV_BANDS) {
                android.graphics.Rect r = bands.get(i);
                c.save();
                c.clipRect(r);
                c.drawColor(bgColor);
                drawSceneRegionAt(c, kit, worldMatrix, scale, ox, oy, new RectF(r));
                c.restore();
            }
        } finally {
            c.setBitmap(null);
        }
    }

    private Bitmap ensureNavBuildBitmap(int bw, int bh, Bitmap.Config cfg) {
        if (navBuildBitmap != null
                && !navBuildBitmap.isRecycled()
                && navBuildBitmap.getWidth() == bw
                && navBuildBitmap.getHeight() == bh
                && navBuildBitmap.getConfig() == cfg) {
            return navBuildBitmap;
        }
        if (navBuildBitmap != null && !navBuildBitmap.isRecycled()) {
            navBuildBitmap.recycle();
        }
        navBuildBitmap = Bitmap.createBitmap(bw, bh, cfg);
        return navBuildBitmap;
    }

    private void applyNavRebuildResult(int gen, Bitmap result, Bitmap hw, Matrix freezeMatrix,
                                       float freezeTransX, float freezeTransY, float freezeScale) {
        if (gen != navRebuildGen || result == null || result.isRecycled()) {
            if (hw != null) hw.recycle();
            navRebuildInFlight = false;
            // Its drained page updates are lost with it.
            navFreezeFullDirty = true;
            invalidate();
            return;
        }
        // Swap the build buffer in whether or not a gesture is running: backdropMatrix
        // records which camera these pixels belong to, and every blit goes through it.
        Bitmap old = sceneBackdrop;
        sceneBackdrop = result;
        sceneBackdropCanvas.setBitmap(sceneBackdrop);
        backdropMatrix.set(freezeMatrix);
        if (result == navBuildBitmap) {
            navBuildBitmap = old;
        } else if (old != null && old != result && !old.isRecycled()) {
            if (navBuildBitmap != null && navBuildBitmap != old && !navBuildBitmap.isRecycled()) {
                navBuildBitmap.recycle();
            }
            navBuildBitmap = old;
        }
        sceneBackdropDirty = false;
        sceneBackdropReady = true;
        sceneBackdropOmitsSelection = navRebuildSkipSelected;
        // Requested after the deselect (it bumps the generation), so it has them.
        clearDeselectedPending();
        releaseBackdropHw();
        if (hw != null) {
            sceneBackdropHw = hw;
            sceneBackdropHwFor = result;
        }
        if (navSnapshotActive) {
            navStartViewMatrix.set(freezeMatrix);
            navStartTransX = freezeTransX;
            navStartTransY = freezeTransY;
            navStartScale = freezeScale;
            updateNavSnapshotTransform();
        }
        navRebuildInFlight = false;
        // The background path owns the spare buffer now, so the sync rebuild's own
        // spare is dead weight — a third full-screen ARGB bitmap. Let it go; the rare
        // sync rebuild allocates one again if it ever runs.
        releaseSceneBackdropBack();
        // Pages that landed meanwhile, or still past the margin? Go again.
        if (navSnapshotActive && pinchScaling) {
            if (document.isOpen() && document.hasLandedPages()) requestNavRebuildAsync();
            else maybeSlideNavSnapshot();
        }
        invalidate();
    }

    /** A PDF page finished rendering off-thread — fold the pixels into the scene. */
    /** A PDF page finished rendering off-thread — fold the pixels into the scene. */
    private void onDocumentPageReady() {
        if (!document.isOpen()) return;
        // Don't kill the ink fast path mid-stroke — defer until the tip lifts.
        if (activeStroke != null || !Float.isNaN(eraseX)) {
            pendingPdfReady = true;
            return;
        }
        // Tile atlas lands many cells in a burst. Each used to mark dirty + invalidate,
        // and onDraw then rebuilt the entire overscan on the UI thread → ~0.5 fps.
        scheduleCoalescedPdfReady();
    }

    private void scheduleCoalescedPdfReady() {
        pendingPdfReady = true;
        if (pdfReadyCoalescePosted) return;
        pdfReadyCoalescePosted = true;
        postDelayed(coalescePdfReadyRunnable, PDF_READY_COALESCE_MS);
    }

    private void flushCoalescedPdfReady() {
        pdfReadyCoalescePosted = false;
        if (!pendingPdfReady) return;
        applyDocumentPageReady();
    }

    private void applyDocumentPageReady() {
        fireSceneChanged(true);
        pendingPdfReady = false;
        sceneBackdropDirty = true;
        if (navSnapshotActive && !pinchScaling) {
            // Mid-scroll: redraw just the bands those pages cover. The freeze gets
            // the same pages when it is next redrawn.
            List<RectF> pages = document.drainLandedPages();
            freezeLandedPending.addAll(pages);
            refreshBandsForPages(pages);
            invalidate();
            return;
        }
        if (navSnapshotActive) {
            // Mid-gesture: refresh the freeze in the background rather than
            // dropping it, so the pan keeps blitting without a hitch.
            requestNavRebuildAsync();
            return;
        }
        if (!isNavigating()) {
            removeCallbacks(warmBackdropRunnable);
            post(warmBackdropRunnable);
        }
        // Do not invalidate here: warm rebuild invalidates when done. A dirty
        // invalidate forced onDraw to sync-rebuild the whole scene every tile.
    }

    private void flushPendingPdfReady() {
        removeCallbacks(coalescePdfReadyRunnable);
        pdfReadyCoalescePosted = false;
        if (pendingPdfReady) applyDocumentPageReady();
    }

    private boolean backdropSkipSelected() {
        // Keep selected items out of the backdrop whenever a selection exists so
        // drag can start without punching a hole (punch caused flicker + erased neighbors).
        return hasSelection();
    }

    /** Unused — kept so removeCallbacks sites compile if a prior freeze was posted. */
    private final Runnable freezeNavigationRunnable = new Runnable() {
        @Override
        public void run() {
            // Intentionally empty: never rebuild the scene mid-gesture (that was the hitch).
        }
    };

    /**
     * Keeps the PDF page cache in step with the zoom. While a gesture runs the cache
     * is told to hold what it has — otherwise a pinch queues a re-rasterisation of
     * every visible page on every frame of the gesture.
     */
    private void syncPageRenderScale() {
        if (!document.isOpen()) return;
        boolean settled = !isNavigating() && selGesture == SelGesture.NONE;
        document.setRenderScale(viewScale(), settled);
        if (settled && getWidth() > 0 && getHeight() > 0) {
            ensureInverse();
            detailPts[0] = 0f;
            detailPts[1] = 0f;
            detailPts[2] = getWidth();
            detailPts[3] = getHeight();
            inverse.mapPoints(detailPts);
            detailVisible.set(
                    Math.min(detailPts[0], detailPts[2]), Math.min(detailPts[1], detailPts[3]),
                    Math.max(detailPts[0], detailPts[2]), Math.max(detailPts[1], detailPts[3]));
            document.updateDetail(detailVisible, viewScale(), true);
        }
    }

    private final float[] detailPts = new float[4];
    private final RectF detailVisible = new RectF();

    // ---- PDF text selection hooks (the overlay lives in MainActivity's canvas pane) ----

    public boolean supportsPdfTextSelection() {
        return document.isOpen() && DocumentPages.supportsTextSelection();
    }

    /** Text between two world points on the page under the first; null if none. */
    public DocumentPages.TextSelection selectPdfText(float wx0, float wy0, float wx1, float wy1) {
        if (!supportsPdfTextSelection()) return null;
        int page = document.pageIndexAt(wy0);
        RectF pb = document.pageBounds(page);
        if (!pb.contains(wx0, wy0)) return null;
        // Keep the far end on the same page — selections do not span pages.
        float cx = Math.max(pb.left, Math.min(pb.right, wx1));
        float cy = Math.max(pb.top, Math.min(pb.bottom, wy1));
        return document.selectText(page, wx0, wy0, cx, cy);
    }

    /** World → this view's coordinates (a fresh array). */
    public float[] screenFromWorld(float wx, float wy) {
        float[] p = {wx, wy};
        viewMatrix.mapPoints(p);
        return p;
    }

    /** Idle rebuild after pan/zoom — runs off the gesture start path. */
    private final Runnable warmBackdropRunnable = this::runWarmBackdrop;

    private void runWarmBackdrop() {
        if (navigating || pinchScaling || panFlinging || overscrollSnapping
                || selGesture != SelGesture.NONE || navSnapshotActive) {
            // Still mid-gesture — try again shortly so a cancelled settle still upgrades.
            if (sceneBackdropDirty && document.isOpen()) {
                postDelayed(warmBackdropRunnable, 120L);
            }
            return;
        }
        if (!sceneBackdropDirty) return;
        // Settled path: this is where a zoom change earns its sharper rasterisation.
        syncPageRenderScale();
        int bw = getWidth();
        int bh = getHeight();
        if (bw <= 0 || bh <= 0) return;
        if (navRebuildInFlight || navRebuildBusy) {
            postDelayed(warmBackdropRunnable, 60L);
            return;
        }
        if (sceneBackdropReady && backdropMatches(bw, bh)
                && sceneBackdrop != null && !sceneBackdrop.isRecycled()) {
            // Something showable is already up. Redrawing the world here held the UI
            // thread for ~230ms at the end of every scroll; off-thread, the existing
            // pixels keep being blitted (through their own camera) until the sharp
            // ones are ready.
            requestNavRebuildAsync();
            invalidate();
            return;
        }
        rebuildSceneBackdropNow(backdropSkipSelected());
        invalidate();
    }

    /** True while a finger pan/pinch is transforming the viewport. */
    public boolean isNavigating() {
        return navigating || pinchScaling || panFlinging || overscrollSnapping;
    }

    private void setNavigating(boolean value) {
        if (navigating == value) return;
        navigating = value;
        if (listener != null) {
            listener.onNavigationChanged(value || pinchScaling || panFlinging || overscrollSnapping);
        }
    }

    /**
     * End any pan/fling still in flight so the pen draws live.
     *
     * <p>Flick to scroll, then start writing before it settles: the canvas was still
     * in navigation, so ink took the frozen-snapshot path, which does not draw the
     * active stroke and blits the freeze through its own matrix. The stroke stayed
     * invisible until it was committed, on a page drawn a moment behind the camera.
     */
    private void settleNavigationForInk() {
        boolean was = navigating || navSnapshotActive || panFlinging
                || !panScroller.isFinished() || overscrollSnapping;
        if (!was) return;
        if (overscrollSnapping) cancelOverscrollSnap();
        stopPanFling();
        setNavigating(false);
        endNavigation();
        invalidate();
    }

    private void stopPanFling() {
        if (panFlinging || !panScroller.isFinished()) {
            // Commit the scroller's current offset before killing it.
            camScrollX = panScroller.getCurrX();
            camScrollY = panScroller.getCurrY();
            applyCamera();
        }
        panFlinging = false;
        panScroller.forceFinished(true);
    }

    /** Stop coasting and settle the scene (no follow-up finger gesture). */
    private void abortPanFling() {
        boolean wasFlinging = panFlinging || !panScroller.isFinished();
        stopPanFling();
        if (wasFlinging && !fingerPanArmed && !pinchScaling) {
            setNavigating(false);
            endNavigation();
        }
    }

    private void startPanFling(float velocityX, float velocityY) {
        if (documentFitsHorizontally()) velocityX = 0f;
        // ScrollView convention: positive fling velocity scrolls content up/left.
        int vx = Math.round(-velocityX);
        int vy = Math.round(-velocityY);
        if (Math.abs(vx) < minFlingVelocity && Math.abs(vy) < minFlingVelocity) {
            setNavigating(false);
            endNavigation();
            return;
        }
        overscrollCharge = 0f;
        beginNavigation();
        setNavigating(true);
        panFlinging = true;

        float[] range = new float[4];
        scrollRange(range);
        // Hard bounds — fling cannot enter add-page overscroll.
        panScroller.fling(
                Math.round(camScrollX), Math.round(camScrollY),
                vx, vy,
                Math.round(range[0]), Math.round(range[1]),
                Math.round(range[2]), Math.round(range[3]));
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (!panFlinging) {
            if (!panScroller.isFinished()) panScroller.forceFinished(true);
            return;
        }
        if (!panScroller.computeScrollOffset()) {
            panFlinging = false;
            camScrollX = panScroller.getCurrX();
            camScrollY = panScroller.getCurrY();
            applyCamera();
            setNavigating(false);
            endNavigation();
            invalidate();
            return;
        }
        camScrollX = panScroller.getCurrX();
        camScrollY = panScroller.getCurrY();
        applyCamera();
        invalidate();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == oldw && h == oldh) return;
        // The in-flight snapshot was captured at the old overscan size; keeping it would
        // draw offset against the new margin. Drop it and redraw from the world.
        cancelNavRefreshCallbacks();
        navSnapshotActive = false;
        updateOverscanMetrics(w, h);
        sceneBackdropDirty = true;
        sceneBackdropReady = false;
        if (document.isOpen()) {
            if (oldw == 0 && w > 0) {
                // First real size. Fitting here would undo a camera restored from the
                // session before the view had been measured.
                if (!cameraRestored) fitDocumentInView();
            } else if (w > 0 && w != oldw) {
                // Side panels changed the content width. A page that fits re-centres in
                // the new strip; zoomed in past the page width there is nothing to
                // centre, so the scroll is only clamped. It used to be assigned
                // range[0] first and clamped after, which is the same as always
                // slamming to the left edge — that was the jump when a panel opened
                // at high zoom.
                float[] range = new float[4];
                scrollRange(range);
                if (documentFitsHorizontally()) {
                    camScrollX = range[0];
                } else {
                    if (camScrollX < range[0]) camScrollX = range[0];
                    if (camScrollX > range[1]) camScrollX = range[1];
                }
                applyCamera();
                invalidate();
            } else if (h > 0 && h != oldh) {
                invalidate();
            }
        }
    }

    private void updateNavSnapshotTransform() {
        if (!navStartViewMatrix.invert(navInverseStart)) {
            navSnapshotMatrix.reset();
            return;
        }
        // Map pixels captured under startMatrix into current screen space:
        // screen_new = current * inv(start) * screen_old
        // The bitmap's (0,0) is the overscan corner, so shift it back to the screen
        // position it was captured at before applying that transform.
        navSnapshotMatrix.setConcat(viewMatrix, navInverseStart);
        navSnapshotMatrix.preTranslate(-overscanX, -overscanY);
    }

    private void ensureSelDragBitmap(int w, int h) {
        if (selDragBitmap != null
                && selDragBitmap.getWidth() == w
                && selDragBitmap.getHeight() == h
                && !selDragBitmap.isRecycled()) {
            return;
        }
        if (selDragBitmap != null && !selDragBitmap.isRecycled()) {
            selDragBitmap.recycle();
        }
        selDragBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    private void captureSelDragBitmap() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            selDragBitmapReady = false;
            return;
        }
        ensureSelDragBitmap(w, h);
        if (selDragBitmap == null) {
            selDragBitmapReady = false;
            return;
        }
        selDragBitmap.eraseColor(0);
        selDragCanvas.setBitmap(selDragBitmap);
        selDragCanvas.save();
        selDragCanvas.concat(viewMatrix);
        drawSelectedContent(selDragCanvas, viewScale());
        selDragCanvas.restore();
        selDragScreenDx = 0f;
        selDragScreenDy = 0f;
        selDragBitmapReady = true;
    }

    private void mapWorldRectToScreen(RectF world, RectF outScreen) {
        selDragMapPts[0] = world.left;
        selDragMapPts[1] = world.top;
        selDragMapPts[2] = world.right;
        selDragMapPts[3] = world.top;
        selDragMapPts[4] = world.right;
        selDragMapPts[5] = world.bottom;
        selDragMapPts[6] = world.left;
        selDragMapPts[7] = world.bottom;
        viewMatrix.mapPoints(selDragMapPts);
        float minX = selDragMapPts[0], minY = selDragMapPts[1];
        float maxX = selDragMapPts[0], maxY = selDragMapPts[1];
        for (int i = 2; i < 8; i += 2) {
            minX = Math.min(minX, selDragMapPts[i]);
            minY = Math.min(minY, selDragMapPts[i + 1]);
            maxX = Math.max(maxX, selDragMapPts[i]);
            maxY = Math.max(maxY, selDragMapPts[i + 1]);
        }
        outScreen.set(minX, minY, maxX, maxY);
    }

    private void releaseSelDragBitmap() {
        selDragBitmapReady = false;
        selDragScreenDx = 0f;
        selDragScreenDy = 0f;
    }

    private void ensureSelectionBackdrop(boolean force) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureSceneBackdrop(w, h);
        if (sceneBackdrop == null) return;
        if (!force && sceneBackdropReady && !sceneBackdropDirty && sceneBackdropOmitsSelection) {
            return;
        }
        rebuildSceneBackdropNow(/*skipSelected*/ true);
    }

    private void drawSelDragOverlay(Canvas canvas) {
        if (!selDragBitmapReady || selDragBitmap == null) return;
        canvas.drawBitmap(selDragBitmap, selDragScreenDx, selDragScreenDy, selDragPaint);
    }

    private static final class ContentSnap {
        final List<StrokeSnap> strokes = new ArrayList<>();
        final List<ImgSnap> images = new ArrayList<>();
        final List<TextFieldSnap> textFields = new ArrayList<>();
        boolean full = true;
    }


    private static final class StrokeSnap {
        final Stroke stroke;
        final float[] xs, ys, ws;
        final int color;
        final String colorName;
        final boolean presentHidden;

        StrokeSnap(Stroke s, float[] xs, float[] ys, float[] ws, int color, String colorName,
                   boolean presentHidden) {
            stroke = s;
            this.xs = xs;
            this.ys = ys;
            this.ws = ws;
            this.color = color;
            this.colorName = colorName;
            this.presentHidden = presentHidden;
        }

        StrokeSnap(Stroke s) {
            stroke = s;
            color = s.color;
            colorName = s.colorName;
            presentHidden = s.presentHidden;
            int n = s.samples.size();
            xs = new float[n];
            ys = new float[n];
            ws = new float[n];
            for (int i = 0; i < n; i++) {
                Sample p = s.samples.get(i);
                xs[i] = p.x;
                ys[i] = p.y;
                ws[i] = p.width;
            }
        }

        void restore() {
            stroke.color = color;
            stroke.colorName = colorName;
            stroke.presentHidden = presentHidden;
            // Grow or shrink sample list to match snapshot length.
            while (stroke.samples.size() > xs.length) {
                stroke.samples.remove(stroke.samples.size() - 1);
            }
            for (int i = 0; i < xs.length; i++) {
                if (i < stroke.samples.size()) {
                    Sample p = stroke.samples.get(i);
                    p.x = xs[i];
                    p.y = ys[i];
                    p.width = ws[i];
                } else {
                    stroke.samples.add(new Sample(xs[i], ys[i], ws[i]));
                }
            }
            stroke.recomputeBounds();
        }
    }

    private static final class ImgSnap {
        final CanvasImage img;
        final float cx, cy, w, h, rot;
        final boolean presentHidden;

        ImgSnap(CanvasImage i, float cx, float cy, float w, float h, float rot, boolean hidden) {
            img = i;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.rot = rot;
            presentHidden = hidden;
        }

        ImgSnap(CanvasImage i) {
            img = i;
            presentHidden = i.presentHidden;
            cx = i.cx;
            cy = i.cy;
            w = i.width;
            h = i.height;
            rot = i.rotationDeg;
        }

        void restore() {
            img.cx = cx;
            img.cy = cy;
            img.width = w;
            img.height = h;
            img.rotationDeg = rot;
            img.presentHidden = presentHidden;
            img.markDirty();
        }
    }

    private static final class TextFieldSnap {
        final CanvasTextField field;
        final float cx, cy, width, height;
        final boolean userSized;
        final boolean presentHidden;

        TextFieldSnap(CanvasTextField t, float cx, float cy, float width, float height,
                      boolean userSized, boolean hidden) {
            field = t;
            this.cx = cx;
            this.cy = cy;
            this.width = width;
            this.height = height;
            this.userSized = userSized;
            presentHidden = hidden;
        }

        TextFieldSnap(CanvasTextField t) {
            field = t;
            presentHidden = t.presentHidden;
            cx = t.cx;
            cy = t.cy;
            width = t.width;
            height = t.height;
            userSized = t.userSized;
        }

        void restore() {
            field.cx = cx;
            field.cy = cy;
            field.width = width;
            field.height = height;
            field.userSized = userSized;
            field.presentHidden = presentHidden;
            field.invalidateLayout();
        }
    }

    // ---- Handwriting lines, for recognition ---------------------------------------------

    /** One line (or word group) of handwriting, as plain data for the recogniser. */
    public static final class InkLine {
        /** Identifies exactly these strokes, so unchanged lines are never re-recognised. */
        public final String sig;
        /** Per stroke: its points, world units. */
        public final float[][] xs;
        public final float[][] ys;
        /** Page as the PDF numbers it, and the line's box in that page's unit square. */
        public final int filePage;
        public final float[] rect;
        public final float width;
        public final float height;

        InkLine(String sig, float[][] xs, float[][] ys, int filePage, float[] rect,
                float width, float height) {
            this.sig = sig;
            this.xs = xs;
            this.ys = ys;
            this.filePage = filePage;
            this.rect = rect;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Groups the ink into lines of writing: strokes of about the same height sitting
     * side by side on the same page. Big drawings and highlighter are left out.
     */
    public List<InkLine> collectInkLines() {
        List<InkLine> out = new ArrayList<>();
        if (!document.isOpen()) return out;
        List<Stroke> cand = new ArrayList<>();
        for (Stroke st : inkStrokes) {
            if (st.brush == BRUSH_HIGHLIGHTER || st.samples.isEmpty() || st.bounds.isEmpty()) continue;
            cand.add(st);
        }
        if (cand.isEmpty()) return out;
        float[] hs = new float[cand.size()];
        int nh = 0;
        for (Stroke st : cand) {
            float h = st.bounds.height();
            if (h > 2f) hs[nh++] = h;
        }
        if (nh == 0) return out;
        java.util.Arrays.sort(hs, 0, nh);
        final float hm = hs[nh / 2];
        // Sketches are not writing.
        cand.removeIf(st -> st.bounds.height() > 4f * hm && st.bounds.width() > 4f * hm);
        int n = cand.size();
        if (n == 0) return out;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final List<Stroke> cs = cand;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(cs.get(a).bounds.top, cs.get(b).bounds.top));
        int[] page = new int[n];
        for (int i = 0; i < n; i++) {
            RectF b = cand.get(i).bounds;
            page[i] = pageIndexAt(b.centerX(), b.centerY());
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int oi = 0; oi < n; oi++) {
            int i = order[oi];
            RectF a = cand.get(i).bounds;
            for (int oj = oi + 1; oj < n; oj++) {
                int j = order[oj];
                RectF b = cand.get(j).bounds;
                if (b.top > a.bottom + hm) break;
                if (page[i] != page[j]) continue;
                float gap = Math.max(0f, Math.max(a.left, b.left) - Math.min(a.right, b.right));
                if (gap > 1.5f * hm) continue;
                float overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                boolean sameLine = Math.abs(a.centerY() - b.centerY()) <= 0.9f * hm
                        || overlap >= 0.5f * Math.min(a.height(), b.height());
                if (sameLine) union(parent, i, j);
            }
        }
        java.util.LinkedHashMap<Integer, List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            List<Integer> g = groups.get(r);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(r, g);
            }
            g.add(i);
        }
        for (List<Integer> g : groups.values()) {
            RectF box = new RectF(cand.get(g.get(0)).bounds);
            long h = g.size();
            for (int i : g) {
                Stroke st = cand.get(i);
                box.union(st.bounds);
                try {
                    h = h * 1000003L + strokeToJson(st).optString("packed").hashCode();
                } catch (Exception ignored) {
                }
            }
            if (box.height() > 5f * hm) continue;
            int pi = page[g.get(0)];
            if (pi < 0) continue;
            RectF pb = document.pageBounds(pi);
            if (pb == null || pb.width() <= 0 || pb.height() <= 0) continue;
            // Writing order: left to right.
            g.sort((a, b) -> Float.compare(cand.get(a).bounds.left, cand.get(b).bounds.left));
            float[][] xs = new float[g.size()][];
            float[][] ys = new float[g.size()][];
            for (int k = 0; k < g.size(); k++) {
                List<Sample> pts = cand.get(g.get(k)).samples;
                xs[k] = new float[pts.size()];
                ys[k] = new float[pts.size()];
                for (int q = 0; q < pts.size(); q++) {
                    xs[k][q] = pts.get(q).x;
                    ys[k][q] = pts.get(q).y;
                }
            }
            float[] rect = {
                    (box.left - pb.left) / pb.width(), (box.top - pb.top) / pb.height(),
                    box.width() / pb.width(), box.height() / pb.height()};
            out.add(new InkLine(Long.toHexString(h), xs, ys, pi - document.blankPrefix, rect,
                    box.width(), box.height()));
        }
        return out;
    }

    private int pageIndexAt(float x, float y) {
        for (int i = 0; i < document.pageCount; i++) {
            RectF pb = document.pageBounds(i);
            if (pb != null && pb.contains(x, y)) return i;
        }
        return -1;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    // ---- Undo history kept across closing a document -----------------------------------

    /** Undo steps written to disk per document; redo steps are all kept. */
    private static final int PERSIST_UNDO = 30;

    /**
     * The undo/redo history frozen for saving. Taken on the UI thread; the snapshots
     * it holds are immutable copies, so {@link #encode} can run on any thread.
     */
    public static final class HistoryCapture {
        final String fingerprint;
        final List<ContentSnap> undo = new ArrayList<>();
        final List<ContentSnap> redo = new ArrayList<>();
        final java.util.IdentityHashMap<Stroke, Integer> strokeIds = new java.util.IdentityHashMap<>();
        final List<Integer> strokeBrush = new ArrayList<>();
        final int currentStrokes;
        final java.util.IdentityHashMap<CanvasImage, Integer> imageIds = new java.util.IdentityHashMap<>();
        /** Per image id: its JSON if it is not in the document (deleted, only in history). */
        final List<org.json.JSONObject> imageJson = new ArrayList<>();
        final int currentImages;
        final java.util.IdentityHashMap<CanvasTextField, Integer> textIds = new java.util.IdentityHashMap<>();
        final List<org.json.JSONObject> textJson = new ArrayList<>();
        final int currentTexts;

        private HistoryCapture(CodeCanvasView v) throws Exception {
            fingerprint = v.contentFingerprint();
            for (Stroke st : v.inkStrokes) strokeId(st);
            currentStrokes = strokeIds.size();
            for (CanvasImage img : v.persistedImages()) imageId(img, false);
            currentImages = imageIds.size();
            for (CanvasTextField tf : v.persistedTextFields()) textId(tf, false);
            currentTexts = textIds.size();
            int skip = Math.max(0, v.undoStack.size() - PERSIST_UNDO);
            int i = 0;
            for (ContentSnap snap : v.undoStack) {
                if (i++ >= skip) undo.add(snap);
            }
            redo.addAll(v.redoStack);
            for (List<ContentSnap> list : java.util.Arrays.asList(undo, redo)) {
                for (ContentSnap snap : list) {
                    for (StrokeSnap ss : snap.strokes) strokeId(ss.stroke);
                    for (ImgSnap is : snap.images) imageId(is.img, true);
                    for (TextFieldSnap ts : snap.textFields) textId(ts.field, true);
                }
            }
        }

        public boolean isEmpty() {
            return undo.isEmpty() && redo.isEmpty();
        }

        private void strokeId(Stroke s) {
            if (strokeIds.containsKey(s)) return;
            strokeIds.put(s, strokeIds.size());
            strokeBrush.add(s.brush);
        }

        private void imageId(CanvasImage img, boolean historyOnly) throws Exception {
            if (imageIds.containsKey(img)) return;
            imageIds.put(img, imageIds.size());
            imageJson.add(historyOnly ? img.toJson() : null);
        }

        private void textId(CanvasTextField tf, boolean historyOnly) throws Exception {
            if (textIds.containsKey(tf)) return;
            textIds.put(tf, textIds.size());
            textJson.add(historyOnly ? tf.toJson() : null);
        }

        /** The history as JSON. Stroke shapes shared between steps are stored once. */
        public org.json.JSONObject encode() throws Exception {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("v", 1);
            o.put("fp", fingerprint);
            org.json.JSONArray brushes = new org.json.JSONArray();
            for (int b : strokeBrush) brushes.put(b);
            o.put("strokes", brushes);
            o.put("curStrokes", currentStrokes);
            o.put("images", jsonList(imageJson));
            o.put("curImages", currentImages);
            o.put("texts", jsonList(textJson));
            o.put("curTexts", currentTexts);

            // Each distinct (stroke, shape, colour) once; steps list indices into it.
            org.json.JSONArray states = new org.json.JSONArray();
            java.util.HashMap<Long, List<Integer>> byHash = new java.util.HashMap<>();
            List<StrokeSnap> reps = new ArrayList<>();
            org.json.JSONArray undoJson = new org.json.JSONArray();
            org.json.JSONArray redoJson = new org.json.JSONArray();
            for (int pass = 0; pass < 2; pass++) {
                List<ContentSnap> list = pass == 0 ? undo : redo;
                org.json.JSONArray out = pass == 0 ? undoJson : redoJson;
                for (ContentSnap snap : list) {
                    int[] ids = new int[snap.strokes.size()];
                    for (int k = 0; k < ids.length; k++) {
                        ids[k] = stateId(snap.strokes.get(k), states, byHash, reps);
                    }
                    org.json.JSONObject sj = new org.json.JSONObject();
                    sj.put("full", snap.full);
                    sj.put("s", packInts(ids));
                    org.json.JSONArray im = new org.json.JSONArray();
                    for (ImgSnap is : snap.images) {
                        im.put(new org.json.JSONArray().put(imageIds.get(is.img))
                                .put(is.cx).put(is.cy).put(is.w).put(is.h).put(is.rot)
                                .put(is.presentHidden));
                    }
                    sj.put("i", im);
                    org.json.JSONArray tx = new org.json.JSONArray();
                    for (TextFieldSnap ts : snap.textFields) {
                        tx.put(new org.json.JSONArray().put(textIds.get(ts.field))
                                .put(ts.cx).put(ts.cy).put(ts.width).put(ts.height)
                                .put(ts.userSized).put(ts.presentHidden));
                    }
                    sj.put("t", tx);
                    out.put(sj);
                }
            }
            o.put("states", states);
            o.put("undo", undoJson);
            o.put("redo", redoJson);
            return o;
        }

        private int stateId(StrokeSnap ss, org.json.JSONArray states,
                            java.util.HashMap<Long, List<Integer>> byHash,
                            List<StrokeSnap> reps) throws Exception {
            int obj = strokeIds.get(ss.stroke);
            long h = obj;
            h = h * 31 + java.util.Arrays.hashCode(ss.xs);
            h = h * 31 + java.util.Arrays.hashCode(ss.ys);
            h = h * 31 + java.util.Arrays.hashCode(ss.ws);
            h = h * 31 + ss.color;
            h = h * 31 + (ss.presentHidden ? 1 : 0);
            h = h * 31 + (ss.colorName != null ? ss.colorName.hashCode() : 0);
            List<Integer> cands = byHash.get(h);
            if (cands != null) {
                for (int id : cands) {
                    StrokeSnap r = reps.get(id);
                    if (r.stroke == ss.stroke && r.color == ss.color
                            && r.presentHidden == ss.presentHidden
                            && java.util.Objects.equals(r.colorName, ss.colorName)
                            && java.util.Arrays.equals(r.xs, ss.xs)
                            && java.util.Arrays.equals(r.ys, ss.ys)
                            && java.util.Arrays.equals(r.ws, ss.ws)) {
                        return id;
                    }
                }
            } else {
                cands = new ArrayList<>();
                byHash.put(h, cands);
            }
            int id = reps.size();
            reps.add(ss);
            cands.add(id);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(ss.xs.length * 12)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < ss.xs.length; k++) {
                buf.putFloat(ss.xs[k]);
                buf.putFloat(ss.ys[k]);
                buf.putFloat(ss.ws[k]);
            }
            states.put(new org.json.JSONArray().put(obj)
                    .put(android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP))
                    .put(ss.color).put(ss.colorName != null ? ss.colorName : "")
                    .put(ss.presentHidden));
            return id;
        }

        private static org.json.JSONArray jsonList(List<org.json.JSONObject> list) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (org.json.JSONObject j : list) a.put(j != null ? j : org.json.JSONObject.NULL);
            return a;
        }
    }

    private static String packInts(int[] v) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(v.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int x : v) buf.putInt(x);
        return android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP);
    }

    private static int[] unpackInts(String s) {
        byte[] raw = android.util.Base64.decode(s, android.util.Base64.NO_WRAP);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[raw.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buf.getInt();
        return out;
    }

    /** Images as {@link #exportCanvasState} writes them, in the same order. */
    private List<CanvasImage> persistedImages() {
        List<CanvasImage> out = new ArrayList<>();
        for (CanvasImage img : images) {
            if (img == null) continue;
            boolean hasPixels = img.bitmap != null && !img.bitmap.isRecycled();
            if (hasPixels || img.isLive()) out.add(img);
        }
        return out;
    }

    /** Text fields as a reload brings them back, in order. */
    private List<CanvasTextField> persistedTextFields() {
        List<CanvasTextField> out = new ArrayList<>();
        for (CanvasTextField tf : textFields) {
            if (!isAgentPlacedId(tf.id)) out.add(tf);
        }
        return out;
    }

    /**
     * Identifies the document's content, so saved history is only ever put back onto
     * exactly the content it was recorded against (not, say, after an agent edit).
     */
    private String contentFingerprint() throws Exception {
        long h = 17;
        for (Stroke st : inkStrokes) {
            org.json.JSONObject j = strokeToJson(st);
            h = h * 31 + j.optString("packed").hashCode();
            h = h * 31 + st.color;
        }
        List<CanvasImage> imgs = persistedImages();
        for (CanvasImage img : imgs) {
            h = h * 31 + Math.round(img.cx * 10) + 7L * Math.round(img.cy * 10)
                    + 13L * Math.round(img.width * 10) + 29L * Math.round(img.height * 10);
        }
        List<CanvasTextField> tfs = persistedTextFields();
        for (CanvasTextField tf : tfs) h = h * 31 + tf.toJson().toString().hashCode();
        return inkStrokes.size() + "/" + imgs.size() + "/" + tfs.size() + "/" + Long.toHexString(h);
    }

    /** The current history for saving, or null when there is none. */
    public HistoryCapture captureUndoHistory() {
        if (activeStroke != null) return null;
        try {
            HistoryCapture c = new HistoryCapture(this);
            return c.isEmpty() ? null : c;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Puts saved history back, right after the document's content was loaded. Returns
     * false (and changes nothing) if the content no longer matches what it recorded.
     */
    public boolean restoreUndoHistory(org.json.JSONObject o) {
        if (o == null || o.optInt("v") != 1) return false;
        try {
            if (!o.optString("fp").equals(contentFingerprint())) {
                android.util.Log.i("CodeCanvasView", "undo history skipped: content changed since saved");
                return false;
            }
            org.json.JSONArray brushes = o.getJSONArray("strokes");
            int curS = o.getInt("curStrokes");
            if (curS != inkStrokes.size()) return false;
            Stroke[] strokes = new Stroke[brushes.length()];
            for (int i = 0; i < strokes.length; i++) {
                if (i < curS) {
                    strokes[i] = inkStrokes.get(i);
                } else {
                    strokes[i] = new Stroke(0xFF000000, "");
                    strokes[i].brush = brushes.getInt(i);
                }
            }
            CanvasImage[] imgs = restoreObjects(o.getJSONArray("images"), o.getInt("curImages"),
                    persistedImages(), true);
            CanvasTextField[] texts = restoreObjects(o.getJSONArray("texts"), o.getInt("curTexts"),
                    persistedTextFields(), false);
            if (imgs == null || texts == null) return false;

            org.json.JSONArray states = o.getJSONArray("states");
            StrokeSnap[] stateSnaps = new StrokeSnap[states.length()];
            for (int i = 0; i < stateSnaps.length; i++) {
                org.json.JSONArray st = states.getJSONArray(i);
                List<Sample> pts = new ArrayList<>();
                unpackSamples(st.getString(1), pts);
                float[] xs = new float[pts.size()];
                float[] ys = new float[pts.size()];
                float[] ws = new float[pts.size()];
                for (int k = 0; k < xs.length; k++) {
                    xs[k] = pts.get(k).x;
                    ys[k] = pts.get(k).y;
                    ws[k] = pts.get(k).width;
                }
                String name = st.getString(3);
                stateSnaps[i] = new StrokeSnap(strokes[st.getInt(0)], xs, ys, ws, st.getInt(2),
                        name.isEmpty() ? null : name, st.getBoolean(4));
            }
            List<ContentSnap> undo = decodeSnaps(o.getJSONArray("undo"), stateSnaps, imgs, texts);
            List<ContentSnap> redo = decodeSnaps(o.getJSONArray("redo"), stateSnaps, imgs, texts);
            undoStack.clear();
            redoStack.clear();
            historyChanged();
            undoStack.addAll(undo);
            redoStack.addAll(redo);
            historyChanged();
            android.util.Log.i("CodeCanvasView", "undo history restored: " + undo.size()
                    + " undo, " + redo.size() + " redo");
            return true;
        } catch (Exception e) {
            android.util.Log.w("CodeCanvasView", "undo history not restored: " + e.getMessage());
            return false;
        }
    }

    /** Document objects first (by position), then history-only ones rebuilt from JSON. */
    @SuppressWarnings("unchecked")
    private <T> T[] restoreObjects(org.json.JSONArray json, int current, List<T> live,
                                   boolean image) throws Exception {
        if (current != live.size()) return null;
        Object[] out = image ? new CanvasImage[json.length()] : new CanvasTextField[json.length()];
        for (int i = 0; i < out.length; i++) {
            if (i < current) {
                out[i] = live.get(i);
            } else {
                org.json.JSONObject j = json.optJSONObject(i);
                if (j == null) continue;
                out[i] = image ? CanvasImage.fromJson(j) : CanvasTextField.fromJson(j);
            }
        }
        return (T[]) out;
    }

    private static List<ContentSnap> decodeSnaps(org.json.JSONArray arr, StrokeSnap[] states,
                                                 CanvasImage[] imgs, CanvasTextField[] texts)
            throws Exception {
        List<ContentSnap> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject sj = arr.getJSONObject(i);
            ContentSnap snap = new ContentSnap();
            snap.full = sj.getBoolean("full");
            for (int id : unpackInts(sj.getString("s"))) snap.strokes.add(states[id]);
            org.json.JSONArray im = sj.getJSONArray("i");
            for (int k = 0; k < im.length(); k++) {
                org.json.JSONArray e = im.getJSONArray(k);
                CanvasImage img = imgs[e.getInt(0)];
                if (img == null) continue;
                snap.images.add(new ImgSnap(img, (float) e.getDouble(1), (float) e.getDouble(2),
                        (float) e.getDouble(3), (float) e.getDouble(4), (float) e.getDouble(5),
                        e.getBoolean(6)));
            }
            org.json.JSONArray tx = sj.getJSONArray("t");
            for (int k = 0; k < tx.length(); k++) {
                org.json.JSONArray e = tx.getJSONArray(k);
                CanvasTextField tf = texts[e.getInt(0)];
                if (tf == null) continue;
                snap.textFields.add(new TextFieldSnap(tf, (float) e.getDouble(1),
                        (float) e.getDouble(2), (float) e.getDouble(3), (float) e.getDouble(4),
                        e.getBoolean(5), e.getBoolean(6)));
            }
            out.add(snap);
        }
        return out;
    }

    private ContentSnap captureContent() {
        ContentSnap snap = new ContentSnap();
        snap.full = true;
        for (Stroke s : inkStrokes) snap.strokes.add(new StrokeSnap(s));
        for (CanvasImage img : images) snap.images.add(new ImgSnap(img));
        for (CanvasTextField tf : textFields) snap.textFields.add(new TextFieldSnap(tf));
        return snap;
    }

    private void restoreContent(ContentSnap snap) {
        if (snap == null) return;
        clearSelection();
        inkStrokes.clear();
        for (StrokeSnap s : snap.strokes) {
            s.restore();
            inkStrokes.add(s.stroke);
        }
        images.clear();
        for (ImgSnap s : snap.images) {
            s.restore();
            images.add(s.img);
        }
        textFields.clear();
        for (TextFieldSnap t : snap.textFields) {
            t.restore();
            textFields.add(t.field);
        }
        markSceneDirty();
        notifyContentChanged();
        invalidate();
    }

    private ContentSnap snapshotSelectionGeometry() {
        ContentSnap snap = new ContentSnap();
        snap.full = false;
        for (Stroke st : selectedStrokes) snap.strokes.add(new StrokeSnap(st));
        for (CanvasImage img : selectedImages) snap.images.add(new ImgSnap(img));
        for (CanvasTextField tf : selectedTextFields) snap.textFields.add(new TextFieldSnap(tf));
        return snap;
    }

    private void restoreGeometry(ContentSnap snap) {
        if (snap == null) return;
        for (StrokeSnap st : snap.strokes) st.restore();
        for (ImgSnap im : snap.images) im.restore();
        for (TextFieldSnap tf : snap.textFields) tf.restore();
        if (hasSelection()) {
            // Undo/redo of a move or transform: the box follows the items back. The
            // frame is only rebuilt on demand, so it stayed where the move left it.
            releaseSelDragBitmap();
            recomputeSelectionFrame();
            notifySelectionLayout();
        }
        markSceneDirty();
        invalidate();
    }

    private float pressureOf(MotionEvent event, int pointerIndex, int historical) {
        float p;
        if (historical >= 0) {
            p = event.getHistoricalPressure(pointerIndex, historical);
        } else {
            p = event.getPressure(pointerIndex);
        }
        if (!(p > 0f) || Float.isNaN(p)) {
            float axis = event.getAxisValue(MotionEvent.AXIS_PRESSURE, pointerIndex);
            if (axis > 0f && !Float.isNaN(axis)) p = axis;
            else p = 0.55f;
        }
        if (p < 0.05f) p = 0.05f;
        if (p > 1.2f) p = 1.2f;
        return Math.min(1f, p);
    }

    private float worldWidthForPressure(float pressure) {
        // Fixed world-space width — zoom changes how large it looks on screen,
        // not the stroke thickness stored on the canvas.
        float varied = baseThicknessPx * (0.4f + 0.8f * pressure);
        if (pressureSensitivity <= 0.001f) return baseThicknessPx;
        return baseThicknessPx + (varied - baseThicknessPx) * pressureSensitivity;
    }

    private void addSample(Stroke stroke, float worldX, float worldY, float pressure) {
        float rawW = worldWidthForPressure(pressure);
        // The pen-down reading only stands for a tap (a single-sample dot). Once the pen
        // moves, smoothing starts from the moving pen's pressure, not from the touch-down.
        if (inkSmoothedWidth < 0f || stroke.samples.size() == 1) inkSmoothedWidth = rawW;
        else inkSmoothedWidth += (rawW - inkSmoothedWidth) * WIDTH_SMOOTH;
        float width = inkSmoothedWidth;

        if (!stroke.samples.isEmpty()) {
            Sample last = stroke.samples.get(stroke.samples.size() - 1);
            float dx = worldX - last.x;
            float dy = worldY - last.y;
            float minDist = MIN_SAMPLE_DIST_PX / Math.max(0.01f, viewScale());
            if (dx * dx + dy * dy < minDist * minDist) {
                // Keep the tip live for drawing, but never move a committed sample —
                // rewriting last.x/y used to erase the path between samples and leave
                // straight chords instead of the curve the pen actually traced.
                inkTipX = worldX;
                inkTipY = worldY;
                inkTipW = width;
                inkHasTip = true;
                return;
            }
        }
        inkHasTip = false;
        stroke.samples.add(new Sample(worldX, worldY, width));
        int count = stroke.samples.size();
        if (count > 1 && count <= START_SETTLE_SAMPLES) {
            // A young stroke starts at the width it is settling into: no blob at the start.
            for (int i = 0; i < count - 1; i++) stroke.samples.get(i).width = width;
        }
        if (stroke == activeStroke) fireLiveStroke();
    }

    private long lastLiveStrokeFire;

    /** While drawing, tell the presentation (at most ~30×/s) so the slide follows the pen. */
    private void fireLiveStroke() {
        if (sceneChangedHook == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLiveStrokeFire < 33) return;
        lastLiveStrokeFire = now;
        fireSceneChanged(true);
    }

    private void beginInkPointer(MotionEvent event, int index) {
        inkPointerId = event.getPointerId(index);
        inkSmoothedWidth = -1f;
        inkHasTip = false;
        if (Build.VERSION.SDK_INT >= 30) {
            // Higher-rate MOVE delivery while inking — fewer gaps between samples.
            try {
                requestUnbufferedDispatch(event);
            } catch (Exception ignored) {
            }
        }
    }

    private void clearInkPointer() {
        inkPointerId = -1;
        inkSmoothedWidth = -1f;
        inkHasTip = false;
    }

    /** Draw the in-progress stroke including the ephemeral tip. */
    private void drawActiveStroke(Canvas canvas) {
        if (activeStroke == null) return;
        if (drawShapeMorph(canvas, activeStroke)) return;
        if (inkHasTip) activeStroke.samples.add(new Sample(inkTipX, inkTipY, inkTipW));
        if (activeStroke.brush != BRUSH_INK) {
            // Its samples still change: build the effect fresh, never cache it.
            drawEffectStroke(canvas, activeStroke, strokePaint, true);
        } else {
            drawStrokeSegments(canvas, activeStroke, strokePaint, strokeDrawPath);
        }
        if (inkHasTip) activeStroke.samples.remove(activeStroke.samples.size() - 1);
    }

    // ---- Effect brushes ---------------------------------------------------------------

    static final int BRUSH_INK = 0;
    static final int BRUSH_GLOW = 1;
    static final int BRUSH_RAINBOW = 2;
    static final int BRUSH_HIGHLIGHTER = 3;
    static final int BRUSH_CALLIGRAPHY = 4;
    static final int BRUSH_SPRAY = 5;
    static final int BRUSH_SPARKLE = 6;
    static final int BRUSH_COUNT = 7;
    /** Saved names; the index is the brush. */
    static final String[] BRUSH_KEYS = {
            "ink", "glow", "rainbow", "highlighter", "calligraphy", "spray", "sparkle"
    };
    static final String[] BRUSH_LABELS = {
            "Ink", "Glow", "Rainbow", "Highlighter", "Calligraphy", "Spray", "Sparkle"
    };

    static int brushFromKey(String key) {
        for (int i = 0; i < BRUSH_KEYS.length; i++) {
            if (BRUSH_KEYS[i].equals(key)) return i;
        }
        return BRUSH_INK;
    }

    /** How far past the line an effect reaches, as a multiple of its half-width. */
    private static float brushReach(int brush) {
        switch (brush) {
            case BRUSH_GLOW: return GLOW_REACH;
            case BRUSH_SPARKLE: return 7.5f;
            case BRUSH_SPRAY: return 6.5f;
            case BRUSH_HIGHLIGHTER: return 3.4f;
            case BRUSH_CALLIGRAPHY: return 2.0f;
            default: return 1f;
        }
    }

    /** Brush for new strokes. */
    private int brush = BRUSH_INK;

    public void setBrush(int b) {
        brush = b >= 0 && b < BRUSH_COUNT ? b : BRUSH_INK;
    }

    public int getBrush() {
        return brush;
    }

    /**
     * Draws a short wave with {@code b} in {@code color} into a w×h area — the brush
     * picker's previews use the very same drawing code as the canvas.
     */
    public void drawBrushPreview(Canvas c, int b, int color, float width, float w, float h) {
        Stroke st = new Stroke(color, "preview");
        st.brush = b;
        int n = 48;
        float padX = width * 4f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            float x = padX + t * (w - 2 * padX);
            float y = h / 2f + (float) Math.sin(t * Math.PI * 2.0) * h * 0.22f;
            st.samples.add(new Sample(x, y, width));
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (b == BRUSH_INK) {
            drawStrokeSegments(c, st, p, new Path());
        } else {
            drawEffectStroke(c, st, p, true);
        }
    }

    private void drawEffectStroke(Canvas canvas, Stroke s, Paint ink, boolean live) {
        switch (s.brush) {
            case BRUSH_GLOW: drawGlowStroke(canvas, s, ink, live); break;
            case BRUSH_RAINBOW: drawRainbowStroke(canvas, s, ink, live); break;
            case BRUSH_HIGHLIGHTER: drawHighlighterStroke(canvas, s, ink, live); break;
            case BRUSH_CALLIGRAPHY: drawCalligraphyStroke(canvas, s, ink, live); break;
            case BRUSH_SPRAY: drawSprayStroke(canvas, s, ink, live); break;
            case BRUSH_SPARKLE: drawSparkleStroke(canvas, s, ink, live); break;
            default: drawStrokeSegments(canvas, s, ink, strokeDrawPath); break;
        }
    }

    /** Cached effect geometry for a finished stroke, or null to (re)build it. */
    private StrokeGeom effectGeom(Stroke s, boolean live) {
        if (live) return null;
        StrokeGeom g = s.geom;
        return g != null && g.version == strokeGeomVersion ? g : null;
    }

    private static float averageWidth(List<Sample> pts) {
        float sum = 0f;
        for (Sample p : pts) sum += p.width;
        return pts.isEmpty() ? 1f : sum / pts.size();
    }

    private static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int towardWhite(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Walks the stroke's polyline and reports a point every {@code step} of length:
     * x, y, unit direction and arc length. Positions only depend on the samples so
     * far, so a stroke still being drawn keeps what it already showed.
     */
    private interface ArcVisitor {
        void at(int k, float x, float y, float dx, float dy, float arc);
    }

    private static void walkArc(List<Sample> pts, float step, ArcVisitor v) {
        if (pts.isEmpty() || step <= 0f) return;
        Sample first = pts.get(0);
        // The start always counts: a stroke whose points all coincide (just begun, or a
        // dot) still gets one — callers may rely on at least one point.
        v.at(0, first.x, first.y, 1f, 0f, 0f);
        float next = step;
        float arc = 0f;
        int k = 1;
        if (pts.size() == 1) return;
        for (int i = 1; i < pts.size(); i++) {
            Sample a = pts.get(i - 1);
            Sample b = pts.get(i);
            float sx = b.x - a.x;
            float sy = b.y - a.y;
            float len = (float) Math.hypot(sx, sy);
            if (len < 1e-4f) continue;
            float ux = sx / len;
            float uy = sy / len;
            while (next <= arc + len) {
                float t = next - arc;
                v.at(k++, a.x + ux * t, a.y + uy * t, ux, uy, next);
                next += step;
            }
            arc += len;
        }
    }

    /** Stable per-stroke seed, so scatter looks the same on every redraw. */
    private static long strokeSeed(Stroke s) {
        Sample f = s.samples.get(0);
        return ((long) Float.floatToIntBits(f.x) * 31L) ^ Float.floatToIntBits(f.y) ^ s.color;
    }

    /** Small, fast, well-mixed hash → [0, 1). */
    private static float hash01(long seed, int k, int salt) {
        long z = seed + k * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 40) / (float) (1L << 24);
    }

    private void drawPointBatches(Canvas canvas, Paint ink, StrokeGeom g, int rgb) {
        if (g.dots == null) return;
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < g.dots.length; i++) {
            if (g.dots[i].length == 0) continue;
            ink.setColor(argb(g.dotAlphas[i], rgb));
            ink.setStrokeWidth(g.dotSizes[i]);
            canvas.drawPoints(g.dots[i], ink);
        }
    }

    // Rainbow: the hue cycles along the stroke's length.
    private void drawRainbowStroke(Canvas canvas, Stroke s, Paint ink, boolean live) {
        List<Sample> pts = s.samples;
        if (pts.isEmpty()) return;
        StrokeGeom g = effectGeom(s, live);
        if (g == null || g.segs == null) {
            float width = averageWidth(pts);
            // One full cycle about every 40 line widths.
            float cycle = Math.max(8f, width * 40f);
            float step = Math.max(0.5f, width * 0.6f);
            List<float[]> pos = new ArrayList<>();
            walkArc(pts, step, (k, x, y, dx, dy, arc) -> pos.add(new float[]{x, y, arc}));
            if (pos.size() == 1) pos.add(new float[]{pos.get(0)[0] + 0.01f, pos.get(0)[1], 0f});
            Sample last = pts.get(pts.size() - 1);
            float[] tail = pos.get(pos.size() - 1);
            if (Math.hypot(last.x - tail[0], last.y - tail[1]) > 0.05f) {
                pos.add(new float[]{last.x, last.y, tail[2] + step});
            }
            int n = pos.size() - 1;
            float[] segs = new float[n * 4];
            int[] colors = new int[n];
            float[] hsv = {0f, 0.78f, 1f};
            for (int i = 0; i < n; i++) {
                float[] a = pos.get(i);
                float[] b = pos.get(i + 1);
                segs[i * 4] = a[0];
                segs[i * 4 + 1] = a[1];
                segs[i * 4 + 2] = b[0];
                segs[i * 4 + 3] = b[1];
                hsv[0] = ((a[2] / cycle) * 360f) % 360f;
                colors[i] = android.graphics.Color.HSVToColor(hsv);
            }
            g = new StrokeGeom(new Path[0], new float[]{width}, strokeGeomVersion);
            g.segs = segs;
            g.segColors = colors;
            if (!live) s.geom = g;
        }
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeWidth(g.widths[0]);
        int alpha = (s.color >>> 24) & 0xFF;
        for (int i = 0; i < g.segColors.length; i++) {
            ink.setColor(argb(alpha, g.segColors[i]));
            canvas.drawLine(g.segs[i * 4], g.segs[i * 4 + 1], g.segs[i * 4 + 2], g.segs[i * 4 + 3], ink);
        }
    }

    // Highlighter: wide, translucent, flat-ended — one path, so overlaps do not darken.
    private void drawHighlighterStroke(Canvas canvas, Stroke s, Paint ink, boolean live) {
        List<Sample> pts = s.samples;
        if (pts.isEmpty()) return;
        StrokeGeom g = effectGeom(s, live);
        if (g == null || g.paths.length != 1) {
            g = new StrokeGeom(new Path[]{buildGlowPath(pts, strokeFollow)},
                    new float[]{averageWidth(pts)}, strokeGeomVersion);
            if (!live) s.geom = g;
        }
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.BUTT);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStrokeWidth(g.widths[0] * 3.2f);
        ink.setColor(argb(Math.round(((s.color >>> 24) & 0xFF) * 0.38f), s.color));
        canvas.drawPath(g.paths[0], ink);
    }

    /** Calligraphy nib angle: a flat pen held at 40°. */
    private static final double NIB_ANGLE = Math.toRadians(-40);

    // Calligraphy: the area a flat nib sweeps, filled — thick across the nib, thin along it.
    private void drawCalligraphyStroke(Canvas canvas, Stroke s, Paint ink, boolean live) {
        List<Sample> pts = s.samples;
        if (pts.isEmpty()) return;
        StrokeGeom g = effectGeom(s, live);
        if (g == null || g.fill == null) {
            Path fill = new Path();
            fill.setFillType(Path.FillType.WINDING);
            int n = pts.size();
            float nibCos = (float) Math.cos(NIB_ANGLE);
            float nibSin = (float) Math.sin(NIB_ANGLE);
            for (int i = 0; i < n; i++) {
                Sample a = pts.get(Math.max(0, i - 1));
                Sample b = pts.get(i);
                // Half the nib, scaled with pressure; a sliver stays so thin parts show.
                float half = b.width * 1.25f;
                float nx = nibCos * half;
                float ny = nibSin * half;
                if (i == 0 || n == 1) {
                    fill.addCircle(b.x, b.y, b.width * 0.35f, Path.Direction.CW);
                    if (n == 1) break;
                    continue;
                }
                float ha = a.width * 1.25f;
                float ax = nibCos * ha;
                float ay = nibSin * ha;
                fill.moveTo(a.x + ax, a.y + ay);
                fill.lineTo(b.x + nx, b.y + ny);
                fill.lineTo(b.x - nx, b.y - ny);
                fill.lineTo(a.x - ax, a.y - ay);
                fill.close();
            }
            g = new StrokeGeom(new Path[0], new float[]{averageWidth(pts)}, strokeGeomVersion);
            g.fill = fill;
            if (!live) s.geom = g;
        }
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(s.color);
        canvas.drawPath(g.fill, ink);
        ink.setStyle(Paint.Style.STROKE);
    }

    // Spray: an airbrush of dots, dense at the line and thinning out.
    private void drawSprayStroke(Canvas canvas, Stroke s, Paint ink, boolean live) {
        List<Sample> pts = s.samples;
        if (pts.isEmpty()) return;
        StrokeGeom g = effectGeom(s, live);
        if (g == null || g.dots == null) {
            float width = averageWidth(pts);
            long seed = strokeSeed(s);
            final int buckets = 3;
            final List<List<Float>> out = new ArrayList<>();
            for (int i = 0; i < buckets; i++) out.add(new ArrayList<>());
            float step = Math.max(0.4f, width * 0.55f);
            walkArc(pts, step, (k, x, y, dx, dy, arc) -> {
                for (int d = 0; d < 7; d++) {
                    float ang = hash01(seed, k, d * 3) * (float) (Math.PI * 2);
                    // Squared: most paint near the line, a fine mist further out.
                    float rr = hash01(seed, k, d * 3 + 1);
                    float r = width * 3.1f * rr * rr;
                    int bucket = Math.min(buckets - 1, (int) (hash01(seed, k, d * 3 + 2) * buckets));
                    List<Float> b = out.get(bucket);
                    b.add(x + (float) Math.cos(ang) * r);
                    b.add(y + (float) Math.sin(ang) * r);
                }
            });
            g = new StrokeGeom(new Path[0], new float[]{width}, strokeGeomVersion);
            g.dots = new float[buckets][];
            g.dotSizes = new float[]{width * 0.22f, width * 0.36f, width * 0.55f};
            g.dotAlphas = new int[]{235, 185, 120};
            for (int i = 0; i < buckets; i++) {
                List<Float> b = out.get(i);
                float[] arr = new float[b.size()];
                for (int j = 0; j < arr.length; j++) arr[j] = b.get(j);
                g.dots[i] = arr;
            }
            if (!live) s.geom = g;
        }
        drawPointBatches(canvas, ink, g, s.color);
    }

    // Sparkle: a slim glow scattered with four-point stars.
    private void drawSparkleStroke(Canvas canvas, Stroke s, Paint ink, boolean live) {
        List<Sample> pts = s.samples;
        if (pts.isEmpty()) return;
        StrokeGeom g = effectGeom(s, live);
        if (g == null || g.fill == null || g.paths.length != 1) {
            float width = averageWidth(pts);
            long seed = strokeSeed(s);
            Path stars = new Path();
            final List<Float> motes = new ArrayList<>();
            walkArc(pts, Math.max(1f, width * 3.2f), (k, x, y, dx, dy, arc) -> {
                float side = hash01(seed, k, 1) * 2f - 1f;
                float off = side * width * 2.6f;
                float px = x - dy * off;
                float py = y + dx * off;
                if (hash01(seed, k, 2) < 0.42f) {
                    float r = width * (0.9f + 1.6f * hash01(seed, k, 3));
                    float waist = r * 0.18f;
                    float tilt = hash01(seed, k, 4) * 0.6f - 0.3f;
                    float c = (float) Math.cos(tilt);
                    float sn = (float) Math.sin(tilt);
                    // Four-point star: long spikes, pinched waist.
                    float[][] v = {
                            {0, -r}, {waist, -waist}, {r, 0}, {waist, waist},
                            {0, r}, {-waist, waist}, {-r, 0}, {-waist, -waist}};
                    for (int i = 0; i < v.length; i++) {
                        float vx = px + v[i][0] * c - v[i][1] * sn;
                        float vy = py + v[i][0] * sn + v[i][1] * c;
                        if (i == 0) stars.moveTo(vx, vy);
                        else stars.lineTo(vx, vy);
                    }
                    stars.close();
                } else {
                    motes.add(px);
                    motes.add(py);
                }
            });
            g = new StrokeGeom(new Path[]{buildGlowPath(pts, strokeFollow)},
                    new float[]{width}, strokeGeomVersion);
            g.fill = stars;
            float[] m = new float[motes.size()];
            for (int j = 0; j < m.length; j++) m[j] = motes.get(j);
            g.dots = new float[][]{m};
            g.dotSizes = new float[]{width * 0.45f};
            g.dotAlphas = new int[]{200};
            if (!live) s.geom = g;
        }
        int rgb = s.color & 0x00FFFFFF;
        int baseAlpha = (s.color >>> 24) & 0xFF;
        float width = g.widths[0];
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        float[][] layers = {{3.6f, 0.07f}, {2.2f, 0.16f}, {1.2f, 0.45f}};
        for (float[] layer : layers) {
            ink.setColor(argb(Math.round(baseAlpha * layer[1]), rgb));
            ink.setStrokeWidth(width * layer[0]);
            canvas.drawPath(g.paths[0], ink);
        }
        ink.setColor(argb(baseAlpha, towardWhite(rgb, 0.75f)));
        ink.setStrokeWidth(Math.max(0.5f, width * 0.45f));
        canvas.drawPath(g.paths[0], ink);
        drawPointBatches(canvas, ink, g, towardWhite(rgb, 0.5f));
        // Stars: a coloured halo, then the bright star itself.
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(width * 0.9f);
        ink.setColor(argb(Math.round(baseAlpha * 0.35f), rgb));
        canvas.drawPath(g.fill, ink);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(argb(baseAlpha, towardWhite(rgb, 0.85f)));
        canvas.drawPath(g.fill, ink);
        ink.setStyle(Paint.Style.STROKE);
    }

    /**
     * Glow layers, outermost first: {width × line width, alpha}. Wide and faint to
     * narrow and dense, so the colour falls off smoothly away from the line.
     */
    private static final float[][] GLOW_LAYERS = {
            {6.0f, 0.05f},
            {4.3f, 0.08f},
            {3.0f, 0.13f},
            {2.0f, 0.24f},
            {1.3f, 0.50f},
    };
    /** How far the glow reaches, as a multiple of the line's half-width. */
    private static final float GLOW_REACH = 6.2f;
    /** Core: this share of the line width, the colour this far toward white. */
    private static final float GLOW_CORE_WIDTH = 0.6f;
    private static final float GLOW_CORE_WHITE = 0.65f;

    /**
     * One smooth path for the whole stroke at its average width: a glow is layered by
     * alpha, and per-width runs would double the alpha where they meet.
     */
    private static Path buildGlowPath(List<Sample> pts, float follow) {
        Path path = new Path();
        int n = pts.size();
        if (n == 0) return path;
        Sample p0 = pts.get(0);
        path.moveTo(p0.x, p0.y);
        if (n == 1) {
            path.lineTo(p0.x + 0.01f, p0.y);
            return path;
        }
        if (n == 2) {
            Sample p1 = pts.get(1);
            path.lineTo(p1.x, p1.y);
            return path;
        }
        Sample p1 = pts.get(1);
        path.lineTo((p0.x + p1.x) * 0.5f, (p0.y + p1.y) * 0.5f);
        for (int i = 1; i < n - 1; i++) {
            Sample a = pts.get(i);
            Sample b = pts.get(i + 1);
            Sample prev = pts.get(i - 1);
            float x0 = (prev.x + a.x) * 0.5f;
            float y0 = (prev.y + a.y) * 0.5f;
            float x1 = (a.x + b.x) * 0.5f;
            float y1 = (a.y + b.y) * 0.5f;
            float ctrlX = a.x + ((2f * a.x - (x0 + x1) * 0.5f) - a.x) * follow;
            float ctrlY = a.y + ((2f * a.y - (y0 + y1) * 0.5f) - a.y) * follow;
            path.quadTo(ctrlX, ctrlY, x1, y1);
        }
        Sample last = pts.get(n - 1);
        path.lineTo(last.x, last.y);
        return path;
    }

    private void drawGlowStroke(Canvas canvas, Stroke s, Paint ink, boolean live) {
        List<Sample> pts = s.samples;
        if (pts.isEmpty()) return;
        Path path;
        float width;
        StrokeGeom g = live ? null : s.geom;
        int version = strokeGeomVersion;
        if (g != null && g.version == version && g.paths.length == 1 && g.fill == null) {
            path = g.paths[0];
            width = g.widths[0];
        } else {
            float sum = 0f;
            for (Sample p : pts) sum += p.width;
            width = sum / pts.size();
            path = buildGlowPath(pts, strokeFollow);
            if (!live) s.geom = new StrokeGeom(new Path[]{path}, new float[]{width}, version);
        }
        int rgb = s.color & 0x00FFFFFF;
        int baseAlpha = (s.color >>> 24) & 0xFF;
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        for (float[] layer : GLOW_LAYERS) {
            int a = Math.round(baseAlpha * layer[1]);
            ink.setColor((a << 24) | rgb);
            ink.setStrokeWidth(width * layer[0]);
            canvas.drawPath(path, ink);
        }
        int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += Math.round((255 - r) * GLOW_CORE_WHITE);
        gg += Math.round((255 - gg) * GLOW_CORE_WHITE);
        b += Math.round((255 - b) * GLOW_CORE_WHITE);
        ink.setColor((baseAlpha << 24) | (r << 16) | (gg << 8) | b);
        ink.setStrokeWidth(Math.max(0.5f, width * GLOW_CORE_WIDTH));
        canvas.drawPath(path, ink);
    }

    /** Flush the live tip into the stroke so the lift position is not lost. */
    private void commitInkTip(Stroke stroke) {
        if (!inkHasTip || stroke == null) return;
        if (!stroke.samples.isEmpty()) {
            Sample last = stroke.samples.get(stroke.samples.size() - 1);
            float dx = inkTipX - last.x;
            float dy = inkTipY - last.y;
            if (dx * dx + dy * dy < 1e-8f) {
                last.width = inkTipW;
                inkHasTip = false;
                return;
            }
        }
        stroke.samples.add(new Sample(inkTipX, inkTipY, inkTipW));
        inkHasTip = false;
    }

    /** Index of the stylus pointer that owns the current ink gesture, or -1. */
    private int inkPointerIndex(MotionEvent event) {
        if (inkPointerId < 0) return -1;
        return event.findPointerIndex(inkPointerId);
    }

    /**
     * Favorites radial — drawn in-canvas.
     * Pen button arms eraser; hover-move opens the menu only while the button is
     * still held. Releasing the button cancels the arm (does not open the menu).
     */
    private boolean favoritesArmed;
    private boolean favoritesArmAnchored;
    private boolean favoritesButtonHeld;
    /**
     * When the primary pen button releases while the tip is still down, restore the
     * pencil only after the tip lifts — otherwise a PAGE_DOWN UP that races tip-down
     * kills erase mid-stroke (or before it starts).
     */
    private Runnable restorePencilAfterEraseTip;
    /** Primary hold arms erase; ends the moment the button is up and the tip is off. */
    private boolean penEraseArmed;
    private long penEraseArmMs;
    private boolean sawStylusButtonOnHover;
    private float favoritesArmX;
    private float favoritesArmY;

    public boolean isFavoritesRadialOpen() {
        return favoritesRadial.isOpen();
    }

    /** True while a primary-button favorites gesture is in progress (menu may still be hidden). */
    public boolean hasPendingFavoritesGesture() {
        if (!quickFavoritesEnabled) return false;
        return favoritesGesturePressMs > 0
                || favoritesShowRunnable != null
                || favoritesRadial.isOpen();
    }

    /** Host forwards hover here when an overlay would otherwise steal the stylus stream. */
    public void feedHover(MotionEvent event) {
        onHoverEvent(event);
    }

    public void applyFavoritesRadialColors(int surface, int surfaceHigh, int primary,
                                           int onPrimary, int onSurface, int outline) {
        applyFavoritesRadialColors(surface, surfaceHigh, primary, primary,
                onPrimary, onSurface, onSurface, outline);
    }

    public void applyFavoritesRadialColors(int surface, int surfaceHigh, int primary,
                                           int primaryContainer, int onPrimaryContainer,
                                           int onSurface, int onSurfaceVariant, int outline) {
        favoritesRadial.applyColors(surface, surfaceHigh, primary, primaryContainer,
                onPrimaryContainer, onSurface, onSurfaceVariant, outline);
        if (favoritesRadial.isOpen()) invalidate();
    }

    /**
     * Primary pen button down. Eraser is selected by the host immediately.
     * While hovering, start the gesture; the radial only appears after
     * {@link #FAVORITES_SHOW_DELAY_MS} so a quick flick never flashes the menu.
     */
    public void armFavoritesRadialGesture() {
        if (!quickFavoritesEnabled) {
            // Primary hold is eraser-only when quick favorites are off.
            favoritesButtonHeld = false;
            favoritesArmed = false;
            favoritesArmAnchored = false;
            cancelFavoritesShow();
            return;
        }
        favoritesButtonHeld = true;
        sawStylusButtonOnHover = false;
        penEraseArmed = true;
        penEraseArmMs = android.os.SystemClock.uptimeMillis();
        restorePencilAfterEraseTip = null;
        cancelFavoritesFinalize();
        cancelFavoritesShow();
        cancelFavoritesOpen();
        // Stale tip-block from a prior erase must not keep the menu shut.
        if (favoritesTipBlocked && !isStylusTipDown()) {
            favoritesTipBlocked = false;
        }
        // A press while the menu is up closes it; its release must not reopen it.
        favoritesPressClosedMenu = favoritesRadial.isOpen();
        if (favoritesPressClosedMenu) {
            clearRadialTipPress();
            favoritesRadial.cancel();
            invalidate();
        }
        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesPrePressDirValid = false;
        // Marks a press in progress; the menu opens on release (see finishFavoritesPress),
        // but where the pen was at the press — not wherever it drifted by the release.
        favoritesGesturePressMs = android.os.SystemClock.uptimeMillis();
        favoritesPressX = lastPenX;
        favoritesPressY = lastPenY;
        // Only a press made while the pen actually hovers over the screen counts.
        favoritesPressHovering = hoverInside
                && favoritesGesturePressMs - lastPenHoverMs < FAVORITES_HOVER_FRESH_MS;
    }

    /** The press happened with the pen hovering — required for the menu to open. */
    private boolean favoritesPressHovering;
    /** A hover sample this recent means the pen is over the screen right now. */
    private static final long FAVORITES_HOVER_FRESH_MS = 250L;

    /** Pen position when the button went down; the menu opens there. */
    private float favoritesPressX = Float.NaN;
    private float favoritesPressY = Float.NaN;

    /**
     * Pen tip contact times. The button arrives as key events that race the touch
     * stream, so a flag set on tip-down could be cleared before the press was even
     * seen — and a brief touch during the press still opened the menu. Times do not.
     */
    private long lastTipDownMs;
    private long lastTipUpMs;
    /** Tip contact this long before the press still counts as part of it. */
    private static final long FAVORITES_TIP_LEAD_MS = 300L;
    /** Wait after release, so a tip-down that races the button release still counts. */
    private static final long FAVORITES_OPEN_DELAY_MS = 120L;
    private Runnable favoritesOpenRunnable;

    private void cancelFavoritesOpen() {
        if (favoritesOpenRunnable != null) {
            removeCallbacks(favoritesOpenRunnable);
            favoritesOpenRunnable = null;
        }
    }

    /** True if the tip touched the screen since {@code sinceMs}, or is down now. */
    private boolean tipContactSince(long sinceMs) {
        return lastTipDownMs >= sinceMs || lastTipDownMs > lastTipUpMs || isStylusTipDown();
    }

    /** The press that just ended closed an open menu (so its release opens nothing). */
    private boolean favoritesPressClosedMenu;
    /** Swallow the rest of a pen contact that only closed the menu. */
    private boolean swallowPenUntilUp;

    /**
     * Pen button released. A single press while hovering — no tip contact during it —
     * opens the menu at the pen, where it stays until an item is tapped, the pen taps
     * elsewhere, or the button is pressed again. Returns true when it opened.
     */
    public void finishFavoritesPress(Runnable onOpening, Runnable onNotOpened) {
        boolean tipBlocked = favoritesTipBlocked;
        boolean closedByPress = favoritesPressClosedMenu;
        boolean pressed = favoritesGesturePressMs > 0;
        long pressMs = favoritesGesturePressMs;
        cancelFavoritesShow();
        cancelFavoritesFinalize();
        favoritesButtonHeld = false;
        sawStylusButtonOnHover = false;
        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesGesturePressMs = 0;
        favoritesPrePressDirValid = false;
        favoritesTipBlocked = false;
        favoritesPressClosedMenu = false;
        cancelFavoritesOpen();
        long since = pressMs - FAVORITES_TIP_LEAD_MS;
        final float pressX = favoritesPressX;
        final float pressY = favoritesPressY;
        final boolean pressHovering = favoritesPressHovering;
        favoritesPressHovering = false;
        if (!quickFavoritesEnabled || !pressed || tipBlocked || closedByPress
                || tipContactSince(since)) {
            if (onNotOpened != null) onNotOpened.run();
            return;
        }
        // Decide a moment later: a tip-down racing the release means erase, not menu.
        favoritesOpenRunnable = () -> {
            favoritesOpenRunnable = null;
            // The pen must have been hovering at the press itself; a press made with
            // the pen away from the screen opens nothing.
            float ox = pressX;
            float oy = pressY;
            if (!pressHovering || tipContactSince(since) || listener == null
                    || Float.isNaN(ox)) {
                if (onNotOpened != null) onNotOpened.run();
                return;
            }
            if (onOpening != null) onOpening.run();
            listener.onFavoritesRadialOpenRequested(ox, oy);
            if (favoritesRadial.isOpen()) {
                favoritesRadial.hoverAt(lastPenX, lastPenY);
                invalidate();
            }
        };
        postDelayed(favoritesOpenRunnable, FAVORITES_OPEN_DELAY_MS);
    }

    private void beginFavoritesGestureAt(float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        favoritesGesturePressMs = now;
        favoritesGestureCx = x;
        favoritesGestureCy = y;
        stashPrePressDirection(now);
        favoritesArmed = false;
        favoritesArmAnchored = false;
        scheduleFavoritesShow();
    }

    private void scheduleFavoritesShow() {
        cancelFavoritesShow();
        if (favoritesTipBlocked || listener == null || favoritesRadial.isOpen()) return;
        favoritesShowRunnable = () -> {
            favoritesShowRunnable = null;
            // Do not require hoverInside: some pens drop HOVER briefly while the
            // side button is held, which used to abort the menu forever.
            if (!favoritesButtonHeld || favoritesTipBlocked
                    || favoritesRadial.isOpen() || listener == null) {
                return;
            }
            listener.onFavoritesRadialOpenRequested(favoritesGestureCx, favoritesGestureCy);
            // Held long enough to see the menu: from here on the pen simply points at
            // an item (position relative to the center), no flick reading.
            pointFavoritesRadialAtPen();
        };
        postDelayed(favoritesShowRunnable, FAVORITES_SHOW_DELAY_MS);
    }

    private void cancelFavoritesShow() {
        if (favoritesShowRunnable != null) {
            removeCallbacks(favoritesShowRunnable);
            favoritesShowRunnable = null;
        }
    }

    private void stashPrePressDirection(long pressMs) {
        float[] d = directionFromTrailWindow(pressMs - FAVORITES_PRE_PRESS_MS, pressMs);
        if (d != null) {
            favoritesPrePressDirX = d[0];
            favoritesPrePressDirY = d[1];
            favoritesPrePressDirValid = true;
        } else {
            favoritesPrePressDirValid = false;
        }
    }

    /** Prefer [press−200ms, release−50ms]; else fall back to stashed pre-press direction. */
    private float[] directionForGesture(long pressMs, long releaseMs) {
        float[] full = directionFromTrailWindow(
                pressMs - FAVORITES_PRE_PRESS_MS, releaseMs - FAVORITES_END_TRIM_MS);
        if (full != null) return full;
        if (favoritesPrePressDirValid) {
            return new float[]{favoritesPrePressDirX, favoritesPrePressDirY};
        }
        return null;
    }

    /**
     * True when hover travel during the favorites window is a deliberate flick,
     * not the jitter of holding still before a tip-down erase.
     */
    private boolean isIntentionalFavoritesFlick(long pressMs, long releaseMs, float dens) {
        float speed = (float) Math.hypot(hoverVelX, hoverVelY);
        if (speed >= 10f * dens) return true;
        float minTravel = 28f * dens;
        float travel = trailTravelPx(
                pressMs - FAVORITES_PRE_PRESS_MS, releaseMs - FAVORITES_END_TRIM_MS);
        if (travel >= minTravel) return true;
        // Pre-press stash alone is not enough — that was hover before the hold.
        return false;
    }

    private float trailTravelPx(long t0, long t1) {
        if (hoverTrailCount < 2) return 0f;
        float oldestX = 0, oldestY = 0, newestX = 0, newestY = 0;
        long oldestT = Long.MAX_VALUE, newestT = Long.MIN_VALUE;
        boolean any = false;
        for (int i = 0; i < hoverTrailCount; i++) {
            int idx = (hoverTrailHead - 1 - i + HOVER_TRAIL * 4) % HOVER_TRAIL;
            long t = hoverTrailT[idx];
            if (t < t0 || t > t1) continue;
            float x = hoverTrailX[idx];
            float y = hoverTrailY[idx];
            any = true;
            if (t < oldestT) {
                oldestT = t;
                oldestX = x;
                oldestY = y;
            }
            if (t >= newestT) {
                newestT = t;
                newestX = x;
                newestY = y;
            }
        }
        if (!any || newestT == oldestT) return 0f;
        return (float) Math.hypot(newestX - oldestX, newestY - oldestY);
    }

    private void pushHoverTrail(float x, float y, long t) {
        hoverTrailX[hoverTrailHead] = x;
        hoverTrailY[hoverTrailHead] = y;
        hoverTrailT[hoverTrailHead] = t;
        hoverTrailHead = (hoverTrailHead + 1) % HOVER_TRAIL;
        if (hoverTrailCount < HOVER_TRAIL) hoverTrailCount++;
    }

    /** Unit vector from oldest→newest sample in {@code [t0, t1]}, or null. */
    private float[] directionFromTrailWindow(long t0, long t1) {
        if (hoverTrailCount < 2) return null;
        float oldestX = 0, oldestY = 0, newestX = 0, newestY = 0;
        long oldestT = Long.MAX_VALUE, newestT = Long.MIN_VALUE;
        boolean any = false;
        for (int i = 0; i < hoverTrailCount; i++) {
            int idx = (hoverTrailHead - 1 - i + HOVER_TRAIL * 4) % HOVER_TRAIL;
            long t = hoverTrailT[idx];
            if (t < t0 || t > t1) continue;
            float x = hoverTrailX[idx];
            float y = hoverTrailY[idx];
            any = true;
            if (t < oldestT) {
                oldestT = t;
                oldestX = x;
                oldestY = y;
            }
            if (t >= newestT) {
                newestT = t;
                newestX = x;
                newestY = y;
            }
        }
        if (!any || newestT == oldestT) return null;
        float dx = newestX - oldestX;
        float dy = newestY - oldestY;
        float len = (float) Math.hypot(dx, dy);
        if (len < 0.25f) return null;
        return new float[]{dx / len, dy / len};
    }

    /** Highlight the favorite in the pen's direction from the open menu's center. */
    private void pointFavoritesRadialAtPen() {
        if (!favoritesRadial.isOpen()) return;
        favoritesRadial.updateTip(lastHoverX, lastHoverY);
        invalidate();
    }

    private void cancelFavoritesFinalize() {
        if (favoritesFinalizeRunnable != null) {
            removeCallbacks(favoritesFinalizeRunnable);
            favoritesFinalizeRunnable = null;
        }
    }

    public interface FavoritesPickCallback {
        void onFavoritesPicked(String favoriteId);
    }

    /** Pen button up: drop the arm so release never opens the menu. */
    public void releaseFavoritesRadialGesture() {
        cancelFavoritesShow();
        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesButtonHeld = false;
        sawStylusButtonOnHover = false;
        favoritesTipBlocked = false;
        favoritesGesturePressMs = 0;
        favoritesPrePressDirValid = false;
    }

    public void openFavoritesRadial(List<RadialFavoritesPainter.Item> items, float x, float y) {
        clearRadialTipPress();
        cancelFavoritesShow();
        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesRadial.setItems(items);
        favoritesRadial.openAt(x, y);
        favoritesGestureCx = x;
        favoritesGestureCy = y;
        invalidate();
    }

    /** Replace items while keeping the menu open at the same center (e.g. after a remove). */
    public void refreshFavoritesRadial(List<RadialFavoritesPainter.Item> items) {
        if (!favoritesRadial.isOpen()) return;
        if (items == null || items.isEmpty()) {
            cancelFavoritesRadial();
            return;
        }
        favoritesRadial.setItems(items);
        invalidate();
    }

    public void cancelFavoritesRadial() {
        cancelFavoritesFinalize();
        cancelFavoritesShow();
        clearRadialTipPress();
        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesButtonHeld = false;
        sawStylusButtonOnHover = false;
        favoritesGesturePressMs = 0;
        favoritesPrePressDirValid = false;
        if (!favoritesRadial.isOpen()) return;
        favoritesRadial.cancel();
        invalidate();
    }

    /**
     * After primary release: pick using hover motion in
     * [press−200ms, release−50ms]. Works with or without the radial having been shown.
     *
     * <p>Tip contact during the hold means erase, never a favorite. No clear flick
     * direction also means no pick.
     */
    public void finishFavoritesRadialPickDelayed(
            List<RadialFavoritesPainter.Item> items, FavoritesPickCallback cb) {
        cancelFavoritesShow();
        cancelFavoritesFinalize();
        boolean tipBlocked = favoritesTipBlocked;
        boolean hadGesture = favoritesGesturePressMs > 0 || favoritesRadial.isOpen();
        long pressMs = favoritesGesturePressMs > 0
                ? favoritesGesturePressMs
                : android.os.SystemClock.uptimeMillis();
        long releaseMs = android.os.SystemClock.uptimeMillis();
        float dens = getResources().getDisplayMetrics().density;

        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesButtonHeld = false;
        sawStylusButtonOnHover = false;
        clearRadialTipPress();

        if (tipBlocked || !hadGesture) {
            favoritesTipBlocked = false;
            favoritesGesturePressMs = 0;
            favoritesPrePressDirValid = false;
            if (favoritesRadial.isOpen()) {
                favoritesRadial.cancel();
                invalidate();
            }
            if (cb != null) cb.onFavoritesPicked(null);
            return;
        }

        if (favoritesRadial.isOpen()) {
            // Menu visible → pick what the pen points at; resting in the center hub
            // picks nothing (falls back to the pencil like an empty release).
            pointFavoritesRadialAtPen();
            String id = favoritesRadial.highlightedId();
            favoritesRadial.cancel();
            invalidate();
            favoritesTipBlocked = false;
            favoritesGesturePressMs = 0;
            favoritesPrePressDirValid = false;
            if (cb != null) cb.onFavoritesPicked(id);
            return;
        }

        float[] dir = directionForGesture(pressMs, releaseMs);
        // No intentional flick → not a favorite. Forcing a sector (was straight-up)
        // stole every button-hold-then-tip erase when PAGE_DOWN UP raced tip-down.
        // Tiny hover jitter also produced a unit vector — require real travel.
        if (dir == null || !isIntentionalFavoritesFlick(pressMs, releaseMs, dens)) {
            favoritesTipBlocked = false;
            favoritesGesturePressMs = 0;
            favoritesPrePressDirValid = false;
            if (favoritesRadial.isOpen()) {
                favoritesRadial.cancel();
                invalidate();
            }
            if (cb != null) cb.onFavoritesPicked(null);
            return;
        }

        // Quick flick before the menu showed: pick by the flick's direction.
        favoritesTipBlocked = false;
        favoritesRadial.setItems(items);
        String id = favoritesRadial.idForUnitDirection(dir[0], dir[1]);
        favoritesGesturePressMs = 0;
        favoritesPrePressDirValid = false;
        if (cb != null) cb.onFavoritesPicked(id);
    }

    /** True while the stylus tip is contacting the canvas (ink or erase). */
    public boolean isStylusTipDown() {
        return !Float.isNaN(eraseX) || activeStroke != null || inkPointerId >= 0;
    }

    /** Tip contacted during this primary-button hold (erase, not favorite). */
    public boolean wasTipUsedOnFavoritesHold() {
        return favoritesTipBlocked;
    }

    /** True when tip should erase: button held, or an erase stroke still in progress. */
    public boolean shouldTipErase() {
        if (favoritesButtonHeld || penEraseArmed) return true;
        if (restorePencilAfterEraseTip != null) return true;
        return currentTool == Tool.ERASER || eraseMode;
    }

    /**
     * Primary button released while the tip is (or will be) erasing: keep eraser until tip up.
     */
    public void keepEraserUntilTipUp(Runnable restorePencil) {
        favoritesButtonHeld = false;
        sawStylusButtonOnHover = false;
        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesGesturePressMs = 0;
        favoritesPrePressDirValid = false;
        cancelFavoritesShow();
        cancelFavoritesFinalize();
        clearRadialTipPress();
        if (favoritesRadial.isOpen()) {
            favoritesRadial.cancel();
            invalidate();
        }
        restorePencilAfterEraseTip = restorePencil;
        if (!isStylusTipDown()) {
            // Tip is off the screen: back to the pencil right away, no linger.
            fireRestorePencilAfterEraseTip();
            return;
        }
        penEraseArmed = true;
        currentTool = Tool.ERASER;
        eraseMode = true;
    }

    /** Favorite was activated — drop erase arm. */
    public void clearPenEraseArm() {
        penEraseArmed = false;
        penEraseArmMs = 0;
        restorePencilAfterEraseTip = null;
        favoritesTipBlocked = false;
    }

    private void fireRestorePencilAfterEraseTip() {
        Runnable r = restorePencilAfterEraseTip;
        restorePencilAfterEraseTip = null;
        penEraseArmed = false;
        penEraseArmMs = 0;
        favoritesTipBlocked = false;
        if (r != null) r.run();
    }

    /** @deprecated use {@link #finishFavoritesRadialPickDelayed} */
    public String finishFavoritesRadialPick() {
        clearRadialTipPress();
        cancelFavoritesShow();
        favoritesArmed = false;
        favoritesArmAnchored = false;
        favoritesButtonHeld = false;
        sawStylusButtonOnHover = false;
        favoritesTipBlocked = false;
        if (!favoritesRadial.isOpen()) return null;
        String id = favoritesRadial.closeAndPick();
        favoritesGesturePressMs = 0;
        favoritesPrePressDirValid = false;
        invalidate();
        return id;
    }

    private String radialTipPendingId;
    private float radialTipDownX;
    private float radialTipDownY;
    private boolean radialTipLongFired;
    private boolean radialTipActive;
    private boolean radialTipFlickPicked;
    private Runnable radialTipLongPress;
    /** Undo/redo icon the tip went down on; sliding sideways from it scrubs history. */
    private String radialScrubCandidate;
    private boolean radialScrubActive;
    private float radialScrubOriginX;

    private static boolean isUndoRedoFavorite(String id) {
        return FavoritesStore.TOOL_UNDO.equals(id) || FavoritesStore.TOOL_REDO.equals(id);
    }

    private void onRadialTipLongPress() {
        if (!favoritesRadial.isOpen() || radialTipPendingId == null) return;
        radialTipLongFired = true;
        String id = radialTipPendingId;
        radialTipPendingId = null;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        if (listener != null) listener.onFavoritesRadialRemoveRequested(id);
    }

    private void clearRadialTipPress() {
        if (radialTipLongPress != null) removeCallbacks(radialTipLongPress);
        radialTipPendingId = null;
        radialTipLongFired = false;
        radialTipActive = false;
        radialTipFlickPicked = false;
        radialScrubCandidate = null;
    }

    private void commitRadialPick(String id) {
        if (id == null) return;
        clearRadialTipPress();
        favoritesRadial.cancel();
        invalidate();
        if (listener != null) listener.onFavoritesRadialPicked(id);
    }

    /** Where the pen last was over this view (hover or contact), view px; NaN if never. */
    private float lastPenX = Float.NaN;
    private float lastPenY = Float.NaN;
    /** When the pen last hovered here; the menu only opens where the pen really is. */
    private long lastPenHoverMs;

    /** The pen's last position in this view's coordinates, or null if none seen yet. */
    public float[] getLastPenPoint() {
        if (Float.isNaN(lastPenX)) return null;
        return new float[]{lastPenX, lastPenY};
    }

    private static boolean hoverPoint(MotionEvent event, float[] outXy) {
        if (event == null || event.getPointerCount() <= 0) return false;
        outXy[0] = event.getX(0);
        outXy[1] = event.getY(0);
        return true;
    }

    private static boolean stylusButtonDown(MotionEvent event) {
        if (event == null) return false;
        int buttons = event.getButtonState();
        return (buttons & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                || (buttons & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0;
    }

    private int lastStylusButtonState;
    private final float[] hoverTmp = new float[2];

    /** Report primary/secondary barrel edges to the host; keep favorites-arm in sync. */
    private void dispatchStylusButtons(MotionEvent event) {
        if (event == null) return;
        int action = event.getActionMasked();
        // Some stacks deliver button edges as BUTTON_PRESS/RELEASE without a move.
        if (action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            int ab = event.getActionButton();
            boolean down = action == MotionEvent.ACTION_BUTTON_PRESS;
            if (ab == MotionEvent.BUTTON_STYLUS_PRIMARY) {
                if (listener != null) listener.onStylusButton(true, down);
                if (down) {
                    lastStylusButtonState |= MotionEvent.BUTTON_STYLUS_PRIMARY;
                    sawStylusButtonOnHover = true;
                } else {
                    lastStylusButtonState &= ~MotionEvent.BUTTON_STYLUS_PRIMARY;
                }
            } else if (ab == MotionEvent.BUTTON_STYLUS_SECONDARY) {
                if (listener != null) listener.onStylusButton(false, down);
                if (down) {
                    lastStylusButtonState |= MotionEvent.BUTTON_STYLUS_SECONDARY;
                    sawStylusButtonOnHover = true;
                } else {
                    lastStylusButtonState &= ~MotionEvent.BUTTON_STYLUS_SECONDARY;
                }
            }
            if (!down && (lastStylusButtonState
                    & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) == 0) {
                favoritesArmed = false;
                favoritesArmAnchored = false;
                // favoritesButtonHeld is owned by the host key arm/release path
                // (PAGE_DOWN). Clearing it here aborted the flick menu whenever the
                // hover stream briefly dropped BUTTON_STYLUS_PRIMARY.
                sawStylusButtonOnHover = false;
            }
            return;
        }

        int buttons = event.getButtonState();
        final int primaryMask = MotionEvent.BUTTON_STYLUS_PRIMARY;
        final int secondaryMask = MotionEvent.BUTTON_STYLUS_SECONDARY;
        boolean wasPrimary = (lastStylusButtonState & primaryMask) != 0;
        boolean wasSecondary = (lastStylusButtonState & secondaryMask) != 0;
        boolean isPrimary = (buttons & primaryMask) != 0;
        boolean isSecondary = (buttons & secondaryMask) != 0;
        if (isPrimary != wasPrimary && listener != null) {
            listener.onStylusButton(true, isPrimary);
        }
        if (isSecondary != wasSecondary && listener != null) {
            listener.onStylusButton(false, isSecondary);
        }
        boolean any = isPrimary || isSecondary;
        if (any) sawStylusButtonOnHover = true;
        // Hover button bits dropped — do not clear favoritesButtonHeld (host-owned).
        if (sawStylusButtonOnHover && !any) {
            favoritesArmed = false;
            favoritesArmAnchored = false;
            sawStylusButtonOnHover = false;
        }
        lastStylusButtonState = buttons;
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (event == null) return false;
        int action = event.getActionMasked();
        boolean hasPt = hoverPoint(event, hoverTmp);
        if (hasPt) {
            lastPenX = hoverTmp[0];
            lastPenY = hoverTmp[1];
            lastPenHoverMs = android.os.SystemClock.uptimeMillis();
            long now = android.os.SystemClock.uptimeMillis();
            if (prevHoverMs > 0) {
                float dt = (now - prevHoverMs) / 1000f;
                float ddx = hoverTmp[0] - prevHoverX;
                float ddy = hoverTmp[1] - prevHoverY;
                if (dt > 0.0005f && dt < 0.2f) {
                    hoverVelX = ddx / dt;
                    hoverVelY = ddy / dt;
                }
            }
            prevHoverX = lastHoverX = hoverTmp[0];
            prevHoverY = lastHoverY = hoverTmp[1];
            prevHoverMs = now;
            pushHoverTrail(lastHoverX, lastHoverY, now);
        }

        dispatchStylusButtons(event);
        boolean stylusBtn = stylusButtonDown(event);

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                if (hasPt) hoverInside = true;
                if (favoritesRadial.isOpen() && hasPt) {
                    // Enlarge whatever item the pen is over; nothing is picked by hovering.
                    favoritesRadial.hoverAt(lastHoverX, lastHoverY);
                    invalidate();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_HOVER_EXIT: {
                hoverInside = false;
                // While the primary button is held, keep the pending flick menu.
                // Cancelling here meant a brief HOVER_EXIT (common on tip-near / button
                // hold) permanently killed the radial for that press.
                if (!favoritesButtonHeld) {
                    favoritesArmed = false;
                    favoritesArmAnchored = false;
                    cancelFavoritesShow();
                }
                hoverVelX = 0;
                hoverVelY = 0;
                prevHoverMs = 0;
                // Do NOT cancel an open radial here: tip-down is preceded by HOVER_EXIT.
                break;
            }
            default:
                break;
        }
        // Do not call super: View.setHovered → drawable-state refresh has been flashing
        // the whole canvas on HOVER_ENTER/EXIT on this tablet (OEM + shared backdrop).
        // Hover tracking above is self-contained.
        return true;
    }

    /** Stamp a finished stroke onto the live backdrop so the next stroke stays on the fast path. */
    private void commitStrokeToBackdrop(Stroke s) {
        if (s == null) return;
        if (!sceneBackdropReady || sceneBackdrop == null || sceneBackdrop.isRecycled()
                || sceneBackdropDirty) {
            // Not in the backdrop until its redraw lands; draw it live till then so
            // the finished stroke does not blink out after the pen lifts.
            deselectedStrokes.add(s);
            markSceneDirty();
            return;
        }
        releaseBackdropHw();  // drawn in place — the GPU copy no longer matches
        resetBands();
        sceneBackdropCanvas.setBitmap(sceneBackdrop);
        sceneBackdropCanvas.save();
        sceneBackdropCanvas.translate(overscanX, overscanY);
        sceneBackdropCanvas.concat(viewMatrix);
        drawStroke(sceneBackdropCanvas, s);
        sceneBackdropCanvas.restore();
    }

    private boolean hasSelection() {
        return !selectedStrokes.isEmpty() || !selectedImages.isEmpty()
                || !selectedTextFields.isEmpty();
    }

    public boolean canAddRegionToChat() {
        return hasSelection() || hasLassoRegion();
    }

    private boolean hitSelection(float screenX, float screenY) {
        if (!hasSelection()) return false;
        float[] w = screenToWorld(screenX, screenY);
        float wx = w[0], wy = w[1];
        float hitR = 48f / viewScale();

        for (CanvasImage img : selectedImages) {
            if (img.contains(wx, wy)) return true;
        }
        for (CanvasTextField tf : selectedTextFields) {
            if (tf.contains(wx, wy)) return true;
        }
        for (Stroke s : selectedStrokes) {
            if (s.hit(wx, wy, hitR)) return true;
        }

        RectF bounds = selectionBoundsWorld();
        if (bounds == null) return false;
        RectF hit = new RectF(bounds);
        float pad = 64f / viewScale();
        hit.inset(-pad, -pad);
        return hit.contains(wx, wy);
    }

    private boolean selectionSupportsBoxResize() {
        if (!selectedTextFields.isEmpty() && selectionIsOnlyContentBlocks()) {
            return false;
        }
        return selectedStrokes.isEmpty() && selectedImages.isEmpty()
                && !selectedTextFields.isEmpty();
    }

    /** True when the selection is one or more content blocks and nothing else. */
    boolean selectionIsOnlyContentBlocks() {
        if (selectedTextFields.isEmpty()) return false;
        if (!selectedStrokes.isEmpty() || !selectedImages.isEmpty()) {
            return false;
        }
        for (CanvasTextField tf : selectedTextFields) {
            if (!tf.contentBlock) return false;
        }
        return true;
    }

    private boolean selectionCanTransform() {
        if (selectionIsOnlyContentBlocks()) return false;
        return !selectedStrokes.isEmpty() || !selectedImages.isEmpty() || selectionSupportsBoxResize();
    }

    /** Handle half-size in world units (~18 screen px). */
    private float gimbalHitRadius() {
        return 20f / viewScale();
    }

    private float rotateHandleOffset() {
        return 72f / viewScale();
    }

    private void fillGimbalCorners(RectF b, float[] out8) {
        out8[0] = b.left;
        out8[1] = b.top;
        out8[2] = b.right;
        out8[3] = b.top;
        out8[4] = b.left;
        out8[5] = b.bottom;
        out8[6] = b.right;
        out8[7] = b.bottom;
    }

    /** Eight resize handles: 4 corners then top / bottom / left / right mid-edges. */
    private void fillBoxResizeHandles(RectF b, float[] out16) {
        fillGimbalCorners(b, out16);
        out16[8] = b.centerX();
        out16[9] = b.top;
        out16[10] = b.centerX();
        out16[11] = b.bottom;
        out16[12] = b.left;
        out16[13] = b.centerY();
        out16[14] = b.right;
        out16[15] = b.centerY();
    }

    private void setBoxResizeAnchor(int handle, RectF box) {
        switch (handle) {
            case 0:
                resizeAnchorX = box.right;
                resizeAnchorY = box.bottom;
                break;
            case 1:
                resizeAnchorX = box.left;
                resizeAnchorY = box.bottom;
                break;
            case 2:
                resizeAnchorX = box.right;
                resizeAnchorY = box.top;
                break;
            case 3:
                resizeAnchorX = box.left;
                resizeAnchorY = box.top;
                break;
            case 4:
                resizeAnchorX = box.centerX();
                resizeAnchorY = box.bottom;
                break;
            case 5:
                resizeAnchorX = box.centerX();
                resizeAnchorY = box.top;
                break;
            case 6:
                resizeAnchorX = box.right;
                resizeAnchorY = box.centerY();
                break;
            default:
                resizeAnchorX = box.left;
                resizeAnchorY = box.centerY();
                break;
        }
    }

    private final float[] gimbalCorners = new float[8];
    private final float[] gimbalHandles = new float[16];

    private void recomputeSelectionFrame() {
        if (!selectionCanTransform()) {
            selFrameValid = false;
            return;
        }
        RectF union = null;
        for (Stroke s : selectedStrokes) {
            if (s.bounds.isEmpty()) continue;
            if (union == null) union = new RectF(s.bounds);
            else union.union(s.bounds);
        }
        for (CanvasImage img : selectedImages) {
            RectF b = img.bounds();
            if (union == null) union = b;
            else union.union(b);
        }
        for (CanvasTextField tf : selectedTextFields) {
            RectF b = tf.bounds();
            if (union == null) union = b;
            else union.union(b);
        }
        if (union == null || union.isEmpty()) {
            selFrameValid = false;
            return;
        }
        selFramePivotX = union.centerX();
        selFramePivotY = union.centerY();
        float hw = union.width() * 0.5f;
        float hh = union.height() * 0.5f;
        selFrameLocalBounds.set(-hw, -hh, hw, hh);
        selFrameRotDeg = 0f;
        if (selectedImages.size() == 1 && selectedStrokes.isEmpty()) {
            selFrameRotDeg = selectedImages.get(0).rotationDeg;
        }
        selFrameValid = true;
    }

    /** Map world point into selection-local coordinates (unrotated frame). */
    private float[] worldToSelLocal(float wx, float wy) {
        float dx = wx - selFramePivotX;
        float dy = wy - selFramePivotY;
        double rad = Math.toRadians(-selFrameRotDeg);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        return new float[]{c * dx - s * dy, s * dx + c * dy};
    }

    /** @return ROTATE, SCALE, RESIZE, MOVE, or NONE */
    private SelGesture hitGimbal(float screenX, float screenY) {
        RectF bounds = selectionBoundsWorld();
        if (bounds == null) return SelGesture.NONE;
        float[] w = screenToWorld(screenX, screenY);
        float wx = w[0], wy = w[1];
        float r = gimbalHitRadius();
        float r2 = r * r;
        float pad = 8f / viewScale();
        RectF box = tmpRect;

        if (selectionSupportsBoxResize()) {
            box.set(bounds);
            box.inset(-pad, -pad);
            fillBoxResizeHandles(box, gimbalHandles);
            for (int i = 0; i < 8; i++) {
                float hx = gimbalHandles[i * 2];
                float hy = gimbalHandles[i * 2 + 1];
                float dx = wx - hx;
                float dy = wy - hy;
                if (dx * dx + dy * dy <= r2) {
                    resizeHandle = i;
                    resizeStartUnion.set(bounds);
                    setBoxResizeAnchor(i, box);
                    return SelGesture.RESIZE;
                }
            }
            RectF hit = new RectF(box);
            hit.inset(-r, -r);
            if (hit.contains(wx, wy) || hitSelection(screenX, screenY)) {
                return SelGesture.MOVE;
            }
            return SelGesture.NONE;
        }

        if (selectionCanTransform() && selFrameValid) {
            float[] local = worldToSelLocal(wx, wy);
            float lx = local[0];
            float ly = local[1];
            box.set(selFrameLocalBounds);
            box.inset(-pad, -pad);

            float cx = box.centerX();
            float rotY = box.top - rotateHandleOffset();
            float dx = lx - cx;
            float dy = ly - rotY;
            if (dx * dx + dy * dy <= r2) {
                gimbalPivotX = selFramePivotX;
                gimbalPivotY = selFramePivotY;
                gimbalPrevAngle = (float) Math.toDegrees(Math.atan2(wy - gimbalPivotY, wx - gimbalPivotX));
                return SelGesture.ROTATE;
            }
            fillGimbalCorners(box, gimbalCorners);
            for (int i = 0; i < 4; i++) {
                float hx = gimbalCorners[i * 2];
                float hy = gimbalCorners[i * 2 + 1];
                dx = lx - hx;
                dy = ly - hy;
                if (dx * dx + dy * dy <= r2) {
                    gimbalPivotX = selFramePivotX;
                    gimbalPivotY = selFramePivotY;
                    float[] cornerWorld = selLocalToWorld(hx, hy);
                    gimbalPrevDist = (float) Math.hypot(cornerWorld[0] - gimbalPivotX, cornerWorld[1] - gimbalPivotY);
                    if (gimbalPrevDist < 1f) gimbalPrevDist = 1f;
                    return SelGesture.SCALE;
                }
            }
            RectF hit = new RectF(box);
            hit.inset(-r, -r);
            if (hit.contains(lx, ly) || hitSelection(screenX, screenY)) {
                return SelGesture.MOVE;
            }
            return SelGesture.NONE;
        }

        box.set(bounds);
        box.inset(-pad, -pad);

        if (selectionCanTransform()) {
            float cx = box.centerX();
            float rotY = box.top - rotateHandleOffset();
            float dx = wx - cx;
            float dy = wy - rotY;
            if (dx * dx + dy * dy <= r2) {
                gimbalPivotX = cx;
                gimbalPivotY = box.centerY();
                gimbalPrevAngle = (float) Math.toDegrees(Math.atan2(wy - gimbalPivotY, wx - gimbalPivotX));
                return SelGesture.ROTATE;
            }
            fillGimbalCorners(box, gimbalCorners);
            for (int i = 0; i < 4; i++) {
                float hx = gimbalCorners[i * 2];
                float hy = gimbalCorners[i * 2 + 1];
                dx = wx - hx;
                dy = wy - hy;
                if (dx * dx + dy * dy <= r2) {
                    gimbalPivotX = box.centerX();
                    gimbalPivotY = box.centerY();
                    gimbalPrevDist = (float) Math.hypot(hx - gimbalPivotX, hy - gimbalPivotY);
                    if (gimbalPrevDist < 1f) gimbalPrevDist = 1f;
                    return SelGesture.SCALE;
                }
            }
        }

        RectF hit = new RectF(box);
        hit.inset(-r, -r);
        if (hit.contains(wx, wy) || hitSelection(screenX, screenY)) {
            return SelGesture.MOVE;
        }
        return SelGesture.NONE;
    }

    private float[] selLocalToWorld(float lx, float ly) {
        double rad = Math.toRadians(selFrameRotDeg);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        return new float[]{
                selFramePivotX + c * lx - s * ly,
                selFramePivotY + s * lx + c * ly
        };
    }

    private boolean beginSelectionGesture(float screenX, float screenY) {
        SelGesture g = hitGimbal(screenX, screenY);
        if (g == SelGesture.NONE) {
            selGesture = SelGesture.NONE;
            movingSelection = false;
            transformingSelection = false;
            gestureStartSnap = null;
            return false;
        }
        selGesture = g;
        movingSelection = (g == SelGesture.MOVE);
        transformingSelection = (g == SelGesture.ROTATE || g == SelGesture.SCALE || g == SelGesture.RESIZE);
        selLastScreenX = screenX;
        selLastScreenY = screenY;
        // Defer undo snapshot — copying stroke points on DOWN blocked the first MOVE.
        gestureStartSnap = null;
        contentDirty = false;
        selOverlayActive = true;
        releaseSelDragBitmap();
        // Backdrop already omits the selection (see backdropSkipSelected) — no punch.
        return true;
    }

    private boolean continueSelectionGesture(float screenX, float screenY) {
        if (selGesture == SelGesture.NONE) return false;
        if (gestureStartSnap == null) {
            gestureStartSnap = snapshotSelectionGeometry();
            // Hide floating chrome on the first MOVE, not DOWN (avoids a pre-drag flash).
            if (listener != null) listener.onNavigationChanged(true);
        }
        if (selGesture == SelGesture.MOVE) {
            float scale = viewScale();
            float sdx = screenX - selLastScreenX;
            float sdy = screenY - selLastScreenY;
            translateSelection(sdx / scale, sdy / scale);
            selDragScreenDx += sdx;
            selDragScreenDy += sdy;
            selLastScreenX = screenX;
            selLastScreenY = screenY;
            invalidate();
            // Overlays live in the host's space, so a dragged element has to be told
            // to follow. Without this the gimbals moved and the content stayed put
            // until some later event happened to reposition it.
            notifySelectionLayout();
            return true;
        }
        float[] w = screenToWorld(screenX, screenY);
        if (selGesture == SelGesture.ROTATE) {
            float angle = (float) Math.toDegrees(Math.atan2(w[1] - gimbalPivotY, w[0] - gimbalPivotX));
            float deltaDeg = angle - gimbalPrevAngle;
            if (deltaDeg > 180f) deltaDeg -= 360f;
            if (deltaDeg < -180f) deltaDeg += 360f;
            rotateScaleSelectionAround(gimbalPivotX, gimbalPivotY, deltaDeg, 1f);
            if (selFrameValid) selFrameRotDeg += deltaDeg;
            gimbalPrevAngle = angle;
            invalidate();
            notifySelectionLayout();
            return true;
        }
        if (selGesture == SelGesture.SCALE) {
            float dist = (float) Math.hypot(w[0] - gimbalPivotX, w[1] - gimbalPivotY);
            if (dist < 1f) dist = 1f;
            float scale = dist / gimbalPrevDist;
            if (scale < 0.05f) scale = 0.05f;
            if (scale > 8f) scale = 8f;
            rotateScaleSelectionAround(gimbalPivotX, gimbalPivotY, 0f, scale);
            if (selFrameValid) {
                selFrameLocalBounds.left *= scale;
                selFrameLocalBounds.top *= scale;
                selFrameLocalBounds.right *= scale;
                selFrameLocalBounds.bottom *= scale;
            }
            gimbalPrevDist = dist;
            notifySelectionLayout();
            invalidate();
            return true;
        }
        if (selGesture == SelGesture.RESIZE) {
            applyBoxResizeFromPointer(w[0], w[1]);
            invalidate();
            if (editingTextField != null) notifySelectionLayout();
            return true;
        }
        return false;
    }

    private void endSelectionGesture() {
        boolean wasActive = selGesture != SelGesture.NONE;
        boolean dirty = contentDirty;
        if (wasActive) enforceLiveAspects();
        movingSelection = false;
        transformingSelection = false;
        selGesture = SelGesture.NONE;
        resizeHandle = -1;
        releaseSelDragBitmap();
        if (wasActive && dirty && gestureStartSnap != null) {
            undoStack.addLast(gestureStartSnap);
            redoStack.clear();
            historyChanged();
            while (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
        }
        gestureStartSnap = null;
        // Selection stays as an overlay on a backdrop that omits it — no bake needed.
        selOverlayActive = hasSelection();
        if (wasActive) {
            invalidate();
            if (listener != null) listener.onNavigationChanged(false);
        }
        if (dirty) flushContentDirty();
    }

    private void drawSelectionGimbals(Canvas canvas, RectF bounds, float scale) {
        float pad = 8f / scale;
        RectF box = tmpRect;
        box.set(bounds);
        box.inset(-pad, -pad);
        float r = 10f / scale;
        gimbalStrokePaint.setStrokeWidth(2f / scale);

        int handleCount = selectionSupportsBoxResize() ? 8 : 4;
        if (handleCount == 8) fillBoxResizeHandles(box, gimbalHandles);
        else fillGimbalCorners(box, gimbalCorners);
        float[] handles = handleCount == 8 ? gimbalHandles : gimbalCorners;
        for (int i = 0; i < handleCount; i++) {
            float hx = handles[i * 2];
            float hy = handles[i * 2 + 1];
            float hr = (i >= 4) ? r * 0.85f : r;
            canvas.drawCircle(hx, hy, hr, gimbalFillPaint);
            canvas.drawCircle(hx, hy, hr, gimbalStrokePaint);
        }
        if (selectionCanTransform() && !selectionSupportsBoxResize()) {
            float cx = box.centerX();
            float rotY = box.top - rotateHandleOffset();
            canvas.drawLine(cx, box.top, cx, rotY, gimbalStrokePaint);
            canvas.drawCircle(cx, rotY, r * 1.15f, gimbalFillPaint);
            canvas.drawCircle(cx, rotY, r * 1.15f, gimbalStrokePaint);
        }
    }

    private void drawSelectionOverlay(Canvas canvas, float scale) {
        if (drawingLasso || drawingTextRect) return;
        // Content blocks carry their own card chrome — no text-field gimbals/dashes.
        if (selectionIsOnlyContentBlocks()) return;
        RectF selBounds = overlayBoundsWorld();
        if (selBounds == null && !hasSelection()) return;

        if (selFrameValid && selectionCanTransform()) {
            float pad = 8f / scale;
            selectionPaint.setStyle(Paint.Style.STROKE);
            selectionPaint.setStrokeWidth(2.5f / scale);
            selectionPaint.setColor(chromeAccent);
            selectionPaint.setPathEffect(selectionDash);
            canvas.save();
            canvas.translate(selFramePivotX, selFramePivotY);
            canvas.rotate(selFrameRotDeg);
            RectF box = tmpRect;
            box.set(selFrameLocalBounds);
            box.inset(-pad, -pad);
            canvas.drawRect(box, selectionPaint);
            selectionPaint.setPathEffect(null);
            if (hasSelection()) drawSelectionGimbals(canvas, selFrameLocalBounds, scale);
            canvas.restore();
            return;
        }

        if (selBounds == null) return;
        float pad = 8f / scale;
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(2.5f / scale);
        selectionPaint.setColor(chromeAccent);
        selectionPaint.setPathEffect(selectionDash);
        canvas.drawRect(
                selBounds.left - pad, selBounds.top - pad,
                selBounds.right + pad, selBounds.bottom + pad,
                selectionPaint);
        selectionPaint.setPathEffect(null);
        if (hasSelection()) drawSelectionGimbals(canvas, selBounds, scale);
    }

    /** Draw only selected canvas objects (used during drag overlay mode). */
    private void drawSelectedContent(Canvas canvas, float scale) {
        for (CanvasTextField tf : selectedTextFields) {
            // Drawn by the overlay editor instead; drawing it here too during a
            // move/resize leaves the pre-gesture text stranded on the canvas.
            if (tf == editingTextField) continue;
            tf.draw(canvas, true);
        }

        for (Stroke s : selectedStrokes) {
            drawStroke(canvas, s);
        }

        for (CanvasImage img : selectedImages) {
            img.draw(canvas, imagePaint);
        }
    }

    private RectF selectionBoundsWorld() {
        RectF r = null;
        for (Stroke s : selectedStrokes) {
            if (s.bounds.isEmpty()) continue;
            if (r == null) r = new RectF(s.bounds);
            else r.union(s.bounds);
        }
        for (CanvasImage img : selectedImages) {
            RectF b = img.bounds();
            if (r == null) r = b;
            else r.union(b);
        }
        for (CanvasTextField tf : selectedTextFields) {
            RectF b = tf.bounds();
            if (r == null) r = b;
            else r.union(b);
        }
        return r;
    }

    private RectF overlayBoundsWorld() {
        RectF r = selectionBoundsWorld();
        if (r != null) return r;
        if (hasLassoRegion()) return lassoRegionBounds;
        return null;
    }

    private float[] selectionCentroid() {
        float sx = 0f, sy = 0f;
        int n = 0;
        for (Stroke s : selectedStrokes) {
            if (s.bounds.isEmpty()) continue;
            sx += s.bounds.centerX();
            sy += s.bounds.centerY();
            n++;
        }
        for (CanvasImage img : selectedImages) {
            sx += img.cx;
            sy += img.cy;
            n++;
        }
        for (CanvasTextField tf : selectedTextFields) {
            sx += tf.cx;
            sy += tf.cy;
            n++;
        }
        if (n == 0) return new float[]{0f, 0f};
        return new float[]{sx / n, sy / n};
    }

    private static boolean pointInPolygon(float x, float y, List<float[]> poly) {
        int n = poly.size();
        if (n < 3) return false;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = poly.get(i)[0], yi = poly.get(i)[1];
            float xj = poly.get(j)[0], yj = poly.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi + 0f) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /** A text box is picked up when the lasso path crosses it or encloses its centre. */
    private boolean lassoTouchesTextField(CanvasTextField tf) {
        if (pointInPolygon(tf.cx, tf.cy, lassoPoints)) return true;
        for (int i = 0; i < lassoPoints.size(); i++) {
            float[] a = lassoPoints.get(i);
            if (tf.contains(a[0], a[1])) return true;
            if (i == 0) continue;
            float[] b = lassoPoints.get(i - 1);
            float len = (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
            int steps = (int) (len / 8f);
            for (int k = 1; k < steps; k++) {
                float t = k / (float) steps;
                if (tf.contains(b[0] + (a[0] - b[0]) * t, b[1] + (a[1] - b[1]) * t)) return true;
            }
        }
        return false;
    }

    private void finalizeLassoSelection() {
        selectedStrokes.clear();
        selectedImages.clear();
        selectedTextFields.clear();
        hasLassoRegion = false;
        lassoRegionBounds.setEmpty();

        if (lassoPoints.size() < 3) {
            lassoPoints.clear();
            drawingLasso = false;
            invalidate();
            notifySelectionChanged();
            if (listener != null) listener.onLassoRegionChanged(false);
            return;
        }

        for (float[] p : lassoPoints) {
            if (lassoRegionBounds.isEmpty()) {
                lassoRegionBounds.set(p[0], p[1], p[0], p[1]);
            } else {
                lassoRegionBounds.union(p[0], p[1]);
            }
        }
        hasLassoRegion = true;

        if (lassoSelectInk || lassoSelectHighlighter) {
            for (Stroke s : inkStrokes) {
                boolean target = s.brush == BRUSH_HIGHLIGHTER ? lassoSelectHighlighter : lassoSelectInk;
                if (!target) continue;
                for (Sample p : s.samples) {
                    if (pointInPolygon(p.x, p.y, lassoPoints)) {
                        selectedStrokes.add(s);
                        break;
                    }
                }
            }
        }
        if (lassoSelectImages) {
            for (CanvasImage img : images) {
                if (pointInPolygon(img.cx, img.cy, lassoPoints)) {
                    selectedImages.add(img);
                }
            }
        }
        if (lassoSelectText) {
            for (CanvasTextField tf : textFields) {
                if (lassoTouchesTextField(tf)) {
                    selectedTextFields.add(tf);
                }
            }
        }
        lassoPoints.clear();
        drawingLasso = false;
        recomputeSelectionFrame();
        selOverlayActive = false;
        markSceneDirty();
        invalidate();
        notifySelectionChanged();
        if (listener != null) listener.onLassoRegionChanged(true);
    }

    private void translateSelection(float dx, float dy) {
        if (dx == 0f && dy == 0f) return;
        for (Stroke s : selectedStrokes) {
            s.translate(dx, dy);
        }
        for (CanvasImage img : selectedImages) {
            img.cx += dx;
            img.cy += dy;
            img.markDirty();
        }
        for (CanvasTextField tf : selectedTextFields) {
            tf.cx += dx;
            tf.cy += dy;
        }
        if (selFrameValid) {
            selFramePivotX += dx;
            selFramePivotY += dy;
        }
        contentDirty = true;
    }

    private void rotateScaleSelection(float deltaDeg, float scale) {
        float[] c = selectionCentroid();
        rotateScaleSelectionAround(c[0], c[1], deltaDeg, scale);
    }

    private void applyBoxResizeFromPointer(float wx, float wy) {
        if (gestureStartSnap == null || resizeHandle < 0) return;
        float left;
        float right;
        float top;
        float bottom;
        if (resizeHandle >= 4) {
            switch (resizeHandle) {
                case 4:
                    left = resizeStartUnion.left;
                    right = resizeStartUnion.right;
                    top = wy;
                    bottom = resizeStartUnion.bottom;
                    if (bottom - top < 48f) top = bottom - 48f;
                    break;
                case 5:
                    left = resizeStartUnion.left;
                    right = resizeStartUnion.right;
                    top = resizeStartUnion.top;
                    bottom = wy;
                    if (bottom - top < 48f) bottom = top + 48f;
                    break;
                case 6:
                    left = wx;
                    right = resizeStartUnion.right;
                    top = resizeStartUnion.top;
                    bottom = resizeStartUnion.bottom;
                    if (right - left < 48f) left = right - 48f;
                    break;
                case 7:
                    left = resizeStartUnion.left;
                    right = wx;
                    top = resizeStartUnion.top;
                    bottom = resizeStartUnion.bottom;
                    if (right - left < 48f) right = left + 48f;
                    break;
                default:
                    return;
            }
        } else {
            left = Math.min(resizeAnchorX, wx);
            right = Math.max(resizeAnchorX, wx);
            top = Math.min(resizeAnchorY, wy);
            bottom = Math.max(resizeAnchorY, wy);
            if (right - left < 48f) {
                if (wx >= resizeAnchorX) right = left + 48f;
                else left = right - 48f;
            }
            if (bottom - top < 48f) {
                if (wy >= resizeAnchorY) bottom = top + 48f;
                else top = bottom - 48f;
            }
        }
        float oL = resizeStartUnion.left;
        float oT = resizeStartUnion.top;
        float oR = resizeStartUnion.right;
        float oB = resizeStartUnion.bottom;
        float oW = Math.max(1f, oR - oL);
        float oH = Math.max(1f, oB - oT);
        float nW = right - left;
        float nH = bottom - top;
        // Apply from gesture-start geometry — no full restoreGeometry() every frame.
        for (TextFieldSnap snap : gestureStartSnap.textFields) {
            float sL = snap.cx - snap.width * 0.5f;
            float sT = snap.cy - snap.height * 0.5f;
            float sR = snap.cx + snap.width * 0.5f;
            float sB = snap.cy + snap.height * 0.5f;
            float relL = (sL - oL) / oW;
            float relT = (sT - oT) / oH;
            float relR = (sR - oL) / oW;
            float relB = (sB - oT) / oH;
            snap.field.setBoundsPreview(
                    left + relL * nW,
                    top + relT * nH,
                    left + relR * nW,
                    top + relB * nH);
        }
        recomputeSelectionFrame();
        contentDirty = true;
    }

    private void rotateScaleSelectionAround(float pivotX, float pivotY, float deltaDeg, float scale) {
        if (!hasSelection()) return;
        if (Math.abs(deltaDeg) < 0.01f && Math.abs(scale - 1f) < 0.0001f) return;
        double rad = Math.toRadians(deltaDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        for (Stroke s : selectedStrokes) {
            s.rotateScaleAround(pivotX, pivotY, cos, sin, scale);
        }
        for (CanvasImage img : selectedImages) {
            float dx = (img.cx - pivotX) * scale;
            float dy = (img.cy - pivotY) * scale;
            img.cx = pivotX + cos * dx - sin * dy;
            img.cy = pivotY + sin * dx + cos * dy;
            img.width *= scale;
            img.height *= scale;
            img.rotationDeg += deltaDeg;
            img.markDirty();
        }
        contentDirty = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int penTool = event.getToolType(index);
        if (penTool == MotionEvent.TOOL_TYPE_STYLUS || penTool == MotionEvent.TOOL_TYPE_ERASER) {
            lastPenX = event.getX(index);
            lastPenY = event.getY(index);
            long now = android.os.SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                lastTipDownMs = now;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_UP) {
                lastTipUpMs = now;
            }
        }

        // Barrel buttons also arrive on the tip stream while contacting the screen.
        dispatchStylusButtons(event);

        // Any tip contact blocks opening a radial for the rest of this button hold.
        // Tip = erase when the primary button is (or was) held for erase — never a
        // favorite pick, no matter what hover gesture was in progress.
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            favoritesTipBlocked = true;
            favoritesArmed = false;
            favoritesArmAnchored = false;
            cancelFavoritesShow();
            if (favoritesButtonHeld || restorePencilAfterEraseTip != null
                    || shouldTipErase()) {
                favoritesGesturePressMs = 0;
                favoritesPrePressDirValid = false;
                clearRadialTipPress();
                        if (favoritesRadial.isOpen()) {
                    favoritesRadial.cancel();
                    invalidate();
                }
                currentTool = Tool.ERASER;
                eraseMode = true;
                penEraseArmed = true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_UP) {
            if (!favoritesButtonHeld) favoritesTipBlocked = false;
        }

        if (swallowPenUntilUp) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                swallowPenUntilUp = false;
            }
            return true;
        }

        // Sliding off the radial's undo/redo icon scrubs history until the tip lifts.
        if (radialScrubActive) {
            if (action == MotionEvent.ACTION_MOVE) {
                if (listener != null) {
                    listener.onUndoScrubDrag(event.getX(index) - radialScrubOriginX);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                radialScrubActive = false;
                if (listener != null) listener.onUndoScrubEnd();
            }
            return true;
        }

        // Radial tip: long-press removes; short tap on an icon activates;
        // otherwise highlight follows the tip and the pen-button release commits.
        // Skip while erasing — tip contact must punch ink, not steal the gesture.
        if (favoritesRadial.isOpen()
                && currentTool != Tool.ERASER && !eraseMode && !favoritesButtonHeld) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                favoritesArmed = false;
                favoritesArmAnchored = false;
                float sx = event.getX(index);
                float sy = event.getY(index);
                clearRadialTipPress();
                radialTipActive = true;
                radialTipDownX = sx;
                radialTipDownY = sy;
                radialTipLongFired = false;
                radialTipFlickPicked = false;
                String hit = favoritesRadial.pickAt(sx, sy);
                if (hit == null) {
                    // Tapped beside the items: just close the menu, draw nothing.
                    clearRadialTipPress();
                    favoritesRadial.cancel();
                    swallowPenUntilUp = true;
                    invalidate();
                    return true;
                }
                radialTipPendingId = hit;
                radialScrubCandidate = isUndoRedoFavorite(hit) ? hit : null;
                postDelayed(radialTipLongPress, ViewConfiguration.getLongPressTimeout());
                favoritesRadial.hoverAt(sx, sy);
                invalidate();
                return true;
            } else if (radialTipActive
                    && (action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_UP)) {
                float sx = event.getX(index);
                float sy = event.getY(index);
                if (action == MotionEvent.ACTION_MOVE && radialScrubCandidate != null
                        && !radialTipLongFired) {
                    float dx = sx - radialTipDownX;
                    float dy = sy - radialTipDownY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        // Mostly sideways: hand over to the history scrubber.
                        clearRadialTipPress();
                        favoritesRadial.cancel();
                        invalidate();
                        radialScrubActive = true;
                        radialScrubOriginX = radialTipDownX + Math.signum(dx) * touchSlop;
                        if (listener != null) {
                            listener.onUndoScrubStart(radialTipDownX, radialTipDownY);
                            listener.onUndoScrubDrag(sx - radialScrubOriginX);
                        }
                        return true;
                    }
                    if (Math.abs(dy) > touchSlop) radialScrubCandidate = null;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    favoritesRadial.hoverAt(sx, sy);
                    invalidate();
                    // Moving off the pressed icon cancels a pending long-press remove,
                    // but keeps the menu open — highlight is the selection.
                    if (radialTipPendingId != null && !radialTipLongFired) {
                        float dx = sx - radialTipDownX;
                        float dy = sy - radialTipDownY;
                        float slop = touchSlop;
                        if (dx * dx + dy * dy > slop * slop) {
                            if (radialTipLongPress != null) removeCallbacks(radialTipLongPress);
                            radialTipPendingId = null;
                        }
                    }
                    return true;
                }
                boolean longFired = radialTipLongFired;
                String id = radialTipPendingId;
                clearRadialTipPress();
                if (longFired) return true;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                    // Lifted still on the item it went down on: pick it.
                    if (id != null && id.equals(favoritesRadial.pickAt(sx, sy))) {
                        commitRadialPick(id);
                    }
                    return true;
                }
                return true;
            }
        }

        // Any press cancels an in-flight fling immediately (all tools / pointers).
        // Record the interrupt before clearing scroller state — ACTION_DOWN must not
        // treat this as a cold tap (that blits the pre-scroll backdrop while held).
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (overscrollSnapping) cancelOverscrollSnap();
            if (panFlinging || !panScroller.isFinished()) {
                interruptedPanFling = true;
                stopPanFling();
            }
        }

        // During an active ink gesture, tool type must come from the stylus pointer —
        // ACTION_MOVE's action index is usually 0 (often a palm/finger).
        int toolIndex = index;
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL)
                && inkPointerId >= 0) {
            int pi = inkPointerIndex(event);
            if (pi >= 0) toolIndex = pi;
        }
        int toolType = event.getToolType(toolIndex);
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusAtMs = System.currentTimeMillis();
        } else if (rejectAsPalm(event, toolType)) {
            // Swallow it: returning true keeps the gesture from reaching pan/zoom.
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            // Skip input buffering so pan/stylus samples arrive with lower latency.
            try {
                requestUnbufferedDispatch(event);
            } catch (Throwable ignored) {
            }
            // The overlay EditText keeps focus when the canvas is touched, so tapping
            // away has to dismiss it explicitly. The move/resize handles sit outside the
            // field, so grabbing one must not count as tapping away.
            if (editingTextField != null && listener != null) {
                float[] w = screenToWorld(event.getX(index), event.getY(index));
                boolean onHandle =
                        hitGimbal(event.getX(index), event.getY(index)) != SelGesture.NONE;
                if (!onHandle && !editingTextField.contains(w[0], w[1])) {
                    listener.onTextFieldEditDismissRequested();
                }
            }
        }

        boolean stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER
                || (activeStroke != null && inkPointerId >= 0);
        boolean hardwareEraser = toolType == MotionEvent.TOOL_TYPE_ERASER;

        boolean erasing = (currentTool == Tool.ERASER || eraseMode) || hardwareEraser;

        if (!document.isOpen() && stylus) {
            return true;
        }

        // Putting the pen down outside the selection with any tool but the lasso ends
        // the selection, leaving the items where they are; the stroke carries on.
        // The lasso handles its own selection below.
        if (stylus && action == MotionEvent.ACTION_DOWN
                && (currentTool != Tool.LASSO || hardwareEraser)
                && (hasSelection() || hasLassoRegion())) {
            float sx = event.getX(index);
            float sy = event.getY(index);
            if (!hitSelection(sx, sy) && hitGimbal(sx, sy) == SelGesture.NONE) {
                clearSelection();
            }
        }

        if (currentTool == Tool.TEXT && !hardwareEraser) {
            handleTextFinger(event, action);
            return true;
        }

        if (currentTool == Tool.LASSO && stylus && !hardwareEraser) {
            float sx = event.getX(index);
            float sy = event.getY(index);
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (hasSelection() && beginSelectionGesture(sx, sy)) {
                    drawingLasso = false;
                    lassoPoints.clear();
                    return true;
                }
            }
            if (selGesture != SelGesture.NONE) {
                float mx = event.getX(0);
                float my = event.getY(0);
                if (action == MotionEvent.ACTION_MOVE) {
                    continueSelectionGesture(mx, my);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_POINTER_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    endSelectionGesture();
                    return true;
                }
            }
            handleLassoDraw(event, action, index);
            return true;
        }

        if (!stylus) {
            // Stepping into a placed element is checked before anything else: the first
            // tap selects it, and from then on the selection handler eats every touch,
            // so a GestureDetector would never see the second one.
            if (action == MotionEvent.ACTION_DOWN) {
                // Clear first: the scrim the host puts up swallows the rest of the
                // entering gesture, so the UP that would have reset this never arrives
                // and every later finger touch was dropped on the floor.
                swallowingLiveEnter = false;
                if (liveArtifactDoubleTap(event.getX(index), event.getY(index))) {
                    swallowingLiveEnter = true;
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                noteLiveTapRelease(
                        action == MotionEvent.ACTION_UP,
                        event.getX(index),
                        event.getY(index));
            }
            if (swallowingLiveEnter) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    swallowingLiveEnter = false;
                }
                return true;
            }
            if (hasSelection() && handleSelectionTouch(event, action)) {
                return true;
            }
            gestureDetector.onTouchEvent(event);
            scaleDetector.onTouchEvent(event);
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    // interruptedPanFling may already be set by the press-to-stop hook above.
                    boolean wasFlinging = interruptedPanFling
                            || panFlinging
                            || !panScroller.isFinished();
                    stopPanFling();
                    if (panVelocityTracker != null) panVelocityTracker.recycle();
                    panVelocityTracker = VelocityTracker.obtain();
                    panVelocityTracker.addMovement(event);
                    suppressFingerNav = false;
                    fingerPanArmed = false;
                    pinchChangedScale = false;
                    pinchStartScale = camScale;
                    interruptedPanFling = wasFlinging;
                    fingerDownX = event.getX();
                    fingerDownY = event.getY();
                    lastFocusX = fingerDownX;
                    lastFocusY = fingerDownY;
                    removeCallbacks(fingerLongPressRunnable);
                    // Do not cancel warmBackdropRunnable — that aborted sharp PDF
                    // upgrades when the next touch arrived before the idle rebuild.
                    postDelayed(fingerLongPressRunnable,
                            ViewConfiguration.getLongPressTimeout());
                    if (wasFlinging) {
                        // Stay on the live-nav draw path while the finger is held —
                        // dropping navigating here would blit the pre-scroll backdrop.
                        setNavigating(true);
                        beginNavigation();
                        invalidate();
                    } else {
                        setNavigating(false);
                        beginNavigation();
                    }
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    stopPanFling();
                    // Fingers spreading is not travel: drop the history so a pinch
                    // cannot be read as a flick when the hand comes off.
                    if (panVelocityTracker != null) panVelocityTracker.clear();
                    interruptedPanFling = false;
                    removeCallbacks(fingerLongPressRunnable);
                    if (!suppressFingerNav) {
                        fingerPanArmed = true;
                        setNavigating(true);
                        beginNavigation();
                        setNavFocus(event, -1);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (panVelocityTracker != null) panVelocityTracker.addMovement(event);
                    if (suppressFingerNav) break;
                    if (!fingerPanArmed && event.getPointerCount() == 1) {
                        float dx = event.getX() - fingerDownX;
                        float dy = event.getY() - fingerDownY;
                        // Use touch-slop so a steady long-press is not cancelled by noise.
                        // After stopping a coast, still require the same slop.
                        float armSlop = touchSlop;
                        if (dx * dx + dy * dy < armSlop * armSlop) break;
                        // Movement cancels long-press; pan starts from the down point
                        // so the first frame includes the motion that armed it.
                        removeCallbacks(fingerLongPressRunnable);
                        interruptedPanFling = false;
                        fingerPanArmed = true;
                        setNavigating(true);
                        if (!navSnapshotActive) beginNavigation();
                        lastFocusX = fingerDownX;
                        lastFocusY = fingerDownY;
                    }
                    if (navigating && event.getPointerCount() > 0) {
                        float[] focus = navFocus(event, -1);
                        float dx = focus[0] - lastFocusX;
                        float dy = focus[1] - lastFocusY;
                        // Pages that fit the width stay centered — ignore horizontal pan.
                        if (documentFitsHorizontally() || pinchScaling) dx = 0f;
                        // During pinch, onScale usually already applied focus pan + scale and
                        // synced lastFocus — residual is ~0. If onScale skipped this event
                        // (pure two-finger pan), apply the leftover translate here.
                        if (dx != 0f || dy != 0f) {
                            panCameraBy(dx, dy);
                            clampDocumentCamera();
                            lastFocusX = focus[0];
                            lastFocusY = focus[1];
                            invalidate();
                        } else {
                            lastFocusX = focus[0];
                            lastFocusY = focus[1];
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (panVelocityTracker != null) panVelocityTracker.clear();
                    if (!suppressFingerNav) setNavFocus(event, event.getActionIndex());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(fingerLongPressRunnable);
                    removeCallbacks(freezeNavigationRunnable);
                    boolean wasNav = navigating || navSnapshotActive;
                    float vx = 0f;
                    float vy = 0f;
                    if (panVelocityTracker != null) {
                        panVelocityTracker.addMovement(event);
                        if (action == MotionEvent.ACTION_UP && fingerPanArmed && !suppressFingerNav) {
                            panVelocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                            vx = panVelocityTracker.getXVelocity();
                            vy = panVelocityTracker.getYVelocity();
                        }
                        panVelocityTracker.recycle();
                        panVelocityTracker = null;
                    }
                    boolean tapStoppedFling = interruptedPanFling && !fingerPanArmed;
                    boolean committedPage = false;
                    if (action == MotionEvent.ACTION_UP && !tapStoppedFling) {
                        committedPage = finishOverscrollOnRelease();
                    }
                    boolean pastRest = documentPastRestBottom() > 0.5f
                            || documentPastRestTop() > 0.5f
                            || overscrollCharge > 0.01f;
                    fingerPanArmed = false;
                    interruptedPanFling = false;
                    suppressFingerNav = false;
                    pinchScaling = false;
                    // Never fling out of an incomplete overscroll — always ease back.
                    // Nor out of a pinch: the tracked finger travels fast while the
                    // hand spreads, so releasing quickly handed that speed to the
                    // scroller and the page drifted on after the zoom before clamping
                    // back. Releasing slowly left no velocity, which is why holding
                    // still at the end always looked fine.
                    if (action == MotionEvent.ACTION_UP
                            && wasNav
                            && !pinchChangedScale
                            && !tapStoppedFling
                            && !committedPage
                            && !pastRest
                            && (Math.abs(vx) > minFlingVelocity || Math.abs(vy) > minFlingVelocity)) {
                        startPanFling(vx, vy);
                    } else {
                        setNavigating(false);
                        endNavigation();
                        if (wasNav || tapStoppedFling || committedPage || pastRest) invalidate();
                    }
                    break;
                }
            }
            return true;
        }

        if (erasing) {
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                settleNavigationForInk();
            }
            handleErase(event, action, index);
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (activeStroke != null) break;
                settleNavigationForInk();
                beginInkPointer(event, index);
                float[] w = screenToWorld(event.getX(index), event.getY(index));
                drawBaseline = null;
                activeStroke = new Stroke(inkColor, inkName);
                activeStroke.brush = brush;
                cancelShapeHold();
                addSample(activeStroke, w[0], w[1], pressureOf(event, index, -1));
                armShapeHold(activeStroke, event.getX(index), event.getY(index));
                postInvalidateOnAnimation();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activeStroke == null) break;
                int pi = inkPointerIndex(event);
                if (pi < 0) break;
                if (snapBase != null) {
                    // Snapped and still held: the pen now resizes the shape.
                    float[] w = screenToWorld(event.getX(pi), event.getY(pi));
                    resizeSnappedShape(w[0], w[1]);
                    break;
                }
                for (int i = 0; i < event.getHistorySize(); i++) {
                    float[] w = screenToWorld(event.getHistoricalX(pi, i), event.getHistoricalY(pi, i));
                    addSample(activeStroke, w[0], w[1], pressureOf(event, pi, i));
                }
                float[] w = screenToWorld(event.getX(pi), event.getY(pi));
                addSample(activeStroke, w[0], w[1], pressureOf(event, pi, -1));
                trackShapeHold(activeStroke, event.getX(pi), event.getY(pi));
                postInvalidateOnAnimation();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                // Ignore other fingers lifting while the stylus stroke is active.
                if (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(index) != inkPointerId) {
                    break;
                }
                // One sample is a legitimate mark — a dot, dotting an i, a decimal
                // point. Requiring two threw every tap away after drawing it.
                cancelShapeHold();
                // A deliberate dot ends with ACTION_UP. A touch the system cancels before it
                // moved is not a mark: when one pen shows up as two input devices, the first
                // is cancelled the instant the second starts, and committing it left a stray
                // one-point stroke (a dot) under the start of every real stroke.
                boolean cancelledBeforeMoving = action == MotionEvent.ACTION_CANCEL
                        && activeStroke != null && activeStroke.samples.size() < 2;
                if (activeStroke != null && !activeStroke.samples.isEmpty() && !cancelledBeforeMoving) {
                    commitInkTip(activeStroke);
                    final Stroke done = activeStroke;
                    done.recomputeBounds();
                    // Snapshot before add so undo removes this stroke.
                    recordUndoPoint();
                    inkStrokes.add(done);
                    commitStrokeToBackdrop(done);
                    post(this::notifyContentChanged);
                }
                drawBaseline = null;
                activeStroke = null;
                clearInkPointer();
                flushPendingPdfReady();
                postInvalidateOnAnimation();
                break;
            }
        }
        return true;
    }

    private void handleLassoDraw(MotionEvent event, int action, int index) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                drawingLasso = true;
                lassoPoints.clear();
                float[] w = screenToWorld(event.getX(index), event.getY(index));
                lassoPoints.add(new float[]{w[0], w[1]});
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!drawingLasso) break;
                int pi = event.findPointerIndex(event.getPointerId(index));
                if (pi < 0) pi = 0;
                for (int i = 0; i < event.getHistorySize(); i++) {
                    float[] w = screenToWorld(event.getHistoricalX(pi, i), event.getHistoricalY(pi, i));
                    appendLassoPoint(w[0], w[1]);
                }
                float[] w = screenToWorld(event.getX(pi), event.getY(pi));
                appendLassoPoint(w[0], w[1]);
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drawingLasso) finalizeLassoSelection();
                break;
        }
    }

    private void appendLassoPoint(float x, float y) {
        if (!lassoPoints.isEmpty()) {
            float[] last = lassoPoints.get(lassoPoints.size() - 1);
            float dx = x - last[0];
            float dy = y - last[1];
            if (dx * dx + dy * dy < 4f) return;
        }
        lassoPoints.add(new float[]{x, y});
    }

    /** Size of the text box a plain tap creates with the text tool (world units). */
    private static final float TAP_TEXT_BOX_W = 320f;
    private static final float TAP_TEXT_BOX_H = 64f;

    private void handleTextFinger(MotionEvent event, int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float[] w = screenToWorld(event.getX(), event.getY());
                drawingTextRect = true;
                textRectStartX = textRectEndX = w[0];
                textRectStartY = textRectEndY = w[1];
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!drawingTextRect) break;
                float[] w = screenToWorld(event.getX(), event.getY());
                textRectEndX = w[0];
                textRectEndY = w[1];
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!drawingTextRect) break;
                drawingTextRect = false;
                float left = Math.min(textRectStartX, textRectEndX);
                float top = Math.min(textRectStartY, textRectEndY);
                float width = Math.abs(textRectEndX - textRectStartX);
                float height = Math.abs(textRectEndY - textRectStartY);
                invalidate();
                if (width > 12f && height > 12f) {
                    addTextFieldRect(left, top, width, height);
                    if (listener != null) listener.onTextFieldRectCreated();
                } else {
                    // A tap: a fixed-size box with its top-left corner at the tap.
                    addTextFieldRect(textRectStartX, textRectStartY, TAP_TEXT_BOX_W, TAP_TEXT_BOX_H);
                    if (listener != null) listener.onTextFieldRectCreated();
                }
                break;
            }
        }
    }

    /** Finger/stylus on selection: move body, or drag gimbals to rotate/scale. */
    private boolean handleSelectionTouch(MotionEvent event, int action) {
        if (!hasSelection()) return false;

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                return beginSelectionGesture(event.getX(), event.getY());
            }
            case MotionEvent.ACTION_MOVE: {
                if (selGesture == SelGesture.NONE) return false;
                return continueSelectionGesture(event.getX(), event.getY());
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean handled = selGesture != SelGesture.NONE;
                endSelectionGesture();
                return handled;
            }
            default:
                return selGesture != SelGesture.NONE;
        }
    }

    private void handleErase(MotionEvent event, int action, int index) {
        float scale = viewScale();
        float radius = eraseRadiusPx / scale;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                eraseBaseline = null;
                eraseDidRemove = false;
                eraseDirtyValid = false;
                beginInkPointer(event, index);
                // Freeze a clean backdrop before punching / region rebuilds.
                if (sceneBackdropDirty || !sceneBackdropReady) {
                    rebuildSceneBackdropNow(/*skipSelected*/ false);
                } else {
                    ensureSceneBackdrop(getWidth(), getHeight());
                }
                int pi = event.findPointerIndex(event.getPointerId(index));
                if (pi < 0) pi = 0;
                float[] w = screenToWorld(event.getX(pi), event.getY(pi));
                eraseX = w[0];
                eraseY = w[1];
                if (eraseAt(w[0], w[1], radius)) {
                    eraseDidRemove = true;
                    contentDirty = true;
                    applyEraseVisualUpdate(radius);
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int pi = event.findPointerIndex(event.getPointerId(index));
                if (pi < 0) pi = 0;
                boolean removed = false;
                eraseDirtyValid = false;
                int hist = event.getHistorySize();
                int step = hist > 12 ? 2 : 1;
                for (int i = 0; i < hist; i += step) {
                    float[] w = screenToWorld(event.getHistoricalX(pi, i), event.getHistoricalY(pi, i));
                    eraseX = w[0];
                    eraseY = w[1];
                    removed |= eraseAt(w[0], w[1], radius);
                }
                float[] w = screenToWorld(event.getX(pi), event.getY(pi));
                eraseX = w[0];
                eraseY = w[1];
                removed |= eraseAt(w[0], w[1], radius);
                if (removed) {
                    eraseDidRemove = true;
                    contentDirty = true;
                    applyEraseVisualUpdate(radius);
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                eraseX = eraseY = Float.NaN;
                if (eraseDidRemove && eraseBaseline != null) {
                    undoStack.addLast(eraseBaseline);
                    redoStack.clear();
                    historyChanged();
                    while (undoStack.size() > MAX_UNDO) undoStack.removeFirst();
                }
                // One clean full rebuild after the gesture — not per-move.
                if (eraseDidRemove) markSceneDirty();
                eraseBaseline = null;
                eraseDidRemove = false;
                eraseDirtyValid = false;
                flushContentDirty();
                notifySelectionChanged();
                clearInkPointer();
                fireRestorePencilAfterEraseTip();
                flushPendingPdfReady();
                invalidate();
                break;
        }
    }

    /** Redraw only the dirty screen region instead of the whole canvas. */
    private void applyEraseVisualUpdate(float worldRadius) {
        if (!eraseDirtyValid) {
            markSceneDirty();
            return;
        }
        // Pad for stroke width / brush.
        float pad = worldRadius + 8f;
        eraseDirtyWorld.inset(-pad, -pad);
        rebuildBackdropRegion(eraseDirtyWorld);
        eraseDirtyValid = false;
    }

    private void rebuildBackdropRegion(RectF worldDirty) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureSceneBackdrop(w, h);
        if (sceneBackdrop == null) {
            markSceneDirty();
            return;
        }
        if (!sceneBackdropReady || sceneBackdropDirty) {
            rebuildSceneBackdropNow(backdropSkipSelected());
            return;
        }

        eraseMapPts[0] = worldDirty.left;
        eraseMapPts[1] = worldDirty.top;
        eraseMapPts[2] = worldDirty.right;
        eraseMapPts[3] = worldDirty.top;
        eraseMapPts[4] = worldDirty.right;
        eraseMapPts[5] = worldDirty.bottom;
        eraseMapPts[6] = worldDirty.left;
        eraseMapPts[7] = worldDirty.bottom;
        viewMatrix.mapPoints(eraseMapPts);
        float minX = eraseMapPts[0], minY = eraseMapPts[1];
        float maxX = eraseMapPts[0], maxY = eraseMapPts[1];
        for (int i = 2; i < 8; i += 2) {
            minX = Math.min(minX, eraseMapPts[i]);
            minY = Math.min(minY, eraseMapPts[i + 1]);
            maxX = Math.max(maxX, eraseMapPts[i]);
            maxY = Math.max(maxY, eraseMapPts[i + 1]);
        }
        int padPx = 4;
        // Screen coords shift by the overscan margin to land in bitmap space.
        eraseDirtyScreen.set(
                (int) Math.floor(minX) - padPx + overscanX,
                (int) Math.floor(minY) - padPx + overscanY,
                (int) Math.ceil(maxX) + padPx + overscanX,
                (int) Math.ceil(maxY) + padPx + overscanY);
        if (!eraseDirtyScreen.intersect(
                0, 0, sceneBackdrop.getWidth(), sceneBackdrop.getHeight())) {
            return;
        }

        releaseBackdropHw();  // repaired in place — the GPU copy no longer matches
        resetBands();
        sceneBackdropCanvas.setBitmap(sceneBackdrop);
        sceneBackdropCanvas.save();
        sceneBackdropCanvas.clipRect(eraseDirtyScreen);
        sceneBackdropCanvas.drawColor(sceneBgColor);
        sceneBackdropCanvas.translate(overscanX, overscanY);
        sceneCullOverride = worldDirty;
        try {
            drawSceneWorld(sceneBackdropCanvas, /*includeOverlays*/ false, /*skipSelected*/ false);
        } finally {
            sceneCullOverride = null;
        }
        sceneBackdropCanvas.restore();
    }

    private void unionEraseDirty(RectF bounds) {
        if (bounds == null || bounds.isEmpty()) return;
        if (!eraseDirtyValid) {
            eraseDirtyWorld.set(bounds);
            eraseDirtyValid = true;
        } else {
            eraseDirtyWorld.union(bounds);
        }
    }

    private boolean eraseAt(float x, float y, float radius) {
        boolean removed = false;

        if (eraseSelectInk || eraseSelectHighlighter) {
            Iterator<Stroke> it = inkStrokes.iterator();
            while (it.hasNext()) {
                Stroke s = it.next();
                boolean target = s.brush == BRUSH_HIGHLIGHTER ? eraseSelectHighlighter : eraseSelectInk;
                if (target && s.hit(x, y, radius)) {
                    if (eraseBaseline == null) eraseBaseline = captureContent();
                    unionEraseDirty(s.bounds);
                    it.remove();
                    selectedStrokes.remove(s);
                    removed = true;
                }
            }
        }

        if (eraseSelectText) {
            Iterator<CanvasTextField> tfIt = textFields.iterator();
            while (tfIt.hasNext()) {
                CanvasTextField tf = tfIt.next();
                if (tf == editingTextField) continue;
                tmpRect.set(tf.bounds());
                tmpRect.inset(-radius, -radius);
                if (tmpRect.contains(x, y)) {
                    if (eraseBaseline == null) eraseBaseline = captureContent();
                    unionEraseDirty(tf.bounds());
                    tfIt.remove();
                    selectedTextFields.remove(tf);
                    removed = true;
                }
            }
        }

        if (eraseSelectImages) {
            Iterator<CanvasImage> imgIt = images.iterator();
            while (imgIt.hasNext()) {
                CanvasImage img = imgIt.next();
                tmpRect.set(img.bounds());
                tmpRect.inset(-radius, -radius);
                if (tmpRect.contains(x, y) || img.contains(x, y)) {
                    if (eraseBaseline == null) eraseBaseline = captureContent();
                    unionEraseDirty(img.bounds());
                    imgIt.remove();
                    selectedImages.remove(img);
                    removed = true;
                }
            }
        }

        return removed;
    }

    private float[] navFocus(MotionEvent e, int skipIndex) {
        float sx = 0f, sy = 0f;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skipIndex) continue;
            sx += e.getX(i);
            sy += e.getY(i);
            n++;
        }
        if (n == 0) {
            navTmp[0] = lastFocusX;
            navTmp[1] = lastFocusY;
            return navTmp;
        }
        navTmp[0] = sx / n;
        navTmp[1] = sy / n;
        return navTmp;
    }

    private void setNavFocus(MotionEvent e, int skipIndex) {
        float[] f = navFocus(e, skipIndex);
        lastFocusX = f[0];
        lastFocusY = f[1];
    }

    private void markMatrixDirty() {
        inverseDirty = true;
        if (navSnapshotActive) {
            updateNavSnapshotTransform();
        }
        // Live document pan draws each frame — don't dirty the idle backdrop mid-gesture.
        if (!(document.isOpen() && (navigating || pinchScaling || panFlinging || overscrollSnapping))) {
            if (selGesture == SelGesture.NONE) {
                sceneBackdropDirty = true;
            }
        }
        if (document.isOpen() && (navigating || pinchScaling || navSnapshotActive || panFlinging
                || overscrollSnapping)) {
            clampDocumentCamera();
        }
        if (!navSnapshotActive
                && !navigating
                && !pinchScaling
                && !panFlinging
                && !overscrollSnapping
                && (hasSelection() || hasLassoRegion())
                && selGesture == SelGesture.NONE) {
            notifySelectionLayout();
        }
    }

    private void ensureInverse() {
        if (!inverseDirty) return;
        if (!viewMatrix.invert(inverse)) {
            inverse.reset();
        }
        inverseDirty = false;
        viewMatrix.getValues(matrixValues);
        float sx = matrixValues[Matrix.MSCALE_X];
        float shy = matrixValues[Matrix.MSKEW_Y];
        float scale = (float) Math.hypot(sx, shy);
        cachedViewScale = scale < 0.01f ? 0.01f : scale;
    }

    /** Fills {@link #worldTmp}; do not store the returned array. */
    private float[] screenToWorld(float x, float y) {
        ensureInverse();
        worldTmp[0] = x;
        worldTmp[1] = y;
        inverse.mapPoints(worldTmp);
        return worldTmp;
    }

    private void flushContentDirty() {
        if (!contentDirty) return;
        contentDirty = false;
        notifyContentChanged();
    }

    private float viewScale() {
        ensureInverse();
        return cachedViewScale;
    }

    private void notifyContentChanged() {
        fireSceneChanged(true);
        if (listener == null) return;
        listener.onContentChanged();
    }

    private void drawStroke(Canvas canvas, Stroke s) {
        drawStroke(canvas, s, strokePaint, strokeDrawPath);
    }

    /** Draws a finished stroke from its cached geometry, building it on first use. */
    private void drawStroke(Canvas canvas, Stroke s, Paint ink, Path scratch) {
        if (s.brush != BRUSH_INK) {
            drawEffectStroke(canvas, s, ink, false);
            return;
        }
        if (s.samples.size() <= 2) {
            drawStrokeSegments(canvas, s, ink, scratch);
            return;
        }
        StrokeGeom g = s.geom;
        int version = strokeGeomVersion;
        if (g == null || g.version != version) {
            g = buildStrokeGeom(s, version);
            if (g == null) {
                drawStrokeSegments(canvas, s, ink, scratch);
                return;
            }
            s.geom = g;
        }
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        if (needsPenOutline(s)) {
            // All of the outline first, so it never cuts into a later piece of the stroke.
            ink.setColor(PenOutline.color());
            for (int i = 0; i < g.paths.length; i++) {
                ink.setStrokeWidth(g.widths[i] + PenOutline.strokeExtra(g.widths[i]));
                canvas.drawPath(g.paths[i], ink);
            }
        }
        ink.setColor(s.color);
        for (int i = 0; i < g.paths.length; i++) {
            ink.setStrokeWidth(g.widths[i]);
            canvas.drawPath(g.paths[i], ink);
        }
    }

    /**
     * Same curve as {@link #drawStrokeSegments}: a line to the first midpoint, a
     * quadratic through each sample between midpoints, a line out to the last sample.
     * Consecutive pieces of similar width share one path.
     */
    private StrokeGeom buildStrokeGeom(Stroke s, int version) {
        List<Sample> pts = s.samples;
        int n = pts.size();
        if (n < 3) return null;
        float follow = strokeFollow;
        ArrayList<Path> paths = new ArrayList<>();
        ArrayList<Float> widths = new ArrayList<>();
        Path cur = null;
        float curW = -1f;

        // Piece 0: p0 → mid(p0, p1)
        Sample p0 = pts.get(0);
        Sample p1 = pts.get(1);
        float w0 = (p0.width + p1.width) * 0.5f;
        cur = new Path();
        cur.moveTo(p0.x, p0.y);
        cur.lineTo((p0.x + p1.x) * 0.5f, (p0.y + p1.y) * 0.5f);
        curW = w0;

        for (int i = 1; i < n - 1; i++) {
            Sample a = pts.get(i);
            Sample b = pts.get(i + 1);
            Sample prev = pts.get(i - 1);
            float x0 = (prev.x + a.x) * 0.5f;
            float y0 = (prev.y + a.y) * 0.5f;
            float x1 = (a.x + b.x) * 0.5f;
            float y1 = (a.y + b.y) * 0.5f;
            float ctrlX = a.x + ((2f * a.x - (x0 + x1) * 0.5f) - a.x) * follow;
            float ctrlY = a.y + ((2f * a.y - (y0 + y1) * 0.5f) - a.y) * follow;
            if (Math.abs(a.width - curW) > curW * GEOM_WIDTH_TOLERANCE) {
                paths.add(cur);
                widths.add(curW);
                cur = new Path();
                cur.moveTo(x0, y0);
                curW = a.width;
            }
            cur.quadTo(ctrlX, ctrlY, x1, y1);
        }

        Sample last = pts.get(n - 1);
        Sample prev = pts.get(n - 2);
        float endW = (prev.width + last.width) * 0.5f;
        if (Math.abs(endW - curW) > curW * GEOM_WIDTH_TOLERANCE) {
            paths.add(cur);
            widths.add(curW);
            cur = new Path();
            cur.moveTo((prev.x + last.x) * 0.5f, (prev.y + last.y) * 0.5f);
            curW = endW;
        }
        cur.lineTo(last.x, last.y);
        paths.add(cur);
        widths.add(curW);

        float[] w = new float[widths.size()];
        for (int i = 0; i < w.length; i++) w[i] = widths.get(i);
        return new StrokeGeom(paths.toArray(new Path[0]), w, version);
    }

    /** Per-segment drawing for the stroke still under the pen (its samples change). */
    private void drawStrokeSegments(Canvas canvas, Stroke s, Paint ink, Path scratch) {
        if (needsPenOutline(s)) {
            drawStrokeSegmentsPass(canvas, s, ink, scratch, PenOutline.color(), true);
        }
        drawStrokeSegmentsPass(canvas, s, ink, scratch, s.color, false);
    }

    private static float wide(float width, boolean outline) {
        return outline ? width + PenOutline.strokeExtra(width) : width;
    }

    private void drawStrokeSegmentsPass(Canvas canvas, Stroke s, Paint ink, Path scratch, int color, boolean outline) {
        ink.setColor(color);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        List<Sample> pts = s.samples;
        int n = pts.size();
        if (n == 0) return;
        if (n == 1) {
            Sample a = pts.get(0);
            ink.setStrokeWidth(wide(a.width, outline));
            canvas.drawPoint(a.x, a.y, ink);
            return;
        }
        if (n == 2) {
            Sample a = pts.get(0);
            Sample b = pts.get(1);
            ink.setStrokeWidth(wide((a.width + b.width) * 0.5f, outline));
            canvas.drawLine(a.x, a.y, b.x, b.y, ink);
            return;
        }

        // Midpoint quadratic segments — sharp polyline corners become smooth curves
        // while still allowing gentle per-segment width from pressure.
        Sample p0 = pts.get(0);
        Sample p1 = pts.get(1);
        float midX = (p0.x + p1.x) * 0.5f;
        float midY = (p0.y + p1.y) * 0.5f;
        ink.setStrokeWidth(wide((p0.width + p1.width) * 0.5f, outline));
        canvas.drawLine(p0.x, p0.y, midX, midY, ink);

        for (int i = 1; i < n - 1; i++) {
            Sample a = pts.get(i);
            Sample b = pts.get(i + 1);
            Sample prev = pts.get(i - 1);
            float x0 = (prev.x + a.x) * 0.5f;
            float y0 = (prev.y + a.y) * 0.5f;
            float x1 = (a.x + b.x) * 0.5f;
            float y1 = (a.y + b.y) * 0.5f;
            ink.setStrokeWidth(wide(a.width, outline));
            // Control point that would make the curve pass through the sample at t=0.5:
            // B(0.5) = (P0 + 2*P1 + P2)/4, so P1 = 2a - (P0 + P2)/2.
            float follow = strokeFollow;
            float ctrlX = a.x + ((2f * a.x - (x0 + x1) * 0.5f) - a.x) * follow;
            float ctrlY = a.y + ((2f * a.y - (y0 + y1) * 0.5f) - a.y) * follow;
            scratch.reset();
            scratch.moveTo(x0, y0);
            scratch.quadTo(ctrlX, ctrlY, x1, y1);
            canvas.drawPath(scratch, ink);
        }

        Sample last = pts.get(n - 1);
        Sample prev = pts.get(n - 2);
        float endX = (prev.x + last.x) * 0.5f;
        float endY = (prev.y + last.y) * 0.5f;
        ink.setStrokeWidth(wide((prev.width + last.width) * 0.5f, outline));
        canvas.drawLine(endX, endY, last.x, last.y, ink);
    }

    private void rebuildLassoPath() {
        lassoPath.reset();
        if (lassoPoints.isEmpty()) return;
        float[] first = lassoPoints.get(0);
        lassoPath.moveTo(first[0], first[1]);
        for (int i = 1; i < lassoPoints.size(); i++) {
            float[] p = lassoPoints.get(i);
            lassoPath.lineTo(p[0], p[1]);
        }
    }

    private void updateDashEffects(float scale) {
        if (Math.abs(scale - cachedDashScale) < 0.05f && lassoDash != null) return;
        cachedDashScale = scale;
        lassoDash = new DashPathEffect(new float[]{14f / scale, 10f / scale}, 0f);
        selectionDash = new DashPathEffect(new float[]{12f / scale, 8f / scale}, 0f);
    }

    private void computeVisibleWorld(float scale) {
        ensureInverse();
        worldTmp[0] = 0;
        worldTmp[1] = 0;
        inverse.mapPoints(worldTmp);
        float left = worldTmp[0];
        float top = worldTmp[1];
        worldTmp[0] = getWidth();
        worldTmp[1] = getHeight();
        inverse.mapPoints(worldTmp);
        float right = worldTmp[0];
        float bottom = worldTmp[1];
        visibleWorld.set(
                Math.min(left, right), Math.min(top, bottom),
                Math.max(left, right), Math.max(top, bottom));
        float pad = 128f / scale;
        visibleWorld.inset(-pad, -pad);
    }

    private int emptyButtonFill = 0xFF3F51B5;
    private int emptyButtonIcon = 0xFFE8EAF6;
    private final RectF emptyButtonRect = new RectF();

    /** With no document open: one round plus button and a short label, centred. */
    private void drawEmptyState(Canvas canvas, int w, int h) {
        float den = getResources().getDisplayMetrics().density;
        float r = 36f * den;
        float cx = w * 0.5f, cy = h * 0.45f;
        emptyButtonRect.set(cx - r - 12f * den, cy - r - 12f * den, cx + r + 12f * den, cy + r + 52f * den);
        Paint p = emptyHintPaint;
        int keep = p.getColor();
        p.setStyle(Paint.Style.FILL);
        p.setColor(emptyButtonFill);
        p.setShadowLayer(10f * den, 0, 3f * den, 0x33000000);
        canvas.drawCircle(cx, cy, r, p);
        p.clearShadowLayer();
        p.setColor(emptyButtonIcon);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3.5f * den);
        float arm = 15f * den;
        canvas.drawLine(cx - arm, cy, cx + arm, cy, p);
        canvas.drawLine(cx, cy - arm, cx, cy + arm, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(keep);
        p.setTextSize(15f * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("New document", cx, cy + r + 30f * den, p);
    }

    private boolean emptyButtonHit(float x, float y) {
        return emptyButtonRect.contains(x, y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        updateOverscanMetrics(w, h);

        if (!document.isOpen()) {
            canvas.drawColor(sceneBgColor);
            drawEmptyState(canvas, w, h);
            drawFavoritesRadialOverlay(canvas);
            return;
        }

        syncPageRenderScale();

        boolean drawingInk = activeStroke != null
                && !navigating
                && !navSnapshotActive
                && !panFlinging
                && !pinchScaling
                && selGesture == SelGesture.NONE
                && !drawingLasso
                && Float.isNaN(eraseX);

        // Ink keeps the fast path even when PDF tiles have marked the backdrop dirty —
        // otherwise every tile landing forced a full-scene rebuild under the pen.
        if (drawingInk && sceneBackdropReady && backdropMatches(w, h)) {
            drawBackdropForCamera(canvas);
            canvas.save();
            canvas.concat(viewMatrix);
            drawActiveStroke(canvas);
            canvas.restore();
            drawFavoritesRadialOverlay(canvas);
            return;
        }

        // Pan/fling/pinch/snap: blit the frozen overscan bitmap (cheap) instead of
        // re-stroking every ink path each frame.
        if (navSnapshotActive && sceneBackdrop != null && !sceneBackdrop.isRecycled()) {
            canvas.drawColor(sceneBgColor);
            updateNavSnapshotTransform();
            canvas.drawBitmap(displayBackdrop(), navSnapshotMatrix, navSnapshotPaint);
            drawDeselectedPending(canvas);
            if (prepareBands()) drawBandsAndGaps(canvas, navSnapshotMatrix);
            else drawUncoveredLive(canvas, navSnapshotMatrix);
            if (selDragBitmapReady || hasSelection() || hasLassoRegion()) {
                canvas.save();
                canvas.concat(viewMatrix);
                float scale = viewScale();
                updateDashEffects(scale);
                if (hasSelection() && !selDragBitmapReady) {
                    drawSelectedContent(canvas, scale);
                }
                drawSelectionOverlay(canvas, scale);
                canvas.restore();
                if (selDragBitmapReady) drawSelDragOverlay(canvas);
            }
            drawOverscrollCharge(canvas, w, h);
            drawFavoritesRadialOverlay(canvas);
            return;
        }

        // Fallback if a gesture started before a freeze was available.
        if (document.isOpen() && (navigating || panFlinging || pinchScaling || overscrollSnapping)) {
            canvas.drawColor(sceneBgColor);
            drawSceneWorld(canvas, /*includeOverlays*/ true, /*skipSelected*/ false);
            drawOverscrollCharge(canvas, w, h);
            drawFavoritesRadialOverlay(canvas);
            return;
        }

        ensureSceneBackdrop(w, h);
        boolean selectionDragging = hasSelection() && selGesture != SelGesture.NONE;
        if (selectionDragging) {
            // Blit backdrop (already without selection) + live selection — no punch/rebuild.
            if (sceneBackdrop != null && !sceneBackdrop.isRecycled() && backdropMatches(w, h)) {
                drawBackdropForCamera(canvas);
            } else {
                canvas.drawColor(sceneBgColor);
            }
            canvas.save();
            canvas.concat(viewMatrix);
            float scale = viewScale();
            updateDashEffects(scale);
            drawSelectedContent(canvas, scale);
            drawSelectionOverlay(canvas, scale);
            canvas.restore();
            drawFavoritesRadialOverlay(canvas);
            return;
        }

        // Only block the UI thread for a rebuild when we have nothing to show, or
        // the selection omit mode changed. A merely-dirty backdrop (new PDF tiles)
        // keeps blitting the previous frame and rebuilds via warmBackdropRunnable.
        boolean sizeOrReady = !sceneBackdropReady || !backdropMatches(w, h);
        boolean selectionOmitChanged = sceneBackdropReady && backdropMatches(w, h)
                && (hasSelection() != sceneBackdropOmitsSelection);
        // A plain deselect is covered by drawing the deselected items live meanwhile.
        boolean coveredByPending = selectionOmitChanged && !hasSelection()
                && sceneBackdropOmitsSelection && hasDeselectedPending();
        if (sizeOrReady || (selectionOmitChanged && !coveredByPending)) {
            rebuildSceneBackdropNow(backdropSkipSelected());
        } else if (sceneBackdropDirty || coveredByPending) {
            removeCallbacks(warmBackdropRunnable);
            post(warmBackdropRunnable);
        }

        if (sceneBackdrop == null || sceneBackdrop.isRecycled()) {
            canvas.drawColor(sceneBgColor);
            drawFavoritesRadialOverlay(canvas);
            return;
        }
        drawBackdropForCamera(canvas);
        canvas.save();
        canvas.concat(viewMatrix);
        float scale = viewScale();
        drawSearchHits(canvas, scale);
        if (activeStroke != null) drawActiveStroke(canvas);
        if (drawingLasso && lassoPoints.size() >= 2) {
            rebuildLassoPath();
            lassoPaint.setStrokeWidth(2f / scale);
            lassoPaint.setPathEffect(lassoDash);
            canvas.drawPath(lassoPath, lassoPaint);
        }
        if (drawingTextRect) {
            selectionPaint.setStyle(Paint.Style.STROKE);
            selectionPaint.setStrokeWidth(2f / scale);
            selectionPaint.setColor(chromeAccent);
            selectionPaint.setPathEffect(selectionDash);
            canvas.drawRect(
                    Math.min(textRectStartX, textRectEndX),
                    Math.min(textRectStartY, textRectEndY),
                    Math.max(textRectStartX, textRectEndX),
                    Math.max(textRectStartY, textRectEndY),
                    selectionPaint);
            selectionPaint.setPathEffect(null);
        }
        if (hasSelection()) drawSelectedContent(canvas, scale);
        drawSelectionOverlay(canvas, scale);
        if (!Float.isNaN(eraseX)) {
            float r = eraseRadiusPx / scale;
            eraseHaloPaint.setStrokeWidth(4.5f / scale);
            canvas.drawCircle(eraseX, eraseY, r, eraseHaloPaint);
            erasePreviewPaint.setStrokeWidth(2f / scale);
            canvas.drawCircle(eraseX, eraseY, r, erasePreviewPaint);
        }
        canvas.restore();
        drawOverscrollCharge(canvas, w, h);
        drawFavoritesRadialOverlay(canvas);
    }

    private void drawFavoritesRadialOverlay(Canvas canvas) {
        if (favoritesRadial.isOpen()) favoritesRadial.draw(canvas);
    }

    /** Margin for one axis: the requested fraction, clipped by {@link #MAX_BACKDROP_DIM}. */
    private static int overscanMargin(int size, float frac) {
        int margin = Math.round(size * frac);
        int room = (MAX_BACKDROP_DIM - size) / 2;
        return Math.max(0, Math.min(margin, room));
    }

    private int overscanWidth(int w) {
        return w + 2 * overscanMargin(w, OVERSCAN_FRAC);
    }

    private int overscanHeight(int h) {
        return h + 2 * overscanMargin(h, OVERSCAN_FRAC_Y);
    }

    /** Keeps the margin offsets in step with the viewport, including across a resize. */
    private void updateOverscanMetrics(int w, int h) {
        overscanX = overscanMargin(w, OVERSCAN_FRAC);
        overscanY = overscanMargin(h, OVERSCAN_FRAC_Y);
    }

    /**
     * The camera the backdrop pixels were drawn at.
     *
     * <p>Not always the current one: a rebuild can still be pending when the next
     * gesture starts. Freezing then has to blit through the matrix these pixels
     * actually came from, or the stale image is shown unscaled — which is a frame or
     * two of everything at the previous zoom before the rebuild lands.
     */
    private final Matrix backdropMatrix = new Matrix();

    private final Matrix backdropInverse = new Matrix();
    private final Matrix backdropBlitMatrix = new Matrix();

    /**
     * Blit the backdrop where its pixels actually belong.
     *
     * <p>Drawing it at the plain overscan offset assumes it was rasterised for the
     * camera on screen right now. Straight after a scroll it was not: the freeze
     * ends, the rebuild is still to come, and the raw blit put the page a couple of
     * hundred pixels out for as long as that took — the small jump at the end of
     * every scroll. Going through {@link #backdropMatrix} costs one concat and is
     * exactly what the pan freeze already does.
     */
    private void drawBackdropForCamera(Canvas canvas) {
        if (sceneBackdrop == null || sceneBackdrop.isRecycled()) return;
        if (backdropMatrix.equals(viewMatrix) || !backdropMatrix.invert(backdropInverse)) {
            canvas.drawBitmap(displayBackdrop(), -overscanX, -overscanY, backdropPaint);
            drawDeselectedPending(canvas);
            return;
        }
        backdropBlitMatrix.setConcat(viewMatrix, backdropInverse);
        backdropBlitMatrix.preTranslate(-overscanX, -overscanY);
        canvas.drawColor(sceneBgColor);
        canvas.drawBitmap(displayBackdrop(), backdropBlitMatrix, navSnapshotPaint);
        drawDeselectedPending(canvas);
        if (prepareBands()) drawBandsAndGaps(canvas, backdropBlitMatrix);
        else drawUncoveredLive(canvas, backdropBlitMatrix);
    }


    // ---- Scroll bands ---------------------------------------------------------------
    //
    // While scrolling at a fixed zoom the scene is shown as horizontal bands: slices
    // BAND_H pixels tall, anchored to the document at the current scale, each drawn
    // once off-thread and blitted from its own small GPU copy. A scroll only ever
    // draws the bands it newly needs. The single overscan freeze this replaces had to
    // be copied (~15ms) and re-uploaded (~22ms, 47MB) whole on every step, however
    // thin the new strip — the leftover judder once ink drawing itself was fast.
    // Bands are display-only: they are never drawn while the backdrop sits exactly at
    // the camera, and any content change drops them.

    private static final int BAND_H = 512;
    /** Horizontal slack either side of the view before bands must be redrawn. */
    private static final int BAND_X_MARGIN = 256;
    private static final int BAND_AHEAD = 5;
    private static final int BAND_BEHIND = 2;
    /** Bands kept beyond the visible ones before the farthest are dropped. */
    private static final int BAND_KEEP = 5;
    private static final int BAND_MAX_PENDING = 6;

    private static final class Band {
        final Bitmap bmp;

        Band(Bitmap bmp) {
            this.bmp = bmp;
        }
    }

    /** UI thread only. */
    private final java.util.HashMap<Integer, Band> bands = new java.util.HashMap<>();
    private final Set<Integer> bandPending = new HashSet<>();
    /** Bands to redraw although present (their pages' pixels changed); old one shows meanwhile. */
    private final Set<Integer> bandStale = new HashSet<>();
    private int bandGen;
    private float bandScale = -1f;
    private float bandTx;
    private int bandW;
    private int bandBg;
    private boolean bandOmitsSelection;
    private String bandDocPath = "";
    /** Page updates the overscan freeze has not folded in yet (bands take theirs directly). */
    private final List<RectF> freezeLandedPending = new ArrayList<>();
    private final java.util.concurrent.ExecutorService bandPool =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "cc-scroll-band");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private final ThreadLocal<SceneKit> bandKitTl = ThreadLocal.withInitial(SceneKit::new);
    private final ThreadLocal<Canvas> bandCanvasTl = ThreadLocal.withInitial(Canvas::new);
    /** Software scratch bitmaps for band rendering, reused across bands. */
    private final ArrayDeque<Bitmap> bandScratch = new ArrayDeque<>();
    private final float[] bandVals = new float[9];

    private void resetBands() {
        bandGen++;
        for (Band b : bands.values()) {
            if (b.bmp != null && !b.bmp.isRecycled()) b.bmp.recycle();
        }
        bands.clear();
        bandPending.clear();
        bandStale.clear();
        bandScale = -1f;
    }

    /**
     * True when bands can stand in for the scene at the current camera, resetting them
     * first if what they were drawn for (zoom, horizontal position, theme, selection,
     * document, width) no longer holds.
     */
    private boolean prepareBands() {
        if (!document.isOpen() || pinchScaling) return false;
        int w = getWidth();
        if (w <= 0) return false;
        viewMatrix.getValues(bandVals);
        if (bandVals[Matrix.MSKEW_X] != 0f || bandVals[Matrix.MSKEW_Y] != 0f) return false;
        float s = bandVals[Matrix.MSCALE_X];
        float tx = bandVals[Matrix.MTRANS_X];
        boolean skip = backdropSkipSelected();
        String doc = document.path == null ? "" : document.path;
        if (s != bandScale || Math.abs(tx - bandTx) > BAND_X_MARGIN
                || bandW != w + 2 * BAND_X_MARGIN || bandBg != sceneBgColor
                || bandOmitsSelection != skip || !bandDocPath.equals(doc)) {
            resetBands();
            bandScale = s;
            bandTx = tx;
            bandW = w + 2 * BAND_X_MARGIN;
            bandBg = sceneBgColor;
            bandOmitsSelection = skip;
            bandDocPath = doc;
            synchronized (bandScratch) {
                for (Bitmap b : bandScratch) b.recycle();
                bandScratch.clear();
            }
        }
        return true;
    }

    /** Band index containing screen row {@code y} at the current camera. */
    private int bandAt(float y, float ty) {
        return (int) Math.floor((y - ty) / BAND_H);
    }

    /** Asks for missing (and stale) bands around the view, nearest in scroll direction first. */
    private void requestBands() {
        // Pages that finished rendering while nothing was scrolling: their bands (and
        // the freeze) still show the older pixels.
        if (document.hasLandedPages()) {
            List<RectF> pages = document.drainLandedPages();
            freezeLandedPending.addAll(pages);
            refreshBandsForPages(pages);
        }
        int h = getHeight();
        float ty = bandVals[Matrix.MTRANS_Y];
        int first = bandAt(0, ty);
        int last = bandAt(h - 1, ty);
        boolean moving = android.os.SystemClock.uptimeMillis() - navLastMoveMs < 80L;
        int ahead = moving ? BAND_AHEAD : 2;
        int behind = moving ? BAND_BEHIND : 2;
        int lo, hi;
        if (moving && navVelY > 0f) {  // content moving down: earlier bands come in
            lo = first - ahead;
            hi = last + behind;
        } else if (moving && navVelY < 0f) {
            lo = first - behind;
            hi = last + ahead;
        } else {
            lo = first - ahead;
            hi = last + ahead;
        }
        int docLo = -1;
        int docHi = (int) Math.floor(document.documentBottom() * bandScale / BAND_H) + 1;
        lo = Math.max(lo, docLo);
        hi = Math.min(hi, docHi);

        // Order: visible first (top to bottom), then outward.
        List<Integer> order = new ArrayList<>();
        for (int k = Math.max(first, lo); k <= Math.min(last, hi); k++) order.add(k);
        for (int d = 1; d <= Math.max(ahead, behind); d++) {
            if (navVelY <= 0f) {
                if (last + d <= hi) order.add(last + d);
                if (first - d >= lo) order.add(first - d);
            } else {
                if (first - d >= lo) order.add(first - d);
                if (last + d <= hi) order.add(last + d);
            }
        }
        SceneKit snap = null;
        for (int k : order) {
            if (bandPending.size() >= BAND_MAX_PENDING) break;
            if (bandPending.contains(k)) continue;
            if (bands.containsKey(k) && !bandStale.contains(k)) continue;
            if (snap == null) {
                snap = new SceneKit();
                snapshotSceneInto(snap, bandOmitsSelection);
            }
            submitBand(k, snap);
        }

        // Drop bands far from the view.
        if (bands.size() > (last - first + 1) + 2 * BAND_KEEP) {
            java.util.Iterator<java.util.Map.Entry<Integer, Band>> it = bands.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<Integer, Band> e = it.next();
                int k = e.getKey();
                if (k < first - BAND_KEEP || k > last + BAND_KEEP) {
                    Bitmap b = e.getValue().bmp;
                    if (b != null && !b.isRecycled()) b.recycle();
                    it.remove();
                    bandStale.remove(k);
                }
            }
        }
    }

    private void submitBand(int k, SceneKit snap) {
        final int gen = bandGen;
        final float s = bandScale;
        final float tx0 = bandTx;
        final int bw = bandW;
        final int bg = bandBg;
        bandPending.add(k);
        bandStale.remove(k);
        try {
            bandPool.execute(() -> {
                Bitmap out = renderBand(k, s, tx0, bw, bg, snap);
                post(() -> onBandRendered(k, gen, out));
            });
        } catch (RuntimeException rejected) {
            bandPending.remove(k);
        }
    }

    /** Band thread: draw one band and hand back a GPU copy (software if that fails). */
    private Bitmap renderBand(int k, float s, float tx0, int bw, int bg, SceneKit snap) {
        Bitmap scratch;
        synchronized (bandScratch) {
            scratch = bandScratch.poll();
        }
        if (scratch == null || scratch.isRecycled()
                || scratch.getWidth() != bw || scratch.getHeight() != BAND_H) {
            try {
                scratch = Bitmap.createBitmap(bw, BAND_H, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError oom) {
                return null;
            }
        }
        SceneKit kit = bandKitTl.get();
        shareSceneKit(kit, snap);
        Canvas c = bandCanvasTl.get();
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(tx0 + BAND_X_MARGIN, -(float) k * BAND_H);
        Matrix inv = new Matrix();
        m.invert(inv);
        RectF cull = new RectF(0, 0, bw, BAND_H);
        inv.mapRect(cull);
        float pad = 8f / Math.max(s, 0.0001f);
        cull.inset(-pad, -pad);
        Bitmap result;
        try {
            c.setBitmap(scratch);
            c.drawColor(bg);
            drawSceneWorld(c, /*includeOverlays*/ false, /*skipSelected*/ false, m, s, cull, kit);
            c.setBitmap(null);
            Bitmap hw = null;
            try {
                hw = scratch.copy(Bitmap.Config.HARDWARE, false);
            } catch (Throwable ignored) {
            }
            if (hw != null) {
                result = hw;
                synchronized (bandScratch) {
                    if (bandScratch.size() < 4) bandScratch.push(scratch);
                    else scratch.recycle();
                }
            } else {
                result = scratch;  // no GPU copy: show the software pixels
            }
        } catch (Throwable t) {
            c.setBitmap(null);
            scratch.recycle();
            return null;
        }
        return result;
    }

    private void onBandRendered(int k, int gen, Bitmap bmp) {
        if (gen != bandGen) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            return;
        }
        bandPending.remove(k);
        if (bmp == null) return;
        Band old = bands.put(k, new Band(bmp));
        if (old != null && old.bmp != null && old.bmp != bmp && !old.bmp.isRecycled()) {
            old.bmp.recycle();
        }
        invalidate();
    }

    /** Marks bands under freshly rendered PDF pages for redraw (the old ones show meanwhile). */
    private void refreshBandsForPages(List<RectF> pages) {
        if (bandScale <= 0f) return;
        for (RectF p : pages) {
            int k0 = (int) Math.floor(p.top * bandScale / BAND_H);
            int k1 = (int) Math.floor(p.bottom * bandScale / BAND_H);
            for (int k = k0; k <= k1; k++) {
                if (bands.containsKey(k)) bandStale.add(k);
            }
        }
    }

    /**
     * Draws the bands over whatever is already on the canvas, then draws live any rows
     * that neither the bands nor {@code freezeBlit} (the overscan freeze's screen
     * mapping, or null) reach. Call after {@link #prepareBands()} returned true.
     */
    private void drawBandsAndGaps(Canvas canvas, Matrix freezeBlit) {
        int w = getWidth();
        int h = getHeight();
        requestBands();
        float tx = bandVals[Matrix.MTRANS_X];
        float ty = bandVals[Matrix.MTRANS_Y];
        int first = bandAt(0, ty);
        int last = bandAt(h - 1, ty);
        float left = -BAND_X_MARGIN + (tx - bandTx);

        // Rows covered by the freeze (only if it spans the full width).
        float fTop = 0f, fBottom = -1f;
        if (freezeBlit != null && sceneBackdrop != null) {
            liveCovered.set(0, 0, sceneBackdrop.getWidth(), sceneBackdrop.getHeight());
            freezeBlit.mapRect(liveCovered);
            if (liveCovered.left <= 0.5f && liveCovered.right >= w - 0.5f) {
                fTop = liveCovered.top;
                fBottom = liveCovered.bottom;
            }
        }

        float gapTop = -1f;
        for (int k = first; k <= last + 1; k++) {
            float top = k * (float) BAND_H + ty;
            float bottom = top + BAND_H;
            boolean inRange = k <= last;
            Band b = inRange ? bands.get(k) : null;
            if (b != null && !b.bmp.isRecycled()) {
                canvas.drawBitmap(b.bmp, left, top, navSnapshotPaint);
            }
            boolean covered = b != null || (fBottom > fTop && top >= fTop - 0.5f && bottom <= fBottom + 0.5f);
            float rowTop = Math.min(h, Math.max(0f, top));
            if (inRange && !covered) {
                if (gapTop < 0f) gapTop = rowTop;
            } else if (gapTop >= 0f) {
                drawLiveRows(canvas, gapTop, Math.max(gapTop, rowTop), fTop, fBottom, w);
                gapTop = -1f;
            }
        }
    }

    /** Live-draws rows [t, b), skipping the part the freeze does cover. */
    private void drawLiveRows(Canvas canvas, float t, float b, float fTop, float fBottom, int w) {
        if (b - t < 0.5f) return;
        liveStripLowRes = true;
        try {
            if (fBottom > fTop && fTop < b && fBottom > t) {
                if (fTop > t) drawLiveStrip(canvas, 0f, t, w, fTop);
                if (fBottom < b) drawLiveStrip(canvas, 0f, fBottom, w, b);
            } else {
                drawLiveStrip(canvas, 0f, t, w, b);
            }
        } finally {
            liveStripLowRes = false;
        }
    }

    private final RectF liveCovered = new RectF();
    /** UI thread only: the stop-gap strip is being drawn, so pages may be low res. */
    private boolean liveStripLowRes;
    /** UI thread only: the stop-gap strip draws pages without ink/notes/images. */
    private boolean liveStripPagesOnly;
    private final RectF liveStripWorld = new RectF();
    private final float[] liveStripPts = new float[4];

    /**
     * Draws live whatever part of the viewport the blitted backdrop does not reach.
     *
     * <p>A fling can outrun the background redraw of the frozen scene (a gesture's
     * first freeze has no lead yet, and a finished redraw still waits its turn on the
     * UI thread). That strip used to show bare background and then pop in — pages,
     * ink and images at once. Pages come from the cached bitmaps, so this costs one
     * strip's worth of ink for the odd frame, not a whole-scene redraw.
     */
    private void drawUncoveredLive(Canvas canvas, Matrix blit) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || sceneBackdrop == null) return;
        liveCovered.set(0, 0, sceneBackdrop.getWidth(), sceneBackdrop.getHeight());
        blit.mapRect(liveCovered);
        boolean top = liveCovered.top > 0.5f;
        boolean bottom = liveCovered.bottom < h - 0.5f;
        boolean left = liveCovered.left > 0.5f;
        boolean right = liveCovered.right < w - 0.5f;
        if (!top && !bottom && !left && !right) return;
        // One strip per uncovered side, each culled to itself. Zooming out uncovers
        // all four sides at once, and a single bounding box of them was the whole
        // screen — every visible stroke redrawn on the UI thread each frame.
        float midTop = Math.max(0f, Math.min(h, liveCovered.top));
        float midBottom = Math.max(0f, Math.min(h, liveCovered.bottom));
        // Mid-pinch the freeze is redrawn at each new zoom anyway; stroking every
        // stroke in the margins meanwhile is what made zooming out crawl.
        liveStripPagesOnly = pinchScaling;
        liveStripLowRes = true;
        try {
            if (top) drawLiveStrip(canvas, 0f, 0f, w, midTop);
            if (bottom) drawLiveStrip(canvas, 0f, midBottom, w, h);
            if (left) drawLiveStrip(canvas, 0f, midTop, Math.min(w, liveCovered.left), midBottom);
            if (right) drawLiveStrip(canvas, Math.max(0f, liveCovered.right), midTop, w, midBottom);
        } finally {
            liveStripLowRes = false;
            liveStripPagesOnly = false;
        }
    }

    private void drawLiveStrip(Canvas canvas, float l, float t, float r, float b) {
        if (r - l < 0.5f || b - t < 0.5f) return;
        ensureInverse();
        liveStripPts[0] = l;
        liveStripPts[1] = t;
        liveStripPts[2] = r;
        liveStripPts[3] = b;
        inverse.mapPoints(liveStripPts);
        liveStripWorld.set(
                Math.min(liveStripPts[0], liveStripPts[2]), Math.min(liveStripPts[1], liveStripPts[3]),
                Math.max(liveStripPts[0], liveStripPts[2]), Math.max(liveStripPts[1], liveStripPts[3]));
        float scale = viewScale();
        liveStripWorld.inset(-16f / Math.max(scale, 0.0001f), -16f / Math.max(scale, 0.0001f));
        canvas.save();
        canvas.clipRect(l, t, r, b);
        drawSceneWorld(canvas, /*includeOverlays*/ false, backdropSkipSelected(),
                viewMatrix, scale, liveStripWorld);
        canvas.restore();
    }

    /** Rebuild scene into a back buffer, then swap — never erase the live displayed bitmap. */
    private void rebuildSceneBackdropNow(boolean skipSelected) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureSceneBackdrop(w, h);
        if (sceneBackdrop == null || sceneBackdrop.isRecycled()) return;
        int bw = sceneBackdrop.getWidth();
        int bh = sceneBackdrop.getHeight();
        Bitmap.Config cfg = sceneBackdrop.getConfig() != null
                ? sceneBackdrop.getConfig()
                : Bitmap.Config.ARGB_8888;
        Bitmap back = ensureSceneBackdropBack(bw, bh, cfg);
        // The front and back buffers trade places, so a GPU copy made of this buffer
        // earlier would match it again after the swap and show its old pixels — a
        // moved selection sat at its old spot after deselecting until a scroll.
        releaseBackdropHw();
        back.eraseColor(sceneBgColor);
        sceneBackdropCanvas.setBitmap(back);
        drawSceneOverscan(sceneBackdropCanvas, skipSelected);
        Bitmap old = sceneBackdrop;
        sceneBackdrop = back;
        sceneBackdropBack = old;
        sceneBackdropCanvas.setBitmap(sceneBackdrop);
        backdropMatrix.set(viewMatrix);
        sceneBackdropDirty = false;
        sceneBackdropReady = true;
        sceneBackdropOmitsSelection = skipSelected;
        clearDeselectedPending();
    }

    private Bitmap ensureSceneBackdropBack(int bw, int bh, Bitmap.Config cfg) {
        if (sceneBackdropBack != null
                && !sceneBackdropBack.isRecycled()
                && sceneBackdropBack != navRebuildSource
                && sceneBackdropBack.getWidth() == bw
                && sceneBackdropBack.getHeight() == bh
                && sceneBackdropBack.getConfig() == cfg) {
            return sceneBackdropBack;
        }
        // Never draw into (or recycle) the buffer a scroll redraw is copying from.
        recycleBackdropBitmap(sceneBackdropBack);
        sceneBackdropBack = Bitmap.createBitmap(bw, bh, cfg);
        return sceneBackdropBack;
    }

    private void releaseSceneBackdropBack() {
        recycleBackdropBitmap(sceneBackdropBack);
        sceneBackdropBack = null;
    }

    private void releaseBackdropHw() {
        if (sceneBackdropHw != null && !sceneBackdropHw.isRecycled()) sceneBackdropHw.recycle();
        sceneBackdropHw = null;
        sceneBackdropHwFor = null;
    }

    /** The bitmap to blit for the current backdrop: its GPU copy when that is current. */
    private Bitmap displayBackdrop() {
        if (sceneBackdropHw != null && sceneBackdropHwFor == sceneBackdrop
                && !sceneBackdropHw.isRecycled()) {
            return sceneBackdropHw;
        }
        return sceneBackdrop;
    }

    /** Recycle unless the rebuild thread is still copying from it (GC frees it then). */
    private void recycleBackdropBitmap(Bitmap b) {
        if (b == null || b.isRecycled() || b == navRebuildSource) return;
        b.recycle();
    }

    private void ensureSceneBackdrop(int w, int h) {
        int bw = overscanWidth(w);
        int bh = overscanHeight(h);
        updateOverscanMetrics(w, h);
        Bitmap.Config wantCfg = document.isOpen()
                ? Bitmap.Config.ARGB_8888
                : Bitmap.Config.RGB_565;
        if (sceneBackdrop != null
                && sceneBackdrop.getWidth() == bw
                && sceneBackdrop.getHeight() == bh
                && !sceneBackdrop.isRecycled()
                && sceneBackdrop.getConfig() == wantCfg) {
            return;
        }
        recycleBackdropBitmap(sceneBackdrop);
        releaseSceneBackdropBack();
        // Opaque paper + PDF need full colour depth when a document is open —
        // RGB_565 softened fine text into the backdrop.
        releaseBackdropHw();
        sceneBackdrop = Bitmap.createBitmap(bw, bh, wantCfg);
        sceneBackdropDirty = true;
        sceneBackdropReady = false;
        sceneBackdropOmitsSelection = false;
    }

    /** True when the backdrop bitmap matches the current viewport's overscanned size. */
    private boolean backdropMatches(int w, int h) {
        return sceneBackdrop != null
                && !sceneBackdrop.isRecycled()
                && sceneBackdrop.getWidth() == overscanWidth(w)
                && sceneBackdrop.getHeight() == overscanHeight(h);
    }

    /**
     * Draws the world into an overscanned bitmap. The canvas is pre-translated by the
     * margin so world content lands at the same place it would on screen, just shifted
     * into the larger bitmap.
     */
    private void drawSceneOverscan(Canvas target, boolean skipSelected) {
        target.save();
        target.translate(overscanX, overscanY);
        RectF prev = sceneCullOverride;
        sceneCullOverride = overscanCullWorld();
        try {
            drawSceneWorld(target, /*includeOverlays*/ false, skipSelected,
                    viewMatrix, viewScale(), sceneCullOverride);
        } finally {
            sceneCullOverride = prev;
            target.restore();
        }
    }

    /**
     * Background-safe overscan draw using a frozen view matrix (does not touch live camera).
     */
    private void drawSceneOverscanAt(Canvas target, SceneKit kit,
                                     Matrix worldMatrix, float scale,
                                     int ox, int oy, int vw, int vh) {
        if (!worldMatrix.invert(navRebuildInverse)) return;
        navRebuildPts[0] = -ox;
        navRebuildPts[1] = -oy;
        navRebuildPts[2] = vw + ox;
        navRebuildPts[3] = vh + oy;
        navRebuildInverse.mapPoints(navRebuildPts);
        navRebuildCull.set(
                Math.min(navRebuildPts[0], navRebuildPts[2]),
                Math.min(navRebuildPts[1], navRebuildPts[3]),
                Math.max(navRebuildPts[0], navRebuildPts[2]),
                Math.max(navRebuildPts[1], navRebuildPts[3]));
        float pad = 128f / Math.max(scale, 0.0001f);
        navRebuildCull.inset(-pad, -pad);

        target.save();
        target.translate(ox, oy);
        try {
            // Selection skipping already lives in the kit's skip sets.
            drawSceneWorld(target, /*includeOverlays*/ false, /*skipSelected*/ false,
                    worldMatrix, scale, navRebuildCull, kit);
        } finally {
            target.restore();
        }
    }

    /** Background-safe draw of one bitmap-space region of the overscan freeze. */
    private void drawSceneRegionAt(Canvas target, SceneKit kit, Matrix worldMatrix, float scale,
                                   int ox, int oy, RectF bitmapRect) {
        // Locals only: several band threads run this at once.
        Matrix inv = new Matrix();
        if (!worldMatrix.invert(inv)) return;
        float[] pts = {
                bitmapRect.left - ox, bitmapRect.top - oy,
                bitmapRect.right - ox, bitmapRect.bottom - oy,
        };
        inv.mapPoints(pts);
        RectF cull = new RectF(
                Math.min(pts[0], pts[2]), Math.min(pts[1], pts[3]),
                Math.max(pts[0], pts[2]), Math.max(pts[1], pts[3]));
        float pad = 8f / Math.max(scale, 0.0001f);
        cull.inset(-pad, -pad);
        target.save();
        target.translate(ox, oy);
        try {
            drawSceneWorld(target, /*includeOverlays*/ false, /*skipSelected*/ false,
                    worldMatrix, scale, cull, kit);
        } finally {
            target.restore();
        }
    }

    /** World rect covering the overscanned bitmap, so culling keeps the margin populated. */
    private RectF overscanCullWorld() {
        ensureInverse();
        float[] pts = {
                -overscanX, -overscanY,
                getWidth() + overscanX, getHeight() + overscanY,
        };
        inverse.mapPoints(pts);
        RectF r = new RectF(
                Math.min(pts[0], pts[2]), Math.min(pts[1], pts[3]),
                Math.max(pts[0], pts[2]), Math.max(pts[1], pts[3]));
        float pad = 128f / Math.max(viewScale(), 0.0001f);
        r.inset(-pad, -pad);
        return r;
    }

    /** Draw grid + text fields + ink + images (+ selection chrome) in world space. */
    private void drawSceneWorld(Canvas canvas, boolean includeOverlays) {
        drawSceneWorld(canvas, includeOverlays, backdropSkipSelected(),
                viewMatrix, viewScale(), null);
    }

    private void drawSceneWorld(Canvas canvas, boolean includeOverlays, boolean skipSelected) {
        drawSceneWorld(canvas, includeOverlays, skipSelected, viewMatrix, viewScale(),
                sceneCullOverride);
    }

    private void drawSceneWorld(Canvas canvas, boolean includeOverlays, boolean skipSelected,
                                Matrix matrix, float scale, RectF cullOrNull) {
        drawSceneWorld(canvas, includeOverlays, skipSelected, matrix, scale, cullOrNull, null);
    }

    /**
     * @param kit when non-null, the draw runs entirely off this kit's paints, scratch
     *            path, cull rect and snapshot lists — the contract the background
     *            rebuild needs. Null means the UI thread's own fields and live lists.
     */
    private void drawSceneWorld(Canvas canvas, boolean includeOverlays, boolean skipSelected,
                                Matrix matrix, float scale, RectF cullOrNull, SceneKit kit) {
        canvas.save();
        canvas.concat(matrix);

        RectF cull = kit != null ? kit.cull : visibleWorld;
        if (cullOrNull != null) {
            cull.set(cullOrNull);
        } else if (kit == null) {
            computeVisibleWorld(scale);
        }
        if (includeOverlays) {
            updateDashEffects(scale);
        }

        Paint inkPaint = kit != null ? kit.stroke : strokePaint;
        Path inkPath = kit != null ? kit.path : strokeDrawPath;
        Paint paper = kit != null ? kit.paper : paperPaint;
        Paint gridInk = kit != null ? kit.grid : gridPaint;
        Paint titleInk = kit != null ? kit.title : titlePaint;
        Paint imgInk = kit != null ? kit.image : imagePaint;
        RectF itemBounds = kit != null ? kit.itemBounds : tmpRect;

        boolean pagesOnly = kit == null && liveStripPagesOnly;
        List<CanvasTextField> texts = pagesOnly ? java.util.Collections.emptyList()
                : kit != null ? kit.texts : textFields;
        List<Stroke> strokes = pagesOnly ? java.util.Collections.emptyList()
                : kit != null ? kit.strokes : inkStrokes;
        List<CanvasImage> imgs = pagesOnly ? java.util.Collections.emptyList()
                : kit != null ? kit.imgs : images;
        CanvasTextField editing = kit != null ? kit.skipEditing : editingTextField;

        Set<CanvasTextField> skipTexts;
        Set<Stroke> skipStrokes;
        Set<CanvasImage> skipImages;
        if (kit != null) {
            skipTexts = kit.skipTexts;
            skipStrokes = kit.skipStrokes;
            skipImages = kit.skipImages;
        } else {
            skipTexts = skipSelected && !selectedTextFields.isEmpty()
                    ? new HashSet<>(selectedTextFields) : null;
            skipStrokes = skipSelected && !selectedStrokes.isEmpty()
                    ? new HashSet<>(selectedStrokes) : null;
            skipImages = skipSelected && !selectedImages.isEmpty()
                    ? new HashSet<>(selectedImages) : null;
        }

        float grid = scale < 0.35f ? 256f : (scale < 0.7f ? 128f : 64f);
        if (kit != null && kit.skipDocument) {
            // Annotations only; the caller draws (or keeps) the page underneath.
        } else if (document.isOpen()) {
            document.draw(canvas, cull, paper, pagePaperColor, kit == null && liveStripLowRes);
        } else if (scale >= 0.2f) {
            float x0 = (float) (Math.floor(cull.left / grid) * grid);
            float y0 = (float) (Math.floor(cull.top / grid) * grid);
            for (float x = x0; x <= cull.right; x += grid) {
                canvas.drawLine(x, cull.top, x, cull.bottom, gridInk);
            }
            for (float y = y0; y <= cull.bottom; y += grid) {
                canvas.drawLine(cull.left, y, cull.right, y, gridInk);
            }
        }

        titleInk.setTextSize(22f / Math.max(scale, 0.5f));

        for (int i = 0; i < texts.size(); i++) {
            CanvasTextField tf = texts.get(i);
            if (skipTexts != null && skipTexts.contains(tf)) continue;
            if (tf == editing) {
                // The overlay editor draws the text itself; its outline goes underneath it.
                synchronized (sharedItemDrawLock) {
                    itemBounds.set(tf.bounds());
                    if (RectF.intersects(cull, itemBounds)) tf.drawOutlineOnly(canvas);
                }
                continue;
            }
            // Copy out: bounds() hands back the field's own shared rect.
            synchronized (sharedItemDrawLock) {
                itemBounds.set(tf.bounds());
                if (!RectF.intersects(cull, itemBounds)) continue;
                tf.draw(canvas, kit == null && selectedTextFields.contains(tf));
            }
        }

        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            if (skipStrokes != null && skipStrokes.contains(s)) continue;
            if (!s.bounds.isEmpty() && !RectF.intersects(cull, s.bounds)) continue;
            drawStroke(canvas, s, inkPaint, inkPath);
        }

        for (int i = 0; i < imgs.size(); i++) {
            CanvasImage img = imgs.get(i);
            if (skipImages != null && skipImages.contains(img)) continue;
            synchronized (sharedItemDrawLock) {
                itemBounds.set(img.bounds());
                if (!RectF.intersects(cull, itemBounds)) continue;
                if (img.isLive()) {
                    // Live content is an overlay above this view; the page holds the slot,
                    // so the space is reserved and scrolls with the document.
                    canvas.drawRoundRect(itemBounds, 10f, 10f, webSlotPaint);
                    continue;
                }
                img.draw(canvas, imgInk);
            }
        }



        if (includeOverlays) {
            if (activeStroke != null) drawActiveStroke(canvas);
            if (drawingLasso && lassoPoints.size() >= 2) {
                rebuildLassoPath();
                lassoPaint.setStrokeWidth(2f / scale);
                lassoPaint.setPathEffect(lassoDash);
                canvas.drawPath(lassoPath, lassoPaint);
            }
            if (drawingTextRect) {
                selectionPaint.setStyle(Paint.Style.STROKE);
                selectionPaint.setStrokeWidth(2f / scale);
                selectionPaint.setColor(chromeAccent);
                selectionPaint.setPathEffect(selectionDash);
                canvas.drawRect(
                        Math.min(textRectStartX, textRectEndX),
                        Math.min(textRectStartY, textRectEndY),
                        Math.max(textRectStartX, textRectEndX),
                        Math.max(textRectStartY, textRectEndY),
                        selectionPaint);
                selectionPaint.setPathEffect(null);
            }
            RectF selBounds = overlayBoundsWorld();
            if (selBounds != null && !drawingLasso && !drawingTextRect) {
                float pad = 8f / scale;
                selectionPaint.setStyle(Paint.Style.STROKE);
                selectionPaint.setStrokeWidth(2.5f / scale);
                selectionPaint.setColor(chromeAccent);
                selectionPaint.setPathEffect(selectionDash);
                canvas.drawRect(
                        selBounds.left - pad, selBounds.top - pad,
                        selBounds.right + pad, selBounds.bottom + pad,
                        selectionPaint);
                selectionPaint.setPathEffect(null);
                drawSelectionGimbals(canvas, selBounds, scale);
            }
            if (!Float.isNaN(eraseX)) {
                float r = eraseRadiusPx / scale;
                eraseHaloPaint.setStrokeWidth(4.5f / scale);
                canvas.drawCircle(eraseX, eraseY, r, eraseHaloPaint);
                erasePreviewPaint.setStrokeWidth(2f / scale);
                canvas.drawCircle(eraseX, eraseY, r, erasePreviewPaint);
            }
        }

        canvas.restore();
    }
}
