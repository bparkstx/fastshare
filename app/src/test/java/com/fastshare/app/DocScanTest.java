package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class DocScanTest {

    /** Corners of the page inside the "photo": top-left, top-right, bottom-right, bottom-left. */
    static final double[] PX = {150, 660, 700, 110};
    static final double[] PY = {90, 130, 520, 470};

    /** A photo of a page: dark table, a white page seen at an angle, dark "text" blocks, uneven light, and noise. */
    static DocScan.Img photo(int w, int h, double[] px, double[] py, boolean darkTable, long seed) {
        double[] hm = DocScan.homography(new double[]{0, 1, 1, 0}, new double[]{0, 0, 1, 1}, px, py);   // page (u,v) -> photo
        double[] inv = DocScan.homography(px, py, new double[]{0, 1, 1, 0}, new double[]{0, 0, 1, 1});   // photo -> page (u,v)
        Random r = new Random(seed);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double den = inv[6] * (x + 0.5) + inv[7] * (y + 0.5) + 1;
                double u = (inv[0] * (x + 0.5) + inv[1] * (y + 0.5) + inv[2]) / den;
                double v = (inv[3] * (x + 0.5) + inv[4] * (y + 0.5) + inv[5]) / den;
                boolean onPage = u >= 0 && u <= 1 && v >= 0 && v <= 1;
                double light = 0.72 + 0.28 * x / w;                                   // darker on the left
                int rr, gg, bb;
                if (onPage) {
                    boolean text = textAt(u, v);
                    int base = text ? 40 : 238;
                    rr = gg = bb = (int) (base * light);
                } else if (darkTable) {
                    rr = (int) (62 * light); gg = (int) (44 * light); bb = (int) (30 * light);
                } else {
                    rr = (int) (200 * light); gg = (int) (190 * light); bb = (int) (170 * light);
                }
                int n = r.nextInt(9) - 4;
                out[y * w + x] = 0xFF000000 | (DocScan.clamp(rr + n) << 16) | (DocScan.clamp(gg + n) << 8) | DocScan.clamp(bb + n);
            }
        }
        return new DocScan.Img(w, h, out);
    }

    /** Where the "text" is on the page, in page coordinates. */
    static boolean textAt(double u, double v) {
        for (int line = 0; line < 8; line++) {
            double top = 0.12 + line * 0.095;
            if (v >= top && v < top + 0.035 && u > 0.12 && u < 0.88 - (line % 3) * 0.1) return true;
        }
        return false;
    }

    private static double dist(double x0, double y0, double x1, double y1) {
        return Math.hypot(x1 - x0, y1 - y0);
    }

    @Test public void findsThePageOnADarkTable() {
        DocScan.Img img = photo(800, 600, PX, PY, true, 1);
        DocScan.Quad q = DocScan.detect(img);
        for (int i = 0; i < 4; i++) {
            double d = dist(q.x[i], q.y[i], PX[i], PY[i]);
            assertTrue("corner " + i + " is " + d + " px off", d < 24);
        }
    }

    @Test public void findsThePageOnALightTableToo() {
        // a light table around a white page is harder; the page edge shows as a small step in brightness
        DocScan.Img img = photo(800, 600, PX, PY, false, 2);
        DocScan.Quad q = DocScan.detect(img);
        assertTrue(q.isSane(800, 600));
        assertTrue("some quad came back", q.area() > 800 * 600 * 0.15);
    }

    @Test public void fallsBackToTheWholePictureWhenThereIsNoPage() {
        Random r = new Random(5);
        int[] noise = new int[400 * 300];
        for (int i = 0; i < noise.length; i++) {
            int v = 100 + r.nextInt(30);
            noise[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        DocScan.Quad q = DocScan.detect(new DocScan.Img(400, 300, noise));
        assertTrue(q.isSane(400, 300));
        assertTrue("covers most of the picture", q.area() > 400 * 300 * 0.5);
    }

    @Test public void aPageFillingTheFrameIsFoundAsTheWholeFrame() {
        DocScan.Img img = photo(600, 800, new double[]{4, 596, 596, 4}, new double[]{4, 4, 796, 796}, true, 3);
        DocScan.Quad q = DocScan.detect(img);
        assertTrue(q.area() > 600 * 800 * 0.9);
    }

    @Test public void straighteningGivesAnUprightPageWithTheTextWhereItBelongs() {
        DocScan.Img img = photo(800, 600, PX, PY, true, 4);
        DocScan.Quad q = new DocScan.Quad(PX[0], PY[0], PX[1], PY[1], PX[2], PY[2], PX[3], PY[3]);
        DocScan.Img flat = DocScan.straighten(img, q, 2000);
        double expectedW = Math.max(dist(PX[0], PY[0], PX[1], PY[1]), dist(PX[3], PY[3], PX[2], PY[2]));
        double expectedH = Math.max(dist(PX[0], PY[0], PX[3], PY[3]), dist(PX[1], PY[1], PX[2], PY[2]));
        assertEquals(expectedW, flat.w, 2);
        assertEquals(expectedH, flat.h, 2);
        // a text line and a paper gap, at known page positions
        int textY = (int) ((0.12 + 0.0175) * flat.h), textX = (int) (0.3 * flat.w);
        int gapY = (int) ((0.12 + 0.065) * flat.h);
        assertTrue("text is dark: " + DocScan.luma(flat.px[textY * flat.w + textX]), DocScan.luma(flat.px[textY * flat.w + textX]) < 120);
        assertTrue("gap between lines is paper: " + DocScan.luma(flat.px[gapY * flat.w + textX]), DocScan.luma(flat.px[gapY * flat.w + textX]) > 140);
        assertTrue("page corner area is paper", DocScan.luma(flat.px[(int) (0.95 * flat.h) * flat.w + (int) (0.9 * flat.w)]) > 140);
    }

    @Test public void straighteningTheWholePictureChangesNothingMuch() {
        DocScan.Img img = photo(200, 150, PX, PY, true, 6);
        DocScan.Quad whole = new DocScan.Quad(0, 0, 200, 0, 200, 150, 0, 150);
        DocScan.Img same = DocScan.straighten(img, whole, 2000);
        assertEquals(200, same.w);
        assertEquals(150, same.h);
        long diff = 0;
        for (int i = 0; i < img.px.length; i++) diff += Math.abs(DocScan.luma(img.px[i]) - DocScan.luma(same.px[i]));
        assertTrue("average difference " + diff / (double) img.px.length, diff / (double) img.px.length < 4);
    }

    @Test public void theDocumentLookWhitensUnevenlyLitPaperAndKeepsTextDark() {
        DocScan.Img img = photo(800, 600, PX, PY, true, 7);
        DocScan.Img flat = DocScan.straighten(img, new DocScan.Quad(PX[0], PY[0], PX[1], PY[1], PX[2], PY[2], PX[3], PY[3]), 2000);
        DocScan.Img doc = DocScan.enhance(flat, DocScan.Look.DOCUMENT);
        int gapY = (int) ((0.12 + 0.065) * doc.h);
        int left = DocScan.luma(doc.px[gapY * doc.w + (int) (0.15 * doc.w)]);
        int right = DocScan.luma(doc.px[gapY * doc.w + (int) (0.85 * doc.w)]);
        assertTrue("paper on the dim left side is white: " + left, left > 225);
        assertTrue("paper on the bright right side is white: " + right, right > 225);
        int textY = (int) ((0.12 + 0.0175) * doc.h);
        assertTrue("text stays dark: " + DocScan.luma(doc.px[textY * doc.w + (int) (0.3 * doc.w)]), DocScan.luma(doc.px[textY * doc.w + (int) (0.3 * doc.w)]) < 110);
    }

    @Test public void blackAndWhiteHasOnlyTwoValuesAndGrayHasNoColour() {
        DocScan.Img img = photo(800, 600, PX, PY, true, 8);
        DocScan.Img flat = DocScan.straighten(img, new DocScan.Quad(PX[0], PY[0], PX[1], PY[1], PX[2], PY[2], PX[3], PY[3]), 2000);
        DocScan.Img bw = DocScan.enhance(flat, DocScan.Look.BLACK_WHITE);
        int black = 0, white = 0;
        for (int p : bw.px) {
            int v = p & 0xFF;
            assertTrue(v == 0 || v == 255);
            assertEquals(v, (p >> 8) & 0xFF);
            if (v == 0) black++; else white++;
        }
        assertTrue("mostly paper: " + white + " white, " + black + " black", white > black * 3 && black > flat.w * flat.h / 100);
        DocScan.Img gray = DocScan.enhance(flat, DocScan.Look.GRAY);
        for (int p : gray.px) assertTrue(((p >> 16) & 0xFF) == (p & 0xFF) && ((p >> 8) & 0xFF) == (p & 0xFF));
        assertTrue(DocScan.enhance(flat, DocScan.Look.ORIGINAL) == flat);
    }

    @Test public void rotatingByQuarterTurnsMovesPixelsCorrectly() {
        int[] px = new int[2 * 3];                         // 2 wide, 3 tall: values 0..5 row by row
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | i;
        DocScan.Img img = new DocScan.Img(2, 3, px);
        DocScan.Img cw = DocScan.rotate(img, 1);
        assertEquals(3, cw.w);
        assertEquals(2, cw.h);
        // clockwise: the left column (0,2,4) becomes the top row, read right to left: 4,2,0 -> top row is 4 2 0
        assertArrayEquals(new int[]{4, 2, 0, 5, 3, 1}, lows(cw.px));
        assertArrayEquals(new int[]{5, 4, 3, 2, 1, 0}, lows(DocScan.rotate(img, 2).px));
        assertArrayEquals(new int[]{1, 3, 5, 0, 2, 4}, lows(DocScan.rotate(img, 3).px));
        assertArrayEquals(lows(px), lows(DocScan.rotate(img, 4).px));
        assertArrayEquals(lows(DocScan.rotate(img, 3).px), lows(DocScan.rotate(img, -1).px));
    }

    private static int[] lows(int[] p) {
        int[] o = new int[p.length];
        for (int i = 0; i < p.length; i++) o[i] = p[i] & 0xFF;
        return o;
    }

    @Test public void fitShrinksBigPicturesAndLeavesSmallOnesAlone() {
        DocScan.Img big = new DocScan.Img(1000, 500, new int[500_000]);
        DocScan.Img small = DocScan.fit(big, 250);
        assertEquals(250, small.w);
        assertEquals(125, small.h);
        assertTrue(DocScan.fit(small, 400) == small);
    }

    @Test public void insanePagesAreRejected() {
        assertTrue(new DocScan.Quad(0, 0, 100, 0, 100, 100, 0, 100).isSane(100, 100));
        assertTrue("a bow-tie isn't a page", !new DocScan.Quad(0, 0, 100, 100, 100, 0, 0, 100).isSane(100, 100));
        assertTrue("a sliver isn't a page", !new DocScan.Quad(0, 0, 100, 0, 100, 1, 0, 1).isSane(100, 100));
        assertNotNull(DocScan.Quad.whole(10, 10));
    }
}
