package com.fastshare.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Computers, NAS boxes and servers you can browse: FTP, SFTP, Windows shares and WebDAV. */
public class NetworkActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    private final ExecutorService io = Executors.newCachedThreadPool();
    private Connections store;
    private RowAdapter adapter;
    private View empty;
    private TextView emptyText;
    private TextView info;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_list);
        store = RemoteSession.store(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.emptyText);
        info = findViewById(R.id.info);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, new HashSet<String>(), this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        toolbar.setTitle("Network locations");
        toolbar.setNavigationOnClickListener(x -> finish());
        toolbar.inflateMenu(R.menu.network_main);
        toolbar.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == R.id.action_add) {
                edit(null);
                return true;
            }
            return false;
        });
        emptyText.setText("No locations yet.\nTap + to add a computer, a NAS or a server on your network.");
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void render() {
        List<Connection> all = store.list();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Connection c : all) {
            rows.add(RowAdapter.Row.item(c.id, c.name.isEmpty() ? c.host : c.name, c.type.label + "  ·  " + c.describe(), FileOps.Kind.OTHER)
                    .icon(c.type == Connection.Type.SMB || c.type == Connection.Type.SFTP ? R.drawable.ic_server : R.drawable.ic_cloud,
                            getColor(R.color.kind_archive)));
        }
        adapter.setRows(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        info.setVisibility(View.GONE);
    }

    @Override
    public void onClick(RowAdapter.Row row) {
        startActivity(new Intent(this, RemoteActivity.class).putExtra(RemoteActivity.EXTRA_ID, row.id));
    }

    @Override
    public void onLongClick(final RowAdapter.Row row) {
        View anchor = findViewById(R.id.list);
        PopupMenu m = new PopupMenu(this, anchor, android.view.Gravity.CENTER);
        m.getMenu().add(0, 1, 0, "Edit");
        m.getMenu().add(0, 2, 1, "Remove");
        m.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) {
                edit(store.get(row.id));
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Remove “" + row.title + "”?")
                        .setMessage("This only forgets the saved details. Nothing on the server is touched.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, w) -> {
                            try {
                                store.remove(row.id);
                            } catch (IOException ignored) { }
                            render();
                        }).show();
            }
            return true;
        });
        m.show();
    }

    // ------------------------------------------------------------------ add / edit

    private static final String[] HELP = {
            "For a computer or NAS that offers FTP. Not encrypted, so only use it at home.",
            "FTP with encryption. Many NAS boxes and web hosts offer it.",
            "File access over SSH. Works with most Linux machines, Macs with Remote Login on, and many NAS boxes.",
            "For Windows shared folders, Macs with File Sharing on, and most NAS boxes. Enter the share's name below.",
            "For Nextcloud, ownCloud and many NAS boxes. Use this for http:// addresses.",
            "WebDAV with encryption. Use this for https:// addresses.",
    };

    /** Shows the add/edit form. {@code existing} is null for a new location. */
    void edit(final Connection existing) {
        View v = getLayoutInflater().inflate(R.layout.dialog_connection, null);
        final Spinner type = v.findViewById(R.id.cType);
        final TextView help = v.findViewById(R.id.cTypeHelp);
        final EditText name = v.findViewById(R.id.cName);
        final EditText host = v.findViewById(R.id.cHost);
        final EditText port = v.findViewById(R.id.cPort);
        final EditText user = v.findViewById(R.id.cUser);
        final EditText pass = v.findViewById(R.id.cPassword);
        final EditText path = v.findViewById(R.id.cPath);
        final EditText key = v.findViewById(R.id.cKey);
        final CheckBox trust = v.findViewById(R.id.cTrust);
        final TextView status = v.findViewById(R.id.cStatus);

        final Connection.Type[] types = Connection.Type.values();
        List<String> labels = new ArrayList<>();
        for (Connection.Type t : types) labels.add(t.label);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));

        final Connection base = existing == null ? new Connection() : existing;
        type.setSelection(base.type.ordinal());
        name.setText(base.name);
        host.setText(base.host);
        port.setText(base.port > 0 ? String.valueOf(base.port) : "");
        user.setText(base.user);
        pass.setText(base.password);
        path.setText(base.path);
        key.setText(base.privateKey);
        trust.setChecked(base.trustAll);

        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                Connection.Type t = types[pos];
                help.setText(HELP[pos]);
                port.setHint("Port (" + t.defaultPort + ")");
                path.setHint(t == Connection.Type.SMB ? "Shared folder name (required)" : "Folder to start in (optional)");
                key.setVisibility(t == Connection.Type.SFTP ? View.VISIBLE : View.GONE);
                trust.setVisibility(t == Connection.Type.FTPS || t == Connection.Type.WEBDAVS ? View.VISIBLE : View.GONE);
            }

            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        final AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "Add a location" : "Edit location")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Test", null)
                .setPositiveButton("Save", null)
                .create();
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                Connection c = read(base, types[type.getSelectedItemPosition()], name, host, port, user, pass, path, key, trust);
                String problem = c.problem();
                if (problem != null) {
                    say(status, problem, true);
                    return;
                }
                if (c.name.trim().isEmpty()) c.name = c.host;
                try {
                    store.save(c);
                } catch (IOException e) {
                    say(status, e.getMessage(), true);
                    return;
                }
                dlg.dismiss();
                render();
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x -> {
                final Connection c = read(base, types[type.getSelectedItemPosition()], name, host, port, user, pass, path, key, trust);
                String problem = c.problem();
                if (problem != null) {
                    say(status, problem, true);
                    return;
                }
                say(status, "Connecting…", false);
                io.execute(() -> {
                    String result;
                    boolean bad = false;
                    try {
                        Remote r = Remotes.open(c);
                        int n = r.list("/").size();
                        r.close();
                        result = "Connected. " + Fmt.plural(n, "item", "items") + " in the first folder.";
                        base.hostKey = c.hostKey;
                    } catch (IOException e) {
                        result = e.getMessage();
                        bad = true;
                    }
                    final String msg = result;
                    final boolean isBad = bad;
                    runOnUiThread(() -> {
                        if (!isDestroyed()) say(status, msg, isBad);
                    });
                });
            });
        });
        dlg.show();
    }

    private static Connection read(Connection base, Connection.Type t, EditText name, EditText host, EditText port, EditText user,
                                   EditText pass, EditText path, EditText key, CheckBox trust) {
        Connection c = base.copy();
        c.type = t;
        c.name = name.getText().toString().trim();
        c.host = host.getText().toString().trim();
        String p = port.getText().toString().trim();
        try {
            c.port = p.isEmpty() ? 0 : Integer.parseInt(p);
        } catch (NumberFormatException e) {
            c.port = -1;
        }
        c.user = user.getText().toString().trim();
        c.password = pass.getText().toString();
        c.path = path.getText().toString().trim();
        c.privateKey = t == Connection.Type.SFTP ? key.getText().toString().trim() : "";
        c.trustAll = (t == Connection.Type.FTPS || t == Connection.Type.WEBDAVS) && trust.isChecked();
        // a changed address or key type means the remembered server key no longer applies
        if (!c.host.equals(base.host) || c.type != base.type || c.portOrDefault() != base.portOrDefault()) c.hostKey = "";
        return c;
    }

    private void say(TextView status, String msg, boolean bad) {
        status.setText(msg);
        status.setTextColor(getColor(bad ? R.color.fs_err : R.color.fs_ok));
    }
}
