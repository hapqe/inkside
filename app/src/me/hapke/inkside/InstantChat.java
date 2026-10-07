package me.hapke.inkside;

import android.view.Gravity;
import android.widget.FrameLayout;
import org.json.JSONObject;

/**
 * Instant chat: the floating mini chat on the active chat.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class InstantChat {
    private final MainActivity act;

    private static final int MINI_CHAT_W = 360;
    private static final int MINI_CHAT_H = 440;

    InstantChat(MainActivity act) {
        this.act = act;
    }

    /**
     * Quick action: open the floating chat up-left of the pen and start listening
     * straight away. Picks arrive by several routes (a flick released with the pen
     * button, a tap on the radial), so the pen's position is read here, not passed.
     */
    void startInstantChat() {
        startInstantChat(penPointInRoot());
    }

    /** @param anchor root-layout point the window opens up-left of (the pen), or null. */
    void startInstantChat(float[] anchor) {
        if (!act.aiEnabled || !act.computers.requireHost("Instant chat")) return;
        showMiniChat(anchor);
        if (act.voiceRecorder == null && !act.voiceTranscribing) act.dictation.toggleVoiceInput();
    }

    /** The pen's last position on the canvas, in rootLayout coordinates, or null. */
    private float[] penPointInRoot() {
        if (act.canvas == null || act.rootLayout == null) return null;
        float[] p = act.canvas.getLastPenPoint();
        if (p == null) return null;
        int[] c = new int[2];
        int[] r = new int[2];
        act.canvas.getLocationInWindow(c);
        act.rootLayout.getLocationInWindow(r);
        return new float[]{p[0] + c[0] - r[0], p[1] + c[1] - r[1]};
    }

    private void showMiniChat() {
        showMiniChat(null);
    }

    private void showMiniChat(float[] anchor) {
        if (act.rootLayout == null) return;
        boolean created = false;
        if (act.miniChat == null) {
            act.miniChat = new MiniChatWindow(act, miniChatHost);
            applyMiniChatTheme();
            syncMiniChatZ();
            created = true;
        }
        int w = act.dp(MINI_CHAT_W);
        int h = act.dp(MINI_CHAT_H);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.TOP | Gravity.START;
        int rootW = act.rootLayout.getWidth();
        int rootH = act.rootLayout.getHeight();
        int edge = act.dp(12);
        int left;
        int top;
        if (anchor != null) {
            // Bottom-right corner just up-left of the pen, so the pen does not cover it.
            left = Math.round(anchor[0]) - w - act.dp(12);
            top = Math.round(anchor[1]) - h - act.dp(12);
        } else if (!created && act.miniChat.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams cur = (FrameLayout.LayoutParams) act.miniChat.getLayoutParams();
            left = cur.leftMargin;
            top = cur.topMargin;
        } else {
            left = rootW - w - act.dp(24);
            top = rootH - h - act.dp(24);
        }
        lp.leftMargin = Math.max(edge, Math.min(rootW - w - edge, left));
        lp.topMargin = Math.max(edge, Math.min(rootH - h - edge, top));
        if (created) {
            act.rootLayout.addView(act.miniChat, lp);
        } else {
            act.miniChat.setLayoutParams(lp);
            act.miniChat.resetPosition();
        }
        final MiniChatWindow shown = act.miniChat;
        shown.post(() -> shown.setKeyboardOverlap(act.keyboardOverlapPx));
        ChatSession c = act.conversations.activeChat();
        act.miniChat.setTitle(c != null ? c.title : "Chat");
        act.miniChat.setListening(act.voiceRecorder != null);
        act.miniChat.setTranscribing(act.voiceTranscribing);
    }

    private void closeMiniChat() {
        if (act.miniChat == null) return;
        // "Send" was pressed and the transcript is still on its way: closing must not
        // lose it. Finish transcribing and send through the main chat instead.
        boolean sendPending = act.miniSendAfterDictation && (act.voiceRecorder != null || act.voiceTranscribing);
        if (sendPending) {
            act.miniClosedDraft = act.miniChat.text();
            if (act.voiceRecorder != null) act.dictation.stopVoiceRecording(true);
        } else {
            if (act.voiceRecorder != null) act.dictation.stopVoiceRecording(false);
            act.miniSendAfterDictation = false;
        }
        MiniChatWindow w = act.miniChat;
        act.miniChat = null;
        act.rootLayout.removeView(w);
        w.destroy();
    }

    /**
     * Above panels and the canvas chrome; in Zen mode also above the lifted canvas
     * ({@link MainActivity#ZEN_LIFT_DP}). Only then that high — the shadow grows with the height.
     */
    void syncMiniChatZ() {
        if (act.miniChat == null) return;
        act.miniChat.setTranslationZ(act.dp(act.zenMode ? MainActivity.ZEN_LIFT_DP + 40 : 40));
    }

    void applyMiniChatTheme() {
        if (act.miniChat == null) return;
        act.miniChat.setColors(act.M3_SURFACE_CONTAINER_HIGH, act.M3_SURFACE_CONTAINER_HIGHEST,
                act.M3_ON_SURFACE, act.M3_ON_SURFACE_VARIANT, act.M3_PRIMARY,
                act.M3_PRIMARY_CONTAINER, act.M3_ON_PRIMARY_CONTAINER, act.M3_OUTLINE_VARIANT);
    }

    /** Sends through the normal chat, so both views and the transcript stay one. */
    void sendFromMiniChat(String text) {
        if (act.miniChat == null || text == null || text.trim().isEmpty()) return;
        if (act.conversations.isActiveChatStreaming()) {
            act.snackbar("Still answering — send when it is done", false);
            return;
        }
        act.chatInput.setText(text.trim());
        act.conversations.sendChat();
        if (act.chatInput.getText().length() == 0) act.miniChat.clearInput();
    }

    private final MiniChatWindow.Host miniChatHost = new MiniChatWindow.Host() {
        @Override
        public void onMiniSend(String text) {
            // Send while still dictating: finish the clip, send once it is text.
            if (act.voiceRecorder != null) {
                act.miniSendAfterDictation = true;
                act.dictation.stopVoiceRecording(true);
                return;
            }
            if (act.voiceTranscribing) {
                act.miniSendAfterDictation = true;
                return;
            }
            sendFromMiniChat(text);
        }

        @Override
        public void onMiniMic() {
            act.dictation.toggleVoiceInput();
        }

        @Override
        public void onMiniExpand() {
            String draft = act.miniChat != null ? act.miniChat.text() : "";
            closeMiniChat();
            if (!draft.isEmpty() && act.chatInput != null && act.chatInput.getText().length() == 0) {
                act.chatInput.setText(draft);
                act.chatInput.setSelection(draft.length());
            }
            if (act.chatCollapsed) act.chatView.toggleChatCollapsed();
        }

        @Override
        public void onMiniClose() {
            closeMiniChat();
        }

        @Override
        public void onMiniDiscard() {
            act.sendAfterDictation = false;
            act.miniSendAfterDictation = false;
            act.dictation.stopVoiceRecording(false);
        }

        @Override
        public void onMiniWebReady() {
            if (act.miniChat == null) return;
            act.chatView.applyChatWebTheme(ThemeConfig.appThemeById(act.appThemeId));
            act.miniChat.evalJs("setAll(" + JSONObject.quote(act.chatPlain.toString()) + ")");
            act.conversations.syncChatRunState();
        }

        @Override
        public Object miniJsBridge() {
            return new ChatJsBridge(act, "mini");
        }
    };
}
