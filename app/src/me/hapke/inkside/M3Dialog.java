package me.hapke.inkside;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The app's dialog: a Material 3 card in the theme's colours that lands like every
 * other window here and drops away when closed. Its {@link Builder} speaks the same
 * calls as {@code android.app.AlertDialog.Builder} (title, message, view, items,
 * positive / negative / neutral buttons), so a platform dialog moves over by
 * changing the class name. Works from any view, not just the activity.
 */
final class M3Dialog extends Dialog {
    /** Theme colours, set by the activity whenever the app theme changes. */
    static int surface = 0xFF26252E;
    static int onSurface = 0xFFE6E1E9;
    static int onSurfaceVariant = 0xFFCAC4D0;
    static int primary = 0xFFBAC3FF;
    static int primaryContainer = 0xFF3F51B5;
    static int onPrimaryContainer = 0xFFE8EAF6;
    static int surfaceHighest = 0xFF31303A;
    static int outlineVariant = 0xFF49454F;
    /**
     * Material 3's error roles, for the dark or the light scheme by the theme's text colour:
     * the dark scheme's pale red is barely visible on a light surface. Set by
     * {@link #setPalette}; read by {@link M3Menu} and the rest for "Delete" and the like.
     */
    static int ERROR = 0xFFF2B8B5;
    static int ERROR_CONTAINER = 0xFF8C1D18;
    static int ON_ERROR_CONTAINER = 0xFFF9DEDC;

    static void setPalette(int surface, int onSurface, int onSurfaceVariant, int primary,
                           int primaryContainer, int onPrimaryContainer, int surfaceHighest,
                           int outlineVariant) {
        M3Dialog.surface = surface | 0xFF000000;
        M3Dialog.onSurface = onSurface;
        M3Dialog.onSurfaceVariant = onSurfaceVariant;
        M3Dialog.primary = primary;
        M3Dialog.primaryContainer = primaryContainer;
        M3Dialog.onPrimaryContainer = onPrimaryContainer;
        M3Dialog.surfaceHighest = surfaceHighest;
        M3Dialog.outlineVariant = outlineVariant;
        // Dark text means a light theme.
        boolean light = android.graphics.Color.luminance(onSurface | 0xFF000000) < 0.5f;
        ERROR = light ? 0xFFB3261E : 0xFFF2B8B5;
        ERROR_CONTAINER = light ? 0xFFF9DEDC : 0xFF8C1D18;
        ON_ERROR_CONTAINER = light ? 0xFF410E0B : 0xFFF9DEDC;
    }

    private final float density;
    private FrameLayout root;
    private View scrim;
    private View card;
    private boolean closing;

