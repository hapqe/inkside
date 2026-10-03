package me.hapke.inkside;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thin HTTP client for the host on a connected computer (its files, agent and tools). */
public final class BridgeClient implements Workspace {
    @Override
    public boolean isLocal() {
        return false;
    }

    public interface Callback<T> {
        void onSuccess(T value);

        void onError(String message);
    }

    /** Streaming chat callbacks (all invoked on the main thread). */
    public interface ChatStreamListener {
        void onStatus(String message);

        /** Cumulative assistant text so far. */
        void onDelta(String text);

        /**
         * A tool step in the agent timeline (Read / Shell / …).
         * {@code status} is running|completed|error; {@code message} is the display line.
         */
        default void onTool(String name, String detail, String status, String message) {}

        void onDone(ChatResult result);

        void onError(String message);
    }

    public static final class FileEntry {
        public final String name;
        public final String path;
        public final String type;
        public final long size;

        FileEntry(String name, String path, String type, long size) {
            this.name = name;
            this.path = path;
            this.type = type;
            this.size = size;
        }
    }

    public static final class DirListing {
        public final String path;
        public final String parent;
        public final List<FileEntry> items;

        DirListing(String path, String parent, List<FileEntry> items) {
            this.path = path;
            this.parent = parent;
            this.items = items;
        }
    }

    /** Library grid entry: folder | project | pdf. */
    public static final class LibraryEntry {
        public final String name;
        public final String path;
        public final String kind;

        LibraryEntry(String name, String path, String kind) {
            this.name = name;
            this.path = path;
            this.kind = kind;
        }
    }

    public static final class LibraryListing {
        public final String path;
        public final String parent;
        public final List<LibraryEntry> entries;

        LibraryListing(String path, String parent, List<LibraryEntry> entries) {
            this.path = path;
            this.parent = parent;
            this.entries = entries;
        }
    }

    public static final class FileContent {
        public final String path;
        public final String text;
        public final int lines;

        FileContent(String path, String text, int lines) {
            this.path = path;
            this.text = text;
            this.lines = lines;
        }
    }

    public static final class ChatResult {
        public final String status;
        public final String text;
        public final String error;

        ChatResult(String status, String text, String error) {
            this.status = status;
            this.text = text;
            this.error = error;
        }
    }

    public static final class RunResult {
        public final boolean ok;
        public final String stdout;
        public final String stderr;
        public final int exitCode;

        RunResult(boolean ok, String stdout, String stderr, int exitCode) {
            this.ok = ok;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
    }

    /** Server→tablet push (canvas commands). Callbacks on the main thread. */
    public interface EventsListener {
        /** The workspace changed on disk; reload whatever is on screen. */
        default void onFilesChanged() {}

        /** Same event with the changed directories (workspace-relative, "" = root). */
        default void onFilesChanged(java.util.List<String> dirs) {
            onFilesChanged();
        }

        void onCanvasBatch(String batchId, JSONArray commands);

        void onEventsError(String message);

        /** The agent wants to see the canvas (kind "info" or "pages"); answer with postCaptureResult. */
        default void onCaptureRequest(JSONObject request) {}
    }

    /** Answer a capture request (screenshots for the agent). */
    public void postCaptureResult(JSONObject body) {
        io.execute(() -> {
            try {
                postJson("/canvas/capture-result", body);
            } catch (Exception e) {
                android.util.Log.w("BridgeClient", "capture result not delivered: " + e.getMessage());
            }
        });
    }

    // Concurrent chat streams each occupy a worker for the whole NDJSON lifetime.
    private final ExecutorService io = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "cc-bridge-io");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Written from the UI thread (settings), read on the io worker threads. */
    private volatile String baseUrl;
    private volatile boolean eventsWanted;
    private volatile HttpURLConnection eventsConn;
    private volatile EventsListener eventsListener;

    public BridgeClient(String baseUrl) {
        this.baseUrl = trimSlash(baseUrl);
    }

