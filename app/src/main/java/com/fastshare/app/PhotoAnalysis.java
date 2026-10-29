package com.fastshare.app;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Looks at the pixels of photos to find near-copies, blurry shots and accidental black frames.
 * Works on small greyscale pictures, so it has no Android imports and can be tested with made-up images.
 */
public final class PhotoAnalysis {
    private PhotoAnalysis() { }

    /** A small greyscale picture, values 0 (black) to 255 (white). */
    public static final class Gray {
        public final int w;
        public final int h;
        public final int[] px;
        /** Size of the original photo in pixels (the decoded copy is smaller). */
        public final int originalW;
        public final int originalH;

        public Gray(int w, int h, int[] px, int originalW, int originalH) {
            if (px.length != w * h) throw new IllegalArgumentException("pixel count");
            this.w = w;
            this.h = h;
            this.px = px;
            this.originalW = originalW;
            this.originalH = originalH;
        }

        public Gray(int w, int h, int[] px) {
            this(w, h, px, w, h);
        }
    }

    /** Turns a file into a small greyscale picture. The app uses Android's decoder; tests use their own. */
    public interface Decoder {
        Gray decode(File f) throws IOException;
    }

    public static final class Stat {
        public final String path;
        public final long size;
        public final long modified;
        public final long hash;          // 64-bit difference hash
        public final double sharpness;   // variance of the Laplacian, higher = sharper
        public final double mean;        // average brightness 0..255
        public final double contrast;    // spread of brightness; near 0 = one flat colour
        public final int width;
        public final int height;

        public Stat(String path, long size, long modified, long hash, double sharpness, double mean, double contrast, int width, int height) {
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.hash = hash;
            this.sharpness = sharpness;
            this.mean = mean;
            this.contrast = contrast;
            this.width = width;
            this.height = height;
        }

        public File file() {
            return new File(path);
        }

        public long pixels() {
            return (long) width * height;
        }
    }

    // thresholds, chosen to flag clear cases only; every suggestion is reviewed by the person anyway
    public static final double BLURRY_BELOW = 25;
    public static final double DARK_BELOW = 14;
    public static final int SIMILAR_MAX_BITS = 8;
    public static final double FLAT_CONTRAST = 8;

    // ------------------------------------------------------------------ pixels

