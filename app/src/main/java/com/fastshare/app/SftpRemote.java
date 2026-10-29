package com.fastshare.app;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

/**
 * SFTP over SSH. The first time we meet a server we remember its key; if the key ever changes the connection is refused,
 * which is what protects against someone pretending to be your server.
 */
final class SftpRemote implements Remote {
    private final Connection c;
    private final String base;
    private final Session session;
    private final ChannelSftp sftp;
    /** The server's key fingerprint when it was new to us, so the caller can save it. Null when it matched. */
    String learnedHostKey;

    SftpRemote(Connection c) throws IOException {
        this.c = c;
        this.base = Paths.clean(c.path);
        try {
            JSch jsch = new JSch();
            if (!c.privateKey.trim().isEmpty()) {
                byte[] pass = c.password.isEmpty() ? null : c.password.getBytes(StandardCharsets.UTF_8);
                jsch.addIdentity("key", c.privateKey.getBytes(StandardCharsets.UTF_8), null, pass);
            }
            jsch.setHostKeyRepository(new Repo());
            Session s = jsch.getSession(c.user, c.host, c.portOrDefault());
            if (c.privateKey.trim().isEmpty()) s.setPassword(c.password);
            s.setConfig("StrictHostKeyChecking", "yes");
            s.setConfig("PreferredAuthentications", c.privateKey.trim().isEmpty() ? "password,keyboard-interactive" : "publickey,password,keyboard-interactive");
            s.setUserInfo(new UserInfo() {
                @Override public String getPassphrase() { return c.password; }
                @Override public String getPassword() { return c.password; }
                @Override public boolean promptPassword(String m) { return true; }
                @Override public boolean promptPassphrase(String m) { return true; }
                @Override public boolean promptYesNo(String m) { return false; }
                @Override public void showMessage(String m) { }
            });
            s.setServerAliveInterval(15_000);
            s.connect(20_000);
            ChannelSftp ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(20_000);
            this.session = s;
            this.sftp = ch;
        } catch (JSchException e) {
            throw new IOException(explain(e), e);
        }
    }

    private static String explain(JSchException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("Auth fail") || m.contains("Auth cancel")) return "Wrong user name or password (or key).";
        if (m.toLowerCase().contains("hostkey has been changed") || m.toLowerCase().contains("reject hostkey"))
            return "This server's security key has changed since you first connected. If you didn't expect that, "
                    + "someone may be pretending to be it. Edit the connection and clear the saved key only if you trust the change.";
        if (m.contains("invalid privatekey") || m.contains("passphrase")) return "The private key couldn't be read. Check it and its password.";
        return m.isEmpty() ? "Couldn't connect." : m;
    }

    private String abs(String path) {
        String p = Paths.clean(path);
        if (base.equals("/")) return p;
        return p.equals("/") ? base : base + p;
    }

    private static IOException io(SftpException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return new IOException("That doesn't exist on the server.", e);
        if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) return new IOException("The server won't allow that (permission denied).", e);
        return new IOException(m.isEmpty() ? "The server reported an error." : m, e);
    }

    @Override
    public synchronized List<Item> list(String path) throws IOException {
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(abs(path));
            List<Item> out = new ArrayList<>();
            for (ChannelSftp.LsEntry e : entries) {
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                SftpATTRS a = e.getAttrs();
                String full = Paths.join(path, n);
                boolean dir = a.isDir();
                long size = a.getSize();
                if (a.isLink()) {
                    try {
                        SftpATTRS target = sftp.stat(abs(full));
                        dir = target.isDir();
                        size = target.getSize();
                    } catch (SftpException broken) {
                        continue;                      // a link to nowhere
                    }
                }
                out.add(new Item(n, full, dir, dir ? -1 : size, a.getMTime() * 1000L));
            }
            Collections.sort(out, ORDER);
            return out;
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized InputStream open(String path, long offset) throws IOException {
        try {
            return sftp.get(abs(path), null, Math.max(0, offset));
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized void put(String path, InputStream in, long size) throws IOException {
        try {
            sftp.put(in, abs(path), ChannelSftp.OVERWRITE);
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized void mkdir(String path) throws IOException {
        try {
            sftp.mkdir(abs(path));
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized void delete(String path, boolean dir) throws IOException {
        try {
            if (!dir) {
                sftp.rm(abs(path));
                return;
            }
            for (Item child : list(path)) delete(child.path, child.dir);
            sftp.rmdir(abs(path));
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized void rename(String from, String to) throws IOException {
        try {
            sftp.rename(abs(from), abs(to));
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override
    public synchronized void close() {
        try { sftp.disconnect(); } catch (Exception ignored) { }
        try { session.disconnect(); } catch (Exception ignored) { }
    }

    private static String fingerprint(byte[] key) {
        try {
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(key);
        }
    }

    /** Trust on first use, then insist on the same key. */
    private final class Repo implements HostKeyRepository {
        @Override public int check(String host, byte[] key) {
            String fp = fingerprint(key);
            if (c.hostKey == null || c.hostKey.isEmpty()) {
                learnedHostKey = fp;
                return OK;
            }
            return c.hostKey.equals(fp) ? OK : CHANGED;
        }
        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "filebandit"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }
}
