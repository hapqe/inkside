package me.hapke.inkside;

import android.content.ClipData;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import java.io.InputStream;

/**
 * Drag and drop onto the canvas: chat text, workspace files and images.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class CanvasDrops {
    private final MainActivity act;

    /** Prefix for native drag localState when dropping chat text onto the canvas. */
    private static final String DROP_TEXT_PREFIX = "codingcanvas:text:";

    CanvasDrops(MainActivity act) {
        this.act = act;
    }

    void setupCanvasDragDrop(CodeCanvasView target) {
        target.setOnDragListener((v, event) -> {
            int action = event.getAction();
            if (action == android.view.DragEvent.ACTION_DROP) {
                try {
                    return handleCanvasDrop(event);
                } catch (Throwable t) {
                    // A drop must never take the app down; say so and keep going.
                    Log.e(MainActivity.TAG, "canvas drop failed", t);
                    act.snackbar("Could not place that on the canvas", false);
                    return true;
                }
            }
            return action == android.view.DragEvent.ACTION_DRAG_STARTED
                    || action == android.view.DragEvent.ACTION_DRAG_ENTERED
                    || action == android.view.DragEvent.ACTION_DRAG_LOCATION;
        });
    }

    private boolean handleCanvasDrop(android.view.DragEvent event) {
        {
            {
                if (act.canvas == null) return true;
                float[] w0 = act.canvas.worldFromScreen(event.getX(), event.getY());
                // worldFromScreen hands back a shared scratch array — copy it.
                float[] w = {w0[0], w0[1]};
                Object local = event.getLocalState();
                if (local instanceof FolderExplorerView.ExplorerDrag) {
                    handleCanvasWorkspaceDrop(local.toString(), w[0], w[1]);
                    return true;
                }
                if (local instanceof String) {
                    handleCanvasWorkspaceDrop((String) local, w[0], w[1]);
                    return true;
                }
                ClipData clip = event.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    ClipData.Item item = clip.getItemAt(0);
                    if (item.getUri() != null) {
                        addImageFromUriAt(item.getUri(), w[0], w[1]);
                        return true;
                    }
                    if (item.getText() != null) {
                        String text = item.getText().toString();
                        if (isCanvasTextClip(clip) || !looksLikeWorkspacePath(text)) {
                            placeDroppedText(text, w[0], w[1]);
                            return true;
                        }
                        handleCanvasWorkspaceDrop(text, w[0], w[1]);
                        return true;
                    }
                }
            }
        }
        return true;
    }

    /** Text dragged in from elsewhere (e.g. the chat's own text drag) vs. a file path. */
    private static boolean looksLikeWorkspacePath(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 400 || t.indexOf('\n') >= 0 || t.indexOf('$') >= 0) return false;
        if ("textfield".equals(t) || t.startsWith(DROP_TEXT_PREFIX)) return true;
        int slash = t.lastIndexOf('/');
        String leaf = slash >= 0 ? t.substring(slash + 1) : t;
        return leaf.lastIndexOf('.') > 0 || slash >= 0;
    }

    private boolean isCanvasTextClip(ClipData clip) {
        if (clip == null || clip.getDescription() == null) return false;
        // Drags from other apps often carry no label at all.
        CharSequence label = clip.getDescription().getLabel();
        return label != null && MainActivity.DROP_TEXT_LABEL.contentEquals(label);
    }

    private void placeDroppedText(String text, float worldX, float worldY) {
        if (act.canvas == null || text == null) return;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        // Plain canvas text, as if typed there — not a chat card.
        act.canvas.addPlainTextField(trimmed, worldX, worldY);
        act.persistence.scheduleSave();
        act.canvasAgent.pushCanvasStateToBridge();
    }

    private void handleCanvasWorkspaceDrop(String path, float worldX, float worldY) {
        if (path == null || path.isEmpty() || act.canvas == null) return;
        if (path.startsWith(DROP_TEXT_PREFIX)) {
            placeDroppedText(path.substring(DROP_TEXT_PREFIX.length()), worldX, worldY);
            return;
        }
        if ("textfield".equals(path)) {
            act.canvas.addTextField(worldX, worldY);
            act.persistence.scheduleSave();
            return;
        }
        String lower = path.toLowerCase();
        if (lower.endsWith(".pdf")) {
            act.documents.openPdfDocument(path);
            return;
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif")) {
            addWorkspaceImageAt(path, worldX, worldY);
            return;
        }
        if (lower.endsWith(".viz")) {
            act.codeEditor.openVizFile(path);
            return;
        }
        act.codeEditor.openScriptEditor(path);
    }

    private void addWorkspaceImageAt(String path, float worldX, float worldY) {
        act.workspace.readFileBytes(path, new BridgeClient.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] value) {
                if (act.isDead()) return;
                Bitmap bmp = ImageDecode.decode(value);
                if (bmp != null && act.canvas != null) {
                    act.canvas.addImageBitmapAt(bmp, worldX, worldY, path);
                    act.persistence.scheduleSave();
                }
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                act.conversations.appendChat("warn", "image: " + message);
            }
        });
    }

    private void addImageFromUriAt(Uri uri, float worldX, float worldY) {
        try {
            ContentResolver cr = act.getContentResolver();
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return;
                byte[] data = Conversations.readAll(in, 20_000_000);
                Bitmap bmp = ImageDecode.decode(data);
                if (bmp != null && act.canvas != null) {
                    act.canvas.addImageBitmapAt(bmp, worldX, worldY);
                    act.persistence.scheduleSave();
                }
            }
        } catch (Exception e) {
            act.conversations.appendChat("warn", "image drop: " + e.getMessage());
        }
    }

    void addAssetToCanvasCenter(String path, boolean pdf) {
        if (pdf) {
            act.documents.openPdfDocument(path);
            return;
        }
        if (act.canvas == null) return;
        float[] c = act.canvas.getViewCenterWorld();
        addWorkspaceImageAt(path, c[0], c[1]);
        act.persistence.scheduleSave();
    }
}
