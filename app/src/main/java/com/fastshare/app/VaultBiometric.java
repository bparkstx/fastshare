package com.fastshare.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Fingerprint (or face) unlock. After the PIN has opened the vault once, the vault key is wrapped with a Keystore key
 * that can only be used right after a successful strong biometric check. The PIN always keeps working.
 */
final class VaultBiometric {
    private VaultBiometric() { }

    private static final String ALIAS = "filebandit_vault_bio";
    private static final int AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    interface Result {
        void ok();
        void failed(String message);
    }

    static boolean available(AppCompatActivity a) {
        try {
            return BiometricManager.from(a).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static SecretKey createKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build());
        return kg.generateKey();
    }

    private static SecretKey existingKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private static BiometricPrompt.PromptInfo info(String title, String subtitle) {
        return new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Use PIN instead")
                .setAllowedAuthenticators(AUTH)
                .build();
    }

    /** Asks for the fingerprint once and stores the wrapped vault key. The vault must already be unlocked. */
    static void enable(final AppCompatActivity a, final Vault vault, final Result result) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, createKey());
            BiometricPrompt prompt = new BiometricPrompt(a, ContextCompat.getMainExecutor(a), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                    byte[] key = null;
                    try {
                        Cipher c = r.getCryptoObject().getCipher();
                        key = vault.exportKeyForBiometric();
                        vault.setBiometric(c.getIV(), c.doFinal(key));
                        result.ok();
                    } catch (Exception e) {
                        result.failed("Couldn't turn on fingerprint unlock: " + e.getMessage());
                    } finally {
                        if (key != null) Arrays.fill(key, (byte) 0);
                    }
                }

                @Override public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                    result.failed(msg.toString());
                }
            });
            prompt.authenticate(info("Turn on fingerprint unlock", "Touch the sensor to confirm"), new BiometricPrompt.CryptoObject(cipher));
        } catch (Exception e) {
            result.failed("Fingerprint unlock isn't available: " + e.getMessage());
        }
    }

    /** Asks for the fingerprint and, if it matches, opens the vault. */
    static void unlock(final AppCompatActivity a, final Vault vault, final Result result) {
        try {
            byte[] iv = vault.biometricIv();
            final byte[] blob = vault.biometricBlob();
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try {
                cipher.init(Cipher.DECRYPT_MODE, existingKey(), new GCMParameterSpec(128, iv));
            } catch (KeyPermanentlyInvalidatedException gone) {
                vault.clearBiometric();            // a new fingerprint was added: the old key is dead, back to the PIN
                result.failed("Your fingerprints changed, so unlock with your PIN and turn fingerprint unlock on again.");
                return;
            }
            BiometricPrompt prompt = new BiometricPrompt(a, ContextCompat.getMainExecutor(a), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                    byte[] key = null;
                    try {
                        key = r.getCryptoObject().getCipher().doFinal(blob);
                        if (vault.unlockWithKey(key)) result.ok();
                        else result.failed("Fingerprint unlock didn't work. Use your PIN.");
                    } catch (Exception e) {
                        result.failed("Fingerprint unlock didn't work. Use your PIN.");
                    } finally {
                        if (key != null) Arrays.fill(key, (byte) 0);
                    }
                }

                @Override public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                    result.failed(msg.toString());
                }
            });
            prompt.authenticate(info("Unlock your private vault", "Touch the sensor"), new BiometricPrompt.CryptoObject(cipher));
        } catch (Exception e) {
            result.failed("Fingerprint unlock isn't available. Use your PIN.");
        }
    }

    static void disable(Vault vault) {
        try {
            vault.clearBiometric();
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
    }
}
