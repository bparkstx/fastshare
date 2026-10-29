package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/** The phone's own WebDAV drive, driven by our WebDAV client and (when installed) the litmus compliance suite. */
public class WebDavServerTest extends RemoteContractTest {
    private static final String TOKEN = "abc234";
    private FileServer server;
    private int port;
    private final List<FileServer.Transfer> transfers = new CopyOnWriteArrayList<>();

    private FileServer start(File root, boolean writable) throws Exception {
        FileServer s = new FileServer(TOKEN, new FileServer.Backend() {
            @Override public byte[] indexPage() { return new byte[0]; }
            @Override public String deviceName() { return "Test"; }
            @Override public InputStream openShared(Object ref) { return null; }
            @Override public FileServer.Sink openSink(String n) { return null; }
        }, new FileServer.Listener() {
            @Override public void onClient(String ip, String ua) { }
            @Override public void onSharedChanged() { }
            @Override public void onTransfer(FileServer.Transfer t) { if (t.state != FileServer.State.ACTIVE) transfers.add(t); }
        });
        port = s.start(0);
        s.setDav(root, writable, null);
        return s;
    }

    private Connection conn(String user, String pw) {
        Connection c = new Connection();
        c.type = Connection.Type.WEBDAV;
        c.host = "127.0.0.1";
        c.port = port;
        c.user = user;
        c.password = pw;
        c.path = "/d/" + TOKEN;
        return c;
    }

    @Override protected Remote connect(File root) throws Exception {
        server = start(root, true);
        return new DavRemote(conn("", ""));
    }

    @Override protected void stopServer() {
        if (server != null) server.stop();
    }

    private final OkHttpClient http = new OkHttpClient();

    private Response call(String method, String path, String body, String... headers) throws IOException {
        Request.Builder b = new Request.Builder().url("http://127.0.0.1:" + port + path)
                .method(method, body == null && !method.equals("PUT") && !method.equals("PROPFIND") && !method.equals("LOCK") && !method.equals("PROPPATCH")
                        ? null : RequestBody.create(body == null ? "" : body, MediaType.parse("application/xml")));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.newCall(b.build()).execute();
    }

    private String dav(String rel) {
        return "/d/" + TOKEN + rel;
    }

    @Test public void optionsAdvertisesDavAndLocks() throws Exception {
        try (Response r = call("OPTIONS", dav("/"), null)) {
            assertEquals(200, r.code());
            assertEquals("1, 2", r.header("DAV"));
            assertTrue(r.header("Allow"), r.header("Allow").contains("PROPFIND") && r.header("Allow").contains("MKCOL"));
            assertEquals("DAV", r.header("MS-Author-Via"));
        }
    }

