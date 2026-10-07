package me.hapke.inkside;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * The shape tool's ready-made shapes, each drawn in the unit square (0,0)–(1,1). The
 * canvas maps that square onto the box the pen dragged out — start corner to end corner,
 * so arrows and lines point the way the pen went — and onto whatever the selection
 * later turns, scales or mirrors it into.
 */
final class ShapeLibrary {
    private ShapeLibrary() {}

    static final String[] NAMES = {
            "Rectangle", "Rounded rectangle", "Ellipse", "Triangle", "Right triangle",
            "Diamond", "Parallelogram", "Trapezoid", "Pentagon", "Hexagon", "Octagon",
            "Star", "Six-point star", "Arrow", "Double arrow", "Chevron", "Line",
            "Line with arrow", "Heart", "Speech bubble", "Cloud", "Plus", "Cross",
            "Lightning", "Moon", "Ring", "Cylinder", "Cube", "Check mark", "Wave",
    };

    static final int RECTANGLE = 0, ROUNDED = 1, ELLIPSE = 2, TRIANGLE = 3, RIGHT_TRIANGLE = 4,
            DIAMOND = 5, PARALLELOGRAM = 6, TRAPEZOID = 7, PENTAGON = 8, HEXAGON = 9,
            OCTAGON = 10, STAR = 11, STAR6 = 12, ARROW = 13, DOUBLE_ARROW = 14, CHEVRON = 15,
            LINE = 16, LINE_ARROW = 17, HEART = 18, BUBBLE = 19, CLOUD = 20, PLUS = 21,
            CROSS = 22, LIGHTNING = 23, MOON = 24, RING = 25, CYLINDER = 26, CUBE = 27,
            CHECK = 28, WAVE = 29;

    static int count() {
        return NAMES.length;
    }

    static int clamp(int kind) {
        return kind < 0 || kind >= NAMES.length ? RECTANGLE : kind;
    }

    /** Open shapes (lines, ticks) are only ever stroked, never filled. */
    static boolean isOpen(int kind) {
        return kind == LINE || kind == LINE_ARROW || kind == CHECK || kind == WAVE;
    }

