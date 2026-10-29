package com.fastshare.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Looks through a storage volume and works out where the space went: totals by file type, the biggest
 * files and folders, duplicate files, and leftovers that are safe to clear.
 */
public final class Analyzer {
    private Analyzer() { }

    public enum JunkKind {
        THUMBNAILS("Thumbnail caches", true),
        TEMP("Temporary files", false),
        INCOMPLETE("Unfinished downloads", false),
        TRASHED("Android trash leftovers", false),
        INSTALLERS("Old installer files (.apk)", false),
        EMPTY_FOLDERS("Empty folders", true);

        public final String title;
        /** Removed for good instead of going to the recycle bin (nothing worth getting back). */
        public final boolean permanent;

        JunkKind(String title, boolean permanent) {
            this.title = title;
            this.permanent = permanent;
        }
    }

    public static final class Junk {
        public final JunkKind kind;
        public final File file;
        public final long size;

        Junk(JunkKind kind, File file, long size) {
            this.kind = kind;
            this.file = file;
            this.size = size;
        }
    }

    public static final class FolderSize {
        public final File folder;
        public final long size;

        FolderSize(File folder, long size) {
            this.folder = folder;
            this.size = size;
        }
    }

    public static final class DupGroup {
        public final long size;
        /** Oldest first, so the first one is normally the original. */
        public final List<File> files;

        DupGroup(long size, List<File> files) {
            this.size = size;
            this.files = files;
        }

        public long wasted() {
            return size * (files.size() - 1);
        }
    }

    public static final class Result {
        public final File root;
        public long totalBytes;
        public long fileCount;
        public long trashBytes;
        public final Map<FileOps.Kind, Long> bytesByKind = new EnumMap<>(FileOps.Kind.class);
        public final Map<FileOps.Kind, Integer> countByKind = new EnumMap<>(FileOps.Kind.class);
        public final List<FileOps.Item> largest = new ArrayList<>();
        public final List<FolderSize> folders = new ArrayList<>();
        public final List<Junk> junk = new ArrayList<>();
        /** Files big enough to be worth checking for duplicates, grouped by size. */
        final Map<Long, List<File>> sameSize = new HashMap<>();

        Result(File root) {
            this.root = root;
        }

        /** Takes something that was just deleted out of the totals, so the numbers stay right without a new scan. */
        public void forget(File f, long size, boolean wasFolder) {
            totalBytes -= size;
            FileOps.Kind k = wasFolder ? FileOps.Kind.OTHER : FileOps.kindOfName(f.getName());
            Long b = bytesByKind.get(k);
            bytesByKind.put(k, Math.max(0, (b == null ? 0 : b) - size));
            if (!wasFolder) {
                fileCount--;
                Integer c = countByKind.get(k);
                countByKind.put(k, Math.max(0, (c == null ? 0 : c) - 1));
            }
        }

        public long junkBytes() {
            long t = 0;
            for (Junk j : junk) t += j.size;
            return t;
        }
    }

    public interface Progress {
        void onProgress(long filesSeen, String where);
    }

    public static final int LARGEST_COUNT = 100;
    /** Smaller files are not worth listing as duplicates. */
    public static final long DUPLICATE_MIN_BYTES = 100 * 1024;

    // ------------------------------------------------------------------ scan

    public static Result scan(File root, Progress progress, AtomicBoolean cancel) {
        Result r = new Result(root);
        PriorityQueue<FileOps.Item> heap = new PriorityQueue<>(LARGEST_COUNT + 1, new Comparator<FileOps.Item>() {
            @Override public int compare(FileOps.Item a, FileOps.Item b) { return Long.compare(a.size, b.size); }
        });
        File[] top = root.listFiles();
        if (top != null) {
            for (File f : top) {
                if (cancel != null && cancel.get()) break;
                if (Trash.DIR_NAME.equals(f.getName())) {
                    r.trashBytes = new Trash(root).totalSize();
                    continue;
                }
                long before = r.totalBytes;
                walk(f, r, heap, progress, cancel);
                if (f.isDirectory() && !isSymlink(f)) r.folders.add(new FolderSize(f, r.totalBytes - before));
            }
        }
        List<FileOps.Item> sorted = new ArrayList<>(heap);
        Collections.sort(sorted, new Comparator<FileOps.Item>() {
            @Override public int compare(FileOps.Item a, FileOps.Item b) { return Long.compare(b.size, a.size); }
        });
        r.largest.addAll(sorted);
        Collections.sort(r.folders, new Comparator<FolderSize>() {
            @Override public int compare(FolderSize a, FolderSize b) { return Long.compare(b.size, a.size); }
        });
        Collections.sort(r.junk, new Comparator<Junk>() {
            @Override public int compare(Junk a, Junk b) { return Long.compare(b.size, a.size); }
        });
        return r;
    }

    private static boolean isSymlink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean unreadableApp(File dir) {
        String p = dir.getPath();
        return p.endsWith("/Android/data") || p.endsWith("/Android/obb");
    }

