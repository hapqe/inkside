package me.hapke.inkside;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The agent's side of the canvas: bridge events, page captures and canvas commands.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class CanvasAgent {
    private final MainActivity act;

    CanvasAgent(MainActivity act) {
        this.act = act;
    }

    void startCanvasEvents() {
        if (!act.computers.hasHost()) return; // needs a connected computer
        if (act.bridge == null) return;
        act.bridge.startEvents(new BridgeClient.EventsListener() {
            @Override
            public void onCanvasBatch(String batchId, org.json.JSONArray commands) {
                if (act.isDead()) return;
                applyCanvasBatch(batchId, commands);
            }

            @Override
            public void onFilesChanged(java.util.List<String> dirs) {
                if (act.isDead()) return;
                // Something changed on the computer: bring it over (the views reload once it has).
                if (act.remoteSync != null) {
                    act.remoteSync.requestSoon();
                    return;
                }
                onFilesChanged();
                // An agent (or a script) rewriting the open PDF shows up on the page.
                if (act.documents.filesEventTouchesOpenPdf(dirs)) act.documents.reloadOpenPdfIfChanged();
            }

            @Override
            public void onFilesChanged() {
                if (act.isDead()) return;
                if (act.folderExplorer != null) {
                    act.folderExplorer.reloadOpenFolders();
                    act.folderExplorer.reloadShared();
                }
                if (act.allProjectsView != null && act.allProjectsView.getVisibility() == View.VISIBLE) {
                    act.allProjectsView.reload();
                }
                // An agent editing an artifact's HTML should show up on the page.
                act.artifacts.reloadArtifactOverlays();
                act.codeEditor.reloadEditorViz();
            }

            @Override
            public void onEventsError(String message) {
                // Quiet reconnect — BridgeClient retries on its own.
            }

            @Override
            public void onCaptureRequest(JSONObject request) {
                if (act.isDead()) return;
                answerCaptureRequest(request);
            }
        });
    }

    // ---- Canvas awareness: screenshots of pages for the chat agent's tools -------------

    /** Long edge of a page screenshot sent to the agent (px). */
    private static final int CAPTURE_LONG_EDGE = 1600;

    private void answerCaptureRequest(JSONObject request) {
        final String id = request.optString("id", "");
        if (id.isEmpty() || act.bridge == null) return;
        final JSONObject reply = new JSONObject();
        try {
            reply.put("id", id);
            reply.put("ok", true);
            if (act.canvas == null || !act.canvas.hasDocument()) {
                reply.put("document", "");
                reply.put("images", new JSONArray());
                act.bridge.postCaptureResult(reply);
                return;
            }
            final int count = act.canvas.getDocumentPageCount();
            final int current = Math.max(0, act.canvas.currentPageIndex());
            reply.put("document", act.canvas.getDocumentPath());
            reply.put("currentPage", current + 1);
            reply.put("pageCount", count);
            if (!"pages".equals(request.optString("kind"))) {
                act.bridge.postCaptureResult(reply);
                return;
            }
            JSONObject spec = request.optJSONObject("pages");
            String mode = spec != null ? spec.optString("mode", "current") : "current";
            int max = Math.max(1, request.optInt("maxPages", 20));
            final List<Integer> pages = new ArrayList<>();
            if ("all".equals(mode)) {
                for (int i = 0; i < count; i++) pages.add(i);
            } else if ("list".equals(mode) && spec.optJSONArray("list") != null) {
                JSONArray arr = spec.optJSONArray("list");
                for (int i = 0; i < arr.length(); i++) {
                    int p = arr.optInt(i, 0) - 1;
                    if (p >= 0 && p < count && !pages.contains(p)) pages.add(p);
                }
            } else {
                pages.add(current);
            }
            if (pages.size() > max) {
                reply.put("truncated", true);
                while (pages.size() > max) pages.remove(pages.size() - 1);
            }
            final JSONArray images = new JSONArray();
            reply.put("images", images);
            if (!pages.isEmpty()) {
                StringBuilder which = new StringBuilder();
                for (int i = 0; i < pages.size(); i++) {
                    if (i > 0) which.append(", ");
                    which.append(pages.get(i) + 1);
                }
                act.snackbar("Agent is looking at page"
                        + (pages.size() > 1 ? "s " : " ") + which, false);
            }
            captureNextPage(pages, 0, images, reply);
        } catch (Exception e) {
            try {
                reply.put("ok", false);
                reply.put("error", "capture failed: " + e.getMessage());
            } catch (Exception ignored) {
            }
            act.bridge.postCaptureResult(reply);
        }
    }

    private void captureNextPage(List<Integer> pages, int i, JSONArray images, JSONObject reply) {
        if (i >= pages.size() || act.canvas == null || act.isDead()) {
            if (act.bridge != null) act.bridge.postCaptureResult(reply);
            return;
        }
        final int page = pages.get(i);
        act.canvas.renderPage(page, CAPTURE_LONG_EDGE, CAPTURE_LONG_EDGE, true, bmp -> {
            if (bmp == null) {
                captureNextPage(pages, i + 1, images, reply);
                return;
            }
            final Runnable encode = () -> {
                try {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    JSONObject img = new JSONObject();
                    img.put("page", page + 1);
                    img.put("mimeType", "image/jpeg");
                    img.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                    synchronized (images) {
                        images.put(img);
                    }
                } catch (Throwable t) {
                    Log.w(MainActivity.TAG, "page capture failed", t);
                } finally {
                    bmp.recycle();
                }
                act.runOnUiThread(() -> captureNextPage(pages, i + 1, images, reply));
            };
            try {
                act.saveExecutor.execute(encode);
            } catch (java.util.concurrent.RejectedExecutionException closing) {
                bmp.recycle();
            }
        });
    }

    private void applyCanvasBatch(String batchId, org.json.JSONArray commands) {
        if (act.canvas == null || commands == null) {
            if (act.bridge != null) act.bridge.ackCanvasBatch(batchId, false, "no canvas");
            return;
        }
        String err = null;
        try {
            for (int i = 0; i < commands.length(); i++) {
                org.json.JSONObject cmd = commands.getJSONObject(i);
                String applyErr = applyCanvasCommand(cmd);
                if (applyErr != null) {
                    err = applyErr;
                    break;
                }
            }
        } catch (Exception e) {
            err = e.getMessage() != null ? e.getMessage() : "apply failed";
        }
        if (act.bridge != null) {
            act.bridge.ackCanvasBatch(batchId, err == null, err == null ? "" : err);
        }
        if (err == null) {
            act.persistence.scheduleSave();
            pushCanvasStateToBridge();
        }
    }

    /** @return null on success, else a short error for the ack. */
    private String applyCanvasCommand(org.json.JSONObject cmd) {
        if (cmd == null || act.canvas == null) return "null command";
        String op = cmd.optString("op", "");
        switch (op) {
            case "note.create":
            case "note.update":
            case "note.delete":
            case "image.add":
            case "view.focus":
                return "op " + op + " disabled — agents cannot add canvas elements";
            case "file.open": {
                String path = cmd.optString("path", "");
                if (path.isEmpty()) return "file.open needs path";
                act.codeEditor.openScriptEditor(path);
                return null;
            }
            case "script.add": {
                // Legacy op: scripts no longer live on the canvas — open in the editor.
                String path = cmd.optString("path", "");
                if (path.isEmpty()) return "script.add needs path";
                act.codeEditor.openScriptEditor(path);
                return null;
            }
            case "pdf.open": {
                String path = cmd.optString("path", "");
                if (path.isEmpty()) return "pdf.open needs path";
                act.documents.openPdfDocument(path);
                return null;
            }
            case "pdf.create": {
                String path = cmd.optString("path", "");
                if (path.isEmpty()) path = "untitled.pdf";
                if (!path.toLowerCase().endsWith(".pdf")) path = path + ".pdf";
                act.documents.createPdfDocumentAt(path);
                return null;
            }
            case "pdf.add": {
                // Legacy: opening the document replaces placing a card on the canvas.
                String path = cmd.optString("path", "");
                if (path.isEmpty()) return "pdf.add needs path";
                act.documents.openPdfDocument(path);
                return null;
            }
            default:
                return "unsupported op " + op;
        }
    }

    void pushCanvasStateToBridge() {
        if (!act.computers.hasHost()) return; // needs a connected computer
        if (act.bridge == null || act.canvas == null) return;
        try {
            org.json.JSONObject state = act.canvas.exportCanvasState();
            float[] vc = act.canvas.getViewCenterWorld();
            org.json.JSONArray viewCenter = new org.json.JSONArray();
            viewCenter.put(vc[0]);
            viewCenter.put(vc[1]);
            state.put("viewCenter", viewCenter);
            if (act.scriptEditorPath != null && !act.scriptEditorPath.isEmpty()) {
                state.put("editorPath", act.scriptEditorPath);
            }
            act.bridge.putCanvasState(state, null);
        } catch (Exception ignored) {
        }
    }

    /**
     * The explorer moved/renamed ({@code to} set) or deleted ({@code to} null) a file or
     * folder. Carry saved ink, recents and the open document over to the new path.
     */
    void onWorkspacePathChanged(String from, String to) {
        if (from == null) return;
        String f = FavoritesStore.normalizePath(from);
        String t = to != null ? FavoritesStore.normalizePath(to) : null;
        try {
            act.documents.stashCurrentDocumentState();
            for (String k : act.docStore.paths()) {
                String nk = FavoritesStore.normalizePath(k);
                if (!nk.equals(f) && !nk.startsWith(f + "/")) continue;
                if (t != null) act.docStore.move(k, t + nk.substring(f.length()));
                else act.docStore.remove(k);
            }
        } catch (Exception ignored) {
        }
        for (int i = act.recentDocs.size() - 1; i >= 0; i--) {
            String nk = FavoritesStore.normalizePath(act.recentDocs.get(i));
            if (!nk.equals(f) && !nk.startsWith(f + "/")) continue;
            if (t != null) act.recentDocs.set(i, t + nk.substring(f.length()));
            else act.recentDocs.remove(i);
        }
        act.overflowMenu.syncQuickSwitchButton();
        String open = act.canvas != null ? act.canvas.getDocumentPath() : null;
        if (open != null && t != null) {
            String no = FavoritesStore.normalizePath(open);
            if (no.equals(f) || no.startsWith(f + "/")) {
                String moved = t + no.substring(f.length());
                // Reopen from the new place; ink rides along via docStore.
                act.canvas.closeDocument();
                act.documents.openPdfDocument(moved);
            }
        }
        act.persistence.scheduleSave();
    }
}
