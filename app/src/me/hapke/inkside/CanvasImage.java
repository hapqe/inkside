package me.hapke.inkside;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/** A free-floating image on the infinite canvas. */
final class CanvasImage {
    Bitmap bitmap;
    float cx, cy; // center in world space
    float width, height; // unrotated axis-aligned size in world space
    float rotationDeg;
    /** Left out of the presentation slide; still shown on the tablet. */
    boolean presentHidden;
    /** Mirrored along its own width / height axis (applied after the rotation). */
    boolean flipX, flipY;
    /** Drawn under the handwriting and text instead of over it. */
    boolean behindInk;
    /** Workspace path for agent viz images — refreshed when the file updates. */
    String vizPath;
    /** Source workspace path for dropped/placed images (non-agent). */
    String sourcePath;
    /**
     * Artifact HTML this element shows live. When set the canvas draws only the slot
     * and the host floats a WebView over it — but it is still an image as far as
     * selection, moving, resizing, rotating, deleting and undo are concerned.
     */
    String livePath;
    /**
     * Height over width the artifact's own content asked for, 0 when not measured yet.
     * A placed element keeps this shape; resizing changes how much of the page shows.
     */
    float liveAspect;

    boolean isLive() {
        return livePath != null && !livePath.isEmpty();
    }

    private final RectF cachedBounds = new RectF();
    private final RectF drawDst = new RectF();
    private boolean boundsDirty = true;

    CanvasImage(Bitmap bitmap, float cx, float cy, float width, float height) {
        this.bitmap = bitmap;
        this.cx = cx;
        this.cy = cy;
        this.width = width;
        this.height = height;
        this.rotationDeg = 0f;
    }

    /** Copies how it is shown — rotation, mirroring, layer, presentation — onto a duplicate. */
    void copyLookTo(CanvasImage dup) {
        dup.presentHidden = presentHidden;
        dup.rotationDeg = rotationDeg;
        dup.flipX = flipX;
        dup.flipY = flipY;
        dup.behindInk = behindInk;
    }

    synchronized void markDirty() {
        boundsDirty = true;
    }

    synchronized void setCenter(float x, float y) {
        cx = x;
        cy = y;
        boundsDirty = true;
    }

    synchronized void setSize(float w, float h) {
        width = w;
        height = h;
        boundsDirty = true;
    }

    synchronized void setRotation(float deg) {
        rotationDeg = deg;
        boundsDirty = true;
    }

    synchronized RectF bounds() {
        if (!boundsDirty) return cachedBounds;
        float hw = width * 0.5f;
        float hh = height * 0.5f;
        double rad = Math.toRadians(rotationDeg);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float x0 = cx + c * (-hw) - s * (-hh);
        float y0 = cy + s * (-hw) + c * (-hh);
        float x1 = cx + c * (hw) - s * (-hh);
        float y1 = cy + s * (hw) + c * (-hh);
        float x2 = cx + c * (hw) - s * (hh);
        float y2 = cy + s * (hw) + c * (hh);
        float x3 = cx + c * (-hw) - s * (hh);
        float y3 = cy + s * (-hw) + c * (hh);
        cachedBounds.set(
                Math.min(Math.min(x0, x1), Math.min(x2, x3)),
                Math.min(Math.min(y0, y1), Math.min(y2, y3)),
                Math.max(Math.max(x0, x1), Math.max(x2, x3)),
                Math.max(Math.max(y0, y1), Math.max(y2, y3)));
        boundsDirty = false;
        return cachedBounds;
    }

    boolean contains(float x, float y) {
        float dx = x - cx;
        float dy = y - cy;
        double rad = Math.toRadians(-rotationDeg);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float lx = c * dx - s * dy;
        float ly = s * dx + c * dy;
        return Math.abs(lx) <= width * 0.5f && Math.abs(ly) <= height * 0.5f;
    }

