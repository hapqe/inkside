package me.hapke.inkside;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Uploads into the workspace and downloads to the tablet's Downloads folder.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class FileTransfers {
    private final MainActivity act;

    FileTransfers(MainActivity act) {
        this.act = act;
    }

    /** A file saved into Downloads: the name it really got, and where it is. */
    static final class SavedFile {
        final String name;
        final Uri uri;

        SavedFile(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    /** Any file into Downloads/Inkside via MediaStore; returns the name it really got. */
    private String saveBytesToDownloads(String name, String mime, byte[] data) throws Exception {
        return saveToDownloads(name, mime, data).name;
    }

    SavedFile saveToDownloads(String name, String mime, byte[] data) throws Exception {
        if (data == null) throw new Exception("nothing to save");
        android.content.ContentResolver cr = act.getContentResolver();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                mime != null ? mime : "application/octet-stream");
        v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/Inkside");
        v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("Downloads is not available");
        try (java.io.OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new Exception("could not open the file");
            out.write(data);
        } catch (Exception e) {
            cr.delete(uri, null, null);
            throw e;
        }
        android.content.ContentValues done = new android.content.ContentValues();
        done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        // MediaStore may have numbered it ("name (1).pdf"); report what it really is.
        try (android.database.Cursor c = cr.query(uri,
                new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        return new SavedFile(name, uri);
    }

    /** Upload limit per file (the bridge refuses bigger bodies). */
    private static final int UPLOAD_MAX_BYTES = 25_000_000;

    /** Pick files on the tablet and copy them into {@code dir} of the workspace. */
    void startUploadInto(String dir) {
        act.pendingUploadDir = dir == null || dir.isEmpty() ? "." : dir;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            // Straight to the system picker: through a chooser, some apps allow only one file.
            act.startActivityForResult(intent, MainActivity.REQ_UPLOAD_FILES);
        } catch (Exception e) {
            act.statusToast("No file picker available");
        }
    }

    void handleUploadResult(Intent data) {
        final String dir = act.pendingUploadDir != null ? act.pendingUploadDir : ".";
        act.pendingUploadDir = null;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty() || act.bridge == null) return;
        act.statusToastShort("Uploading " + uris.size() + " file" + (uris.size() == 1 ? "" : "s") + "…");
        new Thread(() -> {
            int ok = 0;
            String failure = null;
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                try {
                    String name = displayNameOf(u);
                    if (uris.size() > 1) {
                        final String step = "Uploading " + (i + 1) + " of " + uris.size() + " · " + name;
                        act.runOnUiThread(() -> {
                            if (!act.isDead()) act.statusToastShort(step);
                        });
                    }
                    byte[] bytes;
                    try (InputStream in = act.getContentResolver().openInputStream(u)) {
                        if (in == null) throw new Exception("cannot read " + name);
                        bytes = Conversations.readAll(in, UPLOAD_MAX_BYTES);
                    }
                    String target = ".".equals(dir) ? name : dir + "/" + name;
                    act.workspace.uploadFileSync(target, bytes);
                    ok++;
                } catch (Throwable t) {
                    failure = t.getMessage() != null ? t.getMessage() : t.toString();
                }
            }
            final int sent = ok;
            final String why = failure;
            act.runOnUiThread(() -> {
                if (act.isDead()) return;
                if (act.folderExplorer != null) act.folderExplorer.reloadOpenFolders();
                act.statusToast(why == null
                        ? "Uploaded " + sent + " file" + (sent == 1 ? "" : "s")
                        : "Uploaded " + sent + " of " + uris.size() + " — " + why);
            });
        }, "cc-upload").start();
    }

    private String displayNameOf(Uri uri) {
        try (android.database.Cursor c = act.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n.replace('/', '_');
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last.replace('/', '_') : "upload";
    }

    /** Copy a workspace file to the tablet's Downloads/Inkside. */
    void downloadWorkspaceFile(String path) {
        if (act.bridge == null || path == null) return;
        act.statusToastShort("Downloading…");
        act.workspace.readFileBytes(path, new BridgeClient.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] data) {
                if (act.isDead()) return;
                new Thread(() -> {
                    String msg;
                    try {
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(name);
                        String mime = ext != null
                                ? android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        ext.toLowerCase(java.util.Locale.ROOT))
                                : null;
                        msg = "Saved to Downloads/Inkside/" + saveBytesToDownloads(name, mime, data);
                    } catch (Exception e) {
                        msg = "Download failed: " + e.getMessage();
                    }
                    final String m = msg;
                    act.runOnUiThread(() -> {
                        if (!act.isDead()) act.statusToast(m);
                    });
                }, "cc-download").start();
            }

            @Override
            public void onError(String message) {
                if (!act.isDead()) act.statusToast("Download failed: " + message);
            }
        });
    }
}
