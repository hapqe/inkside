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
 * Talking to a host before it is part of the app: connecting with an access token (all a
 * device needs) and checking which of a saved host's addresses still answers.
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

    /** Where a plain access token connects: the Inkside test computer, over the internet. */
    static final String TEST_COMPUTER_URL = "https://inkside.hapke.me";
    private static final String CONNECT_PREFIX = "ink1.";

    /**
     * What an access token says: the token itself, and where its host is. A connection
     * token ("ink1." + base64url of {"u": [urls], "t": token}, printed by a host) carries
     * its addresses; a plain token belongs to the test computer.
     */
    static String[] decode(String raw, java.util.List<String> urls) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith(CONNECT_PREFIX)) {
            try {
                byte[] json = android.util.Base64.decode(s.substring(CONNECT_PREFIX.length()),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                JSONObject o = new JSONObject(new String(json, StandardCharsets.UTF_8));
                org.json.JSONArray u = o.optJSONArray("u");
                for (int i = 0; u != null && i < u.length(); i++) {
                    String url = u.optString(i, "").trim().replaceAll("/+$", "");
                    if (!url.isEmpty()) urls.add(url);
                }
                return new String[]{o.optString("t", "")};
            } catch (Exception e) {
                return new String[]{""};
            }
        }
        urls.add(TEST_COMPUTER_URL);
        return new String[]{s};
    }

    /**
     * Connects with an access token: tries the addresses it names (or the test computer)
     * until one answers /health as authenticated. Saves the host and calls back on the
     * main thread.
     */
    static void connect(Context ctx, String accessToken, Result<PairedHosts.Host> cb) {
        final Context app = ctx.getApplicationContext();
        final java.util.List<String> urls = new java.util.ArrayList<>();
        final String token = decode(accessToken, urls)[0];
        new Thread(() -> {
            String error = token.isEmpty()
                    ? "that is not an access token — copy it again, all of it"
                    : null;
            for (String url : urls) {
                if (token.isEmpty()) break;
                try {
                    JSONObject health = request("GET", url + "/health", null, token, 6000);
                    String id = health.optString("hostId", "");
                    if (health.optBoolean("authenticated", false) && !id.isEmpty()) {
                        PairedHosts.Host h = PairedHosts.get(app, id);
                        if (h == null) h = new PairedHosts.Host(id);
                        h.name = health.optString("name", "Computer");
                        h.port = Uri.parse(url).getPort();
                        h.token = token;
                        h.urls.clear();
                        h.urls.addAll(urls);
                        h.preferUrl(url);
                        PairedHosts.save(app, h);
                        final PairedHosts.Host done = h;
                        MAIN.post(() -> cb.onSuccess(done));
                        return;
                    }
                    if ("token".equals(health.optString("reason"))) {
                        error = "that access token was not accepted";
                    } else if (error == null) {
                        error = "that computer runs an older host — update it first";
                    }
                } catch (Exception e) {
                    if (error == null) {
                        error = "no answer — check this tablet's internet connection, "
                                + "and that the computer is running";
                    }
                }
            }
            final String msg = error;
            MAIN.post(() -> cb.onError(msg));
        }, "connect").start();
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
