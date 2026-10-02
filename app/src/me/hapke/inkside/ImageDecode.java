package me.hapke.inkside;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Bounded image decoding. A dropped or pasted photo used to be decoded at full
 * resolution (a 12 MP shot is ~48 MB of pixels), kept on the canvas and saved into
 * the session as PNG — enough to exhaust the heap on the drop and again on every
 * launch while restoring. Everything placed on the canvas goes through here.
 */
final class ImageDecode {
    /** Longest edge kept for canvas images — plenty for a page-sized figure. */
    static final int MAX_EDGE = 2048;

    private ImageDecode() {}

    static Bitmap decode(byte[] data) {
        return decode(data, MAX_EDGE);
    }

    /** Decodes at most {@code maxEdge} px on the long side; null when unreadable. */
    static Bitmap decode(byte[] data, int maxEdge) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (Math.max(w, h) / (sample * 2) >= maxEdge) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp == null) return null;
            int long_ = Math.max(bmp.getWidth(), bmp.getHeight());
            if (long_ <= maxEdge) return bmp;
            float s = maxEdge / (float) long_;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, Math.round(bmp.getWidth() * s)),
                    Math.max(1, Math.round(bmp.getHeight() * s)), true);
            if (scaled != bmp) bmp.recycle();
            return scaled;
        } catch (OutOfMemoryError oom) {
            return null;
        }
    }

    /** True when {@link #decode} would shrink this image. */
    static boolean wouldShrink(byte[] data, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        return Math.max(bounds.outWidth, bounds.outHeight) > maxEdge;
    }
}
