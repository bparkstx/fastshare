package com.fastshare.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** The file manager tab. */
public class FilesFragment extends Fragment implements FileAdapter.Callbacks {

    /** Files waiting to be pasted. Shared so it survives leaving and re-entering the tab. */
    private static final List<File> clipboard = new ArrayList<>();
    private static boolean clipboardCut;

    /** Forgets copied/cut files (tests start each case with an empty clipboard). */
    static void resetClipboard() {
        clipboard.clear();
        clipboardCut = false;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private AtomicBoolean loadCancel = new AtomicBoolean();
    private int gen;

    private Prefs prefs;
    private ShareHub hub;
    private FileAdapter adapter;

    private MaterialToolbar toolbar;
    private View searchRow;
    private EditText searchInput;
    private View crumbScroll;
    private LinearLayout crumbs;
    private View home;
    private RecyclerView list;
    private View empty;
    private TextView emptyText;
    private View permScreen;
    private FloatingActionButton fab;
    private View pasteBar;
    private TextView pasteText;
    private View pasteGo;

    // where we are: home (all null), a real folder, a category listing, or search results
    private File currentDir;
    private MediaQuery.Category category;
    private String query;
    private boolean searchOpen;

    // ------------------------------------------------------------------ lifecycle

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_files, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle state) {
        super.onViewCreated(v, state);
        prefs = new Prefs(requireContext());
        hub = FastShareApp.hub(requireContext());

        toolbar = v.findViewById(R.id.toolbar);
        searchRow = v.findViewById(R.id.searchRow);
        searchInput = v.findViewById(R.id.searchInput);
        crumbScroll = v.findViewById(R.id.crumbScroll);
        crumbs = v.findViewById(R.id.crumbs);
        home = v.findViewById(R.id.home);
        list = v.findViewById(R.id.list);
        empty = v.findViewById(R.id.empty);
        emptyText = v.findViewById(R.id.emptyText);
        permScreen = v.findViewById(R.id.permScreen);
        fab = v.findViewById(R.id.fab);
        pasteBar = v.findViewById(R.id.pasteBar);
        pasteText = v.findViewById(R.id.pasteText);
        pasteGo = v.findViewById(R.id.pasteGo);

        adapter = new FileAdapter(requireContext(), selected, this);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        applyViewMode();

        toolbar.setNavigationOnClickListener(x -> {
            if (!selected.isEmpty()) clearSelection();
            else if (searchOpen) closeSearch();
            else goUp();
        });
        toolbar.setOnMenuItemClickListener(this::onMenu);
        searchInput.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                runSearch(searchInput.getText().toString());
                hideKeyboard();
                return true;
            }
            return false;
        });
        fab.setOnClickListener(x -> showCreateDialog());
        v.findViewById(R.id.pasteCancel).setOnClickListener(x -> {
            clipboard.clear();
            render();
        });
        pasteGo.setOnClickListener(x -> paste());
        v.findViewById(R.id.permButton).setOnClickListener(x -> askForAccess());
        v.findViewById(R.id.storageCard).setOnClickListener(x -> openDir(primaryRoot()));

        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null && !isHidden()) {
            consumeNav();
            render();
        }
    }

    /** Opens whatever another screen (such as Storage) asked the Files tab to show. */
    void consumeNav() {
        File d = Nav.dir;
        MediaQuery.Category c = Nav.category;
        if (d == null && c == null) return;
        Nav.dir = null;
        Nav.category = null;
        if (getView() == null) return;
        if (c != null) openCategory(c); else openDir(d);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) render();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        loadCancel.set(true);
        gen++;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ storage helpers

    private static File primaryRoot() {
        return Volumes.primary();
    }

    private List<File> roots() {
        return Volumes.roots(requireContext());
    }

    private File rootOf(File f) {
        return Volumes.rootOf(roots(), f);
    }

    private String rootLabel(File root) {
        return Volumes.label(requireContext(), root);
    }

    private static boolean hasAccess() {
        return Access.allFiles();
    }

    private void askForAccess() {
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + requireContext().getPackageName()));
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ------------------------------------------------------------------ navigation

    public void openDir(File dir) {
        currentDir = dir;
        category = null;
        query = null;
        searchOpen = false;
        selected.clear();
        render();
    }

    private void openCategory(MediaQuery.Category c) {
        currentDir = null;
        category = c;
        query = null;
        searchOpen = false;
        selected.clear();
        render();
    }

    private void goHome() {
        currentDir = null;
        category = null;
        query = null;
        searchOpen = false;
        selected.clear();
        render();
    }

    private void goUp() {
        if (category != null || currentDir == null) {
            goHome();
            return;
        }
        File parent = currentDir.getParentFile();
        if (currentDir.equals(rootOf(currentDir)) || parent == null) goHome();
        else openDir(parent);
    }

    /** Called by the activity for the system Back button. Returns true if it was used here. */
    public boolean handleBack() {
        if (!isAdded() || isHidden() || getView() == null) return false;
        if (!selected.isEmpty()) {
            clearSelection();
            return true;
        }
        if (searchOpen) {
            closeSearch();
            return true;
        }
        if (hasAccess() && (currentDir != null || category != null)) {
            goUp();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ rendering

    /** List or thumbnail grid, as chosen in the menu. */
    private void applyViewMode() {
        boolean grid = prefs.grid();
        if (adapter.isGrid() == grid && list.getLayoutManager() != null
                && (list.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager) == grid) return;
        adapter.setGrid(grid);
        if (grid) {
            float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
            list.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(requireContext(), Math.max(3, (int) (widthDp / 120))));
        } else {
            list.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
    }

    private void render() {
        if (!isAdded() || getView() == null) return;
        applyViewMode();
        boolean access = hasAccess();
        permScreen.setVisibility(access ? View.GONE : View.VISIBLE);
        if (!access) {
            home.setVisibility(View.GONE);
            list.setVisibility(View.GONE);
            empty.setVisibility(View.GONE);
            crumbScroll.setVisibility(View.GONE);
            searchRow.setVisibility(View.GONE);
            pasteBar.setVisibility(View.GONE);
            fab.hide();
            toolbar.getMenu().clear();
            toolbar.setNavigationIcon(null);
            toolbar.setTitle("Files");
            return;
        }

        boolean atHome = currentDir == null && category == null && query == null;
        home.setVisibility(atHome ? View.VISIBLE : View.GONE);
        list.setVisibility(atHome ? View.GONE : View.VISIBLE);
        empty.setVisibility(View.GONE);
        searchRow.setVisibility(searchOpen ? View.VISIBLE : View.GONE);
        boolean inFolder = currentDir != null && category == null && query == null;
        crumbScroll.setVisibility(inFolder ? View.VISIBLE : View.GONE);
        if (inFolder) buildCrumbs();
        if (inFolder && selected.isEmpty()) fab.show(); else fab.hide();
        updatePasteBar();
        updateToolbar();
        if (atHome) refreshHome(); else reload();
    }

    private void updatePasteBar() {
        if (clipboard.isEmpty()) {
            pasteBar.setVisibility(View.GONE);
            return;
        }
        pasteBar.setVisibility(View.VISIBLE);
        String verb = clipboardCut ? "move" : "copy";
        boolean canPaste = currentDir != null && category == null && query == null;
        pasteText.setText(canPaste
                ? Fmt.plural(clipboard.size(), "item", "items") + " to " + verb
                : "Open a folder to " + verb + " " + Fmt.plural(clipboard.size(), "item", "items") + " into");
        pasteGo.setEnabled(canPaste);
    }

    private void updateToolbar() {
        Menu m = toolbar.getMenu();
        m.clear();
        if (!selected.isEmpty()) {
            toolbar.inflateMenu(R.menu.files_selection);
            toolbar.setTitle(String.valueOf(selected.size()));
            toolbar.setNavigationIcon(R.drawable.ic_close);
            toolbar.setNavigationContentDescription("Cancel selection");
            boolean one = selected.size() == 1;
            m.findItem(R.id.action_favorite).setTitle(allFavorites(selectedFiles()) ? "Remove from favorites" : "Add to favorites");
            m.findItem(R.id.action_rename).setTitle(one ? "Rename..." : "Rename " + selected.size() + " files...");
            File f = one ? new File(selected.iterator().next()) : null;
            m.findItem(R.id.action_extract).setVisible(one && f != null && f.isFile()
                    && Archives.formatOf(f.getName()) != null);
            return;
        }
        toolbar.inflateMenu(R.menu.files_main);
        m.findItem(R.id.action_hidden).setChecked(prefs.showHidden());
        boolean atHome = currentDir == null && category == null && query == null;
        boolean inRealFolder = currentDir != null && category == null && query == null;
        m.findItem(R.id.action_pin_folder).setVisible(inRealFolder);
        m.findItem(R.id.action_view_mode).setVisible(!atHome);
        m.findItem(R.id.action_view_mode).setTitle(prefs.grid() ? "List view" : "Grid view");
        if (inRealFolder) {
            m.findItem(R.id.action_pin_folder).setTitle(prefs.favorites().contains(currentDir.getPath())
                    ? "Remove folder from favorites" : "Add folder to favorites");
        }
        m.findItem(R.id.action_sort).setVisible(!atHome && query == null && category == null);
        if (atHome) {
            toolbar.setTitle("Files");
            toolbar.setNavigationIcon(null);
        } else {
            String title;
            if (query != null) title = "Results for “" + query + "”";
            else if (category != null) title = category.title;
            else title = currentDir.equals(rootOf(currentDir)) ? rootLabel(currentDir) : currentDir.getName();
            toolbar.setTitle(title);
            toolbar.setNavigationIcon(R.drawable.ic_back);
            toolbar.setNavigationContentDescription("Back");
        }
    }

    private void buildCrumbs() {
        crumbs.removeAllViews();
        File root = rootOf(currentDir);
        List<File> chain = new ArrayList<>();
        for (File f = currentDir; f != null; f = f.getParentFile()) {
            chain.add(0, f);
            if (f.equals(root)) break;
        }
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            if (i > 0) {
                ImageView sep = new ImageView(requireContext());
                sep.setImageResource(R.drawable.ic_chevron);
                sep.setColorFilter(ContextCompat.getColor(requireContext(), R.color.fs_muted));
                crumbs.addView(sep, new LinearLayout.LayoutParams(dp(18), dp(18)));
            }
            TextView t = new TextView(requireContext());
            t.setText(i == 0 ? rootLabel(root) : f.getName());
            t.setTextSize(14);
            boolean last = i == chain.size() - 1;
            t.setTextColor(ContextCompat.getColor(requireContext(), last ? R.color.fs_text : R.color.fs_primary));
            if (last) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            if (!last) t.setOnClickListener(x -> openDir(f));
            crumbs.addView(t);
        }
        crumbScroll.post(() -> ((android.widget.HorizontalScrollView) crumbScroll).fullScroll(View.FOCUS_RIGHT));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ loading

    private void reload() {
        final int g = ++gen;
        final File dir = currentDir;
        final MediaQuery.Category cat = category;
        final String q = query;
        final boolean hidden = prefs.showHidden();
        final FileOps.Sort sort = prefs.sort();
        final boolean asc = prefs.ascending();
        final Context appCtx = requireContext().getApplicationContext();
        loadCancel.set(true);                 // stop any search still walking folders
        final AtomicBoolean myCancel = new AtomicBoolean();
        loadCancel = myCancel;
        try {
            io.execute(() -> {
                List<FileOps.Item> out;
                if (q != null) {
                    out = FileOps.search(dir != null ? dir : primaryRoot(), q, hidden, 500, myCancel);
                } else if (cat != null) {
                    out = MediaQuery.items(appCtx, cat, hidden, 1000);
                } else {
                    out = FileOps.list(dir, hidden, sort, asc);
                }
                final List<FileOps.Item> result = out;
                ui.post(() -> {
                    if (g != gen || !isAdded() || getView() == null) return;
                    Set<String> present = new HashSet<>();
                    for (FileOps.Item it : result) present.add(it.file.getPath());
                    if (selected.retainAll(present)) updateToolbar();
                    adapter.setItems(result, q != null || cat != null);
                    if (result.isEmpty()) {
                        emptyText.setText(q != null ? "No files match “" + q + "”"
                                : cat != null ? "No " + cat.title.toLowerCase() + " found" : "This folder is empty");
                        empty.setVisibility(View.VISIBLE);
                    } else {
                        empty.setVisibility(View.GONE);
                    }
                });
            });
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ home screen

    private void refreshHome() {
        View v = getView();
        if (v == null) return;
        File root = primaryRoot();

        TextView title = v.findViewById(R.id.storageTitle);
        TextView sub = v.findViewById(R.id.storageSub);
        LinearProgressIndicator bar = v.findViewById(R.id.storageBar);
        title.setText("Internal storage");
        try {
            StatFs fs = new StatFs(root.getPath());
            long total = fs.getTotalBytes();
            long free = fs.getAvailableBytes();
            if (total > 0) {
                long used = total - free;
                sub.setText(Fmt.bytes(free) + " free of " + Fmt.bytes(total));
                bar.setProgress((int) (used * 1000 / total));
                bar.setVisibility(View.VISIBLE);
            } else {
                sub.setText("");
                bar.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            sub.setText("");
            bar.setVisibility(View.GONE);
        }

        LinearLayout quick = v.findViewById(R.id.quickGrid);
        quick.removeAllViews();
        List<Object[]> tiles = new ArrayList<>();
        addQuick(tiles, "Downloads", R.drawable.ic_download, R.color.kind_document, new File(root, "Download"), true);
        addQuick(tiles, "Camera", R.drawable.ic_camera, R.color.kind_image, new File(root, "DCIM/Camera"), false);
        addQuick(tiles, "Pictures", R.drawable.ic_image, R.color.kind_image, new File(root, "Pictures"), false);
        addQuick(tiles, "Screenshots", R.drawable.ic_phone, R.color.kind_video, new File(root, "Pictures/Screenshots"), false);
        addQuick(tiles, "Documents", R.drawable.ic_doc, R.color.kind_document, new File(root, "Documents"), false);
        addQuick(tiles, "Music", R.drawable.ic_audio, R.color.kind_audio, new File(root, "Music"), false);
        addQuick(tiles, "Movies", R.drawable.ic_video, R.color.kind_video, new File(root, "Movies"), false);
        addQuick(tiles, "From iPhone", R.drawable.ic_swap, R.color.kind_apk, new File(root, "Download/FileBandit"), true);
        fillGrid(quick, tiles, 4);

        refreshFavorites(v);
        refreshRecent(v);

        LinearLayout cats = v.findViewById(R.id.catGrid);
        cats.removeAllViews();
        List<Object[]> catTiles = new ArrayList<>();
        for (final MediaQuery.Category c : MediaQuery.Category.values()) {
            if (c == MediaQuery.Category.RECENT) continue;       // has its own section above
            catTiles.add(new Object[]{c.title, c.icon, Kinds.color(requireContext(), c.kind), (Runnable) () -> openCategory(c)});
        }
        catTiles.add(new Object[]{"Trash", R.drawable.ic_delete, ContextCompat.getColor(requireContext(), R.color.kind_other),
                (Runnable) () -> startActivity(new Intent(requireContext(), TrashActivity.class))});
        fillGrid(cats, catTiles, 3);

        LinearLayout tools = v.findViewById(R.id.toolsGrid);
        tools.removeAllViews();
        List<Object[]> toolTiles = new ArrayList<>();
        toolTiles.add(new Object[]{"Storage", R.drawable.ic_storage, ContextCompat.getColor(requireContext(), R.color.kind_archive),
                (Runnable) () -> startActivity(new Intent(requireContext(), StorageActivity.class))});
        toolTiles.add(new Object[]{"Vault", R.drawable.ic_lock, ContextCompat.getColor(requireContext(), R.color.kind_audio),
                (Runnable) () -> startActivity(new Intent(requireContext(), VaultActivity.class))});
        toolTiles.add(new Object[]{"Apps", R.drawable.ic_apk, ContextCompat.getColor(requireContext(), R.color.kind_apk),
                (Runnable) () -> startActivity(new Intent(requireContext(), AppsActivity.class))});
        toolTiles.add(new Object[]{"Network", R.drawable.ic_cloud, ContextCompat.getColor(requireContext(), R.color.kind_document),
                (Runnable) () -> startActivity(new Intent(requireContext(), NetworkActivity.class))});
        toolTiles.add(new Object[]{"Backup", R.drawable.ic_upload, ContextCompat.getColor(requireContext(), R.color.kind_image),
                (Runnable) () -> startActivity(new Intent(requireContext(), BackupActivity.class))});
        toolTiles.add(new Object[]{"Clean photos", R.drawable.ic_image, ContextCompat.getColor(requireContext(), R.color.kind_video),
                (Runnable) () -> startActivity(new Intent(requireContext(), SwipeCleanActivity.class))});
        fillGrid(tools, toolTiles, 4);

        LinearLayout vols = v.findViewById(R.id.volumes);
        vols.removeAllViews();
        List<File> all = roots();
        v.findViewById(R.id.volumesTitle).setVisibility(all.size() > 1 ? View.VISIBLE : View.GONE);
        for (int i = 1; i < all.size(); i++) {
            final File r = all.get(i);
            View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_volume, vols, false);
            ((TextView) row.findViewById(R.id.volName)).setText(rootLabel(r));
            try {
                StatFs fs = new StatFs(r.getPath());
                ((TextView) row.findViewById(R.id.volSub)).setText(Fmt.bytes(fs.getAvailableBytes()) + " free of " + Fmt.bytes(fs.getTotalBytes()));
            } catch (Exception ignored) { }
            row.setOnClickListener(x -> openDir(r));
            vols.addView(row);
        }
    }

    private void addQuick(List<Object[]> tiles, String label, int icon, int colorRes, final File dir, boolean always) {
        if (!always && !dir.isDirectory()) return;
        tiles.add(new Object[]{label, icon, ContextCompat.getColor(requireContext(), colorRes), (Runnable) () -> {
            if (!dir.exists()) dir.mkdirs();
            openDir(dir);
        }});
    }

    private void fillGrid(LinearLayout grid, List<Object[]> tiles, int perRow) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Object[] t = tiles.get(i);
            View tile = inf.inflate(R.layout.item_tile, row, false);
            ImageView icon = tile.findViewById(R.id.tileIcon);
            icon.setImageResource((Integer) t[1]);
            icon.setColorFilter((Integer) t[2]);
            icon.setBackground(Kinds.badge((Integer) t[2], dp(26), true));
            ((TextView) tile.findViewById(R.id.tileLabel)).setText((String) t[0]);
            final Runnable action = (Runnable) t[3];
            tile.setOnClickListener(x -> action.run());
            if (t.length > 4) {
                final Runnable longAction = (Runnable) t[4];
                tile.setOnLongClickListener(x -> {
                    longAction.run();
                    return true;
                });
            }
            row.addView(tile);
        }
        // keep the last row's tiles the same width as the others
        if (row != null) {
            for (int k = row.getChildCount(); k < perRow; k++) {
                View filler = new View(requireContext());
                row.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    // ------------------------------------------------------------------ favorites and recent files

    private boolean allFavorites(List<File> files) {
        List<String> favs = prefs.favorites();
        for (File f : files) if (!favs.contains(f.getPath())) return false;
        return !files.isEmpty();
    }

    /** Adds the files to favorites, or removes them if they all are favorites already. */
    private void toggleFavorites(List<File> files) {
        List<String> favs = prefs.favorites();
        boolean remove = allFavorites(files);
        for (File f : files) {
            if (remove) favs.remove(f.getPath());
            else if (!favs.contains(f.getPath())) favs.add(f.getPath());
        }
        prefs.setFavorites(favs);
        Toast.makeText(requireContext(), remove ? "Removed from favorites" : "Added to favorites", Toast.LENGTH_SHORT).show();
    }

    private void refreshFavorites(View v) {
        LinearLayout grid = v.findViewById(R.id.favGrid);
        grid.removeAllViews();
        List<Object[]> tiles = new ArrayList<>();
        List<String> favs = prefs.favorites();
        List<String> alive = new ArrayList<>();
        for (final String path : favs) {
            final File f = new File(path);
            if (!f.exists()) continue;                                 // deleted or moved elsewhere
            alive.add(path);
            FileOps.Kind kind = f.isDirectory() ? FileOps.Kind.FOLDER : FileOps.kindOfName(f.getName());
            tiles.add(new Object[]{f.getName(), Kinds.icon(kind), Kinds.color(requireContext(), kind),
                    (Runnable) () -> openAny(f),
                    (Runnable) () -> {
                        List<String> now = prefs.favorites();
                        now.remove(path);
                        prefs.setFavorites(now);
                        Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show();
                        refreshHome();
                    }});
        }
        if (alive.size() != favs.size()) prefs.setFavorites(alive);
        v.findViewById(R.id.favTitle).setVisibility(tiles.isEmpty() ? View.GONE : View.VISIBLE);
        grid.setVisibility(tiles.isEmpty() ? View.GONE : View.VISIBLE);
        if (!tiles.isEmpty()) fillGrid(grid, tiles, 4);
    }

    private void refreshRecent(final View v) {
        final LinearLayout listView = v.findViewById(R.id.recentList);
        final View header = v.findViewById(R.id.recentHeader);
        v.findViewById(R.id.recentAll).setOnClickListener(x -> openCategory(MediaQuery.Category.RECENT));
        final Context appCtx = requireContext().getApplicationContext();
        final boolean hidden = prefs.showHidden();
        final int g = ++homeGen;
        io.execute(() -> {
            final List<FileOps.Item> recent = MediaQuery.items(appCtx, MediaQuery.Category.RECENT, hidden, 5);
            ui.post(() -> {
                if (g != homeGen || !isAdded() || getView() == null) return;
                listView.removeAllViews();
                header.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
                LayoutInflater inf = LayoutInflater.from(requireContext());
                for (final FileOps.Item item : recent) {
                    View row = inf.inflate(R.layout.item_file, listView, false);
                    ((TextView) row.findViewById(R.id.name)).setText(item.name);
                    File parent = item.file.getParentFile();
                    ((TextView) row.findViewById(R.id.sub)).setText(Fmt.bytes(item.size) + "  \u00b7  "
                            + Fmt.ago(item.modified, System.currentTimeMillis())
                            + (parent == null ? "" : "  \u00b7  " + parent.getName()));
                    FileAdapter.styleIcon(requireContext(), (ImageView) row.findViewById(R.id.icon), item);
                    row.setOnClickListener(x -> openAny(item.file));
                    listView.addView(row);
                }
            });
        });
    }

    private int homeGen;

    /** Opens a folder inside the file manager, or a file with its viewer. */
    private void openAny(File f) {
        if (f.isDirectory()) openDir(f); else Viewer.open(requireActivity(), f, null);
    }

    // ------------------------------------------------------------------ selection and clicks

    @Override
    public void onClick(FileOps.Item item) {
        if (!selected.isEmpty()) {
            toggle(item);
            return;
        }
        if (item.dir) {
            openDir(item.file);
        } else if (Archives.formatOf(item.name) != null) {
            final File archive = item.file;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(item.name)
                    .setItems(new String[]{"Extract here", "Open with another app"}, (d, which) -> {
                        if (which == 0) extract(archive); else FileActions.open(requireActivity(), archive);
                    }).show();
        } else {
            List<File> neighbours = new ArrayList<>();
            for (FileOps.Item it : adapter.items()) if (!it.dir) neighbours.add(it.file);
            Viewer.open(requireActivity(), item.file, neighbours);
        }
    }

    @Override
    public void onLongClick(FileOps.Item item) {
        toggle(item);
    }

    private void toggle(FileOps.Item item) {
        String p = item.file.getPath();
        if (!selected.remove(p)) selected.add(p);
        selectionChanged();
    }

    private void selectionChanged() {
        adapter.notifyDataSetChanged();
        updateToolbar();
        boolean inFolder = currentDir != null && category == null && query == null;
        if (inFolder && selected.isEmpty()) fab.show(); else fab.hide();
    }

    private void clearSelection() {
        selected.clear();
        selectionChanged();
    }

    private List<File> selectedFiles() {
        List<File> out = new ArrayList<>();
        for (String p : selected) out.add(new File(p));
        return out;
    }

    // ------------------------------------------------------------------ toolbar actions

    private boolean onMenu(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.action_search) {
            openSearch();
        } else if (id == R.id.action_sort) {
            showSortDialog();
        } else if (id == R.id.action_hidden) {
            prefs.setShowHidden(!prefs.showHidden());
            render();
        } else if (id == R.id.action_refresh) {
            render();
        } else if (id == R.id.action_send) {
            sendToIphone();
        } else if (id == R.id.action_copy || id == R.id.action_cut) {
            clipboard.clear();
            clipboard.addAll(selectedFiles());
            clipboardCut = id == R.id.action_cut;
            Toast.makeText(requireContext(), "Open the destination folder, then tap Paste here", Toast.LENGTH_LONG).show();
            clearSelection();
            updatePasteBar();
        } else if (id == R.id.action_delete) {
            trashFiles(selectedFiles());
        } else if (id == R.id.action_delete_forever) {
            confirmDelete(selectedFiles());
        } else if (id == R.id.action_select_all) {
            for (FileOps.Item it : adapter.items()) selected.add(it.file.getPath());
            selectionChanged();
        } else if (id == R.id.action_share) {
            FileActions.share(requireActivity(), selectedFiles());
        } else if (id == R.id.action_rename) {
            if (selected.size() == 1) showRenameDialog(new File(selected.iterator().next()));
            else showBatchRename(selectedFiles());
        } else if (id == R.id.action_view_mode) {
            prefs.setGrid(!prefs.grid());
            render();
        } else if (id == R.id.action_invert) {
            Set<String> inverted = new HashSet<>();
            for (FileOps.Item it : adapter.items()) if (!selected.contains(it.file.getPath())) inverted.add(it.file.getPath());
            selected.clear();
            selected.addAll(inverted);
            selectionChanged();
        } else if (id == R.id.action_select_type) {
            showSelectTypeDialog();
        } else if (id == R.id.action_favorite) {
            toggleFavorites(selectedFiles());
            clearSelection();
        } else if (id == R.id.action_pin_folder) {
            toggleFavorites(java.util.Collections.singletonList(currentDir));
            updateToolbar();
        } else if (id == R.id.action_upload_network) {
            chooseNetworkLocation(selectedFiles());
        } else if (id == R.id.action_vault) {
            confirmMoveToVault(selectedFiles());
        } else if (id == R.id.action_compress) {
            compress(selectedFiles());
        } else if (id == R.id.action_extract) {
            extract(new File(selected.iterator().next()));
        } else if (id == R.id.action_details) {
            showDetails(selectedFiles());
        } else {
            return false;
        }
        return true;
    }

    private void sendToIphone() {
        List<File> files = selectedFiles();
        hub.start();
        hub.addFiles(files);
        clearSelection();
        Toast.makeText(requireContext(),
                "Added " + Fmt.plural(files.size(), "item", "items") + ". Now open the link on the iPhone.", Toast.LENGTH_LONG).show();
        ((MainActivity) requireActivity()).showTransfer();
    }

    // ------------------------------------------------------------------ search

    private void openSearch() {
        searchOpen = true;
        searchRow.setVisibility(View.VISIBLE);
        searchInput.setText(query == null ? "" : query);
        searchInput.setHint(currentDir != null ? "Search in " + currentDir.getName() : "Search all files");
        searchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void runSearch(String text) {
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) return;
        query = q;
        selected.clear();
        render();
    }

    private void closeSearch() {
        searchOpen = false;
        hideKeyboard();
        boolean hadResults = query != null;
        query = null;
        if (hadResults) selected.clear();
        render();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ dialogs

    private void showSortDialog() {
        final String[] labels = {"Name (A to Z)", "Name (Z to A)", "Newest first", "Oldest first",
                "Largest first", "Smallest first", "Type"};
        final FileOps.Sort[] sorts = {FileOps.Sort.NAME, FileOps.Sort.NAME, FileOps.Sort.DATE, FileOps.Sort.DATE,
                FileOps.Sort.SIZE, FileOps.Sort.SIZE, FileOps.Sort.TYPE};
        final boolean[] asc = {true, false, false, true, false, true, true};
        int checked = 0;
        for (int i = 0; i < sorts.length; i++) {
            if (sorts[i] == prefs.sort() && asc[i] == prefs.ascending()) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Sort by")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.setSort(sorts[which], asc[which]);
                    d.dismiss();
                    render();
                }).show();
    }

    private void showCreateDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setItems(new String[]{"New folder", "New text file"}, (d, which) -> {
                    if (which == 0) askName("New folder", "Folder name", "", name -> FileOps.newFolder(currentDir, name));
                    else askName("New text file", "File name", "note.txt", name -> {
                        String err = FileOps.checkName(name);
                        if (err != null) throw new java.io.IOException(err);
                        File f = new File(currentDir, name.trim());
                        if (f.exists()) throw new java.io.IOException("\"" + name.trim() + "\" already exists");
                        if (!f.createNewFile()) throw new java.io.IOException("Couldn't create the file");
                        return f;
                    });
                }).show();
    }

    private void showRenameDialog(final File f) {
        askName("Rename", "New name", f.getName(), name -> {
            final File renamed = FileOps.rename(f, name);
            final String oldName = f.getName();
            pendingUndo = () -> {
                FileOps.rename(renamed, oldName);
                return "Renamed back to " + oldName;
            };
            pendingUndoMessage = "Renamed to " + renamed.getName();
            return renamed;
        });
    }

    /** Picks which saved server to send the selection to; the upload itself happens in the server's own screen. */
    private void chooseNetworkLocation(final List<File> files) {
        final List<Connection> saved = RemoteSession.store(requireContext()).list();
        if (saved.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("No network locations yet")
                    .setMessage("Add a computer, NAS or server first, then send files to it from here.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add one", (d, w) -> startActivity(new Intent(requireContext(), NetworkActivity.class))).show();
            return;
        }
        String[] names = new String[saved.size()];
        for (int i = 0; i < names.length; i++) names[i] = saved.get(i).name.isEmpty() ? saved.get(i).host : saved.get(i).name;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Upload " + Fmt.plural(files.size(), "item", "items") + " to…")
                .setItems(names, (d, which) -> {
                    RemoteSession.pendingUploads.clear();
                    RemoteSession.pendingUploads.addAll(files);
                    clearSelection();
                    startActivity(new Intent(requireContext(), RemoteActivity.class).putExtra(RemoteActivity.EXTRA_ID, saved.get(which).id));
                }).show();
    }

    private void confirmMoveToVault(final List<File> files) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Move " + Fmt.plural(files.size(), "item", "items") + " to the private vault?")
                .setMessage("They are encrypted with your PIN, then the originals are deleted. If you forget the PIN, they can't be recovered.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> {
                    VaultSession.pending.clear();
                    VaultSession.pending.addAll(files);
                    clearSelection();
                    startActivity(new Intent(requireContext(), VaultActivity.class));
                }).show();
    }

    private void showSelectTypeDialog() {
        final String[] labels = {"Folders", "Photos", "Videos", "Audio", "Documents", "Archives", "Apps", "Everything"};
        final FileOps.Kind[] kinds = {FileOps.Kind.FOLDER, FileOps.Kind.IMAGE, FileOps.Kind.VIDEO, FileOps.Kind.AUDIO,
                FileOps.Kind.DOCUMENT, FileOps.Kind.ARCHIVE, FileOps.Kind.APK, null};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select all")
                .setItems(labels, (d, which) -> {
                    for (FileOps.Item it : adapter.items()) {
                        boolean match = kinds[which] == null || it.kind == kinds[which]
                                || (kinds[which] == FileOps.Kind.DOCUMENT && it.kind == FileOps.Kind.TEXT);
                        if (match) selected.add(it.file.getPath());
                    }
                    selectionChanged();
                }).show();
    }

    private void showBatchRename(final List<File> files) {
        BatchRenameDialog.show(requireContext(), files, undoPairs -> {
            clearSelection();
            render();
            showUndo("Renamed " + Fmt.plural(undoPairs.size(), "file", "files"), () -> {
                BatchRename.renamePairs(undoPairs);
                return "Names restored";
            });
        });
    }

    private Undo pendingUndo;
    private String pendingUndoMessage;

    private interface NameAction {
        File run(String name) throws Exception;
    }

    private void askName(String title, String hint, String initial, final NameAction action) {
        final EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(initial);
        int dot = initial.lastIndexOf('.');
        input.setSelection(0, dot > 0 ? dot : initial.length());
        FrameHolder holder = new FrameHolder(requireContext(), input);
        final AlertDialog dlg = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(holder)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", null)
                .create();
        dlg.setOnShowListener(d -> {
            input.requestFocus();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                try {
                    action.run(input.getText().toString());
                    dlg.dismiss();
                    clearSelection();
                    render();
                    if (pendingUndo != null) {
                        showUndo(pendingUndoMessage, pendingUndo);
                        pendingUndo = null;
                    }
                } catch (Exception e) {
                    input.setError(e.getMessage());
                }
            });
        });
        dlg.show();
    }

    /** Wraps a field in a frame with dialog-sized side padding. */
    private static final class FrameHolder extends android.widget.FrameLayout {
        FrameHolder(Context c, View child) {
            super(c);
            int p = (int) (24 * c.getResources().getDisplayMetrics().density);
            setPadding(p, p / 3, p, 0);
            addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    /** Moves files to the recycle bin; "Undo" puts them straight back. */
    private void trashFiles(final List<File> files) {
        final List<File> roots = roots();
        runJob("Moving to trash\u2026", job -> {
            final List<Trash> owners = new ArrayList<>();
            final List<Trash.Entry> entries = new ArrayList<>();
            String error = null;
            for (File f : files) {
                if (job.cancel.get()) break;
                job.label = f.getName();
                try {
                    Trash t = new Trash(Volumes.rootOf(roots, f));
                    entries.add(t.trash(f));
                    owners.add(t);
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            if (!entries.isEmpty()) {
                job.undo = () -> {
                    int n = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        owners.get(i).restore(entries.get(i));
                        n++;
                    }
                    return "Restored " + Fmt.plural(n, "item", "items");
                };
            }
            String msg = entries.isEmpty() ? "Nothing was deleted" : "Moved " + Fmt.plural(entries.size(), "item", "items") + " to trash";
            return error == null ? msg : msg + ". " + error;
        });
    }

    private void confirmDelete(final List<File> files) {
        String what = files.size() == 1 ? "“" + files.get(0).getName() + "”" : Fmt.plural(files.size(), "item", "items");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete " + what + " permanently?")
                .setMessage("It will not go to the trash. This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> runJob("Deleting…", job -> {
                    int failed = 0;
                    for (File f : files) {
                        if (job.cancel.get()) break;
                        job.label = f.getName();
                        if (!FileOps.delete(f)) failed++;
                    }
                    return failed == 0 ? "Deleted " + Fmt.plural(files.size(), "item", "items")
                            : "Couldn't delete " + Fmt.plural(failed, "item", "items");
                })).show();
    }

    private void showDetails(final List<File> files) {
        final boolean single = files.size() == 1;
        final StringBuilder base = new StringBuilder();
        if (single) {
            File f = files.get(0);
            base.append("Name: ").append(f.getName()).append('\n');
            base.append("Type: ").append(f.isDirectory() ? "Folder" : FileActions.mimeFor(f)).append('\n');
            base.append("Location: ").append(f.getParent()).append('\n');
            base.append("Modified: ").append(Fmt.dateTime(f.lastModified())).append('\n');
        } else {
            base.append(Fmt.plural(files.size(), "item", "items")).append(" selected\n");
        }
        final AlertDialog dlg = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Details")
                .setMessage(base + "Size: calculating…")
                .setPositiveButton("OK", null)
                .show();
        io.execute(() -> {
            long total = 0;
            for (File f : files) total += FileOps.sizeOf(f, null);
            final String size = Fmt.bytes(total);
            ui.post(() -> {
                if (dlg.isShowing()) dlg.setMessage(base + "Size: " + size);
            });
        });
    }

    // ------------------------------------------------------------------ copy, move, extract (background jobs)

    private void paste() {
        final File dest = currentDir;
        if (dest == null || clipboard.isEmpty()) return;
        final List<File> files = new ArrayList<>(clipboard);
        final boolean cut = clipboardCut;
        runJob(cut ? "Moving…" : "Copying…", job -> {
            long total = 0;
            for (File f : files) total += FileOps.sizeOf(f, job.cancel);
            job.total = total;
            int done = 0;
            StringBuilder errors = new StringBuilder();
            final List<File> results = new ArrayList<>();
            final List<File> origins = new ArrayList<>();
            for (File f : files) {
                if (job.cancel.get()) break;
                job.label = f.getName();
                try {
                    File made = cut ? FileOps.move(f, dest, job::addBytes, job.cancel)
                            : FileOps.copy(f, dest, job::addBytes, job.cancel);
                    results.add(made);
                    origins.add(f);
                    done++;
                } catch (Exception e) {
                    if (errors.length() > 0) errors.append('\n');
                    errors.append(e.getMessage());
                }
            }
            if (cut && errors.length() == 0) {
                ui.post(clipboard::clear);
            }
            if (!results.isEmpty()) {
                job.undo = () -> {
                    for (int i = 0; i < results.size(); i++) {
                        if (cut) FileOps.move(results.get(i), origins.get(i).getParentFile(), null, null);
                        else FileOps.delete(results.get(i));
                    }
                    return cut ? "Moved back" : "Copy removed";
                };
            }
            String verb = cut ? "Moved " : "Copied ";
            return errors.length() == 0 ? verb + Fmt.plural(done, "item", "items")
                    : verb + done + " of " + files.size() + ". " + errors;
        });
    }

    private void extract(final File archive) {
        final File dest = archive.getParentFile();
        runJob("Extracting\u2026", job -> {
            job.label = archive.getName();
            job.total = Archives.formatOf(archive.getName()) == Archives.Format.ZIP ? expandedSize(archive) : archive.length();
            final File out = Archives.extract(archive, dest, job.cancel, job::addBytes);
            job.undo = () -> {
                FileOps.delete(out);
                return "Removed \u201c" + out.getName() + "\u201d";
            };
            return "Extracted to \u201c" + out.getName() + "\u201d";
        });
    }

    private static long expandedSize(File zip) {
        long total = 0;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) total += Math.max(0, en.nextElement().getSize());
        } catch (Exception ignored) { }
        return total;
    }

    private void compress(final List<File> files) {
        if (files.isEmpty() || currentDir == null && category == null && query == null) return;
        final File dest = files.get(0).getParentFile();
        String suggestion = files.size() == 1 ? files.get(0).getName() : "Archive";
        askName("Compress to zip", "Zip name", suggestion + ".zip", name -> {
            String err = FileOps.checkName(name);
            if (err != null) throw new java.io.IOException(err);
            final String zipName = name.trim();
            ui.post(() -> runJob("Compressing\u2026", job -> {
                long total = 0;
                for (File f : files) total += FileOps.sizeOf(f, job.cancel);
                job.total = total;
                job.label = zipName;
                final File zip = Archives.compressZip(files, dest, zipName, job::addBytes, job.cancel);
                job.undo = () -> {
                    FileOps.delete(zip);
                    return "Removed " + zip.getName();
                };
                return "Created \u201c" + zip.getName() + "\u201d (" + Fmt.bytes(zip.length()) + ")";
            }));
            return new File(dest, zipName);
        });
    }

    /** A snackbar with an Undo button that stays long enough to be tapped. */
    private void showUndo(String message, final Undo undo) {
        if (!isAdded() || getView() == null) return;
        Snackbar.make(requireView(), message, 8000)
                .setAction("Undo", v -> runJob("Undoing\u2026", job -> undo.run()))
                .show();
    }

    private static final class Job {
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile long total;
        volatile long done;
        volatile String label = "";
        volatile Undo undo;

        void addBytes(long n) {
            done += n;
        }
    }

    private interface JobBody {
        String run(Job job) throws Exception;
    }

    private void runJob(String title, final JobBody body) {
        final Job job = new Job();
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        box.setPadding(p, dp(8), p, 0);
        final TextView label = new TextView(requireContext());
        label.setTextColor(ContextCompat.getColor(requireContext(), R.color.fs_muted));
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        final LinearProgressIndicator bar = new LinearProgressIndicator(requireContext());
        bar.setIndeterminate(true);
        bar.setMax(1000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(label);
        box.addView(bar, lp);

        final AlertDialog dlg = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> job.cancel.set(true))
                .show();

        final Runnable[] ticker = new Runnable[1];
        ticker[0] = () -> {
            if (!dlg.isShowing()) return;
            label.setText(job.label);
            if (job.total > 0) {
                bar.setIndeterminate(false);
                bar.setProgress((int) Math.min(1000, job.done * 1000 / job.total));
            }
            ui.postDelayed(ticker[0], 150);
        };
        ui.post(ticker[0]);

        try {
            io.execute(() -> {
                String msg;
                try {
                    msg = body.run(job);
                } catch (Exception e) {
                    msg = e.getMessage() == null ? "Something went wrong" : e.getMessage();
                }
                final String result = msg;
                ui.post(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    if (!isAdded() || getView() == null) return;
                    selected.clear();
                    if (job.undo != null) showUndo(result, job.undo);
                    else Snackbar.make(requireView(), result, Snackbar.LENGTH_LONG).show();
                    render();
                });
            });
        } catch (Exception e) {
            dlg.dismiss();
        }
    }
}
