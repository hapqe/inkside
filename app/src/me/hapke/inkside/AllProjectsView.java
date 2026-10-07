package me.hapke.inkside;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen home library (Notes-style grid): folders, projects, shared PDFs.
 * No side panel — the whole screen is the library until a project is opened.
 */
final class AllProjectsView extends FrameLayout {
    interface Listener {
        void onOpenProject(String path, String name);

        void onOpenSharedPdf(String path);

        default void onLibraryMessage(String msg) {}

        /** The ⋮ in the title row: the app's menu (settings, learning, …). */
        default void onShowMenu(View anchor) {}
    }

    /**
     * Tiles still need to be told apart at a glance, but they are not allowed their own
     * palette: each is the app's accent rotated round the hue wheel, so the library
     * reads as the same product as the canvas in every theme.
     */
    private static final int TILE_HUES = 8;

    private final Workspace workspace;
    private final Listener listener;
    private final LinearLayout rootCol;
    private final ImageView upButton;
    private final ImageView menuButton;
    private final TextView titleView;
    private final EditText searchInput;
    private final TextView filterAll;
    private final TextView filterFav;
    private final TextView statusView;
    private final GridLayout grid;
    private final ScrollView scroll;
    private final LinearLayout fabCol;

    private String browsePath = ".";
    private String parentPath;
    private final List<BridgeClient.LibraryEntry> entries = new ArrayList<>();
    private final List<BridgeClient.LibraryEntry> folderTargets = new ArrayList<>();
    private String searchQuery = "";
    private boolean favoritesOnly;

    private int colorSurface = 0xFF12141A;
    private int colorOnSurface = 0xFFECEDF2;
    private int colorOnVariant = 0xFF9BA0AE;
    private int colorPrimary = 0xFFBAC3FF;
    private int colorPrimaryContainer = 0xFF2B3153;
    private int colorOnPrimaryContainer = 0xFFE8EAF6;
    private int colorSurfaceHigh = 0xFF1C1B22;
    private int colorOutline = 0xFF3A3B47;
    /** True when the theme is a light one — decides shadow strength and tile text. */
    private boolean lightTheme;

    AllProjectsView(Context ctx, Workspace workspace, Listener listener) {
        super(ctx);
        this.workspace = workspace;
        this.listener = listener;
        setClickable(true);
        setBackgroundColor(colorSurface);

        rootCol = new LinearLayout(ctx);
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setPadding(dp(32), dp(24), dp(32), dp(24));

        // —— Top: back (when nested) + title ——
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        upButton = new ImageView(ctx);
        upButton.setImageResource(R.drawable.ic_chevron_left);
        upButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        upButton.setContentDescription("Back");
        upButton.setOnClickListener(v -> navigateUp());
        upButton.setVisibility(INVISIBLE);
        top.addView(upButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        titleView = new TextView(ctx);
        titleView.setText("All Projects");
        titleView.setTextSize(28);
        HeadlineFont.apply(titleView);
        titleView.setTextColor(colorOnSurface);
        titleView.setPadding(dp(4), 0, 0, 0);
        top.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        menuButton = new ImageView(ctx);
        menuButton.setImageResource(R.drawable.ic_more);
        menuButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        menuButton.setContentDescription("More");
        menuButton.setOnClickListener(v -> {
            if (listener != null) listener.onShowMenu(v);
        });
        top.addView(menuButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        rootCol.addView(top, matchWrap());

        // —— Search ——
        searchInput = new EditText(ctx);
        searchInput.setHint("Search your projects");
        searchInput.setSingleLine(true);
        searchInput.setTextSize(15);
        searchInput.setBackground(pill(colorSurfaceHigh, colorOutline));
        searchInput.setPadding(dp(18), dp(14), dp(18), dp(14));
        searchInput.setTextColor(colorOnSurface);
        searchInput.setHintTextColor(colorOnVariant);
        LinearLayout.LayoutParams searchLp = matchWrap();
        searchLp.topMargin = dp(16);
        rootCol.addView(searchInput, searchLp);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                rebuildGrid();
            }
        });

        // —— Filters ——
        LinearLayout filters = new LinearLayout(ctx);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams filtersLp = matchWrap();
        filtersLp.topMargin = dp(14);
        filtersLp.bottomMargin = dp(8);

