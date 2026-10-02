package me.hapke.inkside;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A small page drawn the way {@link DocumentPages} draws a real one — paper colour
 * and ruling (lines, grid, dots) at a given spacing — for the page style dialog's
 * live preview and its ruling tiles.
 */
final class PagePreviewView extends View {
    private final Paint paperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF page = new RectF();
    private final float density;

    private int paper = 0xFFFFFFFF;
    private String style = DocumentPages.STYLE_BLANK;
    private float ruleScale = 1f;
    /** Page units shown across the view: a real page is ~595 wide; tiles zoom in. */
    private float unitsAcross = PdfDocumentIo.A4_WIDTH_PT;
    private float cornerDp = 10f;
    private int edgeColor = 0x33000000;

    PagePreviewView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(density);
    }

    void setLook(int paper, String style, float ruleScale) {
        this.paper = paper | 0xFF000000;
        this.style = style != null ? style : DocumentPages.STYLE_BLANK;
        this.ruleScale = ruleScale;
        invalidate();
    }

    void setUnitsAcross(float units) {
        unitsAcross = units;
        invalidate();
    }

    void setCornerDp(float dp) {
        cornerDp = dp;
        invalidate();
    }

    void setEdgeColor(int color) {
        edgeColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        page.set(0, 0, getWidth(), getHeight());
        float r = cornerDp * density;
        paperPaint.setColor(paper);
        c.drawRoundRect(page, r, r, paperPaint);

        if (!DocumentPages.STYLE_BLANK.equals(style)) {
            float k = getWidth() / unitsAcross;
            float step = 24f * ruleScale * k;
            rulePaint.setColor(DocumentPages.ruleColor(paper));
            // Rules a touch stronger than on the page: the preview is much smaller.
            rulePaint.setAlpha(Math.min(255, Math.round(rulePaint.getAlpha() * 1.8f)));
            c.save();
            c.clipRect(page.left + r * 0.3f, page.top + r * 0.3f,
                    page.right - r * 0.3f, page.bottom - r * 0.3f);
            if (DocumentPages.STYLE_DOTS.equals(style)) {
                rulePaint.setStyle(Paint.Style.FILL);
                float dot = Math.max(0.9f * density, 1.1f * k);
                for (float y = step; y < page.bottom; y += step) {
                    for (float x = step; x < page.right; x += step) c.drawCircle(x, y, dot, rulePaint);
                }
            } else {
                rulePaint.setStyle(Paint.Style.STROKE);
                rulePaint.setStrokeWidth(Math.max(0.8f * density, 0.8f * k));
                float lineStep = DocumentPages.STYLE_LINES.equals(style) ? 28f * ruleScale * k : step;
                for (float y = lineStep; y < page.bottom; y += lineStep) {
                    c.drawLine(page.left, y, page.right, y, rulePaint);
                }
                if (DocumentPages.STYLE_GRID.equals(style)) {
                    for (float x = step; x < page.right; x += step) {
                        c.drawLine(x, page.top, x, page.bottom, rulePaint);
                    }
                }
            }
            c.restore();
        }

        edgePaint.setColor(edgeColor);
        float h = edgePaint.getStrokeWidth() / 2f;
        page.inset(h, h);
        c.drawRoundRect(page, r, r, edgePaint);
    }
}
