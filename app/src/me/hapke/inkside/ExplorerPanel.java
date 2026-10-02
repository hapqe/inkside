package me.hapke.inkside;

import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * The folder explorer side panel: building, sliding and resizing it.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class ExplorerPanel {
    private final MainActivity act;

    private View explorerScrim;

    private boolean explorerPanelDragging = false;
    private float explorerSlideStartRawX;
    private float explorerSlideStartTx;
    private float explorerDragStartRawX;
    private float explorerDragStartRawY;

    private FrameLayout.LayoutParams explorerResizeHandleLp;

    private int explorerResizeStartWidth;
    private int explorerResizePendingWidth;
    private float explorerResizeStartRawX;
    private float explorerResizeLastRawX;

    private static final int EXPLORER_EDGE_DRAG_W = 24;

    ExplorerPanel(MainActivity act) {
        this.act = act;
    }

    /**
     * How far the editor must shift to clear the explorer.
     *
     * <p>Uses the live on-screen width while the explorer is dragging or sliding so
     * the editor tracks it frame-by-frame. When settled and open, uses the full
     * panel width so a transient 0-width reading cannot leave the editor underneath.
     */
    int explorerOpenOffsetPx() {
        if (act.explorerCollapsed || act.explorerPanel == null) return 0;
        if (explorerPanelDragging
                || (act.explorerSlideAnim != null && act.explorerSlideAnim.isRunning())) {
            return act.visibleExplorerWidthPx();
        }
        return explorerPanelWidth();
    }

    int explorerPanelWidth() {
        if (act.explorerPanelWidthPx <= 0) act.explorerPanelWidthPx = act.dp(MainActivity.EXPLORER_PANEL_W);
        return act.clampSidePanelWidth(act.explorerPanelWidthPx, act.dp(MainActivity.EXPLORER_PANEL_MIN_W));
    }

    private void applyExplorerPanelWidth(int widthPx) {
        act.explorerPanelWidthPx = act.clampSidePanelWidth(widthPx, act.dp(MainActivity.EXPLORER_PANEL_MIN_W));
        if (act.explorerPanelLp != null && act.explorerPanel != null) {
            act.explorerPanelLp.width = explorerPanelWidth();
            act.explorerPanel.setLayoutParams(act.explorerPanelLp);
        }
        act.codeEditor.syncEditorOffset();
        syncExplorerResizeHandleVisibility();
        act.codeEditor.syncEditorResizeHandleVisibility();
        refreshExplorerPanelBackground();
        act.codeEditor.refreshEditorPanelBackground();
        act.rebudgetPanelWidths(act.explorerPanel);
        act.updateCenterPaneInsets();
        act.updateToolPillPosition();
    }

    private void ensureExplorerResizeHandle() {
        if (act.rootLayout == null) return;
        if (act.explorerResizeHandle == null) {
            FrameLayout handle = new FrameLayout(act);
            handle.setClickable(true);
            handle.setFocusable(true);
            handle.setContentDescription("Drag to resize explorer");
            handle.setVisibility(View.INVISIBLE);
            handle.setElevation(0f);
            View pill = new View(act);
            act.explorerResizeGrip = pill;
            FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                    act.dp(MainActivity.CHAT_RESIZE_PILL_W), act.dp(MainActivity.CHAT_RESIZE_PILL_H));
            gripLp.gravity = Gravity.CENTER;
            handle.addView(pill, gripLp);
            handle.setOnTouchListener((v, event) -> {
                if (act.explorerPanel == null || act.explorerCollapsed) return false;
                explorerResizeLastRawX = event.getRawX();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        explorerPanelDragging = false;
                        act.explorerResizing = true;
                        explorerResizeStartWidth = explorerPanelWidth();
                        explorerResizePendingWidth = explorerResizeStartWidth;
                        explorerResizeStartRawX = explorerResizeLastRawX;
                        setExplorerResizeHandleVisible(true);
                        positionExplorerResizeHandle();
                        if (act.explorerResizeHandle != null) act.explorerResizeHandle.bringToFront();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (!act.explorerResizing) return true;
                        float dx = event.getRawX() - explorerResizeStartRawX;
                        int delta = act.explorerOnLeft ? Math.round(dx) : Math.round(-dx);
                        applyExplorerPanelWidth(explorerResizeStartWidth + delta);
                        explorerResizePendingWidth = explorerPanelWidth();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if (act.explorerResizing) {
                            act.explorerResizing = false;
                            applyExplorerPanelWidth(explorerResizePendingWidth);
                            act.persistence.scheduleSave();
                            syncExplorerResizeHandleVisibility();
                        }
                        return true;
                    default:
                        return false;
                }
            });
            act.explorerResizeHandle = handle;
        }
        act.refreshSidePanelHandleLook(act.explorerResizeHandle, act.explorerResizeGrip);
        syncExplorerResizeHandleVisibility();
    }

    private void setExplorerResizeHandleVisible(boolean visible) {
        if (act.explorerResizeHandle == null) return;
        act.explorerResizeHandle.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (act.explorerResizeGrip != null) {
            act.explorerResizeGrip.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private boolean isExplorerFullyExpanded() {
        if (act.explorerCollapsed || act.explorerPanel == null || explorerPanelDragging) return false;
        return Math.abs(act.explorerPanel.getTranslationX()) < 0.5f;
    }

    void syncExplorerResizeHandleVisibility() {
        boolean show = !act.compactScreen() && (act.explorerResizing || isExplorerFullyExpanded());
        setExplorerResizeHandleVisible(show);
        if (show) positionExplorerResizeHandle();
    }

    private void positionExplorerResizeHandle() {
        if (act.rootLayout == null || act.explorerResizeHandle == null) return;
        if (act.explorerCollapsed || act.explorerPanel == null) {
            setExplorerResizeHandleVisible(false);
            return;
        }
        int hw = act.dp(MainActivity.CHAT_RESIZE_HANDLE_W);
        int hh = act.dp(MainActivity.CHAT_RESIZE_HANDLE_H);
        int rootW = act.rootLayout.getWidth();
        int rootH = act.rootLayout.getHeight();
        if (rootW <= 0 || rootH <= 0) {
            act.rootLayout.post(this::positionExplorerResizeHandle);
            return;
        }
        int explorerW = explorerPanelWidth();
        float tx = act.explorerPanel.getTranslationX();
        int edgeX = act.explorerOnLeft
                ? Math.round(explorerW + tx)
                : Math.round(rootW - explorerW + tx);
        int left = Math.max(0, Math.min(edgeX - hw / 2, rootW - hw));
        int top = Math.max(0, (rootH - hh) / 2);

        if (explorerResizeHandleLp == null) {
            explorerResizeHandleLp = new FrameLayout.LayoutParams(hw, hh);
        }
        explorerResizeHandleLp.width = hw;
        explorerResizeHandleLp.height = hh;
        explorerResizeHandleLp.gravity = Gravity.TOP | Gravity.START;
        explorerResizeHandleLp.leftMargin = left;
        explorerResizeHandleLp.topMargin = top;
        explorerResizeHandleLp.rightMargin = 0;
        explorerResizeHandleLp.bottomMargin = 0;

        if (act.explorerResizeHandle.getParent() != act.rootLayout) {
            if (act.explorerResizeHandle.getParent() instanceof ViewGroup) {
                ((ViewGroup) act.explorerResizeHandle.getParent()).removeView(act.explorerResizeHandle);
            }
            act.rootLayout.addView(act.explorerResizeHandle, explorerResizeHandleLp);
        } else {
            act.explorerResizeHandle.setLayoutParams(explorerResizeHandleLp);
        }
        if (act.explorerResizeGrip != null) {
            FrameLayout.LayoutParams gripLp = (FrameLayout.LayoutParams) act.explorerResizeGrip.getLayoutParams();
            if (gripLp == null) {
                gripLp = new FrameLayout.LayoutParams(act.dp(MainActivity.CHAT_RESIZE_PILL_W), act.dp(MainActivity.CHAT_RESIZE_PILL_H));
            }
            gripLp.width = act.dp(MainActivity.CHAT_RESIZE_PILL_W);
            gripLp.height = act.dp(MainActivity.CHAT_RESIZE_PILL_H);
            gripLp.gravity = Gravity.CENTER;
            gripLp.leftMargin = 0;
            gripLp.rightMargin = 0;
            act.explorerResizeGrip.setLayoutParams(gripLp);
        }
        act.refreshSidePanelHandleLook(act.explorerResizeHandle, act.explorerResizeGrip);
        if (act.explorerResizeHandle.getVisibility() == View.VISIBLE) {
            act.explorerResizeHandle.bringToFront();
        }
    }

    void toggleFolderExplorer() {
        if (act.rootLayout == null) return;
        ensureExplorerPanel();
        if (act.explorerCollapsed) {
            showFolderExplorer();
        } else {
            hideFolderExplorer();
        }
    }

    private void showFolderExplorer() {
        act.closeOtherPanelsIfCompact("explorer");
        ensureExplorerPanel();
        if (act.folderExplorer != null) act.folderExplorer.onShown();
        animateExplorerSlide(false);
    }

    void hideFolderExplorer() {
        if (act.explorerPanel == null) return;
        if (act.folderExplorer != null) act.folderExplorer.persistScroll();
        animateExplorerSlide(true);
    }

    void ensureExplorerPanel() {
        if (act.rootLayout == null) return;
        FolderExplorerView.ensurePrefsLoaded(act);
        if (act.explorerPanel == null) {
            // Intercept horizontal swipes inside the panel to slide it (same as chat).
            act.explorerPanel = new FrameLayout(act) {
                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    if (act.explorerCollapsed || explorerPanelDragging) return false;
                    final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            explorerDragStartRawX = ev.getRawX();
                            explorerDragStartRawY = ev.getRawY();
                            explorerSlideStartRawX = ev.getRawX();
                            explorerSlideStartTx = getTranslationX();
                            return false;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = ev.getRawX() - explorerDragStartRawX;
                            float dy = ev.getRawY() - explorerDragStartRawY;
                            if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
                            if (Math.abs(dx) <= Math.abs(dy) * 1.15f) return false;
                            boolean towardClose = act.explorerOnLeft ? dx < 0f : dx > 0f;
                            if (!towardClose) return false;
                            explorerPanelDragging = true;
                            setExplorerResizeHandleVisible(false);
                            if (act.explorerSlideAnim != null) act.explorerSlideAnim.cancel();
                                    if (act.explorerVelocityTracker != null) act.explorerVelocityTracker.recycle();
                            act.explorerVelocityTracker = VelocityTracker.obtain();
                            act.explorerVelocityTracker.addMovement(ev);
                            return true;
                        }
                        default:
                            return explorerPanelDragging;
                    }
                }

                @Override
                public boolean onTouchEvent(MotionEvent ev) {
                    if (!explorerPanelDragging) return super.onTouchEvent(ev);
                    if (act.explorerVelocityTracker != null) act.explorerVelocityTracker.addMovement(ev);
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_MOVE:
                            setExplorerTranslation(
                                    explorerSlideStartTx + (ev.getRawX() - explorerSlideStartRawX));
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            float vx = 0f;
                            if (act.explorerVelocityTracker != null) {
                                act.explorerVelocityTracker.computeCurrentVelocity(1000);
                                vx = act.explorerVelocityTracker.getXVelocity();
                                act.explorerVelocityTracker.recycle();
                                act.explorerVelocityTracker = null;
                            }
                            snapExplorerFromGesture(vx);
                            explorerPanelDragging = false;
                            syncExplorerResizeHandleVisibility();
                            act.codeEditor.syncEditorResizeHandleVisibility();
                            return true;
                        }
                        default:
                            return true;
                    }
                }
            };
            act.explorerPanel.setBackgroundColor(0x00000000);
            act.explorerPanel.setElevation(0f);
            act.explorerPanel.setClickable(true);
            act.explorerPanel.setClipChildren(true);
            act.explorerPanel.setClipToPadding(true);

            explorerScrim = new View(act);
            explorerScrim.setClickable(false);
            explorerScrim.setFocusable(false);
            act.explorerPanel.addView(explorerScrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            act.folderExplorer = new FolderExplorerView(act, act.workspace, new FolderExplorerView.Listener() {
                @Override
                public void onOpenFile(String path) {
                    act.codeEditor.openScriptEditor(path);
                }

                @Override
                public void onOpenPdfDocument(String path) {
                    act.documents.openPdfDocument(path);
                }

                @Override
                public void onOpenViz(String path) {
                    act.codeEditor.openVizFile(path);
                }

                @Override
                public void onCreatePdfDocument() {
                    act.documents.promptCreatePdfDocument();
                }

                @Override
                public void onFilesMutated() {
                    act.refreshFileViews();
                }

                @Override
                public void onPathChanged(String from, String to) {
                    act.canvasAgent.onWorkspacePathChanged(from, to);
                }

                @Override
                public void onUploadInto(String dir) {
                    act.transfers.startUploadInto(dir);
                }

                @Override
                public void onDownloadFile(String path) {
                    act.transfers.downloadWorkspaceFile(path);
                }

                @Override
                public void onExplorerMessage(String message) {
                    if (message != null && !message.isEmpty()) {
                        act.snackbar(message, false);
                    }
                }

                @Override
                public void onAddImageToCanvas(String path) {
                    act.canvasDrops.addAssetToCanvasCenter(path, false);
                }

                @Override
                public void onDismiss() {
                    hideFolderExplorer();
                }
            });
            act.folderExplorer.setTopInset(act.statusBarHeight());
            act.explorerPanel.addView(act.folderExplorer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            act.explorerEdgeDrag = new View(act);
            act.explorerEdgeDragLp = new FrameLayout.LayoutParams(
                    act.dp(EXPLORER_EDGE_DRAG_W), ViewGroup.LayoutParams.MATCH_PARENT);
            attachExplorerSlideDrag(act.explorerEdgeDrag);
        }

        if (act.explorerPanelLp == null) {
            act.explorerPanelLp = new FrameLayout.LayoutParams(
                    explorerPanelWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
        }
        act.explorerPanelLp.width = explorerPanelWidth();
        act.explorerPanelLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        act.explorerPanelLp.gravity = act.explorerOnLeft ? Gravity.START : Gravity.END;

        if (act.explorerPanel.getParent() != act.rootLayout) {
            if (act.explorerPanel.getParent() instanceof ViewGroup) {
                ((ViewGroup) act.explorerPanel.getParent()).removeView(act.explorerPanel);
            }
            act.rootLayout.addView(act.explorerPanel, act.explorerPanelLp);
        } else {
            act.explorerPanel.setLayoutParams(act.explorerPanelLp);
        }

        if (act.explorerEdgeDrag != null) {
            // Outer screen edge — open when collapsed, close when expanded (same as chat).
            act.explorerEdgeDragLp.gravity = act.explorerOnLeft ? Gravity.START : Gravity.END;
            if (act.explorerEdgeDrag.getParent() != act.rootLayout) {
                if (act.explorerEdgeDrag.getParent() instanceof ViewGroup) {
                    ((ViewGroup) act.explorerEdgeDrag.getParent()).removeView(act.explorerEdgeDrag);
                }
                act.rootLayout.addView(act.explorerEdgeDrag, act.explorerEdgeDragLp);
            } else {
                act.explorerEdgeDrag.setLayoutParams(act.explorerEdgeDragLp);
            }
        }

        if (act.folderExplorer != null) {
            act.folderExplorer.applyTheme(
                    act.M3_SURFACE_CONTAINER,
                    act.M3_ON_SURFACE,
                    act.M3_ON_SURFACE_VARIANT,
                    act.M3_PRIMARY,
                    act.M3_PRIMARY_CONTAINER,
                    act.M3_ON_PRIMARY_CONTAINER,
                    act.M3_OUTLINE_VARIANT);
            act.folderExplorer.setTopInset(act.statusBarHeight());
        }
        refreshExplorerPanelBackground();
        ensureExplorerResizeHandle();
        syncExplorerResizeHandleVisibility();
        act.chatView.layoutChatOverlayViews();
    }

    void refreshExplorerPanelBackground() {
        if (explorerScrim == null) return;
        boolean editorStacked = !act.editorCollapsed && act.codeEditor.visibleEditorWidthPx() > act.dp(8);
        float[] radii = act.sidePanelCornerRadii(
                act.explorerOnLeft,
                /* roundCanvasEdge */ !editorStacked,
                /* roundScreenEdge */ false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(act.M3_SURFACE_CONTAINER);
        g.setCornerRadii(radii);
        explorerScrim.setBackground(g);
        if (act.explorerPanel != null) {
            act.explorerPanel.setBackgroundColor(0x00000000);
            applyExplorerPanelClip();
        }
    }

    private void applyExplorerPanelClip() {
        if (act.folderExplorer == null) return;
        act.folderExplorer.setClipToOutline(true);
        act.folderExplorer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w <= 0 || h <= 0) return;
                boolean editorStacked = !act.editorCollapsed && act.codeEditor.visibleEditorWidthPx() > act.dp(8);
                float[] radii = act.sidePanelCornerRadii(act.explorerOnLeft, !editorStacked, false);
                Path path = new Path();
                path.addRoundRect(0, 0, w, h, radii, Path.Direction.CW);
                outline.setPath(path);
            }
        });
        act.folderExplorer.invalidateOutline();
    }

    void applyExplorerCollapsed(boolean collapsed) {
        act.explorerCollapsed = collapsed;
        // Budgets depend on which panels are open.
        act.rebudgetPanelWidths(act.explorerPanel);
        if (act.explorerSlideAnim != null) act.explorerSlideAnim.cancel();
        ensureExplorerPanel();
        if (act.explorerPanel != null) {
            act.explorerPanel.setTranslationX(collapsed ? explorerClosedTranslation() : 0f);
            act.explorerPanel.setVisibility(View.VISIBLE);
        }
        if (act.openFileButton != null) act.applyIconSelected(act.openFileButton, !collapsed);
        act.codeEditor.syncEditorOffset();
        if (collapsed) {
            setExplorerResizeHandleVisible(false);
        } else {
            syncExplorerResizeHandleVisibility();
        }
        act.codeEditor.syncEditorResizeHandleVisibility();
        if (act.centerPane != null) act.centerPane.setTranslationX(0f);
        act.updateCenterPaneInsets();
        act.updateToolPillPosition();
    }

    float explorerClosedTranslation() {
        return act.explorerOnLeft ? -explorerPanelWidth() : explorerPanelWidth();
    }

    private void animateExplorerSlide(boolean collapsedEnd) {
        if (act.explorerPanel == null) return;
        if (act.explorerSlideAnim != null) act.explorerSlideAnim.cancel();
        int oldLeft = act.centerPane != null ? act.centerPane.getPaddingLeft() : 0;
        int oldRight = act.centerPane != null ? act.centerPane.getPaddingRight() : 0;
        act.explorerCollapsed = collapsedEnd;
        // Budgets depend on which panels are open.
        act.rebudgetPanelWidths(act.explorerPanel);
        // Keep the editor glued to the explorer's live edge for the whole slide —
        // a one-shot sync at the start used the still-closed width (0) and left the
        // editor under the explorer until something else nudged it.
        act.explorerSlideAnim = act.slidePanelTo(
                act.explorerPanel, collapsedEnd ? explorerClosedTranslation() : 0f,
                act.codeEditor::syncEditorOffset,
                this::syncExplorerResizeHandleVisibility);
        act.codeEditor.syncEditorOffset();
        if (collapsedEnd) {
            setExplorerResizeHandleVisible(false);
        } else {
            syncExplorerResizeHandleVisibility();
        }
        act.codeEditor.syncEditorResizeHandleVisibility();
        if (act.openFileButton != null) act.applyIconSelected(act.openFileButton, !collapsedEnd);
        refreshExplorerPanelBackground();
        act.codeEditor.refreshEditorPanelBackground();
        act.updateCenterPaneInsets();
        act.persistence.scheduleSave();
    }

    private void attachExplorerSlideDrag(View dragRegion) {
        final int slop = ViewConfiguration.get(act).getScaledTouchSlop();
        dragRegion.setOnTouchListener((v, event) -> {
            if (act.explorerPanel == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (act.explorerSlideAnim != null) act.explorerSlideAnim.cancel();
                    if (act.explorerVelocityTracker != null) act.explorerVelocityTracker.recycle();
                    act.explorerVelocityTracker = VelocityTracker.obtain();
                    act.explorerVelocityTracker.addMovement(event);
                    explorerDragStartRawX = event.getRawX();
                    explorerDragStartRawY = event.getRawY();
                    explorerSlideStartRawX = event.getRawX();
                    explorerSlideStartTx = act.explorerPanel.getTranslationX();
                    explorerPanelDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (act.explorerVelocityTracker != null) act.explorerVelocityTracker.addMovement(event);
                    float dx = event.getRawX() - explorerDragStartRawX;
                    float dy = event.getRawY() - explorerDragStartRawY;
                    if (!explorerPanelDragging) {
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                        if (Math.abs(dx) <= Math.abs(dy) * 1.2f) return true;
                        explorerPanelDragging = true;
                        setExplorerResizeHandleVisible(false);
                    }
                    float next = explorerSlideStartTx + (event.getRawX() - explorerSlideStartRawX);
                    setExplorerTranslation(next);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float vx = 0f;
                    if (act.explorerVelocityTracker != null) {
                        act.explorerVelocityTracker.addMovement(event);
                        act.explorerVelocityTracker.computeCurrentVelocity(1000);
                        vx = act.explorerVelocityTracker.getXVelocity();
                        act.explorerVelocityTracker.recycle();
                        act.explorerVelocityTracker = null;
                    }
                    if (explorerPanelDragging) snapExplorerFromGesture(vx);
                    explorerPanelDragging = false;
                    syncExplorerResizeHandleVisibility();
                    act.codeEditor.syncEditorResizeHandleVisibility();
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private void setExplorerTranslation(float tx) {
        if (act.explorerPanel == null) return;
        float closed = explorerClosedTranslation();
        if (act.explorerOnLeft) {
            if (tx > 0f) tx = 0f;
            if (tx < closed) tx = closed;
        } else {
            if (tx < 0f) tx = 0f;
            if (tx > closed) tx = closed;
        }
        act.explorerPanel.setTranslationX(tx);
        if (Math.abs(tx) > 0.5f) {
            setExplorerResizeHandleVisible(false);
        }
        act.codeEditor.syncEditorOffset();
        positionExplorerResizeHandle();
        act.updateToolPillPosition();
    }

    private void snapExplorerFromGesture(float velocityX) {
        float closed = explorerClosedTranslation();
        float tx = act.explorerPanel != null ? act.explorerPanel.getTranslationX() : closed;
        float progress;
        if (Math.abs(closed) < 1f) {
            progress = act.explorerCollapsed ? 1f : 0f;
        } else if (act.explorerOnLeft) {
            progress = tx / closed;
        } else {
            progress = tx / closed;
        }
        boolean close;
        float fling = act.explorerOnLeft ? -velocityX : velocityX;
        if (Math.abs(fling) > 800f) {
            close = fling > 0f;
        } else {
            close = progress > 0.45f;
        }
        animateExplorerSlide(close);
    }
}
