package com.fastshare.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies new photos and videos to a server folder. Safe to stop at any moment and to run again:
 * a file is sent under a temporary name and renamed when complete, and only files that are fully there are remembered.
 * No Android imports.
 */
public final class Backup {

    public static final class Options {
        /** Folders on this phone to look through (subfolders included). */
        public List<File> folders = new ArrayList<>();
        /** Folder on the server everything goes into, for example "/Backups/Pixel 8". */
        public String destRoot = "/FileBandit Backup";
        /** Sort into destRoot/2026/03/ by the date the file was taken or saved. */
        public boolean byDate = true;
        public boolean includePhotos = true;
        public boolean includeVideos = true;
        /** Files changed within this long are left for next time, because the camera or an app may still be writing them. */
        public long minAgeMs = 60_000;
        /** Stop after this many files in a row fail (the server went away). */
        public int maxConsecutiveFailures = 3;
    }

    public static final class Summary {
        public int uploaded;
        public int skipped;      // already on the server
        public int failed;
        public long bytes;
        public boolean cancelled;
        public boolean stoppedEarly;   // the server stopped answering
        public final List<String> errors = new ArrayList<>();
    }

    public interface Listener {
        /** About to send {@code name}; {@code index} of {@code total}. */
        void file(String name, int index, int total);
        void bytes(long n);
        boolean cancelled();
    }

