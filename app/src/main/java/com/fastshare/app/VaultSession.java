package com.fastshare.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The one vault instance shared by the screens, and files waiting to be moved into it. */
final class VaultSession {
    private VaultSession() { }

    /** Tests can swap in a faster, hardware-free vault. */
    static volatile Vault testVault;

    private static Vault vault;

    /** Files chosen in the Files tab, waiting for the vault to be unlocked so they can be encrypted. */
    static final List<File> pending = new ArrayList<>();

    static synchronized Vault get(Context c) {
        if (testVault != null) return testVault;
        if (vault == null) {
            File dir = new File(c.getApplicationContext().getFilesDir(), "vault");
            Vault.OuterWrap outer = KeystoreWrap.tryCreate();
            vault = new Vault(dir, outer == null ? Vault.NO_OUTER : outer, Vault.DEFAULT_ITERATIONS);
        }
        return vault;
    }

    /** Where decrypted copies are placed while a file is being viewed. Wiped when the vault locks. */
    static File viewCache(Context c) {
        return new File(c.getCacheDir(), "vault_view");
    }

    static void wipeViewCache(Context c) {
        FileOps.delete(viewCache(c));
    }
}
