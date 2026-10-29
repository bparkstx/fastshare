package com.fastshare.app;

import java.io.Closeable;
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
import java.util.Locale;

/**
 * A folder tree on another computer or server (FTP, SFTP, SMB, WebDAV). Paths always look like "/Photos/2026", use "/"
 * and start at the location's own root. No Android imports, so the real protocols can be tested on a plain JVM.
 */
public interface Remote extends Closeable {

    final class Item {
        public final String name;
        public final String path;
        public final boolean dir;
        public final long size;       // -1 unknown
        public final long modified;   // 0 unknown

        public Item(String name, String path, boolean dir, long size, long modified) {
            this.name = name;
            this.path = path;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    /** Lets long copies report bytes and be stopped. */
    interface Progress {
        boolean cancelled();
        void bytes(long n);
    }

    /** Folders first, then names, ignoring case. */
    Comparator<Item> ORDER = new Comparator<Item>() {
        @Override public int compare(Item a, Item b) {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
        }
    };

    /** The folder's contents, without "." and "..". */
    List<Item> list(String path) throws IOException;

    /** Reads a file starting at {@code offset}. The caller closes the stream. */
    InputStream open(String path, long offset) throws IOException;

    /** Writes (or replaces) a file. {@code size} is -1 when unknown. Does not close {@code in}. */
    void put(String path, InputStream in, long size) throws IOException;

    void mkdir(String path) throws IOException;

    /** Deletes a file, or a folder with everything inside it. */
    void delete(String path, boolean dir) throws IOException;

    void rename(String from, String to) throws IOException;

    // ------------------------------------------------------------------ path helpers

    final class Paths {
        private Paths() { }

        /** "a//b/", "\\a\\b" and "" become "/a/b" and "/". */
        public static String clean(String p) {
            if (p == null) return "/";
            String s = p.replace('\\', '/');
            StringBuilder out = new StringBuilder();
            for (String part : s.split("/")) {
                if (part.isEmpty() || part.equals(".")) continue;
                if (part.equals("..")) {
                    int cut = out.lastIndexOf("/");
                    out.setLength(Math.max(cut, 0));
                    continue;
                }
                out.append('/').append(part);
            }
            return out.length() == 0 ? "/" : out.toString();
        }

        public static String join(String dir, String name) {
            String d = clean(dir);
            return d.equals("/") ? "/" + name : d + "/" + name;
        }

        public static String parent(String p) {
            String c = clean(p);
            int i = c.lastIndexOf('/');
            return i <= 0 ? "/" : c.substring(0, i);
        }

        public static String name(String p) {
            String c = clean(p);
            return c.substring(c.lastIndexOf('/') + 1);
        }
    }

    // ------------------------------------------------------------------ copying whole trees

    final class Copy {
        private Copy() { }

        private static final int BUF = 256 * 1024;

        /** A name from a server can't be trusted to stay inside the folder it is saved in. */
        static String safeName(String n) {
            String s = n == null ? "" : n.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
            if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "_" + s;
            return s.length() > 200 ? s.substring(0, 200) : s;
        }

        /** Downloads a file or folder into {@code destDir}. Returns what was created. Half-written files are removed on failure. */
        public static File download(Remote r, Item item, File destDir, Progress p) throws IOException {
            if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("Can't create " + destDir.getName());
            File dest = FileOps.uniqueSibling(destDir, safeName(item.name));
            if (item.dir) {
                if (!dest.mkdirs()) throw new IOException("Can't create " + dest.getName());
                for (Item child : r.list(item.path)) {
                    if (p != null && p.cancelled()) break;
                    download(r, child, dest, p);
                }
                return dest;
            }
            InputStream in = r.open(item.path, 0);
            OutputStream out = null;
            boolean ok = false;
            try {
                out = new FileOutputStream(dest);
                byte[] buf = new byte[BUF];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (p != null && p.cancelled()) throw new IOException("Cancelled");
                    out.write(buf, 0, n);
                    if (p != null) p.bytes(n);
                }
                out.close();
                out = null;
                if (item.modified > 0) //noinspection ResultOfMethodCallIgnored
                    dest.setLastModified(item.modified);
                ok = true;
            } finally {
                try { in.close(); } catch (IOException ignored) { }
                if (out != null) {
                    try { out.close(); } catch (IOException ignored) { }
                }
                if (!ok) //noinspection ResultOfMethodCallIgnored
                    dest.delete();
            }
            return dest;
        }

        /** Uploads a file or folder into the remote folder {@code destDir}. */
        public static int upload(Remote r, File src, String destDir, Progress p) throws IOException {
            String target = Paths.join(destDir, src.getName());
            if (src.isDirectory()) {
                try {
                    r.mkdir(target);
                } catch (IOException exists) {
                    // it may simply be there already; listing it tells us
                    r.list(target);
                }
                int n = 0;
                File[] kids = src.listFiles();
                if (kids != null) {
                    List<File> sorted = new ArrayList<>();
                    Collections.addAll(sorted, kids);
                    Collections.sort(sorted);
                    for (File k : sorted) {
                        if (p != null && p.cancelled()) break;
                        n += upload(r, k, target, p);
                    }
                }
                return n;
            }
            InputStream in = new FileInputStream(src);
            try {
                r.put(target, p == null ? in : new CountingStream(in, p), src.length());
            } finally {
                in.close();
            }
            return 1;
        }
    }

    /** Reports every byte that passes through, and stops when asked. */
    final class CountingStream extends InputStream {
        private final InputStream in;
        private final Progress p;

        public CountingStream(InputStream in, Progress p) {
            this.in = in;
            this.p = p;
        }

        @Override public int read() throws IOException {
            if (p.cancelled()) throw new IOException("Cancelled");
            int b = in.read();
            if (b >= 0) p.bytes(1);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (p.cancelled()) throw new IOException("Cancelled");
            int n = in.read(b, off, len);
            if (n > 0) p.bytes(n);
            return n;
        }

        @Override public void close() throws IOException {
            in.close();
        }
    }
}
