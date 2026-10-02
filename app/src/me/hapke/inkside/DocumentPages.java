package me.hapke.inkside;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF page stack for the canvas — Lineage/AOSP style.
 *
 * <p>Each file page is rasterised once at roughly full-screen resolution via
 * {@link PdfRenderer} (PDFium) on a background thread, then blit. No tile atlas,
 * no zoom-chasing re-renders: pan/zoom just transforms the cached bitmaps.
 */
final class DocumentPages {
    static final float PAGE_GAP = 28f;
    /** Cap on a full-page bitmap edge (GPU / memory). */
    private static final int MAX_PAGE_DIM = 4096;
    private static final float SHADOW_DX = 3f;
    private static final float SHADOW_DY = 4f;
    private static final long CACHE_BUDGET_BYTES = 180L * 1024 * 1024;
    private static final int CACHE_MIN_PAGES = 2;
    private static final int EAGER_PAGES = 3;
    /** Full-res pages kept ready on each side of what is on screen. */
    private static final int PREFETCH_PAGES = 1;
    /**
     * Long edge of the low-res stand-in kept for every page.
     *
     * <p>A full-res page costs ~47MB here, so only about three fit in the budget:
     * scrolling evicted them and each page then rasterised as it came into view,
     * in plain sight. A preview is ~1/17th of that, so the whole document stays
     * cached and a page is never blank — it arrives soft and sharpens a frame or
     * two later.
     */
    private static final int PREVIEW_LONG_EDGE = 760;
    /** ~40 A4 previews: most documents are fully covered in low res. */
    private static final long PREVIEW_BUDGET_BYTES = 64L * 1024 * 1024;
    private static final int PREVIEW_MIN_PAGES = 8;
    /** Low-res pages kept ready on each side of the view, ahead of a fast scroll. */
    private static final int PREVIEW_PREFETCH_PAGES = 4;

    String path = "";
    int pageCount;
    float pageWidth = PdfDocumentIo.A4_WIDTH_PT;
    float pageHeight = PdfDocumentIo.A4_HEIGHT_PT;
    boolean blankOwned;
    int filePageCount;
    int blankPrefix;

    private static final class PageBmp {
        final Bitmap bmp;

        PageBmp(Bitmap bmp) {
            this.bmp = bmp;
        }
    }

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private File cacheFile;
    private final Object renderLock = new Object();
    private final Object cacheLock = new Object();
    private final LinkedHashMap<Integer, PageBmp> pageCache =
            new LinkedHashMap<>(16, 0.75f, /*accessOrder*/ true);
    private final Set<Integer> pending = new HashSet<>();
    private final LinkedHashMap<Integer, Bitmap> previewCache =
            new LinkedHashMap<>(16, 0.75f, /*accessOrder*/ true);
    private final Set<Integer> previewPending = new HashSet<>();
    /** Requested previews not yet started; the worker takes the one nearest the view. */
    private final Set<Integer> previewQueue = new HashSet<>();
    /**
     * Previews render through their own PdfRenderer (one open page per renderer), so a
     * soft page never waits behind a ~47MB full-res raster — that wait is what left
     * pages blank while scrolling.
     */
    private PdfRenderer previewRenderer;
    private ParcelFileDescriptor previewPfd;
    private final Object previewRenderLock = new Object();
    /**
     * File pages whose pixels changed since the scene last took them. A scroll-time
     * redraw that only fills the newly exposed strip redraws these areas too.
     */
    private final Set<Integer> landedPages = new HashSet<>();
    /** File-page range last drawn (UI or rebuild thread); steers queues and eviction. */
    private volatile int viewFirst;
    private volatile int viewLast;
    private final Paint pageBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private ExecutorService pageExecutor;
    /** Previews have their own thread so a slow full-res page never holds them up. */
    private ExecutorService previewExecutor;
    private volatile int docGen;
    private Runnable pageReadyListener;
    /** Fixed raster scale chosen at open from the display size. */
    private float displayScale = 2.5f;

