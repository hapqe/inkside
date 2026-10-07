package me.hapke.inkside;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Pens, brushes, sizes and the pen / eraser / lasso tool menus; tool selection and the stylus buttons.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class PenTools {
    private final MainActivity act;

    private ImageView toolExpandButton;

    private ValueAnimator toolOptionsAnim;

    private CodeCanvasView.Tool toolBeforeText = CodeCanvasView.Tool.PENCIL;
    private int inkBeforeText = 0;

    PenTools(MainActivity act) {
        this.act = act;
    }

    /**
     * Slider position to world-space nib width. The old curve (1.5 + p/100 * 14.5)
     * ran thick: mid-slider was 6.6px, which on a page is a marker rather than a pen.
     */
    static float thicknessForProgress(int progress) {
        int p = Math.max(0, Math.min(100, progress));
        return 1.2f + (p / 100f) * 10.8f;
    }

    private static final String[] INK_NAMES = {
            "blue", "green", "yellow", "red", "violet"
    };

    // ---- Pens: each brush keeps its own colours and properties ------------------

    /**
     * One pen's state. Every brush is its own pen: five colour slots chosen from the
     * shared {@link MainActivity#favoriteColors}, the slot in use, thickness, smoothing, pressure.
     */
    static final class PenState {
        final int[] slots = new int[5];
        int selected;
        /** Thickness slider position, 0..100 (see thicknessForProgress). */
        int thickness = 35;
        /** Three quick sizes (thickness slider positions) and the one in use. */
        final int[] sizes = {15, 35, 65};
        int sizeSlot = 1;
        float smoothing = 0.15f;
        float pressure = 1f;
    }

    PenState pen() {
        return act.pens[act.brush];
    }

    /**
     * Name a stroke keeps for its colour — used to talk about annotations ("blue
     * box"), so custom colours get the nearest plain colour word.
     */
    static String colorNameFor(int c) {
        for (int i = 0; i < MainActivity.INK_COLORS.length; i++) {
            if ((MainActivity.INK_COLORS[i] & 0xFFFFFF) == (c & 0xFFFFFF)) return INK_NAMES[i];
        }
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.15f) return hsv[2] > 0.85f ? "white" : hsv[2] < 0.2f ? "black" : "gray";
        float h = hsv[0];
        if (h < 15 || h >= 335) return "red";
        if (h < 40) return "orange";
        if (h < 65) return "yellow";
        if (h < 160) return "green";
        if (h < 195) return "cyan";
        if (h < 255) return "blue";
        if (h < 290) return "violet";
        return "pink";
    }

    String inkNameFor(int index) {
        int[] pal = palette();
        String color = index >= 0 && index < pal.length ? colorNameFor(pal[index]) : "ink";
        if (act.brush == CodeCanvasView.BRUSH_INK) return color;
        String kind = CodeCanvasView.BRUSH_KEYS[act.brush];
        return act.brush == CodeCanvasView.BRUSH_RAINBOW ? kind : color + " " + kind;
    }

    private boolean effectBrush() {
        return act.brush != CodeCanvasView.BRUSH_INK;
    }

    /** Swatch colours of the pen in use. */
    int[] palette() {
        return pen().slots;
    }

    /** The pen button: the pen's own brush (back from the highlighter, tape or shapes). */
    void selectPen() {
        if (!CodeCanvasView.isPenBrush(act.brush)) {
            setBrush(CodeCanvasView.isPenBrush(act.penBrush) ? act.penBrush : CodeCanvasView.BRUSH_INK);
        }
        selectPencil(act.selectedColorIndex);
    }

    /** The tape button: drag to lay tape over something; tap a tape to lift it. */
    void selectTape() {
        if (act.brush != CodeCanvasView.BRUSH_TAPE) setBrush(CodeCanvasView.BRUSH_TAPE);
        selectPencil(act.selectedColorIndex);
    }

    /** The shapes button: drag out the chosen shape; a tap places one. */
    void selectShape() {
        if (act.brush != CodeCanvasView.BRUSH_SHAPE) setBrush(CodeCanvasView.BRUSH_SHAPE);
        applyShapeStyle();
        selectPencil(act.selectedColorIndex);
    }

    /**
     * Where the stylus's primary button goes back to: the pen or the highlighter,
     * whichever was picked last (from tape or shapes too).
     */
    void selectWritingTool(boolean dropSelection) {
        int want = act.writingBrush == CodeCanvasView.BRUSH_HIGHLIGHTER
                ? CodeCanvasView.BRUSH_HIGHLIGHTER
                : (CodeCanvasView.isPenBrush(act.penBrush) ? act.penBrush : CodeCanvasView.BRUSH_INK);
        if (act.brush != want) setBrush(want);
        selectPencil(act.selectedColorIndex, dropSelection);
    }

    /**
     * The highlighter button. It is the pen with the highlighter brush and keeps its own
     * colours and size, like every brush; the stylus's primary button goes back to it
     * when it was the last drawing tool picked.
     */
    void selectHighlighter() {
        if (act.brush != CodeCanvasView.BRUSH_HIGHLIGHTER) setBrush(CodeCanvasView.BRUSH_HIGHLIGHTER);
        selectPencil(act.selectedColorIndex);
    }

    boolean highlighterOn() {
        return act.brush == CodeCanvasView.BRUSH_HIGHLIGHTER;
    }

    private void setBrush(int b) {
        if (act.brush == b || b < 0 || b >= act.pens.length) return;
        pen().selected = act.selectedColorIndex;
        act.brush = b;
        if (CodeCanvasView.isPenBrush(b)) act.penBrush = b;
        if (CodeCanvasView.isPenBrush(b) || b == CodeCanvasView.BRUSH_HIGHLIGHTER) act.writingBrush = b;
        if (act.canvas != null) act.canvas.setBrush(b);
        act.selectedColorIndex = pen().selected;
        applyPenProperties();
        selectPencil(act.selectedColorIndex, false);
        if (penMenuRefresh != null) penMenuRefresh.run();
        act.persistence.scheduleSave();
    }

    /** Puts the current pen's thickness, smoothing and pressure on the canvas and sliders. */
    void applyPenProperties() {
        PenState p = pen();
        if (act.canvas != null) {
            act.canvas.setBaseThicknessPx(thicknessForProgress(p.thickness));
            act.canvas.setStabilization(p.smoothing);
            act.canvas.setPressureSensitivity(p.pressure);
        }
        if (act.thicknessSeekBar != null) act.thicknessSeekBar.setProgress(p.thickness);
        if (act.stabilizationSeekBar != null) act.stabilizationSeekBar.setProgress(Math.round(p.smoothing * 100f));
        if (act.pressureSeekBar != null) act.pressureSeekBar.setProgress(Math.round(p.pressure * 100f));
        refreshSizeDots();
    }

    /** The swatch colour a brush would draw with right now. */
    private int previewColorFor(int b) {
        PenState p = act.pens[b];
        int idx = b == act.brush ? act.selectedColorIndex : p.selected;
        return p.slots[Math.max(0, Math.min(p.slots.length - 1, idx))];
    }

    /** Sessions from before pens kept their own state: two palettes and one set of properties. */
    void migrateOldPenState(JSONObject state) {
        JSONArray inkJson = state.optJSONArray("inkColors");
        JSONArray glowJson = state.optJSONArray("glowColors");
        int oldSel = Math.max(0, Math.min(4, state.optInt("selectedColorIndex", 0)));
        int oldOther = Math.max(0, Math.min(4, state.optInt("otherPaletteIndex", 0)));
        float px = (float) state.optDouble("baseThicknessPx", 4.5);
        int thick = Math.max(0, Math.min(100, Math.round((px - 1.2f) / 10.8f * 100f)));
        float smooth = (float) state.optDouble("stabilization", 0.15);
        float press = (float) state.optDouble("pressureSensitivity", 1.0);
        for (int b = 0; b < act.pens.length; b++) {
            PenState p = act.pens[b];
            JSONArray src = b == CodeCanvasView.BRUSH_INK ? inkJson : glowJson;
            if (src != null) {
                for (int k = 0; k < 5 && k < src.length(); k++) {
                    int c = src.optInt(k, 0);
                    if (c != 0) p.slots[k] = c;
                }
            }
            boolean isCurrentFamily = (b == CodeCanvasView.BRUSH_INK) == (act.brush == CodeCanvasView.BRUSH_INK);
            p.selected = isCurrentFamily ? oldSel : oldOther;
            if (b == CodeCanvasView.BRUSH_INK || b == act.brush) p.thickness = thick;
            p.smoothing = smooth;
            p.pressure = press;
            for (int c : p.slots) {
                if (!act.favoriteColors.contains(c)) act.favoriteColors.add(c);
            }
        }
    }

    JSONArray pensToJson() throws Exception {
        JSONArray arr = new JSONArray();
        pen().selected = act.selectedColorIndex;
        for (int b = 0; b < act.pens.length; b++) {
            PenState p = act.pens[b];
            JSONObject o = new JSONObject();
            o.put("brush", CodeCanvasView.BRUSH_KEYS[b]);
            JSONArray slots = new JSONArray();
            for (int c : p.slots) slots.put(c);
            o.put("slots", slots);
            o.put("selected", p.selected);
            o.put("thickness", p.thickness);
            JSONArray sizes = new JSONArray();
            for (int v : p.sizes) sizes.put(v);
            o.put("sizes", sizes);
            o.put("sizeSlot", p.sizeSlot);
            o.put("smoothing", p.smoothing);
            o.put("pressure", p.pressure);
            arr.put(o);
        }
        return arr;
    }

    void pensFromJson(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int b = CodeCanvasView.brushFromKey(o.optString("brush", ""));
            if (b == CodeCanvasView.BRUSH_INK && !"ink".equals(o.optString("brush", ""))) continue;
            PenState p = act.pens[b];
            JSONArray slots = o.optJSONArray("slots");
            if (slots != null) {
                for (int k = 0; k < p.slots.length && k < slots.length(); k++) {
                    int c = slots.optInt(k, 0);
                    if (c != 0) p.slots[k] = c;
                }
            }
            p.selected = Math.max(0, Math.min(p.slots.length - 1, o.optInt("selected", 0)));
            p.thickness = Math.max(0, Math.min(100, o.optInt("thickness", p.thickness)));
            JSONArray sizes = o.optJSONArray("sizes");
            if (sizes != null && sizes.length() == 3) {
                for (int k = 0; k < 3; k++) p.sizes[k] = Math.max(0, Math.min(100, sizes.optInt(k, p.sizes[k])));
                p.sizeSlot = Math.max(0, Math.min(2, o.optInt("sizeSlot", 1)));
            } else {
                // From before presets: the size in use becomes Medium.
                p.sizes[1] = p.thickness;
                p.sizes[0] = Math.max(0, p.thickness - 20);
                p.sizes[2] = Math.min(100, p.thickness + 30);
                p.sizeSlot = 1;
            }
            p.thickness = p.sizes[p.sizeSlot];
            p.smoothing = (float) o.optDouble("smoothing", p.smoothing);
            p.pressure = (float) o.optDouble("pressure", p.pressure);
        }
    }

    /**
     * Brush picker for the pen's extra options: a chip per brush, each showing a wave
     * drawn by that brush itself, two to a row. The chosen one is outlined.
     */
    private View brushPickerGrid() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_SM));
        col.addView(toolMenuLabel("Brush"));
        final List<View> chips = new ArrayList<>();
        final List<Integer> chipBrushes = new ArrayList<>();
        LinearLayout row = null;
        int n = 0;
        // Highlighter, tape and shapes have slots of their own in the toolbar.
        for (int b = 0; b < CodeCanvasView.BRUSH_COUNT; b++) {
            if (!CodeCanvasView.isPenBrush(b)) continue;
            if (n++ % 2 == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = act.dp(MainActivity.SPACE_MD);
                col.addView(row, rlp);
            }
            final int brushId = b;
            LinearLayout chip = new LinearLayout(act);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM));
            chip.setClickable(true);
            View preview = new View(act) {
                @Override
                protected void onDraw(android.graphics.Canvas c) {
                    if (act.canvas == null) return;
                    act.canvas.drawBrushPreview(c, brushId, previewColorFor(brushId),
                            act.dp(3), getWidth(), getHeight());
                }
            };
            preview.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            chip.addView(preview, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, act.dp(34)));
            // The stroke preview says it all; the name stays for accessibility only.
            chip.setContentDescription(CodeCanvasView.BRUSH_LABELS[b]);
            chip.setOnClickListener(v -> {
                setBrush(brushId);
                for (int i = 0; i < chips.size(); i++) styleBrushChip(chips.get(i), chipBrushes.get(i) == act.brush);
                for (View c : chips) ((ViewGroup) c).getChildAt(0).invalidate();
            });
            styleBrushChip(chip, b == act.brush);
            chips.add(chip);
            chipBrushes.add(b);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (n % 2 == 1) clp.rightMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(chip, clp);
        }
        if (n % 2 == 1 && row != null) {
            row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        return col;
    }

    // ---- Shape tool ----------------------------------------------------------------

    /** Short names of the line styles, for the segmented row and the shape chip. */
    static final String[] LINE_SHORT = {"Solid", "Dash", "Dot", "Dash-dot", "Long"};

    /** Puts the shape tool's settings on the canvas. */
    void applyShapeStyle() {
        if (act.canvas != null) {
            act.canvas.setShapeStyle(act.shapeKind, act.shapeFill, act.shapeBorder, act.shapeBorderWidth);
            act.canvas.setShapeDash(act.shapeDash);
        }
    }

    private TextView shapeLineLabel;

    /** The shape border's line style, as a menu under the chip. */
    private void showShapeLineMenu(View anchor) {
        M3Menu m = new M3Menu(act);
        for (int i = 0; i < CodeCanvasView.LINE_STYLE_COUNT; i++) {
            final int style = i;
            m.add(i == act.shapeDash ? R.drawable.ic_check : 0, CodeCanvasView.LINE_STYLE_LABELS[i], () -> {
                act.shapeDash = style;
                applyShapeStyle();
                refreshShapeOptions();
                act.persistence.scheduleSave();
            });
        }
        m.showUnder(anchor);
    }

    /**
     * The option row while the shape tool is in hand, as a run of borderless chips of
     * one height: the shape (opens the picker), fill, border, and the border's width.
     */
    LinearLayout buildShapeOptionsRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout kind = optionChip(null, () -> showShapePicker());
        kind.setContentDescription("Choose shape");
        act.shapeKindButton = new ImageView(act);
        kind.addView(act.shapeKindButton, 0, new LinearLayout.LayoutParams(act.dp(22), act.dp(22)));
        ImageView more = new ImageView(act);
        more.setImageResource(R.drawable.ic_expand_more);
        more.setColorFilter(act.M3_ON_SURFACE_VARIANT);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(act.dp(18), act.dp(18));
        ml.leftMargin = act.dp(2);
        kind.addView(more, ml);
        row.addView(kind, chipLp(false));

        LinearLayout fill = optionChip("Fill", () -> showShapeColorPicker(true));
        act.shapeFillSwatch = new View(act);
        fill.addView(act.shapeFillSwatch, 0, new LinearLayout.LayoutParams(act.dp(20), act.dp(20)));
        row.addView(fill, chipLp(true));

        LinearLayout line = optionChip(null, null);
        shapeLineLabel = chipText(LINE_SHORT[act.shapeDash]);
        line.addView(shapeLineLabel);
        line.setClickable(true);
        line.setOnClickListener(v -> showShapeLineMenu(v));
        line.setContentDescription("Border line style");

        LinearLayout border = optionChip("Border", () -> showShapeColorPicker(false));
        act.shapeBorderSwatch = new View(act);
        border.addView(act.shapeBorderSwatch, 0, new LinearLayout.LayoutParams(act.dp(20), act.dp(20)));
        row.addView(border, chipLp(true));
        row.addView(line, chipLp(true));

        // Width: the slider inside a chip of its own, with the value at its end.
        LinearLayout width = optionChip(null, null);
        width.setPadding(act.dp(12), 0, act.dp(12), 0);
        TextView label = chipText("Width");
        width.addView(label);
        Material3Slider slider = new Material3Slider(act);
        shapeWidthSlider = slider;
        slider.setMax(24);
        slider.setProgress(Math.round(act.shapeBorderWidth));
        slider.setContentDescription("Border width");
        act.tintSeekBar(slider);
        final TextView value = chipText(String.valueOf(Math.round(act.shapeBorderWidth)));
        value.setMinWidth(act.dp(18));
        value.setGravity(Gravity.END);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(String.valueOf(progress));
                if (!fromUser) return;
                act.shapeBorderWidth = progress;
                applyShapeStyle();
                refreshShapeOptions();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                act.persistence.scheduleSave();
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(act.dp(112), act.dp(32));
        sl.leftMargin = act.dp(4);
        width.addView(slider, sl);
        width.addView(value);
        row.addView(width, chipLp(true));
        return row;
    }

    private static final int CHIP_H = 40;

    /** A borderless chip: [content…] label, the whole thing tappable. */
    private LinearLayout optionChip(String label, Runnable onTap) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(act.dp(10), 0, act.dp(14), 0);
        // No box: the press shows as a rounded ripple only.
        if (onTap != null) {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(act.dp(12));
            chip.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf((act.M3_PRIMARY & 0x00FFFFFF) | 0x33000000),
                    null, mask));
            chip.setClickable(true);
            chip.setOnClickListener(v -> onTap.run());
            chip.setContentDescription(label);
        }
        if (label != null) {
            TextView t = chipText(label);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = act.dp(8);
            chip.addView(t, lp);
        }
        return chip;
    }

    private TextView chipText(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(act.M3_ON_SURFACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        return t;
    }

    private LinearLayout.LayoutParams chipLp(boolean gapBefore) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(CHIP_H));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (gapBefore) lp.leftMargin = act.dp(8);
        return lp;
    }

    /** A colour disc; "none" is a ring with a slash. */
    private android.graphics.drawable.Drawable colorDisc(int color) {
        if ((color >>> 24) == 0) {
            return new android.graphics.drawable.Drawable() {
                final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

                @Override
                public void draw(android.graphics.Canvas c) {
                    android.graphics.Rect b = getBounds();
                    float r = Math.min(b.width(), b.height()) / 2f - act.dp(1);
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(act.getResources().getDisplayMetrics().density * 1.5f);
                    p.setColor(act.M3_ON_SURFACE_VARIANT);
                    c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, p);
                    float d = r * 0.7f;
                    c.drawLine(b.exactCenterX() - d, b.exactCenterY() + d, b.exactCenterX() + d, b.exactCenterY() - d, p);
                }

                @Override public void setAlpha(int a) {}

                @Override public void setColorFilter(android.graphics.ColorFilter cf) {}

                @Override public int getOpacity() {
                    return android.graphics.PixelFormat.TRANSLUCENT;
                }
            };
        }
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(act.dp(1), (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x99000000);
        return g;
    }

    private Material3Slider shapeWidthSlider;

    void refreshShapeOptions() {
        // Built before the theme was applied: take its colours now, as the pen's sliders do.
        if (shapeWidthSlider != null) act.tintSeekBar(shapeWidthSlider);
        if (act.shapeKindButton != null) {
            act.shapeKindButton.setImageDrawable(new ShapeIconDrawable(act.shapeKind,
                    (act.shapeBorder >>> 24) != 0 ? act.shapeBorder : act.M3_ON_SURFACE_VARIANT,
                    act.shapeFill, act.getResources().getDisplayMetrics().density * 1.8f));
        }
        if (act.shapeFillSwatch != null) act.shapeFillSwatch.setBackground(colorDisc(act.shapeFill));
        if (act.shapeBorderSwatch != null) act.shapeBorderSwatch.setBackground(colorDisc(act.shapeBorder));
        if (shapeLineLabel != null) shapeLineLabel.setText("Line: " + LINE_SHORT[act.shapeDash]);
    }

    /** Every shape in a grid, drawn in the current fill and border; a tap picks one. */
    private void showShapePicker() {
        int cols = 6;
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        final M3Dialog[] dialog = new M3Dialog[1];
        int stroke = (act.shapeBorder >>> 24) != 0 ? act.shapeBorder : act.M3_ON_SURFACE;
        LinearLayout row = null;
        for (int k = 0; k < ShapeLibrary.count(); k++) {
            if (k % cols == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final int kind = k;
            ImageView cell = new ImageView(act);
            cell.setImageDrawable(new ShapeIconDrawable(k, stroke, act.shapeFill, act.getResources().getDisplayMetrics().density * 1.6f));
            int pad = act.dp(10);
            cell.setPadding(pad, pad, pad, pad);
            cell.setContentDescription(ShapeLibrary.NAMES[k]);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(act.dp(14));
            if (k == act.shapeKind) {
                bg.setColor(act.M3_PRIMARY_CONTAINER | 0xFF000000);
            } else {
                bg.setColor(0);
            }
            cell.setBackground(bg);
            cell.setOnClickListener(v -> {
                act.shapeKind = kind;
                applyShapeStyle();
                refreshShapeOptions();
                act.persistence.scheduleSave();
                if (dialog[0] != null) dialog[0].dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, act.dp(56), 1f);
            lp.setMargins(act.dp(2), act.dp(2), act.dp(2), act.dp(2));
            row.addView(cell, lp);
        }
        int rest = ShapeLibrary.count() % cols;
        if (rest != 0 && row != null) {
            for (int i = rest; i < cols; i++) row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        dialog[0] = new M3Dialog.Builder(act)
                .setTitle("Shapes")
                .setView(grid)
                .setNegativeButton("Close", null)
                .show();
    }

    /** Colours for fill or border: none, greys, the pen's colours and the favourites. */
    private void showShapeColorPicker(boolean fill) {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        set.add(0);
        for (int c : new int[]{0xFFFFFFFF, 0xFFBDBDBD, 0xFF616161, 0xFF000000}) set.add(c);
        for (int c : act.pens[CodeCanvasView.BRUSH_INK].slots) set.add(c | 0xFF000000);
        for (int c : act.pens[CodeCanvasView.BRUSH_TAPE].slots) set.add(c | 0xFF000000);
        for (int c : act.favoriteColors) {
            if (set.size() >= 24) break;
            set.add(c | 0xFF000000);
        }
        final int current = fill ? act.shapeFill : act.shapeBorder;
        int cols = 6;
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        final M3Dialog[] dialog = new M3Dialog[1];
        LinearLayout row = null;
        int i = 0;
        for (int c : set) {
            if (i++ % cols == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final int color = c;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(act);
            View disc = new View(act);
            disc.setBackground(colorDisc(c));
            cell.addView(disc, new android.widget.FrameLayout.LayoutParams(act.dp(34), act.dp(34), Gravity.CENTER));
            if (c == current) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ring.setStroke(Math.round(act.getResources().getDisplayMetrics().density * 2.5f), act.M3_PRIMARY);
                View ringView = new View(act);
                ringView.setBackground(ring);
                cell.addView(ringView, new android.widget.FrameLayout.LayoutParams(act.dp(44), act.dp(44), Gravity.CENTER));
            }
            cell.setContentDescription(c == 0 ? "None" : PenTools.colorNameFor(c));
            cell.setOnClickListener(v -> {
                if (fill) act.shapeFill = color;
                else act.shapeBorder = color;
                applyShapeStyle();
                refreshShapeOptions();
                act.persistence.scheduleSave();
                if (dialog[0] != null) dialog[0].dismiss();
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, act.dp(52), 1f));
        }
        int rest = set.size() % cols;
        if (rest != 0 && row != null) {
            for (int k = rest; k < cols; k++) row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        dialog[0] = new M3Dialog.Builder(act)
                .setTitle(fill ? "Fill" : "Border")
                .setView(grid)
                .setNegativeButton("Close", null)
                .show();
    }

    /** The pen icon for the extra stroke settings wears the theme's primary colour. */
    void stylePenSettingsButton() {
        for (ImageView b : new ImageView[] {
                act.penSettingsButton, act.lassoSettingsButton, act.eraserSettingsButton}) {
            if (b == null) continue;
            act.applyIconSelected(b, false);
            b.setColorFilter(new PorterDuffColorFilter(act.M3_PRIMARY, PorterDuff.Mode.SRC_IN));
        }
    }

    /**
     * Theme change: the option rows take their colours when they are built, so the settings
     * buttons are restyled and the shape row (its labels, swatches and slider) is rebuilt in
     * place, keeping whether it shows.
     */
    void applyOptionsTheme() {
        stylePenSettingsButton();
        View old = act.shapeOptions;
        if (old == null || !(old.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) old.getParent();
        int at = parent.indexOfChild(old);
        LinearLayout fresh = buildShapeOptionsRow();
        fresh.setVisibility(old.getVisibility());
        parent.removeViewAt(at);
        parent.addView(fresh, at, old.getLayoutParams());
        act.shapeOptions = fresh;
        refreshShapeOptions();
    }

    private void styleBrushChip(View chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(12));
        bg.setColor(selected ? (act.M3_PRIMARY_CONTAINER & 0x00FFFFFF) | 0x55000000
                : (act.M3_SURFACE_CONTAINER_HIGHEST & 0x00FFFFFF) | 0x99000000);
        bg.setStroke(act.dp(selected ? 2 : 1), selected ? act.M3_PRIMARY
                : (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x55000000);
        chip.setBackground(act.withHoverRipple(bg, false));
    }

    private View penSettingsPopup;

    /**
     * Primary / secondary pen button: the item says what it is mapped to; tap it (or
     * Change), then press the pen button to assign. While waiting the row says so and
     * its button breathes.
     */
    LinearLayout penButtonMapRow(LinearLayout card, boolean primaryRole) {
        PenButtonStore store = PenButtonStore.get(act);
        int hw = primaryRole ? store.getPrimaryHw() : store.getSecondaryHw();
        boolean listening = act.penMapListening == (primaryRole ? 1 : 2);

        View.OnClickListener startListen = v -> {
            act.penMapListening = listening ? 0 : (primaryRole ? 1 : 2);
            act.settingsPanel.populateOptionsCard(card);
        };
        TextView btn = act.panelAction(listening ? "Cancel" : "Change", listening, () -> {});
        btn.setOnClickListener(startListen);
        btn.setMinHeight(act.dp(40));
        btn.setPadding(act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_LG), 0);

        String role = primaryRole ? "Primary pen button" : "Secondary pen button";
        String what = primaryRole ? "eraser while held" : "switches to the lasso";
        String sub = listening
                ? "Press the pen button now…"
                : PenButtonStore.statusLabel(hw) + " · " + what;
        LinearLayout row = act.settingsPanel.settingsItem(R.drawable.ic_stylus, role, sub, btn, startListen);
        if (listening) {
            TextView subView = (TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(1);
            subView.setTextColor(act.M3_PRIMARY);
            act.settingsPanel.pulse(subView);
        }
        return row;
    }

    /**
     * Pen buttons arrive as Ctrl+4 (PAGEDOWN) / Ctrl+5 (PAGEUP) from pen_remap —
     * works without stylus hover on Lineage.
     */
    void onPenKeyEdge(boolean penA, boolean down) {
        int hw = penA ? PenButtonStore.HW_PEN_A : PenButtonStore.HW_PEN_B;
        if (act.penMapListening != 0) {
            if (!down) return;
            boolean primaryRole = act.penMapListening == 1;
            PenButtonStore.get(act).assign(primaryRole, hw);
            act.penMapListening = 0;
            if (act.optionsCard != null) act.settingsPanel.populateOptionsCard(act.optionsCard);
            return;
        }
        int role = PenButtonStore.get(act).roleForHardware(hw);
        if (act.presentation != null && act.slideshow.handlePresentationDoublePress(role, down)) return;
        if (role == 1) applyPrimaryPenRole(down);
        else if (role == 2) applySecondaryPenRole(down);
    }

    /**
     * Primary role: hold → eraser; hover flick picks a favorite by motion in
     * [press−200ms, release−50ms]. The radial only appears after 300ms of
     * hover; quicker flicks activate silently. Release → activate that favorite
     * (or pencil if tip was on screen / no gesture).
     */
    private void applyPrimaryPenRole(boolean down) {
        if (down) {
            act.hideSoftKeyboard();
            selectEraser(false);
            if (act.canvas != null && act.canvas.isQuickFavoritesEnabled()) {
                act.canvas.armFavoritesRadialGesture();
            }
            return;
        }
        // Button up. Tip contact = erase; only a real hover flick picks a favorite.
        if (act.canvas != null && (act.canvas.isStylusTipDown() || act.canvas.wasTipUsedOnFavoritesHold())) {
            act.canvas.keepEraserUntilTipUp(() -> selectWritingTool(false));
            return;
        }
        // With a selection up, a press just goes back to the pen: the selection ends
        // where it is, and no favorites menu opens.
        if (act.canvas != null && (act.canvas.hasActiveSelection() || act.canvas.hasLassoRegion())) {
            act.canvas.releaseFavoritesRadialGesture();
            act.canvas.clearPenEraseArm();
            selectWritingTool(true);
            return;
        }
        if (act.canvas != null && act.canvas.isQuickFavoritesEnabled()
                && act.canvas.hasPendingFavoritesGesture()) {
            // Single press while hovering, no tip contact: back to the pen, and the menu
            // opens at it, waiting for a tap on an item. Otherwise the press was for
            // erasing — the eraser stays until the tip lifts (PAGE_DOWN UP races tip-down).
            act.canvas.finishFavoritesPress(
                    () -> {
                        act.canvas.clearPenEraseArm();
                        selectWritingTool(false);
                    },
                    () -> act.canvas.keepEraserUntilTipUp(
                            () -> selectWritingTool(false)));
            return;
        }
        if (act.canvas != null) {
            act.canvas.releaseFavoritesRadialGesture();
            act.canvas.keepEraserUntilTipUp(() -> selectWritingTool(false));
            return;
        }
        selectWritingTool(false);
    }

    /** Secondary role: lasso on press. */
    private void applySecondaryPenRole(boolean down) {
        if (!down) return;
        act.hideSoftKeyboard();
        selectLasso();
    }

    private final List<SizeDot> penSizeDots = new ArrayList<>();
    private final List<SizeDot> eraserSizeDots = new ArrayList<>();

    private static final String[] SIZE_NAMES = {"Fine", "Medium", "Bold"};
    private static final String[] ERASER_SIZE_NAMES = {"Small", "Medium", "Large"};

    /**
     * A size preset button: a dot (pen, in the ink colour) or ring (eraser) whose size
     * shows the preset, on a tonal circle when it is the one in use.
     */
    private final class SizeDot extends View {
        final boolean eraser;
        final int slot;
        boolean selected;
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        SizeDot(boolean eraser, int slot) {
            super(act);
            this.eraser = eraser;
            this.slot = slot;
            setClickable(true);
            setContentDescription((eraser ? "Eraser " + ERASER_SIZE_NAMES[slot] : "Pen " + SIZE_NAMES[slot])
                    .toLowerCase(java.util.Locale.ROOT));
        }

        /** Dot diameter in px for the preset's value. */
        float diameter() {
            if (eraser) {
                float f = (act.eraserSizes[slot] - 8f) / (80f - 8f);
                return act.dp(8) + Math.max(0f, Math.min(1f, f)) * act.dp(16);
            }
            float f = pen().sizes[slot] / 100f;
            return act.dp(3) + Math.max(0f, Math.min(1f, f)) * act.dp(14);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float d = diameter();
            if (selected) {
                // A snug halo around the dot, not the whole button.
                float halo = Math.min(Math.min(cx, cy) - act.dp(1), Math.max(act.dp(8), d / 2f + act.dp(3)));
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(act.M3_PRIMARY_CONTAINER);
                c.drawCircle(cx, cy, halo, paint);
            }
            if (eraser) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(act.dp(2));
                paint.setColor(selected ? act.M3_ON_PRIMARY_CONTAINER : act.M3_ON_SURFACE_VARIANT);
                c.drawCircle(cx, cy, d / 2f, paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                int[] pal = palette();
                paint.setColor(pal[Math.max(0, Math.min(pal.length - 1, act.selectedColorIndex))]);
                c.drawCircle(cx, cy, d / 2f, paint);
            }
        }
    }

    LinearLayout sizePresetRow(boolean eraser) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        List<SizeDot> dots = eraser ? eraserSizeDots : penSizeDots;
        dots.clear();
        for (int i = 0; i < 3; i++) {
            final int slot = i;
            SizeDot dot = new SizeDot(eraser, i);
            dot.setOnClickListener(v -> {
                boolean again = eraser ? act.eraserSlot == slot : pen().sizeSlot == slot;
                if (again) {
                    // A second tap on the size in use opens its menu to fine-tune it.
                    if (eraser) toggleEraserSettingsPopup();
                    else togglePenSettingsPopup();
                    return;
                }
                v.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK);
                if (eraser) selectEraserSize(slot);
                else selectPenSize(slot);
                Motion.pop(v);
            });
            dots.add(dot);
            row.addView(dot, act.iconLp());
        }
        return row;
    }

    private void selectPenSize(int slot) {
        PenState p = pen();
        p.sizeSlot = Math.max(0, Math.min(2, slot));
        p.thickness = p.sizes[p.sizeSlot];
        applyPenProperties();
        act.persistence.scheduleSave();
    }

    private void selectEraserSize(int slot) {
        act.eraserSlot = Math.max(0, Math.min(2, slot));
        if (act.canvas != null) act.canvas.setEraseRadiusPx(act.eraserSizes[act.eraserSlot]);
        refreshSizeDots();
        if (eraserSizeSlider != null) eraserSizeSlider.setProgress(eraserProgress(act.eraserSizes[act.eraserSlot]));
        act.persistence.scheduleSave();
    }

    void refreshSizeDots() {
        for (SizeDot d : penSizeDots) {
            d.selected = d.slot == pen().sizeSlot;
            d.invalidate();
        }
        for (SizeDot d : eraserSizeDots) {
            d.selected = d.slot == act.eraserSlot;
            d.invalidate();
        }
    }

    private static int eraserProgress(float px) {
        return Math.round((px - 8f) / (80f - 8f) * 100f);
    }

    private static float eraserPx(int progress) {
        return 8f + Math.max(0, Math.min(100, progress)) / 100f * (80f - 8f);
    }

    private SeekBar eraserSizeSlider;
    private View eraserSettingsPopup;

    /** Menu card anchored under a toolbar button, landing out of it; closes by dropping away. */
    private FrameLayout toolMenu(View anchor, String title, String subtitle, Runnable onDismiss) {
        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> onDismiss.run());

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_LG));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(28));
        bg.setColor(act.M3_SURFACE_CONTAINER_HIGH | 0xFF000000);
        bg.setStroke(act.dp(1), (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x55000000);
        card.setBackground(bg);
        SketchStyle.elevate(card, 8);
        card.setClickable(true);
        card.setOnClickListener(v -> {});

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, 0, 0, act.dp(MainActivity.SPACE_SM));
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(act.M3_ON_SURFACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        header.addView(t);
        if (subtitle != null) {
            TextView st = act.panelHint(subtitle);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            header.addView(st);
        }
        card.addView(header);

        android.widget.ScrollView scroller = new android.widget.ScrollView(act);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.addView(card);

        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int[] rootLoc = new int[2];
        act.rootLayout.getLocationInWindow(rootLoc);
        int cardW = act.cardWidth(340);
        int top = loc[1] - rootLoc[1] + anchor.getHeight() + act.dp(MainActivity.SPACE_SM);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.topMargin = top;
        lp.bottomMargin = act.dp(MainActivity.SPACE_LG);
        int anchorCenter = loc[0] - rootLoc[0] + anchor.getWidth() / 2;
        lp.leftMargin = Math.max(act.dp(MainActivity.SPACE_SM), Math.min(act.rootLayout.getWidth() - cardW - act.dp(MainActivity.SPACE_SM),
                anchorCenter - cardW / 2));
        overlay.addView(scroller, lp);
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setTranslationZ(act.dp(act.zenMode ? MainActivity.ZEN_LIFT_DP + 50 : 50));
        // Land out of the button that opened it.
        final int pivotX = anchorCenter - lp.leftMargin;
        scroller.setAlpha(0f);
        scroller.post(() -> Motion.popIn(scroller, pivotX, 0f, -8f));
        scroller.setTag(card);
        return overlay;
    }

    /** The card inside a {@link #toolMenu} overlay. */
    private static LinearLayout toolMenuCard(FrameLayout overlay) {
        return (LinearLayout) overlay.getChildAt(0).getTag();
    }

    private void closeToolMenu(View overlay) {
        if (overlay == null || overlay.getParent() == null) return;
        View scroller = ((ViewGroup) overlay).getChildAt(0);
        overlay.setClickable(false);
        overlay.setOnClickListener(null);
        Motion.popOut(scroller, -6f, null, () -> {
            if (overlay.getParent() != null) ((ViewGroup) overlay.getParent()).removeView(overlay);
        });
    }

    private TextView toolMenuLabel(String text) {
        TextView v = new TextView(act);
        v.setText(text);
        v.setTextColor(act.M3_PRIMARY);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_SM));
        return v;
    }

    /** Compact switch row for tool menus: icon, label, switch; the whole row toggles. */
    private LinearLayout toolMenuSwitch(int icon, String label, boolean on,
                                        java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = act.settingsPanel.settingsSwitchItem(icon, label, null, on, onChange);
        row.setMinimumHeight(act.dp(48));
        row.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_XS));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(act.dp(16));
        mask.setColor(0x00000000);
        row.setBackground(act.withHoverRipple(mask, false));
        return row;
    }

    /**
     * Three size chips — dot, name — over a slider that tunes the chosen one. Picking
     * a chip slides the highlight like the settings pickers.
     */
    private View sizePresetEditor(boolean eraser) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        final FrameLayout segHolder = new FrameLayout(act);
        final Runnable[] build = new Runnable[1];
        final SeekBar[] slider = new SeekBar[1];
        build[0] = () -> {
            segHolder.removeAllViews();
            int sel = eraser ? act.eraserSlot : pen().sizeSlot;
            segHolder.addView(act.settingsPanel.optionsSegmentRow(eraser ? ERASER_SIZE_NAMES : SIZE_NAMES, sel, i -> {
                if (eraser) selectEraserSize(i);
                else selectPenSize(i);
                if (slider[0] != null) {
                    slider[0].setProgress(eraser ? eraserProgress(act.eraserSizes[i]) : pen().sizes[i]);
                }
                build[0].run();
            }));
        };
        build[0].run();
        col.addView(segHolder, MainActivity.matchWrap());
        LinearLayout sl = penSettingsSliderRow(
                eraser ? "Eraser size" : "Size",
                eraser ? eraserProgress(act.eraserSizes[act.eraserSlot]) : pen().sizes[pen().sizeSlot],
                p -> {
                    if (eraser) {
                        act.eraserSizes[act.eraserSlot] = eraserPx(p);
                        if (act.canvas != null) act.canvas.setEraseRadiusPx(act.eraserSizes[act.eraserSlot]);
                    } else {
                        PenState ps = pen();
                        ps.sizes[ps.sizeSlot] = p;
                        ps.thickness = p;
                        if (act.canvas != null) act.canvas.setBaseThicknessPx(thicknessForProgress(p));
                    }
                    refreshSizeDots();
                },
                seek -> {
                    slider[0] = seek;
                    if (eraser) eraserSizeSlider = seek;
                    else act.thicknessSeekBar = seek;
                });
        sl.setPadding(0, act.dp(MainActivity.SPACE_MD), 0, 0);
        col.addView(sl);
        return col;
    }

    void togglePenSettingsPopup() {
        if (penSettingsPopup != null) {
            dismissPenSettingsPopup();
            return;
        }
        if (act.rootLayout == null || act.penSettingsButton == null) return;
        FrameLayout overlay = toolMenu(act.penSettingsButton, "Pen",
                CodeCanvasView.BRUSH_LABELS[act.brush], this::dismissPenSettingsPopup);
        LinearLayout card = toolMenuCard(overlay);

        card.addView(brushPickerGrid());
        card.addView(toolMenuLabel("Size"));
        // Each brush has its own sizes: rebuilt when the brush changes.
        final FrameLayout sizeHolder = new FrameLayout(act);
        sizeHolder.addView(sizePresetEditor(false));
        card.addView(sizeHolder, MainActivity.matchWrap());
        final TextView subtitle = (TextView) ((ViewGroup) card.getChildAt(0)).getChildAt(1);
        penMenuRefresh = () -> {
            sizeHolder.removeAllViews();
            sizeHolder.addView(sizePresetEditor(false));
            subtitle.setText(CodeCanvasView.BRUSH_LABELS[act.brush]);
        };
        card.addView(toolMenuLabel("Line style (ink)"));
        card.addView(act.settingsPanel.optionsSegmentRow(LINE_SHORT, act.lineStyle, i -> {
            act.lineStyle = i;
            if (act.canvas != null) act.canvas.setLineStyle(i);
            act.persistence.scheduleSave();
        }), MainActivity.matchWrap());
        card.addView(toolMenuLabel("Feel"));
        card.addView(penSettingsSliderRow(
                "Smoothing",
                act.canvas != null ? Math.round(act.canvas.getStabilization() * 100f) : 65,
                progress -> {
                    pen().smoothing = progress / 100f;
                    if (act.canvas != null) act.canvas.setStabilization(progress / 100f);
                },
                seek -> act.stabilizationSeekBar = seek));
        card.addView(penSettingsSliderRow(
                "Pressure",
                act.canvas != null ? Math.round(act.canvas.getPressureSensitivity() * 100f) : 100,
                progress -> {
                    pen().pressure = progress / 100f;
                    if (act.canvas != null) act.canvas.setPressureSensitivity(progress / 100f);
                },
                seek -> act.pressureSeekBar = seek));
        card.addView(toolMenuLabel("Shapes"));
        card.addView(toolMenuSwitch(R.drawable.ic_shapes, "Hold to snap shapes",
                act.canvas == null || act.canvas.isShapeSnapEnabled(), on -> {
                    if (act.canvas != null) act.canvas.setShapeSnapEnabled(on);
                    act.persistence.scheduleSave();
                }), MainActivity.matchWrap());
        penSettingsPopup = overlay;
    }

    /** Refreshes the open pen menu for a new brush; null when it is closed. */
    private Runnable penMenuRefresh;

    private void dismissPenSettingsPopup() {
        penMenuRefresh = null;
        if (penSettingsPopup == null) return;
        closeToolMenu(penSettingsPopup);
        penSettingsPopup = null;
        // Sliders in the menu are gone; stop steering them.
        act.thicknessSeekBar = null;
        act.stabilizationSeekBar = null;
        act.pressureSeekBar = null;
    }

    private View lassoSettingsPopup;

    void refreshLassoChips() {
        if (act.canvas == null) return;
        act.applyFilterChip(act.chipInk, act.canvas.isLassoInk());
        act.applyFilterChip(act.chipHl, act.canvas.isLassoHighlighter());
        act.applyFilterChip(act.chipText, act.canvas.isLassoText());
        act.applyFilterChip(act.chipImg, act.canvas.isLassoImages());
        if (lassoMenuRefresh != null) lassoMenuRefresh.run();
    }

    /** Re-syncs the open lasso menu's switches with the toolbar toggles. */
    private Runnable lassoMenuRefresh;

    void toggleLassoSettingsPopup() {
        if (lassoSettingsPopup != null) {
            dismissLassoSettingsPopup();
            return;
        }
        if (act.rootLayout == null || act.lassoSettingsButton == null || act.canvas == null) return;
        FrameLayout overlay = toolMenu(act.lassoSettingsButton, "Lasso",
                null, this::dismissLassoSettingsPopup);
        LinearLayout card = toolMenuCard(overlay);
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list, MainActivity.matchWrap());
        lassoMenuRefresh = () -> {
            list.removeAllViews();
            list.addView(toolMenuSwitch(R.drawable.ic_ink, "Handwriting", act.canvas.isLassoInk(), on -> {
                act.canvas.setLassoInk(on);
                syncLassoChipsOnly();
            }), MainActivity.matchWrap());
            list.addView(toolMenuSwitch(R.drawable.ic_draw, "Highlighter", act.canvas.isLassoHighlighter(), on -> {
                act.canvas.setLassoHighlighter(on);
                syncLassoChipsOnly();
            }), MainActivity.matchWrap());
            list.addView(toolMenuSwitch(R.drawable.ic_text, "Text boxes", act.canvas.isLassoText(), on -> {
                act.canvas.setLassoText(on);
                syncLassoChipsOnly();
            }), MainActivity.matchWrap());
            list.addView(toolMenuSwitch(R.drawable.ic_image, "Images", act.canvas.isLassoImages(), on -> {
                act.canvas.setLassoImages(on);
                syncLassoChipsOnly();
            }), MainActivity.matchWrap());
        };
        lassoMenuRefresh.run();
        lassoSettingsPopup = overlay;
    }

    /** Toolbar toggles follow a switch flipped in the menu (the menu keeps its own animation). */
    private void syncLassoChipsOnly() {
        if (act.canvas == null) return;
        act.applyFilterChip(act.chipInk, act.canvas.isLassoInk());
        act.applyFilterChip(act.chipHl, act.canvas.isLassoHighlighter());
        act.applyFilterChip(act.chipText, act.canvas.isLassoText());
        act.applyFilterChip(act.chipImg, act.canvas.isLassoImages());
        act.persistence.scheduleSave();
    }

    private void dismissLassoSettingsPopup() {
        lassoMenuRefresh = null;
        if (lassoSettingsPopup == null) return;
        closeToolMenu(lassoSettingsPopup);
        lassoSettingsPopup = null;
    }

    void toggleEraserSettingsPopup() {
        if (eraserSettingsPopup != null) {
            dismissEraserSettingsPopup();
            return;
        }
        if (act.rootLayout == null || act.eraserSettingsButton == null || act.canvas == null) return;
        FrameLayout overlay = toolMenu(act.eraserSettingsButton, "Eraser",
                null, this::dismissEraserSettingsPopup);
        LinearLayout card = toolMenuCard(overlay);
        card.addView(toolMenuLabel("Size"));
        card.addView(sizePresetEditor(true));
        card.addView(toolMenuSwitch(R.drawable.ic_ink, "Handwriting", act.canvas.isEraseInk(), on -> {
            act.canvas.setEraseInk(on);
            act.persistence.scheduleSave();
        }), MainActivity.matchWrap());
        card.addView(toolMenuSwitch(R.drawable.ic_draw, "Highlighter", act.canvas.isEraseHighlighter(), on -> {
            act.canvas.setEraseHighlighter(on);
            act.persistence.scheduleSave();
        }), MainActivity.matchWrap());
        card.addView(toolMenuSwitch(R.drawable.ic_text, "Text boxes", act.canvas.isEraseText(), on -> {
            act.canvas.setEraseText(on);
            act.persistence.scheduleSave();
        }), MainActivity.matchWrap());
        card.addView(toolMenuSwitch(R.drawable.ic_image, "Images", act.canvas.isEraseImages(), on -> {
            act.canvas.setEraseImages(on);
            act.persistence.scheduleSave();
        }), MainActivity.matchWrap());
        eraserSettingsPopup = overlay;
    }

    private void dismissEraserSettingsPopup() {
        if (eraserSettingsPopup == null) return;
        closeToolMenu(eraserSettingsPopup);
        eraserSettingsPopup = null;
        eraserSizeSlider = null;
    }

    private interface SeekBinder { void bind(SeekBar seek); }

    LinearLayout penSettingsSliderRow(
            String label, int progress, final MainActivity.IntConsumer onProgress, SeekBinder binder) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_SM));

        TextView title = new TextView(act);
        title.setText(label);
        title.setTextColor(act.M3_ON_SURFACE_VARIANT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(title);

        SeekBar seek = new Material3Slider(act);
        seek.setMax(100);
        seek.setProgress(Math.max(0, Math.min(100, progress)));
        seek.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM));
        seek.setSplitTrack(false);
        seek.setOnTouchListener((v, event) -> {
            ViewParent parent = v.getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
                parent = parent.getParent();
            }
            return false;
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                onProgress.accept(p);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                act.persistence.scheduleSave();
            }
        });
        act.tintSeekBar(seek);
        if (binder != null) binder.bind(seek);
        col.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, act.dp(40)));
        return col;
    }

    void selectPencil(int index) {
        selectPencil(index, true);
    }

    void selectPencil(int index, boolean dropSelection) {
        act.hideSoftKeyboard();
        if (act.canvas != null) act.canvas.requestFocus();
        act.selectedColorIndex = index;
        pen().selected = index;
        act.stopwatchSelected = false;
        act.stickersSelected = false;
        act.eraserSelected = false;
        act.lassoSelected = false;
        act.textSelected = false;
        if (act.canvas != null) {
            if (dropSelection) act.canvas.clearSelection();
            act.canvas.setTool(CodeCanvasView.Tool.PENCIL);
            act.canvas.setInk(palette()[index], inkNameFor(index));
        }
        refreshToolSelection();
    }

    void selectText() {
        if (act.textSelected) {
            restoreToolAfterText(true);
            return;
        }
        toolBeforeText = act.eraserSelected
                ? CodeCanvasView.Tool.ERASER
                : act.lassoSelected ? CodeCanvasView.Tool.LASSO : CodeCanvasView.Tool.PENCIL;
        inkBeforeText = act.selectedColorIndex;
        act.stopwatchSelected = false;
        act.stickersSelected = false;
        act.hideSoftKeyboard();
        if (act.canvas != null) act.canvas.requestFocus();
        act.eraserSelected = false;
        act.lassoSelected = false;
        act.textSelected = true;
        if (act.canvas != null) {
            act.canvas.clearSelection();
            act.canvas.setTool(CodeCanvasView.Tool.TEXT);
        }
        refreshToolSelection();
    }

    /** The text tool is one-shot: one dragged field, then back to the interrupted tool. */
    void restoreToolAfterText(boolean dropSelection) {
        if (!act.textSelected) return;
        if (toolBeforeText == CodeCanvasView.Tool.ERASER) {
            selectEraser(dropSelection);
        } else if (toolBeforeText == CodeCanvasView.Tool.LASSO) {
            selectLasso();
        } else {
            selectPencil(inkBeforeText, dropSelection);
        }
    }

    void selectEraser() {
        selectEraser(true);
    }

    void selectEraser(boolean dropSelection) {
        act.hideSoftKeyboard();
        if (act.canvas != null) act.canvas.requestFocus();
        act.stopwatchSelected = false;
        act.stickersSelected = false;
        act.eraserSelected = true;
        act.lassoSelected = false;
        act.textSelected = false;
        if (act.canvas != null) {
            if (dropSelection) act.canvas.clearSelection();
            act.canvas.setTool(CodeCanvasView.Tool.ERASER);
        }
        refreshToolSelection();
    }

    void selectLasso() {
        act.hideSoftKeyboard();
        if (act.canvas != null) act.canvas.requestFocus();
        act.stopwatchSelected = false;
        act.stickersSelected = false;
        act.eraserSelected = false;
        act.lassoSelected = true;
        act.textSelected = false;
        if (act.canvas != null) act.canvas.setTool(CodeCanvasView.Tool.LASSO);
        refreshToolSelection();
    }

    void refreshToolSelection() {
        boolean pencil = !act.eraserSelected && !act.lassoSelected && !act.textSelected;
        int b = act.brush;
        boolean shapes = pencil && b == CodeCanvasView.BRUSH_SHAPE;
        // The stopwatch and the sticker strip take the option row over while open.
        boolean panel = act.stopwatchSelected || act.stickersSelected;
        if (act.pencilButton != null) act.applyIconSelected(act.pencilButton, pencil && CodeCanvasView.isPenBrush(b));
        if (act.highlighterButton != null) act.applyIconSelected(act.highlighterButton, pencil && highlighterOn());
        if (act.tapeButton != null) act.applyIconSelected(act.tapeButton, pencil && b == CodeCanvasView.BRUSH_TAPE);
        if (act.shapesButton != null) act.applyIconSelected(act.shapesButton, shapes);
        if (act.eraserButton != null) act.applyIconSelected(act.eraserButton, act.eraserSelected);
        if (act.lassoButton != null) act.applyIconSelected(act.lassoButton, act.lassoSelected);
        if (act.textToolButton != null) act.applyIconSelected(act.textToolButton, act.textSelected);
        // Same idle tint as the rest of the toolbar (was stuck on launch defaults).
        if (act.undoButton != null) act.applyIconSelected(act.undoButton, false);
        if (act.redoButton != null) act.applyIconSelected(act.redoButton, false);

        for (int i = 0; i < act.colorButtons.size(); i++) {
            boolean selected = pencil && i == act.selectedColorIndex;
            act.applyRoundStyle(act.colorButtons.get(i), palette()[i], selected);
        }

        // The stopwatch panel takes the option row over from the drawing tool's options.
        if (act.pencilOptions != null) {
            act.pencilOptions.setVisibility(pencil && !shapes && !panel ? View.VISIBLE : View.GONE);
        }
        if (act.shapeOptions != null) {
            act.shapeOptions.setVisibility(shapes && !panel ? View.VISIBLE : View.GONE);
            if (shapes) refreshShapeOptions();
        }
        if (act.targetOptions != null) {
            act.targetOptions.setVisibility(act.lassoSelected && !panel ? View.VISIBLE : View.GONE);
        }
        if (act.eraserOptions != null) {
            act.eraserOptions.setVisibility(act.eraserSelected && !panel ? View.VISIBLE : View.GONE);
        }
        refreshSizeDots();
        if (act.textDefaultsOptions != null) {
            act.textDefaultsOptions.setVisibility(act.textSelected && !panel ? View.VISIBLE : View.GONE);
            if (act.textSelected) act.textTools.refreshTextDefaultsRow();
        }
        if (act.stopwatch != null) act.stopwatch.setVisibility(act.stopwatchSelected ? View.VISIBLE : View.GONE);
        if (act.stickerStrip != null) act.stickerStrip.setVisibility(act.stickersSelected ? View.VISIBLE : View.GONE);
        if (act.stickersButton != null) act.applyIconSelected(act.stickersButton, act.stickersSelected);
        act.refreshStopwatchButton();
        refreshToolOptionRow();
        refreshLassoChips();
        act.refreshSelectionActions(act.canvas != null && act.canvas.hasActiveSelection());
        act.updateToolPillPosition();
    }

    /** Same footprint as the other tool icons so the chevron is not clipped. */
    LinearLayout.LayoutParams expandLp() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(act.dp(MainActivity.ICON_SIZE), act.dp(MainActivity.ICON_SIZE));
        lp.setMargins(act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_XS), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    ImageView buildToolExpandButton() {
        ImageView v = new ImageView(act);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(act.dp(MainActivity.ICON_PAD), act.dp(MainActivity.ICON_PAD), act.dp(MainActivity.ICON_PAD), act.dp(MainActivity.ICON_PAD));
        v.setImageResource(R.drawable.ic_expand_more);
        v.setContentDescription("Tool options");
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(x -> setToolOptionsExpanded(!act.toolOptionsExpanded));
        toolExpandButton = v;
        styleToolExpandButton();
        return v;
    }

    void styleToolExpandButton() {
        if (toolExpandButton == null) return;
        // Match toolbar idle/selected chrome (ColorFilter, not a leftover XML tint).
        toolExpandButton.setImageTintList(null);
        act.applyIconSelected(toolExpandButton, act.toolOptionsExpanded);
        toolExpandButton.setRotation(act.toolOptionsExpanded ? 180f : 0f);
    }

    void setToolOptionsExpanded(boolean expanded) {
        if (act.toolOptionsExpanded == expanded) return;
        float fromRot = act.toolOptionsExpanded ? 180f : 0f;
        act.toolOptionsExpanded = expanded;
        if (!expanded) dismissPenSettingsPopup();
        if (toolExpandButton != null) {
            toolExpandButton.setImageTintList(null);
            act.applyIconSelected(toolExpandButton, expanded);
            float toRot = expanded ? 180f : 0f;
            toolExpandButton.setRotation(fromRot);
            toolExpandButton.animate().rotation(toRot).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        refreshToolOptionRow();
        act.persistence.scheduleSave();
    }

    /** The option row only earns its height when expanded and the tool has options. */
    private void refreshToolOptionRow() {
        if (act.toolRowOptions == null) return;
        boolean anyVisible =
                (act.pencilOptions != null && act.pencilOptions.getVisibility() == View.VISIBLE)
                        || (act.shapeOptions != null && act.shapeOptions.getVisibility() == View.VISIBLE)
                        || (act.stickerStrip != null && act.stickerStrip.getVisibility() == View.VISIBLE)
                        || (act.targetOptions != null && act.targetOptions.getVisibility() == View.VISIBLE)
                        || (act.eraserOptions != null && act.eraserOptions.getVisibility() == View.VISIBLE)
                        || (act.textDefaultsOptions != null && act.textDefaultsOptions.getVisibility() == View.VISIBLE)
                        || (act.stopwatch != null && act.stopwatch.getVisibility() == View.VISIBLE);
        boolean wantOpen = act.toolOptionsExpanded && anyVisible;
        animateToolOptionsRow(wantOpen);
        if (toolExpandButton != null) {
            toolExpandButton.setAlpha(anyVisible ? 1f : 0.35f);
            toolExpandButton.setEnabled(anyVisible);
        }
        act.updateToolPillPosition();
    }

    /**
     * Expand/collapse the tool-option strip with a height + fade animation instead of
     * an instant GONE/VISIBLE swap.
     */
    private void animateToolOptionsRow(boolean open) {
        if (act.toolRowOptions == null) return;
        boolean alreadyOpen = act.toolRowOptions.getVisibility() == View.VISIBLE;
        boolean alreadyClosed = act.toolRowOptions.getVisibility() == View.GONE;
        if (open && alreadyOpen && toolOptionsAnim == null) return;
        if (!open && alreadyClosed && toolOptionsAnim == null) return;

        if (toolOptionsAnim != null) {
            toolOptionsAnim.cancel();
            toolOptionsAnim = null;
        }

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) act.toolRowOptions.getLayoutParams();
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = act.dp(MainActivity.SPACE_XS);
            act.toolRowOptions.setLayoutParams(lp);
        }

        if (open) {
            act.toolRowOptions.setVisibility(View.VISIBLE);
            act.toolRowOptions.setAlpha(0f);
            // Measure unconstrained so we know the resting height.
            act.toolRowOptions.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int targetH = Math.max(1, act.toolRowOptions.getMeasuredHeight());
            lp.height = 0;
            act.toolRowOptions.setLayoutParams(lp);
            final LinearLayout.LayoutParams animLp = lp;
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(220);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float f = (Float) a.getAnimatedValue();
                animLp.height = Math.max(1, Math.round(targetH * f));
                act.toolRowOptions.setLayoutParams(animLp);
                act.toolRowOptions.setAlpha(f);
                act.updateToolPillPosition();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    animLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    act.toolRowOptions.setLayoutParams(animLp);
                    act.toolRowOptions.setAlpha(1f);
                    toolOptionsAnim = null;
                    act.updateToolPillPosition();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    toolOptionsAnim = null;
                }
            });
            toolOptionsAnim = anim;
            anim.start();
        } else {
            final int startH = act.toolRowOptions.getHeight() > 0
                    ? act.toolRowOptions.getHeight()
                    : Math.max(1, act.toolRowOptions.getMeasuredHeight());
            final float startA = act.toolRowOptions.getAlpha();
            final LinearLayout.LayoutParams animLp = lp;
            ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
            anim.setDuration(180);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float f = (Float) a.getAnimatedValue();
                animLp.height = Math.max(1, Math.round(startH * f));
                act.toolRowOptions.setLayoutParams(animLp);
                act.toolRowOptions.setAlpha(startA * f);
                act.updateToolPillPosition();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    act.toolRowOptions.setVisibility(View.GONE);
                    act.toolRowOptions.setAlpha(1f);
                    animLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    act.toolRowOptions.setLayoutParams(animLp);
                    toolOptionsAnim = null;
                    act.updateToolPillPosition();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    toolOptionsAnim = null;
                }
            });
            toolOptionsAnim = anim;
            anim.start();
        }
    }
}
