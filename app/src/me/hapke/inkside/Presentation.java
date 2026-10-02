package me.hapke.inkside;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/**
 * Presentation mode: the current page on a second display, paged with the pen buttons.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class Presentation {
    private final MainActivity act;

    // ---- Presentation: double-press the pen buttons to change page ---------------------

    private static final long PEN_DOUBLE_PRESS_MS = 400L;
    /** Last press time per role (index 1 primary, 2 secondary). */
    private final long[] penLastPressAt = new long[3];
    /** The release of a press that was used as a double press is swallowed too. */
    private final boolean[] penSwallowUp = new boolean[3];

    Presentation(MainActivity act) {
        this.act = act;
    }

    /**
     * While presenting: primary pressed twice quickly → next page, secondary twice →
     * previous page. Returns true when the edge was used for that (and must not reach
     * the eraser / lasso roles). The first press still behaves normally; a secondary
     * first press switched to the lasso, so that is undone.
     */
    boolean handlePresentationDoublePress(int role, boolean down) {
        if (role != 1 && role != 2) return false;
        if (!down) {
            if (penSwallowUp[role]) {
                penSwallowUp[role] = false;
                return true;
            }
            return false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        boolean isDouble = now - penLastPressAt[role] <= PEN_DOUBLE_PRESS_MS;
        penLastPressAt[role] = isDouble ? 0L : now;
        if (!isDouble || act.canvas == null || !act.canvas.hasDocument()) return false;
        penSwallowUp[role] = true;
        if (role == 2) act.penTools.selectPencil(act.selectedColorIndex, false);
        int count = act.canvas.getDocumentPageCount();
        int page = act.canvas.currentPageIndex();
        int target = Math.max(0, Math.min(count - 1, page + (role == 1 ? 1 : -1)));
        if (target != page) {
            act.canvas.centerDocumentPage(target);
            schedulePresentationRender(true);
        }
        if (act.canvas != null) act.canvas.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        return true;
    }

    private android.hardware.display.DisplayManager.DisplayListener presentationDisplayListener;
    /** Page shown on the second screen, so camera moves within it cost nothing. */
    private int presentedPage = -1;
    private boolean presentRenderInFlight;
    private boolean presentRenderAgain;
    private final Runnable presentRender = this::renderPresentationSlide;
    /** Settle time before re-rendering, so a scroll or a burst of edits renders once. */
    private static final long PRESENT_DEBOUNCE_MS = 120L;
    /** Slide refresh interval while a stroke is being drawn. */
    private static final long PRESENT_LIVE_MS = 30L;

    void togglePresentation() {
        if (act.presentation != null) {
            stopPresentation(null);
            return;
        }
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
        android.view.Display[] displays = dm != null
                ? dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                : new android.view.Display[0];
        if (displays.length == 0) {
            new M3Dialog.Builder(act)
                    .setTitle("No second screen")
                    .setMessage("Connect a display over USB-C/HDMI, or cast the screen, "
                            + "then choose Present again. The page in the middle of the "
                            + "canvas is shown there as a full slide.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        startPresentation(displays[0]);
    }

    private void startPresentation(android.view.Display display) {
        SlidePresentation p = new SlidePresentation(act, display);
        try {
            p.show();
        } catch (RuntimeException e) {
            act.conversations.appendChat("warn", "could not present on " + display.getName() + ": " + e.getMessage());
            return;
        }
        act.presentation = p;
        presentedPage = -1;
        p.setOnDismissListener(d -> {
            if (act.presentation == p) stopPresentation(null);
        });
        final int displayId = display.getDisplayId();
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
        presentationDisplayListener = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int id) {}

            @Override
            public void onDisplayChanged(int id) {
                if (id == displayId) schedulePresentationRender(true);
            }

            @Override
            public void onDisplayRemoved(int id) {
                if (id == displayId) stopPresentation("Second screen disconnected");
            }
        };
        if (dm != null) dm.registerDisplayListener(presentationDisplayListener, act.saveHandler);
        if (act.canvas != null) act.canvas.setSceneChangedHook(this::schedulePresentationRender);
        schedulePresentationRender(true);
        act.snackbar("Presenting on " + display.getName(), false);
    }

    void stopPresentation(String why) {
        SlidePresentation p = act.presentation;
        act.presentation = null;
        presentedPage = -1;
        act.saveHandler.removeCallbacks(presentRender);
        if (act.canvas != null) act.canvas.setSceneChangedHook(null);
        if (presentationDisplayListener != null) {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) dm.unregisterDisplayListener(presentationDisplayListener);
            presentationDisplayListener = null;
        }
        if (p != null) {
            p.setOnDismissListener(null);
            try {
                p.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
        if (why != null && !act.isDead()) {
            act.snackbar(why, false);
        }
    }

    /** @param content false = only the camera moved; nothing to do unless the page changed. */
    private void schedulePresentationRender(boolean content) {
        if (act.presentation == null) return;
        if (!content && act.canvas != null && act.canvas.currentPageIndex() == presentedPage) return;
        if (act.canvas != null && act.canvas.isInkInProgress()) {
            // Live drawing: throttle, don't debounce — a debounce would hold every frame
            // back until the pen lifts. renderPresentationSlide chains while one is in flight.
            if (!act.saveHandler.hasCallbacks(presentRender)) {
                act.saveHandler.postDelayed(presentRender, PRESENT_LIVE_MS);
            }
            return;
        }
        act.saveHandler.removeCallbacks(presentRender);
        act.saveHandler.postDelayed(presentRender, PRESENT_DEBOUNCE_MS);
    }

    private void renderPresentationSlide() {
        final SlidePresentation p = act.presentation;
        if (p == null || act.canvas == null) return;
        if (presentRenderInFlight) {
            presentRenderAgain = true;
            return;
        }
        final int page = act.canvas.currentPageIndex();
        int[] size = p.screenSize();
        presentRenderInFlight = true;
        act.canvas.renderPageForPresentation(page, size[0], size[1], bmp -> {
            presentRenderInFlight = false;
            if (act.presentation != p) {
                if (bmp != null) bmp.recycle();
                return;
            }
            presentedPage = page;
            p.setSlide(bmp);
            if (presentRenderAgain) {
                presentRenderAgain = false;
                schedulePresentationRender(true);
            }
        });
    }
}
