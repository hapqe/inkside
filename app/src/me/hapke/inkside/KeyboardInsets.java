package me.hapke.inkside;

import android.content.Context;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;

import java.util.List;

/**
 * How far the soft keyboard reaches over a view's bottom edge, kept up to date while the
 * keyboard slides in and out (frame by frame, through a {@link WindowInsetsAnimation}
 * callback), so what sits above it moves with it instead of jumping once it has settled.
 *
 * The overlap is measured, not assumed: 0 when the window was already resized for the
 * keyboard, the keyboard's full height when it was not (the app runs edge to edge without
 * system bars, where adjustResize often does nothing).
 */
final class KeyboardInsets {
    interface Listener {
        /** Pixels of {@code root}'s bottom the keyboard covers now; 0 when it is away. */
        void onOverlap(int px);
    }

    /** Reports {@code root}'s keyboard overlap to {@code listener} from now on. */
    static void follow(View root, Listener listener) {
        final int[] last = {-1};
        final boolean[] animating = {false};
        Runnable settle = () -> report(root, root.getRootWindowInsets(), listener, last);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (!animating[0]) report(root, insets, listener, last);
            return insets;
        });
        root.setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override
                    public void onPrepare(WindowInsetsAnimation animation) {
                        if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) animating[0] = true;
                    }

                    @Override
                    public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) {
                        if (animating[0]) report(root, insets, listener, last);
                        return insets;
                    }

                    @Override
                    public void onEnd(WindowInsetsAnimation animation) {
                        if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                        animating[0] = false;
                        root.post(settle);
                    }
                });
        // The resize for the keyboard can be laid out after its insets arrive: measure again
        // once the root has its new size.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot && !animating[0]) v.post(settle);
        });
    }

    private static void report(View root, WindowInsets insets, Listener listener, int[] last) {
        int px = overlap(root, insets);
        if (px == last[0]) return;
        last[0] = px;
        listener.onOverlap(px);
    }

    /** How much of {@code root}'s bottom the keyboard in {@code insets} covers. */
    static int overlap(View root, WindowInsets insets) {
        if (insets == null || root.getHeight() <= 0) return 0;
        int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
        if (imeBottom <= 0) return 0;
        // Window metrics are the full frame the IME inset is measured against, even when
        // adjustResize has shrunk the view hierarchy.
        WindowManager wm = (WindowManager) root.getContext().getSystemService(Context.WINDOW_SERVICE);
        int windowBottom = wm.getCurrentWindowMetrics().getBounds().bottom;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        int rootBottom = loc[1] + root.getHeight();
        return Math.max(0, rootBottom - (windowBottom - imeBottom));
    }

    private KeyboardInsets() {}
}
