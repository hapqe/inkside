package me.hapke.inkside;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.content.Context;
import android.os.SystemClock;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pen favorites radial — floating toolbar-style icons (no pie wedges).
 * Highlight enlarges the icon in the tip's direction; the host activates it
 * when the primary pen button is released.
 */
final class RadialFavoritesPainter {
    static final class Item {
        final String id;
        final String label;
        final int iconRes;
        final int swatchColor;
        /** Offset from the menu center in dp; null → evenly spaced on the default ring. */
        float[] offsetDp;

        Item(String id, String label, int iconRes, int swatchColor) {
            this.id = id;
            this.label = label;
            this.iconRes = iconRes;
            this.swatchColor = swatchColor;
        }
    }

    /** Default ring radius (dp) for favorites the user has not placed. */
    static final float DEFAULT_ORBIT_DP = 28f;
    /** Chip diameter (dp) at rest. */
    static final float CHIP_DP = 20f;

    /**
     * Radius (dp) of the n-gon the items sit on: the default orbit, widened once n
     * chips would otherwise crowd each other.
     */
    static float ringRadiusDp(int n) {
        float needed = n * CHIP_DP * 1.45f / (float) (2 * Math.PI);
        return Math.max(DEFAULT_ORBIT_DP, needed);
    }

    /** Slot i of n on the regular n-gon, clockwise from the top. */
    static float[] defaultOffsetDp(int i, int n) {
        float slice = 360f / n;
        double mid = Math.toRadians(-90f + i * slice + slice / 2f);
        float r = ringRadiusDp(n);
        return new float[]{
                (float) Math.cos(mid) * r,
                (float) Math.sin(mid) * r,
        };
    }

