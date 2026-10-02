package me.hapke.inkside;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * One week of studying drawn as a schedule: Monday to Sunday across, the hours of the
 * day down, and a block wherever the stopwatch was running. Blocks are coloured by the
 * folder of the document that was open, today's column is lifted, the current time is a
 * line across it, and each day's header carries its total with a small bar against the
 * week's busiest day.
 */
final class StudyWeekView extends View {
    interface Host {
        /** A block was tapped (or, with null, the empty space: clear the selection). */
        void onSessionTapped(StudyLog.Session session);

        /** A horizontal swipe: -1 towards earlier weeks, +1 towards later ones. */
        void onSwipeWeek(int direction);
    }

    private static final String[] DAY_NAMES = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
    private static final int DEFAULT_FROM_HOUR = 7;
    private static final int DEFAULT_TO_HOUR = 22;
    private static final int MIN_SPAN_HOURS = 10;

    /** A session (or the part of it that falls on one day) as a rectangle. */
    private static final class Block {
        final StudyLog.Session session;
        final int day;
        final float fromMin;
        final float toMin;
        final RectF rect = new RectF();

        Block(StudyLog.Session session, int day, float fromMin, float toMin) {
            this.session = session;
            this.day = day;
            this.fromMin = fromMin;
            this.toMin = toMin;
        }
    }

    private final Host host;
    private final float den;
    private final GestureDetector gestures;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private int surface = 0xFF1C1B22;
    private int onSurface = 0xFFE6E1E9;
    private int onSurfaceVariant = 0xFFCAC4D0;
    private int primary = 0xFFBAC3FF;
    private int primaryContainer = 0xFF3F51B5;
    private int onPrimaryContainer = 0xFFE8EAF6;
    private int outline = 0xFF49454F;
    private boolean light;

    private long weekStart;
    private boolean currentWeek;
    private final long[] dayStart = new long[8];
    private final long[] dayMs = new long[7];
    private final List<Block> blocks = new ArrayList<>();
    private int fromHour = DEFAULT_FROM_HOUR;
    private int toHour = DEFAULT_TO_HOUR;
    private StudyLog.Session selected;
    private float reveal = 1f;
    private ValueAnimator revealAnim;