        filterAll = filterChip(ctx, "All", true);
        filterAll.setOnClickListener(v -> {
            favoritesOnly = false;
            styleFilters();
            rebuildGrid();
        });
        filters.addView(filterAll, chipLp());

        filterFav = filterChip(ctx, "Favorites", false);
        filterFav.setOnClickListener(v -> {
            favoritesOnly = true;
            styleFilters();
            rebuildGrid();
        });
        filters.addView(filterFav, chipLp());
        rootCol.addView(filters, filtersLp);

        statusView = new TextView(ctx);
        statusView.setTextSize(13);
        statusView.setTextColor(colorOnVariant);
        statusView.setVisibility(GONE);
        rootCol.addView(statusView, matchWrap());

        scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        grid = new GridLayout(ctx);
        grid.setColumnCount(4);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootCol.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(rootCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // —— "New" button (bottom-right) with its speed dial ——
        fabCol = new LinearLayout(ctx);
        fabCol.setOrientation(LinearLayout.VERTICAL);
        fabCol.setGravity(Gravity.END);
        buildFab();
        FrameLayout.LayoutParams fabFrame = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fabFrame.gravity = Gravity.BOTTOM | Gravity.END;
        fabFrame.setMargins(0, 0, dp(24), dp(24));
        addView(fabCol, fabFrame);

        // Drag a card onto a folder (or the back arrow) to move it there.
        setOnDragListener((v, ev) -> onLibraryDrag(ev));
        makeDropTarget(upButton, () -> parentPath);
    }

    void applyTheme(int surface, int surfaceHigh, int onSurface, int onVariant,
                    int primary, int primaryContainer, int onPrimaryContainer, int outline) {
        // The library is part of the app, not a guest in it: every colour here comes
        // from the same theme the canvas uses. It used to throw these arguments away
        // and paint itself light whatever the rest of the app was set to.
        colorSurface = opaque(surface, colorSurface);
        colorSurfaceHigh = opaque(surfaceHigh, colorSurfaceHigh);
        colorOnSurface = opaque(onSurface, colorOnSurface);
        colorOnVariant = opaque(onVariant, colorOnVariant);
        colorPrimary = opaque(primary, colorPrimary);
        colorPrimaryContainer = opaque(primaryContainer, colorPrimaryContainer);
        colorOnPrimaryContainer = opaque(onPrimaryContainer, colorOnPrimaryContainer);
        colorOutline = opaque(outline, colorOutline);
        lightTheme = luma(colorSurface) > 140;

        setBackgroundColor(colorSurface);
        titleView.setTextColor(colorOnSurface);
        statusView.setTextColor(colorOnVariant);
        searchInput.setBackground(pill(colorSurfaceHigh, colorOutline));
        searchInput.setTextColor(colorOnSurface);
        searchInput.setHintTextColor(colorOnVariant);
        tint(upButton, colorOnSurface);
        tint(menuButton, colorOnSurface);
        styleFilters();
        if (!entries.isEmpty()) rebuildGrid();
        // Rebuild the New button in the new colours.
        buildFab();
        rebuildGrid();
    }

    // ---- New button: extended FAB with a speed dial ------------------------------------

    private boolean fabOpen;
    private LinearLayout fabMain;
    private ImageView fabIcon;
    private final List<View> fabOptions = new ArrayList<>();

