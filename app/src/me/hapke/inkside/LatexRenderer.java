package me.hapke.inkside;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * Renders mixed markdown/LaTeX text to a bitmap via an offscreen WebView + KaTeX.
 */
final class LatexRenderer {
    private int cardBg = 0xFF1C1B1F;
    /** Theme text colour, for renders that do not ask for their own. */
    private int themeFg = 0xFFE8EAED;
    private static final int LAYOUT_WIDTH = 520;
    private static final int MAX_HEIGHT = 2000;
    /**
     * Plain text renders at this CSS font size and is drawn back at the field's own
     * text size, so the bitmap holds ~1.5–3× the pixels it shows at 1:1 and stays
     * sharp when zoomed in.
     */
    private static final float PLAIN_FONT_CSS = 40f;
    /** Longest bitmap edge for a plain render (px); taller text renders coarser. */
    private static final int PLAIN_MAX_EDGE = 4096;

    interface Callback {
        /**
         * @param worldPerPx world units one bitmap pixel covers; 1 for content blocks,
         *                   which are laid out in bitmap pixels.
         */
        void onRendered(Bitmap bitmap, float worldPerPx);

        void onFailed();
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean ready;
    /**
     * Renders waiting for the WebView, in order, each with its own settings. It used to
     * be a single slot, and the flush re-rendered with defaults: a queued plain text
     * came out on the content-block card (the "background around it"), and any earlier
     * queued request was silently dropped.
     */
    private static final class Job {
        final String text;
        final boolean noCard;
        final int fg;
        final float textSize;
        final float maxWorldWidth;
        final Callback callback;

        Job(String text, boolean noCard, int fg, float textSize, float maxWorldWidth,
            Callback callback) {
            this.text = text;
            this.noCard = noCard;
            this.fg = fg;
            this.textSize = textSize;
            this.maxWorldWidth = maxWorldWidth;
            this.callback = callback;
        }
    }

    private final java.util.ArrayDeque<Job> queue = new java.util.ArrayDeque<>();
    /** Current render has no card behind it (plain text fields); see render(). */
    private boolean transparent;
    private boolean busy;
    private String pendingThemeJs;

    LatexRenderer(Activity activity) {
        this.activity = activity;
    }

    void applyTheme(ThemeConfig.AppTheme theme) {
        if (theme == null) return;
        cardBg = theme.surfaceContainerHighest;
        themeFg = theme.onSurface;
        String fg = css(theme.onSurface);
        String muted = css(theme.onSurfaceVariant);
        String codeBg = css(theme.surfaceContainer);
        String card = css(cardBg);
        String errorFg = css(theme.light ? 0xFFB91C1C : 0xFFFCA5A5);
        pendingThemeJs = "(function(){var r=document.documentElement.style;"
                + "r.setProperty('--card-bg'," + JSONObject.quote(card) + ");"
                + "r.setProperty('--fg'," + JSONObject.quote(fg) + ");"
                + "r.setProperty('--muted'," + JSONObject.quote(muted) + ");"
                + "r.setProperty('--code-bg'," + JSONObject.quote(codeBg) + ");"
                + "r.setProperty('--error-fg'," + JSONObject.quote(errorFg) + ");"
                + "document.body.style.background=" + JSONObject.quote(card) + ";"
                + "document.body.style.color=" + JSONObject.quote(fg) + ";"
                + "})()";
        main.post(() -> {
            if (webView != null) {
                webView.setBackgroundColor(cardBg);
                if (ready) webView.evaluateJavascript(pendingThemeJs, null);
            }
        });
    }

    private static String css(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (a >= 255) return String.format("#%02X%02X%02X", r, g, b);
        return String.format("rgba(%d,%d,%d,%.3f)", r, g, b, a / 255f);
    }

