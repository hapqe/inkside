package me.hapke.inkside;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * When the user studied: one entry per stretch of time the stopwatch was running, with
 * the document that was open. It feeds the weekly schedule.
 *
 * <p>A session is open while the stopwatch runs. It is closed when the stopwatch pauses
 * or the app is left, and split when another document is opened. The open session's
 * start and a heartbeat live in preferences, so a session the app never got to close
 * (killed, crashed, battery) is recovered up to its last heartbeat on the next start.
 * Kept across workspaces: learning time belongs to the person, not to a workspace.
 */
final class StudyLog {
    /** One stretch of studying. Times are epoch milliseconds. */
    static final class Session {
        final long start;
        final long end;
        /** Workspace path of the document that was open; may be empty. */
        final String doc;

        Session(long start, long end, String doc) {
            this.start = start;
            this.end = end;
            this.doc = doc == null ? "" : doc;
        }

        long durationMs() {
            return Math.max(0L, end - start);
        }
    }

    /** Shorter than this is a slip of the finger, not studying. */
    private static final long MIN_SESSION_MS = 20_000L;
    private static final long HEARTBEAT_MS = 30_000L;
    private static final String FILE_NAME = "study_sessions.json";
    private static final String PREFS = "inkside_study";
    private static final String KEY_OPEN_START = "openStart";
    private static final String KEY_OPEN_DOC = "openDoc";
    private static final String KEY_BEAT = "lastBeat";

