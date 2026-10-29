package com.fastshare.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows photos (swipe, pinch to zoom), PDFs, videos, music and text files without leaving FileBandit. */
public class ViewerActivity extends AppCompatActivity {

    static final String EXTRA_PATH = "path";
    private static final long MAX_TEXT_BYTES = 1_500_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private File file;
    private Viewer.Type type;
    private final List<File> files = new ArrayList<>();

    private MaterialToolbar mediaBar;
    private MaterialToolbar textBar;
    private ViewPager2 pager;
    private VideoView video;
    private EditText editor;
    private View errorBox;
    private TextView errorText;

    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfFd;
    private boolean dirty;
    private boolean readOnlyText;
    private boolean loadingText;
    private int videoPosition;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        file = path == null ? null : new File(path);
        type = file == null ? null : Viewer.typeOf(file);
        if (file == null || type == null || !file.exists()) {
            if (file != null && file.exists()) FileActions.open(this, file);
            else Toast.makeText(this, "That file isn't there any more", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_viewer);
        mediaBar = findViewById(R.id.mediaBar);
        textBar = findViewById(R.id.textBar);
        pager = findViewById(R.id.pager);
        video = findViewById(R.id.video);
        editor = findViewById(R.id.editor);
        errorBox = findViewById(R.id.errorBox);
        errorText = findViewById(R.id.errorText);
        findViewById(R.id.errorOpen).setOnClickListener(x -> FileActions.open(this, file));

        mediaBar.setNavigationOnClickListener(x -> finish());
        mediaBar.inflateMenu(R.menu.viewer_media);
        mediaBar.setOnMenuItemClickListener(this::onAction);
        textBar.setNavigationOnClickListener(x -> attemptClose());
        textBar.inflateMenu(R.menu.viewer_text);
        textBar.setOnMenuItemClickListener(this::onAction);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { attemptClose(); }
        });

        switch (type) {
            case IMAGE: showImages(); break;
            case PDF: showPdf(); break;
            case VIDEO: case AUDIO: showMedia(); break;
            default: showText();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) {
            videoPosition = video.getCurrentPosition();
            video.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        try {
            synchronized (this) {
                if (pdf != null) pdf.close();
                if (pdfFd != null) pdfFd.close();
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ common

    private void darkChrome() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void fail(String message) {
        findViewById(R.id.pager).setVisibility(View.GONE);
        findViewById(R.id.videoBox).setVisibility(View.GONE);
        findViewById(R.id.textBox).setVisibility(View.GONE);
        mediaBar.setVisibility(View.VISIBLE);
        errorText.setText(message);
        errorBox.setVisibility(View.VISIBLE);
    }

    private void toggleBar() {
        mediaBar.setVisibility(mediaBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private File current() {
        return type == Viewer.Type.IMAGE && !files.isEmpty() ? files.get(pager.getCurrentItem()) : file;
    }

    // ------------------------------------------------------------------ images

    private void showImages() {
        darkChrome();
        files.addAll(Viewer.sessionFiles);
        if (!files.contains(file)) {
            files.clear();
            files.add(file);
        }
        pager.setVisibility(View.VISIBLE);
        pager.setAdapter(new Pages(false));
        pager.setCurrentItem(files.indexOf(file), false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateGalleryTitle(); }
        });
        updateGalleryTitle();
    }

    private void updateGalleryTitle() {
        if (files.isEmpty()) return;
        int pos = pager.getCurrentItem();
        mediaBar.setTitle(files.get(pos).getName());
        mediaBar.setSubtitle(files.size() > 1 ? (pos + 1) + " of " + files.size() : null);
    }

    /** Decodes a photo no larger than needed for the screen. */
    private Drawable decode(File f) throws IOException {
        final int max = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) * 2;
        try {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(f), (decoder, info, src) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                float s = Math.min(1f, max / (float) Math.max(w, h));
                decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (Throwable first) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) throw new IOException("Can't decode " + f.getName());
            return new android.graphics.drawable.BitmapDrawable(getResources(), b);
        }
    }

    /** Pager pages: photos from files, or rendered PDF pages. */
    private final class Pages extends RecyclerView.Adapter<Pages.Holder> {
        private final boolean pdfMode;

        Pages(boolean pdfMode) {
            this.pdfMode = pdfMode;
        }

        @Override public int getItemCount() { return pdfMode ? pdf.getPageCount() : files.size(); }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ZoomImageView v = (ZoomImageView) getLayoutInflater().inflate(R.layout.item_page, parent, false);
            v.setTapListener(ViewerActivity.this::toggleBar);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, final int position) {
            h.view.setImageDrawable(null);
            h.position = position;
            final int width = getResources().getDisplayMetrics().widthPixels;
            try {
                io.execute(() -> {
                    Drawable d = null;
                    try {
                        if (pdfMode) d = new android.graphics.drawable.BitmapDrawable(getResources(), renderPdfPage(position, width));
                        else d = decode(files.get(position));
                    } catch (Throwable e) {
                        final String msg = pdfMode ? "Couldn't draw this PDF page" : "Couldn't open this picture";
                        ui.post(() -> {
                            if (h.position == position && !isDestroyed()) Toast.makeText(ViewerActivity.this, msg, Toast.LENGTH_SHORT).show();
                        });
                    }
                    final Drawable result = d;
                    if (result != null) {
                        ui.post(() -> {
                            if (h.position != position || isDestroyed()) return;
                            h.view.setImageDrawable(result);
                            if (result instanceof Animatable) ((Animatable) result).start();
                        });
                    }
                });
            } catch (Exception ignored) { }
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ZoomImageView view;
            int position = -1;

            Holder(ZoomImageView v) {
                super(v);
                view = v;
            }
        }
    }

    // ------------------------------------------------------------------ pdf

    private void showPdf() {
        darkChrome();
        mediaBar.setTitle(file.getName());
        try {
            pdfFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdf = new PdfRenderer(pdfFd);
        } catch (SecurityException e) {
            fail("This PDF is password protected. Open it with another app.");
            return;
        } catch (Throwable e) {
            fail("FileBandit couldn't read this PDF.");
            return;
        }
        pager.setVisibility(View.VISIBLE);
        pager.setAdapter(new Pages(true));
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                mediaBar.setSubtitle("Page " + (position + 1) + " of " + pdf.getPageCount());
            }
        });
        mediaBar.setSubtitle("Page 1 of " + pdf.getPageCount());
    }

    private Bitmap renderPdfPage(int index, int targetWidth) {
        synchronized (this) {
            PdfRenderer.Page page = pdf.openPage(index);
            try {
                int w = (int) (targetWidth * 1.6f);
                int h = Math.max(1, Math.round(w * (page.getHeight() / (float) page.getWidth())));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } finally {
                page.close();
            }
        }
    }

    // ------------------------------------------------------------------ video and audio

    private void showMedia() {
        darkChrome();
        findViewById(R.id.videoBox).setVisibility(View.VISIBLE);
        mediaBar.setTitle(file.getName());
        if (type == Viewer.Type.AUDIO) {
            findViewById(R.id.audioInfo).setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.audioTitle)).setText(file.getName());
        }
        MediaController controller = new MediaController(this);
        controller.setAnchorView(findViewById(R.id.videoBox));
        video.setMediaController(controller);
        video.setOnErrorListener((mp, what, extra) -> {
            fail("This phone can't play " + file.getName() + " here.");
            return true;
        });
        video.setOnPreparedListener(mp -> {
            if (videoPosition > 0) video.seekTo(videoPosition);
            video.start();
        });
        video.setVideoPath(file.getPath());
        findViewById(R.id.videoBox).setOnClickListener(x -> toggleBar());
    }

    // ------------------------------------------------------------------ text

    private void showText() {
        findViewById(R.id.textBox).setVisibility(View.VISIBLE);
        mediaBar.setVisibility(View.GONE);
        textBar.setTitle(file.getName());
        if (file.length() > MAX_TEXT_BYTES) {
            fail("This file is too large to edit here (" + Fmt.bytes(file.length()) + ").");
            return;
        }
        loadingText = true;
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (loadingText) return;
                dirty = true;
                textBar.setTitle("• " + file.getName());
            }
        });
        io.execute(() -> {
            try {
                final byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                String text;
                boolean strict = true;
                try {
                    text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data)).toString();
                } catch (CharacterCodingException e) {
                    text = new String(data, StandardCharsets.UTF_8);
                    strict = false;
                }
                final String shown = text;
                final boolean editable = strict;
                ui.post(() -> {
                    if (isDestroyed()) return;
                    editor.setText(shown);
                    loadingText = false;
                    readOnlyText = !editable;
                    if (readOnlyText) {
                        editor.setKeyListener(null);
                        Toast.makeText(this, "This isn't plain UTF-8 text, so it is shown read-only.", Toast.LENGTH_LONG).show();
                    }
                    textBar.getMenu().findItem(R.id.action_save).setVisible(!readOnlyText);
                });
            } catch (IOException e) {
                ui.post(() -> fail("Couldn't read " + file.getName()));
            }
        });
    }

    private boolean saveText() {
        if (readOnlyText) return false;
        byte[] data = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
        File part = new File(file.getPath() + ".fbsave");
        try {
            try (FileOutputStream os = new FileOutputStream(part)) {
                os.write(data);
            }
            if (!part.renameTo(file)) {
                try (FileOutputStream os = new FileOutputStream(file)) {
                    os.write(data);
                }
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        dirty = false;
        textBar.setTitle(file.getName());
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void attemptClose() {
        if (type == Viewer.Type.TEXT && dirty) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Save your changes?")
                    .setMessage(file.getName() + " has changes that aren't saved.")
                    .setPositiveButton("Save", (d, w) -> {
                        if (saveText()) finish();
                    })
                    .setNegativeButton("Discard", (d, w) -> finish())
                    .setNeutralButton("Keep editing", null)
                    .show();
        } else {
            finish();
        }
    }

    // ------------------------------------------------------------------ actions

    private boolean onAction(MenuItem mi) {
        int id = mi.getItemId();
        final File f = current();
        if (id == R.id.action_share) {
            FileActions.share(this, Collections.singletonList(f));
        } else if (id == R.id.action_send) {
            ShareHub hub = FastShareApp.hub(this);
            hub.start();
            hub.addFiles(Collections.singletonList(f));
            Toast.makeText(this, "Added to Transfer. Open the Transfer tab to connect the iPhone.", Toast.LENGTH_LONG).show();
        } else if (id == R.id.action_open_with) {
            FileActions.open(this, f);
        } else if (id == R.id.action_info) {
            showInfo(f);
        } else if (id == R.id.action_save) {
            saveText();
        } else if (id == R.id.action_delete) {
            trash(f);
        } else {
            return false;
        }
        return true;
    }

    private void showInfo(File f) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(f.getName()).append('\n');
        sb.append("Size: ").append(Fmt.bytes(f.length())).append('\n');
        sb.append("Modified: ").append(Fmt.dateTime(f.lastModified())).append('\n');
        if (type == Viewer.Type.IMAGE) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth > 0) sb.append("Picture: ").append(o.outWidth).append(" × ").append(o.outHeight).append('\n');
        }
        sb.append("Location: ").append(f.getParent());
        new MaterialAlertDialogBuilder(this).setTitle("Details").setMessage(sb).setPositiveButton("OK", null).show();
    }

    /** Moves the file to the recycle bin. In a gallery the photo disappears and the next one shows; Undo brings it back. */
    private void trash(final File f) {
        if (type == Viewer.Type.TEXT && dirty) {
            Toast.makeText(this, "Save or close your changes first", Toast.LENGTH_SHORT).show();
            return;
        }
        final int pos = type == Viewer.Type.IMAGE ? pager.getCurrentItem() : -1;
        final File volume = Volumes.rootOf(this, f);
        io.execute(() -> {
            final Trash t = new Trash(volume);
            final Trash.Entry entry;
            try {
                entry = t.trash(f);
            } catch (IOException e) {
                ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            ui.post(() -> {
                if (isDestroyed()) return;
                if (pos < 0 || files.size() <= 1) {
                    Toast.makeText(this, "Moved to trash", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                files.remove(pos);
                pager.getAdapter().notifyItemRemoved(pos);
                updateGalleryTitle();
                Snackbar.make(pager, "Moved to trash", 6000).setAction("Undo", v -> io.execute(() -> {
                    try {
                        final File back = t.restore(entry);
                        ui.post(() -> {
                            if (isDestroyed()) return;
                            int at = Math.min(pos, files.size());
                            files.add(at, back);
                            pager.getAdapter().notifyItemInserted(at);
                            pager.setCurrentItem(at, false);
                            updateGalleryTitle();
                        });
                    } catch (IOException e) {
                        ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                })).show();
            });
        });
    }
}
