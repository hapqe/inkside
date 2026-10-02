package me.hapke.inkside;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Persists Inkside UI session to app-private storage. */
final class AppStateStore {
    private static final String TAG = "AppStateStore";
    static final String FILE_NAME = "session_state.json";
    private static final String BACKUP_NAME = "session_state.bak";
    private static final String TEMP_NAME = "session_state.tmp";

    /** Bumped when the persisted shape changes incompatibly. */
    static final int SCHEMA_VERSION = 1;
    private static final String KEY_VERSION = "schemaVersion";

    private final File file;
    private final File backup;
    private final File temp;

    /** Set when the last load fell back to the backup or found the state unreadable. */
    private volatile String lastLoadWarning;

    /** @param dir the profile's data folder (see {@link Profiles#filesDir}). */
    AppStateStore(File dir) {
        file = new File(dir, FILE_NAME);
        backup = new File(dir, BACKUP_NAME);
        temp = new File(dir, TEMP_NAME);
    }

    /**
     * Writes state durably: full write + fsync to a temp file, promote the previous
     * good copy to backup, then rename temp into place. A crash at any point leaves
     * either the previous state or the backup intact.
     *
     * @return null on success, else a human-readable reason the save failed.
     */
    String save(JSONObject state) {
        if (state == null) return "no state";
        try {
            state.put(KEY_VERSION, SCHEMA_VERSION);
        } catch (Exception e) {
            return "could not tag schema version: " + e.getMessage();
        }
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                // Streamed, not state.toString(): with thousands of strokes the session
                // is tens of MB, and building it as one String (plus its byte copy)
                // churned ~100MB of large objects per save — GC pauses mid-scroll.
                java.io.Writer w = new java.io.BufferedWriter(
                        new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), 64 * 1024);
                writeJson(w, state);
                w.flush();
                out.getFD().sync();
            }
            if (file.exists() && !replace(file, backup)) {
                Log.w(TAG, "could not refresh backup; continuing");
            }
            if (!replace(temp, file)) {
                throw new IOException("rename " + temp.getName() + " -> " + file.getName() + " failed");
            }
            return null;
        } catch (Throwable e) {
            // Out of memory included: a failed save is reported, never fatal.
            temp.delete();
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            Log.e(TAG, "session save failed: " + reason, e);
            return reason;
        }
    }

    /** Same compact output as {@link JSONObject#toString()}, written piece by piece. */
    static void writeJson(java.io.Writer w, Object v) throws Exception {
        if (v == null || v == JSONObject.NULL) {
            w.write("null");
        } else if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            w.write('{');
            boolean first = true;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!first) w.write(',');
                first = false;
                w.write(JSONObject.quote(k));
                w.write(':');
                writeJson(w, o.opt(k));
            }
            w.write('}');
        } else if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v;
            w.write('[');
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) w.write(',');
                writeJson(w, a.opt(i));
            }
            w.write(']');
        } else if (v instanceof Number) {
            w.write(JSONObject.numberToString((Number) v));
        } else if (v instanceof Boolean) {
            w.write(v.toString());
        } else {
            w.write(JSONObject.quote(v.toString()));
        }
    }

    private static boolean replace(File from, File to) {
        if (to.exists() && !to.delete()) return false;
        return from.renameTo(to);
    }

    /**
     * Before the first load that moves documents out of the session file, a copy of
     * the file as it was: the move is one-way, and this is the way back if it ever
     * went wrong. Made once; a session already split has nothing to keep.
     */
    private void keepPreSplitCopy(File f) {
        File copy = new File(f.getParentFile(), FILE_NAME + ".pre-split");
        if (copy.exists() || !f.isFile() || f.length() == 0) return;
        if (!mentionsInlineDocuments(f)) return;
        try (FileInputStream in = new FileInputStream(f);
             FileOutputStream out = new FileOutputStream(copy)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (Throwable t) {
            copy.delete();
            Log.e(TAG, "could not keep a pre-split copy", t);
        }
    }

    /** Whether the file still holds documents inline (looks for the key, streaming). */
    private static boolean mentionsInlineDocuments(File f) {
        byte[] needle = "\"documentStates\":{".getBytes(StandardCharsets.US_ASCII);
        try (java.io.InputStream in = new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            int matched = 0;
            int b;
            while ((b = in.read()) >= 0) {
                if (b == needle[matched]) {
                    if (++matched == needle.length) return true;
                } else {
                    matched = b == needle[0] ? 1 : 0;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Receives each document of an older session as it is read, so it never piles up. */
    interface DocumentSink {
        void document(String path, JSONObject slice);
    }

    /**
     * Loads the session, falling back to the backup if the primary is unreadable.
     * A session from before documents had their own files hands each document to
     * {@code docs} while reading, instead of returning them.
     */
    JSONObject load(DocumentSink docs) {
        lastLoadWarning = null;
        keepPreSplitCopy(file);
        JSONObject primary = readFile(file, docs);
        if (primary != null) return primary;

        boolean hadPrimary = file.exists() && file.length() > 0;
        JSONObject fallback = readFile(backup, docs);
        if (fallback != null) {
            lastLoadWarning = hadPrimary
                    ? "Session file was unreadable — restored the previous version."
                    : "Restored the previous session version.";
            Log.w(TAG, lastLoadWarning);
            return fallback;
        }
        if (hadPrimary) {
            // Keep it: the next autosave would otherwise write an empty session over it.
            File aside = new File(file.getParentFile(),
                    FILE_NAME + ".unreadable-" + System.currentTimeMillis());
            boolean kept = file.renameTo(aside);
            lastLoadWarning = "Saved session could not be read and no backup was available."
                    + (kept ? " The file is kept as " + aside.getName() + "." : "");
            Log.e(TAG, lastLoadWarning);
        }
        return null;
    }

    /** Longest string value kept when salvaging an oversized session (embedded images). */
    private static final int SALVAGE_MAX_STRING = 8 * 1024 * 1024;

    private JSONObject readFile(File f, DocumentSink docs) {
        try {
            return readFileStreaming(f, docs);
        } catch (OutOfMemoryError oom) {
            // A session bloated by full-size embedded images used to crash every launch
            // right here. Keep the file aside untouched and load it again without the
            // huge strings (image pixels): ink, text, pages and chats all survive.
            System.gc();
            Log.e(TAG, "session " + f.getName() + " too large to load (" + f.length() + " bytes)");
            try {
                JSONObject salvaged = readFileSalvaging(f);
                File aside = new File(f.getParentFile(),
                        f.getName() + ".oversized-" + System.currentTimeMillis());
                if (!f.renameTo(aside)) Log.w(TAG, "could not keep oversized session aside");
                lastLoadWarning = "The saved session was too large to load, so oversized images were "
                        + "left out. The original is kept as " + aside.getName() + ".";
                return salvaged;
            } catch (Throwable t) {
                Log.e(TAG, "session salvage failed", t);
                return null;
            }
        }
    }

    /**
     * Streams the file into JSON text, replacing any string value longer than
     * {@link #SALVAGE_MAX_STRING} with null. Never holds the whole original in memory.
     */
    private static JSONObject readFileSalvaging(File f) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                (int) Math.min(f.length(), 64L * 1024 * 1024));
        try (java.io.InputStream in = new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            boolean inString = false;
            boolean escape = false;
            boolean dropping = false;
            int stringStart = 0;
            int stringLen = 0;
            int b;
            while ((b = in.read()) >= 0) {
                if (!inString) {
                    if (b == '"') {
                        inString = true;
                        escape = false;
                        dropping = false;
                        stringStart = out.size();
                        stringLen = 0;
                    }
                    out.write(b);
                    continue;
                }
                boolean closing = !escape && b == '"';
                escape = !escape && b == '\\';
                if (closing) {
                    inString = false;
                    if (dropping) out.write("null".getBytes(StandardCharsets.US_ASCII));
                    else out.write(b);
                    continue;
                }
                if (dropping) continue;
                stringLen++;
                if (stringLen > SALVAGE_MAX_STRING) {
                    // Rewind to before the opening quote; the value becomes null.
                    byte[] kept = out.toByteArray();
                    out.reset();
                    out.write(kept, 0, stringStart);
                    dropping = true;
                    continue;
                }
                out.write(b);
            }
        }
        return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * Reads the session a value at a time. Documents under "documentStates" go to
     * {@code docs} one by one (when given), and stroke samples saved the old way —
     * {@code [[x,y,w],...]} — are packed as they are read (see
     * CodeCanvasView.packSamples). Together that keeps a heavily inked session from
     * ever being in memory as one JSON tree, which is what ran the heap out at launch.
     */
    private static JSONObject readFileStreaming(File f, DocumentSink docs) {
        if (f == null || !f.exists() || f.length() == 0) return null;
        try (android.util.JsonReader r = new android.util.JsonReader(new java.io.InputStreamReader(
                new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16),
                StandardCharsets.UTF_8))) {
            r.setLenient(true);
            JSONObject out = new JSONObject();
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (docs != null && "documentStates".equals(name)
                        && r.peek() == android.util.JsonToken.BEGIN_OBJECT) {
                    r.beginObject();
                    while (r.hasNext()) {
                        String path = r.nextName();
                        Object slice = readValue(r);
                        if (slice instanceof JSONObject) docs.document(path, (JSONObject) slice);
                    }
                    r.endObject();
                    out.put("documentStatesExternal", true);
                } else {
                    out.put(name, readValue(r));
                }
            }
            r.endObject();
            return out;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception e) {
            Log.w(TAG, "unreadable session file " + f.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static Object readValue(android.util.JsonReader r) throws Exception {
        switch (r.peek()) {
            case BEGIN_OBJECT: {
                JSONObject o = new JSONObject();
                r.beginObject();
                while (r.hasNext()) {
                    String name = r.nextName();
                    if ("samples".equals(name) && r.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                        o.put("packed", readPackedSamples(r));
                    } else {
                        o.put(name, readValue(r));
                    }
                }
                r.endObject();
                return o;
            }
            case BEGIN_ARRAY: {
                org.json.JSONArray a = new org.json.JSONArray();
                r.beginArray();
                while (r.hasNext()) a.put(readValue(r));
                r.endArray();
                return a;
            }
            case STRING:
                return r.nextString();
            case NUMBER: {
                String raw = r.nextString();
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    try {
                        long v = Long.parseLong(raw);
                        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
                        return v;
                    } catch (NumberFormatException ignored) {
                        // Too long for a long: fall through to double.
                    }
                }
                return Double.parseDouble(raw);
            }
            case BOOLEAN:
                return r.nextBoolean();
            case NULL:
                r.nextNull();
                return JSONObject.NULL;
            default:
                r.skipValue();
                return JSONObject.NULL;
        }
    }

    /** {@code [[x,y,w],...]} straight to the packed form, without a JSON tree in between. */
    private static String readPackedSamples(android.util.JsonReader r) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] rec = new byte[12];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(rec).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) {
                r.skipValue();
                continue;
            }
            r.beginArray();
            float[] v = {Float.NaN, Float.NaN, 4f};
            for (int i = 0; r.hasNext(); i++) {
                if (i < 3 && r.peek() == android.util.JsonToken.NUMBER) v[i] = (float) r.nextDouble();
                else r.skipValue();
            }
            r.endArray();
            if (Float.isNaN(v[0]) || Float.isNaN(v[1])) continue;
            buf.clear();
            buf.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
            bytes.write(rec, 0, 12);
        }
        r.endArray();
        return android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
    }

    /** Non-null when the last {@link #load} fell back or failed; for surfacing to the user. */
    String consumeLoadWarning() {
        String w = lastLoadWarning;
        lastLoadWarning = null;
        return w;
    }

    boolean hasState() {
        return (file.exists() && file.length() > 0) || (backup.exists() && backup.length() > 0);
    }
}
