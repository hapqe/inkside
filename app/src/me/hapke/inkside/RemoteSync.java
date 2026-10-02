package me.hapke.inkside;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the tablet's workspace and the connected computer's workspace the same.
 *
 * <p>The tablet is always the working copy (everything the app shows is read from it, so it
 * works offline); the computer is where the agent and scripts run, so it needs the same
 * files. A pass compares three things per file: what the tablet has, what the computer has
 * and what both had after the last pass ({@code sync_state.json}). Only one side changed →
 * copy it over. Deleted on one side and untouched on the other → delete it there too (it
 * goes to that side's trash). Changed on both sides → the newer one wins and the other is
 * kept next to it as "name (conflict).ext". Timestamps are copied with the content, so a
 * copy is never mistaken for an edit.
 *
 * <p>Each document's handwriting, text and pages (a slice file under {@code doc_states/}) travels
 * the same way, as {@code .inkside/doc_states/<name>} on the computer, so another tablet gets the
 * ink with the document; for those the newer file wins outright. Undo history stays on the tablet.
 * Empty folders are not mirrored.
 */
final class RemoteSync {
    interface Listener {
        /** Main thread. {@code text} is what Settings shows; {@code busy} while a pass runs. */
        void onSyncStatus(String text, boolean busy, boolean error);

        /** Main thread: how far the running pass is. {@code busy} false when it ended. */
        default void onSyncProgress(int done, int total, boolean busy) {}

        /** Main thread: a pass changed files on the tablet (pulled, deleted), so views should reload. */
        default void onLocalFilesChanged() {}

        /** Main thread: ink or chat files were pulled or removed (full mirrored paths). */
        default void onStateFilesChanged(List<String> pulled, List<String> deleted) {}
    }

    private static final long DEBOUNCE_MS = 1500L;
    /** How often a live tablet asks the computer what changed. */
    private static final long POLL_MS = 8_000L;
    private static final long MAX_BYTES = 25_000_000L;
    private static final Set<String> SKIP_DIRS = new HashSet<>(java.util.Arrays.asList(
            "node_modules", "__pycache__", "venv", "env", "dist", "build"));

    /** What both sides looked like after the last pass. */
    private static final class Base {
        final long size;
        final long localMtime;
        final long remoteMtime;

        Base(long size, long localMtime, long remoteMtime) {
            this.size = size;
            this.localMtime = localMtime;
            this.remoteMtime = remoteMtime;
        }
    }

    private static final class Entry {
        final long size;
        final long mtime;

        Entry(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }
    }

    static final String STATE_PREFIX = ".inkside/doc_states/";
    static final String CHAT_PREFIX = ".inkside/chats/";

    private final DocumentStateStore docs;
    private final File chatsDir;
    private final LocalWorkspace local;
    private final BridgeClient bridge;
    private final File stateFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "remote-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private final List<Runnable> waiters = Collections.synchronizedList(new ArrayList<>());
    private Map<String, Base> base = new HashMap<>();
    private final List<String> pulledState = new ArrayList<>();
    private final List<String> deletedState = new ArrayList<>();
    private Listener listener;
    private boolean started;
    private boolean live = true;
    private long lastOk;

    private final Runnable debounced = () -> run();
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!started || !live) return;
            RemoteSync.this.run();
            main.postDelayed(this, POLL_MS);
        }
    };

    RemoteSync(LocalWorkspace local, BridgeClient bridge, File profileDir, DocumentStateStore docs, File chatsDir) {
        this.docs = docs;
        this.chatsDir = chatsDir;
        this.local = local;
        this.bridge = bridge;
        this.stateFile = new File(profileDir, "sync_state.json");
    }

    void setListener(Listener l) {
        listener = l;
    }

    /**
     * Live: a pass shortly after each change and every few seconds for what changed on the
     * computer. Not live: nothing runs by itself, only {@link #syncNow}.
     */
    void setLive(boolean on) {
        live = on;
        main.removeCallbacks(debounced);
        main.removeCallbacks(poll);
        if (!started) return;
        if (on) {
            main.post(poll);
        } else {
            status("Live sync is off — tap Sync now to sync", false, false);
        }
    }

    /** Begin syncing: a pass now, one after each change, and one now and then (when live). */
    void start() {
        if (started) return;
        started = true;
        local.onChanged = this::requestSoon;
        loadState();
        setLive(live);
    }

    void stop() {
        started = false;
        local.onChanged = null;
        main.removeCallbacks(debounced);
        main.removeCallbacks(poll);
        worker.shutdownNow();
    }

    /** Something changed on the tablet: sync shortly (changes in a burst go together). */
    void requestSoon() {
        if (!started || !live) return;
        main.removeCallbacks(debounced);
        main.postDelayed(debounced, DEBOUNCE_MS);
    }

    /** Runs a pass now; {@code done} (main thread) follows once the tablet and computer agree, or it failed. */
    void syncNow(Runnable done) {
        if (done != null) waiters.add(done);
        main.removeCallbacks(debounced);
        run();
    }

    long lastSuccessMs() {
        return lastOk;
    }

    private void run() {
        if (!started) return;
        if (!running.compareAndSet(false, true)) {
            again.set(true);
            return;
        }
        try {
            worker.execute(this::pass);
        } catch (Exception e) {
            running.set(false);
        }
    }

    private void progress(int done, int total, boolean busy) {
        main.post(() -> {
            if (listener != null) listener.onSyncProgress(done, total, busy);
        });
    }

    private void status(String text, boolean busy, boolean error) {
        main.post(() -> {
            if (listener != null) listener.onSyncStatus(text, busy, error);
        });
    }

    // ---- one pass ---------------------------------------------------------------------

    private void pass() {
        boolean failed = false;
        try {
            Map<String, Entry> remote = new HashMap<>();
            for (BridgeClient.RemoteFile f : bridge.syncManifestSync()) {
                if (wanted(f.path, false)) remote.put(f.path, new Entry(f.size, f.mtimeMs));
            }
            Map<String, Entry> here = new HashMap<>();
            walk(local.root(), "", here);
            if (docs != null) {
                File[] slices = docs.dir().listFiles();
                if (slices != null) {
                    for (File f : slices) {
                        String n = f.getName();
                        if (f.isFile() && n.endsWith(".json") && !n.equals("index.json") && f.length() <= MAX_BYTES) {
                            here.put(STATE_PREFIX + n, new Entry(f.length(), f.lastModified()));
                        }
                    }
                }
            }
            File[] chatFiles = chatsDir != null ? chatsDir.listFiles() : null;
            if (chatFiles != null) {
                for (File f : chatFiles) {
                    String n = f.getName();
                    if (f.isFile() && n.endsWith(".json") && f.length() <= MAX_BYTES) {
                        here.put(CHAT_PREFIX + n, new Entry(f.length(), f.lastModified()));
                    }
                }
            }
            pulledState.clear();
            deletedState.clear();

            Set<String> paths = new HashSet<>(remote.keySet());
            paths.addAll(here.keySet());
            paths.addAll(base.keySet());
            List<String> ordered = new ArrayList<>(paths);
            Collections.sort(ordered);

            int total = 0;
            boolean touchedLocal = false;
            List<String[]> plan = new ArrayList<>();
            for (String p : ordered) {
                String action = decide(p, here.get(p), remote.get(p), base.get(p));
                if (action != null) plan.add(new String[]{p, action});
            }
            // A pass that would delete most of what was synced means the other side is not
            // showing its files (wrong folder, a disk that did not mount): do nothing.
            int deletes = 0;
            for (String[] step : plan) if (step[1].startsWith("delete")) deletes++;
            if (deletes > 20 && deletes > base.size() / 2) {
                status("Not synced: that would delete " + deletes + " files. Check that the computer's "
                        + "workspace is the right folder.", false, true);
                return;
            }
            total = plan.size();
            int done = 0;
            if (total > 0) progress(0, total, true);
            for (String[] step : plan) {
                if (!started) return;
                progress(done, total, true);
                try {
                    apply(step[0], step[1], here.get(step[0]), remote.get(step[0]));
                    if (!step[1].equals("push") && !step[1].equals("deleteRemote")
                            && !step[1].equals("adopt") && !step[1].equals("forget")) touchedLocal = true;
                } catch (Exception e) {
                    failed = true;
                    status("Could not sync " + step[0].substring(step[0].lastIndexOf('/') + 1)
                            + ": " + e.getMessage(), false, true);
                }
                done++;
                if (done % 25 == 0) saveState();
            }
            saveState();
            if (!pulledState.isEmpty() || !deletedState.isEmpty()) {
                final List<String> pulled = new ArrayList<>(pulledState);
                final List<String> gone = new ArrayList<>(deletedState);
                main.post(() -> {
                    if (listener != null) listener.onStateFilesChanged(pulled, gone);
                });
            }
            if (touchedLocal) main.post(() -> {
                if (listener != null) listener.onLocalFilesChanged();
            });
            if (!failed) {
                lastOk = System.currentTimeMillis();
                status(total == 0 ? "Up to date" : "Synced " + total + (total == 1 ? " change" : " changes"),
                        false, false);
            }
        } catch (Exception e) {
            failed = true;
            status("Not synced: " + (e.getMessage() != null ? e.getMessage() : "no answer from the computer"),
                    false, true);
        } finally {
            running.set(false);
            progress(0, 0, false);
            final List<Runnable> toRun;
            synchronized (waiters) {
                toRun = new ArrayList<>(waiters);
                waiters.clear();
            }
            main.post(() -> {
                for (Runnable r : toRun) r.run();
            });
            if (again.getAndSet(false) && started && live) main.post(this::requestSoon);
        }
    }

    /**
     * What to do about one path, or null for nothing. Actions: push, pull, deleteRemote,
     * deleteLocal, adopt (both already agree: only remember it), conflict, forget.
     */
    private String decide(String p, Entry l, Entry r, Base b) {
        if (l == null && r == null) return b != null ? "forget" : null;
        if (r == null) {
            if (b != null && !localChanged(l, b)) return "deleteLocal";
            return "push";
        }
        if (l == null) {
            if (b != null && !remoteChanged(r, b)) return "deleteRemote";
            return "pull";
        }
        if (b == null) {
            if (isState(p)) return stateWinner(l, r);
            return l.size == r.size ? "adopt" : "conflict";
        }
        boolean lc = localChanged(l, b), rc = remoteChanged(r, b);
        if (lc && rc) {
            if (isState(p)) return stateWinner(l, r);
            return l.size == r.size && l.mtime == r.mtime ? "adopt" : "conflict";
        }
        if (lc) return "push";
        if (rc) return "pull";
        return null;
    }

    /**
     * Two copies of a document's ink that disagree and neither is known to be the later edit of
     * the other (first contact, or both changed): the bigger one has more handwriting in it, so it
     * wins. Newest-wins here let a device that merely opened a document, and saved it empty,
     * replace another device's real ink.
     */
    private static String stateWinner(Entry l, Entry r) {
        if (l.size == r.size) return l.mtime == r.mtime ? "adopt" : (l.mtime > r.mtime ? "push" : "pull");
        return l.size > r.size ? "push" : "pull";
    }

    private static boolean localChanged(Entry l, Base b) {
        return l.size != b.size || l.mtime != b.localMtime;
    }

    private static boolean remoteChanged(Entry r, Base b) {
        return r.size != b.size || r.mtime != b.remoteMtime;
    }

    private static boolean isState(String p) {
        return p.startsWith(STATE_PREFIX) || p.startsWith(CHAT_PREFIX);
    }

    /** The tablet's file for a mirrored state path (a document's ink or a chat). */
    private File stateFile(String p) {
        if (p.startsWith(CHAT_PREFIX)) return new File(chatsDir, p.substring(CHAT_PREFIX.length()));
        return new File(docs.dir(), p.substring(STATE_PREFIX.length()));
    }

    private byte[] readLocal(String p) throws Exception {
        if (isState(p)) return LocalWorkspace.readAll(stateFile(p));
        return local.readFileBytesSync(p);
    }

    private void writeLocal(String p, byte[] data, long mtime) throws Exception {
        if (isState(p)) {
            File f = stateFile(p);
            LocalWorkspace.writeAtomic(f, data);
            if (mtime > 0) //noinspection ResultOfMethodCallIgnored
                f.setLastModified(mtime);
            pulledState.add(p);
        } else {
            local.syncWrite(p, data, mtime);
        }
    }

    private void deleteLocal(String p) throws Exception {
        if (isState(p)) {
            //noinspection ResultOfMethodCallIgnored
            stateFile(p).delete();
            deletedState.add(p);
        } else {
            local.syncDelete(p);
        }
    }

    private void apply(String p, String action, Entry l, Entry r) throws Exception {
        switch (action) {
            case "push": {
                byte[] data = readLocal(p);
                bridge.syncPushSync(p, data, l.mtime);
                base.put(p, new Base(l.size, l.mtime, l.mtime));
                break;
            }
            case "pull": {
                byte[] data = bridge.readFileBytesSync(p);
                writeLocal(p, data, r.mtime);
                base.put(p, new Base(data.length, r.mtime, r.mtime));
                break;
            }
            case "deleteLocal":
                deleteLocal(p);
                base.remove(p);
                break;
            case "deleteRemote":
                bridge.syncDeleteSync(p);
                base.remove(p);
                break;
            case "adopt":
                base.put(p, new Base(l.size, l.mtime, r.mtime));
                break;
            case "forget":
                base.remove(p);
                break;
            case "conflict": {
                if (isState(p)) {
                    // Ink slices: the newer file wins, no copy beside it.
                    if (l.mtime >= r.mtime) {
                        bridge.syncPushSync(p, readLocal(p), l.mtime);
                        base.put(p, new Base(l.size, l.mtime, l.mtime));
                    } else {
                        byte[] theirs = bridge.readFileBytesSync(p);
                        writeLocal(p, theirs, r.mtime);
                        base.put(p, new Base(theirs.length, r.mtime, r.mtime));
                    }
                    break;
                }
                // The newer one stays at the name; the other is kept beside it.
                String copy = conflictName(p);
                if (l.mtime >= r.mtime) {
                    byte[] theirs = bridge.readFileBytesSync(p);
                    writeLocal(copy, theirs, r.mtime);
                    byte[] mine = readLocal(p);
                    bridge.syncPushSync(p, mine, l.mtime);
                    base.put(p, new Base(l.size, l.mtime, l.mtime));
                } else {
                    byte[] mine = readLocal(p);
                    writeLocal(copy, mine, l.mtime);
                    byte[] theirs = bridge.readFileBytesSync(p);
                    writeLocal(p, theirs, r.mtime);
                    base.put(p, new Base(theirs.length, r.mtime, r.mtime));
                }
                break;
            }
            default:
                break;
        }
    }


    private static String conflictName(String p) {
        int slash = p.lastIndexOf('/');
        String dir = slash >= 0 ? p.substring(0, slash + 1) : "";
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return dir + stem + " (conflict)" + ext;
    }

    // ---- what is mirrored ------------------------------------------------------------------

    /** Same rules as the host's /sync/manifest. */
    private static boolean wanted(String path, boolean isDir) {
        if (path.startsWith(".inkside")) {
            if (isDir) return false;
            String prefix = path.startsWith(STATE_PREFIX) ? STATE_PREFIX : path.startsWith(CHAT_PREFIX) ? CHAT_PREFIX : null;
            return prefix != null && path.indexOf('/', prefix.length()) < 0;
        }
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            String n = parts[i];
            boolean last = i == parts.length - 1;
            if (last && !isDir) {
                if (n.startsWith(".") && !n.equals(".projects.json") && !n.equals(".ccproject")) return false;
                if (n.endsWith(".tmp")) return false;
            } else {
                if (n.startsWith(".") && !n.equals(".artifacts") && !n.equals(".inkside")) return false;
                if (SKIP_DIRS.contains(n)) return false;
            }
        }
        return true;
    }

    private void walk(File dir, String prefix, Map<String, Entry> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String rel = prefix.isEmpty() ? k.getName() : prefix + "/" + k.getName();
            if (k.isDirectory()) {
                if (wanted(rel, true)) walk(k, rel, out);
            } else if (k.isFile() && wanted(rel, false) && k.length() <= MAX_BYTES) {
                out.put(rel, new Entry(k.length(), k.lastModified()));
            }
        }
    }

    // ---- remembered state -------------------------------------------------------------------

    private void loadState() {
        base = new HashMap<>();
        try {
            if (!stateFile.isFile()) return;
            JSONObject root = new JSONObject(new String(LocalWorkspace.readAll(stateFile), StandardCharsets.UTF_8));
            JSONObject files = root.optJSONObject("files");
            if (files == null) return;
            java.util.Iterator<String> it = files.keys();
            while (it.hasNext()) {
                String p = it.next();
                JSONArray a = files.optJSONArray(p);
                if (a != null && a.length() == 3) base.put(p, new Base(a.optLong(0), a.optLong(1), a.optLong(2)));
            }
        } catch (Exception ignored) {
            // An unreadable record means a first-time pass: equal files are adopted, nothing is lost.
        }
    }

    private void saveState() {
        try {
            JSONObject files = new JSONObject();
            for (Map.Entry<String, Base> e : base.entrySet()) {
                files.put(e.getKey(), new JSONArray(new long[]{
                        e.getValue().size, e.getValue().localMtime, e.getValue().remoteMtime}));
            }
            JSONObject root = new JSONObject();
            root.put("files", files);
            File tmp = new File(stateFile.getParentFile(), "sync_state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(stateFile);
        } catch (Exception ignored) {
            // The next pass rebuilds what it can from the files themselves.
        }
    }
}
