package me.hapke.inkside;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * The chat side panel's views: building it, sliding, resizing, the tabs drawer, pulse dots and the chat-selection chip.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class ChatView {
    private final MainActivity act;

    private View chatExpandedBody;

    private FrameLayout.LayoutParams chatCanvasFabLp;

    private FrameLayout chatTabsOverlay;
    private View chatTabsScrim;

    private View chatTabsEdgeAffordance;
    private View chatOuterEdgeDrag;
    private FrameLayout.LayoutParams chatOuterEdgeDragLp;
    private FrameLayout.LayoutParams chatTabsEdgeAffordanceLp;

    private int chatResizeStartWidth;
    private int chatResizePendingWidth;
    private float chatResizeStartRawX;
    private float chatResizeLastRawX;
    private boolean chatTabsOpen = false;
    private ValueAnimator chatTabsAnim;
    private float tabsEdgeStartX;
    private float tabsEdgeStartY;
    private boolean tabsEdgeTracking;
    private boolean tabsEdgeIntercepting;
    private boolean chatPanelDragging = false;

    private float chatPanelDragStartRawX;
    private float chatPanelDragStartRawY;

    private static final int CHAT_TOGGLE_SIZE = 44;

    private static final int CHAT_FAB_INSET = 20;
    private static final int CHAT_EDGE_DRAG_W = 16;
    private static final int CHAT_TABS_DRAWER_W = 260;

    private static final float CHAT_PANEL_MAX_FRAC = 0.72f;

    private FrameLayout.LayoutParams chatResizeHandleLp;
    private View chatResizeGrip;
    /** Solid chat panel fill (canvas-facing corners rounded). */
    private View chatScrim;
    /** Full-panel chat-icon placeholder shown while resizing (content hidden). */
    private FrameLayout chatResizePlaceholder;
    private ImageView chatResizePlaceholderIcon;

    private float chatSlideStartTx;
    private float chatSlideStartRawX;

    ChatView(MainActivity act) {
        this.act = act;
    }

    FrameLayout buildChat() {
        // Intercept rightward horizontal swipes anywhere in the chat panel to open
        // the chats switcher — not only a thin left-edge strip.
        FrameLayout root = new FrameLayout(act) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (act.chatCollapsed || act.chatResizing || chatTabsOpen || chatPanelDragging) {
                    return false;
                }
                final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        tabsEdgeStartX = ev.getRawX();
                        tabsEdgeStartY = ev.getRawY();
                        tabsEdgeTracking = true;
                        tabsEdgeIntercepting = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        if (!tabsEdgeTracking || tabsEdgeIntercepting) {
                            return tabsEdgeIntercepting;
                        }
                        float dx = ev.getRawX() - tabsEdgeStartX;
                        float dy = ev.getRawY() - tabsEdgeStartY;
                        if (dx > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                            // Chat on the right: outer-edge rightward swipe still closes
                            // the panel — don't steal that for the switcher.
                            if (!act.chatOnLeft && act.chatWeb != null && act.chatWeb.getWidth() > 0) {
                                int[] loc = new int[2];
                                act.chatWeb.getLocationOnScreen(loc);
                                float localX = tabsEdgeStartX - loc[0];
                                if (localX >= act.chatWeb.getWidth() - act.dp(56)) {
                                    tabsEdgeTracking = false;
                                    return false;
                                }
                            }
                            // Let horizontally scrollable chat content keep the gesture.
                            if (act.chatWeb != null && (act.chatWebTouchScrollXState == 2
                                    || act.chatWeb.canScrollHorizontally(-1))) {
                                tabsEdgeTracking = false;
                                return false;
                            }
                            if (act.chatWebTouchScrollXState == 0 && dx < slop * 3) {
                                return false;
                            }
                            tabsEdgeIntercepting = true;
                            return true;
                        }
                        if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
                            tabsEdgeTracking = false;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        tabsEdgeTracking = false;
                        return tabsEdgeIntercepting;
                    default:
                        return tabsEdgeIntercepting;
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (!tabsEdgeIntercepting) return super.onTouchEvent(ev);
                final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dx = ev.getRawX() - tabsEdgeStartX;
                        tabsEdgeIntercepting = false;
                        tabsEdgeTracking = false;
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP && dx > slop * 2) {
                            showChatTabsOverlay();
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            }
        };
        root.setBackgroundColor(0x00000000);
        // Clipping to rounded outline is applied in applyChatPanelClip().

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0x00000000);
        // Equal inset on sides + bottom so the composer/send control sits evenly in the shell.
        col.setPadding(act.dp(MainActivity.CHAT_CONTENT_INSET), 0, act.dp(MainActivity.CHAT_CONTENT_INSET), act.dp(MainActivity.CHAT_CONTENT_INSET));

        act.chatTabsRow = new LinearLayout(act);
        act.chatTabsRow.setOrientation(LinearLayout.VERTICAL);
        act.chatTabsRow.setGravity(Gravity.TOP);

        act.chatRunBanner = new LinearLayout(act);
        act.chatRunBanner.setOrientation(LinearLayout.HORIZONTAL);
        act.chatRunBanner.setGravity(Gravity.CENTER_VERTICAL);
        act.chatRunBanner.setVisibility(View.GONE);
        act.chatRunBanner.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_SM));
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadius(act.dp(10));
        bannerBg.setColor(act.M3_PRIMARY_CONTAINER);
        act.chatRunBanner.setBackground(bannerBg);
        act.chatRunBanner.setClickable(true);
        act.chatRunBanner.setFocusable(true);

        act.chatRunBannerSpinner = new ProgressBar(act);
        act.chatRunBannerSpinner.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= 21) {
            act.chatRunBannerSpinner.getIndeterminateDrawable().setColorFilter(
                    new PorterDuffColorFilter(act.M3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN));
        }
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(act.dp(18), act.dp(18));
        spinLp.rightMargin = act.dp(MainActivity.SPACE_SM);
        act.chatRunBanner.addView(act.chatRunBannerSpinner, spinLp);

        act.chatRunBannerLabel = new TextView(act);
        act.chatRunBannerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        act.chatRunBannerLabel.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
        act.chatRunBannerLabel.setMaxLines(1);
        act.chatRunBannerLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        act.chatRunBanner.addView(act.chatRunBannerLabel, labelLp);

        ImageView bannerStop = act.iconBtn(R.drawable.ic_stop, act.conversations::stopChat);
        act.applyIconSelected(bannerStop, true);
        LinearLayout.LayoutParams bannerStopLp = new LinearLayout.LayoutParams(act.dp(32), act.dp(32));
        bannerStopLp.leftMargin = act.dp(MainActivity.SPACE_SM);
        act.chatRunBanner.addView(bannerStop, bannerStopLp);

        LinearLayout.LayoutParams bannerLp = MainActivity.matchWrap();
        bannerLp.bottomMargin = act.dp(MainActivity.SPACE_SM);
        col.addView(act.chatRunBanner, bannerLp);

        act.conversations.ensureDefaultChat();
        act.conversations.refreshChatTabs();

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        chatExpandedBody = body;

        act.chatWeb = new WebView(act);
        act.chatWeb.setBackgroundColor(0x00000000);
        act.chatWeb.setVerticalScrollBarEnabled(false);
        act.chatWeb.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (Build.VERSION.SDK_INT >= 21) {
            act.chatWeb.setBackground(null);
        }
        WebSettings ws = act.chatWeb.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        // Needed so KaTeX can load fonts from file:///android_asset/chat/fonts/
        try {
            ws.getClass()
                    .getMethod("setAllowFileAccessFromFileURLs", boolean.class)
                    .invoke(ws, true);
            ws.getClass()
                    .getMethod("setAllowUniversalAccessFromFileURLs", boolean.class)
                    .invoke(ws, true);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 21) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        act.chatWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                act.chatWebReady = true;
                view.setBackgroundColor(0x00000000);
                applyChatWebTheme(ThemeConfig.appThemeById(act.appThemeId));
                act.conversations.evalChatJs("setAll(" + JSONObject.quote(act.chatPlain.toString()) + ")");
                act.conversations.syncChatRunState();
                act.conversations.resumeRunningSessions();
            }
        });
        act.chatWeb.addJavascriptInterface(new ChatJsBridge(act), "AndroidBridge");
        act.chatWeb.loadUrl("file:///android_asset/chat/index.html");
        attachChatWebHorizontalDrag(act.chatWeb);
        body.addView(act.chatWeb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        chatResizePlaceholder = buildChatResizePlaceholder();
        body.addView(chatResizePlaceholder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        act.attachScroll = new HorizontalScrollView(act);
        act.attachScroll.setHorizontalScrollBarEnabled(false);
        act.attachScroll.setVisibility(View.GONE);
        act.attachRow = new LinearLayout(act);
        act.attachRow.setOrientation(LinearLayout.HORIZONTAL);
        act.attachRow.setGravity(Gravity.CENTER_VERTICAL);
        act.attachScroll.addView(act.attachRow);
        LinearLayout.LayoutParams attachLp = MainActivity.matchWrap();
        attachLp.topMargin = act.dp(MainActivity.SPACE_SM);
        body.addView(act.attachScroll, attachLp);

        act.chatInput = new EditText(act) {
            @Override
            public boolean onTextContextMenuItem(int id) {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    act.conversations.ingestClipboardAttachments();
                }
                return super.onTextContextMenuItem(id);
            }
        };
        act.chatInput.setHint(act.chatIdleHint());
        act.refreshLearningBadge();
        act.chatInput.setHintTextColor(act.M3_ON_SURFACE_VARIANT);
        act.chatInput.setTextColor(act.M3_ON_SURFACE);
        act.chatInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        act.chatInput.setMinLines(1);
        act.chatInput.setMaxLines(5);
        act.chatInput.setMinHeight(act.dp(36));
        act.chatInput.setSingleLine(false);
        act.chatInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        act.chatInput.setBackground(null);
        act.chatInput.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_MD));
        act.chatInput.setShowSoftInputOnFocus(true);
        act.chatInput.setOnClickListener(v -> act.showSoftKeyboard(act.chatInput));
        act.chatInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) act.showSoftKeyboard(act.chatInput);
        });
        act.chatInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                act.conversations.sendChat();
                return true;
            }
            return false;
        });
        act.chatInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                ChatSession cur = act.conversations.activeChat();
                if (cur != null && act.chatInput != null) {
                    cur.draft = act.chatInput.getText().toString();
                }
                act.persistence.scheduleSave();
            }
        });
        if (Build.VERSION.SDK_INT >= 31) {
            act.chatInput.setOnReceiveContentListener(
                    new String[]{"image/*", "application/*", "text/*", "audio/*", "video/*"},
                    (view, payload) -> {
                        ClipData clip = payload.getClip();
                        if (clip == null) return payload;
                        StringBuilder textBuf = new StringBuilder();
                        boolean hasUri = false;
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            ClipData.Item item = clip.getItemAt(i);
                            if (item.getUri() != null) {
                                hasUri = true;
                                act.conversations.addAttachmentFromUri(item.getUri(), act.conversations.guessMime(clip, i, item.getUri()));
                            }
                            CharSequence text = item.getText();
                            if (text != null && text.length() > 0) {
                                if (textBuf.length() > 0) textBuf.append('\n');
                                textBuf.append(text);
                            }
                        }
                        if (hasUri) act.conversations.refreshAttachRow();
                        if (textBuf.length() > 0) {
                            int start = Math.max(act.chatInput.getSelectionStart(), 0);
                            int end = Math.max(act.chatInput.getSelectionEnd(), 0);
                            act.chatInput.getText().replace(
                                    Math.min(start, end), Math.max(start, end), textBuf);
                        }
                        return null;
                    });
        }
        LinearLayout composerShell = new LinearLayout(act);
        composerShell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout buttonRow = new LinearLayout(act);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        composerShell.addView(buttonRow, MainActivity.matchWrap());
        int composerPad = act.dp(8);
        composerShell.setPadding(composerPad, composerPad, composerPad, composerPad);
        GradientDrawable composerBg = new GradientDrawable();
        composerBg.setCornerRadius(act.dp(MainActivity.CHAT_COMPOSER_RADIUS));
        composerBg.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
        composerShell.setBackground(composerBg);
        composerShell.setMinimumHeight(act.dp(MainActivity.CHAT_COMPOSER_MIN_H));
        composerShell.setClipToOutline(true);
        act.chatComposerShell = composerShell;

        act.chatAttachButton = act.iconBtn(R.drawable.ic_add, () -> act.overflowMenu.showAttachMenu(act.chatAttachButton));
        act.applyIconSelected(act.chatAttachButton, false);
        act.chatAttachButton.setContentDescription("Add to message");
        LinearLayout.LayoutParams attachBtnLp = new LinearLayout.LayoutParams(act.dp(32), act.dp(32));
        attachBtnLp.gravity = Gravity.CENTER_VERTICAL;
        buttonRow.addView(act.chatAttachButton, attachBtnLp);

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.gravity = Gravity.CENTER_VERTICAL;
        inputLp.setMargins(act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_XS), 0);
        buttonRow.addView(act.chatInput, inputLp);
        // A message that needs a second line moves above the buttons instead of
        // pushing them apart; it moves back once the box is empty again.
        // Keeps mic and send at the right while the input is above the buttons.
        final View rowSpacer = new View(act);
        rowSpacer.setVisibility(View.GONE);
        buttonRow.addView(rowSpacer, 2, new LinearLayout.LayoutParams(0, 1, 1f));
        final LinearLayout.LayoutParams wideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wideLp.setMargins(act.dp(MainActivity.SPACE_XS), 0, act.dp(MainActivity.SPACE_XS), 0);
        act.chatInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                act.chatInput.post(() -> {
                    boolean above = act.chatInput.getParent() == composerShell;
                    boolean wantAbove = above ? s.length() > 0 : act.chatInput.getLineCount() > 1;
                    if (wantAbove == above) return;
                    int sel = act.chatInput.getSelectionStart();
                    ((ViewGroup) act.chatInput.getParent()).removeView(act.chatInput);
                    rowSpacer.setVisibility(wantAbove ? View.VISIBLE : View.GONE);
                    if (wantAbove) composerShell.addView(act.chatInput, 0, wideLp);
                    else buttonRow.addView(act.chatInput, 1, inputLp);
                    act.chatInput.requestFocus();
                    act.chatInput.setSelection(Math.max(0, Math.min(sel, s.length())));
                });
            }
        });

        act.chatVoiceWave = new VoiceWaveView(act);
        act.chatVoiceWave.setColor(act.M3_PRIMARY);
        act.chatVoiceWave.setVisibility(View.GONE);
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        waveLp.gravity = Gravity.CENTER_VERTICAL;
        waveLp.rightMargin = act.dp(MainActivity.SPACE_MD);
        buttonRow.addView(act.chatVoiceWave, waveLp);

        act.chatMicDiscardButton = act.iconBtn(R.drawable.ic_delete, () -> {
            act.sendAfterDictation = false;
            act.miniSendAfterDictation = false;
            act.dictation.stopVoiceRecording(false);
        });
        act.applyIconSelected(act.chatMicDiscardButton, false);
        act.chatMicDiscardButton.setContentDescription("Discard dictation");
        act.chatMicDiscardButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams micDiscardLp = new LinearLayout.LayoutParams(act.dp(32), act.dp(32));
        micDiscardLp.gravity = Gravity.CENTER_VERTICAL;
        micDiscardLp.rightMargin = act.dp(MainActivity.SPACE_XS);
        buttonRow.addView(act.chatMicDiscardButton, micDiscardLp);

        // Stands in for the mic while the Mac transcribes the recording.
        act.chatMicSpinner = new android.widget.ProgressBar(act);
        act.chatMicSpinner.setIndeterminate(true);
        act.chatMicSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(act.M3_PRIMARY));
        act.chatMicSpinner.setVisibility(View.GONE);
        act.chatMicSpinner.setContentDescription("Transcribing");
        act.chatMicSpinner.setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(6));
        LinearLayout.LayoutParams micSpinLp = new LinearLayout.LayoutParams(act.dp(32), act.dp(32));
        micSpinLp.gravity = Gravity.CENTER_VERTICAL;
        micSpinLp.rightMargin = act.dp(MainActivity.SPACE_XS);
        buttonRow.addView(act.chatMicSpinner, micSpinLp);

        act.chatMicButton = act.iconBtn(R.drawable.ic_mic, act.dictation::toggleVoiceInput);
        act.applyIconSelected(act.chatMicButton, false);
        act.chatMicButton.setContentDescription("Dictate");
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(act.dp(32), act.dp(32));
        micLp.gravity = Gravity.CENTER_VERTICAL;
        micLp.rightMargin = act.dp(MainActivity.SPACE_XS);
        buttonRow.addView(act.chatMicButton, micLp);

        act.sendButton = act.iconBtn(R.drawable.ic_send, act.conversations::sendChat);
        act.applyIconSelected(act.sendButton, true);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(act.dp(32), act.dp(32));
        sendLp.gravity = Gravity.CENTER_VERTICAL;
        buttonRow.addView(act.sendButton, sendLp);

        act.stopButton = act.iconBtn(R.drawable.ic_stop, act.conversations::stopChat);
        act.applyIconSelected(act.stopButton, true);
        act.stopButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(act.dp(32), act.dp(32));
        stopLp.gravity = Gravity.CENTER_VERTICAL;
        stopLp.leftMargin = act.dp(MainActivity.SPACE_XS);
        buttonRow.addView(act.stopButton, stopLp);

        LinearLayout.LayoutParams composerLp = MainActivity.matchWrap();
        composerLp.topMargin = act.dp(2);
        // No extra margins — CHAT_CONTENT_INSET on the column is the sole bottom/side inset.
        body.addView(composerShell, composerLp);

        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        col.addView(body, bodyLp);

        act.chatContentCol = col;
        act.chatShell = new LinearLayout(act);
        act.chatShell.setOrientation(LinearLayout.HORIZONTAL);
        act.chatShell.setBackgroundColor(0x00000000);
        act.chatResizeHandle = buildChatResizeHandle();
        layoutChatShellChildren();
        root.addView(act.chatShell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ensureChatScrimOnPanel(root);

        act.chatNewFab = act.iconBtn(R.drawable.ic_chat_add, act.conversations::createNewChat);
        act.applyIconSelected(act.chatNewFab, true);
        act.chatNewFab.setContentDescription("New chat");
        styleChatHeaderFabShadow(act.chatNewFab);
        FrameLayout.LayoutParams newFabLp = new FrameLayout.LayoutParams(act.dp(44), act.dp(44));
        newFabLp.gravity = Gravity.TOP | Gravity.END;
        newFabLp.topMargin = act.statusBarHeight() + act.dp(MainActivity.SPACE_MD);
        newFabLp.rightMargin = act.dp(CHAT_FAB_INSET);
        root.addView(act.chatNewFab, newFabLp);

        act.chatMoreFab = act.iconBtn(R.drawable.ic_menu, this::showChatTabsOverlay);
        act.applyIconSelected(act.chatMoreFab, true);
        act.chatMoreFab.setContentDescription("More chats");
        styleChatHeaderFabShadow(act.chatMoreFab);
        FrameLayout.LayoutParams moreFabLp = new FrameLayout.LayoutParams(act.dp(44), act.dp(44));
        moreFabLp.gravity = Gravity.TOP | Gravity.START;
        moreFabLp.topMargin = act.statusBarHeight() + act.dp(MainActivity.SPACE_MD);
        moreFabLp.leftMargin = act.dp(CHAT_FAB_INSET);
        root.addView(act.chatMoreFab, moreFabLp);

        buildChatTabsOverlay(root);

        chatOuterEdgeDrag = new View(act);
        chatOuterEdgeDragLp = new FrameLayout.LayoutParams(
                act.dp(CHAT_EDGE_DRAG_W), ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(chatOuterEdgeDrag, chatOuterEdgeDragLp);
        attachChatSlideDrag(chatOuterEdgeDrag);

        // Tabs switcher opens via rightward swipe anywhere in the chat panel
        // (see buildChat onIntercept). Keep a no-op reference for layout helpers.
        chatTabsEdgeAffordance = null;
        chatTabsEdgeAffordanceLp = null;

        layoutChatOverlayViews();

        return root;
    }

    private void buildChatTabsOverlay(FrameLayout root) {
        chatTabsOverlay = new FrameLayout(act) {
            private float dismissStartX;
            private float dismissStartY;
            private boolean dismissIntercepting;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (!chatTabsOpen) return false;
                final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dismissStartX = ev.getRawX();
                        dismissStartY = ev.getRawY();
                        dismissIntercepting = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - dismissStartX;
                        float dy = ev.getRawY() - dismissStartY;
                        if (dx < -slop && Math.abs(dx) > Math.abs(dy)) {
                            dismissIntercepting = true;
                            return true;
                        }
                        return false;
                    }
                    default:
                        return dismissIntercepting;
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (!chatTabsOpen || act.chatTabsDrawer == null) return super.onTouchEvent(ev);
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - dismissStartX;
                        act.chatTabsDrawer.setTranslationX(Math.min(0f, dx));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dx = ev.getRawX() - dismissStartX;
                        dismissIntercepting = false;
                        if (dx < -chatTabsDrawerWidth() / 3f) {
                            hideChatTabsOverlay();
                        } else {
                            act.chatTabsDrawer.animate().translationX(0f).setDuration(150).start();
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            }
        };
        chatTabsOverlay.setVisibility(View.GONE);
        chatTabsOverlay.setClickable(true);
        // Above the All/New chat FABs (those use elevation for drop shadow).
        chatTabsOverlay.setElevation(act.dp(16));
        chatTabsOverlay.setTranslationZ(act.dp(8));

        chatTabsScrim = new View(act);
        chatTabsScrim.setBackgroundColor(0x66000000);
        chatTabsScrim.setOnClickListener(v -> hideChatTabsOverlay());
        chatTabsOverlay.addView(chatTabsScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        act.chatTabsDrawer = new LinearLayout(act);
        act.chatTabsDrawer.setOrientation(LinearLayout.VERTICAL);
        act.chatTabsDrawer.setBackgroundColor(act.M3_SURFACE_CONTAINER_HIGHEST);
        act.chatTabsDrawer.setPadding(act.dp(MainActivity.SPACE_LG), act.statusBarHeight() + act.dp(MainActivity.SPACE_LG),
                act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG));
        act.chatTabsDrawer.setClickable(true);

        act.chatTabsDrawerTitle = new TextView(act);
        act.chatTabsDrawerTitle.setText("Chats");
        act.chatTabsDrawerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        act.chatTabsDrawerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        act.chatTabsDrawerTitle.setTextColor(act.M3_ON_SURFACE);
        act.chatTabsDrawerTitle.setPadding(0, 0, 0, act.dp(MainActivity.SPACE_MD));
        act.chatTabsDrawer.addView(act.chatTabsDrawerTitle, MainActivity.matchWrap());

        ScrollView tabsScroll = new ScrollView(act);
        tabsScroll.setVerticalScrollBarEnabled(false);
        tabsScroll.addView(act.chatTabsRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        act.chatTabsDrawer.addView(tabsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(
                chatTabsDrawerWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
        drawerLp.gravity = Gravity.START;
        chatTabsOverlay.addView(act.chatTabsDrawer, drawerLp);

        root.addView(chatTabsOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void layoutChatOverlayViews() {
        if (chatOuterEdgeDragLp != null && chatOuterEdgeDrag != null) {
            chatOuterEdgeDragLp.gravity = act.chatOnLeft ? Gravity.START : Gravity.END;
            chatOuterEdgeDrag.setLayoutParams(chatOuterEdgeDragLp);
        }
        if (chatTabsEdgeAffordanceLp != null && chatTabsEdgeAffordance != null) {
            chatTabsEdgeAffordanceLp.gravity = Gravity.START;
            chatTabsEdgeAffordance.setLayoutParams(chatTabsEdgeAffordanceLp);
        }
        if (act.explorerEdgeDragLp != null && act.explorerEdgeDrag != null) {
            act.explorerEdgeDragLp.gravity = act.explorerOnLeft ? Gravity.START : Gravity.END;
            act.explorerEdgeDrag.setLayoutParams(act.explorerEdgeDragLp);
        }
        // Bottom → top: shell, edge drags, FABs, then chats drawer above the FABs.
        // Explorer above the editor so the file tree always paints over the code panel
        // along the shared edge (editor sits inboard via translation).
        if (act.editorPanel != null) act.editorPanel.bringToFront();
        if (act.explorerPanel != null) act.explorerPanel.bringToFront();
        if (act.explorerEdgeDrag != null) act.explorerEdgeDrag.bringToFront();
        if (act.chatShell != null) act.chatShell.bringToFront();
        if (chatOuterEdgeDrag != null) chatOuterEdgeDrag.bringToFront();
        if (chatTabsEdgeAffordance != null) chatTabsEdgeAffordance.bringToFront();
        if (act.chatMoreFab != null) act.chatMoreFab.bringToFront();
        if (act.chatNewFab != null) act.chatNewFab.bringToFront();
        // Drawer/scrim must cover the All Chats button (elevation alone is not enough
        // if something else later brings the FAB forward).
        if (chatTabsOverlay != null) chatTabsOverlay.bringToFront();
        if (act.chatResizeHandle != null) act.chatResizeHandle.bringToFront();
        if (act.optionsOverlay != null) act.optionsOverlay.bringToFront();
    }

    private void ensureChatCanvasFab() {
        if (act.rootLayout == null) return;
        if (act.chatCanvasFab == null) {
            act.chatCanvasFab = act.iconBtn(R.drawable.ic_chat, () -> {
                if (act.chatCollapsed) animateChatToExpanded(chatPanelWidth());
            });
            act.applyIconSelected(act.chatCanvasFab, true);
            act.chatCanvasFab.setContentDescription("Open chat");
            act.chatCanvasFab.setElevation(act.dp(12));
            chatCanvasFabLp = new FrameLayout.LayoutParams(act.dp(CHAT_TOGGLE_SIZE), act.dp(CHAT_TOGGLE_SIZE));
        }
        positionChatCanvasFab();
        if (act.chatCanvasFab.getParent() == null) {
            act.rootLayout.addView(act.chatCanvasFab, chatCanvasFabLp);
        } else {
            act.chatCanvasFab.setLayoutParams(chatCanvasFabLp);
        }
        updateChatCanvasFabVisibility();
        refreshChatRunBanner();
    }

    private void positionChatCanvasFab() {
        if (chatCanvasFabLp == null) return;
        chatCanvasFabLp.gravity = Gravity.CENTER_VERTICAL
                | (act.chatOnLeft ? Gravity.START : Gravity.END);
        chatCanvasFabLp.leftMargin = act.chatOnLeft ? act.dp(MainActivity.SPACE_LG) : 0;
        chatCanvasFabLp.rightMargin = act.chatOnLeft ? 0 : act.dp(MainActivity.SPACE_LG);
        chatCanvasFabLp.topMargin = 0;
        chatCanvasFabLp.bottomMargin = 0;
    }

    private void updateChatCanvasFabVisibility() {
        if (act.chatCanvasFab != null) {
            act.chatCanvasFab.setVisibility(act.chatCollapsed && act.aiEnabled ? View.VISIBLE : View.GONE);
        }
    }

    void updateChatDragHeaderTitle() {
        // Session title intentionally hidden in drag header.
    }

    private void setChatResizeHandleVisible(boolean visible) {
        if (act.chatResizeHandle == null) return;
        act.chatResizeHandle.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (chatResizeGrip != null) {
            chatResizeGrip.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** True only when the panel is fully open (not mid-slide / collapsing). */
    private boolean isChatFullyExpanded() {
        if (act.chatCollapsed || act.chatPanel == null || chatPanelDragging) return false;
        return Math.abs(act.chatPanel.getTranslationX()) < 0.5f;
    }

    /**
     * Resize pill: visible only while fully expanded (or actively resizing).
     * Never revealed by WebView scroll / casual chat touches.
     */
    void syncChatResizeHandleVisibility() {
        boolean show = !act.compactScreen() && (act.chatResizing || isChatFullyExpanded());
        setChatResizeHandleVisible(show);
        if (show) positionChatResizeHandle();
    }

    /**
     * The All chats drawer never outgrows the chat it sits in: at most its usual
     * width, and at most most of the panel so a strip of scrim stays tappable.
     */
    private int chatTabsDrawerWidth() {
        int w = act.dp(CHAT_TABS_DRAWER_W);
        int panel = act.chatPanelLp != null && act.chatPanelLp.width > 0 ? chatPanelWidth() : w;
        return Math.max(act.dp(160), Math.min(w, Math.round(panel * 0.85f)));
    }

    private void showChatTabsOverlay() {
        if (chatTabsOverlay == null || act.chatTabsDrawer == null || act.chatCollapsed) return;
        ViewGroup.LayoutParams dlp = act.chatTabsDrawer.getLayoutParams();
        if (dlp != null && dlp.width != chatTabsDrawerWidth()) {
            dlp.width = chatTabsDrawerWidth();
            act.chatTabsDrawer.setLayoutParams(dlp);
        }
        act.conversations.refreshChatTabs();
        chatTabsOpen = true;
        chatTabsOverlay.setVisibility(View.VISIBLE);
        chatTabsOverlay.setElevation(act.dp(16));
        chatTabsOverlay.setTranslationZ(act.dp(8));
        chatTabsOverlay.bringToFront();
        if (act.chatResizeHandle != null) act.chatResizeHandle.bringToFront();
        float off = -chatTabsDrawerWidth();
        act.chatTabsDrawer.setTranslationX(off);
        if (chatTabsAnim != null) chatTabsAnim.cancel();
        chatTabsAnim = ValueAnimator.ofFloat(off, 0f);
        chatTabsAnim.setDuration(200);
        chatTabsAnim.setInterpolator(new DecelerateInterpolator());
        chatTabsAnim.addUpdateListener(a -> act.chatTabsDrawer.setTranslationX((Float) a.getAnimatedValue()));
        chatTabsAnim.start();
    }

    void hideChatTabsOverlay() {
        if (chatTabsOverlay == null || act.chatTabsDrawer == null) return;
        if (chatTabsOverlay.getVisibility() != View.VISIBLE) {
            chatTabsOpen = false;
            return;
        }
        chatTabsOpen = false;
        float off = -chatTabsDrawerWidth();
        if (chatTabsAnim != null) chatTabsAnim.cancel();
        chatTabsAnim = ValueAnimator.ofFloat(act.chatTabsDrawer.getTranslationX(), off);
        chatTabsAnim.setDuration(180);
        chatTabsAnim.setInterpolator(new DecelerateInterpolator());
        chatTabsAnim.addUpdateListener(a -> act.chatTabsDrawer.setTranslationX((Float) a.getAnimatedValue()));
        chatTabsAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                chatTabsOverlay.setVisibility(View.GONE);
            }
        });
        chatTabsAnim.start();
    }

    private void attachChatSlideDrag(View dragRegion) {
        final int slop = ViewConfiguration.get(act).getScaledTouchSlop();
        dragRegion.setOnTouchListener((v, event) -> {
            if (act.chatPanel == null || act.chatResizing) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (act.chatSlideAnim != null) act.chatSlideAnim.cancel();
                    if (act.chatVelocityTracker != null) act.chatVelocityTracker.recycle();
                    act.chatVelocityTracker = VelocityTracker.obtain();
                    act.chatVelocityTracker.addMovement(event);
                    chatPanelDragStartRawX = event.getRawX();
                    chatPanelDragStartRawY = event.getRawY();
                    chatSlideStartRawX = event.getRawX();
                    chatSlideStartTx = act.chatPanel.getTranslationX();
                    chatPanelDragging = false;
                    if (chatTabsOpen) hideChatTabsOverlay();
                    try {
                        v.requestUnbufferedDispatch(event);
                    } catch (Throwable ignored) {
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (act.chatVelocityTracker != null) act.chatVelocityTracker.addMovement(event);
                    float dx = event.getRawX() - chatPanelDragStartRawX;
                    float dy = event.getRawY() - chatPanelDragStartRawY;
                    if (!chatPanelDragging) {
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                        if (Math.abs(dx) <= Math.abs(dy) * 1.2f) return true;
                        chatPanelDragging = true;
                        setChatResizeHandleVisible(false);
                    }
                    float next = chatSlideStartTx + (event.getRawX() - chatSlideStartRawX);
                    setChatTranslation(next);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float vx = 0f;
                    if (act.chatVelocityTracker != null) {
                        act.chatVelocityTracker.addMovement(event);
                        act.chatVelocityTracker.computeCurrentVelocity(1000);
                        vx = act.chatVelocityTracker.getXVelocity();
                        act.chatVelocityTracker.recycle();
                        act.chatVelocityTracker = null;
                    }
                    if (chatPanelDragging) snapChatFromGesture(vx);
                    chatPanelDragging = false;
                    syncChatResizeHandleVisibility();
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private void attachChatWebHorizontalDrag(WebView web) {
        final int slop = ViewConfiguration.get(act).getScaledTouchSlop();
        final int outerCloseZone = act.dp(56);
        web.setOnTouchListener((v, event) -> {
            if (act.chatPanel == null || act.chatCollapsed || act.chatResizing) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    chatPanelDragStartRawX = event.getRawX();
                    chatPanelDragStartRawY = event.getRawY();
                    chatSlideStartRawX = event.getRawX();
                    chatSlideStartTx = act.chatPanel.getTranslationX();
                    chatPanelDragging = false;
                    act.chatWebTouchScrollXState = 0;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - chatPanelDragStartRawX;
                    float dy = event.getRawY() - chatPanelDragStartRawY;
                    if (!chatPanelDragging) {
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
                        // Vertical scroll / text selection — leave to WebView.
                        if (Math.abs(dx) <= Math.abs(dy) * 1.15f) return false;
                        // Rightward from the main chat area opens the switcher (panel
                        // intercept). Only steal for panel-close when:
                        //  - chat on left + leftward, or
                        //  - chat on right + rightward starting near the outer edge.
                        boolean towardClose = act.chatOnLeft ? dx < 0f : dx > 0f;
                        if (!towardClose) return false;
                        if (!act.chatOnLeft) {
                            int[] loc = new int[2];
                            web.getLocationOnScreen(loc);
                            float localX = chatPanelDragStartRawX - loc[0];
                            if (localX < web.getWidth() - outerCloseZone) {
                                return false; // let switcher intercept handle it
                            }
                        }
                        int hDir = dx > 0f ? -1 : 1;
                        int scrollState = act.chatWebTouchScrollXState;
                        // Wide content — never steal; let the page scroll horizontally.
                        if (scrollState == 2 || web.canScrollHorizontally(hDir)) {
                            return false;
                        }
                        // Brief wait so the JS touchstart bridge can report scrollability.
                        if (scrollState == 0 && Math.abs(dx) < slop * 3) {
                            return false;
                        }
                        beginChatWebPanelCloseDrag(web, event);
                    } else if (act.chatVelocityTracker != null) {
                        act.chatVelocityTracker.addMovement(event);
                    }
                    setChatTranslation(chatSlideStartTx + (event.getRawX() - chatSlideStartRawX));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!chatPanelDragging) return false;
                    float vx = 0f;
                    if (act.chatVelocityTracker != null) {
                        act.chatVelocityTracker.addMovement(event);
                        act.chatVelocityTracker.computeCurrentVelocity(1000);
                        vx = act.chatVelocityTracker.getXVelocity();
                        act.chatVelocityTracker.recycle();
                        act.chatVelocityTracker = null;
                    }
                    snapChatFromGesture(vx);
                    endChatWebPanelCloseDrag();
                    syncChatResizeHandleVisibility();
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /** Take over the gesture for panel close: cancel WebView selection/scroll, block further selects. */
    private void beginChatWebPanelCloseDrag(WebView web, MotionEvent event) {
        chatPanelDragging = true;
        setChatResizeHandleVisible(false);
        if (act.chatSlideAnim != null) act.chatSlideAnim.cancel();
        if (act.chatVelocityTracker != null) act.chatVelocityTracker.recycle();
        act.chatVelocityTracker = VelocityTracker.obtain();
        act.chatVelocityTracker.addMovement(event);

        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            web.onTouchEvent(cancel);
        } catch (Throwable ignored) {
        } finally {
            cancel.recycle();
        }
        act.conversations.evalChatJs(
                "try{"
                        + "if(window.getSelection)window.getSelection().removeAllRanges();"
                        + "document.documentElement.style.webkitUserSelect='none';"
                        + "document.body.style.webkitUserSelect='none';"
                        + "document.documentElement.style.userSelect='none';"
                        + "document.body.style.userSelect='none';"
                        + "}catch(e){}");
    }

    private void endChatWebPanelCloseDrag() {
        chatPanelDragging = false;
        act.chatWebTouchScrollXState = 0;
        act.conversations.evalChatJs(
                "try{"
                        + "document.documentElement.style.webkitUserSelect='';"
                        + "document.body.style.webkitUserSelect='';"
                        + "document.documentElement.style.userSelect='';"
                        + "document.body.style.userSelect='';"
                        + "}catch(e){}");
    }

    private void applyChatPanelWidth(int w) {
        act.chatPanelWidthPx = clampChatWidth(w);
        if (act.chatPanelLp != null && act.chatPanel != null) {
            act.chatPanel.setScaleX(1f);
            act.chatPanelLp.width = act.chatPanelWidthPx;
            act.chatPanel.setLayoutParams(act.chatPanelLp);
        }
        if (act.chatCollapsed && act.chatPanel != null) {
            act.chatPanel.setTranslationX(chatClosedTranslation());
        }
        positionChatResizeHandle();
        syncChatResizeHandleVisibility();
        act.updateCenterPaneInsets();
        act.updateToolPillPosition();
    }

    private void layoutChatShellChildren() {
        if (act.chatShell == null || act.chatContentCol == null) return;
        act.chatShell.removeAllViews();
        act.chatShell.addView(act.chatContentCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (act.chatPanel != null) {
            ensureChatScrimOnPanel(act.chatPanel);
        }
        positionChatResizeHandle();
        layoutChatOverlayViews();
    }

    private void ensureChatScrimOnPanel(FrameLayout panel) {
        if (panel == null) return;
        if (chatScrim == null) {
            chatScrim = new View(act);
            chatScrim.setClickable(false);
            chatScrim.setFocusable(false);
        }
        if (chatScrim.getParent() instanceof ViewGroup) {
            ((ViewGroup) chatScrim.getParent()).removeView(chatScrim);
        }
        panel.addView(chatScrim, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshChatScrim();
    }

    private void refreshChatScrim() {
        refreshChatPanelBackground();
    }

    /** Solid chat chrome with canvas-facing corners rounded to match bubble radius. */
    void refreshChatPanelBackground() {
        float r = act.dp(MainActivity.CHAT_BG_CORNER);
        float[] radii = chatCornerRadii(r);
        GradientDrawable g = new GradientDrawable();
        g.setColor(act.M3_SURFACE_CONTAINER);
        g.setCornerRadii(radii);
        if (chatScrim != null) {
            chatScrim.setBackground(g);
        }
        if (act.chatPanel != null) {
            act.chatPanel.setElevation(0f);
            // Keep panel itself transparent; scrim paints the rounded fill.
            act.chatPanel.setBackgroundColor(0x00000000);
            applyChatPanelClip(act.chatPanel);
        }
    }

    private float[] chatCornerRadii(float r) {
        return act.chatOnLeft
                ? new float[]{0, 0, r, r, r, r, 0, 0}
                : new float[]{r, r, 0, 0, 0, 0, r, r};
    }

    /** Clip message content to the rounded shell; keep panel itself unclipped so overlays stay hittable. */
    private void applyChatPanelClip(FrameLayout panel) {
        if (panel != null) {
            panel.setClipToOutline(false);
            panel.setClipChildren(false);
            panel.setClipToPadding(false);
        }
        View clipTarget = act.chatContentCol != null ? act.chatContentCol : act.chatShell;
        if (clipTarget == null) return;
        clipTarget.setClipToOutline(true);
        if (clipTarget instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) clipTarget;
            vg.setClipChildren(true);
            vg.setClipToPadding(true);
        }
        clipTarget.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w <= 0 || h <= 0) return;
                float r = act.dp(MainActivity.CHAT_BG_CORNER);
                Path path = new Path();
                path.addRoundRect(0, 0, w, h, chatCornerRadii(r), Path.Direction.CW);
                outline.setPath(path);
            }
        });
        clipTarget.invalidateOutline();
    }

    /**
     * Handle lives on {@link MainActivity#rootLayout} (not inside the chat panel) so the part that
     * sticks out onto the canvas still receives touches — children outside a parent
     * are not hittable.
     */
    void positionChatResizeHandle() {
        if (act.rootLayout == null || act.chatResizeHandle == null) return;
        if (act.chatCollapsed || act.chatPanel == null) {
            setChatResizeHandleVisible(false);
            return;
        }
        int hw = act.dp(MainActivity.CHAT_RESIZE_HANDLE_W);
        int hh = act.dp(MainActivity.CHAT_RESIZE_HANDLE_H);
        int rootW = act.rootLayout.getWidth();
        int rootH = act.rootLayout.getHeight();
        if (rootW <= 0 || rootH <= 0) {
            act.rootLayout.post(this::positionChatResizeHandle);
            return;
        }
        int chatW = chatPanelWidth();
        float tx = act.chatPanel.getTranslationX();
        // Canvas-facing edge of the chat in root coordinates.
        int edgeX = act.chatOnLeft
                ? Math.round(chatW + tx)
                : Math.round(rootW - chatW + tx);
        int left = edgeX - hw / 2;
        left = Math.max(0, Math.min(left, rootW - hw));
        int top = Math.max(0, (rootH - hh) / 2);

        if (chatResizeHandleLp == null) {
            chatResizeHandleLp = new FrameLayout.LayoutParams(hw, hh);
        }
        chatResizeHandleLp.width = hw;
        chatResizeHandleLp.height = hh;
        chatResizeHandleLp.gravity = Gravity.TOP | Gravity.START;
        chatResizeHandleLp.leftMargin = left;
        chatResizeHandleLp.topMargin = top;
        chatResizeHandleLp.rightMargin = 0;
        chatResizeHandleLp.bottomMargin = 0;

        if (act.chatResizeHandle.getParent() != act.rootLayout) {
            if (act.chatResizeHandle.getParent() instanceof ViewGroup) {
                ((ViewGroup) act.chatResizeHandle.getParent()).removeView(act.chatResizeHandle);
            }
            act.rootLayout.addView(act.chatResizeHandle, chatResizeHandleLp);
        } else {
            act.chatResizeHandle.setLayoutParams(chatResizeHandleLp);
        }
        if (chatResizeGrip != null) {
            FrameLayout.LayoutParams gripLp = (FrameLayout.LayoutParams) chatResizeGrip.getLayoutParams();
            if (gripLp == null) {
                gripLp = new FrameLayout.LayoutParams(act.dp(MainActivity.CHAT_RESIZE_PILL_W), act.dp(MainActivity.CHAT_RESIZE_PILL_H));
            }
            gripLp.width = act.dp(MainActivity.CHAT_RESIZE_PILL_W);
            gripLp.height = act.dp(MainActivity.CHAT_RESIZE_PILL_H);
            gripLp.gravity = Gravity.CENTER;
            gripLp.leftMargin = 0;
            gripLp.rightMargin = 0;
            chatResizeGrip.setLayoutParams(gripLp);
        }
        refreshChatHandleLook();
        if (act.chatResizeHandle.getVisibility() == View.VISIBLE) {
            act.chatResizeHandle.bringToFront();
        }
    }

    private void layoutChatResizeHandleOnPanel(FrameLayout panel) {
        // Legacy entry point — handle is positioned on rootLayout now.
        positionChatResizeHandle();
    }

    void refreshChatHandleLook() {
        if (act.chatResizeHandle != null) {
            // Transparent hit target — only the thin pill is painted.
            act.chatResizeHandle.setBackgroundColor(0x00000000);
        }
        if (chatResizeGrip != null) {
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(act.dp(999));
            pill.setColor(act.M3_ON_SURFACE_VARIANT);
            chatResizeGrip.setBackground(pill);
            FrameLayout.LayoutParams gripLp = (FrameLayout.LayoutParams) chatResizeGrip.getLayoutParams();
            if (gripLp == null) {
                gripLp = new FrameLayout.LayoutParams(act.dp(MainActivity.CHAT_RESIZE_PILL_W), act.dp(MainActivity.CHAT_RESIZE_PILL_H));
            }
            gripLp.width = act.dp(MainActivity.CHAT_RESIZE_PILL_W);
            gripLp.height = act.dp(MainActivity.CHAT_RESIZE_PILL_H);
            gripLp.gravity = Gravity.CENTER;
            chatResizeGrip.setLayoutParams(gripLp);
        }
        if (chatScrim != null) {
            refreshChatScrim();
        }
        if (chatResizePlaceholderIcon != null) {
            chatResizePlaceholderIcon.setColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN);
        }
    }

    private FrameLayout buildChatResizePlaceholder() {
        FrameLayout wrap = new FrameLayout(act);
        wrap.setVisibility(View.GONE);
        wrap.setClickable(false);
        wrap.setFocusable(false);
        wrap.setBackgroundColor(0x00000000);

        ImageView icon = new ImageView(act);
        chatResizePlaceholderIcon = icon;
        icon.setImageResource(R.drawable.ic_chat);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN);
        // Icon only — no filled bubble behind it while resizing.
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(act.dp(56), act.dp(56));
        iconLp.gravity = Gravity.CENTER;
        wrap.addView(icon, iconLp);
        return wrap;
    }

    private View buildChatResizeHandle() {
        FrameLayout handle = new FrameLayout(act);
        handle.setClickable(true);
        handle.setFocusable(true);
        handle.setContentDescription("Drag to resize chat");
        handle.setVisibility(View.INVISIBLE);
        handle.setElevation(0f);

        View pill = new View(act);
        chatResizeGrip = pill;
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                act.dp(MainActivity.CHAT_RESIZE_PILL_W), act.dp(MainActivity.CHAT_RESIZE_PILL_H));
        gripLp.gravity = Gravity.CENTER;
        handle.addView(pill, gripLp);
        refreshChatHandleLook();

        handle.setOnTouchListener((v, event) -> {
            if (act.chatPanel == null || act.chatCollapsed) return false;
            chatResizeLastRawX = event.getRawX();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    chatPanelDragging = false;
                    beginChatResize();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!act.chatResizing) return true;
                    float dx = event.getRawX() - chatResizeStartRawX;
                    int delta = act.chatOnLeft ? Math.round(dx) : Math.round(-dx);
                    updateChatResizePreview(chatResizeStartWidth + delta);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (act.chatResizing) {
                        endChatResize();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
        return handle;
    }

    private void beginChatResize() {
        act.chatResizing = true;
        chatPanelDragging = false;
        chatResizeStartWidth = chatPanelWidth();
        chatResizePendingWidth = chatResizeStartWidth;
        chatResizeStartRawX = chatResizeLastRawX;
        // Keep composer (bottom); swap messages for a centered chat icon.
        if (act.chatWeb != null) act.chatWeb.setVisibility(View.GONE);
        if (act.attachScroll != null) act.attachScroll.setVisibility(View.GONE);
        if (chatResizePlaceholder != null) chatResizePlaceholder.setVisibility(View.VISIBLE);
        setChatResizeHandleVisible(true);
        positionChatResizeHandle();
        if (act.chatResizeHandle != null) act.chatResizeHandle.bringToFront();
    }

    private void endChatResize() {
        act.chatResizing = false;
        applyChatPanelWidth(chatResizePendingWidth);
        if (chatResizePlaceholder != null) chatResizePlaceholder.setVisibility(View.GONE);
        if (act.chatWeb != null) act.chatWeb.setVisibility(View.VISIBLE);
        if (act.attachScroll != null && act.pendingAttachments != null && !act.pendingAttachments.isEmpty()) {
            act.attachScroll.setVisibility(View.VISIBLE);
        }
        if (act.chatPanel != null) act.chatPanel.setScaleX(1f);
        positionChatResizeHandle();
        act.persistence.scheduleSave();
        syncChatResizeHandleVisibility();
        act.updateToolPillPosition();
    }

    private void updateChatResizePreview(int pendingWidth) {
        // Wider than fits pushes the explorer/editor; the chat stops only once they
        // are at their minimum.
        act.chatPanelWidthPx = clampChatWidth(pendingWidth);
        act.rebudgetPanelWidths(act.chatPanel);
        chatResizePendingWidth = chatPanelWidth();
        applyChatPanelWidth(chatResizePendingWidth);
        positionChatResizeHandle();
        if (act.chatResizeHandle != null) act.chatResizeHandle.bringToFront();
    }

    private void resetChatResizePreview() {
        if (chatResizePlaceholder != null) chatResizePlaceholder.setVisibility(View.GONE);
        if (act.chatWeb != null) act.chatWeb.setVisibility(View.VISIBLE);
        if (act.attachScroll != null && act.pendingAttachments != null && !act.pendingAttachments.isEmpty()) {
            act.attachScroll.setVisibility(View.VISIBLE);
        }
        if (act.chatPanel != null) act.chatPanel.setScaleX(1f);
        act.chatResizing = false;
    }

    /** Closed translation hides the panel fully off-screen. */
    float chatClosedTranslation() {
        return act.chatOnLeft ? -chatPanelWidth() : chatPanelWidth();
    }

    private void setChatTranslation(float tx) {
        if (act.chatPanel == null) return;
        float closed = chatClosedTranslation();
        if (act.chatOnLeft) {
            if (tx > 0f) tx = 0f;
            if (tx < closed) tx = closed;
        } else {
            if (tx < 0f) tx = 0f;
            if (tx > closed) tx = closed;
        }
        act.chatPanel.setTranslationX(tx);
        if (Math.abs(tx) > 0.5f) {
            setChatResizeHandleVisible(false);
        }
        positionChatResizeHandle();
        act.updateToolPillPosition();
    }

    private void snapChatFromGesture(float velocityX) {
        float closed = chatClosedTranslation();
        float tx = act.chatPanel != null ? act.chatPanel.getTranslationX() : closed;
        float progress; // 0 = open, 1 = closed
        if (Math.abs(closed) < 1f) {
            progress = act.chatCollapsed ? 1f : 0f;
        } else if (act.chatOnLeft) {
            progress = tx / closed; // closed negative
        } else {
            progress = tx / closed;
        }
        boolean close;
        float fling = act.chatOnLeft ? -velocityX : velocityX; // positive = toward close
        if (Math.abs(fling) > 800f) {
            close = fling > 0f;
        } else {
            close = progress > 0.45f;
        }
        if (close) animateChatToCollapsed();
        else animateChatToExpanded(chatPanelWidth());
    }

    void animateChatToCollapsed() {
        animateChatSlide(true);
    }

    void animateChatToExpanded(int ignoredWidth) {
        if (!act.aiEnabled || !act.computers.requireHost("Chat")) return;
        act.closeOtherPanelsIfCompact("chat");
        if (ignoredWidth > 0) act.chatPanelWidthPx = clampChatWidth(ignoredWidth);
        animateChatSlide(false);
    }

    private void animateChatSlide(boolean collapsedEnd) {
        if (act.chatPanel == null || act.chatPanelLp == null) return;
        if (act.chatSlideAnim != null) act.chatSlideAnim.cancel();
        int oldLeft = act.centerPane != null ? act.centerPane.getPaddingLeft() : 0;
        int oldRight = act.centerPane != null ? act.centerPane.getPaddingRight() : 0;
        ensureChatOverlayLayout();
        if (act.chatContentCol != null) act.chatContentCol.setVisibility(View.VISIBLE);
        if (chatExpandedBody != null) chatExpandedBody.setVisibility(View.VISIBLE);
        act.chatCollapsed = collapsedEnd;
        // Budgets depend on which panels are open.
        act.rebudgetPanelWidths(act.chatPanel);
        act.chatSlideAnim = act.slidePanelTo(
                act.chatPanel, collapsedEnd ? chatClosedTranslation() : 0f,
                this::syncChatResizeHandleVisibility);
        updateChatCanvasFabVisibility();
        if (collapsedEnd) {
            dismissChatTabsOverlayImmediate();
            setChatResizeHandleVisible(false);
        } else {
            syncChatResizeHandleVisibility();
        }
        positionChatResizeHandle();
        act.updateCenterPaneInsets();
        act.persistence.scheduleSave();
    }

    private void ensureChatOverlayLayout() {
        if (act.chatPanel == null || act.chatPanelLp == null) return;
        int w = chatPanelWidth();
        if (act.chatPanelLp.width != w) {
            act.chatPanelLp.width = w;
            act.chatPanel.setLayoutParams(act.chatPanelLp);
        }
        act.chatPanel.setVisibility(View.VISIBLE);
    }

    int chatPanelWidth() {
        if (act.chatPanelWidthPx <= 0) act.chatPanelWidthPx = act.dp(MainActivity.CHAT_PANEL_W);
        return clampChatWidth(act.chatPanelWidthPx);
    }

    private int clampChatWidth(int w) {
        if (act.compactScreen()) return act.screenWidthPx();
        int min = act.dp(MainActivity.CHAT_PANEL_MIN_W);
        int screen = act.getResources().getDisplayMetrics().widthPixels;
        int max = Math.max(min + act.dp(48), Math.round(screen * CHAT_PANEL_MAX_FRAC));
        if (w < min) return min;
        if (w > max) return max;
        return w;
    }

    /**
     * A working agent is marked by a small pulsing dot on the All chats button and on
     * the collapsed-chat button rather than a banner across the top of the chat.
     */
    void refreshChatRunBanner() {
        if (act.chatRunBanner != null) {
            act.chatRunBanner.setVisibility(View.GONE);
            act.chatRunBanner.setOnClickListener(null);
        }
        boolean busy = act.conversations.anyChatStreaming();
        setPulseDot(act.chatMoreFab, busy);
        setPulseDot(act.chatCanvasFab, busy);
    }

    private final java.util.HashMap<View, PulseDotDrawable> pulseDots = new java.util.HashMap<>();
    private final java.util.HashSet<View> pulseDotLayoutHooked = new java.util.HashSet<>();

    private void setPulseDot(View host, boolean on) {
        if (host == null) return;
        PulseDotDrawable d = pulseDots.get(host);
        if (on) {
            if (d == null) {
                d = new PulseDotDrawable();
                pulseDots.put(host, d);
                host.getOverlay().add(d);
            }
            if (pulseDotLayoutHooked.add(host)) {
                host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionPulseDot(v));
            }
            positionPulseDot(host);
            d.invalidateSelf();
        } else if (d != null) {
            d.attached = false;
            d.unscheduleSelf(d.tick);
            host.getOverlay().remove(d);
            pulseDots.remove(host);
        }
    }

    /** Bottom-right, but inside the round button (header buttons clip to their circle). */
    private void positionPulseDot(View host) {
        PulseDotDrawable d = pulseDots.get(host);
        if (d == null || host.getWidth() <= 0 || host.getHeight() <= 0) return;
        int size = act.dp(10);
        int cx = Math.round(host.getWidth() * 0.76f);
        int cy = Math.round(host.getHeight() * 0.76f);
        d.setBounds(cx - size / 2, cy - size / 2, cx - size / 2 + size, cy - size / 2 + size);
    }

    /** Self-animating dot (same breathing rhythm as the chat's activity pulse). */
    private final class PulseDotDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final Runnable tick = this::invalidateSelf;
        boolean attached = true;

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            long now = android.os.SystemClock.uptimeMillis();
            float t = (now % 1300L) / 1300f;
            float k = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * t);
            float r = Math.min(b.width(), b.height()) / 2f;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            // Halo in the surface color keeps the dot legible on the tinted button.
            paint.setColor(act.M3_SURFACE);
            paint.setAlpha(230);
            c.drawCircle(cx, cy, r, paint);
            paint.setColor(act.M3_PRIMARY);
            paint.setAlpha(Math.round(255 * (0.4f + 0.6f * k)));
            c.drawCircle(cx, cy, r * (0.5f + 0.25f * k), paint);
            // ~30 fps is plenty for a breathing dot and keeps the cost negligible.
            if (attached) scheduleSelf(tick, now + 33);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }


    void applyChatSide(boolean left) {
        act.chatOnLeft = left;
        act.explorerOnLeft = !left;
        layoutChatShellChildren();
        if (act.rootLayout == null || act.centerPane == null || act.chatPanel == null) return;
        // removeAllViews drops the settings dialog — keep a handle and put it back on top.
        View opts = act.optionsOverlay;
        if (opts != null && opts.getParent() == act.rootLayout) {
            // Moved, not closed: no exit animation.
            act.openedWindows.remove(opts);
            act.rootLayout.removeView(opts);
        }
        act.rootLayout.removeAllViews();
        act.rootLayout.addView(act.centerPane, act.centerPaneLp);
        if (act.chatPanelLp == null) {
            act.chatPanelLp = new FrameLayout.LayoutParams(
                    chatPanelWidth(), ViewGroup.LayoutParams.MATCH_PARENT);
        }
        act.chatPanelLp.width = chatPanelWidth();
        act.chatPanelLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        act.chatPanelLp.gravity = left ? Gravity.START : Gravity.END;
        act.chatPanel.setLayoutParams(act.chatPanelLp);
        act.chatPanel.setElevation(0f);
        act.chatPanel.setBackgroundColor(0x00000000);
        act.rootLayout.addView(act.chatPanel, act.chatPanelLp);
        act.explorer.ensureExplorerPanel();
        act.codeEditor.ensureEditorPanel();
        ensureChatCanvasFab();
        layoutChatOverlayViews();
        applyChatCollapsed(act.chatCollapsed);
        act.explorer.applyExplorerCollapsed(act.explorerCollapsed);
        act.codeEditor.applyEditorCollapsed(act.editorCollapsed);
        act.updateCenterPaneInsets();
        refreshChatPanelBackground();
        act.explorer.refreshExplorerPanelBackground();
        act.codeEditor.refreshEditorPanelBackground();
        positionChatResizeHandle();
        syncChatResizeHandleVisibility();
        act.codeEditor.syncEditorResizeHandleVisibility();
        act.explorer.syncExplorerResizeHandleVisibility();
        act.updateToolPillPosition();
        // removeAllViews() detaches All Projects — put it back on top.
        act.projects.reattachAllProjectsView();
        if (opts != null) {
            if (opts.getParent() instanceof ViewGroup) {
                ((ViewGroup) opts.getParent()).removeView(opts);
            }
            act.rootLayout.addView(opts, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            act.optionsOverlay = opts;
            opts.bringToFront();
        }
    }

    void applyChatWebTheme(ThemeConfig.AppTheme theme) {
        if (act.chatWeb == null || !act.chatWebReady) return;
        String bg = "transparent";
        String fg = MainActivity.toCssColor(theme.onSurface);
        String muted = MainActivity.toCssColor(theme.onSurfaceVariant);
        String primary = MainActivity.toCssColor(theme.primary);
        String highest = MainActivity.toCssColor(theme.surfaceContainerHighest);
        String outline = MainActivity.toCssColor(theme.outlineVariant);
        String surface = MainActivity.toCssColor(theme.surface);
        String codeBg = MainActivity.toCssColor(theme.surfaceContainer);
        String youBg = MainActivity.toCssColor(theme.chatYouBg);
        String agentBg = MainActivity.toCssColor(theme.chatAgentBg);
        String warnBg = MainActivity.toCssColor(theme.chatWarnBg);
        String errorBg = MainActivity.toCssColor(theme.chatErrorBg);
        String warnFg = MainActivity.toCssColor(theme.light ? 0xFFB45309 : 0xFFFBBF24);
        String errorFg = MainActivity.toCssColor(theme.light ? 0xFFB91C1C : 0xFFFCA5A5);
        String js = "(function(){var r=document.documentElement.style;"
                + "r.setProperty('--bg'," + JSONObject.quote(bg) + ");"
                + "r.setProperty('--fg'," + JSONObject.quote(fg) + ");"
                + "r.setProperty('--muted'," + JSONObject.quote(muted) + ");"
                + "r.setProperty('--primary'," + JSONObject.quote(primary) + ");"
                + "r.setProperty('--highest'," + JSONObject.quote(highest) + ");"
                + "r.setProperty('--outline'," + JSONObject.quote(outline) + ");"
                + "r.setProperty('--surface'," + JSONObject.quote(surface) + ");"
                + "r.setProperty('--surface-container'," + JSONObject.quote(codeBg) + ");"
                + "r.setProperty('--code-bg'," + JSONObject.quote(codeBg) + ");"
                + "r.setProperty('--you-bg'," + JSONObject.quote(youBg) + ");"
                + "r.setProperty('--agent-bg'," + JSONObject.quote(agentBg) + ");"
                + "r.setProperty('--warn-bg'," + JSONObject.quote(warnBg) + ");"
                + "r.setProperty('--error-bg'," + JSONObject.quote(errorBg) + ");"
                + "r.setProperty('--warn-fg'," + JSONObject.quote(warnFg) + ");"
                + "r.setProperty('--error-fg'," + JSONObject.quote(errorFg) + ");"
                + "document.documentElement.style.background='transparent';"
                + "document.body.style.background='transparent';"
                + "document.body.style.color=getComputedStyle(document.documentElement).getPropertyValue('--fg');"
                + "})()";
        act.conversations.evalChatJs(js);
    }

    void toggleChatCollapsed() {
        if (act.chatCollapsed) animateChatToExpanded(chatPanelWidth());
        else animateChatToCollapsed();
    }

    void applyChatCollapsed(boolean collapsed) {
        // The chat talks to a computer's agent: closed on the tablet's own workspace.
        if (!collapsed && (!act.computers.hasHost() || !act.aiEnabled)) collapsed = true;
        act.chatCollapsed = collapsed;
        // Budgets depend on which panels are open.
        act.rebudgetPanelWidths(act.chatPanel);
        if (act.chatSlideAnim != null) act.chatSlideAnim.cancel();
        ensureChatOverlayLayout();
        if (act.chatPanel != null) {
            act.chatPanel.setTranslationX(collapsed ? chatClosedTranslation() : 0f);
        }
        // Keep content attached — sliding uses translation only (no layout thrash).
        if (act.chatContentCol != null) act.chatContentCol.setVisibility(View.VISIBLE);
        if (chatExpandedBody != null) chatExpandedBody.setVisibility(View.VISIBLE);
        updateChatCanvasFabVisibility();
        if (collapsed) {
            dismissChatTabsOverlayImmediate();
            setChatResizeHandleVisible(false);
        } else {
            syncChatResizeHandleVisibility();
        }
        if (act.centerPane != null) act.centerPane.setTranslationX(0f);
        act.updateCenterPaneInsets();
        act.updateToolPillPosition();
    }

    private void dismissChatTabsOverlayImmediate() {
        if (chatTabsAnim != null) chatTabsAnim.cancel();
        chatTabsOpen = false;
        if (chatTabsOverlay != null) chatTabsOverlay.setVisibility(View.GONE);
        if (act.chatTabsDrawer != null) act.chatTabsDrawer.setTranslationX(-chatTabsDrawerWidth());
    }

    // ---- Chat selection → canvas: a "Drag to canvas" chip beside the selection ------

    private TextView chatSelectionChip;
    private String chatSelectionText = "";

    void showChatSelectionChip(String webId, String source, float x, float y) {
        if (act.isDead() || act.rootLayout == null) return;
        WebView web = "mini".equals(webId) && act.miniChat != null ? act.miniChat.webView() : act.chatWeb;
        boolean visible = source != null && !source.trim().isEmpty() && web != null
                && web.isShown() && (web != act.chatWeb || !act.chatCollapsed);
        if (!visible) {
            chatSelectionText = "";
            if (chatSelectionChip != null) chatSelectionChip.setVisibility(View.GONE);
            return;
        }
        chatSelectionText = source.trim();
        if (chatSelectionChip == null) {
            chatSelectionChip = buildChatSelectionChip();
        }
        styleChatSelectionChip();
        if (chatSelectionChip.getParent() == null) {
            act.rootLayout.addView(chatSelectionChip, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START));
        }
        int[] wl = new int[2];
        web.getLocationInWindow(wl);
        int[] rl = new int[2];
        act.rootLayout.getLocationInWindow(rl);
        chatSelectionChip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int cw = chatSelectionChip.getMeasuredWidth();
        int ch = chatSelectionChip.getMeasuredHeight();
        // Just below the end of the selection, kept inside the chat view.
        int left = Math.round(wl[0] - rl[0] + Math.min(x, web.getWidth()) - cw);
        int top = Math.round(wl[1] - rl[1] + Math.min(y, web.getHeight()) + act.dp(MainActivity.SPACE_LG));
        left = Math.max(wl[0] - rl[0] + act.dp(MainActivity.SPACE_SM),
                Math.min(left, wl[0] - rl[0] + web.getWidth() - cw - act.dp(MainActivity.SPACE_SM)));
        top = Math.max(wl[1] - rl[1], Math.min(top, wl[1] - rl[1] + web.getHeight() - ch));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) chatSelectionChip.getLayoutParams();
        lp.leftMargin = left;
        lp.topMargin = top;
        chatSelectionChip.setLayoutParams(lp);
        chatSelectionChip.setVisibility(View.VISIBLE);
        chatSelectionChip.bringToFront();
        chatSelectionChip.setTranslationZ(act.dp(act.zenMode ? MainActivity.ZEN_LIFT_DP + 60 : 60));
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private TextView buildChatSelectionChip() {
        TextView chip = new TextView(act);
        chip.setText("Drag to canvas");
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8));
        chip.setElevation(act.dp(6));
        chip.setClickable(true);
        chip.setContentDescription("Drag the selected text onto the canvas, or tap to place it");
        final float[] down = new float[2];
        final boolean[] dragged = new boolean[1];
        final int slop = ViewConfiguration.get(act).getScaledTouchSlop();
        chip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    dragged[0] = false;
                    return false; // keep the ripple / click
                case MotionEvent.ACTION_MOVE:
                    if (!dragged[0] && Math.hypot(e.getRawX() - down[0], e.getRawY() - down[1]) > slop) {
                        dragged[0] = true;
                        ClipData data = ClipData.newPlainText(MainActivity.DROP_TEXT_LABEL, chatSelectionText);
                        v.startDragAndDrop(data, new View.DragShadowBuilder(v), null, 0);
                        v.setPressed(false);
                        return true;
                    }
                    return dragged[0];
                default:
                    return dragged[0];
            }
        });
        // Tap: place it in the middle of the view.
        chip.setOnClickListener(v -> {
            if (!chatSelectionText.isEmpty()) act.startTextDrag(chatSelectionText);
        });
        return chip;
    }

    private void styleChatSelectionChip() {
        if (chatSelectionChip == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(999));
        bg.setColor(act.M3_PRIMARY_CONTAINER);
        chatSelectionChip.setBackground(act.withHoverRipple(bg, false));
        chatSelectionChip.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
    }

    /** Soft oval drop shadow for the chat header All/New FABs. */
    void styleChatHeaderFabShadow(ImageView fab) {
        if (fab == null) return;
        fab.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        fab.setClipToOutline(true);
        fab.setElevation(act.dp(4));
        fab.setTranslationZ(act.dp(2));
    }
}
