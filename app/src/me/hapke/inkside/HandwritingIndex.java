package me.hapke.inkside;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.common.RecognitionCandidate;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext;
import com.google.mlkit.vision.digitalink.recognition.WritingArea;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device handwriting recognition (ML Kit Digital Ink) and the text index it feeds,
 * so search finds handwritten notes. Nothing leaves the tablet except the one-off
 * model download.
 *
 * <p>Each document's recognised lines live in {@code files/ink_index/}, keyed by a
 * signature of their strokes: a line is only recognised again once its ink changes.
 * UI-thread API; recognition and file work happen elsewhere and report back here.
 */
final class HandwritingIndex {
    private static final String TAG = "HandwritingIndex";

    /** Languages offered in settings: tag → name. */
    static final String[][] LANGUAGES = {
            {"en-US", "English"},
            {"de-DE", "Deutsch"},
    };

    interface StatusListener {
        void onStatusChanged(String status);
    }

    static final class Hit {
        final String path;
        final int page;
        final float[] rect;
        final String text;

        Hit(String path, int page, float[] rect, String text) {
            this.path = path;
            this.page = page;
            this.rect = rect;
            this.text = text;
        }
    }

    interface SearchCallback {
        void onResult(List<Hit> hits);
    }

    private static final class Entry {
        final String text;
        final List<String> alts;

        Entry(String text, List<String> alts) {
            this.text = text;
            this.alts = alts;
        }
    }

    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean enabled;
    private String language = defaultLanguage();
    private DigitalInkRecognitionModel model;
    private DigitalInkRecognizer recognizer;
    private boolean modelReady;
    private boolean downloading;
    private String status = "Off";
    private StatusListener statusListener;
    /** Called once the model is ready, so the open document gets indexed. */
    private Runnable onReady;

    // The document being indexed and what is known about its lines.
    private String docPath;
    private List<CodeCanvasView.InkLine> docLines = new ArrayList<>();
    private Map<String, Entry> known = new HashMap<>();
    private String knownFor;
    private final ArrayDeque<CodeCanvasView.InkLine> queue = new ArrayDeque<>();
    private boolean recognizing;
    /** Bumped whenever the document or language changes; stale results are dropped. */
    private int generation;

    HandwritingIndex(File base) {
        dir = new File(base, "ink_index");
    }

    static String defaultLanguage() {
        return "de".equals(Locale.getDefault().getLanguage()) ? "de-DE" : "en-US";
    }

    static String languageName(String tag) {
        for (String[] l : LANGUAGES) if (l[0].equals(tag)) return l[1];
        return tag;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getLanguage() {
        return language;
    }

    String getStatus() {
        return status;
    }

    void setStatusListener(StatusListener l) {
        statusListener = l;
    }

    void setOnReady(Runnable r) {
        onReady = r;
    }

    void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (on) {
            prepareModel();
        } else {
            generation++;
            queue.clear();
            closeRecognizer();
            setStatus("Off");
        }
    }

    void setLanguage(String tag) {
        if (tag == null || tag.equals(language)) return;
        language = tag;
        generation++;
        queue.clear();
        known = new HashMap<>();
        knownFor = null;
        closeRecognizer();
        if (enabled) prepareModel();
    }