    /** Synchronized: {@code drawDst} is per-object scratch and two threads draw. */
    synchronized void draw(android.graphics.Canvas canvas, android.graphics.Paint paint) {
        if (bitmap == null || bitmap.isRecycled()) return;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(rotationDeg);
        if (flipX || flipY) canvas.scale(flipX ? -1f : 1f, flipY ? -1f : 1f);
        drawDst.set(-width * 0.5f, -height * 0.5f, width * 0.5f, height * 0.5f);
        canvas.drawBitmap(bitmap, null, drawDst, paint);
        canvas.restore();
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("cx", cx);
        o.put("cy", cy);
        o.put("width", width);
        o.put("height", height);
        o.put("rotationDeg", rotationDeg);
        if (presentHidden) o.put("presentHidden", true);
        if (flipX) o.put("flipX", true);
        if (flipY) o.put("flipY", true);
        if (behindInk) o.put("behindInk", true);
        if (vizPath != null && !vizPath.isEmpty()) o.put("vizPath", vizPath);
        if (livePath != null && !livePath.isEmpty()) o.put("livePath", livePath);
        if (liveAspect > 0f) o.put("liveAspect", liveAspect);
        if (sourcePath != null && !sourcePath.isEmpty()) o.put("sourcePath", sourcePath);
        // A live element has no pixels of its own; its content is the artifact file.
        String png = encodedPng();
        if (png != null) o.put("png", png);
        return o;
    }

    /** Base64 PNG of {@link #bitmap}, re-encoded only when the pixels change. */
    private Bitmap pngOf;
    private int pngGeneration;
    private String pngBase64;

    /**
     * Every session save used to PNG-compress each image on the UI thread, 400ms
     * after every stroke — tens of ms per image, landing mid-stroke as handwriting lag.
     */
    private synchronized String encodedPng() {
        Bitmap bmp = bitmap;
        if (bmp == null || bmp.isRecycled()) return null;
        if (pngBase64 != null && pngOf == bmp && pngGeneration == bmp.getGenerationId()) {
            return pngBase64;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
        pngBase64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        pngOf = bmp;
        pngGeneration = bmp.getGenerationId();
        return pngBase64;
    }

    private synchronized void seedEncodedPng(String base64) {
        if (bitmap == null || base64 == null) return;
        pngBase64 = base64;
        pngOf = bitmap;
        pngGeneration = bitmap.getGenerationId();
    }

    static CanvasImage fromJson(JSONObject o) throws Exception {
        String live = o.optString("livePath", "");
        Bitmap bmp = null;
        String png = o.optString("png", null);
        boolean shrunk = false;
        if (png != null) {
            byte[] bytes = Base64.decode(png, Base64.DEFAULT);
            // Oversized images from before decoding was capped shrink on load, so the
            // next save writes them small and the session stops growing.
            shrunk = ImageDecode.wouldShrink(bytes, ImageDecode.MAX_EDGE);
            bmp = ImageDecode.decode(bytes);
        }
        // Only a live element may arrive without pixels.
        if (bmp == null && live.isEmpty()) return null;
        CanvasImage img = new CanvasImage(
                bmp,
                (float) o.getDouble("cx"),
                (float) o.getDouble("cy"),
                (float) o.getDouble("width"),
                (float) o.getDouble("height"));
        img.rotationDeg = (float) o.optDouble("rotationDeg", 0);
        img.presentHidden = o.optBoolean("presentHidden", false);
        img.flipX = o.optBoolean("flipX", false);
        img.flipY = o.optBoolean("flipY", false);
        img.behindInk = o.optBoolean("behindInk", false);
        img.vizPath = o.optString("vizPath", null);
        if (img.vizPath != null && img.vizPath.isEmpty()) img.vizPath = null;
        img.sourcePath = o.optString("sourcePath", null);
        if (img.sourcePath != null && img.sourcePath.isEmpty()) img.sourcePath = null;
        img.livePath = live.isEmpty() ? null : live;
        img.liveAspect = (float) o.optDouble("liveAspect", 0);
        // The saved bytes already encode these pixels — no need to compress again.
        if (!shrunk) img.seedEncodedPng(png);
        return img;
    }
}
