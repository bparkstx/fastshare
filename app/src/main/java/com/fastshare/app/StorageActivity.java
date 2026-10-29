package com.fastshare.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Where did my space go? Totals by type, the biggest files, duplicates, and leftovers to clear. */
public class StorageActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    static final String EXTRA_ROOT = "root";

    private enum Mode { OVERVIEW, LARGE, DUPLICATES, CLEANUP }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, File> fileOf = new HashMap<>();
    private final Map<String, Analyzer.Junk> junkOf = new HashMap<>();
    private final AtomicBoolean cancel = new AtomicBoolean();

    private File root;
    private Mode mode = Mode.OVERVIEW;
    private Analyzer.Result result;
    private List<Analyzer.DupGroup> dups;
    private boolean scanning;
    private long lastProgressUi;

    private MaterialToolbar toolbar;
    private RowAdapter adapter;
    private View busy;
    private TextView info;
    private UsageBar usageBar;
    private View empty;
    private TextView emptyText;
    private View bottomBar;
    private TextView bottomText;
    private MaterialButton bottomButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_list);
        String r = getIntent().getStringExtra(EXTRA_ROOT);
        root = r == null ? Volumes.primary() : new File(r);

        toolbar = findViewById(R.id.toolbar);
        busy = findViewById(R.id.busy);
        info = findViewById(R.id.info);
        usageBar = findViewById(R.id.usageBar);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.emptyText);
        bottomBar = findViewById(R.id.bottomBar);
        bottomText = findViewById(R.id.bottomText);
        bottomButton = findViewById(R.id.bottomButton);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, selected, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        MaterialButtonToggleGroup group = findViewById(R.id.modeGroup);
        group.setVisibility(View.VISIBLE);
        String[] names = {"Overview", "Large files", "Duplicates", "Cleanup"};
        int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(View.generateViewId());
            b.setText(names[i]);
            b.setTextSize(12);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setPadding(dp(4), 0, dp(4), 0);
            group.addView(b, new MaterialButtonToggleGroup.LayoutParams(0, dp(44), 1f));
            ids[i] = b.getId();
        }
        final int[] modeIds = ids;
        group.check(modeIds[0]);
        group.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            for (int i = 0; i < modeIds.length; i++) if (modeIds[i] == id) mode = Mode.values()[i];
            selected.clear();
            render();
        });

        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(x -> finish());
        toolbar.setOnMenuItemClickListener(this::onMenu);
        bottomButton.setOnClickListener(x -> confirmRemove());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!selected.isEmpty()) {
                    selected.clear();
                    render();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel.set(true);
        io.shutdownNow();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ scanning

    private void startScan() {
        cancel.set(true);                     // stop any scan still running
        final AtomicBoolean mine = new AtomicBoolean();
        cancel.set(false);
        result = null;
        dups = null;
        selected.clear();
        scanning = true;
        info.setText("Scanning…");
        render();
        final File scanRoot = root;
        io.execute(() -> {
            final Analyzer.Result r = Analyzer.scan(scanRoot, (n, where) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUi < 150) return;
                lastProgressUi = now;
                final String msg = String.format(Locale.US, "Scanning… %,d files  ·  %s", n, where);
                ui.post(() -> {
                    if (scanning && !isDestroyed()) info.setText(msg);
                });
            }, cancel);
            ui.post(() -> {
                if (isDestroyed() || cancel.get()) return;
                result = r;
                scanning = false;
                render();
            });
        });
    }

    private void findDuplicates() {
        final Analyzer.Result r = result;
        if (r == null || dups != null || scanning) return;
        scanning = true;
        info.setText("Comparing files…");
        render();
        io.execute(() -> {
            final List<Analyzer.DupGroup> found = Analyzer.findDuplicates(r, (n, name) -> {
                long now = System.currentTimeMillis();
                if (now - lastProgressUi < 150) return;
                lastProgressUi = now;
                ui.post(() -> {
                    if (scanning && !isDestroyed()) info.setText("Comparing files… " + n + " checked");
                });
            }, cancel);
            ui.post(() -> {
                if (isDestroyed() || result != r) return;
                dups = found;
                scanning = false;
                render();
            });
        });
    }

    // ------------------------------------------------------------------ rendering

    private static String kindName(FileOps.Kind k) {
        switch (k) {
            case IMAGE: return "Photos";
            case VIDEO: return "Videos";
            case AUDIO: return "Audio";
            case DOCUMENT: return "Documents";
            case TEXT: return "Text files";
            case ARCHIVE: return "Archives";
            case APK: return "Installers (.apk)";
            default: return "Everything else";
        }
    }

    private String rel(File f) {
        String p = f.getParent() == null ? "" : f.getParent();
        if (p.startsWith(root.getPath())) p = p.substring(root.getPath().length());
        return p.isEmpty() ? Volumes.label(this, root) : p.startsWith("/") ? p.substring(1) : p;
    }

    private void render() {
        fileOf.clear();
        junkOf.clear();
        List<RowAdapter.Row> rows = new ArrayList<>();
        usageBar.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        busy.setVisibility(scanning ? View.VISIBLE : View.GONE);
        info.setVisibility(View.VISIBLE);
        toolbar.setTitle("Storage");
        toolbar.setSubtitle(Volumes.roots(this).size() > 1 ? Volumes.label(this, root) : null);

        if (result == null) {
            adapter.setRows(rows);
            bottomBar.setVisibility(View.GONE);
            updateMenu();
            return;
        }
        switch (mode) {
            case OVERVIEW: renderOverview(rows); break;
            case LARGE: renderLarge(rows); break;
            case DUPLICATES: renderDuplicates(rows); break;
            default: renderCleanup(rows);
        }
        adapter.setRows(rows);
        if (rows.isEmpty() && !scanning) {
            empty.setVisibility(View.VISIBLE);
            emptyText.setText(mode == Mode.DUPLICATES ? "No duplicate files found." : mode == Mode.CLEANUP
                    ? "Nothing to clean up. You're tidy." : "Nothing to show.");
        }
        updateBottomBar();
        updateMenu();
    }

    private void renderOverview(List<RowAdapter.Row> rows) {
        Analyzer.Result r = result;
        String free = "";
        try {
            StatFs fs = new StatFs(root.getPath());
            if (fs.getTotalBytes() > 0) free = "  ·  " + Fmt.bytes(fs.getAvailableBytes()) + " free of " + Fmt.bytes(fs.getTotalBytes());
        } catch (Exception ignored) { }
        info.setText(Fmt.bytes(r.totalBytes) + " in " + String.format(Locale.US, "%,d", r.fileCount) + (r.fileCount == 1 ? " file" : " files") + free);

        List<Map.Entry<FileOps.Kind, Long>> kinds = new ArrayList<>(r.bytesByKind.entrySet());
        Collections.sort(kinds, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        long[] values = new long[kinds.size()];
        int[] colors = new int[kinds.size()];
        for (int i = 0; i < kinds.size(); i++) {
            values[i] = kinds.get(i).getValue();
            colors[i] = Kinds.color(this, kinds.get(i).getKey());
        }
        usageBar.setSegments(values, colors, ContextCompat.getColor(this, R.color.fs_line));
        usageBar.setVisibility(r.totalBytes > 0 ? View.VISIBLE : View.GONE);

        rows.add(RowAdapter.Row.header("BY TYPE"));
        for (Map.Entry<FileOps.Kind, Long> e : kinds) {
            if (e.getValue() <= 0) continue;
            Integer count = r.countByKind.get(e.getKey());
            long pct = r.totalBytes == 0 ? 0 : e.getValue() * 100 / r.totalBytes;
            rows.add(RowAdapter.Row.item("kind:" + e.getKey(), kindName(e.getKey()),
                    Fmt.bytes(e.getValue()) + "  ·  " + pct + "%" + (count == null ? "" : "  ·  " + String.format(Locale.US, "%,d", count) + (count == 1 ? " file" : " files")),
                    e.getKey()).tag(e.getKey()));
        }
        if (!r.folders.isEmpty()) {
            rows.add(RowAdapter.Row.header("BIGGEST FOLDERS"));
            for (int i = 0; i < Math.min(8, r.folders.size()); i++) {
                Analyzer.FolderSize fsz = r.folders.get(i);
                if (fsz.size <= 0) continue;
                RowAdapter.Row row = RowAdapter.Row.item("folder:" + fsz.folder.getPath(), fsz.folder.getName(), Fmt.bytes(fsz.size), FileOps.Kind.FOLDER);
                row.tag(fsz.folder);
                rows.add(row);
            }
        }
        if (r.trashBytes > 0) {
            rows.add(RowAdapter.Row.header("RECYCLE BIN"));
            rows.add(RowAdapter.Row.item("trash", "Trash", Fmt.bytes(r.trashBytes) + "  ·  tap to open", FileOps.Kind.OTHER)
                    .icon(R.drawable.ic_delete, ContextCompat.getColor(this, R.color.kind_other)));
        }
    }

    private void renderLarge(List<RowAdapter.Row> rows) {
        info.setText("The " + result.largest.size() + " biggest files on " + Volumes.label(this, root));
        for (FileOps.Item it : result.largest) {
            String id = it.file.getPath();
            fileOf.put(id, it.file);
            rows.add(RowAdapter.Row.item(id, it.name, Fmt.bytes(it.size) + "  ·  " + rel(it.file), it.kind).thumb(it.file));
        }
    }

    private void renderDuplicates(List<RowAdapter.Row> rows) {
        if (dups == null) {
            if (!scanning) findDuplicates();
            return;
        }
        long wasted = 0;
        for (Analyzer.DupGroup g : dups) wasted += g.wasted();
        info.setText(dups.isEmpty() ? "Checked files over " + Fmt.bytes(Analyzer.DUPLICATE_MIN_BYTES)
                : Fmt.plural(dups.size(), "set", "sets") + " of identical files  ·  " + Fmt.bytes(wasted) + " could be freed");
        for (Analyzer.DupGroup g : dups) {
            rows.add(RowAdapter.Row.header(g.files.size() + " identical copies  ·  " + Fmt.bytes(g.size) + " each"));
            for (File f : g.files) {
                String id = f.getPath();
                fileOf.put(id, f);
                rows.add(RowAdapter.Row.item(id, f.getName(), rel(f) + "  ·  " + Fmt.date(f.lastModified()),
                        FileOps.kindOfName(f.getName())).thumb(f));
            }
        }
    }

    private void renderCleanup(List<RowAdapter.Row> rows) {
        info.setText(result.junk.isEmpty() ? "Nothing found" : Fmt.bytes(result.junkBytes()) + " of leftovers found. Tick what to clear.");
        for (Analyzer.JunkKind kind : Analyzer.JunkKind.values()) {
            long total = 0;
            List<Analyzer.Junk> items = new ArrayList<>();
            for (Analyzer.Junk j : result.junk) {
                if (j.kind == kind) {
                    items.add(j);
                    total += j.size;
                }
            }
            if (items.isEmpty()) continue;
            rows.add(RowAdapter.Row.header(kind.title.toUpperCase(Locale.ROOT) + "  ·  " + Fmt.bytes(total)));
            for (Analyzer.Junk j : items) {
                String id = j.file.getPath();
                fileOf.put(id, j.file);
                junkOf.put(id, j);
                rows.add(RowAdapter.Row.item(id, j.file.getName(), (j.size > 0 ? Fmt.bytes(j.size) + "  ·  " : "") + rel(j.file),
                        j.file.isDirectory() ? FileOps.Kind.FOLDER : FileOps.kindOfName(j.file.getName())));
            }
        }
    }

    private long selectedBytes() {
        long total = 0;
        for (String id : selected) {
            Analyzer.Junk j = junkOf.get(id);
            File f = fileOf.get(id);
            if (j != null) total += j.size;
            else if (f != null) total += f.length();
        }
        return total;
    }

    private void updateBottomBar() {
        boolean show = mode != Mode.OVERVIEW && !selected.isEmpty();
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        bottomText.setText(selected.size() + " selected  ·  " + Fmt.bytes(selectedBytes()));
        bottomButton.setText(mode == Mode.CLEANUP ? "Clean up" : "Move to trash");
    }

    private void updateMenu() {
        Menu m = toolbar.getMenu();
        m.clear();
        toolbar.inflateMenu(R.menu.storage_main);
        m.findItem(R.id.action_select_extras).setVisible(mode == Mode.DUPLICATES && dups != null && !dups.isEmpty());
        m.findItem(R.id.action_select_all).setVisible(mode != Mode.OVERVIEW);
        m.findItem(R.id.action_select_none).setVisible(mode != Mode.OVERVIEW && !selected.isEmpty());
        List<File> roots = Volumes.roots(this);
        for (int i = 0; i < roots.size(); i++) {
            if (!roots.get(i).equals(root)) m.add(Menu.NONE, 1000 + i, Menu.NONE, "Scan " + Volumes.label(this, roots.get(i)));
        }
    }

    private boolean onMenu(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.action_rescan) {
            startScan();
        } else if (id == R.id.action_select_all) {
            selected.addAll(fileOf.keySet());
            render();
        } else if (id == R.id.action_select_none) {
            selected.clear();
            render();
        } else if (id == R.id.action_select_extras) {
            selected.clear();
            for (File f : Analyzer.extraCopies(dups)) selected.add(f.getPath());
            render();
        } else if (id >= 1000) {
            root = Volumes.roots(this).get(id - 1000);
            startScan();
        } else {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public void onClick(RowAdapter.Row row) {
        if (mode == Mode.OVERVIEW) {
            if (row.tag instanceof FileOps.Kind) {
                Nav.category = categoryOf((FileOps.Kind) row.tag);
                if (Nav.category != null) finish();
            } else if (row.tag instanceof File) {
                Nav.dir = (File) row.tag;
                finish();
            } else if ("trash".equals(row.id)) {
                startActivity(new android.content.Intent(this, TrashActivity.class));
            }
            return;
        }
        if (row.header) return;
        if (mode == Mode.CLEANUP || !selected.isEmpty()) {
            toggle(row);
            return;
        }
        File f = fileOf.get(row.id);
        if (f != null) Viewer.open(this, f, null);
    }

    @Override
    public void onLongClick(RowAdapter.Row row) {
        if (mode != Mode.OVERVIEW && !row.header) toggle(row);
    }

    private void toggle(RowAdapter.Row row) {
        if (!selected.remove(row.id)) selected.add(row.id);
        adapter.notifyDataSetChanged();
        updateBottomBar();
        updateMenu();
    }

    private static MediaQuery.Category categoryOf(FileOps.Kind k) {
        switch (k) {
            case IMAGE: return MediaQuery.Category.IMAGES;
            case VIDEO: return MediaQuery.Category.VIDEOS;
            case AUDIO: return MediaQuery.Category.AUDIO;
            case DOCUMENT: case TEXT: return MediaQuery.Category.DOCUMENTS;
            case ARCHIVE: return MediaQuery.Category.ARCHIVES;
            case APK: return MediaQuery.Category.APPS;
            default: return null;
        }
    }

    private void confirmRemove() {
        if (selected.isEmpty()) return;
        final boolean clean = mode == Mode.CLEANUP;
        String what = Fmt.plural(selected.size(), "item", "items") + " (" + Fmt.bytes(selectedBytes()) + ")";
        new MaterialAlertDialogBuilder(this)
                .setTitle(clean ? "Clear " + what + "?" : "Move " + what + " to the trash?")
                .setMessage(clean ? "Thumbnail caches and empty folders are deleted for good. Everything else goes to the trash first, so you can still get it back."
                        : "You can restore them from the Trash for 30 days.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(clean ? "Clean up" : "Move to trash", (d, w) -> remove())
                .show();
    }

    private void remove() {
        final List<String> ids = new ArrayList<>(selected);
        final List<File> roots = Volumes.roots(this);
        final Mode m = mode;
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            final List<Trash> owners = new ArrayList<>();
            final List<Trash.Entry> entries = new ArrayList<>();
            final Set<String> gone = new HashSet<>();
            long freed = 0;
            for (String id : ids) {
                File f = fileOf.get(id);
                if (f == null || !f.exists()) {
                    gone.add(id);
                    continue;
                }
                Analyzer.Junk j = junkOf.get(id);
                boolean wasFolder = f.isDirectory();
                long size = j != null ? j.size : wasFolder ? FileOps.sizeOf(f, null) : f.length();
                try {
                    if (m == Mode.CLEANUP && j != null && j.kind.permanent) {
                        if (!FileOps.delete(f)) continue;
                    } else {
                        Trash t = new Trash(Volumes.rootOf(roots, f));
                        entries.add(t.trash(f));
                        owners.add(t);
                    }
                    gone.add(id);
                    result.forget(f, size, wasFolder);
                    freed += size;
                } catch (Exception ignored) { }
            }
            final long freedBytes = freed;
            ui.post(() -> {
                if (isDestroyed()) return;
                result.largest.removeIf(it -> gone.contains(it.file.getPath()));
                result.junk.removeIf(j -> gone.contains(j.file.getPath()));
                if (dups != null) {
                    for (Analyzer.DupGroup g : dups) g.files.removeIf(f -> gone.contains(f.getPath()));
                    dups.removeIf(g -> g.files.size() < 2);
                }
                selected.clear();
                render();
                Snackbar sb = Snackbar.make(findViewById(R.id.list), "Freed " + Fmt.bytes(freedBytes), 8000);
                if (!entries.isEmpty()) {
                    sb.setAction("Undo", v -> io.execute(() -> {
                        for (int i = 0; i < entries.size(); i++) {
                            try {
                                owners.get(i).restore(entries.get(i));
                            } catch (Exception ignored) { }
                        }
                        ui.post(() -> {
                            if (!isDestroyed()) startScan();
                        });
                    }));
                }
                sb.show();
            });
        });
    }
}