    private static void walk(File f, Result r, PriorityQueue<FileOps.Item> heap, Progress progress, AtomicBoolean cancel) {
        if (cancel != null && cancel.get()) return;
        if (isSymlink(f)) return;
        if (f.isDirectory()) {
            if (unreadableApp(f)) return;
            String name = f.getName();
            if (name.equals(".thumbnails") || name.equals(".thumbcache")) {
                long size = FileOps.sizeOf(f, cancel);
                r.junk.add(new Junk(JunkKind.THUMBNAILS, f, size));
                r.totalBytes += size;
                Long other = r.bytesByKind.get(FileOps.Kind.OTHER);
                r.bytesByKind.put(FileOps.Kind.OTHER, (other == null ? 0 : other) + size);
                return;
            }
            File[] kids = f.listFiles();
            if (kids == null) return;
            if (kids.length == 0) {
                r.junk.add(new Junk(JunkKind.EMPTY_FOLDERS, f, 0));
                return;
            }
            for (File k : kids) walk(k, r, heap, progress, cancel);
            return;
        }
        if (!f.isFile()) return;
        long size = f.length();
        String name = f.getName();
        FileOps.Kind kind = FileOps.kindOfName(name);
        r.totalBytes += size;
        r.fileCount++;
        Long b = r.bytesByKind.get(kind);
        r.bytesByKind.put(kind, (b == null ? 0 : b) + size);
        Integer c = r.countByKind.get(kind);
        r.countByKind.put(kind, (c == null ? 0 : c) + 1);

        if (heap.size() < LARGEST_COUNT || size > heap.peek().size) {
            heap.add(FileOps.Item.of(f));
            if (heap.size() > LARGEST_COUNT) heap.poll();
        }
        if (size >= DUPLICATE_MIN_BYTES) {
            List<File> l = r.sameSize.get(size);
            if (l == null) {
                l = new ArrayList<>(2);
                r.sameSize.put(size, l);
            }
            l.add(f);
        }
        JunkKind jk = junkKindOf(f);
        if (jk != null) r.junk.add(new Junk(jk, f, size));
        if (progress != null && r.fileCount % 500 == 0) progress.onProgress(r.fileCount, f.getParentFile() == null ? "" : f.getParentFile().getName());
    }

    static JunkKind junkKindOf(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        String ext = FileOps.extension(n);
        if (n.startsWith(".trashed-")) return JunkKind.TRASHED;
        if (ext.equals("part") || ext.equals("crdownload") || ext.equals("partial") || ext.equals("download")) return JunkKind.INCOMPLETE;
        if (ext.equals("tmp") || ext.equals("temp")) return JunkKind.TEMP;
        if (ext.equals("apk")) {
            File p = f.getParentFile();
            if (p != null && (p.getName().equalsIgnoreCase("Download") || p.getName().equalsIgnoreCase("Downloads"))) return JunkKind.INSTALLERS;
        }
        return null;
    }

    // ------------------------------------------------------------------ duplicates

    /** Identical files (same size, then same content). Biggest waste first. */
    public static List<DupGroup> findDuplicates(Result r, Progress progress, AtomicBoolean cancel) {
        List<DupGroup> out = new ArrayList<>();
        long checked = 0;
        for (Map.Entry<Long, List<File>> e : r.sameSize.entrySet()) {
            if (cancel != null && cancel.get()) break;
            List<File> same = e.getValue();
            if (same.size() < 2) continue;
            // cheap fingerprint first: first and last 64 KB
            Map<String, List<File>> byEdges = new HashMap<>();
            for (File f : same) {
                String key = edgeHash(f, e.getKey());
                if (key == null) continue;
                List<File> l = byEdges.get(key);
                if (l == null) {
                    l = new ArrayList<>();
                    byEdges.put(key, l);
                }
                l.add(f);
            }
            for (List<File> cand : byEdges.values()) {
                if (cand.size() < 2) continue;
                Map<String, List<File>> byFull = new HashMap<>();
                for (File f : cand) {
                    if (cancel != null && cancel.get()) break;
                    String h = fullHash(f, cancel);
                    if (h == null) continue;
                    List<File> l = byFull.get(h);
                    if (l == null) {
                        l = new ArrayList<>();
                        byFull.put(h, l);
                    }
                    l.add(f);
                    checked++;
                    if (progress != null) progress.onProgress(checked, f.getName());
                }
                for (List<File> g : byFull.values()) {
                    if (g.size() < 2) continue;
                    Collections.sort(g, new Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            int c = Long.compare(a.lastModified(), b.lastModified());
                            return c != 0 ? c : a.getPath().compareTo(b.getPath());
                        }
                    });
                    out.add(new DupGroup(e.getKey(), g));
                }
            }
        }
        Collections.sort(out, new Comparator<DupGroup>() {
            @Override public int compare(DupGroup a, DupGroup b) { return Long.compare(b.wasted(), a.wasted()); }
        });
        return out;
    }

    private static String edgeHash(File f, long size) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                byte[] buf = new byte[64 * 1024];
                int n = raf.read(buf);
                if (n > 0) md.update(buf, 0, n);
                if (size > buf.length * 2L) {
                    raf.seek(size - buf.length);
                    n = raf.read(buf);
                    if (n > 0) md.update(buf, 0, n);
                }
            } finally {
                raf.close();
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String fullHash(File f, AtomicBoolean cancel) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) return null;
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** For each group, every file except the oldest (the likely original). */
    public static List<File> extraCopies(List<DupGroup> groups) {
        List<File> out = new ArrayList<>();
        for (DupGroup g : groups) out.addAll(g.files.subList(1, g.files.size()));
        return out;
    }
}
