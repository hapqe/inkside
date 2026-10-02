package me.hapke.inkside;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The workspace on the tablet itself: the same files, projects and trash the host
 * keeps on a computer, so the app works with no computer connected. Mirrors the host's
 * file routes (host/src/server.mjs) closely enough that every screen behaves the
 * same — a project is a folder with a {@code .ccproject} marker, deletes go to
 * {@code .trash/} with an index of where each item came from.
 */
final class LocalWorkspace implements Workspace {
    private static final String PROJECT_MARKER = ".ccproject";
    private static final String TRASH_DIR = ".trash";
    private static final String TRASH_INDEX = ".index.json";
    private static final Set<String> HIDDEN_LIBRARY_DIRS = new HashSet<>(Arrays.asList(
            "visualizations", "node_modules", "__pycache__", "venv"));
    private static final long MAX_TEXT_BYTES = 2_000_000;

    private final File root;
    private final String rootPath;
    private final LocalPdf pdf;
    /** One worker: file operations stay in the order they were asked for. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "local-workspace");
        t.setDaemon(true);
        return t;
    });
    /** Searches are slow (text extraction); they must not hold up saves. */
    private final ExecutorService searchIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "local-search");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    LocalWorkspace(File root, LocalPdf pdf) {
        this.root = root;
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        String p;
        try {
            p = root.getCanonicalPath();
        } catch (IOException e) {
            p = root.getAbsolutePath();
        }
        this.rootPath = p;
        this.pdf = pdf;
    }

    File root() {
        return root;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    void shutdown() {
        io.shutdownNow();
        searchIo.shutdownNow();
    }

    // ---- plumbing ------------------------------------------------------------------

    private interface Job<T> {
        T run() throws Exception;
    }

    private <T> void async(ExecutorService on, Job<T> job, BridgeClient.Callback<T> cb) {
        on.execute(() -> {
            try {
                T v = job.run();
                main.post(() -> cb.onSuccess(v));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> cb.onError(msg));
            }
        });
    }

    private <T> void async(Job<T> job, BridgeClient.Callback<T> cb) {
        async(io, job, cb);
    }

    /** Called after anything that changes files, so a connected computer can be brought up to date. */
    volatile Runnable onChanged;

    private void changed() {
        Runnable r = onChanged;
        if (r != null) r.run();
    }

    /** A job that changes files: {@link #onChanged} fires once it succeeded. */
    private <T> void mutate(Job<T> job, BridgeClient.Callback<T> cb) {
        async(() -> {
            T v = job.run();
            changed();
            return v;
        }, cb);
    }

    // ---- used by RemoteSync (no change notification, it is the one changing files) -----

    /** Writes a file the way the computer has it: content and timestamp. */
    void syncWrite(String rel, byte[] data, long mtimeMs) throws Exception {
        File f = resolve(rel);
        writeAtomic(f, data);
        if (mtimeMs > 0) //noinspection ResultOfMethodCallIgnored
            f.setLastModified(mtimeMs);
    }

    /** Removes a file the computer no longer has; it goes to the trash like any delete. */
    void syncDelete(String rel) throws Exception {
        File f = resolve(rel);
        if (f.exists()) trash(f);
    }

    /** Workspace-relative path → file, refusing anything outside the workspace. */
    File resolve(String rel) throws IOException {
        String r = rel == null || rel.isEmpty() ? "." : rel;
        File f = new File(root, r);
        String canon = f.getCanonicalPath();
        if (!canon.equals(rootPath) && !canon.startsWith(rootPath + File.separator)) {
            throw new IOException("path outside workspace");
        }
        return new File(canon);
    }

    String rel(File f) {
        String p;
        try {
            p = f.getCanonicalPath();
        } catch (IOException e) {
            p = f.getAbsolutePath();
        }
        if (p.equals(rootPath)) return ".";
        return p.substring(rootPath.length() + 1);
    }

    private boolean isRoot(File f) {
        return rel(f).equals(".");
    }

    private static boolean hiddenInLibrary(String name) {
        return name == null || name.startsWith(".") || HIDDEN_LIBRARY_DIRS.contains(name);
    }

    private static boolean validLeafName(String name) {
        return name != null && !name.isEmpty() && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && !name.equals(".") && !name.equals("..") && !name.startsWith(".");
    }

    private static boolean isProjectDir(File dir) {
        return new File(dir, PROJECT_MARKER).isFile();
    }

    private static File[] children(File dir) {
        File[] kids = dir.listFiles();
        return kids != null ? kids : new File[0];
    }

    static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len > Integer.MAX_VALUE) throw new IOException("file too large");
        byte[] out = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off < out.length) out = Arrays.copyOf(out, off);
        }
        return out;
    }

    /** Write through a temp file so a crash never leaves half a document. */
    static void writeAtomic(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot write " + f.getName());
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static String stem(String name) {
        String ext = extension(name);
        return ext.isEmpty() ? name : name.substring(0, name.length() - ext.length());
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) for (File k : children(f)) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void copyRecursively(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.mkdirs() && !to.isDirectory()) throw new IOException("cannot create " + to.getName());
            for (File k : children(from)) copyRecursively(k, new File(to, k.getName()));
        } else {
            writeAtomic(to, readAll(from));
        }
    }

    private static void rename(File from, File to) throws IOException {
        if (!from.renameTo(to)) throw new IOException("cannot move " + from.getName());
    }

    /**
     * A first run starts with something to write in: a "Notes" project holding one
     * blank page. Only when the workspace has nothing in it at all.
     */
    void seedIfEmpty() {
        for (File k : children(root)) {
            if (!k.getName().startsWith(".")) return;
        }
        try {
            String project = createProjectSync(".", "Notes");
            writeAtomic(resolve(project + "/Notes.pdf"), PdfDocumentIo.createBlankA4(1));
        } catch (Exception e) {
            android.util.Log.w("LocalWorkspace", "could not create the starter project", e);
        }
    }

    // ---- browsing ------------------------------------------------------------------

    @Override
    public void listFiles(String path, BridgeClient.Callback<BridgeClient.DirListing> cb) {
        async(() -> {
            File dir = resolve(path);
            if (!dir.isDirectory()) throw new IOException("not a directory");
            List<BridgeClient.FileEntry> items = new ArrayList<>();
            for (File k : children(dir)) {
                String n = k.getName();
                if ((hiddenInLibrary(n) && !n.equals("visualizations")) || n.equals(PROJECT_MARKER) || n.endsWith(".tmp")) continue;
                items.add(new BridgeClient.FileEntry(n, rel(k), k.isDirectory() ? "dir" : "file", k.length()));
            }
            Collections.sort(items, (a, b) -> {
                if (!a.type.equals(b.type)) return a.type.equals("dir") ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            String parent = isRoot(dir) ? null : rel(dir.getParentFile());
            return new BridgeClient.DirListing(rel(dir), parent, items);
        }, cb);
    }

    @Override
    public void listLibrary(String path, BridgeClient.Callback<BridgeClient.LibraryListing> cb) {
        async(() -> {
            File dir = resolve(path);
            if (!dir.isDirectory()) throw new IOException("not a directory");
            List<BridgeClient.LibraryEntry> out = new ArrayList<>();
            for (File k : children(dir)) {
                String n = k.getName();
                if (hiddenInLibrary(n)) continue;
                if (k.isDirectory()) {
                    out.add(new BridgeClient.LibraryEntry(n, rel(k), isProjectDir(k) ? "project" : "folder"));
                } else if (k.isFile() && n.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    out.add(new BridgeClient.LibraryEntry(n, rel(k), "pdf"));
                }
            }
            Collections.sort(out, (a, b) -> {
                int ra = rank(a.kind), rb = rank(b.kind);
                if (ra != rb) return ra - rb;
                return a.name.compareToIgnoreCase(b.name);
            });
            String parent = isRoot(dir) ? null : rel(dir.getParentFile());
            return new BridgeClient.LibraryListing(rel(dir), parent, out);
        }, cb);
    }

    private static int rank(String kind) {
        switch (kind) {
            case "folder": return 0;
            case "project": return 1;
            case "pdf": return 2;
            default: return 9;
        }
    }

    @Override
    public void listSharedPdfs(BridgeClient.Callback<List<BridgeClient.LibraryEntry>> cb) {
        async(() -> {
            List<BridgeClient.LibraryEntry> items = new ArrayList<>();
            walkShared(root, items);
            Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name));
            return items;
        }, cb);
    }

    private void walkShared(File dir, List<BridgeClient.LibraryEntry> out) {
        for (File k : children(dir)) {
            String n = k.getName();
            if (hiddenInLibrary(n)) continue;
            if (k.isDirectory()) {
                if (!isProjectDir(k)) walkShared(k, out);
            } else if (n.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                out.add(new BridgeClient.LibraryEntry(n, rel(k), "pdf"));
            }
        }
    }

    @Override
    public void projectOf(String path, BridgeClient.Callback<String[]> cb) {
        async(() -> {
            File dir = resolve(path);
            if (!dir.isDirectory()) dir = dir.getParentFile();
            while (dir != null && !isRoot(dir)) {
                if (isProjectDir(dir)) {
                    String name = dir.getName();
                    try {
                        String meta = new String(readAll(new File(dir, PROJECT_MARKER)), StandardCharsets.UTF_8);
                        String n = new JSONObject(meta).optString("name", "").trim();
                        if (!n.isEmpty()) name = n;
                    } catch (Exception ignored) {
                        // Marker without JSON: the folder name.
                    }
                    return new String[]{rel(dir), name};
                }
                dir = dir.getParentFile();
            }
            return new String[]{null, null};
        }, cb);
    }

    // ---- library operations --------------------------------------------------------

    @Override
    public void libraryMkdir(String parent, String name, BridgeClient.Callback<BridgeClient.LibraryEntry> cb) {
        mutate(() -> {
            String n = name == null ? "" : name.trim();
            if (!validLeafName(n)) throw new IOException("invalid folder name");
            File p = resolve(parent);
            if (isProjectDir(p)) throw new IOException("cannot create folder inside a project from the library");
            File dir = resolve(rel(p).equals(".") ? n : rel(p) + "/" + n);
            if (dir.exists()) throw new IOException("already exists");
            if (!dir.mkdir()) throw new IOException("cannot create folder");
            return new BridgeClient.LibraryEntry(n, rel(dir), "folder");
        }, cb);
    }

    @Override
    public void libraryCreateProject(String parent, String name, BridgeClient.Callback<BridgeClient.LibraryEntry> cb) {
        async(() -> new BridgeClient.LibraryEntry(name, createProjectSync(parent, name), "project"), cb);
    }

    /** Creates a project folder with its marker; returns its path. */
    String createProjectSync(String parent, String name) throws Exception {
        String n = name == null ? "" : name.trim();
        if (!validLeafName(n)) throw new IOException("invalid project name");
        File p = resolve(parent);
        if (isProjectDir(p)) throw new IOException("cannot nest a project inside another project");
        File dir = resolve(rel(p).equals(".") ? n : rel(p) + "/" + n);
        if (dir.exists()) throw new IOException("already exists");
        if (!dir.mkdir()) throw new IOException("cannot create project");
        JSONObject meta = new JSONObject();
        meta.put("name", n);
        writeAtomic(new File(dir, PROJECT_MARKER), (meta.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        return rel(dir);
    }

    @Override
    public void libraryMove(String from, String toDir, BridgeClient.Callback<String> cb) {
        mutate(() -> {
            File src = resolve(from);
            File dest = resolve(toDir);
            if (isProjectDir(dest)) throw new IOException("cannot move into a project from the library");
            File target = new File(dest, src.getName());
            if (target.getCanonicalPath().equals(src.getCanonicalPath())) return rel(src);
            if (target.exists()) throw new IOException("a file with that name exists there");
            rename(src, target);
            return rel(target);
        }, cb);
    }

    @Override
    public void fsOp(String route, JSONObject body, BridgeClient.Callback<String> cb) {
        mutate(() -> {
            switch (route) {
                case "/fs/mkdir": return fsMkdir(body);
                case "/fs/rename": return fsRename(body);
                case "/fs/move": return fsMove(body);
                case "/fs/copy": return fsCopy(body);
                case "/fs/delete": return trash(resolve(body.optString("path", "")));
                case "/fs/restore": return restore(body.optString("name", ""));
                default: throw new IOException("unknown operation " + route);
            }
        }, cb);
    }

    private String fsMkdir(JSONObject body) throws Exception {
        String name = body.optString("name", "").trim();
        if (!validLeafName(name)) throw new IOException("invalid folder name");
        File dir = new File(resolve(body.optString("parent", ".")), name);
        dir = resolve(rel(dir.getParentFile()).equals(".") ? name : rel(dir.getParentFile()) + "/" + name);
        if (dir.exists()) throw new IOException("already exists");
        if (!dir.mkdir()) throw new IOException("cannot create folder");
        return rel(dir);
    }

    private String fsRename(JSONObject body) throws Exception {
        String name = body.optString("name", "").trim();
        if (!validLeafName(name)) throw new IOException("invalid name");
        File from = resolve(body.optString("path", ""));
        if (isRoot(from)) throw new IOException("cannot change the workspace root");
        File to = new File(from.getParentFile(), name);
        if (to.getCanonicalPath().equals(from.getCanonicalPath())) return rel(from);
        if (to.exists()) throw new IOException("a file with that name exists");
        rename(from, to);
        return rel(to);
    }

    private String fsMove(JSONObject body) throws Exception {
        File from = resolve(body.optString("from", ""));
        if (isRoot(from)) throw new IOException("cannot change the workspace root");
        File toDir = resolve(body.optString("toDir", "."));
        String fromPath = from.getCanonicalPath();
        String toPath = toDir.getCanonicalPath();
        if (toPath.equals(fromPath) || toPath.startsWith(fromPath + File.separator)) {
            throw new IOException("cannot move a folder into itself");
        }
        File to = new File(toDir, from.getName());
        if (to.getCanonicalPath().equals(fromPath)) return rel(from);
        if (to.exists()) throw new IOException("a file with that name exists there");
        rename(from, to);
        return rel(to);
    }

    private String fsCopy(JSONObject body) throws Exception {
        File from = resolve(body.optString("from", ""));
        if (isRoot(from)) throw new IOException("cannot change the workspace root");
        File toDir = resolve(body.optString("toDir", "."));
        String fromPath = from.getCanonicalPath();
        String toPath = toDir.getCanonicalPath();
        if (toPath.equals(fromPath) || toPath.startsWith(fromPath + File.separator)) {
            throw new IOException("cannot copy a folder into itself");
        }
        String base = from.getName();
        if (new File(toDir, base).exists()) {
            String ext = extension(base), st = stem(base);
            String candidate = st + " copy" + ext;
            for (int i = 2; new File(toDir, candidate).exists(); i++) candidate = st + " copy " + i + ext;
            base = candidate;
        }
        File to = new File(toDir, base);
        copyRecursively(from, to);
        return rel(to);
    }

    // ---- trash ---------------------------------------------------------------------

    private File trashDir() {
        return new File(root, TRASH_DIR);
    }

    private JSONObject readTrashIndex() {
        try {
            return new JSONObject(new String(readAll(new File(trashDir(), TRASH_INDEX)), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeTrashIndex(JSONObject idx) throws Exception {
        writeAtomic(new File(trashDir(), TRASH_INDEX), idx.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static String stamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /** Delete = move into .trash/<stamp>-name, recorded with where it came from. */
    private String trash(File f) throws Exception {
        if (isRoot(f)) throw new IOException("cannot change the workspace root");
        File t = trashDir();
        String fp = f.getCanonicalPath();
        if (fp.equals(t.getCanonicalPath()) || fp.startsWith(t.getCanonicalPath() + File.separator)) {
            throw new IOException("already in the trash");
        }
        if (!f.exists()) throw new IOException("not found");
        if (!t.isDirectory() && !t.mkdirs()) throw new IOException("cannot create the trash");
        File dest = new File(t, stamp() + "-" + f.getName());
        String original = rel(f);
        rename(f, dest);
        JSONObject idx = readTrashIndex();
        JSONObject meta = new JSONObject();
        meta.put("original", original);
        meta.put("deletedAt", System.currentTimeMillis());
        idx.put(dest.getName(), meta);
        writeTrashIndex(idx);
        return rel(dest);
    }

    /** Keeps a copy of {@code f} in the trash (for undoing a rewrite). */
    private void backupToTrash(File f) throws Exception {
        File t = trashDir();
        if (!t.isDirectory() && !t.mkdirs()) throw new IOException("cannot create the trash");
        File dest = new File(t, stamp() + "-" + f.getName());
        writeAtomic(dest, readAll(f));
        JSONObject idx = readTrashIndex();
        JSONObject meta = new JSONObject();
        meta.put("original", rel(f));
        meta.put("deletedAt", System.currentTimeMillis());
        idx.put(dest.getName(), meta);
        writeTrashIndex(idx);
    }

    private static String strippedTrashName(String name) {
        return name.replaceFirst("^\\d{4}-\\d{2}-\\d{2}T[\\d-]+Z-", "");
    }

    private String restore(String name) throws Exception {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IOException("invalid trash entry");
        }
        File src = new File(trashDir(), name);
        if (!src.exists()) throw new IOException("not in the trash");
        JSONObject idx = readTrashIndex();
        JSONObject meta = idx.optJSONObject(name);
        String original = meta != null ? meta.optString("original", strippedTrashName(name)) : strippedTrashName(name);
        File dest = resolve(original);
        if (isRoot(dest)) throw new IOException("cannot change the workspace root");
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot restore");
        if (dest.exists()) {
            String ext = extension(dest.getName()), st = stem(dest.getName());
            File candidate;
            int n = 1;
            do {
                candidate = new File(parent, st + " (restored" + (n > 1 ? " " + n : "") + ")" + ext);
                n++;
            } while (candidate.exists());
            dest = candidate;
        }
        rename(src, dest);
        idx.remove(name);
        writeTrashIndex(idx);
        return rel(dest);
    }

    @Override
    public void listTrash(BridgeClient.Callback<List<BridgeClient.TrashItem>> cb) {
        async(() -> {
            JSONObject idx = readTrashIndex();
            List<BridgeClient.TrashItem> items = new ArrayList<>();
            for (File k : children(trashDir())) {
                String n = k.getName();
                if (n.startsWith(".")) continue;
                JSONObject meta = idx.optJSONObject(n);
                String original = meta != null ? meta.optString("original", strippedTrashName(n)) : strippedTrashName(n);
                long at = meta != null ? meta.optLong("deletedAt", k.lastModified()) : k.lastModified();
                items.add(new BridgeClient.TrashItem(n, original, at, k.isDirectory()));
            }
            Collections.sort(items, (a, b) -> Long.compare(b.deletedAt, a.deletedAt));
            return items;
        }, cb);
    }

    // ---- reading and writing -------------------------------------------------------

    @Override
    public void readFile(String path, BridgeClient.Callback<BridgeClient.FileContent> cb) {
        async(() -> {
            File f = resolve(path);
            if (!f.isFile()) throw new IOException("not a file");
            if (f.length() > MAX_TEXT_BYTES) throw new IOException("file too large");
            String text = new String(readAll(f), StandardCharsets.UTF_8);
            return new BridgeClient.FileContent(rel(f), text, text.split("\r?\n", -1).length);
        }, cb);
    }

    @Override
    public void writeFile(String path, String text, BridgeClient.Callback<BridgeClient.FileContent> cb) {
        mutate(() -> {
            String t = text != null ? text : "";
            byte[] data = t.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_TEXT_BYTES) throw new IOException("file too large");
            File f = resolve(path);
            writeAtomic(f, data);
            return new BridgeClient.FileContent(rel(f), t, t.split("\r?\n", -1).length);
        }, cb);
    }

    @Override
    public void readFileBytes(String relPath, BridgeClient.Callback<byte[]> cb) {
        async(() -> readFileBytesSync(relPath), cb);
    }

    @Override
    public byte[] readFileBytesSync(String relPath) throws Exception {
        File f = resolve(relPath);
        if (!f.isFile()) throw new IOException("not a file");
        return readAll(f);
    }

    /** Same tag the host uses: size and modification time. */
    private static String etagOf(File f) {
        return "\"" + f.length() + "-" + f.lastModified() + "\"";
    }

    @Override
    public void readFileBytesConditional(String relPath, String knownEtag, BridgeClient.BinaryCallback cb) {
        io.execute(() -> {
            try {
                File f = resolve(relPath);
                if (!f.isFile()) throw new IOException("not a file");
                String tag = etagOf(f);
                if (tag.equals(knownEtag)) {
                    main.post(() -> cb.onSuccess(null, tag));
                    return;
                }
                byte[] data = readAll(f);
                main.post(() -> cb.onSuccess(data, tag));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> cb.onError(msg));
            }
        });
    }

    @Override
    public void writeFileBytes(String path, byte[] data, BridgeClient.Callback<Boolean> cb) {
        async(() -> {
            writeFileBytesSync(path, data);
            return true;
        }, cb);
    }

    @Override
    public void writeFileBytesSync(String path, byte[] data) throws Exception {
        writeAtomic(resolve(path), data != null ? data : new byte[0]);
        changed();
    }

    @Override
    public String uploadFileSync(String path, byte[] data) throws Exception {
        File f = resolve(path);
        if (f.exists()) {
            String ext = extension(f.getName()), st = stem(f.getName());
            int n = 2;
            File candidate;
            do {
                candidate = new File(f.getParentFile(), st + " (" + n + ")" + ext);
                n++;
            } while (candidate.exists());
            f = candidate;
        }
        writeAtomic(f, data != null ? data : new byte[0]);
        changed();
        return rel(f);
    }

    // ---- PDFs ----------------------------------------------------------------------

    @Override
    public void flattenPdf(String relPath, int[] pages, byte[] overlay, BridgeClient.Callback<byte[]> cb) {
        flattenPdf(relPath, pages, overlay, null, cb);
    }

    @Override
    public void flattenPdf(String relPath, int[] pages, byte[] overlay,
                           java.util.function.DoubleConsumer onProgress, BridgeClient.Callback<byte[]> cb) {
        async(() -> {
            byte[] out = pdf.flatten(readAll(resolve(relPath)), overlay, pages);
            if (onProgress != null) main.post(() -> onProgress.accept(1.0));
            return out;
        }, cb);
    }

    @Override
    public void reorderPdf(String relPath, int[] order, float width, float height, BridgeClient.Callback<byte[]> cb) {
        mutate(() -> {
            File f = resolve(relPath);
            byte[] next = pdf.reorder(readAll(f), order, width, height);
            backupToTrash(f);
            writeAtomic(f, next);
            return next;
        }, cb);
    }

    @Override
    public void searchPdfs(String query, String relPathOrNull, BridgeClient.Callback<JSONObject> cb) {
        async(searchIo, () -> {
            String q = query == null ? "" : query.trim();
            JSONObject result = new JSONObject();
            JSONArray files = new JSONArray();
            result.put("files", files);
            result.put("total", 0);
            result.put("truncated", false);
            if (q.length() < 2) return result;
            List<File> targets = new ArrayList<>();
            if (relPathOrNull != null && !relPathOrNull.isEmpty()) targets.add(resolve(relPathOrNull));
            else collectPdfs(root, targets);
            int total = 0, limit = 300, perFile = 60;
            boolean truncated = false;
            for (File f : targets) {
                if (total >= limit) {
                    truncated = true;
                    break;
                }
                if (!f.isFile()) continue;
                LocalPdf.Indexed doc;
                try {
                    doc = pdf.index(f);
                } catch (Exception e) {
                    continue; // unreadable PDF: skipped, like on the host
                }
                int cap = Math.min(perFile, limit - total);
                JSONArray matches = LocalPdf.search(doc, q, cap + 1);
                if (matches.length() > cap) {
                    truncated = true;
                    matches.remove(matches.length() - 1);
                }
                if (matches.length() == 0) continue;
                total += matches.length();
                JSONObject entry = new JSONObject();
                entry.put("path", rel(f));
                entry.put("pageCount", doc.pages.size());
                entry.put("matches", matches);
                files.put(entry);
            }
            result.put("total", total);
            result.put("truncated", truncated);
            return result;
        }, cb);
    }

    private void collectPdfs(File dir, List<File> out) {
        for (File k : children(dir)) {
            String n = k.getName();
            if (k.isDirectory()) {
                if (n.startsWith(".") || n.equals("node_modules")) continue;
                collectPdfs(k, out);
            } else if (n.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                out.add(k);
            }
        }
    }
}
