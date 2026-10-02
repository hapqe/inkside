package me.hapke.inkside;

final class ChatSession {
    final String id;
    String title;
    final StringBuilder log = new StringBuilder();
    String draft = "";
    long updatedAt;
    /**
     * Workspace-relative project path this chat belongs to.
     * Legacy chats get the first project on restore.
     */
    String projectPath = "";

    ChatSession(String id, String title) {
        this.id = id;
        this.title = title != null && !title.isEmpty() ? title : "Chat";
        this.updatedAt = System.currentTimeMillis();
    }
}
