package me.hapke.inkside;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Talking to a host before it is part of the app: connecting by address (the host
 * answers devices on its own network) and checking which saved address still works.
 */
final class HostLink {
    private HostLink() {}

    interface Result<T> {
        void onSuccess(T value);

        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static JSONObject request(String method, String url, JSONObject body, String token, int timeoutMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                in.close();
            }
            String raw = bos.toString("UTF-8");
            JSONObject o = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            if (code >= 400) throw new Exception(o.optString("error", "HTTP " + code));
            return o;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Connects to the computer at {@code address} ("192.168.1.20", "host:8787" or a
     * URL): it answers /health with its id and name when this tablet is on its
     * network. Saves it and calls back on the main thread.
     */
    static void connect(Context ctx, String address, String token, Result<PairedHosts.Host> cb) {
        final Context app = ctx.getApplicationContext();
        final String url = normalize(address);
        new Thread(() -> {
            String error;
            try {
                JSONObject health = request("GET", url + "/health", null, token, 4000);
                String id = health.optString("hostId", "");
                if (health.optBoolean("authenticated", false) && !id.isEmpty()) {
                    PairedHosts.Host h = PairedHosts.get(app, id);
                    if (h == null) h = new PairedHosts.Host(id);
                    h.name = health.optString("name", "Computer");
                    h.port = Uri.parse(url).getPort();
                    h.token = token == null ? "" : token;
                    if (!h.urls.contains(url)) h.urls.add(url);
                    h.preferUrl(url);
                    PairedHosts.save(app, h);
                    final PairedHosts.Host done = h;
                    MAIN.post(() -> cb.onSuccess(done));
                    return;
                }
                if ("network".equals(health.optString("reason"))) {
                    error = "that computer only accepts devices on its own network — "
                            + "connect this tablet to the same Wi-Fi";
                } else if ("token".equals(health.optString("reason"))) {
                    error = token == null || token.isEmpty()
                            ? "that computer needs an access token — enter the one you were given"
                            : "that access token was not accepted";
                } else {
                    error = "that computer runs an older host — update it first";
                }
            } catch (Exception e) {
                error = url.startsWith("https://")
                        ? "no answer from " + url.replace("https://", "")
                                + " — check the address and this tablet's internet connection"
                        : "no answer from " + url.replace("http://", "")
                                + " — is the Inkside host running, and is this tablet on the same network?";
            }
            final String msg = error;
            MAIN.post(() -> cb.onError(msg));
        }, "connect").start();
    }

    /**
     * "192.168.1.20" → "http://192.168.1.20:8787"; a name on the internet ("inkside.hapke.me")
     * → "https://inkside.hapke.me", since such a host sits behind a TLS proxy.
     */
    static String normalize(String address) {
        String a = address == null ? "" : address.trim();
        boolean scheme = a.startsWith("http://") || a.startsWith("https://");
        if (!scheme) a = (isInternetName(a) ? "https://" : "http://") + a;
        a = a.replaceAll("/+$", "");
        // https addresses (a proxy in front) use their own port.
        if (Uri.parse(a).getPort() < 0 && !a.startsWith("https://")) a = a + ":8787";
        return a;
    }

    /**
     * A bare domain name with no port: not an address, not a name on the local network
     * (".local", a tailnet's ".ts.net", a single-label machine name).
     */
    static boolean isInternetName(String address) {
        String a = address.toLowerCase(java.util.Locale.ROOT).replaceAll("/.*$", "");
        if (a.isEmpty() || a.startsWith("[") || a.contains(":") || !a.contains(".")) return false;
        if (a.matches("[0-9.]+")) return false;
        return !(a.endsWith(".local") || a.endsWith(".ts.net") || a.endsWith(".lan")
                || a.endsWith(".home") || a.endsWith(".internal"));
    }

    /**
     * The first of the host's addresses that answers as that host and lets this
     * tablet in.
     * Blocking; null when none does.
     */
    static String reachableUrl(PairedHosts.Host host, int timeoutMs) {
        for (String url : host.urls) {
            try {
                JSONObject h = request("GET", url + "/health", null, host.token, timeoutMs);
                if (host.id.equals(h.optString("hostId")) && h.optBoolean("authenticated")) return url;
            } catch (Exception ignored) {
                // try the next address
            }
        }
        return null;
    }
}
