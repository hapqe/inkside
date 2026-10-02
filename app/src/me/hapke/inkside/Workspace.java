package me.hapke.inkside;

import java.util.List;

import org.json.JSONObject;

/**
 * Where the open project's files live: on this tablet ({@link LocalWorkspace}) or on
 * a connected computer ({@link BridgeClient}, over the host's HTTP API). Paths are
 * relative to the workspace root, "." is the root. Callbacks arrive on the main
 * thread; the *Sync methods block and must not be called from it.
 */
interface Workspace {
    /** True when this is the tablet's own storage (no agent, no scripts). */
    boolean isLocal();

    // ---- Browsing ----------------------------------------------------------------

    void listFiles(String path, BridgeClient.Callback<BridgeClient.DirListing> cb);

    /** Folders, projects and PDFs, for the All Projects grid. */
    void listLibrary(String path, BridgeClient.Callback<BridgeClient.LibraryListing> cb);

    /** PDFs that are not inside any project. */
    void listSharedPdfs(BridgeClient.Callback<List<BridgeClient.LibraryEntry>> cb);

    /** {project path, display name} of the project holding {@code path}, or {null, null}. */
    void projectOf(String path, BridgeClient.Callback<String[]> cb);

    // ---- Library and explorer operations ----------------------------------------

    void libraryMkdir(String parent, String name, BridgeClient.Callback<BridgeClient.LibraryEntry> cb);

    void libraryCreateProject(String parent, String name, BridgeClient.Callback<BridgeClient.LibraryEntry> cb);

    void libraryMove(String from, String toDir, BridgeClient.Callback<String> cb);

    /**
     * Explorer file operation: route is one of /fs/mkdir, /fs/rename, /fs/move,
     * /fs/copy, /fs/delete, /fs/restore. Succeeds with the resulting path (or "").
     */
    void fsOp(String route, JSONObject body, BridgeClient.Callback<String> cb);

    void listTrash(BridgeClient.Callback<List<BridgeClient.TrashItem>> cb);

    // ---- Reading and writing -----------------------------------------------------

    void readFile(String path, BridgeClient.Callback<BridgeClient.FileContent> cb);

    void writeFile(String path, String text, BridgeClient.Callback<BridgeClient.FileContent> cb);

    void readFileBytes(String relPath, BridgeClient.Callback<byte[]> cb);

    byte[] readFileBytesSync(String relPath) throws Exception;

    /** Null bytes in the callback: the caller's copy (tagged {@code knownEtag}) is current. */
    void readFileBytesConditional(String relPath, String knownEtag, BridgeClient.BinaryCallback cb);

    void writeFileBytes(String path, byte[] data, BridgeClient.Callback<Boolean> cb);

    void writeFileBytesSync(String path, byte[] data) throws Exception;

    /** Write that never overwrites; returns the path the file really got. */
    String uploadFileSync(String path, byte[] data) throws Exception;

    // ---- PDFs --------------------------------------------------------------------

    void flattenPdf(String relPath, int[] pages, byte[] overlay, BridgeClient.Callback<byte[]> cb);

    /** @param onProgress (may be null) 0..1 on the main thread. */
    void flattenPdf(String relPath, int[] pages, byte[] overlay,
                    java.util.function.DoubleConsumer onProgress, BridgeClient.Callback<byte[]> cb);

    /**
     * New page order ({@code order[j]}: the page that becomes page j, or −1 for a blank
     * page of {@code width}×{@code height} points). The old file goes to the trash.
     */
    void reorderPdf(String relPath, int[] order, float width, float height, BridgeClient.Callback<byte[]> cb);

    /**
     * {@code {files:[{path, pageCount, matches:[{page, snippet, start, length, rects}]}],
     * total, truncated}}; rects are fractions of the page as shown.
     */
    void searchPdfs(String query, String relPathOrNull, BridgeClient.Callback<JSONObject> cb);
}
