package me.hapke.inkside;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONObject;

import java.util.UUID;
import java.util.regex.Pattern;

/** Free-floating text label on the canvas. */
final class CanvasTextField {
    private static final Pattern LATEX_PATTERN = Pattern.compile(
            "\\$\\$[\\s\\S]+?\\$\\$|"
                    + "\\\\\\[[\\s\\S]+?\\\\\\]|"
                    + "\\\\\\([\\s\\S]+?\\\\\\)|"
                    + "(?:^|[^\\\\$])\\$(?:[^$\\\\\\n]|\\\\.)+?\\$(?!\\$)",
            Pattern.MULTILINE);

    private static int chromeFill = 0xFF26262F;
    private static int chromeStroke = 0xFF3F3F46;
    private static int chromeStrokeSelected = 0xFFC4B5FD;
    private static int defaultTextColor = 0xFFF4F4F5;

    /** Theme text colour new fields start with. */
    static int defaultTextColor() {
        return defaultTextColor;
    }

    /** Drive content-block card chrome from the active app theme. */
    static void applyChromeTheme(ThemeConfig.AppTheme theme) {
        if (theme == null) return;
        chromeFill = theme.surfaceContainerHighest;
        chromeStroke = theme.outlineVariant;
        chromeStrokeSelected = theme.primary;
        defaultTextColor = theme.onSurface;
    }

    String id;
    String text = "";
    float cx, cy;
    float width = 280f;
    float height = 80f;
    // Defaults for NEW text boxes, set from the text tool's options in the tool bar.
    // Saved boxes keep their own values (fromJson overwrites all of these).
    static float newSize = 15f;
    static String newFamily = "sans";
    static int newStyle = Typeface.NORMAL;
    /** 0 = follow the theme's text colour. */
    static int newColor = 0;
    /** Smallest text size allowed anywhere. */
    static final float MIN_TEXT_SIZE = 4f;

    /** Default for new boxes; saved boxes keep their own size. */
    float textSize = newSize;
    int color = newColor != 0 ? newColor : defaultTextColor;
    /** When true, height is controlled by the user (resize/draw rect), not auto-reflow. */
    boolean userSized = false;
    /** When true: chat-like rendered block (markdown/LaTeX bitmap); not an inline EditText. */
    boolean contentBlock = false;
    /** Left out of the presentation slide; still shown on the tablet. */
    boolean presentHidden;
    /** Plain text only: never rendered as LaTeX/markdown (text dropped in from the chat). */
    boolean plainOnly;

    /** True when this field should go through the LaTeX/markdown bitmap renderer. */
    boolean wantsRender() {
        return !plainOnly && (contentBlock || containsLatex(text));
    }
    /** sans | serif | mono */
    String fontFamily = newFamily;
    /** Typeface.NORMAL / BOLD / ITALIC / BOLD_ITALIC */
    int typefaceStyle = newStyle;

