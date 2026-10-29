package com.fastshare.app;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The saved network locations, and files waiting to be uploaded to one of them. */
final class RemoteSession {
    private RemoteSession() { }

    /** Files chosen in the Files tab, waiting for the user to pick where on the server they go. */
    static final List<File> pendingUploads = new ArrayList<>();

    private static Connections store;

    static synchronized Connections store(Context c) {
        if (store == null) {
            store = new Connections(new File(c.getApplicationContext().getFilesDir(), "connections.tsv"), new Sealer());
        }
        return store;
    }

    /** Tests start from an empty store. */
    static synchronized void reset() {
        store = null;
    }

    /** Passwords are stored encrypted with a key that never leaves this phone's Keystore (where the phone has one). */
    private static final class Sealer implements Connections.Secret {
        private final KeystoreWrap wrap = KeystoreWrap.tryCreate();

        @Override public String seal(String plain) throws IOException {
            if (plain == null || plain.isEmpty()) return "";
            if (wrap == null) return "p:" + plain;
            try {
                return "k:" + Base64.encodeToString(wrap.wrap(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            } catch (Exception e) {
                throw new IOException("Can't protect the saved password on this phone", e);
            }
        }

        @Override public String open(String sealed) throws IOException {
            if (sealed == null || sealed.isEmpty()) return "";
            if (sealed.startsWith("p:")) return sealed.substring(2);
            if (!sealed.startsWith("k:") || wrap == null) throw new IOException("Saved password can't be read");
            try {
                return new String(wrap.unwrap(Base64.decode(sealed.substring(2), Base64.NO_WRAP)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IOException("Saved password can't be read", e);
            }
        }
    }
}
