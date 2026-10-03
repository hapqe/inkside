package me.hapke.inkside;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the tutor knows in the current project, in one place: course progress, the topics
 * on the understanding ladder, and the learning goals per document. Read from the host
 * ({@code GET /learning/progress}); opened from the ⋮ menu and Settings → Learning Mode.
 */
final class LearningDialog {
    /** Host level ids, lowest first, and how they read here. */
    private static final String[] LEVELS = {
            "not_encountered", "explained", "recalled", "applied", "transferred", "mastered"};
    private static final String[] LEVEL_LABELS = {
            "Not yet", "Explained", "Recalled", "Applied", "Transferred", "Mastered"};
    /** Tonal ramp of the primary colour, one step per rung. */
    private static final int[] LEVEL_ALPHA = {0x00, 0x4D, 0x73, 0x99, 0xC7, 0xFF};

    private final MainActivity act;
    private View shell;
    private LinearLayout body;
    private TextView subtitle;
    private int stagger;

    LearningDialog(MainActivity act) {
        this.act = act;
    }

    void show() {
        if (act.rootLayout == null) return;
        final Runnable dismiss = () -> {
            if (shell != null && shell.getParent() != null) act.rootLayout.removeView(shell);
        };
        View[] built = act.buildPanelShell(0.94f, 0.92f, dismiss);
        shell = built[0];
        LinearLayout card = (LinearLayout) built[1];

        card.addView(act.panelTitle("Learning"));
        subtitle = text(13, act.M3_ON_SURFACE_VARIANT, false);
        subtitle.setText(projectName());
        card.addView(subtitle);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, act.dp(MainActivity.SPACE_LG));
        scroll.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = act.dp(MainActivity.SPACE_LG);
        card.addView(scroll, scrollLp);

        LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.addView(act.panelAction("Done", true, dismiss));
        LinearLayout.LayoutParams footLp = MainActivity.matchWrap();
        footLp.topMargin = act.dp(MainActivity.SPACE_MD);
        card.addView(footer, footLp);

