package me.hapke.inkside;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * All Projects view and switching the active project.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class Projects {
    private final MainActivity act;

    Projects(MainActivity act) {
        this.act = act;
    }

    void ensureAllProjectsView() {
        if (act.rootLayout == null) return;
        if (act.allProjectsView == null) {
            act.allProjectsView = new AllProjectsView(act, act.workspace, new AllProjectsView.CreatePdfListener() {
                @Override
                public void onOpenProject(String path, String name) {
                    // Opening a project from the library picks up where you left off in it.
                    enterProject(path, name, lastDocumentIn(path));
                }

                @Override
                public void onOpenSharedPdf(String path) {
                    openSharedPdfFromLibrary(path);
                }

                @Override
                public void onCreateSharedPdf(String relativePath) {
                    if (act.bridge == null) return;
                    final String path = relativePath.startsWith("/")
                            ? relativePath.substring(1) : relativePath;
                    new Thread(() -> {
                        try {
                            byte[] bytes = PdfDocumentIo.createBlankA4(1);
                            act.workspace.writeFileBytesSync(path, bytes);
                            act.runOnUiThread(() -> {
                                if (act.isDead()) return;
                                act.appCreatedDocs.add(path);
                                if (act.allProjectsView != null) act.allProjectsView.reload();
                                act.snackbar("Created " + path, false);
                            });
                        } catch (Exception e) {
                            act.runOnUiThread(() -> {
                                if (!act.isDead()) {
                                    act.snackbar("create PDF: " + e.getMessage(), false);
                                }
                            });
                        }
                    }).start();
                }

                @Override
                public void onShowMenu(View anchor) {
                    act.overflowMenu.showOverflowMenu(anchor, true);
                }

                @Override
                public void onLibraryMessage(String msg) {
                    if (msg != null && !msg.isEmpty()) {
                        act.snackbar(msg, false);
                    }
                }
            });
            act.allProjectsView.setVisibility(View.GONE);
            // Its height comes from liftPanel each time it is shown (Zen may have changed).
            act.allProjectsView.setTopInset(act.statusBarHeight());
            applyAllProjectsTheme();
        }
        reattachAllProjectsView();
    }

    /** Keep the library overlay attached after {@code rootLayout.removeAllViews()}. */
    void reattachAllProjectsView() {
        if (act.rootLayout == null || act.allProjectsView == null) return;
        if (act.allProjectsView.getParent() == act.rootLayout) return;
        if (act.allProjectsView.getParent() instanceof ViewGroup) {
            ((ViewGroup) act.allProjectsView.getParent()).removeView(act.allProjectsView);
        }
        act.rootLayout.addView(act.allProjectsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        act.allProjectsView.bringToFront();
    }

    void applyAllProjectsTheme() {
        if (act.allProjectsView == null) return;
        act.allProjectsView.applyTheme(
                act.M3_SURFACE, act.M3_SURFACE_CONTAINER_HIGH, act.M3_ON_SURFACE, act.M3_ON_SURFACE_VARIANT,
                act.M3_PRIMARY, act.M3_PRIMARY_CONTAINER, act.M3_ON_PRIMARY_CONTAINER, act.M3_OUTLINE_VARIANT);
    }

    void syncProjectsBackButton() {
        if (act.projectsBackButton == null) return;
        boolean libraryOpen = act.allProjectsView != null
                && act.allProjectsView.getVisibility() == View.VISIBLE;
        // Always available on the canvas — not only when activeProjectPath is set.
        act.projectsBackButton.setVisibility(libraryOpen ? View.GONE : View.VISIBLE);
        // Nothing in this pill means anything while the library itself is up.
        if (act.navPill != null) act.navPill.setVisibility(libraryOpen ? View.GONE : View.VISIBLE);
        if (!libraryOpen) {
            // No bringToFront: in a LinearLayout that reorders the children, which put
            // the back arrow to the right of the recents button in its own pill.
            act.applyIconSelected(act.projectsBackButton, false);
        }
    }

    void showAllProjects() {
        ensureAllProjectsView();
        if (act.activeProjectPath != null && !act.activeProjectPath.isEmpty()) {
            act.lastProjectPath = act.activeProjectPath;
        }
        act.documents.stashCurrentDocumentState();
        act.activeProjectPath = null;
        act.explorer.hideFolderExplorer();
        reattachAllProjectsView();
        if (act.allProjectsView != null) {
            act.allProjectsView.setVisibility(View.VISIBLE);
            act.liftPanel(act.allProjectsView);
            act.allProjectsView.bringToFront();
            act.allProjectsView.showAndReload();
        }
        syncProjectsBackButton();
        act.persistence.scheduleSave();
    }

    private void hideAllProjects() {
        if (act.allProjectsView != null) act.allProjectsView.setVisibility(View.GONE);
        syncProjectsBackButton();
    }

    static String projectDisplayName(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return "Project";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Enter a project workspace. Optionally open {@code openPdfPath} after enter
     * (used for shared PDFs from All Projects).
     */
    /** The document most recently open inside {@code project}, or null (recents are newest first). */
    String lastDocumentIn(String project) {
        if (project == null || project.isEmpty()) return null;
        String prefix = project.endsWith("/") ? project : project + "/";
        String saved = act.lastDocByProject.get(project);
        if (saved != null && saved.startsWith(prefix)) return saved;
        for (String doc : act.recentDocs) {
            if (doc != null && doc.startsWith(prefix) && doc.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) return doc;
        }
        return null;
    }

    void enterProject(String path, String name, String openPdfPath) {
        if (path == null || path.isEmpty()) return;
        act.documents.stashCurrentDocumentState();
        act.activeProjectPath = path;
        act.lastProjectPath = path;
        hideAllProjects();
        applyProjectToExplorer();
        act.conversations.ensureChatsForActiveProject();
        syncProjectsBackButton();
        if (openPdfPath != null && !openPdfPath.isEmpty()) {
            act.documents.openPdfDocument(openPdfPath);
        } else {
            // Prefer restoring last document if it lives in this project; else leave canvas.
            act.persistence.scheduleSave();
        }
        act.persistence.scheduleSave();
    }

    /**
     * Opening a document (from recents, favourites, search, …) also opens the project
     * it lives in, so the explorer and the chats match what is on the canvas. Documents
     * outside any project leave the current project as it is.
     */
    void ensureProjectForDocument(String docPath) {
        if (act.bridge == null || docPath == null || docPath.isEmpty()) return;
        act.workspace.projectOf(docPath, new BridgeClient.Callback<String[]>() {
            @Override
            public void onSuccess(String[] value) {
                if (act.isDead() || value == null || value[0] == null || value[0].isEmpty()) return;
                String project = value[0];
                if (act.canvas == null || !docPath.equals(act.canvas.getDocumentPath())) return;
                if (project.equals(act.activeProjectPath)) return;
                enterProject(project, value[1] != null ? value[1] : projectDisplayName(project), null);
            }

            @Override
            public void onError(String message) {
                // Offline: stay where we are.
            }
        });
    }

    private void openSharedPdfFromLibrary(String pdfPath) {
        String project = act.lastProjectPath;
        if (project == null || project.isEmpty()) {
            // Fall back: any known project chat's path, or require creating one.
            for (ChatSession c : act.chats) {
                if (c.projectPath != null && !c.projectPath.isEmpty()) {
                    project = c.projectPath;
                    break;
                }
            }
        }
        if (project == null || project.isEmpty()) {
            new M3Dialog.Builder(act)
                    .setMessage("Open or create a project first — shared PDFs open alongside your last project.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        enterProject(project, projectDisplayName(project), pdfPath);
    }

    private void applyProjectToExplorer() {
        act.explorer.ensureExplorerPanel();
        if (act.folderExplorer == null || act.activeProjectPath == null) return;
        act.folderExplorer.setProjectContext(act.activeProjectPath, projectDisplayName(act.activeProjectPath));
        act.folderExplorer.applyTheme(
                act.M3_SURFACE, act.M3_ON_SURFACE, act.M3_ON_SURFACE_VARIANT, act.M3_PRIMARY,
                act.M3_PRIMARY_CONTAINER, act.M3_ON_PRIMARY_CONTAINER, act.M3_OUTLINE_VARIANT);
    }
}
