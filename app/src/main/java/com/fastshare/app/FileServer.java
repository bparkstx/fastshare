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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        /** Folder for half-received uploads so they can be resumed. Null turns resumable uploads off. */
        default File partialDir() { return null; }
    }

    public interface Sink {
        OutputStream stream();
        /** Called after all bytes were written and the stream was closed. */
        void commit() throws IOException;
        /** Called when the transfer failed; remove any partial file. */
        void abort();
        /** Human readable place the file ended up, e.g. "Downloads/FileBandit". */
        String location();
    }

    public interface Listener {
        /** An authorised request arrived from a device (called often; cheap to ignore). */
        void onClient(String ip, String userAgent);
        void onSharedChanged();
        /** A transfer started, progressed (throttled) or finished. Read the fields of {@code t}. */
        void onTransfer(Transfer t);
        /** A shared note was added or the notes were cleared. */
        default void onNotesChanged() { }
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

    /** A line of text passed between the phone and the other device (a link, an address, a code). */
    public static final class Note {
        public final int id;
        public final boolean fromPhone;
        public final String text;
        public final long time;

        Note(int id, boolean fromPhone, String text, long time) {
            this.id = id;
            this.fromPhone = fromPhone;
            this.text = text;
            this.time = time;
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
        public volatile long touched = System.currentTimeMillis();   // last progress, for noticing a stalled resumable upload
        public volatile long base;       // bytes already there when this attempt began (resumed transfers)
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
            return Fmt.speed(Math.max(0, done - base), nanos);
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
    private java.util.concurrent.ScheduledExecutorService stallTimer;
    private byte[] indexHtml;

    // optional PIN on the link
    private static final SecureRandom RANDOM = new SecureRandom();
    private volatile String pin;
    private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private static final class Attempts {
        int misses;
        long lockedUntil;
    }

    // resumable uploads
    private final Map<String, Transfer> resumable = new ConcurrentHashMap<>();
    private final Map<String, Boolean> receiving = new ConcurrentHashMap<>();
    static volatile long STALL_MS = 20_000;
    static final long PARTIAL_MAX_AGE_MS = 3L * 24 * 3600 * 1000;

    public FileServer(String token, Backend backend, Listener listener) {
        this.token = token;
        this.backend = backend;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts listening on all interfaces. Tries the preferred port, then any free port. Returns the port used. */
    public int start(int preferredPort) throws IOException {
        indexHtml = backend.indexPage();
        sweepPartials();
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
        stallTimer = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread th = new Thread(r, "fastshare-stall");
                th.setDaemon(true);
                return th;
            }
        });
        stallTimer.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { markStalled(); }
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
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
        if (stallTimer != null) stallTimer.shutdownNow();
        pool.shutdownNow();
    }

    private static ServerSocket newServerSocket() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.setReceiveBufferSize(BUFFER); // must be set before bind for TCP window scaling
        return ss;
    }

    // ------------------------------------------------------------------ shared notes

    static final int NOTE_MAX_CHARS = 20_000;
    static final int NOTE_KEEP = 50;
    private final List<Note> notes = new CopyOnWriteArrayList<>();

    /** Adds a note (newest are shown first). Overlong text is cut. Returns null for empty text. */
    public Note addNote(String text, boolean fromPhone) {
        if (text == null) return null;
        String t = text.length() > NOTE_MAX_CHARS ? text.substring(0, NOTE_MAX_CHARS) : text;
        if (t.trim().isEmpty()) return null;
        Note n = new Note(idCounter.incrementAndGet(), fromPhone, t, System.currentTimeMillis());
        notes.add(0, n);
        while (notes.size() > NOTE_KEEP) notes.remove(notes.size() - 1);
        listener.onNotesChanged();
        return n;
    }

    public List<Note> getNotes() {
        return new java.util.ArrayList<>(notes);
    }

    public void removeNote(int id) {
        for (Note n : notes) if (n.id == id) notes.remove(n);
        listener.onNotesChanged();
    }

    public void clearNotes() {
        notes.clear();
        listener.onNotesChanged();
    }

    private String notesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Note n : notes) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(n.id).append(",\"from\":\"").append(n.fromPhone ? "phone" : "other")
                    .append("\",\"text\":").append(jsonStr(n.text)).append(",\"time\":").append(n.time).append('}');
        }
        return sb.append(']').toString();
    }

    private void receiveNote(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        long len;
        try {
            len = Long.parseLong(headers.get("content-length") == null ? "-1" : headers.get("content-length"));
        } catch (NumberFormatException e) {
            len = -1;
        }
        if (len < 0) { sendText(out, 411, "Length Required", "application/json", "{\"ok\":false}"); return; }
        if (len > 4L * NOTE_MAX_CHARS) { sendText(out, 413, "Too Large", "application/json", "{\"ok\":false,\"error\":\"too long\"}"); return; }
        byte[] body = new byte[(int) len];
        int got = 0;
        while (got < body.length) {
            int n = in.read(body, got, body.length - got);
            if (n < 0) throw new EOFException();
            got += n;
        }
        Note n = addNote(new String(body, StandardCharsets.UTF_8), false);
        sendText(out, n == null ? 400 : 200, n == null ? "Bad Request" : "OK", "application/json; charset=utf-8",
                n == null ? "{\"ok\":false,\"error\":\"empty\"}" : "{\"ok\":true,\"id\":" + n.id + "}");
    }

    // ------------------------------------------------------------------ PIN

    /** Asks every device for this PIN before it can use the link. Null or empty turns it off. Signs everybody out. */
    public void setPin(String p) {
        pin = p == null || p.isEmpty() ? null : p;
        sessions.clear();
        attempts.clear();
    }

    public String getPin() {
        return pin;
    }

    private boolean signedIn(Map<String, String> headers) {
        String cookie = headers.get("cookie");
        if (cookie == null) return false;
        for (String part : cookie.split(";")) {
            String c = part.trim();
            if (c.startsWith("fbs=") && sessions.containsKey(c.substring(4))) return true;
        }
        return false;
    }

    private void tryPin(InputStream in, OutputStream out, Map<String, String> headers, String ip) throws IOException {
        long len = 0;
        try {
            len = Long.parseLong(headers.get("content-length") == null ? "0" : headers.get("content-length"));
        } catch (NumberFormatException ignored) { }
        if (len < 0 || len > 512) { sendText(out, 413, "Too Large", "application/json", "{\"ok\":false}"); return; }
        byte[] body = new byte[(int) len];
        int got = 0;
        while (got < body.length) {
            int n = in.read(body, got, body.length - got);
            if (n < 0) throw new EOFException();
            got += n;
        }
        Map<String, String> form = new HashMap<>();
        parseQuery(new String(body, StandardCharsets.UTF_8), form);
        String tried = form.get("pin") == null ? "" : form.get("pin");

        Attempts a = attempts.get(ip);
        if (a == null) {
            a = new Attempts();
            attempts.put(ip, a);
        }
        long now = System.currentTimeMillis();
        synchronized (a) {
            if (a.lockedUntil > now) {
                sendText(out, 429, "Too Many Requests", "application/json; charset=utf-8",
                        "{\"ok\":false,\"wait\":" + ((a.lockedUntil - now) / 1000 + 1) + "}");
                return;
            }
            String real = pin;
            boolean ok = real != null && MessageDigest.isEqual(real.getBytes(StandardCharsets.UTF_8), tried.getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                a.misses++;
                long wait = 0;
                if (a.misses >= 5) {
                    wait = Math.min(600_000L, 30_000L << Math.min(a.misses - 5, 5));
                    a.lockedUntil = now + wait;
                }
                sendText(out, 403, "Forbidden", "application/json; charset=utf-8",
                        "{\"ok\":false,\"wait\":" + (wait / 1000) + "}");
                return;
            }
            a.misses = 0;
            a.lockedUntil = 0;
        }
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        StringBuilder id = new StringBuilder();
        for (byte b : raw) id.append(String.format(Locale.US, "%02x", b));
        sessions.put(id.toString(), Boolean.TRUE);
        byte[] json = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + json.length
                + "\r\nSet-Cookie: fbs=" + id + "; Path=/; HttpOnly; SameSite=Lax\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(json);
        out.flush();
    }

    private static String pinPage() {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>FileBandit</title><style>body{font:17px/1.5 -apple-system,system-ui,sans-serif;margin:0;padding:32px 20px;background:#f3f5f8;color:#15181d}"
                + "@media(prefers-color-scheme:dark){body{background:#0e1013;color:#eceff3}input{background:#181b20!important;color:#eceff3!important;border-color:#2a2f37!important}}"
                + "main{max-width:380px;margin:8vh auto 0;text-align:center}h2{margin:0 0 6px}p{color:#667085;margin:0 0 20px}"
                + "input{width:100%;box-sizing:border-box;font-size:28px;letter-spacing:.3em;text-align:center;padding:12px;border-radius:14px;border:1px solid #d0d5dd}"
                + "button{margin-top:14px;width:100%;font-size:17px;font-weight:600;padding:14px;border:0;border-radius:14px;background:#1a73e8;color:#fff}"
                + "#msg{color:#d93025;min-height:1.5em;margin-top:12px}</style></head><body><main>"
                + "<h2>Enter the PIN</h2><p>It is shown on the Android phone, under the QR code.</p>"
                + "<form id=f><input id=p name=pin inputmode=numeric autocomplete=off autofocus placeholder=&bull;&bull;&bull;&bull;>"
                + "<button type=submit>Open</button></form><div id=msg></div></main><script>"
                + "var f=document.getElementById('f'),m=document.getElementById('msg');"
                + "f.onsubmit=function(e){e.preventDefault();var x=new XMLHttpRequest();x.open('POST','/pin'+location.search);"
                + "x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');"
                + "x.onload=function(){var j={};try{j=JSON.parse(x.responseText)}catch(e){}"
                + "if(x.status==200){location.reload()}else if(x.status==429||j.wait){m.textContent='Too many tries. Wait '+j.wait+' seconds.'}"
                + "else{m.textContent='That PIN isn\\u2019t right.';document.getElementById('p').value=''}};"
                + "x.onerror=function(){m.textContent='Can\\u2019t reach the phone.'};"
                + "x.send('pin='+encodeURIComponent(document.getElementById('p').value))};</script></body></html>";
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

            if ("POST".equals(method) && path.equals("/pin")) {
                if (pin == null) sendText(out, 200, "OK", "application/json", "{\"ok\":true}");
                else tryPin(in, out, headers, ip);
                return;
            }
            if (pin != null && !signedIn(headers)) {
                if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                    sendBytes(out, 200, "OK", "text/html; charset=utf-8", pinPage().getBytes(StandardCharsets.UTF_8));
                } else {
                    sendText(out, 401, "Unauthorized", "application/json; charset=utf-8", "{\"pin\":true}");
                }
                return;
            }

            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                sendBytes(out, 200, "OK", "text/html; charset=utf-8", indexHtml);
            } else if ("GET".equals(method) && path.equals("/api/info")) {
                sendText(out, 200, "OK", "application/json; charset=utf-8",
                        "{\"device\":" + jsonStr(backend.deviceName()) + ",\"ok\":true}");
            } else if ("GET".equals(method) && path.equals("/api/notes")) {
                sendText(out, 200, "OK", "application/json; charset=utf-8", notesJson());
            } else if ("POST".equals(method) && path.equals("/api/notes")) {
                receiveNote(in, out, headers);
            } else if ("POST".equals(method) && path.equals("/api/notes/clear")) {
                clearNotes();
                sendText(out, 200, "OK", "application/json", "{\"ok\":true}");
            } else if ("GET".equals(method) && path.equals("/api/files")) {
                sendText(out, 200, "OK", "application/json; charset=utf-8", filesJson());
            } else if (("GET".equals(method) || "HEAD".equals(method)) && path.equals("/dl/all")) {
                serveAll(out, ip, "HEAD".equals(method));
            } else if (("GET".equals(method) || "HEAD".equals(method)) && path.startsWith("/dl/")) {
                serveDownload(out, path.substring(4), ip, headers.get("range"), "HEAD".equals(method));
            } else if ("POST".equals(method) && path.equals("/up")) {
                receiveUpload(in, out, headers, query.get("name"), ip);
            } else if ("GET".equals(method) && path.equals("/up2/status")) {
                uploadStatus(out, query.get("uid"));
            } else if ("POST".equals(method) && path.equals("/up2")) {
                receiveChunk(in, out, headers, query, ip);
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

    /** Parses a single "bytes=a-b" range. Null: can't be satisfied. {-1,-1}: ignore it and send everything. */
    static long[] parseRange(String header, long size) {
        if (header == null || size <= 0) return new long[]{-1, -1};
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, 6) || h.indexOf(',') >= 0) return new long[]{-1, -1};
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return new long[]{-1, -1};
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (a.isEmpty()) {                       // last N bytes
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                start = Math.max(0, size - n);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
            }
            if (start < 0 || start >= size || end < start) return null;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return new long[]{-1, -1};
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) {
                if (in.read() < 0) throw new EOFException();
                k = 1;
            }
            n -= k;
        }
    }

    private void serveDownload(OutputStream out, String id, String ip, String range, boolean head) throws IOException {
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

        try {
            long from = 0;
            long to = size - 1;
            boolean partial = false;
            if (!zipFolder && size >= 0) {
                long[] r = parseRange(range, size);
                if (r == null) {
                    String h = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    out.write(h.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    return;
                }
                if (r[0] >= 0) {
                    from = r[0];
                    to = r[1];
                    partial = true;
                }
            }
            long length = zipFolder || size < 0 ? -1 : to - from + 1;

            String ascii = f.name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
            String utf8 = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20");
            StringBuilder h = new StringBuilder(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(zipFolder ? "application/zip" : "application/octet-stream").append("\r\n");
            if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
            if (!zipFolder) h.append("Accept-Ranges: bytes\r\n");
            if (partial) h.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
            h.append("Content-Disposition: attachment; filename=\"").append(ascii)
                    .append("\"; filename*=UTF-8''").append(utf8).append("\r\n");
            h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (head) {
                out.flush();
                return;
            }

            Transfer t = new Transfer(idCounter.incrementAndGet(), false, f.name, ip, length);
            listener.onTransfer(t);
            long t0 = System.nanoTime();
            try {
                if (zipFolder) {
                    FileOps.zipDirectory((File) f.ref, counting(out, t, t0));
                } else {
                    if (from > 0) skipFully(src, from);
                    byte[] buf = new byte[BUFFER];
                    long remaining = length;
                    long last = t0;
                    while (remaining > 0) {
                        int n = src.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (n < 0) throw new EOFException("File ended early");
                        out.write(buf, 0, n);
                        remaining -= n;
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
            }
        } finally {
            if (src != null) {
                try { src.close(); } catch (IOException ignored) { }
            }
        }
    }

    private OutputStream counting(final OutputStream out, final Transfer t, final long start) {
        return new OutputStream() {
            long last = start;
            @Override public void write(int b) throws IOException { out.write(b); count(1); }
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count(len); }
            private void count(int n) {
                t.done += n;
                long now = System.nanoTime();
                t.nanos = now - start;
                if (now - last > 250_000_000L) { last = now; listener.onTransfer(t); }
            }
        };
    }

    /** Everything that is being shared, in one zip. */
    private void serveAll(OutputStream out, String ip, boolean head) throws IOException {
        if (shared.isEmpty()) { sendText(out, 404, "Not Found", "text/plain", "Nothing is shared"); return; }
        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename=\"FileBandit.zip\"\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        if (head) {
            out.flush();
            return;
        }
        Transfer t = new Transfer(idCounter.incrementAndGet(), false, "FileBandit.zip", ip, -1);
        listener.onTransfer(t);
        long t0 = System.nanoTime();
        try {
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(counting(out, t, t0));
            zos.setLevel(1);
            java.util.Set<String> used = new java.util.HashSet<>();
            for (Shared s : shared) {
                String base = s.dir && s.name.endsWith(".zip") ? s.name.substring(0, s.name.length() - 4) : s.name;
                String name = base;
                for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) {
                    int dot = base.lastIndexOf('.');
                    name = dot > 0 && !s.dir ? base.substring(0, dot) + " (" + i + ")" + base.substring(dot) : base + " (" + i + ")";
                }
                if (s.ref instanceof File) {
                    FileOps.addToZip(zos, (File) s.ref, name);
                } else {
                    InputStream in = backend.openShared(s.ref);
                    try {
                        zos.putNextEntry(new java.util.zip.ZipEntry(name));
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                        zos.closeEntry();
                    } finally {
                        in.close();
                    }
                }
            }
            zos.finish();
            zos.flush();
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

    // ------------------------------------------------------------------ resumable upload (/up2)

    private static boolean validUid(String uid) {
        return uid != null && uid.matches("[A-Za-z0-9_-]{8,64}");
    }

    private void uploadStatus(OutputStream out, String uid) throws IOException {
        File dir = backend.partialDir();
        if (dir == null) { sendText(out, 501, "Not Implemented", "application/json", "{\"error\":\"unsupported\"}"); return; }
        if (!validUid(uid)) { sendText(out, 400, "Bad Request", "application/json", "{\"error\":\"bad uid\"}"); return; }
        File part = new File(dir, uid + ".part");
        sendText(out, 200, "OK", "application/json; charset=utf-8", "{\"received\":" + (part.isFile() ? part.length() : 0) + "}");
    }

    private void receiveChunk(InputStream in, OutputStream out, Map<String, String> headers, Map<String, String> q, String ip)
            throws IOException {
        File dir = backend.partialDir();
        if (dir == null) { sendText(out, 501, "Not Implemented", "application/json", "{\"error\":\"unsupported\"}"); return; }
        String uid = q.get("uid");
        if (!validUid(uid)) { sendText(out, 400, "Bad Request", "application/json", "{\"error\":\"bad uid\"}"); return; }
        long length;
        long offset;
        long total;
        try {
            String lh = headers.get("content-length");
            if (lh == null) { sendText(out, 411, "Length Required", "text/plain", "Length required"); return; }
            length = Long.parseLong(lh);
            offset = Long.parseLong(q.get("offset"));
            total = Long.parseLong(q.get("total"));
        } catch (NumberFormatException e) {
            sendText(out, 400, "Bad Request", "application/json", "{\"error\":\"bad numbers\"}");
            return;
        }
        if (length < 0 || offset < 0 || total < 0 || offset + length > total) {
            sendText(out, 400, "Bad Request", "application/json", "{\"error\":\"bad range\"}");
            return;
        }
        if (receiving.putIfAbsent(uid, Boolean.TRUE) != null) {
            File p0 = new File(dir, uid + ".part");
            sendText(out, 409, "Conflict", "application/json", "{\"received\":" + (p0.isFile() ? p0.length() : 0) + ",\"busy\":true}");
            return;
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File part = new File(dir, uid + ".part");
            long have = part.isFile() ? part.length() : 0;
            if (offset != have) {
                sendText(out, 409, "Conflict", "application/json", "{\"received\":" + have + "}");
                return;
            }
            String name = sanitize(q.get("name"));
            Transfer t = resumable.get(uid);
            if (t == null || t.total != total) {
                t = new Transfer(idCounter.incrementAndGet(), true, name, ip, total);
                resumable.put(uid, t);
            } else {
                t.state = State.ACTIVE;
                t.detail = "";
            }
            t.done = offset;
            t.base = offset;
            t.nanos = 0;
            t.touched = System.currentTimeMillis();
            listener.onTransfer(t);
            long t0 = System.nanoTime();
            try {
                if (length > 0 || !part.isFile()) {
                    OutputStream os = new java.io.FileOutputStream(part, true);
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
                            t.touched = System.currentTimeMillis();
                            if (now - last > 250_000_000L) { last = now; listener.onTransfer(t); }
                        }
                    } finally {
                        os.close();
                    }
                }
            } catch (IOException e) {
                t.nanos = System.nanoTime() - t0;
                t.state = State.FAILED;
                t.detail = "Paused";
                listener.onTransfer(t);
                throw e;
            }
            have = part.length();
            if (have < total) {
                t.touched = System.currentTimeMillis();
                listener.onTransfer(t);
                sendText(out, 200, "OK", "application/json; charset=utf-8", "{\"ok\":true,\"received\":" + have + "}");
                return;
            }
            // all bytes are here: hand the file to its final home
            Sink sink = null;
            try {
                sink = backend.openSink(name);
                OutputStream dest = sink.stream();
                InputStream src = new FileInputStream(part);
                try {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = src.read(buf)) > 0) dest.write(buf, 0, n);
                } finally {
                    src.close();
                }
                dest.close();
                sink.commit();
            } catch (IOException e) {
                if (sink != null) sink.abort();
                t.state = State.FAILED;
                t.detail = "Couldn't save: " + e.getMessage();
                listener.onTransfer(t);
                sendText(out, 500, "Error", "application/json; charset=utf-8", "{\"error\":" + jsonStr("Couldn't save the file: " + e.getMessage()) + "}");
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            resumable.remove(uid);
            t.nanos = System.nanoTime() - t0;
            t.state = State.DONE;
            t.detail = sink.location();
            listener.onTransfer(t);
            sendText(out, 200, "OK", "application/json; charset=utf-8", "{\"ok\":true,\"done\":true,\"received\":" + total + "}");
        } finally {
            receiving.remove(uid);
        }
    }

    /** Throws away half-received uploads nobody came back for, and marks stalled ones as paused. */
    private void sweepPartials() {
        File dir = backend.partialDir();
        if (dir == null || !dir.isDirectory()) return;
        File[] old = dir.listFiles();
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - PARTIAL_MAX_AGE_MS;
        for (File f : old) {
            if (f.getName().endsWith(".part") && f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private void markStalled() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Transfer> e : resumable.entrySet()) {
            Transfer t = e.getValue();
            if (t.state == State.ACTIVE && now - t.touched > STALL_MS && !receiving.containsKey(e.getKey())) {
                t.state = State.FAILED;
                t.detail = "Paused";
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
                + "<p>Open FileBandit on the Android phone and scan its QR code again with the iPhone Camera app, "
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