    StudyWeekView(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        this.den = ctx.getResources().getDisplayMetrics().density;
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                StudyLog.Session hit = null;
                for (int i = blocks.size() - 1; i >= 0; i--) {
                    if (blocks.get(i).rect.contains(e.getX(), e.getY())) {
                        hit = blocks.get(i).session;
                        break;
                    }
                }
                StudyWeekView.this.host.onSessionTapped(hit);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > dp(500)) {
                    StudyWeekView.this.host.onSwipeWeek(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    private float dp(float v) {
        return v * den;
    }

    void setColors(int surface, int onSurface, int onSurfaceVariant, int primary,
                   int primaryContainer, int onPrimaryContainer, int outline, boolean light) {
        this.surface = surface | 0xFF000000;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.primary = primary;
        this.primaryContainer = primaryContainer;
        this.onPrimaryContainer = onPrimaryContainer;
        this.outline = outline;
        this.light = light;
        invalidate();
    }

    /**
     * Shows the week starting at {@code weekStartMs} (a Monday, midnight).
     *
     * @param animate grow the blocks in; the first show and every week change do
     */
    void setWeek(long weekStartMs, List<StudyLog.Session> list, boolean isCurrentWeek, boolean animate) {
        weekStart = weekStartMs;
        currentWeek = isCurrentWeek;
        selected = null;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(weekStartMs);
        for (int i = 0; i <= 7; i++) {
            dayStart[i] = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        blocks.clear();
        for (int i = 0; i < 7; i++) dayMs[i] = 0;
        int lo = 24 * 60, hi = 0;
        for (StudyLog.Session s : list) {
            for (int d = 0; d < 7; d++) {
                long from = Math.max(s.start, dayStart[d]);
                long to = Math.min(s.end, dayStart[d + 1]);
                if (to <= from) continue;
                dayMs[d] += to - from;
                float fm = (from - dayStart[d]) / 60000f;
                float tm = (to - dayStart[d]) / 60000f;
                blocks.add(new Block(s, d, fm, tm));
                lo = Math.min(lo, (int) fm);
                hi = Math.max(hi, (int) Math.ceil(tm));
            }
        }
        fromHour = DEFAULT_FROM_HOUR;
        toHour = DEFAULT_TO_HOUR;
        if (!blocks.isEmpty()) {
            fromHour = Math.max(0, Math.min(fromHour, lo / 60));
            toHour = Math.min(24, Math.max(toHour, (hi + 59) / 60));
        }
        if (toHour - fromHour < MIN_SPAN_HOURS) toHour = Math.min(24, fromHour + MIN_SPAN_HOURS);
        if (animate) startReveal();
        else reveal = 1f;
        invalidate();
    }

    long dayMillis(int day) {
        return dayMs[day];
    }

    private void startReveal() {
        if (revealAnim != null) revealAnim.cancel();
        reveal = 0f;
        revealAnim = ValueAnimator.ofFloat(0f, 1f);
        revealAnim.setDuration(520);
        revealAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        revealAnim.addUpdateListener(a -> {
            reveal = (float) a.getAnimatedValue();
            invalidate();
        });
        revealAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (revealAnim != null) revealAnim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // ---- drawing ---------------------------------------------------------------------

    private static int mix(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    private static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0f, Math.min(1f, a))) << 24);
    }

    /** Blocks are coloured by the folder (project) of their document. */
    static String folderOf(String doc) {
        int slash = doc == null ? -1 : doc.lastIndexOf('/');
        return slash > 0 ? doc.substring(0, slash) : "";
    }

    static String titleOf(String doc) {
        if (doc == null || doc.isEmpty()) return "Studying";
        String name = doc.substring(doc.lastIndexOf('/') + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        return name.replace('_', ' ');
    }

    int blockColor(String doc) {
        String folder = folderOf(doc);
        if (folder.isEmpty()) return primary;
        float[] hsv = new float[3];
        Color.colorToHSV(primary, hsv);
        hsv[0] = (hsv[0] + (Math.abs(folder.hashCode()) % 7) * 51f + 25f) % 360f;
        hsv[1] = Math.max(0.5f, Math.min(0.85f, hsv[1] < 0.15f ? 0.6f : hsv[1]));
        hsv[2] = light ? 0.72f : 0.93f;
        return Color.HSVToColor(hsv);
    }

    static String formatDuration(long ms) {
        long min = Math.round(ms / 60000.0);
        if (min <= 0) return "–";
        long h = min / 60, m = min % 60;
        if (h == 0) return m + " min";
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        final float gutter = dp(54), padRight = dp(6), headerH = dp(66), padBottom = dp(10);
        final float bodyTop = headerH, bodyBottom = h - padBottom;
        final float colW = (w - gutter - padRight) / 7f;
        final int span = Math.max(1, toHour - fromHour);
        final float hourH = (bodyBottom - bodyTop) / span;
        final Calendar now = Calendar.getInstance();
        final long nowMs = now.getTimeInMillis();
        int todayCol = -1;
        if (currentWeek) {
            for (int d = 0; d < 7; d++) if (nowMs >= dayStart[d] && nowMs < dayStart[d + 1]) todayCol = d;
        }

        // Today's column: a soft lifted lane behind header and body.
        if (todayCol >= 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alpha(primary, light ? 0.10f : 0.13f));
            tmp.set(gutter + todayCol * colW + dp(3), dp(4), gutter + (todayCol + 1) * colW - dp(3), bodyBottom + dp(4));
            canvas.drawRoundRect(tmp, dp(18), dp(18), paint);
        }

        // Hour lines and labels.
        text.setTextSize(dp(11));
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setColor(onSurfaceVariant);
        text.setTextAlign(Paint.Align.RIGHT);
        paint.setStrokeWidth(Math.max(1f, dp(0.8f)));
        for (int hr = fromHour; hr <= toHour; hr++) {
            float y = bodyTop + (hr - fromHour) * hourH;
            paint.setStyle(Paint.Style.STROKE);
            paint.setPathEffect(null);
            paint.setColor(alpha(outline, 0.55f));
            canvas.drawLine(gutter, y, w - padRight, y, paint);
            if (hr < toHour) {
                canvas.drawText(String.format(Locale.ROOT, "%02d:00", hr % 24), gutter - dp(10), y + dp(4), text);
            }
        }
        // Day separators.
        paint.setColor(alpha(outline, 0.30f));
        for (int d = 1; d < 7; d++) {
            float x = gutter + d * colW;
            canvas.drawLine(x, bodyTop, x, bodyBottom, paint);
        }

        drawHeader(canvas, gutter, colW, headerH, todayCol);
        drawBlocks(canvas, gutter, colW, bodyTop, hourH);

        // The current time.
        if (todayCol >= 0) {
            float minutes = (nowMs - dayStart[todayCol]) / 60000f;
            float y = bodyTop + (minutes / 60f - fromHour) * hourH;
            if (y >= bodyTop && y <= bodyBottom) {
                float x0 = gutter + todayCol * colW;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setColor(0xFFF2555E);
                canvas.drawLine(x0, y, x0 + colW, y, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x0, y, dp(4.5f), paint);
            }
        }

        if (blocks.isEmpty()) drawEmpty(canvas, gutter, w - padRight, bodyTop, bodyBottom);
    }

    private void drawHeader(Canvas canvas, float gutter, float colW, float headerH, int todayCol) {
        Calendar c = Calendar.getInstance();
        for (int d = 0; d < 7; d++) {
            float cx = gutter + d * colW + colW / 2f;
            boolean today = d == todayCol;
            c.setTimeInMillis(dayStart[d]);

            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(dp(11));
            text.setLetterSpacing(0.1f);
            text.setColor(today ? primary : onSurfaceVariant);
            canvas.drawText(DAY_NAMES[d], cx, dp(26), text);
            text.setLetterSpacing(0f);

            // Date number: a filled disc for today.
            float cy = dp(50);
            if (today) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(primaryContainer);
                canvas.drawCircle(cx, cy - dp(1), dp(17), paint);
            }
            text.setTextSize(dp(17));
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setColor(today ? onPrimaryContainer : onSurface);
            canvas.drawText(String.valueOf(c.get(Calendar.DAY_OF_MONTH)), cx, cy + dp(5), text);

        }
    }

