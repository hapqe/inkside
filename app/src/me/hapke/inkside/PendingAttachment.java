package me.hapke.inkside;

final class PendingAttachment {
    final String name;
    final String mimeType;
    final byte[] data;
    final boolean image;
    final boolean script;
    final String workspacePath;
    /** How the agent should treat it: "" (plain), "reference" or "goal" (a learning goal). */
    String role = "";

    PendingAttachment(String name, String mimeType, byte[] data, boolean image) {
        this(name, mimeType, data, image, false, null);
    }

    PendingAttachment(
            String name,
            String mimeType,
            byte[] data,
            boolean image,
            boolean script,
            String workspacePath) {
        this.name = name;
        this.mimeType = mimeType;
        this.data = data;
        this.image = image;
        this.script = script;
        this.workspacePath = workspacePath;
    }
}
