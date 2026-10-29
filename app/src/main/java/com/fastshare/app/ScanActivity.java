package com.fastshare.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Photograph paper, get straight, clean pages, save them as one PDF. */
public class ScanActivity extends AppCompatActivity {

    private static final int SOURCE_MAX = 2400;      // photos are shrunk to this before working on them
    private static final int PAGE_MAX = 2200;        // longest side of a saved page

    private static final class Page {
        final File source;          // the photo, shrunk, in this app's cache
        DocScan.Quad quad;          // where the page is in the photo
        DocScan.Look look = DocScan.Look.DOCUMENT;
        int turns;                  // quarter turns clockwise
        Bitmap thumb;
        Bitmap preview;

        Page(File source, DocScan.Quad quad) {
            this.source = source;
            this.quad = quad;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Page> pages = new ArrayList<>();
    private int current = -1;
    private int captureCounter;
    private File pendingCapture;

    // the photo being cropped (new page, or an existing one whose edges are being adjusted)
    private File cropSource;
    private Bitmap cropBitmap;
    private int cropPage = -1;

    private MaterialToolbar toolbar;
    private View busy, emptyBox, pagesBox, cropBox;
    private ImageView preview;
    private TextView pageInfo;
    private CropView cropView;
    private MaterialButtonToggleGroup looks;
    private RecyclerView thumbs;
    private RecyclerView.Adapter<ThumbHolder> thumbAdapter;

    private final ActivityResultLauncher<Uri> camera = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
        if (ok && pendingCapture != null && pendingCapture.length() > 0) addFromFile(pendingCapture);
        else if (pendingCapture != null) //noinspection ResultOfMethodCallIgnored
            pendingCapture.delete();
    });

