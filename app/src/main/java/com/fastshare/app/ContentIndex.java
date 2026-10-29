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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is written in, and shown by, the files on this phone: the words found in photos and documents, and what a photo
 * contains ("dog", "beach"). Kept in one private file; searching is plain text matching on the phone. No Android imports.
 */
public final class ContentIndex {

    /** What was learned about one file. */
    public static final class Entry {
        public final String path;
        public final long size;
        public final long modified;
        public final String text;          // the words read out of the file
        public final List<String> labels;  // what a photo shows

        public Entry(String path, long size, long modified, String text, List<String> labels) {
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.text = text;
            this.labels = labels;
        }

        public File file() {
            return new File(path);
        }
    }

    public static final class Hit {
        public final Entry entry;
        public final int score;
        public final String snippet;

        Hit(Entry entry, int score, String snippet) {
            this.entry = entry;
            this.score = score;
            this.snippet = snippet;
        }
    }

    public static final int MAX_TEXT = 20_000;

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> folded = new HashMap<>();      // path -> searchable lower-case text

    public ContentIndex(File file) {
        this.file = file;
        load();
    }

    public static String key(File f) {
        return f.getPath() + "|" + f.length() + "|" + f.lastModified();
    }

    public synchronized boolean has(File f) {
        Entry e = entries.get(f.getPath());
        return e != null && e.size == f.length() && e.modified == f.lastModified();
    }

    public synchronized void put(Entry e) {
        String text = e.text.length() > MAX_TEXT ? e.text.substring(0, MAX_TEXT) : e.text;
        Entry kept = new Entry(e.path, e.size, e.modified, text, e.labels);
        entries.put(e.path, kept);
        folded.put(e.path, fold(text + "\n" + String.join(" ", e.labels)));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void remove(String path) {
        entries.remove(path);
        folded.remove(path);
    }

    /** Drops what is known about files that no longer exist or have changed. Returns how many were dropped. */
    public synchronized int prune() {
        List<String> gone = new ArrayList<>();
        for (Entry e : entries.values()) {
            File f = e.file();
            if (!f.isFile() || f.length() != e.size || f.lastModified() != e.modified) gone.add(e.path);
        }
        for (String p : gone) remove(p);
        return gone.size();
    }

    /** Lower case, accents removed, so "Café" finds "cafe". */
    static String fold(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ searching

    /** Files that contain every word of the query (in their text, labels or name), best first. */
    public synchronized List<Hit> search(String query, int limit) {
        List<String> terms = new ArrayList<>();
        for (String t : fold(query).split("[\\s,;]+")) if (t.length() > 0) terms.add(t);
        List<Hit> hits = new ArrayList<>();
        if (terms.isEmpty()) return hits;
        for (Entry e : entries.values()) {
            String body = folded.get(e.path);
            String name = fold(e.file().getName());
            int score = 0;
            boolean all = true;
            for (String t : terms) {
                int inName = name.contains(t) ? 1 : 0;
                int inBody = count(body, t);
                if (inName == 0 && inBody == 0) {
                    all = false;
                    break;
                }
                score += inName * 5 + Math.min(inBody, 10);
                for (String label : e.labels) if (fold(label).equals(t)) score += 4;          // a photo of a "dog" beats one that mentions it
                if (startsWord(body, t)) score += 2;                                       // a whole word beats a piece of one
            }
            if (!all) continue;
            hits.add(new Hit(e, score, snippet(e.text, terms)));
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.score != b.score) return Integer.compare(b.score, a.score);
                return Long.compare(b.entry.modified, a.entry.modified);
            }
        });
        return limit > 0 && hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    private static int count(String body, String t) {
        int n = 0;
        for (int i = body.indexOf(t); i >= 0; i = body.indexOf(t, i + t.length())) n++;
        return n;
    }

    private static boolean startsWord(String body, String t) {
        for (int i = body.indexOf(t); i >= 0; i = body.indexOf(t, i + 1)) {
            if (i == 0 || !Character.isLetterOrDigit(body.charAt(i - 1))) return true;
        }
        return false;
    }

    /** A line of text around the first word that matched. */
    static String snippet(String text, List<String> terms) {
        if (text == null || text.isEmpty()) return "";
        String f = fold(text);
        int at = -1;
        for (String t : terms) {
            int i = f.indexOf(t);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) return "";
        int start = Math.max(0, at - 40), end = Math.min(text.length(), at + 80);
        // fold() can change length for odd characters; keep to the original text's bounds
        start = Math.min(start, text.length());
        String s = text.substring(start, Math.max(start, end)).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + s + (end < text.length() ? "…" : "");
    }

    // ------------------------------------------------------------------ storage

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public synchronized void save() {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try {
                for (Entry e : entries.values()) {
                    w.write(esc(e.path) + "\t" + e.size + "\t" + e.modified + "\t" + esc(String.join(",", e.labels)) + "\t" + esc(e.text) + "\n");
                }
            } finally {
                w.close();
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (IOException ignored) { }
    }

    private void load() {
        if (!file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 5);
                if (p.length < 5) continue;
                try {
                    List<String> labels = new ArrayList<>();
                    String joined = unesc(p[3]);
                    if (!joined.isEmpty()) Collections.addAll(labels, joined.split(","));
                    put(new Entry(unesc(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]), unesc(p[4]), labels));
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
    }
}
