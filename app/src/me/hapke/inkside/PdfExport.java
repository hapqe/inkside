package me.hapke.inkside;

import android.animation.Animator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Export PDF: the export dialog and its steps.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class PdfExport {
    private final MainActivity act;

    PdfExport(MainActivity act) {
        this.act = act;
    }

    /**
     * Export: the open PDF with everything on it — ink, text, images — saved to
     * Downloads/Inkside on the tablet (a numbered copy if the name is taken). The
     * tablet draws the annotations as a vector layer; the Mac stamps it onto the
     * original pages, so the PDF's own text stays sharp and selectable.
     *
     * <p>Opens the export dialog: which pages, what goes in and the file name, then
     * the same card follows the export step by step and ends with Open / Share.
     */
    void exportCurrentPdf() {
        if (act.canvas == null || !act.canvas.hasDocument() || act.bridge == null || act.rootLayout == null) return;
        final String path = act.canvas.getDocumentPath();
        if (path == null || path.isEmpty()) return;
        new ExportDialog(path).show();
    }

    private static final String PREF_EXPORT = "export_";

    /** The export window: options → progress → result, in one card. */
    private final class ExportDialog {
        final String path;
        final int pageCount;
        final int currentPage;
        final android.content.SharedPreferences prefs =
                act.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);

        /** 0 all pages, 1 this page, 2 a range. */
        int pagesMode;
        final int[] from = new int[1];
        final int[] to = new int[1];
        boolean ink = prefs.getBoolean(PREF_EXPORT + "ink", true);
        boolean text = prefs.getBoolean(PREF_EXPORT + "text", true);
        boolean images = prefs.getBoolean(PREF_EXPORT + "images", true);
        boolean pageLook = prefs.getBoolean(PREF_EXPORT + "pageLook", true);
        boolean presentHidden = prefs.getBoolean(PREF_EXPORT + "presentHidden", true);
        String fileName;

        FrameLayout overlay;
        LinearLayout card;
        /** Swapped between the options, progress and result views. */
        FrameLayout body;
        /** Pinned under the scrolling body; holds the options view's buttons. */
        LinearLayout footer;
        /** Options view pieces that change as choices change. */
        TextView pagesSummary;
        View rangeRow;
        TextView exportButton;

        // Progress view.
        Material3ProgressBar bar;
        TextView progressTitle;
        TextView progressDetail;
        final ImageView[] stepIcons = new ImageView[3];
        final TextView[] stepLabels = new TextView[3];
        int step = -1;
        android.animation.Animator stepPulse;
        boolean running;
        boolean hidden;

        ExportDialog(String path) {
            this.path = path;
            pageCount = Math.max(1, act.canvas.getDocumentPageCount());
            currentPage = Math.max(0, Math.min(pageCount - 1, act.canvas.getCurrentPageIndex()));
            from[0] = 1;
            to[0] = pageCount;
            String base = path;
            int slash = base.lastIndexOf('/');
            if (slash >= 0) base = base.substring(slash + 1);
            if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                base = base.substring(0, base.length() - 4);
            }
            fileName = base;
        }

        void show() {
            overlay = new FrameLayout(act);
            overlay.setClickable(true);
            overlay.setBackgroundColor(0x99000000);
            overlay.setOnClickListener(v -> close());

            card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            act.settingsPanel.applyOptionsCardSurface(card);
            card.setElevation(act.dp(6));
            card.setClickable(true);
            card.setOnClickListener(v -> {});
            card.setClipToOutline(true);
            // The card grows and shrinks smoothly as its content is swapped.
            android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
            lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
            lt.setDuration(Motion.CHANGE_MS);
            lt.setInterpolator(android.animation.LayoutTransition.CHANGING, Motion.STANDARD);
            lt.setInterpolator(android.animation.LayoutTransition.CHANGE_APPEARING, Motion.STANDARD);
            lt.setInterpolator(android.animation.LayoutTransition.CHANGE_DISAPPEARING, Motion.STANDARD);
            card.setLayoutTransition(lt);

            body = new FrameLayout(act);
            final int maxH = Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.88f)
                    - act.dp(72);
            android.widget.ScrollView scroller = new android.widget.ScrollView(act) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    // Taller than the screen allows: scroll inside the card.
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
                }
            };
            scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroller.setVerticalFadingEdgeEnabled(true);
            scroller.setFadingEdgeLength(act.dp(24));
            scroller.addView(body, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            // Buttons that must always be reachable sit under the scrolling part.
            footer = new LinearLayout(act);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            footer.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_LG));
            card.addView(footer, MainActivity.matchWrap());
            setBody(buildOptions(), false);

            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    act.cardWidth(480), ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.gravity = Gravity.CENTER;
            cardLp.leftMargin = act.dp(MainActivity.SPACE_XL);
            cardLp.rightMargin = act.dp(MainActivity.SPACE_XL);
            overlay.addView(card, cardLp);
            act.liftPanel(overlay);
            act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void close() {
            act.hideSoftKeyboard();
            if (running) {
                // Keeps going; the result arrives as a message.
                hidden = true;
                act.statusToastShort("Export continues in the background");
            }
            if (overlay != null && overlay.getParent() != null) act.rootLayout.removeView(overlay);
        }

        /** Old content fades away, the new content settles in row by row. */
        void setBody(View next, boolean animate) {
            if (footer != null && !(next.getTag() instanceof String && "options".equals(next.getTag()))) {
                footer.setVisibility(View.GONE);
            }
            if (animate && body.getChildCount() > 0) {
                View old = body.getChildAt(0);
                old.animate().alpha(0f).setDuration(Motion.EXIT_MS - 50)
                        .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                        .withEndAction(() -> body.removeView(old)).start();
                ((FrameLayout.LayoutParams) old.getLayoutParams()).gravity = Gravity.TOP;
            } else {
                body.removeAllViews();
            }
            body.addView(next, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (animate && next instanceof ViewGroup) Motion.stagger((ViewGroup) next, 90L, 12f);
        }

        // ---- Options --------------------------------------------------------------

        View buildOptions() {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setTag("options");
            col.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_XL));

            col.addView(header("Export PDF", fileName + ".pdf"));

            // Pages: all / this page / a range, with the count spelled out.
            LinearLayout pagesCol = new LinearLayout(act);
            pagesCol.setOrientation(LinearLayout.VERTICAL);
            final FrameLayout segHolder = new FrameLayout(act);
            final Runnable[] buildSeg = new Runnable[1];
            buildSeg[0] = () -> {
                segHolder.removeAllViews();
                segHolder.addView(act.settingsPanel.optionsSegmentRow(
                        new String[] {"All", "Page " + (currentPage + 1), "Range"}, pagesMode, i -> {
                            pagesMode = i;
                            buildSeg[0].run();
                            if (i == 2) Motion.expand(rangeRow);
                            else Motion.collapse(rangeRow);
                            refreshSummary();
                        }));
            };
            buildSeg[0].run();
            pagesCol.addView(segHolder, MainActivity.matchWrap());
            rangeRow = rangeSteppers();
            rangeRow.setVisibility(pagesMode == 2 ? View.VISIBLE : View.GONE);
            pagesCol.addView(rangeRow, MainActivity.matchWrap());
            pagesSummary = act.panelHint("");
            pagesSummary.setPadding(act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_SM), 0, 0);
            pagesCol.addView(pagesSummary);
            refreshSummary();
            col.addView(act.settingsPanel.settingsGroup("Pages",
                    act.settingsPanel.settingsBlock(R.drawable.ic_pages, "Which pages", null, pagesCol)));

            col.addView(act.settingsPanel.settingsGroup("Include",
                    act.settingsPanel.settingsSwitchItem(R.drawable.ic_ink, "Handwriting", "Pen and highlighter strokes",
                            ink, on -> { ink = on; save("ink", on); }),
                    act.settingsPanel.settingsSwitchItem(R.drawable.ic_text, "Text boxes", null,
                            text, on -> { text = on; save("text", on); }),
                    act.settingsPanel.settingsSwitchItem(R.drawable.ic_image, "Images", null,
                            images, on -> { images = on; save("images", on); }),
                    act.settingsPanel.settingsSwitchItem(R.drawable.ic_palette, "Paper and lines",
                            "Page colour and ruling you set in Page style",
                            pageLook, on -> { pageLook = on; save("pageLook", on); }),
                    act.settingsPanel.settingsSwitchItem(R.drawable.ic_visibility_off, "Hidden while presenting",
                            "Items you hid from the presentation",
                            presentHidden, on -> { presentHidden = on; save("presentHidden", on); })));

            // File name, saved under Downloads/Inkside.
            LinearLayout nameRow = new LinearLayout(act);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            final EditText name = act.codeEditor.editorField("File name");
            name.setText(fileName);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    fileName = s.toString();
                    if (exportButton != null) MainActivity.setActionEnabled(exportButton, !cleanName().isEmpty());
                }
            });
            nameRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView ext = act.panelHint(".pdf");
            ext.setPadding(act.dp(MainActivity.SPACE_SM), 0, 0, 0);
            nameRow.addView(ext);
            col.addView(act.settingsPanel.settingsGroup("Save as",
                    act.settingsPanel.settingsBlock(R.drawable.ic_description, "File name",
                            "Saved to Downloads/Inkside on this tablet", nameRow)));

            footer.removeAllViews();
            footer.addView(textButton("Cancel", this::close));
            exportButton = filledButton("Export", R.drawable.ic_download, this::start);
            footer.addView(exportButton);
            footer.setVisibility(View.VISIBLE);
            col.setPadding(col.getPaddingLeft(), col.getPaddingTop(), col.getPaddingRight(), 0);
            return col;
        }

        void save(String key, boolean on) {
            prefs.edit().putBoolean(PREF_EXPORT + key, on).apply();
        }

        String cleanName() {
            String n = fileName == null ? "" : fileName.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
            if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) n = n.substring(0, n.length() - 4);
            return n.trim();
        }

        int[] chosenPages() {
            if (pagesMode == 1) return new int[] {currentPage};
            if (pagesMode == 2) {
                int a = Math.min(from[0], to[0]) - 1;
                int b = Math.max(from[0], to[0]) - 1;
                int[] out = new int[b - a + 1];
                for (int i = 0; i < out.length; i++) out[i] = a + i;
                return out;
            }
            return null;
        }

        void refreshSummary() {
            if (pagesSummary == null) return;
            int n = pagesMode == 0 ? pageCount : pagesMode == 1 ? 1 : Math.abs(to[0] - from[0]) + 1;
            pagesSummary.setText(n + (n == 1 ? " page" : " pages") + " of " + pageCount);
        }

        /** From and To, each with − and + buttons. */
        View rangeSteppers() {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, act.dp(MainActivity.SPACE_MD), 0, 0);
            row.addView(stepper("From", from), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(stepper("To", to), lp);
            return row;
        }

        View stepper(String label, int[] value) {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(act.dp(999));
            bg.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
            box.setBackground(bg);
            box.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS));
            TextView l = new TextView(act);
            l.setText(label);
            l.setTextColor(act.M3_ON_SURFACE_VARIANT);
            l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            box.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final TextView num = new TextView(act);
            num.setText(String.valueOf(value[0]));
            num.setTextColor(act.M3_ON_SURFACE);
            num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            num.setGravity(Gravity.CENTER);
            num.setMinWidth(act.dp(32));
            ImageView minus = roundIcon(R.drawable.ic_remove, label + " earlier", () -> {
                if (value[0] <= 1) return;
                value[0]--;
                num.setText(String.valueOf(value[0]));
                Motion.pop(num);
                refreshSummary();
            });
            ImageView plus = roundIcon(R.drawable.ic_add, label + " later", () -> {
                if (value[0] >= pageCount) return;
                value[0]++;
                num.setText(String.valueOf(value[0]));
                Motion.pop(num);
                refreshSummary();
            });
            box.addView(minus, new LinearLayout.LayoutParams(act.dp(36), act.dp(36)));
            box.addView(num);
            box.addView(plus, new LinearLayout.LayoutParams(act.dp(36), act.dp(36)));
            return box;
        }

        // ---- Progress ---------------------------------------------------------------

        void start() {
            final String name = cleanName();
            if (name.isEmpty() || running) return;
            act.hideSoftKeyboard();
            running = true;
            overlay.setOnClickListener(null);
            setBody(buildProgress(), true);

            CodeCanvasView.ExportOptions opts = new CodeCanvasView.ExportOptions();
            opts.pages = chosenPages();
            opts.ink = ink;
            opts.text = text;
            opts.images = images;
            opts.pageLook = pageLook;
            opts.presentHidden = presentHidden;
            final int total = opts.pages != null ? opts.pages.length : pageCount;

            setStep(0);
            progressDetail.setText("Page 0 of " + total);
            // Drawing pages is the first 40% of the bar, the Mac 40–90%, saving the rest.
            act.canvas.exportAnnotationLayer(opts, (done, of) -> {
                bar.setProgress(0.4f * done / of);
                progressDetail.setText("Page " + done + " of " + of);
            }, layer -> {
                if (act.isDead()) return;
                if (layer.pdf == null) {
                    fail(layer.error);
                    return;
                }
                setStep(1);
                progressDetail.setText(act.workspace.isLocal()
                        ? "Merging " + formatBytes(layer.pdf.length) + " of annotations"
                        : "Sending " + formatBytes(layer.pdf.length) + " to " + act.computers.workspaceName());
                act.workspace.flattenPdf(path, layer.filePages, layer.pdf, f -> {
                    bar.setProgress(0.4f + 0.5f * (float) f);
                    if (f >= 0.5) progressDetail.setText("Stamping your notes onto the pages");
                }, new BridgeClient.Callback<byte[]>() {
                    @Override
                    public void onSuccess(byte[] data) {
                        if (act.isDead()) return;
                        setStep(2);
                        bar.setProgress(0.92f);
                        progressDetail.setText(formatBytes(data.length));
                        new Thread(() -> {
                            FileTransfers.SavedFile saved = null;
                            String error = null;
                            try {
                                saved = act.transfers.saveToDownloads(name + ".pdf", "application/pdf", data);
                            } catch (Exception e) {
                                Log.w(MainActivity.TAG, "export failed", e);
                                error = e.getMessage();
                            }
                            final FileTransfers.SavedFile s = saved;
                            final String err = error;
                            act.runOnUiThread(() -> {
                                if (act.isDead()) return;
                                if (s != null) succeed(s);
                                else fail(err);
                            });
                        }, "cc-export").start();
                    }

                    @Override
                    public void onError(String message) {
                        if (!act.isDead()) fail(message);
                    }
                });
            });
        }

        View buildProgress() {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL));

            progressTitle = new TextView(act);
            progressTitle.setText("Exporting…");
            progressTitle.setTextColor(act.M3_ON_SURFACE);
            progressTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            col.addView(progressTitle);
            TextView file = act.panelHint(cleanName() + ".pdf");
            file.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            file.setPadding(0, act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_XL));
            col.addView(file);

            bar = new Material3ProgressBar(act);
            bar.applyColors(act.M3_PRIMARY, act.M3_SURFACE_CONTAINER_HIGHEST);
            col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(8)));

            LinearLayout steps = new LinearLayout(act);
            steps.setOrientation(LinearLayout.VERTICAL);
            steps.setPadding(0, act.dp(MainActivity.SPACE_XL), 0, 0);
            String[] labels = {"Drawing your pages",
                    act.workspace.isLocal() ? "Merging" : "Merging on " + act.computers.workspaceName(),
                    "Saving to Downloads"};
            for (int i = 0; i < 3; i++) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(act.dp(40));
                ImageView icon = new ImageView(act);
                stepIcons[i] = icon;
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(act.dp(20), act.dp(20));
                ilp.rightMargin = act.dp(MainActivity.SPACE_LG);
                row.addView(icon, ilp);
                TextView l = new TextView(act);
                l.setText(labels[i]);
                l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                stepLabels[i] = l;
                row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                steps.addView(row, MainActivity.matchWrap());
            }
            col.addView(steps, MainActivity.matchWrap());
            progressDetail = act.panelHint("");
            progressDetail.setPadding(act.dp(20 + MainActivity.SPACE_LG), 0, 0, 0);
            col.addView(progressDetail);
            styleSteps();

            LinearLayout buttons = buttonRow();
            buttons.addView(textButton("Hide", this::close));
            col.addView(buttons, MainActivity.matchWrap());
            return col;
        }

        void setStep(int s) {
            int prev = step;
            step = s;
            styleSteps();
            // The step just finished lands its check.
            if (stepPulse != null) {
                stepPulse.cancel();
                stepPulse = null;
            }
            for (ImageView icon : stepIcons) if (icon != null) icon.setAlpha(1f);
            if (prev >= 0 && prev < s && prev < 3) Motion.pop(stepIcons[prev]);
            if (s >= 0 && s < 3) stepPulse = act.settingsPanel.pulse(stepIcons[s]);
            // Move the detail line under the running step.
            if (progressDetail != null && progressDetail.getParent() instanceof ViewGroup) {
                ViewGroup col = (ViewGroup) progressDetail.getParent();
                ViewGroup steps = (ViewGroup) stepIcons[0].getParent().getParent();
                col.removeView(progressDetail);
                steps.addView(progressDetail, Math.min(steps.getChildCount(), s + 1));
            }
        }

        void styleSteps() {
            for (int i = 0; i < 3; i++) {
                ImageView icon = stepIcons[i];
                if (icon == null) continue;
                boolean done = i < step;
                boolean now = i == step;
                if (done || now) {
                    icon.setImageResource(done ? R.drawable.ic_check_circle : R.drawable.ic_sync);
                    icon.setColorFilter(act.M3_PRIMARY, PorterDuff.Mode.SRC_IN);
                    icon.setBackground(null);
                } else {
                    // Pending: an empty ring.
                    icon.setImageDrawable(null);
                    GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setStroke(act.dp(2), act.M3_OUTLINE_VARIANT);
                    icon.setBackground(ring);
                }
                stepLabels[i].setTextColor(done || now ? act.M3_ON_SURFACE : act.M3_ON_SURFACE_VARIANT);
                stepLabels[i].setTypeface(Typeface.create(
                        now ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            }
        }

        // ---- Result -----------------------------------------------------------------

        void succeed(FileTransfers.SavedFile saved) {
            running = false;
            setStep(3);
            bar.setProgress(1f);
            if (hidden || overlay.getParent() == null) {
                act.statusToast("Saved to Downloads/Inkside/" + saved.name);
                return;
            }
            bar.postDelayed(() -> {
                if (overlay.getParent() == null) return;
                setBody(buildResult(saved), true);
            }, 450);
        }

        void fail(String message) {
            running = false;
            String msg = message != null && !message.isEmpty() ? message : "something went wrong";
            if (hidden || overlay.getParent() == null) {
                act.statusToast("Export failed: " + msg);
                return;
            }
            overlay.setOnClickListener(v -> close());
            setBody(buildFailure(msg), true);
        }

        View buildResult(FileTransfers.SavedFile saved) {
            overlay.setOnClickListener(v -> close());
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL));

            FrameLayout badge = new FrameLayout(act);
            GradientDrawable bb = new GradientDrawable();
            bb.setShape(GradientDrawable.OVAL);
            bb.setColor(act.M3_PRIMARY_CONTAINER);
            badge.setBackground(bb);
            ImageView check = new ImageView(act);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(act.M3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(act.dp(36), act.dp(36));
            clp.gravity = Gravity.CENTER;
            badge.addView(check, clp);
            col.addView(badge, new LinearLayout.LayoutParams(act.dp(72), act.dp(72)));
            badge.setScaleX(0.5f);
            badge.setScaleY(0.5f);
            badge.animate().scaleX(1f).scaleY(1f).setStartDelay(80)
                    .setDuration(Motion.ENTER_MS + 80).setInterpolator(Motion.LAND).start();
            check.setRotation(-30f);
            check.setScaleX(0f);
            check.setScaleY(0f);
            check.animate().rotation(0f).scaleX(1f).scaleY(1f).setStartDelay(200)
                    .setDuration(Motion.ENTER_MS).setInterpolator(Motion.LAND).start();
            badge.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);

            TextView title = new TextView(act);
            title.setText("Exported");
            title.setTextColor(act.M3_ON_SURFACE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_XS));
            col.addView(title);
            TextView where = act.panelHint("Downloads/Inkside/" + saved.name);
            where.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            where.setGravity(Gravity.CENTER);
            col.addView(where);

            LinearLayout buttons = buttonRow();
            buttons.setGravity(Gravity.CENTER);
            buttons.addView(textButton("Done", this::close));
            buttons.addView(tonalButton("Share", R.drawable.ic_share, () -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/pdf");
                send.putExtra(Intent.EXTRA_STREAM, saved.uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    act.startActivity(Intent.createChooser(send, "Share PDF"));
                } catch (Exception e) {
                    act.statusToast("Nothing to share with");
                }
            }));
            buttons.addView(filledButton("Open", R.drawable.ic_open_in_new, () -> {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(saved.uri, "application/pdf");
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    act.startActivity(view);
                    close();
                } catch (Exception e) {
                    act.statusToast("No app to open PDFs");
                }
            }));
            col.addView(buttons, MainActivity.matchWrap());
            return col;
        }

        View buildFailure(String message) {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL));
            ImageView icon = new ImageView(act);
            icon.setImageResource(R.drawable.ic_error);
            icon.setColorFilter(0xFFF2B8B5, PorterDuff.Mode.SRC_IN);
            col.addView(icon, new LinearLayout.LayoutParams(act.dp(40), act.dp(40)));
            TextView title = new TextView(act);
            title.setText("Export failed");
            title.setTextColor(act.M3_ON_SURFACE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            title.setPadding(0, act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_XS));
            col.addView(title);
            TextView why = act.panelHint(message);
            why.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            col.addView(why);
            LinearLayout buttons = buttonRow();
            buttons.addView(textButton("Close", this::close));
            buttons.addView(filledButton("Try again", R.drawable.ic_restart,
                    () -> setBody(buildOptions(), true)));
            col.addView(buttons, MainActivity.matchWrap());
            return col;
        }

        // ---- Pieces -------------------------------------------------------------------

        View header(String titleText, String sub) {
            LinearLayout header = new LinearLayout(act);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(act.dp(MainActivity.SPACE_SM), 0, 0, 0);
            LinearLayout titles = new LinearLayout(act);
            titles.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(act);
            t.setText(titleText);
            t.setTextColor(act.M3_ON_SURFACE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            titles.addView(t);
            TextView s = act.panelHint(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            titles.addView(s);
            header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView x = roundIcon(R.drawable.ic_close, "Close", this::close);
            header.addView(x, new LinearLayout.LayoutParams(act.dp(48), act.dp(48)));
            return header;
        }

        LinearLayout buttonRow() {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.setPadding(0, act.dp(MainActivity.SPACE_XL), 0, 0);
            return row;
        }

        ImageView roundIcon(int res, String description, Runnable onTap) {
            ImageView v = new ImageView(act);
            v.setImageResource(res);
            v.setColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN);
            v.setScaleType(ImageView.ScaleType.CENTER);
            v.setContentDescription(description);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x00000000);
            v.setBackground(act.withHoverRipple(bg, true));
            v.setClickable(true);
            v.setOnClickListener(x -> onTap.run());
            return v;
        }

        TextView textButton(String label, Runnable onTap) {
            TextView b = pillButton(label, 0, 0x00000000, act.M3_PRIMARY, onTap);
            return b;
        }

        TextView tonalButton(String label, int icon, Runnable onTap) {
            return pillButton(label, icon, act.M3_SURFACE_CONTAINER_HIGHEST, act.M3_ON_SURFACE, onTap);
        }

        TextView filledButton(String label, int icon, Runnable onTap) {
            return pillButton(label, icon, act.M3_PRIMARY_CONTAINER, act.M3_ON_PRIMARY_CONTAINER, onTap);
        }

        TextView pillButton(String label, int icon, int fill, int fg, Runnable onTap) {
            TextView b = new TextView(act);
            b.setText(label);
            b.setTextColor(fg);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setGravity(Gravity.CENTER);
            b.setPadding(act.dp(icon != 0 ? MainActivity.SPACE_LG : MainActivity.SPACE_LG + 2), 0, act.dp(MainActivity.SPACE_XL), 0);
            if (icon != 0) {
                android.graphics.drawable.Drawable d = act.getDrawable(icon).mutate();
                d.setTint(fg);
                d.setBounds(0, 0, act.dp(18), act.dp(18));
                b.setCompoundDrawablesRelative(d, null, null, null);
                b.setCompoundDrawablePadding(act.dp(MainActivity.SPACE_SM));
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(act.dp(999));
            bg.setColor(fill);
            b.setBackground(act.withHoverRipple(bg, false));
            b.setMinHeight(act.dp(40));
            b.setOnClickListener(v -> {
                if (v.isEnabled()) onTap.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(40));
            lp.leftMargin = act.dp(MainActivity.SPACE_SM);
            b.setLayoutParams(lp);
            return b;
        }
    }

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return Math.round(n / 1024f) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024f * 1024f));
    }
}
