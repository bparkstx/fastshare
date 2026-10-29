package com.fastshare.app;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Creates zip files and unpacks zip, 7z, rar, tar, tar.gz, tar.bz2, tar.xz, gz, bz2 and xz. */
public final class Archives {
    private Archives() { }

    public enum Format { ZIP, SEVENZ, RAR, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ }

    private static final String[][] SUFFIXES = {
            {".tar.gz", "TAR_GZ"}, {".tgz", "TAR_GZ"}, {".tar.bz2", "TAR_BZ2"}, {".tbz2", "TAR_BZ2"}, {".tbz", "TAR_BZ2"},
            {".tar.xz", "TAR_XZ"}, {".txz", "TAR_XZ"}, {".tar", "TAR"}, {".zip", "ZIP"}, {".7z", "SEVENZ"}, {".rar", "RAR"},
            {".gz", "GZ"}, {".bz2", "BZ2"}, {".xz", "XZ"},
    };

    /** The archive format a file name suggests, or null if it isn't one we can unpack. */
    public static Format formatOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SUFFIXES) {
            if (n.endsWith(s[0]) && n.length() > s[0].length()) return Format.valueOf(s[1]);
        }
        return null;
    }

    /** "photos.tar.gz" becomes "photos". */
    public static String baseName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SUFFIXES) {
            if (n.endsWith(s[0]) && n.length() > s[0].length()) return name.substring(0, name.length() - s[0].length());
        }
        return name;
    }

    // ------------------------------------------------------------------ safety

    /** Resolves an entry name inside {@code dest}, refusing anything that would land outside it. */
    static File safeTarget(File dest, String entryName) throws IOException {
        String n = entryName.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty()) throw new IOException("Unsafe path in archive");
        File out = new File(dest, n);
        String base = dest.getCanonicalPath();
        String path = out.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) {
            throw new IOException("Unsafe path in archive: " + entryName);
        }
        return out;
    }

    private static void cancelCheck(AtomicBoolean cancel) throws IOException {
        if (cancel != null && cancel.get()) throw new IOException("Cancelled");
    }

    private static void copyOut(InputStream in, File target, long mtime, AtomicBoolean cancel, LongConsumer bytes) throws IOException {
        if (target.getParentFile() != null) target.getParentFile().mkdirs();
        OutputStream os = new FileOutputStream(target);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                cancelCheck(cancel);
                os.write(buf, 0, n);
                if (bytes != null) bytes.accept(n);
            }
        } finally {
            os.close();
        }
        if (mtime > 0) target.setLastModified(mtime);
    }

    /** Counts the bytes read from the archive file itself, so progress reflects how far through it we are. */
    private static final class Counting extends FilterInputStream {
        private final LongConsumer sink;

        Counting(InputStream in, LongConsumer sink) {
            super(in);
            this.sink = sink;
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && sink != null) sink.accept(1);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && sink != null) sink.accept(n);
            return n;
        }
    }

    // ------------------------------------------------------------------ extract

    /**
     * Unpacks an archive into a new folder named after it, next to {@code destParent}'s other files.
     * {@code bytes} receives how many bytes of the archive were consumed (or written, for zip).
     * Nothing is left behind if it fails or is cancelled.
     */
    public static File extract(File archive, File destParent, AtomicBoolean cancel, LongConsumer bytes) throws IOException {
        Format fmt = formatOf(archive.getName());
        if (fmt == null) throw new IOException("Not an archive FileBandit can open");
        File dest = FileOps.uniqueSibling(destParent, baseName(archive.getName()));
        if (!dest.mkdirs()) throw new IOException("Can't create " + dest.getName());
        boolean ok = false;
        try {
            switch (fmt) {
                case ZIP: extractZip(archive, dest, cancel, bytes); break;
                case SEVENZ: extractSevenZ(archive, dest, cancel, bytes); break;
                case RAR: extractRar(archive, dest, cancel, bytes); break;
                case TAR: extractTar(archive, dest, fmt, cancel, bytes); break;
                case TAR_GZ: case TAR_BZ2: case TAR_XZ: extractTar(archive, dest, fmt, cancel, bytes); break;
                default: extractSingle(archive, dest, fmt, cancel, bytes);
            }
            ok = true;
            return dest;
        } finally {
            if (!ok) FileOps.delete(dest);
        }
    }

    private static void extractZip(File archive, File dest, AtomicBoolean cancel, LongConsumer bytes) throws IOException {
        ZipFile zf = new ZipFile(archive);
        try {
            Set<String> seen = new HashSet<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                cancelCheck(cancel);
                ZipEntry e = en.nextElement();
                File out = safeTarget(dest, e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                if (!seen.add(out.getCanonicalPath())) continue;
                InputStream in;
                try {
                    in = zf.getInputStream(e);
                } catch (IOException enc) {
                    throw new IOException("This zip is password protected or damaged");
                }
                try {
                    copyOut(in, out, e.getTime(), cancel, bytes);
                } catch (java.util.zip.ZipException z) {
                    throw new IOException("This zip is password protected or damaged");
                } finally {
                    in.close();
                }
            }
        } finally {
            zf.close();
        }
    }

    private static void extractSevenZ(File archive, File dest, AtomicBoolean cancel, LongConsumer bytes) throws IOException {
        SevenZFile z;
        try {
            z = SevenZFile.builder().setFile(archive).get();
        } catch (IOException e) {
            throw new IOException("Couldn't open this 7z file (" + e.getMessage() + "). It may be password protected.");
        }
        try {
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                cancelCheck(cancel);
                File out = safeTarget(dest, e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        cancelCheck(cancel);
                        os.write(buf, 0, n);
                        if (bytes != null) bytes.accept(n);
                    }
                } finally {
                    os.close();
                }
                if (e.getHasLastModifiedDate()) out.setLastModified(e.getLastModifiedDate().getTime());
            }
        } finally {
            z.close();
        }
    }

    private static void extractRar(File archive, File dest, AtomicBoolean cancel, LongConsumer bytes) throws IOException {
        Archive rar = null;
        try {
            rar = new Archive(archive);
            if (rar.isEncrypted()) throw new IOException("This rar is password protected");
            FileHeader fh;
            while ((fh = rar.nextFileHeader()) != null) {
                cancelCheck(cancel);
                if (fh.isEncrypted()) throw new IOException("This rar is password protected");
                File out = safeTarget(dest, fh.getFileName());
                if (fh.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                OutputStream os = new FileOutputStream(out);
                try {
                    rar.extractFile(fh, os);
                } finally {
                    os.close();
                }
                if (bytes != null) bytes.accept(out.length());
                if (fh.getMTime() != null) out.setLastModified(fh.getMTime().getTime());
            }
        } catch (RarException e) {
            throw new IOException("Couldn't read this rar file (" + e.getMessage() + ")");
        } finally {
            if (rar != null) rar.close();
        }
    }

    private static void extractTar(File archive, File dest, Format fmt, AtomicBoolean cancel, LongConsumer bytes) throws IOException {
        InputStream raw = new BufferedInputStream(new Counting(new FileInputStream(archive), bytes), 65536);
        InputStream in = raw;
        try {
            if (fmt == Format.TAR_GZ) in = new GzipCompressorInputStream(raw);
            else if (fmt == Format.TAR_BZ2) in = new BZip2CompressorInputStream(raw);
            else if (fmt == Format.TAR_XZ) in = new XZCompressorInputStream(raw);
            TarArchiveInputStream tar = new TarArchiveInputStream(in);
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                cancelCheck(cancel);
                if (e.isSymbolicLink() || e.isLink()) continue;     // never create links from an archive
                File out = safeTarget(dest, e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                copyOut(tar, out, e.getModTime() == null ? 0 : e.getModTime().getTime(), cancel, null);
            }
        } finally {
            raw.close();
        }
    }

    private static void extractSingle(File archive, File dest, Format fmt, AtomicBoolean cancel, LongConsumer bytes) throws IOException {
        InputStream raw = new BufferedInputStream(new Counting(new FileInputStream(archive), bytes), 65536);
        try {
            InputStream in;
            if (fmt == Format.GZ) in = new GzipCompressorInputStream(raw);
            else if (fmt == Format.BZ2) in = new BZip2CompressorInputStream(raw);
            else in = new XZCompressorInputStream(raw);
            String name = baseName(archive.getName());
            copyOut(in, safeTarget(dest, name), archive.lastModified(), cancel, null);
        } finally {
            raw.close();
        }
    }

    // ------------------------------------------------------------------ compress

    /**
     * Zips files and folders into {@code destDir/<name>.zip} (a free name is chosen if it exists).
     * Returns the new zip. The zip appears under its final name only once it is complete.
     */
    public static File compressZip(List<File> sources, File destDir, String name, LongConsumer bytes, AtomicBoolean cancel)
            throws IOException {
        String zipName = name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name : name + ".zip";
        File target = FileOps.uniqueSibling(destDir, zipName);
        File part = new File(destDir, target.getName() + ".part");
        boolean ok = false;
        ZipOutputStream zos = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(part), 65536));
        try {
            zos.setLevel(5);
            Set<String> used = new HashSet<>();
            byte[] buf = new byte[256 * 1024];
            for (File src : sources) {
                String top = src.getName();
                for (int i = 1; !used.add(top.toLowerCase(Locale.ROOT)); i++) {
                    int dot = src.getName().lastIndexOf('.');
                    top = dot > 0 ? src.getName().substring(0, dot) + " (" + i + ")" + src.getName().substring(dot)
                            : src.getName() + " (" + i + ")";
                }
                addToZip(zos, src, top, part, buf, bytes, cancel);
            }
            zos.finish();
            zos.close();
            if (!part.renameTo(target)) throw new IOException("Couldn't save " + target.getName());
            ok = true;
            return target;
        } finally {
            if (!ok) {
                try { zos.close(); } catch (IOException ignored) { }
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    private static void addToZip(ZipOutputStream zos, File f, String path, File skip, byte[] buf,
                                 LongConsumer bytes, AtomicBoolean cancel) throws IOException {
        cancelCheck(cancel);
        if (f.equals(skip)) return;
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(path + "/"));
            zos.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) {
                java.util.Arrays.sort(kids);
                for (File k : kids) addToZip(zos, k, path + "/" + k.getName(), skip, buf, bytes, cancel);
            }
        } else if (f.isFile()) {
            ZipEntry e = new ZipEntry(path);
            e.setTime(f.lastModified());
            zos.putNextEntry(e);
            InputStream in = new FileInputStream(f);
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    cancelCheck(cancel);
                    zos.write(buf, 0, n);
                    if (bytes != null) bytes.accept(n);
                }
            } finally {
                in.close();
            }
            zos.closeEntry();
        }
    }
}
