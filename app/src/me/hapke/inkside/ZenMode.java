package me.hapke.inkside;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

/**
 * Zen mode: hides every panel but the pages.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class ZenMode {
    private final MainActivity act;

    private final java.util.HashMap<View, Float> zenSavedZ = new java.util.HashMap<>();
    private ViewOutlineProvider zenSavedOutline;
    private android.window.OnBackInvokedCallback zenBackCallback;

    ZenMode(MainActivity act) {
        this.act = act;
    }

    /**
     * Nothing but the canvas. Rather than hiding each piece of chrome — which half the
     * app re-shows on its own schedule — the canvas pane is lifted above everything in
     * Z order, and inside it the canvas above its own pills. Anything drawn on the
     * canvas itself (inline text editor, live artifacts) is lifted with it. Back exits.
     */
    void enterZenMode() {
        if (act.zenMode || act.centerPane == null || act.canvas == null || act.rootLayout == null) return;
        act.zenMode = true;
        act.instantChat.syncMiniChatZ();
        act.overflowMenu.dismissOverflowMenu();
        act.favorites.dismissFavoritesMenu();
        act.hideSoftKeyboard();

        float lift = act.dp(MainActivity.ZEN_LIFT_DP);
        zenLift(act.centerPane, lift);
        // No shadow around a pane that now fills the screen.
        zenSavedOutline = act.centerPane.getOutlineProvider();
        act.centerPane.setOutlineProvider(null);
        ViewGroup pane = (ViewGroup) act.centerPane;
        for (int i = 0; i < pane.getChildCount(); i++) zenLiftPaneChild(pane.getChildAt(i));
        pane.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                if (act.zenMode) zenLiftPaneChild(child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                Float z = zenSavedZ.remove(child);
                if (z != null) child.setTranslationZ(z);
            }
        });

        // A selection made before entering keeps its actions.
        act.refreshSelectionActions(act.canvas != null && act.canvas.hasActiveSelection());
        if (Build.VERSION.SDK_INT >= 33) {
            zenBackCallback = this::exitZenMode;
            act.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, zenBackCallback);
        }
    }

    void exitZenMode() {
        if (!act.zenMode) return;
        act.zenMode = false;
        act.instantChat.syncMiniChatZ();
        if (zenBackCallback != null && Build.VERSION.SDK_INT >= 33) {
            act.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(zenBackCallback);
        }
        zenBackCallback = null;
        if (act.centerPane instanceof ViewGroup) {
            ((ViewGroup) act.centerPane).setOnHierarchyChangeListener(null);
        }
        for (java.util.Map.Entry<View, Float> e : zenSavedZ.entrySet()) {
            e.getKey().setTranslationZ(e.getValue());
        }
        zenSavedZ.clear();
        if (act.centerPane != null) {
            act.centerPane.setOutlineProvider(
                    zenSavedOutline != null ? zenSavedOutline : ViewOutlineProvider.BACKGROUND);
        }
        zenSavedOutline = null;
        act.refreshSelectionActions(act.canvas != null && act.canvas.hasActiveSelection());
        act.updateToolPillPosition();
    }

    /** Canvas chrome stays under the canvas; the canvas and what it hosts go over. */
    private void zenLiftPaneChild(View child) {
        if (child == null) return;
        if (child == act.navPill || child == act.toolScroll) {
            return;
        }
        float lift = act.dp(MainActivity.ZEN_LIFT_DP);
        // Selection actions and the text style bar ride above the canvas, so a
        // selection can still be cut, copied, recoloured… in focus mode.
        boolean selectionChrome = child == act.selectionActions || child == act.textStyleFloatingBar;
        zenLift(child, child == act.canvas ? lift : lift + act.dp(selectionChrome ? 4 : 1));
    }

    private void zenLift(View v, float z) {
        if (!zenSavedZ.containsKey(v)) zenSavedZ.put(v, v.getTranslationZ());
        v.setTranslationZ(z);
    }
}
