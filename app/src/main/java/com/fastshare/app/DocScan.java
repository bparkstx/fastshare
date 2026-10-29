package com.fastshare.app;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * The picture work behind the document scanner: find the page in a photo, straighten it, and make it look like a scan.
 * Works on plain int arrays (ARGB), so it has no Android imports and is tested with made-up photos.
 */
public final class DocScan {
    private DocScan() { }

    /** A picture as 0xAARRGGBB pixels. */
    public static final class Img {
        public final int w;
        public final int h;
        public final int[] px;

        public Img(int w, int h, int[] px) {
            if (px.length != w * h) throw new IllegalArgumentException("pixel count");
            this.w = w;
            this.h = h;
            this.px = px;
        }
    }

    /** Four corners in pixel coordinates: top-left, top-right, bottom-right, bottom-left. */
    public static final class Quad {
        public final double[] x = new double[4];
        public final double[] y = new double[4];

        public Quad(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
            x[0] = x0; y[0] = y0; x[1] = x1; y[1] = y1; x[2] = x2; y[2] = y2; x[3] = x3; y[3] = y3;
        }

        public double area() {
            double a = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                a += x[i] * y[j] - x[j] * y[i];
            }
            return Math.abs(a) / 2;
        }

        /** True when the corners go round in order and none of them are on top of each other. */
        public boolean isSane(int w, int h) {
            for (int i = 0; i < 4; i++) {
                if (x[i] < -w * 0.2 || x[i] > w * 1.2 || y[i] < -h * 0.2 || y[i] > h * 1.2) return false;
            }
            double cross = 0;
            int signs = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4, k = (i + 2) % 4;
                cross = (x[j] - x[i]) * (y[k] - y[j]) - (y[j] - y[i]) * (x[k] - x[j]);
                if (cross > 0) signs++;
            }
            return (signs == 4 || signs == 0) && area() > w * h * 0.02;
        }

        public Quad scaled(double sx, double sy) {
            return new Quad(x[0] * sx, y[0] * sy, x[1] * sx, y[1] * sy, x[2] * sx, y[2] * sy, x[3] * sx, y[3] * sy);
        }

        /** The whole picture, with a small margin. */
        public static Quad whole(int w, int h) {
            double mx = w * 0.03, my = h * 0.03;
            return new Quad(mx, my, w - mx, my, w - mx, h - my, mx, h - my);
        }
    }

    // ------------------------------------------------------------------ small helpers

    static int luma(int argb) {
        return (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
    }

    static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** Shrinks by averaging. Returns the picture itself if it's already small enough. */
    public static Img fit(Img src, int maxSide) {
        int big = Math.max(src.w, src.h);
        if (big <= maxSide) return src;
        double s = (double) maxSide / big;
        int w = Math.max(1, (int) Math.round(src.w * s));
        int h = Math.max(1, (int) Math.round(src.h * s));
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = (int) ((long) y * src.h / h);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * src.h / h));
            for (int x = 0; x < w; x++) {
                int x0 = (int) ((long) x * src.w / w);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * src.w / w));
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int c = src.px[yy * src.w + xx];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                out[y * w + x] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return new Img(w, h, out);
    }

    static int otsu(int[] luma) {
        int[] hist = new int[256];
        for (int v : luma) hist[v]++;
        long total = luma.length;
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += i * (double) hist[i];
        double sumB = 0, best = -1;
        long wB = 0;
        int threshold = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            long wF = total - wB;
            if (wF == 0) break;
            sumB += t * (double) hist[t];
            double mB = sumB / wB, mF = (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                threshold = t;
            }
        }
        return threshold;
    }

    // ------------------------------------------------------------------ finding the page

    /**
     * Looks for a page: the big, roughly even area in the middle of the picture that differs from its surroundings,
     * and returns its four corners in the picture's own pixel coordinates. If nothing page-like is found it returns the
     * whole picture, and the person drags the corners to fit.
     */
    public static Quad detect(Img src) {
        Img s = fit(src, 320);
        int w = s.w, h = s.h;
        int[] l = new int[w * h];
        for (int i = 0; i < l.length; i++) l[i] = luma(s.px[i]);
        int t = otsu(l);
        // which side of the threshold is the page? the one that fills more of the middle of the picture
        int bright = 0, centre = 0;
        for (int y = h / 4; y < h * 3 / 4; y++) {
            for (int x = w / 4; x < w * 3 / 4; x++) {
                centre++;
                if (l[y * w + x] > t) bright++;
            }
        }
        boolean pageIsBright = bright * 2 >= centre;
        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < mask.length; i++) mask[i] = (l[i] > t) == pageIsBright;
        mask = close(mask, w, h, Math.max(2, Math.max(w, h) / 64));        // text and marks inside the page don't split it
        boolean[] page = biggestBlob(mask, w, h);
        if (page == null) return Quad.whole(src.w, src.h);
        // corners: the extreme points along the two diagonals
        int tl = -1, tr = -1, br = -1, bl = -1;
        double minS = Double.MAX_VALUE, maxS = -Double.MAX_VALUE, minD = Double.MAX_VALUE, maxD = -Double.MAX_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!page[y * w + x]) continue;
                double sum = x + y, diff = x - y;
                if (sum < minS) { minS = sum; tl = y * w + x; }
                if (sum > maxS) { maxS = sum; br = y * w + x; }
                if (diff > maxD) { maxD = diff; tr = y * w + x; }
                if (diff < minD) { minD = diff; bl = y * w + x; }
            }
        }
        if (tl < 0 || tr < 0 || br < 0 || bl < 0) return Quad.whole(src.w, src.h);
        double sx = (double) src.w / w, sy = (double) src.h / h;
        Quad q = new Quad(tl % w + 0.5, tl / w + 0.5, tr % w + 0.5, tr / w + 0.5, br % w + 0.5, br / w + 0.5, bl % w + 0.5, bl / w + 0.5).scaled(sx, sy);
        if (!q.isSane(src.w, src.h) || q.area() < src.w * (double) src.h * 0.15) return Quad.whole(src.w, src.h);
        return q;
    }

    /** Fills small gaps: dilate then erode with a square window. */
    static boolean[] close(boolean[] m, int w, int h, int r) {
        return erode(dilate(m, w, h, r), w, h, r);
    }

    private static boolean[] dilate(boolean[] m, int w, int h, int r) {
        boolean[] tmp = new boolean[m.length];
        boolean[] out = new boolean[m.length];
        for (int y = 0; y < h; y++) {                      // horizontal pass
            for (int x = 0; x < w; x++) {
                boolean any = false;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r) && !any; k++) any = m[y * w + k];
                tmp[y * w + x] = any;
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean any = false;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r) && !any; k++) any = tmp[k * w + x];
                out[y * w + x] = any;
            }
        }
        return out;
    }

    private static boolean[] erode(boolean[] m, int w, int h, int r) {
        boolean[] tmp = new boolean[m.length];
        boolean[] out = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean all = true;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r) && all; k++) all = m[y * w + k];
                tmp[y * w + x] = all;
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean all = true;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r) && all; k++) all = tmp[k * w + x];
                out[y * w + x] = all;
            }
        }
        return out;
    }

    /** The largest 4-connected group of set pixels. Null when there are none. */
    static boolean[] biggestBlob(boolean[] mask, int w, int h) {
        int[] label = new int[mask.length];
        int best = 0, bestSize = 0, next = 0;
        int[] stack = new int[mask.length];
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || label[i] != 0) continue;
            next++;
            int sp = 0, size = 0;
            stack[sp++] = i;
            label[i] = next;
            while (sp > 0) {
                int p = stack[--sp];
                size++;
                int x = p % w, y = p / w;
                if (x > 0 && mask[p - 1] && label[p - 1] == 0) { label[p - 1] = next; stack[sp++] = p - 1; }
                if (x < w - 1 && mask[p + 1] && label[p + 1] == 0) { label[p + 1] = next; stack[sp++] = p + 1; }
                if (y > 0 && mask[p - w] && label[p - w] == 0) { label[p - w] = next; stack[sp++] = p - w; }
                if (y < h - 1 && mask[p + w] && label[p + w] == 0) { label[p + w] = next; stack[sp++] = p + w; }
            }
            if (size > bestSize) {
                bestSize = size;
                best = next;
            }
        }
        if (best == 0) return null;
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < out.length; i++) out[i] = label[i] == best;
        return out;
    }

    // ------------------------------------------------------------------ straightening

    /** Solves the 8 numbers of the perspective transform that carries 4 points onto 4 others. */
    static double[] homography(double[] sx, double[] sy, double[] dx, double[] dy) {
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            a[2 * i] = new double[]{sx[i], sy[i], 1, 0, 0, 0, -dx[i] * sx[i], -dx[i] * sy[i], dx[i]};
            a[2 * i + 1] = new double[]{0, 0, 0, sx[i], sy[i], 1, -dy[i] * sx[i], -dy[i] * sy[i], dy[i]};
        }
        for (int c = 0; c < 8; c++) {
            int p = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r;
            double[] t = a[c]; a[c] = a[p]; a[p] = t;
            if (Math.abs(a[c][c]) < 1e-12) return null;
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double f = a[r][c] / a[c][c];
                for (int k = c; k < 9; k++) a[r][k] -= f * a[c][k];
            }
        }
        double[] h = new double[8];
        for (int i = 0; i < 8; i++) h[i] = a[i][8] / a[i][i];
        return h;
    }

    /** Cuts the quad out of the picture and stretches it into an upright rectangle. */
    public static Img straighten(Img src, Quad q, int maxSide) {
        double top = Math.hypot(q.x[1] - q.x[0], q.y[1] - q.y[0]);
        double bottom = Math.hypot(q.x[2] - q.x[3], q.y[2] - q.y[3]);
        double left = Math.hypot(q.x[3] - q.x[0], q.y[3] - q.y[0]);
        double right = Math.hypot(q.x[2] - q.x[1], q.y[2] - q.y[1]);
        double ow = Math.max(top, bottom), oh = Math.max(left, right);
        double scale = Math.min(1.0, maxSide / Math.max(ow, oh));
        int w = Math.max(2, (int) Math.round(ow * scale));
        int h = Math.max(2, (int) Math.round(oh * scale));
        // map the output rectangle onto the quad (destination -> source), so every output pixel knows where to look
        double[] hm = homography(new double[]{0, w, w, 0}, new double[]{0, 0, h, h}, q.x, q.y);
        if (hm == null) return src;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double px = x + 0.5, py = y + 0.5;
                double den = hm[6] * px + hm[7] * py + 1;
                double u = (hm[0] * px + hm[1] * py + hm[2]) / den - 0.5;
                double v = (hm[3] * px + hm[4] * py + hm[5]) / den - 0.5;
                out[y * w + x] = bilinear(src, u, v);
            }
        }
        return new Img(w, h, out);
    }

    static int bilinear(Img s, double u, double v) {
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        double fx = u - x0, fy = v - y0;
        int xa = Math.max(0, Math.min(s.w - 1, x0)), xb = Math.max(0, Math.min(s.w - 1, x0 + 1));
        int ya = Math.max(0, Math.min(s.h - 1, y0)), yb = Math.max(0, Math.min(s.h - 1, y0 + 1));
        int c00 = s.px[ya * s.w + xa], c10 = s.px[ya * s.w + xb], c01 = s.px[yb * s.w + xa], c11 = s.px[yb * s.w + xb];
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double top = ((c00 >> shift) & 0xFF) * (1 - fx) + ((c10 >> shift) & 0xFF) * fx;
            double bot = ((c01 >> shift) & 0xFF) * (1 - fx) + ((c11 >> shift) & 0xFF) * fx;
            out |= clamp((int) Math.round(top * (1 - fy) + bot * fy)) << shift;
        }
        return out;
    }

    // ------------------------------------------------------------------ looking like a scan

    public enum Look { ORIGINAL, DOCUMENT, GRAY, BLACK_WHITE }

    /** The paper brightness around every pixel, so a shadow across the page doesn't turn it grey. */
    static int[] background(Img img) {
        int w = img.w, h = img.h;
        long[] integral = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                row += luma(img.px[y * w + x]);
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row;
            }
        }
        int r = Math.max(4, Math.max(w, h) / 16);
        int[] bg = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                long sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0];
                bg[y * w + x] = (int) (sum / ((long) (x1 - x0) * (y1 - y0)));
            }
        }
        return bg;
    }

    /** The brightness of the paper itself: ink covers a small part of a page, so the brighter pixels in the neighbourhood are paper. */
    static int[] paper(Img img) {
        int[] mean = background(img);
        int w = img.w, h = img.h;
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) out[i] = Math.max(40, Math.min(255, (int) (mean[i] * 1.12) + 4));
        return out;
    }

    public static Img enhance(Img src, Look look) {
        if (look == Look.ORIGINAL) return src;
        int w = src.w, h = src.h;
        int[] out = new int[w * h];
        if (look == Look.GRAY) {
            for (int i = 0; i < out.length; i++) {
                int l = luma(src.px[i]);
                out[i] = 0xFF000000 | (l << 16) | (l << 8) | l;
            }
            return new Img(w, h, out);
        }
        int[] paper = paper(src);
        for (int i = 0; i < out.length; i++) {
            int c = src.px[i];
            double k = 255.0 / paper[i];
            if (look == Look.BLACK_WHITE) {
                double l = luma(c) * k;
                int v = l < 150 ? 0 : 255;
                out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            } else {                                                    // DOCUMENT: white paper, colours kept, ink darker
                int r = levels(((c >> 16) & 0xFF) * k), g = levels(((c >> 8) & 0xFF) * k), b = levels((c & 0xFF) * k);
                out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return new Img(w, h, out);
    }

    /** Pushes near-white to white and deepens the darks a little. */
    private static int levels(double v) {
        double n = (v - 40) / (235 - 40);
        if (n <= 0) return 0;
        if (n >= 1) return 255;
        return (int) Math.round(255 * n * n * (3 - 2 * n) * 0.35 + 255 * n * 0.65);
    }

    // ------------------------------------------------------------------ turning

    public static Img rotate(Img src, int quarterTurnsClockwise) {
        int turns = ((quarterTurnsClockwise % 4) + 4) % 4;
        if (turns == 0) return src;
        int w = src.w, h = src.h;
        if (turns == 2) {
            int[] out = new int[w * h];
            for (int i = 0; i < out.length; i++) out[out.length - 1 - i] = src.px[i];
            return new Img(w, h, out);
        }
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (turns == 1) out[x * h + (h - 1 - y)] = src.px[y * w + x];
                else out[(w - 1 - x) * h + y] = src.px[y * w + x];
            }
        }
        return new Img(h, w, out);
    }
}
