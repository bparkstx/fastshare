package com.fastshare.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes a PDF with one picture per page. The pictures arrive already JPEG-compressed, so the file is the size of the
 * pictures plus a few hundred bytes. No Android imports.
 */
public final class PdfWriter {
    private final List<byte[]> jpegs = new ArrayList<>();
    private final List<int[]> sizes = new ArrayList<>();
    private String title = "Scan";

    public PdfWriter title(String t) {
        title = t == null ? "Scan" : t;
        return this;
    }

    /** Adds a page that is exactly this JPEG, {@code w} by {@code h} pixels. */
    public PdfWriter addJpeg(byte[] jpeg, int w, int h) {
        jpegs.add(jpeg);
        sizes.add(new int[]{w, h});
        return this;
    }

    public int pages() {
        return jpegs.size();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') sb.append('\\').append(c);
            else if (c < 32 || c > 126) sb.append('?');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** The finished file. */
    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            writeTo(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        if (jpegs.isEmpty()) throw new IOException("There are no pages");
        int n = jpegs.size();
        // object numbers: 1 catalog, 2 pages, 3 info, then per page: page, contents, image
        List<Long> offsets = new ArrayList<>();
        Counter c = new Counter(out);
        c.write("%PDF-1.4\n%âãÏÓ\n");
        offsets.add(c.pos);
        c.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add(c.pos);
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(4 + i * 3).append(" 0 R ");
        c.write("2 0 obj\n<< /Type /Pages /Count " + n + " /Kids [" + kids + "] >>\nendobj\n");
        offsets.add(c.pos);
        c.write("3 0 obj\n<< /Title (" + esc(title) + ") /Producer (FileBandit) >>\nendobj\n");
        for (int i = 0; i < n; i++) {
            int pageObj = 4 + i * 3, contentObj = pageObj + 1, imageObj = pageObj + 2;
            int pw = sizes.get(i)[0], ph = sizes.get(i)[1];
            // page size in points: the long side is 842 (A4) so a scan prints at a sensible size
            double scale = 842.0 / Math.max(pw, ph);
            double w = pw * scale, h = ph * scale;
            String box = String.format(Locale.US, "%.2f %.2f", w, h);
            offsets.add(c.pos);
            c.write(pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + box + "] /Resources << /XObject << /Im0 " + imageObj
                    + " 0 R >> /ProcSet [/PDF /ImageC] >> /Contents " + contentObj + " 0 R >>\nendobj\n");
            String content = String.format(Locale.US, "q %.2f 0 0 %.2f 0 0 cm /Im0 Do Q\n", w, h);
            offsets.add(c.pos);
            c.write(contentObj + " 0 obj\n<< /Length " + content.length() + " >>\nstream\n" + content + "endstream\nendobj\n");
            offsets.add(c.pos);
            byte[] jpeg = jpegs.get(i);
            c.write(imageObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + pw + " /Height " + ph
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length + " >>\nstream\n");
            c.write(jpeg);
            c.write("\nendstream\nendobj\n");
        }
        long xref = c.pos;
        int total = 3 + n * 3;
        StringBuilder x = new StringBuilder("xref\n0 " + (total + 1) + "\n0000000000 65535 f \n");
        for (long off : offsets) x.append(String.format(Locale.US, "%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(total + 1).append(" /Root 1 0 R /Info 3 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        c.write(x.toString());
        out.flush();
    }

    private static final class Counter {
        final OutputStream out;
        long pos;

        Counter(OutputStream out) {
            this.out = out;
        }

        void write(String s) throws IOException {
            write(s.getBytes(StandardCharsets.ISO_8859_1));
        }

        void write(byte[] b) throws IOException {
            out.write(b);
            pos += b.length;
        }
    }
}
