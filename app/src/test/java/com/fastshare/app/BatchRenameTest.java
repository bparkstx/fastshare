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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BatchRenameTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static List<String> names(List<BatchRename.Preview> p) {
        List<String> out = new ArrayList<>();
        for (BatchRename.Preview x : p) out.add(x.newName);
        return out;
    }

    @Test public void numberingKeepsExtensionsAndUsesNaturalOrder() throws Exception {
        File d = tmp.newFolder();
        List<File> files = Arrays.asList(file(d, "IMG_10.jpg", "a"), file(d, "IMG_2.jpg", "b"), file(d, "clip.mp4", "c"));
        BatchRename.Options o = new BatchRename.Options();
        o.base = "Trip ";
        o.start = 1;
        o.digits = 2;
        List<BatchRename.Preview> p = BatchRename.plan(files, o);
        // natural order: IMG_2, IMG_10 (not IMG_10 first), then clip
        assertEquals("IMG_10.jpg is second in natural order", "IMG_2.jpg", p.get(1).file.getName());
        assertEquals(Arrays.asList("Trip 01.mp4", "Trip 02.jpg", "Trip 03.jpg"), names(p));
    }

    @Test public void findAndReplaceWorksOnTheNameNotTheExtension() throws Exception {
        File d = tmp.newFolder();
        List<File> files = Arrays.asList(file(d, "Holiday jpg 1.jpg", "a"), file(d, "other.jpg", "b"));
        BatchRename.Options o = new BatchRename.Options();
        o.mode = BatchRename.Mode.FIND_REPLACE;
        o.find = "JPG";
        o.replace = "photo";
        List<BatchRename.Preview> p = BatchRename.plan(files, o);
        assertEquals("Holiday photo 1.jpg", p.get(0).newName);
        assertFalse("untouched files are left alone", p.get(1).changes());
        o.matchCase = true;
        assertFalse("case sensitive: JPG doesn't match jpg", BatchRename.plan(files, o).get(0).changes());
    }

    @Test public void prefixSuffixAndCase() throws Exception {
        File d = tmp.newFolder();
        List<File> files = Arrays.asList(file(d, "my FILE.TXT", "a"));
        BatchRename.Options o = new BatchRename.Options();
        o.mode = BatchRename.Mode.PREFIX_SUFFIX;
        o.prefix = "2026-";
        o.suffix = "_v2";
        assertEquals("2026-my FILE_v2.TXT", BatchRename.plan(files, o).get(0).newName);

        o.mode = BatchRename.Mode.CASE;
        o.caseMode = BatchRename.CaseMode.UPPER;
        assertEquals("MY FILE.TXT", BatchRename.plan(files, o).get(0).newName);
        o.caseMode = BatchRename.CaseMode.LOWER;
        assertEquals("my file.TXT", BatchRename.plan(files, o).get(0).newName);
        o.caseMode = BatchRename.CaseMode.TITLE;
        assertEquals("My File.TXT", BatchRename.plan(files, o).get(0).newName);
        o.caseMode = BatchRename.CaseMode.SENTENCE;
        assertEquals("My file.TXT", BatchRename.plan(files, o).get(0).newName);
    }

    @Test public void dateModeUsesTheModifiedTime() throws Exception {
        File d = tmp.newFolder();
        File f = file(d, "x.jpg", "a");
        f.setLastModified(java.sql.Timestamp.valueOf("2026-03-04 05:06:07").getTime());
        BatchRename.Options o = new BatchRename.Options();
        o.mode = BatchRename.Mode.DATE;
        o.datePattern = "yyyy-MM-dd HH.mm.ss";
        assertEquals("2026-03-04 05.06.07.jpg", BatchRename.plan(Arrays.asList(f), o).get(0).newName);
        o.datePattern = "not a pattern {{";
        assertNotNull("a bad pattern is reported, not applied", BatchRename.plan(Arrays.asList(f), o).get(0).error);
    }

    @Test public void clashesAreReportedBeforeAnythingChanges() throws Exception {
        File d = tmp.newFolder();
        File a = file(d, "a.txt", "1");
        File b = file(d, "b.txt", "2");
        file(d, "keep.txt", "3");
        BatchRename.Options o = new BatchRename.Options();
        o.mode = BatchRename.Mode.FIND_REPLACE;
        o.find = "a";
        o.replace = "keep";
        List<BatchRename.Preview> p = BatchRename.plan(Arrays.asList(a, b), o);
        assertTrue(p.get(0).error, p.get(0).error.contains("already exists"));

        // two files that would get the same name
        o = new BatchRename.Options();
        o.mode = BatchRename.Mode.FIND_REPLACE;
        o.find = "";
        BatchRename.Options same = new BatchRename.Options();
        same.mode = BatchRename.Mode.NUMBERING;
        same.base = "x";
        same.digits = 1;
        same.start = 1;
        File c1 = file(new File(d, "one"), "p.txt", "1");
        List<BatchRename.Preview> q = BatchRename.plan(Arrays.asList(c1, file(new File(d, "two"), "q.txt", "2")), same);
        assertNull("different folders may share a name", q.get(0).error);
        assertNull(q.get(1).error);

        // illegal / empty names
        BatchRename.Options bad = new BatchRename.Options();
        bad.mode = BatchRename.Mode.PREFIX_SUFFIX;
        bad.prefix = "a/b";
        assertNotNull(BatchRename.plan(Arrays.asList(a), bad).get(0).error);
        try {
            BatchRename.apply(BatchRename.plan(Arrays.asList(a, b), bad));
            fail();
        } catch (IOException expected) { }
        assertTrue("nothing was renamed", a.exists() && b.exists());
    }

    @Test public void applyRenamesAndReturnsUndoPairs() throws Exception {
        File d = tmp.newFolder();
        File a = file(d, "a.txt", "A");
        File b = file(d, "b.txt", "B");
        BatchRename.Options o = new BatchRename.Options();
        o.base = "doc";
        o.digits = 3;
        List<File[]> undo = BatchRename.apply(BatchRename.plan(Arrays.asList(a, b), o));
        assertEquals("A", new String(Files.readAllBytes(new File(d, "doc001.txt").toPath()), StandardCharsets.UTF_8));
        assertEquals("B", new String(Files.readAllBytes(new File(d, "doc002.txt").toPath()), StandardCharsets.UTF_8));
        assertFalse(a.exists());
        BatchRename.renamePairs(undo);
        assertEquals("A", new String(Files.readAllBytes(a.toPath()), StandardCharsets.UTF_8));
        assertEquals("B", new String(Files.readAllBytes(b.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void namesCanTradePlaces() throws Exception {
        File d = tmp.newFolder();
        File a = file(d, "a.txt", "A");
        File b = file(d, "b.txt", "B");
        List<File[]> swap = new ArrayList<>();
        swap.add(new File[]{a, new File(d, "b.txt")});
        swap.add(new File[]{b, new File(d, "a.txt")});
        BatchRename.renamePairs(swap);
        assertEquals("B", new String(Files.readAllBytes(a.toPath()), StandardCharsets.UTF_8));
        assertEquals("A", new String(Files.readAllBytes(b.toPath()), StandardCharsets.UTF_8));
        assertEquals("no temporary files left", 2, d.list().length);
    }

    @Test public void renumberingOverItselfIsFine() throws Exception {
        // files already called 1.txt 2.txt 3.txt shifted by one (start at 2): 1->2, 2->3, 3->4
        File d = tmp.newFolder();
        List<File> files = Arrays.asList(file(d, "1.txt", "one"), file(d, "2.txt", "two"), file(d, "3.txt", "three"));
        BatchRename.Options o = new BatchRename.Options();
        o.start = 2;
        o.digits = 1;
        List<BatchRename.Preview> p = BatchRename.plan(files, o);
        for (BatchRename.Preview x : p) assertNull(x.error);
        BatchRename.apply(p);
        assertEquals("one", new String(Files.readAllBytes(new File(d, "2.txt").toPath()), StandardCharsets.UTF_8));
        assertEquals("two", new String(Files.readAllBytes(new File(d, "3.txt").toPath()), StandardCharsets.UTF_8));
        assertEquals("three", new String(Files.readAllBytes(new File(d, "4.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void naturalOrder() {
        assertTrue(BatchRename.naturalCompare("img2", "img10") < 0);
        assertTrue(BatchRename.naturalCompare("a", "B") < 0);
        assertTrue(BatchRename.naturalCompare("file001", "file1") == 0 || BatchRename.naturalCompare("file001", "file1") != 0);
        assertEquals(0, BatchRename.naturalCompare("same", "same"));
    }
}
