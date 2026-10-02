package me.hapke.inkside;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The code / viz editor side panel: loading, saving, running scripts, find bar and panel motion.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class CodeEditor {
    private final MainActivity act;

    private LinearLayout editorShell;
    private View editorScrim;
    private FrameLayout editorTextWrap;
    private GradientDrawable editorTextWrapBg;
    private GradientDrawable editorOutputBg;
    private TextView editorTitle;
    private ImageView editorFindToggle;
    private ImageView editorViewOnlyToggle;
    private View editorFindBar;
    private ImageView editorUndoButton;
    private ImageView editorRedoButton;
    private ImageView editorRunButton;

    private ProgressBar editorRunSpinner;
    private LinearLayout editorOutputWrap;
    private TextView editorOutputStatus;
    private TextView editorOutputClear;
    private TextView editorOutput;
    private ScrollView editorOutputScroll;

    private WebView editorVizWeb;

    /** Set while the buffer is replaced programmatically so the load is not seen as an edit. */
    private boolean editorLoading = false;
    private boolean editorRunning = false;

    private boolean editorPanelDragging = false;
    private float editorSlideStartRawX;
    private float editorSlideStartTx;
    private float editorDragStartRawX;
    private float editorDragStartRawY;
    private VelocityTracker editorVelocityTracker;

    private FrameLayout.LayoutParams editorResizeHandleLp;

    private int editorResizeStartWidth;
    private int editorResizePendingWidth;
    private float editorResizeStartRawX;
    private float editorResizeLastRawX;
    private final Runnable editorAutoSaveRunnable;

    private EditText findInput;
    private EditText replaceInput;
    private TextView findCount;
    private TextView findReplaceOne;
    private TextView findReplaceAll;

    private static final long EDITOR_AUTOSAVE_MS = 700L;

    CodeEditor(MainActivity act) {
        this.act = act;
        editorAutoSaveRunnable = () -> {
            if (act.isDead()) return;
            if (act.editorDirty && act.scriptEditorPath != null) saveScriptEditorQuiet();
        };
    }

    private static JSONObject defaultArgsFromParams(JSONObject params) {
        JSONObject args = new JSONObject();
        if (params == null) return args;
        try {
            java.util.Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject spec = params.optJSONObject(name);
                if (spec != null) args.put(name, spec.optString("default", ""));
            }
        } catch (Exception ignored) {
        }
        return args;
    }

    /** Opens {@code path} in the docked editor panel, autosaving prior dirty buffer first. */
    void openScriptEditor(String path) {
        if (path == null || path.isEmpty()) return;
        if (path.toLowerCase(java.util.Locale.US).endsWith(".viz")) {
            openVizFile(path);
            return;
        }
        ensureEditorPanel();
        if (path.equals(act.scriptEditorPath) && act.editorVizArtifact == null) {
            showEditorPanel();
            return;
        }
        if (act.editorDirty && act.scriptEditorPath != null && act.editorVizArtifact == null) {
            final String next = path;
            saveScriptEditorQuiet(() -> {
                enterCodeEditorMode();
                loadEditorFile(next);
                showEditorPanel();
            });
            return;
        }
        enterCodeEditorMode();
        loadEditorFile(path);
        showEditorPanel();
    }

    /**
     * Open a {@code .viz} pointer from the explorer. Never shows source — only the
     * rendered HTML under {@code .artifacts/}.
     */
    void openVizFile(String vizPath) {
        if (vizPath == null || vizPath.isEmpty()) return;
        ensureEditorPanel();
        Runnable load = () -> {
            act.scriptEditorPath = vizPath;
            act.openFile = vizPath;
            setEditorDirty(false);
            // Resolve pointer: optional one-line artifact name, else same stem as .viz.
            act.workspace.readFile(vizPath, new BridgeClient.Callback<BridgeClient.FileContent>() {
                @Override
                public void onSuccess(BridgeClient.FileContent file) {
                    if (act.isDead()) return;
                    if (!vizPath.equals(act.scriptEditorPath)) return;
                    String artifact = artifactNameFromViz(vizPath, file != null ? file.text : null);
                    showVizInEditor(vizPath, artifact);
                }

                @Override
                public void onError(String message) {
                    if (act.isDead()) return;
                    if (!vizPath.equals(act.scriptEditorPath)) return;
                    // Missing/unreadable pointer still opens by stem convention.
                    showVizInEditor(vizPath, artifactNameFromViz(vizPath, null));
                }
            });
            showEditorPanel();
        };
        if (act.editorDirty && act.scriptEditorPath != null && act.editorVizArtifact == null) {
            saveScriptEditorQuiet(load);
        } else {
            load.run();
        }
    }

    /** First non-empty non-comment line, or {@code {stem}.html} from the .viz path. */
    private static String artifactNameFromViz(String vizPath, String text) {
        if (text != null) {
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith(".artifacts/")) line = line.substring(".artifacts/".length());
                int slash = line.lastIndexOf('/');
                if (slash >= 0) line = line.substring(slash + 1);
                if (!line.isEmpty()) {
                    if (!line.toLowerCase(java.util.Locale.US).endsWith(".html")) line = line + ".html";
                    return line;
                }
            }
        }
        String base = baseName(vizPath);
        if (base.toLowerCase(java.util.Locale.US).endsWith(".viz")) {
            base = base.substring(0, base.length() - 4);
        }
        return base + ".html";
    }

    void showVizInEditor(String vizPath, String artifactHtml) {
        ensureEditorPanel();
        enterVizEditorMode();
        act.editorVizArtifact = artifactHtml;
        act.scriptEditorPath = vizPath;
        act.openFile = vizPath;
        if (act.scriptEditorInput != null) {
            setEditorText("");
            act.scriptEditorInput.setHint(null);
        }
        if (editorVizWeb != null) {
            String url = act.artifactUrl(artifactHtml);
            editorVizWeb.loadUrl(url);
        }
        refreshEditorHeader();
        clearEditorOutput();
    }

    private void enterVizEditorMode() {
        ensureEditorPanel();
        if (act.scriptEditorInput != null) act.scriptEditorInput.setVisibility(View.GONE);
        if (editorVizWeb != null) editorVizWeb.setVisibility(View.VISIBLE);
        if (editorFindBar != null) editorFindBar.setVisibility(View.GONE);
        if (editorOutputWrap != null) editorOutputWrap.setVisibility(View.GONE);
        setEditorChromeForViz(true);
    }

    private void enterCodeEditorMode() {
        act.editorVizArtifact = null;
        if (editorVizWeb != null) {
            editorVizWeb.stopLoading();
            editorVizWeb.loadUrl("about:blank");
            editorVizWeb.setVisibility(View.GONE);
        }
        if (act.scriptEditorInput != null) act.scriptEditorInput.setVisibility(View.VISIBLE);
        setEditorChromeForViz(false);
    }

    private void setEditorChromeForViz(boolean viz) {
        int gone = View.GONE;
        int visible = View.VISIBLE;
        if (editorUndoButton != null) editorUndoButton.setVisibility(viz ? gone : visible);
        if (editorRedoButton != null) editorRedoButton.setVisibility(viz ? gone : visible);
        if (editorFindToggle != null) editorFindToggle.setVisibility(viz ? gone : visible);
        if (editorViewOnlyToggle != null) editorViewOnlyToggle.setVisibility(viz ? gone : visible);
        if (editorRunButton != null) editorRunButton.setVisibility(viz ? gone : visible);
    }

    /**
     * Extensions that are never source. Loading one into the editor decodes a binary
     * as text and hands EditText a megabyte of garbage to line-break.
     */
    private static final java.util.Set<String> EDITOR_BINARY_EXTS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "docx", "doc", "xlsx", "xls", "pptx", "ppt", "odt", "ods", "odp",
                    "pdf", "zip", "gz", "tar", "7z", "rar", "jar", "apk",
                    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff",
                    "mp3", "mp4", "wav", "mov", "avi", "mkv", "webm", "ogg",
                    "ttf", "otf", "woff", "woff2", "so", "dll", "dylib", "bin",
                    "pyc", "class", "o", "a", "npy", "npz", "db", "sqlite",
                    "viz"));

    private static String extensionOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase(java.util.Locale.US);
    }

    /** Cheap sniff for content that is not text, whatever the name says. */
    private static boolean looksBinary(String text) {
        if (text == null || text.isEmpty()) return false;
        int n = Math.min(text.length(), 4000);
        int odd = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\0') return true;
            // Control characters outside tab/newline/CR, and the replacement char a
            // failed UTF-8 decode leaves behind.
            if ((c < 0x09) || (c > 0x0D && c < 0x20) || c == 0xFFFD) odd++;
        }
        return odd * 100 / n > 5;
    }

    /**
     * Why this file must not go into the editor, or null if it may.
     *
     * <p>A 1.08MB .docx restored into the editor at launch was enough to hang the main
     * thread inside LineBreaker for over five seconds — the app ANR'd on every open.
     */
    private String editorRefusal(String path, String text) {
        if (EDITOR_BINARY_EXTS.contains(extensionOf(path))) {
            return "Not a text file: ." + extensionOf(path);
        }
        if (looksBinary(text)) return "Not a text file (binary content)";
        return null;
    }

    /** Keep the buffer to something the layout pass can chew through. */
    private String clampEditorText(String text) {
        if (text == null) return "";
        if (text.length() <= MainActivity.MAX_EDITOR_BUFFER_CHARS) return text;
        return text.substring(0, MainActivity.MAX_EDITOR_BUFFER_CHARS)
                + "\n\n… truncated at " + MainActivity.MAX_EDITOR_BUFFER_CHARS + " characters …\n";
    }

    void loadEditorFile(final String path) {
        ensureEditorPanel();
        if (act.scriptEditorInput == null) return;
        enterCodeEditorMode();
        if (EDITOR_BINARY_EXTS.contains(extensionOf(path))) {
            act.scriptEditorPath = null;
            act.openFile = null;
            setEditorText("");
            act.scriptEditorInput.setHint("Not a text file: ." + extensionOf(path));
            refreshEditorHeader();
            return;
        }
        act.scriptEditorPath = path;
        act.openFile = path;
        setEditorText("");
        act.scriptEditorInput.setHint("Loading…");
        clearEditorOutput();
        refreshEditorHeader();
        act.workspace.readFile(path, new BridgeClient.Callback<BridgeClient.FileContent>() {
            @Override
            public void onSuccess(BridgeClient.FileContent file) {
                if (act.isDead() || act.scriptEditorInput == null) return;
                if (!path.equals(act.scriptEditorPath)) return;
                String restored = act.pendingEditorText;
                act.pendingEditorText = null;
                String text = restored != null ? restored : file.text;
                String refusal = editorRefusal(path, text);
                if (refusal != null) {
                    setEditorText("");
                    act.scriptEditorInput.setHint(refusal);
                    setEditorDirty(false);
                    return;
                }
                act.scriptEditorInput.setHint(null);
                setEditorText(clampEditorText(text));
                setEditorDirty(restored != null && !restored.equals(file.text));
                refreshFindCount();
            }

            @Override
            public void onError(String message) {
                if (act.isDead() || act.scriptEditorInput == null) return;
                if (!path.equals(act.scriptEditorPath)) return;
                String restored = act.pendingEditorText;
                act.pendingEditorText = null;
                if (restored != null) {
                    String refusal = editorRefusal(path, restored);
                    if (refusal != null) {
                        setEditorText("");
                        act.scriptEditorInput.setHint(refusal);
                        return;
                    }
                    act.scriptEditorInput.setHint(null);
                    setEditorText(clampEditorText(restored));
                    setEditorDirty(true);
                    return;
                }
                act.scriptEditorInput.setHint("Failed to load: " + message);
            }
        });
    }

    private void setEditorText(String text) {
        if (act.scriptEditorInput == null) return;
        editorLoading = true;
        act.scriptEditorInput.setInitialText(text);
        editorLoading = false;
        setEditorDirty(false);
    }

    private void setEditorDirty(boolean dirty) {
        boolean changed = act.editorDirty != dirty;
        act.editorDirty = dirty;
        refreshEditorHeader();
        if (changed) act.persistence.scheduleSave();
        act.saveHandler.removeCallbacks(editorAutoSaveRunnable);
        if (dirty && act.scriptEditorPath != null) {
            act.saveHandler.postDelayed(editorAutoSaveRunnable, EDITOR_AUTOSAVE_MS);
        }
    }

    private static String baseName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private void refreshEditorHeader() {
        if (editorTitle == null) return;
        String name = act.scriptEditorPath == null ? "No file open" : baseName(act.scriptEditorPath);
        if (act.editorVizArtifact != null) {
            editorTitle.setText(name);
            setEditorChromeForViz(true);
            return;
        }
        editorTitle.setText(act.editorDirty && !act.editorViewOnly ? name + "  •" : name);
        if (editorRunButton != null) {
            editorRunButton.setAlpha(act.scriptEditorPath == null || editorRunning ? 0.4f : 1f);
        }
        float editAlpha = act.editorViewOnly ? 0.35f : 1f;
        if (editorUndoButton != null) {
            editorUndoButton.setAlpha(editAlpha);
            editorUndoButton.setEnabled(!act.editorViewOnly);
        }
        if (editorRedoButton != null) {
            editorRedoButton.setAlpha(editAlpha);
            editorRedoButton.setEnabled(!act.editorViewOnly);
        }
        if (findReplaceOne != null) {
            findReplaceOne.setAlpha(editAlpha);
            findReplaceOne.setEnabled(!act.editorViewOnly);
        }
        if (findReplaceAll != null) {
            findReplaceAll.setAlpha(editAlpha);
            findReplaceAll.setEnabled(!act.editorViewOnly);
        }
        if (replaceInput != null) {
            replaceInput.setEnabled(!act.editorViewOnly);
            replaceInput.setAlpha(editAlpha);
        }
        refreshEditorFindToggleLook();
        refreshEditorViewOnlyToggleLook();
    }

    private void toggleEditorViewOnly() {
        setEditorViewOnly(!act.editorViewOnly);
    }

    void setEditorViewOnly(boolean viewOnly) {
        act.editorViewOnly = viewOnly;
        if (act.scriptEditorInput != null) {
            act.scriptEditorInput.setViewOnly(viewOnly);
            if (viewOnly) act.hideSoftKeyboard();
        }
        refreshEditorHeader();
        act.persistence.scheduleSave();
    }

    private void refreshEditorViewOnlyToggleLook() {
        if (editorViewOnlyToggle == null) return;
        editorViewOnlyToggle.setColorFilter(
                act.editorViewOnly ? act.M3_PRIMARY : act.M3_ON_SURFACE_VARIANT);
    }

    private void toggleEditorFindBar() {
        if (editorFindBar == null) return;
        boolean show = editorFindBar.getVisibility() != View.VISIBLE;
        editorFindBar.setVisibility(show ? View.VISIBLE : View.GONE);
        refreshEditorFindToggleLook();
        if (show && findInput != null) {
            findInput.requestFocus();
            act.showSoftKeyboard(findInput);
        } else if (act.scriptEditorInput != null) {
            act.scriptEditorInput.requestFocus();
        }
    }

    private void refreshEditorFindToggleLook() {
        if (editorFindToggle == null) return;
        boolean on = editorFindBar != null && editorFindBar.getVisibility() == View.VISIBLE;
        editorFindToggle.setColorFilter(on ? act.M3_PRIMARY : act.M3_ON_SURFACE_VARIANT);
    }

    // ---- run ----

    private void runEditorFile() {
        if (act.scriptEditorPath == null || editorRunning || act.editorVizArtifact != null) return;
        if (!act.computers.requireHost("Running scripts")) return;
        final String path = act.scriptEditorPath;
        // Running the disk copy of a file with pending edits would be a lie; save first.
        if (act.editorDirty) {
            saveScriptEditor(() -> act.syncThen(() -> startEditorRun(path)));
        } else {
            act.syncThen(() -> startEditorRun(path));
        }
    }

    private void startEditorRun(final String path) {
        if (editorRunning) return;
        setEditorRunning(true);
        showEditorOutput();
        if (editorOutput != null) editorOutput.setText("");
        if (editorOutputStatus != null) editorOutputStatus.setText("Running " + baseName(path) + "…");
        act.bridge.scriptMeta(path, new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject meta) {
                if (act.isDead()) return;
                act.scriptMetaCache.put(path, meta.toString());
                executeEditorRun(path, defaultArgsFromParams(meta.optJSONObject("params")));
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                executeEditorRun(path, new JSONObject());
            }
        });
    }

    private void executeEditorRun(final String path, JSONObject args) {
        act.bridge.runScript(path, args != null ? args : new JSONObject(),
                new BridgeClient.Callback<BridgeClient.RunResult>() {
                    @Override
                    public void onSuccess(BridgeClient.RunResult value) {
                        if (act.isDead()) return;
                        setEditorRunning(false);
                        StringBuilder sb = new StringBuilder();
                        if (value.stdout != null) sb.append(value.stdout);
                        if (value.stderr != null && !value.stderr.isEmpty()) {
                            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                            sb.append(value.stderr);
                        }
                        if (sb.length() == 0) sb.append(value.ok ? "(no output)" : "(failed)");
                        if (editorOutput != null) editorOutput.setText(sb.toString());
                        if (editorOutputStatus != null) {
                            editorOutputStatus.setText("exit " + value.exitCode);
                            editorOutputStatus.setTextColor(
                                    value.ok ? act.M3_ON_SURFACE_VARIANT : MainActivity.INK_COLORS[3]);
                        }
                        if (value.ok) act.vizImages.refreshImagesAfterScript(path, value.stdout, value.stderr);
                    }

                    @Override
                    public void onError(String message) {
                        if (act.isDead()) return;
                        setEditorRunning(false);
                        if (editorOutput != null) editorOutput.setText("error: " + message);
                        if (editorOutputStatus != null) {
                            editorOutputStatus.setText("failed");
                            editorOutputStatus.setTextColor(MainActivity.INK_COLORS[3]);
                        }
                    }
                });
    }

    private void setEditorRunning(boolean running) {
        editorRunning = running;
        if (editorRunSpinner != null) {
            editorRunSpinner.setVisibility(running ? View.VISIBLE : View.GONE);
        }
        refreshEditorHeader();
    }

    private void showEditorOutput() {
        if (editorOutputWrap != null) editorOutputWrap.setVisibility(View.VISIBLE);
    }

    private void clearEditorOutput() {
        if (editorOutput != null) editorOutput.setText("");
        if (editorOutputStatus != null) {
            editorOutputStatus.setText("");
            editorOutputStatus.setTextColor(act.M3_ON_SURFACE_VARIANT);
        }
        if (editorOutputWrap != null) editorOutputWrap.setVisibility(View.GONE);
    }

    // ---- panel ----

    private void toggleEditorPanel() {
        ensureEditorPanel();
        if (act.editorCollapsed) {
            showEditorPanel();
        } else {
            requestCloseEditor();
        }
    }

    void showEditorPanel() {
        act.closeOtherPanelsIfCompact("editor");
        ensureEditorPanel();
        animateEditorSlide(false);
    }

    private void hideEditorPanel() {
        if (act.editorPanel == null) return;
        if (act.scriptEditorInput != null && act.scriptEditorInput.hasFocus()) {
            act.scriptEditorInput.clearFocus();
            act.hideSoftKeyboard();
        }
        animateEditorSlide(true);
    }

    /** Autosave then hide — no confirm dialog. */
    /** Android back: closes the editor panel (saving first). */
    void closeEditorPanel() {
        requestCloseEditor();
    }

    private void requestCloseEditor() {
        if (act.editorDirty && act.scriptEditorPath != null) {
            saveScriptEditorQuiet(() -> hideEditorPanel());
        } else {
            hideEditorPanel();
        }
    }

    void ensureEditorPanel() {
        if (act.rootLayout == null) return;
        if (act.editorPanel == null) buildEditorPanel();

        if (act.editorPanelLp == null) {
            act.editorPanelLp = new FrameLayout.LayoutParams(
                    editorPanelWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
        }
        act.editorPanelLp.width = editorPanelWidth();
        act.editorPanelLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        act.editorPanelLp.gravity = act.explorerOnLeft ? Gravity.START : Gravity.END;

        if (act.editorPanel.getParent() != act.rootLayout) {
            if (act.editorPanel.getParent() instanceof ViewGroup) {
                ((ViewGroup) act.editorPanel.getParent()).removeView(act.editorPanel);
            }
            act.rootLayout.addView(act.editorPanel, act.editorPanelLp);
        } else {
            act.editorPanel.setLayoutParams(act.editorPanelLp);
        }
        // addView stacks on top — put the explorer back above the editor.
        if (act.explorerPanel != null) act.explorerPanel.bringToFront();
        if (act.explorerEdgeDrag != null) act.explorerEdgeDrag.bringToFront();
        refreshEditorPanelBackground();
        refreshEditorChrome();
        refreshEditorCodeStyle();
        ensureEditorResizeHandle();
        syncEditorResizeHandleVisibility();
        act.explorer.syncExplorerResizeHandleVisibility();
    }

    private void buildEditorPanel() {
        // Horizontal swipe anywhere in the panel slides it closed (same as explorer).
        act.editorPanel = new FrameLayout(act) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (act.editorCollapsed || editorPanelDragging) return false;
                final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        editorDragStartRawX = ev.getRawX();
                        editorDragStartRawY = ev.getRawY();
                        editorSlideStartRawX = ev.getRawX();
                        editorSlideStartTx = getTranslationX();
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - editorDragStartRawX;
                        float dy = ev.getRawY() - editorDragStartRawY;
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
                        if (Math.abs(dx) <= Math.abs(dy) * 1.15f) return false;
                        boolean towardClose = act.explorerOnLeft ? dx < 0f : dx > 0f;
                        if (!towardClose) return false;
                        editorPanelDragging = true;
                        setEditorResizeHandleVisible(false);
                        if (act.editorSlideAnim != null) act.editorSlideAnim.cancel();
                            if (editorVelocityTracker != null) editorVelocityTracker.recycle();
                        editorVelocityTracker = VelocityTracker.obtain();
                        editorVelocityTracker.addMovement(ev);
                        return true;
                    }
                    default:
                        return editorPanelDragging;
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (!editorPanelDragging) return super.onTouchEvent(ev);
                if (editorVelocityTracker != null) editorVelocityTracker.addMovement(ev);
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        setEditorTranslation(
                                editorSlideStartTx + (ev.getRawX() - editorSlideStartRawX));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float vx = 0f;
                        if (editorVelocityTracker != null) {
                            editorVelocityTracker.computeCurrentVelocity(1000);
                            vx = editorVelocityTracker.getXVelocity();
                            editorVelocityTracker.recycle();
                            editorVelocityTracker = null;
                        }
                        snapEditorFromGesture(vx);
                        editorPanelDragging = false;
                        syncEditorResizeHandleVisibility();
                        return true;
                    }
                    default:
                        return true;
                }
            }
        };
        act.editorPanel.setBackgroundColor(0x00000000);
        act.editorPanel.setElevation(0f);
        act.editorPanel.setClickable(true);
        act.editorPanel.setClipChildren(true);
        act.editorPanel.setClipToPadding(true);

        editorScrim = new View(act);
        editorScrim.setClickable(false);
        editorScrim.setFocusable(false);
        act.editorPanel.addView(editorScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editorShell = new LinearLayout(act);
        editorShell.setOrientation(LinearLayout.VERTICAL);
        editorShell.setPadding(
                act.dp(MainActivity.SPACE_LG), act.statusBarHeight() + act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG));
        act.editorPanel.addView(editorShell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        editorTitle = new TextView(act);
        editorTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        editorTitle.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        editorTitle.setMaxLines(1);
        editorTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        header.addView(editorTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        editorUndoButton = editorIcon(R.drawable.ic_undo, "Undo",
                v -> {
                    if (act.editorViewOnly || act.scriptEditorInput == null) return;
                    act.scriptEditorInput.undo();
                });
        header.addView(editorUndoButton);
        editorRedoButton = editorIcon(R.drawable.ic_redo, "Redo",
                v -> {
                    if (act.editorViewOnly || act.scriptEditorInput == null) return;
                    act.scriptEditorInput.redo();
                });
        header.addView(editorRedoButton);
        editorFindToggle = editorIcon(R.drawable.ic_search, "Find / replace", v -> toggleEditorFindBar());
        header.addView(editorFindToggle);
        editorViewOnlyToggle = editorIcon(R.drawable.ic_visibility, "View only",
                v -> toggleEditorViewOnly());
        header.addView(editorViewOnlyToggle);
        editorRunButton = editorIcon(R.drawable.ic_play, "Run file", v -> runEditorFile());
        header.addView(editorRunButton);
        editorShell.addView(header, MainActivity.matchWrap());

        editorFindBar = buildFindBar();
        editorFindBar.setVisibility(View.GONE);
        editorShell.addView(editorFindBar, MainActivity.matchWrap());

        editorTextWrap = new FrameLayout(act);
        editorTextWrapBg = new GradientDrawable();
        editorTextWrapBg.setCornerRadii(act.sidePanelCornerRadii(
                act.explorerOnLeft, true, false,
                Math.max(0, act.dp(MainActivity.CHAT_BG_CORNER) - act.dp(MainActivity.CHAT_CONTENT_INSET))));
        editorTextWrapBg.setColor(ThemeConfig.codeStyleById(act.codeStyleId).paper);
        editorTextWrap.setBackground(editorTextWrapBg);
        editorTextWrap.setClipToOutline(true);

        act.scriptEditorInput = new CodeEditorView(act);
        act.scriptEditorInput.setHint("Open a file from the explorer");
        act.scriptEditorInput.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD));
        act.scriptEditorInput.setCodeStyle(ThemeConfig.codeStyleById(act.codeStyleId));
        act.scriptEditorInput.setSoftWrap(true);
        act.scriptEditorInput.setViewOnly(act.editorViewOnly);
        // The EditText scrolls itself; a wrapping ScrollView would zero out getScrollY()
        // and defeat the gutter's visible-line culling.
        act.scriptEditorInput.setVerticalScrollBarEnabled(true);
        act.scriptEditorInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (!editorLoading && !act.editorViewOnly && act.editorVizArtifact == null) {
                    setEditorDirty(true);
                }
            }
        });
        editorTextWrap.addView(act.scriptEditorInput, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editorVizWeb = new WebView(act);
        editorVizWeb.getSettings().setJavaScriptEnabled(true);
        editorVizWeb.getSettings().setDomStorageEnabled(true);
        editorVizWeb.getSettings().setUseWideViewPort(true);
        editorVizWeb.getSettings().setLoadWithOverviewMode(true);
        editorVizWeb.setBackgroundColor(0xFFFFFFFF);
        editorVizWeb.setVisibility(View.GONE);
        editorVizWeb.setVerticalScrollBarEnabled(true);
        editorVizWeb.setHorizontalScrollBarEnabled(false);
        editorVizWeb.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        editorVizWeb.setWebViewClient(new WebViewClient());
        editorTextWrap.addView(editorVizWeb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        inputLp.topMargin = act.dp(MainActivity.SPACE_MD);
        editorShell.addView(editorTextWrap, inputLp);

        editorOutputWrap = new LinearLayout(act);
        editorOutputWrap.setOrientation(LinearLayout.VERTICAL);
        editorOutputWrap.setVisibility(View.GONE);
        editorOutputWrap.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD));
        editorOutputBg = new GradientDrawable();
        editorOutputBg.setCornerRadius(act.dp(10));
        editorOutputWrap.setBackground(editorOutputBg);

        LinearLayout outHeader = new LinearLayout(act);
        outHeader.setOrientation(LinearLayout.HORIZONTAL);
        outHeader.setGravity(Gravity.CENTER_VERTICAL);
        editorRunSpinner = new ProgressBar(act);
        editorRunSpinner.setIndeterminate(true);
        editorRunSpinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(act.dp(14), act.dp(14));
        spinLp.rightMargin = act.dp(MainActivity.SPACE_SM);
        outHeader.addView(editorRunSpinner, spinLp);
        editorOutputStatus = new TextView(act);
        editorOutputStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        editorOutputStatus.setMaxLines(1);
        editorOutputStatus.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        outHeader.addView(editorOutputStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editorOutputClear = new TextView(act);
        editorOutputClear.setText("Clear");
        editorOutputClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        editorOutputClear.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS));
        editorOutputClear.setOnClickListener(v -> clearEditorOutput());
        outHeader.addView(editorOutputClear);
        editorOutputWrap.addView(outHeader, MainActivity.matchWrap());

        editorOutputScroll = new ScrollView(act);
        editorOutput = new TextView(act);
        editorOutput.setTypeface(Typeface.MONOSPACE);
        editorOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        editorOutput.setTextIsSelectable(true);
        editorOutputScroll.addView(editorOutput, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, act.dp(150));
        outLp.topMargin = act.dp(MainActivity.SPACE_SM);
        editorOutputWrap.addView(editorOutputScroll, outLp);

        LinearLayout.LayoutParams outWrapLp = MainActivity.matchWrap();
        outWrapLp.topMargin = act.dp(MainActivity.SPACE_MD);
        editorShell.addView(editorOutputWrap, outWrapLp);
    }

    void refreshEditorPanelBackground() {
        if (editorScrim == null) return;
        // Square on the screen edge; rounded on the canvas-facing edge.
        float[] radii = act.sidePanelCornerRadii(
                act.explorerOnLeft,
                /* roundCanvasEdge */ true,
                /* roundScreenEdge */ false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(act.M3_SURFACE_CONTAINER);
        g.setCornerRadii(radii);
        editorScrim.setBackground(g);
        if (act.editorPanel != null) {
            act.editorPanel.setBackgroundColor(0x00000000);
            applyEditorPanelClip();
        }
        if (editorTextWrapBg != null) {
            // Match panel: round only the canvas-facing corners of the code well.
            float inner = Math.max(0, act.dp(MainActivity.CHAT_BG_CORNER) - act.dp(MainActivity.CHAT_CONTENT_INSET));
            editorTextWrapBg.setCornerRadii(act.sidePanelCornerRadii(
                    act.explorerOnLeft, true, false, inner));
            if (editorTextWrap != null) editorTextWrap.setBackground(editorTextWrapBg);
        }
        // Screen-edge padding stays 0 so that side reads as the screen end.
        if (editorShell != null) {
            int side = act.dp(MainActivity.SPACE_LG);
            int top = act.statusBarHeight() + act.dp(MainActivity.SPACE_MD);
            int bottom = act.dp(MainActivity.SPACE_LG);
            if (act.explorerOnLeft) {
                editorShell.setPadding(0, top, side, bottom);
            } else {
                editorShell.setPadding(side, top, 0, bottom);
            }
        }
    }

    private void applyEditorPanelClip() {
        if (editorShell == null) return;
        // Scrim paints the rounded shape; do not clip header icons against the corner.
        editorShell.setClipToOutline(false);
        editorShell.setClipChildren(false);
        editorShell.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    }

    /** Theme colours are mutable fields, so every cached drawable is rebuilt here. */
    void refreshEditorChrome() {
        if (editorTitle != null) editorTitle.setTextColor(act.M3_ON_SURFACE);
        ImageView[] icons = {
                editorUndoButton, editorRedoButton, editorRunButton,
                editorFindToggle, editorViewOnlyToggle
        };
        for (ImageView v : icons) {
            if (v == null) continue;
            v.setColorFilter(act.M3_ON_SURFACE_VARIANT);
            v.setBackground(null);
            if (Build.VERSION.SDK_INT >= 23) v.setForeground(null);
        }
        refreshEditorFindToggleLook();
        refreshEditorViewOnlyToggleLook();
        if (act.scriptEditorInput != null) {
            act.scriptEditorInput.setHintTextColor(act.M3_ON_SURFACE_VARIANT);
        }
        if (editorOutputBg != null) {
            editorOutputBg.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
            if (editorOutputWrap != null) editorOutputWrap.setBackground(editorOutputBg);
        }
        if (editorOutputStatus != null) editorOutputStatus.setTextColor(act.M3_ON_SURFACE_VARIANT);
        if (editorOutputClear != null) editorOutputClear.setTextColor(act.M3_ON_SURFACE_VARIANT);
        if (editorOutput != null) editorOutput.setTextColor(act.M3_ON_SURFACE);
        if (editorRunSpinner != null && Build.VERSION.SDK_INT >= 21) {
            editorRunSpinner.getIndeterminateDrawable().setColorFilter(
                    new PorterDuffColorFilter(act.M3_PRIMARY, PorterDuff.Mode.SRC_IN));
        }
        if (findCount != null) findCount.setTextColor(act.M3_ON_SURFACE_VARIANT);
        if (findReplaceOne != null) findReplaceOne.setTextColor(act.M3_ON_SURFACE_VARIANT);
        if (findReplaceAll != null) findReplaceAll.setTextColor(act.M3_PRIMARY);
        applyEditorFieldTheme(findInput);
        applyEditorFieldTheme(replaceInput);
        if (act.editorToggleButton != null) act.applyIconSelected(act.editorToggleButton, !act.editorCollapsed);
        refreshEditorHeader();
    }

    void refreshEditorCodeStyle() {
        if (act.scriptEditorInput == null) return;
        ThemeConfig.CodeStyle style = ThemeConfig.codeStyleById(act.codeStyleId);
        act.scriptEditorInput.setCodeStyle(style);
        if (editorTextWrapBg != null) {
            editorTextWrapBg.setColor(style.paper);
            if (editorTextWrap != null) editorTextWrap.setBackground(editorTextWrapBg);
        }
    }

    void applyEditorCollapsed(boolean collapsed) {
        act.editorCollapsed = collapsed;
        // Budgets depend on which panels are open.
        act.rebudgetPanelWidths(act.editorPanel);
        if (act.editorSlideAnim != null) act.editorSlideAnim.cancel();
        ensureEditorPanel();
        if (act.editorPanel != null) {
            act.editorPanel.setTranslationX(
                    collapsed ? editorClosedTranslation() : editorOpenTranslation());
            act.editorPanel.setVisibility(View.VISIBLE);
        }
        if (act.editorToggleButton != null) act.applyIconSelected(act.editorToggleButton, !collapsed);
        if (act.centerPane != null) act.centerPane.setTranslationX(0f);
        syncEditorResizeHandleVisibility();
        act.updateCenterPaneInsets();
        act.updateToolPillPosition();
    }

    /** Open position sits just inboard of the explorer so neither panel covers the other. */
    private float editorOpenTranslation() {
        int off = act.explorer.explorerOpenOffsetPx();
        return act.explorerOnLeft ? off : -off;
    }

    float editorClosedTranslation() {
        return act.explorerOnLeft ? -editorPanelWidth() : editorPanelWidth();
    }

    /** Keeps the editor flush against the explorer while the explorer slides. */
    void syncEditorOffset() {
        if (act.editorPanel == null || act.editorCollapsed || editorPanelDragging) return;
        // Don't fight the editor's own open/close slide; that animation's end
        // callback re-applies the open translation once it settles.
        if (act.editorSlideAnim != null && act.editorSlideAnim.isRunning()) return;
        act.editorPanel.setTranslationX(editorOpenTranslation());
        if (act.editorResizing || isEditorFullyExpanded()) {
            positionEditorResizeHandle();
        }
        refreshEditorPanelBackground();
        act.explorer.refreshExplorerPanelBackground();
    }

    /** How much of the screen edge the editor occupies, explorer offset included. */
    int visibleEditorWidthPx() {
        if (act.editorPanel == null) return 0;
        float tx = act.editorPanel.getTranslationX();
        int visible = Math.round(editorPanelWidth() + (act.explorerOnLeft ? tx : -tx));
        return Math.max(0, visible);
    }

    int editorPanelWidth() {
        if (act.editorPanelWidthPx <= 0) act.editorPanelWidthPx = act.dp(MainActivity.EDITOR_PANEL_W);
        return act.clampSidePanelWidth(act.editorPanelWidthPx, act.dp(MainActivity.EDITOR_PANEL_MIN_W));
    }

    private void applyEditorPanelWidth(int widthPx) {
        act.editorPanelWidthPx = act.clampSidePanelWidth(widthPx, act.dp(MainActivity.EDITOR_PANEL_MIN_W));
        if (act.editorPanelLp != null && act.editorPanel != null) {
            act.editorPanelLp.width = editorPanelWidth();
            act.editorPanel.setLayoutParams(act.editorPanelLp);
        }
        if (!act.editorCollapsed && act.editorPanel != null) {
            act.editorPanel.setTranslationX(editorOpenTranslation());
        }
        syncEditorResizeHandleVisibility();
        act.explorer.syncExplorerResizeHandleVisibility();
        refreshEditorPanelBackground();
        act.explorer.refreshExplorerPanelBackground();
        act.rebudgetPanelWidths(act.editorPanel);
        act.updateCenterPaneInsets();
        act.updateToolPillPosition();
    }

    private void ensureEditorResizeHandle() {
        if (act.rootLayout == null) return;
        if (act.editorResizeHandle == null) {
            FrameLayout handle = new FrameLayout(act);
            handle.setClickable(true);
            handle.setFocusable(true);
            handle.setContentDescription("Drag to resize editor");
            handle.setVisibility(View.INVISIBLE);
            handle.setElevation(0f);
            View pill = new View(act);
            act.editorResizeGrip = pill;
            FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                    act.dp(MainActivity.CHAT_RESIZE_PILL_W), act.dp(MainActivity.CHAT_RESIZE_PILL_H));
            gripLp.gravity = Gravity.CENTER;
            handle.addView(pill, gripLp);
            handle.setOnTouchListener((v, event) -> {
                if (act.editorPanel == null || act.editorCollapsed) return false;
                editorResizeLastRawX = event.getRawX();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        editorPanelDragging = false;
                        act.editorResizing = true;
                        editorResizeStartWidth = editorPanelWidth();
                        editorResizePendingWidth = editorResizeStartWidth;
                        editorResizeStartRawX = editorResizeLastRawX;
                        setEditorResizeHandleVisible(true);
                        positionEditorResizeHandle();
                        if (act.editorResizeHandle != null) act.editorResizeHandle.bringToFront();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (!act.editorResizing) return true;
                        float dx = event.getRawX() - editorResizeStartRawX;
                        int delta = act.explorerOnLeft ? Math.round(dx) : Math.round(-dx);
                        applyEditorPanelWidth(editorResizeStartWidth + delta);
                        editorResizePendingWidth = editorPanelWidth();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if (act.editorResizing) {
                            act.editorResizing = false;
                            applyEditorPanelWidth(editorResizePendingWidth);
                            act.persistence.scheduleSave();
                            syncEditorResizeHandleVisibility();
                        }
                        return true;
                    default:
                        return false;
                }
            });
            act.editorResizeHandle = handle;
        }
        act.refreshSidePanelHandleLook(act.editorResizeHandle, act.editorResizeGrip);
        syncEditorResizeHandleVisibility();
    }

    private void setEditorResizeHandleVisible(boolean visible) {
        if (act.editorResizeHandle == null) return;
        act.editorResizeHandle.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (act.editorResizeGrip != null) {
            act.editorResizeGrip.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** True only when the panel is fully open (not mid-slide / collapsing). */
    private boolean isEditorFullyExpanded() {
        if (act.editorCollapsed || act.editorPanel == null || editorPanelDragging) return false;
        return Math.abs(act.editorPanel.getTranslationX() - editorOpenTranslation()) < 0.5f;
    }

    /**
     * Resize pill: visible only while fully expanded (or actively resizing),
     * matching the chat panel.
     */
    void syncEditorResizeHandleVisibility() {
        boolean show = !act.compactScreen() && (act.editorResizing || isEditorFullyExpanded());
        setEditorResizeHandleVisible(show);
        if (show) positionEditorResizeHandle();
    }

    private void positionEditorResizeHandle() {
        if (act.rootLayout == null || act.editorResizeHandle == null) return;
        if (act.editorCollapsed || act.editorPanel == null) {
            setEditorResizeHandleVisible(false);
            return;
        }
        int hw = act.dp(MainActivity.CHAT_RESIZE_HANDLE_W);
        int hh = act.dp(MainActivity.CHAT_RESIZE_HANDLE_H);
        int rootW = act.rootLayout.getWidth();
        int rootH = act.rootLayout.getHeight();
        if (rootW <= 0 || rootH <= 0) {
            act.rootLayout.post(this::positionEditorResizeHandle);
            return;
        }
        int editorW = editorPanelWidth();
        float tx = act.editorPanel.getTranslationX();
        int edgeX = act.explorerOnLeft
                ? Math.round(editorW + tx)
                : Math.round(rootW - editorW + tx);
        int left = Math.max(0, Math.min(edgeX - hw / 2, rootW - hw));
        int top = Math.max(0, (rootH - hh) / 2);

        if (editorResizeHandleLp == null) {
            editorResizeHandleLp = new FrameLayout.LayoutParams(hw, hh);
        }
        editorResizeHandleLp.width = hw;
        editorResizeHandleLp.height = hh;
        editorResizeHandleLp.gravity = Gravity.TOP | Gravity.START;
        editorResizeHandleLp.leftMargin = left;
        editorResizeHandleLp.topMargin = top;
        editorResizeHandleLp.rightMargin = 0;
        editorResizeHandleLp.bottomMargin = 0;

        if (act.editorResizeHandle.getParent() != act.rootLayout) {
            if (act.editorResizeHandle.getParent() instanceof ViewGroup) {
                ((ViewGroup) act.editorResizeHandle.getParent()).removeView(act.editorResizeHandle);
            }
            act.rootLayout.addView(act.editorResizeHandle, editorResizeHandleLp);
        } else {
            act.editorResizeHandle.setLayoutParams(editorResizeHandleLp);
        }
        if (act.editorResizeGrip != null) {
            FrameLayout.LayoutParams gripLp = (FrameLayout.LayoutParams) act.editorResizeGrip.getLayoutParams();
            if (gripLp == null) {
                gripLp = new FrameLayout.LayoutParams(act.dp(MainActivity.CHAT_RESIZE_PILL_W), act.dp(MainActivity.CHAT_RESIZE_PILL_H));
            }
            gripLp.width = act.dp(MainActivity.CHAT_RESIZE_PILL_W);
            gripLp.height = act.dp(MainActivity.CHAT_RESIZE_PILL_H);
            gripLp.gravity = Gravity.CENTER;
            gripLp.leftMargin = 0;
            gripLp.rightMargin = 0;
            act.editorResizeGrip.setLayoutParams(gripLp);
        }
        act.refreshSidePanelHandleLook(act.editorResizeHandle, act.editorResizeGrip);
        if (act.editorResizeHandle.getVisibility() == View.VISIBLE) {
            act.editorResizeHandle.bringToFront();
        }
    }

    private void animateEditorSlide(boolean collapsedEnd) {
        if (act.editorPanel == null) return;
        if (act.editorSlideAnim != null) act.editorSlideAnim.cancel();
        int oldLeft = act.centerPane != null ? act.centerPane.getPaddingLeft() : 0;
        int oldRight = act.centerPane != null ? act.centerPane.getPaddingRight() : 0;
        act.editorCollapsed = collapsedEnd;
        // Budgets depend on which panels are open.
        act.rebudgetPanelWidths(act.editorPanel);
        final float target = collapsedEnd ? editorClosedTranslation() : editorOpenTranslation();
        act.editorSlideAnim = act.slidePanelTo(
                act.editorPanel, target,
                () -> {
                    syncEditorResizeHandleVisibility();
                    // Re-apply in case the explorer moved while we were sliding.
                    if (!act.editorCollapsed && act.editorPanel != null
                            && (act.editorSlideAnim == null || !act.editorSlideAnim.isRunning())) {
                        act.editorPanel.setTranslationX(editorOpenTranslation());
                    }
                    positionEditorResizeHandle();
                });
        if (act.editorToggleButton != null) {
            act.applyIconSelected(act.editorToggleButton, !collapsedEnd);
        }
        if (collapsedEnd) {
            setEditorResizeHandleVisible(false);
        } else {
            syncEditorResizeHandleVisibility();
        }
        act.explorer.syncExplorerResizeHandleVisibility();
        refreshEditorPanelBackground();
        act.explorer.refreshExplorerPanelBackground();
        act.updateCenterPaneInsets();
        act.persistence.scheduleSave();
    }

    private void setEditorTranslation(float tx) {
        if (act.editorPanel == null) return;
        float closed = editorClosedTranslation();
        float open = editorOpenTranslation();
        if (act.explorerOnLeft) {
            tx = Math.max(closed, Math.min(open, tx));
        } else {
            tx = Math.min(closed, Math.max(open, tx));
        }
        act.editorPanel.setTranslationX(tx);
        if (Math.abs(tx - open) > 0.5f) {
            setEditorResizeHandleVisible(false);
        }
        positionEditorResizeHandle();
        act.updateToolPillPosition();
    }

    private void snapEditorFromGesture(float velocityX) {
        float closed = editorClosedTranslation();
        float open = editorOpenTranslation();
        float tx = act.editorPanel != null ? act.editorPanel.getTranslationX() : closed;
        float span = closed - open;
        float progress = Math.abs(span) < 1f
                ? (act.editorCollapsed ? 1f : 0f)
                : (tx - open) / span;
        boolean close;
        float fling = act.explorerOnLeft ? -velocityX : velocityX;
        if (Math.abs(fling) > 800f) {
            close = fling > 0f;
        } else {
            close = progress > 0.45f;
        }
        animateEditorSlide(close);
    }

    private ImageView editorIcon(int iconRes, String description, View.OnClickListener onClick) {
        ImageView v = new ImageView(act);
        v.setImageResource(iconRes);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setColorFilter(act.M3_ON_SURFACE_VARIANT);
        v.setContentDescription(description);
        v.setBackground(null);
        if (Build.VERSION.SDK_INT >= 23) v.setForeground(null);
        v.setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(6));
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(act.dp(MainActivity.ICON_SIZE), act.dp(MainActivity.ICON_SIZE));
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    /** Find/replace strip for the code editor. */
    private View buildFindBar() {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, act.dp(MainActivity.SPACE_MD), 0, 0);

        findInput = editorField("Find");
        LinearLayout.LayoutParams findLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        bar.addView(findInput, findLp);

        findCount = new TextView(act);
        findCount.setTextColor(act.M3_ON_SURFACE_VARIANT);
        findCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        findCount.setPadding(act.dp(MainActivity.SPACE_MD), 0, act.dp(MainActivity.SPACE_MD), 0);
        bar.addView(findCount);

        bar.addView(editorIcon(R.drawable.ic_chevron_left, "Previous match",
                v -> stepFind(false)));
        bar.addView(editorIcon(R.drawable.ic_chevron_right, "Next match",
                v -> stepFind(true)));

        replaceInput = editorField("Replace");
        LinearLayout.LayoutParams repLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        repLp.leftMargin = act.dp(MainActivity.SPACE_MD);
        bar.addView(replaceInput, repLp);

        findReplaceOne = new TextView(act);
        findReplaceOne.setText("Replace");
        findReplaceOne.setTextColor(act.M3_ON_SURFACE_VARIANT);
        findReplaceOne.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        findReplaceOne.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM));
        findReplaceOne.setOnClickListener(v -> {
            if (act.editorViewOnly || act.scriptEditorInput == null) return;
            act.scriptEditorInput.replaceSelection(text(replaceInput));
            stepFind(true);
        });
        bar.addView(findReplaceOne);

        findReplaceAll = new TextView(act);
        findReplaceAll.setText("All");
        findReplaceAll.setTextColor(act.M3_PRIMARY);
        findReplaceAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        findReplaceAll.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM));
        findReplaceAll.setOnClickListener(v -> {
            if (act.editorViewOnly || act.scriptEditorInput == null) return;
            int n = act.scriptEditorInput.replaceAll(text(findInput), text(replaceInput), false);
            findCount.setText(n + " replaced");
        });
        bar.addView(findReplaceAll);

        findInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                refreshFindCount();
            }
        });
        return bar;
    }

    EditText editorField(String hint) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setPadding(act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_MD));
        applyEditorFieldTheme(e);
        return e;
    }

    private void applyEditorFieldTheme(EditText e) {
        if (e == null) return;
        e.setTextColor(act.M3_ON_SURFACE);
        e.setHintTextColor(act.M3_ON_SURFACE_VARIANT);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(12));
        // Slightly raised vs the dialog surface so the field is visible on dark themes.
        bg.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
        bg.setStroke(act.dp(1), (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x66000000);
        e.setBackground(bg);
    }

    static String text(EditText e) {
        return e == null || e.getText() == null ? "" : e.getText().toString();
    }

    private void refreshFindCount() {
        if (act.scriptEditorInput == null || findCount == null) return;
        String needle = text(findInput);
        if (needle.isEmpty()) {
            findCount.setText("");
            return;
        }
        findCount.setText(String.valueOf(act.scriptEditorInput.countMatches(needle, false)));
    }

    private void stepFind(boolean forward) {
        if (act.scriptEditorInput == null) return;
        String needle = text(findInput);
        if (needle.isEmpty()) return;
        int caret = Math.max(0, act.scriptEditorInput.getSelectionEnd());
        int at = forward
                ? act.scriptEditorInput.findNext(needle, caret, false)
                : act.scriptEditorInput.findPrevious(
                        needle, Math.max(0, act.scriptEditorInput.getSelectionStart()), false);
        if (at < 0) {
            findCount.setText("0");
            return;
        }
        act.scriptEditorInput.selectRange(at, needle.length());
        refreshFindCount();
    }

    private void saveScriptEditor() {
        saveScriptEditor(null);
    }

    void saveScriptEditorQuiet() {
        saveScriptEditorQuiet(null);
    }

    void saveScriptEditorQuiet(final Runnable after) {
        saveScriptEditor(after, false);
    }

    private void saveScriptEditor(final Runnable after) {
        saveScriptEditor(after, true);
    }

    private void saveScriptEditor(final Runnable after, boolean announce) {
        if (act.editorVizArtifact != null || act.scriptEditorPath == null || act.scriptEditorInput == null) {
            if (after != null) after.run();
            return;
        }
        if (act.scriptEditorPath.toLowerCase(java.util.Locale.US).endsWith(".viz")) {
            if (after != null) after.run();
            return;
        }
        final String path = act.scriptEditorPath;
        final String text = act.scriptEditorInput.getText() != null
                ? act.scriptEditorInput.getText().toString() : "";
        act.workspace.writeFile(path, text, new BridgeClient.Callback<BridgeClient.FileContent>() {
            @Override
            public void onSuccess(BridgeClient.FileContent file) {
                if (act.isDead()) return;
                if (path.equals(act.scriptEditorPath)) setEditorDirty(false);
                act.persistence.scheduleSave();
                if (announce) act.conversations.appendChat("system", "saved " + path);
                if (after != null) after.run();
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                act.conversations.appendChat("warn", "save failed: " + message);
                if (after != null) after.run();
            }
        });
    }

    void reloadEditorViz() {
        if (act.editorVizArtifact == null || editorVizWeb == null) return;
        String url = act.artifactUrl(act.editorVizArtifact);
        editorVizWeb.loadUrl(url + (url.indexOf('?') >= 0 ? '&' : '?') + "t=" + System.currentTimeMillis());
    }

    private void loadInitialFile() {
        openWorkspaceFile("demo/hello.py", true);
    }

    private void openWorkspaceFile(String path, boolean resetView) {
        // Code no longer sits on the canvas — open (or seed) it in the editor panel.
        act.openFile = path;
        if (resetView) {
            // Expand the editor on first boot so the demo file is visible.
            ensureEditorPanel();
            applyEditorCollapsed(false);
        }
        act.workspace.readFile(path, new BridgeClient.Callback<BridgeClient.FileContent>() {
            @Override
            public void onSuccess(BridgeClient.FileContent value) {
                if (act.isDead()) return;
                act.openFile = value.path;
                openScriptEditor(value.path);
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                if ("demo/hello.py".equals(path)) {
                    // Seed a tiny demo buffer when the workspace file is missing.
                    ensureEditorPanel();
                    act.scriptEditorPath = path;
                    act.openFile = path;
                    setEditorText(
                            "\"\"\"Tiny demo file for Inkside.\"\"\"\n\n"
                                    + "def greet(names):\n"
                                    + "    out = []\n"
                                    + "    for name in names:\n"
                                    + "        out.append(f\"hello, {name}\")\n"
                                    + "    return out\n");
                    setEditorDirty(true);
                    applyEditorCollapsed(false);
                    refreshEditorChrome();
                } else {
                    act.conversations.appendChat("warn", message);
                }
            }
        });
    }

    void refreshOpenFiles() {
        // Reload the file currently open in the editor (if any) after an agent turn.
        if (act.scriptEditorPath == null || act.scriptEditorPath.isEmpty()) return;
        if (act.editorVizArtifact != null) {
            reloadEditorViz();
            return;
        }
        if (act.editorDirty) return; // don't clobber unsaved local edits
        final String path = act.scriptEditorPath;
        act.workspace.readFile(path, new BridgeClient.Callback<BridgeClient.FileContent>() {
            @Override
            public void onSuccess(BridgeClient.FileContent file) {
                if (act.isDead()) return;
                if (!path.equals(act.scriptEditorPath) || act.editorDirty || act.editorVizArtifact != null) return;
                setEditorText(file.text != null ? file.text : "");
                setEditorDirty(false);
                refreshEditorChrome();
            }

            @Override
            public void onError(String message) {
                /* keep current buffer */
            }
        });
    }

    private void showWorkspacePicker() {
        act.workspace.listFiles(act.workspaceDir, new BridgeClient.Callback<BridgeClient.DirListing>() {
            @Override
            public void onSuccess(BridgeClient.DirListing value) {
                if (act.isDead()) return;
                act.workspaceDir = value.path;
                List<String> labels = new ArrayList<>();
                List<BridgeClient.FileEntry> entries = new ArrayList<>();
                if (value.parent != null) {
                    labels.add("..");
                    entries.add(null);
                }
                for (BridgeClient.FileEntry e : value.items) {
                    labels.add((e.type.equals("dir") ? "[dir] " : "") + e.name);
                    entries.add(e);
                }
                new M3Dialog.Builder(act)
                        .setTitle(value.path)
                        .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                            BridgeClient.FileEntry e = entries.get(which);
                            if (e == null) {
                                act.workspaceDir = value.parent;
                                showWorkspacePicker();
                                return;
                            }
                            if ("dir".equals(e.type)) {
                                act.workspaceDir = e.path;
                                showWorkspacePicker();
                            } else {
                                openWorkspaceFile(e.path, false);
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                act.conversations.appendChat("warn", "files: " + message);
            }
        });
    }
}
