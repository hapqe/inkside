package me.hapke.inkside;

import android.webkit.JavascriptInterface;

/** JS ↔ Android bridge for artifact iframes in chat. */
final class ChatJsBridge {
    interface Host {
        String artifactUrl(String relPath);

        /** Begin a native drag of chat text onto the canvas. */
        void startTextDrag(String text);

        /** Touch began on (or inside) a horizontally scrollable chat element. */
        void reportChatTouchScrollX(boolean canScrollX);

        /** Open this artifact as a visualization in the editor panel (never as source). */
        void openArtifact(String relPath);

        /**
         * Chat text selection changed. {@code source} is the selection as markdown/TeX
         * (empty when cleared); x/y are its end in the web view's pixels.
         */
        default void onChatSelection(String webId, String source, float x, float y) {}
    }

    private final Host host;
    /** Which chat web view this bridge serves ("main" or "mini"). */
    private final String webId;

    ChatJsBridge(Host host) {
        this(host, "main");
    }

    ChatJsBridge(Host host, String webId) {
        this.host = host;
        this.webId = webId;
    }

    @JavascriptInterface
    public void onChatSelection(String source, float x, float y) {
        if (host != null) host.onChatSelection(webId, source, x, y);
    }

    @JavascriptInterface
    public String artifactUrl(String relPath) {
        return host != null ? host.artifactUrl(relPath) : "";
    }

    @JavascriptInterface
    public void startTextDrag(String text) {
        if (host != null) host.startTextDrag(text);
    }

    @JavascriptInterface
    public void reportChatTouchScrollX(boolean canScrollX) {
        if (host != null) host.reportChatTouchScrollX(canScrollX);
    }

    @JavascriptInterface
    public void openArtifact(String relPath) {
        if (host != null) host.openArtifact(relPath);
    }
}
