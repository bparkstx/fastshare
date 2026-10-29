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
import java.util.List;

/** Remembers finished transfers across app restarts. One line per transfer, newest first. */
public final class History {

    public static final class Entry {
        public final String key;
        public final boolean incoming;
        public final String name;
        public final long size;
        public final long when;
        public final String client;
        public final boolean ok;
        public final String detail;

        public Entry(String key, boolean incoming, String name, long size, long when, String client, boolean ok, String detail) {
            this.key = key;
            this.incoming = incoming;
            this.name = name;
            this.size = size;
            this.when = when;
            this.client = client;
            this.ok = ok;
            this.detail = detail;
        }
    }

    static final int MAX = 300;

    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public History(File file) {
        this.file = file;
        load();
    }

    /** What to remember about a transfer that has just ended. */
    public static Entry of(FileServer.Transfer t) {
        return new Entry(t.startedAt + "-" + t.id, t.incoming, t.name, t.done, System.currentTimeMillis(), t.client,
                t.state == FileServer.State.DONE, t.detail == null ? "" : t.detail);
    }

    /** Adds the entry, or replaces the one with the same key (a resumed transfer finishing). */
    public synchronized void record(Entry e) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key.equals(e.key)) {
                entries.remove(i);
                break;
            }
        }
        entries.add(0, e);
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
        save();
    }

    public synchronized List<Entry> list() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private void load() {
        if (!file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 8) continue;
                try {
                    entries.add(new Entry(p[0], "1".equals(p[1]), p[2], Long.parseLong(p[3]), Long.parseLong(p[4]), p[5], "1".equals(p[6]), p[7]));
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void save() {
        File tmp = new File(file.getPath() + ".tmp");
        try {
            File dir = file.getParentFile();
            if (dir != null) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try {
                for (Entry e : entries) {
                    w.write(clean(e.key) + "\t" + (e.incoming ? "1" : "0") + "\t" + clean(e.name) + "\t" + e.size + "\t" + e.when
                            + "\t" + clean(e.client) + "\t" + (e.ok ? "1" : "0") + "\t" + clean(e.detail) + "\n");
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
}
