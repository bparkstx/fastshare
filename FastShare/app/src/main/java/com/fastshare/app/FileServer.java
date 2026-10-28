package com.fastshare.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
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

/**
 * Tiny, dependency-free HTTP server tuned for raw throughput:
 * 1 MB buffers, large socket buffers, TCP_NODELAY, no chunking, no compression,
 * and uploads are streamed straight to disk as a single raw body (no multipart parsing).
 */
public class FileServer {

    public interface Listener {
        void onLog(String message);
        void onProgress(String message);
        void onFilesChanged();
    }

    public static class Shared {
        public final String id;
        public final Uri uri;
        public final String name;
        public final long size;

        Shared(String id, Uri uri, String name, long size) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    private static final int BUFFER = 1 << 20; // 1 MB

    private final Context ctx;
    private final String token;
    private final Listener listener;
    private final List<Shared> shared = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private byte[] indexHtml;
    private int idCounter = 0;

    public FileServer(Context ctx, String token, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.token = token;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts listening. Tries the preferred port first, then any free port. Returns the port used. */
    public int start(int preferredPort) throws IOException {
        indexHtml = readAsset("index.html");
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

    private byte[] readAsset(String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ shared files

    public synchronized void addShared(Uri uri, String name, long size) {
        idCounter++;
        shared.add(new Shared(Integer.toString(idCounter), uri, name, size));
        listener.onFilesChanged();
    }

    public void clearShared() {
        shared.clear();
        listener.onFilesChanged();
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
            while ((line = readLine(in)) != null && line.length() > 0) {
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

            if (!token.equals(query.get("t"))) {
                sendText(out, 403, "Forbidden", "text/plain",
                        "Open the exact link or scan the QR code shown in the FastShare app.");
                return;
            }

            if ("GET".equals(method) && (path.equals("/") || path.equals("/index.html"))) {
                sendBytes(out, 200, "OK", "text/html; charset=utf-8", indexHtml);
            } else if ("GET".equals(method) && path.equals("/api/files")) {
                sendText(out, 200, "OK", "application/json; charset=utf-8", filesJson());
            } else if ("GET".equals(method) && path.startsWith("/dl/")) {
                serveDownload(out, path.substring(4));
            } else if ("POST".equals(method) && path.equals("/up")) {
                receiveUpload(in, out, headers, query.get("name"));
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

    private void serveDownload(OutputStream out, String id) throws IOException {
        Shared f = null;
        for (Shared c : shared) {
            if (c.id.equals(id)) { f = c; break; }
        }
        if (f == null) { sendText(out, 404, "Not Found", "text/plain", "No such file"); return; }

        InputStream src;
        try {
            src = ctx.getContentResolver().openInputStream(f.uri);
        } catch (Exception e) {
            src = null;
        }
        if (src == null) { sendText(out, 500, "Error", "text/plain", "Cannot open file"); return; }

        try {
            String ascii = f.name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
            String utf8 = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20");
            StringBuilder h = new StringBuilder("HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: application/octet-stream\r\n");
            if (f.size >= 0) h.append("Content-Length: ").append(f.size).append("\r\n");
            h.append("Content-Disposition: attachment; filename=\"").append(ascii)
                    .append("\"; filename*=UTF-8''").append(utf8).append("\r\n");
            h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));

            byte[] buf = new byte[BUFFER];
            long total = 0;
            long t0 = System.nanoTime();
            long lastReport = t0;
            int n;
            while ((n = src.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                long now = System.nanoTime();
                if (now - lastReport > 250_000_000L) {
                    lastReport = now;
                    listener.onProgress("Sending " + f.name + ": " + fmtBytes(total) + "  " + fmtSpeed(total, now - t0));
                }
            }
            out.flush();
            listener.onLog("Sent " + f.name + " (" + fmtBytes(total) + " @ " + fmtSpeed(total, System.nanoTime() - t0) + ")");
            listener.onProgress("");
        } finally {
            try { src.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ upload (other device -> phone)

    private void receiveUpload(InputStream in, OutputStream out, Map<String, String> headers, String rawName)
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
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FastShare");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri dest = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        OutputStream os = dest == null ? null : resolver.openOutputStream(dest);
        if (dest == null || os == null) {
            sendText(out, 500, "Error", "text/plain", "Cannot create file in Downloads");
            return;
        }

        boolean ok = false;
        try {
            byte[] buf = new byte[BUFFER];
            long remaining = length;
            long total = 0;
            long t0 = System.nanoTime();
            long lastReport = t0;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("Connection closed early");
                os.write(buf, 0, n);
                remaining -= n;
                total += n;
                long now = System.nanoTime();
                if (now - lastReport > 250_000_000L) {
                    lastReport = now;
                    int pct = length > 0 ? (int) (total * 100 / length) : 100;
                    listener.onProgress("Receiving " + name + ": " + pct + "%  " + fmtSpeed(total, now - t0));
                }
            }
            os.close();
            os = null;
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(dest, done, null, null);
            ok = true;
            listener.onLog("Received " + name + " (" + fmtBytes(total) + " @ " + fmtSpeed(total, System.nanoTime() - t0)
                    + ") -> Downloads/FastShare");
            listener.onProgress("");
            sendText(out, 200, "OK", "application/json; charset=utf-8", "{\"ok\":true}");
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignored) { }
            }
            if (!ok) {
                try { resolver.delete(dest, null, null); } catch (Exception ignored) { }
                listener.onLog("Upload of " + name + " did not finish");
                listener.onProgress("");
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
                    .append(",\"size\":").append(f.size).append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String sanitize(String raw) {
        String n = raw == null ? "" : raw;
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "upload_" + System.currentTimeMillis();
        if (n.length() > 180) n = n.substring(n.length() - 180);
        return n;
    }

    private static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
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

    public static String fmtBytes(long b) {
        if (b < 1000) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1000 && i < u.length - 1) { v /= 1000; i++; }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    public static String fmtSpeed(long bytes, long nanos) {
        double sec = Math.max(nanos / 1e9, 0.001);
        double mbps = bytes * 8.0 / 1e6 / sec;
        return String.format(Locale.US, "%.1f MB/s (%.0f Mbps)", bytes / 1e6 / sec, mbps);
    }
}
