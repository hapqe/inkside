package me.hapke.inkside;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A {@link ShapeLibrary} shape as an icon: outlined, optionally filled. */
final class ShapeIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int kind;
    private final int stroke;
    private final int fill;
    private final float strokeWidth;

    ShapeIconDrawable(int kind, int stroke, int fill, float strokeWidth) {
        this.kind = kind;
        this.stroke = stroke;
        this.fill = fill;
        this.strokeWidth = strokeWidth;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float inset = strokeWidth + 1f;
        float w = b.width() - 2 * inset, h = b.height() - 2 * inset;
        if (w <= 0 || h <= 0) return;
        path.set(ShapeLibrary.unitPath(kind));
        Matrix m = new Matrix();
        m.setScale(w, h);
        m.postTranslate(b.left + inset, b.top + inset);
        path.transform(m);
        if (fill != 0 && !ShapeLibrary.isOpen(kind)) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(stroke);
        c.drawPath(path, paint);
        Path detail = ShapeLibrary.detailPath(kind);
        if (detail != null) {
            detail.transform(m);
            c.drawPath(detail, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
