package me.hapke.inkside;

import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Page styling: paper colour and ruling of the open document.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class PageStyleMenu {
    private final MainActivity act;

    PageStyleMenu(MainActivity act) {
        this.act = act;
    }

    void showPageStyleMenu() {
        if (act.rootLayout == null || act.canvas == null) return;
        if (act.canvas.getDocumentPath().isEmpty()) {
            act.conversations.appendChat("warn", "open a document first");
            return;
        }
        // Paper and ruling are drawn over the page, so on an imported PDF they would
        // sit on top of whatever the document already has. Only pages this app made
        // are blank enough to style.
        if (!act.canvas.canStylePages()) {
            act.snackbar("Page styling is for PDFs created in the app — this one was imported", true);
            return;
        }
        final int page = act.canvas.getCurrentPageIndex();
        final int pageCount = act.canvas.getDocumentPageCount();
        final int[] paper = {act.canvas.getPageBackgroundAt(page)};
        final String[] style = {act.canvas.getPageStyleAt(page)};
        final float[] ruleScale = {act.canvas.getPageRuleScale()};
        final CodeCanvasView.PageLookScope[] scope = {CodeCanvasView.PageLookScope.CURRENT};
        final int themePaper = act.canvas.getThemePaperColor();

        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setBackgroundColor(0x99000000);
        final Runnable close = () -> {
            if (overlay.getParent() != null) act.rootLayout.removeView(overlay);
        };
        overlay.setOnClickListener(v -> close.run());

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_XL));
        act.settingsPanel.applyOptionsCardSurface(card);
        card.setElevation(act.dp(6));
        card.setClickable(true);
        card.setOnClickListener(v -> {});

        // Header: title, which page, close.
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(act);
        title.setText("Page style");
        title.setTextColor(act.M3_ON_SURFACE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        titles.addView(title);
        TextView subtitle = new TextView(act);
        subtitle.setText("Page " + (page + 1) + " of " + Math.max(pageCount, page + 1));
        subtitle.setTextColor(act.M3_ON_SURFACE_VARIANT);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView closeBtn = new ImageView(act);
        closeBtn.setImageResource(R.drawable.ic_close);
        closeBtn.setColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN);
        closeBtn.setScaleType(ImageView.ScaleType.CENTER);
        closeBtn.setContentDescription("Close");
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        cbg.setColor(0x00000000);
        closeBtn.setBackground(act.withHoverRipple(cbg, true));
        closeBtn.setClickable(true);
        closeBtn.setOnClickListener(v -> close.run());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(act.dp(48), act.dp(48));
        clp.gravity = Gravity.TOP;
        header.addView(closeBtn, clp);
        card.addView(header, MainActivity.matchWrap());

        // Body: the live page on the left, choices on the right.
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), 0);

        final PagePreviewView preview = new PagePreviewView(act);
        preview.setEdgeColor((act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x99000000);
        preview.setElevation(act.dp(3));
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), act.dp(10));
            }
        });
        final Runnable updatePreview = () -> preview.setLook(
                paper[0] != 0 ? paper[0] : themePaper, style[0], ruleScale[0]);
        updatePreview.run();
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                act.dp(170), Math.round(act.dp(170) * PdfDocumentIo.A4_HEIGHT_PT
                        / (float) PdfDocumentIo.A4_WIDTH_PT));
        plp.rightMargin = act.dp(MainActivity.SPACE_XL);
        plp.gravity = Gravity.TOP;
        body.addView(preview, plp);

        final LinearLayout controls = new LinearLayout(act);
        controls.setOrientation(LinearLayout.VERTICAL);
        body.addView(controls, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(body, MainActivity.matchWrap());

        // Spacing lives outside the rebuilt controls so the slider is not torn down
        // under the finger; it folds away for blank pages.
        final LinearLayout spacing = act.penTools.penSettingsSliderRow(
                "Spacing",
                ruleScaleToProgress(ruleScale[0]),
                p -> {
                    ruleScale[0] = progressToRuleScale(p);
                    updatePreview.run();
                },
                null);
        spacing.setPadding(0, act.dp(MainActivity.SPACE_MD), 0, 0);

        final Runnable[] rebuild = new Runnable[1];
        final String[] animate = {null};
        rebuild[0] = () -> {
            controls.removeAllViews();
            controls.addView(pageStyleLabel("Paper", PAGE_PAPER_NAMES[paperIndex(paper[0])]));
            controls.addView(pageStyleSwatchRow(paper, themePaper, animate, () -> {
                updatePreview.run();
                rebuild[0].run();
            }));
            controls.addView(pageStyleLabel("Lines", null));
            controls.addView(pageRulingTiles(style, paper[0] != 0 ? paper[0] : themePaper,
                    animate, () -> {
                        updatePreview.run();
                        if (DocumentPages.STYLE_BLANK.equals(style[0])) Motion.collapse(spacing);
                        else Motion.expand(spacing);
                        rebuild[0].run();
                    }));
            if (spacing.getParent() != null) ((ViewGroup) spacing.getParent()).removeView(spacing);
            controls.addView(spacing, MainActivity.matchWrap());
        };
        rebuild[0].run();
        spacing.setVisibility(DocumentPages.STYLE_BLANK.equals(style[0]) ? View.GONE : View.VISIBLE);

        // Where it goes, then one Apply.
        TextView applyLabel = pageStyleLabel("Apply to", null);
        applyLabel.setPadding(0, act.dp(MainActivity.SPACE_XL), 0, act.dp(MainActivity.SPACE_SM));
        card.addView(applyLabel);
        final String[] scopeLabels = {"This page", "All pages", "New pages"};
        final CodeCanvasView.PageLookScope[] scopes = {
                CodeCanvasView.PageLookScope.CURRENT,
                CodeCanvasView.PageLookScope.ALL,
                CodeCanvasView.PageLookScope.DEFAULT,
        };
        final FrameLayout scopeHolder = new FrameLayout(act);
        final Runnable[] buildScope = new Runnable[1];
        buildScope[0] = () -> {
            scopeHolder.removeAllViews();
            int sel = 0;
            for (int i = 0; i < scopes.length; i++) if (scopes[i] == scope[0]) sel = i;
            scopeHolder.addView(act.settingsPanel.optionsSegmentRow(scopeLabels, sel, i -> {
                scope[0] = scopes[i];
                buildScope[0].run();
            }));
        };
        buildScope[0].run();
        LinearLayout.LayoutParams slp = MainActivity.matchWrap();
        slp.rightMargin = act.dp(MainActivity.SPACE_MD);
        card.addView(scopeHolder, slp);

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_MD), 0);
        TextView cancel = new TextView(act);
        cancel.setText("Cancel");
        cancel.setTextColor(act.M3_PRIMARY);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_LG), 0);
        GradientDrawable xb = new GradientDrawable();
        xb.setCornerRadius(act.dp(999));
        xb.setColor(0x00000000);
        cancel.setBackground(act.withHoverRipple(xb, false));
        cancel.setOnClickListener(v -> close.run());
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(40));
        xlp.rightMargin = act.dp(MainActivity.SPACE_SM);
        buttons.addView(cancel, xlp);
        TextView apply = new TextView(act);
        apply.setText("Apply");
        apply.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
        apply.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        apply.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        apply.setGravity(Gravity.CENTER);
        apply.setPadding(act.dp(MainActivity.SPACE_XL), 0, act.dp(MainActivity.SPACE_XL), 0);
        GradientDrawable apb = new GradientDrawable();
        apb.setCornerRadius(act.dp(999));
        apb.setColor(act.M3_PRIMARY_CONTAINER);
        apply.setBackground(act.withHoverRipple(apb, false));
        apply.setOnClickListener(v -> {
            act.canvas.applyPageLook(paper[0], style[0], ruleScale[0], scope[0]);
            act.persistence.scheduleSave();
            close.run();
        });
        buttons.addView(apply, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(40)));
        card.addView(buttons, MainActivity.matchWrap());

        android.widget.ScrollView scroller = new android.widget.ScrollView(act);
        scroller.addView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                act.cardWidth(560), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = act.dp(MainActivity.SPACE_XL);
        cardLp.rightMargin = act.dp(MainActivity.SPACE_XL);
        overlay.addView(scroller, cardLp);
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static int paperIndex(int value) {
        for (int i = 0; i < PAGE_PAPERS.length; i++) if (PAGE_PAPERS[i] == value) return i;
        return 0;
    }

    /** Section label with an optional value after it ("Paper · Cream"). */
    private TextView pageStyleLabel(String label, String value) {
        TextView v = new TextView(act);
        v.setText(value != null ? label + " · " + value : label);
        v.setTextColor(act.M3_ON_SURFACE_VARIANT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, act.dp(MainActivity.SPACE_SM), 0, act.dp(MainActivity.SPACE_SM));
        return v;
    }

    /**
     * Paper swatches that write into {@code paper[0]}: circles with a check on the
     * chosen one; "Default" shows the paper a page has with no look chosen, with a small palette mark.
     */
    private LinearLayout pageStyleSwatchRow(int[] paper, int themePaper, String[] animate,
                                            Runnable onPick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, act.dp(MainActivity.SPACE_SM));
        for (int i = 0; i < PAGE_PAPERS.length; i++) {
            final int value = PAGE_PAPERS[i];
            final String key = "paper" + i;
            boolean selected = value == paper[0];
            int fill = (value == 0 ? themePaper : value) | 0xFF000000;
            boolean light = DocumentPages.ruleColor(fill) == 0x2A000000;

            FrameLayout swatch = new FrameLayout(act);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(fill);
            bg.setStroke(act.dp(selected ? 3 : 1),
                    selected ? act.M3_PRIMARY : (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0xAA000000);
            swatch.setBackground(bg);
            if (selected || value == 0) {
                ImageView mark = new ImageView(act);
                mark.setImageResource(selected ? R.drawable.ic_check : R.drawable.ic_palette);
                mark.setColorFilter(light ? 0xCC000000 : 0xE6FFFFFF, PorterDuff.Mode.SRC_IN);
                FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(act.dp(18), act.dp(18));
                mlp.gravity = Gravity.CENTER;
                swatch.addView(mark, mlp);
                if (selected && key.equals(animate[0])) {
                    animate[0] = null;
                    Motion.pop(swatch);
                    mark.setScaleX(0f);
                    mark.setScaleY(0f);
                    mark.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                            .setInterpolator(Motion.LAND).start();
                }
            }
            swatch.setClickable(true);
            swatch.setContentDescription(PAGE_PAPER_NAMES[i] + " paper");
            swatch.setOnClickListener(v -> {
                if (paper[0] == value) return;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK);
                paper[0] = value;
                animate[0] = key;
                onPick.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(act.dp(40), act.dp(40));
            if (i > 0) lp.leftMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(swatch, lp);
        }
        return row;
    }

    /** One tile per ruling, each a zoomed-in piece of paper showing it, named underneath. */
    private LinearLayout pageRulingTiles(String[] style, int paperColor, String[] animate,
                                         Runnable onPick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PAGE_STYLES.length; i++) {
            final String id = PAGE_STYLES[i][0];
            boolean on = id.equals(style[0]);

            LinearLayout tile = new LinearLayout(act);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS));

            FrameLayout frame = new FrameLayout(act);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(act.dp(14));
            ring.setColor(0x00000000);
            ring.setStroke(act.dp(on ? 3 : 1),
                    on ? act.M3_PRIMARY : (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0xAA000000);
            frame.setForeground(ring);
            frame.setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3));
            PagePreviewView swatch = new PagePreviewView(act);
            swatch.setUnitsAcross(150f);
            swatch.setCornerDp(11f);
            swatch.setEdgeColor(0x00000000);
            swatch.setLook(paperColor, id, 1f);
            frame.addView(swatch, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tile.addView(frame, new LinearLayout.LayoutParams(act.dp(56), act.dp(56)));
            if (on && ("style" + id).equals(animate[0])) {
                animate[0] = null;
                Motion.pop(frame);
            }

            TextView label = new TextView(act);
            label.setText(PAGE_STYLES[i][1]);
            label.setTextColor(on ? act.M3_ON_SURFACE : act.M3_ON_SURFACE_VARIANT);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            label.setPadding(0, act.dp(MainActivity.SPACE_XS), 0, 0);
            label.setGravity(Gravity.CENTER);
            tile.addView(label, MainActivity.matchWrap());

            tile.setClickable(true);
            GradientDrawable rb = new GradientDrawable();
            rb.setCornerRadius(act.dp(14));
            rb.setColor(0x00000000);
            tile.setBackground(act.withHoverRipple(rb, false));
            tile.setOnClickListener(v -> {
                if (id.equals(style[0])) return;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK);
                style[0] = id;
                animate[0] = "style" + id;
                onPick.run();
            });
            row.addView(tile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    /** id, label */
    /** Ruling size slider maps 0..100 onto the document's allowed spacing range. */
    private static int ruleScaleToProgress(float scale) {
        float span = DocumentPages.RULE_SCALE_MAX - DocumentPages.RULE_SCALE_MIN;
        float f = (DocumentPages.clampRuleScale(scale) - DocumentPages.RULE_SCALE_MIN) / span;
        return Math.round(f * 100f);
    }

    private static float progressToRuleScale(int progress) {
        float span = DocumentPages.RULE_SCALE_MAX - DocumentPages.RULE_SCALE_MIN;
        return DocumentPages.RULE_SCALE_MIN + (progress / 100f) * span;
    }

    private static final String[][] PAGE_STYLES = {
            {"blank", "Blank"}, {"lines", "Lines"}, {"grid", "Grid"}, {"dots", "Dots"},
    };
    /** 0 = the default: the app theme for a document the app made, the PDF as is for an imported one. */
    private static final int[] PAGE_PAPERS = {
            0, 0xFFFFFFFF, 0xFFFBF3E0, 0xFFE8F0E6, 0xFF2A2A2E, 0xFF12131A,
    };
    private static final String[] PAGE_PAPER_NAMES = {
            "Default", "White", "Cream", "Mint", "Slate", "Ink",
    };
}
