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
    private FileServer server;
    private String base;
    private final List<String> clients = new CopyOnWriteArrayList<>();
    private final List<FileServer.Transfer> finished = new CopyOnWriteArrayList<>();
    private final AtomicInteger sharedChanges = new AtomicInteger();
    private final List<File> aborted = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch terminal = new CountDownLatch(0);

    @Before public void start() throws Exception {
        received = tmp.newFolder("received");
        server = new FileServer(TOKEN, new FileServer.Backend() {
            @Override public byte[] indexPage() throws IOException {
                return Files.readAllBytes(new File("src/main/assets/index.html").toPath());
            }
            @Override public String deviceName() { return "Test \"Phone\""; }
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
        assertTrue(html.contains("FastShare"));
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
