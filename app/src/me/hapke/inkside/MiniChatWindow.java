package me.hapke.inkside;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small floating chat for the "Instant chat" quick action: a header (title, open in the
 * full chat, close), a mirror of the active chat, and its own input with mic and send.
 * It never talks to the agent itself — the host sends through the normal chat and
 * pushes every chat view update here as well, so both views always show the same chat.
 * Drag it by the header.
 */
final class MiniChatWindow extends LinearLayout {
    interface Host {
        void onMiniSend(String text);

        void onMiniMic();

        void onMiniExpand();

        void onMiniClose();

        /** The chat page loaded: push theme and transcript. */
        void onMiniWebReady();

        Object miniJsBridge();

        /** Throw the running dictation away. */
        default void onMiniDiscard() {}
    }

    private final Host host;
    private final TextView title;
    private final ImageView expandBtn;
    private final ImageView closeBtn;
    private final WebView web;
    private final EditText input;
    private final ImageView mic;
    private final android.widget.ProgressBar micSpinner;
    private final ImageView discard;
    private final VoiceWaveView wave;
    private final ImageView send;
    private final LinearLayout inputRow;
    private final GradientDrawable cardBg = new GradientDrawable();
    private boolean webReady;
    private boolean listening;
    private boolean transcribing;
    private String idleHint = "Speak or type…";

    private int surface = 0xFF26252E;
    private int surfaceHighest = 0xFF31303A;
    private int onSurface = 0xFFE6E1E9;
    private int onSurfaceVariant = 0xFFCAC4D0;
    private int primary = 0xFFBAC3FF;
    private int primaryContainer = 0xFF3F51B5;
    private int onPrimaryContainer = 0xFFE8EAF6;
    private int outlineVariant = 0xFF49454F;

