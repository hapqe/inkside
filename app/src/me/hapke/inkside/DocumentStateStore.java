package me.hapke.inkside;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each document's saved ink, text, images and pages, one file per document under
 * {@code files/doc_states/}, read only when that document is opened.
 *
 * <p>All of it used to live inside the one session file, as a JSON tree held in
 * memory for the whole run and written out in full on every save. With enough
 * documents inked that no longer fit the heap: the app died loading its session at
 * launch, and later mid-save. Now memory holds the documents in use, and a save
 * writes only the ones that changed.
 *
 * <p>Methods are for the UI thread, except {@link #flush}, which the save thread runs
 * on a {@link #snapshotDirty() snapshot}.
 */
final class DocumentStateStore {
    private static final String TAG = "DocumentStateStore";
    private static final String DIR = "doc_states";
    private static final String INDEX = "index.json";

    private final File dir;
    /** Document path → file name, for every document with saved state. */
    private final Map<String, String> index = new HashMap<>();
    /** Slices in memory: the ones in use, and changed ones not yet on disk. */
    private final Map<String, JSONObject> cache = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Set<String> removed = new HashSet<>();
    private boolean indexDirty;

    DocumentStateStore(File base) {
        dir = new File(base, DIR);
        loadIndex();
    }

    private void loadIndex() {
        JSONObject o = readJson(new File(dir, INDEX));
        if (o == null) return;
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String path = it.next();
            String name = o.optString(path, "");
            if (!name.isEmpty()) index.put(path, name);
        }
    }

    File dir() {
        return dir;
    }

    /**
     * Sync changed slice files on disk (pulled from, or deleted because of, another device):
     * bring the index and memory in line. A document with an unsaved edit here keeps it.
     */
    List<String> reloadFromFiles(List<String> pulled, List<String> deleted) {
        List<String> changedPaths = new ArrayList<>();
        for (String name : pulled) {
            JSONObject slice = readJson(new File(dir, name));
            if (slice == null) continue;
            String path = slice.optString("documentPath", "");
            if (path.isEmpty() || dirty.contains(path)) continue;
            cache.remove(path);
            changedPaths.add(path);
            if (!name.equals(index.get(path))) {
                index.put(path, name);
                indexDirty = true;
            }
        }
        for (String name : deleted) {
            String found = null;
            for (Map.Entry<String, String> e : index.entrySet()) {
                if (e.getValue().equals(name)) found = e.getKey();
            }
            if (found != null && !dirty.contains(found)) {
                index.remove(found);
                cache.remove(found);
                indexDirty = true;
            }
        }
        saveIndexNow();
        return changedPaths;
    }

    boolean has(String path) {
        return path != null && (cache.containsKey(path) || index.containsKey(path));
    }

    List<String> paths() {
        Set<String> all = new HashSet<>(index.keySet());
        all.addAll(cache.keySet());
        return new ArrayList<>(all);
    }

    /** The saved slice for {@code path}, from memory or its file; null if none. */
    JSONObject get(String path) {
        if (path == null) return null;
        JSONObject slice = cache.get(path);
        if (slice != null) return slice;
        String name = index.get(path);
        if (name == null) return null;
        slice = readJson(new File(dir, name));
        if (slice != null) cache.put(path, slice);
        return slice;
    }

    void put(String path, JSONObject slice) {
        if (path == null || path.isEmpty() || slice == null) return;
        cache.put(path, slice);
        dirty.add(path);
        removed.remove(path);
        if (!index.containsKey(path)) {
            index.put(path, fileNameFor(path));
            indexDirty = true;
        }
    }

    void remove(String path) {
        if (path == null) return;
        cache.remove(path);
        dirty.remove(path);
        if (index.remove(path) != null) {
            removed.add(path);
            indexDirty = true;
        }
    }

    /** A rename or move: the saved state follows the document to its new path. */
    void move(String from, String to) {
        JSONObject slice = get(from);
        remove(from);
        if (slice != null && to != null) {
            try {
                slice.put("documentPath", to);
            } catch (Exception ignored) {
            }
            put(to, slice);
        }
    }

    /**
     * A document read out of an older, all-in-one session: written to its own file
     * straight away so it does not stay in memory. A document that already has a
     * file keeps it (that one is newer). Call {@link #saveIndexNow()} after the last.
     */
    void importNow(String path, JSONObject slice) {
        if (path == null || path.isEmpty() || slice == null || has(path)) return;
        String name = fileNameFor(path);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("no directory");
            writeAtomic(new File(dir, name), slice);
            index.put(path, name);
            indexDirty = true;
        } catch (Throwable t) {
            Log.e(TAG, "could not move " + path + " to its own file", t);
            put(path, slice);  // kept in memory; the next save writes it
        }
    }

    void saveIndexNow() {
        if (!indexDirty) return;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : index.entrySet()) o.put(e.getKey(), e.getValue());
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            writeAtomic(new File(dir, INDEX), o);
            indexDirty = false;
        } catch (Throwable t) {
            Log.e(TAG, "document index save failed", t);
        }
    }

    /** What a save has to write; taken on the UI thread, written by {@link #flush}. */
    static final class Snapshot {
        final Map<String, JSONObject> slices = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        final Set<String> deleteNames = new HashSet<>();
        Map<String, String> index;

        boolean isEmpty() {
            return slices.isEmpty() && deleteNames.isEmpty() && index == null;
        }
    }

    Snapshot snapshotDirty() {
        Snapshot snap = new Snapshot();
        for (String path : dirty) {
            JSONObject slice = cache.get(path);
            String name = index.get(path);
            if (slice == null || name == null) continue;
            snap.slices.put(path, slice);
            snap.names.put(path, name);
        }
        for (String path : removed) snap.deleteNames.add(fileNameFor(path));
        if (indexDirty) snap.index = new HashMap<>(index);
        dirty.clear();
        removed.clear();
        indexDirty = false;
        return snap;
    }

    /**
     * After a flush: drops written slices from memory unless {@code keepPath} (the
     * open document) or changed again since. They read back from disk when needed.
     */
    void release(Snapshot written, String keepPath) {
        for (Map.Entry<String, JSONObject> e : written.slices.entrySet()) {
            String path = e.getKey();
            if (path.equals(keepPath) || dirty.contains(path)) continue;
            if (cache.get(path) == e.getValue()) cache.remove(path);
        }
    }

    /** A failed flush: mark its slices changed again so the next save retries them. */
    void retry(Snapshot failed) {
        for (String path : failed.slices.keySet()) {
            if (cache.containsKey(path)) dirty.add(path);
        }
        if (failed.index != null) indexDirty = true;
    }

    /** Writes a snapshot (save thread). @return null on success, else why it failed. */
    String flush(Snapshot snap) {
        if (snap == null || snap.isEmpty()) return null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return "could not create " + dir;
            for (Map.Entry<String, JSONObject> e : snap.slices.entrySet()) {
                writeAtomic(new File(dir, snap.names.get(e.getKey())), e.getValue());
            }
            if (snap.index != null) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, String> e : snap.index.entrySet()) o.put(e.getKey(), e.getValue());
                writeAtomic(new File(dir, INDEX), o);
            }
            for (String name : snap.deleteNames) {
                if (snap.index != null && snap.index.containsValue(name)) continue;
                new File(dir, name).delete();
            }
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "document state save failed", t);
            return t.getMessage() != null ? t.getMessage() : t.toString();
        }
    }

    private static void writeAtomic(File target, JSONObject o) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            java.io.Writer w = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
            AppStateStore.writeJson(w, o);
            w.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new Exception("could not replace " + target.getName());
        if (!tmp.renameTo(target)) throw new Exception("could not write " + target.getName());
    }

    private static JSONObject readJson(File f) {
        if (!f.isFile() || f.length() == 0) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // One unreadable document must not take the app down; it opens without ink.
            Log.e(TAG, "unreadable document state " + f.getName(), t);
            return null;
        }
    }

    private static String fileNameFor(String path) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb + ".json";
        } catch (Exception e) {
            return Integer.toHexString(path.hashCode()) + ".json";
        }
    }
}
