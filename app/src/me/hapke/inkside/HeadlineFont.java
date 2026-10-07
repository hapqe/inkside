package me.hapke.inkside;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

/**
 * Fraunces, the website's headline face (inkside.hapke.me), for screen and dialog titles.
 * One variable font in assets, used at the site's heading weight; falls back to the
 * system bold if it cannot be loaded.
 */
final class HeadlineFont {
    private static Typeface face;

    static Typeface get(Context ctx) {
        if (face == null) {
            try {
                face = new Typeface.Builder(ctx.getAssets(), "fonts/Fraunces.ttf")
                        .setFontVariationSettings("'wght' 700, 'opsz' 48")
                        .build();
            } catch (RuntimeException e) {
                face = null;
            }
            if (face == null) face = Typeface.DEFAULT_BOLD;
        }
        return face;
    }

    static void apply(TextView t) {
        t.setTypeface(get(t.getContext()));
    }

    private HeadlineFont() {}
}