    private Bitmap latexBitmap;
    /** World units one latex bitmap pixel covers (plain text renders supersampled). */
    private float latexWorldPerPx = 1f;
    private final Paint latexPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF latexDst = new RectF();
    private final RectF bounds = new RectF();
    // Unhinted, fractional advances: the layout is made at world size and then scaled by the
    // zoom, so hinted widths would space the letters unevenly and wrap differently from the
    // editor, which lays out at the on-screen size.
    private final TextPaint textPaint = new TextPaint(
            Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout layout;
    private String layoutKey = "";

    CanvasTextField(float cx, float cy) {
        this.id = UUID.randomUUID().toString();
        this.cx = cx;
        this.cy = cy;
        this.text = "Text";
    }

    static boolean containsLatex(String t) {
        return t != null && !t.isEmpty() && LATEX_PATTERN.matcher(t).find();
    }

    /**
     * Space between the box edge and its text, in world px. Plain text boxes have
     * none — the box is the text. Content blocks draw a card, so keep clear of it.
     */
    float insetX() {
        return contentBlock ? 12f : 0f;
    }

    float insetY() {
        return contentBlock ? 14f : 0f;
    }

    boolean hasLatexBitmap() {
        return latexBitmap != null && !latexBitmap.isRecycled();
    }

    synchronized RectF bounds() {
        bounds.set(cx - width * 0.5f, cy - height * 0.5f, cx + width * 0.5f, cy + height * 0.5f);
        return bounds;
    }

    boolean contains(float x, float y) {
        return bounds().contains(x, y);
    }

    synchronized void setText(String t) {
        text = t == null ? "" : t;
        clearLatexBitmap();
        layout = null;
        layoutKey = "";
        reflowHeight();
    }

    synchronized void setStyle(String family, int style, float size, int argb) {
        if (family != null && !family.isEmpty()) fontFamily = family;
        typefaceStyle = style;
        if (size >= MIN_TEXT_SIZE) textSize = size;
        color = argb;
        clearLatexBitmap();
        layout = null;
        layoutKey = "";
        reflowHeight();
    }

    synchronized void setLatexBitmap(Bitmap bmp) {
        setLatexBitmap(bmp, 1f);
    }

    synchronized void setLatexBitmap(Bitmap bmp, float worldPerPx) {
        if (latexBitmap != null && latexBitmap != bmp && !latexBitmap.isRecycled()) {
            latexBitmap.recycle();
        }
        latexBitmap = bmp;
        latexWorldPerPx = worldPerPx > 0f ? worldPerPx : 1f;
        layout = null;
        layoutKey = "";
        if (bmp != null && !bmp.isRecycled()) {
            // Size the box to the rendered content (no extra empty height that
            // looks like a clipped text field).
            float pad = contentBlock ? 8f : 0f;
            float left = cx - width * 0.5f, top = cy - height * 0.5f;
            width = bmp.getWidth() * latexWorldPerPx + pad;
            height = bmp.getHeight() * latexWorldPerPx + pad;
            if (!contentBlock) {
                // Plain text grows from its top-left, like typed text, not its centre.
                cx = left + width * 0.5f;
                cy = top + height * 0.5f;
            }
            userSized = false;
        } else {
            reflowHeight();
        }
    }

    synchronized void clearLatexBitmap() {
        if (latexBitmap != null && !latexBitmap.isRecycled()) {
            latexBitmap.recycle();
        }
        latexBitmap = null;
    }

    private Typeface resolveTypeface() {
        Typeface base;
        if ("serif".equals(fontFamily)) base = Typeface.SERIF;
        else if ("mono".equals(fontFamily)) base = Typeface.MONOSPACE;
        else base = Typeface.SANS_SERIF;
        return Typeface.create(base, typefaceStyle);
    }

    private synchronized void ensureLayout() {
        if (hasLatexBitmap()) return;
        String key = text + "|" + fontFamily + "|" + typefaceStyle + "|" + textSize + "|" + width + "|" + color;
        if (layout != null && key.equals(layoutKey)) return;
        textPaint.setTextSize(textSize);
        textPaint.setColor(color);
        textPaint.setTypeface(resolveTypeface());
        int w = Math.max(8, Math.round(width - 2f * insetX()));
        CharSequence src = text.isEmpty() ? " " : text;
        layout = StaticLayout.Builder.obtain(src, 0, src.length(), textPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
        layoutKey = key;
    }

    synchronized void reflowHeight() {
        if (hasLatexBitmap()) return;
        ensureLayout();
        if (layout != null && !userSized) {
            height = layout.getHeight() + 2f * insetY();
        }
    }

    synchronized void invalidateLayout() {
        layout = null;
        layoutKey = "";
        if (userSized) ensureLayout();
        else reflowHeight();
    }

    synchronized void setBounds(float left, float top, float right, float bottom) {
        width = Math.max(12f, right - left);
        height = Math.max(12f, bottom - top);
        cx = (left + right) * 0.5f;
        cy = (top + bottom) * 0.5f;
        userSized = true;
        layout = null;
        layoutKey = "";
        ensureLayout();
    }

    /** Update box during live resize; the layout rebuilds so wrapping tracks the new width. */
    synchronized void setBoundsPreview(float left, float top, float right, float bottom) {
        width = Math.max(12f, right - left);
        height = Math.max(12f, bottom - top);
        cx = (left + right) * 0.5f;
        cy = (top + bottom) * 0.5f;
        userSized = true;
        ensureLayout();
    }

    /**
     * Locked against the mutators above: the UI thread and the background
     * backdrop rebuild can be inside this method for the same field at once,
     * and an edit landing mid-draw used to null the layout under it.
     */
    synchronized void draw(Canvas canvas, boolean selected) {
        RectF b = bounds();
        if (contentBlock) {
            boxPaint.setStyle(Paint.Style.FILL);
            boxPaint.setColor(chromeFill);
            canvas.drawRoundRect(b, 18f, 18f, boxPaint);
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(2f);
            boxPaint.setColor(selected ? chromeStrokeSelected : chromeStroke);
            canvas.drawRoundRect(b, 18f, 18f, boxPaint);
            boxPaint.setStyle(Paint.Style.FILL);
        }

        if (hasLatexBitmap()) {
            float bw = latexBitmap.getWidth() * latexWorldPerPx;
            float bh = latexBitmap.getHeight() * latexWorldPerPx;
            if (!contentBlock && bw > 0f && bh > 0f) {
                // A resized plain box scales its text with it, anchored top-left.
                float fit = Math.min(b.width() / bw, b.height() / bh);
                latexDst.set(b.left, b.top, b.left + bw * fit, b.top + bh * fit);
            } else {
                float left = b.left + (b.width() - bw) * 0.5f;
                float top = b.top + (b.height() - bh) * 0.5f;
                latexDst.set(left, top, left + bw, top + bh);
            }
            canvas.drawBitmap(latexBitmap, null, latexDst, latexPaint);
            return;
        }

        ensureLayout();
        if (layout == null) return;
        canvas.save();
        canvas.clipRect(b);
        canvas.translate(b.left + insetX(), b.top + insetY());
        if (PenOutline.needed(color)) drawOutlinePass(canvas);
        layout.draw(canvas);
        canvas.restore();
    }

    /** Traces the glyphs in the outline colour (the layout's paint, set back to fill afterwards). */
    private void drawOutlinePass(Canvas canvas) {
        textPaint.setStyle(Paint.Style.STROKE);
        textPaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setStrokeWidth(PenOutline.textLine(textSize));
        textPaint.setColor(PenOutline.color());
        layout.draw(canvas);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(color);
    }

    /** Only the outline (what sits under the overlay editor while a box is being edited). */
    synchronized void drawOutlineOnly(Canvas canvas) {
        if (hasLatexBitmap() || !PenOutline.needed(color)) return;
        ensureLayout();
        if (layout == null) return;
        RectF b = bounds();
        canvas.save();
        canvas.clipRect(b);
        canvas.translate(b.left + insetX(), b.top + insetY());
        drawOutlinePass(canvas);
        canvas.restore();
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("text", text);
        o.put("cx", cx);
        o.put("cy", cy);
        o.put("width", width);
        o.put("height", height);
        o.put("textSize", textSize);
        o.put("color", color);
        o.put("fontFamily", fontFamily);
        o.put("typefaceStyle", typefaceStyle);
        o.put("userSized", userSized);
        o.put("contentBlock", contentBlock);
        if (presentHidden) o.put("presentHidden", true);
        if (plainOnly) o.put("plainOnly", true);
        o.put("hasLatex", containsLatex(text));
        return o;
    }

    static CanvasTextField fromJson(JSONObject o) throws Exception {
        CanvasTextField t = new CanvasTextField(
                (float) o.optDouble("cx", 0),
                (float) o.optDouble("cy", 0));
        t.id = o.optString("id", t.id);
        t.text = o.optString("text", "");
        t.width = (float) o.optDouble("width", 280);
        t.height = (float) o.optDouble("height", 80);
        t.textSize = (float) o.optDouble("textSize", 28);
        t.color = o.optInt("color", defaultTextColor);
        t.fontFamily = o.optString("fontFamily", "sans");
        t.typefaceStyle = o.optInt("typefaceStyle", Typeface.NORMAL);
        t.userSized = o.optBoolean("userSized", false);
        t.contentBlock = o.optBoolean("contentBlock", false);
        t.plainOnly = o.optBoolean("plainOnly", false);
        t.presentHidden = o.optBoolean("presentHidden", false);
        t.invalidateLayout();
        return t;
    }

    CanvasTextField duplicate(float ox, float oy) {
        CanvasTextField d = new CanvasTextField(cx + ox, cy + oy);
        d.text = text;
        d.width = width;
        d.height = height;
        d.textSize = textSize;
        d.color = color;
        d.fontFamily = fontFamily;
        d.typefaceStyle = typefaceStyle;
        d.userSized = userSized;
        d.contentBlock = contentBlock;
        d.plainOnly = plainOnly;
        d.presentHidden = presentHidden;
        // Must not share the bitmap: clearLatexBitmap/setLatexBitmap recycle it, which
        // would leave the other copy drawing a recycled bitmap and crash the canvas.
        if (latexBitmap != null && !latexBitmap.isRecycled()) {
            d.latexBitmap = latexBitmap.copy(latexBitmap.getConfig(), false);
            d.latexWorldPerPx = latexWorldPerPx;
        }
        d.layout = null;
        d.layoutKey = "";
        return d;
    }
}
