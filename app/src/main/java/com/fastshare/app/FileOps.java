package com.fastshare.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** File manager operations on plain java.io.File. No Android classes, so it can be unit tested on a laptop. */
public final class FileOps {
    private FileOps() { }

    public enum Kind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, TEXT, OTHER }

    public enum Sort { NAME, DATE, SIZE, TYPE }

    /** One row in a folder listing. */
    public static final class Item {
        public final File file;
        public final String name;
        public final boolean dir;
        public final long size;       // bytes, files only
        public final long modified;   // epoch millis
        public final int children;    // direct children for folders, -1 for files / unknown
        public final Kind kind;

        Item(File f, boolean dir, long size, long modified, int children) {
            this.file = f;
            this.name = f.getName();
            this.dir = dir;
            this.size = size;
            this.modified = modified;
            this.children = children;
            this.kind = dir ? Kind.FOLDER : kindOfName(f.getName());
        }

        public static Item of(File f) {
            boolean d = f.isDirectory();
            int kids = -1;
            if (d) {
                String[] names = f.list();
                kids = names == null ? 0 : names.length;
            }
            return new Item(f, d, d ? 0 : f.length(), f.lastModified(), kids);
        }
    }

    // ------------------------------------------------------------------ types

    private static final Map<String, Kind> EXT = new HashMap<>();
    private static final Map<String, String> MIME = new HashMap<>();

    private static void reg(Kind k, String mimePrefix, String... exts) {
        for (String e : exts) {
            EXT.put(e, k);
            if (mimePrefix != null) MIME.put(e, mimePrefix.endsWith("/") ? mimePrefix + e : mimePrefix);
        }
    }

    static {
        reg(Kind.IMAGE, "image/", "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif");
        MIME.put("jpg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        EXT.put("svg", Kind.IMAGE);
        MIME.put("heic", "image/heic");
        MIME.put("heif", "image/heif");
        reg(Kind.VIDEO, "video/", "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "wmv");
        MIME.put("mov", "video/quicktime");
        MIME.put("mkv", "video/x-matroska");
        MIME.put("avi", "video/x-msvideo");
        MIME.put("3gp", "video/3gpp");
        MIME.put("wmv", "video/x-ms-wmv");
        MIME.put("flv", "video/x-flv");
        reg(Kind.AUDIO, "audio/", "mp3", "wav", "ogg", "flac", "aac", "m4a", "opus", "amr", "wma");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("wma", "audio/x-ms-wma");
        reg(Kind.DOCUMENT, null, "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "pages", "numbers", "key");
        MIME.put("pdf", "application/pdf");
        MIME.put("doc", "application/msword");
        MIME.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME.put("xls", "application/vnd.ms-excel");
        MIME.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME.put("ppt", "application/vnd.ms-powerpoint");
        MIME.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        MIME.put("epub", "application/epub+zip");
        MIME.put("rtf", "application/rtf");
        reg(Kind.ARCHIVE, null, "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz");
        MIME.put("zip", "application/zip");
        MIME.put("rar", "application/vnd.rar");
        MIME.put("7z", "application/x-7z-compressed");
        MIME.put("tar", "application/x-tar");
        MIME.put("gz", "application/gzip");
        reg(Kind.APK, "application/vnd.android.package-archive", "apk");
        reg(Kind.TEXT, "text/", "txt", "log", "md", "csv", "json", "xml", "html", "htm", "css", "js", "ini", "conf", "yml", "yaml", "java", "py", "sh", "kt", "c", "h", "cpp");
        MIME.put("txt", "text/plain");
        MIME.put("log", "text/plain");
        MIME.put("md", "text/markdown");
        MIME.put("csv", "text/csv");
        MIME.put("json", "application/json");
        MIME.put("xml", "text/xml");
        MIME.put("html", "text/html");
        MIME.put("htm", "text/html");
        MIME.put("css", "text/css");
        MIME.put("js", "text/javascript");
        MIME.put("ini", "text/plain");
        MIME.put("conf", "text/plain");
        MIME.put("yml", "text/plain");
        MIME.put("yaml", "text/plain");
        MIME.put("java", "text/plain");
        MIME.put("py", "text/plain");
        MIME.put("sh", "text/plain");
        MIME.put("kt", "text/plain");
        MIME.put("c", "text/plain");
        MIME.put("h", "text/plain");
        MIME.put("cpp", "text/plain");
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static Kind kindOfName(String name) {
        Kind k = EXT.get(extension(name));
        return k == null ? Kind.OTHER : k;
    }

    public static String mimeOfName(String name) {
        String m = MIME.get(extension(name));
        return m == null ? "application/octet-stream" : m;
    }

    // ------------------------------------------------------------------ listing

    /** Lists a folder. Folders come first; {@code ascending} only applies within each group. */
    public static List<Item> list(File dir, boolean showHidden, Sort sort, boolean ascending) {
        File[] files = dir.listFiles();
        List<Item> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            if (!showHidden && f.getName().startsWith(".")) continue;
            out.add(Item.of(f));
        }
        sortItems(out, sort, ascending);
        return out;
    }

    public static void sortItems(List<Item> items, final Sort sort, final boolean ascending) {
        Collections.sort(items, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                int c;
                switch (sort) {
                    case DATE: c = Long.compare(a.modified, b.modified); break;
                    case SIZE: c = Long.compare(a.dir ? a.children : a.size, b.dir ? b.children : b.size); break;
                    case TYPE:
                        c = extension(a.name).compareTo(extension(b.name));
                        if (c == 0) c = a.name.compareToIgnoreCase(b.name);
                        break;
                    default: c = a.name.compareToIgnoreCase(b.name);
                }
                if (c == 0) c = a.name.compareTo(b.name);
                return ascending ? c : -c;
            }
        });
    }

    /** Breadth-first, case-insensitive name search under {@code root}. Stops early at {@code limit} results. */
    public static List<Item> search(File root, String query, boolean showHidden, int limit, AtomicBoolean cancel) {
        List<Item> out = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return out;
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && out.size() < limit) {
            if (cancel != null && cancel.get()) break;
            File dir = queue.poll();
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!showHidden && f.getName().startsWith(".")) continue;
                boolean isDir = f.isDirectory();
                if (f.getName().toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(Item.of(f));
                    if (out.size() >= limit) break;
                }
                if (isDir && !isSymlink(f)) queue.add(f);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ names

    /** Returns a File in {@code dir} named like {@code name}, adding " (1)", " (2)"... before the extension if taken. */
    public static File uniqueSibling(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    /** Validates a user-typed file or folder name. Returns an error message, or null when it is fine. */
    public static String checkName(String name) {
        if (name == null || name.trim().isEmpty()) return "Name can't be empty";
        if (name.equals(".") || name.equals("..")) return "That name isn't allowed";
        if (name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) return "Name can't contain /";
        if (name.length() > 200) return "Name is too long";
        return null;
    }

    // ------------------------------------------------------------------ copy / move / delete

    private static boolean isSymlink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isInside(File parent, File child) throws IOException {
        String p = parent.getCanonicalPath();
        String c = child.getCanonicalPath();
        return c.equals(p) || c.startsWith(p.endsWith("/") ? p : p + "/");
    }

    private static void cancelCheck(AtomicBoolean cancel) throws IOException {
        if (cancel != null && cancel.get()) throw new IOException("Cancelled");
    }

    /** Copies a file or folder into {@code destDir}, never overwriting (it picks a free name). Returns the new File. */
    public static File copy(File src, File destDir, LongConsumer onBytes, AtomicBoolean cancel) throws IOException {
        if (!src.exists()) throw new IOException(src.getName() + " no longer exists");
        if (src.isDirectory() && isInside(src, destDir)) {
            throw new IOException("Can't copy a folder into itself");
        }
        File target = uniqueSibling(destDir, src.getName());
        copyInto(src, target, onBytes, cancel);
        return target;
    }

    private static void copyInto(File src, File target, LongConsumer onBytes, AtomicBoolean cancel) throws IOException {
        cancelCheck(cancel);
        if (src.isDirectory() && !isSymlink(src)) {
            if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Can't create " + target.getName());
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) copyInto(k, new File(target, k.getName()), onBytes, cancel);
            }
            target.setLastModified(src.lastModified());
            return;
        }
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    cancelCheck(cancel);
                    out.write(buf, 0, n);
                    if (onBytes != null) onBytes.accept(n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        target.setLastModified(src.lastModified());
    }

    /** Moves into {@code destDir}. Uses a rename when possible, otherwise copy then delete. Returns the new File. */
    public static File move(File src, File destDir, LongConsumer onBytes, AtomicBoolean cancel) throws IOException {
        if (!src.exists()) throw new IOException(src.getName() + " no longer exists");
        if (src.getParentFile() != null && src.getParentFile().getCanonicalPath().equals(destDir.getCanonicalPath())) {
            return src; // already there
        }
        if (src.isDirectory() && isInside(src, destDir)) {
            throw new IOException("Can't move a folder into itself");
        }
        File target = uniqueSibling(destDir, src.getName());
        if (src.renameTo(target)) return target;
        copyInto(src, target, onBytes, cancel);
        if (!delete(src)) throw new IOException("Copied, but couldn't remove the original " + src.getName());
        return target;
    }

    public static File rename(File src, String newName) throws IOException {
        String err = checkName(newName);
        if (err != null) throw new IOException(err);
        File target = new File(src.getParentFile(), newName.trim());
        if (target.equals(src)) return src;
        if (target.exists()) throw new IOException("\"" + newName.trim() + "\" already exists");
        if (!src.renameTo(target)) throw new IOException("Couldn't rename " + src.getName());
        return target;
    }

    /** Deletes a file, or a folder and everything in it. Symlinks are removed without following them. */
    public static boolean delete(File f) {
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) delete(k);
            }
        }
        return f.delete();
    }

    public static File newFolder(File parent, String name) throws IOException {
        String err = checkName(name);
        if (err != null) throw new IOException(err);
        File f = new File(parent, name.trim());
        if (f.exists()) throw new IOException("\"" + name.trim() + "\" already exists");
        if (!f.mkdirs()) throw new IOException("Couldn't create the folder");
        return f;
    }

    /** Total bytes in a file or folder tree. */
    public static long sizeOf(File f, AtomicBoolean cancel) {
        if (!f.isDirectory() || isSymlink(f)) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        for (File k : kids) {
            if (cancel != null && cancel.get()) return total;
            total += sizeOf(k, cancel);
        }
        return total;
    }

    // ------------------------------------------------------------------ zip

    /** Streams a folder as a zip into {@code out} without closing it. */
    public static void zipDirectory(File dir, OutputStream out) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        zos.setLevel(1); // speed over size: most phone files are already compressed
        byte[] buf = new byte[256 * 1024];
        zipEntries(zos, dir, dir.getName(), buf);
        zos.finish();
        zos.flush();
    }

    private static void zipEntries(ZipOutputStream zos, File f, String path, byte[] buf) throws IOException {
        if (f.isDirectory() && !isSymlink(f)) {
            zos.putNextEntry(new ZipEntry(path + "/"));
            zos.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) {
                Arrays.sort(kids);
                for (File k : kids) zipEntries(zos, k, path + "/" + k.getName(), buf);
            }
        } else if (f.isFile()) {
            ZipEntry e = new ZipEntry(path);
            e.setTime(f.lastModified());
            zos.putNextEntry(e);
            InputStream in = new FileInputStream(f);
            try {
                int n;
                while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
            } finally {
                in.close();
            }
            zos.closeEntry();
        }
    }

    /** Extracts a zip into a new folder next to it (named after the zip). Returns that folder. */
    public static File unzip(File zip, AtomicBoolean cancel) throws IOException {
        String base = zip.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File dest = uniqueSibling(zip.getParentFile(), base);
        if (!dest.mkdirs()) throw new IOException("Can't create " + dest.getName());
        String destPath = dest.getCanonicalPath() + File.separator;
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            ZipEntry e;
            byte[] buf = new byte[256 * 1024];
            Set<String> seen = new HashSet<>();
            while ((e = zin.getNextEntry()) != null) {
                cancelCheck(cancel);
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(destPath) && !out.getCanonicalPath().equals(dest.getCanonicalPath())) {
                    throw new IOException("Unsafe path in zip: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                if (!seen.add(out.getPath())) continue;
                OutputStream os = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    os.close();
                }
                if (e.getTime() > 0) out.setLastModified(e.getTime());
            }
        } finally {
            zin.close();
        }
        return dest;
    }
}
