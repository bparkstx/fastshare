package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class AnalyzerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File blob(File dir, String rel, int size, long seed) throws IOException {
        File f = new File(dir, rel);
        f.getParentFile().mkdirs();
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        Files.write(f.toPath(), b);
        return f;
    }

    @Test public void totalsAndTypes() throws Exception {
        File root = tmp.newFolder();
        blob(root, "DCIM/a.jpg", 300_000, 1);
        blob(root, "DCIM/b.jpg", 200_000, 2);
        blob(root, "Movies/v.mp4", 900_000, 3);
        blob(root, "Documents/d.pdf", 50_000, 4);
        blob(root, "notes.txt", 10, 5);

        Analyzer.Result r = Analyzer.scan(root, null, null);
        assertEquals(1_450_010, r.totalBytes);
        assertEquals(5, r.fileCount);
        assertEquals(500_000, (long) r.bytesByKind.get(FileOps.Kind.IMAGE));
        assertEquals(2, (int) r.countByKind.get(FileOps.Kind.IMAGE));
        assertEquals(900_000, (long) r.bytesByKind.get(FileOps.Kind.VIDEO));

        assertEquals("v.mp4", r.largest.get(0).name);
        assertEquals("a.jpg", r.largest.get(1).name);
        assertEquals("Movies", r.folders.get(0).folder.getName());
        assertEquals(900_000, r.folders.get(0).size);
    }

    @Test public void largestKeepsOnlyTheTopOnes() throws Exception {
        File root = tmp.newFolder();
        for (int i = 1; i <= Analyzer.LARGEST_COUNT + 20; i++) blob(root, "f" + i + ".bin", i, i);
        Analyzer.Result r = Analyzer.scan(root, null, null);
        assertEquals(Analyzer.LARGEST_COUNT, r.largest.size());
        assertEquals(Analyzer.LARGEST_COUNT + 20, r.largest.get(0).size);
        assertEquals(21, r.largest.get(r.largest.size() - 1).size);
    }

    @Test public void findsRealDuplicatesAndIgnoresLookAlikes() throws Exception {
        File root = tmp.newFolder();
        File a = blob(root, "one/photo.jpg", 400_000, 7);
        File b = new File(root, "two/photo copy.jpg");
        b.getParentFile().mkdirs();
        Files.copy(a.toPath(), b.toPath());
        File c = new File(root, "three/p.jpg");
        c.getParentFile().mkdirs();
        Files.copy(a.toPath(), c.toPath());
        a.setLastModified(1_000_000_000L);
        b.setLastModified(2_000_000_000L);
        c.setLastModified(3_000_000_000L);
        blob(root, "same-size-different.jpg", 400_000, 99);        // same length, different bytes
        // differs only in the middle: the quick edge check passes, the full hash must catch it
        File mid = new File(root, "middle.jpg");
        Files.copy(a.toPath(), mid.toPath());
        try (RandomAccessFile raf = new RandomAccessFile(mid, "rw")) {
            raf.seek(200_000);
            raf.write(new byte[]{0x55, 0x66, 0x77});
        }
        blob(root, "tiny1.txt", 10, 1);                               // too small to bother with
        blob(root, "tiny2.txt", 10, 1);

        Analyzer.Result r = Analyzer.scan(root, null, null);
        List<Analyzer.DupGroup> dups = Analyzer.findDuplicates(r, null, null);
        assertEquals(1, dups.size());
        Analyzer.DupGroup g = dups.get(0);
        assertEquals(3, g.files.size());
        assertEquals("oldest is first", "photo.jpg", g.files.get(0).getName());
        assertEquals(800_000, g.wasted());
        List<File> extras = Analyzer.extraCopies(dups);
        assertEquals(2, extras.size());
        assertFalse(extras.contains(a));
    }

    @Test public void findsLeftoversButNotNormalFiles() throws Exception {
        File root = tmp.newFolder();
        blob(root, "DCIM/.thumbnails/t1.jpg", 5000, 1);
        blob(root, "DCIM/.thumbnails/t2.jpg", 6000, 2);
        blob(root, "Download/setup.apk", 3000, 3);
        blob(root, "Other/setup.apk", 3000, 4);                      // an apk elsewhere isn't flagged
        blob(root, "Download/movie.mp4.part", 8000, 5);
        blob(root, "Pictures/.trashed-1750000000-IMG.jpg", 7000, 6);
        blob(root, "cache/work.tmp", 100, 7);
        new File(root, "Empty/nested").mkdirs();
        blob(root, "Keep/important.docx", 4000, 8);

        Analyzer.Result r = Analyzer.scan(root, null, null);
        java.util.Map<Analyzer.JunkKind, Long> by = new java.util.EnumMap<>(Analyzer.JunkKind.class);
        for (Analyzer.Junk j : r.junk) {
            Long cur = by.get(j.kind);
            by.put(j.kind, (cur == null ? 0 : cur) + j.size);
        }
        assertEquals(11_000, (long) by.get(Analyzer.JunkKind.THUMBNAILS));
        assertEquals(3000, (long) by.get(Analyzer.JunkKind.INSTALLERS));
        assertEquals(8000, (long) by.get(Analyzer.JunkKind.INCOMPLETE));
        assertEquals(7000, (long) by.get(Analyzer.JunkKind.TRASHED));
        assertEquals(100, (long) by.get(Analyzer.JunkKind.TEMP));
        assertTrue(by.containsKey(Analyzer.JunkKind.EMPTY_FOLDERS));
        for (Analyzer.Junk j : r.junk) {
            assertFalse(j.file.getName().equals("important.docx"));
            if (j.kind == Analyzer.JunkKind.INSTALLERS) assertEquals("Download", j.file.getParentFile().getName());
        }
        assertEquals(29_100, r.junkBytes());
    }

    @Test public void theRecycleBinIsCountedSeparately() throws Exception {
        File root = tmp.newFolder();
        File f = blob(root, "gone.bin", 12_345, 1);
        blob(root, "stay.bin", 100, 2);
        new Trash(root).trash(f);
        Analyzer.Result r = Analyzer.scan(root, null, null);
        assertEquals(12_345, r.trashBytes);
        assertEquals("trash isn't part of the normal totals", 100, r.totalBytes);
    }

    @Test public void symlinksAreNotFollowed() throws Exception {
        File root = tmp.newFolder();
        File real = blob(root, "real/data.bin", 50_000, 1);
        try {
            Files.createSymbolicLink(Paths.get(root.getPath(), "link"), real.getParentFile().toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return;      // filesystem without symlinks
        }
        Analyzer.Result r = Analyzer.scan(root, null, null);
        assertEquals(50_000, r.totalBytes);
        assertEquals(1, r.fileCount);
    }

    @Test public void cancelStopsEarly() throws Exception {
        File root = tmp.newFolder();
        for (int i = 0; i < 20; i++) blob(root, "d" + i + "/f.bin", 1000, i);
        Analyzer.Result r = Analyzer.scan(root, null, new AtomicBoolean(true));
        assertEquals(0, r.fileCount);
    }

    @Test public void reportsProgress() throws Exception {
        File root = tmp.newFolder();
        for (int i = 0; i < 1200; i++) blob(root, "f" + i, 1, i);
        final int[] calls = {0};
        Analyzer.scan(root, (n, where) -> calls[0]++, null);
        assertTrue(calls[0] >= 2);
    }
}
