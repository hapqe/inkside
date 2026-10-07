package me.hapke.inkside;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewOutlineProvider;

/**
 * The website's look (inkside.hapke.me) for the Inkside themes: raised surfaces get a 2dp ink
 * border and a hard, unblurred ink shadow offset down and to the right, and no soft shadow is
 * drawn anywhere (elevation stays, so stacking order does not change; only the shadow colours
 * go transparent). Under every other theme {@link #elevate} is a plain {@code setElevation}.
 *
 * The original background and padding are kept in tags, so a surface can switch back and forth
 * with the theme; the drawable it was built from is never changed. While sketched, the padding
 * grows by the border and the shadow, so contents (a selected tool's circle, a menu row) stay
 * inside the border instead of running over it.
 */
final class SketchStyle {
    private static final int TAG_PLAIN_BG = R.id.sketch_plain_bg;
    private static final int TAG_PLAIN_PAD = R.id.sketch_plain_pad;

    /** On while an Inkside theme is active; set by {@link MainActivity#applyAppTheme}. */
    static boolean on;
    /** Border and shadow colour: the theme's text colour, like the site's ink. */
    static int ink = 0xFF1B1F3B;

    static void setTheme(ThemeConfig.AppTheme theme) {
        on = theme != null && theme.id.startsWith("inkside-");
        if (theme != null) ink = theme.onSurface | 0xFF000000;
    }

    /**
     * Raises {@code v} by {@code dp}: a soft shadow normally, the ink border and offset shadow
     * under an Inkside theme. Call it after the background is set (or again after replacing it).
     */
    static void elevate(View v, float dp) {
        elevate(v, dp, -1);
    }

    /** {@link #elevate}, with the hard shadow's offset given ({@code offsetDp}) instead of
     *  derived from the elevation; −1 derives it. */
    static void elevate(View v, float dp, int offsetDp) {
        v.setElevation(dp * v.getResources().getDisplayMetrics().density);
        sketch(v, dp, offsetDp);
        shadow(v);
    }

    /**
     * The ink border and offset shadow under an Inkside theme, and nothing otherwise: for
     * surfaces that are flat in the other themes (menu cards inside a popup, dialog buttons).
     */
    static void outline(View v, float dp) {
        sketch(v, dp, -1);
        shadow(v);
    }

    /** Soft shadow colours: none under an Inkside theme, the platform's black otherwise. */
    static void shadow(View v) {
        int c = on ? 0 : 0xFF000000;
        v.setOutlineAmbientShadowColor(c);
        v.setOutlineSpotShadowColor(c);
    }