    private final ActivityResultLauncher<String> gallery = registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
        if (uris != null) for (Uri u : uris) addFromUri(u);
    });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_scan);
        toolbar = findViewById(R.id.toolbar);
        busy = findViewById(R.id.busy);
        emptyBox = findViewById(R.id.emptyBox);
        pagesBox = findViewById(R.id.pagesBox);
        cropBox = findViewById(R.id.cropBox);
        preview = findViewById(R.id.preview);
        pageInfo = findViewById(R.id.pageInfo);
        cropView = findViewById(R.id.cropView);
        looks = findViewById(R.id.looks);
        thumbs = findViewById(R.id.thumbs);

        toolbar.setNavigationOnClickListener(x -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (cropBox.getVisibility() == View.VISIBLE) {
                    closeCrop();
                } else if (!pages.isEmpty()) {
                    confirmDiscard();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        findViewById(R.id.eCamera).setOnClickListener(x -> takePhoto());
        findViewById(R.id.aAdd).setOnClickListener(x -> chooseSource());
        findViewById(R.id.eGallery).setOnClickListener(x -> gallery.launch("image/*"));
        findViewById(R.id.aRotate).setOnClickListener(x -> {
            if (current < 0) return;
            pages.get(current).turns = (pages.get(current).turns + 1) % 4;
            rebuild(current);
        });
        findViewById(R.id.aDelete).setOnClickListener(x -> deleteCurrent());
        findViewById(R.id.aEdges).setOnClickListener(x -> {
            if (current >= 0) openCrop(pages.get(current).source, pages.get(current).quad, current);
        });
        findViewById(R.id.aSave).setOnClickListener(x -> save());
        findViewById(R.id.cropCancel).setOnClickListener(x -> closeCrop());
        findViewById(R.id.cropDone).setOnClickListener(x -> finishCrop());
        looks.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked || current < 0) return;
            Page p = pages.get(current);
            DocScan.Look l = id == R.id.lookGray ? DocScan.Look.GRAY : id == R.id.lookBw ? DocScan.Look.BLACK_WHITE
                    : id == R.id.lookOriginal ? DocScan.Look.ORIGINAL : DocScan.Look.DOCUMENT;
            if (p.look != l) {
                p.look = l;
                rebuild(current);
            }
        });
        thumbs.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        thumbAdapter = new RecyclerView.Adapter<ThumbHolder>() {
            @Override public ThumbHolder onCreateViewHolder(ViewGroup parent, int type) {
                ImageView v = new ImageView(ScanActivity.this);
                int d = (int) (72 * getResources().getDisplayMetrics().density);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(d, d);
                lp.setMargins(0, 0, (int) (8 * getResources().getDisplayMetrics().density), 0);
                v.setLayoutParams(lp);
                v.setScaleType(ImageView.ScaleType.CENTER_CROP);
                v.setBackgroundResource(R.drawable.bg_card_soft);
                v.setPadding(3, 3, 3, 3);
                return new ThumbHolder(v);
            }

            @Override public void onBindViewHolder(ThumbHolder h, int position) {
                final int at = position;
                h.view.setImageBitmap(pages.get(position).thumb);
                h.view.setAlpha(position == current ? 1f : 0.55f);
                h.view.setOnClickListener(x -> select(at));
            }

            @Override public int getItemCount() {
                return pages.size();
            }
        };
        thumbs.setAdapter(thumbAdapter);
        updateChrome();
    }

    private static final class ThumbHolder extends RecyclerView.ViewHolder {
        final ImageView view;

        ThumbHolder(ImageView v) {
            super(v);
            view = v;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void leave() {
        if (cropBox.getVisibility() == View.VISIBLE) closeCrop();
        else if (!pages.isEmpty()) confirmDiscard();
        else finish();
    }

    private void confirmDiscard() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Throw away these scans?")
                .setMessage("You haven't saved them yet.")
                .setNegativeButton("Keep scanning", null)
                .setPositiveButton("Throw away", (d, w) -> finish()).show();
    }

    // ------------------------------------------------------------------ getting a photo

    private File scansCache() {
        File dir = new File(getCacheDir(), "scans");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private void takePhoto() {
        pendingCapture = new File(scansCache(), "capture_" + (captureCounter++) + "_" + System.currentTimeMillis() + ".jpg");
        Uri out = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingCapture);
        camera.launch(out);
    }

    private void chooseSource() {
        new MaterialAlertDialogBuilder(this).setTitle("Add a page")
                .setItems(new String[]{"Take a photo", "Choose from gallery"}, (d, which) -> {
                    if (which == 0) takePhoto(); else gallery.launch("image/*");
                }).show();
    }

    /** Copies a picked picture into the cache (shrunk), then goes to the crop screen. */
    void addFromUri(final Uri u) {
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            File copy = new File(scansCache(), "pick_" + (captureCounter++) + "_" + System.currentTimeMillis() + ".jpg");
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("Can't open that picture");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    Snackbar.make(toolbar, "Couldn't read that picture", 5000).show();
                });
                return;
            }
            ui.post(() -> addFromFile(copy));
        });
    }

    /** A photo from the camera or gallery: shrink it, look for the page, and open the corner editor. */
    void addFromFile(final File photo) {
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            final Bitmap b = SwipeCleanActivity.decodeForScreen(photo, SOURCE_MAX);
            if (b == null) {
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    Snackbar.make(toolbar, "Couldn't read that picture", 5000).show();
                });
                return;
            }
            File small = new File(scansCache(), "page_" + (captureCounter++) + "_" + System.currentTimeMillis() + ".png");
            try (OutputStream os = new FileOutputStream(small)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, os);
            } catch (IOException ignored) { }
            if (!photo.equals(small)) //noinspection ResultOfMethodCallIgnored
                photo.delete();
            final DocScan.Quad q = DocScan.detect(toImg(b));
            ui.post(() -> {
                if (isDestroyed()) return;
                busy.setVisibility(View.GONE);
                openCrop(small, q, -1, b);
            });
        });
    }

    // ------------------------------------------------------------------ crop

    private void openCrop(File source, DocScan.Quad q, int existingPage) {
        busy.setVisibility(View.VISIBLE);
        final File src = source;
        io.execute(() -> {
            final Bitmap b = SwipeCleanActivity.decodeForScreen(src, SOURCE_MAX);
            ui.post(() -> {
                if (isDestroyed()) return;
                busy.setVisibility(View.GONE);
                if (b == null) return;
                openCrop(src, q, existingPage, b);
            });
        });
    }

    private void openCrop(File source, DocScan.Quad q, int existingPage, Bitmap bitmap) {
        cropSource = source;
        cropBitmap = bitmap;
        cropPage = existingPage;
        cropView.setImage(bitmap, q);
        cropBox.setVisibility(View.VISIBLE);
        updateChrome();
    }

    private void closeCrop() {
        if (cropPage < 0 && cropSource != null) //noinspection ResultOfMethodCallIgnored
            cropSource.delete();
        cropBox.setVisibility(View.GONE);
        cropBitmap = null;
        cropSource = null;
        updateChrome();
    }

    private void finishCrop() {
        DocScan.Quad q = cropView.getQuad();
        if (!q.isSane(cropBitmap.getWidth(), cropBitmap.getHeight())) {
            Snackbar.make(cropView, "The corners cross over. Drag them to the page's four corners, in a loop.", 6000).show();
            return;
        }
        if (cropPage >= 0) {
            pages.get(cropPage).quad = q;
            cropBox.setVisibility(View.GONE);
            rebuild(cropPage);
        } else {
            Page p = new Page(cropSource, q);
            pages.add(p);
            cropBox.setVisibility(View.GONE);
            current = pages.size() - 1;
            rebuild(current);
        }
        cropBitmap = null;
        cropSource = null;
        updateChrome();
    }

    // ------------------------------------------------------------------ pages

    static DocScan.Img toImg(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return new DocScan.Img(w, h, px);
    }

    static Bitmap toBitmap(DocScan.Img img) {
        return Bitmap.createBitmap(img.px, img.w, img.h, Bitmap.Config.ARGB_8888);
    }

    /** Straightens and cleans a page at the given size. Runs off the main thread. */
    private DocScan.Img process(Page p, int maxSide) throws IOException {
        Bitmap src = SwipeCleanActivity.decodeForScreen(p.source, SOURCE_MAX);
        if (src == null) throw new IOException("The photo is gone");
        DocScan.Img flat = DocScan.straighten(toImg(src), p.quad, maxSide);
        src.recycle();
        return DocScan.rotate(DocScan.enhance(flat, p.look), p.turns);
    }

    private void rebuild(final int index) {
        final Page p = pages.get(index);
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                DocScan.Img big = process(p, 1400);
                final Bitmap prev = toBitmap(big);
                final Bitmap th = Bitmap.createScaledBitmap(prev, 72, Math.max(1, 72 * prev.getHeight() / prev.getWidth()), true);
                ui.post(() -> {
                    if (isDestroyed()) return;
                    busy.setVisibility(View.GONE);
                    p.preview = prev;
                    p.thumb = th;
                    if (index < pages.size() && pages.get(index) == p) {
                        current = index;
                        showCurrent();
                    }
                    thumbAdapter.notifyDataSetChanged();
                });
            } catch (IOException e) {
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    Snackbar.make(toolbar, e.getMessage(), 5000).show();
                });
            }
        });
    }

    private void select(int index) {
        current = index;
        showCurrent();
        thumbAdapter.notifyDataSetChanged();
    }

    private void showCurrent() {
        if (current < 0 || current >= pages.size()) return;
        Page p = pages.get(current);
        preview.setImageBitmap(p.preview);
        pageInfo.setText("Page " + (current + 1) + " of " + pages.size());
        int id = p.look == DocScan.Look.GRAY ? R.id.lookGray : p.look == DocScan.Look.BLACK_WHITE ? R.id.lookBw
                : p.look == DocScan.Look.ORIGINAL ? R.id.lookOriginal : R.id.lookDocument;
        if (looks.getCheckedButtonId() != id) looks.check(id);
        updateChrome();
    }

    private void deleteCurrent() {
        if (current < 0) return;
        Page p = pages.remove(current);
        //noinspection ResultOfMethodCallIgnored
        p.source.delete();
        current = pages.isEmpty() ? -1 : Math.min(current, pages.size() - 1);
        thumbAdapter.notifyDataSetChanged();
        if (current >= 0) showCurrent();
        updateChrome();
    }

    private void updateChrome() {
        boolean cropping = cropBox.getVisibility() == View.VISIBLE;
        emptyBox.setVisibility(pages.isEmpty() && !cropping ? View.VISIBLE : View.GONE);
        pagesBox.setVisibility(!pages.isEmpty() && !cropping ? View.VISIBLE : View.GONE);
        toolbar.setTitle(pages.isEmpty() ? "Scan documents" : "Scan documents (" + pages.size() + ")");
        Menu m = toolbar.getMenu();
        m.clear();
    }

    // ------------------------------------------------------------------ saving

    static File scansDir() {
        return new File(Environment.getExternalStorageDirectory(), "Documents/FileBandit Scans");
    }

    private void save() {
        if (pages.isEmpty()) return;
        final boolean shared = !Access.allFiles();
        busy.setVisibility(View.VISIBLE);
        final List<Page> todo = new ArrayList<>(pages);
        io.execute(() -> {
            try {
                PdfWriter pdf = new PdfWriter().title("Scan " + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
                for (Page p : todo) {
                    DocScan.Img img = process(p, PAGE_MAX);
                    Bitmap b = toBitmap(img);
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 88, jpeg);
                    b.recycle();
                    pdf.addJpeg(jpeg.toByteArray(), img.w, img.h);
                }
                File dir = shared ? scansCache() : scansDir();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir.getName());
                File out = FileOps.uniqueSibling(dir, "Scan " + new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(new Date()) + ".pdf");
                try (OutputStream os = new FileOutputStream(out)) {
                    pdf.writeTo(os);
                }
                final File result = out;
                ui.post(() -> saved(result, todo.size(), shared));
            } catch (IOException e) {
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    Snackbar.make(toolbar, "Couldn't save: " + e.getMessage(), 8000).show();
                });
            }
        });
    }

    private void saved(final File pdf, int count, boolean temporaryOnly) {
        if (isDestroyed()) return;
        busy.setVisibility(View.GONE);
        for (Page p : pages) //noinspection ResultOfMethodCallIgnored
            p.source.delete();
        pages.clear();
        current = -1;
        thumbAdapter.notifyDataSetChanged();
        updateChrome();
        Snackbar bar = Snackbar.make(toolbar, "Saved " + Fmt.plural(count, "page", "pages") + " as " + pdf.getName()
                + (temporaryOnly ? " (allow file access to keep it in Documents)" : " in Documents/FileBandit Scans"), 12000);
        bar.setAction("Share", v -> FileActions.share(this, java.util.Collections.singletonList(pdf)));
        bar.show();
        lastSaved = pdf;
    }

    /** The most recently saved PDF (for the tests and the Open button). */
    File lastSaved;

    int pageCount() {
        return pages.size();
    }

    Bitmap currentPreview() {
        return current >= 0 ? pages.get(current).preview : null;
    }
}