    void init(ViewGroup host) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.enableSlowWholeDocumentDraw();
        }
        activity.runOnUiThread(() -> {
            webView = new WebView(activity);
            webView.setVisibility(View.INVISIBLE);
            webView.setBackgroundColor(cardBg);
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            WebSettings ws = webView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(true);
            try {
                ws.setAllowFileAccessFromFileURLs(true);
                ws.setAllowUniversalAccessFromFileURLs(true);
            } catch (Throwable ignored) {
            }
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    ready = true;
                    if (pendingThemeJs != null) {
                        view.evaluateJavascript(pendingThemeJs, null);
                    }
                    flushQueue();
                }
            });
            // Wide enough that markdown/KaTeX lay out at the target card width.
            host.addView(webView, new ViewGroup.LayoutParams(LAYOUT_WIDTH, 400));
            webView.loadUrl("file:///android_asset/chat/latex_bitmap.html");
        });
    }

    /** A content block: the chat's card, laid out at card width. */
    void renderCard(String text, Callback callback) {
        enqueue(new Job(text, false, 0, 0f, 0f, callback));
    }

    /**
     * Plain canvas text, as the chat shows it: no card, transparent, only as wide as
     * the text needs (up to {@code maxWorldWidth}), and sized so its body text matches
     * {@code textSize} on the canvas.
     *
     * @param fg text colour (the field's own), or 0 for the theme's
     */
    void renderPlain(String text, int fg, float textSize, float maxWorldWidth, Callback callback) {
        enqueue(new Job(text, true, fg, textSize, maxWorldWidth, callback));
    }

    private void enqueue(Job job) {
        if (job.text == null || job.text.isEmpty()) {
            if (job.callback != null) job.callback.onFailed();
            return;
        }
        main.post(() -> {
            if (!ready || webView == null || busy) {
                queue.addLast(job);
                return;
            }
            run(job);
        });
    }

    private void run(Job job) {
        if (job.noCard) {
            doRenderPlain(job);
        } else {
            doRender(job.text, job.fg, job.callback);
        }
    }

    private void doRenderPlain(Job job) {
        final Callback callback = job.callback;
        if (webView == null) {
            if (callback != null) callback.onFailed();
            return;
        }
        busy = true;
        transparent = true;
        webView.setBackgroundColor(0x00000000);
        final float dpr = Math.max(0.5f, activity.getResources().getDisplayMetrics().density);
        final float textSize = job.textSize > 1f ? job.textSize : 28f;
        // CSS px per world unit, so the render's font lands at the field's text size.
        final float cssPerWorld = PLAIN_FONT_CSS / textSize;
        final float maxCss = Math.max(40f, job.maxWorldWidth * cssPerWorld);
        final int viewW = Math.min(PLAIN_MAX_EDGE, (int) Math.ceil(maxCss * dpr) + 8);
        String fgCss = css(job.fg != 0 ? job.fg : themeFg);
        webView.evaluateJavascript("document.body.classList.add('plain');"
                + "document.body.style.background='transparent';"
                + "document.documentElement.style.setProperty('--fg'," + JSONObject.quote(fgCss) + ");"
                + "document.body.style.color=" + JSONObject.quote(fgCss) + ";", null);
        webView.measure(
                View.MeasureSpec.makeMeasureSpec(viewW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PLAIN_MAX_EDGE, View.MeasureSpec.AT_MOST));
        webView.layout(0, 0, viewW, Math.max(webView.getMeasuredHeight(), 80));
        final String call = "(function(){ return preparePlainRender(" + JSONObject.quote(job.text)
                + "," + PLAIN_FONT_CSS + "," + maxCss + "); })()";
        webView.evaluateJavascript(call, first -> main.postDelayed(() -> {
            if (webView == null) {
                finishFailed(callback);
                return;
            }
            // Measure again once KaTeX's fonts have settled; the first pass can be short.
            webView.evaluateJavascript("(function(){ return measurePlain(); })()",
                    dimJson -> main.post(() -> {
                        if (webView == null) {
                            finishFailed(callback);
                            return;
                        }
                        float wCss, hCss;
                        try {
                            JSONObject dims = new JSONObject(unquoteJsString(dimJson));
                            wCss = (float) dims.optDouble("w", 0);
                            hCss = (float) dims.optDouble("h", 0);
                        } catch (Exception e) {
                            finishFailed(callback);
                            return;
                        }
                        if (!(wCss > 0) || !(hCss > 0)) {
                            finishFailed(callback);
                            return;
                        }
                        int w = Math.min(viewW, (int) Math.ceil(wCss * dpr));
                        int h = Math.min(PLAIN_MAX_EDGE, (int) Math.ceil(hCss * dpr));
                        webView.measure(
                                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                        webView.layout(0, 0, w, h);
                        final float worldPerPx = 1f / (cssPerWorld * dpr);
                        main.postDelayed(() -> snapshot(w, h, worldPerPx, callback), 80);
                    }));
        }, 180));
    }

    private void doRender(String text, int fg, Callback callback) {
        if (webView == null) {
            if (callback != null) callback.onFailed();
            return;
        }
        busy = true;
        transparent = false;
        webView.setBackgroundColor(cardBg);
        String fgCss = css(fg != 0 ? fg : themeFg);
        webView.evaluateJavascript("document.body.classList.remove('plain');"
                + "document.body.style.background=" + JSONObject.quote(css(cardBg)) + ";"
                + "document.documentElement.style.setProperty('--fg'," + JSONObject.quote(fgCss) + ");"
                + "document.body.style.color=" + JSONObject.quote(fgCss) + ";", null);
        // Pre-layout at card width so prepareBitmapRender measures full wrapped height.
        webView.measure(
                View.MeasureSpec.makeMeasureSpec(LAYOUT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(MAX_HEIGHT, View.MeasureSpec.AT_MOST));
        int mh = Math.max(webView.getMeasuredHeight(), 80);
        webView.layout(0, 0, LAYOUT_WIDTH, mh);

        String quoted = JSONObject.quote(text);
        webView.evaluateJavascript(
                "(function(){ return prepareBitmapRender(" + quoted + "); })()",
                dimJson -> main.post(() -> {
                    if (webView == null) {
                        busy = false;
                        if (callback != null) callback.onFailed();
                        return;
                    }
                    if (dimJson == null || "null".equals(dimJson)) {
                        finishFailed(callback);
                        return;
                    }
                    try {
                        String raw = unquoteJsString(dimJson);
                        JSONObject dims = new JSONObject(raw);
                        int w = Math.min(Math.max(dims.optInt("w", LAYOUT_WIDTH), 200), LAYOUT_WIDTH + 40);
                        int h = Math.min(Math.max(dims.optInt("h", 80), 48), MAX_HEIGHT);
                        captureBitmap(w, h, callback);
                    } catch (Exception e) {
                        finishFailed(callback);
                    }
                }));
    }

    private void captureBitmap(int w, int h, Callback callback) {
        if (webView == null) {
            finishFailed(callback);
            return;
        }
        webView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        webView.layout(0, 0, w, h);
        // Second measure pass after KaTeX fonts settle — re-query height.
        main.postDelayed(() -> {
            if (webView == null) {
                finishFailed(callback);
                return;
            }
            webView.evaluateJavascript(
                    "(function(){ var r=document.getElementById('root');"
                            + "return JSON.stringify({w:Math.ceil(r.scrollWidth),"
                            + "h:Math.ceil(r.scrollHeight)}); })()",
                    dimJson -> main.post(() -> {
                        int measuredW = w;
                        int measuredH = h;
                        try {
                            String raw = unquoteJsString(dimJson);
                            JSONObject dims = new JSONObject(raw);
                            measuredW = Math.min(Math.max(dims.optInt("w", w), 200), LAYOUT_WIDTH + 40);
                            measuredH = Math.min(Math.max(dims.optInt("h", h), 48), MAX_HEIGHT);
                        } catch (Exception ignored) {
                        }
                        // Copies must be effectively final to be captured by the delayed snapshot.
                        final int finalW = measuredW;
                        final int finalH = measuredH;
                        webView.measure(
                                View.MeasureSpec.makeMeasureSpec(finalW, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(finalH, View.MeasureSpec.EXACTLY));
                        webView.layout(0, 0, finalW, finalH);
                        main.postDelayed(() -> snapshot(finalW, finalH, 1f, callback), 80);
                    }));
        }, 180);
    }

    private void snapshot(int w, int h, float worldPerPx, Callback callback) {
        Bitmap bmp = null;
        try {
            if (webView == null) {
                finishFailed(callback);
                return;
            }
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(transparent ? 0x00000000 : cardBg);
            webView.draw(new Canvas(bmp));
        } catch (Throwable t) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            finishFailed(callback);
            return;
        }
        busy = false;
        // Once handed over, the bitmap belongs to the canvas: never recycle it here
        // (a throw inside the callback used to recycle a bitmap the canvas then drew).
        try {
            if (callback != null) callback.onRendered(bmp, worldPerPx);
        } catch (Throwable t) {
            android.util.Log.w("LatexRenderer", "render callback failed", t);
        }
        flushQueue();
    }

    private void finishFailed(Callback callback) {
        busy = false;
        if (callback != null) callback.onFailed();
        flushQueue();
    }

    private void flushQueue() {
        if (!ready || webView == null || busy) return;
        Job next = queue.pollFirst();
        if (next != null) run(next);
    }

    private static String unquoteJsString(String value) {
        if (value == null || "null".equals(value)) return "";
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\");
        }
        return value;
    }

    void destroy() {
        main.post(() -> {
            if (webView != null) {
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) parent.removeView(webView);
                webView.destroy();
                webView = null;
            }
            ready = false;
            busy = false;
            queue.clear();
        });
    }
}
