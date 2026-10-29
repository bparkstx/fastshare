package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Against a real SFTP server (asyncssh, run by tools/sftp_test_server.py). */
public class SftpRemoteTest extends RemoteContractTest {
    private ExternalServer server;
    private File hostKeyFile;
    private int port;

    @Override protected Remote connect(File root) throws Exception {
        Assume.assumeNotNull("asyncssh isn't installed (pip install asyncssh)", ExternalServer.python("asyncssh"));
        hostKeyFile = new File(tmp.getRoot(), "hostkey");
        startServer(root, hostKeyFile);
        return Remotes.open(conn("bob", "pw"));
    }

    private void startServer(File root, File key) throws Exception {
        port = ExternalServer.freePort();
        String python = ExternalServer.python("asyncssh");
        server = ExternalServer.start(Arrays.asList(python, "-u", new File("../tools/sftp_test_server.py").getCanonicalPath(),
                String.valueOf(port), root.getPath(), key.getPath()), port, tmp.newFile("sftp-" + System.nanoTime() + ".log"));
    }

    private Connection conn(String user, String pw) {
        Connection c = new Connection();
        c.type = Connection.Type.SFTP;
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
            Remotes.open(conn("bob", "nope"));
            fail("should not log in");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Wrong user name or password"));
        }
    }

    @Test public void theServersKeyIsRememberedAndThenEnforced() throws Exception {
        Connection c = conn("bob", "pw");
        assertEquals("", c.hostKey);
        Remote first = Remotes.open(c);
        first.close();
        assertTrue("first contact stores the fingerprint", c.hostKey.startsWith("SHA256:"));
        String seen = c.hostKey;

        Remotes.open(c).close();                       // same key: fine
        assertEquals(seen, c.hostKey);

        // a different server key on the same address looks like someone pretending to be the server
        server.stop();
        startServer(root, new File(tmp.getRoot(), "otherhostkey"));
        Connection same = conn("bob", "pw");
        same.hostKey = seen;
        try {
            Remotes.open(same);
            fail("a changed key must be refused");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("security key has changed"));
        }
    }

    @Test public void startingFolderIsHonoured() throws Exception {
        new File(root, "docs").mkdir();
        java.nio.file.Files.write(new File(root, "docs/a.txt").toPath(), "x".getBytes("UTF-8"));
        Connection c = conn("bob", "pw");
        c.path = "/docs";
        try (Remote r = Remotes.open(c)) {
            assertEquals("[a.txt]", names(r.list("/")).toString());
            assertEquals("/a.txt", r.list("/").get(0).path);
        }
    }

    @Test public void unreachableServerExplainsItself() throws Exception {
        Connection c = conn("bob", "pw");
        c.port = 1;
        try {
            Remotes.open(c);
            fail("should not connect");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Can't reach 127.0.0.1 on port 1"));
        }
    }
}
