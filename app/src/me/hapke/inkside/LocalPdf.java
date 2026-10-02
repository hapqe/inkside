package me.hapke.inkside;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.multipdf.LayerUtility;
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import com.tom_roush.pdfbox.util.Matrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF work the host does with pdf-lib and pdf.js (host/src/pdfTools.mjs), done on the
 * tablet with PdfBox so a document on the tablet can be exported, rearranged and
 * searched with no computer connected.
 */
final class LocalPdf {
    private static final int INDEX_CACHE = 24;

    private final Map<String, Indexed> index = new LinkedHashMap<String, Indexed>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Indexed> e) {
            return size() > INDEX_CACHE;
        }
    };

    LocalPdf(Context ctx) {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());
    }

    /**
     * Copy of {@code src} for {@code into}. Inherited attributes (resources, boxes,
     * rotation live on the page tree in many files) are pinned on the page first, so
     * the copy looks the same outside its old tree. Copies, not imports: a page used
     * twice must be two objects.
     */
    private static PDPage copyPage(PDDocument into, PDFCloneUtility cloner, PDPage src) throws IOException {
        src.setResources(src.getResources());
        src.setMediaBox(src.getMediaBox());
        src.setCropBox(src.getCropBox());
        src.setRotation(src.getRotation());
        COSDictionary dict = (COSDictionary) cloner.cloneForNewDocument(src.getCOSObject());
        dict.removeItem(com.tom_roush.pdfbox.cos.COSName.PARENT);
        PDPage page = new PDPage(dict);
        into.addPage(page);
        return page;
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    /**
     * Stamps the tablet's annotation layer onto the original. {@code pages[i]} is the
     * original page under overlay page {@code i}, or −1 for a page only the app has
     * (taken from the overlay as it is). Original pages keep their own content, so
     * their text stays selectable.
     */
    byte[] flatten(byte[] originalBytes, byte[] overlayBytes, int[] pages) throws IOException {
        try (PDDocument original = PDDocument.load(originalBytes);
             PDDocument overlay = PDDocument.load(overlayBytes);
             PDDocument out = new PDDocument()) {
            if (pages.length != overlay.getNumberOfPages()) {
                throw new IOException("page map has " + pages.length + " entries for "
                        + overlay.getNumberOfPages() + " pages");
            }
            PDFCloneUtility fromOriginal = new PDFCloneUtility(out);
            PDFCloneUtility fromOverlay = new PDFCloneUtility(out);
            LayerUtility layers = new LayerUtility(out);
            int count = original.getNumberOfPages();
            for (int i = 0; i < pages.length; i++) {
                int idx = pages[i];
                if (idx < 0 || idx >= count) {
                    copyPage(out, fromOverlay, overlay.getPage(i));
                    continue;
                }
                PDPage page = copyPage(out, fromOriginal, original.getPage(idx));
                PDFormXObject layer = layers.importPageAsForm(overlay, i);
                placeLayer(out, page, layer);
            }
            return save(out);
        }
    }

    /** Draws the layer over the page as the tablet shows it: filling the crop box, turned with /Rotate. */
    private static void placeLayer(PDDocument doc, PDPage page, PDFormXObject layer) throws IOException {
        PDRectangle box = page.getCropBox();
        int rot = ((page.getRotation() % 360) + 360) % 360;
        boolean quarter = rot == 90 || rot == 270;
        float width = quarter ? box.getHeight() : box.getWidth();
        float height = quarter ? box.getWidth() : box.getHeight();
        float x = box.getLowerLeftX();
        float y = box.getLowerLeftY();
        if (rot == 90) x = box.getLowerLeftX() + box.getWidth();
        else if (rot == 180) {
            x = box.getLowerLeftX() + box.getWidth();
            y = box.getLowerLeftY() + box.getHeight();
        } else if (rot == 270) y = box.getLowerLeftY() + box.getHeight();
        PDRectangle bbox = layer.getBBox();
        Matrix m = Matrix.getTranslateInstance(x, y);
        m.rotate(Math.toRadians(rot));
        m.scale(width / bbox.getWidth(), height / bbox.getHeight());
        m.translate(-bbox.getLowerLeftX(), -bbox.getLowerLeftY());
        try (PDPageContentStream cs = new PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.saveGraphicsState();
            cs.transform(m);
            cs.drawForm(layer);
            cs.restoreGraphicsState();
        }
    }

    /**
     * A new page order: {@code order[j]} is the page that becomes page j (twice:
     * duplicated; left out: dropped), or −1 for a blank page of width × height points.
     */
    byte[] reorder(byte[] originalBytes, int[] order, float width, float height) throws IOException {
        try (PDDocument original = PDDocument.load(originalBytes);
             PDDocument out = new PDDocument()) {
            PDFCloneUtility cloner = new PDFCloneUtility(out);
            int count = original.getNumberOfPages();
            for (int idx : order) {
                if (idx >= 0 && idx < count) copyPage(out, cloner, original.getPage(idx));
                else out.addPage(new PDPage(new PDRectangle(width, height)));
            }
            if (out.getNumberOfPages() == 0) throw new IOException("a document needs at least one page");
            return save(out);
        }
    }

    // ---- Search ----------------------------------------------------------------------

    /** One page's text, one entry per character, with each character's box as fractions of the page. */
    static final class PageText {
        final StringBuilder text = new StringBuilder();
        /** Per character of {@link #text}: {x, y, w, h}, or null for inserted spaces. */
        final List<float[]> boxes = new ArrayList<>();
    }

    static final class Indexed {
        final String stamp;
        final List<PageText> pages;

        Indexed(String stamp, List<PageText> pages) {
            this.stamp = stamp;
            this.pages = pages;
        }
    }

    /** The file's text, extracted once per version (size + modification time). */
    Indexed index(File f) throws IOException {
        String key = f.getAbsolutePath();
        String stamp = f.length() + "-" + f.lastModified();
        synchronized (index) {
            Indexed hit = index.get(key);
            if (hit != null && hit.stamp.equals(stamp)) return hit;
        }
        Indexed fresh = new Indexed(stamp, extract(LocalWorkspace.readAll(f)));
        synchronized (index) {
            index.put(key, fresh);
        }
        return fresh;
    }

    private static List<PageText> extract(byte[] bytes) throws IOException {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(bytes)) {
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                PDPage page = doc.getPage(p);
                PDRectangle box = page.getCropBox();
                int rot = ((page.getRotation() % 360) + 360) % 360;
                boolean quarter = rot == 90 || rot == 270;
                final float shownW = quarter ? box.getHeight() : box.getWidth();
                final float shownH = quarter ? box.getWidth() : box.getHeight();
                final PageText pt = new PageText();
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String s, List<TextPosition> positions) {
                        for (TextPosition tp : positions) {
                            String u = tp.getUnicode();
                            if (u == null) continue;
                            float x = tp.getXDirAdj() / shownW;
                            float h = Math.max(tp.getHeightDir(), 1f);
                            float y = (tp.getYDirAdj() - h) / shownH;
                            float w = tp.getWidthDirAdj() / shownW;
                            for (int k = 0; k < u.length(); k++) {
                                pt.text.append(u.charAt(k));
                                pt.boxes.add(new float[]{x + w * k / u.length(), y, w / u.length(), h / shownH});
                            }
                        }
                    }

                    @Override
                    protected void writeWordSeparator() {
                        pt.text.append(' ');
                        pt.boxes.add(null);
                    }

                    @Override
                    protected void writeLineSeparator() {
                        pt.text.append(' ');
                        pt.boxes.add(null);
                    }
                };
                stripper.setSortByPosition(true);
                stripper.setStartPage(p + 1);
                stripper.setEndPage(p + 1);
                stripper.getText(doc);
                pages.add(pt);
            }
        }
        return pages;
    }

    /** Lower case without accents, one character in → one character out. */
    private static char fold(char c) {
        String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        char base = d.isEmpty() ? c : d.charAt(0);
        return Character.toLowerCase(base);
    }

    /** Matches as the host reports them: {@code [{page, snippet, start, length, rects}]}. */
    static JSONArray search(Indexed doc, String query, int limit) throws Exception {
        JSONArray out = new JSONArray();
        String q0 = query.trim().replaceAll("\\s+", " ");
        StringBuilder qf = new StringBuilder();
        for (int i = 0; i < q0.length(); i++) qf.append(fold(q0.charAt(i)));
        String q = qf.toString();
        if (q.isEmpty()) return out;
        for (int p = 0; p < doc.pages.size() && out.length() < limit; p++) {
            PageText pt = doc.pages.get(p);
            // Collapse runs of whitespace, remembering which character each one was.
            StringBuilder text = new StringBuilder();
            List<float[]> at = new ArrayList<>();
            boolean lastSpace = true;
            for (int i = 0; i < pt.text.length(); i++) {
                char c = pt.text.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (!lastSpace) {
                        text.append(' ');
                        at.add(null);
                    }
                    lastSpace = true;
                } else {
                    text.append(c);
                    at.add(pt.boxes.get(i));
                    lastSpace = false;
                }
            }
            StringBuilder hay = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) hay.append(fold(text.charAt(i)));
            int from = 0;
            while (out.length() < limit) {
                int i = hay.indexOf(q, from);
                if (i < 0) break;
                int end = i + q.length();
                JSONArray rects = new JSONArray();
                float[] cur = null;
                for (int k = i; k < end; k++) {
                    float[] b = at.get(k);
                    if (b == null) continue;
                    // Characters on the same line merge into one highlight.
                    if (cur != null && Math.abs(b[1] - cur[1]) < cur[3] * 0.5f) {
                        float right = Math.max(cur[0] + cur[2], b[0] + b[2]);
                        cur[0] = Math.min(cur[0], b[0]);
                        cur[2] = right - cur[0];
                    } else {
                        if (cur != null) rects.put(rect(cur));
                        cur = b.clone();
                    }
                }
                if (cur != null) rects.put(rect(cur));
                int s0 = Math.max(0, i - 50);
                int s1 = Math.min(text.length(), end + 70);
                String body = text.substring(s0, s1);
                String trimmed = body.trim();
                int lead = body.length() - body.replaceAll("^\\s+", "").length();
                String prefix = s0 > 0 ? "…" : "";
                JSONObject m = new JSONObject();
                m.put("page", p);
                m.put("snippet", prefix + trimmed + (s1 < text.length() ? "…" : ""));
                m.put("start", prefix.length() + (i - s0 - lead));
                m.put("length", q.length());
                m.put("rects", rects);
                out.put(m);
                from = end;
            }
        }
        return out;
    }

    private static JSONArray rect(float[] b) throws Exception {
        JSONArray r = new JSONArray();
        for (float v : b) r.put((double) v);
        return r;
    }
}
