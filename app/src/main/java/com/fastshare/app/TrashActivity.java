package com.fastshare.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The recycle bin: deleted items stay here for 30 days and can be put back. */
public class TrashActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    private static final class Item {
        final Trash trash;
        final Trash.Entry entry;

        Item(Trash trash, Trash.Entry entry) {
            this.trash = trash;
            this.entry = entry;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Item> items = new LinkedHashMap<>();

    private MaterialToolbar toolbar;
    private RowAdapter adapter;
    private View empty;
    private TextView emptyText;
    private TextView info;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_list);
        toolbar = findViewById(R.id.toolbar);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.emptyText);
        info = findViewById(R.id.info);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, selected, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        toolbar.setNavigationOnClickListener(x -> {
            if (!selected.isEmpty()) clearSelection(); else finish();
        });
        toolbar.setOnMenuItemClickListener(this::onMenu);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!selected.isEmpty()) {
                    clearSelection();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        updateToolbar();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void load() {
        final List<File> roots = Volumes.roots(this);
        io.execute(() -> {
            final Map<String, Item> found = new LinkedHashMap<>();
            for (File root : roots) {
                Trash t = new Trash(root);
                for (Trash.Entry e : t.list()) found.put(root.getPath() + "|" + e.id, new Item(t, e));
            }
            ui.post(() -> {
                if (isDestroyed()) return;
                items.clear();
                items.putAll(found);
                selected.retainAll(items.keySet());
                render();
            });
        });
    }

    private void render() {
        List<Item> sorted = new ArrayList<>(items.values());
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(b.entry.deletedAt, a.entry.deletedAt));
        List<RowAdapter.Row> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        long total = 0;
        for (Map.Entry<String, Item> me : items.entrySet()) {
            Item it = me.getValue();
            total += it.entry.size;
        }
        for (Item it : sorted) {
            Trash.Entry e = it.entry;
            String id = null;
            for (Map.Entry<String, Item> me : items.entrySet()) if (me.getValue() == it) id = me.getKey();
            File parent = e.original.getParentFile();
            String from = parent == null ? "" : "From " + parent.getName() + "  ·  ";
            rows.add(RowAdapter.Row.item(id, e.name,
                    from + Fmt.ago(e.deletedAt, now) + "  ·  " + Fmt.bytes(e.size),
                    e.dir ? FileOps.Kind.FOLDER : FileOps.kindOfName(e.name)).tag(it));
        }
        adapter.setRows(rows);
        if (rows.isEmpty()) {
            emptyText.setText("The trash is empty.\nDeleted items wait here for 30 days.");
            empty.setVisibility(View.VISIBLE);
            info.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            info.setText(Fmt.plural(rows.size(), "item", "items") + "  ·  " + Fmt.bytes(total)
                    + "  ·  items are removed for good after 30 days");
            info.setVisibility(View.VISIBLE);
        }
        updateToolbar();
    }

    private void updateToolbar() {
        Menu m = toolbar.getMenu();
        m.clear();
        if (!selected.isEmpty()) {
            toolbar.inflateMenu(R.menu.trash_selection);
            toolbar.setTitle(String.valueOf(selected.size()));
            toolbar.setNavigationIcon(R.drawable.ic_close);
        } else {
            toolbar.inflateMenu(R.menu.trash_main);
            m.findItem(R.id.action_empty).setVisible(!items.isEmpty());
            toolbar.setTitle("Trash");
            toolbar.setNavigationIcon(R.drawable.ic_back);
        }
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public void onClick(RowAdapter.Row row) {
        if (!selected.isEmpty()) {
            toggle(row);
            return;
        }
        final Item it = items.get(row.id);
        if (it == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(it.entry.name)
                .setItems(new String[]{"Restore", "Delete forever"}, (d, which) -> {
                    List<Item> one = new ArrayList<>();
                    one.add(it);
                    if (which == 0) restore(one); else confirmForever(one);
                }).show();
    }

    @Override
    public void onLongClick(RowAdapter.Row row) {
        toggle(row);
    }

    private void toggle(RowAdapter.Row row) {
        if (!selected.remove(row.id)) selected.add(row.id);
        adapter.notifyDataSetChanged();
        updateToolbar();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        updateToolbar();
    }

    private List<Item> selectedItems() {
        List<Item> out = new ArrayList<>();
        for (String id : selected) {
            Item it = items.get(id);
            if (it != null) out.add(it);
        }
        return out;
    }

    private boolean onMenu(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.action_restore) {
            restore(selectedItems());
        } else if (id == R.id.action_delete_forever) {
            confirmForever(selectedItems());
        } else if (id == R.id.action_select_all) {
            selected.addAll(items.keySet());
            adapter.notifyDataSetChanged();
            updateToolbar();
        } else if (id == R.id.action_empty) {
            confirmForever(new ArrayList<>(items.values()));
        } else {
            return false;
        }
        return true;
    }

    private void restore(final List<Item> list) {
        io.execute(() -> {
            int ok = 0;
            String err = null;
            for (Item it : list) {
                try {
                    it.trash.restore(it.entry);
                    ok++;
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }
            final String msg = err == null ? "Restored " + Fmt.plural(ok, "item", "items")
                    : "Restored " + ok + ". " + err;
            ui.post(() -> {
                if (isDestroyed()) return;
                selected.clear();
                Snackbar.make(findViewById(R.id.list), msg, Snackbar.LENGTH_LONG).show();
                load();
            });
        });
    }

    private void confirmForever(final List<Item> list) {
        if (list.isEmpty()) return;
        String what = list.size() == 1 ? "“" + list.get(0).entry.name + "”" : Fmt.plural(list.size(), "item", "items");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + what + " forever?")
                .setMessage("This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete forever", (d, w) -> io.execute(() -> {
                    for (Item it : list) it.trash.deleteForever(it.entry);
                    ui.post(() -> {
                        if (isDestroyed()) return;
                        selected.clear();
                        load();
                    });
                })).show();
    }
}
