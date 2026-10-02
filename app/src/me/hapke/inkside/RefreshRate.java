package me.hapke.inkside;

import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.util.ArrayList;

/**
 * Keeps the display at its high refresh rate.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class RefreshRate {
    private final MainActivity act;

    private boolean refreshRateKeepaliveActive = false;
    private float lockedRefreshHz = 0f;
    private final Runnable refreshRateKeepalive = new Runnable() {
        @Override
        public void run() {
            if (act.isFinishing()) return;
            if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return;
            requestHighRefreshRate();
            if (refreshRateKeepaliveActive) {
                // 5s is enough to fight SF demotion; 1s re-voted and flashed on stylus proximity.
                act.saveHandler.postDelayed(this, 5000L);
            }
        }
    };

    RefreshRate(MainActivity act) {
        this.act = act;
    }

    void startRefreshRateKeepalive() {
        requestHighRefreshRate();
        if (refreshRateKeepaliveActive) return;
        refreshRateKeepaliveActive = true;
        act.saveHandler.removeCallbacks(refreshRateKeepalive);
        act.saveHandler.post(refreshRateKeepalive);
    }

    void stopRefreshRateKeepalive() {
        refreshRateKeepaliveActive = false;
        lockedRefreshHz = 0f;
        act.saveHandler.removeCallbacks(refreshRateKeepalive);
    }

    /**
     * Hard-lock to a stable high refresh mode (prefer 120Hz).
     * Soft "max rate" votes let SurfaceFlinger fall through alternate rates to 30Hz.
     */
    void requestHighRefreshRate() {
        Window window = act.getWindow();
        if (window == null) return;

        Display display = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display = act.getDisplay();
        }
        if (display == null) {
            DisplayManager dm = act.getSystemService(DisplayManager.class);
            if (dm != null) display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        }
        if (display == null) return;

        Display.Mode best = pickStableHighRefreshMode(display);
        final float frameHz = best != null ? best.getRefreshRate() : 120f;

        // Re-applying the same mode every keepalive can flash on stylus proximity (VRR).
        if (Math.abs(frameHz - lockedRefreshHz) < 0.5f) {
            return;
        }

        WindowManager.LayoutParams params = window.getAttributes();
        if (best != null) {
            params.preferredDisplayModeId = best.getModeId();
        }
        params.preferredRefreshRate = frameHz;
        window.setAttributes(params);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setPreferMinimalPostProcessing(true);
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        window.getClass()
                                .getMethod("setFrameRate", float.class, int.class, int.class)
                                .invoke(
                                        window,
                                        frameHz,
                                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                        // ONLY_IF_SEAMLESS: ALWAYS flashes the whole display
                                        // when the stylus approaches on VRR panels (Pad).
                                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                    } catch (NoSuchMethodException e) {
                        window.getClass()
                                .getMethod("setFrameRate", float.class, int.class)
                                .invoke(
                                        window,
                                        frameHz,
                                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                    }
                } else {
                    window.getClass()
                            .getMethod("setFrameRate", float.class, int.class)
                            .invoke(
                                    window,
                                    frameHz,
                                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                }
            } catch (Throwable ignored) {
            }
        }

        View decor = window.getDecorView();
        if (decor != null) {
            lockViewRootSurface(decor, frameHz);
            if (act.canvas != null) lockViewRootSurface(act.canvas, frameHz);
        }
        lockedRefreshHz = frameHz;
    }

    /**
     * Prefer ~120Hz at the current resolution. 144Hz modes on Xiaomi often expose
     * 30Hz alternates that SurfaceFlinger will pick under load.
     */
    private static Display.Mode pickStableHighRefreshMode(Display display) {
        Display.Mode current = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        if (current == null || modes == null || modes.length == 0) return null;

        java.util.ArrayList<Display.Mode> sameRes = new java.util.ArrayList<>();
        for (Display.Mode mode : modes) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                sameRes.add(mode);
            }
        }
        if (sameRes.isEmpty()) {
            for (Display.Mode mode : modes) sameRes.add(mode);
        }

        Display.Mode exact120 = null;
        Display.Mode bestAtLeast90 = null;
        for (Display.Mode mode : sameRes) {
            float hz = mode.getRefreshRate();
            if (hz >= 119f && hz <= 121f) {
                exact120 = mode;
            }
            if (hz >= 90f && hz <= 121f) {
                if (bestAtLeast90 == null || hz > bestAtLeast90.getRefreshRate()) {
                    bestAtLeast90 = mode;
                }
            }
        }
        if (exact120 != null) return exact120;
        if (bestAtLeast90 != null) return bestAtLeast90;

        // Last resort: highest available (still better than 30).
        Display.Mode best = null;
        for (Display.Mode mode : sameRes) {
            if (best == null || mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        return best;
    }

    /** Pin ViewRootImpl's Surface so SF cannot silently switch to 30Hz. */
    private static void lockViewRootSurface(View view, float hz) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || view == null) return;
        try {
            Surface surface = findViewRootSurface(view);
            if (surface != null && surface.isValid()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    surface.setFrameRate(
                            hz,
                            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                } else {
                    surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Surface findViewRootSurface(View view) {
        try {
            Object viewRoot = view.getRootView().getParent();
            if (viewRoot == null) {
                java.lang.reflect.Method getViewRootImpl =
                        View.class.getDeclaredMethod("getViewRootImpl");
                getViewRootImpl.setAccessible(true);
                viewRoot = getViewRootImpl.invoke(view);
            }
            if (viewRoot == null) return null;

            try {
                java.lang.reflect.Field surfaceField =
                        viewRoot.getClass().getDeclaredField("mSurface");
                surfaceField.setAccessible(true);
                Object s = surfaceField.get(viewRoot);
                if (s instanceof Surface) return (Surface) s;
            } catch (NoSuchFieldException ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
