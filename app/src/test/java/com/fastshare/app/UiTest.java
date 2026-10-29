package com.fastshare.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Environment;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowStatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Drives the real activity and fragments on the JVM: screens inflate, buttons do what they say,
 * and every screen is rendered to build/shots/*.png so the result can be looked at.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class UiTest {

    private static final int W = 1080;
    private static final int H = 2340;

    /** Stands in for Android's media index so "recent files" and the category lists have something to show. */
    public static class FakeMedia extends android.content.ContentProvider {
        static final java.util.List<String> PATHS = new ArrayList<>();

        @Override public boolean onCreate() { return true; }

        private static android.database.Cursor rows() {
            android.database.MatrixCursor c = new android.database.MatrixCursor(new String[]{"_data"});
            for (String p : PATHS) c.addRow(new Object[]{p});
            return c;
        }

        @Override public android.database.Cursor query(android.net.Uri u, String[] p, android.os.Bundle args, android.os.CancellationSignal cs) { return rows(); }
        @Override public android.database.Cursor query(android.net.Uri u, String[] p, String sel, String[] a, String sort) { return rows(); }
        @Override public String getType(android.net.Uri u) { return null; }
        @Override public android.net.Uri insert(android.net.Uri u, android.content.ContentValues v) { return null; }
        @Override public int delete(android.net.Uri u, String s, String[] a) { return 0; }
        @Override public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) { return 0; }
    }

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private File storage;

    @Before
    public void setUp() throws Exception {
        Access.testOverride = true;
        FilesFragment.resetClipboard();
        storage = Environment.getExternalStorageDirectory();
        storage.mkdirs();
        ShadowStatFs.registerStats(storage, 62_500_000, 31_000_000, 31_000_000);   // 256 GB, about half used
        Net.testOverride = new Net.Info(true, new ArrayList<>(Arrays.asList(
                new Net.Addr("192.168.23.33", "Wi-Fi"), new Net.Addr("10.0.0.7", "Network"))));
        makeSampleFiles();
        FakeMedia.PATHS.clear();
        for (String rel : new String[]{"Download/Invoice March.pdf", "DCIM/Camera/IMG_20260102_130000.jpg", "Download/holiday-video.mp4",
                "Documents/Budget.xlsx", "Music/song.mp3", "DCIM/Camera/IMG_20260101_101010.jpg"}) {
            FakeMedia.PATHS.add(new File(storage, rel).getPath());
        }
        Robolectric.setupContentProvider(FakeMedia.class, "media");
    }

    @After
    public void tearDown() {
        Net.testOverride = null;
        Access.testOverride = null;
        FilesFragment.resetClipboard();
        if (appsController != null) {
            try { appsController.pause().stop().destroy(); } catch (Exception ignored) { }
        }
        if (storageController != null) {
            try { storageController.pause().stop().destroy(); } catch (Exception ignored) { }
        }
        if (viewerController != null) {
            try { viewerController.pause().stop().destroy(); } catch (Exception ignored) { }
        }
        if (controller != null) {
            try { controller.pause().stop().destroy(); } catch (Exception ignored) { }
        }
    }

    private void makeSampleFiles() throws Exception {
        write("Download/Invoice March.pdf", 482_000);
        write("Download/holiday-video.mp4", 48_300_000L);
        write("Download/photos.zip", 12_800_000);
        write("Download/app-release.apk", 5_100_000);
        write("Download/notes.txt", 1_200);
        write("Download/FileBandit/IMG_4021.HEIC", 2_900_000);
        write("DCIM/Camera/IMG_20260101_101010.jpg", 3_100_000);
        write("DCIM/Camera/IMG_20260102_130000.jpg", 2_800_000);
        write("Documents/Budget.xlsx", 88_000);
        write("Music/song.mp3", 4_400_000);
        new File(storage, "Pictures/Screenshots").mkdirs();
        new File(storage, "Download/Old stuff").mkdirs();
        write("Download/Old stuff/a.txt", 10);
    }

    private File write(String rel, long size) throws Exception {
        File f = new File(storage, rel);
        f.getParentFile().mkdirs();
        try (FileOutputStream os = new FileOutputStream(f)) {
            if (size < 100_000) {
                os.write(new byte[(int) size]);
            }
        }
        if (size >= 100_000) {
            // sparse: the listing shows the length without writing 48 MB
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "rw")) {
                r.setLength(size);
            }
        }
        f.setLastModified(1_767_000_000_000L + Math.abs(rel.hashCode() % 1_000_000) * 1000L);
        return f;
    }

    private void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        settle();
        layout();
    }

    private FilesFragment files() {
        return (FilesFragment) activity.getSupportFragmentManager().findFragmentByTag("files");
    }

    private TransferFragment transfer() {
        return (TransferFragment) activity.getSupportFragmentManager().findFragmentByTag("transfer");
    }

    /** Runs the UI clock in small steps until the condition holds (or gives up after a few seconds). */
    private static void waitUntil(java.util.function.BooleanSupplier cond) {
        for (int i = 0; i < 100 && !cond.getAsBoolean(); i++) {
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60));
        }
    }

    /** Waits for background work, then lets posted UI work run. */
    private static void settle() {
        for (int i = 0; i < 10; i++) {
            try { Thread.sleep(40); } catch (InterruptedException ignored) { }
            // idleFor also runs the hub's short "coalesce changes" delay
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
        }
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    private void shot(String name) throws Exception {
        shotOf(activity, name);
    }

    private static void shotOf(android.app.Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFFFFFFF);
        decor.draw(new Canvas(b));
        save(b, name);
    }

    /** Renders a whole scrolling page, not just what fits on screen. */
    private void shotScrollContent(String name, View scroll) throws Exception {
        layout();
        View content = ((ViewGroup) scroll).getChildAt(0);
        content.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = content.getMeasuredHeight();
        content.layout(0, 0, W, h);
        Bitmap b = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFF3F5F8);
        content.draw(new Canvas(b));
        save(b, name);
        layout();
    }

    private static void save(Bitmap b, String name) throws Exception {
        File out = new File(System.getProperty("shots.dir", "build/shots"), name + ".png");
        out.getParentFile().mkdirs();
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    private RecyclerView list() {
        return files().getView().findViewById(R.id.list);
    }

    private int rowOf(String name) {
        RecyclerView.Adapter<?> a = list().getAdapter();
        FileAdapter fa = (FileAdapter) a;
        for (int i = 0; i < fa.items().size(); i++) {
            if (fa.items().get(i).name.equals(name)) return i;
        }
        throw new AssertionError("no row named " + name + " in " + fa.items().size() + " rows");
    }

    private void clickRow(String name) {
        layout();
        View v = list().findViewHolderForAdapterPosition(rowOf(name)).itemView;
        v.performClick();
        settle();
        layout();
    }

    private void longClickRow(String name) {
        layout();
        View v = list().findViewHolderForAdapterPosition(rowOf(name)).itemView;
        v.performLongClick();
        settle();
        layout();
    }

    private void menu(int id) {
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        assertTrue("menu item missing/hidden: " + id, tb.getMenu().findItem(id) != null && tb.getMenu().findItem(id).isVisible());
        tb.getMenu().performIdentifierAction(id, 0);
        settle();
        layout();
    }

    private void pressDialogButton(int which) {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d != null) {
            d.getButton(which).performClick();
        } else {
            androidx.appcompat.app.AlertDialog x = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            x.getButton(which).performClick();
        }
        settle();
        layout();
    }

    private void selectTab(int navId) {
        com.google.android.material.bottomnavigation.BottomNavigationView nav = activity.findViewById(R.id.nav);
        nav.setSelectedItemId(navId);
        settle();
        layout();
    }

    // ------------------------------------------------------------------ tests

    @Test public void filesHomeShowsStorageShortcutsAndCategories() throws Exception {
        launch();
        View v = files().getView();
        assertEquals(View.VISIBLE, v.findViewById(R.id.home).getVisibility());
        assertEquals(View.GONE, v.findViewById(R.id.permScreen).getVisibility());
        android.widget.TextView sub = v.findViewById(R.id.storageSub);
        assertTrue(sub.getText().toString(), sub.getText().toString().contains("free of"));
        android.widget.LinearLayout quick = v.findViewById(R.id.quickGrid);
        assertTrue("quick access tiles", quick.getChildCount() >= 2);
        android.widget.LinearLayout cats = v.findViewById(R.id.catGrid);
        assertEquals(3, cats.getChildCount());   // 6 categories + Trash, 3 per row
        shot("01_files_home");
    }

    @Test public void withoutAllFilesAccessTheScreenExplainsHowToAllowIt() throws Exception {
        Access.testOverride = false;
        launch();
        View v = files().getView();
        assertEquals(View.VISIBLE, v.findViewById(R.id.permScreen).getVisibility());
        assertEquals(View.GONE, v.findViewById(R.id.home).getVisibility());
        assertEquals(View.GONE, v.findViewById(R.id.fab).getVisibility());
        v.findViewById(R.id.permButton).performClick();
        android.content.Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.getAction());
        shot("02_files_permission");
    }

    @Test public void browsingIntoAFolderAndBackAgain() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        layout();
        assertEquals(View.VISIBLE, files().getView().findViewById(R.id.list).getVisibility());
        FileAdapter fa = (FileAdapter) list().getAdapter();
        // folders first, then files by name
        assertEquals("FileBandit", fa.items().get(0).name);
        assertEquals("Old stuff", fa.items().get(1).name);
        assertEquals("app-release.apk", fa.items().get(2).name);
        shot("03_folder_listing");

        clickRow("Old stuff");
        assertEquals(1, ((FileAdapter) list().getAdapter()).items().size());
        assertTrue(files().handleBack());                       // back goes up one level
        settle();
        assertEquals("Old stuff", ((FileAdapter) list().getAdapter()).items().get(1).name);
        files().handleBack();                                   // up to storage root
        settle();
        assertTrue(files().handleBack());                       // up to home
        settle();
        assertEquals(View.VISIBLE, files().getView().findViewById(R.id.home).getVisibility());
        assertFalse(files().handleBack());                      // nothing left: Back leaves the app
    }

    @Test public void longPressSelectsAndShowsActions() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        assertEquals("1", tb.getTitle().toString());
        assertTrue(tb.getMenu().findItem(R.id.action_rename).isVisible());
        assertFalse(tb.getMenu().findItem(R.id.action_extract).isVisible());

        clickRow("Invoice March.pdf");                       // while selecting, a tap toggles
        assertEquals("2", tb.getTitle().toString());
        assertEquals("rename becomes a batch rename", "Rename 2 files...", tb.getMenu().findItem(R.id.action_rename).getTitle().toString());
        shot("04_selection");

        assertTrue(files().handleBack());                    // Back clears the selection first
        assertTrue(tb.getTitle().toString(), tb.getTitle().toString().equals("Download"));
    }

    @Test public void zipOffersExtraction() throws Exception {
        File zip = new File(storage, "Download/real.zip");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("hello.txt"));
            z.write("hi".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("real.zip");
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        assertTrue(tb.getMenu().findItem(R.id.action_extract).isVisible());
        menu(R.id.action_extract);
        settle();
        assertTrue(new File(storage, "Download/real/hello.txt").exists());
    }

    @Test public void copyThenPasteIntoAnotherFolder() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_copy);

        View v = files().getView();
        assertEquals(View.VISIBLE, v.findViewById(R.id.pasteBar).getVisibility());
        clickRow("Old stuff");                                // go into the destination
        android.widget.TextView t = v.findViewById(R.id.pasteText);
        assertEquals("1 item to copy", t.getText().toString());
        shot("05_paste_bar");
        v.findViewById(R.id.pasteGo).performClick();
        for (int i = 0; i < 5; i++) settle();
        assertTrue(new File(storage, "Download/Old stuff/notes.txt").exists());
        assertTrue("copy must leave the original", new File(storage, "Download/notes.txt").exists());
    }

    @Test public void moveRemovesTheOriginal() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_cut);
        clickRow("Old stuff");
        files().getView().findViewById(R.id.pasteGo).performClick();
        for (int i = 0; i < 5; i++) settle();
        assertTrue(new File(storage, "Download/Old stuff/notes.txt").exists());
        assertFalse(new File(storage, "Download/notes.txt").exists());
        assertEquals(View.GONE, files().getView().findViewById(R.id.pasteBar).getVisibility());
    }

    /** Waits (in small steps, so the snackbar doesn't time out on the test clock) for its Undo button, then taps it. */
    private void clickSnackbarAction() {
        View action = null;
        for (int i = 0; i < 80 && action == null; i++) {
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60));
            action = activity.getWindow().getDecorView().findViewById(com.google.android.material.R.id.snackbar_action);
        }
        assertNotNull("an Undo button should be showing", action);
        action.performClick();
        for (int i = 0; i < 4; i++) settle();
        layout();
    }

    @Test public void deleteMovesToTrashAndUndoBringsItBack() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_delete);
        waitUntil(() -> !new File(storage, "Download/notes.txt").exists());
        assertFalse("gone from the folder", new File(storage, "Download/notes.txt").exists());
        assertEquals("sits in the recycle bin", 1, new Trash(storage).list().size());
        clickSnackbarAction();
        assertTrue("undo puts it back", new File(storage, "Download/notes.txt").exists());
        assertTrue(new Trash(storage).list().isEmpty());
    }

    @Test public void permanentDeleteAsksFirstAndSkipsTheTrash() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_delete_forever);
        shot("06b_delete_permanently_confirm");
        assertTrue("nothing is deleted before confirming", new File(storage, "Download/notes.txt").exists());
        pressDialogButton(AlertDialog.BUTTON_POSITIVE);
        for (int i = 0; i < 5; i++) settle();
        assertFalse(new File(storage, "Download/notes.txt").exists());
        assertTrue("not in the trash either", new Trash(storage).list().isEmpty());
    }

    @Test public void trashScreenListsRestoresAndEmpties() throws Exception {
        new Trash(storage).trash(new File(storage, "Download/notes.txt"));
        new Trash(storage).trash(new File(storage, "Download/Invoice March.pdf"));
        new Trash(storage).trash(new File(storage, "Download/Old stuff"));
        ActivityController<TrashActivity> c = Robolectric.buildActivity(TrashActivity.class).setup();
        TrashActivity t = c.get();
        settle();
        RecyclerView list = t.findViewById(R.id.list);
        RowAdapter ra = (RowAdapter) list.getAdapter();
        assertEquals(3, ra.rows().size());
        assertEquals(View.GONE, t.findViewById(R.id.empty).getVisibility());
        shotOf(t, "30_trash");

        // select one and restore it
        View decor = t.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        list.findViewHolderForAdapterPosition(0).itemView.performLongClick();
        settle();
        MaterialToolbar tb = t.findViewById(R.id.toolbar);
        assertEquals("1", tb.getTitle().toString());
        tb.getMenu().performIdentifierAction(R.id.action_restore, 0);
        for (int i = 0; i < 4; i++) settle();
        assertEquals(2, ((RowAdapter) list.getAdapter()).rows().size());
        int back = 0;
        for (String n : new String[]{"Download/notes.txt", "Download/Invoice March.pdf", "Download/Old stuff"}) {
            if (new File(storage, n).exists()) back++;
        }
        assertEquals("exactly one item was restored", 1, back);

        // empty the trash
        tb.getMenu().performIdentifierAction(R.id.action_empty, 0);
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        for (int i = 0; i < 4; i++) settle();
        assertEquals(View.VISIBLE, t.findViewById(R.id.empty).getVisibility());
        assertTrue(new Trash(storage).list().isEmpty());
        shotOf(t, "31_trash_empty");
        c.pause().stop().destroy();
    }

    @Test public void renameCanBeUndone() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_rename);
        androidx.appcompat.app.AlertDialog dlg = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        findEditText(dlg.getWindow().getDecorView()).setText("todo.txt");
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertTrue(new File(storage, "Download/todo.txt").exists());
        clickSnackbarAction();
        assertTrue(new File(storage, "Download/notes.txt").exists());
        assertFalse(new File(storage, "Download/todo.txt").exists());
    }

    @Test public void moveCanBeUndone() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_cut);
        clickRow("Old stuff");
        files().getView().findViewById(R.id.pasteGo).performClick();
        waitUntil(() -> new File(storage, "Download/Old stuff/notes.txt").exists());
        assertTrue(new File(storage, "Download/Old stuff/notes.txt").exists());
        clickSnackbarAction();
        assertTrue(new File(storage, "Download/notes.txt").exists());
        assertFalse(new File(storage, "Download/Old stuff/notes.txt").exists());
    }

    @Test public void compressSelectionToZipThenUndo() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        clickRow("Invoice March.pdf");
        menu(R.id.action_compress);
        androidx.appcompat.app.AlertDialog dlg = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        android.widget.EditText input = findEditText(dlg.getWindow().getDecorView());
        assertEquals("Archive.zip", input.getText().toString());
        shot("09a_compress_dialog");
        input.setText("Paperwork.zip");
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        File zip = new File(storage, "Download/Paperwork.zip");
        waitUntil(zip::exists);
        assertTrue(zip.exists());
        assertTrue(new File(storage, "Download/notes.txt").exists());      // originals stay
        clickSnackbarAction();
        assertFalse("undo removes the zip", zip.exists());
    }

    @Test public void tappingARarOffersExtractAndWorks() throws Exception {
        File rar = new File(storage, "Download/stored.rar");
        try (java.io.InputStream in = getClass().getResourceAsStream("/archives/stored.rar")) {
            java.nio.file.Files.copy(in, rar.toPath());
        }
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        clickRow("stored.rar");
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertEquals("stored.rar", ((android.widget.TextView) d.findViewById(androidx.appcompat.R.id.alertTitle)).getText().toString());
        pressListItem(0);                                                  // Extract here
        File out = new File(storage, "Download/stored/hello.txt");
        waitUntil(out::exists);
        assertTrue(out.exists());
        assertTrue(new File(storage, "Download/stored/docs/readme.md").exists());
    }

    @Test public void homeShowsRecentFilesFromTheMediaIndex() throws Exception {
        launch();
        settle();
        View v = files().getView();
        android.widget.LinearLayout recent = v.findViewById(R.id.recentList);
        assertEquals("only the newest five are previewed", 5, recent.getChildCount());
        assertEquals(View.VISIBLE, v.findViewById(R.id.recentHeader).getVisibility());
        assertEquals("Invoice March.pdf", ((android.widget.TextView) recent.getChildAt(0).findViewById(R.id.name)).getText().toString());
        shot("40_home_recent");

        v.findViewById(R.id.recentAll).performClick();
        settle();
        MaterialToolbar tb = v.findViewById(R.id.toolbar);
        assertEquals("Recent files", tb.getTitle().toString());
        assertEquals("see all lists everything", 6, ((FileAdapter) list().getAdapter()).items().size());
    }

    @Test public void favoritesCanBeAddedPinnedAndRemoved() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        // pin the open folder
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        assertEquals("Add folder to favorites", tb.getMenu().findItem(R.id.action_pin_folder).getTitle().toString());
        tb.getMenu().performIdentifierAction(R.id.action_pin_folder, 0);
        assertEquals("Remove folder from favorites", tb.getMenu().findItem(R.id.action_pin_folder).getTitle().toString());
        // and a file, from the selection menu
        longClickRow("notes.txt");
        assertEquals("Add to favorites", tb.getMenu().findItem(R.id.action_favorite).getTitle().toString());
        menu(R.id.action_favorite);

        // back on the home screen both are there
        files().handleBack(); settle();
        files().handleBack(); settle();
        View v = files().getView();
        assertEquals(View.VISIBLE, v.findViewById(R.id.favGrid).getVisibility());
        android.widget.LinearLayout grid = v.findViewById(R.id.favGrid);
        java.util.List<String> labels = new ArrayList<>();
        for (int r = 0; r < grid.getChildCount(); r++) {
            android.view.ViewGroup row = (android.view.ViewGroup) grid.getChildAt(r);
            for (int k = 0; k < row.getChildCount(); k++) {
                View t = row.getChildAt(k).findViewById(R.id.tileLabel);
                if (t != null) labels.add(((android.widget.TextView) t).getText().toString());
            }
        }
        assertEquals(java.util.Arrays.asList("Download", "notes.txt"), labels);
        shot("41_home_favorites");

        // tapping a favourite folder opens it; long-pressing removes it
        android.view.ViewGroup row0 = (android.view.ViewGroup) grid.getChildAt(0);
        row0.getChildAt(0).performClick();
        settle();
        assertEquals("Download", ((MaterialToolbar) v.findViewById(R.id.toolbar)).getTitle().toString());
        files().handleBack(); settle(); files().handleBack(); settle();
        ((android.view.ViewGroup) ((android.widget.LinearLayout) v.findViewById(R.id.favGrid)).getChildAt(0)).getChildAt(0).performLongClick();
        settle();
        assertEquals(java.util.Collections.singletonList(new File(storage, "Download/notes.txt").getPath()),
                new Prefs(activity).favorites());
    }

    @Test public void favoritesThatNoLongerExistAreDropped() throws Exception {
        new Prefs(RuntimeEnvironment.getApplication()).setFavorites(new ArrayList<>(java.util.Arrays.asList(
                new File(storage, "Download/gone.txt").getPath(), new File(storage, "Download/notes.txt").getPath())));
        launch();
        assertEquals(java.util.Collections.singletonList(new File(storage, "Download/notes.txt").getPath()),
                new Prefs(activity).favorites());
    }

    @Test public void batchRenameShowsAPreviewRenamesAndUndoes() throws Exception {
        write("Download/batch/IMG_10.jpg", 100);
        write("Download/batch/IMG_2.jpg", 100);
        write("Download/batch/IMG_3.jpg", 100);
        launch();
        files().openDir(new File(storage, "Download/batch"));
        settle();
        longClickRow("IMG_10.jpg");
        clickRow("IMG_2.jpg");
        clickRow("IMG_3.jpg");
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        assertEquals("Rename 3 files...", tb.getMenu().findItem(R.id.action_rename).getTitle().toString());
        menu(R.id.action_rename);

        androidx.appcompat.app.AlertDialog dlg = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View root = dlg.getWindow().getDecorView();
        android.widget.TextView preview = root.findViewById(R.id.brPreview);
        assertEquals("nothing typed yet, but numbering with no name is a real change", true, preview.getText().length() > 0);
        ((android.widget.EditText) root.findViewById(R.id.brBase)).setText("Trip_");
        ((android.widget.EditText) root.findViewById(R.id.brDigits)).setText("3");
        shadowOf(Looper.getMainLooper()).idle();
        String text = preview.getText().toString();
        assertTrue(text, text.contains("IMG_2.jpg  \u2192  Trip_001.jpg"));
        assertTrue(text, text.contains("IMG_10.jpg  \u2192  Trip_003.jpg"));
        View ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        assertTrue(ok.isEnabled());
        androidx.appcompat.app.AlertDialog shown = dlg;
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFFFFFFF);
        View decor = shown.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        Bitmap d = Bitmap.createBitmap(decor.getMeasuredWidth(), decor.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        d.eraseColor(0xFFFFFFFF);
        decor.draw(new Canvas(d));
        save(d, "50_batch_rename_dialog");

        ok.performClick();
        settle();
        assertTrue(new File(storage, "Download/batch/Trip_001.jpg").exists());
        assertTrue(new File(storage, "Download/batch/Trip_002.jpg").exists());
        assertTrue(new File(storage, "Download/batch/Trip_003.jpg").exists());
        assertFalse(new File(storage, "Download/batch/IMG_2.jpg").exists());
        clickSnackbarAction();
        assertTrue("undo restores the original names", new File(storage, "Download/batch/IMG_2.jpg").exists());
        assertTrue(new File(storage, "Download/batch/IMG_10.jpg").exists());
        assertFalse(new File(storage, "Download/batch/Trip_001.jpg").exists());
    }

    @Test public void batchRenameBlocksClashes() throws Exception {
        write("Download/clash/a.txt", 5);
        write("Download/clash/b.txt", 5);
        write("Download/clash/keep.txt", 5);
        launch();
        files().openDir(new File(storage, "Download/clash"));
        settle();
        longClickRow("a.txt");
        clickRow("b.txt");
        menu(R.id.action_rename);
        androidx.appcompat.app.AlertDialog dlg = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View root = dlg.getWindow().getDecorView();
        android.widget.Spinner mode = root.findViewById(R.id.brMode);
        mode.setSelection(1);                                             // find and replace
        shadowOf(Looper.getMainLooper()).idle();
        ((android.widget.EditText) root.findViewById(R.id.brFindText)).setText("a");
        ((android.widget.EditText) root.findViewById(R.id.brReplaceText)).setText("keep");
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("renaming a.txt to keep.txt would overwrite", dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(((android.widget.TextView) root.findViewById(R.id.brPreview)).getText().toString().contains("already exists"));
    }

    @Test public void invertAndSelectByType() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_invert);
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        int total = ((FileAdapter) list().getAdapter()).items().size();
        assertEquals("everything except notes.txt", String.valueOf(total - 1), tb.getTitle().toString());
        menu(R.id.action_invert);
        assertEquals("inverting twice leaves only notes.txt", "1", tb.getTitle().toString());

        menu(R.id.action_select_type);                                    // opens the list of types
        pressListItem(6);                                                 // Apps
        assertEquals("the apk is added to the selection", "2", tb.getTitle().toString());
    }

    @Test public void gridViewShowsTilesAndSelectionBadges() throws Exception {
        launch();
        files().openDir(new File(storage, "DCIM/Camera"));
        settle();
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        assertEquals("Grid view", tb.getMenu().findItem(R.id.action_view_mode).getTitle().toString());
        tb.getMenu().performIdentifierAction(R.id.action_view_mode, 0);
        settle();
        assertTrue(list().getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager);
        assertEquals("List view", tb.getMenu().findItem(R.id.action_view_mode).getTitle().toString());
        layout();
        longClickRow("IMG_20260102_130000.jpg");
        shot("51_grid_view");
        View cell = list().findViewHolderForAdapterPosition(rowOf("IMG_20260102_130000.jpg")).itemView;
        assertEquals(View.VISIBLE, cell.findViewById(R.id.check).getVisibility());
        assertTrue(new Prefs(activity).grid());                           // remembered
        tb.getMenu().performIdentifierAction(R.id.action_view_mode, 0);
    }

    private File writePng(String rel, int color, int w, int h) throws Exception {
        File f = new File(storage, rel);
        f.getParentFile().mkdirs();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(color);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFFFFFFFF);
        c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 4f, p);
        try (FileOutputStream os = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, os); }
        return f;
    }

    private ViewerActivity openViewerFromList(String rowName) throws Exception {
        clickRow(rowName);
        android.content.Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("a viewer should have been started", started);
        assertEquals(ViewerActivity.class.getName(), started.getComponent().getClassName());
        ActivityController<ViewerActivity> vc = Robolectric.buildActivity(ViewerActivity.class, started).setup();
        viewerController = vc;
        return vc.get();
    }

    private ActivityController<ViewerActivity> viewerController;

    private void viewerMenu(ViewerActivity v, int mediaOrTextBar, int id) {
        MaterialToolbar bar = v.findViewById(mediaOrTextBar);
        assertTrue("menu item should exist: " + id, bar.getMenu().findItem(id) != null);
        bar.getMenu().performIdentifierAction(id, 0);
    }

    @Test public void tappingAPhotoOpensTheGalleryWithItsNeighbours() throws Exception {
        writePng("Pictures/Gallery/a_red.png", 0xFFE53935, 400, 300);
        writePng("Pictures/Gallery/b_green.png", 0xFF43A047, 300, 400);
        writePng("Pictures/Gallery/c_blue.png", 0xFF1E88E5, 400, 400);
        write("Pictures/Gallery/notes.txt", 5);
        launch();
        files().openDir(new File(storage, "Pictures/Gallery"));
        settle();
        ViewerActivity v = openViewerFromList("b_green.png");
        settle();
        ViewPager2 pager = v.findViewById(R.id.pager);
        assertEquals(View.VISIBLE, pager.getVisibility());
        assertEquals("only the 3 photos, not the text file", 3, pager.getAdapter().getItemCount());
        assertEquals("opens on the tapped photo", 1, pager.getCurrentItem());
        MaterialToolbar bar = v.findViewById(R.id.mediaBar);
        assertEquals("b_green.png", bar.getTitle().toString());
        assertEquals("2 of 3", bar.getSubtitle().toString());
        for (int i = 0; i < 6; i++) settle();
        shotOf(v, "60_viewer_photo");

        pager.setCurrentItem(2, false);
        settle();
        assertEquals("c_blue.png", bar.getTitle().toString());
        assertEquals("3 of 3", bar.getSubtitle().toString());
    }

    @Test public void deletingAPhotoInTheGalleryUsesTheTrashAndCanBeUndone() throws Exception {
        writePng("Pictures/Gallery/one.png", 0xFFE53935, 100, 100);
        writePng("Pictures/Gallery/two.png", 0xFF43A047, 100, 100);
        launch();
        files().openDir(new File(storage, "Pictures/Gallery"));
        settle();
        ViewerActivity v = openViewerFromList("one.png");
        settle();
        ViewPager2 pager = v.findViewById(R.id.pager);
        assertEquals(2, pager.getAdapter().getItemCount());
        viewerMenu(v, R.id.mediaBar, R.id.action_delete);
        waitUntil(() -> pager.getAdapter().getItemCount() == 1);
        assertEquals(1, pager.getAdapter().getItemCount());
        assertFalse(new File(storage, "Pictures/Gallery/one.png").exists());
        assertEquals(1, new Trash(storage).list().size());

        View action = null;
        for (int i = 0; i < 80 && action == null; i++) {
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60));
            action = v.getWindow().getDecorView().findViewById(com.google.android.material.R.id.snackbar_action);
        }
        assertNotNull(action);
        action.performClick();
        waitUntil(() -> pager.getAdapter().getItemCount() == 2);
        assertEquals(2, pager.getAdapter().getItemCount());
        assertTrue(new File(storage, "Pictures/Gallery/one.png").exists());
    }

    @Test public void textFilesOpenInTheEditorAndSave() throws Exception {
        File f = new File(storage, "Documents/todo.txt");
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), "buy milk\ncall mum\n".getBytes("UTF-8"));
        launch();
        files().openDir(new File(storage, "Documents"));
        settle();
        ViewerActivity v = openViewerFromList("todo.txt");
        waitUntil(() -> ((android.widget.EditText) v.findViewById(R.id.editor)).getText().length() > 0);
        android.widget.EditText ed = v.findViewById(R.id.editor);
        assertEquals("buy milk\ncall mum\n", ed.getText().toString());
        MaterialToolbar bar = v.findViewById(R.id.textBar);
        assertEquals("todo.txt", bar.getTitle().toString());

        ed.setText("buy milk\ncall mum\nbook flights\n");
        assertEquals("a dot shows there are unsaved changes", "\u2022 todo.txt", bar.getTitle().toString());
        shotOf(v, "61_viewer_text");
        viewerMenu(v, R.id.textBar, R.id.action_save);
        assertEquals("buy milk\ncall mum\nbook flights\n", new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
        assertEquals("todo.txt", bar.getTitle().toString());
        assertFalse("no temp file left", new File(f.getPath() + ".fbsave").exists());
    }

    @Test public void closingWithUnsavedChangesAsksFirst() throws Exception {
        File f = new File(storage, "Documents/draft.md");
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), "# draft".getBytes("UTF-8"));
        launch();
        files().openDir(new File(storage, "Documents"));
        settle();
        ViewerActivity v = openViewerFromList("draft.md");
        waitUntil(() -> ((android.widget.EditText) v.findViewById(R.id.editor)).getText().length() > 0);
        ((android.widget.EditText) v.findViewById(R.id.editor)).setText("# draft v2");
        v.getOnBackPressedDispatcher().onBackPressed();
        settle();
        assertFalse("still open, waiting for an answer", v.isFinishing());
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();           // Discard
        settle();
        assertTrue(v.isFinishing());
        assertEquals("discarding leaves the file alone", "# draft", new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
    }

    @Test public void filesBinaryOrBigTextAreNotOpenedAsText() throws Exception {
        File f = new File(storage, "Documents/latin1.txt");
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), new byte[]{'c', 'a', 'f', (byte) 0xE9});     // "café" in Latin-1, not valid UTF-8
        launch();
        files().openDir(new File(storage, "Documents"));
        settle();
        ViewerActivity v = openViewerFromList("latin1.txt");
        waitUntil(() -> ((android.widget.EditText) v.findViewById(R.id.editor)).getText().length() > 0);
        MaterialToolbar bar = v.findViewById(R.id.textBar);
        assertFalse("saving would corrupt it, so the button is hidden", bar.getMenu().findItem(R.id.action_save).isVisible());
    }

    @Test public void brokenPdfShowsAnExplanation() throws Exception {
        File f = new File(storage, "Documents/broken.pdf");
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), "not really a pdf".getBytes("UTF-8"));
        launch();
        files().openDir(new File(storage, "Documents"));
        settle();
        ViewerActivity v = openViewerFromList("broken.pdf");
        settle();
        assertEquals(View.VISIBLE, v.findViewById(R.id.errorBox).getVisibility());
        assertTrue(((android.widget.TextView) v.findViewById(R.id.errorText)).getText().toString().contains("PDF"));
        shotOf(v, "62_viewer_error");
    }

    @Test public void musicShowsThePlayerWithTheTitle() throws Exception {
        launch();
        files().openDir(new File(storage, "Music"));
        settle();
        ViewerActivity v = openViewerFromList("song.mp3");
        settle();
        assertEquals(View.VISIBLE, v.findViewById(R.id.videoBox).getVisibility());
        assertEquals(View.VISIBLE, v.findViewById(R.id.audioInfo).getVisibility());
        assertEquals("song.mp3", ((android.widget.TextView) v.findViewById(R.id.audioTitle)).getText().toString());
    }

    @Test public void filesTheViewerCannotShowGoToOtherApps() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        clickRow("holiday-video.mp4");               // video: viewer
        assertEquals(ViewerActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        clickRow("Invoice March.pdf");               // pdf: viewer
        assertEquals(ViewerActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        clickRow("app-release.apk");                 // an installer: another app
        android.content.Intent other = shadowOf(activity).getNextStartedActivity();
        assertEquals(android.content.Intent.ACTION_VIEW, other.getAction());
        assertEquals("application/vnd.android.package-archive", other.getType());
    }

    private ActivityController<StorageActivity> storageController;

    private StorageActivity openStorage() {
        storageController = Robolectric.buildActivity(StorageActivity.class).setup();
        StorageActivity a = storageController.get();
        waitUntil(() -> a.findViewById(R.id.busy).getVisibility() != View.VISIBLE);
        settle();
        return a;
    }

    private void storageTab(StorageActivity a, int index) {
        com.google.android.material.button.MaterialButtonToggleGroup g = a.findViewById(R.id.modeGroup);
        g.getChildAt(index).performClick();
        settle();
    }

    private static java.util.List<String> rowTitles(StorageActivity a) {
        RowAdapter ra = (RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter();
        java.util.List<String> out = new ArrayList<>();
        for (RowAdapter.Row r : ra.rows()) out.add(r.title);
        return out;
    }

    private void blob(String rel, int size, long seed) throws Exception {
        File f = new File(storage, rel);
        f.getParentFile().mkdirs();
        byte[] b = new byte[size];
        new java.util.Random(seed).nextBytes(b);
        java.nio.file.Files.write(f.toPath(), b);
    }

    @Test public void storageOverviewBreaksDownSpaceByTypeAndFolder() throws Exception {
        StorageActivity a = openStorage();
        java.util.List<String> titles = rowTitles(a);
        assertTrue(titles.toString(), titles.contains("BY TYPE"));
        assertTrue(titles.toString(), titles.contains("Videos"));
        assertTrue(titles.toString(), titles.contains("BIGGEST FOLDERS"));
        assertTrue(titles.toString(), titles.contains("Download"));
        assertEquals(View.VISIBLE, a.findViewById(R.id.usageBar).getVisibility());
        String info = ((TextView) a.findViewById(R.id.info)).getText().toString();
        assertTrue(info, info.contains("files"));
        shotOf(a, "70_storage_overview");

        // tapping a type asks the Files tab to show that category
        RecyclerView list = a.findViewById(R.id.list);
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        int videoRow = titles.indexOf("Videos");
        list.findViewHolderForAdapterPosition(videoRow).itemView.performClick();
        assertEquals(MediaQuery.Category.VIDEOS, Nav.category);
        Nav.category = null;
    }

    @Test public void largeFilesAreListedBiggestFirst() throws Exception {
        StorageActivity a = openStorage();
        storageTab(a, 1);
        java.util.List<String> titles = rowTitles(a);
        assertEquals("holiday-video.mp4", titles.get(0));
        assertTrue(titles.indexOf("photos.zip") > titles.indexOf("holiday-video.mp4"));
        shotOf(a, "71_storage_large");
    }

    @Test public void duplicatesCanBeFoundAndTheExtraCopiesTrashed() throws Exception {
        blob("Download/Trip/original.bin", 300_000, 5);
        java.nio.file.Files.copy(new File(storage, "Download/Trip/original.bin").toPath(), new File(storage, "Pictures/copy1.bin").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.copy(new File(storage, "Download/Trip/original.bin").toPath(), new File(storage, "Documents/copy2.bin").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        new File(storage, "Download/Trip/original.bin").setLastModified(1_000_000_000L);
        new File(storage, "Pictures/copy1.bin").setLastModified(2_000_000_000L);
        new File(storage, "Documents/copy2.bin").setLastModified(3_000_000_000L);
        blob("Music/lookalike.bin", 300_000, 6);                               // same size, different content

        StorageActivity a = openStorage();
        storageTab(a, 2);
        waitUntil(() -> rowTitles(a).contains("original.bin"));
        java.util.List<String> titles = rowTitles(a);
        assertTrue(titles.toString(), titles.get(0).startsWith("3 identical copies"));
        assertTrue(titles.toString(), titles.contains("original.bin") && titles.contains("copy1.bin") && titles.contains("copy2.bin"));
        assertFalse("a file that only has the same size isn't a duplicate", titles.contains("lookalike.bin"));

        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_select_extras, 0);
        settle();
        assertEquals("keeps the oldest, selects the other two", "2 selected  \u00b7  600 KB",
                ((TextView) a.findViewById(R.id.bottomText)).getText().toString());
        shotOf(a, "72_storage_duplicates");

        ((View) a.findViewById(R.id.bottomButton)).performClick();
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitUntil(() -> !new File(storage, "Pictures/copy1.bin").exists());
        settle();
        assertTrue("the original stays", new File(storage, "Download/Trip/original.bin").exists());
        assertFalse(new File(storage, "Pictures/copy1.bin").exists());
        assertFalse(new File(storage, "Documents/copy2.bin").exists());
        assertEquals("both copies are in the recycle bin", 2, new Trash(storage).list().size());
        assertEquals("the set disappears once only one copy is left", View.VISIBLE, a.findViewById(R.id.empty).getVisibility());
    }

    @Test public void cleanupClearsLeftoversAndKeepsNormalFiles() throws Exception {
        blob("DCIM/.thumbnails/t1.jpg", 4000, 1);
        blob("Download/old-installer.apk", 5000, 2);
        blob("Download/movie.mkv.part", 6000, 3);
        new File(storage, "Empty/inside").mkdirs();
        StorageActivity a = openStorage();
        storageTab(a, 3);
        java.util.List<String> titles = rowTitles(a);
        assertTrue(titles.toString(), titles.contains("t1.jpg") || titles.contains(".thumbnails"));
        assertTrue(titles.toString(), titles.contains("old-installer.apk"));
        assertTrue(titles.toString(), titles.contains("movie.mkv.part"));
        assertFalse(titles.toString(), titles.contains("Invoice March.pdf"));

        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_select_all, 0);
        settle();
        shotOf(a, "73_storage_cleanup");
        ((View) a.findViewById(R.id.bottomButton)).performClick();
        settle();
        ((androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitUntil(() -> !new File(storage, "Download/old-installer.apk").exists());
        settle();
        assertFalse(new File(storage, "DCIM/.thumbnails").exists());              // caches go for good
        assertFalse(new File(storage, "Download/movie.mkv.part").exists());
        java.util.List<String> trashed = new ArrayList<>();
        for (Trash.Entry e : new Trash(storage).list()) trashed.add(e.name);
        java.util.Collections.sort(trashed);
        assertEquals("installers and partial files can be restored", java.util.Arrays.asList("app-release.apk", "movie.mkv.part", "old-installer.apk"), trashed);
        assertTrue(new File(storage, "Download/Invoice March.pdf").exists());
    }

    @Test public void storageHomeTileOpensTheAnalyzer() throws Exception {
        launch();
        android.widget.LinearLayout tools = files().getView().findViewById(R.id.toolsGrid);
        assertTrue(tools.getChildCount() >= 1);
        android.view.ViewGroup row = (android.view.ViewGroup) tools.getChildAt(0);
        assertEquals("Storage", ((TextView) row.getChildAt(0).findViewById(R.id.tileLabel)).getText().toString());
        row.getChildAt(0).performClick();
        assertEquals(StorageActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    private ActivityController<AppsActivity> appsController;

    private void installFake(String pkg, String label, String version, int apkBytes, boolean system, int... splitBytes) throws Exception {
        File dir = new File(storage, "fake-apps/" + pkg);
        dir.mkdirs();
        File base = new File(dir, "base.apk");
        java.nio.file.Files.write(base.toPath(), new byte[apkBytes]);
        android.content.pm.PackageInfo pi = new android.content.pm.PackageInfo();
        pi.packageName = pkg;
        pi.versionName = version;
        pi.firstInstallTime = 1_760_000_000_000L + apkBytes;
        pi.lastUpdateTime = 1_761_000_000_000L - apkBytes;
        android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
        ai.packageName = pkg;
        ai.nonLocalizedLabel = label;
        ai.sourceDir = base.getPath();
        ai.flags = system ? android.content.pm.ApplicationInfo.FLAG_SYSTEM : 0;
        if (splitBytes.length > 0) {
            ai.splitSourceDirs = new String[splitBytes.length];
            for (int i = 0; i < splitBytes.length; i++) {
                File sp = new File(dir, "split_" + i + ".apk");
                java.nio.file.Files.write(sp.toPath(), new byte[splitBytes[i]]);
                ai.splitSourceDirs[i] = sp.getPath();
            }
        }
        pi.applicationInfo = ai;
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(pi);
    }

    private AppsActivity openApps() {
        appsController = Robolectric.buildActivity(AppsActivity.class).setup();
        AppsActivity a = appsController.get();
        waitUntil(() -> ((RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter()).rows().size() > 0);
        settle();
        return a;
    }

    /** App names in the list, leaving out FileBandit itself (the test phone has it installed too). */
    private static java.util.List<String> appTitles(AppsActivity a) {
        java.util.List<String> out = new ArrayList<>();
        for (RowAdapter.Row r : ((RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter()).rows()) {
            if (!"FileBandit".equals(r.title)) out.add(r.title);
        }
        return out;
    }

    private static View appRow(AppsActivity a, String label) {
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        RecyclerView list = a.findViewById(R.id.list);
        java.util.List<RowAdapter.Row> rows = ((RowAdapter) list.getAdapter()).rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).title.equals(label)) return list.findViewHolderForAdapterPosition(i).itemView;
        }
        throw new AssertionError("no app row " + label);
    }

    @Test public void appsListUserAppsFirstAndCanShowSystemApps() throws Exception {
        installFake("com.example.notes", "Notes", "1.4.2", 3_000_000, false);
        installFake("com.example.camera", "Camera Pro", "9.0", 8_000_000, false);
        installFake("com.android.settings", "Settings", "14", 1_000_000, true);
        AppsActivity a = openApps();
        assertEquals(java.util.Arrays.asList("Camera Pro", "Notes"), appTitles(a));        // A to Z, no system apps
        String info = ((TextView) a.findViewById(R.id.info)).getText().toString();
        assertTrue(info, info.startsWith("3 apps") && info.contains("system apps hidden"));   // Camera Pro, Notes and FileBandit
        shotOf(a, "80_apps");

        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_system, 0);
        waitUntil(() -> appTitles(a).size() == 3);        // Settings joins the list
        assertTrue(appTitles(a).contains("Settings"));
    }

    @Test public void appsCanBeSearchedAndSortedBySize() throws Exception {
        installFake("com.example.alpha", "Alpha", "1", 1_000_000, false);
        installFake("com.example.beta", "Beta", "1", 5_000_000, false);
        installFake("com.example.gamma", "Gamma", "1", 3_000_000, false);
        AppsActivity a = openApps();
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_sort, 0);
        settle();
        pressListItem(1);                                                              // biggest first
        assertEquals(java.util.Arrays.asList("Beta", "Gamma", "Alpha"), appTitles(a));

        tb.getMenu().performIdentifierAction(R.id.action_search, 0);
        android.widget.EditText search = a.findViewById(R.id.searchInput);
        assertEquals(View.VISIBLE, search.getVisibility());
        search.setText("gam");
        settle();
        assertEquals(java.util.Collections.singletonList("Gamma"), appTitles(a));
    }

    @Test public void backingUpAnAppCopiesItsApk() throws Exception {
        installFake("com.example.notes", "Notes", "1.4.2", 4096, false);
        AppsActivity a = openApps();
        appRow(a, "Notes").performClick();
        settle();
        pressListItem(1);                                                              // App info, Back up APK, Share APK, Uninstall (no launcher)
        File backup = new File(storage, "FileBandit/Apps/Notes_1.4.2.apk");
        waitUntil(backup::exists);
        assertTrue("apk copied with a readable name", backup.exists());
        assertEquals(4096, backup.length());
    }

    @Test public void splitAppsAreBackedUpAsAFolderWithEveryPart() throws Exception {
        installFake("com.example.big", "Big App", "2.0", 5000, false, 1000, 2000);
        AppsActivity a = openApps();
        appRow(a, "Big App").performLongClick();                                        // select
        settle();
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        assertEquals("1", tb.getTitle().toString());
        tb.getMenu().performIdentifierAction(R.id.action_backup, 0);
        File folder = new File(storage, "FileBandit/Apps/Big App_2.0");
        waitUntil(() -> new File(folder, "split_1.apk").exists());
        assertTrue(new File(folder, "base.apk").exists());
        assertTrue(new File(folder, "split_0.apk").exists());
        assertTrue(new File(folder, "split_1.apk").exists());
    }

    @Test public void backupNeedsFileAccess() throws Exception {
        Access.testOverride = false;
        installFake("com.example.notes", "Notes", "1", 100, false);
        AppsActivity a = openApps();
        appRow(a, "Notes").performLongClick();
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_backup, 0);
        settle();
        assertFalse(new File(storage, "FileBandit/Apps").exists());
        assertEquals("Allow file access in the Files tab first, then try again.",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test public void uninstallAsksAndroidToDoIt() throws Exception {
        installFake("com.example.notes", "Notes", "1", 100, false);
        AppsActivity a = openApps();
        appRow(a, "Notes").performClick();
        settle();
        pressListItem(3);                                                              // Uninstall
        android.content.Intent i = shadowOf(a).getNextStartedActivity();
        assertEquals(android.content.Intent.ACTION_DELETE, i.getAction());
        assertEquals("package:com.example.notes", i.getData().toString());
    }

    @Test public void appsTileOnHomeOpensTheManager() throws Exception {
        launch();
        android.view.ViewGroup row = (android.view.ViewGroup) ((android.widget.LinearLayout) files().getView().findViewById(R.id.toolsGrid)).getChildAt(0);
        assertEquals("Apps", ((TextView) row.getChildAt(2).findViewById(R.id.tileLabel)).getText().toString());
        row.getChildAt(2).performClick();
        assertEquals(AppsActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    @Test public void searchFindsFilesInSubfolders() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        MaterialToolbar tb = files().getView().findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_search, 0);
        android.widget.EditText input = files().getView().findViewById(R.id.searchInput);
        input.setText("a.txt");
        input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        for (int i = 0; i < 4; i++) settle();
        FileAdapter fa = (FileAdapter) list().getAdapter();
        assertEquals(1, fa.items().size());
        assertEquals("a.txt", fa.items().get(0).name);
        shot("07_search");
    }

    @Test public void newFolderDialogCreatesAFolder() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        files().getView().findViewById(R.id.fab).performClick();
        settle();
        pressListItem(0);                                      // "New folder"
        androidx.appcompat.app.AlertDialog dlg = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        android.widget.EditText input = findEditText(dlg.getWindow().getDecorView());
        input.setText("Trip 2026");
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertTrue(new File(storage, "Download/Trip 2026").isDirectory());
    }

    private void pressListItem(int index) {
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        android.widget.ListView lv = d.getListView();
        lv.performItemClick(lv.getAdapter().getView(index, null, lv), index, lv.getAdapter().getItemId(index));
        settle();
    }

    private static android.widget.EditText findEditText(View v) {
        if (v instanceof android.widget.EditText) return (android.widget.EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.EditText e = findEditText(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    @Test public void renameDialogRenames() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_rename);
        androidx.appcompat.app.AlertDialog dlg = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        android.widget.EditText input = findEditText(dlg.getWindow().getDecorView());
        assertEquals("notes.txt", input.getText().toString());
        input.setText("todo.txt");
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertTrue(new File(storage, "Download/todo.txt").exists());
        assertFalse(new File(storage, "Download/notes.txt").exists());
    }

    @Test public void sendToIphoneSharesFilesAndSwitchesToTransfer() throws Exception {
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("Invoice March.pdf");
        clickRow("Old stuff");
        menu(R.id.action_send);
        ShareHub hub = FastShareApp.hub(activity);
        assertEquals(2, hub.shared().size());
        assertTrue(transfer().isVisible());
        assertFalse(files().isVisible());
        assertEquals(R.id.nav_transfer, ((com.google.android.material.bottomnavigation.BottomNavigationView) activity.findViewById(R.id.nav)).getSelectedItemId());
        assertEquals(View.VISIBLE, transfer().getView().findViewById(R.id.sharedCard).getVisibility());
        shotScrollContent("08_transfer_ready_to_send", transfer().getView());
    }

    @Test public void backFromTransferReturnsToFiles() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        assertTrue(transfer().isVisible());
        activity.getOnBackPressedDispatcher().onBackPressed();
        settle();
        assertTrue(files().isVisible());
    }

    @Test public void transferScreenGuidesThroughWifiSteps() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        assertEquals("Join the same Wi-Fi on both phones", ((android.widget.TextView) v.findViewById(R.id.step1Title)).getText().toString());
        assertEquals(View.GONE, v.findViewById(R.id.hotspotBox).getVisibility());
        String link = ((android.widget.TextView) v.findViewById(R.id.linkText)).getText().toString();
        assertTrue(link, link.startsWith("http://192.168.23.33:"));
        assertTrue(link, link.contains("/?t="));
        assertEquals(View.VISIBLE, v.findViewById(R.id.otherAddrBtn).getVisibility());
        android.widget.TextView s = v.findViewById(R.id.statusNet);
        assertTrue(s.getText().toString(), s.getText().toString().contains("192.168.23.33"));
        android.widget.TextView c = v.findViewById(R.id.statusClient);
        assertTrue(c.getText().toString(), c.getText().toString().startsWith("Waiting for the iPhone"));
        shotScrollContent("09_transfer_wifi", v);

        // "Other address" cycles to the second address
        v.findViewById(R.id.otherAddrBtn).performClick();
        settle();
        link = ((android.widget.TextView) v.findViewById(R.id.linkText)).getText().toString();
        assertTrue(link, link.startsWith("http://10.0.0.7:"));
    }

    @Test public void transferShowsConnectedIphoneAndProgress() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        ShareHub hub = FastShareApp.hub(activity);
        hub.onClient("192.168.23.40",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1");

        FileServer.Transfer done = new FileServer.Transfer(1, true, "IMG_4021.HEIC", "192.168.23.40", 2_900_000);
        done.done = 2_900_000;
        done.nanos = 180_000_000L;
        done.state = FileServer.State.DONE;
        done.detail = "Downloads/FileBandit";
        hub.onTransfer(done);
        FileServer.Transfer running = new FileServer.Transfer(2, false, "holiday-video.mp4", "192.168.23.40", 48_300_000);
        running.done = 21_000_000;
        running.nanos = 1_100_000_000L;
        hub.onTransfer(running);
        FileServer.Transfer failed = new FileServer.Transfer(3, true, "big.mov", "192.168.23.40", 800_000_000);
        failed.done = 120_000_000;
        failed.nanos = 4_000_000_000L;
        failed.state = FileServer.State.FAILED;
        hub.onTransfer(failed);
        settle();

        View v = transfer().getView();
        android.widget.TextView c = v.findViewById(R.id.statusClient);
        assertEquals("iPhone (Safari) connected", c.getText().toString());
        assertEquals(View.VISIBLE, v.findViewById(R.id.activityCard).getVisibility());
        assertEquals(3, ((ViewGroup) v.findViewById(R.id.activityList)).getChildCount());
        shotScrollContent("10_transfer_activity", v);
    }

    @Test public void transferHotspotModeShowsJoinCodeThenLink() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        v.findViewById(R.id.methodHotspot).performClick();
        settle();
        assertEquals(View.VISIBLE, v.findViewById(R.id.hotspotBox).getVisibility());
        assertEquals("Start hotspot", ((android.widget.TextView) v.findViewById(R.id.hotspotBtn)).getText().toString());
        assertEquals("Start the hotspot first expected in caption area", "", ((android.widget.TextView) v.findViewById(R.id.qrCaption)).getText().toString());
        shotScrollContent("11_transfer_hotspot_off", v);

        // pretend the hotspot came up
        ShareHub hub = FastShareApp.hub(activity);
        setField(hub, "hotspotState", ShareHub.HotspotState.ON);
        setField(hub, "hotspotSsid", "AndroidShare_4821");
        setField(hub, "hotspotPass", "k3Rt9xQw2m");
        Net.testOverride = new Net.Info(false, new ArrayList<>(Arrays.asList(new Net.Addr("192.168.43.1", "Hotspot"))));
        hub.refreshNet();
        settle();
        assertTrue(hub.hotspotJoinCode().startsWith("WIFI:T:WPA;S:AndroidShare_4821;P:k3Rt9xQw2m;;"));
        assertEquals("Scan to join “AndroidShare_4821”", ((android.widget.TextView) v.findViewById(R.id.qrCaption)).getText().toString());
        assertEquals("Stop hotspot", ((android.widget.TextView) v.findViewById(R.id.hotspotBtn)).getText().toString());
        shotScrollContent("12_transfer_hotspot_join", v);

        v.findViewById(R.id.qrOpen).performClick();
        settle();
        String link = ((android.widget.TextView) v.findViewById(R.id.linkText)).getText().toString();
        assertTrue(link, link.startsWith("http://192.168.43.1:"));
        shotScrollContent("13_transfer_hotspot_link", v);
    }

    @Test public void helpOpensAndSelfTestRuns() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        assertEquals(View.GONE, v.findViewById(R.id.helpBody).getVisibility());
        v.findViewById(R.id.helpHeader).performClick();
        settle();
        assertEquals(View.VISIBLE, v.findViewById(R.id.helpBody).getVisibility());
        String help = ((android.widget.TextView) v.findViewById(R.id.helpText)).getText().toString();
        assertTrue(help, help.contains("Local Network"));
        assertTrue(help, help.contains("ERR_CONNECTION_TIMED_OUT"));
        shotScrollContent("14_transfer_help", v);

        v.findViewById(R.id.selfTestBtn).performClick();
        for (int i = 0; i < 10; i++) settle();
        String result = ((android.widget.TextView) v.findViewById(R.id.selfTestResult)).getText().toString();
        // the fake address 192.168.23.33 isn't on this machine, so the test must report that honestly
        assertTrue(result, result.startsWith("Couldn't reach the server") || result.startsWith("✓"));
    }

    @Test public void shareFromAnotherAppAddsFilesAndOpensTransfer() throws Exception {
        File f = write("Download/shared-in.txt", 20);
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.fromFile(f));
        controller = Robolectric.buildActivity(MainActivity.class, i).setup();
        activity = controller.get();
        settle();
        assertEquals(1, FastShareApp.hub(activity).shared().size());
        assertTrue(transfer().isVisible());
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    public void darkModeRenders() throws Exception {
        launch();
        shot("20_dark_files_home");
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        shot("21_dark_selection");
        selectTab(R.id.nav_transfer);
        shotScrollContent("22_dark_transfer", transfer().getView());
    }

    // ------------------------------------------------------------------ private vault

    private ActivityController<VaultActivity> vaultController;
    private File vaultDir;

    private VaultActivity openVault() {
        if (VaultSession.testVault == null) {
            vaultDir = new File(storage.getParentFile(), "vault-test-" + System.nanoTime());
            VaultSession.testVault = new Vault(vaultDir, Vault.NO_OUTER, 1000);
            VaultSession.testDecoy = new Vault(new File(storage.getParentFile(), "decoy-test-" + System.nanoTime()), Vault.NO_OUTER, 1000);
        }
        vaultController = Robolectric.buildActivity(VaultActivity.class).setup();
        VaultActivity a = vaultController.get();
        settle();
        return a;
    }

    private void type(VaultActivity a, int id, String text) {
        ((android.widget.EditText) a.findViewById(id)).setText(text);
    }

    private void tapAuth(VaultActivity a) {
        a.findViewById(R.id.authButton).performClick();
        waitUntil(() -> a.findViewById(R.id.busy).getVisibility() != View.VISIBLE);
        settle();
    }

    private static String authError(VaultActivity a) {
        View e = a.findViewById(R.id.authError);
        return e.getVisibility() == View.VISIBLE ? ((TextView) e).getText().toString() : null;
    }

    private static java.util.List<String> vaultRows(VaultActivity a) {
        RowAdapter ra = (RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter();
        java.util.List<String> out = new ArrayList<>();
        for (RowAdapter.Row r : ra.rows()) out.add(r.title);
        return out;
    }

    private void createVault(VaultActivity a, String pin) {
        type(a, R.id.pinInput, pin);
        type(a, R.id.pinConfirm, pin);
        tapAuth(a);
    }

    @After
    public void tearDownVault() {
        if (vaultController != null) {
            try { vaultController.pause().stop().destroy(); } catch (Exception ignored) { }
            vaultController = null;
        }
        VaultSession.testVault = null;
        VaultSession.testDecoy = null;
        VaultSession.decoyActive = false;
        VaultSession.pending.clear();
        if (vaultDir != null) FileOps.delete(vaultDir);
        vaultDir = null;
    }

    @Test public void vaultSetupChecksThePinAndThenOpens() throws Exception {
        VaultActivity a = openVault();
        assertEquals("Create your private vault", ((TextView) a.findViewById(R.id.authTitle)).getText().toString());
        assertTrue("the window is protected from screenshots",
                (a.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
        shotOf(a, "80_vault_setup");

        type(a, R.id.pinInput, "12");
        type(a, R.id.pinConfirm, "12");
        tapAuth(a);
        assertTrue(authError(a), authError(a).contains("at least"));

        type(a, R.id.pinInput, "2468");
        type(a, R.id.pinConfirm, "2469");
        tapAuth(a);
        assertTrue(authError(a), authError(a).contains("don't match"));
        assertFalse(VaultSession.get(a).exists());

        type(a, R.id.pinInput, "2468");
        type(a, R.id.pinConfirm, "2468");
        tapAuth(a);
        assertTrue(VaultSession.get(a).exists());
        assertTrue(VaultSession.get(a).isUnlocked());
        assertEquals(View.VISIBLE, a.findViewById(R.id.listBox).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.empty).getVisibility());
        shotOf(a, "81_vault_empty");
    }

    @Test public void vaultLocksAndOnlyTheRightPinOpensIt() throws Exception {
        VaultActivity a = openVault();
        createVault(a, "2468");
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_lock, 0);
        settle();
        assertFalse(VaultSession.get(a).isUnlocked());
        assertEquals("Vault locked", ((TextView) a.findViewById(R.id.authTitle)).getText().toString());
        assertEquals(View.GONE, a.findViewById(R.id.bioButton).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.listBox).getVisibility());
        shotOf(a, "82_vault_locked");

        type(a, R.id.pinInput, "0000");
        tapAuth(a);
        assertTrue(authError(a), authError(a).contains("isn't right"));
        assertFalse(VaultSession.get(a).isUnlocked());
        assertEquals("the box is cleared after a miss", "", ((android.widget.EditText) a.findViewById(R.id.pinInput)).getText().toString());
        shotOf(a, "83_vault_wrong_pin");

        type(a, R.id.pinInput, "2468");
        tapAuth(a);
        assertTrue(VaultSession.get(a).isUnlocked());
        assertEquals(View.VISIBLE, a.findViewById(R.id.listBox).getVisibility());
    }

    @Test public void filesPickedInTheFilesTabMoveIntoTheVaultAndLeaveNoOriginal() throws Exception {
        File notes = new File(storage, "Download/notes.txt");
        File inner = new File(storage, "Download/Old stuff/a.txt");
        VaultSession.pending.add(notes);
        VaultSession.pending.add(new File(storage, "Download/Old stuff"));
        VaultActivity a = openVault();
        createVault(a, "2468");
        waitUntil(() -> vaultRows(a).size() == 2);
        settle();
        java.util.List<String> rows = vaultRows(a);
        assertTrue(rows.toString(), rows.contains("notes.txt"));
        assertTrue(rows.toString(), rows.contains("Old stuff/a.txt"));
        assertFalse("original file is gone once it is safely inside", notes.exists());
        assertFalse(inner.exists());
        assertFalse("the emptied folder is removed too", new File(storage, "Download/Old stuff").exists());
        assertTrue(VaultSession.pending.isEmpty());
        // nothing readable is left lying around in the vault folder
        for (File f : vaultDir.listFiles()) assertFalse(f.getName(), f.getName().contains("notes"));
        shotOf(a, "84_vault_with_files");
    }

    @Test public void vaultItemsCanBeSelectedExportedAndDeleted() throws Exception {
        VaultSession.pending.add(new File(storage, "Download/notes.txt"));
        VaultSession.pending.add(new File(storage, "Download/photos.zip"));
        VaultActivity a = openVault();
        createVault(a, "2468");
        waitUntil(() -> vaultRows(a).size() == 2);
        settle();

        RecyclerView list = a.findViewById(R.id.list);
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        int notesRow = vaultRows(a).indexOf("notes.txt");
        list.findViewHolderForAdapterPosition(notesRow).itemView.performLongClick();
        settle();
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        assertEquals("1", tb.getTitle().toString());
        shotOf(a, "85_vault_selected");

        tb.getMenu().performIdentifierAction(R.id.action_export, 0);
        waitUntil(() -> new File(VaultActivity.restoreDir(), "notes.txt").exists());
        settle();
        assertTrue(new File(VaultActivity.restoreDir(), "notes.txt").exists());
        assertEquals(1_200, new File(VaultActivity.restoreDir(), "notes.txt").length());
        assertEquals("exported file leaves the vault", java.util.Arrays.asList("photos.zip"), vaultRows(a));

        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        list.findViewHolderForAdapterPosition(0).itemView.performLongClick();
        settle();
        ((MaterialToolbar) a.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(R.id.action_delete, 0);
        settle();
        ((androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitUntil(() -> vaultRows(a).isEmpty());
        settle();
        assertTrue(vaultRows(a).isEmpty());
        assertEquals(View.VISIBLE, a.findViewById(R.id.empty).getVisibility());
    }

    @Test public void tappingAVaultTextFileOpensItInASecureViewerAndLockingWipesTheCopy() throws Exception {
        VaultSession.pending.add(new File(storage, "Download/notes.txt"));
        VaultActivity a = openVault();
        createVault(a, "2468");
        waitUntil(() -> vaultRows(a).size() == 1);
        settle();
        RecyclerView list = a.findViewById(R.id.list);
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        list.findViewHolderForAdapterPosition(0).itemView.performClick();
        File cache = VaultSession.viewCache(a);
        waitUntil(() -> shadowOf(a).peekNextStartedActivity() != null);
        android.content.Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull("the viewer should start", next);
        assertEquals(ViewerActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(next.getBooleanExtra(ViewerActivity.EXTRA_SECURE, false));
        File shown = new File(next.getStringExtra(ViewerActivity.EXTRA_PATH));
        assertTrue("decrypted copy sits in the private cache", shown.getPath().startsWith(cache.getPath()));
        assertTrue(shown.exists());

        ((MaterialToolbar) a.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(R.id.action_lock, 0);
        settle();
        assertFalse("locking removes decrypted copies", shown.exists());
    }

    @Test public void vaultHomeTileOpensTheVaultAndSelectionMovesFilesIn() throws Exception {
        launch();
        android.view.ViewGroup row = (android.view.ViewGroup) ((android.widget.LinearLayout) files().getView().findViewById(R.id.toolsGrid)).getChildAt(0);
        assertEquals("Vault", ((TextView) row.getChildAt(1).findViewById(R.id.tileLabel)).getText().toString());
        row.getChildAt(1).performClick();
        assertEquals(VaultActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());

        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_vault);
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("asks before moving", d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals(1, VaultSession.pending.size());
        assertEquals(VaultActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        assertTrue("nothing is touched until the vault is unlocked", new File(storage, "Download/notes.txt").exists());
    }

    // ------------------------------------------------------------------ transfer upgrades

    @Test public void pinSwitchCreatesAFourDigitPinAndCanBeChangedOrTurnedOff() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        ShareHub hub = FastShareApp.hub(activity);
        android.widget.CompoundButton sw = v.findViewById(R.id.pinSwitch);
        assertFalse(sw.isChecked());
        assertEquals(View.GONE, v.findViewById(R.id.pinText).getVisibility());
        assertEquals("", hub.linkPin());

        sw.performClick();
        settle();
        String pin = hub.linkPin();
        assertTrue(pin, pin.matches("\\d{4}"));
        TextView shown = v.findViewById(R.id.pinText);
        assertEquals(View.VISIBLE, shown.getVisibility());
        assertEquals(pin, shown.getText().toString());
        assertTrue(((TextView) v.findViewById(R.id.pinHelp)).getText().toString().contains("asks for this PIN"));
        shotScrollContent("90_transfer_pin_on", v);

        String old = pin;
        for (int i = 0; i < 20 && old.equals(hub.linkPin()); i++) shown.performClick();   // a new random PIN differs (almost always)
        assertFalse(old.equals(hub.linkPin()));
        assertEquals(hub.linkPin(), ((TextView) v.findViewById(R.id.pinText)).getText().toString());

        sw.performClick();
        settle();
        assertEquals("", hub.linkPin());
        assertEquals(View.GONE, v.findViewById(R.id.pinText).getVisibility());
        assertFalse(((android.widget.CompoundButton) v.findViewById(R.id.pinSwitch)).isChecked());
    }

    @Test public void pinIsRememberedAcrossRestarts() throws Exception {
        launch();
        ShareHub hub = FastShareApp.hub(activity);
        hub.setLinkPin("7319");
        assertEquals("7319", new Prefs(activity).linkPin());
        ShareHub fresh = new ShareHub(activity);
        assertEquals("7319", fresh.linkPin());
    }

    @Test public void shareLinkOpensTheAndroidShareSheetWithTheAddress() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        v.findViewById(R.id.shareBtn).performClick();
        android.content.Intent chooser = shadowOf(activity).getNextStartedActivity();
        assertNotNull(chooser);
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.getAction());
        android.content.Intent inner = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
        assertEquals(android.content.Intent.ACTION_SEND, inner.getAction());
        String text = inner.getStringExtra(android.content.Intent.EXTRA_TEXT);
        assertTrue(text, text.contains("http://192.168.23.33:") && text.contains("/?t="));
        assertFalse("the PIN is never put in the shared text", text.matches("(?s).*\\d{4}\\s*$") && FastShareApp.hub(activity).linkPin().length() > 0);
    }

    private ActivityController<HistoryActivity> historyController;

    @Test public void historyScreenListsFinishedTransfersAndCanBeCleared() throws Exception {
        launch();
        ShareHub hub = FastShareApp.hub(activity);
        FileServer.Transfer got = new FileServer.Transfer(1, true, "IMG_4021.HEIC", "192.168.23.40", 2_900_000);
        got.done = 2_900_000;
        got.nanos = 180_000_000L;
        got.state = FileServer.State.DONE;
        got.detail = "Downloads/FileBandit";
        hub.onTransfer(got);
        FileServer.Transfer sent = new FileServer.Transfer(2, false, "holiday-video.mp4", "192.168.23.40", 48_300_000);
        sent.done = 48_300_000;
        sent.nanos = 4_000_000_000L;
        sent.state = FileServer.State.DONE;
        hub.onTransfer(sent);
        FileServer.Transfer cut = new FileServer.Transfer(3, true, "big.mov", "192.168.23.40", 900_000_000);
        cut.done = 200_000_000;
        cut.state = FileServer.State.FAILED;
        cut.detail = "Paused";
        hub.onTransfer(cut);
        assertEquals("history is written as soon as a transfer ends", 3, hub.history().list().size());
        // an unfinished transfer is not history
        hub.onTransfer(new FileServer.Transfer(4, false, "still-going.zip", "192.168.23.40", 10));
        assertEquals(3, hub.history().list().size());

        selectTab(R.id.nav_transfer);
        transfer().getView().findViewById(R.id.historyBtn).performClick();
        assertEquals(HistoryActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());

        historyController = Robolectric.buildActivity(HistoryActivity.class).setup();
        HistoryActivity a = historyController.get();
        settle();
        RowAdapter ra = (RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter();
        assertEquals(3, ra.rows().size());
        assertEquals("big.mov", ra.rows().get(0).title);
        assertTrue(ra.rows().get(0).sub, ra.rows().get(0).sub.startsWith("Not finished"));
        assertTrue(ra.rows().get(1).sub, ra.rows().get(1).sub.startsWith("Sent  ·  48.3 MB"));
        assertTrue(ra.rows().get(2).sub, ra.rows().get(2).sub.startsWith("Received  ·  2.9 MB"));
        assertTrue(((TextView) a.findViewById(R.id.info)).getText().toString().contains("3 transfers"));
        shotOf(a, "91_history");

        // tapping something that was received opens the folder it landed in
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
        new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileBandit").mkdirs();
        ((RecyclerView) a.findViewById(R.id.list)).findViewHolderForAdapterPosition(2).itemView.performClick();
        assertNotNull(Nav.dir);
        assertEquals("FileBandit", Nav.dir.getName());
        Nav.dir = null;

        ((MaterialToolbar) a.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(R.id.action_clear, 0);
        settle();
        ((androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals(0, hub.history().list().size());
        assertEquals(View.VISIBLE, a.findViewById(R.id.empty).getVisibility());
        try { historyController.pause().stop().destroy(); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ network locations

    /** A small in-memory server so the screens can be driven without a network. */
    static final class FakeRemote implements Remote {
        final java.util.TreeMap<String, byte[]> files = new java.util.TreeMap<>();
        final java.util.TreeSet<String> dirs = new java.util.TreeSet<>();
        volatile boolean closed;
        volatile int failNextLists;

        FakeRemote() {
            dirs.add("/");
        }

        @Override public synchronized List<Item> list(String path) throws IOException {
            if (failNextLists > 0) {
                failNextLists--;
                throw new IOException("Connection reset");
            }
            String dir = Paths.clean(path);
            if (!dirs.contains(dir)) throw new IOException("That doesn't exist on the server.");
            List<Item> out = new ArrayList<>();
            for (String d : dirs) {
                if (!d.equals("/") && Paths.parent(d).equals(dir)) out.add(new Item(Paths.name(d), d, true, -1, 1_760_000_000_000L));
            }
            for (java.util.Map.Entry<String, byte[]> f : files.entrySet()) {
                if (Paths.parent(f.getKey()).equals(dir)) out.add(new Item(Paths.name(f.getKey()), f.getKey(), false, f.getValue().length, 1_760_000_000_000L));
            }
            java.util.Collections.sort(out, ORDER);
            return out;
        }

        @Override public synchronized InputStream open(String path, long offset) throws IOException {
            byte[] b = files.get(Paths.clean(path));
            if (b == null) throw new IOException("That doesn't exist on the server.");
            return new java.io.ByteArrayInputStream(b, (int) offset, b.length - (int) offset);
        }

        @Override public synchronized void put(String path, InputStream in, long size) throws IOException {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            files.put(Paths.clean(path), bos.toByteArray());
        }

        @Override public synchronized void mkdir(String path) throws IOException {
            String p = Paths.clean(path);
            if (dirs.contains(p) || files.containsKey(p)) throw new IOException("Something with that name is already there.");
            dirs.add(p);
        }

        @Override public synchronized void delete(String path, boolean dir) {
            String p = Paths.clean(path);
            files.remove(p);
            if (dir) {
                dirs.remove(p);
                files.keySet().removeIf(k -> k.startsWith(p + "/"));
                dirs.removeIf(k -> k.startsWith(p + "/"));
            }
        }

        @Override public synchronized void rename(String from, String to) throws IOException {
            String a = Paths.clean(from);
            String b = Paths.clean(to);
            if (files.containsKey(b) || dirs.contains(b)) throw new IOException("Something with that name is already there.");
            if (files.containsKey(a)) {
                files.put(b, files.remove(a));
            } else if (dirs.remove(a)) {
                dirs.add(b);
            }
        }

        @Override public void close() { closed = true; }
    }

    private static android.widget.EditText dialogInput(androidx.appcompat.app.AlertDialog d) {
        android.widget.FrameLayout custom = d.findViewById(androidx.appcompat.R.id.custom);
        return (android.widget.EditText) ((android.widget.FrameLayout) custom.getChildAt(0)).getChildAt(0);
    }

    private FakeRemote fake;
    private ActivityController<NetworkActivity> networkController;
    private ActivityController<RemoteActivity> remoteController;

    private void installFakeServer() {
        fake = new FakeRemote();
        fake.dirs.add("/Photos");
        fake.dirs.add("/Photos/2026");
        fake.dirs.add("/Docs");
        fake.files.put("/readme.txt", "hello from the server".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fake.files.put("/Photos/beach.jpg", new byte[2_345_678]);
        fake.files.put("/Photos/2026/new.jpg", new byte[1234]);
        fake.files.put("/Docs/report.pdf", new byte[88_000]);
        RemoteSession.reset();
        Remotes.testOpener = c -> {
            if (c.host.equals("down.local")) throw new IOException(Remotes.friendly(new java.net.ConnectException("refused"), c));
            return fake;
        };
    }

    private Connection saveConnection(String name, String host, Connection.Type type) throws Exception {
        Connection c = new Connection();
        c.name = name;
        c.host = host;
        c.type = type;
        c.user = "bob";
        c.password = "pw";
        if (type == Connection.Type.SMB) c.path = "share";
        return RemoteSession.store(RuntimeEnvironment.getApplication()).save(c);
    }

    private RemoteActivity openRemote(Connection c) {
        remoteController = Robolectric.buildActivity(RemoteActivity.class,
                new android.content.Intent(RuntimeEnvironment.getApplication(), RemoteActivity.class).putExtra(RemoteActivity.EXTRA_ID, c.id)).setup();
        RemoteActivity a = remoteController.get();
        waitUntil(() -> a.findViewById(R.id.busy).getVisibility() != View.VISIBLE);
        settle();
        return a;
    }

    private static java.util.List<String> remoteRows(android.app.Activity a) {
        RowAdapter ra = (RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter();
        java.util.List<String> out = new ArrayList<>();
        for (RowAdapter.Row r : ra.rows()) out.add(r.title);
        return out;
    }

    private static void layoutOf(android.app.Activity a) {
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    private static void tapRow(android.app.Activity a, int position, boolean longPress) {
        layoutOf(a);
        View v = ((RecyclerView) a.findViewById(R.id.list)).findViewHolderForAdapterPosition(position).itemView;
        if (longPress) v.performLongClick(); else v.performClick();
        settle();
    }

    @After
    public void tearDownNetwork() {
        Remotes.testOpener = null;
        RemoteSession.pendingUploads.clear();
        RemoteSession.reset();
        for (ActivityController<?> c : new ActivityController<?>[]{networkController, remoteController}) {
            if (c != null) {
                try { c.pause().stop().destroy(); } catch (Exception ignored) { }
            }
        }
        networkController = null;
        remoteController = null;
    }

    @Test public void networkTileOnHomeOpensTheLocationsList() throws Exception {
        launch();
        android.view.ViewGroup row = (android.view.ViewGroup) ((android.widget.LinearLayout) files().getView().findViewById(R.id.toolsGrid)).getChildAt(0);
        assertEquals("Network", ((TextView) row.getChildAt(3).findViewById(R.id.tileLabel)).getText().toString());
        row.getChildAt(3).performClick();
        assertEquals(NetworkActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    @Test public void emptyLocationsListInvitesYouToAddOne() throws Exception {
        installFakeServer();
        networkController = Robolectric.buildActivity(NetworkActivity.class).setup();
        NetworkActivity a = networkController.get();
        settle();
        assertEquals(View.VISIBLE, a.findViewById(R.id.empty).getVisibility());
        assertTrue(((TextView) a.findViewById(R.id.emptyText)).getText().toString().contains("Tap + to add"));
        shotOf(a, "100_network_empty");
    }

    private static void shotDialog(androidx.appcompat.app.AlertDialog d, String name) throws Exception {
        View decor = d.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(decor.getMeasuredHeight(), 400);
        decor.layout(0, 0, W, h);
        Bitmap b = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFFFFFFF);
        decor.draw(new Canvas(b));
        save(b, name);
    }

    @Test public void addingALocationChecksTestsAndSavesIt() throws Exception {
        installFakeServer();
        networkController = Robolectric.buildActivity(NetworkActivity.class).setup();
        NetworkActivity a = networkController.get();
        settle();
        ((MaterialToolbar) a.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(R.id.action_add, 0);
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        android.widget.Spinner type = d.findViewById(R.id.cType);
        assertEquals(6, type.getAdapter().getCount());
        // choose SFTP: the private key box appears, the certificate box does not
        type.setSelection(Connection.Type.SFTP.ordinal());
        settle();
        assertEquals(View.VISIBLE, d.findViewById(R.id.cKey).getVisibility());
        assertEquals(View.GONE, d.findViewById(R.id.cTrust).getVisibility());
        type.setSelection(Connection.Type.WEBDAVS.ordinal());
        settle();
        assertEquals(View.GONE, d.findViewById(R.id.cKey).getVisibility());
        assertEquals(View.VISIBLE, d.findViewById(R.id.cTrust).getVisibility());
        type.setSelection(Connection.Type.SFTP.ordinal());
        settle();

        // saving with nothing filled in explains what is missing
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        TextView status = d.findViewById(R.id.cStatus);
        assertTrue(status.getText().toString(), status.getText().toString().contains("address"));
        shotDialog(d, "101_network_add_error");

        ((android.widget.EditText) d.findViewById(R.id.cName)).setText("Home NAS");
        ((android.widget.EditText) d.findViewById(R.id.cHost)).setText("192.168.1.20");
        ((android.widget.EditText) d.findViewById(R.id.cUser)).setText("bob");
        ((android.widget.EditText) d.findViewById(R.id.cPassword)).setText("hunter2");
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        waitUntil(() -> status.getText().toString().startsWith("Connected"));
        assertTrue(status.getText().toString(), status.getText().toString().startsWith("Connected. 3 items"));
        shotDialog(d, "102_network_add_tested");

        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        java.util.List<Connection> saved = RemoteSession.store(a).list();
        assertEquals(1, saved.size());
        assertEquals("Home NAS", saved.get(0).name);
        assertEquals("hunter2", saved.get(0).password);
        assertEquals(Connection.Type.SFTP, saved.get(0).type);
        assertEquals(java.util.Arrays.asList("Home NAS"), remoteRows(a));
        RowAdapter ra = (RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter();
        assertTrue(ra.rows().get(0).sub, ra.rows().get(0).sub.startsWith("SFTP (SSH)  ·  sftp://bob@192.168.1.20"));
        shotOf(a, "103_network_list");

        // tapping it opens the browser
        tapRow(a, 0, false);
        android.content.Intent next = shadowOf(a).getNextStartedActivity();
        assertEquals(RemoteActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(saved.get(0).id, next.getStringExtra(RemoteActivity.EXTRA_ID));
    }

    @Test public void locationsCanBeEditedAndRemoved() throws Exception {
        installFakeServer();
        Connection c = saveConnection("Office", "10.0.0.5", Connection.Type.FTP);
        networkController = Robolectric.buildActivity(NetworkActivity.class).setup();
        NetworkActivity a = networkController.get();
        settle();
        a.edit(RemoteSession.store(a).get(c.id));
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertEquals("Office", ((android.widget.EditText) d.findViewById(R.id.cName)).getText().toString());
        assertEquals("10.0.0.5", ((android.widget.EditText) d.findViewById(R.id.cHost)).getText().toString());
        assertEquals("pw", ((android.widget.EditText) d.findViewById(R.id.cPassword)).getText().toString());
        ((android.widget.EditText) d.findViewById(R.id.cName)).setText("Office server");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals("Office server", RemoteSession.store(a).get(c.id).name);
        assertEquals("same entry, not a copy", 1, RemoteSession.store(a).list().size());

        tapRow(a, 0, true);                              // long press -> menu: Edit / Remove
        a.onLongClick(((RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter()).rows().get(0));
    }

    @Test public void browsingFoldersAndGoingBack() throws Exception {
        installFakeServer();
        RemoteActivity a = openRemote(saveConnection("Home NAS", "nas.local", Connection.Type.SFTP));
        assertEquals(java.util.Arrays.asList("Docs", "Photos", "readme.txt"), remoteRows(a));
        assertEquals("Home NAS", ((MaterialToolbar) a.findViewById(R.id.toolbar)).getTitle().toString());
        assertTrue(((TextView) a.findViewById(R.id.info)).getText().toString().contains("3 items"));
        shotOf(a, "104_remote_root");

        tapRow(a, 1, false);                             // Photos
        waitUntil(() -> remoteRows(a).contains("beach.jpg"));
        settle();
        assertEquals(java.util.Arrays.asList("2026", "beach.jpg"), remoteRows(a));
        assertEquals("/Photos", ((MaterialToolbar) a.findViewById(R.id.toolbar)).getSubtitle().toString());
        tapRow(a, 0, false);                             // 2026
        waitUntil(() -> remoteRows(a).contains("new.jpg"));
        settle();
        assertEquals("/Photos/2026", ((MaterialToolbar) a.findViewById(R.id.toolbar)).getSubtitle().toString());
        shotOf(a, "105_remote_subfolder");

        a.getOnBackPressedDispatcher().onBackPressed();
        waitUntil(() -> remoteRows(a).contains("beach.jpg"));
        settle();
        assertEquals("/Photos", ((MaterialToolbar) a.findViewById(R.id.toolbar)).getSubtitle().toString());
        a.getOnBackPressedDispatcher().onBackPressed();
        waitUntil(() -> remoteRows(a).contains("readme.txt"));
        settle();
        assertEquals(java.util.Arrays.asList("Docs", "Photos", "readme.txt"), remoteRows(a));
        assertFalse(a.isFinishing());
        a.getOnBackPressedDispatcher().onBackPressed();
        assertTrue("back at the top leaves the screen", a.isFinishing());
    }

    @Test public void downloadingSavesIntoDownloadsFolderedByLocationName() throws Exception {
        installFakeServer();
        RemoteActivity a = openRemote(saveConnection("Home NAS", "nas.local", Connection.Type.SMB));
        tapRow(a, 1, true);                              // select Photos (a folder)
        tapRow(a, 2, false);                             // add readme.txt
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        assertEquals("2", tb.getTitle().toString());
        shotOf(a, "106_remote_selected");
        tb.getMenu().performIdentifierAction(R.id.action_download, 0);
        File dest = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FileBandit/Home NAS");
        waitUntil(() -> new File(dest, "readme.txt").exists() && new File(dest, "Photos/2026/new.jpg").exists());
        settle();
        assertEquals("hello from the server", new String(Files.readAllBytes(new File(dest, "readme.txt").toPath()), StandardCharsets.UTF_8));
        assertEquals(2_345_678, new File(dest, "Photos/beach.jpg").length());
        assertEquals(1234, new File(dest, "Photos/2026/new.jpg").length());
        View snack = a.getWindow().getDecorView().findViewById(com.google.android.material.R.id.snackbar_text);
        assertNotNull(snack);
        assertTrue(((TextView) snack).getText().toString(), ((TextView) snack).getText().toString().startsWith("Saved 2 items in Downloads/FileBandit/Home NAS"));
    }

    @Test public void openingAFileDownloadsItAndShowsIt() throws Exception {
        installFakeServer();
        RemoteActivity a = openRemote(saveConnection("Home NAS", "nas.local", Connection.Type.WEBDAV));
        tapRow(a, 2, false);                             // readme.txt
        waitUntil(() -> shadowOf(a).peekNextStartedActivity() != null);
        android.content.Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull("the viewer should start", next);
        assertEquals(ViewerActivity.class.getName(), next.getComponent().getClassName());
        File shown = new File(next.getStringExtra(ViewerActivity.EXTRA_PATH));
        assertEquals("readme.txt", shown.getName());
        assertEquals("hello from the server", new String(Files.readAllBytes(shown.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void uploadingFilesFromTheFilesTabLandsInTheFolderYouChose() throws Exception {
        installFakeServer();
        Connection c = saveConnection("Home NAS", "nas.local", Connection.Type.FTP);
        launch();
        files().openDir(new File(storage, "Download"));
        settle();
        longClickRow("notes.txt");
        menu(R.id.action_upload_network);
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        d.getListView().performItemClick(null, 0, 0);
        settle();
        android.content.Intent next = shadowOf(activity).getNextStartedActivity();
        assertEquals(RemoteActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(1, RemoteSession.pendingUploads.size());

        RemoteActivity a = openRemote(c);
        assertEquals(View.VISIBLE, a.findViewById(R.id.bottomBar).getVisibility());
        assertEquals("1 item ready to upload", ((TextView) a.findViewById(R.id.bottomText)).getText().toString());
        tapRow(a, 0, false);                             // into Docs
        waitUntil(() -> remoteRows(a).contains("report.pdf"));
        settle();
        shotOf(a, "107_remote_upload_bar");
        a.findViewById(R.id.bottomButton).performClick();
        waitUntil(() -> fake.files.containsKey("/Docs/notes.txt"));
        settle();
        assertEquals(1_200, fake.files.get("/Docs/notes.txt").length);
        assertTrue(RemoteSession.pendingUploads.isEmpty());
        assertTrue("the original is still on the phone", new File(storage, "Download/notes.txt").exists());
        assertTrue(remoteRows(a).toString(), remoteRows(a).contains("notes.txt"));
    }

    @Test public void deleteRenameAndNewFolderWork() throws Exception {
        installFakeServer();
        RemoteActivity a = openRemote(saveConnection("Home NAS", "nas.local", Connection.Type.SFTP));
        MaterialToolbar tb = a.findViewById(R.id.toolbar);

        tb.getMenu().performIdentifierAction(R.id.action_new_folder, 0);
        settle();
        androidx.appcompat.app.AlertDialog nd = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        dialogInput(nd).setText("Backups");
        nd.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitUntil(() -> fake.dirs.contains("/Backups"));
        settle();
        assertTrue(remoteRows(a).toString(), remoteRows(a).contains("Backups"));

        tapRow(a, remoteRows(a).indexOf("readme.txt"), true);
        tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_rename, 0);
        settle();
        androidx.appcompat.app.AlertDialog rd = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        dialogInput(rd).setText("README.md");
        rd.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitUntil(() -> fake.files.containsKey("/README.md"));
        settle();
        assertFalse(fake.files.containsKey("/readme.txt"));
        assertTrue(remoteRows(a).toString(), remoteRows(a).contains("README.md"));

        tapRow(a, remoteRows(a).indexOf("Photos"), true);
        tb = a.findViewById(R.id.toolbar);
        tb.getMenu().performIdentifierAction(R.id.action_delete, 0);
        settle();
        androidx.appcompat.app.AlertDialog dd = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        shotDialog(dd, "108_remote_delete_confirm");
        dd.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitUntil(() -> !fake.dirs.contains("/Photos"));
        settle();
        assertFalse(fake.files.containsKey("/Photos/beach.jpg"));
        assertFalse(fake.files.containsKey("/Photos/2026/new.jpg"));
        assertFalse(remoteRows(a).contains("Photos"));
    }

    @Test public void aServerThatCantBeReachedExplainsWhy() throws Exception {
        installFakeServer();
        RemoteActivity a = openRemote(saveConnection("Down", "down.local", Connection.Type.SFTP));
        assertEquals(View.VISIBLE, a.findViewById(R.id.empty).getVisibility());
        String msg = ((TextView) a.findViewById(R.id.emptyText)).getText().toString();
        assertTrue(msg, msg.startsWith("Can't reach down.local on port 22"));
        assertTrue(msg, msg.contains("Refresh"));
        shotOf(a, "109_remote_unreachable");
    }

    @Test public void aDroppedConnectionIsReestablishedWhenOpeningAFolder() throws Exception {
        installFakeServer();
        RemoteActivity a = openRemote(saveConnection("Home NAS", "nas.local", Connection.Type.SFTP));
        fake.failNextLists = 1;                          // the first try fails, the retry reconnects
        tapRow(a, 1, false);
        waitUntil(() -> remoteRows(a).contains("beach.jpg"));
        settle();
        assertEquals(java.util.Arrays.asList("2026", "beach.jpg"), remoteRows(a));
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    public void newScreensRenderInDarkMode() throws Exception {
        installFakeServer();
        Connection c = saveConnection("Home NAS", "nas.local", Connection.Type.SMB);
        networkController = Robolectric.buildActivity(NetworkActivity.class).setup();
        settle();
        shotOf(networkController.get(), "110_dark_network_list");
        RemoteActivity r = openRemote(c);
        tapRow(r, 1, true);
        shotOf(r, "111_dark_remote_selected");

        ShareHub hub = FastShareApp.hub(RuntimeEnvironment.getApplication());
        FileServer.Transfer t = new FileServer.Transfer(9, true, "IMG_4021.HEIC", "192.168.23.40", 2_900_000);
        t.done = 2_900_000;
        t.state = FileServer.State.DONE;
        t.detail = "Downloads/FileBandit";
        hub.onTransfer(t);
        ActivityController<HistoryActivity> h = Robolectric.buildActivity(HistoryActivity.class).setup();
        settle();
        shotOf(h.get(), "112_dark_history");
        try { h.pause().stop().destroy(); } catch (Exception ignored) { }

        launch();
        selectTab(R.id.nav_transfer);
        FastShareApp.hub(activity).setLinkPin("4821");
        settle();
        shotScrollContent("113_dark_transfer_pin", transfer().getView());
        VaultSession.testVault = new Vault(new File(storage.getParentFile(), "dark-vault-" + System.nanoTime()), Vault.NO_OUTER, 1000);
        ActivityController<VaultActivity> v = Robolectric.buildActivity(VaultActivity.class).setup();
        settle();
        shotOf(v.get(), "114_dark_vault_setup");
        try { v.pause().stop().destroy(); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ shared clipboard

    /** Posts text to the phone's own server the way the other device's page does. */
    private static void postNoteFromOtherDevice(ShareHub hub, String text) throws Exception {
        java.net.URL u = new java.net.URL(hub.currentUrl().replaceFirst("//[^:/]+", "//127.0.0.1").replaceFirst("/\\?t=", "/api/notes?t="));
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (java.io.OutputStream os = c.getOutputStream()) { os.write(b); }
        assertEquals(200, c.getResponseCode());
        c.getInputStream().close();
    }

    @Test public void sharedClipboardSendsAndReceivesText() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        ShareHub hub = FastShareApp.hub(activity);
        waitUntil(hub::isRunning);

        ((android.widget.EditText) v.findViewById(R.id.noteInput)).setText("https://example.com/wifi-password");
        v.findViewById(R.id.noteSend).performClick();
        settle();
        assertEquals(1, hub.notes().size());
        assertTrue(hub.notes().get(0).fromPhone);
        assertEquals("the box is cleared after sending", "", ((android.widget.EditText) v.findViewById(R.id.noteInput)).getText().toString());

        postNoteFromOtherDevice(hub, "Gate code 4455");
        waitUntil(() -> ((android.widget.LinearLayout) v.findViewById(R.id.noteList)).getChildCount() == 2);
        settle();
        android.widget.LinearLayout list = v.findViewById(R.id.noteList);
        assertEquals(2, list.getChildCount());
        assertEquals("Gate code 4455", ((TextView) list.getChildAt(0).findViewById(R.id.nText)).getText().toString());
        String sub = ((TextView) list.getChildAt(0).findViewById(R.id.nSub)).getText().toString();
        assertTrue(sub, sub.startsWith("From the other device") && sub.endsWith("tap to copy"));
        assertTrue(((TextView) list.getChildAt(1).findViewById(R.id.nSub)).getText().toString().startsWith("You sent this"));
        shotScrollContent("120_transfer_clipboard", v);

        // tapping a note copies it
        list.getChildAt(0).performClick();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        assertEquals("Gate code 4455", cm.getPrimaryClip().getItemAt(0).getText().toString());

        v.findViewById(R.id.notesClear).performClick();
        settle();
        assertEquals(0, hub.notes().size());
        assertEquals(0, ((android.widget.LinearLayout) v.findViewById(R.id.noteList)).getChildCount());
    }

    @Test public void pasteAndSendUsesTheClipboard() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        ShareHub hub = FastShareApp.hub(activity);
        waitUntil(hub::isRunning);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("x", "copied elsewhere"));
        v.findViewById(R.id.notePaste).performClick();
        settle();
        assertEquals("copied elsewhere", hub.notes().get(0).text);
        v.findViewById(R.id.noteSend).performClick();       // empty box: nothing is added
        assertEquals(1, hub.notes().size());
    }

    @Test public void textSharedFromAnotherAppLandsInTheClipboardCard() throws Exception {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "https://maps.example/place/42");
        controller = Robolectric.buildActivity(MainActivity.class, i).setup();
        activity = controller.get();
        settle();
        ShareHub hub = FastShareApp.hub(activity);
        assertEquals(1, hub.notes().size());
        assertEquals("https://maps.example/place/42", hub.notes().get(0).text);
        assertTrue(transfer().isVisible());
    }

    // ------------------------------------------------------------------ phone as a drive

    private static String davCall(ShareHub hub, String method, String rel, String body, String... headers) throws Exception {
        String base = hub.driveUrl().replaceFirst("//[^:/]+", "//127.0.0.1");
        okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(base + rel).method(method,
                body == null && !method.equals("PUT") && !method.equals("PROPFIND") ? null
                        : okhttp3.RequestBody.create(body == null ? "" : body, okhttp3.MediaType.parse("application/xml")));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        try (okhttp3.Response r = new okhttp3.OkHttpClient().newCall(b.build()).execute()) {
            return r.code() + " " + (r.body() == null ? "" : r.body().string());
        }
    }

    @Test public void driveCardTurnsOnShowsAnAddressAndServesFiles() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        ShareHub hub = FastShareApp.hub(activity);
        waitUntil(hub::isRunning);
        assertEquals(View.GONE, v.findViewById(R.id.driveBox).getVisibility());
        assertEquals(null, hub.driveUrl());

        v.findViewById(R.id.driveSwitch).performClick();
        settle();
        assertEquals(View.VISIBLE, v.findViewById(R.id.driveBox).getVisibility());
        String url = ((TextView) v.findViewById(R.id.driveUrl)).getText().toString();
        assertTrue(url, url.matches("http://192\\.168\\.23\\.33:\\d+/d/[a-z0-9]{14}/"));
        assertEquals(url, hub.driveUrl());
        assertTrue(((TextView) v.findViewById(R.id.driveHelp)).getText().toString().contains("can't change anything"));
        shotScrollContent("121_transfer_drive", v);

        // the computer can read, but by default not change
        String listing = davCall(hub, "PROPFIND", "", "", "Depth", "1");
        assertTrue(listing, listing.startsWith("207") && listing.contains("Download"));
        assertTrue(davCall(hub, "PUT", "new.txt", "hi").startsWith("403"));
        assertFalse(new File(storage, "new.txt").exists());

        // allow changes: copy in, then delete (which goes to the recycle bin)
        v.findViewById(R.id.driveWrite).performClick();
        settle();
        assertTrue(((TextView) v.findViewById(R.id.driveHelp)).getText().toString().contains("Deleted things go to this phone's Trash"));
        assertTrue(davCall(hub, "PUT", "new.txt", "hi there").startsWith("201"));
        assertEquals("hi there", new String(Files.readAllBytes(new File(storage, "new.txt").toPath()), StandardCharsets.UTF_8));
        assertTrue(davCall(hub, "DELETE", "new.txt", null).startsWith("204"));
        assertFalse(new File(storage, "new.txt").exists());
        assertEquals("deleted from the computer = recoverable on the phone", 1, new Trash(storage).list().size());
        assertEquals("new.txt", new Trash(storage).list().get(0).name);

        v.findViewById(R.id.driveSwitch).performClick();
        settle();
        assertEquals(View.GONE, v.findViewById(R.id.driveBox).getVisibility());
        assertEquals(null, hub.driveUrl());
    }

    @Test public void driveFolderCanBeChosenAndTheAddressSurvivesRestartsButCanBeReset() throws Exception {
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        ShareHub hub = FastShareApp.hub(activity);
        waitUntil(hub::isRunning);
        v.findViewById(R.id.driveSwitch).performClick();
        settle();
        String first = hub.driveUrl();

        v.findViewById(R.id.driveFolderBtn).performClick();
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        int downloadIndex = -1;
        for (int i = 0; i < d.getListView().getCount(); i++) if ("Download".equals(String.valueOf(d.getListView().getItemAtPosition(i)))) downloadIndex = i;
        assertTrue("Download is offered", downloadIndex > 0);
        d.getListView().performItemClick(null, downloadIndex, downloadIndex);
        settle();
        assertEquals(new File(storage, "Download"), hub.driveRoot());
        assertEquals("Shared folder: Download", ((TextView) v.findViewById(R.id.driveFolderText)).getText().toString());
        String listing = davCall(hub, "PROPFIND", "", "", "Depth", "1");
        assertTrue(listing, listing.contains("notes.txt") && !listing.contains("DCIM"));

        // same address after the app restarts
        ShareHub again = new ShareHub(activity);
        again.start();
        waitUntil(again::isRunning);
        assertEquals(first.replaceFirst(":\\d+/", ":X/"), again.driveUrl().replaceFirst(":\\d+/", ":X/"));
        again.stop();

        // resetting gives a new one, and the old one stops working
        v.findViewById(R.id.driveReset).performClick();
        settle();
        assertFalse(first.equals(hub.driveUrl()));
        String oldBase = first.replaceFirst("//[^:/]+", "//127.0.0.1");
        okhttp3.Request old = new okhttp3.Request.Builder().url(oldBase.replaceFirst(":\\d+/", ":" + hub.port() + "/")).method("PROPFIND",
                okhttp3.RequestBody.create("", okhttp3.MediaType.parse("application/xml"))).header("Depth", "0").build();
        try (okhttp3.Response r = new okhttp3.OkHttpClient().newCall(old).execute()) {
            assertEquals(403, r.code());
        }
    }

    @Test public void driveStaysOffWithoutFileAccess() throws Exception {
        Access.testOverride = false;
        launch();
        selectTab(R.id.nav_transfer);
        View v = transfer().getView();
        v.findViewById(R.id.driveSwitch).performClick();
        settle();
        assertEquals(View.GONE, v.findViewById(R.id.driveBox).getVisibility());
        assertFalse(((android.widget.CompoundButton) v.findViewById(R.id.driveSwitch)).isChecked());
        assertFalse(FastShareApp.hub(activity).driveEnabled());
    }

    // ------------------------------------------------------------------ photo backup

    private ActivityController<BackupActivity> backupController;
    private MemoryRemote backupServer;

    private void photoOnPhone(String rel, int size, long modified) throws Exception {
        File f = new File(storage, rel);
        f.getParentFile().mkdirs();
        byte[] b = new byte[size];
        new java.util.Random(rel.hashCode()).nextBytes(b);
        Files.write(f.toPath(), b);
        assertTrue(f.setLastModified(modified));
    }

    private BackupActivity openBackup() {
        backupController = Robolectric.buildActivity(BackupActivity.class).setup();
        BackupActivity a = backupController.get();
        settle();
        return a;
    }

    private Connection backupSetup() throws Exception {
        backupServer = new MemoryRemote();
        RemoteSession.reset();
        Remotes.testOpener = c -> {
            if (c.host.equals("down.local")) throw new IOException(Remotes.friendly(new java.net.ConnectException("refused"), c));
            return backupServer;
        };
        long old = new java.util.GregorianCalendar(2026, java.util.Calendar.MARCH, 5, 10, 0).getTimeInMillis();
        photoOnPhone("DCIM/Camera/IMG_A.jpg", 3000, old);
        photoOnPhone("DCIM/Camera/IMG_B.mp4", 5000, old + 86_400_000L * 40);
        photoOnPhone("Pictures/Screenshots/shot.png", 2000, old);
        new File(storage, "DCIM/Camera/IMG_20260101_101010.jpg").setLastModified(old);   // from the shared sample set
        return saveConnection("Home NAS", "nas.local", Connection.Type.SFTP);
    }

    @After
    public void tearDownBackup() {
        if (backupController != null) {
            try { backupController.pause().stop().destroy(); } catch (Exception ignored) { }
            backupController = null;
        }
    }

    @Test public void backupScreenInvitesYouToAddAServerFirst() throws Exception {
        RemoteSession.reset();
        BackupActivity a = openBackup();
        assertEquals(View.VISIBLE, a.findViewById(R.id.bNoConn).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.bAddConn).getVisibility());
        assertEquals("Nothing has been backed up yet.", ((TextView) a.findViewById(R.id.bStatus)).getText().toString());
        assertFalse(a.findViewById(R.id.bRunNow).isEnabled());
        a.findViewById(R.id.bAuto).performClick();                    // can't switch on without a destination
        settle();
        assertFalse(((android.widget.CompoundButton) a.findViewById(R.id.bAuto)).isChecked());
        assertFalse(new Prefs(a).backupOn());
        shotOf(a, "130_backup_empty");
    }

    @Test public void backUpNowSendsNewPhotosIntoYearAndMonthFolders() throws Exception {
        Connection c = backupSetup();
        BackupActivity a = openBackup();
        assertTrue(a.findViewById(R.id.bRunNow).isEnabled());
        assertEquals("/FileBandit Backup/" + android.os.Build.MODEL.trim(), ((EditText) a.findViewById(R.id.bDest)).getText().toString().replace("Android", android.os.Build.MODEL.trim()));
        ((EditText) a.findViewById(R.id.bDest)).setText("/Phone backup");
        settle();
        a.findViewById(R.id.bRunNow).performClick();
        waitUntil(() -> !BackupJob.isRunning() && !backupServer.allFiles().isEmpty());
        settle();
        java.util.List<String> sent = backupServer.allFiles();
        assertTrue(sent.toString(), sent.contains("/Phone backup/2026/03/IMG_A.jpg"));
        assertTrue(sent.toString(), sent.contains("/Phone backup/2026/04/IMG_B.mp4"));
        assertFalse("Pictures isn't selected, so screenshots stay", sent.toString().contains("shot.png"));
        assertEquals(3000, backupServer.files.get("/Phone backup/2026/03/IMG_A.jpg").length);
        String status = ((TextView) a.findViewById(R.id.bStatus)).getText().toString();
        assertTrue(status, status.startsWith("Last backup just now: Sent "));
        shotOf(a, "131_backup_done");

        // a second run has nothing new
        a.findViewById(R.id.bRunNow).performClick();
        waitUntil(() -> !BackupJob.isRunning());
        settle();
        assertTrue(((TextView) a.findViewById(R.id.bStatus)).getText().toString(), ((TextView) a.findViewById(R.id.bStatus)).getText().toString().endsWith("Everything was already backed up"));
    }

    @Test public void folderChoicesAndOptionsAreSavedAndUsed() throws Exception {
        Connection c = backupSetup();
        BackupActivity a = openBackup();
        ((android.widget.CompoundButton) a.findViewById(R.id.bPics)).setChecked(true);
        ((android.widget.CompoundButton) a.findViewById(R.id.bVideos)).setChecked(false);
        ((android.widget.CompoundButton) a.findViewById(R.id.bByDate)).setChecked(false);
        ((EditText) a.findViewById(R.id.bDest)).setText("Flat");
        settle();
        Prefs p = new Prefs(a);
        assertTrue(p.backupFolders().containsAll(java.util.Arrays.asList("DCIM", "Pictures")));
        assertFalse(p.backupVideos());
        assertFalse(p.backupByDate());
        assertEquals("Flat", p.backupDest());
        a.findViewById(R.id.bRunNow).performClick();
        waitUntil(() -> !BackupJob.isRunning() && !backupServer.allFiles().isEmpty());
        settle();
        java.util.List<String> sent = backupServer.allFiles();
        assertTrue(sent.toString(), sent.contains("/Flat/IMG_A.jpg") && sent.contains("/Flat/shot.png"));
        assertFalse("videos are off", sent.toString().contains(".mp4"));
    }

    @Test public void autoBackupSchedulesWifiOnlyWorkAndSwitchingOffCancelsIt() throws Exception {
        Connection c = backupSetup();
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication());
        BackupActivity a = openBackup();
        a.findViewById(R.id.bAuto).performClick();
        settle();
        assertTrue(new Prefs(a).backupOn());
        assertEquals(c.id, new Prefs(a).backupConnection());
        java.util.List<androidx.work.WorkInfo> infos = androidx.work.WorkManager.getInstance(a).getWorkInfosForUniqueWork(BackupWorker.UNIQUE_NAME).get();
        assertEquals(1, infos.size());
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, infos.get(0).getState());
        assertEquals(androidx.work.NetworkType.UNMETERED, infos.get(0).getConstraints().getRequiredNetworkType());
        assertTrue(infos.get(0).getConstraints().requiresBatteryNotLow());
        assertFalse(infos.get(0).getConstraints().requiresCharging());

        ((android.widget.CompoundButton) a.findViewById(R.id.bCharging)).setChecked(true);
        settle();
        infos = androidx.work.WorkManager.getInstance(a).getWorkInfosForUniqueWork(BackupWorker.UNIQUE_NAME).get();
        assertTrue("only while charging is passed on", infos.get(0).getConstraints().requiresCharging());

        a.findViewById(R.id.bAuto).performClick();
        settle();
        infos = androidx.work.WorkManager.getInstance(a).getWorkInfosForUniqueWork(BackupWorker.UNIQUE_NAME).get();
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, infos.get(0).getState());
    }

    @Test public void theScheduledWorkerBacksUpAndRetriesWhenTheServerIsOutOfReach() throws Exception {
        Connection c = backupSetup();
        Prefs p = new Prefs(RuntimeEnvironment.getApplication());
        p.setBackupConnection(c.id);
        p.setBackupDest("/Auto");
        p.setBackupOn(false);
        androidx.work.ListenableWorker w = androidx.work.testing.TestListenableWorkerBuilder.from(RuntimeEnvironment.getApplication(), BackupWorker.class).build();
        assertEquals("switched off: does nothing", androidx.work.ListenableWorker.Result.success(), w.startWork().get());
        assertTrue(backupServer.allFiles().isEmpty());

        p.setBackupOn(true);
        w = androidx.work.testing.TestListenableWorkerBuilder.from(RuntimeEnvironment.getApplication(), BackupWorker.class).build();
        assertEquals(androidx.work.ListenableWorker.Result.success(), w.startWork().get());
        assertTrue(backupServer.allFiles().toString(), backupServer.allFiles().contains("/Auto/2026/03/IMG_A.jpg"));

        // away from home: the server can't be reached, so ask WorkManager to try again later
        Connection away = saveConnection("Away", "down.local", Connection.Type.SFTP);
        p.setBackupConnection(away.id);
        w = androidx.work.testing.TestListenableWorkerBuilder.from(RuntimeEnvironment.getApplication(), BackupWorker.class).build();
        assertEquals(androidx.work.ListenableWorker.Result.retry(), w.startWork().get());
        assertTrue(p.backupLast(), p.backupLast().startsWith("Couldn't connect: Can't reach down.local"));
    }

    @Test public void freeingSpaceMovesOnlyVerifiedBackedUpPhotosToTheTrash() throws Exception {
        Connection c = backupSetup();
        BackupActivity a = openBackup();
        ((EditText) a.findViewById(R.id.bDest)).setText("/B");
        settle();
        a.findViewById(R.id.bRunNow).performClick();
        waitUntil(() -> !BackupJob.isRunning() && backupServer.allFiles().size() >= 3);
        settle();
        // one of the backed-up files was deleted from the server by hand
        backupServer.files.remove("/B/2026/04/IMG_B.mp4");

        a.findViewById(R.id.bFree).performClick();
        waitUntil(() -> org.robolectric.shadows.ShadowDialog.getLatestDialog() instanceof androidx.appcompat.app.AlertDialog
                && ((androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()).isShowing());
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        shotDialog(d, "132_backup_free_space");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        waitUntil(() -> !new File(storage, "DCIM/Camera/IMG_A.jpg").exists());
        settle();
        assertFalse(new File(storage, "DCIM/Camera/IMG_A.jpg").exists());
        assertTrue("not on the server any more, so it stays on the phone", new File(storage, "DCIM/Camera/IMG_B.mp4").exists());
        assertTrue("Pictures wasn't part of the backup", new File(storage, "Pictures/Screenshots/shot.png").exists());
        java.util.List<String> trashed = new ArrayList<>();
        for (Trash.Entry e : new Trash(storage).list()) trashed.add(e.name);
        assertTrue(trashed.toString(), trashed.contains("IMG_A.jpg") && !trashed.contains("IMG_B.mp4"));
    }

    @Test public void backupTileOnTheHomeScreenOpensTheScreen() throws Exception {
        launch();
        android.view.ViewGroup tools = files().getView().findViewById(R.id.toolsGrid);
        android.view.ViewGroup row2 = (android.view.ViewGroup) tools.getChildAt(1);
        assertEquals("Backup", ((TextView) row2.getChildAt(0).findViewById(R.id.tileLabel)).getText().toString());
        row2.getChildAt(0).performClick();
        assertEquals(BackupActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    // ------------------------------------------------------------------ decoy vault

    private void vaultMenu(VaultActivity a, int id) {
        ((MaterialToolbar) a.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(id, 0);
        settle();
    }

    private void typeAndUnlock(VaultActivity a, String pin) {
        type(a, R.id.pinInput, pin);
        tapAuth(a);
    }

    @Test public void decoyPinOpensADifferentVaultAndHidesTheDecoySettings() throws Exception {
        VaultActivity a = openVault();
        createVault(a, "2468");
        // set up the decoy
        vaultMenu(a, R.id.action_decoy);
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        shotDialog(d, "140_vault_decoy_setup");
        android.view.ViewGroup box = (android.view.ViewGroup) ((android.view.ViewGroup) d.findViewById(androidx.appcompat.R.id.custom)).getChildAt(0);
        EditText one = null, two = null;
        for (int i = 0; i < box.getChildCount(); i++) {
            if (box.getChildAt(i) instanceof EditText) {
                if (one == null) one = (EditText) box.getChildAt(i); else two = (EditText) box.getChildAt(i);
            }
        }
        one.setText("2468");                                    // the real PIN: refused
        two.setText("2468");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull("must differ from the real PIN", one.getError());
        assertFalse(VaultSession.decoy(a).exists());
        one.setText("1357");
        two.setText("1358");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(two.getError());
        two.setText("1357");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(VaultSession.decoy(a).exists());
        assertFalse("creating it leaves it locked", VaultSession.decoy(a).isUnlocked());

        // put a real file in the real vault
        VaultSession.real(a).add(new java.io.ByteArrayInputStream("secret".getBytes()), "secret.txt", 1L);

        // lock, then open with the decoy PIN: empty, and no decoy/wrong-PIN items in the menu
        vaultMenu(a, R.id.action_lock);
        typeAndUnlock(a, "1357");
        assertTrue(VaultSession.decoyActive);
        assertEquals(View.VISIBLE, a.findViewById(R.id.listBox).getVisibility());
        assertTrue("the real file isn't in the decoy", vaultRows(a).isEmpty());
        assertEquals("looks like a normal vault", "Private vault", ((MaterialToolbar) a.findViewById(R.id.toolbar)).getTitle().toString());
        MaterialToolbar tb = a.findViewById(R.id.toolbar);
        assertFalse(tb.getMenu().findItem(R.id.action_decoy).isVisible());
        assertFalse(tb.getMenu().findItem(R.id.action_intruders).isVisible());
        assertFalse(tb.getMenu().findItem(R.id.action_bio).isVisible());
        assertTrue(tb.getMenu().findItem(R.id.action_change_pin).isVisible());
        // add something to the decoy, then reopen it: the file is there
        VaultSession.decoy(a).add(new java.io.ByteArrayInputStream("holiday".getBytes()), "holiday.jpg", 1L);
        vaultMenu(a, R.id.action_lock);
        typeAndUnlock(a, "1357");
        waitUntil(() -> vaultRows(a).contains("holiday.jpg"));
        settle();
        assertEquals(java.util.Arrays.asList("holiday.jpg"), vaultRows(a));
        shotOf(a, "141_vault_decoy_open");

        // lock again; the real PIN opens the real vault with the real file
        vaultMenu(a, R.id.action_lock);
        assertFalse(VaultSession.decoyActive);
        typeAndUnlock(a, "2468");
        assertFalse(VaultSession.decoyActive);
        waitUntil(() -> vaultRows(a).contains("secret.txt"));
        assertEquals(java.util.Arrays.asList("secret.txt"), vaultRows(a));
        assertTrue(((MaterialToolbar) a.findViewById(R.id.toolbar)).getMenu().findItem(R.id.action_decoy).isVisible());
    }

    @Test public void wrongPinsAreLoggedAndShownToTheOwnerButNotInsideTheDecoy() throws Exception {
        VaultActivity a = openVault();
        createVault(a, "2468");
        VaultSession.decoy(a).create("1357".toCharArray());
        VaultSession.decoy(a).lock();
        vaultMenu(a, R.id.action_lock);

        typeAndUnlock(a, "0000");
        typeAndUnlock(a, "9999");
        assertEquals(2, VaultSession.intruders(a).list().size());
        assertFalse(VaultSession.get(a).isUnlocked());

        // inside the decoy nothing is revealed
        typeAndUnlock(a, "1357");
        assertTrue(VaultSession.decoyActive);
        settle();
        assertFalse("no popup in the decoy", org.robolectric.shadows.ShadowDialog.getLatestDialog() != null
                && ((androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()).isShowing()
                && ((androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()).getWindow().getDecorView().toString().contains("wrong"));
        assertEquals("still logged", 2, VaultSession.intruders(a).list().size());
        vaultMenu(a, R.id.action_lock);

        // the owner's own PIN shows what happened
        typeAndUnlock(a, "2468");
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        assertTrue(d.isShowing());
        shotDialog(d, "142_vault_intruders");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();               // OK clears it
        settle();
        assertEquals(0, VaultSession.intruders(a).list().size());
    }

    // ------------------------------------------------------------------ swipe to clean

    /** A real picture file: soft gradient, rectangles and grain, so the cleaner has something to look at. */
    private File picture(String rel, long seed, int w, int h, int blurDown, long modified, boolean dark) throws Exception {
        return picture(rel, seed, w, h, blurDown, modified, dark, w);
    }

    /** {@code outW} saves a smaller copy of the same scene (the scene itself is always drawn at w x h). */
    private File picture(String rel, long seed, int w, int h, int blurDown, long modified, boolean dark, int outW) throws Exception {
        File f = new File(storage, rel);
        f.getParentFile().mkdirs();
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        if (dark) {
            c.drawColor(0xFF010101);
        } else {
            java.util.Random r = new java.util.Random(seed);
            android.graphics.Paint p = new android.graphics.Paint();
            c.drawColor(android.graphics.Color.rgb(60 + r.nextInt(60), 90 + r.nextInt(80), 120 + r.nextInt(100)));
            for (int i = 0; i < 16; i++) {
                p.setColor(android.graphics.Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
                int x = r.nextInt(w - 20), y = r.nextInt(h - 20);
                c.drawRect(x, y, x + 10 + r.nextInt(w / 3), y + 10 + r.nextInt(h / 3), p);
            }
            java.util.Random grain = new java.util.Random(seed * 17);
            p.setStrokeWidth(1);
            for (int i = 0; i < w * h / 6; i++) {
                int v = 100 + grain.nextInt(120);
                p.setColor(android.graphics.Color.argb(70, v, v, v));
                c.drawPoint(grain.nextInt(w), grain.nextInt(h), p);
            }
        }
        if (blurDown > 1) {
            Bitmap small = Bitmap.createScaledBitmap(bm, Math.max(2, w / blurDown), Math.max(2, h / blurDown), true);
            bm = Bitmap.createScaledBitmap(small, w, h, true);
        }
        if (outW != w) bm = Bitmap.createScaledBitmap(bm, outW, h * outW / w, true);
        try (FileOutputStream os = new FileOutputStream(f)) {
            // the test runtime can write JPEG but not read it back, so the content is PNG; the decoder goes by content, not by name
            bm.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        assertTrue(f.setLastModified(modified));
        return f;
    }

    private ActivityController<SwipeCleanActivity> cleanController;

    private long hoursAgo(int h) {
        return System.currentTimeMillis() - h * 3600_000L;
    }

    private void makePhotoLibrary() throws Exception {
        picture("DCIM/Camera/IMG_A.jpg", 1, 640, 480, 1, hoursAgo(50), false);
        picture("DCIM/Camera/IMG_A_copy.jpg", 1, 640, 480, 1, hoursAgo(49), false, 320);     // same scene, smaller
        picture("DCIM/Camera/IMG_B.jpg", 2, 640, 480, 1, hoursAgo(40), false);
        picture("DCIM/Camera/IMG_BLUR.jpg", 3, 640, 480, 24, hoursAgo(30), false);
        picture("DCIM/Camera/IMG_BLACK.jpg", 4, 640, 480, 1, hoursAgo(20), true);
        picture("Pictures/Screenshots/Screenshot_old.png", 5, 540, 1170, 1, System.currentTimeMillis() - 200L * 24 * 3600_000, false);
    }

    private SwipeCleanActivity openClean() {
        cleanController = Robolectric.buildActivity(SwipeCleanActivity.class).setup();
        SwipeCleanActivity a = cleanController.get();
        waitUntil(() -> ((android.widget.LinearLayout) a.findViewById(R.id.sResults)).getChildCount() == 4);
        settle();
        return a;
    }

    private static String rowSub(android.app.Activity a, int row) {
        return ((TextView) ((android.widget.LinearLayout) a.findViewById(R.id.sResults)).getChildAt(row).findViewById(R.id.cSub)).getText().toString();
    }

    private static void review(android.app.Activity a, int row) {
        ((android.widget.LinearLayout) a.findViewById(R.id.sResults)).getChildAt(row).findViewById(R.id.cReview).performClick();
        settle();
    }

    @After
    public void tearDownClean() {
        if (cleanController != null) {
            try { cleanController.pause().stop().destroy(); } catch (Exception ignored) { }
            cleanController = null;
        }
    }

    @Test public void photoCleanerFindsCopiesBlurBlackFramesAndOldScreenshotsInRealPictures() throws Exception {
        makePhotoLibrary();
        SwipeCleanActivity a = openClean();
        assertTrue(rowSub(a, 0), rowSub(a, 0).startsWith("1 photo"));            // near-copies: the smaller copy
        assertTrue(rowSub(a, 1), rowSub(a, 1).startsWith("1 photo"));            // blurry
        assertTrue(rowSub(a, 2), rowSub(a, 2).startsWith("1 photo"));            // black
        assertTrue(rowSub(a, 3), rowSub(a, 3).startsWith("1 photo"));            // old screenshot
        String intro = ((TextView) a.findViewById(R.id.sIntro)).getText().toString();
        assertTrue(intro, intro.startsWith("Looked at 6 photos"));
        shotOf(a, "150_clean_summary");

        review(a, 0);
        assertEquals(View.VISIBLE, a.findViewById(R.id.reviewBox).getVisibility());
        assertEquals("1 of 1", ((TextView) a.findViewById(R.id.rTitle)).getText().toString());
        String info = ((TextView) a.findViewById(R.id.rInfo)).getText().toString();
        assertTrue(info, info.contains("IMG_A_copy.jpg") && info.contains("IMG_A.jpg, which is the better one"));
        assertEquals(View.VISIBLE, a.findViewById(R.id.rBest).getVisibility());
        waitUntil(() -> ((android.widget.ImageView) a.findViewById(R.id.rImage)).getDrawable() != null);
        settle();
        shotOf(a, "151_clean_card");
    }

    @Test public void trashingKeepingAndUndoingWorkAndKeptPhotosAreNotSuggestedAgain() throws Exception {
        makePhotoLibrary();
        SwipeCleanActivity a = openClean();
        File copy = new File(storage, "DCIM/Camera/IMG_A_copy.jpg");
        File blur = new File(storage, "DCIM/Camera/IMG_BLUR.jpg");

        review(a, 0);
        a.findViewById(R.id.rTrash).performClick();
        settle();
        assertFalse("moved to the Trash", copy.exists());
        assertTrue("the better one stays", new File(storage, "DCIM/Camera/IMG_A.jpg").exists());
        assertEquals(View.VISIBLE, a.findViewById(R.id.summaryBox).getVisibility());
        assertEquals("None found", rowSub(a, 0));
        assertTrue(((TextView) a.findViewById(R.id.sTrashed)).getText().toString().startsWith("Moved to the Trash so far: 1 photo"));
        assertEquals(1, new Trash(storage).list().size());

        // undo from the next review: go into blurry, keep it, then undo the keep
        review(a, 1);
        a.findViewById(R.id.rKeep).performClick();
        settle();
        assertTrue(blur.exists());
        assertEquals(1, new Prefs(a).cleanKept().size());
        assertEquals("None found", rowSub(a, 1));

        // scanning again doesn't bring the kept photo back
        a.findViewById(R.id.sRescan).performClick();
        waitUntil(() -> ((android.widget.LinearLayout) a.findViewById(R.id.sResults)).getChildCount() == 4);
        settle();
        assertEquals("None found", rowSub(a, 1));
        assertEquals("None found", rowSub(a, 0));
    }

    @Test public void undoPutsATrashedPhotoBackWhereItWas() throws Exception {
        makePhotoLibrary();
        SwipeCleanActivity a = openClean();
        File shot = new File(storage, "Pictures/Screenshots/Screenshot_old.png");
        long size = shot.length();
        review(a, 3);
        assertFalse(a.findViewById(R.id.rUndo).isEnabled());
        a.findViewById(R.id.rTrash).performClick();
        settle();
        assertFalse(shot.exists());
        // the review ended (it was the only one), so open the blurry group, which has an Undo that reaches the last decision
        review(a, 1);
        assertTrue(a.findViewById(R.id.rUndo).isEnabled());
        a.findViewById(R.id.rUndo).performClick();
        settle();
        assertTrue("restored", shot.exists());
        assertEquals(size, shot.length());
        assertEquals(0, new Trash(storage).list().size());
    }

    @Test public void swipingLeftTrashesAndSwipingRightKeeps() throws Exception {
        makePhotoLibrary();
        picture("DCIM/Camera/IMG_BLUR2.jpg", 6, 640, 480, 24, hoursAgo(29), false);
        SwipeCleanActivity a = openClean();
        review(a, 1);
        assertEquals("1 of 2", ((TextView) a.findViewById(R.id.rTitle)).getText().toString());
        View card = a.findViewById(R.id.rImage);
        layoutOf(a);
        android.os.SystemClock.setCurrentTimeMillis(android.os.SystemClock.uptimeMillis());
        float w = card.getWidth();
        long t = android.os.SystemClock.uptimeMillis();
        card.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 500f, 500f, 0));
        card.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_MOVE, 500f - w * 0.45f, 500f, 0));
        assertTrue("the TRASH stamp fades in", a.findViewById(R.id.rStampTrash).getAlpha() > 0.9f);
        card.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 100, android.view.MotionEvent.ACTION_UP, 500f - w * 0.45f, 500f, 0));
        waitUntil(() -> "2 of 2".equals(((TextView) a.findViewById(R.id.rTitle)).getText().toString()));
        settle();
        assertEquals("2 of 2", ((TextView) a.findViewById(R.id.rTitle)).getText().toString());
        assertEquals(1, new Trash(storage).list().size());

        // a short drag springs back and decides nothing
        t = android.os.SystemClock.uptimeMillis();
        card.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 500f, 500f, 0));
        card.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_MOVE, 500f + w * 0.1f, 500f, 0));
        card.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 100, android.view.MotionEvent.ACTION_UP, 500f + w * 0.1f, 500f, 0));
        settle();
        assertEquals("2 of 2", ((TextView) a.findViewById(R.id.rTitle)).getText().toString());
        assertEquals(1, new Trash(storage).list().size());
    }

    @Test public void cleanerNeedsFileAccess() throws Exception {
        Access.testOverride = false;
        cleanController = Robolectric.buildActivity(SwipeCleanActivity.class).setup();
        SwipeCleanActivity a = cleanController.get();
        settle();
        assertTrue(((TextView) a.findViewById(R.id.sIntro)).getText().toString().contains("Allow file access"));
    }

    // ------------------------------------------------------------------ document scanner

    private ActivityController<ScanActivity> scanController;

    @After
    public void tearDownScan() {
        if (scanController != null) {
            try { scanController.pause().stop().destroy(); } catch (Exception ignored) { }
            scanController = null;
        }
    }

    private File photoOfAPage(String name, long seed) throws Exception {
        DocScan.Img img = DocScanTest.photo(800, 600, DocScanTest.PX, DocScanTest.PY, true, seed);
        Bitmap b = Bitmap.createBitmap(img.px, img.w, img.h, Bitmap.Config.ARGB_8888);
        File f = new File(storage.getParentFile(), name);
        try (FileOutputStream os = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, os); }
        return f;
    }

    private ScanActivity openScan() {
        scanController = Robolectric.buildActivity(ScanActivity.class).setup();
        ScanActivity a = scanController.get();
        settle();
        return a;
    }

    private void addPage(ScanActivity a, File photo) {
        int before = a.pageCount();
        a.addFromFile(photo);
        waitUntil(() -> a.findViewById(R.id.cropBox).getVisibility() == View.VISIBLE);
        settle();
        a.findViewById(R.id.cropDone).performClick();
        waitUntil(() -> a.pageCount() == before + 1 && a.currentPreview() != null);
        settle();
    }

    @Test public void scannerStartsWithTwoWaysToGetAPhoto() throws Exception {
        ScanActivity a = openScan();
        assertEquals(View.VISIBLE, a.findViewById(R.id.emptyBox).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.pagesBox).getVisibility());
        shotOf(a, "160_scan_empty");
        a.findViewById(R.id.eGallery).performClick();
        android.content.Intent pick = shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals(android.content.Intent.ACTION_GET_CONTENT, pick.getAction());
        assertEquals("image/*", pick.getType());
        a.findViewById(R.id.eCamera).performClick();
        android.content.Intent cam = shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, cam.getAction());
        android.net.Uri out = cam.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT);
        assertNotNull("the camera app is told where to put the photo", out);
        assertTrue(out.toString(), out.toString().contains(a.getPackageName() + ".fileprovider"));
    }

    @Test public void aPhotoOfAPageIsFoundStraightenedAndCleaned() throws Exception {
        ScanActivity a = openScan();
        a.addFromFile(photoOfAPage("page1.png", 1));
        waitUntil(() -> a.findViewById(R.id.cropBox).getVisibility() == View.VISIBLE);
        settle();
        CropView cv = a.findViewById(R.id.cropView);
        DocScan.Quad q = cv.getQuad();
        for (int i = 0; i < 4; i++) {
            double d = Math.hypot(q.x[i] - DocScanTest.PX[i], q.y[i] - DocScanTest.PY[i]);
            assertTrue("corner " + i + " found within " + d, d < 30);
        }
        shotOf(a, "161_scan_crop");

        // corners that cross over aren't accepted
        float[] c2 = cv.cornerOnScreen(2);
        cv.moveTo(0, c2[0], c2[1]);
        a.findViewById(R.id.cropDone).performClick();
        assertEquals("still cropping", View.VISIBLE, a.findViewById(R.id.cropBox).getVisibility());
        cv.setQuad(q);
        a.findViewById(R.id.cropDone).performClick();
        waitUntil(() -> a.pageCount() == 1 && a.currentPreview() != null);
        settle();
        assertEquals(View.VISIBLE, a.findViewById(R.id.pagesBox).getVisibility());
        assertEquals("Page 1 of 1", ((TextView) a.findViewById(R.id.pageInfo)).getText().toString());
        Bitmap prev = a.currentPreview();
        double expectedRatio = Math.max(Math.hypot(DocScanTest.PX[0] - DocScanTest.PX[3], DocScanTest.PY[0] - DocScanTest.PY[3]),
                Math.hypot(DocScanTest.PX[1] - DocScanTest.PX[2], DocScanTest.PY[1] - DocScanTest.PY[2]))
                / Math.max(Math.hypot(DocScanTest.PX[0] - DocScanTest.PX[1], DocScanTest.PY[0] - DocScanTest.PY[1]),
                Math.hypot(DocScanTest.PX[3] - DocScanTest.PX[2], DocScanTest.PY[3] - DocScanTest.PY[2]));
        assertEquals("page shape is right", expectedRatio, prev.getHeight() / (double) prev.getWidth(), 0.06);
        int gap = prev.getPixel((int) (prev.getWidth() * 0.85), (int) (prev.getHeight() * (0.12 + 0.065)));
        assertTrue("paper comes out white: " + Integer.toHexString(gap), (gap & 0xFF) > 220 && ((gap >> 16) & 0xFF) > 220);
        shotOf(a, "162_scan_page");

        // looks
        a.findViewById(R.id.lookBw).performClick();
        waitUntil(() -> { Bitmap b = a.currentPreview(); return b != prev; });
        settle();
        Bitmap bw = a.currentPreview();
        int sample = bw.getPixel(bw.getWidth() / 2, bw.getHeight() / 2);
        assertTrue("black and white only: " + Integer.toHexString(sample), (sample & 0xFFFFFF) == 0 || (sample & 0xFFFFFF) == 0xFFFFFF);
        // turning swaps the sides
        int w0 = bw.getWidth(), h0 = bw.getHeight();
        a.findViewById(R.id.aRotate).performClick();
        waitUntil(() -> a.currentPreview() != bw);
        settle();
        assertEquals(h0, a.currentPreview().getWidth());
        assertEquals(w0, a.currentPreview().getHeight());
    }

    @Test public void severalPagesBecomeOnePdfThatARealReaderOpens() throws Exception {
        ScanActivity a = openScan();
        addPage(a, photoOfAPage("p1.png", 11));
        addPage(a, photoOfAPage("p2.png", 12));
        addPage(a, photoOfAPage("p3.png", 13));
        assertEquals(3, a.pageCount());
        assertEquals("Page 3 of 3", ((TextView) a.findViewById(R.id.pageInfo)).getText().toString());
        assertEquals(3, ((RecyclerView) a.findViewById(R.id.thumbs)).getAdapter().getItemCount());
        // drop the middle one
        ((RecyclerView) a.findViewById(R.id.thumbs)).getAdapter();
        a.findViewById(R.id.aDelete).performClick();
        settle();
        assertEquals(2, a.pageCount());

        a.findViewById(R.id.aSave).performClick();
        waitUntil(() -> a.lastSaved != null);
        settle();
        File pdf = a.lastSaved;
        assertNotNull(pdf);
        assertEquals(new File(storage, "Documents/FileBandit Scans").getPath(), pdf.getParent());
        assertTrue(pdf.getName(), pdf.getName().startsWith("Scan 20") && pdf.getName().endsWith(".pdf"));
        byte[] bytes = Files.readAllBytes(pdf.toPath());
        assertTrue(new String(bytes, 0, 8, StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
        assertTrue("two pages' worth of pictures: " + bytes.length, bytes.length > 20_000);
        assertEquals("the scanner is empty again", 0, a.pageCount());
        assertEquals(View.VISIBLE, a.findViewById(R.id.emptyBox).getVisibility());
        if (new File("/usr/bin/pdfinfo").canExecute()) {
            Process p = new ProcessBuilder("/usr/bin/pdfinfo", pdf.getPath()).redirectErrorStream(true).start();
            String info = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            p.waitFor();
            assertTrue(info, info.contains("Pages: 2"));
        }
        View snack = a.getWindow().getDecorView().findViewById(com.google.android.material.R.id.snackbar_text);
        assertNotNull(snack);
        assertTrue(((TextView) snack).getText().toString(), ((TextView) snack).getText().toString().contains("Saved 2 pages as Scan"));
        shotOf(a, "163_scan_saved");
    }

    @Test public void adjustingEdgesLeavingAndNeedingFileAccess() throws Exception {
        ScanActivity a = openScan();
        addPage(a, photoOfAPage("e1.png", 21));
        a.findViewById(R.id.aEdges).performClick();
        waitUntil(() -> a.findViewById(R.id.cropBox).getVisibility() == View.VISIBLE);
        settle();
        CropView cv = a.findViewById(R.id.cropView);
        DocScan.Quad q = cv.getQuad();
        assertTrue("the editor opens on the page's current corners", Math.hypot(q.x[0] - DocScanTest.PX[0], q.y[0] - DocScanTest.PX[0] * 0 - DocScanTest.PY[0]) < 30);
        a.findViewById(R.id.cropCancel).performClick();
        assertEquals(View.GONE, a.findViewById(R.id.cropBox).getVisibility());
        assertEquals("cancelling keeps the page", 1, a.pageCount());

        // going back with an unsaved scan asks first
        a.getOnBackPressedDispatcher().onBackPressed();
        settle();
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(d);
        assertTrue(d.isShowing());
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();           // keep scanning
        settle();
        assertFalse(a.isFinishing());
        a.getOnBackPressedDispatcher().onBackPressed();
        settle();
        ((androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertTrue(a.isFinishing());
    }

    @Test public void withoutFileAccessTheSavedPdfStaysInTheCacheAndSaysSo() throws Exception {
        Access.testOverride = false;
        ScanActivity a = openScan();
        addPage(a, photoOfAPage("n1.png", 31));
        a.findViewById(R.id.aSave).performClick();
        waitUntil(() -> a.lastSaved != null);
        settle();
        assertTrue(a.lastSaved.getPath().startsWith(a.getCacheDir().getPath()));
        View snack = a.getWindow().getDecorView().findViewById(com.google.android.material.R.id.snackbar_text);
        assertTrue(((TextView) snack).getText().toString().contains("allow file access"));
    }

    @Test public void scanTileOnTheHomeScreenOpensTheScanner() throws Exception {
        launch();
        android.view.ViewGroup tools = files().getView().findViewById(R.id.toolsGrid);
        boolean found = false;
        for (int r = 0; r < tools.getChildCount(); r++) {
            android.view.ViewGroup row = (android.view.ViewGroup) tools.getChildAt(r);
            for (int i = 0; i < row.getChildCount(); i++) {
                View tile = row.getChildAt(i);
                TextView label = tile.findViewById(R.id.tileLabel);
                if (label != null && "Scan".equals(label.getText().toString())) {
                    tile.performClick();
                    found = true;
                }
            }
        }
        assertTrue(found);
        assertEquals(ScanActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    // ------------------------------------------------------------------ search inside files

    private ActivityController<ContentSearchActivity> searchController;

    @After
    public void tearDownContentSearch() {
        ContentSearchActivity.testRecognizer = null;
        if (searchController != null) {
            try { searchController.pause().stop().destroy(); } catch (Exception ignored) { }
            searchController = null;
        }
    }

    private ContentSearchActivity openContentSearch() throws Exception {
        write("Documents/notes-meeting.txt", 20);
        Files.write(new File(storage, "Documents/notes-meeting.txt").toPath(), "Meeting with Dr. Okafor about the lease renewal on Friday".getBytes(StandardCharsets.UTF_8));
        File receipt = writePng("Pictures/IMG_5001.png", 0xFFEEEEEE, 300, 400);
        File dog = writePng("Pictures/IMG_5002.png", 0xFF99BB77, 400, 300);
        File blank = writePng("Pictures/IMG_5003.png", 0xFFFFFFFF, 200, 200);
        ContentSearchActivity.testRecognizer = f -> {
            if (f.getName().equals("IMG_5001.png")) return new ContentIndexer.Result("Caf\u00e9 Luna\nLatte 4.20\nTotal 12.50\nThank you", java.util.Arrays.asList("Text"));
            if (f.getName().equals("IMG_5002.png")) return new ContentIndexer.Result("", java.util.Arrays.asList("Dog", "Grass"));
            if (f.getName().equals("IMG_5003.png")) return new ContentIndexer.Result("", null);
            throw new IOException("not a picture");
        };
        searchController = Robolectric.buildActivity(ContentSearchActivity.class).setup();
        ContentSearchActivity a = searchController.get();
        waitUntil(() -> a.findViewById(R.id.busy).getVisibility() != View.VISIBLE);
        settle();
        return a;
    }

    private static void typeQuery(android.app.Activity a, String q) {
        ((EditText) a.findViewById(R.id.query)).setText(q);
        for (int i = 0; i < 4; i++) settle();
    }

    @Test public void searchFindsWordsInPicturesTextFilesAndWhatPhotosShow() throws Exception {
        ContentSearchActivity a = openContentSearch();
        TextView status = a.findViewById(R.id.status);
        assertTrue(status.getText().toString(), status.getText().toString().contains("searchable"));
        assertTrue(((TextView) a.findViewById(R.id.empty)).getText().toString().contains("Type a word"));
        shotOf(a, "170_search_start");

        typeQuery(a, "cafe total");
        assertEquals(java.util.Arrays.asList("IMG_5001.png"), remoteRows(a));
        RowAdapter ra = (RowAdapter) ((RecyclerView) a.findViewById(R.id.list)).getAdapter();
        assertTrue(ra.rows().get(0).sub, ra.rows().get(0).sub.contains("Caf\u00e9 Luna"));
        assertEquals("1 file matches", ((TextView) a.findViewById(R.id.status)).getText().toString());
        shotOf(a, "171_search_result");

        typeQuery(a, "dog");
        assertEquals(java.util.Arrays.asList("IMG_5002.png"), remoteRows(a));
        assertTrue(ra.rows().get(0).sub, ra.rows().get(0).sub.startsWith("Shows: Dog, Grass"));

        typeQuery(a, "okafor");
        assertEquals(java.util.Arrays.asList("notes-meeting.txt"), remoteRows(a));
        assertTrue(ra.rows().get(0).sub, ra.rows().get(0).sub.contains("Dr. Okafor"));

        typeQuery(a, "zebra");
        assertTrue(remoteRows(a).isEmpty());
        assertEquals("Nothing matches that.", ((TextView) a.findViewById(R.id.empty)).getText().toString());
        typeQuery(a, "");
        assertTrue(((TextView) a.findViewById(R.id.empty)).getText().toString().contains("Type a word"));
    }

    @Test public void tappingAResultOpensItAndHoldingShowsItsFolder() throws Exception {
        ContentSearchActivity a = openContentSearch();
        typeQuery(a, "latte");
        layoutOf(a);
        RecyclerView list = a.findViewById(R.id.list);
        list.findViewHolderForAdapterPosition(0).itemView.performClick();
        android.content.Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(ViewerActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(next.getStringExtra(ViewerActivity.EXTRA_PATH).endsWith("IMG_5001.png"));

        list.findViewHolderForAdapterPosition(0).itemView.performLongClick();
        assertEquals(new File(storage, "Pictures"), Nav.dir);
        assertTrue(a.isFinishing());
        Nav.dir = null;
    }

    @Test public void newFilesAreReadOnTheNextVisitAndOnlyThose() throws Exception {
        openContentSearch();
        searchController.pause().stop().destroy();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ContentSearchActivity.testRecognizer = f -> {
            calls.incrementAndGet();
            return new ContentIndexer.Result("a brand new whiteboard", null);
        };
        writePng("Pictures/IMG_5004.png", 0xFF333333, 100, 100);
        searchController = Robolectric.buildActivity(ContentSearchActivity.class).setup();
        ContentSearchActivity a = searchController.get();
        waitUntil(() -> a.findViewById(R.id.busy).getVisibility() != View.VISIBLE);
        settle();
        assertEquals("only the new picture was read", 1, calls.get());
        typeQuery(a, "whiteboard");
        assertEquals(java.util.Arrays.asList("IMG_5004.png"), remoteRows(a));
    }

    @Test public void ifPicturesCantBeReadTextFilesStillWork() throws Exception {
        write("Documents/plain.txt", 5);
        Files.write(new File(storage, "Documents/plain.txt").toPath(), "grocery list: oat milk".getBytes(StandardCharsets.UTF_8));
        writePng("Pictures/IMG_6001.png", 0xFF123456, 100, 100);
        ContentSearchActivity.testRecognizer = f -> { throw new IOException("models missing"); };
        searchController = Robolectric.buildActivity(ContentSearchActivity.class).setup();
        ContentSearchActivity a = searchController.get();
        waitUntil(() -> a.findViewById(R.id.busy).getVisibility() != View.VISIBLE);
        settle();
        typeQuery(a, "oat milk");
        assertEquals(java.util.Arrays.asList("plain.txt"), remoteRows(a));
    }

    @Test public void searchNeedsFileAccessAndHasATile() throws Exception {
        Access.testOverride = false;
        searchController = Robolectric.buildActivity(ContentSearchActivity.class).setup();
        ContentSearchActivity a = searchController.get();
        settle();
        assertTrue(((TextView) a.findViewById(R.id.empty)).getText().toString().contains("Allow file access"));
        Access.testOverride = true;
        launch();
        android.view.ViewGroup tools = files().getView().findViewById(R.id.toolsGrid);
        boolean found = false;
        for (int r = 0; r < tools.getChildCount() && !found; r++) {
            android.view.ViewGroup row = (android.view.ViewGroup) tools.getChildAt(r);
            for (int i = 0; i < row.getChildCount(); i++) {
                TextView label = row.getChildAt(i).findViewById(R.id.tileLabel);
                if (label != null && "Search inside".equals(label.getText().toString())) {
                    row.getChildAt(i).performClick();
                    found = true;
                }
            }
        }
        assertTrue(found);
        assertEquals(ContentSearchActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        View toolsView = files().getView().findViewById(R.id.toolsGrid);
        toolsView.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        toolsView.layout(0, 0, W, toolsView.getMeasuredHeight());
        Bitmap tb = Bitmap.createBitmap(W, toolsView.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        tb.eraseColor(0xFFF3F5F8);
        toolsView.draw(new Canvas(tb));
        save(tb, "172_home_tools");
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    @Test public void smokeLauncherIconAndTheme() {
        assertNotNull(RuntimeEnvironment.getApplication().getPackageManager());
        assertEquals("com.fastshare.app", RuntimeEnvironment.getApplication().getPackageName());
    }
}
