package me.hapke.inkside;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Presentation mode: the current page, whole, on a second screen (USB-C/HDMI display
 * or a cast screen). The host renders the slide and hands it over; this only shows it,
 * letterboxed on black.
 */
final class SlidePresentation extends Presentation {
    private SlideView view;

    SlidePresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new SlideView(getContext());
        setContentView(view);
        Window w = getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** Takes ownership of {@code slide}; the previous one is recycled. Null clears. */
    void setSlide(Bitmap slide) {
        if (view != null) view.setSlide(slide);
        else if (slide != null) slide.recycle();
    }

    /** Pixel size of the external screen, for rendering at its resolution. */
    int[] screenSize() {
        android.graphics.Point p = new android.graphics.Point();
        getDisplay().getRealSize(p);
        return new int[]{Math.max(1, p.x), Math.max(1, p.y)};
    }

    private static final class SlideView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private Bitmap slide;

        SlideView(Context ctx) {
            super(ctx);
            setBackgroundColor(Color.BLACK);
        }

        void setSlide(Bitmap next) {
            Bitmap old = slide;
            slide = next;
            invalidate();
            if (old != null && old != next) {
                // After this frame has stopped referencing it.
                post(() -> {
                    if (!old.isRecycled()) old.recycle();
                });
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap b = slide;
            if (b == null || b.isRecycled()) return;
            float vw = getWidth();
            float vh = getHeight();
            float s = Math.min(vw / b.getWidth(), vh / b.getHeight());
            float w = b.getWidth() * s;
            float h = b.getHeight() * s;
            dst.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
            canvas.drawBitmap(b, null, dst, paint);
        }
    }
}
