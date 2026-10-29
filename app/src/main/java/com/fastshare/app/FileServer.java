package com.fastshare.app;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny, dependency-free HTTP server tuned for raw throughput: 1 MB buffers, large socket buffers,
 * TCP_NODELAY, no compression, and uploads streamed straight to storage as one raw body.
 *
 * It has no Android imports on purpose: everything platform specific goes through {@link Backend},
 * so the whole thing can be exercised by plain JUnit tests.
 */
public class FileServer {

    /** Platform hooks (assets, Downloads folder, content:// files). */
    public interface Backend {
        byte[] indexPage() throws IOException;
        String deviceName();
        /** Opens a shared item whose ref is not a java.io.File (for example a content:// Uri). */
        InputStream openShared(Object ref) throws IOException;
        /** Creates the destination for a file received from the other device. */
        Sink openSink(String fileName) throws IOException;
    }

    public interface Sink {
        OutputStream stream();
        /** Called after all bytes were written and the stream was closed. */
        void commit() throws IOException;
        /** Called when the transfer failed; remove any partial file. */
        void abort();
        /** Human readable place the file ended up, e.g. "Downloads/FastShare". */
        String location();
    }

    public interface Listener {
        /** An authorised request arrived from a device (called often; cheap to ignore). */
        void onClient(String ip, String userAgent);
        void onSharedChanged();
        /** A transfer started, progressed (throttled) or finished. Read the fields of {@code t}. */
        void onTransfer(Transfer t);
    }

    /** An item the phone offers for download. */
    public static final class Shared {
        public final String id;
        public final Object ref;   // java.io.File or a platform handle such as a content Uri
        public final String name;  // as shown to the other device (folders get ".zip" appended)
        public final long size;    // -1 when unknown (folders are zipped on the fly)
        public final boolean dir;

        Shared(String id, Object ref, String name, long size, boolean dir) {
            this.id = id;
            this.ref = ref;
            this.name = name;
            this.size = size;
            this.dir = dir;
        }
    }

    public enum State { ACTIVE, DONE, FAILED }

    /** One upload or download, for progress bars. Fields are updated by worker threads. */
    public static final class Transfer {
        public final int id;
        public final boolean incoming;   // true = the other device is sending to this phone
        public final String name;
        public final String client;
        public final long total;         // bytes, -1 unknown
        public final long startedAt = System.currentTimeMillis();
        public volatile long done;
        public volatile long nanos;      // elapsed time of the transfer so far
        public volatile State state = State.ACTIVE;
        public volatile String detail = "";  // location on success, reason on failure

        Transfer(int id, boolean incoming, String name, String client, long total) {
            this.id = id;
            this.incoming = incoming;
            this.name = name;
            this.client = client;
            this.total = total;
        }

        public String speed() {
            return Fmt.speed(done, nanos);
        }
    }

    private static final int BUFFER = 1 << 20; // 1 MB

    private final String token;
    private final Backend backend;
    private final Listener listener;
    private final List<Shared> shared = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger idCounter = new AtomicInteger();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private byte[] indexHtml;

