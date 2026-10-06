package me.hapke.inkside;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's long-press and context menus: a rounded surface card of icon rows in the
 * theme's colours ({@link M3Dialog}'s palette), growing out of where it was opened.
 * Replaces the platform PopupMenu, whose stock look matched nothing else in the app.
 */
final class M3Menu {
    private static final int ERROR = 0xFFF2B8B5;
    private static final int WIDTH_DP = 240;

    private static final class Item {
        final int icon;
        final String label;
        final boolean destructive;
        final Runnable action;
        final boolean divider;

        Item(int icon, String label, boolean destructive, Runnable action, boolean divider) {
            this.icon = icon;
            this.label = label;
            this.destructive = destructive;
            this.action = action;
            this.divider = divider;
        }
    }

    private final Context ctx;
    private final float density;
    private final List<Item> items = new ArrayList<>();
    private PopupWindow window;

    M3Menu(Context ctx) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    /** A row; {@code icon} 0 for none. */
    M3Menu add(int icon, String label, Runnable action) {
        items.add(new Item(icon, label, false, action, false));
        return this;
    }

    /** A row in the error colour, for deleting and the like. */
    M3Menu addDestructive(int icon, String label, Runnable action) {
        items.add(new Item(icon, label, true, action, false));
        return this;
    }

    M3Menu divider() {
        if (!items.isEmpty()) items.add(new Item(0, null, false, null, true));
        return this;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    void dismiss() {
        if (window != null) window.dismiss();
    }

    /** Opens under {@code anchor}, its right edge aligned with the anchor's when room is short. */
    M3Menu showUnder(View anchor) {
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        return showAt(anchor, loc[0], loc[1] + anchor.getHeight());
    }

    /** Opens with its corner at screen point (x, y), flipped to stay on screen. */
    M3Menu showAt(View parent, float x, float y) {
        if (items.isEmpty() || parent == null || parent.getWindowToken() == null) return this;
        LinearLayout card = build();
        int w = dp(WIDTH_DP);
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = card.getMeasuredHeight();
        View root = parent.getRootView();
        int screenW = root.getWidth(), screenH = root.getHeight();
        int margin = dp(8);
        int left = Math.round(x);
        int top = Math.round(y);
        boolean flipX = left + w > screenW - margin;
        boolean flipY = top + h > screenH - margin;
        if (flipX) left = Math.max(margin, left - w);
        if (flipY) top = Math.max(margin, top - h);

        window = new PopupWindow(card, w, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.setOutsideTouchable(true);
        window.setElevation(dp(8));
        window.setClippingEnabled(true);
        card.setPivotX(flipX ? w : 0);
        card.setPivotY(flipY ? h : 0);
        card.setAlpha(0f);
        card.setScaleX(0.9f);
        card.setScaleY(0.8f);
        window.showAtLocation(parent, Gravity.TOP | Gravity.START, left, top);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.LAND).start();
        Motion.stagger(card, 30L, -4f);
        return this;
    }

    private LinearLayout build() {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(M3Dialog.surfaceHighest | 0xFF000000);
        bg.setStroke(Math.max(1, dp(1) / 2), (M3Dialog.outlineVariant & 0x00FFFFFF) | 0x66000000);
        card.setBackground(bg);
        card.setClipToOutline(true);
        for (Item it : items) {
            if (it.divider) {
                View line = new View(ctx);
                line.setBackgroundColor((M3Dialog.outlineVariant & 0x00FFFFFF) | 0x99000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
                lp.setMargins(dp(12), dp(4), dp(12), dp(4));
                card.addView(line, lp);
                continue;
            }
            card.addView(row(it), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    private View row(Item it) {
        int fg = it.destructive ? ERROR : M3Dialog.onSurface;
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(46));
        r.setPadding(dp(12), 0, dp(14), 0);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(12));
        r.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((M3Dialog.primary & 0x00FFFFFF) | 0x33000000),
                null, mask));
        r.setClickable(true);
        r.setOnClickListener(v -> {
            dismiss();
            if (it.action != null) it.action.run();
        });
        if (it.icon != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(it.icon);
            iv.setColorFilter(it.destructive ? ERROR : M3Dialog.onSurfaceVariant);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
            ilp.rightMargin = dp(14);
            r.addView(iv, ilp);
        }
        TextView t = new TextView(ctx);
        t.setText(it.label);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
