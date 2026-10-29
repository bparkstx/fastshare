package com.fastshare.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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

/** The private vault: create a PIN, unlock, and keep files encrypted where no other app can read them. */
public class VaultActivity extends AppCompatActivity implements RowAdapter.Callbacks {

    private static final long AUTO_LOCK_MS = 30_000;

    private enum State { SETUP, LOCKED, OPEN }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Vault.Item> items = new LinkedHashMap<>();

    private Vault vault;
    private Vault real;
    private Vault decoyVault;
    private boolean shownIntruders;
    private State state = State.SETUP;
    private long stoppedAt;
    private boolean askedBiometric;

    private MaterialToolbar toolbar;
    private View busy;
    private View authScroll;
    private View listBox;
    private View empty;
    private TextView authTitle;
    private TextView authBody;
    private TextView authError;
    private EditText pinInput;
    private EditText pinConfirm;
    private CheckBox useLetters;
    private MaterialButton authButton;
    private MaterialButton bioButton;
    private RowAdapter adapter;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::addFromPicker);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // no screenshots, and the app switcher shows a blank card
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_vault);
        real = VaultSession.real(this);
        decoyVault = VaultSession.decoy(this);
        vault = VaultSession.get(this);

        toolbar = findViewById(R.id.toolbar);
        busy = findViewById(R.id.busy);
        authScroll = findViewById(R.id.authScroll);
        listBox = findViewById(R.id.listBox);
        empty = findViewById(R.id.empty);
        authTitle = findViewById(R.id.authTitle);
        authBody = findViewById(R.id.authBody);
        authError = findViewById(R.id.authError);
        pinInput = findViewById(R.id.pinInput);
        pinConfirm = findViewById(R.id.pinConfirm);
        useLetters = findViewById(R.id.useLetters);
        authButton = findViewById(R.id.authButton);
        bioButton = findViewById(R.id.bioButton);
        RecyclerView list = findViewById(R.id.list);
        adapter = new RowAdapter(this, selected, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        useLetters.setOnCheckedChangeListener((v, checked) -> {
            int type = checked ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            pinInput.setInputType(type);
            pinConfirm.setInputType(type);
        });
        authButton.setOnClickListener(x -> onAuthButton());
        bioButton.setOnClickListener(x -> tryBiometric());
        findViewById(R.id.fab).setOnClickListener(x -> picker.launch(new String[]{"*/*"}));
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
        refreshState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (stoppedAt > 0 && (real.isUnlocked() || decoyVault.isUnlocked()) && System.currentTimeMillis() - stoppedAt > AUTO_LOCK_MS) lockNow();
        stoppedAt = 0;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) stoppedAt = System.currentTimeMillis();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (isFinishing()) {
            real.lock();
            decoyVault.lock();
            VaultSession.decoyActive = false;
            VaultSession.wipeViewCache(this);
        }
    }

    // ------------------------------------------------------------------ states

    private void refreshState() {
        if (!vault.exists()) state = State.SETUP;
        else if (!vault.isUnlocked()) state = State.LOCKED;
        else state = State.OPEN;
        render();
    }

    private void render() {
        authError.setVisibility(View.GONE);
        pinInput.setText("");
        pinConfirm.setText("");
        selected.clear();
        boolean open = state == State.OPEN;
        authScroll.setVisibility(open ? View.GONE : View.VISIBLE);
        listBox.setVisibility(open ? View.VISIBLE : View.GONE);
        bioButton.setVisibility(View.GONE);
        if (state == State.SETUP) {
            authTitle.setText("Create your private vault");
            authBody.setText("Files you put here are encrypted. Other apps, and anyone who gets hold of this phone, "
                    + "can't open them without your PIN.\n\nThere is no “forgot PIN”. If you forget it, the files "
                    + "cannot be recovered by anyone, including us. Choose something you'll remember.");
            pinConfirm.setVisibility(View.VISIBLE);
            authButton.setText("Create vault");
            pinInput.setHint("Choose a PIN (4 or more)");
        } else if (state == State.LOCKED) {
            authTitle.setText("Vault locked");
            authBody.setText("Enter your PIN to open it.");
            pinConfirm.setVisibility(View.GONE);
            authButton.setText("Unlock");
            pinInput.setHint("PIN or password");
            boolean bio = vault.hasBiometric() && VaultBiometric.available(this);
            bioButton.setVisibility(bio ? View.VISIBLE : View.GONE);
            if (bio && !askedBiometric) {
                askedBiometric = true;
                ui.post(this::tryBiometric);
            }
            showLockoutIfAny();
        } else {
            loadItems();
            maybeShowIntruders();
        }
        updateToolbar();
    }

    private void updateToolbar() {
        Menu m = toolbar.getMenu();
        m.clear();
        if (state != State.OPEN) {
            toolbar.setTitle("Private vault");
            toolbar.setNavigationIcon(R.drawable.ic_back);
            return;
        }
        if (!selected.isEmpty()) {
            toolbar.inflateMenu(R.menu.vault_selection);
            toolbar.setTitle(String.valueOf(selected.size()));
            toolbar.setNavigationIcon(R.drawable.ic_close);
        } else {
            toolbar.inflateMenu(R.menu.vault_main);
            boolean decoy = VaultSession.decoyActive;
            // inside the decoy there is nothing that hints a real vault exists
            m.findItem(R.id.action_bio).setVisible(!decoy && VaultBiometric.available(this));
            m.findItem(R.id.action_decoy).setVisible(!decoy);
            m.findItem(R.id.action_intruders).setVisible(!decoy);
            m.findItem(R.id.action_bio).setTitle(vault.hasBiometric() ? "Turn off fingerprint unlock" : "Turn on fingerprint unlock");
            toolbar.setTitle("Private vault");
            toolbar.setNavigationIcon(R.drawable.ic_back);
        }
    }

    private void showError(String message) {
        authError.setText(message);
        authError.setVisibility(View.VISIBLE);
    }

    private void showLockoutIfAny() {
        long left = vault.lockoutMillisLeft();
        if (left > 0) {
            showError("Too many wrong tries. Wait " + (left / 1000 + 1) + " seconds.");
            authButton.setEnabled(false);
            ui.postDelayed(() -> {
                if (isDestroyed() || state != State.LOCKED) return;
                if (vault.lockoutMillisLeft() > 0) {
                    showLockoutIfAny();
                } else {
                    authButton.setEnabled(true);
                    authError.setVisibility(View.GONE);
                }
            }, 1000);
        } else {
            authButton.setEnabled(true);
        }
    }

    // ------------------------------------------------------------------ set up / unlock

    private void onAuthButton() {
        final char[] pin = pinInput.getText().toString().toCharArray();
        if (state == State.SETUP) {
            if (pin.length < Vault.MIN_PIN_LENGTH) {
                showError("Use at least " + Vault.MIN_PIN_LENGTH + " characters.");
                return;
            }
            if (!pinInput.getText().toString().equals(pinConfirm.getText().toString())) {
                showError("The two entries don't match.");
                return;
            }
            busy.setVisibility(View.VISIBLE);
            authButton.setEnabled(false);
            io.execute(() -> {
                String err = null;
                try {
                    vault.create(pin);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String error = err;
                ui.post(() -> {
                    if (isDestroyed()) return;
                    busy.setVisibility(View.GONE);
                    authButton.setEnabled(true);
                    if (error != null) showError(error);
                    else refreshState();
                });
            });
        } else {
            if (pin.length == 0) return;
            busy.setVisibility(View.VISIBLE);
            authButton.setEnabled(false);
            io.execute(() -> {
                boolean ok = false;
                String err = null;
                try {
                    VaultGate.Result res = VaultGate.unlock(real, decoyVault, pin);
                    if (res == VaultGate.Result.DECOY) {
                        VaultSession.decoyActive = true;
                        vault = decoyVault;
                        ok = true;
                    } else if (res == VaultGate.Result.REAL) {
                        VaultSession.decoyActive = false;
                        vault = real;
                        ok = true;
                    } else {
                        VaultSession.intruders(this).record(System.currentTimeMillis());
                    }
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final boolean opened = ok;
                final String error = err;
                ui.post(() -> {
                    if (isDestroyed()) return;
                    busy.setVisibility(View.GONE);
                    authButton.setEnabled(true);
                    if (opened) {
                        refreshState();
                    } else {
                        pinInput.setText("");
                        showError(error != null ? error : "That PIN isn't right.");
                        showLockoutIfAny();
                    }
                });
            });
        }
    }

    private void tryBiometric() {
        if (state != State.LOCKED || !vault.hasBiometric()) return;
        VaultBiometric.unlock(this, vault, new VaultBiometric.Result() {
            @Override public void ok() { refreshState(); }

            @Override public void failed(String message) {
                if (!isDestroyed() && state == State.LOCKED) {
                    // cancelling the sheet is normal; only say something if it's more than that
                    if (message != null && !message.toLowerCase().contains("cancel") && !message.toLowerCase().contains("use pin")) showError(message);
                    bioButton.setVisibility(vault.hasBiometric() ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    private void lockNow() {
        real.lock();
        decoyVault.lock();
        VaultSession.decoyActive = false;
        shownIntruders = false;
        vault = real;
        VaultSession.wipeViewCache(this);
        items.clear();
        askedBiometric = true;          // after a manual lock don't pop the sensor up again straight away
        refreshState();
    }

    // ------------------------------------------------------------------ contents

    private void loadItems() {
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            List<Vault.Item> list;
            try {
                list = vault.list();
            } catch (IOException e) {
                list = new ArrayList<>();
            }
            final List<Vault.Item> found = list;
            ui.post(() -> {
                if (isDestroyed()) return;
                busy.setVisibility(View.GONE);
                items.clear();
                List<RowAdapter.Row> rows = new ArrayList<>();
                for (Vault.Item it : found) {
                    items.put(it.id, it);
                    rows.add(RowAdapter.Row.item(it.id, it.name, Fmt.bytes(it.size) + "  ·  added " + Fmt.ago(it.added, System.currentTimeMillis()),
                            FileOps.kindOfName(it.name)));
                }
                selected.retainAll(items.keySet());
                adapter.setRows(rows);
                empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                updateToolbar();
                moveInPending();
            });
        });
    }

    /** Encrypts files that were chosen in the Files tab, then removes the originals once they are safely inside. */
    private void moveInPending() {
        if (VaultSession.pending.isEmpty() || !vault.isUnlocked()) return;
        final List<File> todo = new ArrayList<>(VaultSession.pending);
        VaultSession.pending.clear();
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            int moved = 0;
            int failed = 0;
            for (File f : todo) {
                try {
                    moved += encryptTree(f, f.getParentFile(), true);
                } catch (IOException e) {
                    failed++;
                }
            }
            final int ok = moved;
            final int bad = failed;
            ui.post(() -> {
                if (isDestroyed()) return;
                Snackbar.make(findViewById(R.id.list), "Moved " + Fmt.plural(ok, "file", "files") + " into the vault"
                        + (bad > 0 ? ". " + bad + " couldn't be moved." : ""), 6000).show();
                loadItems();
            });
        });
    }

    /** Encrypts a file or a whole folder. Names keep their folder path. Originals are deleted only after a size check. */
    private int encryptTree(File f, File base, boolean deleteOriginal) throws IOException {
        if (f.isDirectory()) {
            int n = 0;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) n += encryptTree(k, base, deleteOriginal);
            if (deleteOriginal) {
                String[] left = f.list();
                if (left == null || left.length == 0) f.delete();
            }
            return n;
        }
        String rel = base == null ? f.getName() : f.getPath().substring(base.getPath().length()).replaceFirst("^/", "");
        Vault.Item it;
        InputStream in = new java.io.FileInputStream(f);
        try {
            it = vault.add(in, rel, f.lastModified());
        } finally {
            in.close();
        }
        if (deleteOriginal && it.size == f.length()) f.delete();
        return 1;
    }

    private void addFromPicker(final List<Uri> uris) {
        if (uris == null || uris.isEmpty() || !vault.isUnlocked()) return;
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            int ok = 0;
            for (Uri u : uris) {
                try {
                    String name = "file";
                    long modified = System.currentTimeMillis();
                    android.database.Cursor c = getContentResolver().query(u, null, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                            }
                        } finally {
                            c.close();
                        }
                    }
                    InputStream in = getContentResolver().openInputStream(u);
                    if (in == null) continue;
                    try {
                        vault.add(in, name, modified);
                        ok++;
                    } finally {
                        in.close();
                    }
                } catch (IOException ignored) { }
            }
            final int done = ok;
            ui.post(() -> {
                if (isDestroyed()) return;
                Snackbar.make(findViewById(R.id.list), "Added " + Fmt.plural(done, "file", "files")
                        + ". The originals are still where they were; delete them yourself if you want them gone.", 8000).show();
                loadItems();
            });
        });
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public void onClick(RowAdapter.Row row) {
        if (!selected.isEmpty()) {
            toggle(row);
            return;
        }
        final Vault.Item it = items.get(row.id);
        if (it == null) return;
        if (Viewer.typeOf(new File(it.name)) == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(it.name)
                    .setMessage("FileBandit can't show this kind of file inside the vault. Take it out of the vault to open it with another app.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                final File plain = vault.decryptToCache(it, VaultSession.viewCache(this));
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    if (!isDestroyed()) Viewer.openSecure(this, plain);
                });
            } catch (IOException e) {
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
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

    private List<Vault.Item> selectedItems() {
        List<Vault.Item> out = new ArrayList<>();
        for (String id : selected) {
            Vault.Item it = items.get(id);
            if (it != null) out.add(it);
        }
        return out;
    }

    private boolean onMenu(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.action_lock) {
            lockNow();
        } else if (id == R.id.action_change_pin) {
            showChangePin();
        } else if (id == R.id.action_bio) {
            toggleBiometric();
        } else if (id == R.id.action_decoy) {
            showDecoy();
        } else if (id == R.id.action_intruders) {
            showIntruders(false);
        } else if (id == R.id.action_export) {
            export(selectedItems());
        } else if (id == R.id.action_delete) {
            confirmDelete(selectedItems());
        } else if (id == R.id.action_select_all) {
            selected.addAll(items.keySet());
            adapter.notifyDataSetChanged();
            updateToolbar();
        } else {
            return false;
        }
        return true;
    }

    private void toggleBiometric() {
        if (vault.hasBiometric()) {
            VaultBiometric.disable(vault);
            Toast.makeText(this, "Fingerprint unlock is off", Toast.LENGTH_SHORT).show();
            updateToolbar();
        } else {
            VaultBiometric.enable(this, vault, new VaultBiometric.Result() {
                @Override public void ok() {
                    Toast.makeText(VaultActivity.this, "Fingerprint unlock is on. Your PIN still works too.", Toast.LENGTH_LONG).show();
                    updateToolbar();
                }

                @Override public void failed(String message) {
                    Toast.makeText(VaultActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    static File restoreDir() {
        return new File(Environment.getExternalStorageDirectory(), "Documents/FileBandit Restored");
    }

    private void export(final List<Vault.Item> list) {
        if (list.isEmpty()) return;
        if (!Access.allFiles()) {
            Toast.makeText(this, "Allow file access in the Files tab first, then try again.", Toast.LENGTH_LONG).show();
            return;
        }
        final File dest = restoreDir();
        busy.setVisibility(View.VISIBLE);
        io.execute(() -> {
            int ok = 0;
            String err = null;
            for (Vault.Item it : list) {
                try {
                    vault.exportTo(it, dest);
                    vault.delete(it);
                    ok++;
                } catch (IOException e) {
                    err = e.getMessage();
                }
            }
            final int done = ok;
            final String error = err;
            ui.post(() -> {
                if (isDestroyed()) return;
                busy.setVisibility(View.GONE);
                clearSelection();
                Snackbar.make(findViewById(R.id.list), error == null ? "Took " + Fmt.plural(done, "file", "files") + " out of the vault"
                        : "Took out " + done + ". " + error, 8000)
                        .setAction("Open folder", v -> {
                            Nav.dir = dest;
                            finish();
                        }).show();
                loadItems();
            });
        });
    }

    private void confirmDelete(final List<Vault.Item> list) {
        if (list.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + Fmt.plural(list.size(), "file", "files") + " for good?")
                .setMessage("Deleted vault files can't be recovered.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> io.execute(() -> {
                    for (Vault.Item it : list) vault.delete(it);
                    ui.post(() -> {
                        if (isDestroyed()) return;
                        clearSelection();
                        loadItems();
                    });
                })).show();
    }

    private void showChangePin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        final EditText cur = pinField("Current PIN");
        final EditText nw = pinField("New PIN (4 or more)");
        final EditText again = pinField("New PIN again");
        box.addView(cur);
        box.addView(nw);
        box.addView(again);
        final androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Change PIN").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Change", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            if (!nw.getText().toString().equals(again.getText().toString())) {
                again.setError("The new PINs don't match");
                return;
            }
            try {
                if (VaultSession.decoyActive && !VaultGate.differsFromReal(real, nw.getText().toString().toCharArray())) {
                    nw.setError("Choose a different PIN");
                    return;
                }
                vault.changePin(cur.getText().toString().toCharArray(), nw.getText().toString().toCharArray());
                dlg.dismiss();
                Toast.makeText(this, "PIN changed", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                cur.setError(e.getMessage());
            }
        }));
        dlg.show();
    }

    // ------------------------------------------------------------------ decoy and wrong-PIN log

    private void showDecoy() {
        if (decoyVault.exists()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Decoy vault is on")
                    .setMessage("Typing the decoy PIN opens a separate vault instead of this one. Nothing on screen tells them apart.\n\n"
                            + "If you turned on fingerprint unlock, a fingerprint still opens this real vault.")
                    .setNegativeButton("Keep it", null)
                    .setPositiveButton("Remove decoy", (d, w) -> new MaterialAlertDialogBuilder(this)
                            .setTitle("Remove the decoy vault?")
                            .setMessage("Anything stored in the decoy is deleted. This real vault isn't touched.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", (d2, w2) -> {
                                decoyVault.deleteAll();
                                Toast.makeText(this, "Decoy vault removed", Toast.LENGTH_SHORT).show();
                            }).show())
                    .show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        TextView explain = new TextView(this);
        explain.setText("If someone makes you open the vault, type this second PIN instead. It opens a different vault that "
                + "holds only what you put in it, and looks just like the real one. Put in a few harmless files so it looks lived in.");
        explain.setTextSize(14);
        box.addView(explain);
        final EditText one = pinField("Decoy PIN (4 or more)");
        final EditText two = pinField("Decoy PIN again");
        box.addView(one);
        box.addView(two);
        final androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Set up a decoy vault").setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            char[] a = one.getText().toString().toCharArray();
            if (a.length < Vault.MIN_PIN_LENGTH) {
                one.setError("Use at least " + Vault.MIN_PIN_LENGTH + " characters");
                return;
            }
            if (!one.getText().toString().equals(two.getText().toString())) {
                two.setError("The two entries don't match");
                return;
            }
            try {
                if (!VaultGate.differsFromReal(real, a)) {
                    one.setError("That's your real PIN. Choose a different one.");
                    return;
                }
                decoyVault.create(a);
                decoyVault.lock();
            } catch (IOException e) {
                one.setError(e.getMessage());
                return;
            }
            dlg.dismiss();
            Toast.makeText(this, "Decoy vault ready. Open the vault with the decoy PIN to fill it.", Toast.LENGTH_LONG).show();
        }));
        dlg.show();
    }

    private void maybeShowIntruders() {
        if (shownIntruders || VaultSession.decoyActive) return;
        if (VaultSession.intruders(this).list().isEmpty()) return;
        shownIntruders = true;
        showIntruders(true);
    }

    private void showIntruders(boolean automatic) {
        final IntruderLog log = VaultSession.intruders(this);
        List<Long> times = log.list();
        if (times.isEmpty()) {
            Toast.makeText(this, "No wrong PINs have been tried", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (int i = times.size() - 1; i >= 0 && i >= times.size() - 8; i--) {
            sb.append(Fmt.dateTime(times.get(i))).append("  (").append(Fmt.ago(times.get(i), now)).append(")\n");
        }
        if (times.size() > 8) sb.append("… and ").append(times.size() - 8).append(" earlier");
        new MaterialAlertDialogBuilder(this)
                .setTitle(automatic ? "Someone tried a wrong PIN" : "Wrong PIN tries")
                .setMessage(Fmt.plural(times.size(), "wrong try", "wrong tries") + " since you last cleared this list:\n\n" + sb)
                .setNegativeButton(automatic ? null : "Keep", null)
                .setPositiveButton(automatic ? "OK" : "Clear", (d, w) -> log.clear()).show();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setSingleLine(true);
        return e;
    }
}
