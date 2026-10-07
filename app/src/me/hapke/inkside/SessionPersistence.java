package me.hapke.inkside;

import android.graphics.Typeface;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Saving and restoring the whole app session (documents, chats, panels, pens).
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class SessionPersistence {
    private final MainActivity act;

    private final Runnable sessionCheckpointRunnable;

    /** Re-arms autosave if the bridge never answers the restore reads. */
    private static final long RESTORE_TIMEOUT_MS = 8000L;
    private final Runnable restoreWatchdog;

    private boolean saveErrorReported = false;

    /** True while the pending save was scheduled for a camera move only. */
    private boolean pendingSaveIsCamera;

    SessionPersistence(MainActivity act) {
        this.act = act;
        sessionCheckpointRunnable = act.conversations::onSessionCheckpoint;
        restoreWatchdog = () -> {
            if (!act.restoring) return;
            Log.w(MainActivity.TAG, "restore timed out after " + RESTORE_TIMEOUT_MS + "ms; re-arming autosave");
            finishRestore();
        };
    }

    /**
     * A save for a scroll/zoom only. Serialising the session (thousands of strokes)
     * 400ms after every fling ran into the next fling and set off large GCs; the
     * position can wait until scrolling pauses. Never delays a content save.
     */
    void scheduleCameraSave() {
        if (act.restoring || act.stateStore == null) return;
        act.sessionDirty = true;
        if (act.saveHandler.hasCallbacks(act.saveRunnable) && !pendingSaveIsCamera) return;
        pendingSaveIsCamera = true;
        act.saveHandler.removeCallbacks(act.saveRunnable);
        // With live sync on, ink reaches the other device sooner when it is saved sooner.
        act.saveHandler.postDelayed(act.saveRunnable,
                act.remoteSync != null && PairedHosts.liveSync(act) ? 1_500L : 4_000L);
    }

    void scheduleSave() {
        if (act.restoring || act.stateStore == null) return;
        pendingSaveIsCamera = false;
        act.sessionDirty = true;
        act.saveHandler.removeCallbacks(act.saveRunnable);
        // Short debounce — adb install can kill the process without a clean onPause,
        // so waiting 1.2s was losing work.
        act.saveHandler.postDelayed(act.saveRunnable, 400);
        act.saveHandler.removeCallbacks(sessionCheckpointRunnable);
        act.saveHandler.postDelayed(sessionCheckpointRunnable, 8_000L);
    }

    void saveSessionNow() {
        saveSessionNow(false);
    }

    /**
     * @param waitForDisk when true, block briefly until the write finishes (lifecycle /
     *                    deploy flush). Never use from the streaming hot path — that
     *                    starved WebView updates and truncated agent replies.
     */
    void saveSessionNow(boolean waitForDisk) {
        if (act.restoring || act.stateStore == null || act.canvas == null) return;
        if (!waitForDisk && act.canvas.isInkInProgress()) {
            // Building the state runs on the UI thread: landing mid-stroke, it stalled
            // the pen halfway through the line. Go again once the pen is up.
            act.saveHandler.removeCallbacks(act.saveRunnable);
            act.saveHandler.postDelayed(act.saveRunnable, 250);
            return;
        }
        act.sessionDirty = false;
        pendingSaveIsCamera = false;
        act.saveHandler.removeCallbacks(act.saveRunnable);
        act.saveHandler.removeCallbacks(sessionCheckpointRunnable);
        try {
            act.conversations.syncActiveFromUi();
            final JSONObject state = act.canvas.exportCanvasState();
            state.put("chatLog", act.chatPlain.toString());
            state.put("chatDraft", act.chatInput != null ? act.chatInput.getText().toString() : "");
            state.put("activeChatId", act.activeChatId != null ? act.activeChatId : "");
            org.json.JSONArray chatsJson = new org.json.JSONArray();
            for (ChatSession c : act.chats) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("title", c.title);
                o.put("log", c.log.toString());
                o.put("draft", c.draft != null ? c.draft : "");
                o.put("updatedAt", c.updatedAt);
                o.put("projectPath", c.projectPath != null ? c.projectPath : "");
                chatsJson.put(o);
            }
            state.put("chats", chatsJson);
            state.put("selectedColorIndex", act.selectedColorIndex);
            if (act.canvas != null) state.put("palmRejection", act.canvas.isPalmRejection());
            if (act.canvas != null) state.put("threeFingerUndo", act.canvas.isThreeFingerUndo());
            if (act.canvas != null) state.put("threeFingerFavorites", act.canvas.isThreeFingerFavorites());
            if (act.canvas != null) state.put("threeFingerDocs", act.canvas.isThreeFingerDocs());
            state.put("twoFingerChat", act.twoFingerChatSwipe);
            if (act.canvas != null) state.put("quickFavorites", act.canvas.isQuickFavoritesEnabled());
            if (act.canvas != null) state.put("shapeSnap", act.canvas.isShapeSnapEnabled());
            if (act.canvas != null) state.put("penOutline", act.canvas.isPenOutline());
            state.put("keepUndoHistory", act.keepUndoHistory);
            if (act.handwriting != null) {
                state.put("handwritingEnabled", act.handwriting.isEnabled());
                state.put("handwritingLang", act.handwriting.getLanguage());
            }
            state.put("toolOptionsExpanded", act.toolOptionsExpanded);
            if (act.canvas != null) state.put("stabilization", act.canvas.getStabilization());
            if (act.canvas != null) state.put("pressureSensitivity", act.canvas.getPressureSensitivity());
            state.put("pens", act.penTools.pensToJson());
            JSONArray eraserJson = new JSONArray();
            for (float v : act.eraserSizes) eraserJson.put(v);
            state.put("eraserSizes", eraserJson);
            state.put("eraserSlot", act.eraserSlot);
            JSONArray favJson = new JSONArray();
            for (int c : act.favoriteColors) favJson.put(c);
            state.put("favoriteColors", favJson);
            state.put("brush", CodeCanvasView.BRUSH_KEYS[act.brush]);
            state.put("penBrush", CodeCanvasView.BRUSH_KEYS[act.penBrush]);
            state.put("writingBrush", CodeCanvasView.BRUSH_KEYS[act.writingBrush]);
            JSONObject shapeTool = new JSONObject();
            shapeTool.put("kind", act.shapeKind);
            shapeTool.put("fill", act.shapeFill);
            shapeTool.put("border", act.shapeBorder);
            shapeTool.put("width", act.shapeBorderWidth);
            shapeTool.put("dash", act.shapeDash);
            state.put("lineStyle", act.lineStyle);
            state.put("shapeTool", shapeTool);
            JSONObject textDefaults = new JSONObject();
            textDefaults.put("size", (double) CanvasTextField.newSize);
            textDefaults.put("family", CanvasTextField.newFamily);
            textDefaults.put("style", CanvasTextField.newStyle);
            textDefaults.put("color", CanvasTextField.newColor);
            state.put("textDefaults", textDefaults);
            state.put("workspaceDir", act.workspaceDir != null ? act.workspaceDir : ".");
            state.put("activeProjectPath", act.activeProjectPath != null ? act.activeProjectPath : "");
            state.put("lastProjectPath", act.lastProjectPath != null ? act.lastProjectPath : "");
            org.json.JSONArray recentJson = new org.json.JSONArray();
            for (String r : act.recentDocs) recentJson.put(r);
            state.put("recentDocs", recentJson);
            JSONObject lastDocs = new JSONObject();
            for (java.util.Map.Entry<String, String> e : act.lastDocByProject.entrySet()) lastDocs.put(e.getKey(), e.getValue());
            state.put("lastDocByProject", lastDocs);
            org.json.JSONArray createdJson = new org.json.JSONArray();
            for (String c : act.appCreatedDocs) createdJson.put(c);
            state.put("appCreatedDocs", createdJson);
            if (act.folderExplorer != null) act.folderExplorer.persistScroll();
            state.put("folderExplorer", FolderExplorerView.exportUiState());
            state.put("chatCollapsed", act.chatCollapsed);
            state.put("explorerCollapsed", act.explorerCollapsed);
            state.put("editorCollapsed", act.editorCollapsed);
            state.put("editorViewOnly", act.editorViewOnly);
            state.put("editorPath", act.scriptEditorPath != null ? act.scriptEditorPath : "");
            state.put("editorDirty", act.editorDirty);
            if (act.editorDirty && act.scriptEditorInput != null && act.scriptEditorInput.getText() != null) {
                String buffer = act.scriptEditorInput.getText().toString();
                if (buffer.length() <= MainActivity.MAX_EDITOR_BUFFER_CHARS) state.put("editorText", buffer);
            }
            state.put("chatPanelWidthDp", Math.round(act.chatView.chatPanelWidth() / act.getResources().getDisplayMetrics().density));
            state.put("editorPanelWidthDp", Math.round(act.codeEditor.editorPanelWidth() / act.getResources().getDisplayMetrics().density));
            state.put("explorerPanelWidthDp", Math.round(act.explorer.explorerPanelWidth() / act.getResources().getDisplayMetrics().density));
            state.put("appThemeId", act.appThemeId);
            state.put("codeStyleId", act.appThemeId);
            state.put("chatOnLeft", act.chatOnLeft);
            String docPath = state.optString("documentPath", "");
            if (!docPath.isEmpty()) {
                act.docStore.put(docPath, Documents.documentSliceFromExport(state));
            }
            // Documents are saved in their own files, and only the ones that changed;
            // the session file keeps the rest of the app's state.
            state.put("documentStatesExternal", true);
            final DocumentStateStore.Snapshot docs = act.docStore.snapshotDirty();
            final DocumentStateStore docWriter = act.docStore;
            // History goes out with the document it belongs to, so a restart always
            // finds the two in step (it is skipped if they ever disagree).
            act.documents.saveUndoHistory();
            final AppStateStore store = act.stateStore;
            // Each chat is also its own file, so another device can take it over (see RemoteSync).
            final java.util.Map<String, String> chatWrites = new java.util.HashMap<>();
            final java.util.List<String> chatDeletes = new java.util.ArrayList<>();
            java.util.Set<String> chatIdsNow = new java.util.HashSet<>();
            for (ChatSession c : act.chats) {
                chatIdsNow.add(c.id);
                String sig = chatSignature(c);
                if (sig.equals(chatSig.get(c.id))) continue;
                JSONObject co = new JSONObject();
                co.put("id", c.id);
                co.put("title", c.title);
                co.put("log", c.log.toString());
                co.put("updatedAt", c.updatedAt);
                co.put("projectPath", c.projectPath != null ? c.projectPath : "");
                chatWrites.put(c.id, co.toString());
                chatSig.put(c.id, sig);
                writtenChatIds.add(c.id);
            }
            for (String id : new java.util.ArrayList<>(writtenChatIds)) {
                if (!chatIdsNow.contains(id)) {
                    chatDeletes.add(id);
                    writtenChatIds.remove(id);
                    chatSig.remove(id);
                }
            }
            final java.io.File chatsDir = act.chatsDir;
            if (!waitForDisk) {
                act.saveExecutor.execute(() -> {
                    writeChatFiles(chatsDir, chatWrites, chatDeletes);
                    String failure = writeSession(store, state, docWriter, docs, docPath);
                    if (failure == null) return;
                    final String reason = failure;
                    act.saveHandler.post(() -> {
                        if (act.isDead() || saveErrorReported) return;
                        saveErrorReported = true;
                        act.conversations.appendChat("warn", "session could not be saved: " + reason);
                    });
                });
                return;
            }
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            final String[] fail = {null};
            act.saveExecutor.execute(() -> {
                try {
                    writeChatFiles(chatsDir, chatWrites, chatDeletes);
                    fail[0] = writeSession(store, state, docWriter, docs, docPath);
                } finally {
                    done.countDown();
                }
            });
            try {
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
                if (act.undoHistory != null) act.undoHistory.flush(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (fail[0] != null && !saveErrorReported) {
                saveErrorReported = true;
                act.conversations.appendChat("warn", "session could not be saved: " + fail[0]);
            }
        } catch (Exception e) {
            Log.e(MainActivity.TAG, "building session state failed", e);
        }
    }

    private final java.util.HashMap<String, String> chatSig = new java.util.HashMap<>();
    private final java.util.HashSet<String> writtenChatIds = new java.util.HashSet<>();

    /** What makes one version of a chat differ from another (cheap: no log copy). */
    static String chatSignature(ChatSession c) {
        return c.updatedAt + ":" + c.log.length() + ":" + (c.title != null ? c.title.hashCode() : 0)
                + ":" + (c.projectPath != null ? c.projectPath.hashCode() : 0);
    }

    /** A chat taken over from another device is already on disk as it came: do not write it back. */
    void chatAdopted(ChatSession c) {
        chatSig.put(c.id, chatSignature(c));
        writtenChatIds.add(c.id);
    }

    /** Save thread: chat files for what changed, and the files of chats that were deleted. */
    private void writeChatFiles(java.io.File dir, java.util.Map<String, String> writes, java.util.List<String> deletes) {
        if (dir == null || (writes.isEmpty() && deletes.isEmpty())) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            for (java.util.Map.Entry<String, String> e : writes.entrySet()) {
                LocalWorkspace.writeAtomic(new java.io.File(dir, e.getKey() + ".json"),
                        e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            for (String id : deletes) {
                //noinspection ResultOfMethodCallIgnored
                new java.io.File(dir, id + ".json").delete();
            }
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "could not write chat files", e);
        }
        if (!writes.isEmpty() || !deletes.isEmpty()) {
            act.saveHandler.post(() -> {
                if (act.remoteSync != null) act.remoteSync.requestSoon();
            });
        }
    }

    /**
     * Save thread: the changed documents, then the session. Nothing here may throw —
     * running out of memory mid-save used to take the whole app down.
     *
     * @return null on success, else a short reason
     */
    private String writeSession(AppStateStore store, JSONObject state, DocumentStateStore docs,
                                DocumentStateStore.Snapshot snap, String openPath) {
        String failure;
        try {
            failure = docs.flush(snap);
            String main = store.save(state);
            if (failure == null) failure = main;
        } catch (Throwable t) {
            failure = t.getMessage() != null ? t.getMessage() : t.toString();
            Log.e(MainActivity.TAG, "session save threw", t);
        }
        final boolean docsOk = failure == null;
        act.saveHandler.post(() -> {
            if (docsOk && !snap.slices.isEmpty() && act.remoteSync != null) act.remoteSync.requestSoon();
            if (docsOk) docs.release(snap, act.canvas != null ? act.canvas.getDocumentPath() : openPath);
            else docs.retry(snap);
        });
        return failure;
    }

    /** @return true if a previous session was restored */
    boolean restoreSession() {
        if (act.stateStore == null || !act.stateStore.hasState()) return false;
        JSONObject state = act.stateStore.load(act.docStore::importNow);
        act.docStore.saveIndexNow();
        String loadWarning = act.stateStore.consumeLoadWarning();
        if (loadWarning != null) {
            // Never lose a canvas silently — say so, whether or not the fallback worked.
            final String w = loadWarning;
            act.saveHandler.post(() -> {
                if (!act.isDead()) act.conversations.appendChat("warn", w);
            });
        }
        if (state == null) return false;
        org.json.JSONArray sheetsJson = state.optJSONArray("sheets");
        org.json.JSONArray imagesJson = state.optJSONArray("images");
        org.json.JSONArray strokesJson = state.optJSONArray("strokes");
        org.json.JSONArray textFieldsJson = state.optJSONArray("textFields");
        org.json.JSONArray pdfsJson = state.optJSONArray("pdfs");
        org.json.JSONArray scriptsJson = state.optJSONArray("scripts");
        boolean hasSheets = sheetsJson != null && sheetsJson.length() > 0;
        boolean hasImages = imagesJson != null && imagesJson.length() > 0;
        boolean hasStrokes = strokesJson != null && strokesJson.length() > 0;
        boolean hasTextFields = textFieldsJson != null && textFieldsJson.length() > 0;
        boolean hasPdfs = pdfsJson != null && pdfsJson.length() > 0;
        boolean hasScripts = scriptsJson != null && scriptsJson.length() > 0;
        boolean hasChats = state.optJSONArray("chats") != null
                && state.optJSONArray("chats").length() > 0;
        boolean hasLegacyChat = state.optString("chatLog", "").length() > 0;
        boolean hasLayout = state.has("chatCollapsed") || state.has("explorerCollapsed")
                || state.has("editorCollapsed")
                || state.has("appThemeId") || state.has("activeChatId");
        // Legacy: strokes nested under sheets also count
        if (!hasStrokes && hasSheets) {
            for (int i = 0; i < sheetsJson.length(); i++) {
                if (sheetsJson.optJSONObject(i) != null
                        && sheetsJson.optJSONObject(i).optJSONArray("strokes") != null
                        && sheetsJson.optJSONObject(i).optJSONArray("strokes").length() > 0) {
                    hasStrokes = true;
                    break;
                }
            }
        }
        boolean hasDocument = state.optString("documentPath", "").length() > 0;
        // Notes / document / scripts / UI-only sessions must restore too —
        // otherwise the next autosave overwrites a real blob with empty defaults.
        if (!hasSheets && !hasImages && !hasStrokes && !hasChats && !hasLegacyChat
                && !hasTextFields && !hasPdfs && !hasScripts && !hasLayout && !hasDocument) {
            return false;
        }

        act.restoring = true;
        try {
            org.json.JSONObject storedDocs = state.optJSONObject("documentStates");
            if (storedDocs != null) {
                // A session from before documents had their own files: move each one
                // out (the next save writes them) and let go of the inline copy.
                for (java.util.Iterator<String> it = storedDocs.keys(); it.hasNext(); ) {
                    String k = it.next();
                    org.json.JSONObject slice = storedDocs.optJSONObject(k);
                    if (slice != null && !act.docStore.has(k)) act.docStore.put(k, slice);
                }
                state.remove("documentStates");
            } else if (!state.optBoolean("documentStatesExternal", false)) {
                // Older still: flat canvas fields for a single document.
                String migratePath = state.optString("documentPath", "");
                if (!migratePath.isEmpty() && !act.docStore.has(migratePath)) {
                    try {
                        act.docStore.put(migratePath, Documents.documentSliceFromExport(state));
                    } catch (Exception ignored) {}
                }
            }
            act.canvas.importCanvasState(state);
            final String docPath = state.optString("documentPath", "");
            act.keepUndoHistory = state.optBoolean("keepUndoHistory", true);
            if (!docPath.isEmpty()) act.documents.restoreUndoHistory(docPath);
            act.restoredDocumentPath = docPath.isEmpty() ? null : docPath;
            if (!docPath.isEmpty() && act.bridge != null) {
                org.json.JSONObject docSlice = act.docStore.get(docPath);
                final int pageCount = Math.max(1,
                        docSlice != null ? docSlice.optInt("pageCount", state.optInt("pageCount", 1))
                                : state.optInt("pageCount", 1));
                final boolean blankOwned = docSlice != null
                        ? docSlice.optBoolean("blankOwned", state.optBoolean("blankOwned", false))
                        : state.optBoolean("blankOwned", false);
                final int blankPrefix = Math.max(0,
                        docSlice != null ? docSlice.optInt("blankPrefix", state.optInt("blankPrefix", 0))
                                : state.optInt("blankPrefix", 0));
                // Re-bind PDF substrate after annotation import (async).
                act.documents.readPdfBytesCached(docPath, new BridgeClient.Callback<byte[]>() {
                    @Override
                    public void onSuccess(byte[] data) {
                        if (act.isDead() || act.canvas == null) return;
                        try {
                            act.canvas.openDocument(act, docPath, data, pageCount,
                                    blankOwned, blankPrefix);
                            act.overflowMenu.noteRecentDoc(docPath);
                        } catch (Exception e) {
                            act.conversations.appendChat("warn", "restore PDF: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (!act.isDead()) act.conversations.appendChat("warn", "restore PDF: " + message);
                    }
                });
            }
            act.canvas.refreshAllLatexTextFields();
            // Before the pencil is selected below, so the right pen is on show.
            JSONObject textDefaults = state.optJSONObject("textDefaults");
            if (textDefaults != null) {
                CanvasTextField.newSize = (float) Math.max(CanvasTextField.MIN_TEXT_SIZE,
                        textDefaults.optDouble("size", CanvasTextField.newSize));
                CanvasTextField.newFamily = textDefaults.optString("family", "sans");
                CanvasTextField.newStyle = textDefaults.optInt("style", Typeface.NORMAL);
                CanvasTextField.newColor = textDefaults.optInt("color", 0);
                act.textTools.refreshTextDefaultsRow();
            }
            act.brush = CodeCanvasView.brushFromKey(state.optString("brush", ""));
            if (act.brush == CodeCanvasView.BRUSH_INK && state.optBoolean("glowBrush", false)) {
                act.brush = CodeCanvasView.BRUSH_GLOW;
            }
            act.penBrush = CodeCanvasView.brushFromKey(state.optString("penBrush",
                    act.brush == CodeCanvasView.BRUSH_HIGHLIGHTER ? "ink" : CodeCanvasView.BRUSH_KEYS[act.brush]));
            if (!CodeCanvasView.isPenBrush(act.penBrush)) act.penBrush = CodeCanvasView.BRUSH_INK;
            // Calligraphy is no longer offered: whoever had it gets plain ink.
            if (act.brush == CodeCanvasView.BRUSH_CALLIGRAPHY) act.brush = CodeCanvasView.BRUSH_INK;
            act.writingBrush = CodeCanvasView.brushFromKey(state.optString("writingBrush",
                    CodeCanvasView.BRUSH_KEYS[act.brush == CodeCanvasView.BRUSH_HIGHLIGHTER
                            ? CodeCanvasView.BRUSH_HIGHLIGHTER : act.penBrush]));
            if (act.writingBrush != CodeCanvasView.BRUSH_HIGHLIGHTER) act.writingBrush = act.penBrush;
            JSONObject shapeTool = state.optJSONObject("shapeTool");
            if (shapeTool != null) {
                act.shapeKind = ShapeLibrary.clamp(shapeTool.optInt("kind", act.shapeKind));
                act.shapeFill = shapeTool.optInt("fill", act.shapeFill);
                act.shapeBorder = shapeTool.optInt("border", act.shapeBorder);
                act.shapeBorderWidth = (float) shapeTool.optDouble("width", act.shapeBorderWidth);
                act.shapeDash = Math.max(0, Math.min(CodeCanvasView.LINE_STYLE_COUNT - 1,
                        shapeTool.optInt("dash", 0)));
            }
            act.lineStyle = Math.max(0, Math.min(CodeCanvasView.LINE_STYLE_COUNT - 1,
                    state.optInt("lineStyle", 0)));
            if (act.canvas != null) act.canvas.setLineStyle(act.lineStyle);
            act.penTools.applyShapeStyle();
            JSONArray pensJson = state.optJSONArray("pens");
            if (pensJson != null) {
                act.penTools.pensFromJson(pensJson);
            } else {
                act.penTools.migrateOldPenState(state);
            }
            JSONArray eraserJson = state.optJSONArray("eraserSizes");
            if (eraserJson != null && eraserJson.length() == 3) {
                for (int k = 0; k < 3; k++) {
                    act.eraserSizes[k] = Math.max(8f, Math.min(80f, (float) eraserJson.optDouble(k, act.eraserSizes[k])));
                }
            }
            act.eraserSlot = Math.max(0, Math.min(2, state.optInt("eraserSlot", 1)));
            if (act.canvas != null) act.canvas.setEraseRadiusPx(act.eraserSizes[act.eraserSlot]);
            JSONArray favJson = state.optJSONArray("favoriteColors");
            if (favJson != null && favJson.length() > 0) {
                act.favoriteColors.clear();
                for (int i = 0; i < favJson.length(); i++) {
                    int c = favJson.optInt(i, 0);
                    if (c != 0 && !act.favoriteColors.contains(c)) act.favoriteColors.add(c);
                }
            }
            if (act.canvas != null) act.canvas.setBrush(act.brush);
            if (act.canvas != null) {
                act.canvas.setPalmRejection(state.optBoolean("palmRejection", true));
                act.canvas.setThreeFingerUndo(state.optBoolean("threeFingerUndo", true));
                act.canvas.setThreeFingerFavorites(state.optBoolean("threeFingerFavorites", true));
                act.canvas.setThreeFingerDocs(state.optBoolean("threeFingerDocs", true));
                act.twoFingerChatSwipe = state.optBoolean("twoFingerChat", true);
                act.canvas.setQuickFavoritesEnabled(state.optBoolean("quickFavorites", true));
                act.canvas.setShapeSnapEnabled(state.optBoolean("shapeSnap", true));
                act.canvas.setPenOutline(state.optBoolean("penOutline", state.optBoolean("penShadow", false)));
            }
            if (act.handwriting != null) {
                act.handwriting.setLanguage(state.optString("handwritingLang",
                        HandwritingIndex.defaultLanguage()));
                act.handwriting.setEnabled(state.optBoolean("handwritingEnabled", false));
            }
            if (act.canvas != null && state.has("stabilization")) {
                float amt = (float) state.optDouble("stabilization", 0.65);
                act.canvas.setStabilization(amt);
                if (act.stabilizationSeekBar != null) {
                    act.stabilizationSeekBar.setProgress(Math.round(amt * 100f));
                }
            }
            if (act.canvas != null && state.has("pressureSensitivity")) {
                float amt = (float) state.optDouble("pressureSensitivity", 1.0);
                act.canvas.setPressureSensitivity(amt);
                if (act.pressureSeekBar != null) {
                    act.pressureSeekBar.setProgress(Math.round(amt * 100f));
                }
            }
            act.toolOptionsExpanded = state.optBoolean("toolOptionsExpanded", false);
            act.penTools.styleToolExpandButton();
            // refreshToolOptionRow reads which tool's options are visible, and at this
            // point in restore nothing has decided that yet — it saw none, disabled the
            // expand button, and nothing re-enabled it until a tool was tapped. Let the
            // tool selection settle first; it ends by refreshing the row.
            act.saveHandler.post(act.penTools::refreshToolSelection);
            act.selectedColorIndex = act.penTools.pen().selected;
            if (state.optBoolean("eraseMode", false)) {
                act.penTools.selectEraser();
            } else {
                act.penTools.selectPencil(act.selectedColorIndex);
            }
            act.penTools.applyPenProperties();
            act.workspaceDir = state.optString("workspaceDir", ".");
            FolderExplorerView.ensurePrefsLoaded(act);
            FolderExplorerView.importUiState(state.optJSONObject("folderExplorer"));
            act.chats.clear();
            org.json.JSONArray chatsJson = state.optJSONArray("chats");
            if (chatsJson != null && chatsJson.length() > 0) {
                for (int i = 0; i < chatsJson.length(); i++) {
                    JSONObject o = chatsJson.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", Conversations.newChatId());
                    // The Improve chat of earlier versions is gone from the app.
                    if (o.optBoolean("improve", false) || "app-improve".equals(id)) continue;
                    ChatSession c = new ChatSession(id, o.optString("title", "Chat"));
                    String log = o.optString("log", "");
                    if (log != null && !log.isEmpty()) c.log.append(log);
                    c.draft = o.optString("draft", "");
                    c.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
                    c.projectPath = o.optString("projectPath", "");
                    act.chats.add(c);
                }
            } else {
                ChatSession c = new ChatSession(Conversations.newChatId(), "Chat");
                String log = state.optString("chatLog", "");
                if (log != null && !log.isEmpty()) c.log.append(log);
                c.draft = state.optString("chatDraft", "");
                act.chats.add(c);
            }
            act.activeProjectPath = state.optString("activeProjectPath", "");
            if (act.activeProjectPath != null && act.activeProjectPath.isEmpty()) act.activeProjectPath = null;
            act.lastProjectPath = state.optString("lastProjectPath", "");
            act.appCreatedDocs.clear();
            org.json.JSONArray createdJson = state.optJSONArray("appCreatedDocs");
            if (createdJson != null) {
                for (int i = 0; i < createdJson.length(); i++) {
                    String c = createdJson.optString(i, "");
                    if (!c.isEmpty()) act.appCreatedDocs.add(c);
                }
            }
            act.lastDocByProject.clear();
            JSONObject lastDocsJson = state.optJSONObject("lastDocByProject");
            if (lastDocsJson != null) {
                for (java.util.Iterator<String> it = lastDocsJson.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String v = lastDocsJson.optString(k, "");
                    if (!k.isEmpty() && !v.isEmpty()) act.lastDocByProject.put(k, v);
                }
            }
            act.recentDocs.clear();
            org.json.JSONArray recentJson = state.optJSONArray("recentDocs");
            if (recentJson != null) {
                for (int i = 0; i < recentJson.length() && act.recentDocs.size() < MainActivity.RECENT_DOCS_MAX; i++) {
                    String r = recentJson.optString(i, "");
                    if (!r.isEmpty() && !act.recentDocs.contains(r)) act.recentDocs.add(r);
                }
            }
            if (act.lastProjectPath != null && act.lastProjectPath.isEmpty()) act.lastProjectPath = null;
            // Legacy chats without projectPath → attach to last/first project.
            String legacyProject = act.lastProjectPath != null ? act.lastProjectPath : "";
            for (ChatSession c : act.chats) {
                if (c.projectPath == null || c.projectPath.isEmpty()) {
                    c.projectPath = legacyProject;
                }
            }
            act.conversations.ensureDefaultChat();
            String want = state.optString("activeChatId", "");
            if (want != null && act.conversations.findChat(want) != null) act.activeChatId = want;
            ChatSession active = act.conversations.activeChat();
            if (active == null || !act.conversations.chatBelongsToActiveProject(active)) {
                act.conversations.ensureChatsForActiveProject();
                active = act.conversations.activeChat();
            }
            act.chatPlain.setLength(0);
            if (active != null) {
                act.chatPlain.append(active.log);
                if (act.chatInput != null) {
                    act.chatInput.setText(active.draft != null ? active.draft : "");
                    act.chatInput.setHint(act.chatIdleHint());
                }
            }
            act.conversations.refreshChatComposer();
            if (act.chatWebReady) {
                act.conversations.evalChatJs("setAll(" + JSONObject.quote(act.chatPlain.toString()) + ")");
            }
            act.conversations.refreshChatTabs();
            if (state.has("chatPanelWidthDp")) {
                act.chatPanelWidthPx = act.dp(state.optInt("chatPanelWidthDp", MainActivity.CHAT_PANEL_W));
            }
            if (state.has("editorPanelWidthDp")) {
                act.editorPanelWidthPx = act.dp(state.optInt("editorPanelWidthDp", MainActivity.EDITOR_PANEL_W));
            }
            if (state.has("explorerPanelWidthDp")) {
                act.explorerPanelWidthPx = act.dp(state.optInt("explorerPanelWidthDp", MainActivity.EXPLORER_PANEL_W));
            }
            act.appThemeId = state.optString("appThemeId", ThemeConfig.APP_THEMES[0].id);
            // Code palette follows app theme; ignore legacy separate codeStyleId.
            act.codeStyleId = act.appThemeId;
            // Chat is left-only; ignore any legacy right-side preference.
            act.chatOnLeft = true;
            act.applyAppTheme(ThemeConfig.appThemeById(act.appThemeId));
            act.chatView.applyChatSide(true);
            act.chatView.applyChatCollapsed(state.optBoolean("chatCollapsed", true));
            act.explorer.applyExplorerCollapsed(state.optBoolean("explorerCollapsed", true));
            String editorPath = state.optString("editorPath", "");
            if (!editorPath.isEmpty()) {
                if (editorPath.toLowerCase(java.util.Locale.US).endsWith(".viz")) {
                    act.codeEditor.openVizFile(editorPath);
                } else {
                    if (state.optBoolean("editorDirty", false) && state.has("editorText")) {
                        act.pendingEditorText = state.optString("editorText", "");
                    }
                    act.codeEditor.loadEditorFile(editorPath);
                }
            }
            act.codeEditor.setEditorViewOnly(state.optBoolean("editorViewOnly", false));
            act.codeEditor.applyEditorCollapsed(state.optBoolean("editorCollapsed", true));
            act.conversations.syncChatRunState();

            // Code no longer lives on the canvas — finish restore once editor/canvas are loaded.
            finishRestore();
            return true;
        } catch (Exception e) {
            Log.e(MainActivity.TAG, "restoreSession failed", e);
            finishRestore();
            return false;
        }
    }

    /**
     * Ends the restore window and re-arms autosave. Idempotent — reached by the last
     * bridge callback, the watchdog, or the failure path, whichever comes first.
     */
    private void finishRestore() {
        act.saveHandler.removeCallbacks(restoreWatchdog);
        if (!act.restoring) return;
        act.restoring = false;
        if (act.canvas != null) {
            int removed = act.canvas.removeAgentPlacedContent();
            if (removed > 0) {
                Log.i(MainActivity.TAG, "stripped " + removed + " agent-placed canvas note(s)");
            }
            act.canvasAgent.pushCanvasStateToBridge();
        }
        scheduleSave();
    }
}
