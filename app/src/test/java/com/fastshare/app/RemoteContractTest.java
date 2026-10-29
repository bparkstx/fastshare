package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The same checks against every protocol, each talking to a real server on this machine: whatever the server stores
 * on disk is compared with what we sent.
 */
public abstract class RemoteContractTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** The folder the server serves. */
    protected File root;
    protected Remote remote;

    /** Starts a server over {@code root} and returns a connected client. Throw AssumptionViolatedException to skip. */
    protected abstract Remote connect(File root) throws Exception;

    protected void stopServer() throws Exception { }

    @Before public void setUp() throws Exception {
        root = tmp.newFolder("served");
        remote = connect(root);
    }

    @After public void tearDown() throws Exception {
        if (remote != null) remote.close();
        stopServer();
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static List<String> names(List<Remote.Item> items) {
        List<String> out = new ArrayList<>();
        for (Remote.Item i : items) out.add((i.dir ? "[" : "") + i.name + (i.dir ? "]" : ""));
        return out;
    }

    @Test public void listsFoldersFirstThenFilesWithSizes() throws Exception {
        Files.write(new File(root, "b.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "A.bin").toPath(), random(1234, 1));
        new File(root, "zeta").mkdir();
        new File(root, "Alpha").mkdir();
        Files.write(new File(root, "Alpha/inner.txt").toPath(), new byte[3]);

        List<Remote.Item> items = remote.list("/");
        assertEquals("[[Alpha], [zeta], A.bin, b.txt]", names(items).toString());
        assertEquals(1234, items.get(2).size);
        assertEquals(5, items.get(3).size);
        assertEquals("/b.txt", items.get(3).path);
        assertTrue("modified time is known", items.get(3).modified > 1_000_000_000_000L);

        List<Remote.Item> inner = remote.list("/Alpha");
        assertEquals("[inner.txt]", names(inner).toString());
        assertEquals("/Alpha/inner.txt", inner.get(0).path);
    }

    @Test public void uploadsArriveByteForByte() throws Exception {
        byte[] data = random(3 * 1024 * 1024 + 17, 2);
        remote.put("/movie.bin", new ByteArrayInputStream(data), data.length);
        assertArrayEquals(data, Files.readAllBytes(new File(root, "movie.bin").toPath()));
        remote.put("/empty.txt", new ByteArrayInputStream(new byte[0]), 0);
        assertEquals(0, new File(root, "empty.txt").length());
        byte[] small = "new".getBytes(StandardCharsets.UTF_8);
        remote.put("/movie.bin", new ByteArrayInputStream(small), small.length);       // replaces
        assertArrayEquals(small, Files.readAllBytes(new File(root, "movie.bin").toPath()));
    }

    @Test public void downloadsAreIntactAndCanStartPartWayIn() throws Exception {
        byte[] data = random(2 * 1024 * 1024 + 5, 3);
        Files.write(new File(root, "big.bin").toPath(), data);
        assertArrayEquals(data, readAll(remote.open("/big.bin", 0)));
        byte[] tail = readAll(remote.open("/big.bin", 1_500_000));
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 1_500_000, data.length), tail);
    }

    @Test public void awkwardNamesSurviveBothWays() throws Exception {
        String name = "My file #1 (café) 100%.txt";
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        remote.put("/" + name, new ByteArrayInputStream(data), 1);
        assertTrue(new File(root, name).isFile());
        Files.write(new File(root, "résumé + more.txt").toPath(), data);
        List<String> n = names(remote.list("/"));
        assertTrue(n.toString(), n.contains(name));
        assertTrue(n.toString(), n.contains("résumé + more.txt"));
        assertArrayEquals(data, readAll(remote.open("/" + name, 0)));
        assertArrayEquals(data, readAll(remote.open("/résumé + more.txt", 0)));
    }

    @Test public void foldersCanBeCreatedRenamedAndDeletedWithContents() throws Exception {
        remote.mkdir("/Photos");
        assertTrue(new File(root, "Photos").isDirectory());
        remote.put("/Photos/a.jpg", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
        remote.mkdir("/Photos/2026");
        remote.put("/Photos/2026/b.jpg", new ByteArrayInputStream(new byte[]{4}), 1);

        remote.rename("/Photos/a.jpg", "/Photos/renamed.jpg");
        assertFalse(new File(root, "Photos/a.jpg").exists());
        assertTrue(new File(root, "Photos/renamed.jpg").exists());
        remote.rename("/Photos", "/Pictures");
        assertTrue(new File(root, "Pictures/2026/b.jpg").exists());
        assertFalse(new File(root, "Photos").exists());

        remote.delete("/Pictures/renamed.jpg", false);
        assertFalse(new File(root, "Pictures/renamed.jpg").exists());
        remote.delete("/Pictures", true);
        assertFalse("folder and everything in it is gone", new File(root, "Pictures").exists());
        assertEquals(0, root.list().length);
    }

    @Test public void wholeFoldersCopyDownAndUp() throws Exception {
        File src = tmp.newFolder("Trip");
        Files.write(new File(src, "a.txt").toPath(), "alpha".getBytes(StandardCharsets.UTF_8));
        new File(src, "sub/deeper").mkdirs();
        byte[] big = random(700_000, 4);
        Files.write(new File(src, "sub/deeper/big.bin").toPath(), big);
        new File(src, "empty").mkdir();

        AtomicLong sent = new AtomicLong();
        int files = Remote.Copy.upload(remote, src, "/", new Remote.Progress() {
            @Override public boolean cancelled() { return false; }
            @Override public void bytes(long n) { sent.addAndGet(n); }
        });
        assertEquals(2, files);
        assertEquals(5 + big.length, sent.get());
        assertArrayEquals(big, Files.readAllBytes(new File(root, "Trip/sub/deeper/big.bin").toPath()));
        assertTrue(new File(root, "Trip/empty").isDirectory());

        File out = tmp.newFolder("downloaded");
        Remote.Item trip = null;
        for (Remote.Item i : remote.list("/")) if (i.name.equals("Trip")) trip = i;
        assertNotNull(trip);
        AtomicLong got = new AtomicLong();
        File made = Remote.Copy.download(remote, trip, out, new Remote.Progress() {
            @Override public boolean cancelled() { return false; }
            @Override public void bytes(long n) { got.addAndGet(n); }
        });
        assertEquals("Trip", made.getName());
        assertEquals(5 + big.length, got.get());
        assertArrayEquals(big, Files.readAllBytes(new File(made, "sub/deeper/big.bin").toPath()));
        assertEquals("alpha", new String(Files.readAllBytes(new File(made, "a.txt").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(made, "empty").isDirectory());

        // a second download doesn't overwrite the first
        File again = Remote.Copy.download(remote, trip, out, null);
        assertTrue(again.getName(), !again.getName().equals("Trip"));
    }

    @Test public void cancellingADownloadLeavesNoHalfFile() throws Exception {
        Files.write(new File(root, "big.bin").toPath(), random(5_000_000, 5));
        Remote.Item item = remote.list("/").get(0);
        final AtomicLong seen = new AtomicLong();
        File out = tmp.newFolder("dl");
        try {
            Remote.Copy.download(remote, item, out, new Remote.Progress() {
                @Override public boolean cancelled() { return seen.get() > 300_000; }
                @Override public void bytes(long n) { seen.addAndGet(n); }
            });
            fail("should have been cancelled");
        } catch (IOException expected) {
            assertEquals("Cancelled", expected.getMessage());
        }
        assertEquals(0, out.list().length);
    }

    @Test public void missingThingsGiveErrorsNotSilence() throws Exception {
        try {
            readAll(remote.open("/nope.txt", 0));
            fail("opening a missing file should fail");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            remote.list("/no/such/folder");
            fail("listing a missing folder should fail");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        // and the connection still works afterwards
        assertEquals(0, remote.list("/").size());
    }

    @Test public void downloadedNamesCannotEscapeTheTargetFolder() throws Exception {
        File out = tmp.newFolder("safe");
        Remote.Item evil = new Remote.Item("..", "/x", true, -1, 0);
        Remote fake = new Remote() {
            @Override public List<Remote.Item> list(String p) { return new ArrayList<>(); }
            @Override public InputStream open(String p, long o) { return new ByteArrayInputStream(new byte[]{1}); }
            @Override public void put(String p, InputStream in, long s) { }
            @Override public void mkdir(String p) { }
            @Override public void delete(String p, boolean d) { }
            @Override public void rename(String a, String b) { }
            @Override public void close() { }
        };
        File made = Remote.Copy.download(fake, evil, out, null);
        assertEquals(out.getCanonicalPath(), made.getParentFile().getCanonicalPath());
        File made2 = Remote.Copy.download(fake, new Remote.Item("a/../../b.txt", "/y", false, 1, 0), out, null);
        assertEquals(out.getCanonicalPath(), made2.getParentFile().getCanonicalPath());
    }
}