    private float downRawX, downRawY, startTx, startTy;
    /** Where the user dragged it (translation), and how far the keyboard reaches up. */
    private float userTy;
    private int keyboardPx;
    private boolean dragging;
    private final int touchSlop;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility"})
    MiniChatWindow(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setClickable(true);
        setElevation(dp(12));
        setClipToOutline(true);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });

        // Header: title + expand + close. Dragging it moves the window.
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(6), dp(6), dp(2));
        title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        expandBtn = headerIcon(ctx, R.drawable.ic_open, "Open in chat", host::onMiniExpand);
        header.addView(expandBtn, new LayoutParams(dp(40), dp(40)));
        closeBtn = headerIcon(ctx, R.drawable.ic_close, "Close", host::onMiniClose);
        header.addView(closeBtn, new LayoutParams(dp(40), dp(40)));
        header.setOnTouchListener(this::onHeaderTouch);
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(ctx);
        web.setBackgroundColor(0x00000000);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        try {
            ws.getClass().getMethod("setAllowFileAccessFromFileURLs", boolean.class)
                    .invoke(ws, true);
            ws.getClass().getMethod("setAllowUniversalAccessFromFileURLs", boolean.class)
                    .invoke(ws, true);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 21) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                webReady = true;
                view.setBackgroundColor(0x00000000);
                host.onMiniWebReady();
            }
        });
        Object bridge = host.miniJsBridge();
        if (bridge != null) web.addJavascriptInterface(bridge, "AndroidBridge");
        web.loadUrl("file:///android_asset/chat/index.html");
        LayoutParams wlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wlp.leftMargin = dp(8);
        wlp.rightMargin = dp(8);
        addView(web, wlp);

        // Input pill: text, mic, send.
        inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(16), dp(4), dp(4), dp(4));
        input = new EditText(ctx);
        input.setBackground(null);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setPadding(0, dp(8), 0, dp(8));
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHint(idleHint);
        inputRow.addView(input, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Listening: live voice bars + discard, same as the main chat composer.
        wave = new VoiceWaveView(ctx);
        wave.setVisibility(GONE);
        LayoutParams wlp2 = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp2.rightMargin = dp(6);
        inputRow.addView(wave, wlp2);
        discard = roundIcon(ctx, R.drawable.ic_delete, "Discard dictation", host::onMiniDiscard);
        discard.setVisibility(GONE);
        inputRow.addView(discard, new LayoutParams(dp(40), dp(40)));
        mic = roundIcon(ctx, R.drawable.ic_mic, "Dictate", host::onMiniMic);
        inputRow.addView(mic, new LayoutParams(dp(40), dp(40)));
        micSpinner = new android.widget.ProgressBar(ctx);
        micSpinner.setIndeterminate(true);
        micSpinner.setPadding(dp(9), dp(9), dp(9), dp(9));
        micSpinner.setVisibility(GONE);
        micSpinner.setContentDescription("Transcribing");
        inputRow.addView(micSpinner, new LayoutParams(dp(40), dp(40)));
        send = roundIcon(ctx, R.drawable.ic_send, "Send", () -> host.onMiniSend(text()));
        LayoutParams slp = new LayoutParams(dp(40), dp(40));
        slp.leftMargin = dp(4);
        inputRow.addView(send, slp);
        LayoutParams ilp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(10), dp(6), dp(10), dp(10));
        addView(inputRow, ilp);

        applyColors();
    }

    String text() {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    EditText input() {
        return input;
    }

    WebView webView() {
        return web;
    }

    void clearInput() {
        input.setText("");
    }

    void setTitle(String t) {
        title.setText(t == null || t.isEmpty() ? "Chat" : t);
    }

    /** Runs a chat-view update here too (mirrors the main chat view). */
    void evalJs(String script) {
        if (!webReady || destroyed) return;
        // A post can outlive the view: the main chat crashed on exactly this.
        web.post(() -> {
            if (destroyed) return;
            try {
                web.evaluateJavascript(script, null);
            } catch (Throwable ignored) {
            }
        });
    }

    private boolean destroyed;

    boolean isWebReady() {
        return webReady;
    }

    void setListening(boolean on) {
        listening = on;
        input.setHint(on ? "Listening…" : transcribing ? "Transcribing…" : idleHint);
        input.setCursorVisible(!on);
        wave.setRunning(on);
        discard.setVisibility(on ? VISIBLE : GONE);
        styleMic();
    }

    void setVoiceLevel(float level) {
        wave.setLevel(level);
    }

    void setTranscribing(boolean on) {
        transcribing = on;
        input.setHint(on ? "Transcribing…" : listening ? "Listening…" : idleHint);
        mic.setAlpha(1f);
        mic.setVisibility(on ? GONE : VISIBLE);
        micSpinner.setIndeterminateTintList(ColorStateList.valueOf(primary));
        micSpinner.setVisibility(on ? VISIBLE : GONE);
    }

    void destroy() {
        destroyed = true;
        try {
            web.stopLoading();
            web.destroy();
        } catch (Throwable ignored) {
        }
    }

    void setColors(int surface, int surfaceHighest, int onSurface, int onSurfaceVariant,
                   int primary, int primaryContainer, int onPrimaryContainer, int outlineVariant) {
        this.surface = surface | 0xFF000000;
        this.surfaceHighest = surfaceHighest | 0xFF000000;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.primary = primary;
        this.primaryContainer = primaryContainer;
        this.onPrimaryContainer = onPrimaryContainer;
        this.outlineVariant = outlineVariant;
        applyColors();
    }

    private void applyColors() {
        cardBg.setCornerRadius(dp(24));
        cardBg.setColor(surface);
        cardBg.setStroke(dp(1), (outlineVariant & 0x00FFFFFF) | 0x55000000);
        setBackground(cardBg);
        SketchStyle.elevate(this, 12);
        title.setTextColor(onSurface);
        tint(expandBtn, onSurfaceVariant);
        tint(closeBtn, onSurfaceVariant);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(24));
        pill.setColor(surfaceHighest);
        inputRow.setBackground(pill);
        SketchStyle.outline(inputRow, 4);
        input.setTextColor(onSurface);
        input.setHintTextColor(onSurfaceVariant);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(primaryContainer);
        send.setBackground(new RippleDrawable(
                ColorStateList.valueOf((onPrimaryContainer & 0x00FFFFFF) | 0x29000000), sendBg, null));
        SketchStyle.outline(send, 2);
        tint(send, onPrimaryContainer);
        wave.setColor(primary);
        discard.setBackground(hoverRipple(true));
        tint(discard, onSurfaceVariant);
        styleMic();
    }

    /** Solid primary while listening, like the main chat's mic. */
    private void styleMic() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(listening ? primary : 0x00000000);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        mic.setBackground(new RippleDrawable(
                ColorStateList.valueOf((onSurface & 0x00FFFFFF) | 0x29000000), d, mask));
        tint(mic, listening ? primaryContainer : onSurfaceVariant);
        mic.setContentDescription(listening ? "Stop dictation" : "Dictate");
    }

    private RippleDrawable hoverRipple(boolean oval) {
        GradientDrawable mask = new GradientDrawable();
        if (oval) mask.setShape(GradientDrawable.OVAL);
        else mask.setCornerRadius(dp(12));
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(
                ColorStateList.valueOf((onSurface & 0x00FFFFFF) | 0x29000000), null, mask);
    }

    private static void tint(ImageView v, int color) {
        v.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    private ImageView headerIcon(Context ctx, int res, String desc, Runnable onTap) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(desc);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask));
        v.setOnClickListener(x -> onTap.run());
        return v;
    }

    private ImageView roundIcon(Context ctx, int res, String desc, Runnable onTap) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(9);
        v.setPadding(pad, pad, pad, pad);
        v.setContentDescription(desc);
        v.setClickable(true);
        v.setOnClickListener(x -> onTap.run());
        return v;
    }

    // ── Drag by the header ──────────────────────────────────────────────

    private boolean onHeaderTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startTx = getTranslationX();
                startTy = userTy;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) dragging = true;
                if (dragging) moveTo(startTx + dx, startTy + dy);
                return true;
            }
            default:
                dragging = false;
                return true;
        }
    }

    /** Keep the whole window on screen. */
    private void moveTo(float tx, float ty) {
        View parent = (View) getParent();
        if (parent != null) {
            tx = Math.max(-getLeft(), Math.min(parent.getWidth() - getRight(), tx));
            ty = Math.max(-getTop(), Math.min(parent.getHeight() - getBottom(), ty));
        }
        setTranslationX(tx);
        userTy = ty;
        applyY();
    }

    /** Back where its layout puts it (shown again elsewhere). */
    void resetPosition() {
        setTranslationX(0f);
        userTy = 0f;
        applyY();
    }

    /** The keyboard covers {@code px} of the screen's bottom: stay just above it. */
    void setKeyboardOverlap(int px) {
        keyboardPx = px;
        applyY();
    }

    private void applyY() {
        float ty = userTy;
        View parent = (View) getParent();
        if (keyboardPx > 0 && parent != null) {
            float limit = parent.getHeight() - keyboardPx - dp(12);
            float bottom = getTop() + ty + getHeight();
            if (bottom > limit) ty = Math.max(-getTop(), ty - (bottom - limit));
        }
        setTranslationY(ty);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
