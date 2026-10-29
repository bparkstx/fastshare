package com.fastshare.app;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Opens a saved connection with the right protocol. */
final class Remotes {
    private Remotes() { }

    /** Tests swap this in to browse a fake server. */
    interface Opener {
        Remote open(Connection c) throws IOException;
    }

    static volatile Opener testOpener;

    /**
     * Connects. For SFTP the server key seen the first time is stored back in {@code c.hostKey}; the caller should
     * save the connection then.
     */
    static Remote open(Connection c) throws IOException {
        String problem = c.problem();
        if (problem != null) throw new IOException(problem);
        Opener fake = testOpener;
        if (fake != null) return fake.open(c);
        try {
            switch (c.type) {
                case FTP:
                case FTPS:
                    return new FtpRemote(c);
                case SFTP: {
                    SftpRemote r = new SftpRemote(c);
                    if (r.learnedHostKey != null) c.hostKey = r.learnedHostKey;
                    return r;
                }
                case SMB:
                    return new SmbRemote(c);
                default:
                    return new DavRemote(c);
            }
        } catch (IOException e) {
            throw new IOException(friendly(e, c), e);
        } catch (RuntimeException e) {
            throw new IOException(friendly(e, c), e);
        }
    }

    /** Plain words for the usual ways a connection fails. */
    static String friendly(Throwable e, Connection c) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof UnknownHostException) return "Can't find “" + c.host + "”. Check the address, and that this phone is on the same network.";
            if (t instanceof ConnectException || t instanceof NoRouteToHostException)
                return "Can't reach " + c.host + " on port " + c.portOrDefault() + ". Is it switched on, on the same network, and is that the right port?";
            if (t instanceof SocketTimeoutException || (t.getMessage() != null && t.getMessage().toLowerCase().contains("timed out")))
                return "The server didn't answer in time. Check the address and your network.";
            if (t instanceof javax.net.ssl.SSLException && (c.type == Connection.Type.WEBDAVS || c.type == Connection.Type.FTPS))
                return "The secure connection failed. If this is your own server with a self-made certificate, turn on “Trust this server's certificate”.";
            t = t.getCause();
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Couldn't connect." : m;
    }
}
