package com.fastshare.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Hosts the two tabs: Files (the file manager) and Transfer (send files to and from an iPhone). */
public class MainActivity extends AppCompatActivity {

    private static final String TAG_FILES = "files";
    private static final String TAG_TRANSFER = "transfer";

    private BottomNavigationView nav;
    private FilesFragment files;
    private TransferFragment transfer;
    private ShareHub hub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hub = FastShareApp.hub(this);
        hub.start();

        nav = findViewById(R.id.nav);
        if (savedInstanceState == null) {
            files = new FilesFragment();
            transfer = new TransferFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, files, TAG_FILES)
                    .add(R.id.container, transfer, TAG_TRANSFER)
                    .hide(transfer)
                    .commitNow();
        } else {
            files = (FilesFragment) getSupportFragmentManager().findFragmentByTag(TAG_FILES);
            transfer = (TransferFragment) getSupportFragmentManager().findFragmentByTag(TAG_TRANSFER);
        }
        nav.setOnItemSelectedListener(item -> {
            show(item.getItemId() == R.id.nav_transfer);
            return true;
        });
        syncKeepAwake();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (transfer != null && transfer.isVisible()) {
                    nav.setSelectedItemId(R.id.nav_files);
                } else if (files == null || !files.handleBack()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) hub.stop();
    }

    // ------------------------------------------------------------------ tabs

    private void show(boolean showTransfer) {
        if (files == null || transfer == null) return;
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        if (showTransfer) tx.hide(files).show(transfer);
        else tx.hide(transfer).show(files);
        tx.commitNowAllowingStateLoss();
        syncKeepAwake();
    }

    /** The screen stays on while the Transfer tab is showing so the connection isn't lost mid-transfer. */
    private void syncKeepAwake() {
        boolean onTransfer = nav.getSelectedItemId() == R.id.nav_transfer;
        if (onTransfer) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void showTransfer() {
        nav.setSelectedItemId(R.id.nav_transfer);
    }

    /** Switches to the Files tab, optionally opening a folder. */
    void showFiles(File dir) {
        nav.setSelectedItemId(R.id.nav_files);
        if (dir != null && files != null) files.openDir(dir);
    }

    // ------------------------------------------------------------------ "Share > FileBandit" from other apps

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        hub.start();
        hub.addUris(uris);
        Toast.makeText(this, "Added " + Fmt.plural(uris.size(), "file", "files") + " to send", Toast.LENGTH_LONG).show();
        showTransfer();
        intent.setAction(Intent.ACTION_MAIN);   // don't re-add them if the activity is recreated
    }
}