    public FileServer(String token, Backend backend, Listener listener) {
        this.token = token;
        this.backend = backend;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts listening on all interfaces. Tries the preferred port, then any free port. Returns the port used. */
    public int start(int preferredPort) throws IOException {
        indexHtml = backend.indexPage();
        ServerSocket ss = newServerSocket();
        try {
            ss.bind(new InetSocketAddress(preferredPort));
        } catch (IOException busy) {
            try { ss.close(); } catch (IOException ignored) { }
            ss = newServerSocket();
            ss.bind(new InetSocketAddress(0));
        }
        serverSocket = ss;
        running = true;
        final ServerSocket accepting = ss;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(accepting); }
        }, "fastshare-accept");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    private static ServerSocket newServerSocket() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.setReceiveBufferSize(BUFFER); // must be set before bind for TCP window scaling
        return ss;
    }

    // ------------------------------------------------------------------ shared items

    /** Offers a file (or a folder, sent as a zip) for download. */
    public Shared addShared(Object ref, String name, long size, boolean dir) {
        Shared s = new Shared(Integer.toString(idCounter.incrementAndGet()), ref,
                dir ? name + ".zip" : name, dir ? -1 : size, dir);
        shared.add(s);
        listener.onSharedChanged();
        return s;
    }

    public void removeShared(String id) {
        for (Shared s : shared) {
            if (s.id.equals(id)) shared.remove(s);
        }
        listener.onSharedChanged();
    }

    public void clearShared() {
        shared.clear();
        listener.onSharedChanged();
    }

    public List<Shared> getShared() {
        return shared;
    }

    // ------------------------------------------------------------------ networking

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                final Socket s = ss.accept();
                pool.execute(new Runnable() {
                    @Override public void run() { handle(s); }
                });
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                return; // pool shut down
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSendBufferSize(BUFFER);
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { sendText(out, 400, "Bad Request", "text/plain", "Bad request"); return; }
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            int count = 0;
            while ((line = readLine(in)) != null && line.length() > 0) {
                if (++count > 100) throw new IOException("Too many headers");
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }

            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                parseQuery(target.substring(q + 1), query);
            }

            if (path.equals("/favicon.ico") || path.startsWith("/apple-touch-icon")) {
                sendBytes(out, 204, "No Content", "text/plain", new byte[0]);
                return;
            }

            String ip = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
            if (!token.equals(query.get("t"))) {
                sendBytes(out, 403, "Forbidden", "text/html; charset=utf-8", forbiddenPage().getBytes(StandardCharsets.UTF_8));
                return;
            }
            listener.onClient(ip, headers.get("user-agent"));

            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                sendBytes(out, 200, "OK", "text/html; charset=utf-8", indexHtml);
            } else if ("GET".equals(method) && path.equals("/api/info")) {
                sendText(out, 200, "OK", "application/json; charset=utf-8",
                        "{\"device\":" + jsonStr(backend.deviceName()) + ",\"ok\":true}");
            } else if ("GET".equals(method) && path.equals("/api/files")) {
                sendText(out, 200, "OK", "application/json; charset=utf-8", filesJson());
            } else if ("GET".equals(method) && path.startsWith("/dl/")) {
                serveDownload(out, path.substring(4), ip);
            } else if ("POST".equals(method) && path.equals("/up")) {
                receiveUpload(in, out, headers, query.get("name"), ip);
            } else {
                sendText(out, 404, "Not Found", "text/plain", "Not found");
            }
        } catch (IOException e) {
            // client went away; nothing to do
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ download (phone -> other device)

    private void serveDownload(OutputStream out, String id, String ip) throws IOException {
        Shared f = null;
        for (Shared c : shared) {
            if (c.id.equals(id)) { f = c; break; }
        }
        if (f == null) { sendText(out, 404, "Not Found", "text/plain", "No such file"); return; }

        InputStream src = null;
        long size = f.size;
        boolean zipFolder = false;
        try {
            if (f.ref instanceof File) {
                File file = (File) f.ref;
                if (f.dir) {
                    zipFolder = file.isDirectory();
                } else {
                    src = new FileInputStream(file);
                    size = file.length();
                }
            } else {
                src = backend.openShared(f.ref);
            }
        } catch (IOException e) {
            src = null;
        }
        if (src == null && !zipFolder) { sendText(out, 500, "Error", "text/plain", "Cannot open file"); return; }

        Transfer t = new Transfer(idCounter.incrementAndGet(), false, f.name, ip, zipFolder ? -1 : size);
        listener.onTransfer(t);
        long t0 = System.nanoTime();
        try {
            String ascii = f.name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
            String utf8 = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20");
            StringBuilder h = new StringBuilder("HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(zipFolder ? "application/zip" : "application/octet-stream").append("\r\n");
            if (size >= 0 && !zipFolder) h.append("Content-Length: ").append(size).append("\r\n");
            h.append("Content-Disposition: attachment; filename=\"").append(ascii)
                    .append("\"; filename*=UTF-8''").append(utf8).append("\r\n");
            h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (zipFolder) {
                final Transfer tt = t;
                final long start = t0;
                OutputStream counting = new OutputStream() {
                    long last = start;
                    @Override public void write(int b) throws IOException { out.write(b); count(1); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count(len); }
                    private void count(int n) {
                        tt.done += n;
                        long now = System.nanoTime();
                        tt.nanos = now - start;
                        if (now - last > 250_000_000L) { last = now; listener.onTransfer(tt); }
                    }
                };
                FileOps.zipDirectory((File) f.ref, counting);
            } else {
                byte[] buf = new byte[BUFFER];
                long last = t0;
                int n;
                while ((n = src.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    t.done += n;
                    long now = System.nanoTime();
                    t.nanos = now - t0;
                    if (now - last > 250_000_000L) { last = now; listener.onTransfer(t); }
                }
            }
            out.flush();
            t.nanos = System.nanoTime() - t0;
            t.state = State.DONE;
            t.detail = "Sent";
            listener.onTransfer(t);
        } catch (IOException e) {
            t.nanos = System.nanoTime() - t0;
            t.state = State.FAILED;
            t.detail = "Interrupted";
            listener.onTransfer(t);
            throw e;
        } finally {
            if (src != null) {
                try { src.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ upload (other device -> phone)

    private void receiveUpload(InputStream in, OutputStream out, Map<String, String> headers, String rawName, String ip)
            throws IOException {
        String lenHeader = headers.get("content-length");
        if (lenHeader == null) { sendText(out, 411, "Length Required", "text/plain", "Length required"); return; }
        long length;
        try {
            length = Long.parseLong(lenHeader);
        } catch (NumberFormatException e) {
            sendText(out, 400, "Bad Request", "text/plain", "Bad length");
            return;
        }

        String name = sanitize(rawName);
        Sink sink;
        try {
            sink = backend.openSink(name);
        } catch (IOException e) {
            sendText(out, 500, "Error", "text/plain", "Cannot create file: " + e.getMessage());
            return;
        }

        Transfer t = new Transfer(idCounter.incrementAndGet(), true, name, ip, length);
        listener.onTransfer(t);
        boolean ok = false;
        long t0 = System.nanoTime();
        OutputStream os = sink.stream();
        try {
            byte[] buf = new byte[BUFFER];
            long remaining = length;
            long last = t0;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("Connection closed early");
                os.write(buf, 0, n);
                remaining -= n;
                t.done += n;
                long now = System.nanoTime();
                t.nanos = now - t0;
                if (now - last > 250_000_000L) { last = now; listener.onTransfer(t); }
            }
            os.close();
            os = null;
            sink.commit();
            ok = true;
            t.nanos = System.nanoTime() - t0;
            t.state = State.DONE;
            t.detail = sink.location();
            listener.onTransfer(t);
            sendText(out, 200, "OK", "application/json; charset=utf-8", "{\"ok\":true}");
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignored) { }
            }
            if (!ok) {
                sink.abort();
                t.nanos = System.nanoTime() - t0;
                t.state = State.FAILED;
                t.detail = "Interrupted";
                listener.onTransfer(t);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private String filesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Shared f : shared) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(f.id).append("\",\"name\":").append(jsonStr(f.name))
                    .append(",\"size\":").append(f.size)
                    .append(",\"dir\":").append(f.dir).append('}');
        }
        return sb.append(']').toString();
    }

    private static String forbiddenPage() {
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='font:17px/1.5 -apple-system,system-ui,sans-serif;padding:24px;max-width:520px;margin:auto'>"
                + "<h2>This link is missing its code</h2>"
                + "<p>Open FastShare on the Android phone and scan its QR code again with the iPhone Camera app, "
                + "or type the whole link exactly as shown, including the part after <b>?t=</b>.</p>";
    }

    static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String sanitize(String raw) {
        String n = raw == null ? "" : raw;
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "upload_" + System.currentTimeMillis();
        if (n.length() > 180) n = n.substring(n.length() - 180);
        return n;
    }

    private static void parseQuery(String qs, Map<String, String> into) {
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) into.put(URLDecoder.decode(pair, "UTF-8"), "");
                else into.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("Header too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void sendText(OutputStream out, int code, String status, String type, String body) throws IOException {
        sendBytes(out, code, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(OutputStream out, int code, String status, String type, byte[] body) throws IOException {
        String h = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
