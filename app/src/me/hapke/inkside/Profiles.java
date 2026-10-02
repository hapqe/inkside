package me.hapke.inkside;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Where the app keeps its state. There is one place: the tablet. Documents live in
 * {@code profiles/local/workspace} and ink, undo history and the session beside it. A
 * connected computer is a second copy of the workspace kept in step by {@link RemoteSync},
 * not a different place to work in.
 *
 * <p>Earlier versions kept one folder of state per computer (and, before that, in the
 * files dir itself). {@link #active} folds those into the single folder the first time
 * it runs: the state of the computer that was in use replaces the tablet-only state,
 * which is moved aside, not deleted.
 */
final class Profiles {
    static final String LOCAL = "local";
    private static final String PREFS = "inkside_profiles";
    private static final String KEY_UNIFIED = "unified";
    private static final String HOST_DIR_PREFIX = "host-";
    private static final String BACKUP_PREFIX = "_before-merge-";

    private Profiles() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The one profile; folds state kept under older layouts into it first. */
    static String active(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(KEY_UNIFIED, false)) {
            try {
                fold(ctx);
            } catch (Exception e) {
                android.util.Log.w("Profiles", "could not fold older state", e);
            }
            p.edit().putBoolean(KEY_UNIFIED, true).commit();
        }
        return LOCAL;
    }

    static File filesDir(Context ctx, String profile) {
        File dir = new File(ctx.getFilesDir(), "profiles/" + LOCAL);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Files on the tablet's own workspace. */
    static File localWorkspaceRoot(Context ctx) {
        return new File(filesDir(ctx, LOCAL), "workspace");
    }

    /** A short tag for cache keys. */
    static String cacheTag(String profile) {
        return "l";
    }

    // ---- folding older layouts ----------------------------------------------------------

    /** Everything in a profile folder except the documents and the sync record is app state. */
    private static boolean isState(File f) {
        String n = f.getName();
        return !n.equals("workspace") && !n.equals("sync_state.json") && !n.startsWith("_");
    }

    private static boolean hasState(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) if (isState(k) && k.getName().startsWith("session_state")) return true;
        return false;
    }

    private static void fold(Context ctx) {
        File base = ctx.getFilesDir();
        File profiles = new File(base, "profiles");
        File target = filesDir(ctx, LOCAL);

        // Candidates: a computer's folder (newest session first), else the files dir itself.
        File source = null;
        long best = -1;
        File[] dirs = profiles.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory() || !d.getName().startsWith(HOST_DIR_PREFIX) || !hasState(d)) continue;
                File s = new File(d, AppStateStore.FILE_NAME);
                long m = s.isFile() ? s.lastModified() : 0;
                if (m > best) {
                    best = m;
                    source = d;
                }
            }
        }
        boolean fromBase = false;
        if (source == null && new File(base, AppStateStore.FILE_NAME).isFile()) {
            source = base;
            fromBase = true;
        }
        if (source == null) return;

        // Tablet-only state is kept aside before the other replaces it.
        if (hasState(target)) {
            File backup = new File(profiles, BACKUP_PREFIX + System.currentTimeMillis());
            //noinspection ResultOfMethodCallIgnored
            backup.mkdirs();
            File[] mine = target.listFiles();
            if (mine != null) for (File f : mine) if (isState(f)) f.renameTo(new File(backup, f.getName()));
        }
        File[] items = source.listFiles();
        if (items != null) {
            for (File f : items) {
                String n = f.getName();
                boolean state = fromBase
                        ? n.startsWith("session_state") || n.equals("doc_states") || n.equals("undo_history")
                                || n.equals("ink_index")
                        : isState(f);
                if (state) f.renameTo(new File(target, n));
            }
        }
        if (!fromBase) source.renameTo(new File(profiles, "_merged-" + source.getName()));
    }
}