    static final String PART_SUFFIX = ".fbpart";
    private static final Set<String> PHOTO = new HashSet<>(java.util.Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "dng", "bmp", "avif"));
    private static final Set<String> VIDEO = new HashSet<>(java.util.Arrays.asList("mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi"));

    private Backup() { }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ finding what to send

    /** Every photo and video under the chosen folders that is old enough to be finished, oldest first. */
    public static List<File> scan(Options o, long now) {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File folder : o.folders) walk(folder, o, now, out, seen, 0);
        Collections.sort(out, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        return out;
    }

    private static void walk(File f, Options o, long now, List<File> out, Set<String> seen, int depth) {
        if (depth > 12 || f == null) return;
        String name = f.getName();
        if (name.startsWith(".") || name.equals("Android")) return;          // hidden: thumbnails, trash, app data
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            for (File k : kids) walk(k, o, now, out, seen, depth + 1);
            return;
        }
        if (!f.isFile() || name.startsWith("pending-") || name.contains(".trashed-")) return;   // still being written, or in the system bin
        String e = ext(name);
        boolean photo = PHOTO.contains(e);
        boolean video = VIDEO.contains(e);
        if (!(photo && o.includePhotos) && !(video && o.includeVideos)) return;
        if (now - f.lastModified() < o.minAgeMs) return;
        try {
            if (!seen.add(f.getCanonicalPath())) return;
        } catch (IOException ex) {
            return;
        }
        out.add(f);
    }

    // ------------------------------------------------------------------ where a file goes

    static String remoteFolder(Options o, File f) {
        String root = Remote.Paths.clean(o.destRoot);
        if (!o.byDate) return root;
        Date d = new Date(f.lastModified());
        return Remote.Paths.join(Remote.Paths.join(root, new SimpleDateFormat("yyyy", Locale.US).format(d)), new SimpleDateFormat("MM", Locale.US).format(d));
    }

    /** "IMG_1.jpg" -> "IMG_1 (2).jpg" */
    static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name + " (" + n + ")" : name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    // ------------------------------------------------------------------ the run

    public static Summary run(Remote r, Options o, BackupLog log, Listener l) {
        Summary s = new Summary();
        List<File> files = scan(o, System.currentTimeMillis());
        Map<String, Map<String, Long>> remoteSizes = new HashMap<>();   // folder -> name -> size
        Set<String> madeDirs = new HashSet<>();
        int consecutive = 0;
        int index = 0;
        for (File f : files) {
            index++;
            if (l != null && l.cancelled()) {
                s.cancelled = true;
                break;
            }
            String key = BackupLog.keyOf(f);
            if (log.has(key)) {
                s.skipped++;
                continue;
            }
            long size = f.length();
            try {
                String folder = remoteFolder(o, f);
                ensureFolder(r, folder, madeDirs);
                Map<String, Long> there = remoteSizes.get(folder);
                if (there == null) {
                    there = new HashMap<>();
                    for (Remote.Item it : r.list(folder)) if (!it.dir) there.put(it.name, it.size);
                    remoteSizes.put(folder, there);
                }
                String name = f.getName();
                Long existing = there.get(name);
                if (existing != null && existing == size) {          // same name and size: already there (from an earlier install or run)
                    log.put(key, Remote.Paths.join(folder, name), size);
                    log.save();
                    s.skipped++;
                    consecutive = 0;
                    continue;
                }
                for (int n = 2; there.containsKey(name); n++) name = numbered(f.getName(), n);   // a different file with the same name
                if (l != null) l.file(f.getName(), index, files.size());
                String tmp = Remote.Paths.join(folder, name + PART_SUFFIX);
                String dest = Remote.Paths.join(folder, name);
                InputStream in = new FileInputStream(f);
                try {
                    r.put(tmp, l == null ? in : new Remote.CountingStream(in, new Remote.Progress() {
                        @Override public boolean cancelled() { return l.cancelled(); }
                        @Override public void bytes(long n) { l.bytes(n); }
                    }), size);
                } catch (IOException e) {
                    try { r.delete(tmp, false); } catch (IOException ignored) { }     // don't leave half a file behind
                    throw e;
                } finally {
                    in.close();
                }
                r.rename(tmp, dest);
                there.put(name, size);
                log.put(key, dest, size);
                log.save();
                s.uploaded++;
                s.bytes += size;
                consecutive = 0;
            } catch (IOException e) {
                if (l != null && l.cancelled()) {
                    s.cancelled = true;
                    break;
                }
                s.failed++;
                if (s.errors.size() < 20) s.errors.add(f.getName() + ": " + e.getMessage());
                if (++consecutive >= o.maxConsecutiveFailures) {
                    s.stoppedEarly = true;
                    break;
                }
            }
        }
        return s;
    }

    private static void ensureFolder(Remote r, String folder, Set<String> made) throws IOException {
        if (made.contains(folder)) return;
        String path = "";
        for (String part : Remote.Paths.clean(folder).split("/")) {
            if (part.isEmpty()) continue;
            path = path + "/" + part;
            if (made.contains(path)) continue;
            try {
                r.mkdir(path);
            } catch (IOException exists) {
                r.list(path);            // fine if it was already there; this throws if it really can't be used
            }
            made.add(path);
        }
        made.add(folder);
    }

    // ------------------------------------------------------------------ freeing space afterwards

    /** Files in the backup log that are still on the phone and unchanged. */
    public static List<File> alreadyBackedUp(Options o, BackupLog log) {
        List<File> out = new ArrayList<>();
        for (File f : scan(o, System.currentTimeMillis())) if (log.has(BackupLog.keyOf(f))) out.add(f);
        return out;
    }

    /**
     * Of the files the log says were sent, those the server really still has (same name, same size). Asked fresh, so a
     * file deleted from the server by hand is never treated as safe to remove from the phone.
     */
    public static List<File> verified(Remote r, List<File> candidates, BackupLog log) throws IOException {
        Map<String, Map<String, Long>> cache = new HashMap<>();
        List<File> ok = new ArrayList<>();
        for (File f : candidates) {
            BackupLog.Entry e = log.get(BackupLog.keyOf(f));
            if (e == null) continue;
            String folder = Remote.Paths.parent(e.remotePath);
            Map<String, Long> there = cache.get(folder);
            if (there == null) {
                there = new HashMap<>();
                try {
                    for (Remote.Item it : r.list(folder)) if (!it.dir) there.put(it.name, it.size);
                } catch (IOException missing) {
                    // the folder is gone: nothing in it is safe
                }
                cache.put(folder, there);
            }
            Long size = there.get(Remote.Paths.name(e.remotePath));
            if (size != null && size == f.length()) ok.add(f);
        }
        return ok;
    }
}
