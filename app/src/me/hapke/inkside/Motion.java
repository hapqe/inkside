package me.hapke.inkside;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * The app's motion vocabulary, taken from the undo scrubber: Material 3 emphasized
 * easing, cards that land with a touch of overshoot rather than just appear, and a
 * quick accelerate-away exit. Everything that opens, closes, expands or is pressed
 * goes through here so the whole app moves the same way.
 */
final class Motion {
    private Motion() {}

    /** Material 3 emphasized decelerate — things arriving. */
    static final TimeInterpolator EMPHASIZED_DECELERATE =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** Material 3 emphasized accelerate — things leaving. */
    static final TimeInterpolator EMPHASIZED_ACCELERATE =
            new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    /** Material 3 standard — things changing in place. */
    static final TimeInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * Emphasized decelerate with a soft bump past the end: the scrubber card's
     * {@code 0.82 + 0.18e + 0.035 sin(πe)} scale curve, normalised so it works for
     * any start and end value.
     */
    static final TimeInterpolator LAND = t -> {
        float e = EMPHASIZED_DECELERATE.getInterpolation(t);
        return e + 0.19f * (float) Math.sin(Math.PI * e);
    };

    static final long ENTER_MS = 300L;
    static final long EXIT_MS = 170L;
    static final long CHANGE_MS = 260L;

    /**
     * A card arriving: fades in over the first ~45% while it scales up from 88%
     * (landing with a slight overshoot) and settles {@code riseDp} into place. The
     * pivot is where it grows from — the anchor it belongs to.
     */
    static void popIn(View v, float pivotX, float pivotY, float riseDp) {
        cancelFade(v);
        v.animate().cancel();
        v.setPivotX(pivotX);
        v.setPivotY(pivotY);
        v.setAlpha(0f);
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.setTranslationY(dp(v, riseDp));
        v.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(ENTER_MS).setInterpolator(LAND).start();
        // Alpha on its own clock: fully opaque well before the scale lands.
        ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(Math.round(ENTER_MS * 0.45f));
        fade.setInterpolator(STANDARD);
        fade.addUpdateListener(a -> v.setAlpha((float) a.getAnimatedValue()));
        v.setTag(R.id.motion_fade, fade);
        fade.start();
    }

    private static void cancelFade(View v) {
        Object prev = v.getTag(R.id.motion_fade);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        v.setTag(R.id.motion_fade, null);
    }

