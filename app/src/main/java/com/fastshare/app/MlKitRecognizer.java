package com.fastshare.app;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Reads words and objects out of pictures, and the first pages of PDFs, with Google's on-device ML Kit models. Nothing
 * leaves the phone: the models are part of the app. Call from a background thread.
 */
final class MlKitRecognizer implements ContentIndexer.Recognizer {
    private static final int PICTURE_SIDE = 1280;
    private static final int PDF_PAGES = 3;
    private static final float MIN_CONFIDENCE = 0.65f;

    private final TextRecognizer text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ImageLabeler labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build());

    @Override
    public ContentIndexer.Result recognize(File f) throws IOException {
        try {
            if (ContentIndexer.ext(f.getName()).equals("pdf")) return pdf(f);
            Bitmap b = SwipeCleanActivity.decodeForScreen(f, PICTURE_SIDE);
            if (b == null) throw new IOException("Can't read " + f.getName());
            try {
                InputImage image = InputImage.fromBitmap(b, 0);
                String words = Tasks.await(text.process(image)).getText();
                List<String> names = new ArrayList<>();
                for (ImageLabel l : Tasks.await(labeler.process(image))) names.add(l.getText());
                return new ContentIndexer.Result(words, names);
            } finally {
                b.recycle();
            }
        } catch (ExecutionException e) {
            throw new IOException("Couldn't read " + f.getName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** A scanned PDF has no text of its own, so the first pages are drawn and read like pictures. */
    private ContentIndexer.Result pdf(File f) throws IOException, ExecutionException, InterruptedException {
        StringBuilder all = new StringBuilder();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(fd)) {
            for (int i = 0; i < Math.min(PDF_PAGES, r.getPageCount()); i++) {
                try (PdfRenderer.Page page = r.openPage(i)) {
                    int w = 1200;
                    int h = Math.max(1, w * page.getHeight() / Math.max(1, page.getWidth()));
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(0xFFFFFFFF);
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    all.append(Tasks.await(text.process(InputImage.fromBitmap(b, 0))).getText()).append('\n');
                    b.recycle();
                }
            }
        } catch (SecurityException locked) {
            throw new IOException("Password protected");
        }
        return new ContentIndexer.Result(all.toString(), new ArrayList<String>());
    }
}
