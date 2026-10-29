package com.fastshare.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * A recycle bin kept in a hidden folder at the top of each storage volume, so putting something in it is a
 * rename (instant, no copying) and it can always be put back where it came from.
 *
 * Layout: {@code <volume>/.FileBanditTrash/<id>} is the deleted file or folder and {@code <id>.meta} remembers
 * where it came from and when it was deleted.
 */
public final class Trash {

    public static final String DIR_NAME = ".FileBanditTrash";
    public static final long DEFAULT_KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    /** One deleted item. */
    public static final class Entry {
        public final String id;
        public final File original;
        public final String name;
        public final boolean dir;
        public final long size;
        public final long deletedAt;
        public final File stored;
        final File meta;

        Entry(String id, File original, String name, boolean dir, long size, long deletedAt, File stored, File meta) {
            this.id = id;
            this.original = original;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.deletedAt = deletedAt;
            this.stored = stored;
            this.meta = meta;
        }
    }

    private final File root;
    private final File dir;

    /** @param volumeRoot the top of a storage volume, for example /storage/emulated/0 */
    public Trash(File volumeRoot) {
        this.root = volumeRoot;
        this.dir = new File(volumeRoot, DIR_NAME);
    }

    public static boolean isTrashPath(File f) {
        for (File p = f; p != null; p = p.getParentFile()) {
            if (DIR_NAME.equals(p.getName())) return true;
        }
        return false;
    }

    public File dir() {
        return dir;
    }

    private void ensureDir() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create the trash folder");
        File nomedia = new File(dir, ".nomedia");   // keeps deleted photos out of the gallery
        if (!nomedia.exists()) {
            //noinspection ResultOfMethodCallIgnored
            nomedia.createNewFile();
        }
    }

    /** Moves a file or folder into the trash. */
    public Entry trash(File f) throws IOException {
        if (!f.exists()) throw new IOException(f.getName() + " no longer exists");
        if (isTrashPath(f)) throw new IOException("That is already in the trash");
        ensureDir();
        String id = Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        File target = new File(dir, id);
        boolean isDir = f.isDirectory();
        long size = isDir ? FileOps.sizeOf(f, null) : f.length();
        if (!f.renameTo(target)) {
            // different file system than the trash folder: copy then remove
            File copy = FileOps.copy(f, dir, null, null);
            if (!copy.exists() || !copy.renameTo(target)) throw new IOException("Couldn't move " + f.getName() + " to the trash");
            if (!FileOps.delete(f)) throw new IOException("Couldn't remove the original " + f.getName());
        }
        File meta = new File(dir, id + ".meta");
        Properties p = new Properties();
        p.setProperty("path", f.getPath());
        p.setProperty("name", f.getName());
        p.setProperty("dir", Boolean.toString(isDir));
        p.setProperty("size", Long.toString(size));
        long now = System.currentTimeMillis();
        p.setProperty("deleted", Long.toString(now));
        OutputStream os = new FileOutputStream(meta);
        try {
            p.store(os, null);
        } finally {
            os.close();
        }
        return new Entry(id, f, f.getName(), isDir, size, now, target, meta);
    }

    /** Everything in the trash, newest first. */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File m : files) {
            if (!m.getName().endsWith(".meta")) continue;
            String id = m.getName().substring(0, m.getName().length() - 5);
            File stored = new File(dir, id);
            if (!stored.exists()) {          // payload gone: drop the orphaned note
                //noinspection ResultOfMethodCallIgnored
                m.delete();
                continue;
            }
            Properties p = new Properties();
            try {
                InputStream in = new FileInputStream(m);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                continue;
            }
            String path = p.getProperty("path", new File(root, id).getPath());
            out.add(new Entry(id, new File(path), p.getProperty("name", stored.getName()),
                    Boolean.parseBoolean(p.getProperty("dir", "false")),
                    parse(p.getProperty("size"), stored.length()), parse(p.getProperty("deleted"), m.lastModified()),
                    stored, m));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.deletedAt, a.deletedAt); }
        });
        return out;
    }

    private static long parse(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Puts an item back where it was deleted from. If that name is taken, "(1)" is added. Returns the restored file. */
    public File restore(Entry e) throws IOException {
        File parent = e.original.getParentFile();
        if (parent == null) throw new IOException("Unknown original folder");
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Can't recreate " + parent.getPath());
        File target = FileOps.uniqueSibling(parent, e.name);
        if (!e.stored.renameTo(target)) {
            File copy = FileOps.copy(e.stored, parent, null, null);
            if (!copy.renameTo(target) && !target.exists()) throw new IOException("Couldn't restore " + e.name);
            FileOps.delete(e.stored);
        }
        //noinspection ResultOfMethodCallIgnored
        e.meta.delete();
        return target;
    }

    public void deleteForever(Entry e) {
        FileOps.delete(e.stored);
        //noinspection ResultOfMethodCallIgnored
        e.meta.delete();
    }

    /** Permanently removes everything. Returns how many items were removed. */
    public int empty() {
        int n = 0;
        for (Entry e : list()) {
            deleteForever(e);
            n++;
        }
        return n;
    }

    /** Permanently removes items deleted more than {@code keepMs} ago. */
    public int purgeOlderThan(long keepMs) {
        long cutoff = System.currentTimeMillis() - keepMs;
        int n = 0;
        for (Entry e : list()) {
            if (e.deletedAt < cutoff) {
                deleteForever(e);
                n++;
            }
        }
        return n;
    }

    public long totalSize() {
        long total = 0;
        for (Entry e : list()) total += e.size;
        return total;
    }
}
