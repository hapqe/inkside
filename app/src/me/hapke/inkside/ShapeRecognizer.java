package me.hapke.inkside;

/**
 * Turns a hand-drawn stroke into a clean shape — line, arrow, ellipse/circle,
 * rectangle or triangle — and resizes that shape as the pen keeps moving.
 *
 * <p>Pure geometry on world coordinates; the canvas decides when to ask (the pen
 * held still at the end of a stroke) and turns the outline into stroke samples.
 */
final class ShapeRecognizer {
    enum Kind { LINE, ARROW, ELLIPSE, CIRCLE, RECT, TRIANGLE }

    /** A recognised shape. Immutable; {@link #resized} returns a new one. */
    static final class Shape {
        final Kind kind;
        // LINE / ARROW: start (ax, ay) to end or tip (bx, by).
        final float ax, ay, bx, by;
        /** ARROW: barb length. */
        final float head;
        // Closed shapes: centre, unit axis (ux, uy), half extents along it and its normal.
        final float cx, cy, ux, uy, hu, hv;
        /** TRIANGLE: vertices in units of (hu, hv) in the shape's frame: x0,y0,x1,y1,x2,y2. */
        final float[] tri;

        private Shape(Kind kind, float ax, float ay, float bx, float by, float head,
                      float cx, float cy, float ux, float uy, float hu, float hv, float[] tri) {
            this.kind = kind;
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
            this.head = head;
            this.cx = cx;
            this.cy = cy;
            this.ux = ux;
            this.uy = uy;
            this.hu = hu;
            this.hv = hv;
            this.tri = tri;
        }

        static Shape segment(Kind kind, float ax, float ay, float bx, float by, float head) {
            return new Shape(kind, ax, ay, bx, by, head, 0, 0, 1, 0, 0, 0, null);
        }

        static Shape closed(Kind kind, float cx, float cy, float ux, float uy,
                            float hu, float hv, float[] tri) {
            return new Shape(kind, 0, 0, 0, 0, 0, cx, cy, ux, uy, hu, hv, tri);
        }

        boolean isClosed() {
            return kind != Kind.LINE && kind != Kind.ARROW;
        }

        /** True for outlines with corners that should stay sharp. */
        boolean hasCorners() {
            return kind != Kind.ELLIPSE && kind != Kind.CIRCLE;
        }
    }

    /** Points the stroke is resampled to before it is judged. */
    private static final int N = 64;
    /** Lines and axes this close to a multiple of 45° (lines) or 90° (boxes) snap onto it. */
    private static final double LINE_SNAP_RAD = Math.toRadians(4);
    private static final double AXIS_SNAP_RAD = Math.toRadians(9);

    private ShapeRecognizer() {}

