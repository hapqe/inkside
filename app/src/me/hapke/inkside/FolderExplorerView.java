package me.hapke.inkside;

import android.content.ClipData;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Workspace file tree as a full-height sidebar panel (same role as the chat sidebar).
 * Remembers expanded paths and scroll position across opens.
 */
final class FolderExplorerView extends FrameLayout {
    interface Listener {
        void onOpenFile(String path);

        /** Open a PDF as the main page document (not a canvas card). */
        void onOpenPdfDocument(String path);

        /** Open a .viz pointer as a rendered visualization (never as source). */
        void onOpenViz(String path);

        void onAddImageToCanvas(String path);

        /** Create a new blank PDF in the workspace. */
        void onCreatePdfDocument();

        void onDismiss();

        /** A file or folder moved/renamed ({@code to} set) or was deleted ({@code to} null). */
        default void onPathChanged(String from, String to) {}

        /** A file operation succeeded: other file views (All Projects) should reload. */
        default void onFilesMutated() {}

        default void onExplorerMessage(String message) {}

        /** Pick files on the tablet and upload them into this workspace folder. */
        default void onUploadInto(String dir) {}

        /** Save this workspace file to the tablet's Downloads. */
        default void onDownloadFile(String path) {}
    }

    /** Explorer clipboard: one item, cut or copied, pasted into a folder. */
    private static String clipPath;
    private static boolean clipCut;

    /** Persists across popup open/close and app sessions. */
    private static final Set<String> EXPANDED = new HashSet<>();
    private static int savedScrollY;
    private static boolean prefsLoaded;

    static {
        EXPANDED.add(".");
    }

    static void ensurePrefsLoaded(Context ctx) {
        if (prefsLoaded || ctx == null) return;
        prefsLoaded = true;
        try {
            android.content.SharedPreferences sp =
                    ctx.getApplicationContext().getSharedPreferences("folder_ui", Context.MODE_PRIVATE);
            savedScrollY = Math.max(0, sp.getInt("scrollY", 0));
            String joined = sp.getString("expanded", ".");
            EXPANDED.clear();
            EXPANDED.add(".");
            if (joined != null) {
                for (String p : joined.split("\n")) {
                    String key = normalizeKey(p);
                    if (!key.isEmpty()) EXPANDED.add(key);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void persistPrefs() {
        try {
            android.content.SharedPreferences sp =
                    getContext().getApplicationContext().getSharedPreferences("folder_ui", Context.MODE_PRIVATE);
            StringBuilder sb = new StringBuilder();
            for (String p : EXPANDED) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(p);
            }
            sp.edit().putString("expanded", sb.toString()).putInt("scrollY", savedScrollY).apply();
        } catch (Exception ignored) {
        }
    }

    static org.json.JSONObject exportUiState() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String p : EXPANDED) arr.put(p);
            o.put("expanded", arr);
            o.put("scrollY", savedScrollY);
        } catch (Exception ignored) {
        }
        return o;
    }