    /**
     * Index of the item whose direction from the center is closest to (dx, dy).
     * Works for any placement, not just an even ring.
     */
    static int nearestByAngle(List<Item> items, float dx, float dy) {
        if (items.isEmpty() || (dx == 0f && dy == 0f)) return items.isEmpty() ? -1 : 0;
        double want = Math.atan2(dy, dx);
        int best = -1;
        double bestDiff = Double.MAX_VALUE;
        int n = items.size();
        for (int i = 0; i < n; i++) {
            float[] o = items.get(i).offsetDp != null
                    ? items.get(i).offsetDp : defaultOffsetDp(i, n);
            double diff = Math.abs(Math.atan2(o[1], o[0]) - want);
            if (diff > Math.PI) diff = Math.PI * 2 - diff;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private final Context appContext;
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Item> items = new ArrayList<>();
    private float[] scales = new float[0];
    private long lastAnimMs;
    private Runnable redraw;

    private float centerX;
    private float centerY;
    private float tipX;
    private float tipY;
    private int highlight = -1;
    private boolean open;
    private float density = 1f;
    private boolean tipWasInHub = true;

    private int colorSurfaceHigh = 0xFF2A2A32;
    private int colorPrimaryContainer = 0xFF3F4A8A;
    private int colorOnPrimaryContainer = 0xFFE8EAF6;
    private int colorOnSurface = 0xFFE6E1E9;
    private int colorOnSurfaceVariant = 0xFFCAC4D0;
    private int colorOutline = 0xFF49454F;

    private static final float SCALE_IDLE = 1f;
    private static final float SCALE_HOT = 1.28f;
    /** Approx. Material emphasized decelerate. */
    private static final PathInterpolator SCALE_EASE =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    RadialFavoritesPainter(Context context) {
        appContext = context.getApplicationContext();
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        discPaint.setStyle(Paint.Style.FILL);
    }

    void setRedraw(Runnable r) {
        redraw = r;
    }

    void setDensity(float d) {
        density = d > 0 ? d : 1f;
        textPaint.setTextSize(10f * density);
    }

    void applyColors(int surface, int surfaceHigh, int primary, int onPrimary,
                     int onSurface, int outline) {
        applyColors(surface, surfaceHigh, primary, primary, onPrimary, onSurface, onSurface, outline);
    }

    void applyColors(int surface, int surfaceHigh, int primary, int primaryContainer,
                     int onPrimaryContainer, int onSurface, int onSurfaceVariant, int outline) {
        colorSurfaceHigh = surfaceHigh;
        colorPrimaryContainer = primaryContainer != 0 ? primaryContainer : primary;
        colorOnPrimaryContainer = onPrimaryContainer;
        colorOnSurface = onSurface;
        colorOnSurfaceVariant = onSurfaceVariant != 0 ? onSurfaceVariant : onSurface;
        colorOutline = outline;
    }

    void setItems(List<Item> next) {
        items.clear();
        if (next != null) items.addAll(next);
        highlight = -1;
        ensureScales();
        Arrays.fill(scales, SCALE_IDLE);
    }

    boolean isOpen() {
        return open;
    }

    int highlightIndex() {
        return highlight;
    }

    void openAt(float x, float y) {
        open = true;
        centerX = x;
        centerY = y;
        tipX = x;
        tipY = y;
        highlight = -1;
        tipWasInHub = true;
        ensureScales();
        Arrays.fill(scales, SCALE_IDLE);
        lastAnimMs = SystemClock.uptimeMillis();
    }

    private float orbitR() {
        return dp(28);
    }

    private float hubR() {
        return dp(14);
    }

    private float pickPastR() {
        return dp(42);
    }

    private void ensureScales() {
        int n = items.size();
        if (scales.length != n) {
            scales = new float[n];
            Arrays.fill(scales, SCALE_IDLE);
        }
    }

    /**
     * Tip moved. Updates which icon is highlighted (enlarged).
     * Activation happens when the host releases the primary pen button.
     */
    String updateTip(float x, float y) {
        if (!open) return null;
        tipX = x;
        tipY = y;
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.hypot(dx, dy);
        float hub = hubR();

        boolean inHub = dist < hub;
        int idx = inHub ? -1 : angleIndex(dx, dy);
        if (idx != highlight) {
            highlight = idx;
            lastAnimMs = SystemClock.uptimeMillis();
            requestRedraw();
        } else {
            highlight = idx;
        }
        tipWasInHub = inHub;
        return null;
    }

    /**
     * Pen hovering over the open menu: enlarges the item under it, if any. Unlike
     * {@link #updateTip} this is a plain hit test — nothing is chosen by direction.
     */
    void hoverAt(float x, float y) {
        if (!open) return;
        tipX = x;
        tipY = y;
        int idx = hitIndex(x, y, 1.25f);
        if (idx != highlight) {
            highlight = idx;
            lastAnimMs = SystemClock.uptimeMillis();
            requestRedraw();
        }
    }

    /** Item under (x, y), or −1; {@code slack} widens the hit circle. */
    private int hitIndex(float x, float y, float slack) {
        if (items.isEmpty()) return -1;
        int n = items.size();
        float orbit = orbitR();
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float[] p = iconPos(i, n, orbit);
            float hitR = dp(16) * slack * (i == highlight ? SCALE_HOT : SCALE_IDLE);
            float d = (float) Math.hypot(x - p[0], y - p[1]);
            if (d <= hitR && d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Currently highlighted favorite id, or null. */
    String highlightedId() {
        if (!open || highlight < 0 || highlight >= items.size()) return null;
        return items.get(highlight).id;
    }

    /** The item tapped at (x, y), or null — only a tap on an item picks it. */
    String pickAt(float x, float y) {
        if (!open) return null;
        int idx = hitIndex(x, y, 1.25f);
        return idx >= 0 ? items.get(idx).id : null;
    }

    /**
     * Direction-only pick — no open menu required. Always returns a sector when
     * items exist (falls back to the first).
     */
    String idForUnitDirection(float dx, float dy) {
        if (items.isEmpty()) return null;
        int idx = angleIndex(dx, dy);
        if (idx < 0) idx = 0;
        return items.get(idx).id;
    }

    /**
     * Direction-only pick from the hub — used for a short tip-down flick.
     * Needs only a small offset out of the hub.
     */
    String pickByDirection(float x, float y) {
        if (!open || items.isEmpty()) return null;
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < hubR() * 0.5f) return null;
        int idx = angleIndex(dx, dy);
        if (idx < 0) return null;
        return items.get(idx).id;
    }

    String closeAndPick() {
        String id = ensureHighlightedId();
        open = false;
        highlight = -1;
        tipWasInHub = true;
        return id;
    }

    /**
     * Guarantees a highlighted favorite when the menu has items — picks the
     * current highlight, otherwise the top sector.
     */
    String ensureHighlightedId() {
        if (!open || items.isEmpty()) return null;
        if (highlight >= 0 && highlight < items.size()) return items.get(highlight).id;
        highlight = 0;
        return items.get(0).id;
    }

    void cancel() {
        open = false;
        highlight = -1;
        tipWasInHub = true;
    }

    private int angleIndex(float dx, float dy) {
        return nearestByAngle(items, dx, dy);
    }

    private float[] iconPos(int i, int n, float orbit) {
        Item item = items.get(i);
        float[] o = item.offsetDp != null ? item.offsetDp : defaultOffsetDp(i, n);
        return new float[]{centerX + dp(o[0]), centerY + dp(o[1])};
    }

    /**
     * The menu is drawn this much larger than its layout's dp (offsets, chips, hit areas
     * and the hub all scale together), so the buttons are easy to hit with a pen or finger.
     */
    private static final float MENU_SCALE = 1.45f;

    private float dp(float v) {
        return v * density * MENU_SCALE;
    }

    private void requestRedraw() {
        if (redraw != null) redraw.run();
    }

    /** Advance per-icon scale toward idle/hot; returns true if still animating. */
    private boolean tickScales() {
        ensureScales();
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - lastAnimMs) / 1000f);
        lastAnimMs = now;
        boolean animating = false;
        // Faster settle (~80ms feel).
        float k = 1f - (float) Math.exp(-dt * 22f);
        for (int i = 0; i < scales.length; i++) {
            float target = (i == highlight) ? SCALE_HOT : SCALE_IDLE;
            float s = scales[i];
            float next = s + (target - s) * k;
            // Ease the last bit so it settles cleanly.
            if (Math.abs(target - next) < 0.008f) next = target;
            else animating = true;
            scales[i] = next;
        }
        return animating;
    }

    void draw(Canvas canvas) {
        if (!open) return;
        boolean animating = tickScales();
        int n = items.size();

        if (n == 0) {
            textPaint.setColor(colorOnSurface);
            canvas.drawText("No favorites", centerX, centerY + dp(4), textPaint);
            if (animating) requestRedraw();
            return;
        }

        float orbit = orbitR();
        float baseBtn = dp(20);

        for (int i = 0; i < n; i++) {
            boolean hi = i == highlight;
            float scale = i < scales.length ? scales[i] : SCALE_IDLE;
            float t = Math.max(0f, Math.min(1f, (scale - SCALE_IDLE) / (SCALE_HOT - SCALE_IDLE)));
            float visual = SCALE_IDLE + (scale - SCALE_IDLE) * SCALE_EASE.getInterpolation(t);
            float[] p = iconPos(i, n, orbit);
            float ix = p[0];
            float iy = p[1];
            float btn = baseBtn * visual;
            float half = btn * 0.5f;

            // Always show a chip background (toolbar selected / idle variants).
            discPaint.setStyle(Paint.Style.FILL);
            discPaint.setColor(hi || visual > 1.04f ? colorPrimaryContainer : colorSurfaceHigh);
            canvas.drawCircle(ix, iy, half, discPaint);

            Item item = items.get(i);
            int tint = hi || visual > 1.06f ? colorOnPrimaryContainer : colorOnSurfaceVariant;
            if (item.iconRes == 0) {
                float sw = half * 0.55f;
                iconPaint.setStyle(Paint.Style.FILL);
                iconPaint.setColor(item.swatchColor);
                canvas.drawCircle(ix, iy, sw, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
                iconPaint.setStrokeWidth(dp(1.5f));
                iconPaint.setColor(tint);
                canvas.drawCircle(ix, iy, sw, iconPaint);
                iconPaint.setStyle(Paint.Style.FILL);
            } else {
                Drawable d = appContext.getDrawable(item.iconRes);
                if (d != null) {
                    int s = Math.round(btn * 0.62f);
                    d = d.mutate();
                    d.setBounds((int) (ix - s / 2f), (int) (iy - s / 2f),
                            (int) (ix + s / 2f), (int) (iy + s / 2f));
                    d.setTint(tint);
                    d.draw(canvas);
                }
            }
        }

        if (animating) requestRedraw();
    }
}
