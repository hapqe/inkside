package me.hapke.inkside;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Each document's undo/redo history, one file per document under
 * {@code files/undo_history/}, so undo still reaches back after the document was
 * closed or the app restarted. Written when leaving a document or the app, read when
 * a document is opened.
 */
final class UndoHistoryStore {
    private static final String TAG = "UndoHistoryStore";

    private final File dir;
    /** One writer, so two saves of the same document can never interleave. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    /**
     * Latest capture waiting per document. Saves come with every autosave; when they
     * pile up only the newest is encoded. A value of {@link #DELETE} removes the file.
     */
    private final java.util.Map<String, Object> pending = new java.util.HashMap<>();
    private static final Object DELETE = new Object();

    UndoHistoryStore(File base) {
        dir = new File(base, "undo_history");
    }

    /** Encodes and writes off the UI thread; a null capture removes the saved history. */
    void save(String path, CodeCanvasView.HistoryCapture latest) {
        if (path == null || path.isEmpty()) return;
        final File target = fileFor(path);
        synchronized (pending) {
            boolean queued = pending.containsKey(path);
            pending.put(path, latest != null ? latest : DELETE);
            if (queued) return;
        }
        writer.execute(() -> {
            Object next;
            synchronized (pending) {
                next = pending.remove(path);
            }
            if (next == null) return;
            CodeCanvasView.HistoryCapture capture =
                    next == DELETE ? null : (CodeCanvasView.HistoryCapture) next;
            try {
                if (capture == null) {
                    if (target.exists() && !target.delete()) Log.w(TAG, "could not delete " + target);
                    return;
                }
                JSONObject o = capture.encode();
                o.put("path", path);
                if (!dir.exists() && !dir.mkdirs()) return;
                File tmp = new File(dir, target.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if (!tmp.renameTo(target)) Log.w(TAG, "rename failed for " + target);
            } catch (Throwable e) {
                Log.w(TAG, "save " + path + ": " + e.getMessage());
            }
        });
    }

    /** Saved history for {@code path}, or null. Reads on the calling thread. */
    JSONObject load(String path) {
        if (path == null || path.isEmpty()) return null;
        File f = fileFor(path);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] raw = new byte[(int) f.length()];
            int off = 0;
            while (off < raw.length) {
                int n = in.read(raw, off, raw.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONObject o = new JSONObject(new String(raw, 0, off, StandardCharsets.UTF_8));
            return path.equals(o.optString("path")) ? o : null;
        } catch (Throwable e) {
            Log.w(TAG, "load " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** Wait (briefly) for pending writes — the app is about to be stopped. */
    void flush(long timeoutMs) {
        try {
            writer.submit(() -> {}).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private File fileFor(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return new File(dir, sb + ".json");
        } catch (Exception e) {
            return new File(dir, Integer.toHexString(path.hashCode()) + ".json");
        }
    }
}
