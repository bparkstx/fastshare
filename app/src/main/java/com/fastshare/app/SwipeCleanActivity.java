package com.fastshare.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finds near-copies, blurry photos, black frames and old screenshots, then lets you swipe through them. */
public class SwipeCleanActivity extends AppCompatActivity {

    /** The tests swap in a decoder that doesn't need real pictures. */
    static volatile PhotoAnalysis.Decoder decoder = new AndroidPhotoDecoder();

    private enum Kind { SIMILAR, BLURRY, DARK, SCREENSHOTS }

    private static final class Card {
        final PhotoAnalysis.Stat stat;
        final PhotoAnalysis.Stat keeping;      // for near-copies: the photo that stays
        final String reason;

        Card(PhotoAnalysis.Stat stat, PhotoAnalysis.Stat keeping, String reason) {
            this.stat = stat;
            this.keeping = keeping;
            this.reason = reason;
        }
    }

    private static final class Decision {
        final Card card;
        final Trash.Entry trashed;      // null when the photo was kept

        Decision(Card card, Trash.Entry trashed) {
            this.card = card;
            this.trashed = trashed;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelScan = new AtomicBoolean();
    private final Set<String> handled = new HashSet<>();     // paths already trashed or kept
    private final Deque<Decision> undo = new ArrayDeque<>();
    private Prefs prefs;
    private Trash trash;
    private PhotoCleaner.Report report;
    private boolean scanning;
    private int trashedCount;
    private long trashedBytes;

    private List<Card> queue = new ArrayList<>();
    private int index;
    private Kind reviewing;

    private View summaryBox, reviewBox, scanBox;
    private LinearLayout results;
    private TextView intro, scanText, trashedText, rTitle, rInfo, stampKeep, stampTrash;
    private LinearProgressIndicator bar;
    private ImageView image, best;
    private MaterialToolbar toolbar;
    private MaterialButton rescan, undoBtn;
    private int generation;     // so a slow image load for an earlier card can't land on a later one

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_swipe_clean);
        prefs = new Prefs(this);
        trash = new Trash(Environment.getExternalStorageDirectory());
        toolbar = findViewById(R.id.toolbar);
        summaryBox = findViewById(R.id.summaryBox);
        reviewBox = findViewById(R.id.reviewBox);
        scanBox = findViewById(R.id.sScanBox);
        results = findViewById(R.id.sResults);
        intro = findViewById(R.id.sIntro);
        scanText = findViewById(R.id.sScanText);
        trashedText = findViewById(R.id.sTrashed);
        bar = findViewById(R.id.sBar);
        rescan = findViewById(R.id.sRescan);
        rTitle = findViewById(R.id.rTitle);
        rInfo = findViewById(R.id.rInfo);
        image = findViewById(R.id.rImage);
        best = findViewById(R.id.rBest);
        stampKeep = findViewById(R.id.rStampKeep);
        stampTrash = findViewById(R.id.rStampTrash);
        undoBtn = findViewById(R.id.rUndo);

