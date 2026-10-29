package com.fastshare.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Wraps the vault key with an AES key that lives in Android's Keystore (hardware-backed on most phones) and can never
 * be copied off the device. A stolen copy of the vault folder is useless without this phone.
 */
final class KeystoreWrap implements Vault.OuterWrap {
    private static final String ALIAS = "filebandit_vault_outer";

    /** True when the phone provides a Keystore we can use. */
    static KeystoreWrap tryCreate() {
        try {
            KeystoreWrap w = new KeystoreWrap();
            w.key();                       // creates the key on first use
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public String id() { return "keystore"; }

    private SecretKey key() throws GeneralSecurityException, java.io.IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    @Override
    public byte[] wrap(byte[] data) throws GeneralSecurityException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(data);
            return ByteBuffer.allocate(1 + iv.length + ct.length).put((byte) iv.length).put(iv).put(ct).array();
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    @Override
    public byte[] unwrap(byte[] data) throws GeneralSecurityException {
        try {
            int ivLen = data[0] & 0xFF;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(data, 1, 1 + ivLen)));
            return c.doFinal(data, 1 + ivLen, data.length - 1 - ivLen);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }
}
