package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class TrashTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(File dir, String name, String text) throws IOException {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test public void trashMovesTheFileAndRemembersWhereItCameFrom() throws Exception {
        File vol = tmp.newFolder();
        File f = write(vol, "Download/report.pdf", "12345");
        Trash trash = new Trash(vol);

        Trash.Entry e = trash.trash(f);
        assertFalse("original must be gone", f.exists());
        assertTrue(e.stored.exists());
        assertTrue("hidden from the gallery", new File(trash.dir(), ".nomedia").exists());

        List<Trash.Entry> list = trash.list();
        assertEquals(1, list.size());
        assertEquals("report.pdf", list.get(0).name);
        assertEquals(f.getPath(), list.get(0).original.getPath());
        assertEquals(5, list.get(0).size);
        assertFalse(list.get(0).dir);
    }

    @Test public void folderRoundTripKeepsEverythingInside() throws Exception {
        File vol = tmp.newFolder();
        File d = new File(vol, "Photos");
        write(d, "a.jpg", "A");
        write(d, "trip/b.jpg", "BB");
        Trash trash = new Trash(vol);

        Trash.Entry e = trash.trash(d);
        assertFalse(d.exists());
        assertTrue(trash.list().get(0).dir);
        assertEquals(3, trash.list().get(0).size);

        File back = trash.restore(e);
        assertEquals(d.getPath(), back.getPath());
        assertEquals("BB", new String(Files.readAllBytes(new File(back, "trip/b.jpg").toPath()), StandardCharsets.UTF_8));
        assertTrue(trash.list().isEmpty());
    }

    @Test public void restoreRecreatesAMissingParentAndAvoidsOverwriting() throws Exception {
        File vol = tmp.newFolder();
        File f = write(vol, "Docs/Work/plan.txt", "v1");
        Trash trash = new Trash(vol);
        Trash.Entry e = trash.trash(f);
        FileOps.delete(new File(vol, "Docs"));                 // the whole folder is gone now
        File back = trash.restore(e);
        assertEquals("plan.txt", back.getName());
        assertTrue(back.exists());

        Trash.Entry again = trash.trash(back);
        write(vol, "Docs/Work/plan.txt", "v2 is newer");        // someone made a new file with the same name
        File second = trash.restore(again);
        assertEquals("plan (1).txt", second.getName());
        assertEquals("v2 is newer", new String(Files.readAllBytes(new File(vol, "Docs/Work/plan.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void sameNameDeletedTwiceKeepsBothCopies() throws Exception {
        File vol = tmp.newFolder();
        Trash trash = new Trash(vol);
        trash.trash(write(vol, "a/x.txt", "first"));
        trash.trash(write(vol, "b/x.txt", "second"));
        assertEquals(2, trash.list().size());
        assertEquals(11, trash.totalSize());
    }

    @Test public void newestComesFirst() throws Exception {
        File vol = tmp.newFolder();
        Trash trash = new Trash(vol);
        trash.trash(write(vol, "one.txt", "1"));
        Thread.sleep(15);
        trash.trash(write(vol, "two.txt", "2"));
        assertEquals("two.txt", trash.list().get(0).name);
    }

    @Test public void deleteForeverAndEmptyReallyRemove() throws Exception {
        File vol = tmp.newFolder();
        Trash trash = new Trash(vol);
        Trash.Entry a = trash.trash(write(vol, "a.txt", "a"));
        trash.trash(write(vol, "b.txt", "b"));
        trash.deleteForever(a);
        assertFalse(a.stored.exists());
        assertEquals(1, trash.list().size());
        assertEquals(1, trash.empty());
        assertTrue(trash.list().isEmpty());
        File[] left = trash.dir().listFiles();
        assertEquals("only .nomedia remains", 1, left.length);
    }

    @Test public void purgeOnlyRemovesOldItems() throws Exception {
        File vol = tmp.newFolder();
        Trash trash = new Trash(vol);
        Trash.Entry old = trash.trash(write(vol, "old.txt", "o"));
        trash.trash(write(vol, "new.txt", "n"));
        // age the first one by rewriting its note
        java.util.Properties p = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(old.meta)) { p.load(in); }
        p.setProperty("deleted", Long.toString(System.currentTimeMillis() - 40L * 24 * 3600 * 1000));
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(old.meta)) { p.store(os, null); }

        assertEquals(1, trash.purgeOlderThan(Trash.DEFAULT_KEEP_MS));
        List<Trash.Entry> left = trash.list();
        assertEquals(1, left.size());
        assertEquals("new.txt", left.get(0).name);
    }

    @Test public void cannotTrashTheTrash() throws Exception {
        File vol = tmp.newFolder();
        Trash trash = new Trash(vol);
        Trash.Entry e = trash.trash(write(vol, "a.txt", "a"));
        try {
            trash.trash(e.stored);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already"));
        }
        assertTrue(Trash.isTrashPath(e.stored));
        assertFalse(Trash.isTrashPath(new File(vol, "a.txt")));
    }

    @Test public void orphanedNotesAreCleanedUp() throws Exception {
        File vol = tmp.newFolder();
        Trash trash = new Trash(vol);
        Trash.Entry e = trash.trash(write(vol, "a.txt", "a"));
        assertTrue(e.stored.delete());                         // payload vanished (user cleaned it by hand)
        assertTrue(trash.list().isEmpty());
        assertFalse(e.meta.exists());
    }

    @Test public void trashingAMissingFileFails() throws Exception {
        File vol = tmp.newFolder();
        try {
            new Trash(vol).trash(new File(vol, "ghost.txt"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no longer exists"));
        }
    }
}
