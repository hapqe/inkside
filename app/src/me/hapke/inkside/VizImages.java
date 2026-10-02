package me.hapke.inkside;

import android.graphics.Bitmap;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps script output images on the canvas in sync with what the agent and scripts wrote.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class VizImages {
    private final MainActivity act;

    private static final Pattern VIZ_EMBED = Pattern.compile("\\[\\[(?:viz|artifact):([^\\]]+)\\]\\]");
    private static final Pattern OUTPUT_IMAGE = Pattern.compile(
            "(?:^|[\\s\"'])((?:[\\w.-]+/)*[\\w.-]+\\.(?:png|jpg|jpeg|gif|webp))",
            Pattern.CASE_INSENSITIVE);

    VizImages(MainActivity act) {
        this.act = act;
    }

    void syncVizImagesFromAgentText(String text) {
        if (text == null || act.canvas == null) return;
        Matcher m = VIZ_EMBED.matcher(text);
        Set<String> paths = new LinkedHashSet<>();
        while (m.find()) {
            String path = m.group(1).trim();
            if (!path.isEmpty()) paths.add(path);
        }
        for (String path : paths) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                refreshVizImageOnCanvas(path);
            }
        }
        act.conversations.refreshChatArtifacts();
    }

    private void refreshVizImageOnCanvas(String path) {
        refreshVizImageOnCanvas(path, Float.NaN, Float.NaN, true);
    }

    private void refreshVizImageOnCanvas(String path, float anchorX, float anchorY) {
        refreshVizImageOnCanvas(path, anchorX, anchorY, true);
    }

    private void refreshVizImageOnCanvas(String path, float anchorX, float anchorY, boolean allowCreate) {
        tryRefreshVizPath(path, 0, anchorX, anchorY, allowCreate);
    }

    private String[] vizPathVariants(String path) {
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
        if (path != null && !path.isEmpty()) {
            variants.add(path);
            String norm = CodeCanvasView.normalizeImagePath(path);
            if (!norm.isEmpty()) {
                variants.add(norm);
                variants.add(".artifacts/" + norm);
            }
            if (path.startsWith(".artifacts/")) {
                variants.add(path.substring(".artifacts/".length()));
            }
        }
        return variants.toArray(new String[0]);
    }

    private void tryRefreshVizPath(
            String path, int index, float anchorX, float anchorY, boolean allowCreate) {
        String[] variants = vizPathVariants(path);
        if (index >= variants.length) return;
        final String tryPath = variants[index];
        act.workspace.readFileBytes(tryPath, new BridgeClient.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] value) {
                if (act.isDead()) return;
                Bitmap bmp = ImageDecode.decode(value);
                if (bmp != null && act.canvas != null) {
                    boolean ok = act.canvas.addOrUpdateVizImage(tryPath, bmp, anchorX, anchorY, allowCreate);
                    if (ok) act.persistence.scheduleSave();
                }
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                tryRefreshVizPath(path, index + 1, anchorX, anchorY, allowCreate);
            }
        });
    }

    private static boolean isImagePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    private static String scriptParentDir(String scriptPath) {
        if (scriptPath == null || scriptPath.isEmpty()) return ".";
        int slash = scriptPath.lastIndexOf('/');
        return slash >= 0 ? scriptPath.substring(0, slash) : ".";
    }

    private void extractImagePaths(String text, String scriptPath, Set<String> out) {
        if (text == null || text.isEmpty()) return;
        String dir = scriptParentDir(scriptPath);
        Matcher m = OUTPUT_IMAGE.matcher(text);
        while (m.find()) {
            String p = m.group(1);
            if (p == null || p.isEmpty()) continue;
            out.add(p);
            if (!p.contains("/") && !".".equals(dir)) {
                out.add(dir + "/" + p);
            }
        }
    }

    private void addInferredScriptOutputs(String scriptPath, Set<String> paths) {
        String dir = scriptParentDir(scriptPath);
        int slash = scriptPath.lastIndexOf('/');
        String file = slash >= 0 ? scriptPath.substring(slash + 1) : scriptPath;
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        if (!".".equals(dir)) {
            paths.add(dir + "/" + base + ".png");
        }
        if (base.startsWith("regenerate_")) {
            String stem = base.substring("regenerate_".length());
            if (!stem.isEmpty() && !".".equals(dir)) {
                paths.add(dir + "/" + stem + ".png");
            }
        }
    }

    private void collectImagesFromListing(BridgeClient.DirListing listing, Set<String> paths) {
        if (listing == null || listing.items == null) return;
        for (BridgeClient.FileEntry entry : listing.items) {
            if (entry.type != null && "file".equals(entry.type) && isImagePath(entry.path)) {
                paths.add(entry.path);
            }
        }
    }

    private String pickScriptOutputLink(String scriptPath, Set<String> paths) {
        String dir = scriptParentDir(scriptPath);
        // Prefer paths under the script directory.
        for (String p : paths) {
            if (p.startsWith(dir + "/") && isImagePath(p)) return p;
        }
        for (String p : paths) {
            if (isImagePath(p)) return p;
        }
        return null;
    }

    /** Deduplicate by basename so chart.png and project/chart.png refresh once. */
    private List<String> uniqueImagePathsByBasename(Set<String> paths) {
        java.util.LinkedHashMap<String, String> byBase = new java.util.LinkedHashMap<>();
        for (String p : paths) {
            if (!isImagePath(p)) continue;
            String base = CodeCanvasView.imageBasename(p).toLowerCase();
            if (base.isEmpty()) continue;
            String prev = byBase.get(base);
            // Prefer directory-qualified paths.
            if (prev == null || (!p.contains("/") && prev.contains("/"))) {
                // keep prev if more specific
                if (prev != null && prev.contains("/") && !p.contains("/")) continue;
            }
            if (prev == null || (p.contains("/") && !prev.contains("/"))) {
                byBase.put(base, p);
            }
        }
        return new ArrayList<>(byBase.values());
    }

    void refreshImagesAfterScript(String scriptPath, String stdout, String stderr) {
        if (act.canvas == null) return;
        Set<String> explicit = new LinkedHashSet<>();
        extractImagePaths(stdout, scriptPath, explicit);
        extractImagePaths(stderr, scriptPath, explicit);
        addInferredScriptOutputs(scriptPath, explicit);

        String cached = act.scriptMetaCache.get(scriptPath);
        if (cached != null) {
            try {
                JSONObject meta = new JSONObject(cached);
                String output = meta.optString("output", null);
                if (output != null && !output.isEmpty()) explicit.add(output);
            } catch (Exception ignored) {
            }
        }

        Set<String> updateOnly = new LinkedHashSet<>(act.canvas.getTrackedImagePaths());

        final String scriptDir = scriptParentDir(scriptPath);
        final String[] dirsToScan = "."
                .equals(scriptDir) ? new String[]{".artifacts"} : new String[]{scriptDir, ".artifacts"};
        scanDirsThenRefreshImages(dirsToScan, 0, explicit, updateOnly, scriptPath);
    }

    private void scanDirsThenRefreshImages(
            String[] dirs,
            int index,
            Set<String> explicit,
            Set<String> updateOnly,
            String scriptPath) {
        if (index >= dirs.length) {
            // Explicit outputs: update existing (incl. untracked) or create once.
            List<String> primary = uniqueImagePathsByBasename(explicit);
            for (String p : primary) {
                refreshVizImageOnCanvas(p, Float.NaN, Float.NaN, true);
            }
            // Tracked / directory images: update in place only — never spawn duplicates.
            Set<String> rest = new LinkedHashSet<>(updateOnly);
            for (String p : primary) {
                rest.removeIf(r -> CodeCanvasView.imagePathsMatch(r, p));
            }
            for (String p : uniqueImagePathsByBasename(rest)) {
                refreshVizImageOnCanvas(p, Float.NaN, Float.NaN, false);
            }
            return;
        }
        act.workspace.listFiles(dirs[index], new BridgeClient.Callback<BridgeClient.DirListing>() {
            @Override
            public void onSuccess(BridgeClient.DirListing value) {
                if (act.isDead()) return;
                // Directory listings only feed update-only (avoid creating one card per file).
                collectImagesFromListing(value, updateOnly);
                // If listing finds a file matching an explicit basename, promote the full path.
                for (String e : new ArrayList<>(explicit)) {
                    String base = CodeCanvasView.imageBasename(e);
                    if (value.items == null) continue;
                    for (BridgeClient.FileEntry entry : value.items) {
                        if ("file".equals(entry.type) && CodeCanvasView.imagePathsMatch(base, entry.path)) {
                            explicit.add(entry.path);
                        }
                    }
                }
                scanDirsThenRefreshImages(dirs, index + 1, explicit, updateOnly, scriptPath);
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                scanDirsThenRefreshImages(dirs, index + 1, explicit, updateOnly, scriptPath);
            }
        });
    }
}