    /**
     * Best shape for the stroke, or null if it doesn't look like one.
     * {@code minSize} is the smallest extent worth snapping (world units).
     */
    static Shape recognize(float[] xs, float[] ys, int n, float minSize) {
        if (n < 4) return null;
        float[][] r = resample(xs, ys, n, N);
        if (r == null) return null;
        float[] px = r[0];
        float[] py = r[1];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            minX = Math.min(minX, px[i]);
            maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]);
            maxY = Math.max(maxY, py[i]);
        }
        float diag = (float) Math.hypot(maxX - minX, maxY - minY);
        if (diag < minSize) return null;
        float gap = (float) Math.hypot(px[N - 1] - px[0], py[N - 1] - py[0]);
        if (gap > 0.25f * diag) return recognizeOpen(px, py);
        return recognizeClosed(px, py, diag);
    }

    // ---- Open strokes ------------------------------------------------------------

    private static Shape recognizeOpen(float[] px, float[] py) {
        float ax = px[0], ay = py[0];
        if (maxDeviation(px, py, 0, N - 1) < 0.06f * dist(px, py, 0, N - 1)) {
            return snapLine(Kind.LINE, ax, ay, px[N - 1], py[N - 1], 0f);
        }
        // Arrow drawn in one go: a straight shaft to the point farthest from the start,
        // then a head scribbled around that tip.
        int tip = 0;
        float far = 0f;
        for (int i = 1; i < N; i++) {
            float d = dist(px, py, 0, i);
            if (d > far) {
                far = d;
                tip = i;
            }
        }
        // The pen returns to the tip between barbs; the shaft ends the first time it arrives.
        for (int i = 1; i < tip; i++) {
            if (dist(px, py, 0, i) > 0.96f * far) {
                while (i + 1 < tip && dist(px, py, 0, i + 1) >= dist(px, py, 0, i)) i++;
                tip = i;
                far = dist(px, py, 0, i);
                break;
            }
        }
        if (tip < N * 0.45f || tip > N - 4) return null;
        if (maxDeviation(px, py, 0, tip) > 0.08f * far) return null;
        float dirX = (px[tip] - ax) / far;
        float dirY = (py[tip] - ay) / far;
        float reach = 0f;
        int behind = 0;
        int counted = 0;
        for (int i = tip + 1; i < N; i++) {
            float dx = px[i] - px[tip];
            float dy = py[i] - py[tip];
            float d = (float) Math.hypot(dx, dy);
            if (d > 0.6f * far) return null;
            reach = Math.max(reach, d);
            // Points back at the tip (between barbs) say nothing about direction.
            if (d < 0.04f * far) continue;
            counted++;
            if (dx * dirX + dy * dirY < 0.2f * d) behind++;
        }
        if (reach < 0.08f * far || behind < counted * 0.7f) return null;
        float head = Math.max(0.12f * far, Math.min(0.4f * far, reach));
        return snapLine(Kind.ARROW, ax, ay, px[tip], py[tip], head);
    }

    private static Shape snapLine(Kind kind, float ax, float ay, float bx, float by, float head) {
        double ang = Math.atan2(by - ay, bx - ax);
        double step = Math.PI / 4;
        double snapped = Math.round(ang / step) * step;
        if (Math.abs(ang - snapped) < LINE_SNAP_RAD) {
            float len = (float) Math.hypot(bx - ax, by - ay);
            bx = ax + (float) Math.cos(snapped) * len;
            by = ay + (float) Math.sin(snapped) * len;
        }
        return Shape.segment(kind, ax, ay, bx, by, head);
    }

    // ---- Closed strokes ----------------------------------------------------------

    private static Shape recognizeClosed(float[] px, float[] py, float diag) {
        int[] corners = closedCorners(px, py, 0.07f * diag);
        int k = corners.length;
        float tol = 0.055f * diag;

        if (k == 4 || k == 5) {
            Shape rect = fitRect(px, py, corners);
            if (rect != null && meanOutlineDistance(px, py, rect) < tol) return rect;
        }
        if (k == 3) {
            Shape tri = fitTriangle(px, py, corners);
            if (meanOutlineDistance(px, py, tri) < tol) return tri;
        }
        Shape ell = fitEllipse(px, py);
        if (ell != null && meanOutlineDistance(px, py, ell) < 0.06f * diag) return ell;
        return null;
    }

    /** Indices of the corners of a closed loop (Douglas–Peucker, then straight joins dropped). */
    private static int[] closedCorners(float[] px, float[] py, float tol) {
        int far = 0;
        float best = 0;
        for (int i = 1; i < N; i++) {
            float d = dist(px, py, 0, i);
            if (d > best) {
                best = d;
                far = i;
            }
        }
        boolean[] keep = new boolean[N];
        keep[0] = true;
        keep[far] = true;
        douglasPeucker(px, py, 0, far, tol, keep);
        douglasPeucker(px, py, far, N - 1, tol, keep);
        int[] idx = new int[N];
        int m = 0;
        for (int i = 0; i < N - 1; i++) if (keep[i]) idx[m++] = i;
        // Drop vertices where the outline barely turns: they are not corners.
        boolean changed = true;
        while (changed && m > 3) {
            changed = false;
            for (int j = 0; j < m; j++) {
                int a = idx[(j + m - 1) % m];
                int b = idx[j];
                int c = idx[(j + 1) % m];
                double turn = turnAngle(px[a], py[a], px[b], py[b], px[c], py[c]);
                if (turn < Math.toRadians(30)) {
                    System.arraycopy(idx, j + 1, idx, j, m - j - 1);
                    m--;
                    changed = true;
                    break;
                }
            }
        }
        int[] out = new int[m];
        System.arraycopy(idx, 0, out, 0, m);
        return out;
    }

    private static void douglasPeucker(float[] px, float[] py, int a, int b, float tol,
                                       boolean[] keep) {
        if (b <= a + 1) return;
        float worst = -1;
        int at = -1;
        for (int i = a + 1; i < b; i++) {
            float d = segDistance(px[i], py[i], px[a], py[a], px[b], py[b]);
            if (d > worst) {
                worst = d;
                at = i;
            }
        }
        if (worst > tol) {
            keep[at] = true;
            douglasPeucker(px, py, a, at, tol, keep);
            douglasPeucker(px, py, at, b, tol, keep);
        }
    }

    private static Shape fitRect(float[] px, float[] py, int[] corners) {
        // Edge directions folded onto a quarter turn, averaged on the 4θ circle.
        double sx = 0, sy = 0;
        int k = corners.length;
        for (int j = 0; j < k; j++) {
            int a = corners[j];
            int b = corners[(j + 1) % k];
            double dx = px[b] - px[a];
            double dy = py[b] - py[a];
            double len = Math.hypot(dx, dy);
            double th = Math.atan2(dy, dx) * 4;
            sx += Math.cos(th) * len;
            sy += Math.sin(th) * len;
        }
        double ang = Math.atan2(sy, sx) / 4;
        double axis = Math.round(ang / (Math.PI / 2)) * (Math.PI / 2);
        if (Math.abs(ang - axis) < AXIS_SNAP_RAD) ang = axis;
        float ux = (float) Math.cos(ang);
        float uy = (float) Math.sin(ang);
        float[] box = frameBox(px, py, ux, uy);
        if (box[2] <= 0 || box[3] <= 0) return null;
        return Shape.closed(Kind.RECT, box[0], box[1], ux, uy, box[2], box[3], null);
    }

    private static Shape fitTriangle(float[] px, float[] py, int[] c) {
        float[] box = frameBox(px, py, 1f, 0f);
        float hu = Math.max(1e-3f, box[2]);
        float hv = Math.max(1e-3f, box[3]);
        float[] tri = new float[6];
        for (int j = 0; j < 3; j++) {
            tri[2 * j] = (px[c[j]] - box[0]) / hu;
            tri[2 * j + 1] = (py[c[j]] - box[1]) / hv;
        }
        return Shape.closed(Kind.TRIANGLE, box[0], box[1], 1f, 0f, hu, hv, tri);
    }

    private static Shape fitEllipse(float[] px, float[] py) {
        // Uniform arc-length samples of an ellipse have variance a²/2 along each axis.
        double mx = 0, my = 0;
        for (int i = 0; i < N - 1; i++) {
            mx += px[i];
            my += py[i];
        }
        mx /= N - 1;
        my /= N - 1;
        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < N - 1; i++) {
            double dx = px[i] - mx;
            double dy = py[i] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        sxx /= N - 1;
        syy /= N - 1;
        sxy /= N - 1;
        double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double tr = sxx + syy;
        double det = Math.sqrt(Math.max(0, (sxx - syy) * (sxx - syy) / 4 + sxy * sxy));
        double l1 = tr / 2 + det;
        double l2 = tr / 2 - det;
        if (l1 <= 0 || l2 <= 0) return null;
        double axis = Math.round(ang / (Math.PI / 2)) * (Math.PI / 2);
        if (Math.abs(ang - axis) < AXIS_SNAP_RAD) ang = axis;
        float ux = (float) Math.cos(ang);
        float uy = (float) Math.sin(ang);
        // PCA gives the axes; the drawn extent along them gives the size.
        float[] box = frameBox(px, py, ux, uy);
        float a = box[2];
        float b = box[3];
        if (a <= 0 || b <= 0) return null;
        if (Math.min(a, b) / Math.max(a, b) > 0.86f) {
            float rr = (a + b) / 2;
            return Shape.closed(Kind.CIRCLE, box[0], box[1], 1f, 0f, rr, rr, null);
        }
        return Shape.closed(Kind.ELLIPSE, box[0], box[1], ux, uy, a, b, null);
    }

    /** Centre and half extents of the points in the frame with unit axis (ux, uy). */
    private static float[] frameBox(float[] px, float[] py, float ux, float uy) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float lo2 = Float.MAX_VALUE, hi2 = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            float u = px[i] * ux + py[i] * uy;
            float v = -px[i] * uy + py[i] * ux;
            lo = Math.min(lo, u);
            hi = Math.max(hi, u);
            lo2 = Math.min(lo2, v);
            hi2 = Math.max(hi2, v);
        }
        float cu = (lo + hi) / 2;
        float cv = (lo2 + hi2) / 2;
        return new float[]{cu * ux - cv * uy, cu * uy + cv * ux, (hi - lo) / 2, (hi2 - lo2) / 2};
    }

    private static float meanOutlineDistance(float[] px, float[] py, Shape s) {
        float[] o = outline(s, 96);
        int m = o.length / 2;
        double sum = 0;
        for (int i = 0; i < N; i++) {
            float best = Float.MAX_VALUE;
            for (int j = 0; j < m - 1; j++) {
                best = Math.min(best, segDistance(px[i], py[i],
                        o[2 * j], o[2 * j + 1], o[2 * j + 2], o[2 * j + 3]));
            }
            sum += best;
        }
        return (float) (sum / N);
    }

    // ---- Resizing ----------------------------------------------------------------

    /**
     * The shape as the pen moves from ({@code hx}, {@code hy}), where it was when the
     * shape snapped, to ({@code x}, {@code y}). Lines and arrows swing and stretch
     * from their start; closed shapes grow or shrink about their centre, per axis
     * when the pen is off towards a corner, uniformly otherwise.
     */
    static Shape resized(Shape s, float hx, float hy, float x, float y) {
        if (!s.isClosed()) {
            float bx = s.bx + (x - hx);
            float by = s.by + (y - hy);
            float head = s.head;
            if (s.kind == Kind.ARROW) {
                float was = (float) Math.hypot(s.bx - s.ax, s.by - s.ay);
                float now = (float) Math.hypot(bx - s.ax, by - s.ay);
                if (was > 0) head = Math.min(0.45f * now, s.head * Math.max(0.5f, Math.min(2f, now / was)));
            }
            return snapLine(s.kind, s.ax, s.ay, bx, by, head);
        }
        float hu0 = (hx - s.cx) * s.ux + (hy - s.cy) * s.uy;
        float hv0 = -(hx - s.cx) * s.uy + (hy - s.cy) * s.ux;
        float pu = (x - s.cx) * s.ux + (y - s.cy) * s.uy;
        float pv = -(x - s.cx) * s.uy + (y - s.cy) * s.ux;
        float su;
        float sv;
        if (s.kind == Kind.CIRCLE) {
            float was = (float) Math.hypot(hu0, hv0);
            if (was < 1e-3f) return s;
            su = sv = (float) Math.hypot(pu, pv) / was;
        } else {
            boolean useU = Math.abs(hu0) > 0.35f * s.hu;
            boolean useV = Math.abs(hv0) > 0.35f * s.hv;
            if (!useU && !useV) return s;
            su = useU ? pu / hu0 : Float.NaN;
            sv = useV ? pv / hv0 : Float.NaN;
            if (!useU) su = sv;
            if (!useV) sv = su;
        }
        su = Math.max(0.05f, su);
        sv = Math.max(0.05f, sv);
        return Shape.closed(s.kind, s.cx, s.cy, s.ux, s.uy, s.hu * su, s.hv * sv, s.tri);
    }

    // ---- Outline -----------------------------------------------------------------

    /**
     * The shape's path as x,y pairs. Closed shapes repeat their first point at the
     * end; {@code curveSegments} sets how finely an ellipse is traced.
     */
    static float[] outline(Shape s, int curveSegments) {
        switch (s.kind) {
            case LINE:
                return new float[]{s.ax, s.ay, s.bx, s.by};
            case ARROW: {
                float len = (float) Math.hypot(s.bx - s.ax, s.by - s.ay);
                if (len < 1e-3f) return new float[]{s.ax, s.ay, s.bx, s.by};
                float dx = (s.bx - s.ax) / len;
                float dy = (s.by - s.ay) / len;
                double spread = Math.toRadians(28);
                float c = (float) Math.cos(spread);
                float sn = (float) Math.sin(spread);
                // Back along the shaft, turned either way.
                float l1x = -(dx * c - dy * sn), l1y = -(dx * sn + dy * c);
                float l2x = -(dx * c + dy * sn), l2y = -(-dx * sn + dy * c);
                return new float[]{
                        s.ax, s.ay, s.bx, s.by,
                        s.bx + l1x * s.head, s.by + l1y * s.head,
                        s.bx, s.by,
                        s.bx + l2x * s.head, s.by + l2y * s.head,
                };
            }
            case RECT:
                return polygon(s, new float[]{-1, -1, 1, -1, 1, 1, -1, 1});
            case TRIANGLE:
                return polygon(s, s.tri);
            default: {
                int m = Math.max(12, curveSegments);
                float[] out = new float[2 * (m + 1)];
                for (int i = 0; i <= m; i++) {
                    double t = 2 * Math.PI * i / m;
                    float u = (float) Math.cos(t) * s.hu;
                    float v = (float) Math.sin(t) * s.hv;
                    out[2 * i] = s.cx + u * s.ux - v * s.uy;
                    out[2 * i + 1] = s.cy + u * s.uy + v * s.ux;
                }
                return out;
            }
        }
    }

    private static float[] polygon(Shape s, float[] local) {
        int k = local.length / 2;
        float[] out = new float[2 * (k + 1)];
        for (int i = 0; i <= k; i++) {
            float u = local[2 * (i % k)] * s.hu;
            float v = local[2 * (i % k) + 1] * s.hv;
            out[2 * i] = s.cx + u * s.ux - v * s.uy;
            out[2 * i + 1] = s.cy + u * s.uy + v * s.ux;
        }
        return out;
    }

    // ---- Helpers -----------------------------------------------------------------

    /** {@code m} points evenly spaced along the polyline by arc length. */
    static float[][] resample(float[] xs, float[] ys, int n, int m) {
        float total = 0;
        for (int i = 1; i < n; i++) total += (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        if (total <= 1e-4f) return null;
        float[] ox = new float[m];
        float[] oy = new float[m];
        float step = total / (m - 1);
        ox[0] = xs[0];
        oy[0] = ys[0];
        int j = 1;
        int seg = 1;
        float segStart = 0;
        while (j < m && seg < n) {
            float len = (float) Math.hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1]);
            float want = j * step;
            if (want <= segStart + len || seg == n - 1) {
                float t = len > 0 ? Math.min(1f, (want - segStart) / len) : 1f;
                ox[j] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t;
                oy[j] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t;
                j++;
            } else {
                segStart += len;
                seg++;
            }
        }
        for (; j < m; j++) {
            ox[j] = xs[n - 1];
            oy[j] = ys[n - 1];
        }
        return new float[][]{ox, oy};
    }

    private static float dist(float[] px, float[] py, int a, int b) {
        return (float) Math.hypot(px[b] - px[a], py[b] - py[a]);
    }

    private static float maxDeviation(float[] px, float[] py, int a, int b) {
        float worst = 0;
        for (int i = a + 1; i < b; i++) {
            worst = Math.max(worst, segDistance(px[i], py[i], px[a], py[a], px[b], py[b]));
        }
        return worst;
    }

    private static float segDistance(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float len2 = dx * dx + dy * dy;
        float t = len2 > 0 ? ((x - ax) * dx + (y - ay) * dy) / len2 : 0;
        t = Math.max(0, Math.min(1, t));
        return (float) Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
    }

    /** How sharply the path turns at b, 0 for straight on. */
    private static double turnAngle(float ax, float ay, float bx, float by, float cx, float cy) {
        double a1 = Math.atan2(by - ay, bx - ax);
        double a2 = Math.atan2(cy - by, cx - bx);
        double d = Math.abs(a2 - a1);
        if (d > Math.PI) d = 2 * Math.PI - d;
        return d;
    }
}