    private void buildFab() {
        fabCol.removeAllViews();
        fabOptions.clear();
        fabOpen = false;
        addFabOption(R.drawable.ic_folder_open, "Project", this::promptNewProject);
        addFabOption(R.drawable.ic_folder, "Folder", this::promptNewFolder);
        addFabOption(R.drawable.ic_pdf_add, "Shared PDF", this::promptNewSharedPdf);

        fabMain = new LinearLayout(getContext());
        fabMain.setOrientation(LinearLayout.HORIZONTAL);
        fabMain.setGravity(Gravity.CENTER_VERTICAL);
        fabMain.setPadding(dp(18), dp(14), dp(22), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorPrimaryContainer);
        bg.setCornerRadius(dp(18));
        fabMain.setBackground(ripple(bg, colorOnPrimaryContainer));
        elevate(fabMain, 6);
        fabIcon = new ImageView(getContext());
        fabIcon.setImageResource(R.drawable.ic_add);
        tint(fabIcon, colorOnPrimaryContainer);
        fabMain.addView(fabIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = new TextView(getContext());
        label.setText("New");
        label.setTextSize(15);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(colorOnPrimaryContainer);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(10);
        fabMain.addView(label, llp);
        fabMain.setClickable(true);
        fabMain.setContentDescription("New project, folder or shared PDF");
        fabMain.setOnClickListener(v -> setFabOpen(!fabOpen));
        LinearLayout.LayoutParams mlp = fabLp();
        mlp.topMargin = dp(12);
        fabCol.addView(fabMain, mlp);
    }

    private void addFabOption(int icon, String label, Runnable action) {
        LinearLayout opt = new LinearLayout(getContext());
        opt.setOrientation(LinearLayout.HORIZONTAL);
        opt.setGravity(Gravity.CENTER_VERTICAL);
        opt.setPadding(dp(14), dp(10), dp(18), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorSurfaceHigh);
        bg.setStroke(dp(1), colorOutline);
        bg.setCornerRadius(dp(999));
        opt.setBackground(ripple(bg, colorOnSurface));
        elevate(opt, 4);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        tint(iv, colorPrimary);
        opt.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextSize(14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(colorOnSurface);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(10);
        opt.addView(t, tlp);
        opt.setClickable(true);
        opt.setOnClickListener(v -> {
            setFabOpen(false);
            action.run();
        });
        opt.setVisibility(GONE);
        LinearLayout.LayoutParams lp = fabLp();
        lp.topMargin = dp(10);
        fabCol.addView(opt, lp);
        fabOptions.add(opt);
    }

    /** Fan the options out above the button (and turn its + into ×), or fold them away. */
    private void setFabOpen(boolean open) {
        fabOpen = open;
        if (fabIcon != null) {
            fabIcon.animate().rotation(open ? 45f : 0f).setDuration(Motion.CHANGE_MS)
                    .setInterpolator(Motion.LAND).start();
        }
        for (int i = 0; i < fabOptions.size(); i++) {
            View o = fabOptions.get(i);
            // Nearest the button moves first.
            long delay = (fabOptions.size() - 1 - i) * 30L;
            o.animate().cancel();
            if (open) {
                o.setVisibility(VISIBLE);
                o.setAlpha(0f);
                o.setTranslationY(dp(12));
                o.setScaleX(0.86f);
                o.setScaleY(0.86f);
                o.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setStartDelay(delay).setDuration(Motion.ENTER_MS)
                        .setInterpolator(Motion.LAND).start();
            } else {
                o.animate().alpha(0f).translationY(dp(8)).setStartDelay(0)
                        .setDuration(Motion.EXIT_MS - 40).setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                        .withEndAction(() -> o.setVisibility(GONE)).start();
            }
        }
    }

    private android.graphics.drawable.RippleDrawable ripple(android.graphics.drawable.Drawable content, int on) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((on & 0x00FFFFFF) | 0x29000000), content, null);
    }

    // ---- Drag and drop between cards --------------------------------------------------

    /** What is being dragged: the entry, and whether the finger has really moved. */
    private static final class LibDrag {
        final BridgeClient.LibraryEntry entry;
        /** The card that was pressed: the menu opens at it. */
        View card;
        float startX = Float.NaN, startY = Float.NaN;
        boolean moved;

        LibDrag(BridgeClient.LibraryEntry entry) {
            this.entry = entry;
        }
    }

    private void startCardDrag(View card, BridgeClient.LibraryEntry e) {
        card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        android.content.ClipData data = android.content.ClipData.newPlainText("library", e.path);
        LibDrag drag = new LibDrag(e);
        drag.card = card;
        card.startDragAndDrop(data, new View.DragShadowBuilder(card), drag, 0);
    }

    /**
     * Root listener: notices whether the finger moved, and when a drag ends without a
     * drop and without moving, treats it as a plain long-press (the item menu).
     */
    private boolean onLibraryDrag(android.view.DragEvent ev) {
        if (!(ev.getLocalState() instanceof LibDrag)) return false;
        LibDrag d = (LibDrag) ev.getLocalState();
        switch (ev.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_STARTED:
                return true;
            case android.view.DragEvent.ACTION_DRAG_LOCATION:
                if (Float.isNaN(d.startX)) {
                    d.startX = ev.getX();
                    d.startY = ev.getY();
                } else if (Math.hypot(ev.getX() - d.startX, ev.getY() - d.startY) > dp(12)) {
                    d.moved = true;
                }
                return true;
            case android.view.DragEvent.ACTION_DROP:
                // Not a target: a drop here is "no drop", so a press released in place
                // ends unhandled and opens the menu below.
                return false;
            case android.view.DragEvent.ACTION_DRAG_ENDED:
                if (!ev.getResult() && !d.moved) post(() -> showItemMenu(d.entry, d.card));
                return true;
            default:
                return true;
        }
    }

