package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Random;

public class BackupTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File phone;
    private BackupLog log;
    private MemoryRemote server;
    private Backup.Options opts;
    private static final long NOW = System.currentTimeMillis();

    private static long date(int y, int m, int d) {
        return new GregorianCalendar(y, Calendar.JANUARY + m - 1, d, 12, 0).getTimeInMillis();
    }

    private File photo(String rel, int size, long modified) throws IOException {
        File f = new File(phone, rel);
        f.getParentFile().mkdirs();
        byte[] b = new byte[size];
        new Random(rel.hashCode()).nextBytes(b);
        Files.write(f.toPath(), b);
        assertTrue(f.setLastModified(modified));
        return f;
    }

    @Before public void setUp() throws Exception {
        phone = tmp.newFolder("phone");
        log = new BackupLog(new File(tmp.getRoot(), "log.tsv"));
        server = new MemoryRemote();
        opts = new Backup.Options();
        opts.folders.add(new File(phone, "DCIM"));
        opts.destRoot = "/Backups/Pixel";
    }

    @Test public void scanTakesPhotosAndVideosOldestFirstAndSkipsUnfinishedOrHiddenThings() throws Exception {
        photo("DCIM/Camera/new.jpg", 10, date(2026, 3, 5));
        photo("DCIM/Camera/old.JPG", 10, date(2025, 1, 2));
        photo("DCIM/Camera/clip.mp4", 10, date(2026, 1, 1));
        photo("DCIM/Camera/notes.txt", 10, date(2026, 1, 1));
        photo("DCIM/.thumbnails/t.jpg", 10, date(2026, 1, 1));
        photo("DCIM/Camera/pending-1700000000-IMG.jpg", 10, date(2026, 1, 1));
        photo("DCIM/Camera/.trashed-1700000000-IMG.jpg", 10, date(2026, 1, 1));
        photo("DCIM/Camera/justnow.jpg", 10, NOW - 5_000);
        photo("Pictures/elsewhere.jpg", 10, date(2026, 1, 1));         // not in a chosen folder
        List<String> names = new ArrayList<>();
        for (File f : Backup.scan(opts, NOW)) names.add(f.getName());
        assertEquals(Arrays.asList("old.JPG", "clip.mp4", "new.jpg"), names);

        opts.includeVideos = false;
        names.clear();
        for (File f : Backup.scan(opts, NOW)) names.add(f.getName());
        assertEquals(Arrays.asList("old.JPG", "new.jpg"), names);
        opts.includePhotos = false;
        opts.includeVideos = true;
        names.clear();
        for (File f : Backup.scan(opts, NOW)) names.add(f.getName());
        assertEquals(Arrays.asList("clip.mp4"), names);
    }

    @Test public void filesLandInYearAndMonthFoldersAndAreIntact() throws Exception {
        File a = photo("DCIM/Camera/a.jpg", 5000, date(2026, 3, 5));
        File b = photo("DCIM/Camera/b.mp4", 90_000, date(2025, 12, 31));
        Backup.Summary s = Backup.run(server, opts, log, null);
        assertEquals(2, s.uploaded);
        assertEquals(0, s.failed);
        assertEquals(95_000, s.bytes);
        assertEquals(Arrays.asList("/Backups/Pixel/2025/12/b.mp4", "/Backups/Pixel/2026/03/a.jpg"), server.allFiles());
        assertArrayEquals(Files.readAllBytes(a.toPath()), server.files.get("/Backups/Pixel/2026/03/a.jpg"));
        assertArrayEquals(Files.readAllBytes(b.toPath()), server.files.get("/Backups/Pixel/2025/12/b.mp4"));
        assertFalse("no half-sent files are left", server.allFiles().toString().contains(Backup.PART_SUFFIX));
        assertEquals(2, log.count());
    }

    @Test public void secondRunSendsOnlyWhatIsNew() throws Exception {
        photo("DCIM/Camera/a.jpg", 100, date(2026, 3, 5));
        assertEquals(1, Backup.run(server, opts, log, null).uploaded);
        Backup.Summary again = Backup.run(server, opts, log, null);
        assertEquals(0, again.uploaded);
        assertEquals(1, again.skipped);
        photo("DCIM/Camera/b.jpg", 100, date(2026, 3, 6));
        Backup.Summary third = Backup.run(server, opts, new BackupLog(new File(tmp.getRoot(), "log.tsv")), null);   // log reloaded from disk
        assertEquals(1, third.uploaded);
        assertEquals(1, third.skipped);
        assertEquals(2, server.allFiles().size());
    }

    @Test public void aFileThatChangedIsSentAgainWithoutOverwritingTheOldCopy() throws Exception {
        File f = photo("DCIM/Camera/a.jpg", 100, date(2026, 3, 5));
        Backup.run(server, opts, log, null);
        Files.write(f.toPath(), new byte[250]);                     // edited: new size
        f.setLastModified(date(2026, 3, 5));
        Backup.Summary s = Backup.run(server, opts, log, null);
        assertEquals(1, s.uploaded);
        assertEquals(Arrays.asList("/Backups/Pixel/2026/03/a (2).jpg", "/Backups/Pixel/2026/03/a.jpg"), server.allFiles());
        assertEquals(100, server.files.get("/Backups/Pixel/2026/03/a.jpg").length);
        assertEquals(250, server.files.get("/Backups/Pixel/2026/03/a (2).jpg").length);
    }

    @Test public void aCopyAlreadyOnTheServerIsRecognisedEvenWithoutALog() throws Exception {
        File f = photo("DCIM/Camera/a.jpg", 100, date(2026, 3, 5));
        Backup.run(server, opts, log, null);
        server.puts.clear();
        BackupLog fresh = new BackupLog(new File(tmp.getRoot(), "fresh.tsv"));       // after reinstalling the app
        Backup.Summary s = Backup.run(server, opts, fresh, null);
        assertEquals(0, s.uploaded);
        assertEquals(1, s.skipped);
        assertTrue("nothing was sent again", server.puts.isEmpty());
        assertTrue(fresh.has(BackupLog.keyOf(f)));
    }

    @Test public void oneBadFileDoesNotStopTheRestButADeadServerDoes() throws Exception {
        for (int i = 1; i <= 6; i++) photo("DCIM/Camera/p" + i + ".jpg", 100, date(2026, 3, i));
        server.failPuts = 1;
        Backup.Summary s = Backup.run(server, opts, log, null);
        assertEquals(1, s.failed);
        assertEquals(5, s.uploaded);
        assertFalse(s.stoppedEarly);
        assertEquals(1, s.errors.size());
        assertTrue(s.errors.get(0), s.errors.get(0).startsWith("p1.jpg: Disk full"));
        // the failed one is tried again next time
        Backup.Summary next = Backup.run(server, opts, log, null);
        assertEquals(1, next.uploaded);
        assertEquals(5, next.skipped);

        for (int i = 7; i <= 12; i++) photo("DCIM/Camera/q" + i + ".jpg", 100, date(2026, 4, i - 6));
        server.failAll = true;
        Backup.Summary dead = Backup.run(server, opts, log, null);
        assertTrue(dead.stoppedEarly);
        assertEquals(3, dead.failed);          // gave up after three in a row, not all six
        assertEquals(0, dead.uploaded);
    }

    @Test public void cancellingStopsPromptlyAndLeavesNoPartialFile() throws Exception {
        for (int i = 1; i <= 5; i++) photo("DCIM/Camera/p" + i + ".jpg", 100, date(2026, 3, i));
        final int[] files = {0};
        Backup.Summary s = Backup.run(server, opts, log, new Backup.Listener() {
            @Override public void file(String name, int index, int total) { files[0]++; }
            @Override public void bytes(long n) { }
            @Override public boolean cancelled() { return files[0] >= 2; }
        });
        assertTrue(s.cancelled);
        assertEquals(1, s.uploaded);
        assertFalse(server.allFiles().toString().contains(Backup.PART_SUFFIX));
        assertEquals(1, log.count());
    }

    @Test public void flatLayoutAndNestedFoldersWork() throws Exception {
        photo("DCIM/Camera/a.jpg", 10, date(2026, 3, 5));
        photo("DCIM/Screens/deep/b.png", 10, date(2026, 3, 6));
        opts.byDate = false;
        opts.destRoot = "Photos";
        Backup.run(server, opts, log, null);
        assertEquals(Arrays.asList("/Photos/a.jpg", "/Photos/b.png"), server.allFiles());
    }

    @Test public void sameNameFromTwoFoldersDoesNotClash() throws Exception {
        photo("DCIM/Camera/IMG_1.jpg", 100, date(2026, 3, 5));
        photo("DCIM/Other/IMG_1.jpg", 200, date(2026, 3, 5));
        Backup.run(server, opts, log, null);
        assertEquals(Arrays.asList("/Backups/Pixel/2026/03/IMG_1 (2).jpg", "/Backups/Pixel/2026/03/IMG_1.jpg"), server.allFiles());
        assertEquals(2, log.count());
    }

    @Test public void onlyFilesStillOnTheServerCountAsSafeToRemoveFromThePhone() throws Exception {
        File a = photo("DCIM/Camera/a.jpg", 100, date(2026, 3, 5));
        File b = photo("DCIM/Camera/b.jpg", 100, date(2026, 3, 6));
        File c = photo("DCIM/Camera/c.jpg", 100, date(2026, 3, 7));
        File d = photo("DCIM/Camera/d.jpg", 100, date(2026, 4, 7));
        Backup.run(server, opts, log, null);
        List<File> backed = Backup.alreadyBackedUp(opts, log);
        assertEquals(4, backed.size());
        server.files.remove("/Backups/Pixel/2026/03/b.jpg");                         // deleted on the server by hand
        server.files.put("/Backups/Pixel/2026/03/c.jpg", new byte[7]);              // replaced by something else
        server.dirs.remove("/Backups/Pixel/2026/04");                               // whole folder gone
        server.files.remove("/Backups/Pixel/2026/04/d.jpg");
        List<File> safe = Backup.verified(server, backed, log);
        assertEquals(Arrays.asList(a), safe);
    }

    @Test public void logSurvivesOddCharactersAndDamage() throws Exception {
        File f = tmp.newFile("log2.tsv");
        BackupLog l = new BackupLog(f);
        l.put("/p/we\tird\nname.jpg|1|2", "/r/we\tird.jpg", 1);
        l.save();
        assertEquals(1, new BackupLog(f).count());
        Files.write(f.toPath(), "junk\nonly\ttwo\nk\tr\tx\ty\n".getBytes("UTF-8"));
        assertEquals(0, new BackupLog(f).count());
    }
}
