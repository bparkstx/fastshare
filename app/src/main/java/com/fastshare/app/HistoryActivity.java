package com.fastshare.app;

import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Everything that was sent or received over the link, kept across restarts. */
public class HistoryActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    private MaterialToolbar toolbar;
    private RowAdapter adapter;
    private View empty;
    private TextView emptyText;
    private TextView info;
    private ShareHub hub;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_list);
        hub = FastShareApp.hub(this);
        toolbar = findViewById(R.id.toolbar);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.emptyText);
        info = findViewById(R.id.info);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, new HashSet<String>(), this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        toolbar.setTitle("Transfer history");
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(x -> finish());
        toolbar.inflateMenu(R.menu.history_main);
        toolbar.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() != R.id.action_clear || hub.history().list().isEmpty()) return false;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Clear the history?")
                    .setMessage("This only removes the list. The files themselves stay where they are.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear", (d, w) -> {
                        hub.history().clear();
                        render();
                    }).show();
            return true;
        });
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        List<History.Entry> entries = hub.history().list();
        List<RowAdapter.Row> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        long moved = 0;
        for (History.Entry e : entries) {
            String what = e.ok ? (e.incoming ? "Received" : "Sent") : (e.detail.equals("Paused") ? "Not finished" : "Interrupted");
            String sub = what + "  ·  " + (e.size > 0 || e.ok ? Fmt.bytes(e.size) + "  ·  " : "") + Fmt.ago(e.when, now);
            RowAdapter.Row row = RowAdapter.Row.item(e.key, e.name, sub, FileOps.kindOfName(e.name));
            row.tag(e);
            if (!e.ok) row.icon(R.drawable.ic_warning, getColor(R.color.fs_warn));
            rows.add(row);
            if (e.ok) moved += e.size;
        }
        adapter.setRows(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText("Nothing has been transferred yet");
        info.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        info.setText(Fmt.plural(rows.size(), "transfer", "transfers") + "  ·  " + Fmt.bytes(moved) + " moved");
    }

    @Override
    public void onClick(RowAdapter.Row row) {
        History.Entry e = (History.Entry) row.tag;
        if (e == null || !e.ok || !e.incoming) return;
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileBandit");
        if (dir.isDirectory()) {
            Nav.dir = dir;
            finish();
        }
    }

    @Override
    public void onLongClick(RowAdapter.Row row) { }
}
