package com.fastshare.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatic photo backup: pick a server, pick folders, switch it on. */
public class BackupActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Prefs prefs;
    private List<Connection> conns = new ArrayList<>();
    private boolean binding;

    private TextView status, progress, noConn;
    private LinearProgressIndicator bar;
    private MaterialButton runNow, stop, addConn;
    private MaterialSwitch auto, videos, byDate, charging;
    private Spinner spinner;
    private EditText dest;
    private CheckBox cam, pics, movies;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_backup);
        prefs = new Prefs(this);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(x -> finish());
        status = findViewById(R.id.bStatus);
        progress = findViewById(R.id.bProgress);
        noConn = findViewById(R.id.bNoConn);
        bar = findViewById(R.id.bBar);
        runNow = findViewById(R.id.bRunNow);
        stop = findViewById(R.id.bCancel);
        addConn = findViewById(R.id.bAddConn);
        auto = findViewById(R.id.bAuto);
        videos = findViewById(R.id.bVideos);
        byDate = findViewById(R.id.bByDate);
        charging = findViewById(R.id.bCharging);
        spinner = findViewById(R.id.bConn);
        dest = findViewById(R.id.bDest);
        cam = findViewById(R.id.bCam);
        pics = findViewById(R.id.bPics);
        movies = findViewById(R.id.bMovies);

        addConn.setOnClickListener(x -> startActivity(new Intent(this, NetworkActivity.class)));
        runNow.setOnClickListener(x -> runNow());
        stop.setOnClickListener(x -> {
            cancel.set(true);
            stop.setEnabled(false);
        });
        findViewById(R.id.bFree).setOnClickListener(x -> freeUp());
        findViewById(R.id.bReset).setOnClickListener(x -> new MaterialAlertDialogBuilder(this)
                .setTitle("Forget what was sent?")
                .setMessage("The next backup checks every photo again. Photos already on the server aren't sent twice.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (d, w) -> {
                    Connection c = selected();
                    if (c != null) BackupJob.logFor(this, c.id).clear();
                    render();
                }).show());

        CompoundButton.OnCheckedChangeListener save = (b, on) -> {
            if (!binding) saveSettings();
        };
        auto.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            if (on && selected() == null) {
                binding = true;
                auto.setChecked(false);
                binding = false;
                Snackbar.make(findViewById(R.id.bStatus), "Add and choose a place to back up to first.", 5000).show();
                return;
            }
            saveSettings();
        });
        videos.setOnCheckedChangeListener(save);
        byDate.setOnCheckedChangeListener(save);
        charging.setOnCheckedChangeListener(save);
        cam.setOnCheckedChangeListener(save);
        pics.setOnCheckedChangeListener(save);
        movies.setOnCheckedChangeListener(save);
        dest.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (!binding) saveSettings();
            }
        });
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!binding) saveSettings();
            }

            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private Connection selected() {
        int i = spinner.getSelectedItemPosition();
        return i >= 0 && i < conns.size() ? conns.get(i) : null;
    }

    private void load() {
        binding = true;
        conns = RemoteSession.store(this).list();
        List<String> names = new ArrayList<>();
        int pos = 0;
        for (int i = 0; i < conns.size(); i++) {
            Connection c = conns.get(i);
            names.add(c.name.isEmpty() ? c.host : c.name);
            if (c.id.equals(prefs.backupConnection())) pos = i;
        }
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        if (!conns.isEmpty()) spinner.setSelection(pos);
        boolean none = conns.isEmpty();
        spinner.setVisibility(none ? View.GONE : View.VISIBLE);
        noConn.setVisibility(none ? View.VISIBLE : View.GONE);
        addConn.setVisibility(none ? View.VISIBLE : View.GONE);
        dest.setText(prefs.backupDest().isEmpty() ? BackupJob.defaultDest() : prefs.backupDest());
        Set<String> folders = prefs.backupFolders();
        cam.setChecked(folders.contains("DCIM"));
        pics.setChecked(folders.contains("Pictures"));
        movies.setChecked(folders.contains("Movies"));
        videos.setChecked(prefs.backupVideos());
        byDate.setChecked(prefs.backupByDate());
        charging.setChecked(prefs.backupCharging());
        auto.setChecked(prefs.backupOn() && !none);
        binding = false;
    }

    private void saveSettings() {
        Connection c = selected();
        prefs.setBackupConnection(c == null ? "" : c.id);
        prefs.setBackupDest(dest.getText().toString().trim());
        Set<String> folders = new LinkedHashSet<>();
        if (cam.isChecked()) folders.add("DCIM");
        if (pics.isChecked()) folders.add("Pictures");
        if (movies.isChecked()) folders.add("Movies");
        prefs.setBackupFolders(folders);
        prefs.setBackupVideos(videos.isChecked());
        prefs.setBackupByDate(byDate.isChecked());
        prefs.setBackupCharging(charging.isChecked());
        prefs.setBackupOn(auto.isChecked() && c != null);
        try {
            BackupWorker.schedule(this);
        } catch (Exception ignored) { }
        render();
    }

    private void render() {
        long t = prefs.backupLastTime();
        String last = prefs.backupLast();
        status.setText(t == 0 ? "Nothing has been backed up yet."
                : "Last backup " + Fmt.ago(t, System.currentTimeMillis()) + ": " + last);
        boolean running = BackupJob.isRunning();
        runNow.setEnabled(!running && selected() != null);
        if (!running) {
            bar.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            stop.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ run now

    private void runNow() {
        if (selected() == null) {
            Snackbar.make(findViewById(R.id.bStatus), "Add and choose a place to back up to first.", 5000).show();
            return;
        }
        saveSettings();
        cancel.set(false);
        runNow.setEnabled(false);
        stop.setEnabled(true);
        stop.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setText("Looking for new photos…");
        final long[] sent = {0};
        io.execute(() -> {
            final BackupJob.Outcome o = BackupJob.run(this, new Backup.Listener() {
                @Override public void file(final String name, final int index, final int total) {
                    ui.post(() -> progress.setText("Sending " + name + "  (" + index + " of " + total + ")"));
                }

                @Override public void bytes(long n) {
                    sent[0] += n;
                }

                @Override public boolean cancelled() {
                    return cancel.get();
                }
            });
            ui.post(() -> {
                if (isDestroyed()) return;
                render();
                String msg = o.alreadyRunning ? "A backup is already running."
                        : o.error != null ? o.error : BackupJob.describe(o.summary);
                Snackbar.make(findViewById(R.id.bStatus), msg, 8000).show();
            });
        });
    }

    // ------------------------------------------------------------------ free up space

    private void freeUp() {
        final Connection c = selected();
        if (c == null) {
            Snackbar.make(findViewById(R.id.bStatus), "Choose where the photos were backed up to first.", 5000).show();
            return;
        }
        if (!Access.allFiles()) {
            Snackbar.make(findViewById(R.id.bStatus), "Allow file access in the Files tab first.", 5000).show();
            return;
        }
        bar.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setText("Checking the server…");
        io.execute(() -> {
            List<File> safe = new ArrayList<>();
            String error = null;
            try {
                Remote r = Remotes.open(c.copy());
                try {
                    Backup.Options o = BackupJob.options(this);
                    BackupLog log = BackupJob.logFor(this, c.id);
                    safe = Backup.verified(r, Backup.alreadyBackedUp(o, log), log);
                } finally {
                    r.close();
                }
            } catch (IOException e) {
                error = e.getMessage();
            }
            final List<File> found = safe;
            final String err = error;
            ui.post(() -> {
                if (isDestroyed()) return;
                bar.setVisibility(View.GONE);
                progress.setVisibility(View.GONE);
                if (err != null) {
                    Snackbar.make(findViewById(R.id.bStatus), "Couldn't check the server: " + err, 8000).show();
                    return;
                }
                if (found.isEmpty()) {
                    Snackbar.make(findViewById(R.id.bStatus), "Nothing to remove. Everything on the phone is either not backed up yet or no longer on the server.", 8000).show();
                    return;
                }
                long total = 0;
                for (File f : found) total += f.length();
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Move " + Fmt.plural(found.size(), "file", "files") + " to the Trash?")
                        .setMessage("They are on your server (" + Fmt.bytes(total) + "). They stay in this phone's Trash for 30 days before they're gone for good.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Move to Trash", (d, w) -> moveToTrash(found)).show();
            });
        });
    }

    private void moveToTrash(final List<File> files) {
        io.execute(() -> {
            Trash trash = new Trash(Environment.getExternalStorageDirectory());
            int moved = 0;
            long bytes = 0;
            for (File f : files) {
                try {
                    long size = f.length();
                    trash.trash(f);
                    moved++;
                    bytes += size;
                } catch (IOException ignored) { }
            }
            final int n = moved;
            final long freed = bytes;
            ui.post(() -> {
                if (isDestroyed()) return;
                Snackbar.make(findViewById(R.id.bStatus), "Moved " + Fmt.plural(n, "file", "files") + " to the Trash (" + Fmt.bytes(freed) + "). Empty the Trash to free the space now.", 10000).show();
            });
        });
    }
}
