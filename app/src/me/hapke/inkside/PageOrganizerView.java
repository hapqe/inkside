package me.hapke.inkside;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A grid of page thumbnails to rearrange. A tap opens a page; long-press selects it
 * and drags the selection to a new place; while anything is selected, taps add to or
 * take from the selection. Every change of order animates: pages glide to their new
 * places, new ones grow in, deleted ones fade. Edits only change {@link #order()} —
 * the host applies it to the document when the user is done.
 */
final class PageOrganizerView extends View {
    interface Host {
        /** A thumbnail of the document's current page {@code source}; null if none. */
        void loadThumb(int source, java.util.function.Consumer<Bitmap> done);

        /** A tap with nothing selected: show the page that is now at {@code position}. */
        void openPage(int position, int source);

        void changed();
    }

    /** One slot in the new order. {@code source} is the current page, or −1 for a new blank. */
    private static final class Entry {
        final int source;
        final long uid;

        Entry(int source, long uid) {
            this.source = source;
            this.uid = uid;
        }
    }

    private final Host host;
    private final float density;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private final Map<Integer, Bitmap> thumbs = new HashMap<>();
    private final Set<Integer> loading = new HashSet<>();
    private long nextUid = 1;
    private int originalCount;
    private float pageAspect = 1.414f;

    private final Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private int cols = 3;
    private float cellW, thumbW, thumbH, cellH;
    private final float gap, pad, labelH;

    private float scrollY;
    private final OverScroller scroller;
    private final GestureDetector gestures;

    /** Drag state: the entries being carried, where the finger is, where they would land. */
    private List<Entry> dragging;
    private float dragX, dragY;
    private int dropIndex = -1;
    private boolean autoScrolling;

    PageOrganizerView(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        density = ctx.getResources().getDisplayMetrics().density;
        gap = 18f * density;
        pad = 16f * density;
        labelH = 26f * density;
        scroller = new OverScroller(ctx);
        paper.setColor(0xFFFFFFFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1f * density);
        accent.setStyle(Paint.Style.STROKE);
        accent.setStrokeWidth(3f * density);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(13f * density);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTextSize(12f * density);
        badgeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        shadow.setColor(0x33000000);
        addIcon = ctx.getDrawable(R.drawable.ic_add).mutate();
        checkIcon = ctx.getDrawable(R.drawable.ic_check).mutate();
        setColors(0xFF3F51B5, 0xFFE6E1E9, 0xFF49454F, 0xFFFFFFFF);
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                cellRect(entries.size(), tmp);
                if (tmp.contains(e.getX(), e.getY())) {
                    appendBlank();
                    return true;
                }
                int i = hit(e.getX(), e.getY());
                if (i < 0) {
                    if (!selected.isEmpty()) {
                        selected.clear();
                        host.changed();
                        invalidate();
                    }
                    return true;
                }
                if (selected.isEmpty()) {
                    host.openPage(i, entries.get(i).source);
                    return true;
                }
                long uid = entries.get(i).uid;
                if (!selected.remove(uid)) selected.add(uid);
                host.changed();
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                int i = hit(e.getX(), e.getY());
                if (i < 0) return;
                Entry pressed = entries.get(i);
                if (!selected.contains(pressed.uid)) {
                    selected.clear();
                    selected.add(pressed.uid);
                }
                dragging = new ArrayList<>();
                for (Entry en : entries) if (selected.contains(en.uid)) dragging.add(en);
                dragX = e.getX();
                dragY = e.getY();
                dropIndex = insertionIndex(dragX, dragY);
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                getParent().requestDisallowInterceptTouchEvent(true);
                host.changed();
                invalidate();
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (dragging != null) return false;
                scrollY = clampScroll(scrollY + dy);
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (dragging != null) return false;
                scroller.fling(0, Math.round(scrollY), 0, Math.round(-vy), 0, 0, 0,
                        Math.round(maxScroll()));
                postInvalidateOnAnimation();
                return true;
            }
        });
    }

    void setColors(int accentColor, int onSurface, int outline, int paperColor) {
        accent.setColor(accentColor);
        label.setColor(onSurface);
        border.setColor(outline);
        paper.setColor(paperColor);
        badge.setColor(accentColor);
        badgeText.setColor(0xFFFFFFFF);
        invalidate();
    }

    /** Starts over from the document as it is: {@code count} pages of {@code aspect} h/w. */
    void reset(int count, float aspect) {
        entries.clear();
        selected.clear();
        anim.clear();
        growIn.clear();
        fading.clear();
        for (int i = 0; i < count; i++) entries.add(new Entry(i, nextUid++));
        originalCount = count;
        pageAspect = aspect > 0.1f ? aspect : 1.414f;
        requestLayout();
        invalidate();
    }

    /** The new order: per slot, the current page it shows, or −1 for a new blank page. */
    int[] order() {
        int[] out = new int[entries.size()];
        for (int i = 0; i < out.length; i++) out[i] = entries.get(i).source;
        return out;
    }

    boolean isChanged() {
        if (entries.size() != originalCount) return true;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).source != i) return true;
        }
        return false;
    }

    int pageCount() {
        return entries.size();
    }

    int selectedCount() {
        return selected.size();
    }

    /** The position of the only selected page, or −1. */
    int singleSelectedPosition() {
        if (selected.size() != 1) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (selected.contains(entries.get(i).uid)) return i;
        }
        return -1;
    }

    int sourceAt(int position) {
        return position >= 0 && position < entries.size() ? entries.get(position).source : -1;
    }

    void selectAll(boolean all) {
        selected.clear();
        if (all) for (Entry e : entries) selected.add(e.uid);
        host.changed();
        invalidate();
    }

    /** A blank page after the last selected one (at the end with nothing selected); selects it. */
    /** The "+" tile after the last page: a blank page at the end. */
    private void appendBlank() {
        Entry blank = new Entry(-1, nextUid++);
        entries.add(blank);
        growIn.add(blank.uid);
        revealPosition(entries.size());  // keep the + tile in view for the next one
        host.changed();
        invalidate();
    }

    /** Each selected page gets a copy right after it; the copies become the selection. */
    void duplicateSelected() {
        if (selected.isEmpty()) return;
        List<Entry> next = new ArrayList<>();
        Set<Long> copies = new HashSet<>();
        int last = -1;
        for (Entry e : entries) {
            next.add(e);
            if (selected.contains(e.uid)) {
                Entry copy = new Entry(e.source, nextUid++);
                next.add(copy);
                copies.add(copy.uid);
                // The copy slides out from under its original.
                float[] from = anim.get(e.uid);
                if (from != null) anim.put(copy.uid, new float[] {from[0], from[1], 1f, 1f});
                last = next.size() - 1;
            }
        }
        entries.clear();
        entries.addAll(next);
        selected.clear();
        selected.addAll(copies);
        if (last >= 0) revealPosition(last);
        host.changed();
        invalidate();
    }

    /** Removes the selected pages; a document keeps at least one. @return false if none left. */
    boolean deleteSelected() {
        if (selected.isEmpty()) return true;
        int remaining = 0;
        for (Entry e : entries) if (!selected.contains(e.uid)) remaining++;
        if (remaining == 0) return false;
        for (Entry e : entries) {
            if (!selected.contains(e.uid)) continue;
            float[] at = anim.get(e.uid);
            if (at != null) fading.add(new Ghost(e, at[0], at[1]));
            anim.remove(e.uid);
        }
        entries.removeIf(e -> selected.contains(e.uid));
        selected.clear();
        scrollY = clampScroll(scrollY);
        host.changed();
        invalidate();
        return true;
    }

    void recycleThumbs() {
        for (Bitmap b : thumbs.values()) if (b != null && !b.isRecycled()) b.recycle();
        thumbs.clear();
        shaders.clear();
        loading.clear();
    }

    // ---- Layout ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    private void relayout() {
        int w = getWidth();
        if (w <= 0) return;
        float usable = w - 2 * pad;
        float target = 150f * density;
        cols = Math.max(2, (int) ((usable + gap) / (target + gap)));
        cellW = (usable - gap * (cols - 1)) / cols;
        thumbW = cellW;
        thumbH = thumbW * pageAspect;
        cellH = thumbH + labelH;
        scrollY = clampScroll(scrollY);
    }

    private float contentHeight() {
        // One more cell than pages: the "+" tile.
        int rows = (entries.size() + 1 + cols - 1) / Math.max(1, cols);
        return pad * 2 + rows * cellH + Math.max(0, rows - 1) * gap;
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight() - getHeight());
    }

    private float clampScroll(float y) {
        return Math.max(0f, Math.min(maxScroll(), y));
    }

    /** Top-left of cell {@code i} in content space (scroll not applied). */
    private float cellX(int i) {
        return pad + (i % cols) * (cellW + gap);
    }

    private float cellY(int i) {
        return pad + (i / cols) * (cellH + gap);
    }

    private void cellRect(int i, RectF out) {
        int row = i / cols, col = i % cols;
        float left = pad + col * (cellW + gap);
        float top = pad + row * (cellH + gap) - scrollY;
        out.set(left, top, left + thumbW, top + thumbH);
    }

    private void revealPosition(int i) {
        if (cellW <= 0) return;
        cellRect(i, tmp);
        if (tmp.top < pad) scrollY = clampScroll(scrollY + tmp.top - pad);
        else if (tmp.bottom + labelH > getHeight() - pad) {
            scrollY = clampScroll(scrollY + tmp.bottom + labelH - getHeight() + pad);
        }
    }

    private int hit(float x, float y) {
        for (int i = 0; i < entries.size(); i++) {
            cellRect(i, tmp);
            tmp.bottom += labelH;
            if (tmp.contains(x, y)) return i;
        }
        return -1;
    }

    /** Where a drop at (x, y) would insert, counting the entries not being dragged. */
    private int insertionIndex(float x, float y) {
        List<Entry> rest = new ArrayList<>(entries);
        if (dragging != null) rest.removeAll(dragging);
        float gy = y + scrollY - pad;
        int row = Math.max(0, (int) Math.floor(gy / (cellH + gap)));
        float gx = x - pad;
        int col = (int) Math.floor((gx + (cellW + gap) * 0.5f) / (cellW + gap));
        col = Math.max(0, Math.min(cols, col));
        return Math.max(0, Math.min(rest.size(), row * cols + col));
    }

    // ---- Touch -------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (dragging != null) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    dragX = e.getX();
                    dragY = e.getY();
                    dropIndex = insertionIndex(dragX, dragY);
                    startAutoScroll();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    finishDrag(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    finishDrag(false);
                    return true;
                default:
                    return true;
            }
        }
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    private void finishDrag(boolean drop) {
        if (drop && dragging != null && dropIndex >= 0) {
            List<Entry> rest = new ArrayList<>(entries);
            rest.removeAll(dragging);
            int at = Math.min(dropIndex, rest.size());
            rest.addAll(at, dragging);
            entries.clear();
            entries.addAll(rest);
        }
        if (dragging != null) {
            // They land from where the finger let go.
            float w = thumbW * 0.8f, hh = thumbH * 0.8f;
            for (int k = 0; k < dragging.size(); k++) {
                float o = Math.min(k, 2) * 6f * density;
                float x = dragX - w / 2 + o, y = dragY - hh / 2 + o + scrollY;
                anim.put(dragging.get(k).uid, new float[] {x, y, 0.8f, 1f});
            }
        }
        dragging = null;
        dropIndex = -1;
        host.changed();
        invalidate();
    }

    /** Near the top or bottom edge while dragging, the grid scrolls under the finger. */
    private void startAutoScroll() {
        if (autoScrolling) return;
        autoScrolling = true;
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                if (dragging == null) {
                    autoScrolling = false;
                    return;
                }
                float edge = 70f * density;
                float step = 0f;
                if (dragY < edge) step = -(edge - dragY) * 0.25f;
                else if (dragY > getHeight() - edge) step = (dragY - (getHeight() - edge)) * 0.25f;
                if (step != 0f) {
                    float before = scrollY;
                    scrollY = clampScroll(scrollY + step);
                    if (scrollY != before) {
                        dropIndex = insertionIndex(dragX, dragY);
                        invalidate();
                    }
                    postOnAnimation(this);
                } else {
                    autoScrolling = false;
                }
            }
        });
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = clampScroll(scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    // ---- Drawing -----------------------------------------------------------------

    // ---- Animation ---------------------------------------------------------------

    /** Per page (by uid): where it is drawn now — content x, y — and its scale and alpha. */
    private final Map<Long, float[]> anim = new HashMap<>();
    /** Pages just added that grow in where they land. */
    private final Set<Long> growIn = new HashSet<>();

    /** A deleted page fading out where it was. */
    private static final class Ghost {
        final Entry entry;
        final float x, y;
        float t = 1f;

        Ghost(Entry entry, float x, float y) {
            this.entry = entry;
            this.x = x;
            this.y = y;
        }
    }

    private final List<Ghost> fading = new ArrayList<>();
    private long lastFrameNs;
    /** Time constant of the glide (ms): about 95% of the way in three of these. */
    private static final float GLIDE_MS = 70f;

    /** Moves {@code cur} toward the target by one frame; true while still moving. */
    private boolean step(float[] cur, float tx, float ty, float k) {
        cur[0] += (tx - cur[0]) * k;
        cur[1] += (ty - cur[1]) * k;
        cur[2] += (1f - cur[2]) * k;
        cur[3] += (1f - cur[3]) * k;
        boolean moving = Math.abs(tx - cur[0]) > 0.5f || Math.abs(ty - cur[1]) > 0.5f
                || Math.abs(1f - cur[2]) > 0.005f || Math.abs(1f - cur[3]) > 0.01f;
        if (!moving) {
            cur[0] = tx;
            cur[1] = ty;
            cur[2] = 1f;
            cur[3] = 1f;
        }
        return moving;
    }

    // ---- Drawing -----------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (cellW <= 0) relayout();
        if (cellW <= 0) return;
        long now = System.nanoTime();
        float dtMs = lastFrameNs == 0 ? 16f : Math.min(48f, (now - lastFrameNs) / 1e6f);
        lastFrameNs = now;
        float k = 1f - (float) Math.exp(-dtMs / GLIDE_MS);
        boolean moving = false;

        // With a drag under way, the grid shows the order the drop would make.
        List<Entry> shown = entries;
        if (dragging != null && dropIndex >= 0) {
            shown = new ArrayList<>(entries);
            shown.removeAll(dragging);
            int at = Math.min(dropIndex, shown.size());
            for (int n = 0; n < dragging.size(); n++) shown.add(at + n, null);
        }
        int h = getHeight();

        for (int g = fading.size() - 1; g >= 0; g--) {
            Ghost gh = fading.get(g);
            gh.t -= dtMs / 180f;
            if (gh.t <= 0f) {
                fading.remove(g);
                continue;
            }
            moving = true;
            float sc = 0.85f + 0.15f * gh.t;
            float cx = gh.x + thumbW / 2, cy = gh.y - scrollY + thumbH / 2;
            tmp.set(cx - thumbW * sc / 2, cy - thumbH * sc / 2, cx + thumbW * sc / 2, cy + thumbH * sc / 2);
            int layer = c.saveLayerAlpha(tmp.left - 8 * density, tmp.top - 8 * density,
                    tmp.right + 8 * density, tmp.bottom + 8 * density, Math.round(255 * gh.t));
            drawThumb(c, gh.entry, tmp, false);
            c.restoreToCount(layer);
        }

        for (int i = 0; i < shown.size(); i++) {
            Entry e = shown.get(i);
            float tx = cellX(i), ty = cellY(i);
            if (e == null) {
                // The gap the dragged pages would fill.
                tmp.set(tx, ty - scrollY, tx + thumbW, ty - scrollY + thumbH);
                if (tmp.bottom < 0 || tmp.top > h) continue;
                accent.setAlpha(110);
                c.drawRoundRect(tmp, 8f * density, 8f * density, accent);
                accent.setAlpha(255);
                continue;
            }
            float[] cur = anim.get(e.uid);
            if (cur == null) {
                boolean grow = growIn.remove(e.uid);
                cur = new float[] {tx, ty, grow ? 0.6f : 1f, grow ? 0f : 1f};
                anim.put(e.uid, cur);
            }
            if (step(cur, tx, ty, k)) moving = true;
            float sc = cur[2];
            float cx = cur[0] + thumbW / 2, cy = cur[1] - scrollY + thumbH / 2;
            tmp.set(cx - thumbW * sc / 2, cy - thumbH * sc / 2, cx + thumbW * sc / 2, cy + thumbH * sc / 2);
            if (tmp.bottom + labelH < 0 || tmp.top > h) continue;
            boolean fadeIn = cur[3] < 0.99f;
            int layer = fadeIn ? c.saveLayerAlpha(tmp.left - 8 * density, tmp.top - 8 * density,
                    tmp.right + 8 * density, tmp.bottom + labelH, Math.round(255 * cur[3])) : -1;
            drawThumb(c, e, tmp, selected.contains(e.uid));
            String name = String.valueOf(i + 1);
            if (e.source < 0) name += " · new";
            c.drawText(name, tmp.centerX(), tmp.bottom + labelH * 0.72f, label);
            if (layer >= 0) c.restoreToCount(layer);
        }

        // The "+" tile after the last page.
        int addAt = shown.size();
        float[] addCur = anim.get(ADD_TILE);
        if (addCur == null) {
            addCur = new float[] {cellX(addAt), cellY(addAt), 1f, 1f};
            anim.put(ADD_TILE, addCur);
        }
        if (step(addCur, cellX(addAt), cellY(addAt), k)) moving = true;
        tmp.set(addCur[0], addCur[1] - scrollY, addCur[0] + thumbW, addCur[1] - scrollY + thumbH);
        if (tmp.bottom >= 0 && tmp.top <= h) drawAddTile(c, tmp);

        if (dragging != null) {
            float w = thumbW * 0.8f, hh = thumbH * 0.8f;
            for (int n = Math.min(dragging.size(), 3) - 1; n >= 0; n--) {
                float o = n * 6f * density;
                tmp.set(dragX - w / 2 + o, dragY - hh / 2 + o, dragX + w / 2 + o, dragY + hh / 2 + o);
                drawThumb(c, dragging.get(n), tmp, true);
            }
            if (dragging.size() > 1) {
                float r = 13f * density;
                float cx = dragX + w / 2, cy = dragY - hh / 2;
                c.drawCircle(cx, cy, r, badge);
                c.drawText(String.valueOf(dragging.size()), cx,
                        cy - (badgeText.ascent() + badgeText.descent()) / 2, badgeText);
            }
        }
        if (moving) {
            postInvalidateOnAnimation();
        } else {
            lastFrameNs = 0;
        }
    }

    /** Animation key of the "+" tile (page uids start at 1). */
    private static final long ADD_TILE = -1L;
    private final Paint addFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint addStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.DashPathEffect addDash =
            new android.graphics.DashPathEffect(new float[] {10f, 8f}, 0f);

    private android.graphics.drawable.Drawable addIcon;
    private android.graphics.drawable.Drawable checkIcon;

    /** Material Symbols glyph centred on (cx, cy), {@code size} px square. */
    private void drawIcon(Canvas c, android.graphics.drawable.Drawable d, float cx, float cy,
                          float size, int color) {
        d.setTint(color);
        int h = Math.round(size / 2f);
        d.setBounds(Math.round(cx) - h, Math.round(cy) - h, Math.round(cx) + h, Math.round(cy) + h);
        d.draw(c);
    }

    private void drawAddTile(Canvas c, RectF r) {
        float radius = 6f * getResources().getDisplayMetrics().density;
        int color = accent.getColor();
        addFill.setStyle(Paint.Style.FILL);
        addFill.setColor((color & 0x00FFFFFF) | 0x1A000000);
        c.drawRoundRect(r, radius, radius, addFill);
        addStroke.setStyle(Paint.Style.STROKE);
        addStroke.setStrokeWidth(2f * density);
        addStroke.setColor((color & 0x00FFFFFF) | 0x99000000);
        addStroke.setPathEffect(addDash);
        c.drawRoundRect(r, radius, radius, addStroke);
        // The plus: a filled circle with a white cross.
        float cx = r.centerX(), cy = r.centerY();
        float cr = Math.min(r.width(), r.height()) * 0.16f;
        addFill.setColor(color);
        c.drawCircle(cx, cy, cr, addFill);
        drawIcon(c, addIcon, cx, cy, cr * 1.3f, 0xFFFFFFFF);
    }

    private void drawThumb(Canvas c, Entry e, RectF r, boolean isSelected) {
        float radius = 6f * density;
        c.drawRoundRect(r.left + 2 * density, r.top + 3 * density, r.right + 2 * density,
                r.bottom + 3 * density, radius, radius, shadow);
        c.drawRoundRect(r, radius, radius, paper);
        if (e.source >= 0) {
            Bitmap b = thumbs.get(e.source);
            if (b != null && !b.isRecycled()) {
                // Painted through the rounded card itself, so the corners are cut
                // (and anti-aliased) rather than the square bitmap poking past them.
                android.graphics.BitmapShader shader = shaders.get(b);
                if (shader == null) {
                    shader = new android.graphics.BitmapShader(b,
                            android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    shaders.put(b, shader);
                }
                shaderMatrix.setRectToRect(new RectF(0, 0, b.getWidth(), b.getHeight()), r,
                        android.graphics.Matrix.ScaleToFit.FILL);
                shader.setLocalMatrix(shaderMatrix);
                bmpPaint.setShader(shader);
                c.drawRoundRect(r, radius, radius, bmpPaint);
                bmpPaint.setShader(null);
            } else {
                requestThumb(e.source);
            }
        }
        c.drawRoundRect(r, radius, radius, border);
        if (isSelected) {
            tmp2.set(r);
            tmp2.inset(-3f * density, -3f * density);
            c.drawRoundRect(tmp2, radius + 3 * density, radius + 3 * density, accent);
            float cr = 11f * density;
            float cx = r.right - cr - 4 * density, cy = r.top + cr + 4 * density;
            c.drawCircle(cx, cy, cr, badge);
            drawIcon(c, checkIcon, cx, cy, cr * 1.5f, badgeText.getColor());
        }
    }

    private final RectF tmp2 = new RectF();
    private final Map<Bitmap, android.graphics.BitmapShader> shaders = new java.util.IdentityHashMap<>();
    private final android.graphics.Matrix shaderMatrix = new android.graphics.Matrix();

    private void requestThumb(int source) {
        if (loading.contains(source)) return;
        loading.add(source);
        host.loadThumb(source, bmp -> {
            if (bmp != null) {
                Bitmap old = thumbs.put(source, bmp);
                if (old != null && old != bmp) {
                    shaders.remove(old);
                    if (!old.isRecycled()) old.recycle();
                }
            }
            invalidate();
        });
    }
}
