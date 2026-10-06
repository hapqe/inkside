package me.hapke.inkside;

import android.content.ClipData;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Quick favorites: the radial menu, its arranger and favorite actions.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class Favorites {
    private final MainActivity act;

    private FrameLayout favoritesOverlay;

    Favorites(MainActivity act) {
        this.act = act;
    }

    void dismissFavoritesMenu() {
        if (favoritesOverlay != null && act.rootLayout != null) act.rootLayout.removeView(favoritesOverlay);
        favoritesOverlay = null;
    }

    void showFavoritesArranger() {
        showFavoritesMenu(null);
    }

    /**
     * Quick favorites menu (three-dot menu → Quick favorites): the on/off switch, the
     * full-size radial to drag each favorite where the pen should find it, and the list
     * of favorites with a delete button each. Opens by itself when a favorite is added,
     * with {@code justAdded} called out so it is easy to place.
     */
    void showFavoritesMenu(String justAdded) {
        if (act.rootLayout == null) return;
        boolean reopening = favoritesOverlay != null;
        dismissFavoritesMenu();
        final FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setBackgroundColor(0x99000000);
        overlay.setOnClickListener(v -> dismissFavoritesMenu());
        // Above All Projects (elevation 40dp), where favorites can be added too.
        act.liftPanel(overlay);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_XL));
        act.settingsPanel.applyOptionsCardSurface(card);
        card.setClickable(true);
        card.setOnClickListener(v -> {});

        TextView title = new TextView(act);
        title.setText("Quick favorites");
        title.setTextColor(act.M3_ON_SURFACE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        card.addView(title);

        card.addView(quickFavoritesSwitchRow());

        final FavoritesStore fav = FavoritesStore.get(act);
        List<RadialFavoritesPainter.Item> items = buildRadialFavoriteItems();

        TextView hint = new TextView(act);
        RadialFavoritesPainter.Item added = null;
        if (justAdded != null) {
            for (RadialFavoritesPainter.Item it : items) {
                if (justAdded.equals(it.id)) added = it;
            }
        }
        hint.setText(added != null
                ? "Added “" + added.label + "”. Drag it where the pen should find it."
                : "Drag a favorite around the ring to reorder. Press the pen button once "
                        + "while hovering to open the menu, then tap an item.");
        hint.setTextColor(added != null ? act.M3_PRIMARY : act.M3_ON_SURFACE_VARIANT);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, 0, 0, act.dp(MainActivity.SPACE_MD));
        card.addView(hint);

        if (items.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText("No favorites yet — long-press a tool, color or file to add one.");
            empty.setTextColor(act.M3_ON_SURFACE);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(0, act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_LG));
            card.addView(empty);
        }
        final FavoritesLayoutView editor = new FavoritesLayoutView(act, items, fav::setOrder);
        editor.applyColors(act.M3_SURFACE_CONTAINER_HIGHEST, act.M3_PRIMARY_CONTAINER,
                act.M3_ON_SURFACE_VARIANT, act.M3_ON_PRIMARY_CONTAINER,
                (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x99000000, act.M3_ON_SURFACE_VARIANT);
        // Predefined actions are dropped straight onto the spot they should take.
        editor.setOnDragListener((v, ev) -> {
            Object state = ev.getLocalState();
            if (!(state instanceof String) || !((String) state).startsWith(FAV_DRAG_PREFIX)) {
                return false;
            }
            if (ev.getAction() == android.view.DragEvent.ACTION_DROP) {
                String id = ((String) state).substring(FAV_DRAG_PREFIX.length());
                // Into the slot of the n-gon (with it added) nearest the drop.
                List<String> order = new ArrayList<>();
                for (RadialFavoritesPainter.Item it : buildRadialFavoriteItems()) order.add(it.id);
                int slot = editor.slotForPoint(ev.getX(), ev.getY(), order.size() + 1);
                order.add(Math.min(slot, order.size()), id);
                // The menu reopens (posted) on the add and then reads this order.
                fav.setFavorite(id, true);
                fav.setOrder(order);
            }
            return true;
        });
        card.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, act.dp(300)));
        View palette = favoriteActionsPalette(fav);
        if (palette != null) {
            card.addView(act.settingsPanel.optionsSectionLabel("Actions"));
            card.addView(palette);
        }
        if (!items.isEmpty()) {
            card.addView(act.settingsPanel.optionsSectionLabel("Favorites"));
            for (RadialFavoritesPainter.Item it : items) {
                card.addView(favoriteListRow(it, justAdded != null && justAdded.equals(it.id)));
            }
        }

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, act.dp(MainActivity.SPACE_MD), 0, 0);
        TextView done = new TextView(act);
        done.setText("Done");
        done.setTextColor(act.M3_PRIMARY);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_SM));
        done.setOnClickListener(v -> dismissFavoritesMenu());
        actions.addView(done);
        card.addView(actions);

        android.widget.ScrollView scroller = new android.widget.ScrollView(act);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setElevation(act.dp(8));
        scroller.addView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Rounded clip so the scrolled content respects the card corners.
        scroller.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), act.dp(28));
            }
        });
        scroller.setClipToOutline(true);

        final int maxH = Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.86f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                act.cardWidth(400), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(scroller, cardLp);
        scroller.post(() -> {
            if (scroller.getHeight() > maxH) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scroller.getLayoutParams();
                lp.height = maxH;
                scroller.setLayoutParams(lp);
            }
        });
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        favoritesOverlay = overlay;
        if (!reopening) {
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(150).start();
            scroller.setTranslationY(act.dp(16));
            scroller.animate().translationY(0f).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    private static final String FAV_DRAG_PREFIX = "fav-action:";

    /** Built-in actions offered in the Quick favorites menu, in this order. */
    private static final String[] PREDEFINED_FAVORITES = {
            FavoritesStore.ACTION_INSTANT_CHAT,
            FavoritesStore.ACTION_LAST_DOCUMENT,
            FavoritesStore.ACTION_NEW_PDF,
            FavoritesStore.TOOL_UNDO,
            FavoritesStore.TOOL_REDO,
            FavoritesStore.TOOL_PENCIL,
            FavoritesStore.TOOL_ERASER,
            FavoritesStore.TOOL_LASSO,
            FavoritesStore.TOOL_TEXT,
            FavoritesStore.TOOL_FOLDER,
    };

    /**
     * The predefined actions not in the radial yet, as chips three to a row: drag one
     * onto the radial above to put it exactly there, or tap it to add it to the ring.
     * Null when every one is already a favorite.
     */
    private View favoriteActionsPalette(FavoritesStore fav) {
        List<String> offer = new ArrayList<>();
        for (String id : PREDEFINED_FAVORITES) {
            if (!fav.isFavorite(id) && radialItemFor(id) != null) offer.add(id);
        }
        if (offer.isEmpty()) return null;
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < offer.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = act.dp(MainActivity.SPACE_LG);
                grid.addView(row, rlp);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 3 != 2) clp.rightMargin = act.dp(MainActivity.SPACE_LG);
            row.addView(favoriteActionChip(offer.get(i), fav), clp);
        }
        // Keep the last row's chips the same width as the rows above.
        int rest = offer.size() % 3;
        for (int i = rest; rest != 0 && i < 3; i++) {
            View spacer = new View(act);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);
            if (i != 2) slp.rightMargin = act.dp(MainActivity.SPACE_LG);
            row.addView(spacer, slp);
        }
        return grid;
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private View favoriteActionChip(String id, FavoritesStore fav) {
        RadialFavoritesPainter.Item item = radialItemFor(id);
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinimumHeight(act.dp(40));
        chip.setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(12));
        bg.setColor(act.M3_SURFACE_CONTAINER_HIGHEST | 0xFF000000);
        bg.setStroke(act.dp(1), (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x66000000);
        chip.setBackground(bg);
        ImageView icon = new ImageView(act);
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(act.dp(18), act.dp(18));
        ilp.rightMargin = act.dp(8);
        chip.addView(icon, ilp);
        TextView label = new TextView(act);
        label.setText(item.label);
        label.setTextColor(act.M3_ON_SURFACE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chip.setContentDescription("Add " + item.label + " to quick favorites");

        final int slop = android.view.ViewConfiguration.get(act).getScaledTouchSlop();
        final float[] down = new float[2];
        final boolean[] dragged = new boolean[1];
        chip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    dragged[0] = false;
                    // Inside the menu's scroller: the drag is ours.
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0];
                    float dy = e.getRawY() - down[1];
                    if (!dragged[0] && dx * dx + dy * dy > slop * slop) {
                        dragged[0] = true;
                        v.setPressed(false);
                        v.startDragAndDrop(android.content.ClipData.newPlainText("favorite", id),
                                new View.DragShadowBuilder(v), FAV_DRAG_PREFIX + id, 0);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    // A tap adds it on the automatic ring (the menu reopens to show it).
                    if (!dragged[0]) fav.setFavorite(id, true);
                    return true;
                default:
                    v.setPressed(false);
                    return true;
            }
        });
        return chip;
    }

    /** One favorite in the list: icon or ink swatch, name, delete. */
    private View favoriteListRow(RadialFavoritesPainter.Item item, boolean highlight) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(act.dp(44));
        row.setPadding(act.dp(MainActivity.SPACE_SM), 0, 0, 0);
        if (highlight) {
            GradientDrawable hl = new GradientDrawable();
            hl.setCornerRadius(act.dp(12));
            hl.setColor(act.M3_PRIMARY_CONTAINER);
            row.setBackground(hl);
        }
        int fg = highlight ? act.M3_ON_PRIMARY_CONTAINER : act.M3_ON_SURFACE;

        View lead;
        if (item.iconRes != 0) {
            ImageView icon = new ImageView(act);
            icon.setImageResource(item.iconRes);
            icon.setColorFilter(new PorterDuffColorFilter(
                    highlight ? act.M3_ON_PRIMARY_CONTAINER : act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
            lead = icon;
        } else {
            View sw = new View(act);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(item.swatchColor);
            d.setStroke(act.dp(1), act.M3_OUTLINE_VARIANT);
            sw.setBackground(d);
            lead = sw;
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(act.dp(20), act.dp(20));
        llp.rightMargin = act.dp(MainActivity.SPACE_LG);
        row.addView(lead, llp);

        TextView label = new TextView(act);
        label.setText(item.label);
        label.setTextColor(fg);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String id = item.id;
        ImageView delete = act.iconBtn(R.drawable.ic_delete, () -> {
            FavoritesStore.get(act).setFavorite(id, false);
            if (act.canvas != null) act.canvas.refreshFavoritesRadial(buildRadialFavoriteItems());
            showFavoritesMenu(null);
        });
        delete.setContentDescription("Remove " + item.label + " from favorites");
        row.addView(delete, new LinearLayout.LayoutParams(act.dp(40), act.dp(40)));

        LinearLayout.LayoutParams rowLp = MainActivity.matchWrap();
        rowLp.bottomMargin = act.dp(MainActivity.SPACE_XS);
        row.setLayoutParams(rowLp);
        return row;
    }

    private LinearLayout quickFavoritesSwitchRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, act.dp(MainActivity.SPACE_SM), 0, act.dp(MainActivity.SPACE_LG));
        row.setMinimumHeight(act.dp(48));

        TextView label = new TextView(act);
        label.setText("Quick favorites");
        label.setTextColor(act.M3_ON_SURFACE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, llp);

        Material3Switch sw = new Material3Switch(act);
        sw.applyColors(
                act.M3_PRIMARY,
                act.M3_PRIMARY_CONTAINER,
                act.M3_SURFACE_CONTAINER_HIGHEST,
                act.M3_OUTLINE_VARIANT,
                act.M3_ON_SURFACE);
        sw.setChecked(act.canvas == null || act.canvas.isQuickFavoritesEnabled());
        sw.setContentDescription("Quick favorites");
        sw.setOnCheckedChangeListener((v, isChecked) -> {
            if (act.canvas != null) act.canvas.setQuickFavoritesEnabled(isChecked);
            act.persistence.scheduleSave();
        });
        row.addView(sw);
        return row;
    }

    void bindFavoriteLongPress(View v, String favId) {
        if (v == null || favId == null) return;
        v.setTag(favId);
        v.setOnLongClickListener(anchor -> {
            showFavoritePopup(anchor, favId);
            return true;
        });
    }

    void showFavoritePopup(View anchor, String favId) {
        showFavoritePopup(anchor, favId, null, null);
    }

    void showFavoritePopup(View anchor, String favId, String extraLabel, Runnable extraAction) {
        if (anchor == null || favId == null) return;
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        FavoritesStore fav = FavoritesStore.get(act);
        boolean on = fav.isFavorite(favId);
        M3Menu menu = new M3Menu(act);
        menu.add(R.drawable.ic_star, on ? "Remove from favorites" : "Add to favorites", () -> fav.toggle(favId));
        if (extraLabel != null && extraAction != null) menu.add(0, extraLabel, extraAction);
        menu.showUnder(anchor);
    }

    List<RadialFavoritesPainter.Item> buildRadialFavoriteItems() {
        List<RadialFavoritesPainter.Item> out = new ArrayList<>();
        FavoritesStore fav = FavoritesStore.get(act);
        for (String id : fav.allOrdered()) {
            RadialFavoritesPainter.Item item = radialItemFor(id);
            if (item == null) continue;
            // Always the even n-gon, in the stored order.
            item.offsetDp = null;
            out.add(item);
        }
        return out;
    }

    private RadialFavoritesPainter.Item radialItemFor(String id) {
        if (id == null) return null;
        if (id.startsWith("color:")) {
            try {
                int idx = Integer.parseInt(id.substring("color:".length()));
                // The active pen's slot, as the toolbar shows it.
                int[] pal = act.penTools.palette();
                if (idx < 0 || idx >= pal.length) return null;
                return new RadialFavoritesPainter.Item(id, PenTools.colorNameFor(pal[idx]), 0, pal[idx]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (FavoritesStore.isFileId(id)) {
            String path = FavoritesStore.pathFromFileId(id);
            String name = path;
            int slash = path != null ? path.lastIndexOf('/') : -1;
            if (slash >= 0 && slash < path.length() - 1) name = path.substring(slash + 1);
            int icon = R.drawable.ic_file;
            if (name != null) {
                String n = name.toLowerCase(java.util.Locale.US);
                if (n.endsWith(".pdf")) icon = R.drawable.ic_pdf;
                else if (n.endsWith(".viz")) icon = R.drawable.ic_viz;
                else if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                        || n.endsWith(".webp") || n.endsWith(".gif")) icon = R.drawable.ic_image;
                else if (n.endsWith(".py") || n.endsWith(".js") || n.endsWith(".java")
                        || n.endsWith(".md") || n.endsWith(".json")) icon = R.drawable.ic_code;
            }
            return new RadialFavoritesPainter.Item(id, name != null ? name : "File", icon, 0);
        }
        switch (id) {
            case FavoritesStore.TOOL_UNDO:
                return new RadialFavoritesPainter.Item(id, "Undo", R.drawable.ic_undo, 0);
            case FavoritesStore.TOOL_REDO:
                return new RadialFavoritesPainter.Item(id, "Redo", R.drawable.ic_redo, 0);
            case FavoritesStore.TOOL_PENCIL:
                return new RadialFavoritesPainter.Item(id, "Pen", R.drawable.ic_pencil, 0);
            case FavoritesStore.TOOL_ERASER:
                return new RadialFavoritesPainter.Item(id, "Eraser", R.drawable.ic_eraser, 0);
            case FavoritesStore.TOOL_LASSO:
                return new RadialFavoritesPainter.Item(id, "Lasso", R.drawable.ic_lasso, 0);
            case FavoritesStore.TOOL_TEXT:
                return new RadialFavoritesPainter.Item(id, "Text", R.drawable.ic_text, 0);
            case FavoritesStore.TOOL_FOLDER:
                return new RadialFavoritesPainter.Item(id, "Files", R.drawable.ic_folder, 0);
            case FavoritesStore.ACTION_NEW_PDF:
                return new RadialFavoritesPainter.Item(id, "New PDF", R.drawable.ic_pdf_add, 0);
            case FavoritesStore.ACTION_INSTANT_CHAT:
                if (!act.aiEnabled) return null;
                return new RadialFavoritesPainter.Item(id, "Instant chat", R.drawable.ic_mic, 0);
            case FavoritesStore.ACTION_LAST_DOCUMENT:
                return new RadialFavoritesPainter.Item(id, "Last document", R.drawable.ic_history, 0);
            default:
                return null;
        }
    }

    void activateFavorite(String id) {
        if (id == null) return;
        if (id.startsWith("color:")) {
            try {
                int idx = Integer.parseInt(id.substring("color:".length()));
                act.penTools.selectPencil(idx);
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        if (FavoritesStore.isFileId(id)) {
            String path = FavoritesStore.pathFromFileId(id);
            if (path == null || path.isEmpty()) return;
            String lower = path.toLowerCase(java.util.Locale.US);
            if (lower.endsWith(".pdf")) act.documents.openPdfDocument(path);
            else if (lower.endsWith(".viz")) act.codeEditor.openVizFile(path);
            else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                act.canvasDrops.addAssetToCanvasCenter(path, false);
            } else {
                act.codeEditor.openScriptEditor(path);
            }
            return;
        }
        switch (id) {
            case FavoritesStore.TOOL_UNDO:
                if (act.canvas != null) {
                    act.canvas.undo();
                    act.persistence.scheduleSave();
                }
                break;
            case FavoritesStore.TOOL_REDO:
                if (act.canvas != null) {
                    act.canvas.redo();
                    act.persistence.scheduleSave();
                }
                break;
            case FavoritesStore.TOOL_PENCIL:
                act.penTools.selectPen();
                break;
            case FavoritesStore.TOOL_ERASER:
                act.penTools.selectEraser();
                break;
            case FavoritesStore.TOOL_LASSO:
                act.penTools.selectLasso();
                break;
            case FavoritesStore.TOOL_TEXT:
                act.penTools.selectText();
                break;
            case FavoritesStore.TOOL_FOLDER:
                act.hideSoftKeyboard();
                if (act.explorerCollapsed) act.explorer.toggleFolderExplorer();
                break;
            case FavoritesStore.ACTION_NEW_PDF:
                act.documents.promptCreatePdfDocument();
                break;
            case FavoritesStore.ACTION_INSTANT_CHAT:
                act.instantChat.startInstantChat();
                break;
            case FavoritesStore.ACTION_LAST_DOCUMENT:
                act.overflowMenu.openLastDocument();
                break;
            default:
                break;
        }
    }
}
