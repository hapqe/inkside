package me.hapke.inkside;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import java.util.List;

/**
 * Live HTML artifacts shown as web views on top of their canvas images.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class ArtifactOverlays {
    private final MainActivity act;

    /** Live overlay per placed element, keyed by the image it belongs to. */
    private final java.util.IdentityHashMap<CanvasImage, ArtifactHost> artifactOverlays =
            new java.util.IdentityHashMap<>();
    /** The element the user has stepped into, or null. */
    private CanvasImage enteredArtifact;
    private View artifactScrim;
    private TextView artifactDoneChip;

    ArtifactOverlays(MainActivity act) {
        this.act = act;
    }

    /**
     * Touch-transparent unless entered.
     *
     * <p>A placed element is an image as far as the user is concerned, so taps, drags and
     * the selection gimbals must reach the canvas underneath — a WebView would otherwise
     * swallow all of it and the element would be the one thing on the page you could not
     * select or move. Double-tapping flips {@code interactive} for as long as the user is
     * working inside it.
     */
    private final class ArtifactHost extends FrameLayout {
        boolean interactive;

        ArtifactHost(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return !interactive;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return false;
        }
    }

    /**
     * Float one WebView over each placed element.
     *
     * <p>The element itself is an ordinary CanvasImage, so selecting, moving,
     * resizing, rotating, deleting and undo are the canvas's existing behaviour and
     * not reimplemented here. All this does is keep a WebView sitting on the image's
     * bounds. The WebView is laid out at the element's CSS viewport and scaled into
     * the slot, so resizing shows more of the page rather than magnifying it.
     */
    void syncArtifactOverlays() {
        if (act.canvas == null || act.centerPane == null) return;
        java.util.List<CanvasImage> live = act.canvas.getLiveImages();
        java.util.IdentityHashMap<CanvasImage, Boolean> present =
                new java.util.IdentityHashMap<>();
        for (CanvasImage img : live) present.put(img, Boolean.TRUE);
        for (java.util.Iterator<java.util.Map.Entry<CanvasImage, ArtifactHost>> it =
                artifactOverlays.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<CanvasImage, ArtifactHost> e = it.next();
            if (!present.containsKey(e.getKey())) {
                if (enteredArtifact == e.getKey()) exitArtifact();
                ((ViewGroup) act.centerPane).removeView(e.getValue());
                it.remove();
            }
        }
        for (CanvasImage img : live) {
            ArtifactHost host = artifactOverlays.get(img);
            if (host == null) {
                host = buildArtifactOverlay(img);
                artifactOverlays.put(img, host);
                ((ViewGroup) act.centerPane).addView(host, new FrameLayout.LayoutParams(1, 1));
            }
            positionArtifactOverlay(img, host);
        }
    }

    private ArtifactHost buildArtifactOverlay(CanvasImage img) {
        ArtifactHost host = new ArtifactHost(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(10));
        bg.setColor(0xFFFFFFFF);
        host.setBackground(bg);
        host.setClipToOutline(true);
        // Nothing in here may take focus while the element is just sitting on the page.
        // Coming back to the app handed focus to the artifact's last control, and Android
        // scrolled the page to it — the element came back showing its own footer.
        host.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        WebView web = new WebView(act);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUseWideViewPort(false);
        web.getSettings().setLoadWithOverviewMode(false);
        web.setBackgroundColor(0xFFFFFFFF);
        web.setPivotX(0f);
        web.setPivotY(0f);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setTag("web");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (enteredArtifact != img) view.scrollTo(0, 0);
                // Twice, late. The page is "finished" before KaTeX has typeset it and
                // before the maths fonts land, and both change how tall it ends up.
                view.postDelayed(() -> measureArtifactAspect(img, view), 300L);
                view.postDelayed(() -> measureArtifactAspect(img, view), 1200L);
            }
        });
        // Loaded after the first positioning pass, not here: at this point the view is
        // 1x1 and the page would lay itself out for a one-pixel viewport.
        host.addView(web, new FrameLayout.LayoutParams(1, 1));
        return host;
    }

    private void positionArtifactOverlay(CanvasImage img, ArtifactHost host) {
        RectF r = act.canvas.getImageScreenRect(img);
        if (r == null) return;
        float scale = act.canvas.getViewScale();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) host.getLayoutParams();
        // The screen rect is in canvas coordinates, and the canvas sits inside
        // centerPane's padding — but so does this overlay, because a FrameLayout child
        // measures its margins from the parent's content box. Subtracting the padding
        // shifted every element left by the chat panel's width.
        int left = Math.round(r.left);
        int top = Math.round(r.top);
        int w = Math.max(1, Math.round(r.width()));
        int h = Math.max(1, Math.round(r.height()));
        // Only cull against a pane that has actually been measured. Testing against a
        // width of 0 during early layout hid every overlay, and nothing showed them
        // again until the camera happened to move.
        int paneW = act.centerPane.getWidth();
        int paneH = act.centerPane.getHeight();
        boolean measured = paneW > 0 && paneH > 0;
        boolean offscreen = measured
                && (r.right < 0 || r.bottom < 0 || r.left > paneW || r.top > paneH);
        host.setVisibility(offscreen ? View.GONE : View.VISIBLE);
        if (lp.leftMargin != left || lp.topMargin != top || lp.width != w || lp.height != h) {
            lp.leftMargin = left;
            lp.topMargin = top;
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.TOP | Gravity.START;
            host.setLayoutParams(lp);
        }
        host.setRotation(img.rotationDeg);
        View web = host.findViewWithTag("web");
        if (web != null) {
            float css = CodeCanvasView.WEB_CSS_PER_WORLD;
            int vw = Math.max(1, Math.round(img.width * css));
            int vh = Math.max(1, Math.round(img.height * css));
            ViewGroup.LayoutParams wlp = web.getLayoutParams();
            if (wlp.width != vw || wlp.height != vh) {
                wlp.width = vw;
                wlp.height = vh;
                web.setLayoutParams(wlp);
            }
            float shown = scale / css;
            web.setScaleX(shown);
            web.setScaleY(shown);
            if (web instanceof WebView && !Boolean.TRUE.equals(web.getTag(R.id.art_loaded))
                    && vw > 1 && vh > 1) {
                web.setTag(R.id.art_loaded, Boolean.TRUE);
                ((WebView) web).loadUrl(act.artifactUrl(img.livePath));
            }
        }
        if (enteredArtifact == img) positionArtifactDoneChip(host);
    }

    /** A figure taller than this stops reading as a figure and starts eating the page. */
    private static final float ART_MAX_ASPECT = 1.6f;
    private static final float ART_MIN_ASPECT = 0.45f;

    /**
     * Ask the page for its own proportions and give the element that shape.
     *
     * <p>The page answers in its own units — content height over viewport width, both
     * CSS pixels. The WebView's CSS viewport is the view's width divided by the screen
     * density, so dividing a CSS height by a device-pixel width made every element
     * come out roughly 2.5x too wide on this tablet.
     *
     * <p>The shape is then the element's for good: resizing decides how much of the
     * page is on screen, and a slot whose proportions wander away from the page inside
     * it only ever crops or pads it. Artifacts written as long explainers rather than
     * figures hit the clamp and show their top; dragging them larger reveals the rest.
     */
    private void measureArtifactAspect(CanvasImage img, WebView web) {
        if (img == null || web == null || !img.isLive()) return;
        web.evaluateJavascript(
                "(function(){var b=document.body,w=window.innerWidth;"
                        + "if(!b||!(w>0))return 0;"
                        + "return Math.max(b.scrollHeight,"
                        + "b.getBoundingClientRect().height)/w;})()",
                value -> {
                    float ratio;
                    try {
                        ratio = Float.parseFloat(String.valueOf(value).replace("\"", ""));
                    } catch (Exception ex) {
                        return;
                    }
                    if (!(ratio > 0.01f)) return;
                    ratio = Math.max(ART_MIN_ASPECT, Math.min(ART_MAX_ASPECT, ratio));
                    act.canvas.setLiveAspect(img, ratio);
                    syncArtifactOverlays();
                    act.persistence.scheduleSave();
                });
    }

    /** An artifact's file changed on disk — show the new version. */
    void reloadArtifactOverlays() {
        for (ArtifactHost host : artifactOverlays.values()) {
            View web = host.findViewWithTag("web");
            if (web instanceof WebView) ((WebView) web).reload();
        }
    }

    /**
     * Step into a placed element so its own controls answer to touch.
     *
     * <p>A scrim underneath catches everything outside the element, which is both how
     * you get back out and what stops a stray drag from panning the page while you are
     * dragging a slider.
     */
    void enterArtifact(CanvasImage img) {
        if (img == null || act.centerPane == null) return;
        ArtifactHost host = artifactOverlays.get(img);
        if (host == null) return;
        if (enteredArtifact == img) return;
        exitArtifact();
        enteredArtifact = img;
        host.interactive = true;
        host.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View web = host.findViewWithTag("web");
        if (web != null) {
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.setVerticalScrollBarEnabled(true);
        }

        artifactScrim = new View(act);
        artifactScrim.setBackgroundColor(0x33000000);
        artifactScrim.setOnClickListener(v -> exitArtifact());
        ((ViewGroup) act.centerPane).addView(artifactScrim,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        host.bringToFront();

        // The ring has to be a foreground: the WebView fills the host and paints over
        // anything the background draws.
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(0x00000000);
        ring.setCornerRadius(act.dp(10));
        ring.setStroke(act.dp(2), act.M3_PRIMARY);
        host.setForeground(ring);

        artifactDoneChip = new TextView(act);
        artifactDoneChip.setText("Done");
        artifactDoneChip.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
        artifactDoneChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        artifactDoneChip.setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(act.M3_PRIMARY_CONTAINER);
        chipBg.setCornerRadius(act.dp(16));
        artifactDoneChip.setBackground(chipBg);
        artifactDoneChip.setOnClickListener(v -> exitArtifact());
        ((ViewGroup) act.centerPane).addView(artifactDoneChip,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT));
        positionArtifactDoneChip(host);
        if (act.canvas != null) act.canvas.clearSelection();
    }

    private void positionArtifactDoneChip(ArtifactHost host) {
        if (artifactDoneChip == null || host == null) return;
        FrameLayout.LayoutParams hlp = (FrameLayout.LayoutParams) host.getLayoutParams();
        FrameLayout.LayoutParams clp =
                (FrameLayout.LayoutParams) artifactDoneChip.getLayoutParams();
        clp.gravity = Gravity.TOP | Gravity.START;
        clp.leftMargin = hlp.leftMargin;
        clp.topMargin = Math.max(0, hlp.topMargin - act.dp(40));
        artifactDoneChip.setLayoutParams(clp);
    }

    /** Hand touches back to the canvas. */
    private void exitArtifact() {
        if (enteredArtifact == null) return;
        ArtifactHost host = artifactOverlays.get(enteredArtifact);
        if (host != null) {
            host.interactive = false;
            View web = host.findViewWithTag("web");
            if (web != null) {
                web.clearFocus();
                web.setFocusable(false);
                web.setFocusableInTouchMode(false);
                web.setVerticalScrollBarEnabled(false);
            }
            host.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            host.setForeground(null);
        }
        if (artifactScrim != null) {
            ((ViewGroup) act.centerPane).removeView(artifactScrim);
            artifactScrim = null;
        }
        if (artifactDoneChip != null) {
            ((ViewGroup) act.centerPane).removeView(artifactDoneChip);
            artifactDoneChip = null;
        }
        enteredArtifact = null;
    }
}
