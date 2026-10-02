package me.hapke.inkside;

import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/**
 * The colour favourites panel and the colour picker.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class ColorPanels {
    private final MainActivity act;

    ColorPanels(MainActivity act) {
        this.act = act;
    }

    /**
     * The shared favourite colours for slot {@code slot} of the pen in use, as a second
     * pill right under the tool bar (not a full-screen dialog): scroll sideways through
     * them, tap one to put it in the slot, long-press one to delete it, and "+" at the
     * right end opens the picker for a new favourite (which then goes in the slot too).
     */
    void showColorFavoritesPanel(int slot) {
        showColorFavoritesPanel(slot, null);
    }

    /**
     * Same, from a tool other than the pen: the slot's colour changes everywhere it shows
     * and {@code afterAssign} then applies it (to the selection, a text box, …) instead
     * of switching to the pen.
     */
    void showColorFavoritesPanel(int slot, MainActivity.IntConsumer afterAssign) {
        if (act.rootLayout == null || slot < 0 || slot >= act.penTools.palette().length) return;
        act.settingsPanel.dismissOptionsMenu();
        dismissColorPanel();

        // Transparent catcher: a tap anywhere else closes the pill.
        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissColorPanel());

        LinearLayout pill = new LinearLayout(act);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(MainActivity.TOOL_PILL_RADIUS));
        bg.setColor(act.M3_SURFACE_CONTAINER_HIGH);
        pill.setBackground(bg);
        pill.setElevation(act.dp(4));
        pill.setClickable(true);
        pill.setOnClickListener(v -> {});

        HorizontalScrollView scroller = new HorizontalScrollView(act);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_XS));
        final int current = act.penTools.palette()[slot];
        for (final int color : new ArrayList<>(act.favoriteColors)) {
            View cell = colorPanelSwatch(color, color == current);
            cell.setContentDescription("Use this colour (long-press to delete)");
            cell.setOnClickListener(v -> {
                assignSlotColor(slot, color, afterAssign);
                dismissColorPanel();
            });
            cell.setOnLongClickListener(v -> {
                if (act.favoriteColors.size() <= 1) {
                    act.statusToast("Keep at least one favourite colour");
                    return true;
                }
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                act.favoriteColors.remove(Integer.valueOf(color));
                act.persistence.scheduleSave();
                // Shrink the swatch away, then drop it — stays in place, no reopen.
                v.setStateListAnimator(null);
                v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(140)
                        .withEndAction(() -> row.removeView(v)).start();
                return true;
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(act.dp(FAV_SWATCH), act.dp(FAV_SWATCH));
            clp.rightMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(cell, clp);
        }
        scroller.addView(row);
        pill.addView(scroller, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View divider = new View(act);
        divider.setBackgroundColor((act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x66000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(act.dp(1), act.dp(20));
        dlp.leftMargin = act.dp(MainActivity.SPACE_XS);
        dlp.rightMargin = act.dp(MainActivity.SPACE_XS);
        pill.addView(divider, dlp);

        ImageView add = act.iconBtn(R.drawable.ic_add, () -> {
            dismissColorPanel();
            showColorPicker(current, "New colour", picked -> {
                if (!act.favoriteColors.contains(picked)) act.favoriteColors.add(picked);
                assignSlotColor(slot, picked, afterAssign);
            });
        });
        add.setContentDescription("New favourite colour");
        pill.addView(add, act.iconLp());

        // Right under the tool bar, left-aligned with it, never wider than it or the screen.
        int[] rootLoc = new int[2];
        act.rootLayout.getLocationInWindow(rootLoc);
        int left = act.dp(MainActivity.SPACE_LG);
        int top = act.statusBarHeight() + act.dp(56);
        int maxW = Math.round(act.getResources().getDisplayMetrics().widthPixels * 0.6f);
        if (act.toolScroll != null && act.toolScroll.getWidth() > 0) {
            int[] tl = new int[2];
            act.toolScroll.getLocationInWindow(tl);
            left = tl[0] - rootLoc[0];
            top = tl[1] - rootLoc[1] + act.toolScroll.getHeight() + act.dp(MainActivity.SPACE_SM);
            maxW = Math.max(act.dp(220), act.toolScroll.getWidth());
        }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int natural = row.getMeasuredWidth() + act.dp(MainActivity.SPACE_MD + MainActivity.SPACE_XS * 3 + 1) + act.dp(MainActivity.ICON_SIZE + MainActivity.SPACE_XS * 2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(maxW, natural), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        overlay.addView(pill, lp);
        overlay.setTranslationZ(act.dp(act.zenMode ? MainActivity.ZEN_LIFT_DP + 60 : 60));
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        colorPanelOverlay = overlay;
        act.overflowMenu.animateDropdownIn(pill);
        // Show the slot's current colour if it is far along the list.
        final int idx = act.favoriteColors.indexOf(current);
        if (idx > 0) scroller.post(() -> scroller.smoothScrollTo(
                Math.max(0, idx * act.dp(FAV_SWATCH + MainActivity.SPACE_MD) - act.dp(40)), 0));
    }

    /** Swatch size in the favourite-colours pill (a bit larger than the tool bar's). */
    private static final int FAV_SWATCH = 26;

    private FrameLayout colorPanelOverlay;

    private void dismissColorPanel() {
        if (colorPanelOverlay != null && colorPanelOverlay.getParent() != null) {
            act.rootLayout.removeView(colorPanelOverlay);
        }
        colorPanelOverlay = null;
    }

    /** Puts {@code color} in slot {@code slot} of the pen in use and draws with it. */
    private void assignSlotColor(int slot, int color, MainActivity.IntConsumer afterAssign) {
        act.penTools.palette()[slot] = color;
        if (afterAssign == null) {
            act.penTools.selectPencil(slot);
        }
        act.penTools.refreshToolSelection();
        if (afterAssign != null) {
            act.refreshSelectionColorSwatches();
            act.textTools.refreshTextColorSwatches(act.textTools.textFieldForStyleUi());
            act.textTools.refreshTextDefaultsRow();
            afterAssign.accept(color);
        }
        act.persistence.scheduleSave();
    }

    private View colorPanelSwatch(int color, boolean current) {
        View v = new View(act);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (current) {
            d.setStroke(act.dp(3), act.M3_PRIMARY);
        } else {
            d.setStroke(act.dp(1), (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x99000000);
        }
        v.setBackground(d);
        v.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, d));
        v.setClickable(true);
        return v;
    }

    private View colorPanelAddButton() {
        ImageView v = new ImageView(act);
        v.setImageResource(R.drawable.ic_add);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(6));
        v.setColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN);
        v.setContentDescription("Add colour");
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0x00000000);
        d.setStroke(act.dp(2), act.M3_OUTLINE_VARIANT);
        v.setBackground(d);
        v.setClickable(true);
        return v;
    }

    /** Quick starting points in the picker. */
    private static final int[] PICKER_PRESETS = {
            0xFF111111, 0xFFFFFFFF, 0xFFEF4444, 0xFFF97316, 0xFFEAB308,
            0xFF22C55E, 0xFF06B6D4, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899,
    };

    /**
     * Colour picker: starts at {@code initial}; "Use" hands the choice to {@code onUse}.
     * Old vs new side by side, an editable hex code, presets, and a filled Use button.
     */
    private void showColorPicker(int initial, String heading, MainActivity.IntConsumer onUse) {
        if (act.rootLayout == null) return;
        act.settingsPanel.dismissOptionsMenu();

        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setBackgroundColor(0x66000000);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(act.dp(20), act.dp(18), act.dp(20), act.dp(16));
        act.settingsPanel.applyOptionsCardSurface(card);
        card.setElevation(act.dp(8));
        card.setClickable(true);
        card.setOnClickListener(v -> {});

        // Header: title left, old | new comparison chip right.
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText(heading);
        title.setTextColor(act.M3_ON_SURFACE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final View oldHalf = new View(act);
        final View newHalf = new View(act);
        float rr = act.dp(12);
        GradientDrawable od = new GradientDrawable();
        od.setColor(initial);
        od.setCornerRadii(new float[]{rr, rr, 0, 0, 0, 0, rr, rr});
        oldHalf.setBackground(od);
        oldHalf.setContentDescription("Current colour");
        LinearLayout compare = new LinearLayout(act);
        compare.setOrientation(LinearLayout.HORIZONTAL);
        compare.addView(oldHalf, new LinearLayout.LayoutParams(act.dp(28), act.dp(28)));
        compare.addView(newHalf, new LinearLayout.LayoutParams(act.dp(28), act.dp(28)));
        header.addView(compare);
        card.addView(header);

        final int[] picked = {initial};
        final ColorPickerView picker = new ColorPickerView(act);
        picker.setColor(initial);
        picker.setEdgeColor((act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x66000000);
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, act.dp(236));
        pickLp.topMargin = act.dp(14);
        card.addView(picker, pickLp);

        // Hex field: type a code, or watch it follow the picker.
        final EditText hex = act.codeEditor.editorField("#RRGGBB");
        hex.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setTypeface(Typeface.MONOSPACE);
        final boolean[] syncing = {false};
        final Runnable showPicked = () -> {
            GradientDrawable nd = new GradientDrawable();
            nd.setColor(picked[0] | 0xFF000000);
            nd.setCornerRadii(new float[]{0, 0, rr, rr, rr, rr, 0, 0});
            newHalf.setBackground(nd);
        };
        showPicked.run();
        hex.setText(String.format("#%06X", initial & 0xFFFFFF));
        picker.setListener(argb -> {
            picked[0] = argb;
            showPicked.run();
            syncing[0] = true;
            hex.setText(String.format("#%06X", argb & 0xFFFFFF));
            syncing[0] = false;
        });
        hex.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (syncing[0]) return;
                String v = e.toString().trim().replace("#", "");
                if (!v.matches("[0-9a-fA-F]{6}")) return;
                picked[0] = 0xFF000000 | Integer.parseInt(v, 16);
                picker.setColor(picked[0]);
                showPicked.run();
            }
        });
        LinearLayout.LayoutParams hexLp = MainActivity.matchWrap();
        hexLp.topMargin = act.dp(12);
        card.addView(hex, hexLp);

        // Presets.
        LinearLayout presets = new LinearLayout(act);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER_VERTICAL);
        for (final int c : PICKER_PRESETS) {
            View sw = colorPanelSwatch(c, false);
            sw.setContentDescription(String.format("#%06X", c & 0xFFFFFF));
            sw.setOnClickListener(v -> {
                picked[0] = c;
                picker.setColor(c);
                showPicked.run();
                syncing[0] = true;
                hex.setText(String.format("#%06X", c & 0xFFFFFF));
                syncing[0] = false;
            });
            presets.addView(sw, new LinearLayout.LayoutParams(0, act.dp(24), 1f));
            View gap = new View(act);
            if (c != PICKER_PRESETS[PICKER_PRESETS.length - 1]) {
                presets.addView(gap, new LinearLayout.LayoutParams(act.dp(6), 1));
            }
        }
        LinearLayout.LayoutParams preLp = MainActivity.matchWrap();
        preLp.topMargin = act.dp(12);
        card.addView(presets, preLp);

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, act.dp(16), 0, 0);
        TextView cancel = new TextView(act);
        cancel.setText("Cancel");
        cancel.setTextColor(act.M3_PRIMARY);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10));
        cancel.setBackground(act.withHoverRipple(null, false));
        buttons.addView(cancel);
        TextView apply = new TextView(act);
        apply.setText("Use");
        apply.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
        apply.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        apply.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        apply.setPadding(act.dp(22), act.dp(10), act.dp(22), act.dp(10));
        GradientDrawable ab = new GradientDrawable();
        ab.setCornerRadius(act.dp(999));
        ab.setColor(act.M3_PRIMARY_CONTAINER);
        apply.setBackground(act.withHoverRipple(ab, false));
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyLp.leftMargin = act.dp(MainActivity.SPACE_SM);
        buttons.addView(apply, applyLp);
        card.addView(buttons);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                act.cardWidth(340), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(card, cardLp);
        overlay.setTranslationZ(act.dp(act.zenMode ? MainActivity.ZEN_LIFT_DP + 60 : 60));
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final Runnable close = () -> {
            act.hideSoftKeyboard();
            if (overlay.getParent() != null) act.rootLayout.removeView(overlay);
        };
        overlay.setOnClickListener(v -> close.run());
        cancel.setOnClickListener(v -> close.run());
        apply.setOnClickListener(v -> {
            close.run();
            onUse.accept(picked[0] | 0xFF000000);
        });
    }
}