    private void drawBlocks(Canvas canvas, float gutter, float colW, float bodyTop, float hourH) {
        int order = 0;
        for (Block b : blocks) {
            float x0 = gutter + b.day * colW + dp(5);
            float x1 = gutter + (b.day + 1) * colW - dp(5);
            float yTop = bodyTop + (b.fromMin / 60f - fromHour) * hourH;
            float yBot = bodyTop + (b.toMin / 60f - fromHour) * hourH;
            if (yBot - yTop < dp(7)) yBot = yTop + dp(7);
            b.rect.set(x0, yTop, x1, yBot);

            // Blocks grow down from their top edge, each a little after the one before.
            float t = Math.max(0f, Math.min(1f, reveal * 1.5f - order * 0.03f));
            order++;
            if (t <= 0f) continue;
            tmp.set(x0, yTop, x1, yTop + (yBot - yTop) * t);

            int base = blockColor(b.session.doc);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(base);
            float radius = Math.min(dp(8), tmp.height() / 2f);
            canvas.drawRoundRect(tmp, radius, radius, paint);
        }
    }

    static String clock(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private void drawEmpty(Canvas canvas, float left, float right, float top, float bottom) {
        float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(dp(16));
        text.setColor(alpha(onSurface, 0.85f));
        canvas.drawText(currentWeek ? "Nothing studied yet this week" : "Nothing studied this week", cx, cy - dp(4), text);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(dp(13));
        text.setColor(alpha(onSurfaceVariant, 0.9f));
        canvas.drawText("Start the stopwatch while you work and the time shows up here.", cx, cy + dp(18), text);
    }
}
