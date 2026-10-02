package me.hapke.inkside;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pages dialog: reorder, duplicate, insert and delete pages of the open PDF.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class PageOrganizerDialog {
    private final MainActivity act;

    private boolean pageOrganizerBusy;

    PageOrganizerDialog(MainActivity act) {
        this.act = act;
    }

    /**
     * The page organizer: every page as a thumbnail, to reorder by dragging, and to
     * duplicate, delete or add blank pages. Nothing changes until Done, which moves
     * the ink with its pages and rewrites the PDF on the Mac in one step (the old file
     * goes to Recently deleted).
     */
    void showPageOrganizer() {
        if (act.rootLayout == null || act.canvas == null) return;
        if (!act.canvas.hasDocument()) {
            act.statusToastShort("Open a document first");
            return;
        }
        if (act.canvas.hasActiveSelection()) act.canvas.clearSelection();
        final int count = act.canvas.getDocumentPageCount();
        final float[] size = act.canvas.documentPageSize();
        final View[] shell = new View[2];
        final PageOrganizerView[] gridRef = new PageOrganizerView[1];
        final Runnable dismiss = () -> {
            if (shell[0] != null && shell[0].getParent() != null) act.rootLayout.removeView(shell[0]);
            if (gridRef[0] != null) gridRef[0].recycleThumbs();
        };
        final Runnable[] refresh = new Runnable[1];
        final PageOrganizerView grid = new PageOrganizerView(act, new PageOrganizerView.Host() {
            @Override
            public void loadThumb(int source, java.util.function.Consumer<Bitmap> done) {
                if (act.canvas == null || act.isDead()) {
                    done.accept(null);
                    return;
                }
                act.canvas.renderPage(source, act.dp(200), act.dp(200), true, done);
            }

            @Override
            public void openPage(int position, int source) {
                if (gridRef[0] != null && gridRef[0].isChanged()) {
                    act.statusToastShort("Press Done to apply the new order first");
                    return;
                }
                dismiss.run();
                if (act.canvas != null && source >= 0) act.canvas.revealDocumentPage(source);
            }

            @Override
            public void changed() {
                if (refresh[0] != null) refresh[0].run();
            }
        });
        gridRef[0] = grid;
        grid.setColors(act.M3_PRIMARY, act.M3_ON_SURFACE, act.M3_OUTLINE_VARIANT, 0xFFFFFFFF);
        grid.reset(count, size[1] / Math.max(1f, size[0]));

        View[] built = act.buildPanelShell(0.86f, 0.86f, () -> {
            if (grid.isChanged()) {
                act.statusToastShort("Press Done to apply, or Cancel to discard");
            } else {
                dismiss.run();
            }
        });
        shell[0] = built[0];
        LinearLayout card = (LinearLayout) built[1];

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(act.panelTitle("Pages"));
        final TextView summary = act.panelHint("");
        summary.setPadding(act.dp(MainActivity.SPACE_XL), 0, 0, 0);
        header.addView(summary, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(header);


        android.widget.HorizontalScrollView toolScroll = new android.widget.HorizontalScrollView(act);
        toolScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        toolScroll.addView(tools);
        final TextView selectAll = act.panelAction("Select all", false, () -> {});
        selectAll.setOnClickListener(v -> grid.selectAll(grid.selectedCount() < grid.pageCount()));
        final TextView duplicate = act.panelAction("Duplicate", false, grid::duplicateSelected);
        final TextView delete = act.panelAction("Delete", false, () -> {
            if (!grid.deleteSelected()) act.statusToastShort("A document needs at least one page");
        });
        delete.setTextColor(0xFFE57373);
        tools.addView(selectAll);
        tools.addView(duplicate);
        tools.addView(delete);
        LinearLayout.LayoutParams toolLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolLp.topMargin = act.dp(MainActivity.SPACE_LG);
        card.addView(toolScroll, toolLp);

        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = act.dp(MainActivity.SPACE_MD);
        gridLp.bottomMargin = act.dp(MainActivity.SPACE_MD);
        GradientDrawable well = new GradientDrawable();
        well.setCornerRadius(act.dp(18));
        well.setColor(act.M3_SURFACE);
        grid.setBackground(well);
        grid.setClipToOutline(true);
        card.addView(grid, gridLp);

        LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        final TextView status = act.panelHint("");
        footer.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView cancel = act.panelAction("Cancel", false, dismiss);
        final TextView done = act.panelAction("Done", true, () -> {});
        done.setOnClickListener(v -> {
            if (!v.isEnabled()) return;
            if (!grid.isChanged()) {
                dismiss.run();
                return;
            }
            MainActivity.setActionEnabled(done, false);
            MainActivity.setActionEnabled(cancel, false);
            status.setText("Rearranging pages…");
            applyPageOrder(grid.order(), err -> {
                if (err == null) {
                    dismiss.run();
                    act.statusToastShort("Pages rearranged");
                } else {
                    status.setText("Could not rearrange: " + err);
                    MainActivity.setActionEnabled(done, true);
                    MainActivity.setActionEnabled(cancel, true);
                }
            });
        });
        footer.addView(cancel);
        footer.addView(done);
        card.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refresh[0] = () -> {
            int n = grid.pageCount();
            int sel = grid.selectedCount();
            summary.setText(n + (n == 1 ? " page" : " pages")
                    + (sel > 0 ? " · " + sel + " selected" : ""));
            boolean any = sel > 0;
            MainActivity.setActionEnabled(duplicate, any);
            MainActivity.setActionEnabled(delete, any);
            selectAll.setText(sel == n && n > 0 ? "Select none" : "Select all");
            if (!pageOrganizerBusy) MainActivity.setActionEnabled(done, true);
        };
        refresh[0].run();
        act.rootLayout.addView(shell[0], new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Applies a page order from the organizer ({@code order[j]}: the current page that
     * becomes page j, or −1 for a new blank page). The PDF is rewritten first; only
     * when that worked does the ink move with it, so a failure leaves everything as
     * it was. {@code done} gets null on success, else a short reason.
     */
    private void applyPageOrder(int[] order, java.util.function.Consumer<String> done) {
        if (act.canvas == null || !act.canvas.hasDocument() || act.bridge == null) {
            done.accept("no document");
            return;
        }
        final String path = act.canvas.getDocumentPath();
        final boolean blankOwned = act.canvas.isBlankOwnedDocument();
        final int n = order.length;
        final int[] fileOrder = new int[n];
        for (int j = 0; j < n; j++) {
            fileOrder[j] = order[j] >= 0 ? act.canvas.documentFilePageFor(order[j]) : -1;
        }
        final float[] size = act.canvas.documentPageSize();
        pageOrganizerBusy = true;
        final BridgeClient.Callback<byte[]> reopen = new BridgeClient.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] bytes) {
                pageOrganizerBusy = false;
                if (act.isDead() || act.canvas == null) return;
                if (!path.equals(act.canvas.getDocumentPath())) {
                    done.accept("the document changed meanwhile");
                    return;
                }
                try {
                    act.canvas.applyPageOrder(order);
                    // Every page is now in the file, in order: no app-only blanks in front.
                    act.canvas.openDocument(act, path, bytes, n, blankOwned, 0);
                } catch (Exception e) {
                    done.accept(e.getMessage() != null ? e.getMessage() : "reopen failed");
                    return;
                }
                act.persistence.scheduleSave();
                act.canvasAgent.pushCanvasStateToBridge();
                done.accept(null);
            }

            @Override
            public void onError(String message) {
                pageOrganizerBusy = false;
                if (!act.isDead()) done.accept(message);
            }
        };
        if (blankOwned) {
            // An app-made blank document: every page is blank, so a fresh one does.
            new Thread(() -> {
                try {
                    byte[] bytes = PdfDocumentIo.createBlankA4(n);
                    act.workspace.writeFileBytesSync(path, bytes);
                    act.runOnUiThread(() -> reopen.onSuccess(bytes));
                } catch (Exception e) {
                    act.runOnUiThread(() -> reopen.onError(e.getMessage()));
                }
            }, "cc-pages").start();
        } else {
            act.workspace.reorderPdf(path, fileOrder, size[0], size[1], reopen);
        }
    }
}
