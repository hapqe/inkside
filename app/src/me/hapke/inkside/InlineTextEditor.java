package me.hapke.inkside;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * In-place editor for a canvas text field.
 *
 * <p>Rather than reimplementing a caret and IME inside the canvas, this overlays a real
 * {@link EditText} on top of the field's screen rect and keeps it pinned there as the
 * canvas pans and zooms. That inherits selection handles, autocorrect, clipboard and
 * stylus handwriting support for free.
 */
final class InlineTextEditor {

    interface Host {
        /** Commit the edited text to the model and persist. */
        void onInlineCommit(String fieldId, String text);

        /** Text changed mid-edit — reflow the field without taking an undo step. */
        void onInlineTextChanged(String fieldId, String text);
    }

    private final Context context;
    private final ViewGroup parent;
    private final CodeCanvasView canvas;
    private final Host host;

    private EditText input;
    private String fieldId;
    private boolean committing;
    private float lastTextPx = -1f;

    InlineTextEditor(Context context, ViewGroup parent, CodeCanvasView canvas, Host host) {
        this.context = context;
        this.parent = parent;
        this.canvas = canvas;
        this.host = host;
    }

    /** The live input while editing (dictation writes here), else null. */
    EditText input() {
        return isActive() ? input : null;
    }

    /** Id of the text field being edited, or null. */
    String fieldId() {
        return isActive() ? fieldId : null;
    }

    boolean isActive() {
        return input != null && fieldId != null;
    }

    String activeFieldId() {
        return fieldId;
    }

    void begin(String id) {
        CanvasTextField tf = canvas.findTextField(id);
        if (tf == null) return;
        if (isActive()) {
            if (id.equals(fieldId)) return;
            dismiss(true);
        }

        // One undo step for the whole session, taken before the first keystroke.
        canvas.recordTextFieldEditUndo();

        fieldId = id;
        lastTextPx = -1f;
        canvas.setEditingTextField(id);

        input = new EditText(context);
        // The overlay must be invisible: the canvas text it replaces has no chrome.
        input.setBackground(null);
        input.setPadding(0, 0, 0, 0);
        input.setMinWidth(0);
        input.setMinHeight(0);
        input.setMinimumWidth(0);
        input.setMinimumHeight(0);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(tf.text);
        input.setSelection(tf.text != null ? tf.text.length() : 0);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        applyStyle(tf);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (fieldId == null || committing) return;
                host.onInlineTextChanged(fieldId, s.toString());
                // The box reflows as text grows, so track its new rect.
                reposition();
            }
        });

        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) dismiss(true);
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(10, 10);
        lp.gravity = Gravity.TOP | Gravity.START;
        parent.addView(input, lp);
        reposition();

        input.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Re-reads the field's style, for when it changes while the editor is open. */
    void refreshStyle() {
        if (!isActive()) return;
        CanvasTextField tf = canvas.findTextField(fieldId);
        if (tf == null) return;
        applyStyle(tf);
        lastTextPx = -1f;
        reposition();
    }

    private void applyStyle(CanvasTextField tf) {
        if (input == null) return;
        Typeface base;
        if ("serif".equals(tf.fontFamily)) base = Typeface.SERIF;
        else if ("mono".equals(tf.fontFamily)) base = Typeface.MONOSPACE;
        else base = Typeface.SANS_SERIF;
        input.setTypeface(Typeface.create(base, tf.typefaceStyle));
        input.setTextColor(tf.color);
        // Must mirror CanvasTextField.ensureLayout, or the committed text reflows.
        input.setIncludeFontPadding(false);
        input.setLineSpacing(0f, 1.15f);
        input.setPaintFlags(input.getPaintFlags() | android.graphics.Paint.SUBPIXEL_TEXT_FLAG
                | android.graphics.Paint.LINEAR_TEXT_FLAG);
        input.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        input.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        input.setFontFeatureSettings(null);
        input.setLetterSpacing(0f);
    }

    /** Pins the overlay to the field's current screen rect; call on every pan/zoom. */
    void reposition() {
        if (!isActive()) return;
        CanvasTextField tf = canvas.findTextField(fieldId);
        RectF r = canvas.getTextFieldScreenRect(fieldId);
        if (tf == null || r == null) {
            dismiss(true);
            return;
        }

        float scale = canvas.getViewScale();
        // Same inset CanvasTextField.draw uses, in world units.
        float padX = tf.insetX() * scale;
        float padY = tf.insetY() * scale;

        // The editor lays its text out at world size and is scaled by the zoom, exactly like
        // the canvas draws it, so line breaks and line spacing cannot differ between the
        // two (laying out at screen size rounds the line heights differently).
        if (Math.abs(tf.textSize - lastTextPx) > 0.01f) {
            input.setTextSize(TypedValue.COMPLEX_UNIT_PX, tf.textSize);
            lastTextPx = tf.textSize;
        }
        input.setPivotX(0f);
        input.setPivotY(0f);
        input.setScaleX(scale);
        input.setScaleY(scale);

        int left = Math.round(r.left + padX);
        int top = Math.round(r.top + padY);
        int w = Math.max(1, Math.round(tf.width - tf.insetX() * 2f));
        int h = Math.max(1, Math.round(tf.height - tf.insetY() * 2f));

        ViewGroup.LayoutParams base = input.getLayoutParams();
        if (base instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) base;
            if (lp.leftMargin != left || lp.topMargin != top
                    || lp.width != w || lp.height != h) {
                lp.leftMargin = left;
                lp.topMargin = top;
                lp.width = w;
                lp.height = h;
                input.setLayoutParams(lp);
            }
        }
        // Off-screen scroll would otherwise leave a stray caret floating over the canvas.
        boolean visible = r.right > 0 && r.bottom > 0
                && r.left < canvas.getWidth() && r.top < canvas.getHeight();
        input.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Tears the overlay down.
     *
     * @param commit true to write the edited text back to the model, false to drop it
     */
    void dismiss(boolean commit) {
        if (input == null) {
            fieldId = null;
            canvas.setEditingTextField(null);
            return;
        }
        if (committing) return;
        committing = true;

        String id = fieldId;
        String text = input.getText() != null ? input.getText().toString() : "";

        input.setOnFocusChangeListener(null);
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        ViewGroup p = (ViewGroup) input.getParent();
        if (p != null) p.removeView(input);

        input = null;
        fieldId = null;
        canvas.setEditingTextField(null);
        committing = false;

        if (commit && id != null) host.onInlineCommit(id, text);
    }
}