    private final File file;
    private final SharedPreferences prefs;
    private final List<Session> sessions = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "study-log");
        t.setDaemon(true);
        return t;
    });

    private long openStart = -1L;
    private String openDoc = "";

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (openStart < 0) return;
            prefs.edit().putLong(KEY_BEAT, System.currentTimeMillis()).apply();
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    StudyLog(Context ctx) {
        Context app = ctx.getApplicationContext();
        file = new File(app.getFilesDir(), FILE_NAME);
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
        recoverUnclosed();
    }

    private void load() {
        try {
            if (!file.isFile()) return;
            byte[] raw = LocalWorkspace.readAll(file);
            JSONArray arr = new JSONObject(new String(raw, StandardCharsets.UTF_8)).optJSONArray("sessions");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long s = o.optLong("s", 0), e = o.optLong("e", 0);
                if (s > 0 && e > s) sessions.add(new Session(s, e, o.optString("d", "")));
            }
        } catch (Exception ignored) {
            // An unreadable log starts over rather than blocking the app.
        }
    }

    /** A session left open by a dead process ends at its last heartbeat. */
    private void recoverUnclosed() {
        long start = prefs.getLong(KEY_OPEN_START, -1L);
        if (start < 0) return;
        long beat = prefs.getLong(KEY_BEAT, start);
        prefs.edit().remove(KEY_OPEN_START).remove(KEY_OPEN_DOC).remove(KEY_BEAT).apply();
        if (beat - start >= MIN_SESSION_MS) {
            sessions.add(new Session(start, beat, prefs.getString(KEY_OPEN_DOC, "")));
            persist();
        }
    }

    boolean isOpen() {
        return openStart >= 0;
    }

    /** The stopwatch started: a session begins (nothing happens if one is already open). */
    synchronized void begin(String doc) {
        if (openStart >= 0) {
            switchDocument(doc);
            return;
        }
        openStart = System.currentTimeMillis();
        openDoc = doc == null ? "" : doc;
        prefs.edit().putLong(KEY_OPEN_START, openStart).putString(KEY_OPEN_DOC, openDoc)
                .putLong(KEY_BEAT, openStart).apply();
        main.removeCallbacks(heartbeat);
        main.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    /** The stopwatch paused or the app was left: the session ends. */
    synchronized void end() {
        if (openStart < 0) return;
        closeAt(System.currentTimeMillis());
        main.removeCallbacks(heartbeat);
        prefs.edit().remove(KEY_OPEN_START).remove(KEY_OPEN_DOC).remove(KEY_BEAT).apply();
    }

    /** Another document came up while studying: the session continues as a new block. */
    synchronized void switchDocument(String doc) {
        if (openStart < 0) return;
        String next = doc == null ? "" : doc;
        if (next.equals(openDoc)) return;
        long now = System.currentTimeMillis();
        closeAt(now);
        openStart = now;
        openDoc = next;
        prefs.edit().putLong(KEY_OPEN_START, openStart).putString(KEY_OPEN_DOC, openDoc)
                .putLong(KEY_BEAT, now).apply();
    }

    private void closeAt(long now) {
        long start = openStart;
        String doc = openDoc;
        openStart = -1L;
        openDoc = "";
        if (now - start >= MIN_SESSION_MS) {
            sessions.add(new Session(start, now, doc));
            persist();
        }
    }

    /**
     * Sessions overlapping [from, to), oldest first, including the one in progress (cut
     * off at now). Stretches of the same document less than two minutes apart — a pause
     * to think — are shown as one block.
     */
    synchronized List<Session> range(long from, long to) {
        List<Session> all = new ArrayList<>(sessions);
        if (openStart >= 0) all.add(new Session(openStart, System.currentTimeMillis(), openDoc));
        java.util.Collections.sort(all, (a, b) -> Long.compare(a.start, b.start));
        List<Session> out = new ArrayList<>();
        for (Session s : all) {
            if (s.end <= from || s.start >= to) continue;
            Session last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.doc.equals(s.doc) && s.start - last.end <= 120_000L) {
                out.set(out.size() - 1, new Session(last.start, Math.max(last.end, s.end), s.doc));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Minutes studied between {@code from} and {@code to} with a document of
     * {@code project} open (a workspace folder; null or empty counts everything).
     */
    synchronized long minutesIn(String project, long from, long to) {
        String prefix = project == null || project.isEmpty() || ".".equals(project) ? null
                : (project.endsWith("/") ? project : project + "/");
        long ms = 0L;
        for (Session s : range(from, to)) {
            if (prefix != null && !s.doc.startsWith(prefix)) continue;
            ms += Math.max(0L, Math.min(s.end, to) - Math.max(s.start, from));
        }
        return ms / 60_000L;
    }

    /** Minutes studied per document between {@code from} and {@code to}. */
    synchronized java.util.Map<String, Long> minutesByDocument(long from, long to) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (Session s : range(from, to)) {
            if (s.doc.isEmpty()) continue;
            long ms = Math.max(0L, Math.min(s.end, to) - Math.max(s.start, from));
            Long prev = out.get(s.doc);
            out.put(s.doc, (prev != null ? prev : 0L) + ms);
        }
        for (java.util.Map.Entry<String, Long> e : out.entrySet()) e.setValue(e.getValue() / 60_000L);
        return out;
    }

    /** Days left in this week (Monday to Sunday), today included. */
    static int daysLeftInWeek() {
        int sinceMonday = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
        return 7 - sinceMonday;
    }

    /** This week's study time in a project, as the host's study plan takes it. */
    JSONObject weekReport(String project) {
        JSONObject o = new JSONObject();
        try {
            long from = StudyWeekDialog.weekStart(0);
            o.put("studiedMinutes", minutesIn(project, from, System.currentTimeMillis()));
            o.put("daysLeft", daysLeftInWeek());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Start of the oldest recorded session, or -1 when nothing has been recorded. */
    synchronized long firstStart() {
        long first = openStart >= 0 ? openStart : -1L;
        for (Session s : sessions) if (first < 0 || s.start < first) first = s.start;
        return first;
    }

    private void persist() {
        final List<Session> snapshot = new ArrayList<>(sessions);
        writer.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Session s : snapshot) {
                    JSONObject o = new JSONObject();
                    o.put("s", s.start);
                    o.put("e", s.end);
                    o.put("d", s.doc);
                    arr.put(o);
                }
                JSONObject root = new JSONObject();
                root.put("sessions", arr);
                File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            } catch (Exception ignored) {
                // Losing one write is better than crashing the stopwatch.
            }
        });
    }
}
