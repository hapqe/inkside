package me.hapke.inkside;

import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The remote side of the workspace: the tablet always holds the documents; a connected
 * computer holds a copy of them (kept in step by {@link RemoteSync}) and adds the agent,
 * scripts and voice. One computer at a time; disconnecting stops the sync and the agent, and
 * connecting again brings both back. A computer is connected with nothing but an access
 * token (see {@link HostLink#decode}). Reaches shared state through {@code act}.
 */
final class Computers {
    private final MainActivity act;
    private boolean reconnecting;
    /** What the sync last reported, for the Settings block. */
    private String syncText = "";
    private boolean syncBusy;
    private boolean syncError;
    private TextView syncLabel;
    private android.widget.ProgressBar syncBar;
    private float syncFraction;

    Computers(MainActivity act) {
        this.act = act;
    }

    /** True when a computer (its agent, scripts, voice) is linked and switched on. */
    boolean hasHost() {
        return act.pairedHost != null;
    }

    /** What the workspace is called in menus: the computer's name, or "This tablet". */
    String workspaceName() {
        if (act.pairedHost != null) return act.pairedHost.name;
        return "This tablet";
    }

    /** Sync state, from {@link RemoteSync}; updates the Settings block if it is showing. */
    void onSyncStatus(String text, boolean busy, boolean error) {
        syncText = text;
        syncBusy = busy;
        syncError = error;
        styleSyncViews();
    }

    /** How far the running pass is (done of total changes); drives the progress bar. */
    void onSyncProgress(int done, int total, boolean busy) {
        syncFraction = total <= 0 ? 0f : Math.min(1f, done / (float) total);
        syncBusy = busy;
        styleSyncViews();
    }

    private void styleSyncViews() {
        if (syncBar != null && syncBar.isAttachedToWindow()) {
            syncBar.setVisibility(syncBusy ? View.VISIBLE : View.INVISIBLE);
            syncBar.setProgress(Math.round(syncFraction * 1000f));
        }
        if (syncLabel != null && syncLabel.isAttachedToWindow()) {
            // Only a problem is worth words; progress is the bar.
            syncLabel.setVisibility(syncError ? View.VISIBLE : View.GONE);
            syncLabel.setText(syncText);
        }
    }

    /**
     * For features that run on a computer (chat, scripts, dictation): true when one is
     * linked and on; otherwise explains and offers to connect or switch the link on.
     */
    boolean requireHost(String feature) {
        if (hasHost()) return true;
        new M3Dialog.Builder(act)
                .setTitle(feature + " needs a computer")
                .setMessage("Connect a computer running the Inkside host to use it.")
                .setPositiveButton("Connect", (d, w) -> showConnect())
                .setNegativeButton("Not now", null)
                .show();
        return false;
    }

    /** The Remote entry in ⋮: sync now or disconnect; or connect when nothing is linked. */
    void showRemoteMenu() {
        final PairedHosts.Host linked = PairedHosts.remote(act);
        if (linked == null) {
            showConnect();
            return;
        }
        new M3Dialog.Builder(act)
                .setTitle(linked.name)
                .setItems(new String[]{"Sync now", "Disconnect"}, (d, which) -> {
                    if (which == 0) {
                        if (act.remoteSync != null) act.remoteSync.syncNow(null);
                    } else {
                        confirmDisconnect(linked);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Connecting is just an access token: from a tester's invite, or printed by your host. */
    void showConnect() {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(line("Paste your access token. Testers get one from us; on your own computer "
                + "the Inkside host prints one when it starts (or run npm run token).",
                M3Dialog.onSurfaceVariant, 14));
        final android.widget.EditText token = field("Access token",
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(token, fieldLp());
        TextView status = line("", M3Dialog.onSurfaceVariant, 13);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = act.dp(MainActivity.SPACE_MD);
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(row, rlp);
        final M3Dialog[] dialog = new M3Dialog[1];
        View.OnClickListener go = v -> {
            String t = token.getText().toString().trim();
            if (t.isEmpty()) {
                status.setText("Paste your access token.");
                return;
            }
            status.setText("Connecting\u2026");
            HostLink.connect(act, t, new HostLink.Result<PairedHosts.Host>() {
                @Override
                public void onSuccess(PairedHosts.Host host) {
                    if (dialog[0] != null) dialog[0].dismiss();
                    onConnected(host);
                }

                @Override
                public void onError(String message) {
                    status.setText(message);
                }
            });
        };
        row.addView(act.panelAction("Connect", true, () -> go.onClick(null)));
        dialog[0] = new M3Dialog.Builder(act)
                .setTitle("Connect a computer")
                .setView(body)
                .setNegativeButton("Cancel", null)
                .show();
        token.setOnEditorActionListener((v, actionId, e) -> {
            go.onClick(v);
            return true;
        });
        token.requestFocus();
    }

    private android.widget.EditText field(String hint, int inputType) {
        android.widget.EditText f = new android.widget.EditText(act);
        f.setInputType(inputType);
        f.setHint(hint);
        f.setTextColor(M3Dialog.onSurface);
        f.setHintTextColor(M3Dialog.outlineVariant);
        f.setSingleLine(true);
        return f;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = act.dp(MainActivity.SPACE_MD);
        return lp;
    }

    private void onConnected(PairedHosts.Host host) {
        // One computer at a time: connecting a new one replaces the old.
        for (PairedHosts.Host other : PairedHosts.all(act)) {
            if (!other.id.equals(host.id)) PairedHosts.remove(act, other.id);
        }
        PairedHosts.setRemote(act, host.id);
        PairedHosts.setEnabled(act, true);
        act.snackbar("Connected to " + host.name, false);
        restart();
    }

    private void confirmDisconnect(PairedHosts.Host host) {
        new M3Dialog.Builder(act)
                .setTitle("Disconnect " + host.name + "?")
                .setMessage("Your documents stay on this tablet and on the computer.")
                .setPositiveButton("Disconnect", (d, w) -> {
                    PairedHosts.remove(act, host.id);
                    restart();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Saves the session and restarts the activity on the new setting. */
    private void restart() {
        if (!act.restoring) act.persistence.saveSessionNow(true);
        act.recreate();
    }

    /**
     * The computer stopped answering where it used to: try its other known addresses
     * and quietly switch to one that answers.
     */
    void reconnect() {
        final PairedHosts.Host host = act.pairedHost;
        if (host == null || reconnecting || host.urls.size() < 2) return;
        reconnecting = true;
        new Thread(() -> {
            String url = HostLink.reachableUrl(host, 2000);
            act.runOnUiThread(() -> {
                reconnecting = false;
                if (url != null) useUrl(host, url);
            });
        }, "reconnect").start();
    }

    private void useUrl(PairedHosts.Host host, String url) {
        reconnecting = false;
        if (act.isDead() || url.equals(act.bridge.getBaseUrl())) return;
        if (!host.urls.contains(url)) host.urls.add(url);
        host.preferUrl(url);
        PairedHosts.save(act, host);
        act.bridge.setBaseUrl(url);
        act.bridge.stopEvents();
        act.canvasAgent.startCanvasEvents();
        act.bridgeStatus.checkBridgeHealthNow();
    }

    // ---- Settings ----------------------------------------------------------------

    private TextView line(String text, int color, float sp) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private LinearLayout row(View label, View action) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, act.dp(MainActivity.SPACE_SM), 0, act.dp(MainActivity.SPACE_SM));
        r.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            r.addView(action, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return r;
    }

    /** The Remote block in Settings: the computer, a progress bar while syncing, Live sync, a link to the guide. */
    View settingsRows() {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);

        final PairedHosts.Host linked = PairedHosts.remote(act);
        if (linked == null) {
            wrap.addView(row(line("No computer", act.M3_ON_SURFACE_VARIANT, 14),
                    act.panelAction("Connect", true, this::showConnect)));
        } else {
            LinearLayout actions = new LinearLayout(act);
            actions.addView(act.panelAction("Sync now", false, () -> {
                if (act.remoteSync != null) act.remoteSync.syncNow(null);
            }));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = act.dp(MainActivity.SPACE_SM);
            actions.addView(act.panelAction("Disconnect", false, () -> confirmDisconnect(linked)), dlp);
            wrap.addView(row(line(linked.name, act.M3_ON_SURFACE, 14), actions));

            syncBar = new android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            syncBar.setMax(1000);
            syncBar.setProgressTintList(android.content.res.ColorStateList.valueOf(act.M3_PRIMARY));
            syncBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(act.M3_OUTLINE_VARIANT));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, act.dp(4));
            blp.topMargin = act.dp(MainActivity.SPACE_XS);
            wrap.addView(syncBar, blp);
            syncLabel = line("", 0xFFFF8A80, 12);
            wrap.addView(syncLabel);
            wrap.addView(act.settingsPanel.settingsSwitchItem(R.drawable.ic_sync, "Live sync", null,
                    PairedHosts.liveSync(act), liveOn -> {
                        PairedHosts.setLiveSync(act, liveOn);
                        if (act.remoteSync != null) act.remoteSync.setLive(liveOn);
                    }), MainActivity.matchWrap());
            styleSyncViews();
        }
        TextView guide = act.panelAction("Setup guide on GitHub", false, () -> {
            try {
                act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(AboutDialog.REPO_URL + "#readme")));
            } catch (Exception e) {
                act.statusToast("No browser available");
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = act.dp(MainActivity.SPACE_SM);
        wrap.addView(guide, glp);
        return act.settingsPanel.settingsBlock(R.drawable.ic_link, "Remote", null, wrap);
    }
}
