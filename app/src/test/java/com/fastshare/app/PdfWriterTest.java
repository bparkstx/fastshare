package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PdfWriterTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Small JPEGs made once with an image tool (a solid colour with a black bar), kept as test resources. */
    private static byte[] jpeg(String name) throws IOException {
        try (InputStream in = PdfWriterTest.class.getResourceAsStream("/pdf/" + name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    /** Reads a binary P6 image. Returns width, height, then every colour value. */
    private static int[] ppm(File f) throws IOException {
        byte[] b = Files.readAllBytes(f.toPath());
        int pos = 0;
        String[] head = new String[4];
        for (int i = 0; i < 4; i++) {
            while (Character.isWhitespace(b[pos])) pos++;
            int start = pos;
            while (!Character.isWhitespace(b[pos])) pos++;
            head[i] = new String(b, start, pos - start, StandardCharsets.US_ASCII);
        }
        pos++;
        int w = Integer.parseInt(head[1]), h = Integer.parseInt(head[2]);
        int[] out = new int[2 + w * h * 3];
        out[0] = w;
        out[1] = h;
        for (int i = 0; i < w * h * 3; i++) out[2 + i] = b[pos + i] & 0xFF;
        return out;
    }

    @Test public void everyObjectIsWhereTheIndexSaysItIs() throws Exception {
        PdfWriter w = new PdfWriter().title("My (scan) \\ é");
        w.addJpeg(jpeg("white_600x800.jpg"), 600, 800);
        w.addJpeg(jpeg("yellow_800x600.jpg"), 800, 600);
        byte[] pdf = w.toBytes();
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("%PDF-1.4"));
        assertTrue(s.trim().endsWith("%%EOF"));
        Matcher sx = Pattern.compile("startxref\\n(\\d+)\\n").matcher(s);
        assertTrue(sx.find());
        int xref = Integer.parseInt(sx.group(1));
        assertTrue(s.substring(xref).startsWith("xref\n0 10\n"));
        String[] lines = s.substring(xref).split("\n");
        for (int i = 3; i < 12; i++) {                          // entries for objects 1..9
            long off = Long.parseLong(lines[i].substring(0, 10));
            assertTrue("object " + (i - 2) + " at " + off, s.substring((int) off).startsWith((i - 2) + " 0 obj"));
        }
        assertEquals(2, w.pages());
        assertTrue(s.contains("/Count 2"));
        assertTrue("title is escaped", s.contains("/Title (My \\(scan\\) \\\\ ?)"));
    }

    @Test public void aRealPdfReaderOpensItAndSeesThePages() throws Exception {
        Assume.assumeTrue("poppler isn't installed", new File("/usr/bin/pdfinfo").canExecute() && new File("/usr/bin/pdftoppm").canExecute());
        PdfWriter w = new PdfWriter().title("Scan 1");
        w.addJpeg(jpeg("white_600x800.jpg"), 600, 800);
        w.addJpeg(jpeg("yellow_800x600.jpg"), 800, 600);
        w.addJpeg(jpeg("blue_300x300.jpg"), 300, 300);
        File f = tmp.newFile("scan.pdf");
        Files.write(f.toPath(), w.toBytes());
        String info = run("/usr/bin/pdfinfo", "-f", "1", "-l", "3", f.getPath());
        String flat = info.replaceAll("\\s+", " ");
        assertTrue(info, flat.contains("Pages: 3"));
        assertTrue(info, flat.contains("Title: Scan 1"));
        assertTrue(info, flat.contains("Page 2 size: 842 x 631.5 pts"));
        // render page 2: a yellow page with a black bar
        File base = new File(tmp.getRoot(), "p");
        String err = run("/usr/bin/pdftoppm", "-f", "2", "-l", "2", "-r", "36", "-f", "2", f.getPath(), base.getPath());
        File[] out = tmp.getRoot().listFiles((d, n) -> n.startsWith("p-") && n.endsWith(".ppm"));
        assertTrue("no picture came out: " + err, out != null && out.length == 1);
        int[] px = ppm(out[0]);
        int w2 = px[0], h2 = px[1];
        int ci = 2 + (3 * w2 + 3) * 3;
        int bi = 2 + ((h2 * 5 / 16) * w2 + w2 / 2) * 3;
        assertTrue("yellowish page: " + px[ci] + "," + px[ci + 1] + "," + px[ci + 2], px[ci] > 230 && px[ci + 1] > 200 && px[ci + 2] < 160);
        assertTrue("black bar: " + px[bi] + "," + px[bi + 1], px[bi] < 60 && px[bi + 1] < 60);
        assertTrue("landscape page", w2 > h2);
    }

    @Test public void aPdfWithNoPagesIsRefused() {
        try {
            new PdfWriter().toBytes();
            fail("should refuse");
        } catch (IllegalStateException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    // pdftoppm writes ppm by default; the helper above reads that
}
