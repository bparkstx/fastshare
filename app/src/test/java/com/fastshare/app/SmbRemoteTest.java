package com.fastshare.app;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Against a real SMB 2/3 server (impacket's smbserver). */
public class SmbRemoteTest extends RemoteContractTest {
    private ExternalServer server;

    @Override protected Remote connect(File root) throws Exception {
        File tool = ExternalServer.find("smbserver.py");
        File py = ExternalServer.find("python3");
        Assume.assumeNotNull("impacket isn't installed (pip install impacket)", tool);
        Assume.assumeNotNull(py);
        int port = ExternalServer.freePort();
        File venvPython = new File(tool.getParentFile(), "python3");
        String python = venvPython.canExecute() ? venvPython.getPath() : py.getPath();
        server = ExternalServer.start(Arrays.asList(python, tool.getPath(), "-smb2support", "-username", "bob", "-password", "pw",
                "-port", String.valueOf(port), "share", root.getPath()), port, tmp.newFile("smb.log"));
        return Remotes.open(conn("bob", "pw", port));
    }

    private Connection conn(String user, String pw, int port) {
        Connection c = new Connection();
        c.type = Connection.Type.SMB;
        c.host = "127.0.0.1";
        c.port = port;
        c.user = user;
        c.password = pw;
        c.path = "share";
        return c;
    }

    @Override protected void stopServer() throws Exception {
        if (server != null) server.stop();
        String keep = System.getenv("FB_KEEP_LOGS");
        if (keep != null) java.nio.file.Files.copy(new File(tmp.getRoot(), "smb.log").toPath(), new File(keep, "smb-" + System.nanoTime() + ".log").toPath());
    }

    @Test public void wrongPasswordIsRefusedWithAClearMessage() throws Exception {
        try {
            Remotes.open(conn("bob", "wrong", server.port));
            fail("should not log in");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Wrong user name or password"));
        }
    }

    @Test public void aShareThatDoesntExistIsExplained() throws Exception {
        Connection c = conn("bob", "pw", server.port);
        c.path = "nosuchshare";
        try {
            Remotes.open(c);
            fail("a missing share must be reported");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no shared folder"));
        }
    }
}