    static void importUiState(org.json.JSONObject o) {
        if (o == null) return;
        EXPANDED.clear();
        EXPANDED.add(".");
        org.json.JSONArray arr = o.optJSONArray("expanded");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, null);
                if (p != null && !p.isEmpty()) EXPANDED.add(normalizeKey(p));
            }
        }
        savedScrollY = Math.max(0, o.optInt("scrollY", 0));
        prefsLoaded = true;
    }

    private final Workspace workspace;
    private final Listener listener;
    private final LinearLayout treeList;
    private final ScrollView scroll;
    private final TextView statusLabel;
    private final TextView titleView;
    private final ImageView rootIcon;
    /** "+ New" in the header: opens the PDF / folder / upload menu, as in All Projects. */
    private final LinearLayout newButton;
    private final ImageView newIcon;
    private final TextView newLabel;
    /** The open menu: a click-catcher over the panel and the option pills under the button. */
    private View newScrim;
    private LinearLayout newMenu;
    private final List<View> newOptions = new ArrayList<>();
    private boolean newMenuOpen;
    private final View headerDivider;
    private final LinearLayout header;
    private final Map<String, Node> nodesByPath = new HashMap<>();
    private Node root;
    private int pendingLoads;

    /** Active project root (workspace-relative). Null = whole workspace (legacy). */
    private String projectPath = ".";
    private String projectName = "Workspace";
    private final List<BridgeClient.LibraryEntry> sharedPdfs = new ArrayList<>();
    /** The workspace's visualizations/*.viz pointers, shown under the project. */
    private final List<BridgeClient.LibraryEntry> vizFiles = new ArrayList<>();

    private int colorOnSurface = 0xFFE6E1E9;
    private int colorOnVariant = 0xFF9A95A3;
    private int colorPrimary = 0xFFBAC3FF;
    private int colorPrimaryContainer = 0xFF3949AB;
    private int colorOnPrimaryContainer = 0xFFE8EAFF;
    private int colorHeaderBg = 0xFF25242C;
    private int colorOutline = 0xFF49454F;
    private int colorDivider = 0xFF3D3A45;
    private int colorRowPress = 0x22BAC3FF;

    private static final class Node {
        final String path;
        final String name;
        final String type; // dir | file
        final int depth;
        boolean expanded;
        boolean loading;
        boolean loaded;
        final List<Node> children = new ArrayList<>();

        Node(String path, String name, String type, int depth) {
            this.path = path;
            this.name = name;
            this.type = type;
            this.depth = depth;
        }

        boolean isDir() {
            return "dir".equals(type);
        }
    }

    FolderExplorerView(Context ctx, Workspace workspace, Listener listener) {
        super(ctx);
        ensurePrefsLoaded(ctx);
        this.workspace = workspace;
        this.listener = listener;
        setClickable(true);
        setBackgroundColor(0x00000000);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, dp(4), dp(4));

        header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(10), dp(2), dp(8));
        header.setBackgroundColor(0x00000000);

        rootIcon = new ImageView(ctx);
        rootIcon.setImageResource(R.drawable.ic_folder_open);
        tint(rootIcon, colorPrimary);
        header.addView(rootIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        titleView = new TextView(ctx);
        titleView.setText("Workspace");
        titleView.setTextColor(colorOnSurface);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setPadding(dp(8), 0, 0, 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleView, titleLp);

        newButton = new LinearLayout(ctx);
        newButton.setOrientation(LinearLayout.HORIZONTAL);
        newButton.setGravity(Gravity.CENTER_VERTICAL);
        newButton.setPadding(dp(10), dp(6), dp(14), dp(6));
        newIcon = new ImageView(ctx);
        newIcon.setImageResource(R.drawable.ic_add);
        newButton.addView(newIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        newLabel = new TextView(ctx);
        newLabel.setText("New");
        newLabel.setTextSize(13);
        newLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(6);
        newButton.addView(newLabel, nlp);
        newButton.setClickable(true);
        newButton.setFocusable(true);
        newButton.setContentDescription("New PDF, folder or upload");
        newButton.setOnClickListener(v -> setNewMenuOpen(!newMenuOpen));
        styleNewButton();
        // At least 34dp tall, and taller when an Inkside theme adds its border and shadow.
        newButton.setMinimumHeight(dp(34));
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        newLp.rightMargin = dp(4);
        header.addView(newButton, newLp);
        col.addView(header, matchWrap());

        headerDivider = new View(ctx);
        headerDivider.setBackgroundColor(colorDivider);
        col.addView(headerDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) -> {
            savedScrollY = scrollY;
        });
        treeList = new LinearLayout(ctx);
        treeList.setOrientation(LinearLayout.VERTICAL);
        treeList.setPadding(0, dp(4), 0, dp(6));
        scroll.addView(treeList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Quiet status line for load errors only (hidden while idle).
        statusLabel = new TextView(ctx);
        statusLabel.setTextColor(colorOnVariant);
        statusLabel.setTextSize(10);
        statusLabel.setPadding(dp(8), dp(2), dp(8), dp(4));
        statusLabel.setVisibility(View.GONE);
        col.addView(statusLabel, matchWrap());

        addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildNewMenu();

        root = new Node(".", "Workspace", "dir", 0);
        root.expanded = true;
        nodesByPath.put(".", root);
        loadChildren(root);
    }

    /**
     * Bind explorer to a project: Shared PDFs on top, project files beneath.
     * {@code projectPath} is workspace-relative (e.g. {@code Notes}).
     */
    void setProjectContext(String projectPath, String projectName) {
        this.projectPath = projectPath != null && !projectPath.isEmpty() ? projectPath : ".";
        this.projectName = projectName != null && !projectName.isEmpty()
                ? projectName
                : displayName(this.projectPath);
        titleView.setText(this.projectName);
        nodesByPath.clear();
        root = new Node(this.projectPath, this.projectName, "dir", 0);
        root.expanded = true;
        nodesByPath.put(normalizeKey(this.projectPath), root);
        EXPANDED.add(normalizeKey(this.projectPath));
        loadChildren(root);
        reloadShared();
    }

    void reloadShared() {
        workspace.listFiles("visualizations", new BridgeClient.Callback<BridgeClient.DirListing>() {
            @Override
            public void onSuccess(BridgeClient.DirListing value) {
                final List<BridgeClient.LibraryEntry> all = new ArrayList<>();
                if (value != null) {
                    for (BridgeClient.FileEntry e : value.items) {
                        if ("file".equals(e.type) && e.name.toLowerCase(Locale.US).endsWith(".viz")) {
                            all.add(new BridgeClient.LibraryEntry(e.name, e.path, "file"));
                        }
                    }
                }
                // Only the visualizations made in this project (the host keeps the record).
                workspace.readFile("visualizations/.projects.json", new BridgeClient.Callback<BridgeClient.FileContent>() {
                    @Override
                    public void onSuccess(BridgeClient.FileContent f) {
                        org.json.JSONObject map = null;
                        try {
                            map = new org.json.JSONObject(f.text);
                        } catch (Exception ignored) {
                        }
                        showViz(all, map);
                    }

                    @Override
                    public void onError(String message) {
                        showViz(all, null);
                    }
                });
            }

            @Override
            public void onError(String message) {
                /* no visualizations folder yet */
            }
        });
        workspace.listSharedPdfs(new BridgeClient.Callback<List<BridgeClient.LibraryEntry>>() {
            @Override
            public void onSuccess(List<BridgeClient.LibraryEntry> value) {
                sharedPdfs.clear();
                if (value != null) sharedPdfs.addAll(value);
                rebuildTree();
                restoreScrollSoon();
            }

            @Override
            public void onError(String message) {
                /* keep previous shared list */
            }
        });
    }

    private void showViz(List<BridgeClient.LibraryEntry> all, org.json.JSONObject owners) {
        vizFiles.clear();
        for (BridgeClient.LibraryEntry e : all) {
            String stem = e.name.substring(0, e.name.length() - 4);
            String owner = owners == null ? "" : owners.optString(stem, "");
            if (!owner.isEmpty() && normalizeKey(owner).equals(normalizeKey(projectPath))) vizFiles.add(e);
        }
        rebuildTree();
    }

    private static String displayName(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return "Workspace";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Match chat / app chrome colors. */
    void applyTheme(int surface, int onSurface, int onVariant, int primary,
                    int primaryContainer, int onPrimaryContainer, int outline) {
        colorHeaderBg = surface;
        colorOnSurface = onSurface;
        colorOnVariant = onVariant;
        colorPrimary = primary;
        colorPrimaryContainer = primaryContainer;
        colorOnPrimaryContainer = onPrimaryContainer;
        colorDivider = (outline & 0x00FFFFFF) | 0x55000000;
        colorOutline = outline;
        colorRowPress = (primary & 0x00FFFFFF) | 0x22000000;

        header.setBackgroundColor(0x00000000);
        titleView.setTextColor(colorOnSurface);
        statusLabel.setTextColor(colorOnVariant);
        headerDivider.setBackgroundColor(colorDivider);
        tint(rootIcon, colorPrimary);
        styleNewButton();
        buildNewMenu();
        rebuildTree();
    }

    /**
     * Flat filled circle like the tool bar's selected icons (the old raised FAB with an
     * off-centre glyph looked out of place), with a hover/press ripple.
     */
    private void styleNewButton() {
        if (newButton == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorPrimaryContainer);
        bg.setCornerRadius(dp(999));
        newButton.setBackground(ripple(bg, colorOnPrimaryContainer));
        SketchStyle.outline(newButton, 2);
        tint(newIcon, colorOnPrimaryContainer);
        newLabel.setTextColor(colorOnPrimaryContainer);
    }

    /** The options, closest to the button first; hidden until the button opens them. */
    private void buildNewMenu() {
        if (newMenu != null) removeView(newMenu);
        if (newScrim != null) removeView(newScrim);
        newOptions.clear();
        newMenuOpen = false;
        if (newIcon != null) newIcon.setRotation(0f);

        newScrim = new View(getContext());
        newScrim.setClickable(true);
        newScrim.setOnClickListener(v -> setNewMenuOpen(false));
        newScrim.setVisibility(GONE);
        addView(newScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        newMenu = new LinearLayout(getContext());
        newMenu.setOrientation(LinearLayout.VERTICAL);
        newMenu.setGravity(Gravity.END);
        newMenu.setClipChildren(false);
        newMenu.setClipToPadding(false);
        newMenu.setPadding(0, 0, dp(8), dp(8));
        View pdf = addNewOption(R.drawable.ic_pdf_add, "PDF", () -> {
            if (listener != null) listener.onCreatePdfDocument();
        });
        pdf.setOnLongClickListener(v -> {
            showToolFavoriteMenu(v, FavoritesStore.ACTION_NEW_PDF);
            return true;
        });
        addNewOption(R.drawable.ic_folder_add, "Folder", () -> promptNewFolder(projectDir()));
        addNewOption(R.drawable.ic_upload, "Upload", () -> {
            if (listener != null) listener.onUploadInto(projectDir());
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        newMenu.setVisibility(GONE);
        addView(newMenu, lp);
    }

    private View addNewOption(int icon, String label, Runnable action) {
        LinearLayout opt = new LinearLayout(getContext());
        opt.setOrientation(LinearLayout.HORIZONTAL);
        opt.setGravity(Gravity.CENTER_VERTICAL);
        opt.setPadding(dp(12), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorHeaderBg | 0xFF000000);
        bg.setStroke(dp(1), colorOutline);
        bg.setCornerRadius(dp(999));
        opt.setBackground(ripple(bg, colorOnSurface));
        SketchStyle.elevate(opt, 4);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        tint(iv, colorPrimary);
        opt.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(colorOnSurface);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(8);
        opt.addView(t, tlp);
        opt.setClickable(true);
        opt.setOnClickListener(v -> {
            setNewMenuOpen(false);
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        newMenu.addView(opt, lp);
        newOptions.add(opt);
        return opt;
    }

    /** Fan the options out beneath the button (its + turns into ×), or fold them away. */
    private void setNewMenuOpen(boolean open) {
        if (newMenu == null) return;
        newMenuOpen = open;
        if (newIcon != null) {
            newIcon.animate().rotation(open ? 45f : 0f).setDuration(Motion.CHANGE_MS)
                    .setInterpolator(Motion.LAND).start();
        }
        if (open) {
            // Just under the header, right-aligned with the button.
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) newMenu.getLayoutParams();
            lp.topMargin = header.getBottom() - dp(2);
            newMenu.setLayoutParams(lp);
            newScrim.setVisibility(VISIBLE);
            newMenu.setVisibility(VISIBLE);
            newMenu.bringToFront();
        } else {
            newScrim.setVisibility(GONE);
        }
        for (int i = 0; i < newOptions.size(); i++) {
            View o = newOptions.get(i);
            o.animate().cancel();
            if (open) {
                o.setAlpha(0f);
                o.setTranslationY(-dp(10));
                o.setScaleX(0.86f);
                o.setScaleY(0.86f);
                // Nearest the button moves first.
                o.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setStartDelay(i * 30L).setDuration(Motion.ENTER_MS)
                        .setInterpolator(Motion.LAND).start();
            } else {
                final boolean last = i == newOptions.size() - 1;
                o.animate().alpha(0f).translationY(-dp(8)).setStartDelay(0)
                        .setDuration(Motion.EXIT_MS - 40).setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                        .withEndAction(() -> {
                            if (last && !newMenuOpen) newMenu.setVisibility(GONE);
                        }).start();
            }
        }
    }

    private android.graphics.drawable.RippleDrawable ripple(Drawable content, int onColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((onColor & 0x00FFFFFF) | 0x29000000),
                content, mask);
    }

    // ---- File operations: new folder, rename, cut / copy / paste, delete, drag-move ----

    private String projectDir() {
        return projectPath == null || projectPath.isEmpty() ? "." : projectPath;
    }

    private static String parentOf(String path) {
        String p = normalizeKey(path);
        int slash = p.lastIndexOf('/');
        return slash > 0 ? p.substring(0, slash) : ".";
    }

    private static String leafOf(String path) {
        String p = normalizeKey(path);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private void promptName(String title, String initial, String okLabel,
                            java.util.function.Consumer<String> onOk) {
        final android.widget.EditText field = new android.widget.EditText(getContext());
        field.setSingleLine(true);
        field.setText(initial == null ? "" : initial);
        if (initial != null) {
            // Select the name without its extension, like a desktop file manager.
            int dot = initial.lastIndexOf('.');
            field.setSelection(0, dot > 0 ? dot : initial.length());
        }
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(getContext());
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(field);
        M3Dialog dlg = new M3Dialog.Builder(getContext())
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(okLabel, (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) onOk.accept(name);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d -> {
            field.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, 0);
        });
        dlg.show();
    }

    private void promptNewFolder(String parentDir) {
        promptName("New folder", "", "Create", name -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("parent", parentDir);
                body.put("name", name);
                runFsOp("/fs/mkdir", body, "New folder", path -> {
                    // Open the parent so the new folder is visible.
                    Node parent = nodesByPath.get(normalizeKey(parentDir));
                    if (parent != null && !parent.expanded && parent != root) toggleExpand(parent);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void promptRename(String path) {
        final String leaf = leafOf(path);
        promptName("Rename", leaf, "Rename", name -> {
            if (name.equals(leaf)) return;
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("path", path);
                body.put("name", name);
                runFsOp("/fs/rename", body, "Rename", to -> {
                    if (listener != null) listener.onPathChanged(path, to);
                    renameFavorite(path, to);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void confirmDelete(String path, boolean isDir) {
        new M3Dialog.Builder(getContext())
                .setMessage("Delete “" + leafOf(path) + "”?")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        org.json.JSONObject body = new org.json.JSONObject();
                        body.put("path", path);
                        runFsOp("/fs/delete", body, "Delete", to -> {
                            if (listener != null) listener.onPathChanged(path, null);
                            FavoritesStore.get(getContext()).setFavorite(FavoritesStore.fileId(path), false);
                            if (normalizeKey(path).equals(normalizeKey(clipPath))) clipPath = null;
                        });
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pasteInto(String dir) {
        final String from = clipPath;
        if (from == null) return;
        final boolean cut = clipCut;
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("from", from);
            body.put("toDir", dir);
            runFsOp(cut ? "/fs/move" : "/fs/copy", body, "Paste", to -> {
                if (cut) {
                    clipPath = null;
                    if (listener != null) listener.onPathChanged(from, to);
                    renameFavorite(from, to);
                }
                Node target = nodesByPath.get(normalizeKey(dir));
                if (target != null && !target.expanded && target != root) toggleExpand(target);
            });
        } catch (Exception ignored) {
        }
    }

    private void moveInto(String from, String dir) {
        if (from == null || dir == null) return;
        String f = normalizeKey(from);
        String d = normalizeKey(dir);
        if (d.equals(parentOf(f)) || d.equals(f) || d.startsWith(f + "/")) return;
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("from", from);
            body.put("toDir", dir);
            runFsOp("/fs/move", body, "Move", to -> {
                if (listener != null) listener.onPathChanged(from, to);
                renameFavorite(from, to);
            });
        } catch (Exception ignored) {
        }
    }

    private void renameFavorite(String from, String to) {
        if (to == null) return;
        FavoritesStore fav = FavoritesStore.get(getContext());
        String oldId = FavoritesStore.fileId(from);
        if (fav.isFavorite(oldId)) fav.replaceId(oldId, FavoritesStore.fileId(to));
    }

    private void runFsOp(String route, org.json.JSONObject body, String what,
                         java.util.function.Consumer<String> onOk) {
        if (workspace == null) return;
        workspace.fsOp(route, body, new BridgeClient.Callback<String>() {
            @Override
            public void onSuccess(String value) {
                if (onOk != null) onOk.accept(value);
                reloadOpenFolders();
                // The shared PDFs and visualizations are lists of their own, and All Projects another view.
                reloadShared();
                if (listener != null) listener.onFilesMutated();
            }

            @Override
            public void onError(String message) {
                if (listener != null) listener.onExplorerMessage(what + " failed: " + message);
                else statusLabel.setText(what + " failed: " + message);
            }
        });
    }

    /** Folder rows (and the project header) take dropped rows: the item moves there. */
    private void makeDropTarget(View target, String dir, GradientDrawable bg) {
        target.setOnDragListener((v, e) -> {
            Object local = e.getLocalState();
            if (!(local instanceof ExplorerDrag)) return false;
            String from = ((ExplorerDrag) local).path;
            switch (e.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    if (bg != null) {
                        bg.setColor(colorRowPress);
                        v.setBackground(bg);
                    }
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    if (bg != null) {
                        bg.setColor(0x00000000);
                        v.setBackground(bg);
                    }
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    if (bg != null) {
                        bg.setColor(0x00000000);
                        v.setBackground(bg);
                    }
                    moveInto(from, dir);
                    return true;
                default:
                    return true;
            }
        });
    }

    /** Local drag state for an explorer row (canvas drops still read the ClipData path). */
    static final class ExplorerDrag {
        final String path;

        ExplorerDrag(String path) {
            this.path = path;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    private void startRowDrag(View row, String path) {
        ClipData data = ClipData.newPlainText("workspace", path);
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(row);
        row.startDragAndDrop(data, shadow, new ExplorerDrag(path), 0);
    }

    void setTopInset(int px) {
        header.setPadding(dp(8), px + dp(4), dp(2), dp(8));
    }

    void persistScroll() {
        if (scroll != null) {
            savedScrollY = scroll.getScrollY();
            persistPrefs();
        }
    }

    /** Rebuild tree (e.g. after favorite toggle). */
    void refreshFavorites() {
        rebuildTree();
        restoreScrollSoon();
    }

    @Override
    protected void onDetachedFromWindow() {
        persistScroll();
        super.onDetachedFromWindow();
    }

    private void loadChildren(Node dir) {
        if (dir.loading) return;
        dir.loading = true;
        pendingLoads++;
        rebuildTree();
        workspace.listFiles(dir.path, new BridgeClient.Callback<BridgeClient.DirListing>() {
            @Override
            public void onSuccess(BridgeClient.DirListing value) {
                dir.loading = false;
                dir.loaded = true;
                pendingLoads = Math.max(0, pendingLoads - 1);
                dir.children.clear();
                List<BridgeClient.FileEntry> dirs = new ArrayList<>();
                List<BridgeClient.FileEntry> files = new ArrayList<>();
                for (BridgeClient.FileEntry e : value.items) {
                    if ("dir".equals(e.type) && shouldHideDir(e.name)) continue;
                    if ("dir".equals(e.type)) dirs.add(e);
                    else files.add(e);
                }
                for (BridgeClient.FileEntry e : dirs) {
                    Node child = new Node(e.path, e.name, "dir", dir.depth + 1);
                    child.expanded = EXPANDED.contains(normalizeKey(e.path));
                    nodesByPath.put(normalizeKey(e.path), child);
                    dir.children.add(child);
                }
                for (BridgeClient.FileEntry e : files) {
                    Node child = new Node(e.path, e.name, "file", dir.depth + 1);
                    nodesByPath.put(normalizeKey(e.path), child);
                    dir.children.add(child);
                }
                for (Node child : new ArrayList<>(dir.children)) {
                    if (child.isDir() && child.expanded && !child.loaded) {
                        loadChildren(child);
                    }
                }
                rebuildTree();
                restoreScrollSoon();
            }

            @Override
            public void onError(String message) {
                dir.loading = false;
                dir.loaded = true;
                pendingLoads = Math.max(0, pendingLoads - 1);
                statusLabel.setVisibility(View.VISIBLE);
                statusLabel.setText("Error: " + message);
                rebuildTree();
                restoreScrollSoon();
            }
        });
    }

    private static boolean shouldHideDir(String name) {
        if (name == null) return true;
        String n = name.toLowerCase(Locale.US);
        return n.equals("node_modules") || n.equals(".venv") || n.equals("venv") || n.equals(".trash")
                || n.equals("__pycache__") || n.equals(".git");
    }

    private static String normalizeKey(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return ".";
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "." : p;
    }

    private void toggleExpand(Node dir) {
        if (!dir.isDir()) return;
        dir.expanded = !dir.expanded;
        String key = normalizeKey(dir.path);
        if (dir.expanded) EXPANDED.add(key);
        else EXPANDED.remove(key);
        persistScroll();
        persistPrefs();
        if (dir.expanded && !dir.loaded) {
            loadChildren(dir);
        } else {
            rebuildTree();
            restoreScrollSoon();
        }
    }

    private void rebuildTree() {
        treeList.removeAllViews();
        addSectionHeader("Shared");
        if (sharedPdfs.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No shared PDFs");
            empty.setTextColor(colorOnVariant);
            empty.setTextSize(11);
            empty.setPadding(dp(8), dp(2), dp(6), dp(8));
            treeList.addView(empty, matchWrap());
        } else {
            for (BridgeClient.LibraryEntry e : sharedPdfs) {
                addSharedRow(e);
            }
        }
        TextView projectHeader = addSectionHeader(projectName != null ? projectName : "Project");
        GradientDrawable phBg = new GradientDrawable();
        phBg.setCornerRadius(dp(6));
        phBg.setColor(0x00000000);
        projectHeader.setBackground(phBg);
        makeDropTarget(projectHeader, projectDir(), phBg);
        if (root == null) return;
        appendVisible(root, true);
        if (!vizFiles.isEmpty()) {
            addSectionHeader("Visualizations");
            for (BridgeClient.LibraryEntry e : vizFiles) addSharedRow(e);
        }
    }

    private TextView addSectionHeader(String label) {
        TextView h = new TextView(getContext());
        h.setText(label);
        // Section label as everywhere else: primary, medium, sentence case.
        h.setTextColor(colorPrimary);
        h.setTextSize(13);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(dp(8), dp(12), dp(6), dp(4));
        treeList.addView(h, matchWrap());
        return h;
    }

    private void addSharedRow(BridgeClient.LibraryEntry e) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(6), dp(4));
        row.setMinimumHeight(dp(32));

        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setCornerRadius(dp(6));
        rowBg.setColor(0x00000000);
        row.setBackground(rowBg);

        ImageView icon = new ImageView(ctx);
        boolean viz = e.name != null && e.name.toLowerCase(Locale.US).endsWith(".viz");
        icon.setImageResource(viz ? R.drawable.ic_viz : R.drawable.ic_pdf);
        tint(icon, viz ? 0xFF80DEEA : colorPrimary);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconLp.rightMargin = dp(6);
        row.addView(icon, iconLp);

        TextView name = new TextView(ctx);
        String label = e.name;
        if (viz && label.length() > 4) label = label.substring(0, label.length() - 4).replace('_', ' ');
        if (label != null && label.toLowerCase(Locale.US).endsWith(".pdf") && label.length() > 4) {
            label = label.substring(0, label.length() - 4);
        }
        name.setText(label);
        name.setTextColor(colorOnSurface);
        name.setTextSize(12);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String path = e.path;
        row.setOnClickListener(v -> openFile(path));
        row.setOnLongClickListener(v -> {
            showItemMenu(v, path, e.name, false);
            return true;
        });
        row.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                rowBg.setColor(colorRowPress);
                row.setBackground(rowBg);
            } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                    || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                rowBg.setColor(0x00000000);
                row.setBackground(rowBg);
            }
            return false;
        });
        treeList.addView(row, matchWrap());
    }

    private void appendVisible(Node node, boolean isRoot) {
        if (!isRoot) addRow(node);
        if (node.isDir() && node.expanded) {
            if (node.loading && node.children.isEmpty()) {
                addLoadingRow(node.depth + (isRoot ? 0 : 1));
            }
            for (Node child : node.children) {
                appendVisible(child, false);
            }
        }
    }

    private void addLoadingRow(int depth) {
        TextView t = new TextView(getContext());
        t.setText("Loading…");
        t.setTextColor(colorOnVariant);
        t.setTextSize(10);
        t.setPadding(dp(8) + depth * dp(12), dp(4), dp(6), dp(4));
        treeList.addView(t, matchWrap());
    }

    private void addRow(Node node) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Depth indent only — no extra left gutter / expand chevrons.
        int padL = dp(4) + Math.max(0, node.depth - 1) * dp(12);
        row.setPadding(padL, dp(4), dp(6), dp(4));
        row.setMinimumHeight(dp(32));

        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setCornerRadius(dp(6));
        rowBg.setColor(0x00000000);
        row.setBackground(rowBg);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconFor(node));
        tint(icon, colorFor(node));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconLp.rightMargin = dp(6);
        row.addView(icon, iconLp);

        TextView name = new TextView(ctx);
        name.setText(node.name);
        name.setTextColor(colorOnSurface);
        name.setTextSize(12);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nameLp);

        row.setOnClickListener(v -> {
            if (node.isDir()) {
                toggleExpand(node);
            } else {
                openFile(node.path);
            }
        });

        // Long-press opens the menu; keep the finger down and move to drag the item
        // onto a folder (moves it) or onto the canvas (places it).
        final float[] down = new float[2];
        final boolean[] armed = new boolean[1];
        final M3Menu[] menu = new M3Menu[1];
        row.setOnLongClickListener(v -> {
            armed[0] = true;
            menu[0] = showItemMenu(v, node.path, node.name, node.isDir());
            return true;
        });

        final int slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
        row.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    armed[0] = false;
                    rowBg.setColor(colorRowPress);
                    row.setBackground(rowBg);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (armed[0] && Math.hypot(ev.getRawX() - down[0], ev.getRawY() - down[1]) > slop * 2) {
                        armed[0] = false;
                        if (menu[0] != null) menu[0].dismiss();
                        rowBg.setColor(0x00000000);
                        row.setBackground(rowBg);
                        startRowDrag(row, node.path);
                        return true;
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    armed[0] = false;
                    rowBg.setColor(0x00000000);
                    row.setBackground(rowBg);
                    break;
                default:
                    break;
            }
            return false;
        });
        if (node.isDir()) makeDropTarget(row, node.path, rowBg);

        treeList.addView(row, matchWrap());
    }

    private void showToolFavoriteMenu(View anchor, String favId) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        FavoritesStore fav = FavoritesStore.get(getContext());
        boolean on = fav.isFavorite(favId);
        new M3Menu(getContext())
                .add(R.drawable.ic_star, on ? "Remove from favorites" : "Add to favorites", () -> fav.toggle(favId))
                .showUnder(anchor);
    }

    private M3Menu showItemMenu(View anchor, String path, String label, boolean isDir) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        FavoritesStore fav = FavoritesStore.get(getContext());
        String id = FavoritesStore.fileId(path);
        boolean on = fav.isFavorite(id);
        final String pasteDir = isDir ? path : parentOf(path);
        M3Menu m = new M3Menu(getContext());
        if (isDir) {
            m.add(R.drawable.ic_folder_add, "New folder here", () -> promptNewFolder(path));
            m.add(R.drawable.ic_upload, "Upload file here\u2026", () -> {
                if (listener != null) listener.onUploadInto(path);
            });
        }
        if (clipPath != null) {
            m.add(R.drawable.ic_paste, (clipCut ? "Move \u201c" : "Paste \u201c") + leafOf(clipPath) + "\u201d here",
                    () -> pasteInto(pasteDir));
        }
        m.divider();
        m.add(R.drawable.ic_edit, "Rename", () -> promptRename(path));
        m.add(R.drawable.ic_cut, "Cut", () -> clipFor(path, true));
        m.add(R.drawable.ic_copy, "Copy", () -> clipFor(path, false));
        if (!isDir) {
            m.add(R.drawable.ic_download, "Download to tablet", () -> {
                if (listener != null) listener.onDownloadFile(path);
            });
            m.add(R.drawable.ic_open_in_new, "Drag to canvas", () -> startRowDrag(anchor, path));
        }
        m.add(R.drawable.ic_star, on ? "Remove from favorites" : "Add to favorites", () -> {
            fav.toggle(id);
            rebuildTree();
        });
        m.divider();
        m.addDestructive(R.drawable.ic_delete, "Delete", () -> confirmDelete(path, isDir));
        m.showUnder(anchor);
        return m;
    }

    /** Cut or Copy: remember the item; a long-press on a folder pastes it there. */
    private void clipFor(String path, boolean cut) {
        clipPath = path;
        clipCut = cut;
        if (listener != null) {
            listener.onExplorerMessage((cut ? "Cut " : "Copied ") + "\u201c" + leafOf(path)
                    + "\u201d \u2014 long-press a folder to paste");
        }
    }

    private void openFile(String path) {
        if (path == null || listener == null) return;
        persistScroll();
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".pdf")) listener.onOpenPdfDocument(path);
        else if (lower.endsWith(".viz")) listener.onOpenViz(path);
        else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif")) listener.onAddImageToCanvas(path);
        else listener.onOpenFile(path);
    }

    private int iconFor(Node node) {
        if (node.isDir()) {
            return node.expanded ? R.drawable.ic_folder_open : R.drawable.ic_folder;
        }
        String n = node.name.toLowerCase(Locale.US);
        if (n.endsWith(".viz")) return R.drawable.ic_viz;
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) {
            return R.drawable.ic_image;
        }
        if (n.endsWith(".pdf")) return R.drawable.ic_pdf;
        if (n.endsWith(".sh")) return R.drawable.ic_script;
        if (n.endsWith(".py") || n.endsWith(".js") || n.endsWith(".mjs")
                || n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".c") || n.endsWith(".cpp")
                || n.endsWith(".h") || n.endsWith(".ts") || n.endsWith(".tsx")
                || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".md")
                || n.endsWith(".txt") || n.endsWith(".css") || n.endsWith(".html")) {
            return R.drawable.ic_code;
        }
        return R.drawable.ic_file;
    }

    private int colorFor(Node node) {
        if (node.isDir()) return 0xFFE8C468; // folders — warm gold
        String n = node.name.toLowerCase(Locale.US);
        if (n.endsWith(".viz")) return 0xFF80DEEA; // viz — cyan
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) {
            return 0xFF7EC8FF; // images — sky
        }
        if (n.endsWith(".pdf")) return 0xFFFF8A80; // pdf — coral
        if (n.endsWith(".sh")) return 0xFF3DDC84; // shell — green
        if (n.endsWith(".py")) return 0xFFFFD54F; // python — amber
        if (n.endsWith(".java") || n.endsWith(".kt")) return 0xFFFFAB91; // jvm — peach
        if (n.endsWith(".ts") || n.endsWith(".tsx") || n.endsWith(".js") || n.endsWith(".mjs")) {
            return 0xFF80CBC4; // js/ts — teal
        }
        if (n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h") || n.endsWith(".hpp")) {
            return 0xFF90CAF9; // c/c++ — blue
        }
        if (n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".yml") || n.endsWith(".yaml")
                || n.endsWith(".toml") || n.endsWith(".css") || n.endsWith(".html")) {
            return 0xFFCE93D8; // config/markup — violet
        }
        if (n.endsWith(".md") || n.endsWith(".txt") || n.endsWith(".rst")) {
            return 0xFFB0BEC5; // docs — blue-grey
        }
        return 0xFFB0A8A0; // generic file
    }

    private boolean anyLoading(Node n) {
        if (n == null) return false;
        if (n.loading) return true;
        if (n.isDir() && n.expanded) {
            for (Node c : n.children) {
                if (anyLoading(c)) return true;
            }
        }
        return false;
    }

    void onShown() {
        restoreScrollSoon();
    }

    /**
     * Re-read every folder that is currently open.
     *
     * <p>The tree was loaded once and never revisited, so a file created or deleted
     * outside the app — by the agent, a script, or on the Mac — simply never showed
     * up. Expansion state and scroll position are kept: loadChildren rebuilds a
     * folder's children in place and re-expands from EXPANDED.
     */
    void reloadOpenFolders() {
        if (workspace == null || root == null) return;
        persistScroll();
        reloadExpanded(root);
        restoreScrollSoon();
    }

    private void reloadExpanded(Node dir) {
        if (dir == null || !dir.isDir()) return;
        if (!dir.expanded && dir != root) return;
        // Snapshot first: loadChildren replaces the list it walks.
        List<Node> subdirs = new ArrayList<>();
        for (Node c : dir.children) {
            if (c.isDir() && c.expanded && c.loaded) subdirs.add(c);
        }
        if (dir.loaded || dir == root) loadChildren(dir);
        for (Node c : subdirs) reloadExpanded(c);
    }

    private void restoreScrollSoon() {
        if (pendingLoads > 0 || anyLoading(root)) return;
        final int y = savedScrollY;
        scroll.post(() -> {
            scroll.scrollTo(0, y);
            scroll.post(() -> scroll.scrollTo(0, Math.min(y, maxScroll())));
        });
    }

    private int maxScroll() {
        View child = scroll.getChildAt(0);
        if (child == null) return 0;
        return Math.max(0, child.getHeight() - scroll.getHeight());
    }

    private void tint(ImageView iv, int color) {
        if (iv == null) return;
        // Clear XML android:tint, then apply a strong SRC_IN so type colors actually show.
        Drawable d = iv.getDrawable();
        if (d != null) {
            d = d.mutate();
            d.setTintList(null);
            d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            iv.setImageDrawable(d);
        }
        iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