        act.rootLayout.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        load();
    }

    private String projectName() {
        String p = act.activeProjectPath;
        if (p == null || p.isEmpty() || ".".equals(p)) return "Outside projects";
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private boolean open() {
        return shell != null && shell.getParent() != null && !act.isDead();
    }

    private void load() {
        if (!act.computers.hasHost() || act.bridge == null) {
            message(R.drawable.ic_cloud, "No computer connected",
                    "The tutor's record lives on your computer. Connect one to see it.");
            return;
        }
        message(R.drawable.ic_sync, "Loading…", null);
        act.bridge.learningProgress(act.activeProjectPath, new BridgeClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject o) {
                if (open()) render(o);
            }

            @Override
            public void onError(String err) {
                if (open()) message(R.drawable.ic_error, "Could not load", err);
            }
        });
    }

    // ---- Rendering -------------------------------------------------------------------

    private void render(JSONObject o) {
        body.removeAllViews();
        stagger = 0;
        subtitle.setText(projectName() + "  ·  Learning Mode " + (act.learningMode ? "on" : "off"));
        JSONObject topics = o.optJSONObject("topics");
        JSONObject goals = o.optJSONObject("goals");
        JSONArray docs = o.optJSONArray("documents");
        JSONArray pending = o.optJSONArray("pendingDocs");
        int topicTotal = topics != null ? topics.optInt("total") : 0;
        int goalTotal = goals != null ? goals.optInt("total") : 0;
        if (topicTotal == 0 && goalTotal == 0 && (pending == null || pending.length() == 0)) {
            message(R.drawable.ic_lightbulb, "Nothing recorded yet",
                    act.learningMode
                            ? "Work through a sheet with the tutor in this project. What you show you "
                                    + "understand, and the goals it sets, will appear here."
                            : "Turn on Learning Mode in Settings → AI, then work through a sheet with the "
                                    + "tutor in this project. Your progress will appear here.");
            return;
        }
        add(hero(o, topics, goals));
        add(statTiles(topics, goals, docs));
        if (topicTotal > 0) {
            add(sectionHeader("Topics", topicTotal + (topicTotal == 1 ? " topic" : " topics")));
            add(levelCard(topics));
            JSONArray items = topics.optJSONArray("items");
            if (items != null) {
                LinearLayout list = card(act.M3_SURFACE_CONTAINER_HIGHEST, 20);
                list.setPadding(0, act.dp(MainActivity.SPACE_SM), 0, act.dp(MainActivity.SPACE_SM));
                for (int i = 0; i < items.length(); i++) {
                    if (i > 0) list.addView(divider());
                    list.addView(topicRow(items.optJSONObject(i)));
                }
                add(list);
            }
        }
        if (docs != null && docs.length() > 0) {
            add(sectionHeader("Goals", goalTotal + (goalTotal == 1 ? " goal" : " goals")));
            for (int i = 0; i < docs.length(); i++) add(documentCard(docs.optJSONObject(i)));
        }
        if (pending != null && pending.length() > 0) {
            add(sectionHeader("Waiting for goals", null));
            LinearLayout list = card(act.M3_SURFACE_CONTAINER_HIGHEST, 20);
            for (int i = 0; i < pending.length(); i++) {
                list.addView(iconLine(R.drawable.ic_pdf, baseName(pending.optString(i)),
                        "The tutor will set goals when it comes up", act.M3_ON_SURFACE_VARIANT));
            }
            add(list);
        }
        TextView note = text(12, act.M3_ON_SURFACE_VARIANT, false);
        note.setText("Course progress weighs how far each topic is up the ladder (60%) and the goals "
                + "you have finished (40%). Only your own answers move a topic past \"Explained\".");
        LinearLayout.LayoutParams nlp = MainActivity.matchWrap();
        nlp.topMargin = act.dp(MainActivity.SPACE_XL);
        body.addView(note, nlp);
    }

    /** Adds a block with a short staggered rise-in. */
    private void add(View v) {
        LinearLayout.LayoutParams lp = MainActivity.matchWrap();
        if (body.getChildCount() > 0) lp.topMargin = act.dp(MainActivity.SPACE_LG + 2);
        body.addView(v, lp);
        v.setAlpha(0f);
        v.setTranslationY(act.dp(14));
        v.animate().alpha(1f).translationY(0f).setStartDelay(40L * stagger++)
                .setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private View hero(JSONObject o, JSONObject topics, JSONObject goals) {
        LinearLayout hero = card(act.M3_PRIMARY_CONTAINER, 28);
        boolean wide = widthDp() >= 560;
        hero.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        hero.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        int pad = act.dp(20);
        hero.setPadding(pad, pad, pad, pad);

        float progress = (float) o.optDouble("progress", 0);
        RingView ring = new RingView(act);
        ring.setColors((act.M3_ON_PRIMARY_CONTAINER & 0x00FFFFFF) | 0x2E000000, act.M3_PRIMARY,
                act.M3_ON_PRIMARY_CONTAINER);
        ring.setLabel("course");
        hero.addView(ring, new LinearLayout.LayoutParams(act.dp(136), act.dp(136)));
        ring.animateTo(progress);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(22, act.M3_ON_PRIMARY_CONTAINER, false);
        title.setText(headline(progress));
        col.addView(title);
        TextView line = text(14, (act.M3_ON_PRIMARY_CONTAINER & 0x00FFFFFF) | 0xCC000000, false);
        line.setText(summaryLine(topics, goals));
        LinearLayout.LayoutParams llp = MainActivity.matchWrap();
        llp.topMargin = act.dp(MainActivity.SPACE_SM);
        col.addView(line, llp);
        if (!o.isNull("topicProgress")) {
            col.addView(meter("Topics", (float) o.optDouble("topicProgress", 0)), meterLp());
        }
        if (!o.isNull("goalProgress")) {
            col.addView(meter("Goals", (float) o.optDouble("goalProgress", 0)), meterLp());
        }
        LinearLayout.LayoutParams clp = wide
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : MainActivity.matchWrap();
        if (wide) clp.leftMargin = act.dp(24);
        else clp.topMargin = act.dp(16);
        hero.addView(col, clp);
        return hero;
    }

    private LinearLayout.LayoutParams meterLp() {
        LinearLayout.LayoutParams lp = MainActivity.matchWrap();
        lp.topMargin = act.dp(MainActivity.SPACE_LG + 2);
        return lp;
    }

    private static String headline(float p) {
        if (p >= 0.999f) return "Course mastered";
        if (p >= 0.75f) return "Nearly there";
        if (p >= 0.4f) return "Well under way";
        if (p > 0.05f) return "Getting started";
        return "Just begun";
    }

    private static String summaryLine(JSONObject topics, JSONObject goals) {
        StringBuilder sb = new StringBuilder();
        if (topics != null && topics.optInt("total") > 0) {
            int total = topics.optInt("total");
            sb.append("You have worked through ").append(topics.optInt("encountered")).append(" of ")
                    .append(total).append(total == 1 ? " topic" : " topics").append(" and mastered ")
                    .append(topics.optInt("mastered")).append('.');
        }
        if (goals != null && goals.optInt("total") > 0) {
            if (sb.length() > 0) sb.append(' ');
            int solved = goals.optInt("solved_by_student");
            sb.append(solved).append(" of ").append(goals.optInt("total"))
                    .append(" goals solved on your own");
            int shown = goals.optInt("solution_shown");
            if (shown > 0) sb.append(", ").append(shown).append(" with the solution shown");
            sb.append('.');
        }
        return sb.toString();
    }

    /** "Topics  ━━━━━━━──── 54%" on the hero card. */
    private View meter(String label, float value) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(13, act.M3_ON_PRIMARY_CONTAINER, true);
        name.setText(label);
        row.addView(name, new LinearLayout.LayoutParams(act.dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        BarView bar = new BarView(act);
        bar.setColors((act.M3_ON_PRIMARY_CONTAINER & 0x00FFFFFF) | 0x2E000000, act.M3_PRIMARY);
        bar.animateTo(value);
        row.addView(bar, new LinearLayout.LayoutParams(0, act.dp(8), 1f));
        TextView pct = text(13, act.M3_ON_PRIMARY_CONTAINER, true);
        pct.setText(percent(value));
        pct.setGravity(Gravity.END);
        row.addView(pct, new LinearLayout.LayoutParams(act.dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View statTiles(JSONObject topics, JSONObject goals, JSONArray docs) {
        int waiting = 0;
        if (docs != null) {
            for (int i = 0; i < docs.length(); i++) {
                JSONArray gs = docs.optJSONObject(i).optJSONArray("goals");
                if (gs == null) continue;
                for (int j = 0; j < gs.length(); j++) {
                    if (gs.optJSONObject(j).optBoolean("awaitingStudent")) waiting++;
                }
            }
        }
        int tTotal = topics != null ? topics.optInt("total") : 0;
        int gTotal = goals != null ? goals.optInt("total") : 0;
        int gDone = goals != null ? goals.optInt("solved_by_student") + goals.optInt("solution_shown") : 0;
        View[] tiles = {
                tile(R.drawable.ic_lightbulb, (topics != null ? topics.optInt("encountered") : 0) + "/" + tTotal,
                        "Topics seen"),
                tile(R.drawable.ic_star, String.valueOf(topics != null ? topics.optInt("mastered") : 0),
                        "Mastered"),
                tile(R.drawable.ic_check_circle, gDone + "/" + gTotal, "Goals done"),
                tile(R.drawable.ic_timer, String.valueOf(waiting), waiting == 1 ? "Hint waiting on you" : "Hints waiting on you"),
        };
        int perRow = widthDp() >= 560 ? 4 : 2;
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = MainActivity.matchWrap();
                if (i > 0) rlp.topMargin = act.dp(MainActivity.SPACE_LG);
                grid.addView(row, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % perRow > 0) lp.leftMargin = act.dp(MainActivity.SPACE_LG);
            row.addView(tiles[i], lp);
        }
        return grid;
    }

    private View tile(int icon, String value, String label) {
        LinearLayout t = card(act.M3_SURFACE_CONTAINER_HIGHEST, 20);
        int pad = act.dp(16);
        t.setPadding(pad, pad, pad, pad);
        ImageView iv = icon(icon, act.M3_PRIMARY, 22);
        t.addView(iv, new LinearLayout.LayoutParams(act.dp(22), act.dp(22)));
        TextView v = text(28, act.M3_ON_SURFACE, false);
        v.setText(value);
        LinearLayout.LayoutParams vlp = MainActivity.matchWrap();
        vlp.topMargin = act.dp(MainActivity.SPACE_LG);
        t.addView(v, vlp);
        TextView l = text(12, act.M3_ON_SURFACE_VARIANT, true);
        l.setText(label);
        t.addView(l);
        return t;
    }

    private View sectionHeader(String title, String trailing) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(act.dp(4), act.dp(MainActivity.SPACE_LG), act.dp(4), 0);
        TextView t = text(16, act.M3_PRIMARY, true);
        t.setText(title);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            TextView tr = text(12, act.M3_ON_SURFACE_VARIANT, false);
            tr.setText(trailing);
            row.addView(tr);
        }
        return row;
    }

    /** The whole project on the ladder: one segmented bar plus a legend. */
    private View levelCard(JSONObject topics) {
        LinearLayout c = card(act.M3_SURFACE_CONTAINER_HIGHEST, 20);
        int pad = act.dp(16);
        c.setPadding(pad, pad, pad, pad);
        JSONObject levels = topics.optJSONObject("levels");
        int[] counts = new int[LEVELS.length];
        for (int i = 0; i < LEVELS.length; i++) counts[i] = levels != null ? levels.optInt(LEVELS[i]) : 0;
        SegmentsView seg = new SegmentsView(act);
        int[] colors = new int[LEVELS.length];
        for (int i = 0; i < LEVELS.length; i++) colors[i] = levelColor(i);
        seg.set(counts, colors);
        c.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(14)));

        // Legend, wrapping two or three per line on a phone.
        int perRow = widthDp() >= 560 ? 6 : 3;
        LinearLayout row = null;
        for (int i = LEVELS.length - 1, n = 0; i >= 0; i--, n++) {
            if (n % perRow == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = MainActivity.matchWrap();
                rlp.topMargin = act.dp(MainActivity.SPACE_LG + 2);
                c.addView(row, rlp);
            }
            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(act);
            dot.setBackground(levelDot(i, 5));
            item.addView(dot, new LinearLayout.LayoutParams(act.dp(10), act.dp(10)));
            TextView l = text(12, act.M3_ON_SURFACE_VARIANT, false);
            l.setText(LEVEL_LABELS[i] + "  " + counts[i]);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = act.dp(6);
            item.addView(l, llp);
            row.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return c;
    }

    private View topicRow(JSONObject t) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12));
        int level = levelIndex(t.optString("level"));

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(15, act.M3_ON_SURFACE, true);
        name.setText(capitalize(t.optString("name")));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(levelChip(level));
        row.addView(top);

        LadderView ladder = new LadderView(act);
        int[] colors = new int[LEVELS.length];
        for (int i = 0; i < LEVELS.length; i++) colors[i] = levelColor(i);
        ladder.set(level, colors, (act.M3_ON_SURFACE & 0x00FFFFFF) | 0x1F000000);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(6));
        llp.topMargin = act.dp(MainActivity.SPACE_LG);
        row.addView(ladder, llp);

        StringBuilder meta = new StringBuilder();
        JSONObject ev = t.optJSONObject("lastEvidence");
        if (ev != null && !ev.optString("note").isEmpty()) {
            meta.append("“").append(ev.optString("note")).append("”");
            String when = relative(ev.optString("at"));
            if (!when.isEmpty()) meta.append("  ·  ").append(when);
        } else if (level == 0) {
            meta.append("Not covered yet");
        }
        int inGoals = t.optInt("inGoals");
        if (inGoals > 0) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append("in ").append(inGoals).append(inGoals == 1 ? " goal" : " goals");
        }
        if (meta.length() > 0) {
            TextView m = text(12, act.M3_ON_SURFACE_VARIANT, false);
            m.setText(meta);
            m.setMaxLines(2);
            m.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mlp = MainActivity.matchWrap();
            mlp.topMargin = act.dp(MainActivity.SPACE_MD);
            row.addView(m, mlp);
        }
        return row;
    }

    private View levelChip(int level) {
        TextView chip = text(12, level >= 3 ? act.M3_ON_PRIMARY_CONTAINER : act.M3_ON_SURFACE, true);
        chip.setText(LEVEL_LABELS[level]);
        chip.setPadding(act.dp(10), act.dp(4), act.dp(10), act.dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(8));
        if (level >= 3) {
            bg.setColor(act.M3_PRIMARY_CONTAINER);
        } else {
            bg.setColor(0);
            bg.setStroke(act.dp(1), act.M3_OUTLINE_VARIANT);
        }
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = act.dp(MainActivity.SPACE_LG);
        chip.setLayoutParams(lp);
        return chip;
    }

    private View documentCard(JSONObject d) {
        LinearLayout c = card(act.M3_SURFACE_CONTAINER_HIGHEST, 20);
        c.setPadding(0, act.dp(16), 0, act.dp(MainActivity.SPACE_LG));
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(act.dp(16), 0, act.dp(16), 0);
        head.addView(icon(R.drawable.ic_description, act.M3_PRIMARY, 22),
                new LinearLayout.LayoutParams(act.dp(22), act.dp(22)));
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(15, act.M3_ON_SURFACE, true);
        name.setText(baseName(d.optString("document")));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titles.addView(name);
        TextView sub = text(12, act.M3_ON_SURFACE_VARIANT, false);
        sub.setText(d.optInt("done") + " of " + d.optInt("total") + " done");
        titles.addView(sub);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = act.dp(12);
        head.addView(titles, tlp);
        TextView pct = text(15, act.M3_PRIMARY, true);
        pct.setText(percent((float) d.optDouble("progress", 0)));
        head.addView(pct);
        c.addView(head);

        BarView bar = new BarView(act);
        bar.setColors((act.M3_ON_SURFACE & 0x00FFFFFF) | 0x1F000000, act.M3_PRIMARY);
        bar.animateTo((float) d.optDouble("progress", 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(6));
        blp.setMargins(act.dp(16), act.dp(12), act.dp(16), act.dp(MainActivity.SPACE_SM));
        c.addView(bar, blp);

        JSONArray goals = d.optJSONArray("goals");
        if (goals != null) {
            for (int i = 0; i < goals.length(); i++) c.addView(goalRow(goals.optJSONObject(i)));
        }
        return c;
    }

    private View goalRow(JSONObject g) {
        String status = g.optString("status");
        boolean solved = "solved_by_student".equals(status);
        boolean shown = "solution_shown".equals(status);
        int hint = g.optInt("hintLevel");
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10));
        View mark;
        if (solved) {
            mark = icon(R.drawable.ic_check_circle, act.M3_PRIMARY, 20);
        } else if (shown) {
            mark = icon(R.drawable.ic_visibility, act.M3_ON_SURFACE_VARIANT, 20);
        } else {
            mark = new View(act);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setStroke(act.dp(2), hint > 0 ? act.M3_PRIMARY : act.M3_OUTLINE_VARIANT);
            mark.setBackground(ring);
        }
        FrameLayout markBox = new FrameLayout(act);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(act.dp(solved || shown ? 20 : 16),
                act.dp(solved || shown ? 20 : 16), Gravity.CENTER);
        markBox.addView(mark, mlp);
        row.addView(markBox, new LinearLayout.LayoutParams(act.dp(20), act.dp(20)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView task = text(14, act.M3_ON_SURFACE, false);
        task.setText(g.optString("task"));
        col.addView(task);
        StringBuilder meta = new StringBuilder(policyLabel(g.optString("policy")));
        if (solved) meta.append("  ·  solved on your own");
        else if (shown) meta.append("  ·  solution shown");
        else if (hint > 0) meta.append("  ·  hint ").append(hint).append("/5");
        if (g.optBoolean("awaitingStudent")) meta.append("  ·  your turn");
        TextView m = text(12, act.M3_ON_SURFACE_VARIANT, false);
        m.setText(meta);
        col.addView(m);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = act.dp(14);
        row.addView(col, clp);

        if (!solved && !shown && "self-solve".equals(g.optString("policy"))) {
            HintDotsView dots = new HintDotsView(act);
            dots.set(hint, act.M3_PRIMARY, (act.M3_ON_SURFACE & 0x00FFFFFF) | 0x26000000);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(act.dp(52), act.dp(8));
            dlp.leftMargin = act.dp(MainActivity.SPACE_LG);
            row.addView(dots, dlp);
        }
        return row;
    }

    private static String policyLabel(String p) {
        if ("guided".equals(p)) return "Guided";
        if ("reference".equals(p)) return "Reference";
        return "Self-solve";
    }

    private View iconLine(int icon, String title, String sub, int tint) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12));
        row.addView(icon(icon, tint, 20), new LinearLayout.LayoutParams(act.dp(20), act.dp(20)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(14, act.M3_ON_SURFACE, false);
        t.setText(title);
        col.addView(t);
        if (sub != null) {
            TextView s = text(12, act.M3_ON_SURFACE_VARIANT, false);
            s.setText(sub);
            col.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = act.dp(14);
        row.addView(col, lp);
        return row;
    }

    /** Empty, loading and error states: a tonal disc with an icon, a title and a line. */
    private void message(int icon, String title, String detail) {
        body.removeAllViews();
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(act.dp(24), act.dp(64), act.dp(24), act.dp(24));
        FrameLayout disc = new FrameLayout(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(act.M3_PRIMARY_CONTAINER);
        disc.setBackground(bg);
        disc.addView(icon(icon, act.M3_ON_PRIMARY_CONTAINER, 32),
                new FrameLayout.LayoutParams(act.dp(32), act.dp(32), Gravity.CENTER));
        box.addView(disc, new LinearLayout.LayoutParams(act.dp(72), act.dp(72)));
        TextView t = text(20, act.M3_ON_SURFACE, false);
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = act.dp(16);
        box.addView(t, tlp);
        if (detail != null && !detail.isEmpty()) {
            TextView d = text(14, act.M3_ON_SURFACE_VARIANT, false);
            d.setText(detail);
            d.setGravity(Gravity.CENTER);
            d.setMaxWidth(act.dp(420));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = act.dp(MainActivity.SPACE_LG);
            box.addView(d, dlp);
        }
        body.addView(box, MainActivity.matchWrap());
    }

    // ---- Small helpers -----------------------------------------------------------------

    private LinearLayout card(int color, int radiusDp) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(act.dp(radiusDp));
        bg.setColor(color | 0xFF000000);
        c.setBackground(bg);
        c.setClipToOutline(true);
        return c;
    }

    private View divider() {
        View d = new View(act);
        d.setBackgroundColor((act.M3_OUTLINE_VARIANT & 0x00FFFFFF) | 0x66000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(act.dp(16), 0, act.dp(16), 0);
        d.setLayoutParams(lp);
        return d;
    }

    private ImageView icon(int res, int tint, int sizeDp) {
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

    private GradientDrawable levelDot(int level, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(act.dp(radiusDp));
        if (level == 0) {
            g.setColor(0);
            g.setStroke(act.dp(1), act.M3_OUTLINE_VARIANT);
        } else {
            g.setColor(levelColor(level));
        }
        return g;
    }

    private int levelColor(int level) {
        if (level <= 0) return (act.M3_ON_SURFACE & 0x00FFFFFF) | 0x1F000000;
        return (act.M3_PRIMARY & 0x00FFFFFF) | (LEVEL_ALPHA[level] << 24);
    }

    private static int levelIndex(String id) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(id)) return i;
        return 0;
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private int widthDp() {
        int w = act.rootLayout.getWidth() > 0 ? act.rootLayout.getWidth()
                : act.getResources().getDisplayMetrics().widthPixels;
        return Math.round(w * 0.94f / act.getResources().getDisplayMetrics().density);
    }

    private static String percent(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 100f) + "%";
    }

    private static String baseName(String path) {
        if (path == null) return "";
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String relative(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            long t = java.time.Instant.parse(iso).toEpochMilli();
            return DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Custom views ------------------------------------------------------------------

    /** M3 circular progress: rounded track and indicator with a gap, percent in the middle. */
    static final class RingView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float value;
        private String label = "";

        RingView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            for (Paint p : new Paint[] {track, arc}) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(12 * d);
            }
            big.setTextAlign(Paint.Align.CENTER);
            big.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            small.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setColors(int trackColor, int arcColor, int textColor) {
            track.setColor(trackColor);
            arc.setColor(arcColor);
            big.setColor(textColor);
            small.setColor((textColor & 0x00FFFFFF) | 0xB3000000);
        }

        void setLabel(String l) {
            label = l;
        }

        void animateTo(float target) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, Math.max(0f, Math.min(1f, target)));
            a.setDuration(1100);
            a.setStartDelay(150);
            a.setInterpolator(new DecelerateInterpolator(2.2f));
            a.addUpdateListener(an -> {
                value = (float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float sw = arc.getStrokeWidth();
            float size = Math.min(getWidth(), getHeight());
            float l = (getWidth() - size) / 2f + sw / 2f;
            float t = (getHeight() - size) / 2f + sw / 2f;
            box.set(l, t, l + size - sw, t + size - sw);
            float sweep = 360f * value;
            // The gap between indicator and track, as in M3's circular indicator.
            float gap = value > 0.005f && value < 0.995f ? (sw * 1.6f) / (box.width() * (float) Math.PI) * 360f : 0f;
            if (value < 0.995f) {
                c.drawArc(box, -90f + sweep + gap, 360f - sweep - 2 * gap, false, track);
            }
            if (value > 0.005f) c.drawArc(box, -90f, sweep, false, arc);
            float d = getResources().getDisplayMetrics().density;
            big.setTextSize(size * 0.25f);
            small.setTextSize(12 * d);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            c.drawText(Math.round(value * 100f) + "%", cx, cy + big.getTextSize() * 0.28f, big);
            c.drawText(label, cx, cy + big.getTextSize() * 0.28f + small.getTextSize() * 1.5f, small);
        }
    }

    /** M3 linear progress: rounded track and indicator separated by a small gap. */
    static final class BarView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float value;

        BarView(Context c) {
            super(c);
        }

        void setColors(int trackColor, int fillColor) {
            track.setColor(trackColor);
            fill.setColor(fillColor);
        }

        void animateTo(float target) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, Math.max(0f, Math.min(1f, target)));
            a.setDuration(900);
            a.setStartDelay(200);
            a.setInterpolator(new DecelerateInterpolator(2f));
            a.addUpdateListener(an -> {
                value = (float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            float w = getWidth();
            float rad = h / 2f;
            float fw = w * value;
            float gap = value > 0.005f && value < 0.995f ? h * 0.6f : 0f;
            if (fw + gap < w) {
                r.set(Math.max(0f, fw + gap), 0, w, h);
                c.drawRoundRect(r, rad, rad, track);
            }
            if (fw > 0.5f) {
                r.set(0, 0, Math.max(h, fw), h);
                c.drawRoundRect(r, rad, rad, fill);
            }
        }
    }

    /** Topics per level as one bar of rounded segments. */
    static final class SegmentsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int[] counts = new int[0];
        private int[] colors = new int[0];

        SegmentsView(Context c) {
            super(c);
        }

        void set(int[] counts, int[] colors) {
            this.counts = counts;
            this.colors = colors;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int total = 0;
            int parts = 0;
            for (int n : counts) {
                total += n;
                if (n > 0) parts++;
            }
            if (total == 0) return;
            float h = getHeight();
            float gap = h * 0.35f;
            float avail = getWidth() - gap * (parts - 1);
            float x = 0;
            // Highest rung first, so the bar fills from the left with what is mastered.
            for (int i = counts.length - 1; i >= 0; i--) {
                if (counts[i] == 0) continue;
                float w = avail * counts[i] / total;
                r.set(x, 0, x + w, h);
                p.setColor(colors[i]);
                c.drawRoundRect(r, h / 2f, h / 2f, p);
                x += w + gap;
            }
        }
    }

    /** One topic's place on the ladder: five steps above "not yet", filled up to it. */
    static final class LadderView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int level;
        private int[] colors = new int[0];
        private int empty;

        LadderView(Context c) {
            super(c);
        }

        void set(int level, int[] colors, int empty) {
            this.level = level;
            this.colors = colors;
            this.empty = empty;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int steps = colors.length - 1;
            float h = getHeight();
            float gap = h * 0.8f;
            float w = (getWidth() - gap * (steps - 1)) / steps;
            for (int i = 0; i < steps; i++) {
                float x = i * (w + gap);
                r.set(x, 0, x + w, h);
                p.setColor(i < level ? colors[level] : empty);
                c.drawRoundRect(r, h / 2f, h / 2f, p);
            }
        }
    }

    /** The hint ladder of a self-solve goal: five dots, filled up to the rung given. */
    static final class HintDotsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int hint;
        private int on;
        private int off;

        HintDotsView(Context c) {
            super(c);
        }

        void set(int hint, int on, int off) {
            this.hint = hint;
            this.on = on;
            this.off = off;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float rad = getHeight() / 2f;
            float step = (getWidth() - 2 * rad) / 4f;
            for (int i = 0; i < 5; i++) {
                p.setColor(i < hint ? on : off);
                c.drawCircle(rad + i * step, rad, rad, p);
            }
        }
    }
}
