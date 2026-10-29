package com.fastshare.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scans photos for near-copies, blurry shots, black frames and old screenshots. Remembers what it learned about each file. */
public final class PhotoCleaner {
    private PhotoCleaner() { }

    public static final long DAY = 24L * 3600 * 1000;

    public static final class Report {
        public final List<PhotoAnalysis.Stat> all = new ArrayList<>();
        public List<PhotoAnalysis.Group> similar = new ArrayList<>();
        public List<PhotoAnalysis.Stat> blurry = new ArrayList<>();
        public List<PhotoAnalysis.Stat> dark = new ArrayList<>();
        public List<PhotoAnalysis.Stat> oldScreenshots = new ArrayList<>();
        public int unreadable;
        public boolean cancelled;

        /** Files that would go if every suggestion in a category were accepted. */
        public int extraCopies() {
            int n = 0;
            for (PhotoAnalysis.Group g : similar) n += g.extras().size();
            return n;
        }

        public long extraCopiesBytes() {
            long n = 0;
            for (PhotoAnalysis.Group g : similar) for (PhotoAnalysis.Stat s : g.extras()) n += s.size;
            return n;
        }

        public static long bytes(List<PhotoAnalysis.Stat> l) {
            long n = 0;
            for (PhotoAnalysis.Stat s : l) n += s.size;
            return n;
        }
    }

    public interface Progress {
        void step(int done, int total);
        boolean cancelled();
    }

    /** What was worked out for each file, so the next scan only looks at new or changed ones. */
    public static final class Cache {
        private final File file;
        private final Map<String, PhotoAnalysis.Stat> map = new HashMap<>();

        public Cache(File file) {
            this.file = file;
            if (!file.isFile()) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length < 9) continue;
                    try {
                        PhotoAnalysis.Stat s = new PhotoAnalysis.Stat(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseUnsignedLong(p[3]),
                                Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]));
                        map.put(key(s.path, s.size, s.modified), s);
                    } catch (NumberFormatException ignored) { }
                }
            } catch (IOException ignored) { }
        }

        static String key(String path, long size, long modified) {
            return path + "|" + size + "|" + modified;
        }

        PhotoAnalysis.Stat get(File f) {
            return map.get(key(f.getPath(), f.length(), f.lastModified()));
        }

        void put(PhotoAnalysis.Stat s) {
            map.put(key(s.path, s.size, s.modified), s);
        }

        public void save(java.util.Collection<File> stillExisting) {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            try {
                Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
                try {
                    java.util.Set<String> keep = new java.util.HashSet<>();
                    for (File f : stillExisting) keep.add(key(f.getPath(), f.length(), f.lastModified()));
                    for (Map.Entry<String, PhotoAnalysis.Stat> e : map.entrySet()) {
                        if (!keep.contains(e.getKey())) continue;
                        PhotoAnalysis.Stat s = e.getValue();
                        w.write(s.path.replace('\t', ' ').replace('\n', ' ') + "\t" + s.size + "\t" + s.modified + "\t" + Long.toUnsignedString(s.hash)
                                + "\t" + s.sharpness + "\t" + s.mean + "\t" + s.contrast + "\t" + s.width + "\t" + s.height + "\n");
                    }
                } finally {
                    w.close();
                }
            } catch (IOException ignored) { }
        }

        public int size() {
            return map.size();
        }
    }

    /** Looks at every file (the ones seen before come from the cache) and sorts out the suggestions. */
    public static Report scan(List<File> files, PhotoAnalysis.Decoder decoder, Cache cache, Progress progress, long now) {
        Report r = new Report();
        int done = 0;
        for (File f : files) {
            if (progress != null) {
                if (progress.cancelled()) {
                    r.cancelled = true;
                    break;
                }
                progress.step(done, files.size());
            }
            done++;
            PhotoAnalysis.Stat s = cache.get(f);
            if (s == null) {
                try {
                    s = PhotoAnalysis.analyze(f, decoder);
                    cache.put(s);
                } catch (IOException | RuntimeException e) {
                    r.unreadable++;
                    continue;
                }
            }
            r.all.add(s);
        }
        cache.save(files);
        r.similar = PhotoAnalysis.similar(r.all, 3 * DAY);
        r.blurry = PhotoAnalysis.blurry(r.all);
        r.dark = PhotoAnalysis.dark(r.all);
        r.oldScreenshots = PhotoAnalysis.oldScreenshots(r.all, now, 90 * DAY);
        return r;
    }
}
