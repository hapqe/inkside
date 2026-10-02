package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings editor for the pen favorites radial: the menu drawn large, its items always
 * evenly spaced on a regular n-gon ring. Dragging an item around the ring reorders it —
 * the others glide aside as it passes — and on release it settles into its slot. What
 * is arranged here is exactly the pen menu's order, only zoomed by {@link #ZOOM}.
 */
final class FavoritesLayoutView extends View {
    interface OnReordered {
        void reordered(List<String> ids);
    }

    /** Editor px per radial px, so small chips are easy to grab with a finger. */
    private static final float ZOOM = 2.4f;
    private static final float HUB_DP = 14f;
    /** Per frame, how much of the way to its slot an item moves. */
    private static final float GLIDE = 0.22f;

    private final List<RadialFavoritesPainter.Item> items;
    private final OnReordered onReordered;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Where each item is drawn right now (radial dp), gliding toward its slot. */
    private final Map<String, float[]> shown = new HashMap<>();
    private RadialFavoritesPainter.Item dragging;
    private float dragX, dragY;
    private int startIndex = -1;

    private int colorChip;
    private int colorChipActive;
    private int colorIcon;
    private int colorIconActive;
    private int colorGuide;
    private int colorLabel;

    FavoritesLayoutView(Context ctx, List<RadialFavoritesPainter.Item> items, OnReordered onReordered) {
        super(ctx);
        this.items = new ArrayList<>(items);
        this.onReordered = onReordered;
        density = ctx.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11f * density);
        for (int i = 0; i < this.items.size(); i++) {
            shown.put(this.items.get(i).id, slotOffset(i, this.items.size()));
        }
    }

    void applyColors(int chip, int chipActive, int icon, int iconActive, int guide, int label) {
        colorChip = chip;
        colorChipActive = chipActive;
        colorIcon = icon;
        colorIconActive = iconActive;
        colorGuide = guide;
        colorLabel = label;
        invalidate();
    }

    private float scale() {
        return density * ZOOM;
    }

    private static float[] slotOffset(int i, int n) {
        return RadialFavoritesPainter.defaultOffsetDp(i, n);
    }

    /**
     * Slot (0..n−1) of the n-gon nearest the direction of (x, y) in this view. Slots
     * run clockwise from the top, as {@link RadialFavoritesPainter#defaultOffsetDp}.
     */
    int slotForPoint(float x, float y, int n) {
        if (n <= 1) return 0;
        double deg = Math.toDegrees(Math.atan2(y - getHeight() / 2f, x - getWidth() / 2f));
        float slice = 360f / n;
        double rel = deg + 90.0 - slice / 2.0;
        int slot = (int) Math.round(rel / slice);
        slot %= n;
        if (slot < 0) slot += n;
        return slot;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float s = scale();
        int n = items.size();

        float ring = RadialFavoritesPainter.ringRadiusDp(Math.max(1, n));
        stroke.setColor(colorGuide);
        stroke.setStrokeWidth(density);
        stroke.setPathEffect(new DashPathEffect(new float[]{4 * density, 4 * density}, 0));
        canvas.drawCircle(cx, cy, ring * s, stroke);
        stroke.setPathEffect(null);
        canvas.drawCircle(cx, cy, HUB_DP * s, stroke);
        text.setColor(colorLabel);
        canvas.drawText("pen", cx, cy + 4 * density, text);

        // Glide every item toward its slot; the dragged one follows the finger.
        boolean moving = false;
        for (int i = 0; i < n; i++) {
            RadialFavoritesPainter.Item item = items.get(i);
            if (item == dragging) continue;
            float[] target = slotOffset(i, n);
            float[] cur = shown.get(item.id);
            if (cur == null) {
                cur = target.clone();
                shown.put(item.id, cur);
            }
            float dx = target[0] - cur[0];
            float dy = target[1] - cur[1];
            if (Math.abs(dx) > 0.05f || Math.abs(dy) > 0.05f) {
                // Along the ring rather than straight across it: ease the angle and radius.
                double a0 = Math.atan2(cur[1], cur[0]);
                double a1 = Math.atan2(target[1], target[0]);
                double da = a1 - a0;
                while (da > Math.PI) da -= 2 * Math.PI;
                while (da < -Math.PI) da += 2 * Math.PI;
                double r0 = Math.hypot(cur[0], cur[1]);
                double r1 = Math.hypot(target[0], target[1]);
                double a = a0 + da * GLIDE;
                double r = r0 + (r1 - r0) * GLIDE;
                cur[0] = (float) (Math.cos(a) * r);
                cur[1] = (float) (Math.sin(a) * r);
                moving = true;
            } else {
                cur[0] = target[0];
                cur[1] = target[1];
            }
        }

        float chipR = RadialFavoritesPainter.CHIP_DP * s / 2f;
        // Dragged item last, so it rides above the others.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                RadialFavoritesPainter.Item item = items.get(i);
                boolean active = item == dragging;
                if ((pass == 1) != active) continue;
                float[] o = active ? new float[]{dragX, dragY} : shown.get(item.id);
                float x = cx + o[0] * s;
                float y = cy + o[1] * s;
                stroke.setColor(colorGuide);
                canvas.drawLine(cx, cy, x, y, stroke);
                fill.setColor(active ? colorChipActive : colorChip);
                canvas.drawCircle(x, y, chipR * (active ? 1.12f : 1f), fill);
                int tint = active ? colorIconActive : colorIcon;
                if (item.iconRes == 0) {
                    fill.setColor(item.swatchColor);
                    canvas.drawCircle(x, y, chipR * 0.55f, fill);
                    stroke.setColor(tint);
                    stroke.setStrokeWidth(1.5f * density);
                    canvas.drawCircle(x, y, chipR * 0.55f, stroke);
                    stroke.setStrokeWidth(density);
                } else {
                    Drawable d = getContext().getDrawable(item.iconRes);
                    if (d != null) {
                        int half = Math.round(chipR * 0.62f);
                        d = d.mutate();
                        d.setBounds((int) x - half, (int) y - half, (int) x + half, (int) y + half);
                        d.setTint(tint);
                        d.draw(canvas);
                    }
                }
                text.setColor(colorLabel);
                canvas.drawText(item.label, x, y + chipR + 13 * density, text);
            }
        }
        if (moving) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float s = scale();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int hit = hit(e.getX(), e.getY(), cx, cy, s);
                if (hit < 0) return false;
                dragging = items.get(hit);
                startIndex = hit;
                float[] o = shown.get(dragging.id);
                dragX = o[0];
                dragY = o[1];
                // Inside the settings scroller: keep the drag ours.
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging == null) return false;
                dragX = (e.getX() - cx) / s;
                dragY = (e.getY() - cy) / s;
                // Keep it within the editor so it stays grabbable.
                float r = (float) Math.hypot(dragX, dragY);
                float max = Math.min(getWidth(), getHeight()) / 2f / s - RadialFavoritesPainter.CHIP_DP / 2f;
                if (r > max && r > 0.01f) {
                    dragX *= max / r;
                    dragY *= max / r;
                }
                int slot = slotForPoint(e.getX(), e.getY(), items.size());
                int at = items.indexOf(dragging);
                if (slot != at) {
                    items.remove(at);
                    items.add(slot, dragging);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging == null) return false;
                // Settle from where it was let go.
                shown.put(dragging.id, new float[]{dragX, dragY});
                boolean changed = items.indexOf(dragging) != startIndex;
                dragging = null;
                startIndex = -1;
                invalidate();
                if (changed && onReordered != null) {
                    List<String> ids = new ArrayList<>();
                    for (RadialFavoritesPainter.Item it : items) ids.add(it.id);
                    onReordered.reordered(ids);
                }
                return true;
            }
            default:
                return dragging != null;
        }
    }

    /** Topmost chip under the finger (with some slack), or −1. */
    private int hit(float x, float y, float cx, float cy, float s) {
        float grab = RadialFavoritesPainter.CHIP_DP * s / 2f * 1.3f;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = items.size() - 1; i >= 0; i--) {
            float[] o = shown.get(items.get(i).id);
            if (o == null) continue;
            float d = (float) Math.hypot(x - (cx + o[0] * s), y - (cy + o[1] * s));
            if (d <= grab && d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
