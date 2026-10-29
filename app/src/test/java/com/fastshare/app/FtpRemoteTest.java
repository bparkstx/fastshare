package com.fastshare.app;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FtpRemoteTest extends RemoteContractTest {
    private FtpServer server;
    private org.apache.ftpserver.listener.Listener listener;
    private int port;

    @Override protected Remote connect(File root) throws Exception {
        FtpServerFactory f = new FtpServerFactory();
        ListenerFactory lf = new ListenerFactory();
        lf.setPort(0);
        listener = lf.createListener();
        f.addListener("default", listener);
        BaseUser u = new BaseUser();
        u.setName("bob");
        u.setPassword("pw");
        u.setHomeDirectory(root.getPath());
        List<Authority> auth = new ArrayList<>();
        auth.add(new WritePermission());
        u.setAuthorities(auth);
        f.getUserManager().save(u);
        server = f.createServer();
        server.start();
        port = listener.getPort();
        return new FtpRemote(conn("bob", "pw"));
    }

    private Connection conn(String user, String pw) {
        Connection c = new Connection();
        c.type = Connection.Type.FTP;
        c.host = "127.0.0.1";
        c.port = port;
        c.user = user;
        c.password = pw;
        return c;
    }

    @Override protected void stopServer() {
        if (server != null) server.stop();
    }

    @Test public void wrongPasswordIsRefusedWithAClearMessage() throws Exception {
        try {
            Remotes.open(conn("bob", "wrong"));
            fail("should not log in");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Wrong user name or password"));
        }
    }

    @Test public void unreachableServerExplainsItself() throws Exception {
        Connection c = conn("bob", "pw");
        c.port = 1;                         // nothing listens here
        try {
            Remotes.open(c);
            fail("should not connect");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Can't reach 127.0.0.1 on port 1"));
        }
    }

    @Test public void connectionIsReestablishedAfterTheServerDropsIt() throws Exception {
        remote.list("/");
        // the server kills every session (idle timeout, Wi-Fi roam...)
        for (Object session : listener.getActiveSessions()) ((org.apache.ftpserver.impl.FtpIoSession) session).closeNow();
        Thread.sleep(300);
        assertTrue(remote.list("/").isEmpty());
    }
}