    private interface PathSupplier { String get(); }

    /** A card (or the back arrow) that accepts dropped cards: they move into it. */
    private void makeDropTarget(View target, PathSupplier dest) {
        target.setOnDragListener((v, ev) -> {
            if (!(ev.getLocalState() instanceof LibDrag)) return false;
            LibDrag d = (LibDrag) ev.getLocalState();
            String to = dest.get();
            boolean ok = to != null && !to.equals(d.entry.path)
                    && !to.startsWith(d.entry.path + "/")
                    && !to.equals(parentOfPath(d.entry.path));
            switch (ev.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return ok;
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                    v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
                    return true;
                case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    d.moved = true;
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    return true;
                case android.view.DragEvent.ACTION_DROP:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    moveEntry(d.entry, to);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void moveEntry(BridgeClient.LibraryEntry e, String dest) {
        workspace.libraryMove(e.path, dest, new BridgeClient.Callback<String>() {
            @Override
            public void onSuccess(String value) {
                // A custom colour follows the item to its new place.
                String c = colorPrefs().getString(e.path, null);
                if (c != null && value != null) {
                    colorPrefs().edit().remove(e.path).putString(value, c).apply();
                }
                if (listener != null) listener.onLibraryMessage("Moved “" + stripPdf(e.name) + "”");
                reload();
            }

            @Override
            public void onError(String message) {
                status(message != null ? message : "Move failed");
            }
        });
    }

    // ---- Custom tile colours (per project / folder, on this tablet) -------------------

    private static final int[] TILE_CHOICES = {
            0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFFDC2626, 0xFFEA580C,
            0xFFCA8A04, 0xFF16A34A, 0xFF0D9488, 0xFF0891B2, 0xFF475569,
    };

    private android.content.SharedPreferences colorPrefs() {
        return getContext().getSharedPreferences("library_colors", Context.MODE_PRIVATE);
    }

    /** Pick a colour for a project or folder tile; "Automatic" goes back to the theme's. */
    private void promptTileColor(BridgeClient.LibraryEntry e) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        GridLayout swatches = new GridLayout(getContext());
        swatches.setColumnCount(5);
        final M3Dialog[] dlg = new M3Dialog[1];
        for (int c : TILE_CHOICES) {
            View sw = new View(getContext());
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            sw.setBackground(ripple(d, 0xFFFFFFFF));
            sw.setClickable(true);
            sw.setOnClickListener(v -> {
                colorPrefs().edit().putString(e.path, String.format("#%08X", c)).apply();
                rebuildGrid();
                if (dlg[0] != null) dlg[0].dismiss();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(40);
            lp.height = dp(40);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            swatches.addView(sw, lp);
        }
        box.addView(swatches);
        dlg[0] = new M3Dialog.Builder(getContext())
                .setTitle("Colour for “" + e.name + "”")
                .setView(box)
                .setNeutralButton("Automatic", (di, w) -> {
                    colorPrefs().edit().remove(e.path).apply();
                    rebuildGrid();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Mix {@code a} into {@code b} by {@code f}, for hairlines that follow the theme. */
    private static int blend(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 0xFF) * f + ((b >> 16) & 0xFF) * (1 - f));
        int g = Math.round(((a >> 8) & 0xFF) * f + ((b >> 8) & 0xFF) * (1 - f));
        int bl = Math.round((a & 0xFF) * f + (b & 0xFF) * (1 - f));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int luma(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    void setTopInset(int px) {
        rootCol.setPadding(dp(28), Math.max(dp(12), px) + dp(8), dp(28), dp(20));
    }

    void showAndReload() {
        setVisibility(VISIBLE);
        bringToFront();
        reload();
    }

    void reload() {
        status("Loading…");
        workspace.listLibrary(browsePath, new BridgeClient.Callback<BridgeClient.LibraryListing>() {
            @Override
            public void onSuccess(BridgeClient.LibraryListing value) {
                browsePath = value.path != null ? value.path : ".";
                parentPath = value.parent;
                entries.clear();
                entries.addAll(value.entries);
                boolean nested = parentPath != null && !parentPath.isEmpty();
                upButton.setVisibility(nested ? VISIBLE : INVISIBLE);
                titleView.setText(nested ? displayPath(browsePath) : "All Projects");
                clearStatus();
                rebuildGrid();
                prefetchFolderTargets();
            }

            @Override
            public void onError(String message) {
                status(message != null ? message : "Failed to load library");
            }
        });
    }

    private void prefetchFolderTargets() {
        folderTargets.clear();
        workspace.listLibrary(".", new BridgeClient.Callback<BridgeClient.LibraryListing>() {
            @Override
            public void onSuccess(BridgeClient.LibraryListing value) {
                for (BridgeClient.LibraryEntry e : value.entries) {
                    if ("folder".equals(e.kind)) folderTargets.add(e);
                }
                for (BridgeClient.LibraryEntry e : entries) {
                    if (!"folder".equals(e.kind)) continue;
                    boolean seen = false;
                    for (BridgeClient.LibraryEntry t : folderTargets) {
                        if (t.path.equals(e.path)) { seen = true; break; }
                    }
                    if (!seen) folderTargets.add(e);
                }
            }

            @Override
            public void onError(String message) { /* ignore */ }
        });
    }

    /** Android back: up one folder; false at the top, where back leaves the app. */
    boolean handleBack() {
        if (parentPath == null) return false;
        navigateUp();
        return true;
    }

    private void navigateUp() {
        if (parentPath == null) return;
        browsePath = parentPath;
        reload();
    }

    private void rebuildGrid() {
        grid.removeAllViews();
        int w = getWidth() > 0 ? getWidth() : dp(900);
        int cols = Math.max(2, Math.min(6, (w - dp(64)) / dp(168)));
        grid.setColumnCount(cols);

        FavoritesStore fav = FavoritesStore.get(getContext());
        List<BridgeClient.LibraryEntry> shown = new ArrayList<>();
        for (BridgeClient.LibraryEntry e : entries) {
            if (searchQuery.length() > 0
                    && !e.name.toLowerCase(Locale.US).contains(searchQuery)) {
                continue;
            }
            if (favoritesOnly && !fav.isFavorite(FavoritesStore.fileId(e.path))) {
                continue;
            }
            shown.add(e);
        }

        int i = 0;
        for (BridgeClient.LibraryEntry e : shown) {
            View card = buildCard(e);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(i % cols, 1f);
            lp.rowSpec = GridLayout.spec(i / cols);
            lp.setMargins(dp(12), dp(12), dp(12), dp(24));
            grid.addView(card, lp);
            i++;
        }
        if (shown.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(favoritesOnly
                    ? "No favorites yet — long-press an item to favorite it."
                    : "Create a project to get started.");
            empty.setTextColor(colorOnVariant);
            empty.setTextSize(15);
            empty.setPadding(dp(8), dp(32), dp(8), dp(8));
            grid.addView(empty);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && !entries.isEmpty()) rebuildGrid();
    }

    private View buildCard(BridgeClient.LibraryEntry e) {
        Context ctx = getContext();
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout tile = new FrameLayout(ctx);
        int tileSize = dp(132);
        boolean isPdf = "pdf".equals(e.kind);

        if (isPdf) {
            // Note thumbnail: white page with subtle lines
            GradientDrawable page = new GradientDrawable();
            page.setCornerRadius(dp(16));
            page.setColor(colorSurfaceHigh);
            page.setStroke(dp(1), colorOutline);
            // On the tile itself, not a backing child: in a FrameLayout elevation also
            // decides draw order, so an elevated backdrop covered the page preview.
            tile.setBackground(page);
            elevate(tile, 2);

            LinearLayout preview = new LinearLayout(ctx);
            preview.setOrientation(LinearLayout.VERTICAL);
            preview.setPadding(dp(14), dp(16), dp(14), dp(14));
            for (int i = 0; i < 4; i++) {
                View line = new View(ctx);
                GradientDrawable ld = new GradientDrawable();
                ld.setCornerRadius(dp(2));
                ld.setColor(blend(colorOnSurface, colorSurfaceHigh, 0.32f));
                line.setBackground(ld);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        i == 3 ? dp(48) : ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
                llp.bottomMargin = dp(8);
                preview.addView(line, llp);
            }
            tile.addView(preview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView pdfIcon = new ImageView(ctx);
            pdfIcon.setImageResource(R.drawable.ic_pdf);
            tint(pdfIcon, colorPrimary);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(28), dp(28));
            ilp.gravity = Gravity.BOTTOM | Gravity.START;
            ilp.setMargins(dp(12), 0, 0, dp(12));
            tile.addView(pdfIcon, ilp);
        } else {
            // Folder / project: solid color tile with initial
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(colorForPath(e.path));
            tile.setBackground(bg);
            elevate(tile, 3);

            if ("folder".equals(e.kind)) {
                // Folders look like folders; projects keep their initial and badge.
                ImageView folder = new ImageView(ctx);
                folder.setImageResource(R.drawable.ic_folder);
                tint(folder, lightTheme ? 0xE616181D : 0xF2FFFFFF);
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(60), dp(60));
                flp.gravity = Gravity.CENTER;
                tile.addView(folder, flp);
            } else {
                TextView letter = new TextView(ctx);
                letter.setText(initialOf(e.name));
                letter.setTextColor(lightTheme ? 0xFF16181D : 0xFFFFFFFF);
                letter.setTextSize(42);
                letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                letter.setGravity(Gravity.CENTER);
                tile.addView(letter, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            if ("project".equals(e.kind)) {
                TextView badge = new TextView(ctx);
                badge.setText("Project");
                badge.setTextSize(10);
                badge.setTextColor(lightTheme ? 0xCC16181D : 0xE6FFFFFF);
                badge.setPadding(dp(9), dp(4), dp(9), dp(4));
                GradientDrawable bbg = new GradientDrawable();
                bbg.setCornerRadius(dp(9));
                bbg.setColor(lightTheme ? 0x22000000 : 0x38000000);
                badge.setBackground(bbg);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                blp.bottomMargin = dp(10);
                tile.addView(badge, blp);
            }
        }

        FavoritesStore fav = FavoritesStore.get(ctx);
        if (fav.isFavorite(FavoritesStore.fileId(e.path))) {
            ImageView star = new ImageView(ctx);
            star.setImageResource(R.drawable.ic_star);
            star.setColorFilter(0xFFFFC107, android.graphics.PorterDuff.Mode.SRC_IN);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(18), dp(18));
            slp.gravity = Gravity.BOTTOM | Gravity.END;
            slp.setMargins(0, 0, dp(10), dp(8));
            tile.addView(star, slp);
        }

        wrap.addView(tile, new LinearLayout.LayoutParams(tileSize, tileSize));

        TextView name = new TextView(ctx);
        name.setText(stripPdf(e.name));
        name.setTextColor(colorOnSurface);
        name.setTextSize(14);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(2), dp(12), dp(2), 0);
        wrap.addView(name, matchWrap());

        wrap.setClickable(true);
        wrap.setFocusable(true);
        wrap.setOnClickListener(v -> onEntryClick(e));
        // Long-press lifts the card: drop it on a folder to move it there; let go
        // without moving and the item menu opens instead.
        wrap.setOnLongClickListener(v -> {
            startCardDrag(tile, e);
            return true;
        });
        if ("folder".equals(e.kind)) makeDropTarget(tile, () -> e.path);
        return wrap;
    }

    private void onEntryClick(BridgeClient.LibraryEntry e) {
        if ("folder".equals(e.kind)) {
            browsePath = e.path;
            reload();
        } else if ("project".equals(e.kind)) {
            if (listener != null) listener.onOpenProject(e.path, e.name);
        } else if ("pdf".equals(e.kind)) {
            if (listener != null) listener.onOpenSharedPdf(e.path);
        }
    }

    private void showCreateMenu() {
        CharSequence[] items = {"New project", "New folder", "New shared PDF"};
        new M3Dialog.Builder(getContext())
                .setItems(items, (d, which) -> {
                    if (which == 0) promptNewProject();
                    else if (which == 1) promptNewFolder();
                    else promptNewSharedPdf();
                })
                .show();
    }

    private void showItemMenu(BridgeClient.LibraryEntry e, View card) {
        FavoritesStore fav = FavoritesStore.get(getContext());
        String id = FavoritesStore.fileId(e.path);
        boolean on = fav.isFavorite(id);
        M3Menu menu = new M3Menu(getContext());
        menu.add(R.drawable.ic_star, on ? "Remove from favorites" : "Add to favorites", () -> {
            fav.toggle(id);
            rebuildGrid();
        });
        menu.add(R.drawable.ic_folder_open, "Move to\u2026", () -> promptMove(e));
        if (!"pdf".equals(e.kind)) menu.add(R.drawable.ic_palette, "Change colour\u2026", () -> promptTileColor(e));
        if (card != null && card.isAttachedToWindow()) {
            int[] loc = new int[2];
            card.getLocationOnScreen(loc);
            menu.showAt(card, loc[0] + card.getWidth() * 0.5f, loc[1] + card.getHeight() * 0.5f);
        } else {
            menu.showAt(this, getWidth() * 0.5f, getHeight() * 0.4f);
        }
    }

    private void promptNewFolder() {
        promptName("New folder", "Folder name", name ->
                workspace.libraryMkdir(browsePath, name, reloadCb("Folder created")));
    }

    private void promptNewProject() {
        promptName("New project", "Project name", name ->
                workspace.libraryCreateProject(browsePath, name,
                        new BridgeClient.Callback<BridgeClient.LibraryEntry>() {
                            @Override
                            public void onSuccess(BridgeClient.LibraryEntry value) {
                                reload();
                                if (listener != null) {
                                    listener.onOpenProject(value.path, value.name);
                                }
                            }

                            @Override
                            public void onError(String message) {
                                status(message != null ? message : "Could not create project");
                            }
                        }));
    }

    private void promptNewSharedPdf() {
        promptName("New shared PDF", "untitled.pdf", name -> {
            String n = name.trim();
            if (!n.toLowerCase(Locale.US).endsWith(".pdf")) n = n + ".pdf";
            if (n.contains("/")) n = n.substring(n.lastIndexOf('/') + 1);
            String rel = ".".equals(browsePath) ? n : browsePath + "/" + n;
            // Create via tiny blank write using host — ask listener through message,
            // or write empty marker; MainActivity handles real PDF creation better.
            // Use bridge write of minimal placeholder then host regenerates — simpler:
            // notify listener with a special path convention.
            if (listener instanceof CreatePdfListener) {
                ((CreatePdfListener) listener).onCreateSharedPdf(rel);
            } else if (listener != null) {
                listener.onLibraryMessage("create-pdf:" + rel);
            }
        });
    }

    /** Optional host hook for creating a real blank PDF in the library. */
    interface CreatePdfListener extends Listener {
        void onCreateSharedPdf(String relativePath);
    }

    private void promptMove(BridgeClient.LibraryEntry e) {
        List<String> labels = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        labels.add("Library root");
        paths.add(".");
        for (BridgeClient.LibraryEntry f : folderTargets) {
            if (f.path.equals(e.path)) continue;
            if (e.path.startsWith(f.path + "/")) continue;
            labels.add(f.path);
            paths.add(f.path);
        }
        if (!".".equals(browsePath)) {
            boolean has = false;
            for (String p : paths) {
                if (p.equals(browsePath)) { has = true; break; }
            }
            if (!has) {
                labels.add(browsePath);
                paths.add(browsePath);
            }
        }
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new M3Dialog.Builder(getContext())
                .setTitle("Move “" + stripPdf(e.name) + "” to…")
                .setItems(items, (d, which) -> {
                    if (which < 0 || which >= paths.size()) return;
                    String dest = paths.get(which);
                    if (dest.equals(parentOfPath(e.path))) return;
                    workspace.libraryMove(e.path, dest, new BridgeClient.Callback<String>() {
                        @Override
                        public void onSuccess(String value) { reload(); }

                        @Override
                        public void onError(String message) {
                            status(message != null ? message : "Move failed");
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface NameCb { void accept(String name); }

    private void promptName(String title, String hint, NameCb cb) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        int pad = dp(20);
        input.setPadding(pad, dp(12), pad, dp(12));
        new M3Dialog.Builder(getContext())
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText() != null
                            ? input.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        status("Name required");
                        return;
                    }
                    cb.accept(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private BridgeClient.Callback<BridgeClient.LibraryEntry> reloadCb(String okMsg) {
        return new BridgeClient.Callback<BridgeClient.LibraryEntry>() {
            @Override
            public void onSuccess(BridgeClient.LibraryEntry value) {
                if (listener != null && okMsg != null) listener.onLibraryMessage(okMsg);
                reload();
            }

            @Override
            public void onError(String message) {
                status(message != null ? message : "Failed");
            }
        };
    }

    private void status(String msg) {
        statusView.setVisibility(VISIBLE);
        statusView.setText(msg);
    }

    private void clearStatus() {
        statusView.setVisibility(GONE);
        statusView.setText("");
    }

    private void styleFilters() {
        styleChip(filterAll, !favoritesOnly);
        styleChip(filterFav, favoritesOnly);
    }

    private TextView filterChip(Context ctx, String label, boolean selected) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(32));
        styleChip(t, selected);
        return t;
    }

    /** Material 3 filter chip: 8dp corners, a leading check when selected. */
    private void styleChip(TextView t, boolean selected) {
        if (t == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (selected) {
            bg.setColor(colorPrimaryContainer);
            t.setTextColor(colorOnPrimaryContainer);
        } else {
            bg.setColor(colorSurfaceHigh);
            bg.setStroke(dp(1), colorOutline);
            t.setTextColor(colorOnSurface);
        }
        t.setBackground(bg);
        android.graphics.drawable.Drawable check = null;
        if (selected) {
            check = t.getContext().getDrawable(R.drawable.ic_check).mutate();
            check.setTint(colorOnPrimaryContainer);
            check.setBounds(0, 0, dp(18), dp(18));
        }
        t.setCompoundDrawablesRelative(check, null, null, null);
        t.setCompoundDrawablePadding(dp(8));
        t.setPadding(dp(selected ? 8 : 16), dp(6), dp(16), dp(6));
    }

    private TextView fabButton(Context ctx, String label, boolean square) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(square ? 22 : 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(colorOnPrimaryContainer);
        t.setGravity(Gravity.CENTER);
        t.setPadding(square ? dp(4) : dp(20), dp(13), square ? dp(4) : dp(20), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorPrimaryContainer);
        bg.setCornerRadius(square ? dp(16) : dp(24));
        t.setBackground(bg);
        elevate(t, 6);
        if (square) {
            t.setMinWidth(dp(48));
            t.setMinHeight(dp(48));
        }
        return t;
    }

    private LinearLayout.LayoutParams fabLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        return lp;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        g.setColor(fill);
        g.setStroke(dp(1), stroke);
        SketchStyle.border(g, getResources().getDisplayMetrics().density);
        return g;
    }

    private static int opaque(int argb, int fallback) {
        return argb == 0 ? fallback : (argb | 0xFF000000);
    }

    /** The accent, rotated to one of a few hues and pushed to tile saturation. */
    private int colorForPath(String path) {
        String custom = path != null ? colorPrefs().getString(path, null) : null;
        if (custom != null) {
            try {
                return android.graphics.Color.parseColor(custom);
            } catch (IllegalArgumentException ignored) {
            }
        }
        int h = path != null ? path.hashCode() : 0;
        int slot = Math.floorMod(h, TILE_HUES);
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(colorPrimary, hsv);
        hsv[0] = (hsv[0] + slot * (360f / TILE_HUES)) % 360f;
        hsv[1] = Math.max(0.35f, Math.min(0.72f, hsv[1] + 0.25f));
        hsv[2] = lightTheme ? 0.82f : 0.52f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** Rounded shadow that follows the tile instead of boxing it. */
    private void elevate(View v, int dpElevation) {
        v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        v.setClipToOutline(true);
        SketchStyle.elevate(v, dpElevation);
    }

    private static String initialOf(String name) {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toLowerCase(Locale.US);
    }

    private static String stripPdf(String name) {
        if (name == null) return "";
        if (name.toLowerCase(Locale.US).endsWith(".pdf") && name.length() > 4) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static String displayPath(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return "All Projects";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String parentOfPath(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return ".";
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return ".";
        return path.substring(0, slash);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static void tint(ImageView iv, int color) {
        if (iv != null) iv.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
    }
}
