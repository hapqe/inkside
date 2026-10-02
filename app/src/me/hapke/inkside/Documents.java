package me.hapke.inkside;

import android.content.Context;
import android.util.Log;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * The open PDF document: loading (with cache), reloading, creating, adding pages, undo history and ink index.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class Documents {
    private final MainActivity act;

    private final Runnable inkIndexRunnable = this::runInkIndex;
    /** Pause after the last ink change before the open document is re-read. */
    private static final long INK_INDEX_DELAY_MS = 2500L;

    Documents(MainActivity act) {
        this.act = act;
    }

    private java.io.File pdfCacheFile(String relPath) {
        return new java.io.File(act.getCacheDir(),
                "pdfcache-" + Profiles.cacheTag(act.profile) + Integer.toHexString(relPath.hashCode()) + ".pdf");
    }

    private String pdfCacheEtag(String relPath) {
        try {
            return act.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("pdfEtag:" + Profiles.cacheTag(act.profile) + relPath, null);
        } catch (Exception e) {
            return null;
        }
    }

    private void storePdfCacheEtag(String relPath, String etag) {
        try {
            act.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("pdfEtag:" + Profiles.cacheTag(act.profile) + relPath, etag == null ? "" : etag).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Fetch a PDF's bytes, reusing the local copy when the bridge says it is current.
     *
     * <p>Opening a document re-downloaded the whole file every time: 751ms of an 834ms
     * open for a 2.3MB PDF, against a copy already sitting in the cache directory. The
     * request is now conditional, so an unchanged file costs one round trip and the
     * bytes come off disk. The bridge stays the source of truth — a changed file still
     * transfers, because the ETag is its size and mtime.
     */
    void readPdfBytesCached(String path, BridgeClient.Callback<byte[]> cb) {
        if (act.bridge == null) {
            cb.onError("workspace unavailable");
            return;
        }
        final java.io.File cache = pdfCacheFile(path);
        final String known = cache.isFile() ? pdfCacheEtag(path) : null;
        act.workspace.readFileBytesConditional(path, known, new BridgeClient.BinaryCallback() {
            @Override
            public void onSuccess(byte[] bytes, String etag) {
                if (bytes == null) {
                    byte[] cached = readCacheFile(cache);
                    if (cached != null) {
                        cb.onSuccess(cached);
                        return;
                    }
                    // Cache vanished under us — ask again without a validator.
                    storePdfCacheEtag(path, null);
                    act.workspace.readFileBytes(path, cb);
                    return;
                }
                writeCacheFile(cache, bytes);
                storePdfCacheEtag(path, etag);
                cb.onSuccess(bytes);
            }

            @Override
            public void onError(String message) {
                // Offline or the bridge is down: a cached copy still opens the document.
                byte[] cached = readCacheFile(cache);
                if (cached != null) cb.onSuccess(cached);
                else cb.onError(message);
            }
        });
    }

    static byte[] readCacheFile(java.io.File f) {
        if (f == null || !f.isFile() || f.length() <= 0) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] out = new byte[(int) f.length()];
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) return null;
                off += n;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCacheFile(java.io.File f, byte[] bytes) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(bytes);
        } catch (Exception ignored) {
        }
    }

    void openPdfDocument(String path) {
        openPdfDocument(path, null);
    }

    /** @param afterOpen runs once the document is on the canvas (not on failure). */
    void openPdfDocument(String path, Runnable afterOpen) {
        if (act.pdfTextSelection != null) act.pdfTextSelection.clear();
        if (path == null || path.isEmpty() || act.bridge == null || act.canvas == null) return;
        readPdfBytesCached(path, new BridgeClient.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] data) {
                if (act.isDead() || act.canvas == null) return;
                applyOpenedPdf(path, data);
                if (afterOpen != null && path.equals(act.canvas.getDocumentPath())) afterOpen.run();
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                // A file that cannot be read is not worth keeping on the recents list:
                // it was renamed, moved or deleted since it was last open.
                if (act.recentDocs.remove(path)) act.overflowMenu.syncQuickSwitchButton();
                act.conversations.appendChat("warn", "PDF read: " + message);
            }
        });
    }

    /** Bind fetched PDF bytes to the canvas, carrying over that document's ink and pages. */
    private void applyOpenedPdf(String path, byte[] data) {
        applyOpenedPdf(path, data, true);
    }

    /** @param stash keep the outgoing document's ink first (false when re-showing the same document). */
    private void applyOpenedPdf(String path, byte[] data, boolean stash) {
                try {
                    // Keep the outgoing document’s pages/ink before we replace the canvas.
                    if (stash) stashCurrentDocumentState();
                    org.json.JSONObject docState = act.docStore.get(path);
                    stripAgentPlacedTextFields(docState);
                    int annPages = 1;
                    boolean owned = act.appCreatedDocs.contains(path);
                    int blankPrefix = 0;
                    if (docState != null) {
                        annPages = Math.max(1, docState.optInt("pageCount", 1));
                        owned = owned || docState.optBoolean("blankOwned", false);
                        blankPrefix = Math.max(0, docState.optInt("blankPrefix", 0));
                    }
                    String current = act.canvas.getDocumentPath();
                    if (docState != null) {
                        try {
                            act.canvas.importCanvasState(docState);
                            restoreUndoHistory(path);
                            // Colour and thickness belong to the pen, not the document.
                            act.penTools.applyPenProperties();
                            if (act.canvas != null) {
                                act.canvas.setInk(act.penTools.palette()[act.selectedColorIndex],
                                        act.penTools.inkNameFor(act.selectedColorIndex));
                            }
                        } catch (Exception ignored) {}
                    } else if (current == null || !path.equals(current)) {
                        act.canvas.closeDocument();
                    }
                    act.canvas.openDocument(act, path, data, annPages, owned, blankPrefix);
                    act.overflowMenu.noteRecentDoc(path);
                    act.projects.ensureProjectForDocument(path);
                    act.persistence.scheduleSave();
                    act.canvasAgent.pushCanvasStateToBridge();
                } catch (Exception e) {
                    act.conversations.appendChat("warn", "PDF open failed: " + e.getMessage());
                }
    }

    /**
     * Another device changed the ink of the document that is open here (sync pulled its file):
     * show it. Waits while a stroke is under the pen or edits here are not saved yet, so nothing
     * being drawn is thrown away; the camera stays where it is.
     */
    void reloadOpenInkFromStore() {
        if (act.canvas == null || act.bridge == null || act.isDead()) return;
        final String path = act.canvas.getDocumentPath();
        if (path == null || path.isEmpty()) return;
        if (act.canvas.isInkInProgress() || act.saveHandler.hasCallbacks(act.saveRunnable)) {
            act.saveHandler.postDelayed(this::reloadOpenInkFromStore, 2500L);
            return;
        }
        try {
            org.json.JSONObject slice = act.docStore.get(path);
            if (slice == null) return;
            org.json.JSONObject mine = act.canvas.exportCanvasState();
            if (mine.has("matrix")) slice.put("matrix", mine.get("matrix"));
        } catch (Exception ignored) {
            return;
        }
        readPdfBytesCached(path, new BridgeClient.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] data) {
                if (act.isDead() || act.canvas == null || !path.equals(act.canvas.getDocumentPath())) return;
                applyOpenedPdf(path, data, false);
            }

            @Override
            public void onError(String message) {
                // The document could not be read just now; the next sync tries again.
            }
        });
    }

    private boolean pdfReloadInFlight;
    private boolean pdfReloadAgain;

    /**
     * The open PDF changed on the Mac (an agent rebuilt it, a script wrote it): fetch it
     * again — a conditional request, so an unchanged file costs a 304 — and reopen it in
     * place. Ink, page looks and the camera ride along through the same stash/restore
     * path as switching documents. Deferred while a stroke is under the pen.
     */
    void reloadOpenPdfIfChanged() {
        if (act.canvas == null || act.bridge == null || act.isDead()) return;
        final String path = act.canvas.getDocumentPath();
        if (path == null || path.isEmpty()) return;
        if (pdfReloadInFlight) {
            pdfReloadAgain = true;
            return;
        }
        pdfReloadInFlight = true;
        final java.io.File cache = pdfCacheFile(path);
        final String known = cache.isFile() ? pdfCacheEtag(path) : null;
        act.workspace.readFileBytesConditional(path, known, new BridgeClient.BinaryCallback() {
            @Override
            public void onSuccess(byte[] bytes, String etag) {
                pdfReloadInFlight = false;
                if (act.isDead() || act.canvas == null) return;
                if (bytes != null) {
                    writeCacheFile(cache, bytes);
                    storePdfCacheEtag(path, etag);
                    if (path.equals(act.canvas.getDocumentPath())
                            && CodeCanvasView.hashBytes(bytes) != act.canvas.getDocumentBytesHash()) {
                        applyReloadedPdf(path, bytes, 0);
                    }
                }
                if (pdfReloadAgain) {
                    pdfReloadAgain = false;
                    reloadOpenPdfIfChanged();
                }
            }

            @Override
            public void onError(String message) {
                pdfReloadInFlight = false;
                pdfReloadAgain = false;
                // Deleted or unreachable: keep showing what we have.
            }
        });
    }

    private void applyReloadedPdf(String path, byte[] bytes, int attempt) {
        if (act.isDead() || act.canvas == null || !path.equals(act.canvas.getDocumentPath())) return;
        if ((act.canvas.isInkInProgress() || act.canvas.isNavigating()) && attempt < 40) {
            act.saveHandler.postDelayed(() -> applyReloadedPdf(path, bytes, attempt + 1), 250L);
            return;
        }
        if (act.pdfTextSelection != null) act.pdfTextSelection.clear();
        applyOpenedPdf(path, bytes);
    }

    /** True when a workspace change in {@code dirs} may have touched the open PDF. */
    boolean filesEventTouchesOpenPdf(java.util.List<String> dirs) {
        if (act.canvas == null) return false;
        String doc = act.canvas.getDocumentPath();
        if (doc == null || doc.isEmpty()) return false;
        if (dirs == null || dirs.isEmpty()) return true;  // old bridge: no detail, just check
        String d = normalizeRel(doc);
        int slash = d.lastIndexOf('/');
        String parent = slash >= 0 ? d.substring(0, slash) : "";
        for (String dir : dirs) {
            if (parent.equals(normalizeRel(dir))) return true;
        }
        return false;
    }

    private static String normalizeRel(String p) {
        if (p == null) return "";
        String s = p.replace('\\', '/').trim();
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return ".".equals(s) ? "" : s;
    }

    void createPdfDocumentAt(String relPath) {
        if (act.bridge == null) return;
        final String path = relPath.startsWith("/") ? relPath.substring(1) : relPath;
        new Thread(() -> {
            try {
                byte[] bytes = PdfDocumentIo.createBlankA4(1);
                act.workspace.writeFileBytesSync(path, bytes);
                act.runOnUiThread(() -> {
                    if (act.isDead()) return;
                    act.appCreatedDocs.add(path);
                    if (act.folderExplorer != null) act.folderExplorer.onShown();
                    openPdfDocumentFresh(path, bytes, true);
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> {
                    if (!act.isDead()) act.conversations.appendChat("warn", "create PDF: " + e.getMessage());
                });
            }
        }).start();
    }

    private void openPdfDocumentFresh(String path, byte[] data, boolean blankOwned) {
        if (act.canvas == null || data == null) return;
        try {
            stashCurrentDocumentState();
            act.canvas.closeDocument();
            act.canvas.openDocument(act, path, data, 1, blankOwned, 0);
            act.overflowMenu.noteRecentDoc(path);
            act.persistence.scheduleSave();
            act.canvasAgent.pushCanvasStateToBridge();
        } catch (Exception e) {
            act.conversations.appendChat("warn", "PDF open failed: " + e.getMessage());
        }
    }

    /** Re-read the open document's handwriting once the ink has settled. */
    void scheduleInkIndex() {
        if (act.handwriting == null || !act.handwriting.isEnabled() || act.canvas == null) return;
        act.saveHandler.removeCallbacks(inkIndexRunnable);
        act.saveHandler.postDelayed(inkIndexRunnable, INK_INDEX_DELAY_MS);
    }

    private void runInkIndex() {
        if (act.handwriting == null || !act.handwriting.isEnabled() || act.canvas == null
                || !act.canvas.hasDocument() || act.canvas.isInkInProgress()) {
            return;
        }
        act.handwriting.update(act.canvas.getDocumentPath(), act.canvas.collectInkLines());
    }

    /** Write the open document's undo history, if keeping it is on. */
    void saveUndoHistory() {
        if (!act.keepUndoHistory || act.undoHistory == null || act.canvas == null || !act.canvas.hasDocument()) {
            return;
        }
        act.undoHistory.save(act.canvas.getDocumentPath(), act.canvas.captureUndoHistory());
    }

    /** Put back the history saved for {@code path}; call right after its content loads. */
    void restoreUndoHistory(String path) {
        if (!act.keepUndoHistory || act.undoHistory == null || act.canvas == null) return;
        act.canvas.restoreUndoHistory(act.undoHistory.load(path));
    }

    /** Persist the open document’s ink/pages into {@link MainActivity#docStore} (keyed by path). */
    void stashCurrentDocumentState() {
        if (act.canvas == null || !act.canvas.hasDocument()) return;
        saveUndoHistory();
        try {
            org.json.JSONObject export = act.canvas.exportCanvasState();
            String path = export.optString("documentPath", "");
            if (path.isEmpty()) return;
            act.docStore.put(path, documentSliceFromExport(export));
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "stash document state: " + e.getMessage());
        }
    }

    static org.json.JSONObject documentSliceFromExport(org.json.JSONObject export)
            throws Exception {
        org.json.JSONObject slice = new org.json.JSONObject();
        String[] keys = {
                "documentPath", "pageCount", "blankOwned", "blankPrefix",
                "pageBg", "plainPaper", "pageStyle", "pageRuleScale", "pageLooks", "webBlocks",
                "strokes", "images", "textFields", "matrix",
                "baseThicknessPx", "inkColor", "inkName", "eraseMode",
                "selectInk", "selectImages", "lassoSelectInk", "lassoSelectImages",
                "eraseSelectInk", "eraseSelectImages", "eraseSelectHighlighter", "eraseSelectText",
                "lassoSelectHighlighter", "lassoSelectText"
        };
        for (String k : keys) {
            if (export.has(k)) slice.put(k, export.get(k));
        }
        stripAgentPlacedTextFields(slice);
        return slice;
    }

    /** Drop agent-batched note ids from a document slice (mutates in place). */
    private static void stripAgentPlacedTextFields(org.json.JSONObject slice) {
        if (slice == null) return;
        org.json.JSONArray tfs = slice.optJSONArray("textFields");
        if (tfs == null) return;
        org.json.JSONArray kept = new org.json.JSONArray();
        for (int i = 0; i < tfs.length(); i++) {
            org.json.JSONObject tf = tfs.optJSONObject(i);
            if (tf == null) continue;
            if (CodeCanvasView.isAgentPlacedId(tf.optString("id", ""))) continue;
            kept.put(tf);
        }
        try {
            slice.put("textFields", kept);
        } catch (Exception ignored) {
        }
    }

    void promptCreatePdfDocument() {
        final android.widget.EditText input = new android.widget.EditText(act);
        input.setHint("untitled.pdf");
        input.setText("untitled.pdf");
        input.setSingleLine(true);
        int pad = act.dp(16);
        input.setPadding(pad, pad, pad, pad);
        new M3Dialog.Builder(act)
                .setTitle("New PDF")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (name.isEmpty()) name = "untitled.pdf";
                    if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";
                    if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
                    String rel = name;
                    if (act.activeProjectPath != null && !act.activeProjectPath.isEmpty()) {
                        rel = act.activeProjectPath + "/" + name;
                    }
                    createPdfDocumentAt(rel);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void appendDocumentPageFromOvershoot() {
        if (act.canvas == null || !act.canvas.hasDocument()) return;
        final String path = act.canvas.getDocumentPath();
        final int nextCount = act.canvas.getDocumentPageCount() + 1;
        if (act.canvas.isBlankOwnedDocument() && act.bridge != null) {
            new Thread(() -> {
                try {
                    byte[] bytes = PdfDocumentIo.createBlankA4(nextCount);
                    act.workspace.writeFileBytesSync(path, bytes);
                    act.runOnUiThread(() -> {
                        if (act.isDead() || act.canvas == null) return;
                        try {
                            // Preserve annotations: openDocument only swaps the substrate.
                            act.canvas.openDocument(act, path, bytes, nextCount, true);
                            act.persistence.scheduleSave();
                        } catch (Exception e) {
                            act.canvas.appendDocumentPage();
                            act.persistence.scheduleSave();
                        }
                    });
                } catch (Exception e) {
                    act.runOnUiThread(() -> {
                        if (act.canvas != null) {
                            act.canvas.appendDocumentPage();
                            act.persistence.scheduleSave();
                        }
                    });
                }
            }).start();
        } else {
            act.canvas.appendDocumentPage();
            act.persistence.scheduleSave();
        }
    }

    void prependDocumentPageFromOvershoot() {
        if (act.canvas == null || !act.canvas.hasDocument()) return;
        final String path = act.canvas.getDocumentPath();
        final int nextCount = act.canvas.getDocumentPageCount() + 1;
        // Shift annotations + camera first so content stays put; blankPrefix marks the
        // new page above the file so restore doesn't shove it under as a suffix blank.
        act.canvas.prependDocumentPage();
        act.persistence.scheduleSave();
        if (act.canvas.isBlankOwnedDocument() && act.bridge != null) {
            new Thread(() -> {
                try {
                    byte[] bytes = PdfDocumentIo.createBlankA4(nextCount);
                    act.workspace.writeFileBytesSync(path, bytes);
                    act.runOnUiThread(() -> {
                        if (act.isDead() || act.canvas == null) return;
                        try {
                            // Full rewrite: blank pages are real file pages → prefix 0.
                            act.canvas.openDocument(act, path, bytes, nextCount,
                                    true, 0);
                            act.persistence.scheduleSave();
                        } catch (Exception e) {
                            act.persistence.scheduleSave();
                        }
                    });
                } catch (Exception e) {
                    act.runOnUiThread(act.persistence::scheduleSave);
                }
            }).start();
        }
    }

    private void openPdfEditor(String path) {
        openPdfDocument(path);
    }

    void deleteDocumentPageAt(int pageIndex) {
        if (act.canvas == null || !act.canvas.canDeleteDocumentPage(pageIndex)) return;
        final String path = act.canvas.getDocumentPath();
        final boolean blankOwned = act.canvas.isBlankOwnedDocument();
        if (!act.canvas.deleteDocumentPage(pageIndex)) return;
        act.persistence.scheduleSave();
        if (blankOwned && act.bridge != null && path != null && !path.isEmpty()) {
            final int nextCount = Math.max(1, act.canvas.getDocumentPageCount());
            new Thread(() -> {
                try {
                    byte[] bytes = PdfDocumentIo.createBlankA4(nextCount);
                    act.workspace.writeFileBytesSync(path, bytes);
                    act.runOnUiThread(() -> {
                        if (act.isDead() || act.canvas == null) return;
                        try {
                            act.canvas.openDocument(act, path, bytes, nextCount,
                                    true, 0);
                            act.persistence.scheduleSave();
                        } catch (Exception e) {
                            act.persistence.scheduleSave();
                        }
                    });
                } catch (Exception e) {
                    act.runOnUiThread(act.persistence::scheduleSave);
                }
            }).start();
        }
    }
}
