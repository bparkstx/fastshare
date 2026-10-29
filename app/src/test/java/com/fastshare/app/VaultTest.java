package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public class VaultTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final int FAST = 1000;      // PBKDF2 rounds: low so tests are quick

    private Vault newVault(File dir) {
        return new Vault(dir, Vault.NO_OUTER, FAST);
    }

    private static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private byte[] decrypt(Vault v, Vault.Item it) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        v.decryptTo(it, bos);
        return bos.toByteArray();
    }

    private Vault.Item add(Vault v, String name, byte[] data) throws IOException {
        return v.add(new ByteArrayInputStream(data), name, 1_700_000_000_000L);
    }

    @Test public void createUnlockAndWrongPin() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        assertFalse(v.exists());
        v.create("1234".toCharArray());
        assertTrue(v.exists());
        assertTrue(v.isUnlocked());
        v.lock();
        assertFalse(v.isUnlocked());

        Vault again = newVault(dir);                 // a fresh instance, like after restarting the app
        assertFalse(again.unlock("9999".toCharArray()));
        assertFalse(again.isUnlocked());
        assertTrue(again.unlock("1234".toCharArray()));
        assertTrue(again.isUnlocked());
    }

    @Test public void shortPinsAreRefusedAndASecondVaultCannotOverwriteTheFirst() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        try {
            v.create("123".toCharArray());
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("at least"));
        }
        v.create("abcd".toCharArray());
        try {
            newVault(dir).create("zzzz".toCharArray());
            fail();
        } catch (IOException expected) { }
        assertTrue(newVault(dir).unlock("abcd".toCharArray()));
    }

    @Test public void filesRoundTripAtAwkwardSizes() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        int[] sizes = {0, 1, 100, 65535, 65536, 65537, 131072, 300_000};
        for (int s : sizes) {
            byte[] data = random(s, s);
            Vault.Item it = add(v, "file" + s + ".bin", data);
            assertEquals(s, it.size);
            assertArrayEquals("size " + s, data, decrypt(v, it));
        }
        assertEquals(sizes.length, v.list().size());
    }

    @Test public void nothingReadableIsLeftOnDisk() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        v.create("1234".toCharArray());
        byte[] secret = "TOP SECRET PLAN: attack at dawn. The password is hunter2.".getBytes(StandardCharsets.UTF_8);
        Vault.Item it = add(v, "Tax return 2025.pdf", secret);

        byte[] raw = v.rawBytes(it);
        String rawText = new String(raw, StandardCharsets.ISO_8859_1);
        assertFalse(rawText.contains("TOP SECRET"));
        assertFalse(rawText.contains("hunter2"));
        assertFalse("the real file name must not be visible", rawText.contains("Tax return"));
        for (File f : dir.listFiles()) assertFalse(f.getName().contains("Tax"));
        assertTrue(it.file.getName().matches("[0-9a-f]{24}\\.fbv"));
        String meta = new String(Files.readAllBytes(new File(dir, "vault.meta").toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("the PIN is never stored", meta.contains("1234"));
    }

    @Test public void sameContentEncryptsDifferentlyEachTime() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        byte[] data = random(70_000, 1);
        byte[] a = v.rawBytes(add(v, "a", data));
        byte[] b = v.rawBytes(add(v, "a", data));
        assertNotEquals(Arrays.toString(Arrays.copyOfRange(a, 17, 60)), Arrays.toString(Arrays.copyOfRange(b, 17, 60)));
    }

    @Test public void namesDatesAndUnicodeSurvive() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        Vault.Item it = v.add(new ByteArrayInputStream(new byte[]{1, 2, 3}), "Fotos/Café \"best\" 写真.jpg", 1_234_567_890_000L);
        Vault.Item found = v.list().get(0);
        assertEquals("Fotos/Café \"best\" 写真.jpg", found.name);
        assertEquals(1_234_567_890_000L, found.modified);
        assertEquals(3, found.size);
        assertEquals(it.id, found.id);
    }

    @Test public void aFlippedByteIsDetected() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        v.create("1234".toCharArray());
        Vault.Item it = add(v, "x.bin", random(200_000, 3));
        try (RandomAccessFile raf = new RandomAccessFile(it.file, "rw")) {
            raf.seek(70_000);
            int b = raf.read();
            raf.seek(70_000);
            raf.write(b ^ 0x01);
        }
        try {
            decrypt(v, it);
            fail("tampered data must not decrypt");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("damaged or has been tampered"));
        }
    }

    @Test public void aCutShortFileIsDetected() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        byte[] data = random(200_000, 4);
        Vault.Item it = add(v, "x.bin", data);
        byte[] raw = Files.readAllBytes(it.file.toPath());

        // drop the last data chunk but keep a valid-looking trailer
        int metaLen = java.nio.ByteBuffer.wrap(raw, raw.length - 4, 4).getInt();
        byte[] trailer = Arrays.copyOfRange(raw, raw.length - 4 - metaLen, raw.length);
        int lastChunkLen = 4 + (200_000 - 3 * 65536) + 16;
        byte[] cut = new byte[raw.length - 4 - metaLen - lastChunkLen + trailer.length];
        System.arraycopy(raw, 0, cut, 0, raw.length - 4 - metaLen - lastChunkLen);
        System.arraycopy(trailer, 0, cut, raw.length - 4 - metaLen - lastChunkLen, trailer.length);
        Files.write(it.file.toPath(), cut);
        try {
            decrypt(v, it);
            fail("truncation must be noticed");
        } catch (IOException expected) { }
    }

    @Test public void swappedChunksAreDetected() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        Vault.Item it = add(v, "x.bin", random(200_000, 5));
        byte[] raw = Files.readAllBytes(it.file.toPath());
        int one = 4 + 65536 + 16;
        int start = 17;
        byte[] swapped = raw.clone();
        System.arraycopy(raw, start + one, swapped, start, one);          // chunk 2 where chunk 1 was
        System.arraycopy(raw, start, swapped, start + one, one);
        Files.write(it.file.toPath(), swapped);
        try {
            decrypt(v, it);
            fail("reordering must be noticed");
        } catch (IOException expected) { }
    }

    @Test public void lockedVaultRefusesEverything() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        Vault.Item it = add(v, "x.bin", random(10, 6));
        v.lock();
        try { v.list(); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("locked")); }
        try { add(v, "y", new byte[1]); fail(); } catch (IOException expected) { }
        try { decrypt(v, it); fail(); } catch (IOException expected) { }
    }

    @Test public void tooManyWrongPinsLockYouOutAndSuccessResets() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        v.create("1234".toCharArray());
        v.lock();
        final AtomicLong now = new AtomicLong(1_000_000);
        v.clock = now::get;
        for (int i = 0; i < 4; i++) assertFalse(v.unlock("0000".toCharArray()));
        assertEquals("four misses cost nothing", 0, v.lockoutMillisLeft());
        assertFalse(v.unlock("0000".toCharArray()));                         // fifth
        assertEquals(30_000, v.lockoutMillisLeft());
        try {
            v.unlock("1234".toCharArray());                                   // even the right PIN waits
            fail();
        } catch (Vault.LockedOutException e) {
            assertTrue(e.millisLeft > 0);
            assertTrue(e.getMessage(), e.getMessage().contains("Try again in"));
        }
        now.addAndGet(31_000);
        assertFalse(v.unlock("0000".toCharArray()));                         // sixth: waits twice as long
        assertEquals(60_000, v.lockoutMillisLeft());
        now.addAndGet(61_000);
        assertTrue(v.unlock("1234".toCharArray()));
        assertEquals(0, v.lockoutMillisLeft());
        v.lock();
        assertFalse("counter started over", v.unlock("0000".toCharArray()));
        assertEquals(0, v.lockoutMillisLeft());

        // the lockout survives restarting the app
        Vault restarted = newVault(dir);
        restarted.clock = now::get;
        for (int i = 0; i < 4; i++) restarted.unlock("0000".toCharArray());
        assertEquals(30_000, restarted.lockoutMillisLeft());
        Vault again = newVault(dir);
        again.clock = now::get;
        try { again.unlock("1234".toCharArray()); fail(); } catch (Vault.LockedOutException expected) { }
    }

    @Test public void changingThePinKeepsFilesReadable() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        v.create("1234".toCharArray());
        byte[] data = random(80_000, 7);
        add(v, "keep.bin", data);
        try {
            v.changePin("0000".toCharArray(), "5678".toCharArray());
            fail("needs the current PIN");
        } catch (IOException expected) { }
        v.changePin("1234".toCharArray(), "5678".toCharArray());
        v.lock();
        Vault fresh = newVault(dir);
        assertFalse(fresh.unlock("1234".toCharArray()));
        assertTrue(fresh.unlock("5678".toCharArray()));
        assertArrayEquals(data, decrypt(fresh, fresh.list().get(0)));
    }

    @Test public void exportRecreatesFoldersNeverOverwritesAndRefusesEscapes() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        File out = tmp.newFolder();
        Vault.Item a = add(v, "Trip/day1/photo.jpg", new byte[]{1, 2, 3});
        File one = v.exportTo(a, out);
        assertEquals(new File(out, "Trip/day1/photo.jpg").getPath(), one.getPath());
        File two = v.exportTo(a, out);
        assertEquals("photo (1).jpg", two.getName());

        Vault.Item sneaky = add(v, "../../escape.txt", new byte[]{9});
        File landed = v.exportTo(sneaky, out);
        assertTrue("stays inside the destination", landed.getCanonicalPath().startsWith(out.getCanonicalPath() + File.separator));
        assertEquals("escape.txt", landed.getName());
        assertFalse(new File(out.getParentFile().getParentFile(), "escape.txt").exists());
    }

    @Test public void deleteRemovesTheEncryptedFile() throws Exception {
        Vault v = newVault(new File(tmp.newFolder(), "vault"));
        v.create("1234".toCharArray());
        Vault.Item it = add(v, "x", new byte[]{1});
        v.delete(it);
        assertFalse(it.file.exists());
        assertTrue(v.list().isEmpty());
    }

    @Test public void unlockWithKeyChecksTheKey() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        v.create("1234".toCharArray());
        byte[] key = v.exportKeyForBiometric();
        v.setBiometric(new byte[12], new byte[]{1, 2, 3});
        assertTrue(v.hasBiometric());
        v.lock();
        Vault other = newVault(dir);
        assertFalse(other.unlockWithKey(new byte[32]));
        assertFalse(other.isUnlocked());
        assertTrue(other.unlockWithKey(key));
        assertTrue(other.isUnlocked());
        other.clearBiometric();
        assertFalse(other.hasBiometric());
    }

    @Test public void outerKeystoreLayerIsUsedAndRequired() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        final byte[] secret = random(32, 8);
        Vault.OuterWrap xor = new Vault.OuterWrap() {
            @Override public String id() { return "xor-test"; }
            @Override public byte[] wrap(byte[] d) { return xorWith(d); }
            @Override public byte[] unwrap(byte[] d) { return xorWith(d); }
            private byte[] xorWith(byte[] d) {
                byte[] o = d.clone();
                for (int i = 0; i < o.length; i++) o[i] ^= secret[i % secret.length];
                return o;
            }
        };
        Vault v = new Vault(dir, xor, FAST);
        v.create("1234".toCharArray());
        add(v, "x", new byte[]{1});
        v.lock();
        assertTrue(new Vault(dir, xor, FAST).unlock("1234".toCharArray()));
        // without the phone's outer key the right PIN is not enough
        assertFalse("correct PIN, missing hardware key", newVault(dir).unlock("1234".toCharArray()));
    }

    @Test public void listIgnoresJunkInTheFolder() throws Exception {
        File dir = new File(tmp.newFolder(), "vault");
        Vault v = newVault(dir);
        v.create("1234".toCharArray());
        add(v, "real.bin", new byte[]{1, 2});
        Files.write(new File(dir, "ffff.fbv").toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "notes.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        List<Vault.Item> items = v.list();
        assertEquals(1, items.size());
        assertEquals("real.bin", items.get(0).name);
    }
}
