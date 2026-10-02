package me.hapke.inkside;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Undo / redo buttons and the slide-to-undo scrubber.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class UndoScrubber {
    private final MainActivity act;

    /** Step the canvas is at in the current scrub (negative = undone). */
    private int undoScrubApplied;

    UndoScrubber(MainActivity act) {
        this.act = act;
    }

    /**
     * Sliding sideways across an undo/redo button scrubs through history: left undoes,
     * right redoes, one step per notch, previewed live on the canvas. A plain tap and
     * the long-press favorite menu still work as before.
     */
    void bindUndoScrub(View btn) {
        final float[] down = new float[2];
        final boolean[] scrubbing = {false};
        final int slop = android.view.ViewConfiguration.get(act).getScaledTouchSlop();
        btn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    scrubbing[0] = false;
                    // Keep the scrolling tool bar from claiming the sideways slide.
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0];
                    float dy = e.getRawY() - down[1];
                    if (!scrubbing[0]) {
                        if (Math.abs(dx) <= slop || Math.abs(dx) < Math.abs(dy)) return false;
                        if (act.canvas == null) return false;
                        scrubbing[0] = true;
                        v.setPressed(false);
                        v.cancelLongPress();
                        down[0] += Math.signum(dx) * slop;
                        dx = e.getRawX() - down[0];
                        int[] loc = new int[2];
                        v.getLocationInWindow(loc);
                        int[] host = new int[2];
                        if (act.undoScrubHost != null) act.undoScrubHost.getLocationInWindow(host);
                        float ax = loc[0] - host[0] + v.getWidth() / 2f;
                        float ay = loc[1] - host[1] + v.getHeight() / 2f;
                        boolean below = act.undoScrubHost == null || ay < act.undoScrubHost.getHeight() / 2f;
                        if (below && act.toolScroll != null && act.toolScroll.isShown()) {
                            // Hang the card just under the whole tool bar (colour row included).
                            int[] bar = new int[2];
                            act.toolScroll.getLocationInWindow(bar);
                            ay = bar[1] - host[1] + act.toolScroll.getHeight() - act.dp(20);
                        }
                        beginUndoScrub(null, ax, ay, below, act.dp(24));
                    }
                    dragUndoScrub(dx);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!scrubbing[0]) return false;
                    scrubbing[0] = false;
                    endUndoScrub();
                    return true;
                default:
                    return scrubbing[0];
            }
        });
    }

    /**
     * Greys undo/redo out when there is nothing to undo/redo. They stay touchable, so
     * a slide can still start on a greyed button and scrub the other way.
     */
    void syncUndoRedoButtons() {
        fadeHistoryButton(act.undoButton, act.canvas != null && act.canvas.canUndo());
        fadeHistoryButton(act.redoButton, act.canvas != null && act.canvas.canRedo());
    }

    private void fadeHistoryButton(View b, boolean available) {
        if (b == null) return;
        float target = available ? 1f : 0.38f;
        // Compare with where the fade is heading, not the alpha it has reached so far:
        // a state that flips back mid-fade would otherwise leave the old fade running.
        Object heading = b.getTag(R.id.history_fade_target);
        if (heading instanceof Float && (Float) heading == target) return;
        b.setTag(R.id.history_fade_target, target);
        b.animate().cancel();
        b.animate().alpha(target).setDuration(160).start();
        b.setContentDescription((b == act.undoButton ? "Undo" : "Redo")
                + (available ? "" : " (nothing to " + (b == act.undoButton ? "undo)" : "redo)")));
    }

    /** Anchor is in {@code from}'s coordinates, or the scrub host's when null. */
    void beginUndoScrub(View from, float x, float y, boolean below, float stepPx) {
        if (act.undoScrub == null || act.canvas == null) return;
        if (from != null && act.undoScrubHost != null) {
            int[] a = new int[2];
            int[] b = new int[2];
            from.getLocationInWindow(a);
            act.undoScrubHost.getLocationInWindow(b);
            x += a[0] - b[0];
            y += a[1] - b[1];
        }
        undoScrubApplied = 0;
        act.undoScrub.setColors(act.M3_SURFACE_CONTAINER_HIGHEST, act.M3_ON_SURFACE, act.M3_ON_SURFACE_VARIANT,
                act.M3_PRIMARY, act.M3_SURFACE_CONTAINER_HIGHEST, act.M3_OUTLINE_VARIANT);
        act.undoScrub.begin(x, y, below, act.canvas.undoDepth(), act.canvas.redoDepth(), stepPx);
    }

    void dragUndoScrub(float dx) {
        if (act.undoScrub == null || act.canvas == null || !act.undoScrub.isScrubbing()) return;
        int target = act.undoScrub.drag(dx);
        while (undoScrubApplied > target && act.canvas.undoDepth() > 0) {
            act.canvas.undo();
            undoScrubApplied--;
        }
        while (undoScrubApplied < target && act.canvas.redoDepth() > 0) {
            act.canvas.redo();
            undoScrubApplied++;
        }
    }

    void endUndoScrub() {
        if (act.undoScrub == null) return;
        boolean changed = undoScrubApplied != 0;
        act.undoScrub.end();
        undoScrubApplied = 0;
        if (changed) act.persistence.scheduleSave();
    }
}
