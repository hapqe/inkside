package me.hapke.inkside;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persisted favorites for tools, ink slots, and workspace paths. */
final class FavoritesStore {
    static final String TOOL_UNDO = "tool:undo";
    static final String TOOL_REDO = "tool:redo";
    static final String TOOL_PENCIL = "tool:pencil";
    static final String TOOL_ERASER = "tool:eraser";
    static final String TOOL_LASSO = "tool:lasso";
    static final String TOOL_TEXT = "tool:text";
    static final String TOOL_FOLDER = "tool:folder";
    static final String ACTION_NEW_PDF = "action:new_pdf";
    /** Floating chat on the last open chat, listening straight away. */
    static final String ACTION_INSTANT_CHAT = "action:instant_chat";
    /** Back to the document opened before the current one. */
    static final String ACTION_LAST_DOCUMENT = "action:last_document";

    private static final String PREFS = "cc_favorites";
    private static final String KEY = "ids";
    /** "id\tx\ty" lines: where each favorite sits in the radial, in dp from its center. */
    private static final String KEY_POS = "positions";

    /** Told whenever a favorite is newly added (not on removal or reordering). */
    interface OnAdded {
        void added(String id);
    }

    private static FavoritesStore instance;
    private OnAdded onAdded;
    private final SharedPreferences sp;
    private final LinkedHashSet<String> ids = new LinkedHashSet<>();
    private final java.util.HashMap<String, float[]> positions = new java.util.HashMap<>();

    private FavoritesStore(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "");
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split("\n")) {
                String id = p.trim();
                if (!id.isEmpty()) ids.add(id);
            }
        }
        String rawPos = sp.getString(KEY_POS, "");
        if (rawPos != null && !rawPos.isEmpty()) {
            for (String line : rawPos.split("\n")) {
                String[] parts = line.split("\t");
                if (parts.length != 3) continue;
                try {
                    positions.put(parts[0], new float[]{
                            Float.parseFloat(parts[1]), Float.parseFloat(parts[2])});
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /** Saved radial offset (dp) for a favorite, or null when it sits on the default ring. */
    synchronized float[] position(String id) {
        float[] p = positions.get(id);
        return p != null ? p.clone() : null;
    }

    synchronized void setPosition(String id, float x, float y) {
        if (id == null || id.isEmpty()) return;
        positions.put(id, new float[]{x, y});
        persistPositions();
    }

    /** Back to the automatic ring for every favorite. */
    synchronized void clearPositions() {
        positions.clear();
        persistPositions();
    }

    private void persistPositions() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, float[]> e : positions.entrySet()) {
            if (!ids.contains(e.getKey())) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\t').append(e.getValue()[0])
                    .append('\t').append(e.getValue()[1]);
        }
        sp.edit().putString(KEY_POS, sb.toString()).apply();
    }

    static FavoritesStore get(Context ctx) {
        if (instance == null) {
            instance = new FavoritesStore(ctx);
        }
        return instance;
    }

    static String colorId(int index) {
        return "color:" + index;
    }

    static String fileId(String path) {
        return "file:" + normalizePath(path);
    }

    static boolean isFileId(String id) {
        return id != null && id.startsWith("file:");
    }

    static String pathFromFileId(String id) {
        if (!isFileId(id)) return null;
        return id.substring("file:".length());
    }

    static String normalizePath(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return ".";
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "." : p;
    }

    void setOnAdded(OnAdded listener) {
        onAdded = listener;
    }

    private void notifyAdded(String id) {
        OnAdded l = onAdded;
        if (l != null) l.added(id);
    }

    synchronized boolean isFavorite(String id) {
        return id != null && ids.contains(id);
    }

    synchronized void setFavorite(String id, boolean on) {
        if (id == null || id.isEmpty()) return;
        boolean added = on && !ids.contains(id);
        if (on) ids.add(id);
        else ids.remove(id);
        if (!on) positions.remove(id);
        persist();
        if (added) notifyAdded(id);
    }

    /** Keep a favorite (and its radial spot) when its file is renamed or moved. */
    synchronized void replaceId(String oldId, String newId) {
        if (oldId == null || newId == null || !ids.contains(oldId) || oldId.equals(newId)) return;
        java.util.List<String> order = new java.util.ArrayList<>(ids);
        ids.clear();
        for (String i : order) ids.add(i.equals(oldId) ? newId : i);
        float[] pos = positions.remove(oldId);
        if (pos != null) positions.put(newId, pos);
        persist();
        persistPositions();
    }

    synchronized boolean toggle(String id) {
        if (id == null || id.isEmpty()) return false;
        boolean now = !ids.contains(id);
        if (now) ids.add(id);
        else ids.remove(id);
        if (!now) positions.remove(id);
        persist();
        if (now) notifyAdded(id);
        return now;
    }

    /**
     * New order for the favorites (the radial's n-gon, clockwise from the top). Ids
     * not listed keep their relative order after the listed ones. Free positions from
     * the old drag-anywhere layout are dropped: the ring decides where items go.
     */
    synchronized void setOrder(List<String> ordered) {
        LinkedHashSet<String> next = new LinkedHashSet<>();
        for (String id : ordered) {
            if (ids.contains(id)) next.add(id);
        }
        next.addAll(ids);
        ids.clear();
        ids.addAll(next);
        positions.clear();
        persist();
        persistPositions();
    }

    /** Favorite ids in insertion order. */
    synchronized List<String> allOrdered() {
        return new ArrayList<>(ids);
    }

    /** Favorite file paths in insertion order. */
    synchronized List<String> favoritePaths() {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (isFileId(id)) out.add(pathFromFileId(id));
        }
        return out;
    }

    synchronized Set<String> snapshot() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    private void persist() {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(id);
        }
        sp.edit().putString(KEY, sb.toString()).apply();
    }
}
