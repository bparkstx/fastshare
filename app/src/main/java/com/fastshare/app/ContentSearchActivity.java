package com.fastshare.app;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Search for words inside photos and documents, and for what photos show. Everything is read on this phone. */
public class ContentSearchActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    /** Tests give it a recognizer that doesn't need Google's models. */
    static volatile ContentIndexer.Recognizer testRecognizer;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService searcher = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private ContentIndex index;
    private RowAdapter adapter;
    private EditText query;
    private TextView status, empty;
    private LinearProgressIndicator busy;
    private final Runnable runSearch = this::search;
    private boolean indexing;
    private String indexNote = "";
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_content_search);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(x -> finish());
        query = findViewById(R.id.query);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        busy = findViewById(R.id.busy);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, new HashSet<String>(), this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        index = new ContentIndex(new File(getFilesDir(), "content_index.tsv"));
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                ui.removeCallbacks(runSearch);
                ui.postDelayed(runSearch, 250);
            }
        });
        query.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                ui.removeCallbacks(runSearch);
                search();
                return true;
            }
            return false;
        });
        render();
        if (Access.allFiles()) {
            startIndexing();
        } else {
            empty.setText("Allow file access in the Files tab first, then come back.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel.set(true);
        io.shutdownNow();
        searcher.shutdownNow();
    }

    // ------------------------------------------------------------------ reading the files

    private ContentIndexer.Recognizer recognizer() {
        if (testRecognizer != null) return testRecognizer;
        try {
            return new MlKitRecognizer();
        } catch (Throwable t) {
            return null;
        }
    }

    private void startIndexing() {
        if (indexing) return;
        indexing = true;
        cancel.set(false);
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            File root = Environment.getExternalStorageDirectory();
            List<File> folders = new ArrayList<>();
            for (String n : new String[]{"DCIM", "Pictures", "Download", "Documents"}) folders.add(new File(root, n));
            List<File> files = ContentIndexer.candidates(folders, 20_000);
            final ContentIndexer.Recognizer rec = recognizer();
            final ContentIndexer.Recognizer use = rec != null ? rec : new ContentIndexer.Recognizer() {
                @Override public ContentIndexer.Result recognize(File f) throws java.io.IOException {
                    throw new java.io.IOException("Reading pictures isn't available on this phone");
                }
            };
            final ContentIndexer.Summary s = ContentIndexer.run(files, use, index, new ContentIndexer.Progress() {
                private long last;
                @Override public void step(final int done, final int total) {
                    long now = System.currentTimeMillis();
                    if (now - last < 300 && done < total) return;
                    last = now;
                    ui.post(() -> {
                        indexNote = "Reading your photos and documents… " + done + " of " + total;
                        render();
                    });
                }

                @Override public boolean cancelled() {
                    return cancel.get();
                }
            });
            ui.post(() -> {
                if (isDestroyed()) return;
                indexing = false;
                busy.setVisibility(View.GONE);
                indexNote = rec == null ? "Reading pictures isn't available on this phone, so only documents and text files can be searched." : "";
                render();
                search();
            });
        });
    }

    private void render() {
        String note = indexNote;
        status.setText(note.isEmpty() ? Fmt.plural(index.size(), "file", "files") + " searchable. Only new files are read next time." : note);
        if (adapter.getItemCount() == 0 && Access.allFiles()) {
            empty.setText(query.getText().toString().trim().isEmpty()
                    ? "Type a word to find it inside your photos and documents.\n\nIt can also find what a photo shows, like “dog”, “beach” or “food”."
                    : "Nothing matches that" + (indexing ? " yet. Still reading your files." : "."));
        }
        empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ searching

    private void search() {
        final String q = query.getText().toString().trim();
        final int gen = ++generation;
        if (q.isEmpty()) {
            adapter.setRows(new ArrayList<RowAdapter.Row>());
            render();
            return;
        }
        try {
            searcher.execute(() -> {
                final List<ContentIndex.Hit> hits = index.search(q, 200);
                ui.post(() -> {
                    if (isDestroyed() || gen != generation) return;
                    List<RowAdapter.Row> rows = new ArrayList<>();
                    for (ContentIndex.Hit h : hits) {
                        File f = h.entry.file();
                        FileOps.Kind kind = FileOps.kindOfName(f.getName());
                        StringBuilder sub = new StringBuilder();
                        if (!h.snippet.isEmpty()) sub.append(h.snippet);
                        else if (!h.entry.labels.isEmpty()) sub.append("Shows: ").append(String.join(", ", h.entry.labels));
                        sub.append(sub.length() > 0 ? "\n" : "").append(Fmt.bytes(h.entry.size)).append("  ·  ").append(Fmt.date(h.entry.modified));
                        RowAdapter.Row row = RowAdapter.Row.item(f.getPath(), f.getName(), sub.toString(), kind);
                        if (kind == FileOps.Kind.IMAGE) row.thumb(f);
                        rows.add(row);
                    }
                    adapter.setRows(rows);
                    render();
                    if (!rows.isEmpty()) status.setText(Fmt.plural(rows.size(), "file matches", "files match") + (indexing ? "  ·  still reading…" : ""));
                });
            });
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public void onClick(RowAdapter.Row row) {
        File f = new File(row.id);
        if (!f.isFile()) return;
        Viewer.open(this, f, null);
    }

    @Override
    public void onLongClick(RowAdapter.Row row) {
        File f = new File(row.id);
        if (f.getParentFile() == null) return;
        Nav.dir = f.getParentFile();
        finish();
    }
}
