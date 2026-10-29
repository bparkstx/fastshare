package com.fastshare.app;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * A WebDAV server (class 1 and 2) over one folder, so Windows, macOS and Linux can mount the phone as a drive.
 * No Android imports. {@link FileServer} routes requests under its secret path here and does the sign-in.
 *
 * Locks are accepted and honoured only in the sense that clients are told they succeeded: there is one user, and
 * macOS Finder refuses to write to a server that won't grant them.
 */
public final class WebDav {

    /** Removes a file or folder. The app plugs in "move to the recycle bin". */
    public interface Deleter {
        void delete(File f) throws IOException;
    }

    public static final Deleter REAL_DELETE = new Deleter() {
        @Override public void delete(File f) throws IOException {
            if (!FileOps.delete(f)) throw new IOException("Can't delete " + f.getName());
        }
    };

    private static final int BUFFER = 256 * 1024;
    private static final long TRANSFER_MIN = 1_000_000;

    private final File root;
    private final boolean writable;
    private final Deleter deleter;
    private final FileServer owner;
    private final String prefix;
    private final String canonicalRoot;

    WebDav(FileServer owner, String prefix, File root, boolean writable, Deleter deleter) throws IOException {
        this.owner = owner;
        this.prefix = prefix;
        this.root = root;
        this.canonicalRoot = root.getCanonicalPath();
        this.writable = writable;
        this.deleter = deleter == null ? REAL_DELETE : deleter;
    }

    // ------------------------------------------------------------------ entry point

    /** Handles one request. {@code rawPath} is the request path as sent (still percent-encoded, without the query). */
    void handle(String method, String rawPath, Map<String, String> headers, InputStream in, OutputStream out, String ip) throws IOException {
        String rel = decode(rawPath.substring(prefix.length()));
        File f = resolve(rel);
        if (f == null) { status(out, 403, "Forbidden"); return; }
        boolean isDir = f.isDirectory();
        String href = hrefOf(rel, isDir);

        if (!method.equals("OPTIONS") && !method.equals("GET") && !method.equals("HEAD") && !method.equals("PROPFIND")
                && !writable) {
            status(out, 403, "Forbidden");
            return;
        }
        switch (method) {
            case "OPTIONS": options(out); break;
            case "PROPFIND": propfind(f, rel, headers, in, out); break;
            case "PROPPATCH": proppatch(f, href, headers, in, out); break;
            case "GET": get(f, headers, out, ip, false); break;
            case "HEAD": get(f, headers, out, ip, true); break;
            case "PUT": put(f, headers, in, out, ip); break;
            case "MKCOL": mkcol(f, headers, in, out); break;
            case "DELETE": delete(f, out); break;
            case "MOVE": copyOrMove(f, rel, headers, out, true); break;
            case "COPY": copyOrMove(f, rel, headers, out, false); break;
            case "LOCK": lock(f, href, headers, in, out); break;
            case "UNLOCK": status(out, 204, "No Content"); break;
            default:
                respond(out, 405, "Method Not Allowed", "text/plain", "Not supported", "Allow: " + allowed() + "\r\n");
        }
    }

    // ------------------------------------------------------------------ paths

