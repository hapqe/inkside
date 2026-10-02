package me.hapke.inkside;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The study schedule: a week of stopwatch time drawn as blocks (see {@link StudyWeekView}),
 * with the weeks before it one tap away, a summary of the week against the one before,
 * and the details of a tapped block. Opened from the stopwatch and the ⋮ menu.
 */
final class StudyWeekDialog {
    private static final long LIVE_REFRESH_MS = 30_000L;
    /** Enough to tell whether an earlier week still reaches the first recorded session. */
    private static final long WEEK_MS = 7L * 24 * 3600 * 1000;

    private final MainActivity act;
    private final StudyLog log;
    private StudyWeekView week;
    private View shell;
    private TextView weekTitle;
    private TextView weekSubtitle;
    private ImageView prev;
    private ImageView next;
    private TextView thisWeek;
    private LinearLayout chips;
    /** 0 = this week, −1 = last week, … */
    private int offset;

    StudyWeekDialog(MainActivity act) {
        this.act = act;
        this.log = act.studyLog;
    }

    /** Monday 00:00 of the week {@code offsetWeeks} from this one. */
    static long weekStart(int offsetWeeks) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int sinceMonday = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        c.add(Calendar.DAY_OF_MONTH, -sinceMonday + 7 * offsetWeeks);
        return c.getTimeInMillis();
    }

    void show() {
        if (act.rootLayout == null || log == null) return;
        final Runnable dismiss = () -> {
            if (shell != null && shell.getParent() != null) act.rootLayout.removeView(shell);
        };
        View[] built = act.buildPanelShell(0.94f, 0.92f, dismiss);
        shell = built[0];
        LinearLayout card = (LinearLayout) built[1];

        // Title row: the title, and the way back to today.
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(act.panelTitle("Study week"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        thisWeek = act.panelAction("This week", false, () -> go(0, 1));
        titleRow.addView(thisWeek);
        card.addView(titleRow);

        // Navigator: ‹  29 Sep – 5 Oct  ›
        LinearLayout nav = new LinearLayout(act);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        prev = act.iconBtn(R.drawable.ic_chevron_left, () -> go(offset - 1, -1));
        prev.setContentDescription("Previous week");
        next = act.iconBtn(R.drawable.ic_chevron_right, () -> go(offset + 1, 1));
        next.setContentDescription("Next week");
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        weekTitle = text(20, act.M3_ON_SURFACE, true);
        weekSubtitle = text(12, act.M3_ON_SURFACE_VARIANT, false);
        weekTitle.setGravity(Gravity.CENTER);
        weekSubtitle.setGravity(Gravity.CENTER);
        titles.addView(weekTitle);
        titles.addView(weekSubtitle);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(act.dp(40), act.dp(40));
        nav.addView(prev, ip);
        nav.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(next, new LinearLayout.LayoutParams(act.dp(40), act.dp(40)));
        LinearLayout.LayoutParams navLp = MainActivity.matchWrap();
        navLp.topMargin = act.dp(MainActivity.SPACE_SM);
        card.addView(nav, navLp);

        // Time per project.
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(chips);
        LinearLayout.LayoutParams chipsLp = MainActivity.matchWrap();
        chipsLp.topMargin = act.dp(MainActivity.SPACE_MD);
        card.addView(chipScroll, chipsLp);

        // The schedule, in a rounded well.
        week = new StudyWeekView(act, new StudyWeekView.Host() {
            @Override
            public void onSessionTapped(StudyLog.Session session) {}

            @Override
            public void onSwipeWeek(int direction) {
                go(offset + direction, direction);
            }
        });
        week.setColors(act.M3_SURFACE, act.M3_ON_SURFACE, act.M3_ON_SURFACE_VARIANT, act.M3_PRIMARY,
                act.M3_PRIMARY_CONTAINER, act.M3_ON_PRIMARY_CONTAINER, act.M3_OUTLINE_VARIANT,
                ThemeConfig.appThemeById(act.appThemeId).light);
        GradientDrawable well = new GradientDrawable();
        well.setCornerRadius(act.dp(22));
        well.setColor(act.M3_SURFACE | 0xFF000000);
        week.setBackground(well);
        week.setClipToOutline(true);
        LinearLayout.LayoutParams weekLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        weekLp.topMargin = act.dp(MainActivity.SPACE_MD);
        card.addView(week, weekLp);

        LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.addView(act.panelAction("Done", true, dismiss));
        LinearLayout.LayoutParams footLp = MainActivity.matchWrap();
        footLp.topMargin = act.dp(MainActivity.SPACE_MD);
        card.addView(footer, footLp);

        refresh(true);
        act.rootLayout.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // While the stopwatch runs on the week being looked at, its block keeps growing.
        final Runnable live = new Runnable() {
            @Override
            public void run() {
                if (shell == null || shell.getParent() == null) return;
                if (offset == 0 && log.isOpen()) refresh(false);
                week.postDelayed(this, LIVE_REFRESH_MS);
            }
        };
        week.postDelayed(live, LIVE_REFRESH_MS);
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    /** Moves to another week, sliding the schedule in from the side it came from. */
    private void go(int newOffset, int direction) {
        if (newOffset > 0 || newOffset == offset) return;
        if (newOffset < offset) {
            long firstStart = log.firstStart();
            if (firstStart < 0 || weekStart(newOffset) + WEEK_MS <= firstStart) return;
        }
        offset = newOffset;
        week.setTranslationX(direction * act.dp(48));
        week.setAlpha(0f);
        week.animate().translationX(0f).alpha(1f).setDuration(240).start();
        refresh(true);
    }

    private void refresh(boolean animate) {
        long start = weekStart(offset);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(start);
        c.add(Calendar.DAY_OF_MONTH, 7);
        long end = c.getTimeInMillis();
        List<StudyLog.Session> sessions = log.range(start, end);
        week.setWeek(start, sessions, offset == 0, animate);

        // Titles.
        SimpleDateFormat day = new SimpleDateFormat("d MMM", Locale.getDefault());
        c.add(Calendar.DAY_OF_MONTH, -1);
        String range = day.format(new Date(start)) + " – " + day.format(c.getTime());
        weekTitle.setText(offset == 0 ? "This week" : offset == -1 ? "Last week" : (-offset) + " weeks ago");
        Calendar now = Calendar.getInstance();
        Calendar s = Calendar.getInstance();
        s.setTimeInMillis(start);
        weekSubtitle.setText(s.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                ? range : range + " " + s.get(Calendar.YEAR));

        // Navigation state.
        long first = log.firstStart();
        boolean canPrev = first >= 0 && weekStart(offset - 1) + WEEK_MS > first;
        setEnabled(prev, canPrev);
        setEnabled(next, offset < 0);
        thisWeek.setVisibility(offset == 0 ? View.INVISIBLE : View.VISIBLE);

        // Time per project (the folder of the document), most first.
        java.util.LinkedHashMap<String, Long> perFolder = new java.util.LinkedHashMap<>();
        java.util.HashMap<String, String> sampleDoc = new java.util.HashMap<>();
        long total = 0;
        for (StudyLog.Session se : sessions) {
            long ms = Math.min(se.end, end) - Math.max(se.start, start);
            if (ms <= 0) continue;
            String folder = StudyWeekView.folderOf(se.doc);
            Long cur = perFolder.get(folder);
            perFolder.put(folder, (cur == null ? 0L : cur) + ms);
            sampleDoc.put(folder, se.doc);
            total += ms;
        }
        weekSubtitle.setText(weekSubtitle.getText() + (total > 0 ? "  ·  " + StudyWeekView.formatDuration(total) : ""));
        java.util.List<java.util.Map.Entry<String, Long>> rows = new java.util.ArrayList<>(perFolder.entrySet());
        java.util.Collections.sort(rows, (x, y) -> Long.compare(y.getValue(), x.getValue()));
        chips.removeAllViews();
        for (java.util.Map.Entry<String, Long> e : rows) {
            addProject(e.getKey(), e.getValue(), week.blockColor(sampleDoc.get(e.getKey())));
        }
    }

    private void setEnabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.3f);
    }

    private void addProject(String folder, long ms, int color) {
        LinearLayout item = new LinearLayout(act);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(act);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(act.dp(4));
        d.setColor(color);
        dot.setBackground(d);
        item.addView(dot, new LinearLayout.LayoutParams(act.dp(8), act.dp(8)));
        TextView name = text(14, act.M3_ON_SURFACE, false);
        name.setSingleLine(true);
        name.setText(folder.isEmpty() ? "Unfiled" : folder.substring(folder.lastIndexOf('/') + 1));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.leftMargin = act.dp(8);
        item.addView(name, nameLp);
        TextView time = text(14, act.M3_ON_SURFACE_VARIANT, false);
        time.setText(StudyWeekView.formatDuration(ms));
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = act.dp(6);
        item.addView(time, timeLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (chips.getChildCount() > 0) lp.leftMargin = act.dp(22);
        chips.addView(item, lp);
    }
}
