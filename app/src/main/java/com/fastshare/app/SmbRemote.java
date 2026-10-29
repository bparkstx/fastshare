package com.fastshare.app;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Windows shares and NAS boxes (SMB 2 and 3). */
final class SmbRemote implements Remote {
    private final SMBClient client;
    private final com.hierynomus.smbj.connection.Connection conn;
    private final DiskShare share;
    private final int readMax;
    private final int writeMax;
    private final String base;       // folder inside the share, "" for the share's root

    SmbRemote(Connection c) throws IOException {
        String raw = c.path.replace('\\', '/').replaceAll("^/+", "");
        int slash = raw.indexOf('/');
        String shareName = slash < 0 ? raw : raw.substring(0, slash);
        base = slash < 0 ? "" : Paths.clean(raw.substring(slash)).substring(1);
        SmbConfig cfg = SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build();
        client = new SMBClient(cfg);
        try {
            conn = client.connect(c.host, c.portOrDefault());
            String user = c.user;
            String domain = "";
            int bs = user.indexOf('\\');
            int at = user.indexOf('@');
            if (bs > 0) {
                domain = user.substring(0, bs);
                user = user.substring(bs + 1);
            } else if (at > 0) {
                domain = user.substring(at + 1);
                user = user.substring(0, at);
            }
            readMax = (int) Math.max(4096, Math.min(conn.getNegotiatedProtocol().getMaxReadSize(), 1 << 20));
            writeMax = (int) Math.max(4096, Math.min(conn.getNegotiatedProtocol().getMaxWriteSize(), 1 << 20));
            AuthenticationContext ac = user.isEmpty() ? AuthenticationContext.anonymous()
                    : new AuthenticationContext(user, c.password.toCharArray(), domain);
            Session s = conn.authenticate(ac);
            try {
                share = (DiskShare) s.connectShare(shareName);
            } catch (SMBApiException noShare) {
                long st = noShare.getStatusCode();
                if (st == NtStatus.STATUS_BAD_NETWORK_NAME.getValue() || st == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue()
                        || st == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.getValue())
                    throw new IOException("That server has no shared folder named “" + shareName + "”.", noShare);
                throw noShare;
            }
        } catch (IOException e) {
            try { client.close(); } catch (Exception ignored) { }
            throw e;
        } catch (RuntimeException e) {
            try { client.close(); } catch (Exception ignored) { }
            throw explain(e);
        }
    }

    private static IOException explain(RuntimeException e) {
        if (e instanceof SMBApiException) {
            long st = ((SMBApiException) e).getStatusCode();
            if (st == NtStatus.STATUS_LOGON_FAILURE.getValue() || st == NtStatus.STATUS_ACCESS_DENIED.getValue())
                return new IOException("Wrong user name or password, or this account can't use that share.", e);
            if (st == NtStatus.STATUS_BAD_NETWORK_NAME.getValue())
                return new IOException("That server has no shared folder with this name.", e);
            if (st == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue() || st == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.getValue())
                return new IOException("That doesn't exist on the server.", e);
            if (st == NtStatus.STATUS_OBJECT_NAME_COLLISION.getValue())
                return new IOException("Something with that name is already there.", e);
            if (st == NtStatus.STATUS_SHARING_VIOLATION.getValue())
                return new IOException("The file is in use on the server.", e);
            return new IOException(e.getMessage() == null ? "The server reported an error." : e.getMessage(), e);
        }
        return new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }

    private String abs(String path) {
        String p = Paths.clean(path).substring(1);
        String joined = base.isEmpty() ? p : p.isEmpty() ? base : base + "/" + p;
        return joined.replace('/', '\\');
    }

    @Override
    public synchronized List<Item> list(String path) throws IOException {
        try {
            List<Item> out = new ArrayList<>();
            for (FileIdBothDirectoryInformation f : share.list(abs(path))) {
                String n = f.getFileName();
                if (n.equals(".") || n.equals("..")) continue;
                boolean dir = (f.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                boolean hidden = false;
                long modified = f.getLastWriteTime() == null ? 0 : f.getLastWriteTime().toEpochMillis();
                if (hidden) continue;
                out.add(new Item(n, Paths.join(path, n), dir, dir ? -1 : f.getEndOfFile(), modified));
            }
            Collections.sort(out, ORDER);
            return out;
        } catch (RuntimeException e) {
            throw explain(e);
        }
    }

    @Override
    public synchronized InputStream open(String path, final long offset) throws IOException {
        final com.hierynomus.smbj.share.File file;
        try {
            file = share.openFile(abs(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, null);
        } catch (RuntimeException e) {
            throw explain(e);
        }
        final long length;
        try {
            length = file.getFileInformation().getStandardInformation().getEndOfFile();
        } catch (RuntimeException e) {
            try { file.close(); } catch (Exception ignored) { }
            throw explain(e);
        }
        return new InputStream() {
            private long pos = Math.max(0, offset);
            private boolean closed;

            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n <= 0 ? -1 : one[0] & 0xFF;
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (pos >= length) return -1;                  // never ask past the end: not every server answers that politely
                try {
                    int n = file.read(b, pos, off, (int) Math.min(Math.min(len, readMax), length - pos));
                    if (n > 0) pos += n;
                    return n <= 0 ? -1 : n;
                } catch (RuntimeException e) {
                    throw explain(e);
                }
            }

            @Override public void close() {
                if (closed) return;
                closed = true;
                try { file.close(); } catch (Exception ignored) { }
            }
        };
    }

    @Override
    public synchronized void put(String path, InputStream in, long size) throws IOException {
        com.hierynomus.smbj.share.File file = null;
        try {
            file = share.openFile(abs(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
            byte[] buf = new byte[Math.min(256 * 1024, writeMax)];     // no bigger than the server accepts
            long pos = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                file.write(buf, pos, 0, n);
                pos += n;
            }
            file.flush();
        } catch (RuntimeException e) {
            throw explain(e);
        } finally {
            if (file != null) {
                try { file.close(); } catch (Exception ignored) { }
            }
        }
    }

    @Override
    public synchronized void mkdir(String path) throws IOException {
        try {
            share.mkdir(abs(path));
        } catch (RuntimeException e) {
            throw explain(e);
        }
    }

    @Override
    public synchronized void delete(String path, boolean dir) throws IOException {
        try {
            if (dir) share.rmdir(abs(path), true);
            else share.rm(abs(path));
        } catch (RuntimeException e) {
            throw explain(e);
        }
    }

    @Override
    public synchronized void rename(String from, String to) throws IOException {
        DiskEntry e = null;
        try {
            e = share.open(abs(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, null);
            e.rename(abs(to));
        } catch (RuntimeException ex) {
            throw explain(ex);
        } finally {
            if (e != null) {
                try { e.close(); } catch (Exception ignored) { }
            }
        }
    }

    @Override
    public synchronized void close() {
        try { share.close(); } catch (Exception ignored) { }
        try { client.close(); } catch (Exception ignored) { }
    }
}
