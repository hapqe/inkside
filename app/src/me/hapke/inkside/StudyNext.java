package me.hapke.inkside;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The ⋮ → Learning actions that talk to the host's study planner: what to study next
 * (across every project, paced by the weekly goals and spaced reviews) and the weekly
 * study-time goal of the current project.
 */
final class StudyNext {
    private final MainActivity act;

    StudyNext(MainActivity act) {
        this.act = act;
    }

    private boolean hostReady() {
        if (act.computers.hasHost() && act.bridge != null) return true;
        act.snackbar("The study plan lives on your computer. Connect one first.", false);
        return false;
    }

    /** Asks the planner and says straight away what to do; Start hands it to the tutor. */
    void showWhatNext() {
        if (!hostReady()) return;
        java.util.Map<String, Long> minutes = act.studyLog != null
                ? act.studyLog.minutesByDocument(StudyWeekDialog.weekStart(0), System.currentTimeMillis())
                : new java.util.HashMap<>();
        act.bridge.learningNext(minutes, StudyLog.daysLeftInWeek(), new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject o) {
                if (act.isDead()) return;
                JSONObject next = o.optJSONObject("next");
                if (next == null) {
                    showNothingDue(o.optJSONObject("nextReview"));
                    return;
                }
                showPick(next, o.optJSONArray("alternatives"));
            }

            @Override
            public void onError(String err) {
                act.snackbar("Could not ask the study plan: " + err, false);
            }
        });
    }

    private void showPick(JSONObject pick, JSONArray alternatives) {
        JSONObject item = pick.optJSONObject("item");
        if (item == null) return;
        M3Dialog.Builder b = new M3Dialog.Builder(act)
                .setTitle("Up next")
                .setView(pickCard(pick, item))
                .setPositiveButton("Start", (d, w) -> start(pick.optString("project", ""), item))
                .setNegativeButton("Later", null);
        if (alternatives != null && alternatives.length() > 0) {
            b.setNeutralButton("Something else", (d, w) -> showAlternatives(alternatives));
        }
        b.show();
    }

    /** The pick, large: what kind of step, what to do, why, and how long and where. */
    private View pickCard(JSONObject pick, JSONObject item) {
        LinearLayout c = card(act.M3_PRIMARY_CONTAINER, 24);
        int pad = act.dp(20);
        c.setPadding(pad, pad, pad, pad);
        int on = act.M3_ON_PRIMARY_CONTAINER;

        c.addView(kindChip(item.optString("kind"), on));
        TextView title = text(22, on, false);
        HeadlineFont.apply(title);
        title.setText(subject(item));
        LinearLayout.LayoutParams tlp = MainActivity.matchWrap();
        tlp.topMargin = act.dp(14);
        c.addView(title, tlp);
        TextView ask = text(15, on, false);
        ask.setText(item.optString("ask"));
        ask.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams alp = MainActivity.matchWrap();
        alp.topMargin = act.dp(6);
        c.addView(ask, alp);

        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(metaChip(R.drawable.ic_timer, duration(item.optInt("minutes")), on));
        chips.addView(metaChip(R.drawable.ic_folder, projectLabel(pick), on));
        LinearLayout.LayoutParams clp = MainActivity.matchWrap();
        clp.topMargin = act.dp(16);
        c.addView(chips, clp);
        String doc = docOf(item);
        if (doc != null) {
            View d = metaChip(R.drawable.ic_description, baseName(doc), on);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = act.dp(8);
            c.addView(d, dlp);
        }

        LinearLayout outer = new LinearLayout(act);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(c, MainActivity.matchWrap());
        LinearLayout why = new LinearLayout(act);
        why.setOrientation(LinearLayout.HORIZONTAL);
        why.setGravity(Gravity.CENTER_VERTICAL);
        why.addView(icon(R.drawable.ic_lightbulb, act.M3_ON_SURFACE_VARIANT), new LinearLayout.LayoutParams(act.dp(18), act.dp(18)));
        TextView reason = text(13, act.M3_ON_SURFACE_VARIANT, false);
        reason.setText(item.optString("reason"));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = act.dp(10);
        why.addView(reason, rlp);
        LinearLayout.LayoutParams wlp = MainActivity.matchWrap();
        wlp.topMargin = act.dp(14);
        outer.addView(why, wlp);

        int goal = pick.optInt("weeklyGoalMinutes");
        if (goal > 0) {
            long studied = pick.optLong("studiedMinutes");
            TextView week = text(13, act.M3_ON_SURFACE_VARIANT, false);
            week.setText("This week in " + projectLabel(pick) + ": " + duration(studied) + " of " + duration(goal));
            LinearLayout.LayoutParams wkp = MainActivity.matchWrap();
            wkp.topMargin = act.dp(14);
            outer.addView(week, wkp);
            outer.addView(bar(Math.min(1f, studied / (float) goal)), barLp());
        }
        return outer;
    }

    /** The other candidates as cards; tapping one shows it as the pick. */
    private void showAlternatives(JSONArray alternatives) {
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        final M3Dialog[] dialog = new M3Dialog[1];
        for (int i = 0; i < alternatives.length(); i++) {
            JSONObject a = alternatives.optJSONObject(i);
            JSONObject item = a != null ? a.optJSONObject("item") : null;
            if (item == null) continue;
            View row = alternativeRow(a, item);
            row.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                showPick(a, null);
            });
            LinearLayout.LayoutParams lp = MainActivity.matchWrap();
            if (list.getChildCount() > 0) lp.topMargin = act.dp(10);
            list.addView(row, lp);
        }
        dialog[0] = new M3Dialog.Builder(act)
                .setTitle("Something else")
                .setView(list)
                .setNegativeButton("Back", null)
                .show();
    }

    private View alternativeRow(JSONObject pick, JSONObject item) {
        LinearLayout row = card(act.M3_SURFACE_CONTAINER_HIGHEST, 20);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = act.dp(14);
        row.setPadding(pad, pad, act.dp(16), pad);
        row.setClickable(true);
        row.setForeground(rippleMask(20));

        // Leading tonal disc with the kind's icon.
        android.widget.FrameLayout disc = new android.widget.FrameLayout(act);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setShape(GradientDrawable.OVAL);
        dbg.setColor(act.M3_PRIMARY_CONTAINER | 0xFF000000);
        disc.setBackground(dbg);
        disc.addView(icon(kindIcon(item.optString("kind")), act.M3_ON_PRIMARY_CONTAINER),
                new android.widget.FrameLayout.LayoutParams(act.dp(22), act.dp(22), Gravity.CENTER));
        row.addView(disc, new LinearLayout.LayoutParams(act.dp(44), act.dp(44)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView kind = text(12, act.M3_PRIMARY, true);
        kind.setText(kindLabel(item.optString("kind")));
        col.addView(kind);
        TextView title = text(16, act.M3_ON_SURFACE, true);
        title.setText(subject(item));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);
        TextView reason = text(13, act.M3_ON_SURFACE_VARIANT, false);
        reason.setText(item.optString("reason"));
        reason.setMaxLines(2);
        reason.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(reason);
        TextView meta = text(12, act.M3_ON_SURFACE_VARIANT, true);
        meta.setText(duration(item.optInt("minutes")) + "  ·  " + where(pick, item));
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams mlp = MainActivity.matchWrap();
        mlp.topMargin = act.dp(6);
        col.addView(meta, mlp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = act.dp(14);
        row.addView(col, clp);
        row.addView(icon(R.drawable.ic_chevron_right, act.M3_ON_SURFACE_VARIANT),
                new LinearLayout.LayoutParams(act.dp(20), act.dp(20)));
        return row;
    }

    private void showNothingDue(JSONObject nextReview) {
        String msg = "No reviews are due and no open goals are waiting.";
        if (nextReview != null && !nextReview.optString("concept").isEmpty()) {
            msg += "\n\nNext review: " + nextReview.optString("concept") + ", "
                    + relative(nextReview.optString("at"))
                    + ". Reviewing earlier would waste the spacing.";
        } else {
            msg += "\n\nOpen a worksheet with the tutor in Learning Mode; its tasks become goals.";
        }
        new M3Dialog.Builder(act)
                .setTitle("Nothing due right now")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Enter the item's project, open its document, and ask the tutor to begin. */
    private void start(String project, JSONObject item) {
        StringBuilder msg = new StringBuilder("Let's study next: ").append(item.optString("title")).append(". ")
                .append(item.optString("ask"));
        String reason = item.optString("reason", "");
        if (!reason.isEmpty()) msg.append(" (").append(reason).append(')');
        final String text = msg.toString();
        Runnable send = () -> {
            if (act.zenMode) act.zen.exitZenMode();
            if (act.chatCollapsed) act.chatView.animateChatToExpanded(act.chatView.chatPanelWidth());
            act.conversations.sendTextViaChat(text);
        };
        if (!project.isEmpty() && !project.equals(act.activeProjectPath)) {
            act.projects.enterProject(project, Projects.projectDisplayName(project), null);
        }
        String doc = docOf(item);
        boolean otherDoc = doc != null && doc.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                && (act.canvas == null || !doc.equals(act.canvas.getDocumentPath()));
        if (otherDoc) act.documents.openPdfDocument(doc, send);
        else send.run();
    }

    /** The weekly study-time goal of the project that is open. */
    void pickWeeklyGoal() {
        if (!hostReady()) return;
        final String project = act.activeProjectPath;
        act.bridge.learningProgress(project, new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject o) {
                if (!act.isDead()) showGoalChoices(project, o.optInt("weeklyGoalMinutes"));
            }

            @Override
            public void onError(String err) {
                act.snackbar("Could not load the weekly goal: " + err, false);
            }
        });
    }

    private static final int[] GOAL_MINUTE_STEPS = {0, 15, 30, 45};

    /**
     * Hours a week on a clock-face dial (1–24, outer ring 1–12, inner 13–24) plus a
     * quarter-hour chip, all in the app's theme colours. "No goal" clears it.
     */
    private void showGoalChoices(String project, int current) {
        final int[] minutes = {current > 0 ? current : 5 * 60};
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView readout = text(40, act.M3_PRIMARY, false);
        readout.setGravity(Gravity.CENTER);
        TextView caption = text(13, act.M3_ON_SURFACE_VARIANT, false);
        caption.setText("a week in " + (project == null || project.isEmpty()
                ? "outside projects" : Projects.projectDisplayName(project)));
        caption.setGravity(Gravity.CENTER);
        body.addView(readout, MainActivity.matchWrap());
        body.addView(caption, MainActivity.matchWrap());

        DurationDialView dial = new DurationDialView(act);
        dial.setColors(act.M3_SURFACE_CONTAINER_HIGHEST, act.M3_PRIMARY, act.M3_ON_PRIMARY_CONTAINER,
                act.M3_ON_SURFACE, act.M3_ON_SURFACE_VARIANT);
        dial.setHours(Math.max(1, Math.min(24, minutes[0] / 60)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = act.dp(16);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(dial, dlp);

        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER);
        final TextView[] chipViews = new TextView[GOAL_MINUTE_STEPS.length];
        final Runnable[] refresh = new Runnable[1];
        for (int i = 0; i < GOAL_MINUTE_STEPS.length; i++) {
            final int m = GOAL_MINUTE_STEPS[i];
            TextView chip = text(14, act.M3_ON_SURFACE, true);
            chip.setText(m == 0 ? ":00" : ":" + m);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8));
            chip.setOnClickListener(v -> {
                minutes[0] = dial.getHours() * 60 + m;
                refresh[0].run();
            });
            chipViews[i] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.leftMargin = act.dp(8);
            chips.addView(chip, clp);
        }
        LinearLayout.LayoutParams chipsLp = MainActivity.matchWrap();
        chipsLp.topMargin = act.dp(16);
        body.addView(chips, chipsLp);

        refresh[0] = () -> {
            readout.setText(duration(minutes[0]));
            int rest = minutes[0] % 60;
            for (int i = 0; i < chipViews.length; i++) {
                boolean on = GOAL_MINUTE_STEPS[i] == rest;
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(act.dp(12));
                if (on) {
                    bg.setColor(act.M3_PRIMARY_CONTAINER | 0xFF000000);
                } else {
                    bg.setColor(0);
                    bg.setStroke(act.dp(1), act.M3_OUTLINE_VARIANT);
                }
                chipViews[i].setBackground(bg);
                chipViews[i].setTextColor(on ? act.M3_ON_PRIMARY_CONTAINER : act.M3_ON_SURFACE);
            }
        };
        dial.setListener(h -> {
            minutes[0] = h * 60 + minutes[0] % 60;
            refresh[0].run();
        });
        refresh[0].run();

        M3Dialog.Builder b = new M3Dialog.Builder(act)
                .setTitle("Weekly goal")
                .setView(body)
                .setPositiveButton("Set", (d, w) -> saveGoal(project, minutes[0]))
                .setNegativeButton("Cancel", null);
        if (current > 0) b.setNeutralButton("No goal", (d, w) -> saveGoal(project, 0));
        b.show();
    }

    private void saveGoal(String project, int minutes) {
        act.bridge.setWeeklyGoal(project, minutes, new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                act.snackbar(minutes > 0 ? "Weekly goal: " + duration(minutes) : "Weekly goal cleared", false);
            }

            @Override
            public void onError(String err) {
                act.snackbar("Could not save the goal: " + err, false);
            }
        });
    }

    // ---- Small view helpers --------------------------------------------------------------

    private static int kindIcon(String kind) {
        if ("review".equals(kind)) return R.drawable.ic_history;
        if ("learn".equals(kind)) return R.drawable.ic_lightbulb;
        return R.drawable.ic_edit;
    }

    private static String kindLabel(String kind) {
        if ("review".equals(kind)) return "Spaced review";
        if ("learn".equals(kind)) return "New topic";
        return "Goal";
    }

    /** The title without its "Review: " / "Learn: " prefix; the kind says that already. */
    private static String subject(JSONObject item) {
        String t = item.optString("title");
        int colon = t.indexOf(": ");
        return colon > 0 && colon < 12 ? t.substring(colon + 2) : t;
    }

    private static String projectLabel(JSONObject pick) {
        String name = pick.optString("projectName", "");
        return name.isEmpty() ? "Outside projects" : name;
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private View kindChip(String kind, int on) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(act.dp(10), act.dp(5), act.dp(12), act.dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(999));
        bg.setColor((on & 0x00FFFFFF) | 0x1F000000);
        chip.setBackground(bg);
        chip.addView(icon(kindIcon(kind), on), new LinearLayout.LayoutParams(act.dp(16), act.dp(16)));
        TextView t = text(12, on, true);
        t.setText(kindLabel(kind));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = act.dp(6);
        chip.addView(t, lp);
        chip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return chip;
    }

    private View metaChip(int iconRes, String label, int on) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(act.dp(10), act.dp(6), act.dp(12), act.dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(10));
        bg.setStroke(act.dp(1), (on & 0x00FFFFFF) | 0x55000000);
        chip.setBackground(bg);
        chip.addView(icon(iconRes, on), new LinearLayout.LayoutParams(act.dp(16), act.dp(16)));
        TextView t = text(13, on, true);
        t.setText(label);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setMaxWidth(act.dp(220));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = act.dp(6);
        chip.addView(t, lp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = act.dp(8);
        chip.setLayoutParams(clp);
        return chip;
    }

    private View bar(float value) {
        android.widget.FrameLayout track = new android.widget.FrameLayout(act);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setCornerRadius(act.dp(4));
        tbg.setColor((act.M3_ON_SURFACE & 0x00FFFFFF) | 0x1F000000);
        track.setBackground(tbg);
        View fill = new View(act);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setCornerRadius(act.dp(4));
        fbg.setColor(act.M3_PRIMARY);
        fill.setBackground(fbg);
        track.addView(fill, new android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * Math.max(0f, Math.min(1f, value)));
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                fill.post(fill::requestLayout);
            }
        });
        return track;
    }

    private LinearLayout.LayoutParams barLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(8));
        lp.topMargin = act.dp(8);
        return lp;
    }

    private LinearLayout card(int color, int radiusDp) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(radiusDp));
        bg.setColor(color | 0xFF000000);
        SketchStyle.border(bg, act.getResources().getDisplayMetrics().density);
        c.setBackground(bg);
        c.setClipToOutline(true);
        return c;
    }

    private Drawable rippleMask(int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(act.dp(radiusDp));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((act.M3_PRIMARY & 0x00FFFFFF) | 0x33000000),
                null, mask);
    }

    private ImageView icon(int res, int tint) {
        ImageView iv = new ImageView(act);
        Drawable dr = act.getDrawable(res);
        if (dr != null) {
            dr = dr.mutate();
            dr.setTint(tint);
        }
        iv.setImageDrawable(dr);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private static String docOf(JSONObject item) {
        if (item == null || item.isNull("document")) return null;
        String d = item.optString("document", "");
        return d.isEmpty() ? null : d;
    }

    private static String where(JSONObject pick, JSONObject item) {
        String name = pick.optString("projectName", "");
        String doc = docOf(item);
        String place = name.isEmpty() ? "Outside projects" : name;
        if (doc != null) place += " / " + doc.substring(doc.lastIndexOf('/') + 1);
        return place;
    }

    /** "45 min", "2 h", "2 h 10 min". */
    static String duration(long minutes) {
        long m = Math.max(0, minutes);
        if (m < 60) return m + " min";
        long h = m / 60, r = m % 60;
        return r == 0 ? h + " h" : h + " h " + r + " min";
    }

    private static String relative(String iso) {
        try {
            long t = java.time.Instant.parse(iso).toEpochMilli();
            return android.text.format.DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
        } catch (Exception e) {
            return "soon";
        }
    }
}
