package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class PhotoAnalysisTest {

    /** A made-up "photo": soft gradient, a dozen rectangles and some grain. The same seed is the same scene. */
    static PhotoAnalysis.Gray scene(long seed, int w, int h) {
        Random r = new Random(seed);
        int[] base = new int[w * h];
        int g0 = 40 + r.nextInt(80);
        int g1 = 120 + r.nextInt(100);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) base[y * w + x] = g0 + (g1 - g0) * x / w;
        for (int k = 0; k < 14; k++) {
            int rx = r.nextInt(w - 8), ry = r.nextInt(h - 8);
            int rw = 6 + r.nextInt(w / 3), rh = 6 + r.nextInt(h / 3);
            int val = r.nextInt(256);
            for (int y = ry; y < Math.min(h, ry + rh); y++) for (int x = rx; x < Math.min(w, rx + rw); x++) base[y * w + x] = val;
        }
        Random grain = new Random(seed * 31 + 7);
        for (int i = 0; i < base.length; i++) base[i] = clamp(base[i] + grain.nextInt(31) - 15);
        return new PhotoAnalysis.Gray(w, h, base);
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    static PhotoAnalysis.Gray brighter(PhotoAnalysis.Gray g, int by) {
        int[] p = g.px.clone();
        for (int i = 0; i < p.length; i++) p[i] = clamp(p[i] + by);
        return new PhotoAnalysis.Gray(g.w, g.h, p, g.originalW, g.originalH);
    }

    static PhotoAnalysis.Gray blurred(PhotoAnalysis.Gray g, int radius) {
        int[] out = new int[g.px.length];
        for (int y = 0; y < g.h; y++) {
            for (int x = 0; x < g.w; x++) {
                long sum = 0;
                int n = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= g.w || yy >= g.h) continue;
                        sum += g.px[yy * g.w + xx];
                        n++;
                    }
                }
                out[y * g.w + x] = (int) (sum / n);
            }
        }
        return new PhotoAnalysis.Gray(g.w, g.h, out, g.originalW, g.originalH);
    }

    static PhotoAnalysis.Gray flat(int value, int w, int h) {
        int[] p = new int[w * h];
        Arrays.fill(p, value);
        return new PhotoAnalysis.Gray(w, h, p);
    }

    /** A decoder that hands back prepared pictures by file name. */
    static PhotoAnalysis.Stat stat(String path, PhotoAnalysis.Gray g, long modified, long size, int origW, int origH) {
        PhotoAnalysis.Gray withSize = new PhotoAnalysis.Gray(g.w, g.h, g.px, origW, origH);
        double[] b = PhotoAnalysis.brightness(withSize);
        return new PhotoAnalysis.Stat(path, size, modified, PhotoAnalysis.dHash(withSize), PhotoAnalysis.sharpness(withSize), b[0], b[1], origW, origH);
    }

    @Test public void theSameSceneHashesAlikeEvenResizedOrBrightenedButOtherScenesDont() {
        PhotoAnalysis.Gray a = scene(1, 256, 192);
        long ha = PhotoAnalysis.dHash(a);
        assertTrue(PhotoAnalysis.distance(ha, PhotoAnalysis.dHash(PhotoAnalysis.shrink(a, 128, 96))) <= 4);
        assertTrue(PhotoAnalysis.distance(ha, PhotoAnalysis.dHash(PhotoAnalysis.shrink(a, 64, 48))) <= 6);
        assertTrue(PhotoAnalysis.distance(ha, PhotoAnalysis.dHash(brighter(a, 12))) <= 4);
        assertEquals(0, PhotoAnalysis.distance(ha, PhotoAnalysis.dHash(a)));
        int far = 0;
        for (long seed = 2; seed < 22; seed++) {
            if (PhotoAnalysis.distance(ha, PhotoAnalysis.dHash(scene(seed, 256, 192))) > PhotoAnalysis.SIMILAR_MAX_BITS) far++;
        }
        assertTrue("different scenes should almost always be far apart, got " + far + " of 20", far >= 19);
    }

    @Test public void blurringLowersSharpnessStepByStep() {
        PhotoAnalysis.Gray a = scene(3, 256, 192);
        double s0 = PhotoAnalysis.sharpness(a);
        double s1 = PhotoAnalysis.sharpness(blurred(a, 1));
        double s2 = PhotoAnalysis.sharpness(blurred(a, 3));
        double s3 = PhotoAnalysis.sharpness(blurred(a, 6));
        assertTrue(s0 + " > " + s1, s0 > s1);
        assertTrue(s1 + " > " + s2, s1 > s2);
        assertTrue(s2 + " > " + s3, s2 > s3);
        assertTrue("a sharp picture is far above the blur line: " + s0, s0 > PhotoAnalysis.BLURRY_BELOW * 4);
        assertTrue("a heavily blurred one is below it: " + s3, s3 < PhotoAnalysis.BLURRY_BELOW);
    }

    @Test public void largePhotosAreJudgedAtTheSameScaleAsSmallOnes() {
        PhotoAnalysis.Gray big = scene(4, 1024, 768);
        PhotoAnalysis.Gray small = PhotoAnalysis.shrink(big, 256, 192);
        double a = PhotoAnalysis.sharpness(big);
        double b = PhotoAnalysis.sharpness(small);
        assertTrue(a + " vs " + b, Math.abs(a - b) / Math.max(a, b) < 0.15);
    }

    @Test public void darkAndFlatFramesAreRecognised() {
        List<PhotoAnalysis.Stat> all = new ArrayList<>();
        all.add(stat("/p/black.jpg", flat(2, 64, 48), 1, 10, 4000, 3000));
        all.add(stat("/p/night.jpg", brighter(flat(0, 64, 48), 5), 2, 10, 4000, 3000));
        all.add(stat("/p/white.jpg", flat(250, 64, 48), 3, 10, 4000, 3000));
        all.add(stat("/p/normal.jpg", scene(5, 128, 96), 4, 10, 4000, 3000));
        List<String> dark = new ArrayList<>();
        for (PhotoAnalysis.Stat s : PhotoAnalysis.dark(all)) dark.add(new File(s.path).getName());
        assertEquals(Arrays.asList("black.jpg", "night.jpg"), dark);
        assertTrue("flat frames are not 'blurry'", PhotoAnalysis.blurry(all).isEmpty());
    }

    @Test public void similarGroupsKeepTheBestAndIgnoreUnrelatedAndFlatPhotos() {
        long day = 24L * 3600 * 1000;
        PhotoAnalysis.Gray a = scene(10, 256, 192);
        List<PhotoAnalysis.Stat> all = new ArrayList<>();
        all.add(stat("/p/a_full.jpg", a, 1000, 4_000_000, 4000, 3000));
        all.add(stat("/p/a_small.jpg", PhotoAnalysis.shrink(a, 128, 96), 1000 + 60_000, 400_000, 1000, 750));
        all.add(stat("/p/a_blurry.jpg", blurred(a, 3), 1000 + 120_000, 3_000_000, 4000, 3000));
        all.add(stat("/p/other.jpg", scene(11, 256, 192), 1000 + 30_000, 3_000_000, 4000, 3000));
        all.add(stat("/p/black1.jpg", flat(1, 64, 48), 1000 + 40_000, 100_000, 4000, 3000));
        all.add(stat("/p/black2.jpg", flat(1, 64, 48), 1000 + 50_000, 100_000, 4000, 3000));
        List<PhotoAnalysis.Group> groups = PhotoAnalysis.similar(all, day);
        assertEquals(1, groups.size());
        PhotoAnalysis.Group g = groups.get(0);
        assertEquals(3, g.members.size());
        assertEquals("/p/a_full.jpg", g.best().path);
        List<String> extras = new ArrayList<>();
        for (PhotoAnalysis.Stat s : g.extras()) extras.add(new File(s.path).getName());
        assertTrue(extras.toString(), extras.containsAll(Arrays.asList("a_small.jpg", "a_blurry.jpg")) && extras.size() == 2);
    }

    @Test public void copiesTakenFarApartOrWithADifferentShapeAreNotMerged() {
        long day = 24L * 3600 * 1000;
        PhotoAnalysis.Gray a = scene(12, 256, 192);
        List<PhotoAnalysis.Stat> all = new ArrayList<>();
        all.add(stat("/p/one.jpg", a, 0, 1_000_000, 4000, 3000));
        all.add(stat("/p/ten_days_later.jpg", a, 10 * day, 1_000_000, 4000, 3000));
        assertTrue(PhotoAnalysis.similar(all, 2 * day).isEmpty());
        assertEquals(1, PhotoAnalysis.similar(all, 11 * day).size());

        List<PhotoAnalysis.Stat> shapes = new ArrayList<>();
        shapes.add(stat("/p/wide.jpg", a, 0, 1_000_000, 4000, 3000));
        shapes.add(stat("/p/tall.jpg", a, 1000, 1_000_000, 3000, 4000));
        assertTrue("a rotated or cropped version has a different shape", PhotoAnalysis.similar(shapes, day).isEmpty());
    }

    @Test public void chainsOfBurstShotsFormOneGroup() {
        PhotoAnalysis.Gray a = scene(13, 256, 192);
        List<PhotoAnalysis.Stat> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) all.add(stat("/p/burst" + i + ".jpg", brighter(a, i * 3), i * 1000L, 2_000_000 + i, 4000, 3000));
        List<PhotoAnalysis.Group> groups = PhotoAnalysis.similar(all, 60_000);
        assertEquals(1, groups.size());
        assertEquals(6, groups.get(0).members.size());
        assertEquals(5, groups.get(0).extras().size());
    }

    @Test public void oldScreenshotsAreFoundByFolderOrName() {
        long day = 24L * 3600 * 1000;
        long now = 200 * day;
        List<PhotoAnalysis.Stat> all = new ArrayList<>();
        PhotoAnalysis.Gray g = scene(14, 64, 48);
        all.add(stat("/sdcard/Pictures/Screenshots/a.png", g, now - 120 * day, 1, 1080, 2340));
        all.add(stat("/sdcard/DCIM/Screenshots/b.png", g, now - 10 * day, 1, 1080, 2340));            // too new
        all.add(stat("/sdcard/Download/Screenshot_2026-01-01.png", g, now - 150 * day, 1, 1080, 2340));
        all.add(stat("/sdcard/DCIM/Camera/IMG_1.jpg", g, now - 300 * day, 1, 4000, 3000));            // a normal photo
        List<String> names = new ArrayList<>();
        for (PhotoAnalysis.Stat s : PhotoAnalysis.oldScreenshots(all, now, 90 * day)) names.add(new File(s.path).getName());
        assertEquals(Arrays.asList("Screenshot_2026-01-01.png", "a.png"), names);
    }
}
