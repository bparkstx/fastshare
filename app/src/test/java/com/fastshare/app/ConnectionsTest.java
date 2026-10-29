package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class ConnectionsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** A "protector" that is easy to recognise in the file. */
    private static final Connections.Secret ROT = new Connections.Secret() {
        @Override public String seal(String plain) { return plain.isEmpty() ? "" : "sealed:" + new StringBuilder(plain).reverse(); }
        @Override public String open(String sealed) throws IOException {
            if (sealed.isEmpty()) return "";
            if (!sealed.startsWith("sealed:")) throw new IOException("bad");
            return new StringBuilder(sealed.substring(7)).reverse().toString();
        }
    };

    private static Connection sample(String name) {
        Connection c = new Connection();
        c.name = name;
        c.type = Connection.Type.SFTP;
        c.host = "nas.local";
        c.port = 2222;
        c.user = "bob";
        c.password = "s3cret é\ttab";
        c.path = "/volume1/photos";
        c.privateKey = "-----BEGIN KEY-----\nabc\n-----END KEY-----";
        c.trustAll = true;
        c.hostKey = "SHA256:abc";
        return c;
    }

    @Test public void everythingSurvivesARestart() throws Exception {
        File f = new File(tmp.getRoot(), "sub/connections.tsv");
        Connections store = new Connections(f, ROT);
        Connection saved = store.save(sample("Home NAS"));
        assertFalse(saved.id.isEmpty());

        Connection back = new Connections(f, ROT).get(saved.id);
        assertNotNull(back);
        assertEquals("Home NAS", back.name);
        assertEquals(Connection.Type.SFTP, back.type);
        assertEquals("nas.local", back.host);
        assertEquals(2222, back.port);
        assertEquals("bob", back.user);
        assertEquals("s3cret é\ttab", back.password);
        assertEquals("/volume1/photos", back.path);
        assertTrue(back.privateKey.contains("\nabc\n"));
        assertTrue(back.trustAll);
        assertEquals("SHA256:abc", back.hostKey);
    }

    @Test public void passwordsAreNotWrittenInTheClear() throws Exception {
        File f = new File(tmp.getRoot(), "c.tsv");
        new Connections(f, ROT).save(sample("x"));
        String raw = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        assertFalse(raw.contains("s3cret"));
        // every field is base64, so even the sealed text is not directly readable here; decode and check it is the sealed form
        boolean found = false;
        for (String part : raw.trim().split("\t")) {
            String d = new String(java.util.Base64.getDecoder().decode(part), "UTF-8");
            assertFalse(d.contains("s3cret"));
            if (d.startsWith("sealed:")) found = true;
        }
        assertTrue(found);
    }

    @Test public void saveReplacesByIdAndRemoveDeletes() throws Exception {
        File f = new File(tmp.getRoot(), "c.tsv");
        Connections store = new Connections(f, ROT);
        Connection a = store.save(sample("A"));
        Connection b = store.save(sample("B"));
        assertEquals(2, store.list().size());
        a.name = "A renamed";
        store.save(a);
        assertEquals(2, store.list().size());
        assertEquals("A renamed", new Connections(f, ROT).get(a.id).name);
        store.remove(b.id);
        assertEquals(1, new Connections(f, ROT).list().size());
        assertNull(new Connections(f, ROT).get(b.id));
    }

    @Test public void changesToAReturnedCopyDoNotLeakIntoTheStore() throws Exception {
        Connections store = new Connections(new File(tmp.getRoot(), "c.tsv"), ROT);
        Connection a = store.save(sample("A"));
        a.name = "changed behind its back";
        assertEquals("A", store.get(a.id).name);
    }

    @Test public void aLostKeyMeansAskForThePasswordAgainNotCrash() throws Exception {
        File f = new File(tmp.getRoot(), "c.tsv");
        Connection a = new Connections(f, ROT).save(sample("A"));
        Connection back = new Connections(f, Connections.Secret.NONE).get(a.id);   // can't open "sealed:..." with the wrong protector? NONE returns as is
        assertNotNull(back);
        Connections.Secret broken = new Connections.Secret() {
            @Override public String seal(String p) { return p; }
            @Override public String open(String s) throws IOException { if (s.isEmpty()) return ""; throw new IOException("key gone"); }
        };
        Connection unreadable = new Connections(f, broken).get(a.id);
        assertNotNull("the connection itself is still listed", unreadable);
        assertEquals("", unreadable.password);
        assertEquals("nas.local", unreadable.host);
    }

    @Test public void damagedLinesAreSkipped() throws Exception {
        File f = new File(tmp.getRoot(), "c.tsv");
        Files.write(f.toPath(), "garbage\nnot\tenough\tfields\n".getBytes("UTF-8"));
        assertEquals(0, new Connections(f, ROT).list().size());
    }

    @Test public void checksCatchObviousMistakes() {
        Connection c = new Connection();
        assertTrue(c.problem().contains("address"));
        c.host = "http://nas";
        assertTrue(c.problem(), c.problem().contains("just a name or number"));
        c.host = "nas.local";
        c.type = Connection.Type.SMB;
        assertTrue(c.problem(), c.problem().contains("shared folder"));
        c.path = "\\\\photos";
        assertNull(c.problem());
        c.type = Connection.Type.SFTP;
        assertTrue(c.problem().contains("user name"));
        c.user = "bob";
        assertNull(c.problem());
        c.port = 70000;
        assertTrue(c.problem().contains("port"));
    }

    @Test public void describeReadsLikeAnAddress() {
        Connection c = sample("n");
        assertEquals("sftp://bob@nas.local:2222/volume1/photos", c.describe());
        c.port = 0;
        c.path = "";
        c.user = "";
        c.type = Connection.Type.WEBDAVS;
        assertEquals("https://nas.local", c.describe());
        assertEquals(443, c.portOrDefault());
    }
}
