package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class PhotoCleanerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File photo(String name, long modified) throws IOException {
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), new byte[]{1, 2, 3, (byte) name.length()});
        f.setLastModified(modified);
        return f;
    }

    private static class Fake implements PhotoAnalysis.Decoder {
        final Map<String, PhotoAnalysis.Gray> byName = new HashMap<>();
        final AtomicInteger decoded = new AtomicInteger();

        @Override public PhotoAnalysis.Gray decode(File f) throws IOException {
            decoded.incrementAndGet();
            PhotoAnalysis.Gray g = byName.get(f.getName());
            if (g == null) throw new IOException("not a picture");
            return new PhotoAnalysis.Gray(g.w, g.h, g.px, 4000, 3000);
        }
    }

    @Test public void scanFindsEverythingAndRemembersWhatItLearned() throws Exception {
        long now = 400L * PhotoCleaner.DAY;
        PhotoAnalysis.Gray a = PhotoAnalysisTest.scene(1, 128, 96);
        Fake d = new Fake();
        d.byName.put("a1.jpg", a);
        d.byName.put("a2.jpg", PhotoAnalysisTest.brighter(a, 5));
        d.byName.put("blur.jpg", PhotoAnalysisTest.blurred(PhotoAnalysisTest.scene(2, 128, 96), 6));
        d.byName.put("dark.jpg", PhotoAnalysisTest.flat(1, 128, 96));
        d.byName.put("Screenshot_1.png", PhotoAnalysisTest.scene(3, 128, 96));
        List<File> files = Arrays.asList(photo("a1.jpg", now - 10 * PhotoCleaner.DAY), photo("a2.jpg", now - 10 * PhotoCleaner.DAY + 5000),
                photo("blur.jpg", now - 9 * PhotoCleaner.DAY), photo("dark.jpg", now - 8 * PhotoCleaner.DAY),
                photo("Screenshot_1.png", now - 200 * PhotoCleaner.DAY), photo("broken.jpg", now - 7 * PhotoCleaner.DAY));
        File cacheFile = new File(tmp.getRoot(), "cache/stats.tsv");
        PhotoCleaner.Cache cache = new PhotoCleaner.Cache(cacheFile);
        PhotoCleaner.Report r = PhotoCleaner.scan(files, d, cache, null, now);
        assertEquals(5, r.all.size());
        assertEquals(1, r.unreadable);
        assertEquals(1, r.similar.size());
        assertEquals(2, r.similar.get(0).members.size());
        assertEquals(1, r.extraCopies());
        assertEquals(1, r.blurry.size());
        assertEquals("blur.jpg", r.blurry.get(0).file().getName());
        assertEquals(1, r.dark.size());
        assertEquals(1, r.oldScreenshots.size());
        assertEquals(6, d.decoded.get());

        // a second scan, even after a restart, only looks at what is new or unreadable
        d.decoded.set(0);
        PhotoCleaner.Report again = PhotoCleaner.scan(files, d, new PhotoCleaner.Cache(cacheFile), null, now);
        assertEquals("only the unreadable file is tried again", 1, d.decoded.get());
        assertEquals(1, again.similar.size());
        assertEquals(1, again.blurry.size());

        // a changed file is looked at again
        File changed = files.get(2);
        Files.write(changed.toPath(), new byte[50]);
        d.decoded.set(0);
        PhotoCleaner.scan(files, d, new PhotoCleaner.Cache(cacheFile), null, now);
        assertEquals(2, d.decoded.get());
    }

    @Test public void cacheForgetsFilesThatAreGone() throws Exception {
        Fake d = new Fake();
        d.byName.put("x.jpg", PhotoAnalysisTest.scene(5, 64, 48));
        d.byName.put("y.jpg", PhotoAnalysisTest.scene(6, 64, 48));
        File x = photo("x.jpg", 1000);
        File y = photo("y.jpg", 2000);
        File cacheFile = new File(tmp.getRoot(), "c.tsv");
        PhotoCleaner.scan(Arrays.asList(x, y), d, new PhotoCleaner.Cache(cacheFile), null, 10_000);
        assertEquals(2, new PhotoCleaner.Cache(cacheFile).size());
        PhotoCleaner.scan(Arrays.asList(x), d, new PhotoCleaner.Cache(cacheFile), null, 10_000);
        assertEquals(1, new PhotoCleaner.Cache(cacheFile).size());
    }

    @Test public void cancellingStopsTheScanAndSaysSo() throws Exception {
        Fake d = new Fake();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            d.byName.put("p" + i + ".jpg", PhotoAnalysisTest.scene(10 + i, 64, 48));
            files.add(photo("p" + i + ".jpg", 1000 + i));
        }
        final int[] seen = {0};
        PhotoCleaner.Report r = PhotoCleaner.scan(files, d, new PhotoCleaner.Cache(new File(tmp.getRoot(), "c.tsv")), new PhotoCleaner.Progress() {
            @Override public void step(int done, int total) { seen[0] = done; }
            @Override public boolean cancelled() { return seen[0] >= 3; }
        }, 10_000);
        assertTrue(r.cancelled);
        assertTrue("stopped part way: " + r.all.size(), r.all.size() >= 3 && r.all.size() < 6);
    }
}
