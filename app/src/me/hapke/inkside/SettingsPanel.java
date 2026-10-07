package me.hapke.inkside;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The Settings card: switches, agent settings, themes, handwriting and bridge rows.
 * Split out of {@link MainActivity}; reaches shared state through {@code act}.
 */
final class SettingsPanel {
    private final MainActivity act;

    /** Bridge URL typed but not yet applied, so a card repopulate does not lose it. */

    /** Model aliases the bridge accepts; it maps them onto the active provider. */
    private static final String[] MODEL_NAMES = {"haiku", "sonnet", "opus"};
    private static final String[] MODEL_LABELS = {"Haiku", "Sonnet", "Opus"};
    /** Last /agent/settings answer, so a card repopulate does not blank the AI section. */
    private JSONObject agentSettings = null;
    /** Transient line under the AI pickers ("switching…", errors); null → derived. */
    private String agentSettingsNote = null;
    private boolean agentSettingsBusy = false;

    SettingsPanel(MainActivity act) {
        this.act = act;
    }

    void showOptionsMenu() {
        if (act.optionsOverlay != null) {
            dismissOptionsMenu();
            return;
        }
        if (act.rootLayout == null) return;

        FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissOptionsMenu());
        overlay.setBackgroundColor(0x99000000);

        // Shell: a pinned header over a scrolling list of grouped settings.
        LinearLayout shell = new LinearLayout(act);
        shell.setOrientation(LinearLayout.VERTICAL);
        SketchStyle.elevate(shell, 6);
        shell.setClickable(true);
        // Don't dismiss when tapping inside the card.
        shell.setOnClickListener(v -> {});
        shell.setClipToOutline(true);
        optionsShell = shell;

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(act.dp(MainActivity.SPACE_XL + 4), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM));
        shell.addView(header, MainActivity.matchWrap());
        optionsHeader = header;

        final int maxScrollH = Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.84f)
                - act.dp(72);
        android.widget.ScrollView scroller = new android.widget.ScrollView(act) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                // Cap tall content so the dialog stays mid-screen on short displays.
                super.onMeasure(widthSpec,
                        MeasureSpec.makeMeasureSpec(maxScrollH, MeasureSpec.AT_MOST));
            }
        };
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setVerticalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength(act.dp(24));
        scroller.setClipToPadding(false);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_XL));
        scroller.addView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroller, MainActivity.matchWrap());

        populateOptionsCard(card);
        act.optionsCard = card;
        if (act.computers.hasHost() && act.aiEnabled) refreshAgentSettings(null);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                act.cardWidth(440), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = act.dp(MainActivity.SPACE_XL);
        cardLp.rightMargin = act.dp(MainActivity.SPACE_XL);
        overlay.addView(shell, cardLp);

        act.liftPanel(overlay);
        act.rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        act.optionsOverlay = overlay;
        // Groups settle in one after another behind the landing card.
        Motion.stagger(card, 70L, 14f);
    }

    /**
     * Fills the options card. Picking an option re-runs this on the same card rather
     * than tearing down and reopening the whole menu, so the panel does not flash and
     * the scroll position survives.
     */
    /** Card surface uses theme fields that change under it, so re-apply on every fill. */
    void applyOptionsCardSurface(LinearLayout card) {
        GradientDrawable bg = new GradientDrawable();
        // Surface container — not Highest — so filled fields / chips read as raised.
        bg.setColor(act.M3_SURFACE_CONTAINER);
        bg.setCornerRadius(act.dp(28));
        bg.setStroke(act.dp(1), (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x33000000);
        card.setBackground(bg);
    }

    void populateOptionsCard(LinearLayout card) {
        card.removeAllViews();
        refreshOptionsShell();

        LinearLayout themeBlock = new LinearLayout(act);
        themeBlock.setOrientation(LinearLayout.VERTICAL);
        themeBlock.addView(settingsItem(R.drawable.ic_palette, "Theme",
                "Colours for the whole app and the canvas", null, null), MainActivity.matchWrap());
        themeBlock.addView(themeSwatchRow(card), MainActivity.matchWrap());
        card.addView(settingsGroup("Appearance", themeBlock));

        card.addView(settingsGroup("Gestures",
                settingsSwitchItem(R.drawable.ic_undo, "Three-finger undo",
                        "Drag three fingers left or right to step back and forward through your changes",
                        act.canvas == null || act.canvas.isThreeFingerUndo(), on -> {
                            if (act.canvas != null) act.canvas.setThreeFingerUndo(on);
                            act.persistence.scheduleSave();
                        }),
                settingsSwitchItem(R.drawable.ic_chat, "Two-finger swipe: chat",
                        "Swipe two fingers sideways anywhere to pull the chat out or push it away",
                        act.twoFingerChatSwipe, on -> {
                            act.twoFingerChatSwipe = on;
                            act.persistence.scheduleSave();
                        }),
                settingsSwitchItem(R.drawable.ic_description, "Three-finger document switcher",
                        "Slide three fingers up or down to pick a recent document; lift to open it",
                        act.canvas == null || act.canvas.isThreeFingerDocs(), on -> {
                            if (act.canvas != null) act.canvas.setThreeFingerDocs(on);
                            act.persistence.scheduleSave();
                        }),
                settingsSwitchItem(R.drawable.ic_star, "Three-finger tap: quick favorites",
                        "Tap with three fingers to open your quick favorites there; tap one to use it",
                        act.canvas == null || act.canvas.isThreeFingerFavorites(), on -> {
                            if (act.canvas != null) act.canvas.setThreeFingerFavorites(on);
                            act.persistence.scheduleSave();
                        })));

        card.addView(settingsGroup("Stylus",
                settingsSwitchItem(R.drawable.ic_back_hand, "Palm rejection",
                        "Ignore your hand resting on the screen while the pen is near",
                        act.canvas == null || act.canvas.isPalmRejection(), on -> {
                            if (act.canvas != null) act.canvas.setPalmRejection(on);
                            act.persistence.scheduleSave();
                        }),
                settingsSwitchItem(R.drawable.ic_shapes, "Hold to snap shapes",
                        null,
                        act.canvas == null || act.canvas.isShapeSnapEnabled(), on -> {
                            if (act.canvas != null) act.canvas.setShapeSnapEnabled(on);
                            act.persistence.scheduleSave();
                        }),
                settingsSwitchItem(R.drawable.ic_ink, "Pen outline",
                        "A thin outline, in the opposite colour of the page, around ink and text that is close to the page colour",
                        act.canvas != null && act.canvas.isPenOutline(), on -> {
                            if (act.canvas != null) act.canvas.setPenOutline(on);
                            act.persistence.scheduleSave();
                        }),
                act.penTools.penButtonMapRow(card, true),
                act.penTools.penButtonMapRow(card, false)));

        List<View> notes = new ArrayList<>();
        notes.add(settingsSwitchItem(R.drawable.ic_history, "Keep undo history",
                "Undo still works after you close and reopen a document",
                act.keepUndoHistory, on -> {
                    act.keepUndoHistory = on;
                    // Off: nothing more is written; history already saved stays until
                    // its document is left again.
                    act.persistence.scheduleSave();
                }));
        View handwritingItem = handwritingSettingsItem();
        if (handwritingItem != null) notes.add(handwritingItem);
        card.addView(settingsGroup("Notes", notes.toArray(new View[0])));

        List<View> ai = new ArrayList<>();
        ai.add(settingsSwitchItem(R.drawable.ic_smart_toy, "AI features",
                act.aiEnabled
                        ? "Chat, instant chat and dictation"
                        : "Off: no chat or dictation — a plain notebook",
                act.aiEnabled, act::setAiEnabled));
        if (act.aiEnabled) {
            ai.add(settingsSwitchItem(act.agentSeesPages ? R.drawable.ic_visibility : R.drawable.ic_visibility_off,
                    "Agent can see my pages",
                    act.agentSeesPages
                            ? "When you ask, the agent may look at your pages and handwriting"
                            : "Off: the agent never sees your pages",
                    act.agentSeesPages, on -> {
                        act.setAgentSeesPages(on);
                        repopulateOptionsCard();
                    }));
            // On in every chat at once; what the tutor knows about you is kept per
            // project, and a chat only sees its own project's.
            ai.add(settingsSwitchItem(R.drawable.ic_bolt, "Learning Mode",
                    act.learningMode
                            ? "Adaptive tutor in every chat: hints before solutions, then it checks "
                                    + "you understood" + (learningProgressLine != null ? "\n" + learningProgressLine : "")
                            : "Off: the tutor answers directly",
                    act.learningMode, on -> {
                        act.setLearningMode(on);
                        repopulateOptionsCard();
                    }));
            if (act.learningMode && act.computers.hasHost()) {
                ai.add(settingsItem(R.drawable.ic_lightbulb, "Learning progress",
                        "Course progress, topics and goals in this project", null,
                        v -> new LearningDialog(act).show()));
                ai.add(settingsItem(R.drawable.ic_restart, "Reset learning progress",
                        "Forget what the tutor has recorded in this project", null,
                        v -> confirmLearningReset()));
                refreshLearningProgress();
            }
        }
        card.addView(settingsGroup("AI", ai.toArray(new View[0])));
        if (act.computers.hasHost() && act.aiEnabled) {
            card.addView(settingsGroup("AI assistant", agentSettingsRows()));
            card.addView(agentSettingsStatus());
        }

        card.addView(settingsGroup("Workspace", act.computers.settingsRows()));
    }

    // ---- Learning Mode progress ------------------------------------------------------

    /** "12 concepts · 3 mastered · 5 goals", from the host; null until it answers. */
    private String learningProgressLine;
    private boolean learningProgressLoading;

    private void refreshLearningProgress() {
        if (learningProgressLoading || act.bridge == null) return;
        learningProgressLoading = true;
        act.bridge.learningState(act.activeProjectPath, new BridgeClient.Callback<org.json.JSONObject>() {
            @Override
            public void onSuccess(org.json.JSONObject o) {
                learningProgressLoading = false;
                if (act.isDead()) return;
                String line = describeLearning(o);
                if (!line.equals(learningProgressLine)) {
                    learningProgressLine = line;
                    repopulateOptionsCard();
                }
            }

            @Override
            public void onError(String message) {
                learningProgressLoading = false;
            }
        });
    }

    private static String describeLearning(org.json.JSONObject o) {
        int concepts = o.optInt("concepts", 0);
        if (concepts == 0 && o.optInt("goals", 0) == 0) return "Nothing recorded yet in this project";
        org.json.JSONObject c = o.optJSONObject("counts");
        int mastered = c != null ? c.optInt("mastered", 0) : 0;
        int applied = c != null ? c.optInt("applied", 0) + c.optInt("transferred", 0) : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(concepts).append(concepts == 1 ? " concept" : " concepts");
        sb.append(" · ").append(mastered).append(" mastered");
        if (applied > 0) sb.append(" · ").append(applied).append(" applied");
        int goals = o.optInt("goals", 0);
        if (goals > 0) sb.append(" · ").append(goals).append(goals == 1 ? " goal" : " goals");
        return sb.toString();
    }

    private void confirmLearningReset() {
        new android.app.AlertDialog.Builder(act)
                .setMessage("Forget everything the tutor has recorded about what you know in this "
                        + "project, and its learning goals? Other projects and your chats stay as they are.")
                .setPositiveButton("Reset", (d, w) -> act.bridge.resetLearning(act.activeProjectPath,
                        new BridgeClient.Callback<org.json.JSONObject>() {
                            @Override
                            public void onSuccess(org.json.JSONObject o) {
                                if (act.isDead()) return;
                                learningProgressLine = describeLearning(o);
                                repopulateOptionsCard();
                            }

                            @Override
                            public void onError(String message) {
                                if (!act.isDead()) act.statusToast("Could not reset: " + message);
                            }
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout optionsShell;
    private LinearLayout optionsHeader;

    /** Shell surface and header follow the theme, which can change while it is open. */
    private void refreshOptionsShell() {
        if (optionsShell != null) {
            applyOptionsCardSurface(optionsShell);
            SketchStyle.elevate(optionsShell, 6);
        }
        LinearLayout header = optionsHeader;
        if (header == null) return;
        header.removeAllViews();
        TextView title = new TextView(act);
        title.setText("Settings");
        title.setTextColor(act.M3_ON_SURFACE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        HeadlineFont.apply(title);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = new ImageView(act);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setContentDescription("Close settings");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00000000);
        close.setBackground(act.withHoverRipple(bg, true));
        close.setClickable(true);
        close.setOnClickListener(v -> dismissOptionsMenu());
        header.addView(close, new LinearLayout.LayoutParams(act.dp(48), act.dp(48)));
    }

    /**
     * A titled group of settings: Material 3 list items stacked with 2dp gaps on a
     * shared container, the outer corners large and the inner ones small, so the
     * group reads as one block while each row stays its own target.
     */
    LinearLayout settingsGroup(String title, View... items) {
        LinearLayout group = new LinearLayout(act);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, act.dp(MainActivity.SPACE_LG), 0, 0);

        TextView label = new TextView(act);
        label.setText(title);
        label.setTextColor(act.M3_PRIMARY);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(act.dp(MainActivity.SPACE_LG), 0, 0, act.dp(MainActivity.SPACE_SM));
        group.addView(label);

        List<View> shown = new ArrayList<>();
        for (View item : items) if (item != null) shown.add(item);
        for (int i = 0; i < shown.size(); i++) {
            View item = shown.get(i);
            float outer = act.dp(20);
            float inner = act.dp(4);
            float top = i == 0 ? outer : inner;
            float bottom = i == shown.size() - 1 ? outer : inner;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadii(new float[] {top, top, top, top, bottom, bottom, bottom, bottom});
            bg.setColor(act.M3_SURFACE_CONTAINER_HIGH | 0xFF000000);
            if (item.isClickable()) {
                GradientDrawable mask = new GradientDrawable();
                mask.setCornerRadii(bg.getCornerRadii());
                mask.setColor(0xFFFFFFFF);
                item.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(act.hoverTint()), bg, mask));
                // Whole rows are big targets: a ripple, not the small-control squeeze.
                item.setTag(R.id.motion_no_press, Boolean.TRUE);
            } else {
                item.setBackground(bg);
            }
            // Keeps ripples of rows nested inside an item within its rounded corners.
            item.setClipToOutline(true);
            LinearLayout.LayoutParams lp = MainActivity.matchWrap();
            if (i > 0) lp.topMargin = act.dp(2);
            group.addView(item, lp);
        }
        return group;
    }

    /**
     * Material 3 list item: leading icon, headline, supporting text, optional trailing
     * control. {@code onClick} null leaves the row inert (its trailing control acts).
     */
    LinearLayout settingsItem(int iconRes, String headline, String supporting,
                                      View trailing, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(act.dp(supporting != null ? 72 : 56));
        row.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_MD));

        ImageView icon = new ImageView(act);
        icon.setImageResource(iconRes);
        icon.setColorFilter(act.M3_ON_SURFACE_VARIANT, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(act.dp(24), act.dp(24));
        ilp.rightMargin = act.dp(MainActivity.SPACE_LG);
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(act);
        head.setText(headline);
        head.setTextColor(act.M3_ON_SURFACE);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(head);
        if (supporting != null) {
            TextView sub = new TextView(act);
            sub.setText(supporting);
            sub.setTextColor(act.M3_ON_SURFACE_VARIANT);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sub.setLineSpacing(0f, 1.1f);
            sub.setPadding(0, act.dp(2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(trailing, tlp);
        }
        if (onClick != null) {
            row.setClickable(true);
            row.setOnClickListener(onClick);
            // Replaced by the group's rounded ripple when the row is a group item itself.
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(act.hoverTint()), null,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            row.setTag(R.id.motion_no_press, Boolean.TRUE);
        }
        return row;
    }

    /** List item with a trailing switch; tapping anywhere on the row flips it. */
    LinearLayout settingsSwitchItem(int iconRes, String headline, String supporting,
                                            boolean checked,
                                            java.util.function.Consumer<Boolean> onChange) {
        Material3Switch sw = new Material3Switch(act);
        // onPrimary ≈ primaryContainer for our themes (dark handle on light primary).
        sw.applyColors(
                act.M3_PRIMARY,
                act.M3_PRIMARY_CONTAINER,
                act.M3_SURFACE_CONTAINER_HIGHEST,
                act.M3_OUTLINE_VARIANT,
                act.M3_ON_SURFACE);
        sw.setChecked(checked);
        sw.setContentDescription(headline);
        sw.setOnCheckedChangeListener((v, isChecked) -> onChange.accept(isChecked));
        sw.setMinimumWidth(act.dp(60));
        sw.setMinimumHeight(act.dp(40));
        return settingsItem(iconRes, headline, supporting, sw, v -> sw.toggle());
    }

    /** An item with a control underneath it (segmented picker, fields…), indented to the text. */
    LinearLayout settingsBlock(int iconRes, String headline, String supporting,
                                       View below) {
        LinearLayout block = new LinearLayout(act);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = settingsItem(iconRes, headline, supporting, null, null);
        top.setMinimumHeight(act.dp(supporting != null ? 64 : 48));
        top.setPadding(top.getPaddingLeft(), top.getPaddingTop(), top.getPaddingRight(), act.dp(MainActivity.SPACE_SM));
        block.addView(top, MainActivity.matchWrap());
        LinearLayout.LayoutParams lp = MainActivity.matchWrap();
        lp.leftMargin = act.dp(MainActivity.SPACE_LG + 24 + MainActivity.SPACE_LG);
        lp.rightMargin = act.dp(MainActivity.SPACE_LG);
        lp.bottomMargin = act.dp(MainActivity.SPACE_LG);
        block.addView(below, lp);
        return block;
    }

    /**
     * Provider + model pickers for chat runs. The provider switch
     * rewrites the Mac's Claude Code settings (same as `claude-provider`); the
     * model is the bridge's own and leaves the Mac's CLI alone.
     */
    private View agentSettingsRows() {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);

        String provider = agentSettings != null ? agentSettings.optString("provider", "") : "";
        String model = agentSettings != null ? agentSettings.optString("chatModel", "") : "";

        // A computer run by someone else fixes the model and provider.
        if (agentSettings != null && agentSettings.optBoolean("fixedModel", false)) return wrap;

        wrap.addView(settingsBlock(R.drawable.ic_cloud, "Provider",
                "Who runs chats on " + act.computers.workspaceName(),
                optionsSegmentRow(MainActivity.PROVIDER_LABELS, indexOf(MainActivity.PROVIDER_NAMES, provider), i -> {
                    if (agentSettingsBusy || MainActivity.PROVIDER_NAMES[i].equals(provider)) return;
                    agentSettingsBusy = true;
                    agentSettingsNote = "switching to " + MainActivity.PROVIDER_LABELS[i] + "…";
                    repopulateOptionsCard();
                    act.bridge.setAgentProvider(MainActivity.PROVIDER_NAMES[i], agentSettingsCallback());
                })), MainActivity.matchWrap());

        wrap.addView(settingsBlock(R.drawable.ic_model, "Model", null,
                optionsSegmentRow(MODEL_LABELS, indexOf(MODEL_NAMES, model), i -> {
                    if (agentSettingsBusy || MODEL_NAMES[i].equals(model)) return;
                    agentSettingsBusy = true;
                    agentSettingsNote = "switching to " + MODEL_LABELS[i] + "…";
                    repopulateOptionsCard();
                    act.bridge.setAgentModel(MODEL_NAMES[i], agentSettingsCallback());
                })), MainActivity.matchWrap());

        return wrap;
    }

    /** One line under the AI group: what is happening, or how the choice applies. */
    private View agentSettingsStatus() {
        String provider = agentSettings != null ? agentSettings.optString("provider", "") : "";
        TextView status = new TextView(act);
        status.setTextColor(agentSettingsBusy ? act.M3_PRIMARY : act.M3_ON_SURFACE_VARIANT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_LG), 0);
        if (agentSettingsNote != null) {
            status.setText(agentSettingsNote);
        } else if (agentSettings == null) {
            status.setText("Loading from " + act.computers.workspaceName() + "…");
        } else {
            status.setText(agentSettings.optBoolean("fixedModel", false)
                    ? "The model is set by whoever runs this computer"
                    : "deepseek".equals(provider)
                    ? "DeepSeek maps Haiku to Flash and Sonnet/Opus to Pro"
                    : "Changes apply from the next message");
        }
        if (agentSettingsBusy) pulse(status);
        return status;
    }

    /** Gentle breathing for "waiting on something" labels; stops when the view goes away. */
    android.animation.Animator pulse(View v) {
        android.animation.ObjectAnimator a =
                android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.45f);
        a.setDuration(700);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(Motion.STANDARD);
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (!a.isStarted()) a.start();
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                a.cancel();
            }
        });
        if (v.isAttachedToWindow()) a.start();
        return a;
    }


    static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    /** Pull provider + model from the bridge; repaints the open settings card. */
    private void refreshAgentSettings(String note) {
        if (act.bridge == null) return;
        act.bridge.getAgentSettings(new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if (act.isDead()) return;
                agentSettings = value;
                agentSettingsNote = note;
                repopulateOptionsCard();
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                agentSettingsNote = act.computers.workspaceName() + ": " + (message != null ? message : "unreachable");
                repopulateOptionsCard();
            }
        });
    }

    /** Shared result handler for a provider or model switch. */
    private BridgeClient.Callback<JSONObject> agentSettingsCallback() {
        return agentSettingsCallback(null);
    }

    /** @param successNote line to show under the pickers once the switch lands, or null. */
    private BridgeClient.Callback<JSONObject> agentSettingsCallback(String successNote) {
        return new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if (act.isDead()) return;
                agentSettingsBusy = false;
                // The provider endpoint omits chatModel, so re-read the full picture.
                refreshAgentSettings(successNote);
            }

            @Override
            public void onError(String message) {
                if (act.isDead()) return;
                agentSettingsBusy = false;
                segmentPending.clear();
                refreshAgentSettings("Could not switch: " + message);
            }
        };
    }

    void repopulateOptionsCard() {
        if (act.optionsCard != null) populateOptionsCard(act.optionsCard);
    }

    /** Last selected index per segmented row (keyed by its labels), so a rebuilt row slides. */
    private final java.util.HashMap<String, Integer> segmentLastSelected = new java.util.HashMap<>();
    /** Tapped but not yet confirmed by the bridge: shown selected meanwhile. */
    private final java.util.HashMap<String, Integer> segmentPending = new java.util.HashMap<>();

    /**
     * Material 3 segmented picker for N options; `selected` −1 highlights none. The
     * selection is a thumb that slides to the tapped option — landing like the undo
     * scrubber's thumb — even though picking rebuilds the settings card.
     */
    View optionsSegmentRow(String[] labels, int confirmed, MainActivity.IntConsumer onPick) {
        String key = String.join("\u0000", labels);
        Integer pending = segmentPending.get(key);
        if (!agentSettingsBusy || (pending != null && pending == confirmed)) {
            segmentPending.remove(key);
            pending = null;
        }
        final int selected = pending != null ? pending : confirmed;
        Integer prev = segmentLastSelected.put(key, selected);
        final int from = prev != null ? prev : selected;

        FrameLayout frame = new FrameLayout(act);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(act.dp(999));
        track.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
        frame.setBackground(track);
        frame.setPadding(act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS));

        View thumb = new View(act);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(act.dp(999));
        tb.setColor(act.M3_PRIMARY_CONTAINER);
        thumb.setBackground(tb);
        thumb.setVisibility(selected >= 0 ? View.VISIBLE : View.INVISIBLE);
        // Sized from the chips on layout: MATCH_PARENT would take all the height a
        // non-scrolling parent offers.
        frame.addView(thumb, new FrameLayout.LayoutParams(0, 0));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;
            TextView chip = new TextView(act);
            chip.setText(labels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(Typeface.create(
                    on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            chip.setTextColor(on ? act.M3_ON_PRIMARY_CONTAINER : act.M3_ON_SURFACE_VARIANT);
            chip.setPadding(act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_MD));
            if (on) {
                android.graphics.drawable.Drawable check = act.getDrawable(R.drawable.ic_check).mutate();
                check.setTint(act.M3_ON_PRIMARY_CONTAINER);
                check.setBounds(0, 0, act.dp(16), act.dp(16));
                chip.setCompoundDrawablesRelative(check, null, null, null);
                chip.setCompoundDrawablePadding(act.dp(MainActivity.SPACE_XS));
            }
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(act.dp(999));
            mask.setColor(0x00000000);
            chip.setBackground(act.withHoverRipple(mask, false));
            chip.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK);
                // Slide now; the bridge confirms (or not) afterwards.
                if (!agentSettingsBusy && index != confirmed) segmentPending.put(key, index);
                onPick.accept(index);
            });
            row.addView(chip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        frame.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            private boolean placed;

            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (selected < 0 || selected >= row.getChildCount()) return;
                View target = row.getChildAt(selected);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
                if (lp.width != target.getWidth() || lp.height != target.getHeight()) {
                    lp.width = target.getWidth();
                    lp.height = target.getHeight();
                    thumb.setLayoutParams(lp);
                }
                if (placed) {
                    thumb.setTranslationX(target.getLeft());
                    return;
                }
                placed = true;
                View start = from >= 0 && from < row.getChildCount() ? row.getChildAt(from) : target;
                thumb.setTranslationX(start.getLeft());
                if (start != target) {
                    thumb.animate().translationX(target.getLeft())
                            .setDuration(Motion.ENTER_MS + 40).setInterpolator(Motion.LAND).start();
                    Motion.pop(target);
                } else if (prev == null) {
                    thumb.setTranslationX(target.getLeft());
                }
            }
        });
        return frame;
    }

    private String themeJustPicked;

    /**
     * Theme picker: a 3-column grid of tiles, each a tiny picture of the app in that
     * theme (its surface, a toolbar, text lines and the accent), named underneath. The
     * chosen tile wears a ring and a check badge that lands as it is picked.
     */
    private View themeSwatchRow(LinearLayout card) {
        final int columns = 3;
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG));
        LinearLayout row = null;
        ThemeConfig.AppTheme[] themes = ThemeConfig.APP_THEMES;
        for (int i = 0; i < themes.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = MainActivity.matchWrap();
                if (i > 0) rlp.topMargin = act.dp(MainActivity.SPACE_MD);
                grid.addView(row, rlp);
            }
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % columns > 0) tlp.leftMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(themeTile(themes[i]), tlp);
        }
        // Keep the last row's tiles the same width as the others.
        int rest = themes.length % columns;
        for (int i = 0; rest > 0 && i < columns - rest; i++) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 0, 1f);
            sp.leftMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(new View(act), sp);
        }
        return grid;
    }

    private View themeTile(ThemeConfig.AppTheme theme) {
        final String id = theme.id;
        boolean on = id.equals(act.appThemeId);

        LinearLayout tile = new LinearLayout(act);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_SM));

        // Mini app: surface, a toolbar pill, two lines of text, the accent as a FAB.
        FrameLayout preview = new FrameLayout(act);
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(act.dp(14));
        pb.setColor(theme.surface | 0xFF000000);
        pb.setStroke(act.dp(on ? 3 : 1), on ? act.M3_PRIMARY : (act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x88000000);
        preview.setBackground(pb);
        preview.addView(themeBar(theme.surfaceContainerHighest | 0xFF000000, act.dp(10)),
                themeBarLp(act.dp(10), act.dp(10), act.dp(44), act.dp(10)));
        preview.addView(themeBar((theme.onSurfaceVariant & 0x00FFFFFF) | 0x80000000, act.dp(3)),
                themeBarLp(act.dp(10), act.dp(30), act.dp(52), act.dp(5)));
        preview.addView(themeBar((theme.onSurfaceVariant & 0x00FFFFFF) | 0x55000000, act.dp(3)),
                themeBarLp(act.dp(10), act.dp(40), act.dp(34), act.dp(5)));
        View fab = new View(act);
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(act.dp(7));
        fb.setColor(theme.primaryContainer | 0xFF000000);
        fab.setBackground(fb);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(act.dp(20), act.dp(20));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = act.dp(9);
        flp.bottomMargin = act.dp(9);
        preview.addView(fab, flp);
        View accent = new View(act);
        GradientDrawable ab = new GradientDrawable();
        ab.setShape(GradientDrawable.OVAL);
        ab.setColor(theme.primary | 0xFF000000);
        accent.setBackground(ab);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(act.dp(8), act.dp(8));
        alp.gravity = Gravity.BOTTOM | Gravity.END;
        alp.rightMargin = act.dp(15);
        alp.bottomMargin = act.dp(15);
        preview.addView(accent, alp);

        if (on) {
            ImageView badge = new ImageView(act);
            badge.setImageResource(R.drawable.ic_check);
            badge.setColorFilter(act.M3_ON_PRIMARY_CONTAINER, PorterDuff.Mode.SRC_IN);
            badge.setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3));
            GradientDrawable bb = new GradientDrawable();
            bb.setShape(GradientDrawable.OVAL);
            bb.setColor(act.M3_PRIMARY_CONTAINER);
            bb.setStroke(act.dp(2), act.M3_PRIMARY);
            badge.setBackground(bb);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(act.dp(22), act.dp(22));
            blp.gravity = Gravity.TOP | Gravity.END;
            blp.topMargin = act.dp(7);
            blp.rightMargin = act.dp(7);
            preview.addView(badge, blp);
            if (id.equals(themeJustPicked)) {
                themeJustPicked = null;
                Motion.pop(preview);
                badge.setScaleX(0f);
                badge.setScaleY(0f);
                badge.animate().scaleX(1f).scaleY(1f).setStartDelay(60)
                        .setDuration(Motion.ENTER_MS).setInterpolator(Motion.LAND).start();
            }
        }
        tile.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, act.dp(64)));

        TextView label = new TextView(act);
        label.setText(theme.label);
        label.setTextColor(on ? act.M3_ON_SURFACE : act.M3_ON_SURFACE_VARIANT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_SM), act.dp(MainActivity.SPACE_XS), 0);
        tile.addView(label, MainActivity.matchWrap());

        tile.setClickable(true);
        tile.setContentDescription(theme.label + " theme");
        GradientDrawable rb = new GradientDrawable();
        rb.setCornerRadius(act.dp(16));
        rb.setColor(0x00000000);
        tile.setBackground(act.withHoverRipple(rb, false));
        tile.setOnClickListener(v -> {
            if (id.equals(act.appThemeId)) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
            themeJustPicked = id;
            // Repopulates the settings card with the new colours.
            act.applyAppTheme(ThemeConfig.appThemeById(id));
            act.persistence.scheduleSave();
        });
        return tile;
    }

    private View themeBar(int color, int radius) {
        View v = new View(act);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(color);
        v.setBackground(d);
        return v;
    }

    private static FrameLayout.LayoutParams themeBarLp(int left, int top, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.topMargin = top;
        return lp;
    }




    /**
     * Handwriting search: a switch item whose language choice and live status fold
     * out beneath it while it is on.
     */
    private View handwritingSettingsItem() {
        if (act.handwriting == null) return null;
        LinearLayout block = new LinearLayout(act);
        block.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout details = new LinearLayout(act);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(act.dp(MainActivity.SPACE_LG + 24 + MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_LG), act.dp(MainActivity.SPACE_LG));

        LinearLayout langRow = new LinearLayout(act);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        final List<TextView> chips = new ArrayList<>();
        final Runnable styleChips = () -> {
            for (int i = 0; i < chips.size(); i++) {
                boolean on = HandwritingIndex.LANGUAGES[i][0].equals(act.handwriting.getLanguage());
                TextView chip = chips.get(i);
                styleFilterChip(chip, on);
            }
        };
        for (String[] lang : HandwritingIndex.LANGUAGES) {
            final String tag = lang[0];
            final TextView[] self = new TextView[1];
            TextView chip = act.panelAction(lang[1], false, () -> {
                if (tag.equals(act.handwriting.getLanguage())) return;
                act.handwriting.setLanguage(tag);
                styleChips.run();
                Motion.pop(self[0]);
                act.documents.scheduleInkIndex();
                act.persistence.scheduleSave();
            });
            self[0] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(32));
            clp.rightMargin = act.dp(MainActivity.SPACE_SM);
            chip.setLayoutParams(clp);
            chips.add(chip);
            langRow.addView(chip);
        }
        styleChips.run();
        details.addView(langRow);

        final TextView status = act.panelHint("");
        status.setPadding(0, act.dp(MainActivity.SPACE_MD), 0, 0);
        details.addView(status);
        TextView note = act.panelHint("Recognition runs on this tablet. Only the language model "
                + "is downloaded, once. Documents are read as you open and write in them.");
        note.setPadding(0, act.dp(MainActivity.SPACE_XS), 0, 0);
        details.addView(note);

        details.setVisibility(act.handwriting.isEnabled() ? View.VISIBLE : View.GONE);
        status.setText(act.handwriting.getStatus());
        act.handwriting.setStatusListener(st -> status.setText(act.handwriting.getStatus()));

        LinearLayout item = settingsSwitchItem(R.drawable.ic_draw, "Handwriting search",
                "Find words you wrote by hand in search", act.handwriting.isEnabled(), on -> {
                    act.handwriting.setEnabled(on);
                    status.setText(act.handwriting.getStatus());
                    if (on) Motion.expand(details);
                    else Motion.collapse(details);
                    act.persistence.scheduleSave();
                });
        block.addView(item, MainActivity.matchWrap());
        block.addView(details, MainActivity.matchWrap());
        return block;
    }

    /** Material 3 filter chip: 8dp corners, a leading check when selected. */
    private void styleFilterChip(TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(8));
        android.graphics.drawable.Drawable check = null;
        if (on) {
            bg.setColor(act.M3_PRIMARY_CONTAINER);
            t.setTextColor(act.M3_ON_PRIMARY_CONTAINER);
            check = act.getDrawable(R.drawable.ic_check).mutate();
            check.setTint(act.M3_ON_PRIMARY_CONTAINER);
            check.setBounds(0, 0, act.dp(18), act.dp(18));
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(act.dp(1), act.M3_OUTLINE_VARIANT);
            t.setTextColor(act.M3_ON_SURFACE_VARIANT);
        }
        t.setBackground(act.withHoverRipple(bg, false));
        t.setCompoundDrawablesRelative(check, null, null, null);
        t.setCompoundDrawablePadding(act.dp(MainActivity.SPACE_SM));
        t.setPadding(act.dp(on ? MainActivity.SPACE_SM : MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_LG), 0);
    }

    void dismissOptionsMenu() {
        dismissOptionsMenu(true);
    }

    void dismissOptionsMenu(boolean clearCapture) {
        if (clearCapture) act.penMapListening = 0;
        act.optionsCard = null;
        optionsShell = null;
        optionsHeader = null;
        if (act.optionsOverlay == null) return;
        ViewGroup parent = (ViewGroup) act.optionsOverlay.getParent();
        if (parent != null) parent.removeView(act.optionsOverlay);
        act.optionsOverlay = null;
    }

    TextView optionsSectionLabel(String text) {
        TextView v = new TextView(act);
        v.setText(text);
        v.setTextColor(act.M3_PRIMARY);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, act.dp(MainActivity.SPACE_LG), 0, act.dp(MainActivity.SPACE_SM));
        return v;
    }

    private LinearLayout optionsBinaryRow(
            String leftLabel, String rightLabel, boolean leftOn, MainActivity.BoolConsumer onPick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(act.dp(999));
        track.setColor(act.M3_SURFACE_CONTAINER_HIGHEST);
        row.setBackground(track);
        row.setPadding(act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS), act.dp(MainActivity.SPACE_XS));

        row.addView(m3SegmentChip(leftLabel, leftOn, () -> onPick.accept(true)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(m3SegmentChip(rightLabel, !leftOn, () -> onPick.accept(false)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView m3SegmentChip(String label, boolean on, Runnable onTap) {
        TextView chip = new TextView(act);
        chip.setText(label);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.create(
                on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        chip.setTextColor(on ? act.M3_ON_PRIMARY_CONTAINER : act.M3_ON_SURFACE_VARIANT);
        chip.setPadding(act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD + 2), act.dp(MainActivity.SPACE_MD), act.dp(MainActivity.SPACE_MD + 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(999));
        bg.setColor(on ? act.M3_PRIMARY_CONTAINER : 0x00000000);
        chip.setBackground(bg);
        chip.setOnClickListener(v -> onTap.run());
        return chip;
    }
}
