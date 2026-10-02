package me.hapke.inkside;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Computers this tablet has connected to: each one's id and name and the addresses
 * it was reachable on (tried in order, last good one first).
 */
final class PairedHosts {
    private static final String PREFS = "inkside_hosts";
    private static final String KEY = "hosts";

    static final class Host {
        final String id;
        String name;
        int port;
        /** "http://a.b.c.d:port" candidates, the one that last worked first. */
        final List<String> urls = new ArrayList<>();
        /** The access token for a computer that asks for one (its BRIDGE_TOKEN); empty if none. */
        String token = "";

        Host(String id) {
            this.id = id;
        }

        String url() {
            return urls.isEmpty() ? "" : urls.get(0);
        }

        /** Puts {@code url} first (it just worked). */
        void preferUrl(String url) {
            urls.remove(url);
            urls.add(0, url);
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("port", port);
            o.put("urls", new JSONArray(urls));
            if (!token.isEmpty()) o.put("token", token);
            return o;
        }

        static Host fromJson(JSONObject o) {
            Host h = new Host(o.optString("id"));
            h.name = o.optString("name", "Computer");
            h.port = o.optInt("port", 8787);
            h.token = o.optString("token", "");
            JSONArray u = o.optJSONArray("urls");
            if (u != null) for (int i = 0; i < u.length(); i++) h.urls.add(u.optString(i));
            return h;
        }
    }

    private PairedHosts() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Host> all(Context ctx) {
        List<Host> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !o.optString("id").isEmpty()) out.add(Host.fromJson(o));
            }
        } catch (Exception ignored) {
            // Unreadable list: as if nothing were paired.
        }
        return out;
    }

    private static final String KEY_CURRENT = "current";
    private static final String KEY_ENABLED = "enabled";

    /** The computer this tablet is linked to (the one most recently connected), or null. */
    static Host remote(Context ctx) {
        List<Host> list = all(ctx);
        String cur = prefs(ctx).getString(KEY_CURRENT, "");
        for (Host h : list) if (h.id.equals(cur)) return h;
        return list.isEmpty() ? null : list.get(0);
    }

    static void setRemote(Context ctx, String id) {
        prefs(ctx).edit().putString(KEY_CURRENT, id).commit();
    }

    /** Whether the link is switched on: when off, nothing is synced and no computer features are offered. */
    static boolean enabled(Context ctx) {
        // There is no off switch any more (disconnecting is the way to stop): a linked computer is on.
        return true;
    }

    /**
     * Live sync: changes go to the computer within seconds and new ones are fetched every few
     * seconds. Off, nothing syncs until "Sync now" (and before a chat message or script run).
     */
    static boolean liveSync(Context ctx) {
        return prefs(ctx).getBoolean("live", true);
    }

    static void setLiveSync(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("live", on).commit();
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).commit();
    }

    static Host get(Context ctx, String id) {
        for (Host h : all(ctx)) if (h.id.equals(id)) return h;
        return null;
    }

    static void save(Context ctx, Host host) {
        List<Host> list = all(ctx);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(host.id)) {
                list.set(i, host);
                replaced = true;
            }
        }
        if (!replaced) list.add(host);
        write(ctx, list);
    }

    static void remove(Context ctx, String id) {
        List<Host> list = all(ctx);
        list.removeIf(h -> h.id.equals(id));
        write(ctx, list);
    }

    private static void write(Context ctx, List<Host> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Host h : list) arr.put(h.toJson());
            prefs(ctx).edit().putString(KEY, arr.toString()).commit();
        } catch (Exception ignored) {
        }
    }
}
