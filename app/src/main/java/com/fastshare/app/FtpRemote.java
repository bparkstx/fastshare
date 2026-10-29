package com.fastshare.app;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.TrustManagerUtils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** FTP and FTP over TLS. An FTP session does one thing at a time, so every call waits its turn. */
final class FtpRemote implements Remote {
    private final Connection c;
    private final String base;
    private FTPClient ftp;

    FtpRemote(Connection c) throws IOException {
        this.c = c;
        this.base = Paths.clean(c.path);
        connect();
    }

    private void connect() throws IOException {
        FTPClient f;
        if (c.type == Connection.Type.FTPS) {
            FTPSClient s = new FTPSClient("TLS", false);        // explicit TLS: connects plain, then upgrades
            if (c.trustAll) s.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager());
            f = s;
        } else {
            f = new FTPClient();
        }
        f.setConnectTimeout(15_000);
        f.setDefaultTimeout(30_000);
        f.setControlEncoding("UTF-8");
        f.setAutodetectUTF8(true);
        f.setBufferSize(256 * 1024);
        try {
            f.connect(c.host, c.portOrDefault());
            if (!FTPReply.isPositiveCompletion(f.getReplyCode())) throw new IOException("The server refused the connection: " + f.getReplyString().trim());
            f.setSoTimeout(30_000);
            if (f instanceof FTPSClient) {
                ((FTPSClient) f).execPBSZ(0);
                ((FTPSClient) f).execPROT("P");
            }
            String user = c.user.isEmpty() ? "anonymous" : c.user;
            String pass = c.user.isEmpty() && c.password.isEmpty() ? "filebandit@" : c.password;
            if (!f.login(user, pass)) throw new IOException("Wrong user name or password.");
            f.enterLocalPassiveMode();
            f.setFileType(FTP.BINARY_FILE_TYPE);
        } catch (IOException e) {
            try { f.disconnect(); } catch (IOException ignored) { }
            throw e;
        }
        ftp = f;
    }

    private synchronized FTPClient cx() throws IOException {
        boolean alive = false;
        if (ftp != null && ftp.isConnected()) {
            try {
                alive = ftp.sendNoOp();
            } catch (IOException ignored) { }
        }
        if (!alive) {
            if (ftp != null) {
                try { ftp.disconnect(); } catch (IOException ignored) { }
            }
            connect();
        }
        return ftp;
    }

    private String abs(String path) {
        String p = Paths.clean(path);
        if (base.equals("/")) return p;
        return p.equals("/") ? base : base + p;
    }

    @Override
    public synchronized List<Item> list(String path) throws IOException {
        FTPClient f = cx();
        String dir = abs(path);
        FTPFile[] files = null;
        try {
            files = f.mlistDir(dir);
        } catch (IOException ignored) { }
        if (files == null || !FTPReply.isPositiveCompletion(f.getReplyCode())) files = f.listFiles(dir);   // no MLSD: use plain LIST
        if (files == null || files.length == 0) {
            // an empty answer is either an empty folder or one that doesn't exist: only the second is an error
            if (!f.changeWorkingDirectory(dir)) throw new IOException("Can't open that folder: " + f.getReplyString().trim());
        }
        List<Item> out = new ArrayList<>();
        if (files == null) return out;
        for (FTPFile e : files) {
            if (e == null) continue;
            String n = e.getName();
            if (n == null) continue;
            int slash = n.lastIndexOf('/');
            if (slash >= 0) n = n.substring(slash + 1);        // some servers repeat the folder in the name
            if (n.isEmpty() || n.equals(".") || n.equals("..")) continue;
            String full = Paths.join(path, n);
            boolean isDir = e.isDirectory();
            if (e.isSymbolicLink()) isDir = f.changeWorkingDirectory(abs(full));
            Calendar t = e.getTimestamp();
            out.add(new Item(n, full, isDir, isDir ? -1 : e.getSize(), t == null ? 0 : t.getTimeInMillis()));
        }
        Collections.sort(out, ORDER);
        return out;
    }

    @Override
    public synchronized InputStream open(String path, long offset) throws IOException {
        final FTPClient f = cx();
        f.setRestartOffset(Math.max(0, offset));
        InputStream in = f.retrieveFileStream(abs(path));
        if (in == null) throw new IOException("Can't read that file: " + f.getReplyString().trim());
        return new FilterInputStream(in) {
            private boolean done;
            @Override public void close() throws IOException {
                if (done) return;
                done = true;
                super.close();
                synchronized (FtpRemote.this) {
                    f.completePendingCommand();
                }
            }
        };
    }

    @Override
    public synchronized void put(String path, InputStream in, long size) throws IOException {
        FTPClient f = cx();
        if (!f.storeFile(abs(path), in)) throw new IOException("The server wouldn't accept the file: " + f.getReplyString().trim());
    }

    @Override
    public synchronized void mkdir(String path) throws IOException {
        FTPClient f = cx();
        if (!f.makeDirectory(abs(path))) throw new IOException("Can't create the folder: " + f.getReplyString().trim());
    }

    @Override
    public synchronized void delete(String path, boolean dir) throws IOException {
        FTPClient f = cx();
        if (!dir) {
            if (!f.deleteFile(abs(path))) throw new IOException("Can't delete it: " + f.getReplyString().trim());
            return;
        }
        for (Item child : list(path)) delete(child.path, child.dir);
        if (!f.removeDirectory(abs(path))) throw new IOException("Can't delete the folder: " + f.getReplyString().trim());
    }

    @Override
    public synchronized void rename(String from, String to) throws IOException {
        FTPClient f = cx();
        if (!f.rename(abs(from), abs(to))) throw new IOException("Can't rename it: " + f.getReplyString().trim());
    }

    @Override
    public synchronized void close() {
        if (ftp == null) return;
        try {
            if (ftp.isConnected()) {
                try { ftp.logout(); } catch (IOException ignored) { }
                ftp.disconnect();
            }
        } catch (IOException ignored) { }
        ftp = null;
    }
}