        toolbar.setNavigationOnClickListener(x -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (reviewing != null) {
                    endReview();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        findViewById(R.id.sStop).setOnClickListener(x -> cancelScan.set(true));
        rescan.setOnClickListener(x -> scan());
        findViewById(R.id.rKeep).setOnClickListener(x -> decide(false));
        findViewById(R.id.rTrash).setOnClickListener(x -> decide(true));
        undoBtn.setOnClickListener(x -> undoLast());
        installSwipe();
        scan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelScan.set(true);
        io.shutdownNow();
    }

    private void leave() {
        if (reviewing != null) endReview(); else finish();
    }

    // ------------------------------------------------------------------ scanning

    private void scan() {
        if (scanning) return;
        if (!Access.allFiles()) {
            intro.setText("Allow file access in the Files tab first, then come back.");
            rescan.setVisibility(View.GONE);
            return;
        }
        scanning = true;
        cancelScan.set(false);
        scanBox.setVisibility(View.VISIBLE);
        results.removeAllViews();
        rescan.setVisibility(View.GONE);
        scanText.setText("Starting…");
        io.execute(() -> {
            File root = Environment.getExternalStorageDirectory();
            Backup.Options o = new Backup.Options();
            o.folders.add(new File(root, "DCIM"));
            o.folders.add(new File(root, "Pictures"));
            o.includeVideos = false;
            List<File> files = Backup.scan(o, System.currentTimeMillis());
            PhotoCleaner.Cache cache = new PhotoCleaner.Cache(new File(getFilesDir(), "photo_stats.tsv"));
            final PhotoCleaner.Report r = PhotoCleaner.scan(files, decoder, cache, new PhotoCleaner.Progress() {
                private long last;
                @Override public void step(final int done, final int total) {
                    long now = System.currentTimeMillis();
                    if (now - last < 150 && done < total) return;
                    last = now;
                    ui.post(() -> scanText.setText("Looking at your photos… " + done + " of " + total));
                }

                @Override public boolean cancelled() {
                    return cancelScan.get();
                }
            }, System.currentTimeMillis());
            ui.post(() -> {
                if (isDestroyed()) return;
                scanning = false;
                report = r;
                scanBox.setVisibility(View.GONE);
                showResults();
            });
        });
    }

    private boolean skip(PhotoAnalysis.Stat s) {
        return handled.contains(s.path) || prefs.cleanKept().contains(PhotoCleaner.Cache.key(s.path, s.size, s.modified)) || !s.file().isFile();
    }

    private List<Card> cardsFor(Kind k) {
        List<Card> out = new ArrayList<>();
        if (report == null) return out;
        long now = System.currentTimeMillis();
        switch (k) {
            case SIMILAR:
                for (PhotoAnalysis.Group g : report.similar) {
                    if (skip(g.best())) continue;
                    for (PhotoAnalysis.Stat s : g.extras()) {
                        if (!skip(s)) out.add(new Card(s, g.best(), "Looks like " + g.best().file().getName() + ", which is the better one"));
                    }
                }
                break;
            case BLURRY:
                for (PhotoAnalysis.Stat s : report.blurry) if (!skip(s)) out.add(new Card(s, null, "Looks blurry"));
                break;
            case DARK:
                for (PhotoAnalysis.Stat s : report.dark) if (!skip(s)) out.add(new Card(s, null, "Almost completely dark"));
                break;
            default:
                for (PhotoAnalysis.Stat s : report.oldScreenshots) {
                    if (!skip(s)) out.add(new Card(s, null, "Screenshot from " + Fmt.date(s.modified) + " (" + Fmt.ago(s.modified, now) + ")"));
                }
        }
        return out;
    }

    private void showResults() {
        reviewing = null;
        reviewBox.setVisibility(View.GONE);
        summaryBox.setVisibility(View.VISIBLE);
        toolbar.setTitle("Clean up photos");
        results.removeAllViews();
        rescan.setVisibility(View.VISIBLE);
        if (report.cancelled) intro.setText("Stopped early. This is what was found so far.");
        else intro.setText("Looked at " + Fmt.plural(report.all.size(), "photo", "photos")
                + (report.unreadable > 0 ? " (" + report.unreadable + " couldn't be read)" : "")
                + ". Swipe left to move a photo to the Trash, right to keep it. Nothing is deleted for good.");
        addRow(Kind.SIMILAR, "Near-copies", R.drawable.ic_copy, R.color.kind_image, "extra copies of the same scene, bursts and re-saved pictures");
        addRow(Kind.BLURRY, "Blurry photos", R.drawable.ic_image, R.color.kind_video, "out of focus or shaken");
        addRow(Kind.DARK, "Black frames", R.drawable.ic_camera, R.color.kind_other, "pocket shots and lens-cap accidents");
        addRow(Kind.SCREENSHOTS, "Old screenshots", R.drawable.ic_phone, R.color.kind_archive, "more than three months old");
        trashedText.setText(trashedCount == 0 ? "" : "Moved to the Trash so far: " + Fmt.plural(trashedCount, "photo", "photos")
                + " (" + Fmt.bytes(trashedBytes) + "). They stay there for 30 days. Empty the Trash to free the space now.");
    }

    private void addRow(final Kind kind, String title, int icon, int color, String what) {
        List<Card> cards = cardsFor(kind);
        long bytes = 0;
        for (Card c : cards) bytes += c.stat.size;
        View row = LayoutInflater.from(this).inflate(R.layout.item_clean_row, results, false);
        ImageView ic = row.findViewById(R.id.cIcon);
        int c = getColor(color);
        ic.setImageResource(icon);
        ic.setColorFilter(c);
        ic.setBackground(Kinds.badge(c, (int) (12 * getResources().getDisplayMetrics().density), false));
        ((TextView) row.findViewById(R.id.cTitle)).setText(title);
        ((TextView) row.findViewById(R.id.cSub)).setText(cards.isEmpty() ? "None found" : Fmt.plural(cards.size(), "photo", "photos") + "  ·  " + Fmt.bytes(bytes) + "\n" + what);
        MaterialButton b = row.findViewById(R.id.cReview);
        b.setEnabled(!cards.isEmpty());
        b.setOnClickListener(x -> startReview(kind));
        results.addView(row);
    }

    // ------------------------------------------------------------------ reviewing

    private void startReview(Kind kind) {
        queue = cardsFor(kind);
        if (queue.isEmpty()) return;
        reviewing = kind;
        index = 0;
        summaryBox.setVisibility(View.GONE);
        reviewBox.setVisibility(View.VISIBLE);
        showCard();
    }

    private void endReview() {
        reviewing = null;
        generation++;
        showResults();
    }

    private String titleOf(Kind k) {
        switch (k) {
            case SIMILAR: return "Near-copies";
            case BLURRY: return "Blurry photos";
            case DARK: return "Black frames";
            default: return "Old screenshots";
        }
    }

    private void showCard() {
        if (index >= queue.size()) {
            Snackbar.make(findViewById(R.id.toolbar), "That's all of them in this group.", 4000).show();
            endReview();
            return;
        }
        final Card card = queue.get(index);
        toolbar.setTitle(titleOf(reviewing));
        rTitle.setText((index + 1) + " of " + queue.size());
        rInfo.setText(card.stat.file().getName() + "  ·  " + Fmt.bytes(card.stat.size) + "  ·  " + Fmt.date(card.stat.modified) + "\n" + card.reason);
        undoBtn.setEnabled(!undo.isEmpty());
        resetCardView();
        image.setImageDrawable(null);
        best.setVisibility(card.keeping == null ? View.GONE : View.VISIBLE);
        final int gen = ++generation;
        loadInto(card.stat.file(), 1400, image, gen);
        if (card.keeping != null) loadInto(card.keeping.file(), 300, best, gen);
    }

    private void loadInto(final File f, final int maxSide, final ImageView target, final int gen) {
        try {
            io.execute(() -> {
                final Bitmap b = decodeForScreen(f, maxSide);
                ui.post(() -> {
                    if (isDestroyed() || gen != generation) {
                        if (b != null) b.recycle();
                        return;
                    }
                    if (b != null) target.setImageBitmap(b);
                    else target.setImageResource(R.drawable.ic_image);
                });
            });
        } catch (RuntimeException ignored) { }
    }

    static Bitmap decodeForScreen(File f, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        try {
            int orientation = new android.media.ExifInterface(f.getPath()).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            int degrees = orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                return r;
            }
        } catch (IOException ignored) { }
        return b;
    }

