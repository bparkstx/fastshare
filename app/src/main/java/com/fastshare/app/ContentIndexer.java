package com.fastshare.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads new and changed files into a {@link ContentIndex}. Safe to stop and start again: it only does what is missing. */
public final class ContentIndexer {
    private ContentIndexer() { }

    /** Reads words and objects out of a picture or a page. The app uses Google's on-device models; tests use a fake. */
    public interface Recognizer {
        Result recognize(File f) throws IOException;
    }

    public static final class Result {
        public final String text;
        public final List<String> labels;

        public Result(String text, List<String> labels) {
            this.text = text == null ? "" : text;
            this.labels = labels == null ? new ArrayList<String>() : labels;
        }
    }

    public interface Progress {
        void step(int done, int total);
        boolean cancelled();
    }

    public static final class Summary {
        public int indexed;       // files read this time
        public int unchanged;     // already known
        public int failed;
        public boolean cancelled;
    }

    static final Set<String> PICTURES = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif"));
    static final Set<String> PDFS = new HashSet<>(Arrays.asList("pdf"));
    static final Set<String> TEXTS = new HashSet<>(Arrays.asList("txt", "md", "csv", "json", "xml", "html", "htm", "log", "ini", "yml", "yaml", "srt", "vcf", "tsv", "rtf"));
    static final long MAX_TEXT_FILE = 2_000_000;

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Which files are worth reading, under the given folders. */
    public static List<File> candidates(List<File> folders, int maxFiles) {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File f : folders) walk(f, out, seen, maxFiles, 0);
        return out;
    }

    private static void walk(File f, List<File> out, Set<String> seen, int max, int depth) {
        if (f == null || out.size() >= max || depth > 14) return;
        String name = f.getName();
        if (name.startsWith(".") || name.equals("Android") || name.equals("cache")) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            Arrays.sort(kids);
            for (File k : kids) walk(k, out, seen, max, depth + 1);
            return;
        }
        String e = ext(name);
        if (!(PICTURES.contains(e) || PDFS.contains(e) || TEXTS.contains(e))) return;
        if (name.contains(".trashed-") || name.startsWith("pending-")) return;
        try {
            if (seen.add(f.getCanonicalPath())) out.add(f);
        } catch (IOException ignored) { }
    }

    static String readText(File f) throws IOException {
        if (f.length() > MAX_TEXT_FILE) {
            // very large: read the start only
            byte[] head = new byte[(int) Math.min(200_000, f.length())];
            try (InputStream in = new FileInputStream(f)) {
                int n = in.read(head);
                return new String(head, 0, Math.max(0, n), StandardCharsets.UTF_8);
            }
        }
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    public static Summary run(List<File> files, Recognizer recognizer, ContentIndex index, Progress progress) {
        Summary s = new Summary();
        int done = 0;
        for (File f : files) {
            if (progress != null) {
                if (progress.cancelled()) {
                    s.cancelled = true;
                    break;
                }
                progress.step(done, files.size());
            }
            done++;
            if (index.has(f)) {
                s.unchanged++;
                continue;
            }
            try {
                String e = ext(f.getName());
                ContentIndexer.Result r;
                if (TEXTS.contains(e)) r = new Result(readText(f), Collections.<String>emptyList());
                else r = recognizer.recognize(f);
                index.put(new ContentIndex.Entry(f.getPath(), f.length(), f.lastModified(), r.text, r.labels));
                s.indexed++;
                if (s.indexed % 25 == 0) index.save();            // a killed run keeps what it learned
            } catch (IOException | RuntimeException ex) {
                s.failed++;
                // remember the failure as "nothing found" so the same broken file isn't retried on every visit
                index.put(new ContentIndex.Entry(f.getPath(), f.length(), f.lastModified(), "", new ArrayList<String>()));
            }
        }
        index.prune();
        index.save();
        return s;
    }
}
