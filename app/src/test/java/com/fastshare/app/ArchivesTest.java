package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ArchivesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private File write(File dir, String name, String text) throws IOException {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b(text));
        return f;
    }

    private void tarEntries(TarArchiveOutputStream t) throws IOException {
        for (String[] e : new String[][]{{"a.txt", "alpha"}, {"sub/b.txt", "beta"}}) {
            TarArchiveEntry te = new TarArchiveEntry(e[0]);
            te.setSize(e[1].length());
            t.putArchiveEntry(te);
            t.write(b(e[1]));
            t.closeArchiveEntry();
        }
        t.finish();
    }

    private void expectAlphaBeta(File dir) throws IOException {
        assertEquals("alpha", read(new File(dir, "a.txt")));
        assertEquals("beta", read(new File(dir, "sub/b.txt")));
    }

    @Test public void namesAndFormats() {
        assertEquals(Archives.Format.ZIP, Archives.formatOf("a.ZIP"));
        assertEquals(Archives.Format.TAR_GZ, Archives.formatOf("x.tar.gz"));
        assertEquals(Archives.Format.TAR_GZ, Archives.formatOf("x.tgz"));
        assertEquals(Archives.Format.TAR_XZ, Archives.formatOf("x.tar.xz"));
        assertEquals(Archives.Format.SEVENZ, Archives.formatOf("x.7z"));
        assertEquals(Archives.Format.RAR, Archives.formatOf("x.rar"));
        assertEquals(Archives.Format.GZ, Archives.formatOf("notes.txt.gz"));
        assertNull(Archives.formatOf("movie.mp4"));
        assertNull(Archives.formatOf(".zip"));
        assertEquals("photos", Archives.baseName("photos.tar.gz"));
        assertEquals("a.b", Archives.baseName("a.b.zip"));
        assertEquals("plain", Archives.baseName("plain"));
    }

    @Test public void extractsZipIntoAFolderNamedAfterIt() throws Exception {
        File dir = tmp.newFolder();
        File zip = new File(dir, "bundle.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("a.txt")); z.write(b("alpha")); z.closeEntry();
            z.putNextEntry(new ZipEntry("sub/")); z.closeEntry();
            z.putNextEntry(new ZipEntry("sub/b.txt")); z.write(b("beta")); z.closeEntry();
        }
        AtomicLong bytes = new AtomicLong();
        File out = Archives.extract(zip, dir, null, bytes::addAndGet);
        assertEquals("bundle", out.getName());
        expectAlphaBeta(out);
        assertEquals(9, bytes.get());
        assertEquals("second run avoids the existing folder", "bundle (1)", Archives.extract(zip, dir, null, null).getName());
    }

    @Test public void extractsTarGzBz2Xz() throws Exception {
        File dir = tmp.newFolder();
        File gz = new File(dir, "one.tar.gz");
        try (TarArchiveOutputStream t = new TarArchiveOutputStream(new GzipCompressorOutputStream(new FileOutputStream(gz)))) { tarEntries(t); }
        File bz = new File(dir, "two.tar.bz2");
        try (TarArchiveOutputStream t = new TarArchiveOutputStream(new BZip2CompressorOutputStream(new FileOutputStream(bz)))) { tarEntries(t); }
        File xz = new File(dir, "three.tar.xz");
        try (TarArchiveOutputStream t = new TarArchiveOutputStream(new XZCompressorOutputStream(new FileOutputStream(xz)))) { tarEntries(t); }
        File tar = new File(dir, "four.tar");
        try (TarArchiveOutputStream t = new TarArchiveOutputStream(new FileOutputStream(tar))) { tarEntries(t); }

        for (File a : Arrays.asList(gz, bz, xz, tar)) {
            File out = Archives.extract(a, dir, null, null);
            expectAlphaBeta(out);
            assertEquals(Archives.baseName(a.getName()), out.getName());
        }
    }

    @Test public void extractsSevenZ() throws Exception {
        File dir = tmp.newFolder();
        File src = tmp.newFolder();
        write(src, "a.txt", "alpha");
        write(src, "sub/b.txt", "beta");
        File z = new File(dir, "pack.7z");
        try (SevenZOutputFile out = new SevenZOutputFile(z)) {
            for (String n : new String[]{"a.txt", "sub/b.txt"}) {
                SevenZArchiveEntry e = out.createArchiveEntry(new File(src, n), n);
                out.putArchiveEntry(e);
                out.write(Files.readAllBytes(new File(src, n).toPath()));
                out.closeArchiveEntry();
            }
        }
        expectAlphaBeta(Archives.extract(z, dir, null, null));
    }

    @Test public void extractsRar() throws Exception {
        File dir = tmp.newFolder();
        File rar = new File(dir, "stored.rar");
        try (InputStream in = getClass().getResourceAsStream("/archives/stored.rar")) {
            Files.copy(in, rar.toPath());
        }
        File out = Archives.extract(rar, dir, null, null);
        assertEquals("hello from rar\n", read(new File(out, "hello.txt")));
        assertEquals("# readme\nsecond file\n", read(new File(out, "docs/readme.md")));
    }

    @Test public void extractsSingleGzipFile() throws Exception {
        File dir = tmp.newFolder();
        File gz = new File(dir, "notes.txt.gz");
        try (OutputStream o = new GzipCompressorOutputStream(new FileOutputStream(gz))) { o.write(b("just text")); }
        File out = Archives.extract(gz, dir, null, null);
        assertEquals("just text", read(new File(out, "notes.txt")));
    }

    @Test public void refusesPathTraversalInEveryFormatAndLeavesNothingBehind() throws Exception {
        File dir = tmp.newFolder();
        File evilZip = new File(dir, "evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(evilZip))) {
            z.putNextEntry(new ZipEntry("ok.txt")); z.write(b("fine")); z.closeEntry();
            z.putNextEntry(new ZipEntry("../escaped.txt")); z.write(b("nope")); z.closeEntry();
        }
        File evilTar = new File(dir, "evil.tar");
        try (TarArchiveOutputStream t = new TarArchiveOutputStream(new FileOutputStream(evilTar))) {
            TarArchiveEntry te = new TarArchiveEntry("../../escaped2.txt");
            te.setSize(4);
            t.putArchiveEntry(te); t.write(b("nope")); t.closeArchiveEntry(); t.finish();
        }
        File evil7z = new File(dir, "evil.7z");
        File src = write(tmp.newFolder(), "x.txt", "nope");
        try (SevenZOutputFile out = new SevenZOutputFile(evil7z)) {
            SevenZArchiveEntry e = out.createArchiveEntry(src, "../escaped3.txt");
            out.putArchiveEntry(e); out.write(b("nope")); out.closeArchiveEntry();
        }
        for (File a : Arrays.asList(evilZip, evilTar, evil7z)) {
            try {
                Archives.extract(a, dir, null, null);
                fail("must refuse " + a.getName());
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Unsafe"));
            }
        }
        for (String n : new String[]{"escaped.txt", "escaped2.txt", "escaped3.txt"}) assertFalse(new File(dir.getParentFile(), n).exists());
        assertFalse("half-extracted folder is removed", new File(dir, "evil").exists());
    }

    @Test public void cancelStopsAndCleansUp() throws Exception {
        File dir = tmp.newFolder();
        File zip = new File(dir, "big.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (int i = 0; i < 5; i++) { z.putNextEntry(new ZipEntry("f" + i)); z.write(new byte[1000]); z.closeEntry(); }
        }
        AtomicBoolean cancel = new AtomicBoolean(true);
        try {
            Archives.extract(zip, dir, cancel, null);
            fail();
        } catch (IOException e) {
            assertEquals("Cancelled", e.getMessage());
        }
        assertFalse(new File(dir, "big").exists());
    }

    @Test public void brokenArchivesGiveReadableErrors() throws Exception {
        File dir = tmp.newFolder();
        File junk = write(dir, "junk.zip", "this is not a zip");
        try {
            Archives.extract(junk, dir, null, null);
            fail();
        } catch (IOException expected) { }
        assertFalse(new File(dir, "junk").exists());
        File junk7 = write(dir, "junk.7z", "this is not a 7z");
        try {
            Archives.extract(junk7, dir, null, null);
            fail();
        } catch (IOException expected) { }
        File junkRar = write(dir, "junk.rar", "this is not a rar");
        try {
            Archives.extract(junkRar, dir, null, null);
            fail();
        } catch (IOException expected) { }
    }

    @Test public void compressZipKeepsFoldersAndRoundTrips() throws Exception {
        File dir = tmp.newFolder();
        File folder = new File(dir, "Trip");
        write(folder, "a.txt", "alpha");
        write(folder, "deep/b.txt", "beta");
        File single = write(dir, "notes.txt", "note");
        List<File> items = new ArrayList<>(Arrays.asList(folder, single));

        AtomicLong bytes = new AtomicLong();
        File zip = Archives.compressZip(items, dir, "My bundle", bytes::addAndGet, null);
        assertEquals("My bundle.zip", zip.getName());
        assertEquals(13, bytes.get());
        assertFalse("no .part left behind", new File(dir, "My bundle.zip.part").exists());

        File out = Archives.extract(zip, dir, null, null);
        assertEquals("alpha", read(new File(out, "Trip/a.txt")));
        assertEquals("beta", read(new File(out, "Trip/deep/b.txt")));
        assertEquals("note", read(new File(out, "notes.txt")));
        assertEquals("second archive gets a free name", "My bundle (1).zip",
                Archives.compressZip(items, dir, "My bundle.zip", null, null).getName());
    }

    @Test public void compressDisambiguatesSameNamedItemsAndSkipsItself() throws Exception {
        File dir = tmp.newFolder();
        File a = write(new File(dir, "one"), "same.txt", "1");
        File b = write(new File(dir, "two"), "same.txt", "2");
        File zip = Archives.compressZip(Arrays.asList(a, b), dir, "pair", null, null);
        File out = Archives.extract(zip, dir, null, null);
        assertEquals("1", read(new File(out, "same.txt")));
        assertEquals("2", read(new File(out, "same (1).txt")));

        // zipping the folder that will contain the zip must not zip the zip into itself
        File zip2 = Archives.compressZip(Arrays.asList(dir), dir, "everything", null, null);
        assertTrue(zip2.length() > 0);
    }

    @Test public void cancelledCompressLeavesNothing() throws Exception {
        File dir = tmp.newFolder();
        File f = write(dir, "a.txt", "alpha");
        try {
            Archives.compressZip(Arrays.asList(f), dir, "x", null, new AtomicBoolean(true));
            fail();
        } catch (IOException e) {
            assertEquals("Cancelled", e.getMessage());
        }
        assertFalse(new File(dir, "x.zip").exists());
        assertFalse(new File(dir, "x.zip.part").exists());
    }
}
