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

/** When someone typed a wrong vault PIN. Shown to the owner the next time they unlock the real vault. */
public final class IntruderLog {
    static final int MAX = 50;

    private final File file;
    private final List<Long> times = new ArrayList<>();

    public IntruderLog(File file) {
        this.file = file;
        if (file.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        times.add(Long.parseLong(line.trim()));
                    } catch (NumberFormatException ignored) { }
                }
            } catch (IOException ignored) { }
        }
    }

    public synchronized void record(long when) {
        times.add(when);
        while (times.size() > MAX) times.remove(0);
        save();
    }

    /** Oldest first. */
    public synchronized List<Long> list() {
        return new ArrayList<>(times);
    }

    public synchronized void clear() {
        times.clear();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void save() {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                for (long t : times) w.write(t + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) { }
    }
}
