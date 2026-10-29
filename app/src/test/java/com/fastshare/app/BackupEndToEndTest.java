package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Random;

/** The backup against real servers: whatever arrives on the server's disk must match the phone's files. */
public class BackupEndToEndTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File makePhone() throws Exception {
        File phone = tmp.newFolder("phone");
        for (int i = 0; i < 4; i++) {
            File f = new File(phone, "DCIM/Camera/IMG_" + i + ".jpg");
            f.getParentFile().mkdirs();
            byte[] b = new byte[300_000 + i * 1000];
            new Random(i).nextBytes(b);
            Files.write(f.toPath(), b);
            f.setLastModified(new GregorianCalendar(2026, i < 2 ? Calendar.MARCH : Calendar.APRIL, 10 + i, 9, 0).getTimeInMillis());
        }
        File video = new File(phone, "DCIM/Camera/clip é.mp4");
        byte[] vb = new byte[2_500_000];
        new Random(99).nextBytes(vb);
        Files.write(video.toPath(), vb);
        video.setLastModified(new GregorianCalendar(2026, Calendar.APRIL, 20, 9, 0).getTimeInMillis());
        return phone;
    }

    private void checkOnDisk(File phone, File served) throws Exception {
        for (File f : new File(phone, "DCIM/Camera").listFiles()) {
            Calendar c = new GregorianCalendar();
            c.setTimeInMillis(f.lastModified());
            File there = new File(served, String.format("Pixel/%d/%02d/%s", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, f.getName()));
            assertTrue(there.getPath(), there.isFile());
            assertArrayEquals(f.getName(), Files.readAllBytes(f.toPath()), Files.readAllBytes(there.toPath()));
        }
        // no temporary names left behind
        assertEquals(0, countParts(served));
    }

    private int countParts(File dir) {
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File k : kids) n += k.isDirectory() ? countParts(k) : (k.getName().endsWith(Backup.PART_SUFFIX) ? 1 : 0);
        return n;
    }

    private Backup.Options options(File phone) {
        Backup.Options o = new Backup.Options();
        o.folders.add(new File(phone, "DCIM"));
        o.destRoot = "/Pixel";
        return o;
    }

    @Test public void backsUpToThePhonesOwnWebDavStyleServerAndResumesAfterADroppedConnection() throws Exception {
        File phone = makePhone();
        File served = tmp.newFolder("served");
        FileServer server = new FileServer("tok12345", new FileServer.Backend() {
            @Override public byte[] indexPage() { return new byte[0]; }
            @Override public String deviceName() { return "x"; }
            @Override public InputStream openShared(Object ref) { return null; }
            @Override public FileServer.Sink openSink(String n) { return null; }
        }, new FileServer.Listener() {
            @Override public void onClient(String ip, String ua) { }
            @Override public void onSharedChanged() { }
            @Override public void onTransfer(FileServer.Transfer t) { }
        });
        int port = server.start(0);
        try {
            server.setDav(served, true, null);
            Connection c = new Connection();
            c.type = Connection.Type.WEBDAV;
            c.host = "127.0.0.1";
            c.port = port;
            c.path = server.davPath();
            BackupLog log = new BackupLog(new File(tmp.getRoot(), "log.tsv"));
            Backup.Summary s;
            try (Remote r = new DavRemote(c)) {
                s = Backup.run(r, options(phone), log, null);
            }
            assertEquals(5, s.uploaded);
            assertEquals(0, s.failed);
            checkOnDisk(phone, served);
            try (Remote r = new DavRemote(c)) {
                Backup.Summary again = Backup.run(r, options(phone), log, null);
                assertEquals(0, again.uploaded);
                assertEquals(5, again.skipped);
            }
        } finally {
            server.stop();
        }
    }

    @Test public void backsUpOverSftp() throws Exception {
        String python = ExternalServer.python("asyncssh");
        Assume.assumeNotNull("asyncssh isn't installed (pip install asyncssh)", python);
        File phone = makePhone();
        File served = tmp.newFolder("served");
        int port = ExternalServer.freePort();
        ExternalServer srv = ExternalServer.start(Arrays.asList(python, "-u", new File("../tools/sftp_test_server.py").getCanonicalPath(),
                String.valueOf(port), served.getPath(), new File(tmp.getRoot(), "hostkey").getPath()), port, tmp.newFile("sftp.log"));
        try {
            Connection c = new Connection();
            c.type = Connection.Type.SFTP;
            c.host = "127.0.0.1";
            c.port = port;
            c.user = "bob";
            c.password = "pw";
            BackupLog log = new BackupLog(new File(tmp.getRoot(), "log.tsv"));
            try (Remote r = Remotes.open(c)) {
                Backup.Summary s = Backup.run(r, options(phone), log, null);
                assertEquals(s.errors.toString(), 5, s.uploaded);
            }
            checkOnDisk(phone, served);
        } finally {
            srv.stop();
        }
    }

    @Test public void backsUpOverSmb() throws Exception {
        File tool = ExternalServer.find("smbserver.py");
        String python = ExternalServer.python("impacket");
        Assume.assumeNotNull("impacket isn't installed (pip install impacket)", tool, python);
        File phone = makePhone();
        File served = tmp.newFolder("served");
        int port = ExternalServer.freePort();
        ExternalServer srv = ExternalServer.start(Arrays.asList(python, "-u", tool.getPath(), "-smb2support", "-username", "bob",
                "-password", "pw", "-port", String.valueOf(port), "share", served.getPath()), port, tmp.newFile("smb.log"));
        try {
            Connection c = new Connection();
            c.type = Connection.Type.SMB;
            c.host = "127.0.0.1";
            c.port = port;
            c.user = "bob";
            c.password = "pw";
            c.path = "share";
            BackupLog log = new BackupLog(new File(tmp.getRoot(), "log.tsv"));
            try (Remote r = Remotes.open(c)) {
                Backup.Summary s = Backup.run(r, options(phone), log, null);
                assertEquals(s.errors.toString(), 5, s.uploaded);
            }
            checkOnDisk(phone, served);
        } finally {
            srv.stop();
        }
    }
}
