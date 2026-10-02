package me.hapke.inkside;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Keeps crashes instead of losing them: an uncaught exception is written to the
 * app's private storage before the process dies, and the next launch uploads what
 * was saved to the bridge (bridge/logs/crashes on the Mac) and then deletes it.
 * Nothing leaves the device except to the user's own bridge.
 */
final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static final String DIR = "crashes";
    /** Never pile up more than this many unsent reports. */
    private static final int KEEP = 10;
    private static boolean installed;

    private CrashReporter() {}

    static synchronized void install(Context ctx) {
        if (installed || ctx == null) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(app, thread, error);
            } catch (Throwable ignored) {
                // Reporting must never mask the original crash.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static void write(Context ctx, Thread thread, Throwable error) throws Exception {
        File dir = new File(ctx.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            pw.println("thread: " + (thread != null ? thread.getName() : "?"));
            error.printStackTrace(pw);
        }
        JSONObject o = new JSONObject();
        o.put("at", System.currentTimeMillis());
        o.put("appVersion", appVersion(ctx));
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        o.put("report", sw.toString());
        File f = new File(dir, "crash-" + System.currentTimeMillis() + ".json");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        prune(dir);
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < files.length - KEEP; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** Number of crash reports still waiting to be sent. */
    static int pendingCount(Context ctx) {
        File[] files = new File(ctx.getFilesDir(), DIR).listFiles((d, n) -> n.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    /**
     * Send saved reports to the bridge in the background; each is deleted once the
     * bridge has it. {@code onDone} runs on a background thread with the count sent.
     */
    static void uploadPending(Context ctx, BridgeClient bridge, java.util.function.IntConsumer onDone) {
        if (ctx == null || bridge == null) return;
        final File dir = new File(ctx.getApplicationContext().getFilesDir(), DIR);
        Thread t = new Thread(() -> {
            int sent = 0;
            File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    try {
                        byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
                        bridge.uploadCrashSync(new JSONObject(new String(raw, StandardCharsets.UTF_8)));
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        sent++;
                    } catch (org.json.JSONException bad) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete(); // unreadable — would fail forever
                    } catch (Exception e) {
                        Log.w(TAG, "crash upload deferred: " + e.getMessage());
                        break; // bridge unreachable: try again next launch
                    }
                }
            }
            if (onDone != null) onDone.accept(sent);
        }, "cc-crash-upload");
        t.setDaemon(true);
        t.start();
    }
}
