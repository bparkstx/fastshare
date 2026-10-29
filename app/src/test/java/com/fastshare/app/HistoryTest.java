package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class HistoryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static History.Entry e(String key, String name, boolean ok) {
        return new History.Entry(key, true, name, 1234, 1_700_000_000_000L, "192.168.1.5", ok, ok ? "Downloads/FileBandit" : "Paused");
    }

    @Test public void entriesSurviveARestartNewestFirst() throws Exception {
        File f = new File(tmp.getRoot(), "sub/history.tsv");
        History h = new History(f);
        h.record(e("a", "first.jpg", true));
        h.record(e("b", "second.mp4", false));
        History again = new History(f);
        List<History.Entry> l = again.list();
        assertEquals(2, l.size());
        assertEquals("second.mp4", l.get(0).name);
        assertEquals("first.jpg", l.get(1).name);
        assertTrue(l.get(1).ok);
        assertFalse(l.get(0).ok);
        assertEquals("Paused", l.get(0).detail);
        assertEquals(1234, l.get(1).size);
        assertTrue(l.get(1).incoming);
    }

    @Test public void aResumedTransferReplacesItsEarlierEntry() throws Exception {
        History h = new History(new File(tmp.getRoot(), "h.tsv"));
        h.record(e("k1", "movie.mp4", false));
        h.record(e("other", "x.txt", true));
        h.record(e("k1", "movie.mp4", true));
        List<History.Entry> l = h.list();
        assertEquals(2, l.size());
        assertEquals("movie.mp4", l.get(0).name);
        assertTrue(l.get(0).ok);
    }

    @Test public void oddNamesCannotBreakTheFile() throws Exception {
        File f = new File(tmp.getRoot(), "h.tsv");
        History h = new History(f);
        h.record(e("k", "tab\there\nnewline.txt", true));
        h.record(e("k2", "fotó 写真.jpg", true));
        List<History.Entry> l = new History(f).list();
        assertEquals(2, l.size());
        assertEquals("fotó 写真.jpg", l.get(0).name);
        assertEquals("tab here newline.txt", l.get(1).name);
    }

    @Test public void historyIsCappedAndClearable() throws Exception {
        File f = new File(tmp.getRoot(), "h.tsv");
        History h = new History(f);
        for (int i = 0; i < History.MAX + 25; i++) h.record(e("k" + i, "f" + i, true));
        assertEquals(History.MAX, h.list().size());
        assertEquals("f" + (History.MAX + 24), h.list().get(0).name);
        h.clear();
        assertEquals(0, h.list().size());
        assertFalse(f.exists());
        assertEquals(0, new History(f).list().size());
    }

    @Test public void damagedLinesAreSkipped() throws Exception {
        File f = new File(tmp.getRoot(), "h.tsv");
        Files.write(f.toPath(), ("garbage\nk\t1\tok.txt\t5\t6\t1.1.1.1\t1\tHere\nk\t1\tbad\tx\t6\t1.1.1.1\t1\tHere\n").getBytes("UTF-8"));
        List<History.Entry> l = new History(f).list();
        assertEquals(1, l.size());
        assertEquals("ok.txt", l.get(0).name);
    }
}
