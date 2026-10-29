package com.fastshare.app;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;

/** WebDAV: what Nextcloud, ownCloud, many NAS boxes and "Web folders" speak. */
final class DavRemote implements Remote {
    private final OkHttpClient http;
    private final HttpUrl root;
    private final String auth;

    DavRemote(Connection c) throws IOException {
        boolean tls = c.type == Connection.Type.WEBDAVS;
        HttpUrl.Builder b = new HttpUrl.Builder().scheme(tls ? "https" : "http").host(c.host).port(c.portOrDefault());
        String base = Paths.clean(c.path);
        if (!base.equals("/")) {
            for (String seg : base.substring(1).split("/")) b.addPathSegment(seg);
        }
        root = b.build();
        auth = c.user.isEmpty() ? null : Credentials.basic(c.user, c.password, java.nio.charset.StandardCharsets.UTF_8);
        OkHttpClient.Builder hb = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true);
        if (tls && c.trustAll) {
            try {
                final X509TrustManager all = new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{all}, new SecureRandom());
                hb.sslSocketFactory(ctx.getSocketFactory(), all);
                hb.hostnameVerifier(new HostnameVerifier() {
                    @Override public boolean verify(String host, SSLSession s) { return true; }
                });
            } catch (Exception e) {
                throw new IOException("Can't set up the secure connection", e);
            }
        }
        http = hb.build();
        // prove the address, the login and the folder all work before saying "connected"
        list("/");
    }

    private HttpUrl url(String path) {
        HttpUrl.Builder b = root.newBuilder();
        String p = Paths.clean(path);
        if (!p.equals("/")) for (String seg : p.substring(1).split("/")) b.addPathSegment(seg);
        return b.build();
    }

    private Request.Builder req(HttpUrl u) {
        Request.Builder b = new Request.Builder().url(u).header("User-Agent", "FileBandit");
        if (auth != null) b.header("Authorization", auth);
        return b;
    }

    private static IOException status(Response r, String doing) {
        int c = r.code();
        if (c == 401) return new IOException("Wrong user name or password.");
        if (c == 403) return new IOException("The server won't allow that (403).");
        if (c == 404) return new IOException("That doesn't exist on the server.");
        if (c == 405) return new IOException("The server doesn't allow that here. Is this the WebDAV address? (405)");
        if (c == 409) return new IOException("The folder it belongs in doesn't exist.");
        if (c == 507) return new IOException("The server is out of space.");
        return new IOException("Couldn't " + doing + " (" + c + (r.message().isEmpty() ? "" : " " + r.message()) + ")");
    }

    @Override
    public List<Item> list(String path) throws IOException {
        String body = "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><resourcetype/><getcontentlength/><getlastmodified/></prop></propfind>";
        HttpUrl self = url(path);
        Request rq = req(self).header("Depth", "1")
                .method("PROPFIND", RequestBody.create(body, MediaType.parse("application/xml; charset=utf-8"))).build();
        try (Response r = http.newCall(rq).execute()) {
            if (r.code() != 207 && r.code() != 200) throw status(r, "open that folder");
            byte[] xml = r.body() == null ? new byte[0] : r.body().bytes();
            return parse(xml, self, path);
        } catch (IOException e) {
            throw e;
        }
    }

    private static List<Item> parse(byte[] xml, HttpUrl self, String path) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
            doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("The server's answer wasn't WebDAV. Check the address.", e);
        }
        List<Item> out = new ArrayList<>();
        String selfPath = decodePath(self.encodedPath());
        NodeList responses = doc.getElementsByTagNameNS("*", "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            String href = text(resp, "href");
            if (href == null) continue;
            String p = href;
            int scheme = p.indexOf("://");
            if (scheme >= 0) {
                int slash = p.indexOf('/', scheme + 3);
                p = slash < 0 ? "/" : p.substring(slash);
            }
            p = decodePath(p);
            boolean dir = false;
            NodeList types = resp.getElementsByTagNameNS("*", "resourcetype");
            if (types.getLength() > 0) dir = ((Element) types.item(0)).getElementsByTagNameNS("*", "collection").getLength() > 0;
            String trimmed = p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
            String selfTrim = selfPath.endsWith("/") && selfPath.length() > 1 ? selfPath.substring(0, selfPath.length() - 1) : selfPath;
            if (trimmed.equals(selfTrim)) continue;                    // the folder itself
            String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            if (name.isEmpty()) continue;
            long size = -1;
            String len = text(resp, "getcontentlength");
            if (len != null) {
                try { size = Long.parseLong(len.trim()); } catch (NumberFormatException ignored) { }
            }
            out.add(new Item(name, Paths.join(path, name), dir, dir ? -1 : size, parseDate(text(resp, "getlastmodified"))));
        }
        Collections.sort(out, ORDER);
        return out;
    }

    private static String decodePath(String p) {
        try {
            return URLDecoder.decode(p.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return p;
        }
    }

    private static String text(Element e, String local) {
        NodeList n = e.getElementsByTagNameNS("*", local);
        if (n.getLength() == 0) return null;
        Node first = n.item(0);
        String t = first.getTextContent();
        return t == null ? null : t.trim();
    }

    private static long parseDate(String s) {
        if (s == null || s.isEmpty()) return 0;
        for (String fmt : new String[]{"EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("GMT"));
                return f.parse(s).getTime();
            } catch (ParseException ignored) { }
        }
        return 0;
    }

    @Override
    public InputStream open(String path, long offset) throws IOException {
        Request.Builder b = req(url(path)).get();
        if (offset > 0) b.header("Range", "bytes=" + offset + "-");
        Response r = http.newCall(b.build()).execute();
        if (!r.isSuccessful() || r.body() == null) {
            IOException e = status(r, "read that file");
            r.close();
            throw e;
        }
        InputStream in = r.body().byteStream();
        if (offset > 0 && r.code() == 200) {          // the server ignored the range: skip ahead ourselves
            long left = offset;
            while (left > 0) {
                long k = in.skip(left);
                if (k <= 0) {
                    if (in.read() < 0) break;
                    k = 1;
                }
                left -= k;
            }
        }
        return in;
    }

    @Override
    public void put(String path, final InputStream in, final long size) throws IOException {
        RequestBody body = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.parse("application/octet-stream"); }
            @Override public long contentLength() { return size; }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                Source src = Okio.source(in);
                sink.writeAll(src);
            }
        };
        try (Response r = http.newCall(req(url(path)).put(body).build()).execute()) {
            if (!r.isSuccessful()) throw status(r, "upload the file");
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        try (Response r = http.newCall(req(url(path)).method("MKCOL", null).build()).execute()) {
            if (r.code() == 405) throw new IOException("Something with that name is already there.");
            if (!r.isSuccessful()) throw status(r, "create the folder");
        }
    }

    @Override
    public void delete(String path, boolean dir) throws IOException {
        try (Response r = http.newCall(req(url(path)).delete().build()).execute()) {
            if (!r.isSuccessful()) throw status(r, "delete it");
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        Request rq = req(url(from)).method("MOVE", null)
                .header("Destination", url(to).toString())
                .header("Overwrite", "F").build();
        try (Response r = http.newCall(rq).execute()) {
            if (r.code() == 412) throw new IOException("Something with that name is already there.");
            if (!r.isSuccessful()) throw status(r, "rename it");
        }
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
