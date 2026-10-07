package me.hapke.inkside;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * ⋮ → Update: looks up the newest release on GitHub, and on request downloads its APK
 * and hands it to the system installer (which asks the user to confirm). Releases are
 * signed with the release key, so this updates a release install in place.
 */
final class Updater {
    private static final String LATEST = "https://api.github.com/repos/hapqe/inkside/releases/latest";
    private static final String ACTION_STATUS = "me.hapke.inkside.UPDATE_INSTALL_STATUS";

    private final MainActivity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    Updater(MainActivity act) {
        this.act = act;
    }

    private String installedVersion() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** -1, 0, 1 comparing dotted versions ("0.3.1" vs "v0.3.0"). */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("[.-]");
        String[] y = b.replaceFirst("^v", "").split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Dialog ------------------------------------------------------------------

    private TextView title, detail, notes;
    private Material3ProgressBar bar;
    private TextView action;
    private M3Dialog dialog;
    private String apkUrl;
    private String latestTag;

    void show() {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);

        title = text(22, act.M3_ON_SURFACE, false);
        HeadlineFont.apply(title);
        title.setText("Checking for updates…");
        body.addView(title, MainActivity.matchWrap());
        detail = text(14, act.M3_ON_SURFACE_VARIANT, false);
        detail.setText("Installed: " + installedVersion());
        LinearLayout.LayoutParams dlp = MainActivity.matchWrap();
        dlp.topMargin = act.dp(4);
        body.addView(detail, dlp);

        notes = text(14, act.M3_ON_SURFACE, false);
        notes.setLineSpacing(0f, 1.2f);
        notes.setVisibility(View.GONE);
        notes.setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(14));
        android.graphics.drawable.GradientDrawable nb = new android.graphics.drawable.GradientDrawable();
        nb.setCornerRadius(act.dp(16));
        nb.setColor(act.M3_SURFACE_CONTAINER_HIGHEST | 0xFF000000);
        notes.setBackground(nb);
        LinearLayout.LayoutParams nlp = MainActivity.matchWrap();
        nlp.topMargin = act.dp(16);
        body.addView(notes, nlp);

        bar = new Material3ProgressBar(act);
        bar.applyColors(act.M3_PRIMARY, act.M3_SURFACE_CONTAINER_HIGHEST);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, act.dp(8));
        blp.topMargin = act.dp(18);
        body.addView(bar, blp);

        action = act.panelAction("Download & install", true, this::download);
        action.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = act.dp(18);
        alp.gravity = Gravity.END;
        body.addView(action, alp);

        dialog = new M3Dialog.Builder(act)
                .setTitle("Update")
                .setView(body)
                .setNegativeButton("Close", null)
                .show();
        check();
    }

    private void check() {
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(LATEST));
                String tag = rel.optString("tag_name", "");
                String url = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                            url = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                final String apk = url;
                final String body = rel.optString("body", "").trim();
                main.post(() -> showLatest(tag, apk, body));
            } catch (Exception e) {
                main.post(() -> {
                    title.setText("Could not check for updates");
                    detail.setText(e.getMessage() != null ? e.getMessage() : "No connection");
                    bar.setVisibility(View.GONE);
                });
            }
        }, "update-check").start();
    }

    private void showLatest(String tag, String apk, String body) {
        latestTag = tag;
        apkUrl = apk;
        bar.setVisibility(View.GONE);
        String installed = installedVersion();
        boolean newer = !tag.isEmpty() && compareVersions(tag, installed) > 0;
        title.setText(newer ? "Inkside " + tag.replaceFirst("^v", "") + " is available" : "You're up to date");
        detail.setText("Installed: " + installed + (tag.isEmpty() ? "" : "  ·  Latest: " + tag.replaceFirst("^v", "")));
        if (!body.isEmpty()) {
            notes.setText(body);
            notes.setVisibility(View.VISIBLE);
        }
        if (apk != null) {
            action.setText(newer ? "Download & install" : "Reinstall latest");
            action.setVisibility(View.VISIBLE);
        }
    }

    // ---- Download and install ----------------------------------------------------

    private void download() {
        if (busy || apkUrl == null) return;
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            // Android asks once per app before it may install updates.
            act.snackbar("Allow Inkside to install updates, then tap the button again", true);
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName())));
            return;
        }
        busy = true;
        action.setVisibility(View.GONE);
        bar.setIndeterminate(false);
        bar.setProgress(0f);
        bar.setVisibility(View.VISIBLE);
        title.setText("Downloading " + latestTag.replaceFirst("^v", "") + "…");
        final File out = new File(act.getCacheDir(), "update.apk");
        new Thread(() -> {
            try {
                HttpURLConnection c = open(apkUrl);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n;
                    long lastPost = 0;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (total > 0 && now - lastPost > 80) {
                            lastPost = now;
                            final float p = done / (float) total;
                            final long d = done;
                            main.post(() -> {
                                bar.setProgress(p);
                                detail.setText(mb(d) + " of " + mb(total));
                            });
                        }
                    }
                }
                main.post(() -> {
                    bar.setProgress(1f);
                    title.setText("Installing…");
                    detail.setText("Confirm in the dialog Android shows");
                });
                install(out);
            } catch (Exception e) {
                busy = false;
                main.post(() -> {
                    title.setText("Download failed");
                    detail.setText(e.getMessage() != null ? e.getMessage() : "No connection");
                    bar.setVisibility(View.GONE);
                    action.setText("Try again");
                    action.setVisibility(View.VISIBLE);
                });
            }
        }, "update-download").start();
    }

    private void install(File apk) throws Exception {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(act.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = s.openWrite("inkside.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            }
            main.post(this::listenForStatus);
            Intent i = new Intent(ACTION_STATUS).setPackage(act.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(act, id, i, flags);
            s.commit(pending.getIntentSender());
        }
    }

    private BroadcastReceiver receiver;

    /** The installer reports back: ask the user to confirm, or say what went wrong. */
    private void listenForStatus() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(confirm);
                    }
                    return;
                }
                busy = false;
                if (status == PackageInstaller.STATUS_SUCCESS) return;  // the app restarts
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_FAILURE_CONFLICT
                        || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    msg = "this copy was signed differently (a development build) — "
                            + "uninstall it once, then install the release";
                }
                if (title != null) {
                    title.setText(status == PackageInstaller.STATUS_FAILURE_ABORTED
                            ? "Update cancelled" : "Update failed");
                    detail.setText(msg != null ? msg : "");
                    bar.setVisibility(View.GONE);
                    action.setText("Try again");
                    action.setVisibility(View.VISIBLE);
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            act.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            act.registerReceiver(receiver, f);
        }
    }

    // ---- Small helpers -------------------------------------------------------------

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        c.setRequestProperty("User-Agent", "Inkside-updater");
        if (c.getResponseCode() >= 400) throw new Exception("GitHub answered " + c.getResponseCode());
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }
}
