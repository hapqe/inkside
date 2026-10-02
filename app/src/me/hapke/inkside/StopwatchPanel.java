package me.hapke.inkside;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The stopwatch tool's options panel: elapsed time, a tonal Reset button and a filled
 * Start / Pause / Resume button, sized to sit in the tool pill's option row.
 *
 * <p>The host calls {@link #pauseForLeaving()} from onPause so time spent outside the
 * app never counts.
 */
final class StopwatchPanel extends LinearLayout {
    /** Whole seconds are shown, so tick once a second — and only while on screen. */
    private static final long TICK_MS = 1000L;

    private final TextView time;
    private final LinearLayout resetButton;
    private final ImageView resetIcon;
    private final TextView resetLabel;
    private final LinearLayout startButton;
    private final ImageView startIcon;
    private final TextView startLabel;
    private final int buttonHeight;
    private final ImageView weekButton;
    private Runnable onWeek;

    private long accumulatedMs;
    private long runningSinceMs = -1L;
    private Runnable onChanged;

    private int onSurface = 0xFFE6E1E9;
    private int primaryContainer = 0xFF3F51B5;
    private int onPrimaryContainer = 0xFFE8EAF6;
    private int secondaryContainer = 0xFF47464F;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderTime();
            scheduleTick();
        }
    };

    StopwatchPanel(Context ctx, int buttonHeightPx) {
        super(ctx);
        buttonHeight = buttonHeightPx;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        time = new TextView(ctx);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        time.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        time.setFontFeatureSettings("tnum");
        time.setSingleLine(true);
        time.setIncludeFontPadding(false);
        LayoutParams tlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(12);
        tlp.rightMargin = dp(20);
        addView(time, tlp);

        resetButton = pillButton(ctx);
        resetIcon = pillIcon(ctx, R.drawable.ic_restart);
        resetLabel = pillLabel(ctx, "Reset");
        resetButton.addView(resetIcon, new LayoutParams(dp(18), dp(18)));
        resetButton.addView(resetLabel);
        resetButton.setOnClickListener(v -> reset());
        addView(resetButton, new LayoutParams(LayoutParams.WRAP_CONTENT, buttonHeightPx));

        startButton = pillButton(ctx);
        startIcon = pillIcon(ctx, R.drawable.ic_play);
        startLabel = pillLabel(ctx, "Start");
        startButton.addView(startIcon, new LayoutParams(dp(18), dp(18)));
        startButton.addView(startLabel);
        startButton.setOnClickListener(v -> toggle());
        LayoutParams slp = new LayoutParams(LayoutParams.WRAP_CONTENT, buttonHeightPx);
        slp.leftMargin = dp(8);
        addView(startButton, slp);

        // The study schedule: what the stopwatch has recorded, week by week.
        weekButton = new ImageView(ctx);
        weekButton.setImageResource(R.drawable.ic_calendar);
        weekButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        weekButton.setPadding(dp(11), dp(11), dp(11), dp(11));
        weekButton.setContentDescription("Study week");
        weekButton.setClickable(true);
        weekButton.setFocusable(true);
        weekButton.setOnClickListener(v -> {
            if (onWeek != null) onWeek.run();
        });
        LayoutParams wlp = new LayoutParams(buttonHeightPx, buttonHeightPx);
        wlp.leftMargin = dp(8);
        addView(weekButton, wlp);

        render();
    }

    void setOnChanged(Runnable r) {
        onChanged = r;
    }

    void setOnWeek(Runnable r) {
        onWeek = r;
    }

    void setColors(int onSurface, int primaryContainer, int onPrimaryContainer,
                   int secondaryContainer) {
        this.onSurface = onSurface;
        this.primaryContainer = primaryContainer;
        this.onPrimaryContainer = onPrimaryContainer;
        this.secondaryContainer = secondaryContainer;
        render();
    }

    boolean isRunning() {
        return runningSinceMs >= 0;
    }

    long elapsedMs() {
        long ms = accumulatedMs;
        if (isRunning()) ms += SystemClock.elapsedRealtime() - runningSinceMs;
        return ms;
    }

    void toggle() {
        if (isRunning()) pause();
        else start();
    }

    void start() {
        if (isRunning()) return;
        runningSinceMs = SystemClock.elapsedRealtime();
        render();
        scheduleTick();
        changed();
    }

    void pause() {
        if (!isRunning()) return;
        accumulatedMs += SystemClock.elapsedRealtime() - runningSinceMs;
        runningSinceMs = -1L;
        removeCallbacks(tick);
        render();
        changed();
    }

    /** App left the foreground. */
    void pauseForLeaving() {
        pause();
    }

    void reset() {
        removeCallbacks(tick);
        accumulatedMs = 0L;
        if (isRunning()) runningSinceMs = SystemClock.elapsedRealtime();
        render();
        scheduleTick();
        changed();
    }

    /** Keeps counting while hidden; only the redraws stop. */
    private void scheduleTick() {
        removeCallbacks(tick);
        if (isRunning() && isShown()) postDelayed(tick, TICK_MS - (elapsedMs() % TICK_MS));
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        renderTime();
        scheduleTick();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleTick();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void changed() {
        if (onChanged != null) onChanged.run();
    }

    private void renderTime() {
        long ms = elapsedMs();
        long h = ms / 3_600_000L;
        long m = (ms / 60_000L) % 60L;
        long s = (ms / 1000L) % 60L;
        time.setText(h > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", m, s));
    }

    private void render() {
        renderTime();
        boolean running = isRunning();
        long elapsed = elapsedMs();
        startIcon.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        startLabel.setText(running ? "Pause" : elapsed > 0 ? "Resume" : "Start");
        boolean canReset = elapsed > 0;
        resetButton.setEnabled(canReset);
        resetButton.setAlpha(canReset ? 1f : 0.38f);

        time.setTextColor(onSurface);
        resetButton.setBackground(pillBg(secondaryContainer, onSurface));
        resetIcon.setColorFilter(new PorterDuffColorFilter(onSurface, PorterDuff.Mode.SRC_IN));
        resetLabel.setTextColor(onSurface);
        startButton.setBackground(pillBg(primaryContainer, onPrimaryContainer));
        startIcon.setColorFilter(new PorterDuffColorFilter(onPrimaryContainer, PorterDuff.Mode.SRC_IN));
        startLabel.setTextColor(onPrimaryContainer);
        weekButton.setBackground(pillBg(secondaryContainer, onSurface));
        weekButton.setColorFilter(new PorterDuffColorFilter(onSurface, PorterDuff.Mode.SRC_IN));
    }

    private LinearLayout pillButton(Context ctx) {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(16), 0);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private ImageView pillIcon(Context ctx, int res) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(res);
        return iv;
    }

    private TextView pillLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setLetterSpacing(0.01f);
        tv.setPadding(dp(8), 0, 0, 0);
        return tv;
    }

    private RippleDrawable pillBg(int fill, int onColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(buttonHeight / 2f);
        bg.setColor(fill);
        return new RippleDrawable(
                ColorStateList.valueOf((onColor & 0x00FFFFFF) | 0x29000000), bg, null);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
