package com.fastshare.app;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Browse a saved network location like a folder on this phone: open, download, upload, rename, delete. */
public class RemoteActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    static final String EXTRA_ID = "id";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Remote.Item> byPath = new LinkedHashMap<>();

    private Connections store;
    private Connection conn;
    private volatile Remote remote;
    private String path = "/";

    private MaterialToolbar toolbar;
    private RowAdapter adapter;
    private View busy;
    private View empty;
    private TextView emptyText;
    private TextView info;
    private View bottomBar;
    private TextView bottomText;
    private com.google.android.material.button.MaterialButton bottomButton;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::uploadUris);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_list);
        store = RemoteSession.store(this);
        conn = store.get(getIntent().getStringExtra(EXTRA_ID));
        if (conn == null) {
            finish();
            return;
        }
        toolbar = findViewById(R.id.toolbar);
        busy = findViewById(R.id.busy);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.emptyText);
        info = findViewById(R.id.info);
        bottomBar = findViewById(R.id.bottomBar);
        bottomText = findViewById(R.id.bottomText);
        bottomButton = findViewById(R.id.bottomButton);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, selected, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        toolbar.setNavigationOnClickListener(x -> goBack());
        toolbar.setOnMenuItemClickListener(this::onMenu);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!goBack()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        FileOps.delete(new File(getCacheDir(), "remote_open"));
        updateToolbar();
        updateUploadBar();
        connectAndLoad();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        final Remote r = remote;
        remote = null;
        if (r != null) {
            new Thread(() -> {
                try { r.close(); } catch (IOException ignored) { }
            }).start();
        }
    }

    /** Selection first, then up one folder. Returns false when there is nothing left to go back to. */
    private boolean goBack() {
        if (!selected.isEmpty()) {
            clearSelection();
            return true;
        }
        if (!path.equals("/")) {
            open(Remote.Paths.parent(path));
            return true;
        }
        if (!RemoteSession.pendingUploads.isEmpty()) RemoteSession.pendingUploads.clear();
        finish();
        return true;
    }

    // ------------------------------------------------------------------ connecting and listing

    private void connectAndLoad() {
        busy.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        info.setVisibility(View.VISIBLE);
        info.setText("Connecting to " + (conn.name.isEmpty() ? conn.host : conn.name) + "…");
        io.execute(() -> {
            Remote r = null;
            String error = null;
            try {
                Connection c = conn.copy();
                r = Remotes.open(c);
                if (!c.hostKey.equals(conn.hostKey)) {          // first meeting with an SFTP server: remember its key
                    conn.hostKey = c.hostKey;
                    store.save(conn);
                }
            } catch (IOException e) {
                error = e.getMessage();
            }
            final Remote opened = r;
            final String err = error;
            ui.post(() -> {
                if (isDestroyed()) {
                    if (opened != null) try { opened.close(); } catch (IOException ignored) { }
                    return;
                }
                if (opened == null) {
                    fail(err);
                    return;
                }
                remote = opened;
                open(path);
            });
        });
    }

    private void fail(String message) {
        busy.setVisibility(View.GONE);
        info.setVisibility(View.GONE);
        adapter.setRows(new ArrayList<RowAdapter.Row>());
        emptyText.setText(message == null ? "Couldn't connect." : message + "\n\nTap the ⋮ menu and choose Refresh to try again.");
        empty.setVisibility(View.VISIBLE);
        updateToolbar();
    }

    /** Lists a folder. If the connection went stale, reconnects once and tries again. */
    private void open(final String target) {
        selected.clear();
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            List<Remote.Item> items = null;
            String error = null;
            for (int attempt = 0; attempt < 2 && items == null; attempt++) {
                try {
                    Remote r = remote;
                    if (r == null) {
                        r = Remotes.open(conn.copy());
                        remote = r;
                    }
                    items = r.list(target);
                } catch (IOException e) {
                    error = e.getMessage();
                    Remote stale = remote;
                    remote = null;
                    if (stale != null) try { stale.close(); } catch (IOException ignored) { }
                }
            }
            final List<Remote.Item> found = items;
            final String err = error;
            ui.post(() -> {
                if (isDestroyed()) return;
                busy.setVisibility(View.GONE);
                if (found == null) {
                    if (remote == null && path.equals("/") && byPath.isEmpty()) {
                        fail(err);
                    } else {
                        Snackbar.make(findViewById(R.id.list), err == null ? "Couldn't open that folder" : err, 6000).show();
                    }
                    return;
                }
                path = target;
                show(found);
            });
        });
    }

    private void show(List<Remote.Item> items) {
        byPath.clear();
        List<RowAdapter.Row> rows = new ArrayList<>();
        long total = 0;
        for (Remote.Item it : items) {
            byPath.put(it.path, it);
            FileOps.Kind kind = it.dir ? FileOps.Kind.FOLDER : FileOps.kindOfName(it.name);
            String sub = it.dir ? (it.modified > 0 ? Fmt.date(it.modified) : "Folder")
                    : (it.size >= 0 ? Fmt.bytes(it.size) : "") + (it.modified > 0 ? "  ·  " + Fmt.date(it.modified) : "");
            rows.add(RowAdapter.Row.item(it.path, it.name, sub, kind));
            if (!it.dir && it.size > 0) total += it.size;
        }
        adapter.setRows(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText("This folder is empty");
        info.setVisibility(View.VISIBLE);
        info.setText(path + "  ·  " + Fmt.plural(rows.size(), "item", "items") + (total > 0 ? "  ·  " + Fmt.bytes(total) : ""));
        updateToolbar();
        updateUploadBar();
    }

    private void updateToolbar() {
        Menu m = toolbar.getMenu();
        m.clear();
        if (!selected.isEmpty()) {
            toolbar.inflateMenu(R.menu.remote_selection);
            m.findItem(R.id.action_rename).setVisible(selected.size() == 1);
            toolbar.setTitle(String.valueOf(selected.size()));
            toolbar.setSubtitle(null);
            toolbar.setNavigationIcon(R.drawable.ic_close);
        } else {
            toolbar.inflateMenu(R.menu.remote_main);
            toolbar.setTitle(conn.name.isEmpty() ? conn.host : conn.name);
            toolbar.setSubtitle(path.equals("/") ? null : path);
            toolbar.setNavigationIcon(R.drawable.ic_back);
        }
    }

    private void updateUploadBar() {
        int n = RemoteSession.pendingUploads.size();
        bottomBar.setVisibility(n > 0 && selected.isEmpty() ? View.VISIBLE : View.GONE);
        if (n > 0) {
            bottomText.setText(Fmt.plural(n, "item", "items") + " ready to upload");
            bottomButton.setText("Upload here");
            bottomButton.setOnClickListener(x -> uploadPending());
        }
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public void onClick(RowAdapter.Row row) {
        if (!selected.isEmpty()) {
            toggle(row);
            return;
        }
        Remote.Item it = byPath.get(row.id);
        if (it == null) return;
        if (it.dir) open(it.path);
        else openFile(it);
    }

    @Override
    public void onLongClick(RowAdapter.Row row) {
        toggle(row);
    }

    private void toggle(RowAdapter.Row row) {
        if (!selected.remove(row.id)) selected.add(row.id);
        adapter.notifyDataSetChanged();
        updateToolbar();
        updateUploadBar();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        updateToolbar();
        updateUploadBar();
    }

    private List<Remote.Item> selectedItems() {
        List<Remote.Item> out = new ArrayList<>();
        for (String p : selected) {
            Remote.Item it = byPath.get(p);
            if (it != null) out.add(it);
        }
        return out;
    }

    private boolean onMenu(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.action_refresh) {
            if (remote == null) connectAndLoad(); else open(path);
        } else if (id == R.id.action_upload) {
            picker.launch(new String[]{"*/*"});
        } else if (id == R.id.action_new_folder) {
            askName("New folder", "Folder name", "", "Create", this::createFolder);
        } else if (id == R.id.action_select_all) {
            selected.addAll(byPath.keySet());
            adapter.notifyDataSetChanged();
            updateToolbar();
        } else if (id == R.id.action_download) {
            download(selectedItems());
        } else if (id == R.id.action_delete) {
            confirmDelete(selectedItems());
        } else if (id == R.id.action_rename) {
            List<Remote.Item> sel = selectedItems();
            if (sel.size() == 1) {
                final Remote.Item it = sel.get(0);
                askName("Rename", "New name", it.name, "Rename", name -> rename(it, name));
            }
        } else {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ operations

    private interface Op {
        /** Does the work on a background thread and returns the message to show when it is done. */
        String run(Remote r, Remote.Progress p) throws IOException;
    }

    private interface Name {
        void accept(String name);
    }

    private void askName(String title, String hint, String initial, String button, final Name done) {
        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(initial);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(button, (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty() && !n.contains("/") && !n.contains("\\")) done.accept(n);
                    else if (!n.isEmpty()) Snackbar.make(findViewById(R.id.list), "A name can't contain / or \\", 4000).show();
                }).show();
    }

    /** Runs a job with a progress box the user can cancel, then refreshes the folder and shows what happened. */
    private void run(String title, final Op op) {
        View v = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        final TextView text = v.findViewById(R.id.pText);
        text.setText("Starting…");
        final AtomicBoolean cancel = new AtomicBoolean();
        final AtomicLong bytes = new AtomicLong();
        final AlertDialog dlg = new MaterialAlertDialogBuilder(this).setTitle(title).setView(v).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancel.set(true)).create();
        dlg.show();
        final Remote.Progress progress = new Remote.Progress() {
            private long lastShown;
            @Override public boolean cancelled() { return cancel.get(); }
            @Override public void bytes(long n) {
                long total = bytes.addAndGet(n);
                long now = System.currentTimeMillis();
                if (now - lastShown > 200) {
                    lastShown = now;
                    ui.post(() -> text.setText(Fmt.bytes(total) + " so far"));
                }
            }
        };
        io.execute(() -> {
            String msg;
            boolean bad = false;
            try {
                Remote r = remote;
                if (r == null) {
                    r = Remotes.open(conn.copy());
                    remote = r;
                }
                msg = op.run(r, progress);
            } catch (IOException e) {
                msg = cancel.get() ? "Cancelled" : e.getMessage();
                bad = !cancel.get();
            }
            final String result = msg;
            final boolean failed = bad;
            ui.post(() -> {
                if (isDestroyed()) return;
                dlg.dismiss();
                Snackbar.make(findViewById(R.id.list), result == null ? "Done" : result, failed ? 8000 : 6000).show();
                open(path);
            });
        });
    }

    private void createFolder(final String name) {
        run("Creating folder", (r, p) -> {
            r.mkdir(Remote.Paths.join(path, name));
            return "Created “" + name + "”";
        });
    }

    private void rename(final Remote.Item it, final String name) {
        run("Renaming", (r, p) -> {
            r.rename(it.path, Remote.Paths.join(Remote.Paths.parent(it.path), name));
            return "Renamed to “" + name + "”";
        });
    }

    private void confirmDelete(final List<Remote.Item> items) {
        if (items.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + (items.size() == 1 ? "“" + items.get(0).name + "”" : Fmt.plural(items.size(), "item", "items")) + "?")
                .setMessage("It is deleted on the server straight away. There is no recycle bin there, so this can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> run("Deleting", (r, p) -> {
                    for (Remote.Item it : items) {
                        if (p.cancelled()) throw new IOException("Cancelled");
                        r.delete(it.path, it.dir);
                    }
                    return "Deleted " + Fmt.plural(items.size(), "item", "items");
                })).show();
    }

    static File downloadDir(Connection c) {
        String n = c.name.isEmpty() ? c.host : c.name;
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileBandit/" + (n.isEmpty() ? "Network" : n));
    }

    private void download(final List<Remote.Item> items) {
        if (items.isEmpty()) return;
        if (!Access.allFiles()) {
            Snackbar.make(findViewById(R.id.list), "Allow file access in the Files tab first, then try again.", 6000).show();
            return;
        }
        final File dest = downloadDir(conn);
        run("Downloading", (r, p) -> {
            int n = 0;
            for (Remote.Item it : items) {
                if (p.cancelled()) throw new IOException("Cancelled");
                Remote.Copy.download(r, it, dest, p);
                n++;
            }
            final String where = "Downloads/FileBandit/" + dest.getName();
            ui.post(() -> clearSelection());
            return "Saved " + Fmt.plural(n, "item", "items") + " in " + where;
        });
    }

    private void openFile(final Remote.Item it) {
        final File dir = new File(getCacheDir(), "remote_open");
        run("Opening " + it.name, (r, p) -> {
            File f = Remote.Copy.download(r, it, dir, p);
            ui.post(() -> {
                if (!isDestroyed()) Viewer.open(this, f, null);
            });
            return null;
        });
    }

    private void uploadPending() {
        final List<File> files = new ArrayList<>(RemoteSession.pendingUploads);
        RemoteSession.pendingUploads.clear();
        updateUploadBar();
        run("Uploading", (r, p) -> {
            int n = 0;
            for (File f : files) {
                if (p.cancelled()) throw new IOException("Cancelled");
                n += Remote.Copy.upload(r, f, path, p);
            }
            return "Uploaded " + Fmt.plural(n, "file", "files");
        });
    }

    private void uploadUris(final List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        run("Uploading", (r, p) -> {
            int n = 0;
            for (Uri u : uris) {
                if (p.cancelled()) throw new IOException("Cancelled");
                String name = "file";
                long size = -1;
                android.database.Cursor c = getContentResolver().query(u, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int si = c.getColumnIndex(OpenableColumns.SIZE);
                            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                        }
                    } finally {
                        c.close();
                    }
                }
                InputStream in = getContentResolver().openInputStream(u);
                if (in == null) throw new IOException("Can't read “" + name + "”");
                try {
                    r.put(Remote.Paths.join(path, name.replace('/', '_')), new Remote.CountingStream(in, p), size);
                    n++;
                } finally {
                    in.close();
                }
            }
            return "Uploaded " + Fmt.plural(n, "file", "files");
        });
    }
}