    /** Applies the choice for the photo on screen. */
    void decide(final boolean moveToTrash) {
        if (reviewing == null || index >= queue.size()) return;
        final Card card = queue.get(index);
        if (moveToTrash) {
            long size = card.stat.size;
            try {
                Trash.Entry e = trash.trash(card.stat.file());
                undo.push(new Decision(card, e));
                trashedCount++;
                trashedBytes += size;
                handled.add(card.stat.path);
            } catch (IOException e) {
                Snackbar.make(findViewById(R.id.toolbar), "Couldn't move it: " + e.getMessage(), 5000).show();
                resetCardView();
                return;
            }
        } else {
            Set<String> kept = prefs.cleanKept();
            kept.add(PhotoCleaner.Cache.key(card.stat.path, card.stat.size, card.stat.modified));
            prefs.setCleanKept(kept);
            undo.push(new Decision(card, null));
            handled.add(card.stat.path);
        }
        index++;
        showCard();
    }

    private void undoLast() {
        if (undo.isEmpty() || reviewing == null) return;
        Decision d = undo.pop();
        if (d.trashed != null) {
            try {
                trash.restore(d.trashed);
                trashedCount--;
                trashedBytes -= d.card.stat.size;
            } catch (IOException e) {
                Snackbar.make(findViewById(R.id.toolbar), "Couldn't put it back: " + e.getMessage(), 5000).show();
                undo.push(d);
                return;
            }
        } else {
            Set<String> kept = prefs.cleanKept();
            kept.remove(PhotoCleaner.Cache.key(d.card.stat.path, d.card.stat.size, d.card.stat.modified));
            prefs.setCleanKept(kept);
        }
        handled.remove(d.card.stat.path);
        int at = queue.indexOf(d.card);
        index = at >= 0 ? at : Math.max(0, index - 1);
        showCard();
    }

    // ------------------------------------------------------------------ swiping

    private void resetCardView() {
        image.setTranslationX(0);
        image.setRotation(0);
        stampKeep.setAlpha(0);
        stampTrash.setAlpha(0);
    }

    private void installSwipe() {
        image.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private boolean dragging;

            @Override public boolean onTouch(View v, MotionEvent e) {
                float width = Math.max(1, v.getWidth());
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        dragging = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) return false;
                        float dx = e.getRawX() - downX;
                        v.setTranslationX(dx);
                        v.setRotation(dx / width * 14);
                        float p = Math.min(1f, Math.abs(dx) / (width * 0.3f));
                        stampTrash.setAlpha(dx < 0 ? p : 0);
                        stampKeep.setAlpha(dx > 0 ? p : 0);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        float moved = e.getRawX() - downX;
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(moved) > width * 0.3f) {
                            final boolean toTrash = moved < 0;
                            v.animate().translationX(moved < 0 ? -width * 1.2f : width * 1.2f).setDuration(140)
                                    .withEndAction(() -> decide(toTrash)).start();
                        } else {
                            v.animate().translationX(0).rotation(0).setDuration(120).start();
                            stampKeep.setAlpha(0);
                            stampTrash.setAlpha(0);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }
}
