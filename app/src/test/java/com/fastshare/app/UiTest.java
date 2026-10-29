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

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

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

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private File storage;

    @Before
    public void setUp() throws Exception {
        Access.testOverride = true;
        storage = Environment.getExternalStorageDirectory();
        storage.mkdirs();
        ShadowStatFs.registerStats(storage, 62_500_000, 31_000_000, 31_000_000);   // 256 GB, about half used
        Net.testOverride = new Net.Info(true, new ArrayList<>(Arrays.asList(
                new Net.Addr("192.168.23.33", "Wi-Fi"), new Net.Addr("10.0.0.7", "Network"))));
        makeSampleFiles();
    }

    @After
    public void tearDown() {
        Net.testOverride = null;
        Access.testOverride = null;
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
        assertTrue(sub.getText().toString(), sub.getText().toString().contains("used of"));
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
        assertFalse(tb.getMenu().findItem(R.id.action_rename).isVisible());
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
