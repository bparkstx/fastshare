package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** Against a real WebDAV server (wsgidav). */
public class DavRemoteTest extends RemoteContractTest {
    private ExternalServer server;

    @Override protected Remote connect(File root) throws Exception {
        File tool = ExternalServer.find("wsgidav");
        Assume.assumeNotNull("wsgidav isn't installed (pip install wsgidav cheroot)", tool);
        int port = ExternalServer.freePort();
        File conf = tmp.newFile("wsgidav.yaml");
        Files.write(conf.toPath(), Arrays.asList(
                "host: 127.0.0.1",
                "port: " + port,
                "provider_mapping: {\"/\": \"" + root.getPath() + "\"}",
                "simple_dc:",
                "  user_mapping:",
                "    \"*\":",
                "      bob: {password: pw}",
                "http_authenticator:",
                "  domain_controller: null",
                "  accept_basic: true",
                "  accept_digest: false",
                "  default_to_digest: false",
                "verbose: 1",
                "dir_browser: {enable: false}"));
        server = ExternalServer.start(Arrays.asList(tool.getPath(), "--config", conf.getPath()), port, tmp.newFile("wsgidav.log"));
        return new DavRemote(conn("bob", "pw", port));
    }

    private Connection conn(String user, String pw, int port) {
        Connection c = new Connection();
        c.type = Connection.Type.WEBDAV;
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
            Remotes.open(conn("bob", "wrong", server.port));
            fail("should not log in");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Wrong user name or password"));
        }
    }

    @Test public void aNonDavAddressIsExplained() throws Exception {
        Connection c = conn("bob", "pw", server.port);
        c.path = "/definitely/not/here";
        try {
            Remotes.open(c);
            fail("a missing folder must be reported");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("doesn't exist"));
        }
    }

    @Test public void startingFolderIsHonoured() throws Exception {
        new File(root, "docs").mkdir();
        Files.write(new File(root, "docs/a.txt").toPath(), "x".getBytes("UTF-8"));
        Connection c = conn("bob", "pw", server.port);
        c.path = "/docs";
        try (Remote r = Remotes.open(c)) {
            assertEquals("[a.txt]", names(r.list("/")).toString());
        }
    }
}
