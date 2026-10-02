package me.hapke.inkside;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Search across the PDFs' text and handwriting.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class PdfSearchDialog {
    private final MainActivity act;

    // ---- Search the PDFs' text ---------------------------------------------------

    private String lastPdfSearch = "";
    private boolean lastPdfSearchAll = false;

    PdfSearchDialog(MainActivity act) {
        this.act = act;
    }

    /**
     * Search panel: text in the open PDF or in every PDF in the workspace. A result
     * opens its document at the page and highlights the match. Handwriting is not
     * searched (yet).
     */
    void showPdfSearch() {
        if (act.rootLayout == null || act.bridge == null) return;
        if (act.canvas != null) act.canvas.clearSearchHits();
        final View[] shell = new View[1];
        final Runnable dismiss = () -> {
            act.hideSoftKeyboard();
            if (shell[0] != null && shell[0].getParent() != null) act.rootLayout.removeView(shell[0]);
        };
        View[] built = act.buildPanelShell(0.6f, 0.78f, dismiss);
        shell[0] = built[0];
        LinearLayout card = (LinearLayout) built[1];
        // The keyboard is up while typing: sit near the top rather than centred.
        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) card.getLayoutParams();
        clp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        clp.topMargin = act.statusBarHeight() + act.dp(MainActivity.SPACE_XL);
        card.setLayoutParams(clp);

        card.addView(act.panelTitle("Search"));

        final EditText input = new EditText(act);
        final boolean inkSearch = act.handwriting != null && act.handwriting.isEnabled();
        input.setHint(inkSearch ? "Search PDFs and handwriting" : "Search text in PDFs");
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setTextColor(act.M3_ON_SURFACE);
        input.setHintTextColor(act.M3_ON_SURFACE_VARIANT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setPadding(act.dp(MainActivity.SPACE_XL + 2), act.dp(MainActivity.SPACE_LG + 2), act.dp(MainActivity.SPACE_XL + 2), act.dp(MainActivity.SPACE_LG + 2));
        GradientDrawable field = new GradientDrawable();
        field.setCornerRadius(act.dp(999));
        field.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
        input.setBackground(field);
        input.setText(lastPdfSearch);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = act.dp(MainActivity.SPACE_LG);
        card.addView(input, inLp);

        LinearLayout scopeRow = new LinearLayout(act);
        scopeRow.setOrientation(LinearLayout.HORIZONTAL);
        scopeRow.setGravity(Gravity.CENTER_VERTICAL);
        scopeRow.setPadding(0, act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_SM));
        final boolean hasDoc = act.canvas != null && act.canvas.hasDocument();
        final boolean[] all = {lastPdfSearchAll || !hasDoc};
        final TextView thisDoc = act.panelAction("This document", !all[0], () -> {});
        final TextView everywhere = act.panelAction("All PDFs", all[0], () -> {});
        if (!hasDoc) MainActivity.setActionEnabled(thisDoc, false);
        scopeRow.addView(thisDoc);
        scopeRow.addView(everywhere);
        final TextView status = act.panelHint("");
        status.setPadding(act.dp(MainActivity.SPACE_MD), 0, 0, 0);
        scopeRow.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(scopeRow);

        final LinearLayout results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        // Handwriting first: it is what the PDF text search can never find.
        final LinearLayout inkResults = new LinearLayout(act);
        inkResults.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout pdfResults = new LinearLayout(act);
        pdfResults.setOrientation(LinearLayout.VERTICAL);
        results.addView(inkResults);
        results.addView(pdfResults);
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(results, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final int[] generation = {0};
        final Runnable run = () -> {
            String q = input.getText().toString().trim();
            lastPdfSearch = q;
            lastPdfSearchAll = all[0];
            final int gen = ++generation[0];
            inkResults.removeAllViews();
            pdfResults.removeAllViews();
            if (q.length() < 2) {
                status.setText(q.isEmpty() ? "" : "Type at least two letters");
                return;
            }
            status.setText("Searching…");
            String only = all[0] || act.canvas == null ? null : act.canvas.getDocumentPath();
            if (inkSearch) {
                act.handwriting.search(q, only, hits -> {
                    if (act.isDead() || gen != generation[0]) return;
                    showInkSearchResults(q, hits, inkResults, dismiss);
                });
            }
            act.workspace.searchPdfs(q, only, new BridgeClient.Callback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject value) {
                    if (act.isDead() || gen != generation[0]) return;
                    showPdfSearchResults(value, pdfResults, status, dismiss);
                }

                @Override
                public void onError(String message) {
                    if (act.isDead() || gen != generation[0]) return;
                    status.setText("Search failed: " + message);
                }
            });
        };
        final Runnable styleScope = () -> {
            styleScopeChip(thisDoc, !all[0]);
            styleScopeChip(everywhere, all[0]);
            if (!hasDoc) MainActivity.setActionEnabled(thisDoc, false);
        };
        thisDoc.setOnClickListener(v -> {
            if (!v.isEnabled() || !all[0]) return;
            all[0] = false;
            styleScope.run();
            run.run();
        });
        everywhere.setOnClickListener(v -> {
            if (all[0]) return;
            all[0] = true;
            styleScope.run();
            run.run();
        });
        styleScope.run();
        // Search as you type, after a short pause.
        final Runnable[] pending = new Runnable[1];
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (pending[0] != null) input.removeCallbacks(pending[0]);
                pending[0] = run;
                input.postDelayed(run, 350);
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (pending[0] != null) input.removeCallbacks(pending[0]);
            run.run();
            return true;
        });

        act.rootLayout.addView(shell[0], new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        input.requestFocus();
        input.setSelection(input.getText().length());
        input.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
        }, 120);
        if (!lastPdfSearch.isEmpty()) run.run();
    }

    private void styleScopeChip(TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(999));
        if (on) {
            bg.setColor(act.M3_PRIMARY_CONTAINER);
            t.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(act.dp(1), act.M3_OUTLINE_VARIANT);
            t.setTextColor(act.M3_PRIMARY);
        }
        t.setBackground(bg);
    }

    private void showPdfSearchResults(JSONObject value, LinearLayout results, TextView status,
                                      Runnable dismiss) {
        results.removeAllViews();
        JSONArray files = value.optJSONArray("files");
        int total = value.optInt("total", 0);
        if (files == null || files.length() == 0) {
            status.setText("No matches");
            return;
        }
        status.setText(total + (total == 1 ? " match" : " matches")
                + (files.length() > 1 ? " in " + files.length() + " PDFs" : "")
                + (value.optBoolean("truncated") ? " (first ones shown)" : ""));
        for (int f = 0; f < files.length(); f++) {
            JSONObject file = files.optJSONObject(f);
            if (file == null) continue;
            final String path = file.optString("path", "");
            JSONArray matches = file.optJSONArray("matches");
            if (matches == null) continue;

            TextView head = new TextView(act);
            String name = path.substring(path.lastIndexOf('/') + 1);
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            head.setText(name + (dir.isEmpty() ? "" : "   " + dir) + "   · " + matches.length());
            head.setTextColor(act.M3_ON_SURFACE);
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.setSingleLine(true);
            head.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            head.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(f == 0 ? MainActivity.SPACE_SM : MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_XS));
            results.addView(head);

            for (int m = 0; m < matches.length(); m++) {
                final JSONObject match = matches.optJSONObject(m);
                if (match == null) continue;
                final int page = match.optInt("page", 0);
                String snippet = match.optString("snippet", "");
                int start = Math.max(0, Math.min(snippet.length(), match.optInt("start", 0)));
                int end = Math.max(start, Math.min(snippet.length(), start + match.optInt("length", 0)));
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                sb.append("p. ").append(String.valueOf(page + 1)).append("   ");
                sb.setSpan(new android.text.style.ForegroundColorSpan(act.M3_PRIMARY), 0, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int off = sb.length();
                sb.append(snippet);
                if (end > start) {
                    sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), off + start, off + end,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.BackgroundColorSpan(0x55FFC400), off + start,
                            off + end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                TextView row = new TextView(act);
                row.setText(sb);
                row.setTextColor(act.M3_ON_SURFACE_VARIANT);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                row.setMaxLines(2);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD));
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setCornerRadius(act.dp(12));
                rowBg.setColor(0x00000000);
                row.setBackground(act.withHoverRipple(rowBg, false));
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    dismiss.run();
                    openSearchHit(path, page, match.optJSONArray("rects"));
                });
                results.addView(row);
            }
        }
    }

    /** Handwritten lines matching the search, grouped by document. */
    private void showInkSearchResults(String query, List<HandwritingIndex.Hit> hits,
                                      LinearLayout into, Runnable dismiss) {
        into.removeAllViews();
        if (hits.isEmpty()) return;
        TextView section = new TextView(act);
        section.setText("Handwriting · " + hits.size() + (hits.size() == 1 ? " match" : " matches"));
        section.setTextColor(act.M3_PRIMARY);
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        section.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        section.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), 0);
        into.addView(section);
        String needle = HandwritingIndex.normalize(query).trim();
        String lastPath = null;
        for (final HandwritingIndex.Hit hit : hits) {
            if (!hit.path.equals(lastPath)) {
                lastPath = hit.path;
                TextView head = new TextView(act);
                String name = hit.path.substring(hit.path.lastIndexOf('/') + 1);
                head.setText(name);
                head.setTextColor(act.M3_ON_SURFACE);
                head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                head.setSingleLine(true);
                head.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                head.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_XS));
                into.addView(head);
            }
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append("p. ").append(String.valueOf(hit.page + 1)).append("   ");
            sb.setSpan(new android.text.style.ForegroundColorSpan(act.M3_PRIMARY), 0, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int off = sb.length();
            sb.append(hit.text);
            // Normalising keeps length for most text; only highlight when it lines up.
            String norm = HandwritingIndex.normalize(hit.text);
            int at = norm.length() == hit.text.length() ? norm.indexOf(needle) : -1;
            if (at >= 0 && !needle.isEmpty()) {
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), off + at,
                        off + at + needle.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.BackgroundColorSpan(0x55FFC400), off + at,
                        off + at + needle.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView row = new TextView(act);
            row.setText(sb);
            row.setTextColor(act.M3_ON_SURFACE_VARIANT);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setMaxLines(2);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(act.dp(12));
            rowBg.setColor(0x00000000);
            row.setBackground(act.withHoverRipple(rowBg, false));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                dismiss.run();
                JSONArray rects = new JSONArray();
                try {
                    rects.put(new JSONArray().put(hit.rect[0]).put(hit.rect[1])
                            .put(hit.rect[2]).put(hit.rect[3]));
                } catch (Exception ignored) {
                }
                openSearchHit(hit.path, hit.page, rects);
            });
            into.addView(row);
        }
    }

    /** Opens {@code path} if it is not open, then shows and highlights the match. */
    private void openSearchHit(String path, int filePage, JSONArray rects) {
        if (act.canvas == null) return;
        final List<float[]> boxes = new ArrayList<>();
        if (rects != null) {
            for (int i = 0; i < rects.length(); i++) {
                JSONArray r = rects.optJSONArray(i);
                if (r == null || r.length() < 4) continue;
                boxes.add(new float[] {(float) r.optDouble(0), (float) r.optDouble(1),
                        (float) r.optDouble(2), (float) r.optDouble(3)});
            }
        }
        Runnable show = () -> {
            if (act.canvas != null) act.canvas.showSearchHit(filePage, boxes);
        };
        if (path.equals(act.canvas.getDocumentPath())) {
            show.run();
        } else {
            // After the camera the document restores for itself has settled.
            act.documents.openPdfDocument(path, () -> act.canvas.post(show));
        }
    }
}
