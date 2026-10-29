package com.fastshare.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** The saved network locations, kept in a private file. Passwords and keys are sealed by the {@link Secret} you give it. */
public final class Connections {

    /** Protects the secret fields on disk. */
    public interface Secret {
        String seal(String plain) throws IOException;
        String open(String sealed) throws IOException;

        Secret NONE = new Secret() {
            @Override public String seal(String plain) { return plain; }
            @Override public String open(String sealed) { return sealed; }
        };
    }

    private final File file;
    private final Secret secret;
    private final List<Connection> items = new ArrayList<>();

    public Connections(File file, Secret secret) {
        this.file = file;
        this.secret = secret;
        load();
    }

    public synchronized List<Connection> list() {
        List<Connection> out = new ArrayList<>();
        for (Connection c : items) out.add(c.copy());
        return out;
    }

    public synchronized Connection get(String id) {
        for (Connection c : items) if (c.id.equals(id)) return c.copy();
        return null;
    }

    /** Adds the connection (giving it an id if it has none) or replaces the one with the same id. */
    public synchronized Connection save(Connection c) throws IOException {
        Connection keep = c.copy();
        if (keep.id.isEmpty()) keep.id = newId();
        boolean replaced = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(keep.id)) {
                items.set(i, keep);
                replaced = true;
            }
        }
        if (!replaced) items.add(keep);
        write();
        return keep.copy();
    }

    public synchronized void remove(String id) throws IOException {
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).id.equals(id)) items.remove(i);
        write();
    }

    private static String newId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ storage: one line per connection, every field in base64

    private static String enc(String s) {
        return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private void write() throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't save");
        File tmp = new File(file.getPath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
        try {
            for (Connection c : items) {
                String[] f = {c.id, c.name, c.type.name(), c.host, String.valueOf(c.port), c.user,
                        secret.seal(c.password), c.path, secret.seal(c.privateKey), c.trustAll ? "1" : "0", c.hostKey};
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < f.length; i++) {
                    if (i > 0) line.append('\t');
                    line.append(enc(f[i]));
                }
                w.write(line.append('\n').toString());
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("Can't save");
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 11) continue;
                try {
                    Connection c = new Connection();
                    c.id = dec(p[0]);
                    c.name = dec(p[1]);
                    c.type = Connection.Type.valueOf(dec(p[2]));
                    c.host = dec(p[3]);
                    c.port = Integer.parseInt(dec(p[4]));
                    c.user = dec(p[5]);
                    try {
                        c.password = secret.open(dec(p[6]));
                        c.privateKey = secret.open(dec(p[8]));
                    } catch (IOException cantOpen) {
                        c.password = "";            // the key that sealed it is gone: ask for the password again
                        c.privateKey = "";
                    }
                    c.path = dec(p[7]);
                    c.trustAll = "1".equals(dec(p[9]));
                    c.hostKey = dec(p[10]);
                    items.add(c);
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException ignored) { }
    }
}
