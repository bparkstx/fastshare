package com.fastshare.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installed apps: back up their APK files, share them, open their settings, uninstall. */
public class AppsActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    /** Where backed-up apps are saved. */
    static File backupDir() {
        return new File(Environment.getExternalStorageDirectory(), "FileBandit/Apps");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, AppList.App> byPkg = new HashMap<>();

    private MaterialToolbar toolbar;
    private RowAdapter adapter;
    private EditText search;
    private TextView info;
    private View busy;
    private View empty;
    private TextView emptyText;

    private List<AppList.App> apps = new ArrayList<>();
    private AppList.Sort sort = AppList.Sort.NAME;
    private boolean showSystem;
    private String filter = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_list);
        toolbar = findViewById(R.id.toolbar);
        search = findViewById(R.id.searchInput);
        info = findViewById(R.id.info);
        busy = findViewById(R.id.busy);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.emptyText);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, selected, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        toolbar.setNavigationOnClickListener(x -> {
            if (!selected.isEmpty()) clearSelection(); else finish();
        });
        toolbar.setOnMenuItemClickListener(this::onMenu);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!selected.isEmpty()) {
                    clearSelection();
                } else if (search.getVisibility() == View.VISIBLE) {
                    search.setText("");
                    search.setVisibility(View.GONE);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!apps.isEmpty()) load();          // an uninstall may have just finished
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void load() {
        busy.setVisibility(View.VISIBLE);
        final boolean system = showSystem;
        io.execute(() -> {
            final List<AppList.App> found = AppList.load(this, system, true);
            ui.post(() -> {
                if (isDestroyed()) return;
                apps = found;
                busy.setVisibility(View.GONE);
                byPkg.clear();
                for (AppList.App a : apps) byPkg.put(a.pkg, a);
                selected.retainAll(byPkg.keySet());
                render();
            });
        });
    }

    private void render() {
        AppList.sort(apps, sort);
        List<RowAdapter.Row> rows = new ArrayList<>();
        long total = 0;
        for (AppList.App a : apps) {
            if (!filter.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(filter) && !a.pkg.toLowerCase(Locale.ROOT).contains(filter)) continue;
            total += a.size;
            String sub = Fmt.bytes(a.size) + (a.version == null ? "" : "  ·  v" + a.version)
                    + "  ·  " + (sort == AppList.Sort.UPDATED ? "updated " + Fmt.date(a.updated) : "installed " + Fmt.date(a.installed))
                    + (a.isSplit() ? "  ·  split" : "");
            rows.add(RowAdapter.Row.item(a.pkg, a.label, sub, FileOps.Kind.APK).drawable(a.icon).tag(a));
        }
        adapter.setRows(rows);
        info.setVisibility(View.VISIBLE);
        info.setText(Fmt.plural(rows.size(), "app", "apps") + "  ·  " + Fmt.bytes(total) + " of app files"
                + (showSystem ? "" : "  ·  system apps hidden"));
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText(filter.isEmpty() ? "No apps found" : "No app matches “" + filter + "”");
        updateToolbar();
    }

    private void updateToolbar() {
        Menu m = toolbar.getMenu();
        m.clear();
        if (!selected.isEmpty()) {
            toolbar.inflateMenu(R.menu.apps_selection);
            toolbar.setTitle(String.valueOf(selected.size()));
            toolbar.setNavigationIcon(R.drawable.ic_close);
        } else {
            toolbar.inflateMenu(R.menu.apps_main);
            m.findItem(R.id.action_system).setChecked(showSystem);
            toolbar.setTitle("Apps");
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
        final AppList.App app = byPkg.get(row.id);
        if (app == null) return;
        final boolean canOpen = getPackageManager().getLaunchIntentForPackage(app.pkg) != null;
        final List<String> labels = new ArrayList<>();
        if (canOpen) labels.add("Open");
        labels.add("App info");
        labels.add("Back up APK");
        labels.add("Share APK");
        labels.add("Uninstall");
        new MaterialAlertDialogBuilder(this)
                .setTitle(app.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String choice = labels.get(which);
                    if (choice.equals("Open")) startActivity(getPackageManager().getLaunchIntentForPackage(app.pkg));
                    else if (choice.equals("App info")) appInfo(app);
                    else if (choice.equals("Back up APK")) backup(single(app));
                    else if (choice.equals("Share APK")) share(single(app));
                    else uninstall(app);
                }).show();
    }

    @Override
    public void onLongClick(RowAdapter.Row row) {
        toggle(row);
    }

    private static List<AppList.App> single(AppList.App a) {
        List<AppList.App> l = new ArrayList<>();
        l.add(a);
        return l;
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

    private List<AppList.App> selectedApps() {
        List<AppList.App> out = new ArrayList<>();
        for (String p : selected) {
            AppList.App a = byPkg.get(p);
            if (a != null) out.add(a);
        }
        return out;
    }

    private boolean onMenu(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.action_search) {
            search.setVisibility(View.VISIBLE);
            search.requestFocus();
        } else if (id == R.id.action_sort) {
            final String[] labels = {"Name", "Biggest first", "Newest installed", "Recently updated"};
            new MaterialAlertDialogBuilder(this).setTitle("Sort by")
                    .setSingleChoiceItems(labels, sort.ordinal(), (d, which) -> {
                        sort = AppList.Sort.values()[which];
                        d.dismiss();
                        render();
                    }).show();
        } else if (id == R.id.action_system) {
            showSystem = !showSystem;
            load();
        } else if (id == R.id.action_backup) {
            backup(selectedApps());
        } else if (id == R.id.action_share_apk) {
            share(selectedApps());
        } else if (id == R.id.action_select_all) {
            for (AppList.App a : apps) selected.add(a.pkg);
            adapter.notifyDataSetChanged();
            updateToolbar();
        } else {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ actions

    private void appInfo(AppList.App app) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Couldn't open app info", Toast.LENGTH_SHORT).show();
        }
    }

    private void uninstall(AppList.App app) {
        if (app.system) {
            Toast.makeText(this, app.label + " is a system app. Android only lets you turn it off in App info.", Toast.LENGTH_LONG).show();
            appInfo(app);
            return;
        }
        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)));
    }

    /**
     * Copies each app's APK into FileBandit/Apps. An app made of several APKs (a "split" app) gets its own folder
     * holding all of them, since the base file alone wouldn't install.
     */
    private void backup(final List<AppList.App> list) {
        if (list.isEmpty()) return;
        if (!Access.allFiles()) {
            Toast.makeText(this, "Allow file access in the Files tab first, then try again.", Toast.LENGTH_LONG).show();
            return;
        }
        busy.setVisibility(View.VISIBLE);
        final File dir = backupDir();
        io.execute(() -> {
            int done = 0;
            String error = null;
            for (AppList.App a : list) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    if (a.isSplit()) {
                        File folder = FileOps.uniqueSibling(dir, a.backupName());
                        if (!folder.mkdirs()) throw new java.io.IOException("Can't create " + folder.getName());
                        for (File apk : a.allApks) FileOps.copy(apk, folder, null, null);
                    } else {
                        File target = FileOps.uniqueSibling(dir, a.backupName() + ".apk");
                        File made = FileOps.copy(a.baseApk, dir, null, null);
                        if (!made.equals(target) && !made.renameTo(target)) target = made;
                    }
                    done++;
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            final int count = done;
            final String err = error;
            ui.post(() -> {
                if (isDestroyed()) return;
                busy.setVisibility(View.GONE);
                clearSelection();
                String msg = err == null ? "Backed up " + Fmt.plural(count, "app", "apps") + " to FileBandit/Apps"
                        : "Backed up " + count + ". " + err;
                Snackbar.make(findViewById(R.id.list), msg, 8000)
                        .setAction("Open folder", v -> {
                            Nav.dir = dir;
                            finish();
                        }).show();
            });
        });
    }

    /** Shares the APK through Android's share sheet, from a copy in the cache so the original is never exposed. */
    private void share(final List<AppList.App> list) {
        if (list.isEmpty()) return;
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            final List<File> copies = new ArrayList<>();
            File cache = new File(getCacheDir(), "apk_share");
            FileOps.delete(cache);
            //noinspection ResultOfMethodCallIgnored
            cache.mkdirs();
            String error = null;
            for (AppList.App a : list) {
                try {
                    File made = FileOps.copy(a.baseApk, cache, null, null);
                    File named = new File(cache, a.backupName() + ".apk");
                    if (made.renameTo(named)) made = named;
                    copies.add(made);
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            final String err = error;
            ui.post(() -> {
                if (isDestroyed()) return;
                busy.setVisibility(View.GONE);
                if (copies.isEmpty()) {
                    Toast.makeText(this, err == null ? "Nothing to share" : err, Toast.LENGTH_LONG).show();
                    return;
                }
                FileActions.share(this, copies);
                if (list.stream().anyMatch(AppList.App::isSplit)) {
                    Toast.makeText(this, "Some of these apps come in several parts. Use Back up APK to keep all the parts.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