    private void closeRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
        }
        recognizer = null;
        model = null;
        modelReady = false;
        downloading = false;
    }

    /** Make sure the model is on the device (downloading it once), then get a recogniser. */
    private void prepareModel() {
        final int gen = ++generation;
        try {
            DigitalInkRecognitionModelIdentifier id =
                    DigitalInkRecognitionModelIdentifier.fromLanguageTag(language);
            if (id == null) {
                setStatus("No handwriting model for " + languageName(language));
                return;
            }
            model = DigitalInkRecognitionModel.builder(id).build();
        } catch (Throwable e) {
            setStatus("Handwriting recognition unavailable: " + e.getMessage());
            return;
        }
        final DigitalInkRecognitionModel m = model;
        final RemoteModelManager mm = RemoteModelManager.getInstance();
        mm.isModelDownloaded(m).addOnSuccessListener(have -> {
            if (gen != generation || !enabled) return;
            if (Boolean.TRUE.equals(have)) {
                onModelReady(m);
                return;
            }
            downloading = true;
            setStatus("Downloading " + languageName(language) + " model…");
            mm.download(m, new DownloadConditions.Builder().build())
                    .addOnSuccessListener(v -> {
                        if (gen != generation || !enabled) return;
                        downloading = false;
                        onModelReady(m);
                    })
                    .addOnFailureListener(e -> {
                        if (gen != generation) return;
                        downloading = false;
                        setStatus("Model download failed: " + e.getMessage());
                    });
        }).addOnFailureListener(e -> {
            if (gen != generation) return;
            setStatus("Handwriting recognition unavailable: " + e.getMessage());
        });
    }

    private void onModelReady(DigitalInkRecognitionModel m) {
        try {
            recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(m).build());
        } catch (Throwable e) {
            setStatus("Handwriting recognition unavailable: " + e.getMessage());
            return;
        }
        modelReady = true;
        setStatus("Ready");
        if (onReady != null) onReady.run();
        pump();
    }

    /**
     * The open document's lines as they are now. Lines seen before keep their text;
     * new or changed ones are queued for recognition; the index file is rewritten
     * once the queue drains.
     */
    void update(String path, List<CodeCanvasView.InkLine> lines) {
        if (!enabled || path == null || path.isEmpty()) return;
        if (!path.equals(docPath)) {
            generation++;
            queue.clear();
            docPath = path;
        }
        docLines = lines;
        if (!path.equals(knownFor)) {
            // Pick up what an earlier session already recognised.
            known = loadKnown(path);
            knownFor = path;
        }
        queue.clear();
        for (CodeCanvasView.InkLine l : lines) {
            if (!known.containsKey(l.sig)) queue.add(l);
        }
        if (queue.isEmpty()) {
            writeIndex();
        } else {
            pump();
        }
    }

    private void pump() {
        if (recognizing || !modelReady || recognizer == null) return;
        if (queue.isEmpty()) {
            if (docPath != null) writeIndex();
            if (!downloading) setStatus("Ready");
            return;
        }
        final CodeCanvasView.InkLine line = queue.poll();
        final int gen = generation;
        final String path = docPath;
        recognizing = true;
        setStatus("Reading handwriting… " + (queue.size() + 1) + " left");
        Ink.Builder ink = Ink.builder();
        for (int s = 0; s < line.xs.length; s++) {
            Ink.Stroke.Builder sb = Ink.Stroke.builder();
            float[] xs = line.xs[s];
            float[] ys = line.ys[s];
            for (int k = 0; k < xs.length; k++) sb.addPoint(Ink.Point.create(xs[k], ys[k]));
            ink.addStroke(sb.build());
        }
        RecognitionContext ctx = RecognitionContext.builder()
                .setPreContext("")
                .setWritingArea(new WritingArea(Math.max(1f, line.width), Math.max(1f, line.height)))
                .build();
        recognizer.recognize(ink.build(), ctx)
                .addOnSuccessListener(result -> {
                    recognizing = false;
                    if (gen == generation && path != null && path.equals(docPath)) {
                        List<RecognitionCandidate> cands = result.getCandidates();
                        String text = cands.isEmpty() ? "" : cands.get(0).getText();
                        List<String> alts = new ArrayList<>();
                        for (int i = 1; i < Math.min(4, cands.size()); i++) {
                            alts.add(cands.get(i).getText());
                        }
                        known.put(line.sig, new Entry(text, alts));
                    }
                    pump();
                })
                .addOnFailureListener(e -> {
                    recognizing = false;
                    Log.w(TAG, "recognize: " + e.getMessage());
                    if (gen == generation) known.put(line.sig, new Entry("", new ArrayList<>()));
                    pump();
                });
    }

    /** Write the document's current lines (with their text) off the UI thread. */
    private void writeIndex() {
        if (docPath == null) return;
        final String path = docPath;
        final String lang = language;
        final JSONObject o = new JSONObject();
        try {
            o.put("path", path);
            o.put("lang", lang);
            JSONArray arr = new JSONArray();
            for (CodeCanvasView.InkLine l : docLines) {
                Entry e = known.get(l.sig);
                if (e == null) continue;
                JSONObject j = new JSONObject();
                j.put("sig", l.sig);
                j.put("text", e.text);
                if (!e.alts.isEmpty()) j.put("alts", new JSONArray(e.alts));
                j.put("page", l.filePage);
                j.put("rect", new JSONArray().put(l.rect[0]).put(l.rect[1])
                        .put(l.rect[2]).put(l.rect[3]));
                arr.put(j);
            }
            o.put("lines", arr);
        } catch (Exception e) {
            return;
        }
        io.execute(() -> {
            try {
                if (!dir.exists() && !dir.mkdirs()) return;
                File target = fileFor(path);
                File tmp = new File(dir, target.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(target)) Log.w(TAG, "rename failed " + target);
            } catch (Exception e) {
                Log.w(TAG, "write index: " + e.getMessage());
            }
        });
    }

    private Map<String, Entry> loadKnown(String path) {
        Map<String, Entry> out = new HashMap<>();
        JSONObject o = readJson(fileFor(path));
        if (o == null || !language.equals(o.optString("lang"))) return out;
        JSONArray arr = o.optJSONArray("lines");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;
            out.put(j.optString("sig"), new Entry(j.optString("text"), alts(j)));
        }
        return out;
    }

    /**
     * Handwritten lines matching {@code query} — in {@code onlyPath}, or every indexed
     * document when null. Case and accents are ignored; every word must appear.
     */
    void search(String query, String onlyPath, SearchCallback cb) {
        final String[] words = normalize(query).trim().split("\\s+");
        io.execute(() -> {
            List<Hit> hits = new ArrayList<>();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null && words.length > 0 && !words[0].isEmpty()) {
                for (File f : files) {
                    JSONObject o = readJson(f);
                    if (o == null) continue;
                    String path = o.optString("path");
                    if (onlyPath != null && !onlyPath.equals(path)) continue;
                    JSONArray arr = o.optJSONArray("lines");
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length() && hits.size() < 300; i++) {
                        JSONObject j = arr.optJSONObject(i);
                        if (j == null) continue;
                        String text = j.optString("text");
                        StringBuilder all = new StringBuilder(normalize(text));
                        for (String a : alts(j)) all.append('\n').append(normalize(a));
                        if (!matchesAll(all.toString(), words)) continue;
                        JSONArray r = j.optJSONArray("rect");
                        if (r == null || r.length() < 4) continue;
                        hits.add(new Hit(path, j.optInt("page"), new float[]{
                                (float) r.optDouble(0), (float) r.optDouble(1),
                                (float) r.optDouble(2), (float) r.optDouble(3)}, text));
                    }
                }
            }
            main.post(() -> cb.onResult(hits));
        });
    }

    private static boolean matchesAll(String hay, String[] words) {
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    private static List<String> alts(JSONObject j) {
        List<String> out = new ArrayList<>();
        JSONArray a = j.optJSONArray("alts");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    static String normalize(String s) {
        String d = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD);
        return d.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace("ß", "ss");
    }

    private void setStatus(String s) {
        // Progress ticks would flood the log; state changes are worth keeping.
        if (!s.equals(status) && !s.startsWith("Reading")) Log.i(TAG, s);
        status = s;
        if (statusListener != null) statusListener.onStatusChanged(s);
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

    private static JSONObject readJson(File f) {
        if (f == null || !f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] raw = new byte[(int) f.length()];
            int off = 0;
            while (off < raw.length) {
                int n = in.read(raw, off, raw.length - off);
                if (n < 0) break;
                off += n;
            }
            return new JSONObject(new String(raw, 0, off, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