    @Test public void propfindListsWithTypesSizesAndEtags() throws Exception {
        Files.write(new File(root, "a b.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        new File(root, "Dir").mkdir();
        try (Response r = call("PROPFIND", dav("/"), "", "Depth", "1")) {
            assertEquals(207, r.code());
            String x = r.body().string();
            assertTrue(x, x.contains("<D:href>/d/" + TOKEN + "/a%20b.txt</D:href>"));
            assertTrue(x, x.contains("<D:href>/d/" + TOKEN + "/Dir/</D:href>"));
            assertTrue(x, x.contains("<D:getcontentlength>5</D:getcontentlength>"));
            assertTrue(x, x.contains("<D:collection/>"));
            assertTrue(x, x.contains("<D:getetag>"));
            assertTrue(x, x.contains("text/plain"));
            assertTrue(x, x.contains("quota-available-bytes"));
        }
        try (Response r = call("PROPFIND", dav("/a%20b.txt"), "", "Depth", "0")) {
            assertEquals(207, r.code());
            assertFalse(r.body().string().contains("Dir"));
        }
        try (Response r = call("PROPFIND", dav("/nope"), "", "Depth", "0")) {
            assertEquals(404, r.code());
        }
    }

    @Test public void chunkedAndExpectContinueUploadsWork() throws Exception {
        byte[] data = random(300_000, 9);
        Socket s = new Socket("127.0.0.1", port);
        OutputStream os = s.getOutputStream();
        os.write(("PUT /d/" + TOKEN + "/chunked.bin HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nExpect: 100-continue\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        os.flush();
        byte[] first = new byte[25];
        int n = s.getInputStream().read(first);
        assertTrue(new String(first, 0, n, StandardCharsets.ISO_8859_1), new String(first, 0, n, StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 100 Continue"));
        int sent = 0;
        while (sent < data.length) {
            int len = Math.min(70_000, data.length - sent);
            os.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            os.write(data, sent, len);
            os.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            sent += len;
        }
        os.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        os.flush();
        String reply = new String(readAll(s.getInputStream()), StandardCharsets.ISO_8859_1);
        assertTrue(reply, reply.startsWith("HTTP/1.1 201"));
        assertArrayEquals(data, Files.readAllBytes(new File(root, "chunked.bin").toPath()));
        assertEquals("no temporary files left behind", 1, root.list().length);
    }

    @Test public void overwriteReturns204AndUploadsAreReportedAsTransfers() throws Exception {
        byte[] big = random(1_500_000, 3);
        remote.put("/f.bin", new ByteArrayInputStream(big), big.length);
        remote.put("/f.bin", new ByteArrayInputStream(new byte[]{1}), 1);
        try (Response r = call("PUT", dav("/f.bin"), "xyz")) {
            assertEquals(204, r.code());
        }
        assertTrue("big upload shows up in the transfer list", transfers.size() >= 1 && transfers.get(0).incoming && transfers.get(0).total == big.length);
        try (Response r = call("PUT", dav("/missing/dir/f.bin"), "x")) {
            assertEquals(409, r.code());
        }
        try (Response r = call("PUT", dav("/"), "x")) {
            assertEquals("a folder can't be overwritten with a file", 405, r.code());
        }
    }

    @Test public void moveAndCopyHonourOverwriteAndDestinations() throws Exception {
        Files.write(new File(root, "a.txt").toPath(), "A".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "b.txt").toPath(), "B".getBytes(StandardCharsets.UTF_8));
        String dest = "http://127.0.0.1:" + port + dav("/b.txt");
        try (Response r = call("COPY", dav("/a.txt"), null, "Destination", dest, "Overwrite", "F")) {
            assertEquals(412, r.code());
        }
        try (Response r = call("COPY", dav("/a.txt"), null, "Destination", dest)) {
            assertEquals(204, r.code());
        }
        assertEquals("A", new String(Files.readAllBytes(new File(root, "b.txt").toPath()), StandardCharsets.UTF_8));
        try (Response r = call("MOVE", dav("/a.txt"), null, "Destination", dav("/moved%20%C3%A9.txt"))) {
            assertEquals(201, r.code());
        }
        assertTrue(new File(root, "moved é.txt").exists());
        assertFalse(new File(root, "a.txt").exists());
        try (Response r = call("MOVE", dav("/b.txt"), null, "Destination", "http://127.0.0.1/elsewhere/b.txt")) {
            assertEquals("a destination outside the drive is refused", 502, r.code());
        }
        try (Response r = call("MOVE", dav("/b.txt"), null, "Destination", dav("/no/such/folder/b.txt"))) {
            assertEquals(409, r.code());
        }
        new File(root, "Dir").mkdir();
        try (Response r = call("MOVE", dav("/Dir"), null, "Destination", dav("/Dir/inside"))) {
            assertEquals("a folder can't be moved into itself", 403, r.code());
        }
    }

    @Test public void copyingAFolderWithDepthZeroCopiesOnlyTheFolder() throws Exception {
        File dir = new File(root, "Album");
        dir.mkdir();
        Files.write(new File(dir, "a.jpg").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        new File(dir, "inner").mkdir();
        try (Response r = call("COPY", dav("/Album"), null, "Destination", dav("/Shallow"), "Depth", "0")) {
            assertEquals(201, r.code());
        }
        assertTrue(new File(root, "Shallow").isDirectory());
        assertEquals("nothing inside was copied", 0, new File(root, "Shallow").list().length);
        try (Response r = call("COPY", dav("/Album"), null, "Destination", dav("/Deep"), "Depth", "infinity")) {
            assertEquals(201, r.code());
        }
        assertTrue(new File(root, "Deep/a.jpg").isFile());
        assertTrue(new File(root, "Deep/inner").isDirectory());
        try (Response r = call("COPY", dav("/Album"), null, "Destination", dav("/Default"))) {
            assertEquals(201, r.code());
        }
        assertTrue("no Depth header means everything", new File(root, "Default/a.jpg").isFile());
    }

    @Test public void pathsCannotEscapeTheSharedFolder() throws Exception {
        File outside = tmp.newFile("secret.txt");
        Files.write(outside.toPath(), "top secret".getBytes(StandardCharsets.UTF_8));
        for (String evil : new String[]{"/../secret.txt", "/%2e%2e/secret.txt", "/%2E%2E%2Fsecret.txt", "/..%2F..%2Fsecret.txt"}) {
            try (Response r = call("GET", dav(evil), null)) {
                String body = r.body().string();
                assertFalse(evil + " leaked", body.contains("top secret"));
            }
        }
        try (Response r = call("DELETE", dav("/../secret.txt"), null)) {
            assertTrue(outside.exists());
        }
        // a link inside the folder that points outside must not be followed either
        try {
            Files.createSymbolicLink(new File(root, "link").toPath(), outside.getParentFile().toPath());
            try (Response r = call("GET", dav("/link/secret.txt"), null)) {
                assertFalse(r.body().string().contains("top secret"));
            }
        } catch (UnsupportedOperationException | IOException noLinks) {
            // filesystem without symlinks
        }
    }

    @Test public void rangedGetsWork() throws Exception {
        byte[] data = random(10_000, 4);
        Files.write(new File(root, "r.bin").toPath(), data);
        try (Response r = call("GET", dav("/r.bin"), null, "Range", "bytes=100-199")) {
            assertEquals(206, r.code());
            assertArrayEquals(Arrays.copyOfRange(data, 100, 200), r.body().bytes());
        }
        try (Response r = call("GET", dav("/r.bin"), null, "Range", "bytes=99999-")) {
            assertEquals(416, r.code());
        }
    }

    @Test public void deletingUsesTheDeleterSoItCanGoToTheRecycleBin() throws Exception {
        final List<String> removed = new CopyOnWriteArrayList<>();
        server.setDav(root, true, new WebDav.Deleter() {
            @Override public void delete(File f) throws IOException {
                removed.add(f.getName());
                if (!FileOps.delete(f)) throw new IOException("x");
            }
        });
        Files.write(new File(root, "gone.txt").toPath(), new byte[3]);
        try (Response r = call("DELETE", dav("/gone.txt"), null)) {
            assertEquals(204, r.code());
        }
        assertEquals(Arrays.asList("gone.txt"), removed);
        try (Response r = call("DELETE", dav("/"), null)) {
            assertEquals("the shared folder itself can't be deleted", 403, r.code());
        }
    }

    @Test public void readOnlyDrivesRefuseEveryChange() throws Exception {
        server.setDav(root, false, null);
        Files.write(new File(root, "keep.txt").toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        try (Response r = call("OPTIONS", dav("/"), null)) {
            assertEquals("1", r.header("DAV"));
        }
        for (String m : new String[]{"PUT", "DELETE", "MKCOL", "MOVE", "COPY", "LOCK", "PROPPATCH"}) {
            try (Response r = call(m, dav("/keep.txt"), "x", "Destination", dav("/other.txt"))) {
                assertEquals(m, 403, r.code());
            }
        }
        try (Response r = call("GET", dav("/keep.txt"), null)) {
            assertEquals("keep", r.body().string());
        }
        assertEquals(1, root.list().length);
    }

    @Test public void lockingHandsOutATokenAndCreatesMissingFiles() throws Exception {
        try (Response r = call("LOCK", dav("/new.txt"), "<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockinfo>")) {
            assertEquals(201, r.code());
            assertTrue(r.header("Lock-Token"), r.header("Lock-Token").startsWith("<opaquelocktoken:"));
            assertTrue(r.body().string().contains("lockdiscovery"));
        }
        assertTrue(new File(root, "new.txt").exists());
        try (Response r = call("UNLOCK", dav("/new.txt"), null, "Lock-Token", "<opaquelocktoken:x>")) {
            assertEquals(204, r.code());
        }
    }

    @Test public void withAPinTheDriveAsksForItAsTheBasicPassword() throws Exception {
        server.setPin("4821");
        try (Response r = call("PROPFIND", dav("/"), "", "Depth", "0")) {
            assertEquals(401, r.code());
            assertTrue(r.header("WWW-Authenticate"), r.header("WWW-Authenticate").startsWith("Basic"));
        }
        try (Response r = call("PROPFIND", dav("/"), "", "Depth", "0", "Authorization", okhttp3.Credentials.basic("anyone", "0000"))) {
            assertEquals(401, r.code());
        }
        try (Response r = call("PROPFIND", dav("/"), "", "Depth", "0", "Authorization", okhttp3.Credentials.basic("anyone", "4821"))) {
            assertEquals(207, r.code());
        }
        try (Remote ok = new DavRemote(conn("me", "4821"))) {
            assertEquals(0, ok.list("/").size());
        }
        // five wrong tries lock the address out, even for the right PIN
        for (int i = 0; i < 5; i++) {
            try (Response r = call("PROPFIND", dav("/"), "", "Depth", "0", "Authorization", okhttp3.Credentials.basic("x", "bad" + i))) {
                assertEquals(401, r.code());
            }
        }
        try (Response r = call("PROPFIND", dav("/"), "", "Depth", "0", "Authorization", okhttp3.Credentials.basic("x", "4821"))) {
            assertEquals(401, r.code());
        }
    }

    @Test public void theDriveNeedsTheSecretPathAndIsOffWhenDisabled() throws Exception {
        try (Response r = call("PROPFIND", "/d/wrongtoken/", "", "Depth", "0")) {
            assertEquals(403, r.code());
        }
        server.setDav(null, true, null);
        assertFalse(server.davEnabled());
        try (Response r = call("PROPFIND", dav("/"), "", "Depth", "0")) {
            assertEquals(403, r.code());
        }
    }

    @Test public void passesTheLitmusBasicCopyMoveAndHttpSuites() throws Exception {
        File litmus = ExternalServer.find("litmus");
        Assume.assumeNotNull("litmus isn't installed (apt install litmus)", litmus);
        ProcessBuilder pb = new ProcessBuilder(litmus.getPath(), "http://127.0.0.1:" + port + "/d/" + TOKEN + "/");
        pb.environment().put("TESTS", "basic copymove http");
        pb.directory(tmp.newFolder("litmus-run"));          // litmus writes a debug.log into its working folder
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        p.waitFor();
        System.out.println(out);
        int failed = 0;
        for (String line : out.split("\n")) {
            if (line.contains("FAIL")) {
                failed++;
                System.out.println("LITMUS FAILURE: " + line);
            }
        }
        assertTrue("litmus reported failures:\n" + out, failed == 0);
        assertTrue(out, out.contains("summary for `basic'") && out.contains("summary for `copymove'"));
    }
}