    /** {@link #shadow} for {@code v} and everything inside it. */
    static void shadowTree(View v) {
        shadow(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) shadowTree(g.getChildAt(i));
        }
    }

    /**
     * Keeps a window free of soft shadows while an Inkside theme is on, including views added
     * later (their elevation and translationZ cast shadows unless their colours are cleared).
     */
    static void watch(View root) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (on) shadowTree(root);
                    }
                });
    }

    /**
     * A card drawn straight on a canvas (gesture menus): the offset ink shadow, the fill from
     * {@code fill} (its colour and alpha), and the ink border. {@code alpha} fades all of it.
     */
    static void drawCard(Canvas c, RectF r, float radius, Paint fill, int alpha, float den) {
        float off = 4 * den;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(ink);
        p.setAlpha(alpha);
        c.drawRoundRect(r.left + off, r.top + off, r.right + off, r.bottom + off, radius, radius, p);
        c.drawRoundRect(r, radius, radius, fill);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * den);
        c.drawRoundRect(r.left + den, r.top + den, r.right - den, r.bottom - den, radius, radius, p);
    }

    /** Sketches {@code v} (true) or puts its plain background and padding back (false). */
    private static boolean sketch(View v, float dp, int offsetDp) {
        float den = v.getResources().getDisplayMetrics().density;
        Drawable bg = v.getBackground();
        Drawable plain = bg instanceof Sketched ? ((Sketched) bg).plain
                : bg instanceof RippleDrawable && v.getTag(TAG_PLAIN_BG) != null
                        && ((RippleDrawable) bg).getNumberOfLayers() > 0
                        && ((RippleDrawable) bg).getDrawable(0) instanceof Sketched
                        ? (Drawable) v.getTag(TAG_PLAIN_BG) : bg;
        if (!on) {
            if (plain != bg) {
                v.setBackground(plain);
                v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            }
            restorePadding(v);
            v.setTag(TAG_PLAIN_BG, null);
            return false;
        }
        GradientDrawable fill = fillOf(plain);
        if (fill == null) {
            // Drawn by the view itself or not a plain rounded fill: keep the soft shadow.
            restorePadding(v);
            return false;
        }
        int stroke = Math.round(2 * den);
        int off = Math.round((offsetDp >= 0 ? offsetDp : dp >= 6 ? 5 : dp >= 3 ? 3 : 2) * den);
        Sketched s = new Sketched(fill, stroke, off);
        Drawable next = s;
        if (plain instanceof RippleDrawable) {
            // Keep the press/hover ripple: same ripple, its content swapped for the sketch.
            Drawable.ConstantState cs = plain.getConstantState();
            if (cs != null) {
                RippleDrawable r = (RippleDrawable) cs.newDrawable().mutate();
                r.setDrawable(0, s);
                next = r;
            }
        }
        v.setTag(TAG_PLAIN_BG, plain);
        v.setBackground(next);
        int[] pad = (int[]) v.getTag(TAG_PLAIN_PAD);
        if (pad == null) {
            pad = new int[] {v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()};
            v.setTag(TAG_PLAIN_PAD, pad);
        }
        v.setPadding(pad[0] + stroke, pad[1] + stroke, pad[2] + stroke + off, pad[3] + stroke + off);
        // The outline covers the fill and the offset shadow together: views that clip to it
        // (round chat buttons, panels) would otherwise cut the shadow off. No soft shadow is
        // drawn from it under these themes, so it only decides clipping and the ripple.
        final float radius = fill.getShape() == GradientDrawable.OVAL ? 0f : s.radius;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        return true;
    }

    /** The rounded fill a background is made of: itself, or a ripple's first layer. */
    private static GradientDrawable fillOf(Drawable d) {
        if (d instanceof GradientDrawable) return (GradientDrawable) d;
        if (d instanceof RippleDrawable) {
            RippleDrawable r = (RippleDrawable) d;
            if (r.getNumberOfLayers() > 0 && r.getId(0) != android.R.id.mask
                    && r.getDrawable(0) instanceof GradientDrawable) {
                return (GradientDrawable) r.getDrawable(0);
            }
        }
        return null;
    }

    private static void restorePadding(View v) {
        int[] pad = (int[]) v.getTag(TAG_PLAIN_PAD);
        if (pad != null) v.setPadding(pad[0], pad[1], pad[2], pad[3]);
        v.setTag(TAG_PLAIN_PAD, null);
    }

    /** Adds the ink border (no shadow) to a flat card's background under an Inkside theme. */
    static void border(GradientDrawable bg, float den) {
        if (on) bg.setStroke(Math.round(2 * den), ink);
    }

    /** The plain fill with an ink stroke, over an ink copy of it shifted by {@code off}. */
    private static final class Sketched extends LayerDrawable {
        final GradientDrawable plain;
        final float radius;

        Sketched(GradientDrawable plain, int stroke, int off) {
            super(new Drawable[] {copy(plain), copy(plain)});
            this.plain = plain;
            this.radius = plain.getCornerRadius();
            GradientDrawable shadow = (GradientDrawable) getDrawable(0);
            shadow.setColor(ink);
            shadow.setStroke(0, 0);
            GradientDrawable fill = (GradientDrawable) getDrawable(1);
            fill.setStroke(stroke, ink);
            setLayerInset(0, off, off, 0, 0);
            setLayerInset(1, 0, 0, off, off);
        }

        private static GradientDrawable copy(GradientDrawable g) {
            Drawable.ConstantState cs = g.getConstantState();
            return (GradientDrawable) (cs != null ? cs.newDrawable().mutate() : new GradientDrawable());
        }
    }

    private SketchStyle() {}
}
