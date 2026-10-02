package me.hapke.inkside;

import java.util.function.IntSupplier;

/**
 * The optional thin outline around ink and text: in the opposite colour of the page, drawn
 * only for things whose colour is close to the page's (white ink on a light page, black on a
 * dark one), so they stay readable when the theme flips.
 */
final class PenOutline {
    static volatile boolean enabled;
    /** The colour of the page the content sits on. */
    static volatile IntSupplier paper = () -> 0xFFFFFFFF;

    private PenOutline() {}

    static double luminance(int c) {
        return (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c)
                + 0.114 * android.graphics.Color.blue(c)) / 255.0;
    }

    /** True when something drawn in {@code color} should get the outline. */
    static boolean needed(int color) {
        return enabled && Math.abs(luminance(color) - luminance(paper.getAsInt())) < 0.4;
    }

    /** Dark outline on a light page, light on a dark one. */
    static int color() {
        return luminance(paper.getAsInt()) > 0.5 ? 0xFF202024 : 0xFFF4F4F5;
    }

    /** How much wider the outline pass is than the stroke it surrounds (both sides together). */
    static float strokeExtra(float width) {
        return 0.45f + width * 0.05f;
    }

    /** Width of the line traced around the glyphs (half of it shows outside them). */
    static float textLine(float size) {
        return Math.max(0.7f, size * 0.04f);
    }
}
