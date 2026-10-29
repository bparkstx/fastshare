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
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers which local files are already safely on the server, so the next run only sends what's new. */
public final class BackupLog {

    /** One backed-up file: where it went and how big it was when it was sent. */
    public static final class Entry {
        public final String key;
        public final String remotePath;
        public final long size;
        public final long when;

        Entry(String key, String remotePath, long size, long when) {
            this.key = key;
            this.remotePath = remotePath;
            this.size = size;
            this.when = when;
        }
    }

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public BackupLog(File file) {
        this.file = file;
        load();
    }

    /** A file counts as the same file while its path, size and modified time are unchanged. */
    public static String keyOf(File f) {
        return f.getPath() + "|" + f.length() + "|" + f.lastModified();
    }

    public synchronized boolean has(String key) {
        return entries.containsKey(key);
    }

    public synchronized Entry get(String key) {
        return entries.get(key);
    }

    public synchronized void put(String key, String remotePath, long size) {
        entries.put(key, new Entry(key, remotePath, size, System.currentTimeMillis()));
    }

    public synchronized int count() {
        return entries.size();
    }

    public synchronized java.util.List<Entry> list() {
        return new java.util.ArrayList<>(entries.values());
    }

    public synchronized void clear() {
        entries.clear();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** Writes to disk. Called after each file so a killed run loses nothing. */
    public synchronized void save() {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try {
                for (Entry e : entries.values()) {
                    w.write(e.key.replace('\t', ' ').replace('\n', ' ') + "\t" + e.remotePath.replace('\t', ' ').replace('\n', ' ')
                            + "\t" + e.size + "\t" + e.when + "\n");
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
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                try {
                    entries.put(p[0], new Entry(p[0], p[1], Long.parseLong(p[2]), Long.parseLong(p[3])));
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
    }
}