    /** Stops accepting work and interrupts in-flight requests; call from onDestroy. */
    public void shutdown() {
        stopEvents();
        io.shutdownNow();
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = trimSlash(baseUrl);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /** Shared secret for a bridge started with BRIDGE_TOKEN; empty = none. */
    private volatile String token = "";

    public void setToken(String t) {
        token = t == null ? "" : t.trim();
    }

    public String getToken() {
        return token;
    }

    /** Every request goes through here so the token is never forgotten. */
    private HttpURLConnection open(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        String t = token;
        if (t != null && !t.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + t);
        return c;
    }

    /**
     * For URLs handed to a web view (artifact pages), which cannot send headers:
     * the token rides in the query and the bridge answers with a cookie for the
     * page's own sub-resources.
     */
    public String withToken(String url) {
        String t = token;
        if (url == null || t == null || t.isEmpty()) return url;
        int hash = url.indexOf('#');
        String frag = hash >= 0 ? url.substring(hash) : "";
        String base = hash >= 0 ? url.substring(0, hash) : url;
        String enc;
        try {
            enc = urlEncode(t);
        } catch (Exception e) {
            return url;
        }
        return base + (base.indexOf('?') >= 0 ? '&' : '?') + "token=" + enc + frag;
    }

    /** One entry in the workspace trash. */
    public static final class TrashItem {
        public final String name;
        public final String original;
        public final long deletedAt;
        public final boolean dir;

        TrashItem(String name, String original, long deletedAt, boolean dir) {
            this.name = name;
            this.original = original;
            this.deletedAt = deletedAt;
            this.dir = dir;
        }
    }

    public void listTrash(Callback<java.util.List<TrashItem>> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/fs/trash");
                JSONArray arr = o.optJSONArray("items");
                java.util.List<TrashItem> out = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        out.add(new TrashItem(it.optString("name"), it.optString("original"),
                                it.optLong("deletedAt"), it.optBoolean("dir")));
                    }
                }
                main.post(() -> cb.onSuccess(out));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Upload a crash recorded on the device (blocking; call off the UI thread). */
    public void uploadCrashSync(JSONObject body) throws Exception {
        postJson("/client/crash", body);
    }

    /** Long-lived SSE to /events. Safe to call repeatedly; restarts the stream. */
    public void startEvents(EventsListener listener) {
        eventsListener = listener;
        if (eventsWanted) return;
        eventsWanted = true;
        io.execute(this::eventsLoop);
    }