    /** Shrinks by averaging, so thin detail isn't lost to skipping pixels. */
    static Gray shrink(Gray g, int tw, int th) {
        if (tw >= g.w && th >= g.h) return g;
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int y0 = y * g.h / th;
            int y1 = Math.max(y0 + 1, (y + 1) * g.h / th);
            for (int x = 0; x < tw; x++) {
                int x0 = x * g.w / tw;
                int x1 = Math.max(x0 + 1, (x + 1) * g.w / tw);
                long sum = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) sum += g.px[yy * g.w + xx];
                out[y * tw + x] = (int) (sum / ((long) (y1 - y0) * (x1 - x0)));
            }
        }
        return new Gray(tw, th, out, g.originalW, g.originalH);
    }

    /** 64 bits: for each of 8 rows, is each of 9 neighbouring cells brighter than the next? Survives resizing and re-saving. */
    public static long dHash(Gray g) {
        Gray s = shrink(g, 9, 8);
        // an image smaller than 9x8 can't be hashed meaningfully; stretch it by repeating
        int[] c = new int[9 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 9; x++) {
                int sx = Math.min(s.w - 1, x * s.w / 9);
                int sy = Math.min(s.h - 1, y * s.h / 8);
                c[y * 9 + x] = s.px[sy * s.w + sx];
            }
        }
        long bits = 0;
        int i = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (c[y * 9 + x] < c[y * 9 + x + 1]) bits |= 1L << i;
                i++;
            }
        }
        return bits;
    }

    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** Variance of the Laplacian on a picture no bigger than 256 pixels across. */
    public static double sharpness(Gray g) {
        double scale = Math.max(g.w, g.h) / 256.0;
        Gray s = scale > 1 ? shrink(g, (int) Math.max(3, Math.round(g.w / scale)), (int) Math.max(3, Math.round(g.h / scale))) : g;
        if (s.w < 3 || s.h < 3) return 0;
        double sum = 0;
        double sumSq = 0;
        int n = 0;
        for (int y = 1; y < s.h - 1; y++) {
            for (int x = 1; x < s.w - 1; x++) {
                int i = y * s.w + x;
                double lap = -4.0 * s.px[i] + s.px[i - 1] + s.px[i + 1] + s.px[i - s.w] + s.px[i + s.w];
                sum += lap;
                sumSq += lap * lap;
                n++;
            }
        }
        double mean = sum / n;
        return sumSq / n - mean * mean;
    }

    static double[] brightness(Gray g) {
        double sum = 0;
        for (int v : g.px) sum += v;
        double mean = sum / g.px.length;
        double var = 0;
        for (int v : g.px) var += (v - mean) * (v - mean);
        return new double[]{mean, Math.sqrt(var / g.px.length)};
    }

    public static Stat analyze(File f, Decoder d) throws IOException {
        Gray g = d.decode(f);
        double[] b = brightness(g);
        return new Stat(f.getPath(), f.length(), f.lastModified(), dHash(g), sharpness(g), b[0], b[1], g.originalW, g.originalH);
    }

    // ------------------------------------------------------------------ finding things

    public static final class Group {
        /** Every photo in the group, best first. */
        public final List<Stat> members;

        Group(List<Stat> members) {
            this.members = members;
        }

        public Stat best() {
            return members.get(0);
        }

        /** The copies that would be removed, keeping {@link #best()}. */
        public List<Stat> extras() {
            return members.subList(1, members.size());
        }
    }

    private static double quality(Stat s) {
        // a sharper picture wins; at similar sharpness the one with more pixels (then more bytes) does
        return Math.min(s.sharpness, 2000) * Math.sqrt(Math.max(1, s.pixels())) + s.size / 1e6;
    }

    /**
     * Photos that look like each other: same scene taken twice, a burst, a re-saved or resized copy.
     * Only photos taken within {@code windowMs} of each other are compared (that's where almost all copies are, and it
     * keeps the work small), and flat pictures are ignored because every black frame looks like every other.
     */
    public static List<Group> similar(List<Stat> all, long windowMs) {
        List<Stat> list = new ArrayList<>();
        for (Stat s : all) if (s.contrast >= FLAT_CONTRAST) list.add(s);
        Collections.sort(list, new Comparator<Stat>() {
            @Override public int compare(Stat a, Stat b) { return Long.compare(a.modified, b.modified); }
        });
        int n = list.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            Stat a = list.get(i);
            for (int j = i + 1; j < n; j++) {
                Stat b = list.get(j);
                if (b.modified - a.modified > windowMs) break;
                if (distance(a.hash, b.hash) > SIMILAR_MAX_BITS) continue;
                double ra = (double) a.width / Math.max(1, a.height);
                double rb = (double) b.width / Math.max(1, b.height);
                if (Math.abs(ra - rb) / Math.max(ra, rb) > 0.08) continue;      // a crop or a rotation is a different picture
                parent[find(parent, i)] = find(parent, j);
            }
        }
        java.util.Map<Integer, List<Stat>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            List<Stat> g = groups.get(r);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(r, g);
            }
            g.add(list.get(i));
        }
        List<Group> out = new ArrayList<>();
        for (List<Stat> g : groups.values()) {
            if (g.size() < 2) continue;
            Collections.sort(g, new Comparator<Stat>() {
                @Override public int compare(Stat a, Stat b) { return Double.compare(quality(b), quality(a)); }
            });
            out.add(new Group(g));
        }
        Collections.sort(out, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) { return Long.compare(b.best().modified, a.best().modified); }
        });
        return out;
    }

    public static List<Stat> blurry(List<Stat> all) {
        List<Stat> out = new ArrayList<>();
        for (Stat s : all) if (s.contrast >= FLAT_CONTRAST && s.sharpness < BLURRY_BELOW) out.add(s);
        Collections.sort(out, new Comparator<Stat>() {
            @Override public int compare(Stat a, Stat b) { return Double.compare(a.sharpness, b.sharpness); }
        });
        return out;
    }

    public static List<Stat> dark(List<Stat> all) {
        List<Stat> out = new ArrayList<>();
        for (Stat s : all) if (s.mean < DARK_BELOW && s.contrast < 25) out.add(s);
        Collections.sort(out, new Comparator<Stat>() {
            @Override public int compare(Stat a, Stat b) { return Double.compare(a.mean, b.mean); }
        });
        return out;
    }

    /** Screenshots older than {@code minAgeMs}, oldest first. Found by where they live or what they're called. */
    public static List<Stat> oldScreenshots(List<Stat> all, long now, long minAgeMs) {
        List<Stat> out = new ArrayList<>();
        for (Stat s : all) {
            String p = s.path.toLowerCase(Locale.ROOT);
            String name = p.substring(p.lastIndexOf('/') + 1);
            boolean shot = p.contains("/screenshots/") || p.contains("/screenshot/") || name.startsWith("screenshot") || name.startsWith("screen_shot")
                    || name.startsWith("screen shot");
            if (shot && now - s.modified >= minAgeMs) out.add(s);
        }
        Collections.sort(out, new Comparator<Stat>() {
            @Override public int compare(Stat a, Stat b) { return Long.compare(a.modified, b.modified); }
        });
        return out;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }
}
