package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ContentIndexTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file(String rel, String content, long modified) throws IOException {
        File f = new File(tmp.getRoot(), rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        f.setLastModified(modified);
        return f;
    }

    private static ContentIndex.Entry entry(File f, String text, String... labels) {
        return new ContentIndex.Entry(f.getPath(), f.length(), f.lastModified(), text, Arrays.asList(labels));
    }

    private static List<String> names(List<ContentIndex.Hit> hits) {
        List<String> out = new ArrayList<>();
        for (ContentIndex.Hit h : hits) out.add(h.entry.file().getName());
        return out;
    }

    @Test public void findsWordsInTextAndLabelsAndNamesIgnoringCaseAndAccents() throws Exception {
        ContentIndex ix = new ContentIndex(new File(tmp.getRoot(), "ix.tsv"));
        File receipt = file("receipt.jpg", "x", 1);
        File dog = file("IMG_001.jpg", "y", 2);
        File invoice = file("scan.pdf", "z", 3);
        ix.put(entry(receipt, "Café Luna\nTotal 12.50 EUR\nThank you", "Text"));
        ix.put(entry(dog, "", "Dog", "Beach"));
        ix.put(entry(invoice, "INVOICE No. 4471\nAmount due: 300.00"));

        assertEquals(Arrays.asList("receipt.jpg"), names(ix.search("cafe", 10)));
        assertEquals(Arrays.asList("receipt.jpg"), names(ix.search("LUNA", 10)));
        assertEquals(Arrays.asList("IMG_001.jpg"), names(ix.search("dog", 10)));
        assertEquals(Arrays.asList("IMG_001.jpg"), names(ix.search("beach dog", 10)));
        assertEquals("every word must match", 0, ix.search("dog invoice", 10).size());
        assertEquals(Arrays.asList("scan.pdf"), names(ix.search("4471", 10)));
        assertEquals("the file name counts too", Arrays.asList("receipt.jpg"), names(ix.search("receipt", 10)));
        assertEquals(0, ix.search("   ", 10).size());
        assertEquals(0, ix.search("zebra", 10).size());
    }

    @Test public void betterMatchesComeFirst() throws Exception {
        ContentIndex ix = new ContentIndex(new File(tmp.getRoot(), "ix.tsv"));
        File photo = file("photo.jpg", "a", 10);
        File note = file("note.txt", "b", 20);
        File mention = file("mention.txt", "c", 30);
        ix.put(entry(photo, "", "Dog"));                                           // a photo OF a dog
        ix.put(entry(note, "my dog is called Rex. The dog likes walks. dog dog"));  // says "dog" a lot
        ix.put(entry(mention, "we saw a dogwood tree"));                           // only part of a word
        List<ContentIndex.Hit> hits = ix.search("dog", 10);
        assertEquals(3, hits.size());
        assertEquals("a photo OF a dog comes first, then text that mentions it, then a piece of a word: " + names(hits),
                Arrays.asList("photo.jpg", "note.txt", "mention.txt"), names(hits));
        assertEquals(1, ix.search("dog", 1).size());
    }

    @Test public void snippetsShowTheWordsAroundTheMatch() throws Exception {
        ContentIndex ix = new ContentIndex(new File(tmp.getRoot(), "ix.tsv"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("filler ");
        sb.append("the passport number is X1234567 and it expires soon ");
        for (int i = 0; i < 40; i++) sb.append("more ");
        ix.put(entry(file("doc.jpg", "x", 1), sb.toString()));
        String snip = ix.search("passport", 5).get(0).snippet;
        assertTrue(snip, snip.contains("passport number is X1234567"));
        assertTrue(snip, snip.startsWith("…") && snip.endsWith("…"));
        assertTrue(snip.length() < 160);
    }

    @Test public void everythingSurvivesARestartIncludingOddCharacters() throws Exception {
        File store = new File(tmp.getRoot(), "sub/ix.tsv");
        ContentIndex ix = new ContentIndex(store);
        File f = file("a b.jpg", "x", 5);
        ix.put(entry(f, "line one\nline\ttwo \\ backslash 写真", "Cat", "Sofa"));
        ix.save();
        ContentIndex again = new ContentIndex(store);
        assertEquals(1, again.size());
        assertTrue(again.has(f));
        ContentIndex.Hit h = again.search("sofa", 5).get(0);
        assertEquals("line one\nline\ttwo \\ backslash 写真", h.entry.text);
        assertEquals(Arrays.asList("Cat", "Sofa"), h.entry.labels);
        assertEquals(1, again.search("写真", 5).size());
    }

    @Test public void changedOrDeletedFilesAreForgotten() throws Exception {
        ContentIndex ix = new ContentIndex(new File(tmp.getRoot(), "ix.tsv"));
        File keep = file("keep.jpg", "k", 1);
        File gone = file("gone.jpg", "g", 2);
        File changed = file("changed.jpg", "c", 3);
        ix.put(entry(keep, "alpha"));
        ix.put(entry(gone, "alpha"));
        ix.put(entry(changed, "alpha"));
        assertTrue(gone.delete());
        Files.write(changed.toPath(), "much longer now".getBytes(StandardCharsets.UTF_8));
        assertFalse(ix.has(changed));
        assertEquals(2, ix.prune());
        assertEquals(Arrays.asList("keep.jpg"), names(ix.search("alpha", 10)));
    }

    @Test public void veryLongTextIsCut() throws Exception {
        ContentIndex ix = new ContentIndex(new File(tmp.getRoot(), "ix.tsv"));
        ix.put(entry(file("big.txt", "x", 1), new String(new char[ContentIndex.MAX_TEXT + 5000]).replace('\0', 'a') + " needle"));
        assertEquals("text beyond the limit isn't searched", 0, ix.search("needle", 5).size());
    }

    // ------------------------------------------------------------------ the indexer

    private static class FakeRecognizer implements ContentIndexer.Recognizer {
        final Map<String, ContentIndexer.Result> byName = new HashMap<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public ContentIndexer.Result recognize(File f) throws IOException {
            calls.incrementAndGet();
            ContentIndexer.Result r = byName.get(f.getName());
            if (r == null) throw new IOException("can't read");
            return r;
        }
    }

    @Test public void theIndexerReadsPicturesPdfsAndTextFilesAndOnlyWhatIsNew() throws Exception {
        FakeRecognizer rec = new FakeRecognizer();
        rec.byName.put("menu.jpg", new ContentIndexer.Result("Pizza Margherita 9.00", Arrays.asList("Food")));
        rec.byName.put("lease.pdf", new ContentIndexer.Result("Rental agreement between the parties", null));
        file("Pictures/menu.jpg", "img", 1);
        file("Documents/lease.pdf", "pdf", 2);
        file("Documents/notes.txt", "remember the milk and the dentist appointment", 3);
        file("Documents/archive.zip", "zip", 4);                 // not something we read
        file("Pictures/.hidden/secret.jpg", "x", 5);             // hidden folder
        file("Android/data/app/cache.jpg", "x", 6);              // app data
        file("Pictures/broken.jpg", "x", 7);                     // the recognizer can't read it

        List<File> files = ContentIndexer.candidates(Arrays.asList(new File(tmp.getRoot(), "Pictures"), new File(tmp.getRoot(), "Documents"),
                new File(tmp.getRoot(), "Android")), 1000);
        List<String> found = new ArrayList<>();
        for (File f : files) found.add(f.getName());
        Collections.sort(found);
        assertEquals(Arrays.asList("broken.jpg", "lease.pdf", "menu.jpg", "notes.txt"), found);

        ContentIndex ix = new ContentIndex(new File(tmp.getRoot(), "index/ix.tsv"));
        ContentIndexer.Summary s = ContentIndexer.run(files, rec, ix, null);
        assertEquals(4, s.indexed + s.failed);
        assertEquals(1, s.failed);
        assertEquals(Arrays.asList("menu.jpg"), names(ix.search("pizza", 5)));
        assertEquals(Arrays.asList("menu.jpg"), names(ix.search("food", 5)));
        assertEquals(Arrays.asList("lease.pdf"), names(ix.search("rental agreement", 5)));
        assertEquals("plain text files are read directly", Arrays.asList("notes.txt"), names(ix.search("dentist", 5)));
        assertEquals("the text file never went to the recognizer", 3, rec.calls.get());

        // second visit: nothing to do, even for the file that failed
        rec.calls.set(0);
        ContentIndexer.Summary again = ContentIndexer.run(files, rec, new ContentIndex(new File(tmp.getRoot(), "index/ix.tsv")), null);
        assertEquals(0, rec.calls.get());
        assertEquals(4, again.unchanged);

        // a new picture is the only thing read
        rec.byName.put("whiteboard.png", new ContentIndexer.Result("Sprint goals", null));
        file("Pictures/whiteboard.png", "img", 8);
        List<File> more = ContentIndexer.candidates(Arrays.asList(new File(tmp.getRoot(), "Pictures"), new File(tmp.getRoot(), "Documents")), 1000);
        ContentIndexer.run(more, rec, new ContentIndex(new File(tmp.getRoot(), "index/ix.tsv")), null);
        assertEquals(1, rec.calls.get());
    }

    @Test public void cancellingKeepsWhatWasLearned() throws Exception {
        FakeRecognizer rec = new FakeRecognizer();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rec.byName.put("p" + i + ".jpg", new ContentIndexer.Result("word" + i, null));
            files.add(file("p" + i + ".jpg", "x", i + 1));
        }
        ContentIndex ix = new ContentIndex(new File(tmp.getRoot(), "ix.tsv"));
        final int[] seen = {0};
        ContentIndexer.Summary s = ContentIndexer.run(files, rec, ix, new ContentIndexer.Progress() {
            @Override public void step(int done, int total) { seen[0] = done; }
            @Override public boolean cancelled() { return seen[0] >= 3; }
        });
        assertTrue(s.cancelled);
        assertTrue(s.indexed >= 3 && s.indexed < 8);
        assertEquals("saved to disk", s.indexed, new ContentIndex(new File(tmp.getRoot(), "ix.tsv")).size());
    }
}
