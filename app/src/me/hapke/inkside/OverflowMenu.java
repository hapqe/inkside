package me.hapke.inkside;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;

/**
 * The toolbar's overflow dropdown and the recent documents menu.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class OverflowMenu {
    private final MainActivity act;

    private FrameLayout overflowOverlay;

    OverflowMenu(MainActivity act) {
        this.act = act;
    }

    void dismissOverflowMenu() {
        final FrameLayout overlay = overflowOverlay;
        overflowOverlay = null;
        if (overlay == null || act.rootLayout == null) return;
        overlay.setClickable(false);
        overlay.setOnClickListener(null);
        View menu = overlay.getChildCount() > 0 ? overlay.getChildAt(0) : null;
        if (menu == null) {
            act.rootLayout.removeView(overlay);
            return;
        }
        menu.animate().cancel();
        menu.setPivotY(0f);
        menu.animate()
                .alpha(0f)
                .translationY(-act.dp(8))
                .scaleY(0.92f)
                .setDuration(Motion.EXIT_MS - 30)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> act.rootLayout.removeView(overlay))
                .start();
    }

    /** Dropdown surface shared by the three-dot and recent-documents menus. */
    private LinearLayout buildDropdownMenu() {
        LinearLayout menu = new LinearLayout(act);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, act.dp(MainActivity.SPACE_SM), 0, act.dp(MainActivity.SPACE_SM));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(16));
        // Same surface + hairline as the tool pills and the settings card, but opaque:
        // the pills' slight translucency let ink show through a list of text.
        bg.setColor(act.M3_SURFACE_CONTAINER_HIGH | 0xFF000000);
        bg.setStroke(act.dp(1), (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x55000000);
        menu.setBackground(bg);
        menu.setElevation(act.dp(8));
        menu.setClickable(true);
        menu.setOnClickListener(v -> {});
        return menu;
    }

    /** Slide down from under the anchor while fading in. */
    void animateDropdownIn(View menu) {
        menu.animate().cancel();
        menu.setAlpha(0f);
        menu.setTranslationY(-act.dp(10));
        menu.setPivotY(0f);
        menu.setScaleX(0.96f);
        menu.setScaleY(0.86f);
        menu.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.LAND)
                .start();
        // Rows follow the surface down, one after another.
        if (menu instanceof ViewGroup) Motion.stagger((ViewGroup) menu, 40L, -6f);
    }

    /** Small dropdown under the three-dot button. */
    void showOverflowMenu() {
        if (overflowOverlay != null) {
            dismissOverflowMenu();
            return;
        }
        if (act.rootLayout == null || act.settingsButton == null) return;

        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissOverflowMenu());

        LinearLayout menu = buildDropdownMenu();
        menu.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD));
        addOverflowRow(menu, overflowItem(R.drawable.ic_folder_open, "All Projects", () -> {
            dismissOverflowMenu();
            act.projects.showAllProjects();
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_palette, "Page styling", () -> {
            dismissOverflowMenu();
            act.pageStyle.showPageStyleMenu();
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_star, "Quick favorites", () -> {
            dismissOverflowMenu();
            act.favorites.showFavoritesArranger();
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_fullscreen, "Zen mode", () -> {
            dismissOverflowMenu();
            act.zen.enterZenMode();
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_present,
                act.presentation != null ? "Stop presenting" : "Present", () -> {
            dismissOverflowMenu();
            act.slideshow.togglePresentation();
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_calendar, "Study week", () -> {
            dismissOverflowMenu();
            new StudyWeekDialog(act).show();
        }));
        if (act.canvas != null && act.canvas.hasDocument()) {
            addOverflowRow(menu, overflowItem(R.drawable.ic_download, "Export PDF", () -> {
                dismissOverflowMenu();
                act.pdfExport.exportCurrentPdf();
            }));
        }
        // App-level settings are a different kind of thing from the actions above.
        View divider = m3MenuDivider();
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        dlp.topMargin = act.dp(MainActivity.SPACE_SM);
        dlp.bottomMargin = act.dp(MainActivity.SPACE_SM);
        menu.addView(divider, dlp);
        final PairedHosts.Host linked = PairedHosts.remote(act);
        addOverflowRow(menu, overflowItem(R.drawable.ic_link,
                linked == null ? "Connect a computer" : "Remote: " + linked.name, () -> {
            dismissOverflowMenu();
            act.computers.showRemoteMenu();
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_delete, "Recently deleted", () -> {
            dismissOverflowMenu();
            TrashDialog.show(act);
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_settings, "Settings", () -> {
            dismissOverflowMenu();
            act.settingsPanel.showOptionsMenu();
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_info, "About", () -> {
            dismissOverflowMenu();
            AboutDialog.show(act);
        }));

        int[] loc = new int[2];
        act.settingsButton.getLocationInWindow(loc);
        int[] rootLoc = new int[2];
        act.rootLayout.getLocationInWindow(rootLoc);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                act.dp(OVERFLOW_MENU_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.topMargin = loc[1] - rootLoc[1] + act.settingsButton.getHeight() + act.dp(MainActivity.SPACE_SM);
        // Right-align to the button so the menu never runs off the edge.
        lp.leftMargin = Math.max(act.dp(MainActivity.SPACE_SM),
                loc[0] - rootLoc[0] + act.settingsButton.getWidth() - act.dp(OVERFLOW_MENU_W));
        overlay.addView(menu, lp);
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overflowOverlay = overlay;
        animateDropdownIn(menu);
    }

    private static final int OVERFLOW_MENU_W = 244;

    /** What the chat's + button offers; opens above the button. */
    void showAttachMenu(View anchor) {
        if (overflowOverlay != null) {
            dismissOverflowMenu();
            return;
        }
        if (act.rootLayout == null || anchor == null) return;
        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissOverflowMenu());

        LinearLayout menu = buildDropdownMenu();
        menu.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD));
        addOverflowRow(menu, overflowItem(R.drawable.ic_upload, "Upload file", () -> {
            dismissOverflowMenu();
            act.conversations.openAttachmentPicker("", false);
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_pdf, "Upload document", () -> {
            dismissOverflowMenu();
            act.conversations.openAttachmentPicker("", true);
        }));
        View divider = m3MenuDivider();
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        dlp.topMargin = act.dp(MainActivity.SPACE_SM);
        dlp.bottomMargin = act.dp(MainActivity.SPACE_SM);
        menu.addView(divider, dlp);
        addOverflowRow(menu, overflowItem(R.drawable.ic_description, "As reference", () -> {
            dismissOverflowMenu();
            act.conversations.openAttachmentPicker("reference", false);
        }));
        addOverflowRow(menu, overflowItem(R.drawable.ic_check_circle, "As learning goal", () -> {
            dismissOverflowMenu();
            act.conversations.openAttachmentPicker("goal", false);
        }));

        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int[] rootLoc = new int[2];
        act.rootLayout.getLocationInWindow(rootLoc);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                act.dp(OVERFLOW_MENU_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.bottomMargin = act.rootLayout.getHeight() - (loc[1] - rootLoc[1]) + act.dp(MainActivity.SPACE_SM);
        lp.leftMargin = Math.max(act.dp(MainActivity.SPACE_SM), loc[0] - rootLoc[0]);
        overlay.addView(menu, lp);
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overflowOverlay = overlay;
        menu.setAlpha(0f);
        menu.setTranslationY(act.dp(10));
        menu.animate().alpha(1f).translationY(0f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.LAND).start();
    }

    /** Rows of the ⋮ menu, a little air between them. */
    private void addOverflowRow(LinearLayout menu, View row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Between rows only; the divider brings its own margins.
        int n = menu.getChildCount();
        if (n > 0 && menu.getChildAt(n - 1) instanceof ViewGroup) {
            lp.topMargin = act.dp(MainActivity.SPACE_XS);
        }
        menu.addView(row, lp);
    }

    private View m3MenuDivider() {
        View line = new View(act);
        line.setBackgroundColor((act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x44000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, act.dp(1) / 2));
        lp.leftMargin = act.dp(MainActivity.SPACE_MD);
        lp.rightMargin = act.dp(MainActivity.SPACE_MD);
        line.setLayoutParams(lp);
        return line;
    }

    /** Most-recent-first, no duplicates, current document at the front. */
    void noteRecentDoc(String path) {
        if (path == null || path.isEmpty()) return;
        // Every document opening passes here: a running study session carries on in it.
        if (act.studyLog != null) act.studyLog.switchDocument(path);
        // Remember it as its project's last document (opening that project reopens it).
        for (String project : new java.util.ArrayList<>(act.lastDocByProject.keySet())) {
            if (!path.startsWith(project + "/")) continue;
            act.lastDocByProject.put(project, path);
        }
        if (act.activeProjectPath != null && path.startsWith(act.activeProjectPath + "/")) {
            act.lastDocByProject.put(act.activeProjectPath, path);
        }
        act.recentDocs.remove(path);
        act.recentDocs.add(0, path);
        while (act.recentDocs.size() > MainActivity.RECENT_DOCS_MAX) act.recentDocs.remove(act.recentDocs.size() - 1);
        syncQuickSwitchButton();
    }

    /**
     * Opens the most recently opened document other than the one on screen — flip
     * back and forth between two documents.
     */
    void openLastDocument() {
        String current = act.canvas != null ? act.canvas.getDocumentPath() : "";
        for (String p : new java.util.ArrayList<>(act.recentDocs)) {
            if (p != null && !p.isEmpty() && !p.equals(current)) {
                act.documents.openPdfDocument(p);
                return;
            }
        }
        act.snackbar("No other document opened yet", false);
    }

    /** True when there is somewhere else to go. */
    private boolean hasOtherRecentDoc() {
        String current = act.canvas != null ? act.canvas.getDocumentPath() : "";
        for (String p : act.recentDocs) {
            if (p != null && !p.isEmpty() && !p.equals(current)) return true;
        }
        return false;
    }

    void syncQuickSwitchButton() {
        if (act.quickSwitchButton == null) return;
        act.quickSwitchButton.setAlpha(hasOtherRecentDoc() ? 1f : 0.45f);
    }

    /** The recent documents, newest first, with the open one marked. */
    void showRecentDocsMenu() {
        if (act.rootLayout == null || act.quickSwitchButton == null) return;
        if (overflowOverlay != null) dismissOverflowMenu();
        String current = act.canvas != null ? act.canvas.getDocumentPath() : "";

        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissOverflowMenu());

        LinearLayout menu = buildDropdownMenu();
        menu.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG));

        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        boolean any = false;
        for (String path : new java.util.ArrayList<>(act.recentDocs)) {
            if (path == null || path.isEmpty()) continue;
            final String target = path;
            boolean open = target.equals(current);
            View row = recentDocRow(target, open, () -> {
                dismissOverflowMenu();
                if (!open) act.documents.openPdfDocument(target);
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // Spacing, not hairlines, separates the rows.
            if (any) rlp.topMargin = act.dp(MainActivity.SPACE_XS * 2);
            list.addView(row, rlp);
            any = true;
        }
        if (!any) {
            TextView empty = overflowItem("No documents opened yet", this::dismissOverflowMenu);
            empty.setTextColor(act.M3_ON_SURFACE_VARIANT);
            list.addView(empty);
        }
        // Eight two-line rows can outgrow a landscape screen; scroll rather than clip.
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list);
        int maxH = Math.round(act.rootLayout.getHeight() * 0.7f);
        menu.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (maxH > 0) {
            list.measure(View.MeasureSpec.makeMeasureSpec(act.dp(304), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (list.getMeasuredHeight() > maxH) {
                scroll.getLayoutParams().height = maxH;
            }
        }

        int[] loc = new int[2];
        act.quickSwitchButton.getLocationInWindow(loc);
        int[] rootLoc = new int[2];
        act.rootLayout.getLocationInWindow(rootLoc);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                act.cardWidth(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.topMargin = loc[1] - rootLoc[1] + act.quickSwitchButton.getHeight() + act.dp(MainActivity.SPACE_SM);
        lp.leftMargin = Math.max(act.dp(MainActivity.SPACE_SM), loc[0] - rootLoc[0] - act.dp(100));
        overlay.addView(menu, lp);
        // Above the floating chat button, which otherwise sat over the list's rows.
        overlay.setTranslationZ(act.dp(48));
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overflowOverlay = overlay;
        animateDropdownIn(menu);
    }

    /**
     * One recent document: a PDF icon, the name, and the folder it lives in beneath,
     * as an M3 two-line list item. The one already open is marked rather than hidden,
     * so the list reads as history rather than as a set of somewhere-elses.
     */
    private View recentDocRow(String path, boolean open, Runnable onTap) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(act.dp(56));
        row.setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10));
        row.setClickable(true);
        row.setOnClickListener(v -> onTap.run());
        if (open) {
            GradientDrawable selected = new GradientDrawable();
            selected.setCornerRadius(act.dp(12));
            selected.setColor((act.M3_PRIMARY_CONTAINER & 0x00FFFFFF) | 0x66000000);
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf((act.M3_PRIMARY & 0x00FFFFFF) | 0x33000000),
                    selected, null));
        } else {
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(act.dp(12));
            mask.setColor(0xFFFFFFFF);
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf((act.M3_PRIMARY & 0x00FFFFFF) | 0x33000000),
                    null, mask));
        }

        ImageView icon = new ImageView(act);
        icon.setImageResource(R.drawable.ic_pdf);
        icon.setColorFilter(new PorterDuffColorFilter(
                open ? act.M3_PRIMARY : act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(act.dp(22), act.dp(22));
        ilp.rightMargin = act.dp(16);
        row.addView(icon, ilp);

        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(act);
        name.setText(docDisplayName(path));
        name.setTextColor(open ? act.M3_PRIMARY : act.M3_ON_SURFACE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(Typeface.create(open ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        String folder = docFolderName(path);
        if (!folder.isEmpty()) {
            TextView sub = new TextView(act);
            sub.setText(folder);
            sub.setTextColor(act.M3_ON_SURFACE_VARIANT);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setSingleLine(true);
            // Folders run deep; the part that tells them apart is the end.
            sub.setEllipsize(android.text.TextUtils.TruncateAt.START);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = act.dp(2);
            text.addView(sub, slp);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (open) {
            TextView badge = new TextView(act);
            badge.setText("Open");
            badge.setTextColor(act.M3_PRIMARY);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = act.dp(12);
            row.addView(badge, blp);
        }
        return row;
    }

    /** Folder part of a workspace path, or "" for the workspace root. */
    private static String docFolderName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "";
        String folder = path.substring(0, slash);
        while (folder.startsWith("./")) folder = folder.substring(2);
        return folder.equals(".") ? "" : folder;
    }

    /** File name without the folder or the .pdf. */
    private static String docDisplayName(String path) {
        if (path == null || path.isEmpty()) return "";
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private TextView overflowItem(String label, Runnable onTap) {
        TextView row = new TextView(act);
        row.setText(label);
        row.setTextColor(act.M3_ON_SURFACE);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setPadding(act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_LG + 2), act.dp(MainActivity.SPACE_XL), act.dp(MainActivity.SPACE_LG + 2));
        row.setBackground(menuRowRipple());
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    /** Icon + label menu row, icon tinted like the tool bar's idle icons. */
    private View overflowItem(int iconRes, String label, Runnable onTap) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(act.dp(46));
        row.setPadding(act.dp(16), act.dp(MainActivity.SPACE_MD), act.dp(16), act.dp(MainActivity.SPACE_MD));
        row.setBackground(menuRowRipple());
        row.setClickable(true);
        row.setOnClickListener(v -> onTap.run());

        ImageView icon = new ImageView(act);
        icon.setImageResource(iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(act.dp(22), act.dp(22));
        ilp.rightMargin = act.dp(14);
        row.addView(icon, ilp);

        TextView text = new TextView(act);
        text.setText(label);
        text.setTextColor(act.M3_ON_SURFACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** Press feedback in the theme's primary, inset so it reads as a rounded pill. */
    private android.graphics.drawable.Drawable menuRowRipple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(act.dp(12));
        android.graphics.drawable.InsetDrawable insetMask =
                new android.graphics.drawable.InsetDrawable(mask, act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_XS), 0);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((act.M3_PRIMARY & 0x00FFFFFF) | 0x33000000),
                null, insetMask);
    }
}
