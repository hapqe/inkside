package me.hapke.inkside;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.InputStream;

/**
 * Clipboard paste onto the canvas and the canvas long-press menu.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class CanvasPaste {
    private final MainActivity act;

    CanvasPaste(MainActivity act) {
        this.act = act;
    }

    /** Paste clipboard image onto canvas when armed or clipboard holds an image URI. */
    boolean tryPasteImageToCanvas() {
        if (act.canvas == null) return false;
        Bitmap bmp = loadClipboardBitmap(act.canvas.isImagePasteArmed());
        if (bmp == null) return false;
        act.canvas.addImageBitmap(bmp);
        act.canvas.clearImagePasteArm();
        act.canvas.requestFocus();
        act.persistence.scheduleSave();
        return true;
    }

    /** Long-press paste: place clipboard image at world coordinates. */
    private boolean tryPasteImageAt(float worldX, float worldY) {
        if (act.canvas == null) return false;
        Bitmap bmp = loadClipboardBitmap(true);
        if (bmp == null) return false;
        act.canvas.addImageBitmapAt(bmp, worldX, worldY);
        act.canvas.clearImagePasteArm();
        act.canvas.requestFocus();
        return true;
    }

    private boolean clipboardHasPasteableContent() {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return false;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null) return false;
        if (loadClipboardBitmap(true) != null) return true;
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence t = clip.getItemAt(i).coerceToText(act);
            if (t != null && t.toString().trim().length() > 0) return true;
        }
        return false;
    }

    boolean tryPasteTextAt(float worldX, float worldY) {
        if (act.canvas == null) return false;
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return false;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence t = clip.getItemAt(i).coerceToText(act);
            if (t == null) continue;
            String s = t.toString();
            if (s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        if (sb.length() == 0) return false;
        act.canvas.addTextFieldWithText(sb.toString(), worldX, worldY);
        act.canvas.requestFocus();
        return true;
    }

    private static final int MENU_CANVAS_PASTE = 0xC11;
    private static final int MENU_CANVAS_PASTE_INK = 0xC13;
    private static final int MENU_CANVAS_DELETE_PAGE = 0xC12;
    private float canvasCtxWorldX;
    private float canvasCtxWorldY;
    private int canvasCtxPageIndex = -1;

    void showCanvasLongPressMenu(float worldX, float worldY,
                                         float screenX, float screenY) {
        if (act.canvas == null || act.isDead()) return;
        // Long-press on PDF text selects it (handles + Copy / Add to chat).
        if (act.pdfTextSelection != null && act.canvas.supportsPdfTextSelection()
                && act.pdfTextSelection.selectWordAt(worldX, worldY)) {
            return;
        }
        canvasCtxWorldX = worldX;
        canvasCtxWorldY = worldY;
        canvasCtxPageIndex = act.canvas.documentPageIndexAt(worldY);
        final boolean canPaste = clipboardHasPasteableContent();
        final boolean canPasteInk = act.canvas.hasCanvasClipboard();
        final boolean canDelete = act.canvas.canDeleteDocumentPage(canvasCtxPageIndex);
        if (!canPaste && !canPasteInk && !canDelete) return;

        M3Menu menu = new M3Menu(act);
        if (canPasteInk) {
            menu.add(R.drawable.ic_paste, "Paste handwriting", () -> {
                if (act.canvas != null && act.canvas.pasteClipboardAt(canvasCtxWorldX, canvasCtxWorldY)) {
                    act.persistence.scheduleSave();
                }
            });
        }
        if (canPaste) {
            menu.add(R.drawable.ic_paste, canPasteInk ? "Paste from clipboard" : "Paste", () -> {
                if (tryPasteImageAt(canvasCtxWorldX, canvasCtxWorldY)
                        || tryPasteTextAt(canvasCtxWorldX, canvasCtxWorldY)) {
                    act.persistence.scheduleSave();
                }
            });
        }
        if (canDelete) {
            if (!menu.isEmpty()) menu.divider();
            menu.addDestructive(R.drawable.ic_delete, "Delete page",
                    () -> act.documents.deleteDocumentPageAt(canvasCtxPageIndex));
        }
        int[] canvasLoc = new int[2];
        act.canvas.getLocationOnScreen(canvasLoc);
        menu.showAt(act.canvas, canvasLoc[0] + screenX, canvasLoc[1] + screenY);
    }

    Bitmap loadClipboardBitmap(boolean allowAnyUri) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null) return null;

        Uri imageUri = findClipboardImageUri(clip, allowAnyUri);
        if (imageUri != null) {
            Bitmap bmp = decodeBitmapFromUri(imageUri);
            if (bmp != null) return bmp;
        }

        // Some OEMs expose image/* without a URI we already tried — scan every item.
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri == null && item.getIntent() != null) {
                uri = item.getIntent().getData();
            }
            if (uri != null) {
                Bitmap bmp = decodeBitmapFromUri(uri);
                if (bmp != null) return bmp;
            }
        }
        return null;
    }

    private static boolean clipHasImageMime(ClipData clip) {
        if (clip.getDescription() == null) return false;
        for (int i = 0; i < clip.getDescription().getMimeTypeCount(); i++) {
            String mime = clip.getDescription().getMimeType(i);
            if (mime != null && mime.startsWith("image/")) return true;
        }
        return false;
    }

    private Uri findClipboardImageUri(ClipData clip, boolean armed) {
        if (!armed && !clipHasImageMime(clip)) {
            // Still accept URIs that resolve to image/* via content resolver.
        }
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri != null) {
                String mime = act.conversations.guessMime(clip, i, uri);
                if (mime != null && mime.startsWith("image/")) return uri;
                if (armed) return uri;
            }
            if (item.getIntent() != null && item.getIntent().getData() != null) {
                Uri intentUri = item.getIntent().getData();
                String mime = act.conversations.guessMime(clip, i, intentUri);
                if (mime != null && mime.startsWith("image/")) return intentUri;
                if (armed) return intentUri;
            }
        }
        return null;
    }

    private Bitmap decodeBitmapFromUri(Uri uri) {
        try {
            try (InputStream in = act.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return ImageDecode.decode(Conversations.readAll(in, 40_000_000));
            }
        } catch (Exception e) {
            act.conversations.appendChat("warn", "image paste failed: " + e.getMessage());
            return null;
        }
    }
}