    private static String decode(String p) {
        try {
            return URLDecoder.decode(p.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return p;
        }
    }

    private static String encodePath(String rel) {
        StringBuilder sb = new StringBuilder();
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) continue;
            try {
                sb.append('/').append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append('/').append(seg);
            }
        }
        return sb.toString();
    }

    private String hrefOf(String rel, boolean dir) {
        String p = prefix + encodePath(rel);
        return dir && !p.endsWith("/") ? p + "/" : p;
    }

    /** The file for a decoded path, or null when it would leave the shared folder. */
    private File resolve(String rel) throws IOException {
        String clean = Remote.Paths.clean(rel);
        File f = clean.equals("/") ? root : new File(root, clean.substring(1));
        String canon = f.getCanonicalPath();
        if (!canon.equals(canonicalRoot) && !canon.startsWith(canonicalRoot + File.separator)) return null;
        return f;
    }

    private static String allowed() {
        return "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY, PROPFIND, PROPPATCH, LOCK, UNLOCK";
    }

    // ------------------------------------------------------------------ responses

    private static void status(OutputStream out, int code, String text) throws IOException {
        respond(out, code, text, "text/plain", "", "");
    }

    private static void respond(OutputStream out, int code, String text, String type, String body, String extra) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " " + text + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n" + extra + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private void options(OutputStream out) throws IOException {
        respond(out, 200, "OK", "text/plain", "", "DAV: " + (writable ? "1, 2" : "1") + "\r\nMS-Author-Via: DAV\r\n"
                + "Allow: " + (writable ? allowed() : "OPTIONS, GET, HEAD, PROPFIND") + "\r\n");
    }

    // ------------------------------------------------------------------ PROPFIND

    private static String httpDate(long t) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(t));
    }

    private static String isoDate(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(t));
    }

    private static String xml(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    private void entry(StringBuilder sb, File f, String rel) {
        boolean dir = f.isDirectory();
        long modified = f.lastModified();
        String name = f.equals(root) ? "" : f.getName();
        sb.append("<D:response><D:href>").append(xml(hrefOf(rel, dir))).append("</D:href><D:propstat><D:prop>");
        sb.append("<D:displayname>").append(xml(name)).append("</D:displayname>");
        sb.append("<D:resourcetype>").append(dir ? "<D:collection/>" : "").append("</D:resourcetype>");
        sb.append("<D:creationdate>").append(isoDate(modified)).append("</D:creationdate>");
        sb.append("<D:getlastmodified>").append(httpDate(modified)).append("</D:getlastmodified>");
        if (!dir) {
            sb.append("<D:getcontentlength>").append(f.length()).append("</D:getcontentlength>");
            sb.append("<D:getcontenttype>").append(xml(FileOps.mimeOfName(f.getName()))).append("</D:getcontenttype>");
            sb.append("<D:getetag>\"").append(Long.toHexString(modified)).append('-').append(Long.toHexString(f.length())).append("\"</D:getetag>");
        } else {
            sb.append("<D:quota-available-bytes>").append(root.getFreeSpace()).append("</D:quota-available-bytes>");
            sb.append("<D:quota-used-bytes>0</D:quota-used-bytes>");
        }
        if (writable) {
            sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>");
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private void propfind(File f, String rel, Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        drain(in, headers);
        if (!f.exists()) { status(out, 404, "Not Found"); return; }
        String depth = headers.get("depth");
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
        entry(sb, f, rel);
        if (f.isDirectory() && !"0".equals(depth)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                Arrays.sort(kids);
                for (File k : kids) entry(sb, k, Remote.Paths.join(rel, k.getName()));
            }
        }
        sb.append("</D:multistatus>");
        respond(out, 207, "Multi-Status", "application/xml; charset=utf-8", sb.toString(), "");
    }

    private void proppatch(File f, String href, Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        drain(in, headers);
        if (!f.exists()) { status(out, 404, "Not Found"); return; }
        // clients set timestamps and Windows attributes; we say yes without storing them
        respond(out, 207, "Multi-Status", "application/xml; charset=utf-8",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>" + xml(href)
                        + "</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>", "");
    }

    // ------------------------------------------------------------------ GET / HEAD

    private void get(File f, Map<String, String> headers, OutputStream out, String ip, boolean head) throws IOException {
        if (!f.exists()) { status(out, 404, "Not Found"); return; }
        if (f.isDirectory()) {
            StringBuilder sb = new StringBuilder("<!doctype html><meta charset=utf-8><title>FileBandit</title><h2>").append(xml(f.equals(root) ? "Shared folder" : f.getName())).append("</h2><ul>");
            File[] kids = f.listFiles();
            if (kids != null) {
                Arrays.sort(kids);
                for (File k : kids) {
                    String rel = k.getPath().substring(root.getPath().length()).replace(File.separatorChar, '/');
                    sb.append("<li><a href=\"").append(xml(hrefOf(rel, k.isDirectory()))).append("\">").append(xml(k.getName())).append(k.isDirectory() ? "/" : "").append("</a></li>");
                }
            }
            respond(out, 200, "OK", "text/html; charset=utf-8", head ? "" : sb.append("</ul>").toString(), "");
            return;
        }
        long size = f.length();
        long[] r = FileServer.parseRange(headers.get("range"), size);
        if (r == null) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return;
        }
        boolean partial = r[0] >= 0;
        long from = partial ? r[0] : 0;
        long to = partial ? r[1] : size - 1;
        long length = size == 0 ? 0 : to - from + 1;
        StringBuilder h = new StringBuilder(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(FileOps.mimeOfName(f.getName())).append("\r\nContent-Length: ").append(length).append("\r\n");
        h.append("Accept-Ranges: bytes\r\nLast-Modified: ").append(httpDate(f.lastModified())).append("\r\n");
        h.append("ETag: \"").append(Long.toHexString(f.lastModified())).append('-').append(Long.toHexString(size)).append("\"\r\n");
        if (partial) h.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(size).append("\r\n");
        h.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (head || length == 0) { out.flush(); return; }

        FileServer.Transfer t = length >= TRANSFER_MIN ? owner.newTransfer(false, f.getName(), ip, length) : null;
        long t0 = System.nanoTime();
        InputStream src = new FileInputStream(f);
        try {
            long skip = from;
            while (skip > 0) {
                long k = src.skip(skip);
                if (k <= 0) throw new EOFException();
                skip -= k;
            }
            byte[] buf = new byte[BUFFER];
            long remaining = length;
            while (remaining > 0) {
                int n = src.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("File ended early");
                out.write(buf, 0, n);
                remaining -= n;
                if (t != null) owner.progress(t, n, t0);
            }
            out.flush();
            if (t != null) owner.finish(t, t0, true, "Sent");
        } catch (IOException e) {
            if (t != null) owner.finish(t, t0, false, "Interrupted");
            throw e;
        } finally {
            src.close();
        }
    }

    // ------------------------------------------------------------------ PUT

    /** Decodes "Transfer-Encoding: chunked" request bodies, which Windows and macOS use for uploads. */
    private static final class ChunkedInput extends InputStream {
        private final InputStream in;
        private long left;
        private boolean done;

        ChunkedInput(InputStream in) {
            this.in = in;
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') return sb.toString().trim();
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 1024) throw new IOException("Bad chunk header");
            }
            throw new EOFException();
        }

        private boolean next() throws IOException {
            if (done) return false;
            if (left == 0) {
                String l = line();
                int semi = l.indexOf(';');
                if (semi >= 0) l = l.substring(0, semi);
                try {
                    left = Long.parseLong(l.trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Bad chunk size");
                }
                if (left == 0) {
                    while (!line().isEmpty()) { /* trailers */ }
                    done = true;
                    return false;
                }
            }
            return true;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (!next()) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException();
            left -= n;
            if (left == 0) line();          // the CRLF after the chunk
            return n;
        }
    }

    private void put(File f, Map<String, String> headers, InputStream in, OutputStream out, String ip) throws IOException {
        if (f.isDirectory()) { status(out, 405, "Method Not Allowed"); return; }
        File parent = f.getParentFile();
        if (f.equals(root) || parent == null || !parent.isDirectory()) { status(out, 409, "Conflict"); return; }
        boolean chunked = headers.get("transfer-encoding") != null && headers.get("transfer-encoding").toLowerCase(Locale.ROOT).contains("chunked");
        long length = -1;
        if (!chunked) {
            String lh = headers.get("content-length");
            if (lh == null) lh = "0";
            try {
                length = Long.parseLong(lh);
            } catch (NumberFormatException e) {
                status(out, 400, "Bad Request");
                return;
            }
        }
        String expect = headers.get("expect");
        if (expect != null && expect.toLowerCase(Locale.ROOT).contains("100-continue")) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        boolean existed = f.exists();
        File tmp = new File(parent, ".fbtmp-" + UUID.randomUUID().toString().substring(0, 8));
        InputStream body = chunked ? new ChunkedInput(in) : in;
        FileServer.Transfer t = (length < 0 || length >= TRANSFER_MIN) ? owner.newTransfer(true, f.getName(), ip, length) : null;
        long t0 = System.nanoTime();
        boolean ok = false;
        try {
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[BUFFER];
                long remaining = length;
                while (chunked || remaining > 0) {
                    int want = chunked ? buf.length : (int) Math.min(buf.length, remaining);
                    int n = body.read(buf, 0, want);
                    if (n < 0) {
                        if (chunked) break;
                        throw new EOFException("Connection closed early");
                    }
                    os.write(buf, 0, n);
                    if (!chunked) remaining -= n;
                    if (t != null) owner.progress(t, n, t0);
                }
            } finally {
                os.close();
            }
            if (existed && !f.delete()) throw new IOException("Can't replace " + f.getName());
            if (!tmp.renameTo(f)) throw new IOException("Can't save " + f.getName());
            ok = true;
        } catch (IOException e) {
            if (t != null) owner.finish(t, t0, false, "Interrupted");
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("space")) {
                status(out, 507, "Insufficient Storage");
                return;
            }
            throw e;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
        if (t != null) owner.finish(t, t0, true, "Saved on this phone");
        status(out, existed ? 204 : 201, existed ? "No Content" : "Created");
    }

    // ------------------------------------------------------------------ MKCOL, DELETE, MOVE, COPY

    private void mkcol(File f, Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        String te = headers.get("transfer-encoding");
        boolean hasBody = (te != null && te.toLowerCase(Locale.ROOT).contains("chunked"))
                || (headers.get("content-length") != null && !headers.get("content-length").trim().equals("0"));
        drain(in, headers);
        if (hasBody) { status(out, 415, "Unsupported Media Type"); return; }   // RFC 4918 9.3.1: we know no body formats
        if (f.exists()) { status(out, 405, "Method Not Allowed"); return; }
        File parent = f.getParentFile();
        if (parent == null || !parent.isDirectory()) { status(out, 409, "Conflict"); return; }
        boolean made = f.mkdir();
        status(out, made ? 201 : 507, made ? "Created" : "Insufficient Storage");
    }

    private void delete(File f, OutputStream out) throws IOException {
        if (f.equals(root)) { status(out, 403, "Forbidden"); return; }
        if (!f.exists()) { status(out, 404, "Not Found"); return; }
        try {
            deleter.delete(f);
        } catch (IOException e) {
            status(out, 403, "Forbidden");
            return;
        }
        status(out, 204, "No Content");
    }

    private void copyOrMove(File src, String rel, Map<String, String> headers, OutputStream out, boolean move) throws IOException {
        if (!src.exists()) { status(out, 404, "Not Found"); return; }
        String dest = headers.get("destination");
        if (dest == null) { status(out, 400, "Bad Request"); return; }
        int scheme = dest.indexOf("://");
        if (scheme >= 0) {
            int slash = dest.indexOf('/', scheme + 3);
            dest = slash < 0 ? "/" : dest.substring(slash);
        }
        int q = dest.indexOf('?');
        if (q >= 0) dest = dest.substring(0, q);
        if (!(dest.equals(prefix) || dest.startsWith(prefix + "/"))) { status(out, 502, "Bad Gateway"); return; }
        File target = resolve(decode(dest.substring(prefix.length())));
        if (target == null || target.equals(root)) { status(out, 403, "Forbidden"); return; }
        if (src.equals(root)) { status(out, 403, "Forbidden"); return; }
        if (target.getCanonicalPath().equals(src.getCanonicalPath())) { status(out, 403, "Forbidden"); return; }
        if (src.isDirectory() && target.getCanonicalPath().startsWith(src.getCanonicalPath() + File.separator)) {
            status(out, 403, "Forbidden");               // into itself
            return;
        }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) { status(out, 409, "Conflict"); return; }
        boolean existed = target.exists();
        String ow = headers.get("overwrite");
        if (existed && ow != null && ow.trim().equalsIgnoreCase("F")) { status(out, 412, "Precondition Failed"); return; }
        if (existed) deleter.delete(target);
        try {
            if (move) {
                if (!src.renameTo(target)) {
                    FileOps.copyExact(src, target);
                    deleter.delete(src);
                }
            } else if (src.isDirectory() && "0".equals(headers.get("depth") == null ? "" : headers.get("depth").trim())) {
                if (!target.mkdir()) throw new IOException("Can't create " + target.getName());     // Depth: 0 copies the folder, not what's in it
            } else {
                FileOps.copyExact(src, target);
            }
        } catch (IOException e) {
            status(out, 507, "Insufficient Storage");
            return;
        }
        status(out, existed ? 204 : 201, existed ? "No Content" : "Created");
    }

    // ------------------------------------------------------------------ LOCK

    private void lock(File f, String href, Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        drain(in, headers);
        boolean created = false;
        if (!f.exists()) {
            File parent = f.getParentFile();
            if (parent == null || !parent.isDirectory()) { status(out, 409, "Conflict"); return; }
            if (!f.createNewFile()) { status(out, 403, "Forbidden"); return; }
            created = true;
        }
        String token = "opaquelocktoken:" + UUID.nameUUIDFromBytes(href.getBytes(StandardCharsets.UTF_8));
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
                + "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>infinity</D:depth>"
                + "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
                + "<D:lockroot><D:href>" + xml(href) + "</D:href></D:lockroot></D:activelock></D:lockdiscovery></D:prop>";
        respond(out, created ? 201 : 200, created ? "Created" : "OK", "application/xml; charset=utf-8", body, "Lock-Token: <" + token + ">\r\n");
    }

    // ------------------------------------------------------------------ helpers

    /** Reads and discards a request body we don't need, so the connection stays tidy. */
    private static void drain(InputStream in, Map<String, String> headers) throws IOException {
        boolean chunked = headers.get("transfer-encoding") != null && headers.get("transfer-encoding").toLowerCase(Locale.ROOT).contains("chunked");
        if (chunked) {
            InputStream c = new ChunkedInput(in);
            byte[] b = new byte[4096];
            while (c.read(b) >= 0) { /* discard */ }
            return;
        }
        long len = 0;
        try {
            len = Long.parseLong(headers.get("content-length") == null ? "0" : headers.get("content-length"));
        } catch (NumberFormatException ignored) { }
        byte[] b = new byte[4096];
        while (len > 0) {
            int n = in.read(b, 0, (int) Math.min(b.length, len));
            if (n < 0) return;
            len -= n;
        }
    }
}
