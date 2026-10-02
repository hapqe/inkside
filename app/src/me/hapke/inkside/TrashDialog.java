package me.hapke.inkside;

import java.util.List;

/** ⋮ → Recently deleted: what was deleted from this workspace, tap to put it back. */
final class TrashDialog {
    private TrashDialog() {}

    static void show(MainActivity act) {
        act.workspace.listTrash(new BridgeClient.Callback<List<BridgeClient.TrashItem>>() {
            @Override
            public void onSuccess(List<BridgeClient.TrashItem> items) {
                if (act.isDead()) return;
                if (items.isEmpty()) {
                    new M3Dialog.Builder(act)
                            .setTitle("Recently deleted")
                            .setMessage("Nothing here.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                CharSequence[] labels = new CharSequence[items.size()];
                long now = System.currentTimeMillis();
                for (int i = 0; i < items.size(); i++) {
                    BridgeClient.TrashItem it = items.get(i);
                    labels[i] = it.original + (it.dir ? "/" : "") + "  ·  " + ago(now - it.deletedAt);
                }
                new M3Dialog.Builder(act)
                        .setTitle("Tap to restore")
                        .setItems(labels, (d, which) -> restore(act, items.get(which)))
                        .setNegativeButton("Close", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                if (!act.isDead()) act.snackbar("Could not read the trash: " + message, false);
            }
        });
    }

    private static void restore(MainActivity act, BridgeClient.TrashItem item) {
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("name", item.name);
            act.workspace.fsOp("/fs/restore", body, new BridgeClient.Callback<String>() {
                @Override
                public void onSuccess(String to) {
                    if (act.isDead()) return;
                    act.snackbar("Restored " + to, false);
                    act.refreshFileViews();
                }

                @Override
                public void onError(String message) {
                    if (!act.isDead()) act.snackbar("Restore failed: " + message, false);
                }
            });
        } catch (Exception ignored) {
            // The body is two plain strings.
        }
    }

    private static String ago(long ms) {
        long m = Math.max(0, ms) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        return d + (d == 1 ? " day ago" : " days ago");
    }
}
