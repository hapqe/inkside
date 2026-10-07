package me.hapke.inkside;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * Bridge URL / token storage and the connection health watch.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class BridgeStatus {
    private final MainActivity act;

    private static final String PREF_BRIDGE_URL = "bridgeUrl";

    BridgeStatus(MainActivity act) {
        this.act = act;
    }

    String loadBridgeUrl() {
        String fallback = act.getString(R.string.default_bridge);
        try {
            return act.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_BRIDGE_URL, fallback);
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "could not read bridge url pref", e);
            return fallback;
        }
    }

    void saveBridgeUrl(String url) {
        try {
            act.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_BRIDGE_URL, url).apply();
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "could not save bridge url pref", e);
        }
    }

    // ---- Bridge connection status -------------------------------------------------

    private static final long HEALTH_INTERVAL_MS = 20_000L;
    private static final long HEALTH_INTERVAL_OFFLINE_MS = 6_000L;
    /** 0 unknown, 1 ok, 2 unreachable, 3 token refused. */
    private int bridgeState = 0;
    private int bridgeFailures = 0;
    private boolean healthWatching;
    private boolean healthInFlight;
    private TextView bridgeStatusChip;
    private final Runnable healthTick = this::checkBridgeHealthNow;

    void startBridgeHealthWatch() {
        healthWatching = true;
        act.saveHandler.removeCallbacks(healthTick);
        act.saveHandler.postDelayed(healthTick, 1500L);
    }

    void stopBridgeHealthWatch() {
        healthWatching = false;
        act.saveHandler.removeCallbacks(healthTick);
    }

    /** Ask /health once; reschedules itself while the app is in front. */
    void checkBridgeHealthNow() {
        act.saveHandler.removeCallbacks(healthTick);
        if (act.bridge == null || act.isDead()) return;
        if (healthInFlight) return;
        healthInFlight = true;
        act.bridge.health(new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                healthInFlight = false;
                if (act.isDead()) return;
                boolean refused = value.optBoolean("authRequired", false)
                        && !value.optBoolean("authenticated", true);
                boolean wasDown = bridgeState == 2 || bridgeState == 3;
                bridgeFailures = 0;
                setBridgeState(refused ? 3 : 1);
                if (!refused && wasDown) {
                    // Back online: pick up anything that happened meanwhile.
                    act.canvasAgent.startCanvasEvents();
                    act.conversations.resumeRunningSessions();
                    act.documents.reloadOpenPdfIfChanged();
                }
                scheduleHealth();
            }

            @Override
            public void onError(String message) {
                healthInFlight = false;
                if (act.isDead()) return;
                // One dropped probe is noise (Wi-Fi hand-over); two in a row is real.
                bridgeFailures++;
                boolean unauthorized = message != null && message.toLowerCase(java.util.Locale.US)
                        .contains("unauthorized");
                if (unauthorized) setBridgeState(3);
                else if (bridgeFailures >= 2) setBridgeState(2);
                scheduleHealth();
            }
        });
    }

    private void scheduleHealth() {
        if (!healthWatching) return;
        act.saveHandler.removeCallbacks(healthTick);
        act.saveHandler.postDelayed(healthTick,
                bridgeState == 1 ? HEALTH_INTERVAL_MS : HEALTH_INTERVAL_OFFLINE_MS);
    }

    private void setBridgeState(int state) {
        if (bridgeState == state) return;
        bridgeState = state;
        if (state == 1 || state == 0) {
            if (bridgeStatusChip != null) {
                final TextView chip = bridgeStatusChip;
                chip.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> chip.setVisibility(View.GONE)).start();
            }
            return;
        }
        if (act.rootLayout == null) return;
        if (bridgeStatusChip == null) {
            bridgeStatusChip = new TextView(act);
            bridgeStatusChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            bridgeStatusChip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            bridgeStatusChip.setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6));
            SketchStyle.elevate(bridgeStatusChip, 10);
            bridgeStatusChip.setClickable(true);
            bridgeStatusChip.setOnClickListener(v -> {
                if (bridgeState == 3 || act.pairedHost == null) act.computers.showConnect();
                else checkBridgeHealthNow();
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = act.dp(20);
            act.rootLayout.addView(bridgeStatusChip, lp);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(999));
        bg.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
        bg.setStroke(act.dp(1), state == 3 ? 0xFFF59E0B : 0xFFEF4444);
        bridgeStatusChip.setBackground(act.withHoverRipple(bg, false));
        SketchStyle.elevate(bridgeStatusChip, 10);
        bridgeStatusChip.setTextColor(act.M3_ON_SURFACE);
        String name = act.computers.workspaceName();
        bridgeStatusChip.setText(act.pairedHost == null
                ? "Connect to your computer to see its documents — tap to connect"
                : state == 3
                ? name + " only accepts devices on its own network — tap to connect"
                : name + " is unreachable — looking for it (tap to retry now)");
        // Its address may have changed (new Wi-Fi, DHCP): look for it by id.
        if (state == 2) act.computers.reconnect();
        bridgeStatusChip.setVisibility(View.VISIBLE);
        bridgeStatusChip.setAlpha(0f);
        bridgeStatusChip.bringToFront();
        bridgeStatusChip.setTranslationZ(act.dp(act.zenMode ? MainActivity.ZEN_LIFT_DP + 50 : 50));
        bridgeStatusChip.animate().alpha(1f).setDuration(200).start();
    }

    private static final String PREF_BRIDGE_TOKEN = "bridgeToken";

    String loadBridgeToken() {
        try {
            return act.getSharedPreferences(PREF_SECURE_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_BRIDGE_TOKEN, "");
        } catch (Exception e) {
            return "";
        }
    }

    void saveBridgeToken(String token) {
        try {
            act.getSharedPreferences(PREF_SECURE_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_BRIDGE_TOKEN, token == null ? "" : token.trim()).apply();
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "could not save bridge token", e);
        }
    }

    /** Kept apart from the session blob, which is mirrored to the bridge. */
    private static final String PREF_SECURE_NAME = "cc_secure";

    /** One line describing a /health answer for the settings card. */
    String describeBridgeHealth(JSONObject value) {
        if (value.optBoolean("authRequired", false) && !value.optBoolean("authenticated", true)) {
            return "reachable, but the token is missing or wrong";
        }
        StringBuilder sb = new StringBuilder("connected");
        String v = value.optString("version", "");
        if (!v.isEmpty()) sb.append(" · host v").append(v);
        sb.append(" · ").append(value.optString("model", "agent"));
        if (!value.optBoolean("authRequired", false)) sb.append(" · open (INKSIDE_OPEN=1)");
        sb.append(" · app v").append(CrashReporter.appVersion(act));
        return sb.toString();
    }
}