    public void stopEvents() {
        eventsWanted = false;
        HttpURLConnection c = eventsConn;
        eventsConn = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private void eventsLoop() {
        while (eventsWanted) {
            HttpURLConnection c = null;
            try {
                c = open(baseUrl + "/events");
                c.setConnectTimeout(12000);
                c.setReadTimeout(0); // long-lived
                c.setRequestMethod("GET");
                c.setRequestProperty("Accept", "text/event-stream");
                eventsConn = c;
                int code = c.getResponseCode();
                if (code >= 400) {
                    throw new Exception("HTTP " + code);
                }
                InputStream stream = c.getInputStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                String event = "message";
                StringBuilder data = new StringBuilder();
                String line;
                while (eventsWanted && (line = br.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            dispatchSseEvent(event, data.toString());
                        }
                        event = "message";
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith(":")) continue; // heartbeat comment
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    }
                }
            } catch (Exception e) {
                EventsListener l = eventsListener;
                if (eventsWanted && l != null) {
                    String msg = e.getMessage() == null ? "events disconnected" : e.getMessage();
                    main.post(() -> l.onEventsError(msg));
                }
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                if (eventsConn == c) eventsConn = null;
            }
            if (!eventsWanted) break;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private void dispatchSseEvent(String event, String dataJson) {
        EventsListener l = eventsListener;
        if (l == null) return;
        if ("files".equals(event)) {
            java.util.List<String> dirs = new java.util.ArrayList<>();
            try {
                JSONArray arr = new JSONObject(dataJson).optJSONArray("dirs");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) dirs.add(arr.optString(i, ""));
                }
            } catch (Exception ignored) {
            }
            main.post(() -> l.onFilesChanged(dirs));
            return;
        }
        if ("capture".equals(event)) {
            try {
                JSONObject o = new JSONObject(dataJson);
                main.post(() -> l.onCaptureRequest(o));
            } catch (Exception ignored) {
            }
            return;
        }
        if (!"canvas".equals(event)) return;
        try {
            JSONObject o = new JSONObject(dataJson);
            String batchId = o.optString("batchId", "");
            JSONArray commands = o.optJSONArray("commands");
            if (batchId.isEmpty() || commands == null) return;
            main.post(() -> l.onCanvasBatch(batchId, commands));
        } catch (Exception e) {
            main.post(() -> l.onEventsError("bad canvas event: " + e.getMessage()));
        }
    }

    public void ackCanvasBatch(String batchId, boolean ok, String message) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("batchId", batchId);
                body.put("ok", ok);
                body.put("message", message == null ? "" : message);
                postJson("/canvas/ack", body);
            } catch (Exception ignored) {
            }
        });
    }

    public void putCanvasState(JSONObject state, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                postJson("/canvas/state", state != null ? state : new JSONObject());
                if (cb != null) main.post(() -> cb.onSuccess(true));
            } catch (Exception e) {
                if (cb != null) main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void health(Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/health");
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void listFiles(String path, Callback<DirListing> cb) {
        io.execute(() -> {
            try {
                String q = path == null ? "." : path;
                JSONObject o = getJson("/files?path=" + urlEncode(q));
                JSONArray items = o.optJSONArray("items");
                List<FileEntry> list = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        list.add(new FileEntry(
                                it.optString("name"),
                                it.optString("path"),
                                it.optString("type"),
                                it.optLong("size")));
                    }
                }
                String parent = o.isNull("parent") ? null : o.optString("parent", null);
                DirListing dir = new DirListing(o.optString("path", "."), parent, list);
                main.post(() -> cb.onSuccess(dir));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void listLibrary(String path, Callback<LibraryListing> cb) {
        io.execute(() -> {
            try {
                String q = path == null ? "." : path;
                JSONObject o = getJson("/library?path=" + urlEncode(q));
                JSONArray entries = o.optJSONArray("entries");
                List<LibraryEntry> list = new ArrayList<>();
                if (entries != null) {
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject it = entries.getJSONObject(i);
                        list.add(new LibraryEntry(
                                it.optString("name"),
                                it.optString("path"),
                                it.optString("kind")));
                    }
                }
                String parent = o.isNull("parent") ? null : o.optString("parent", null);
                LibraryListing listing = new LibraryListing(
                        o.optString("path", "."), parent, list);
                main.post(() -> cb.onSuccess(listing));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void listSharedPdfs(Callback<List<LibraryEntry>> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/library/shared");
                JSONArray items = o.optJSONArray("items");
                List<LibraryEntry> list = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        list.add(new LibraryEntry(
                                it.optString("name"),
                                it.optString("path"),
                                it.optString("kind", "pdf")));
                    }
                }
                main.post(() -> cb.onSuccess(list));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void libraryMkdir(String parent, String name, Callback<LibraryEntry> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("parent", parent != null ? parent : ".");
                body.put("name", name);
                JSONObject o = postJson("/library/mkdir", body);
                LibraryEntry e = new LibraryEntry(
                        name, o.optString("path"), o.optString("kind", "folder"));
                main.post(() -> cb.onSuccess(e));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void libraryCreateProject(String parent, String name, Callback<LibraryEntry> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("parent", parent != null ? parent : ".");
                body.put("name", name);
                JSONObject o = postJson("/library/create-project", body);
                LibraryEntry e = new LibraryEntry(
                        name, o.optString("path"), o.optString("kind", "project"));
                main.post(() -> cb.onSuccess(e));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void libraryMove(String from, String toDir, Callback<String> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("from", from);
                body.put("toDir", toDir != null ? toDir : ".");
                JSONObject o = postJson("/library/move", body);
                main.post(() -> cb.onSuccess(o.optString("path", from)));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /**
     * Explorer file operation: route is one of /fs/mkdir, /fs/rename, /fs/move,
     * /fs/copy, /fs/delete. Succeeds with the resulting workspace path (or "").
     */
    public void fsOp(String route, JSONObject body, Callback<String> cb) {
        io.execute(() -> {
            try {
                JSONObject o = postJson(route, body);
                String p = o.optString("path", o.optString("trashed", ""));
                main.post(() -> cb.onSuccess(p));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** {project path, display name} of the project holding {@code path}, or {null, null}. */
    public void projectOf(String path, Callback<String[]> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/project-of?path=" + urlEncode(path));
                String project = o.isNull("project") ? null : o.optString("project", null);
                String name = o.optString("name", null);
                main.post(() -> cb.onSuccess(new String[]{project, name}));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void readFile(String path, Callback<FileContent> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/file?path=" + urlEncode(path));
                FileContent fc = new FileContent(
                        o.optString("path"),
                        o.optString("text"),
                        o.optInt("lines"));
                main.post(() -> cb.onSuccess(fc));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void writeFile(String path, String text, Callback<FileContent> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("path", path);
                body.put("text", text != null ? text : "");
                JSONObject o = postJson("/file/write", body);
                FileContent fc = new FileContent(
                        o.optString("path", path),
                        text != null ? text : "",
                        o.optInt("lines"));
                main.post(() -> cb.onSuccess(fc));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Write raw bytes (PDF etc.) via base64 JSON. */
    public void writeFileBytes(String path, byte[] data, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                writeFileBytesSync(path, data);
                main.post(() -> cb.onSuccess(true));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Upload that never overwrites; returns the workspace path the file really got. */
    /** One file the computer holds that tablets mirror. */
    static final class RemoteFile {
        final String path;
        final long size;
        final long mtimeMs;

        RemoteFile(String path, long size, long mtimeMs) {
            this.path = path;
            this.size = size;
            this.mtimeMs = mtimeMs;
        }
    }

    /** Blocking: every document on the computer (see the host's /sync/manifest). */
    public List<RemoteFile> syncManifestSync() throws Exception {
        JSONArray arr = getJson("/sync/manifest").optJSONArray("files");
        List<RemoteFile> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(new RemoteFile(o.optString("path"), o.optLong("size"), o.optLong("mtimeMs")));
        }
        return out;
    }

    /** Blocking: stores {@code data} at {@code path} with the given timestamp, replacing what is there. */
    public void syncPushSync(String path, byte[] data, long mtimeMs) throws Exception {
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("mtimeMs", mtimeMs);
        body.put("base64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
        // Gzipped: ink is JSON and shrinks to a fraction on the way (the host inflates it).
        HttpURLConnection c = open(baseUrl + "/file/write-binary");
        c.setConnectTimeout(8000);
        c.setReadTimeout(150000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Content-Encoding", "gzip");
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(c.getOutputStream())) {
            gz.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        readResponse(c);
    }

    /** Blocking: the computer moves {@code path} to its trash. A file already gone is fine. */
    public void syncDeleteSync(String path) throws Exception {
        JSONObject body = new JSONObject();
        body.put("path", path);
        try {
            postJson("/fs/delete", body);
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (!m.contains("not found") && !m.contains("404")) throw e;
        }
    }

    public String uploadFileSync(String path, byte[] data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("unique", true);
        body.put("base64", android.util.Base64.encodeToString(
                data != null ? data : new byte[0], android.util.Base64.NO_WRAP));
        return postJson("/file/write-binary", body).optString("path", path);
    }

    public void writeFileBytesSync(String path, byte[] data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("base64", android.util.Base64.encodeToString(
                data != null ? data : new byte[0], android.util.Base64.NO_WRAP));
        postJson("/file/write-binary", body);
    }

    public void resetAgent(Callback<Boolean> cb) {
        resetAgent(null, cb);
    }

    public void resetAgent(String chatId, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                if (chatId != null && !chatId.isEmpty()) body.put("chatId", chatId);
                postJson("/agent/reset", body);
                main.post(() -> cb.onSuccess(true));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void cancelChat(Callback<Boolean> cb) {
        cancelChat(null, cb);
    }

    public void cancelChat(String chatId, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                if (chatId != null && !chatId.isEmpty()) body.put("chatId", chatId);
                postJson("/chat/cancel", body);
                main.post(() -> cb.onSuccess(true));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Which provider the Mac's Claude Code will use for the next run. */
    public void getAgentProvider(Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/improve/provider");
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /**
     * Switch the Mac's Claude Code provider ("claude" or "deepseek"). Applies to
     * the next run — anything already in flight keeps the CLI it started with.
     */
    public void setAgentProvider(String provider, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("provider", provider != null ? provider : "");
                JSONObject o = postJson("/improve/provider", body);
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Provider, chosen model, and the models the bridge offers for chat runs. */
    public void getAgentSettings(Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/agent/settings");
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Pick the model ("haiku", "sonnet", "opus") for the bridge's next runs. */
    public void setAgentModel(String model, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model != null ? model : "");
                JSONObject o = postJson("/agent/model", body);
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Sessions the bridge is holding, running or recently finished. */
    public void listSessions(Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/chat/sessions");
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> cb.onError(msg));
            }
        });
    }

    /**
     * Reattach to a chat session the bridge is already running. The first event
     * replays everything produced while this client was away.
     */
    public void chatFollow(String chatId, ChatStreamListener listener) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("chatId", chatId == null ? "default" : chatId);
                streamNdjson("/chat/follow", body, listener, 900_000);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> listener.onError(msg));
            }
        });
    }

    public void chat(
            String message,
            String openFile,
            JSONArray images,
            JSONArray documents,
            Callback<ChatResult> cb) {
        chatStream(null, message, openFile, images, documents, new ChatStreamListener() {
            @Override
            public void onStatus(String message) {}

            @Override
            public void onDelta(String text) {}

            @Override
            public void onDone(ChatResult result) {
                cb.onSuccess(result);
            }

            @Override
            public void onError(String message) {
                cb.onError(message);
            }
        });
    }

    /** NDJSON streaming chat — status/delta arrive while the agent works. */
    public void chatStream(
            String chatId,
            String message,
            String openFile,
            JSONArray images,
            JSONArray documents,
            String history,
            String project,
            ChatStreamListener listener) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("message", message != null ? message : "");
                if (chatId != null && !chatId.isEmpty()) body.put("chatId", chatId);
                if (openFile != null) body.put("openFile", openFile);
                body.put("images", images != null ? images : new JSONArray());
                body.put("documents", documents != null ? documents : new JSONArray());
                if (history != null && !history.isEmpty()) body.put("history", history);
                if (project != null && !project.isEmpty()) body.put("project", project);
                // Learning Mode is global: every message says whether it is on.
                body.put("learningMode", learningMode);
                body.put("allowPageView", allowPageView);
                streamNdjson("/chat/stream", body, listener);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> listener.onError(msg));
            }
        });
    }

    /** Learning Mode (Settings): sent with every chat message. */
    private volatile boolean learningMode;
    /** Settings → AI: whether the agent may look at the user's pages; sent with every message. */
    private volatile boolean allowPageView = true;

    public void setAllowPageView(boolean on) {
        allowPageView = on;
    }

    public void setLearningMode(boolean on) {
        learningMode = on;
    }

    /** Tell the host too, so its stored setting (and other tablets' fallback) match. */
    public void postLearningMode(boolean on, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("enabled", on);
                JSONObject o = postJson("/learning/mode", body);
                if (cb != null) main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                if (cb != null) main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Counts per level in one project: {enabled, concepts, counts:{level:n}, goals, …}. */
    public void learningState(String project, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/learning/state?project="
                        + java.net.URLEncoder.encode(project != null ? project : "", "UTF-8"));
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** One project's progress, topics and goals, for the Learning view. */
    public void learningProgress(String project, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/learning/progress?project="
                        + java.net.URLEncoder.encode(project != null ? project : "", "UTF-8"));
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Forget what the tutor recorded in one project; the others keep theirs. */
    public void resetLearning(String project, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("project", project != null ? project : "");
                JSONObject o = postJson("/learning/reset", body);
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** @deprecated use overload with project */
    public void chatStream(
            String chatId,
            String message,
            String openFile,
            JSONArray images,
            JSONArray documents,
            String history,
            ChatStreamListener listener) {
        chatStream(chatId, message, openFile, images, documents, history, null, listener);
    }

    /** @deprecated use overload with history */
    public void chatStream(
            String chatId,
            String message,
            String openFile,
            JSONArray images,
            JSONArray documents,
            ChatStreamListener listener) {
        chatStream(chatId, message, openFile, images, documents, null, null, listener);
    }

    private void streamNdjson(String path, JSONObject body, ChatStreamListener listener)
            throws Exception {
        // No idle gap limit: long Shell/simulations emit only heartbeats from the bridge.
        streamNdjson(path, body, listener, 0);
    }

    private void streamNdjson(
            String path, JSONObject body, ChatStreamListener listener, int readTimeoutMs)
            throws Exception {
        HttpURLConnection c = null;
        boolean finished = false;
        try {
            c = open(baseUrl + path);
            c.setConnectTimeout(10000);
            // Gap *between* NDJSON events, not total run length — the bridge emits status
            // events throughout, so a longer silence means the link is dead.
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setDoInput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/x-ndjson");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }

            int code = c.getResponseCode();
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null) {
                throw new Exception("HTTP " + code + " empty body");
            }

            // Non-NDJSON error bodies (e.g. plain JSON errors).
            String contentType = c.getContentType() != null ? c.getContentType() : "";
            if (code >= 400 && !contentType.contains("ndjson")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = stream.read(buf)) >= 0) bos.write(buf, 0, n);
                String raw = bos.toString(StandardCharsets.UTF_8.name());
                String err = raw;
                try {
                    err = new JSONObject(raw).optString("error", raw);
                } catch (Exception ignored) {
                }
                throw new Exception(err);
            }

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8), 16 * 1024)) {
                String line;
                String lastText = "";
                String lastStatus = "";
                // Coalesce deltas: only the latest text is posted to the main thread
                // so a busy UI never drops the tail by processing a stale queue.
                final String[] pendingDelta = {null};
                final boolean[] deltaScheduled = {false};
                final Runnable flushDelta = () -> {
                    deltaScheduled[0] = false;
                    String t = pendingDelta[0];
                    pendingDelta[0] = null;
                    if (t != null) listener.onDelta(t);
                };
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    JSONObject o;
                    try {
                        o = new JSONObject(line);
                    } catch (Exception parseErr) {
                        if (code >= 400) throw new Exception(line);
                        continue;
                    }
                    String type = o.optString("type", "");
                    if ("snapshot".equals(type)) {
                        // Sent first on a reattach: the session's state so far.
                        String text = o.optString("text", "");
                        if (!text.isEmpty()) {
                            lastText = text;
                            final String t = text;
                            main.post(() -> listener.onDelta(t));
                        }
                        String msg = o.optString("statusMessage", "");
                        if (!msg.isEmpty() && !msg.equals(lastStatus)) {
                            lastStatus = msg;
                            main.post(() -> listener.onStatus(msg));
                        }
                    } else if ("status".equals(type)) {
                        String msg = o.optString("message", "");
                        if (!msg.isEmpty() && !msg.equals(lastStatus)) {
                            lastStatus = msg;
                            main.post(() -> listener.onStatus(msg));
                        }
                    } else if ("tool".equals(type)) {
                        final String name = o.optString("name", "tool");
                        final String detail = o.optString("detail", "");
                        final String status = o.optString("status", "running");
                        final String message = o.optString("message", name);
                        String statusMsg = o.optString("message", "");
                        if (!statusMsg.isEmpty()) lastStatus = statusMsg;
                        main.post(() -> listener.onTool(name, detail, status, message));
                    } else if ("delta".equals(type)) {
                        String text = o.optString("text", "");
                        lastText = text;
                        pendingDelta[0] = text;
                        if (!deltaScheduled[0]) {
                            deltaScheduled[0] = true;
                            main.post(flushDelta);
                        }
                    } else if ("done".equals(type)) {
                        // Ensure the latest delta is applied before done.
                        if (pendingDelta[0] != null) {
                            final String t = pendingDelta[0];
                            pendingDelta[0] = null;
                            deltaScheduled[0] = false;
                            main.post(() -> listener.onDelta(t));
                        }
                        String text = o.optString("text", lastText);
                        String status = o.optString("status", "finished");
                        String err = o.has("error") && !o.isNull("error")
                                ? o.optString("error", null)
                                : null;
                        ChatResult r = new ChatResult(status, text, err);
                        finished = true;
                        main.post(() -> listener.onDone(r));
                    } else if ("error".equals(type)) {
                        String err = o.optString("error", "chat error");
                        finished = true;
                        main.post(() -> listener.onError(err));
                    }
                }
            }

            if (!finished) {
                if (code >= 400) {
                    throw new Exception("HTTP " + code);
                }
                main.post(() -> listener.onError("chat stream ended without a result"));
            }
        } catch (Exception e) {
            if (!finished) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> listener.onError(msg));
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Result of a conditional read: {@code bytes} is null when the copy on disk is current. */
    public interface BinaryCallback {
        void onSuccess(byte[] bytesOrNullIfUnchanged, String etag);

        void onError(String message);
    }

    /**
     * Binary read that skips the transfer when the caller already holds the current
     * bytes. Pass the ETag from the last successful read; a 304 comes back as a null
     * byte array, leaving the caller to use its cached file.
     */
    public void readFileBytesConditional(String relPath, String knownEtag, BinaryCallback cb) {
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = open(baseUrl + "/file/binary?path=" + urlEncode(relPath));
                c.setConnectTimeout(12000);
                c.setReadTimeout(120000);
                c.setRequestMethod("GET");
                if (knownEtag != null && !knownEtag.isEmpty()) {
                    c.setRequestProperty("If-None-Match", knownEtag);
                }
                int code = c.getResponseCode();
                final String etag = c.getHeaderField("ETag");
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    main.post(() -> cb.onSuccess(null, etag != null ? etag : knownEtag));
                    return;
                }
                InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (stream == null) throw new Exception("HTTP " + code);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = stream.read(buf)) >= 0) bos.write(buf, 0, n);
                stream.close();
                if (code >= 400) throw new Exception("HTTP " + code);
                final byte[] data = bos.toByteArray();
                main.post(() -> cb.onSuccess(data, etag));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> cb.onError(msg));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    public void readFileBytes(String relPath, Callback<byte[]> cb) {
        io.execute(() -> {
            try {
                byte[] data = readFileBytesSync(relPath);
                main.post(() -> cb.onSuccess(data));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    /** Blocking read for PDF editor thread. */
    public byte[] readFileBytesSync(String relPath) throws Exception {
        HttpURLConnection c = open(baseUrl + "/file/binary?path=" + urlEncode(relPath));
        c.setConnectTimeout(12000);
        c.setReadTimeout(120000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) throw new Exception("HTTP " + code);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = stream.read(buf)) >= 0) bos.write(buf, 0, n);
        stream.close();
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return bos.toByteArray();
    }

    public void scriptMeta(String path, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                JSONObject o = getJson("/script/meta?path=" + urlEncode(path));
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public void runScript(String path, JSONObject args, Callback<RunResult> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("path", path);
                body.put("args", args != null ? args : new JSONObject());
                JSONObject o = postJson("/run", body);
                RunResult r = new RunResult(
                        o.optBoolean("ok", false),
                        o.optString("stdout", ""),
                        o.optString("stderr", ""),
                        o.optInt("exitCode", 1));
                main.post(() -> cb.onSuccess(r));
            } catch (Exception e) {
                main.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection c = open(baseUrl + path);
        c.setConnectTimeout(8000);
        c.setReadTimeout(120000);
        c.setRequestMethod("GET");
        return readResponse(c);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        HttpURLConnection c = open(baseUrl + path);
        c.setConnectTimeout(8000);
        // Longest server-side op on this path is /run, capped at 120s by the bridge.
        c.setReadTimeout(150000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        return readResponse(c);
    }

    /** Send a recording to the Mac's local speech model; answers the transcript. */
    public void transcribe(byte[] audio, String mimeType, Callback<String> cb) {
        io.execute(() -> {
            try {
                HttpURLConnection c = open(baseUrl + "/transcribe");
                c.setConnectTimeout(8000);
                // First clip after a bridge restart also loads the model.
                c.setReadTimeout(120000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", mimeType);
                c.setFixedLengthStreamingMode(audio.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(audio);
                }
                String text = readResponse(c).optString("text", "");
                main.post(() -> cb.onSuccess(text));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> cb.onError(msg));
            }
        });
    }

    /**
     * Export: the Mac stamps {@code overlay} (one PDF page per document page — ink,
     * text, images) onto the workspace PDF at {@code relPath} and answers the merged
     * file. {@code pages} maps each overlay page to its PDF page index, or −1 for a
     * page that exists only in the app.
     */
    public void flattenPdf(String relPath, int[] pages, byte[] overlay, Callback<byte[]> cb) {
        flattenPdf(relPath, pages, overlay, null, cb);
    }

    /**
     * @param onProgress (may be null) hears on the main thread how far the transfer is,
     *                   0..1: the first half is sending the layer, the second half is
     *                   receiving the merged PDF.
     */
    public void flattenPdf(String relPath, int[] pages, byte[] overlay,
                           java.util.function.DoubleConsumer onProgress, Callback<byte[]> cb) {
        final long[] lastPost = {0L};
        final java.util.function.DoubleConsumer report = f -> {
            if (onProgress == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (f < 1.0 && now - lastPost[0] < 50) return;
            lastPost[0] = now;
            main.post(() -> onProgress.accept(f));
        };
        io.execute(() -> {
            try {
                StringBuilder map = new StringBuilder();
                for (int i = 0; i < pages.length; i++) {
                    if (i > 0) map.append(',');
                    map.append(pages[i]);
                }
                HttpURLConnection c = open(baseUrl + "/pdf/flatten?path=" + urlEncode(relPath)
                        + "&pages=" + urlEncode(map.toString()));
                c.setConnectTimeout(12000);
                c.setReadTimeout(180000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/pdf");
                c.setFixedLengthStreamingMode(overlay.length);
                try (OutputStream os = c.getOutputStream()) {
                    int chunk = 64 * 1024;
                    for (int off = 0; off < overlay.length; off += chunk) {
                        int n = Math.min(chunk, overlay.length - off);
                        os.write(overlay, off, n);
                        report.accept(0.5 * (off + n) / overlay.length);
                    }
                }
                int code = c.getResponseCode();
                if (code >= 400) {
                    readResponse(c);  // throws with the bridge's error message
                    throw new Exception("HTTP " + code);
                }
                long total = c.getContentLengthLong();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    long got = 0;
                    while ((n = in.read(buf)) >= 0) {
                        bos.write(buf, 0, n);
                        got += n;
                        if (total > 0) report.accept(0.5 + 0.5 * got / total);
                    }
                }
                report.accept(1.0);
                c.disconnect();
                final byte[] data = bos.toByteArray();
                main.post(() -> cb.onSuccess(data));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> cb.onError(msg));
            }
        });
    }

    /**
     * Rewrites the workspace PDF at {@code relPath} to a new page order ({@code order[j]}:
     * the PDF page that becomes page j, or −1 for a blank page of {@code width}×{@code height}
     * points). The Mac keeps the old file in the trash; answers the new PDF.
     */
    public void reorderPdf(String relPath, int[] order, float width, float height,
                           Callback<byte[]> cb) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("path", relPath);
                JSONArray arr = new JSONArray();
                for (int i : order) arr.put(i);
                body.put("order", arr);
                body.put("width", width);
                body.put("height", height);
                HttpURLConnection c = open(baseUrl + "/pdf/reorder");
                c.setConnectTimeout(12000);
                c.setReadTimeout(180000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
                int code = c.getResponseCode();
                if (code >= 400) {
                    readResponse(c);  // throws with the bridge's error message
                    throw new Exception("HTTP " + code);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                }
                c.disconnect();
                final byte[] data = bos.toByteArray();
                main.post(() -> cb.onSuccess(data));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> cb.onError(msg));
            }
        });
    }

    /**
     * Text search in the workspace PDFs, or only {@code relPathOrNull}. Answers
     * {@code {files:[{path, pageCount, matches:[{page, snippet, start, length, rects}]}],
     * total, truncated}}; rects are fractions of the page as shown.
     */
    public void searchPdfs(String query, String relPathOrNull, Callback<JSONObject> cb) {
        io.execute(() -> {
            try {
                String q = "/pdf/search?q=" + urlEncode(query);
                if (relPathOrNull != null && !relPathOrNull.isEmpty()) {
                    q += "&path=" + urlEncode(relPathOrNull);
                }
                JSONObject o = getJson(q);
                main.post(() -> cb.onSuccess(o));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                main.post(() -> cb.onError(msg));
            }
        });
    }

    private JSONObject readResponse(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) throw new Exception("HTTP " + code + " empty body");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        String raw = sb.toString();
        if (code >= 400) {
            String msg = raw;
            try {
                msg = new JSONObject(raw).optString("error", raw);
            } catch (Exception ignored) {
            }
            throw new Exception(msg);
        }
        return new JSONObject(raw);
    }

    private static String trimSlash(String u) {
        if (u == null) return "";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.trim();
    }

    private static String urlEncode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }
}
