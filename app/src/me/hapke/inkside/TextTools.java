package me.hapke.inkside;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Text boxes: default style row, font / colour / bold / italic of the selected box, inline editing and LaTeX rendering.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class TextTools {
    private final MainActivity act;

    private TextView textDefFontChip;
    private TextView textDefBoldChip;
    private TextView textDefItalicChip;
    private TextView textDefSizeLabel;
    private final List<View> textDefSwatches = new ArrayList<>();

    TextTools(MainActivity act) {
        this.act = act;
    }

    LinearLayout buildTextDefaultsRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        textDefFontChip = miniChip("Sans", () -> {
            int i = 0;
            for (int k = 0; k < FONT_FAMILIES.length; k++) {
                if (FONT_FAMILIES[k].equals(CanvasTextField.newFamily)) i = k;
            }
            CanvasTextField.newFamily = FONT_FAMILIES[(i + 1) % FONT_FAMILIES.length];
            refreshTextDefaultsRow();
            act.persistence.scheduleSave();
        });
        row.addView(textDefFontChip);
        textDefBoldChip = miniChip("B", () -> {
            CanvasTextField.newStyle ^= Typeface.BOLD;
            refreshTextDefaultsRow();
            act.persistence.scheduleSave();
        });
        row.addView(textDefBoldChip);
        textDefItalicChip = miniChip("I", () -> {
            CanvasTextField.newStyle ^= Typeface.ITALIC;
            refreshTextDefaultsRow();
            act.persistence.scheduleSave();
        });
        row.addView(textDefItalicChip);
        textDefSwatches.clear();
        for (int i = 0; i <= MainActivity.INK_COLORS.length; i++) {
            final int slot = i;
            View sw = act.roundSwatch(textSwatchColor(slot), false);
            sw.setContentDescription(slot == 0 ? "Theme text colour" : "Text colour " + slot);
            sw.setOnClickListener(v -> {
                Runnable single = () -> {
                    CanvasTextField.newColor = slot == 0 ? 0 : textSwatchColor(slot);
                    refreshTextDefaultsRow();
                    act.persistence.scheduleSave();
                };
                if (slot == 0) single.run();
                else act.swatchTap(v, slot - 1, single, c -> {
                    CanvasTextField.newColor = c;
                    refreshTextDefaultsRow();
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(act.dp(MainActivity.SWATCH_SIZE), act.dp(MainActivity.SWATCH_SIZE));
            lp.setMargins(act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_SM), 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(sw, lp);
            textDefSwatches.add(sw);
        }
        SeekBar size = new Material3Slider(act);
        size.setMin((int) CanvasTextField.MIN_TEXT_SIZE);
        size.setMax(72);
        size.setProgress(Math.round(CanvasTextField.newSize));
        size.setContentDescription("Default text size");
        act.tintSeekBar(size);
        size.setOnTouchListener((v, event) -> {
            ViewParent parent = v.getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
                parent = parent.getParent();
            }
            return false;
        });
        textDefSizeLabel = new TextView(act);
        textDefSizeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        textDefSizeLabel.setTextColor(act.M3_ON_SURFACE_VARIANT);
        textDefSizeLabel.setMinWidth(act.dp(22));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                CanvasTextField.newSize = Math.max(CanvasTextField.MIN_TEXT_SIZE, progress);
                refreshTextDefaultsRow();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { act.persistence.scheduleSave(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(act.dp(110), act.dp(36));
        slp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(size, slp);
        row.addView(textDefSizeLabel);
        textDefSizeBar = size;
        refreshTextDefaultsRow();
        return row;
    }

    private SeekBar textDefSizeBar;

    void refreshTextDefaultsRow() {
        if (act.textDefaultsOptions == null && textDefFontChip == null) return;
        if (textDefFontChip != null) {
            String label = FONT_LABELS[0];
            for (int k = 0; k < FONT_FAMILIES.length; k++) {
                if (FONT_FAMILIES[k].equals(CanvasTextField.newFamily)) label = FONT_LABELS[k];
            }
            textDefFontChip.setText(label);
        }
        styleToggleChip(textDefBoldChip, (CanvasTextField.newStyle & Typeface.BOLD) != 0);
        styleToggleChip(textDefItalicChip, (CanvasTextField.newStyle & Typeface.ITALIC) != 0);
        for (int i = 0; i < textDefSwatches.size(); i++) {
            int c = textSwatchColor(i);
            boolean on = i == 0 ? CanvasTextField.newColor == 0 : CanvasTextField.newColor == c;
            act.applyRoundStyle(textDefSwatches.get(i), c, on);
        }
        if (textDefSizeLabel != null) textDefSizeLabel.setText(String.valueOf(Math.round(CanvasTextField.newSize)));
        if (textDefSizeBar != null) textDefSizeBar.setProgress(Math.round(CanvasTextField.newSize));
    }

    /** On = filled primary container; off = outlined, like a Material filter chip. */
    private void styleToggleChip(TextView chip, boolean on) {
        if (chip == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(999));
        if (on) {
            bg.setColor(act.M3_PRIMARY_CONTAINER);
            chip.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(act.dp(1), act.M3_OUTLINE_VARIANT);
            chip.setTextColor(act.M3_ON_SURFACE_VARIANT);
        }
        chip.setBackground(act.withHoverRipple(bg, false));
    }

    int textSwatchColor(int slot) {
        return slot == 0 ? CanvasTextField.defaultTextColor()
                : act.pens[CodeCanvasView.BRUSH_INK].slots[slot - 1];
    }

    /** Recolour the text style swatches (palette may have changed) and mark the current one. */
    void refreshTextColorSwatches(CanvasTextField tf) {
        for (int i = 0; i < act.textColorSwatches.size(); i++) {
            int c = textSwatchColor(i);
            act.applyRoundStyle(act.textColorSwatches.get(i), c, tf != null && tf.color == c);
        }
    }

    CanvasTextField textFieldForStyleUi() {
        if (act.canvas == null) return null;
        if (act.canvas.isEditingTextField()) {
            CanvasTextField ed = act.canvas.getEditingTextField();
            if (ed != null && !ed.contentBlock) return ed;
        }
        CanvasTextField sole = act.canvas.getSoleSelectedTextField();
        if (sole != null && !sole.contentBlock) return sole;
        return null;
    }

    /** The text tool's option row (font, B, I, colours, size) takes the current theme. */
    void applyThemeToDefaultsRow() {
        if (textDefFontChip != null) {
            textDefFontChip.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(act.dp(999));
            bg.setColor(act.M3_PRIMARY_CONTAINER);
            textDefFontChip.setBackground(bg);
        }
        refreshTextDefaultsRow();
        if (textDefSizeLabel != null) textDefSizeLabel.setTextColor(act.M3_ON_SURFACE_VARIANT);
        if (textDefSizeBar != null) act.tintSeekBar(textDefSizeBar);
    }

    void refreshTextStyleChips() {
        if (act.textStyleInner == null) return;
        for (int i = 0; i < act.textStyleInner.getChildCount(); i++) {
            View child = act.textStyleInner.getChildAt(i);
            if (child instanceof TextView && !(child instanceof EditText)) {
                TextView t = (TextView) child;
                t.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(act.dp(999));
                bg.setColor(act.M3_PRIMARY_CONTAINER);
                t.setBackground(bg);
            }
        }
    }

    void requestLatexRenderForField(String id) {
        if (act.latexRenderer == null || act.canvas == null || id == null) return;
        CanvasTextField tf = act.canvas.findTextField(id);
        if (tf == null) return;
        if (!tf.wantsRender()) return;
        final String text = tf.text;
        if (text == null || text.isEmpty()) return;
        // Plain text renders in its own colour, no card; content blocks keep the theme's.
        LatexRenderer.Callback done = new LatexRenderer.Callback() {
            @Override
            public void onRendered(Bitmap bitmap, float worldPerPx) {
                CanvasTextField current = act.canvas.findTextField(id);
                if (current == null || !text.equals(current.text)) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
                act.canvas.setTextFieldLatexBitmap(id, bitmap, worldPerPx);
                act.persistence.scheduleSave();
            }

            @Override
            public void onFailed() {
                // Plaintext fallback is drawn by CanvasTextField when no bitmap is set.
            }
        };
        if (tf.contentBlock) {
            act.latexRenderer.renderCard(text, done);
        } else {
            // A box the user sized keeps its width; otherwise wrap like a dropped text box.
            float maxW = tf.userSized ? tf.width : CodeCanvasView.DROPPED_TEXT_MAX_W;
            act.latexRenderer.renderPlain(text, tf.color, tf.textSize, maxW, done);
        }
    }

    private void placeTextFieldAtCenter() {
        if (act.canvas == null) return;
        float[] c = act.canvas.getViewCenterWorld();
        act.canvas.addTextField(c[0], c[1]);
        act.persistence.scheduleSave();
    }

    TextView miniChip(String label, Runnable onClick) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(999));
        bg.setColor(act.M3_PRIMARY_CONTAINER);
        t.setBackground(bg);
        t.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, act.dp(MainActivity.SPACE_XS), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        t.setLayoutParams(lp);
        return t;
    }

    private static final String[] FONT_FAMILIES = {"sans", "serif", "mono"};
    private static final String[] FONT_LABELS = {"Sans", "Serif", "Mono"};

    void cycleSelectedTextFont() {
        if (act.canvas == null) return;
        CanvasTextField tf = textFieldForStyleUi();
        if (tf == null) return;
        int next = 0;
        for (int i = 0; i < FONT_FAMILIES.length; i++) {
            if (FONT_FAMILIES[i].equals(tf.fontFamily)) {
                next = (i + 1) % FONT_FAMILIES.length;
                break;
            }
        }
        act.canvas.updateTextFieldStyle(
                tf.id, FONT_FAMILIES[next], tf.typefaceStyle, tf.textSize, tf.color);
        refreshFontCycleChip();
        if (act.inlineEditor != null && act.inlineEditor.isActive()) act.inlineEditor.refreshStyle();
        act.persistence.scheduleSave();
    }

    /** Keeps the cycle chip showing the selected field's current family. */
    void refreshFontCycleChip() {
        if (act.fontCycleChip == null) return;
        String family = "sans";
        CanvasTextField tf = textFieldForStyleUi();
        if (tf != null && tf.fontFamily != null) family = tf.fontFamily;
        for (int i = 0; i < FONT_FAMILIES.length; i++) {
            if (FONT_FAMILIES[i].equals(family)) {
                act.fontCycleChip.setText(FONT_LABELS[i]);
                return;
            }
        }
        act.fontCycleChip.setText(FONT_LABELS[0]);
    }

    void applySelectedTextColor(int color) {
        if (act.canvas == null) return;
        CanvasTextField tf = textFieldForStyleUi();
        if (tf == null) return;
        act.canvas.updateTextFieldStyle(tf.id, tf.fontFamily, tf.typefaceStyle, tf.textSize, color);
        if (act.inlineEditor != null && act.inlineEditor.isActive()) act.inlineEditor.refreshStyle();
        refreshTextColorSwatches(tf);
        act.persistence.scheduleSave();
    }

    void toggleSelectedTextBold() {
        if (act.canvas == null) return;
        CanvasTextField tf = textFieldForStyleUi();
        if (tf == null) return;
        int s = tf.typefaceStyle;
        boolean bold = (s & Typeface.BOLD) != 0;
        boolean italic = (s & Typeface.ITALIC) != 0;
        int next = Typeface.NORMAL;
        if (!bold && italic) next = Typeface.BOLD_ITALIC;
        else if (!bold) next = Typeface.BOLD;
        else if (italic) next = Typeface.ITALIC;
        act.canvas.updateTextFieldStyle(tf.id, tf.fontFamily, next, tf.textSize, tf.color);
        if (act.inlineEditor != null && act.inlineEditor.isActive()) act.inlineEditor.refreshStyle();
        act.persistence.scheduleSave();
    }

    void toggleSelectedTextItalic() {
        if (act.canvas == null) return;
        CanvasTextField tf = textFieldForStyleUi();
        if (tf == null) return;
        int s = tf.typefaceStyle;
        boolean bold = (s & Typeface.BOLD) != 0;
        boolean italic = (s & Typeface.ITALIC) != 0;
        int next = Typeface.NORMAL;
        if (bold && !italic) next = Typeface.BOLD_ITALIC;
        else if (!bold && !italic) next = Typeface.ITALIC;
        else if (bold) next = Typeface.BOLD;
        act.canvas.updateTextFieldStyle(tf.id, tf.fontFamily, next, tf.textSize, tf.color);
        if (act.inlineEditor != null && act.inlineEditor.isActive()) act.inlineEditor.refreshStyle();
        act.persistence.scheduleSave();
    }

    /** Opens the text field for editing in place on the canvas. */
    void openTextFieldEditor(String id) {
        if (act.canvas == null || id == null || act.centerPane == null) return;
        CanvasTextField existing = act.canvas.findTextField(id);
        if (existing == null || existing.contentBlock) return;
        if (act.inlineEditor == null) {
            act.inlineEditor = new InlineTextEditor(
                    act, (ViewGroup) act.centerPane, act.canvas, new InlineTextEditor.Host() {
                @Override
                public void onInlineCommit(String fieldId, String text) {
                    if (act.isDead() || act.canvas == null) return;
                    // Live edits already applied the text; this settles layout and LaTeX.
                    act.canvas.setTextFieldTextLive(fieldId, text);
                    CanvasTextField tf = act.canvas.findTextField(fieldId);
                    if (tf != null && tf.wantsRender()) {
                        requestLatexRenderForField(fieldId);
                    }
                    act.persistence.scheduleSave();
                    act.refreshSelectionActions(act.canvas.hasActiveSelection());
                }

                @Override
                public void onInlineTextChanged(String fieldId, String text) {
                    if (act.isDead() || act.canvas == null) return;
                    act.canvas.setTextFieldTextLive(fieldId, text);
                }
            });
        }
        act.inlineEditor.begin(id);
        act.refreshSelectionActions(act.canvas.hasActiveSelection());
    }

    /** Commits any in-place edit; call before actions that move or replace the field. */
    void commitInlineEdit() {
        if (act.inlineEditor != null && act.inlineEditor.isActive()) act.inlineEditor.dismiss(true);
        act.refreshSelectionActions(act.canvas != null && act.canvas.hasActiveSelection());
    }
}