    /**
     * A card leaving: accelerates away, shrinking to 94% and fading, then runs
     * {@code end}. {@code invalidate} (may be null) is poked each frame — needed when
     * the view has already been removed from its parent and is only drawn as a
     * disappearing child.
     */
    static void popOut(View v, float dropDp, View invalidate, Runnable end) {
        cancelFade(v);
        v.animate().cancel();
        final float a0 = v.getAlpha();
        final float s0 = v.getScaleX();
        final float y0 = v.getTranslationY();
        final float dy = dp(v, dropDp);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(EXIT_MS);
        anim.setInterpolator(EMPHASIZED_ACCELERATE);
        anim.addUpdateListener(a -> {
            float e = (float) a.getAnimatedValue();
            v.setAlpha(a0 * (1f - e));
            float s = s0 + (0.94f - s0) * e;
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationY(y0 + dy * e);
            if (invalidate != null) invalidate.invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (end != null) end.run();
            }
        });
        anim.start();
    }

    /** Fades a scrim (or any plain view) out alongside a {@link #popOut}. */
    static void fadeOut(View v, View invalidate) {
        final float a0 = v.getAlpha();
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(EXIT_MS);
        anim.setInterpolator(STANDARD);
        anim.addUpdateListener(a -> {
            v.setAlpha(a0 * (1f - (float) a.getAnimatedValue()));
            if (invalidate != null) invalidate.invalidate();
        });
        anim.start();
    }

    /** Selection feedback: a quick swell and land, like the scrubber's label on each step. */
    static void pop(View v) {
        v.animate().cancel();
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(CHANGE_MS).setInterpolator(LAND).start();
    }

    /**
     * Children of {@code group} rise into place one after another — rows of a menu
     * or list settling in after their container.
     */
    static void stagger(ViewGroup group, long startDelayMs, float riseDp) {
        int shown = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            c.animate().cancel();
            c.setAlpha(0f);
            c.setTranslationY(dp(c, riseDp));
            c.animate().alpha(1f).translationY(0f)
                    .setStartDelay(startDelayMs + Math.min(shown, 10) * 22L)
                    .setDuration(240L).setInterpolator(EMPHASIZED_DECELERATE)
                    .withEndAction(() -> c.animate().setStartDelay(0))
                    .start();
            shown++;
        }
    }

    /** Reveals a collapsed (GONE) view by growing its height and fading its content in. */
    static void expand(View v) {
        if (v.getVisibility() == View.VISIBLE && v.getTag(R.id.motion_anim) == null) return;
        cancelSize(v);
        v.setVisibility(View.VISIBLE);
        ViewGroup parent = (ViewGroup) v.getParent();
        int wSpec = View.MeasureSpec.makeMeasureSpec(
                parent != null ? parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()
                        : 0,
                parent != null && parent.getWidth() > 0
                        ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED);
        v.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int target = v.getMeasuredHeight();
        int from = v.getHeight() > 0 && v.getLayoutParams().height >= 0 ? v.getHeight() : 0;
        animateHeight(v, from, target, true);
    }

    /** Hides a view by shrinking it to nothing, then sets it GONE. */
    static void collapse(View v) {
        if (v.getVisibility() == View.GONE) return;
        cancelSize(v);
        if (v.getHeight() == 0) {
            v.setVisibility(View.GONE);
            return;
        }
        animateHeight(v, v.getHeight(), 0, false);
    }

    private static void cancelSize(View v) {
        Object prev = v.getTag(R.id.motion_anim);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        v.setTag(R.id.motion_anim, null);
    }

    private static void animateHeight(View v, int from, int to, boolean opening) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = from;
        v.setLayoutParams(lp);
        v.setAlpha(opening ? 0f : v.getAlpha());
        ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(opening ? CHANGE_MS + 40 : CHANGE_MS - 40);
        anim.setInterpolator(opening ? EMPHASIZED_DECELERATE : EMPHASIZED_ACCELERATE);
        final float a0 = v.getAlpha();
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            float f = a.getAnimatedFraction();
            // Content fades in late on open (after room is made) and out early on close.
            v.setAlpha(opening ? Math.max(0f, (f - 0.3f) / 0.7f) : a0 * Math.max(0f, 1f - f * 1.6f));
            v.setLayoutParams(lp);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                v.setTag(R.id.motion_anim, null);
                if (cancelled) return;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
                v.setAlpha(1f);
                if (!opening) v.setVisibility(View.GONE);
            }
        });
        v.setTag(R.id.motion_anim, anim);
        anim.start();
    }

    /**
     * Press feedback for a small control: squeezes to {@code pressedScale} while held
     * and springs back (with a little overshoot) on release. Runs off the pressed
     * state, so it never touches the view's own touch handling.
     */
    static void addPressScale(View v, float pressedScale) {
        StateListAnimator sla = new StateListAnimator();
        ObjectAnimator down = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, pressedScale),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, pressedScale));
        down.setDuration(90);
        down.setInterpolator(STANDARD);
        ObjectAnimator up = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        up.setDuration(320);
        up.setInterpolator(LAND);
        sla.addState(new int[] {android.R.attr.state_pressed}, down);
        AnimatorSet idle = new AnimatorSet();
        idle.play(up);
        sla.addState(new int[0], idle);
        v.setStateListAnimator(sla);
    }

    private static float dp(View v, float dp) {
        return dp * v.getResources().getDisplayMetrics().density;
    }
}