    private M3Dialog(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    @Override
    public void show() {
        super.show();
        Window w = getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(0x00000000));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setWindowAnimations(0);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // The app runs without system bars; a dialog window must not bring them back.
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        if (scrim != null) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(200).setInterpolator(Motion.STANDARD).start();
        }
        if (card != null) {
            card.setAlpha(0f);
            card.post(() -> Motion.popIn(card, card.getWidth() / 2f, card.getHeight() / 2f, 16f));
        }
    }

    /** Drops the card away first, then really closes. */
    @Override
    public void dismiss() {
        if (closing || card == null || !isShowing()) {
            if (!closing) super.dismiss();
            return;
        }
        closing = true;
        if (scrim != null) Motion.fadeOut(scrim, null);
        Motion.popOut(card, 8f, null, () -> {
            try {
                M3Dialog.super.dismiss();
            } catch (Exception ignored) {
                // Window already gone (activity finishing).
            }
        });
    }

    private float dp(float v) {
        return v * density;
    }

    /** Same calls as {@code AlertDialog.Builder}. */
    static final class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private View view;
        private CharSequence[] items;
        private DialogInterface.OnClickListener itemsListener;
        private CharSequence positive;
        private DialogInterface.OnClickListener positiveListener;
        private CharSequence negative;
        private DialogInterface.OnClickListener negativeListener;
        private CharSequence neutral;
        private DialogInterface.OnClickListener neutralListener;
        private DialogInterface.OnDismissListener dismissListener;
        private boolean cancelable = true;

        Builder(Context context) {
            this.context = context;
        }

        Builder setTitle(CharSequence t) {
            title = t;
            return this;
        }

        Builder setMessage(CharSequence m) {
            message = m;
            return this;
        }

        Builder setView(View v) {
            view = v;
            return this;
        }

        Builder setItems(CharSequence[] list, DialogInterface.OnClickListener l) {
            items = list;
            itemsListener = l;
            return this;
        }

        Builder setPositiveButton(CharSequence label, DialogInterface.OnClickListener l) {
            positive = label;
            positiveListener = l;
            return this;
        }

        Builder setNegativeButton(CharSequence label, DialogInterface.OnClickListener l) {
            negative = label;
            negativeListener = l;
            return this;
        }

        Builder setNeutralButton(CharSequence label, DialogInterface.OnClickListener l) {
            neutral = label;
            neutralListener = l;
            return this;
        }

        Builder setOnDismissListener(DialogInterface.OnDismissListener l) {
            dismissListener = l;
            return this;
        }

        Builder setCancelable(boolean c) {
            cancelable = c;
            return this;
        }

        M3Dialog show() {
            M3Dialog d = create();
            d.show();
            return d;
        }

        M3Dialog create() {
            final M3Dialog d = new M3Dialog(context);
            float den = d.density;
            FrameLayout root = new FrameLayout(context);
            View scrim = new View(context);
            scrim.setBackgroundColor(0x99000000);
            root.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim.setOnClickListener(v -> {
                if (cancelable) d.cancel();
            });

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(24 * den);
            col.setPadding(pad, pad, pad, Math.round(18 * den));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(28 * den);
            bg.setColor(surface);
            bg.setStroke(Math.round(den), (outlineVariant & 0x00FFFFFF) | 0x33000000);
            col.setBackground(bg);
            SketchStyle.elevate(col, 6);
            col.setClickable(true);

            if (title != null) {
                TextView t = new TextView(context);
                t.setText(title);
                t.setTextColor(onSurface);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                HeadlineFont.apply(t);
                t.setPadding(0, 0, 0, Math.round(16 * den));
                col.addView(t);
            }

            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            if (message != null) {
                TextView m = new TextView(context);
                m.setText(message);
                m.setTextColor(onSurfaceVariant);
                m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                m.setLineSpacing(0f, 1.15f);
                m.setMovementMethod(LinkMovementMethod.getInstance());
                body.addView(m);
            }
            if (view != null) {
                styleFields(view, den);
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (message != null) vlp.topMargin = Math.round(12 * den);
                body.addView(view, vlp);
            }
            if (items != null) {
                for (int i = 0; i < items.length; i++) {
                    final int index = i;
                    TextView row = new TextView(context);
                    row.setText(items[i]);
                    row.setTextColor(onSurface);
                    row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setMinHeight(Math.round(52 * den));
                    row.setPadding(Math.round(16 * den), 0, Math.round(16 * den), 0);
                    row.setBackground(ripple(0x00000000, 16 * den));
                    row.setOnClickListener(v -> {
                        if (itemsListener != null) itemsListener.onClick(d, index);
                        d.dismiss();
                    });
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    // Rows reach the card's inner edge; the ripple keeps its own rounding.
                    rlp.leftMargin = -Math.round(8 * den);
                    rlp.rightMargin = -Math.round(8 * den);
                    body.addView(row, rlp);
                }
            }
            ScrollView scroll = new ScrollView(context) {
                @Override
                protected void onMeasure(int w, int h) {
                    int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.6f);
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
                }
            };
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroll.addView(body);
            col.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (positive != null || negative != null || neutral != null) {
                LinearLayout buttons = new LinearLayout(context);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                buttons.setPadding(0, Math.round(20 * den), 0, 0);
                if (neutral != null) {
                    buttons.addView(button(context, neutral, false, false, v -> {
                        if (neutralListener != null) neutralListener.onClick(d, DialogInterface.BUTTON_NEUTRAL);
                        d.dismiss();
                    }, den));
                    // Neutral sits apart, at the start.
                    View gap = new View(context);
                    buttons.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
                }
                if (negative != null) {
                    buttons.addView(button(context, negative, false, false, v -> {
                        if (negativeListener != null) negativeListener.onClick(d, DialogInterface.BUTTON_NEGATIVE);
                        d.cancel();
                    }, den));
                }
                if (positive != null) {
                    boolean destructive = isDestructive(positive);
                    buttons.addView(button(context, positive, true, destructive, v -> {
                        if (positiveListener != null) positiveListener.onClick(d, DialogInterface.BUTTON_POSITIVE);
                        d.dismiss();
                    }, den));
                }
                col.addView(buttons, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    Math.min(Math.round(440 * den), screenW - Math.round(48 * den)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.CENTER;
            root.addView(col, clp);

            d.setContentView(root);
            SketchStyle.watch(root);
            // Fields in a dialog: the card centres in what the keyboard leaves free, moving
            // with the keyboard as it slides (the window itself is often not resized).
            KeyboardInsets.follow(root, px -> root.setPadding(
                    root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), px));
            d.root = root;
            d.scrim = scrim;
            d.card = col;
            d.setCancelable(cancelable);
            d.setCanceledOnTouchOutside(cancelable);
            if (dismissListener != null) d.setOnDismissListener(dismissListener);
            return d;
        }

        private static boolean isDestructive(CharSequence label) {
            String s = label.toString().toLowerCase(java.util.Locale.ROOT);
            return s.startsWith("delete") || s.startsWith("remove") || s.startsWith("discard")
                    || s.startsWith("empty");
        }

        private TextView button(Context ctx, CharSequence label, boolean filled, boolean destructive,
                                View.OnClickListener onClick, float den) {
            TextView b = new TextView(ctx);
            b.setText(label);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(Math.round(40 * den));
            b.setPadding(Math.round((filled ? 24 : 16) * den), 0, Math.round((filled ? 24 : 16) * den), 0);
            int fill = !filled ? 0x00000000 : destructive ? ERROR_CONTAINER : primaryContainer;
            b.setTextColor(!filled ? (destructive ? ERROR : primary)
                    : destructive ? ON_ERROR_CONTAINER : onPrimaryContainer);
            b.setBackground(ripple(fill, 999 * den));
            if (filled) SketchStyle.outline(b, 2);
            b.setOnClickListener(onClick);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * den));
            lp.leftMargin = Math.round(8 * den);
            b.setLayoutParams(lp);
            return b;
        }

        private static RippleDrawable ripple(int fill, float radius) {
            GradientDrawable content = new GradientDrawable();
            content.setCornerRadius(radius);
            content.setColor(fill);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(radius);
            mask.setColor(0xFFFFFFFF);
            return new RippleDrawable(android.content.res.ColorStateList.valueOf(
                    (onSurface & 0x00FFFFFF) | 0x29000000), content, mask);
        }

        /** Plain platform text fields inside the dialog get the app's filled field look. */
        private static void styleFields(View v, float den) {
            if (v instanceof EditText) {
                EditText e = (EditText) v;
                e.setTextColor(onSurface);
                e.setHintTextColor(onSurfaceVariant);
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                GradientDrawable f = new GradientDrawable();
                f.setCornerRadius(12 * den);
                f.setColor(surfaceHighest);
                f.setStroke(Math.round(den), (outlineVariant & 0x00FFFFFF) | 0x66000000);
                SketchStyle.border(f, den);
                e.setBackground(f);
                int p = Math.round(14 * den);
                e.setPadding(Math.round(16 * den), p, Math.round(16 * den), p);
                return;
            }
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                // Only plain labels that kept the platform's default colours.
                if (t.getCurrentTextColor() == 0xDE000000 || t.getCurrentTextColor() == 0xFF000000) {
                    t.setTextColor(onSurfaceVariant);
                }
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) styleFields(g.getChildAt(i), den);
            }
        }
    }
}
