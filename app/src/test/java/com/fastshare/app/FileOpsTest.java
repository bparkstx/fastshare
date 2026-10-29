package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class FileOpsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(File dir, String name, String text) throws IOException {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void kindsAndMimeFollowTheExtension() {
        assertEquals(FileOps.Kind.IMAGE, FileOps.kindOfName("IMG_0001.HEIC"));
        assertEquals(FileOps.Kind.VIDEO, FileOps.kindOfName("clip.mp4"));
        assertEquals(FileOps.Kind.AUDIO, FileOps.kindOfName("song.mp3"));
        assertEquals(FileOps.Kind.APK, FileOps.kindOfName("app.apk"));
        assertEquals(FileOps.Kind.ARCHIVE, FileOps.kindOfName("a.zip"));
        assertEquals(FileOps.Kind.DOCUMENT, FileOps.kindOfName("a.pdf"));
        assertEquals(FileOps.Kind.OTHER, FileOps.kindOfName("noextension"));
        assertEquals(FileOps.Kind.OTHER, FileOps.kindOfName("trailingdot."));
        assertEquals("image/jpeg", FileOps.mimeOfName("a.jpg"));
        assertEquals("video/mp4", FileOps.mimeOfName("a.mp4"));
        assertEquals("application/vnd.android.package-archive", FileOps.mimeOfName("a.apk"));
        assertEquals("application/octet-stream", FileOps.mimeOfName("a.weird"));
    }

    @Test public void listPutsFoldersFirstAndHidesDotFiles() throws Exception {
        File root = tmp.newFolder();
        write(root, "b.txt", "bb");
        write(root, "a.txt", "a");
        write(root, ".hidden", "x");
        new File(root, "zdir").mkdir();
        new File(root, "adir").mkdir();

        List<FileOps.Item> items = FileOps.list(root, false, FileOps.Sort.NAME, true);
        assertEquals(4, items.size());
        assertEquals("adir", items.get(0).name);
        assertEquals("zdir", items.get(1).name);
        assertEquals("a.txt", items.get(2).name);
        assertEquals("b.txt", items.get(3).name);

        assertEquals(5, FileOps.list(root, true, FileOps.Sort.NAME, true).size());

        List<FileOps.Item> desc = FileOps.list(root, false, FileOps.Sort.SIZE, false);
        assertEquals("zdir", desc.get(0).name);      // folders still first
        assertEquals("b.txt", desc.get(2).name);     // biggest file first when descending
    }

    @Test public void searchFindsNestedFilesAndHonoursLimitAndCancel() throws Exception {
        File root = tmp.newFolder();
        write(root, "a/b/holiday.jpg", "1");
        write(root, "a/Holiday2.png", "2");
        write(root, "c/other.txt", "3");
        write(root, ".secret/holiday.txt", "4");

        assertEquals(2, FileOps.search(root, "HOLIDAY", false, 50, null).size());
        assertEquals(3, FileOps.search(root, "holiday", true, 50, null).size());
        assertEquals(1, FileOps.search(root, "holiday", false, 1, null).size());
        assertEquals(0, FileOps.search(root, "   ", false, 50, null).size());
        assertEquals(0, FileOps.search(root, "holiday", false, 50, new AtomicBoolean(true)).size());
    }

    @Test public void uniqueSiblingAddsCounterBeforeExtension() throws Exception {
        File dir = tmp.newFolder();
        assertEquals("a.txt", FileOps.uniqueSibling(dir, "a.txt").getName());
        write(dir, "a.txt", "x");
        assertEquals("a (1).txt", FileOps.uniqueSibling(dir, "a.txt").getName());
        write(dir, "a (1).txt", "x");
        assertEquals("a (2).txt", FileOps.uniqueSibling(dir, "a.txt").getName());
        write(dir, "noext", "x");
        assertEquals("noext (1)", FileOps.uniqueSibling(dir, "noext").getName());
    }

    @Test public void checkNameRejectsBadNames() {
        assertNotNull(FileOps.checkName(""));
        assertNotNull(FileOps.checkName("  "));
        assertNotNull(FileOps.checkName(".."));
        assertNotNull(FileOps.checkName("a/b"));
        assertNull(FileOps.checkName("My file (1).txt"));
    }

    @Test public void copyFolderRecursivelyAndReportBytes() throws Exception {
        File root = tmp.newFolder();
        File src = new File(root, "src");
        write(src, "one.txt", "hello");
        write(src, "sub/two.txt", "world!");
        File dest = new File(root, "dest");
        dest.mkdir();

        final AtomicLong bytes = new AtomicLong();
        File copy = FileOps.copy(src, dest, new java.util.function.LongConsumer() {
            @Override public void accept(long v) { bytes.addAndGet(v); }
        }, null);
        assertEquals("src", copy.getName());
        assertEquals("hello", read(new File(copy, "one.txt")));
        assertEquals("world!", read(new File(copy, "sub/two.txt")));
        assertEquals(11, bytes.get());
        assertTrue(src.exists());

        File again = FileOps.copy(src, dest, null, null);
        assertEquals("src (1)", again.getName());
    }

    @Test public void copyFolderIntoItselfIsRefused() throws Exception {
        File root = tmp.newFolder();
        File src = new File(root, "src");
        write(src, "a.txt", "a");
        new File(src, "inner").mkdir();
        try {
            FileOps.copy(src, new File(src, "inner"), null, null);
            fail("expected an error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("itself"));
        }
        try {
            FileOps.move(src, src, null, null);
        } catch (IOException ignored) { }
        assertTrue(new File(src, "a.txt").exists());
    }

    @Test public void moveRenamesAndAvoidsOverwriting() throws Exception {
        File root = tmp.newFolder();
        File a = write(root, "a/file.txt", "A");
        File b = new File(root, "b");
        b.mkdir();
        write(b, "file.txt", "B");

        File moved = FileOps.move(a, b, null, null);
        assertEquals("file (1).txt", moved.getName());
        assertEquals("A", read(moved));
        assertEquals("B", read(new File(b, "file.txt")));
        assertFalse(a.exists());

        // moving to the folder it is already in does nothing
        assertEquals(moved, FileOps.move(moved, b, null, null));
        assertTrue(moved.exists());
    }

    @Test public void renameChecksNamesAndConflicts() throws Exception {
        File dir = tmp.newFolder();
        File a = write(dir, "a.txt", "A");
        write(dir, "b.txt", "B");
        try {
            FileOps.rename(a, "b.txt");
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("already exists"));
        }
        try {
            FileOps.rename(a, "x/y");
            fail();
        } catch (IOException ignored) { }
        File r = FileOps.rename(a, "c.txt");
        assertEquals("c.txt", r.getName());
        assertEquals("A", read(r));
    }

    @Test public void deleteRemovesTrees() throws Exception {
        File root = tmp.newFolder();
        File d = new File(root, "d");
        write(d, "x/y/z.txt", "z");
        assertTrue(FileOps.delete(d));
        assertFalse(d.exists());
        assertTrue(root.exists());
    }

    @Test public void newFolderValidates() throws Exception {
        File dir = tmp.newFolder();
        File f = FileOps.newFolder(dir, "Photos");
        assertTrue(f.isDirectory());
        try {
            FileOps.newFolder(dir, "Photos");
            fail();
        } catch (IOException ignored) { }
    }

    @Test public void sizeOfSumsFolder() throws Exception {
        File root = tmp.newFolder();
        write(root, "a", "12345");
        write(root, "s/b", "123");
        assertEquals(8, FileOps.sizeOf(root, null));
    }

    @Test public void zipRoundTrip() throws Exception {
        File root = tmp.newFolder();
        File src = new File(root, "My Folder");
        write(src, "a.txt", "alpha");
        write(src, "deep/b.txt", "beta");
        new File(src, "empty").mkdir();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileOps.zipDirectory(src, bos);

        File zip = new File(root, "My Folder.zip");
        try (FileOutputStream fos = new FileOutputStream(zip)) { fos.write(bos.toByteArray()); }
        File out = FileOps.unzip(zip, null);
        assertEquals("My Folder (1)", out.getName());
        assertEquals("alpha", read(new File(out, "My Folder/a.txt")));
        assertEquals("beta", read(new File(out, "My Folder/deep/b.txt")));
        assertTrue(new File(out, "My Folder/empty").isDirectory());
    }

    @Test public void unzipRejectsPathTraversal() throws Exception {
        File root = tmp.newFolder();
        File zip = new File(root, "evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("../escaped.txt"));
            z.write("nope".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        try {
            FileOps.unzip(zip, null);
            fail("zip slip must be refused");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Unsafe"));
        }
        assertFalse(new File(root, "escaped.txt").exists());
    }

    @Test public void fmtBytesIsReadable() {
        assertEquals("0 B", Fmt.bytes(0));
        assertEquals("999 B", Fmt.bytes(999));
        assertEquals("1.5 KB", Fmt.bytes(1500));
        assertEquals("12.3 MB", Fmt.bytes(12_300_000));
        assertEquals("2.0 GB", Fmt.bytes(2_000_000_000L));
        assertEquals("", Fmt.bytes(-1));
    }
}