    /** The shape in the unit square. A new Path each call; callers transform it. */
    static Path unitPath(int kind) {
        Path p = new Path();
        switch (clamp(kind)) {
            case RECTANGLE:
                p.addRect(0f, 0f, 1f, 1f, Path.Direction.CW);
                break;
            case ROUNDED:
                p.addRoundRect(new RectF(0f, 0f, 1f, 1f), 0.18f, 0.18f, Path.Direction.CW);
                break;
            case ELLIPSE:
                p.addOval(new RectF(0f, 0f, 1f, 1f), Path.Direction.CW);
                break;
            case TRIANGLE:
                poly(p, 0.5f, 0f, 1f, 1f, 0f, 1f);
                break;
            case RIGHT_TRIANGLE:
                poly(p, 0f, 0f, 1f, 1f, 0f, 1f);
                break;
            case DIAMOND:
                poly(p, 0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f);
                break;
            case PARALLELOGRAM:
                poly(p, 0.25f, 0f, 1f, 0f, 0.75f, 1f, 0f, 1f);
                break;
            case TRAPEZOID:
                poly(p, 0.22f, 0f, 0.78f, 0f, 1f, 1f, 0f, 1f);
                break;
            case PENTAGON:
                regular(p, 5, 1f, 0f);
                break;
            case HEXAGON:
                regular(p, 6, 1f, 0f);
                break;
            case OCTAGON:
                regular(p, 8, 1f, (float) (Math.PI / 8));
                break;
            case STAR:
                star(p, 5, 0.42f);
                break;
            case STAR6:
                star(p, 6, 0.55f);
                break;
            case ARROW:
                poly(p, 0f, 0.3f, 0.62f, 0.3f, 0.62f, 0f, 1f, 0.5f, 0.62f, 1f, 0.62f, 0.7f, 0f, 0.7f);
                break;
            case DOUBLE_ARROW:
                poly(p, 0f, 0.5f, 0.3f, 0f, 0.3f, 0.3f, 0.7f, 0.3f, 0.7f, 0f, 1f, 0.5f,
                        0.7f, 1f, 0.7f, 0.7f, 0.3f, 0.7f, 0.3f, 1f);
                break;
            case CHEVRON:
                poly(p, 0f, 0f, 0.6f, 0f, 1f, 0.5f, 0.6f, 1f, 0f, 1f, 0.4f, 0.5f);
                break;
            case LINE:
                p.moveTo(0f, 0f);
                p.lineTo(1f, 1f);
                break;
            case LINE_ARROW: {
                p.moveTo(0f, 0f);
                p.lineTo(1f, 1f);
                // The head, in the line's own direction.
                p.moveTo(0.78f, 0.92f);
                p.lineTo(1f, 1f);
                p.lineTo(0.92f, 0.78f);
                break;
            }
            case HEART:
                p.moveTo(0.5f, 0.28f);
                p.cubicTo(0.5f, 0.05f, 0.1f, -0.05f, 0.03f, 0.28f);
                p.cubicTo(-0.02f, 0.55f, 0.3f, 0.75f, 0.5f, 1f);
                p.cubicTo(0.7f, 0.75f, 1.02f, 0.55f, 0.97f, 0.28f);
                p.cubicTo(0.9f, -0.05f, 0.5f, 0.05f, 0.5f, 0.28f);
                p.close();
                break;
            case BUBBLE:
                p.addRoundRect(new RectF(0f, 0f, 1f, 0.75f), 0.14f, 0.14f, Path.Direction.CW);
                Path tail = new Path();
                poly(tail, 0.2f, 0.7f, 0.42f, 0.7f, 0.12f, 1f);
                p.op(tail, Path.Op.UNION);
                break;
            case CLOUD: {
                // Three puffs on a flat base, merged into one outline so the border has
                // no seams inside.
                Path a = new Path();
                a.addCircle(0.3f, 0.6f, 0.24f, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(0.52f, 0.38f, 0.3f, Path.Direction.CW);
                Path d = new Path();
                d.addCircle(0.76f, 0.58f, 0.23f, Path.Direction.CW);
                Path base = new Path();
                base.addRect(0.3f, 0.6f, 0.76f, 0.83f, Path.Direction.CW);
                p.op(a, b, Path.Op.UNION);
                p.op(d, Path.Op.UNION);
                p.op(base, Path.Op.UNION);
                break;
            }
            case PLUS:
                poly(p, 0.35f, 0f, 0.65f, 0f, 0.65f, 0.35f, 1f, 0.35f, 1f, 0.65f, 0.65f, 0.65f,
                        0.65f, 1f, 0.35f, 1f, 0.35f, 0.65f, 0f, 0.65f, 0f, 0.35f, 0.35f, 0.35f);
                break;
            case CROSS:
                poly(p, 0.15f, 0f, 0.5f, 0.35f, 0.85f, 0f, 1f, 0.15f, 0.65f, 0.5f, 1f, 0.85f,
                        0.85f, 1f, 0.5f, 0.65f, 0.15f, 1f, 0f, 0.85f, 0.35f, 0.5f, 0f, 0.15f);
                break;
            case LIGHTNING:
                poly(p, 0.55f, 0f, 0.15f, 0.55f, 0.45f, 0.55f, 0.3f, 1f, 0.85f, 0.4f, 0.55f, 0.4f, 0.75f, 0f);
                break;
            case MOON: {
                Path outer = new Path();
                outer.addCircle(0.5f, 0.5f, 0.5f, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(0.72f, 0.38f, 0.42f, Path.Direction.CW);
                p.op(outer, cut, Path.Op.DIFFERENCE);
                break;
            }
            case RING:
                p.setFillType(Path.FillType.EVEN_ODD);
                p.addOval(new RectF(0f, 0f, 1f, 1f), Path.Direction.CW);
                p.addOval(new RectF(0.25f, 0.25f, 0.75f, 0.75f), Path.Direction.CW);
                break;
            case CYLINDER: {
                // The silhouette: the body with a round top and bottom. The rim of the
                // top is a detail line (see detailPath), so the fill is never cut by it.
                Path body = new Path();
                body.moveTo(0f, 0.12f);
                body.lineTo(0f, 0.88f);
                body.arcTo(new RectF(0f, 0.76f, 1f, 1f), 180f, -180f, false);
                body.lineTo(1f, 0.12f);
                body.arcTo(new RectF(0f, 0f, 1f, 0.24f), 0f, -180f, false);
                body.close();
                p.set(body);
                break;
            }
            case CUBE:
                // The silhouette only; the front face's edges are detail lines.
                poly(p, 0f, 0.25f, 0.25f, 0f, 1f, 0f, 1f, 0.75f, 0.75f, 1f, 0f, 1f);
                break;
            case CHECK:
                p.moveTo(0f, 0.55f);
                p.lineTo(0.35f, 0.9f);
                p.lineTo(1f, 0.1f);
                break;
            case WAVE:
                p.moveTo(0f, 0.5f);
                p.cubicTo(0.12f, 0f, 0.25f, 0f, 0.33f, 0.5f);
                p.cubicTo(0.42f, 1f, 0.58f, 1f, 0.67f, 0.5f);
                p.cubicTo(0.75f, 0f, 0.88f, 0f, 1f, 0.5f);
                break;
            default:
                p.addRect(0f, 0f, 1f, 1f, Path.Direction.CW);
        }
        return p;
    }

    /**
     * Lines drawn inside a shape's outline (a cylinder's top rim, a cube's front edges):
     * stroked with the border, never filled. Null for shapes without any.
     */
    static Path detailPath(int kind) {
        switch (clamp(kind)) {
            case CYLINDER: {
                Path d = new Path();
                // The front half of the top ellipse.
                d.addArc(new RectF(0f, 0f, 1f, 0.24f), 0f, 180f);
                return d;
            }
            case CUBE: {
                Path d = new Path();
                d.moveTo(0f, 0.25f);
                d.lineTo(0.75f, 0.25f);
                d.lineTo(1f, 0f);
                d.moveTo(0.75f, 0.25f);
                d.lineTo(0.75f, 1f);
                return d;
            }
            default:
                return null;
        }
    }

    private static void poly(Path p, float... xy) {
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.close();
    }

    /** A regular polygon filling the unit square, one corner straight up (plus {@code turn}). */
    private static void regular(Path p, int n, float scale, float turn) {
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + turn + i * 2 * Math.PI / n;
            float x = 0.5f + 0.5f * scale * (float) Math.cos(a);
            float y = 0.5f + 0.5f * scale * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
    }

    private static void star(Path p, int points, float inner) {
        for (int i = 0; i < points * 2; i++) {
            double a = -Math.PI / 2 + i * Math.PI / points;
            float r = (i % 2 == 0 ? 0.5f : 0.5f * inner);
            float x = 0.5f + r * (float) Math.cos(a);
            float y = 0.5f + r * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
    }
}
