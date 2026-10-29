package com.fastshare.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/** A server held in memory, with switches to make it misbehave. */
final class MemoryRemote implements Remote {
    final Map<String, byte[]> files = new TreeMap<>();
    final TreeSet<String> dirs = new TreeSet<>();
    final List<String> puts = new ArrayList<>();
    volatile int failPuts;               // the next N puts throw
    volatile boolean failAll;            // every call throws
    volatile boolean closed;
    final AtomicInteger lists = new AtomicInteger();

    MemoryRemote() {
        dirs.add("/");
    }

    private void check() throws IOException {
        if (failAll) throw new IOException("Connection reset");
    }

    @Override public synchronized List<Item> list(String path) throws IOException {
        check();
        lists.incrementAndGet();
        String dir = Paths.clean(path);
        if (!dirs.contains(dir)) throw new IOException("That doesn't exist on the server.");
        List<Item> out = new ArrayList<>();
        for (String d : dirs) if (!d.equals("/") && Paths.parent(d).equals(dir)) out.add(new Item(Paths.name(d), d, true, -1, 1_760_000_000_000L));
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (Paths.parent(f.getKey()).equals(dir)) out.add(new Item(Paths.name(f.getKey()), f.getKey(), false, f.getValue().length, 1_760_000_000_000L));
        }
        Collections.sort(out, ORDER);
        return out;
    }

    @Override public synchronized InputStream open(String path, long offset) throws IOException {
        check();
        byte[] b = files.get(Paths.clean(path));
        if (b == null) throw new IOException("That doesn't exist on the server.");
        return new ByteArrayInputStream(b, (int) offset, b.length - (int) offset);
    }

    @Override public synchronized void put(String path, InputStream in, long size) throws IOException {
        check();
        if (failPuts > 0) {
            failPuts--;
            throw new IOException("Disk full on the server");
        }
        String p = Paths.clean(path);
        if (!dirs.contains(Paths.parent(p))) throw new IOException("The folder it belongs in doesn't exist.");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        files.put(p, bos.toByteArray());
        puts.add(p);
    }

    @Override public synchronized void mkdir(String path) throws IOException {
        check();
        String p = Paths.clean(path);
        if (dirs.contains(p) || files.containsKey(p)) throw new IOException("Something with that name is already there.");
        if (!dirs.contains(Paths.parent(p))) throw new IOException("The folder it belongs in doesn't exist.");
        dirs.add(p);
    }

    @Override public synchronized void delete(String path, boolean dir) throws IOException {
        check();
        String p = Paths.clean(path);
        files.remove(p);
        if (dir) {
            dirs.remove(p);
            files.keySet().removeIf(k -> k.startsWith(p + "/"));
            dirs.removeIf(k -> k.startsWith(p + "/"));
        }
    }

    @Override public synchronized void rename(String from, String to) throws IOException {
        check();
        String a = Paths.clean(from);
        String b = Paths.clean(to);
        if (files.containsKey(b) || dirs.contains(b)) throw new IOException("Something with that name is already there.");
        if (files.containsKey(a)) files.put(b, files.remove(a));
        else if (dirs.remove(a)) dirs.add(b);
        else throw new IOException("That doesn't exist on the server.");
    }

    @Override public void close() {
        closed = true;
    }

    synchronized List<String> allFiles() {
        return new ArrayList<>(files.keySet());
    }
}