    private final Paint pageShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * Mutated while drawing (colour, style, xfermode), and pages are drawn from several
     * threads at once — the UI thread plus the parallel scroll redraw — so one each.
     */
    private final ThreadLocal<Paint> pageRuleTl =
            ThreadLocal.withInitial(() -> new Paint(Paint.ANTI_ALIAS_FLAG));
    private final ThreadLocal<Paint> pageTintTl =
            ThreadLocal.withInitial(() -> new Paint(Paint.ANTI_ALIAS_FLAG));
    private static final android.graphics.PorterDuffXfermode MULTIPLY =
            new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY);
    private static final android.graphics.PorterDuffXfermode DARKEN =
            new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DARKEN);

    private static boolean isDarkPaper(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 140;
    }

    static final String STYLE_BLANK = "blank";
    static final String STYLE_GRID = "grid";
    static final String STYLE_LINES = "lines";
    static final String STYLE_DOTS = "dots";

    int paperOverride;
    String pageStyle = STYLE_BLANK;
    float ruleScale = 1f;
    static final float RULE_SCALE_MIN = 0.5f;
    static final float RULE_SCALE_MAX = 2.5f;
    private final java.util.HashMap<Integer, int[]> paperByPage = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, String> styleByPage = new java.util.HashMap<>();

    boolean hasLook() {
        if (paperOverride != 0) return true;
        if (pageStyle != null && !STYLE_BLANK.equals(pageStyle)) return true;
        if (!paperByPage.isEmpty()) return true;
        for (String st : styleByPage.values()) {
            if (st != null && !STYLE_BLANK.equals(st)) return true;
        }
        return false;
    }

    int paperForPage(int index) {
        int[] v = paperByPage.get(index);
        return v != null ? v[0] : paperOverride;
    }

    float ruleScaleForPage(int index) {
        int[] v = paperByPage.get(index);
        if (v == null) return clampRuleScale(ruleScale);
        if (v.length < 2 || v[1] <= 0) return 1f;
        return clampRuleScale(v[1] / 10f);
    }

    static float clampRuleScale(float v) {
        if (!(v > 0f)) return 1f;
        return Math.max(RULE_SCALE_MIN, Math.min(RULE_SCALE_MAX, v));
    }

    private static int[] paperEntry(int paper, float scale) {
        return new int[] {paper, Math.round(clampRuleScale(scale) * 10f)};
    }

    String styleForPage(int index) {
        String v = styleByPage.get(index);
        return v != null ? v : (pageStyle == null ? STYLE_BLANK : pageStyle);
    }

    /** Page looks follow their pages to a new order (see CodeCanvasView.applyPageOrder). */
    void remapPageLooks(int[] order) {
        java.util.HashMap<Integer, int[]> papers = new java.util.HashMap<>();
        java.util.HashMap<Integer, String> styles = new java.util.HashMap<>();
        for (int j = 0; j < order.length; j++) {
            int i = order[j];
            if (i < 0) continue;
            int[] paper = paperByPage.get(i);
            if (paper != null) papers.put(j, paper.clone());
            String style = styleByPage.get(i);
            if (style != null) styles.put(j, style);
        }
        paperByPage.clear();
        paperByPage.putAll(papers);
        styleByPage.clear();
        styleByPage.putAll(styles);
    }

    void setPageLook(int index, int paper, String style, float scale) {
        paperByPage.put(index, paperEntry(paper, scale));
        styleByPage.put(index, style == null ? STYLE_BLANK : style);
    }

    void setAllPagesLook(int paper, String style, float scale) {
        paperByPage.clear();
        styleByPage.clear();
        paperOverride = paper;
        pageStyle = style == null ? STYLE_BLANK : style;
        ruleScale = clampRuleScale(scale);
    }

    void setDefaultLook(int paper, String style, float scale) {
        for (int i = 0; i < pageCount; i++) {
            if (!paperByPage.containsKey(i)) {
                paperByPage.put(i, paperEntry(paperOverride, ruleScale));
            }
            if (!styleByPage.containsKey(i)) styleByPage.put(i, pageStyle);
        }
        paperOverride = paper;
        pageStyle = style == null ? STYLE_BLANK : style;
        ruleScale = clampRuleScale(scale);
    }

    org.json.JSONObject looksToJson() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject();
        for (java.util.Map.Entry<Integer, int[]> e : paperByPage.entrySet()) {
            org.json.JSONObject row = new org.json.JSONObject();
            row.put("paper", e.getValue()[0]);
            row.put("size", e.getValue().length > 1 ? e.getValue()[1] : 10);
            String st = styleByPage.get(e.getKey());
            row.put("style", st == null ? STYLE_BLANK : st);
            o.put(String.valueOf(e.getKey()), row);
        }
        return o;
    }

    void looksFromJson(org.json.JSONObject o) {
        paperByPage.clear();
        styleByPage.clear();
        if (o == null) return;
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            org.json.JSONObject row = o.optJSONObject(k);
            if (row == null) continue;
            try {
                int idx = Integer.parseInt(k);
                paperByPage.put(idx, new int[] {
                        row.optInt("paper", 0), row.optInt("size", 10)});
                styleByPage.put(idx, row.optString("style", STYLE_BLANK));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    DocumentPages() {
        pageShadow.setColor(0x40000000);
        pageShadow.setStyle(Paint.Style.FILL);
        pageBorder.setStyle(Paint.Style.STROKE);
        pageBorder.setStrokeWidth(1f);
        pageBorder.setColor(0x33000000);
    }

    void setPageReadyListener(Runnable listener) {
        pageReadyListener = listener;
    }

    /**
     * Kept for call-site compatibility; see {@link #updateDetail} for zoom sharpness.
     */
    void setRenderScale(float viewScale, boolean settled) {
        // no-op
    }

    // ---- Zoom detail: the visible part of each page re-rendered at screen resolution ----

    /**
     * The base bitmaps are about one screen wide, so zooming far into a page upsampled
     * them and text went soft. Once the view settles above that resolution, the part
     * of each visible page that is on screen is rendered again at exactly the view's
     * scale and drawn over the base. Nothing re-renders while a gesture is running.
     */
    private static final class Detail {
        final int fileIdx;
        final RectF world;
        final Bitmap bmp;

        Detail(int fileIdx, RectF world, Bitmap bmp) {
            this.fileIdx = fileIdx;
            this.world = world;
            this.bmp = bmp;
        }
    }

    /** Pixel budget for all detail patches together (~48MB ARGB). */
    private static final long DETAIL_MAX_PIXELS = 12L * 1024 * 1024;
    private static final int DETAIL_MAX_DIM = 8192;
    /** Only bother once the base bitmap would be stretched by more than this. */
    private static final float DETAIL_MIN_GAIN = 1.15f;

    private volatile List<Detail> details = new ArrayList<>();
    private final RectF detailCovered = new RectF();
    private volatile float detailScale;
    private final RectF detailWantRect = new RectF();
    private float detailWantScale;
    private volatile int detailReqId;
    private ExecutorService detailExecutor;

    /**
     * @param visibleWorld the part of the world on screen right now
     * @param viewScale    screen px per world unit
     * @param settled      false while panning / pinching / flinging
     */
    void updateDetail(RectF visibleWorld, float viewScale, boolean settled) {
        if (!isOpen() || !settled || visibleWorld == null || visibleWorld.isEmpty()) return;
        if (viewScale <= displayScale * DETAIL_MIN_GAIN) {
            // Base bitmaps are sharp enough here; drop patches so memory goes back.
            if (!details.isEmpty()) {
                details = new ArrayList<>();
                synchronized (cacheLock) {
                    detailCovered.setEmpty();
                }
            }
            detailWantRect.setEmpty();
            return;
        }
        boolean sameScale = Math.abs(viewScale - detailScale) <= detailScale * 0.02f;
        boolean covered;
        synchronized (cacheLock) {
            covered = detailCovered.contains(visibleWorld);
        }
        if (sameScale && covered) return;
        boolean wantSame = Math.abs(viewScale - detailWantScale) <= detailWantScale * 0.02f;
        if (wantSame && detailWantRect.contains(visibleWorld)) return;  // already queued

        // A little margin so small pans after settling stay sharp.
        RectF want = new RectF(visibleWorld);
        want.inset(-visibleWorld.width() * 0.1f, -visibleWorld.height() * 0.1f);
        detailWantRect.set(want);
        detailWantScale = viewScale;
        final int reqId = ++detailReqId;
        final int gen = docGen;
        final float scale = viewScale;
        final RectF area = new RectF(want);
        if (detailExecutor == null) {
            detailExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cc-pdf-detail");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
        try {
            detailExecutor.execute(() -> renderDetails(reqId, gen, area, scale));
        } catch (RuntimeException ignored) {
        }
    }

    private void renderDetails(int reqId, int gen, RectF area, float scale) {
        if (reqId != detailReqId || gen != docGen) return;  // superseded
        float stride = pageHeight + PAGE_GAP;
        int first = Math.max(0, (int) Math.floor(area.top / stride));
        int last = Math.min(pageCount - 1, (int) Math.floor(area.bottom / stride));
        List<RectF> rects = new ArrayList<>();
        List<Integer> files = new ArrayList<>();
        double pixels = 0;
        for (int i = first; i <= last; i++) {
            int fileIdx = i - blankPrefix;
            if (fileIdx < 0 || fileIdx >= filePageCount) continue;
            RectF page = pageBounds(i);
            RectF r = new RectF();
            if (!r.setIntersect(page, area)) continue;
            rects.add(r);
            files.add(fileIdx);
            pixels += (double) r.width() * r.height() * scale * scale;
        }
        if (rects.isEmpty()) return;
        // Stay inside the memory budget by lowering the patch scale (still far sharper).
        float s = scale;
        if (pixels > DETAIL_MAX_PIXELS) s *= (float) Math.sqrt(DETAIL_MAX_PIXELS / pixels);
        for (RectF r : rects) {
            float maxEdge = Math.max(r.width(), r.height()) * s;
            if (maxEdge > DETAIL_MAX_DIM) s *= DETAIL_MAX_DIM / maxEdge;
        }
        if (s <= displayScale * 1.05f) return;

        List<Detail> out = new ArrayList<>();
        for (int k = 0; k < rects.size(); k++) {
            if (reqId != detailReqId || gen != docGen) return;
            RectF r = rects.get(k);
            int fileIdx = files.get(k);
            int w = Math.max(1, Math.round(r.width() * s));
            int h = Math.max(1, Math.round(r.height() * s));
            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError oom) {
                return;
            }
            bmp.eraseColor(Color.WHITE);
            synchronized (renderLock) {
                if (renderer == null || gen != docGen) return;
                try (PdfRenderer.Page page = renderer.openPage(fileIdx)) {
                    int pw = Math.max(1, page.getWidth());
                    int ph = Math.max(1, page.getHeight());
                    RectF pb = pageBounds(fileIdx + blankPrefix);
                    // Page points → world (pages are stretched to the first page's size)
                    // → patch pixels.
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setScale(s * pageWidth / pw, s * pageHeight / ph);
                    m.postTranslate((pb.left - r.left) * s, (pb.top - r.top) * s);
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (Exception e) {
                    return;
                }
            }
            bmp.setHasAlpha(false);
            out.add(new Detail(fileIdx, new RectF(r), bmp));
        }
        if (reqId != detailReqId || gen != docGen) return;
        details = out;
        synchronized (cacheLock) {
            for (Detail d : out) landedPages.add(d.fileIdx);
            detailCovered.set(area);
        }
        detailScale = scale;
        Runnable cb = pageReadyListener;
        if (cb != null) cb.run();
    }

    private void drawDetails(Canvas canvas, int fileIdx, RectF page) {
        List<Detail> snapshot = details;
        if (snapshot.isEmpty()) return;
        for (int k = 0; k < snapshot.size(); k++) {
            Detail d = snapshot.get(k);
            if (d.fileIdx != fileIdx || d.bmp.isRecycled()) continue;
            if (!RectF.intersects(d.world, page)) continue;
            canvas.drawBitmap(d.bmp, null, d.world, pageBmpPaint);
        }
    }

    // ---- Text selection (PdfRenderer text APIs, Android 15+) ----

    /** A run of selected PDF text and where it sits, in world coordinates. */
    static final class TextSelection {
        final int pageIndex;
        final String text;
        final List<RectF> rects;

        TextSelection(int pageIndex, String text, List<RectF> rects) {
            this.pageIndex = pageIndex;
            this.text = text;
            this.rects = rects;
        }
    }

    static boolean supportsTextSelection() {
        return android.os.Build.VERSION.SDK_INT >= 35;
    }

    /**
     * Select the text between two world points on page {@code pageIndex} — both
     * points the same selects the word there. Null when the page has no text there.
     */
    TextSelection selectText(int pageIndex, float wx0, float wy0, float wx1, float wy1) {
        if (!supportsTextSelection() || !isOpen()) return null;
        int fileIdx = pageIndex - blankPrefix;
        if (fileIdx < 0 || fileIdx >= filePageCount) return null;
        RectF pb = pageBounds(pageIndex);
        synchronized (renderLock) {
            if (renderer == null) return null;
            try (PdfRenderer.Page page = renderer.openPage(fileIdx)) {
                int pw = Math.max(1, page.getWidth());
                int ph = Math.max(1, page.getHeight());
                float sx = pw / pageWidth;
                float sy = ph / pageHeight;
                android.graphics.Point p0 = new android.graphics.Point(
                        clampI(Math.round((wx0 - pb.left) * sx), 0, pw),
                        clampI(Math.round((wy0 - pb.top) * sy), 0, ph));
                android.graphics.Point p1 = new android.graphics.Point(
                        clampI(Math.round((wx1 - pb.left) * sx), 0, pw),
                        clampI(Math.round((wy1 - pb.top) * sy), 0, ph));
                android.graphics.pdf.models.selection.PageSelection sel = page.selectContent(
                        new android.graphics.pdf.models.selection.SelectionBoundary(p0),
                        new android.graphics.pdf.models.selection.SelectionBoundary(p1));
                if (sel == null) return null;
                StringBuilder text = new StringBuilder();
                List<RectF> rects = new ArrayList<>();
                List<android.graphics.pdf.content.PdfPageTextContent> parts =
                        sel.getSelectedTextContents();
                if (parts == null) return null;
                for (android.graphics.pdf.content.PdfPageTextContent c : parts) {
                    if (c == null) continue;
                    if (c.getText() != null) text.append(c.getText());
                    List<RectF> bounds = c.getBounds();
                    if (bounds == null) continue;
                    for (RectF b : bounds) {
                        rects.add(new RectF(
                                pb.left + b.left / sx, pb.top + b.top / sy,
                                pb.left + b.right / sx, pb.top + b.bottom / sy));
                    }
                }
                String t = text.toString().trim();
                if (t.isEmpty() || rects.isEmpty()) return null;
                return new TextSelection(pageIndex, t, rects);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    boolean isOpen() {
        return path != null && !path.isEmpty() && pageCount > 0;
    }

    void close() {
        ExecutorService dying;
        ExecutorService dyingPreview;
        synchronized (cacheLock) {
            docGen++;
            pageCache.clear();
            pending.clear();
            previewCache.clear();
            previewPending.clear();
            previewQueue.clear();
            landedPages.clear();
            dying = pageExecutor;
            pageExecutor = null;
            dyingPreview = previewExecutor;
            previewExecutor = null;
        }
        if (dying != null) dying.shutdownNow();
        if (dyingPreview != null) dyingPreview.shutdownNow();
        details = new ArrayList<>();
        synchronized (cacheLock) {
            detailCovered.setEmpty();
        }
        detailWantRect.setEmpty();
        detailScale = 0f;
        detailWantScale = 0f;
        detailReqId++;
        synchronized (previewRenderLock) {
            if (previewRenderer != null) {
                try { previewRenderer.close(); } catch (Exception ignored) {}
                previewRenderer = null;
            }
            if (previewPfd != null) {
                try { previewPfd.close(); } catch (Exception ignored) {}
                previewPfd = null;
            }
        }
        synchronized (renderLock) {
            if (renderer != null) {
                try { renderer.close(); } catch (Exception ignored) {}
                renderer = null;
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
                pfd = null;
            }
            cacheFile = null;
            path = "";
            pageCount = 0;
            filePageCount = 0;
            blankPrefix = 0;
            blankOwned = false;
        }
    }

    void open(Context ctx, String workspacePath, byte[] pdfBytes, int annotationPageCount,
              boolean blankOwnedFlag) throws Exception {
        open(ctx, workspacePath, pdfBytes, annotationPageCount, blankOwnedFlag, 0);
    }

    void open(Context ctx, String workspacePath, byte[] pdfBytes, int annotationPageCount,
              boolean blankOwnedFlag, int savedBlankPrefix) throws Exception {
        close();
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new Exception("empty PDF");
        }
        path = workspacePath == null ? "" : workspacePath;
        blankOwned = blankOwnedFlag;
        cacheFile = new File(ctx.getCacheDir(), "doc-" + Integer.toHexString(path.hashCode()) + ".pdf");
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(pdfBytes);
        }
        pfd = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(pfd);
        filePageCount = renderer.getPageCount();
        if (filePageCount <= 0) throw new Exception("PDF has no pages");
        try (PdfRenderer.Page page0 = renderer.openPage(0)) {
            pageWidth = page0.getWidth();
            pageHeight = page0.getHeight();
        }
        try {
            ParcelFileDescriptor ppfd =
                    ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY);
            synchronized (previewRenderLock) {
                previewPfd = ppfd;
                previewRenderer = new PdfRenderer(ppfd);
            }
        } catch (Exception e) {
            // Previews fall back to the shared renderer — slower, still correct.
            synchronized (previewRenderLock) {
                if (previewPfd != null) {
                    try { previewPfd.close(); } catch (Exception ignored) {}
                }
                previewPfd = null;
                previewRenderer = null;
            }
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        // Full-screen quality: page width maps to the wider screen edge, with a
        // little oversample. Side-panel resize does not change this.
        float screen = Math.max(dm.widthPixels, dm.heightPixels);
        displayScale = Math.min(
                MAX_PAGE_DIM / Math.max(pageWidth, pageHeight),
                Math.max(2f, screen / Math.max(1f, pageWidth)));

        int ann = Math.max(1, annotationPageCount);
        int virtual = Math.max(0, ann - filePageCount);
        blankPrefix = Math.max(0, Math.min(Math.max(0, savedBlankPrefix), virtual));
        pageCount = Math.max(filePageCount + blankPrefix, ann);

        int gen = docGen;
        Bitmap first = renderPage(0, gen);
        if (first != null) {
            synchronized (cacheLock) {
                if (gen == docGen) pageCache.put(0, new PageBmp(first));
            }
        }
        for (int i = 1; i < Math.min(filePageCount, EAGER_PAGES); i++) {
            requestPage(i);
        }
        // Low-res pass over as much of the document as the preview budget holds, so
        // scrolling finds a page already there. The worker goes nearest-first, so a
        // restored mid-document view fills in around itself before anything else.
        viewFirst = 0;
        viewLast = 0;
        for (int i = 0; i < Math.min(filePageCount, previewBudgetPages()); i++) {
            requestPreview(i);
        }
    }

    /** World rects of pages whose pixels changed since the last call; clears the set. */
    List<RectF> drainLandedPages() {
        List<RectF> out = new ArrayList<>();
        synchronized (cacheLock) {
            for (int f : landedPages) {
                int index = f + blankPrefix;
                if (index >= 0 && index < pageCount) out.add(pageBounds(index));
            }
            landedPages.clear();
        }
        return out;
    }

    boolean hasLandedPages() {
        synchronized (cacheLock) {
            return !landedPages.isEmpty();
        }
    }

    private int previewBudgetPages() {
        float s = PREVIEW_LONG_EDGE / Math.max(1f, Math.max(pageWidth, pageHeight));
        long bytes = Math.max(1L, (long) (pageWidth * s) * (long) (pageHeight * s) * 4L);
        return (int) Math.max(PREVIEW_MIN_PAGES, PREVIEW_BUDGET_BYTES / bytes);
    }

    /** Pages from the view, 0 inside it. */
    private int distanceFromView(int fileIndex) {
        int a = viewFirst, b = viewLast;
        if (fileIndex < a) return a - fileIndex;
        if (fileIndex > b) return fileIndex - b;
        return 0;
    }

    float documentBottom() {
        if (pageCount <= 0) return 0f;
        return pageCount * pageHeight + (pageCount - 1) * PAGE_GAP;
    }

    RectF pageBounds(int index) {
        float top = index * (pageHeight + PAGE_GAP);
        return new RectF(0, top, pageWidth, top + pageHeight);
    }

    int pageIndexAt(float worldY) {
        if (pageCount <= 0) return 0;
        float stride = pageHeight + PAGE_GAP;
        int i = (int) Math.floor(worldY / stride);
        if (i < 0) return 0;
        if (i >= pageCount) return pageCount - 1;
        return i;
    }

    void appendVirtualPage() {
        pageCount++;
    }

    void prependVirtualPage() {
        blankPrefix++;
        pageCount++;
    }

    void setPageCount(int n) {
        pageCount = Math.max(1, n);
        if (blankPrefix > pageCount) blankPrefix = Math.max(0, pageCount - filePageCount);
    }

    boolean removePageAt(int index) {
        if (pageCount <= 1 || index < 0 || index >= pageCount) return false;
        if (index < blankPrefix) {
            blankPrefix--;
            pageCount--;
            return true;
        }
        pageCount--;
        if (blankPrefix > pageCount) blankPrefix = Math.max(0, pageCount - filePageCount);
        return true;
    }

    private PageBmp cachedPage(int fileIndex) {
        synchronized (cacheLock) {
            PageBmp pb = pageCache.get(fileIndex);
            if (pb == null) return null;
            if (pb.bmp.isRecycled()) {
                pageCache.remove(fileIndex);
                return null;
            }
            return pb;
        }
    }

    private Bitmap cachedPreview(int fileIndex) {
        synchronized (cacheLock) {
            Bitmap bmp = previewCache.get(fileIndex);
            if (bmp == null) return null;
            if (bmp.isRecycled()) {
                previewCache.remove(fileIndex);
                return null;
            }
            return bmp;
        }
    }

    private void requestPreview(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= filePageCount) return;
        final int gen;
        ExecutorService exec;
        synchronized (cacheLock) {
            if (previewPending.contains(fileIndex)) return;
            if (previewCache.containsKey(fileIndex)) return;
            if (previewExecutor == null) {
                previewExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "cc-pdf-preview");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
            }
            exec = previewExecutor;
            previewPending.add(fileIndex);
            previewQueue.add(fileIndex);
            gen = docGen;
        }
        try {
            // One task per request, but each task renders whichever queued page is
            // nearest the view right now — not the one that asked first.
            exec.execute(() -> renderNextPreview(gen));
        } catch (RuntimeException rejected) {
            synchronized (cacheLock) {
                previewPending.remove(fileIndex);
                previewQueue.remove(fileIndex);
            }
        }
    }

    private void renderNextPreview(int gen) {
        int fileIndex = -1;
        synchronized (cacheLock) {
            if (gen != docGen) return;
            int best = Integer.MAX_VALUE;
            for (int i : previewQueue) {
                int d = distanceFromView(i);
                if (d < best) {
                    best = d;
                    fileIndex = i;
                }
            }
            if (fileIndex < 0) return;
            previewQueue.remove(fileIndex);
        }
        float scale = PREVIEW_LONG_EDGE / Math.max(1f, Math.max(pageWidth, pageHeight));
        Bitmap bmp = renderPreviewAt(fileIndex, gen, scale);
        boolean landed;
        synchronized (cacheLock) {
            previewPending.remove(fileIndex);
            landed = bmp != null && gen == docGen;
            if (landed) {
                previewCache.put(fileIndex, bmp);
                landedPages.add(fileIndex);
            }
        }
        if (landed) trimPreviewCache();
        Runnable cb = pageReadyListener;
        if (landed && cb != null) cb.run();
    }

    private void requestPage(int fileIndex) {
        if (fileIndex < 0 || fileIndex >= filePageCount) return;
        final int gen;
        ExecutorService exec;
        synchronized (cacheLock) {
            if (pending.contains(fileIndex)) return;
            if (pageCache.containsKey(fileIndex)) return;
            if (pageExecutor == null) {
                pageExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "cc-pdf-render");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
            }
            exec = pageExecutor;
            pending.add(fileIndex);
            gen = docGen;
        }
        try {
            exec.execute(() -> {
                // A fast scroll queues every page it passes. Skip the ones already left
                // behind so the page the view stopped on is not stuck behind them; if it
                // comes back into view, draw() asks for it again.
                if (distanceFromView(fileIndex) > PREFETCH_PAGES + 1) {
                    synchronized (cacheLock) {
                        pending.remove(fileIndex);
                    }
                    return;
                }
                Bitmap bmp = renderPage(fileIndex, gen);
                boolean landed;
                synchronized (cacheLock) {
                    pending.remove(fileIndex);
                    landed = bmp != null && gen == docGen;
                    if (landed) {
                        pageCache.put(fileIndex, new PageBmp(bmp));
                        landedPages.add(fileIndex);
                    }
                }
                if (landed) trimCache();
                Runnable cb = pageReadyListener;
                if (landed && cb != null) cb.run();
            });
        } catch (RuntimeException rejected) {
            synchronized (cacheLock) {
                pending.remove(fileIndex);
            }
        }
    }

    private Bitmap renderPage(int fileIndex, int gen) {
        return renderPageAt(fileIndex, gen, displayScale);
    }

    private Bitmap renderPageAt(int fileIndex, int gen, float scale) {
        synchronized (renderLock) {
            return rasterize(renderer, fileIndex, gen, scale);
        }
    }

    private Bitmap renderPreviewAt(int fileIndex, int gen, float scale) {
        synchronized (previewRenderLock) {
            if (previewRenderer != null) {
                return rasterize(previewRenderer, fileIndex, gen, scale);
            }
        }
        return renderPageAt(fileIndex, gen, scale);
    }

    /** Caller holds the lock that owns {@code r}. */
    private Bitmap rasterize(PdfRenderer r, int fileIndex, int gen, float scale) {
        if (r == null || gen != docGen) return null;
        if (fileIndex < 0 || fileIndex >= filePageCount) return null;
        try (PdfRenderer.Page page = r.openPage(fileIndex)) {
            int pw = Math.max(1, page.getWidth());
            int ph = Math.max(1, page.getHeight());
            float capped = Math.min(scale,
                    MAX_PAGE_DIM / (float) Math.max(pw, ph));
            int w = Math.max(1, Math.round(pw * capped));
            int h = Math.max(1, Math.round(ph * capped));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            bmp.setHasAlpha(false);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void trimCache() {
        List<Bitmap> dropped = null;
        synchronized (cacheLock) {
            long bytes = 0;
            for (PageBmp pb : pageCache.values()) bytes += pb.bmp.getByteCount();
            if (bytes <= CACHE_BUDGET_BYTES) return;
            Iterator<Map.Entry<Integer, PageBmp>> it = pageCache.entrySet().iterator();
            while (it.hasNext() && bytes > CACHE_BUDGET_BYTES
                    && pageCache.size() > CACHE_MIN_PAGES) {
                Map.Entry<Integer, PageBmp> eldest = it.next();
                bytes -= eldest.getValue().bmp.getByteCount();
                if (dropped == null) dropped = new ArrayList<>();
                dropped.add(eldest.getValue().bmp);
                it.remove();
            }
        }
        if (dropped != null) dropped.clear();
    }

    private void trimPreviewCache() {
        synchronized (cacheLock) {
            long bytes = 0;
            for (Bitmap b : previewCache.values()) bytes += b.getByteCount();
            // Evict the page farthest from the view. LRU dropped the first pages the
            // upfront pass rendered — exactly the ones at the top where reading starts.
            while (bytes > PREVIEW_BUDGET_BYTES && previewCache.size() > PREVIEW_MIN_PAGES) {
                int far = -1;
                int farD = -1;
                for (int i : previewCache.keySet()) {
                    int d = distanceFromView(i);
                    if (d > farD) {
                        farD = d;
                        far = i;
                    }
                }
                if (far < 0) break;
                bytes -= previewCache.remove(far).getByteCount();
            }
        }
    }

    /**
     * True for a PDF imported as it is: its pages show untinted, with no app paper or
     * ruling, until a look is chosen. False for documents that already carry ink drawn
     * on the theme's paper (saved before this default existed) — they keep that look.
     */
    boolean plainImport = true;

    /** Back to no look at all: the next document does not inherit this one's paper or ruling. */
    void resetLooks() {
        paperOverride = 0;
        pageStyle = STYLE_BLANK;
        ruleScale = 1f;
        paperByPage.clear();
        styleByPage.clear();
        plainImport = true;
    }

    /**
     * A page's paper: the user's choice if there is one. Otherwise a document the app
     * made follows the theme, while an imported PDF stays as it was uploaded — white,
     * untinted — until the user picks a look for it.
     */
    private int paperColorFor(int override, int themePaper) {
        if (override != 0) return override;
        return blankOwned || !plainImport ? themePaper : 0xFFFFFFFF;
    }

    /** The paper a page shows when no look is chosen (what "Default" means in the menu). */
    int defaultPaper(int themePaper) {
        return paperColorFor(0, themePaper);
    }

    private int ruleColorFor(int paper) {
        return ruleColor(paper);
    }

    /** Ruling colour on {@code paper}: dark rules on light paper, light on dark. */
    static int ruleColor(int paper) {
        int r = (paper >> 16) & 0xFF, g = (paper >> 8) & 0xFF, b = paper & 0xFF;
        boolean lightPaper = (r * 299 + g * 587 + b * 114) / 1000 >= 140;
        return lightPaper ? 0x2A000000 : 0x30FFFFFF;
    }

    /** PDF page behind document page {@code index}, or −1 for a page only the app has. */
    int filePageFor(int index) {
        int f = index - blankPrefix;
        return f >= 0 && f < filePageCount ? f : -1;
    }

    /**
     * What an exported page carries under the annotations, drawn at the origin in
     * page units: paper for a page only the app has, and the page's ruling. A PDF
     * page keeps its own paper — the display tint is a view setting, not content.
     */
    void drawForExport(Canvas canvas, int index, int themePaper) {
        RectF page = new RectF(0, 0, pageWidth, pageHeight);
        int paper = paperColorFor(paperForPage(index), themePaper);
        if (filePageFor(index) < 0) {
            Paint fill = new Paint();
            fill.setColor(paper);
            canvas.drawRect(page, fill);
        } else {
            paper = 0xFFFFFFFF;
        }
        drawPageStyle(canvas, page, paper, styleForPage(index), ruleScaleForPage(index));
    }

    private void drawPageStyle(Canvas canvas, RectF page, int paper, String style, float scale) {
        if (style == null) style = STYLE_BLANK;
        if (STYLE_BLANK.equals(style)) return;
        Paint pageRule = pageRuleTl.get();
        pageRule.setColor(ruleColorFor(paper));
        float step = 24f * scale;
        canvas.save();
        canvas.clipRect(page);
        if (STYLE_DOTS.equals(style)) {
            pageRule.setStyle(Paint.Style.FILL);
            for (float y = page.top + step; y < page.bottom; y += step) {
                for (float x = page.left + step; x < page.right; x += step) {
                    canvas.drawCircle(x, y, 1.1f, pageRule);
                }
            }
        } else {
            pageRule.setStyle(Paint.Style.STROKE);
            pageRule.setStrokeWidth(0.8f);
            float lineStep = STYLE_LINES.equals(style) ? 28f * scale : step;
            for (float y = page.top + lineStep; y < page.bottom; y += lineStep) {
                canvas.drawLine(page.left, y, page.right, y, pageRule);
            }
            if (STYLE_GRID.equals(style)) {
                for (float x = page.left + step; x < page.right; x += step) {
                    canvas.drawLine(x, page.top, x, page.bottom, pageRule);
                }
            }
        }
        canvas.restore();
    }

    /**
     * Draw page papers (+ PDF bitmaps) intersecting visibleWorld. Safe from UI and
     * background rebuild threads — scratch rects are locals.
     */
    void draw(Canvas canvas, RectF visibleWorld, Paint paperPaint, int paperColor) {
        draw(canvas, visibleWorld, paperPaint, paperColor, false);
    }

    /**
     * @param preferPreview draw the low-res page when there is one. For a frame's
     *                      stop-gap strip on the GPU: a full-res page is a ~47MB
     *                      texture upload the first time it is drawn there.
     */
    void draw(Canvas canvas, RectF visibleWorld, Paint paperPaint, int paperColor,
              boolean preferPreview) {
        if (!isOpen()) return;
        float stride = pageHeight + PAGE_GAP;
        int first = (int) Math.floor(visibleWorld.top / stride);
        int last = (int) Math.floor(visibleWorld.bottom / stride);
        if (first < 0) first = 0;
        if (last > pageCount - 1) last = pageCount - 1;
        if (last < first) return;

        viewFirst = first - blankPrefix;
        viewLast = last - blankPrefix;

        RectF page = new RectF();
        RectF shadow = new RectF();
        Paint pageTint = pageTintTl.get();
        paperPaint.setStyle(Paint.Style.FILL);
        for (int i = first; i <= last; i++) {
            float top = i * stride;
            page.set(0, top, pageWidth, top + pageHeight);
            if (!RectF.intersects(visibleWorld, page)) continue;

            int fileIdx = i - blankPrefix;
            PageBmp pb = (fileIdx >= 0 && fileIdx < filePageCount)
                    ? cachedPage(fileIdx) : null;

            shadow.set(page.right, page.top + SHADOW_DY,
                    page.right + SHADOW_DX, page.bottom + SHADOW_DY);
            canvas.drawRect(shadow, pageShadow);
            shadow.set(page.left + SHADOW_DX, page.bottom,
                    page.right, page.bottom + SHADOW_DY);
            canvas.drawRect(shadow, pageShadow);

            final int override = paperForPage(i);
            final int paper = paperColorFor(override, paperColor);
            paperPaint.setColor(paper);
            canvas.drawRect(page, paperPaint);

            if (fileIdx >= 0 && fileIdx < filePageCount) {
                Bitmap low = preferPreview ? cachedPreview(fileIdx) : null;
                if (low != null) {
                    canvas.drawBitmap(low, null, page, pageBmpPaint);
                    if (pb == null) requestPage(fileIdx);
                } else if (pb != null) {
                    canvas.drawBitmap(pb.bmp, null, page, pageBmpPaint);
                } else {
                    Bitmap preview = cachedPreview(fileIdx);
                    if (preview != null) {
                        canvas.drawBitmap(preview, null, page, pageBmpPaint);
                    } else {
                        requestPreview(fileIdx);
                    }
                    requestPage(fileIdx);
                }
                if (low == null) drawDetails(canvas, fileIdx, page);
                if (isDarkPaper(paper)) {
                    pageTint.setColor(paper);
                    pageTint.setXfermode(DARKEN);
                    canvas.drawRect(page, pageTint);
                    pageTint.setXfermode(null);
                } else if (override != 0 || paper != 0xFFFFFFFF) {
                    pageTint.setColor(paper);
                    pageTint.setXfermode(MULTIPLY);
                    canvas.drawRect(page, pageTint);
                    pageTint.setXfermode(null);
                }
            }
            drawPageStyle(canvas, page, paper, styleForPage(i), ruleScaleForPage(i));
            canvas.drawRect(page, pageBorder);
        }
        // Rasterise just off-screen too, so the next page is ready before it is asked for.
        for (int i = first - PREFETCH_PAGES; i <= last + PREFETCH_PAGES; i++) {
            if (i < 0 || i > pageCount - 1) continue;
            int fileIdx = i - blankPrefix;
            if (fileIdx < 0 || fileIdx >= filePageCount) continue;
            if (cachedPage(fileIdx) == null) requestPage(fileIdx);
        }
        // Low res reaches further, so a fling lands on pages that are at least soft.
        for (int i = first - PREVIEW_PREFETCH_PAGES; i <= last + PREVIEW_PREFETCH_PAGES; i++) {
            int fileIdx = i - blankPrefix;
            if (fileIdx < 0 || fileIdx >= filePageCount) continue;
            if (cachedPage(fileIdx) == null && cachedPreview(fileIdx) == null) {
                requestPreview(fileIdx);
            }
        }
    }

    void applyTheme(boolean light) {
        pageBorder.setColor(light ? 0x33000000 : 0x66FFFFFF);
        pageShadow.setColor(light ? 0x28000000 : 0x66000000);
    }
}
