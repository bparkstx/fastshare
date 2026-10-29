package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Runs the real server on localhost and talks to it with real HTTP. */
public class FileServerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String TOKEN = "abc234";

    private File received;
    private File partials;
    private FileServer server;
    private String base;
    private final List<String> clients = new CopyOnWriteArrayList<>();
    private final List<FileServer.Transfer> finished = new CopyOnWriteArrayList<>();
    private final AtomicInteger sharedChanges = new AtomicInteger();
    private final List<File> aborted = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch terminal = new CountDownLatch(0);

    @Before public void start() throws Exception {
        received = tmp.newFolder("received");
        partials = tmp.newFolder("partials");
        server = new FileServer(TOKEN, new FileServer.Backend() {
            @Override public byte[] indexPage() throws IOException {
                return Files.readAllBytes(new File("src/main/assets/index.html").toPath());
            }
            @Override public String deviceName() { return "Test \"Phone\""; }
            @Override public File partialDir() { return partials; }
            @Override public InputStream openShared(Object ref) { return new ByteArrayInputStream((byte[]) ref); }
            @Override public FileServer.Sink openSink(String fileName) throws IOException {
                final File f = FileOps.uniqueSibling(received, fileName);
                final FileOutputStream os = new FileOutputStream(f);
                return new FileServer.Sink() {
                    @Override public OutputStream stream() { return os; }
                    @Override public void commit() { }
                    @Override public void abort() { aborted.add(f); f.delete(); }
                    @Override public String location() { return "Downloads/FastShare"; }
                };
            }
        }, new FileServer.Listener() {
            @Override public void onClient(String ip, String ua) { clients.add(ip); }
            @Override public void onSharedChanged() { sharedChanges.incrementAndGet(); }
            @Override public void onTransfer(FileServer.Transfer t) {
                if (t.state != FileServer.State.ACTIVE) {
                    finished.add(t);
                    terminal.countDown();
                }
            }
        });
        int port = server.start(0);
        base = "http://127.0.0.1:" + port;
    }

    @After public void stop() {
        FileServer.STALL_MS = 20_000;
        server.stop();
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private int upload(String name, byte[] data) throws IOException {
        HttpURLConnection c = open("/up?t=" + TOKEN + "&name=" + URLEncoder.encode(name, "UTF-8"), "POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) { os.write(data); }
        int code = c.getResponseCode();
        readAll(code == 200 ? c.getInputStream() : c.getErrorStream());
        return code;
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test public void refusesRequestsWithoutTheToken() throws Exception {
        HttpURLConnection c = open("/", "GET");
        assertEquals(403, c.getResponseCode());
        String body = new String(readAll(c.getErrorStream()), StandardCharsets.UTF_8);
        assertTrue(body.contains("missing its code"));

        assertEquals(403, open("/api/files?t=wrong", "GET").getResponseCode());
        assertEquals(403, open("/dl/1", "GET").getResponseCode());
        assertTrue(clients.isEmpty());   // unauthorised requests are not "connected devices"
    }

    @Test public void faviconNeverNeedsTheToken() throws Exception {
        assertEquals(204, open("/favicon.ico", "GET").getResponseCode());
    }

    @Test public void servesThePageAndReportsTheClient() throws Exception {
        HttpURLConnection c = open("/?t=" + TOKEN, "GET");
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getContentType().startsWith("text/html"));
        String html = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(html.contains("FileBandit"));
        assertEquals(1, clients.size());
        assertEquals("127.0.0.1", clients.get(0));
    }

    @Test public void infoEndpointEscapesTheDeviceName() throws Exception {
        HttpURLConnection c = open("/api/info?t=" + TOKEN, "GET");
        assertEquals(200, c.getResponseCode());
        assertEquals("{\"device\":\"Test \\\"Phone\\\"\",\"ok\":true}", new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
    }

    @Test public void uploadsAreSavedByteForByte() throws Exception {
        terminal = new CountDownLatch(1);
        byte[] data = random(5 * 1024 * 1024 + 123);
        assertEquals(200, upload("photo 1.jpg", data));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertArrayEquals(data, Files.readAllBytes(new File(received, "photo 1.jpg").toPath()));

        FileServer.Transfer t = finished.get(0);
        assertTrue(t.incoming);
        assertEquals(FileServer.State.DONE, t.state);
        assertEquals(data.length, t.done);
        assertEquals(data.length, t.total);
        assertEquals("Downloads/FastShare", t.detail);
    }

    @Test public void zeroByteUploadWorks() throws Exception {
        assertEquals(200, upload("empty.txt", new byte[0]));
        assertEquals(0, new File(received, "empty.txt").length());
    }

    @Test public void uploadNamesCannotEscapeTheFolder() throws Exception {
        assertEquals(200, upload("../../evil.txt", "x".getBytes(StandardCharsets.UTF_8)));
        File[] files = received.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertFalse(files[0].getName().contains("/"));
        assertFalse(new File(tmp.getRoot(), "evil.txt").exists());
    }

    @Test public void unicodeNamesSurvive() throws Exception {
        assertEquals(200, upload("fot\u00f3 \u2013 \u5199\u771f.jpg", new byte[]{1, 2, 3}));
        assertEquals(1, received.list().length);   // exact on-disk name depends on the host filesystem encoding
    }

    @Test public void interruptedUploadIsAbortedAndReportedAsFailed() throws Exception {
        terminal = new CountDownLatch(1);
        Socket s = new Socket("127.0.0.1", Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)));
        OutputStream os = s.getOutputStream();
        os.write(("POST /up?t=" + TOKEN + "&name=cut.bin HTTP/1.1\r\nHost: x\r\nContent-Length: 1000000\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        os.write(new byte[1000]);
        os.flush();
        s.close();   // the phone's Wi-Fi dropped half way
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertEquals(FileServer.State.FAILED, finished.get(0).state);
        assertEquals(1, aborted.size());
        assertFalse(new File(received, "cut.bin").exists());
    }

    @Test public void uploadWithoutLengthIsRefused() throws Exception {
        Socket s = new Socket("127.0.0.1", Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)));
        s.getOutputStream().write(("POST /up?t=" + TOKEN + "&name=x HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        String reply = new String(readAll(s.getInputStream()), StandardCharsets.ISO_8859_1);
        assertTrue(reply.startsWith("HTTP/1.1 411"));
    }

    @Test public void sharedFilesDownloadWithLengthAndFilename() throws Exception {
        byte[] data = random(3 * 1024 * 1024 + 7);
        File f = tmp.newFile("movie.mp4");
        Files.write(f.toPath(), data);
        // the name shown to the other device can differ from the name on disk
        FileServer.Shared s = server.addShared(f, "movie \u00e9.mp4", f.length(), false);

        HttpURLConnection list = open("/api/files?t=" + TOKEN, "GET");
        String json = new String(readAll(list.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"id\":\"" + s.id + "\""));
        assertTrue(json.contains("\"dir\":false"));
        assertTrue(json.contains("movie \u00e9.mp4"));

        terminal = new CountDownLatch(1);
        HttpURLConnection c = open("/dl/" + s.id + "?t=" + TOKEN, "GET");
        assertEquals(200, c.getResponseCode());
        assertEquals(data.length, c.getContentLengthLong());
        String cd = c.getHeaderField("Content-Disposition");
        assertTrue(cd, cd.startsWith("attachment; filename=\"movie _.mp4\""));
        assertTrue(cd, cd.contains("filename*=UTF-8''movie%20%C3%A9.mp4"));
        assertArrayEquals(data, readAll(c.getInputStream()));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        FileServer.Transfer t = finished.get(0);
        assertFalse(t.incoming);
        assertEquals(FileServer.State.DONE, t.state);
        assertEquals(data.length, t.done);
    }

    @Test public void sharedNonFileRefsGoThroughTheBackend() throws Exception {
        byte[] data = random(4000);
        FileServer.Shared s = server.addShared(data, "from-uri.bin", data.length, false);
        HttpURLConnection c = open("/dl/" + s.id + "?t=" + TOKEN, "GET");
        assertArrayEquals(data, readAll(c.getInputStream()));
    }

    @Test public void foldersDownloadAsZip() throws Exception {
        File dir = tmp.newFolder("Trip");
        Files.write(new File(dir, "a.txt").toPath(), "alpha".getBytes(StandardCharsets.UTF_8));
        File sub = new File(dir, "pics");
        sub.mkdir();
        Files.write(new File(sub, "b.bin").toPath(), random(300_000));

        FileServer.Shared s = server.addShared(dir, "Trip", -1, true);
        assertEquals("Trip.zip", s.name);
        HttpURLConnection c = open("/dl/" + s.id + "?t=" + TOKEN, "GET");
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getHeaderField("Content-Disposition").contains("Trip.zip"));
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(readAll(c.getInputStream())));
        List<String> names = new ArrayList<>();
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) names.add(e.getName());
        Collections.sort(names);
        assertEquals("[Trip/, Trip/a.txt, Trip/pics/, Trip/pics/b.bin]", names.toString());
    }

    @Test public void removedAndUnknownItemsAre404() throws Exception {
        FileServer.Shared s = server.addShared(new byte[]{1}, "x.bin", 1, false);
        server.removeShared(s.id);
        assertEquals(404, open("/dl/" + s.id + "?t=" + TOKEN, "GET").getResponseCode());
        assertEquals(404, open("/dl/999?t=" + TOKEN, "GET").getResponseCode());
        assertEquals(404, open("/nope?t=" + TOKEN, "GET").getResponseCode());
        assertTrue(sharedChanges.get() >= 2);
    }

    @Test public void clearSharedEmptiesTheList() throws Exception {
        server.addShared(new byte[]{1}, "a", 1, false);
        server.addShared(new byte[]{1}, "b", 1, false);
        server.clearShared();
        assertEquals("[]", new String(readAll(open("/api/files?t=" + TOKEN, "GET").getInputStream()), StandardCharsets.UTF_8));
    }

    @Test public void manyParallelUploadsAllArrive() throws Exception {
        final int n = 6;
        final CountDownLatch done = new CountDownLatch(n);
        final AtomicInteger ok = new AtomicInteger();
        final byte[][] blobs = new byte[n][];
        for (int i = 0; i < n; i++) {
            final int k = i;
            blobs[i] = random(2_000_000 + i);
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        if (upload("p" + k + ".bin", blobs[k]) == 200) ok.incrementAndGet();
                    } catch (IOException ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(n, ok.get());
        for (int i = 0; i < n; i++) {
            assertArrayEquals(blobs[i], Files.readAllBytes(new File(received, "p" + i + ".bin").toPath()));
        }
    }

    // ------------------------------------------------------------------ range downloads

    private HttpURLConnection ranged(String id, String range) throws IOException {
        HttpURLConnection c = open("/dl/" + id + "?t=" + TOKEN, "GET");
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    @Test public void rangeRequestsReturnPartialContent() throws Exception {
        byte[] data = random(10_000);
        File f = tmp.newFile("r.bin");
        Files.write(f.toPath(), data);
        FileServer.Shared s = server.addShared(f, "r.bin", f.length(), false);

        HttpURLConnection plain = ranged(s.id, null);
        assertEquals(200, plain.getResponseCode());
        assertEquals("bytes", plain.getHeaderField("Accept-Ranges"));
        readAll(plain.getInputStream());

        HttpURLConnection c = ranged(s.id, "bytes=100-199");
        assertEquals(206, c.getResponseCode());
        assertEquals("bytes 100-199/10000", c.getHeaderField("Content-Range"));
        assertEquals(100, c.getContentLengthLong());
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 100, 200), readAll(c.getInputStream()));

        c = ranged(s.id, "bytes=9000-");
        assertEquals(206, c.getResponseCode());
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 9000, 10000), readAll(c.getInputStream()));

        c = ranged(s.id, "bytes=-50");
        assertEquals(206, c.getResponseCode());
        assertEquals("bytes 9950-9999/10000", c.getHeaderField("Content-Range"));
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 9950, 10000), readAll(c.getInputStream()));

        c = ranged(s.id, "bytes=5000-99999");     // end past the file is clipped
        assertEquals(206, c.getResponseCode());
        assertEquals(5000, c.getContentLengthLong());
        readAll(c.getInputStream());

        c = ranged(s.id, "bytes=20000-");
        assertEquals(416, c.getResponseCode());
        assertEquals("bytes */10000", c.getHeaderField("Content-Range"));

        c = ranged(s.id, "bytes=1-2,5-6");           // multi-range isn't supported, so the whole file is sent
        assertEquals(200, c.getResponseCode());
        assertArrayEquals(data, readAll(c.getInputStream()));
        c = ranged(s.id, "garbage");
        assertEquals(200, c.getResponseCode());
        readAll(c.getInputStream());
    }

    @Test public void aBrokenDownloadCanBeFinishedWithARange() throws Exception {
        byte[] data = random(3_000_000);
        File f = tmp.newFile("big.bin");
        Files.write(f.toPath(), data);
        FileServer.Shared s = server.addShared(f, "big.bin", f.length(), false);
        // read only the first part of the file, then hang up
        HttpURLConnection c = ranged(s.id, null);
        InputStream in = c.getInputStream();
        byte[] first = new byte[1_000_000];
        int got = 0;
        while (got < first.length) {
            int n = in.read(first, got, first.length - got);
            if (n < 0) break;
            got += n;
        }
        in.close();
        c.disconnect();
        HttpURLConnection rest = ranged(s.id, "bytes=" + got + "-");
        assertEquals(206, rest.getResponseCode());
        byte[] tail = readAll(rest.getInputStream());
        byte[] all = new byte[got + tail.length];
        System.arraycopy(first, 0, all, 0, got);
        System.arraycopy(tail, 0, all, got, tail.length);
        assertArrayEquals(data, all);
    }

    @Test public void rangesWorkForNonFileSources() throws Exception {
        byte[] data = random(5000);
        FileServer.Shared s = server.addShared(data, "uri.bin", data.length, false);
        HttpURLConnection c = ranged(s.id, "bytes=1234-2233");
        assertEquals(206, c.getResponseCode());
        assertArrayEquals(java.util.Arrays.copyOfRange(data, 1234, 2234), readAll(c.getInputStream()));
    }

    @Test public void headReturnsOnlyHeaders() throws Exception {
        byte[] data = random(7777);
        FileServer.Shared s = server.addShared(data, "h.bin", data.length, false);
        HttpURLConnection c = open("/dl/" + s.id + "?t=" + TOKEN, "HEAD");
        assertEquals(200, c.getResponseCode());
        assertEquals(7777, c.getContentLengthLong());
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"));
        assertEquals(0, finished.size());       // nobody downloaded anything
    }

    @Test public void parseRangeEdgeCases() {
        assertArrayEquals(new long[]{0, 9}, FileServer.parseRange("bytes=0-9", 100));
        assertArrayEquals(new long[]{90, 99}, FileServer.parseRange("bytes=-10", 100));
        assertArrayEquals(new long[]{0, 99}, FileServer.parseRange("bytes=-500", 100));
        assertArrayEquals(new long[]{-1, -1}, FileServer.parseRange(null, 100));
        assertArrayEquals(new long[]{-1, -1}, FileServer.parseRange("bytes=0-1", 0));
        assertArrayEquals(new long[]{-1, -1}, FileServer.parseRange("bytes=a-b", 100));
        assertEquals(null, FileServer.parseRange("bytes=100-", 100));
        assertEquals(null, FileServer.parseRange("bytes=9-3", 100));
        assertEquals(null, FileServer.parseRange("bytes=-0", 100));
    }

    @Test public void downloadAllZipsEverythingWithUniqueNames() throws Exception {
        File a = tmp.newFile("a.txt");
        Files.write(a.toPath(), "alpha".getBytes(StandardCharsets.UTF_8));
        File dir = tmp.newFolder("Trip");
        Files.write(new File(dir, "b.txt").toPath(), "beta".getBytes(StandardCharsets.UTF_8));
        server.addShared(a, "a.txt", a.length(), false);
        server.addShared(new byte[]{1, 2, 3}, "a.txt", 3, false);             // same name from a different source
        server.addShared(dir, "Trip", -1, true);
        HttpURLConnection c = open("/dl/all?t=" + TOKEN, "GET");
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getHeaderField("Content-Disposition").contains("FileBandit.zip"));
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(readAll(c.getInputStream())));
        List<String> names = new ArrayList<>();
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) names.add(e.getName());
        Collections.sort(names);
        assertEquals("[Trip/, Trip/b.txt, a (2).txt, a.txt]", names.toString());
    }

    @Test public void downloadAllWithNothingSharedIs404() throws Exception {
        assertEquals(404, open("/dl/all?t=" + TOKEN, "GET").getResponseCode());
    }

    // ------------------------------------------------------------------ resumable uploads

    private String chunk(String uid, String name, byte[] data, int from, int to, long total) throws IOException {
        HttpURLConnection c = open("/up2?t=" + TOKEN + "&uid=" + uid + "&name=" + URLEncoder.encode(name, "UTF-8")
                + "&offset=" + from + "&total=" + total, "POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(to - from);
        try (OutputStream os = c.getOutputStream()) { os.write(data, from, to - from); }
        int code = c.getResponseCode();
        return code + " " + new String(readAll(code < 400 ? c.getInputStream() : c.getErrorStream()), StandardCharsets.UTF_8);
    }

    private long received(String uid) throws IOException {
        String json = new String(readAll(open("/up2/status?t=" + TOKEN + "&uid=" + uid, "GET").getInputStream()), StandardCharsets.UTF_8);
        return Long.parseLong(json.replaceAll("[^0-9]", ""));
    }

    @Test public void chunkedUploadAssemblesTheFile() throws Exception {
        terminal = new CountDownLatch(1);
        byte[] data = random(2_500_000);
        String uid = "u-abcdef0123";
        assertEquals(0, received(uid));
        assertTrue(chunk(uid, "movie.mp4", data, 0, 1_000_000, data.length).startsWith("200 {\"ok\":true,\"received\":1000000"));
        assertEquals(1_000_000, received(uid));
        assertTrue(chunk(uid, "movie.mp4", data, 1_000_000, 2_000_000, data.length).contains("\"received\":2000000"));
        assertEquals("not finished yet, so nothing in the destination", 0, received.list().length);
        String last = chunk(uid, "movie.mp4", data, 2_000_000, data.length, data.length);
        assertTrue(last, last.contains("\"done\":true"));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertArrayEquals(data, Files.readAllBytes(new File(received, "movie.mp4").toPath()));
        assertEquals("the partial file is cleaned up", 0, partials.list().length);
        assertEquals(1, finished.size());
        FileServer.Transfer t = finished.get(0);
        assertEquals(FileServer.State.DONE, t.state);
        assertEquals(data.length, t.done);
        assertEquals(data.length, t.total);
        assertTrue(t.incoming);
        assertEquals(0, received(uid));
    }

    @Test public void anInterruptedChunkResumesFromTheLastByteThatArrived() throws Exception {
        byte[] data = random(1_000_000);
        String uid = "u-resume0001";
        terminal = new CountDownLatch(1);
        Socket s = new Socket("127.0.0.1", Integer.parseInt(base.substring(base.lastIndexOf(':') + 1)));
        OutputStream os = s.getOutputStream();
        os.write(("POST /up2?t=" + TOKEN + "&uid=" + uid + "&name=cut.bin&offset=0&total=" + data.length
                + " HTTP/1.1\r\nHost: x\r\nContent-Length: " + data.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        os.write(data, 0, 300_000);
        os.flush();
        s.close();                                    // the iPhone went to sleep
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertEquals(FileServer.State.FAILED, finished.get(0).state);
        long have = received(uid);
        assertEquals("everything that arrived is kept", 300_000, have);

        // a stale offset is corrected, not trusted
        assertTrue(chunk(uid, "cut.bin", data, 0, 1000, data.length).startsWith("409"));
        terminal = new CountDownLatch(1);
        String done = chunk(uid, "cut.bin", data, (int) have, data.length, data.length);
        assertTrue(done, done.contains("\"done\":true"));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertArrayEquals(data, Files.readAllBytes(new File(received, "cut.bin").toPath()));
        assertEquals("one transfer entry, finished", FileServer.State.DONE, finished.get(finished.size() - 1).state);
    }

    @Test public void chunkRequestsAreValidated() throws Exception {
        byte[] data = random(100);
        assertTrue(chunk("bad", "x.bin", data, 0, 100, 100).startsWith("400"));                 // uid too short
        assertTrue(chunk("../../etc/pass", "x.bin", data, 0, 100, 100).startsWith("400"));      // path characters
        assertTrue(chunk("u-valid0001", "x.bin", data, 0, 100, 50).startsWith("400"));          // longer than the total
        assertTrue(chunk("u-valid0001", "x.bin", data, 50, 100, 100).startsWith("409"));        // nothing before offset 50
        assertEquals(0, partials.list().length);
    }

    @Test public void zeroByteFileFinishesInOneChunk() throws Exception {
        String r = chunk("u-empty0001", "empty.txt", new byte[0], 0, 0, 0);
        assertTrue(r, r.contains("\"done\":true"));
        assertEquals(0, new File(received, "empty.txt").length());
    }

    @Test public void aTransferThatGoesQuietShowsAsPaused() throws Exception {
        FileServer.STALL_MS = 300;
        byte[] data = random(1000);
        terminal = new CountDownLatch(1);
        assertTrue(chunk("u-stall0001", "slow.bin", data, 0, 500, 1000).startsWith("200"));
        assertTrue("it should be marked paused", terminal.await(8, TimeUnit.SECONDS));
        assertEquals("Paused", finished.get(0).detail);
        // the next chunk wakes it up again and it still finishes
        terminal = new CountDownLatch(1);
        assertTrue(chunk("u-stall0001", "slow.bin", data, 500, 1000, 1000).contains("\"done\":true"));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertEquals(FileServer.State.DONE, finished.get(finished.size() - 1).state);
    }

    @Test public void oldPartialFilesAreSweptWhenTheServerStarts() throws Exception {
        File stale = new File(partials, "u-old000001.part");
        File fresh = new File(partials, "u-new000001.part");
        Files.write(stale.toPath(), new byte[10]);
        Files.write(fresh.toPath(), new byte[10]);
        assertTrue(stale.setLastModified(System.currentTimeMillis() - FileServer.PARTIAL_MAX_AGE_MS - 60_000));
        FileServer again = new FileServer("t", new FileServer.Backend() {
            @Override public byte[] indexPage() { return new byte[0]; }
            @Override public String deviceName() { return "x"; }
            @Override public InputStream openShared(Object ref) { return null; }
            @Override public FileServer.Sink openSink(String n) { return null; }
            @Override public File partialDir() { return partials; }
        }, new FileServer.Listener() {
            @Override public void onClient(String ip, String ua) { }
            @Override public void onSharedChanged() { }
            @Override public void onTransfer(FileServer.Transfer t) { }
        });
        try {
            again.start(0);
            assertFalse(stale.exists());
            assertTrue(fresh.exists());
        } finally {
            again.stop();
        }
    }

    // ------------------------------------------------------------------ PIN on the link

    private HttpURLConnection withCookie(String path, String method, String cookie) throws IOException {
        HttpURLConnection c = open(path, method);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        return c;
    }

    private HttpURLConnection postPin(String pin) throws IOException {
        HttpURLConnection c = open("/pin?t=" + TOKEN, "POST");
        c.setDoOutput(true);
        byte[] body = ("pin=" + URLEncoder.encode(pin, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        c.getResponseCode();
        return c;
    }

    private static String bodyOf(HttpURLConnection c) throws IOException {
        return new String(readAll(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()), StandardCharsets.UTF_8);
    }

    @Test public void withAPinTheLinkAloneIsNotEnough() throws Exception {
        server.setPin("4821");
        HttpURLConnection page = open("/?t=" + TOKEN, "GET");
        assertEquals(200, page.getResponseCode());
        String html = bodyOf(page);
        assertTrue(html, html.contains("Enter the PIN"));
        assertFalse("the real page isn't leaked", html.contains("Send to the Android phone"));
        HttpURLConnection api = open("/api/files?t=" + TOKEN, "GET");
        assertEquals(401, api.getResponseCode());
        assertTrue(bodyOf(api).contains("\"pin\":true"));
        assertEquals(401, open("/dl/1?t=" + TOKEN, "GET").getResponseCode());
        assertEquals(401, open("/dl/all?t=" + TOKEN, "GET").getResponseCode());
        try {
            assertEquals(401, upload("x.bin", new byte[]{1}));
        } catch (IOException | NullPointerException refused) {
            // the server may hang up before reading the body, which is just as good
        }
        assertEquals(401, open("/up2/status?t=" + TOKEN + "&uid=u-12345678", "GET").getResponseCode());
        assertEquals("nothing was saved", 0, received.list().length);
        assertEquals(403, open("/pin", "POST").getResponseCode());        // still needs the token
    }

    @Test public void theRightPinSignsADeviceIn() throws Exception {
        server.setPin("4821");
        HttpURLConnection bad = postPin("0000");
        assertEquals(403, bad.getResponseCode());
        assertEquals(null, bad.getHeaderField("Set-Cookie"));
        HttpURLConnection ok = postPin("4821");
        assertEquals(200, ok.getResponseCode());
        String set = ok.getHeaderField("Set-Cookie");
        assertTrue(set, set.startsWith("fbs=") && set.contains("HttpOnly"));
        String cookie = set.substring(0, set.indexOf(';'));

        HttpURLConnection page = withCookie("/?t=" + TOKEN, "GET", cookie);
        assertTrue(bodyOf(page).contains("Send to the Android phone"));
        assertEquals(200, withCookie("/api/files?t=" + TOKEN, "GET", cookie).getResponseCode());
        assertEquals(401, withCookie("/api/files?t=" + TOKEN, "GET", "fbs=forged").getResponseCode());

        // changing the PIN signs everybody out
        server.setPin("9999");
        assertEquals(401, withCookie("/api/files?t=" + TOKEN, "GET", cookie).getResponseCode());
        server.setPin(null);
        assertEquals(200, open("/api/files?t=" + TOKEN, "GET").getResponseCode());
    }

    @Test public void tooManyWrongPinsLockTheDeviceOut() throws Exception {
        server.setPin("4821");
        for (int i = 0; i < 4; i++) assertEquals(403, postPin("000" + i).getResponseCode());
        HttpURLConnection fifth = postPin("0004");
        assertEquals(403, fifth.getResponseCode());
        String fifthBody = bodyOf(fifth);
        assertTrue(fifthBody, fifthBody.contains("\"wait\":30"));
        HttpURLConnection locked = postPin("4821");        // even the right one is refused while locked
        assertEquals(429, locked.getResponseCode());
        assertEquals(null, locked.getHeaderField("Set-Cookie"));
    }

    @Test public void signedInDevicesCanTransferNormally() throws Exception {
        server.setPin("4821");
        String cookie = postPin("4821").getHeaderField("Set-Cookie");
        cookie = cookie.substring(0, cookie.indexOf(';'));
        byte[] data = random(2000);
        HttpURLConnection c = open("/up?t=" + TOKEN + "&name=signed.bin", "POST");
        c.setRequestProperty("Cookie", cookie);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) { os.write(data); }
        assertEquals(200, c.getResponseCode());
        assertArrayEquals(data, Files.readAllBytes(new File(received, "signed.bin").toPath()));
    }

    @Test public void fallsBackToAnotherPortWhenBusy() throws Exception {
        FileServer other = new FileServer("t", new FileServer.Backend() {
            @Override public byte[] indexPage() { return new byte[0]; }
            @Override public String deviceName() { return "x"; }
            @Override public InputStream openShared(Object ref) { return null; }
            @Override public FileServer.Sink openSink(String n) { return null; }
        }, new FileServer.Listener() {
            @Override public void onClient(String ip, String ua) { }
            @Override public void onSharedChanged() { }
            @Override public void onTransfer(FileServer.Transfer t) { }
        });
        try {
            int busyPort = Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));
            int p = other.start(busyPort);
            assertTrue(p != busyPort && p > 0);
        } finally {
            other.stop();
        }
    }
}
