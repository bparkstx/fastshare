package com.fastshare.app;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.LongSupplier;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * An encrypted folder for private files.
 *
 * <ul>
 *   <li>Every file is encrypted with AES-256-GCM in 64 KB chunks. Each chunk is authenticated and bound to its
 *       position, so tampering, reordering and truncation are all detected.</li>
 *   <li>The file name, size and dates are inside the encrypted data too; on disk each file is just random bytes
 *       called {@code <random>.fbv}.</li>
 *   <li>Files are encrypted with a random master key. The master key is stored only in encrypted form, wrapped with a
 *       key derived from the PIN (PBKDF2-HMAC-SHA256), then (on a phone) wrapped again by a key that never leaves
 *       Android's hardware-backed Keystore.</li>
 *   <li>Wrong PINs are rate limited. There is no recovery: forget the PIN and the files are gone for good.</li>
 * </ul>
 */
public final class Vault {

    /** An extra layer around the PIN-wrapped key, normally backed by the Android Keystore. */
    public interface OuterWrap {
        String id();
        byte[] wrap(byte[] data) throws GeneralSecurityException;
        byte[] unwrap(byte[] data) throws GeneralSecurityException;
    }

    public static final OuterWrap NO_OUTER = new OuterWrap() {
        @Override public String id() { return "none"; }
        @Override public byte[] wrap(byte[] d) { return d; }
        @Override public byte[] unwrap(byte[] d) { return d; }
    };

    /** The PIN was tried too many times; wait. */
    public static final class LockedOutException extends IOException {
        public final long millisLeft;

        LockedOutException(long millisLeft) {
            super("Too many wrong attempts. Try again in " + (millisLeft / 1000 + 1) + " s.");
            this.millisLeft = millisLeft;
        }
    }

    /** One file inside the vault. */
    public static final class Item {
        public final String id;
        public final String name;        // may contain "/" for files that came from folders
        public final long size;
        public final long modified;
        public final long added;
        final File file;

        Item(String id, String name, long size, long modified, long added, File file) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.modified = modified;
            this.added = added;
            this.file = file;
        }
    }

    private static final byte[] MAGIC = {'F', 'B', 'V', '1'};
    private static final int HEADER_LEN = 4 + 1 + 4 + 8;
    private static final int CHUNK = 64 * 1024;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] CANARY = "filebandit-vault-ok".getBytes(StandardCharsets.UTF_8);
    public static final int DEFAULT_ITERATIONS = 210_000;
    public static final int MIN_PIN_LENGTH = 4;

    private final File dir;
    private final OuterWrap outer;
    private final int iterations;
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] masterKey;
    /** Replaceable clock so lockouts can be tested without waiting. */
    LongSupplier clock = System::currentTimeMillis;

    public Vault(File dir, OuterWrap outer, int iterations) {
        this.dir = dir;
        this.outer = outer == null ? NO_OUTER : outer;
        this.iterations = iterations;
    }

    // ------------------------------------------------------------------ state

    private File metaFile() {
        return new File(dir, "vault.meta");
    }

    private File failFile() {
        return new File(dir, "vault.fail");
    }

    public boolean exists() {
        return metaFile().isFile();
    }

    public boolean isUnlocked() {
        return masterKey != null;
    }

    /** Forgets the key in memory. The files stay encrypted on disk. */
    public void lock() {
        byte[] k = masterKey;
        masterKey = null;
        if (k != null) Arrays.fill(k, (byte) 0);
    }

    private byte[] key() throws IOException {
        byte[] k = masterKey;
        if (k == null) throw new IOException("The vault is locked");
        return k;
    }

    // ------------------------------------------------------------------ meta file

    private Properties readMeta() throws IOException {
        Properties p = new Properties();
        InputStream in = new FileInputStream(metaFile());
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return p;
    }

    private void writeMeta(Properties p) throws IOException {
        File tmp = new File(dir, "vault.meta.tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            p.store(os, null);
        } finally {
            os.close();
        }
        if (!tmp.renameTo(metaFile())) throw new IOException("Couldn't save the vault settings");
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    // ------------------------------------------------------------------ setup and unlock

    private byte[] derive(char[] pin, byte[] salt, int iter) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, iter, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] seal(byte[] key, byte[] plain, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plain);
        return ByteBuffer.allocate(12 + ct.length).put(nonce).put(ct).array();
    }

    private static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        if (sealed.length < 12 + 16) throw new AEADBadTagException("too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOfRange(sealed, 0, 12)));
        c.updateAAD(aad);
        return c.doFinal(sealed, 12, sealed.length - 12);
    }

    private static final byte[] AAD_MASTER = "FBV-master".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_CANARY = "FBV-canary".getBytes(StandardCharsets.UTF_8);

    /** Creates a new vault protected by {@code pin} and leaves it unlocked. */
    public void create(char[] pin) throws IOException {
        if (exists()) throw new IOException("A vault already exists");
        if (pin == null || pin.length < MIN_PIN_LENGTH) throw new IOException("Use at least " + MIN_PIN_LENGTH + " characters");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create the vault folder");
        try {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            byte[] master = new byte[32];
            random.nextBytes(master);
            byte[] kek = derive(pin, salt, iterations);
            Properties p = new Properties();
            p.setProperty("version", "1");
            p.setProperty("iterations", Integer.toString(iterations));
            p.setProperty("salt", b64(salt));
            p.setProperty("outer", outer.id());
            p.setProperty("wrapped", b64(outer.wrap(seal(kek, master, AAD_MASTER))));
            p.setProperty("canary", b64(seal(master, CANARY, AAD_CANARY)));
            Arrays.fill(kek, (byte) 0);
            writeMeta(p);
            masterKey = master;
        } catch (GeneralSecurityException e) {
            throw new IOException("Couldn't set up encryption: " + e.getMessage(), e);
        }
    }

    /**
     * Opens the vault. Returns false for a wrong PIN. Throws {@link LockedOutException} while waiting after too many
     * wrong tries.
     */
    public boolean unlock(char[] pin) throws IOException {
        long left = lockedOutFor();
        if (left > 0) throw new LockedOutException(left);
        Properties p = readMeta();
        byte[] master;
        try {
            byte[] wrapped = outer.unwrap(unb64(p.getProperty("wrapped")));
            byte[] kek = derive(pin, unb64(p.getProperty("salt")), Integer.parseInt(p.getProperty("iterations")));
            try {
                master = open(kek, wrapped, AAD_MASTER);
            } finally {
                Arrays.fill(kek, (byte) 0);
            }
        } catch (AEADBadTagException wrong) {
            recordFailure();
            return false;
        } catch (GeneralSecurityException e) {
            throw new IOException("This vault can't be opened on this phone (" + e.getMessage() + ")", e);
        }
        clearFailures();
        masterKey = master;
        return true;
    }

    /** Opens the vault with the master key (used by fingerprint unlock). Returns false if the key is wrong. */
    public boolean unlockWithKey(byte[] key) throws IOException {
        try {
            byte[] canary = open(key, unb64(readMeta().getProperty("canary")), AAD_CANARY);
            if (!MessageDigest.isEqual(canary, CANARY)) return false;
        } catch (GeneralSecurityException e) {
            return false;
        }
        masterKey = key.clone();
        return true;
    }

    /** A copy of the master key, for wrapping it with the fingerprint. The caller must wipe it. */
    public byte[] exportKeyForBiometric() throws IOException {
        return key().clone();
    }

    public void changePin(char[] oldPin, char[] newPin) throws IOException {
        if (newPin == null || newPin.length < MIN_PIN_LENGTH) throw new IOException("Use at least " + MIN_PIN_LENGTH + " characters");
        if (!unlock(oldPin)) throw new IOException("The current PIN is wrong");
        try {
            Properties p = readMeta();
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            byte[] kek = derive(newPin, salt, iterations);
            p.setProperty("iterations", Integer.toString(iterations));
            p.setProperty("salt", b64(salt));
            p.setProperty("outer", outer.id());
            p.setProperty("wrapped", b64(outer.wrap(seal(kek, key(), AAD_MASTER))));
            Arrays.fill(kek, (byte) 0);
            writeMeta(p);
        } catch (GeneralSecurityException e) {
            throw new IOException("Couldn't change the PIN: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ fingerprint blob

    public boolean hasBiometric() {
        try {
            return exists() && readMeta().getProperty("bioBlob") != null;
        } catch (IOException e) {
            return false;
        }
    }

    public void setBiometric(byte[] iv, byte[] blob) throws IOException {
        Properties p = readMeta();
        p.setProperty("bioIv", b64(iv));
        p.setProperty("bioBlob", b64(blob));
        writeMeta(p);
    }

    public byte[] biometricIv() throws IOException {
        return unb64(readMeta().getProperty("bioIv"));
    }

    public byte[] biometricBlob() throws IOException {
        return unb64(readMeta().getProperty("bioBlob"));
    }

    public void clearBiometric() throws IOException {
        Properties p = readMeta();
        p.remove("bioIv");
        p.remove("bioBlob");
        writeMeta(p);
    }

    // ------------------------------------------------------------------ rate limiting

    private long lockedOutFor() {
        Properties p = new Properties();
        File f = failFile();
        if (!f.isFile()) return 0;
        try {
            InputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            long until = Long.parseLong(p.getProperty("until", "0"));
            return Math.max(0, until - clock.getAsLong());
        } catch (Exception e) {
            return 0;
        }
    }

    /** How long (ms) until another PIN try is allowed; 0 if allowed now. */
    public long lockoutMillisLeft() {
        return lockedOutFor();
    }

    private void recordFailure() {
        Properties p = new Properties();
        File f = failFile();
        try {
            if (f.isFile()) {
                InputStream in = new FileInputStream(f);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            }
            int n = Integer.parseInt(p.getProperty("count", "0")) + 1;
            p.setProperty("count", Integer.toString(n));
            // 5 wrong tries in a row: 30 s, then doubling each further miss up to an hour
            long wait = n < 5 ? 0 : Math.min(3_600_000L, 30_000L << Math.min(10, n - 5));
            p.setProperty("until", Long.toString(clock.getAsLong() + wait));
            OutputStream os = new FileOutputStream(f);
            try {
                p.store(os, null);
            } finally {
                os.close();
            }
        } catch (Exception ignored) { }
    }

    private void clearFailures() {
        //noinspection ResultOfMethodCallIgnored
        failFile().delete();
    }

    // ------------------------------------------------------------------ files

    private byte[] header(byte[] noncePrefix) {
        return ByteBuffer.allocate(HEADER_LEN).put(MAGIC).put((byte) 1).putInt(CHUNK).put(noncePrefix).array();
    }

    private static byte[] nonce(byte[] prefix, int counter) {
        return ByteBuffer.allocate(12).put(prefix).putInt(counter).array();
    }

    private Cipher chunkCipher(int mode, byte[] key, byte[] prefix, int counter, byte[] header, boolean last, boolean meta)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, counter)));
        c.updateAAD(header);
        if (meta) {
            c.updateAAD(new byte[]{'M'});
        } else {
            c.updateAAD(ByteBuffer.allocate(5).putInt(counter).put((byte) (last ? 1 : 0)).array());
        }
        return c;
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        byte[] buf = new byte[max];
        int off = 0;
        while (off < max) {
            int n = in.read(buf, off, max - off);
            if (n < 0) break;
            off += n;
        }
        return off == max ? buf : Arrays.copyOf(buf, off);
    }

    private static String metaJson(String name, long size, long modified, long added) {
        return "{\"name\":" + FileServer.jsonStr(name) + ",\"size\":" + size + ",\"modified\":" + modified + ",\"added\":" + added + "}";
    }

    /** Encrypts everything read from {@code in} into the vault. */
    public Item add(InputStream in, String name, long modified) throws IOException {
        byte[] key = key();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create the vault folder");
        byte[] idBytes = new byte[12];
        random.nextBytes(idBytes);
        StringBuilder idb = new StringBuilder();
        for (byte b : idBytes) idb.append(String.format(Locale.ROOT, "%02x", b));
        String id = idb.toString();
        File part = new File(dir, id + ".fbv.part");
        File target = new File(dir, id + ".fbv");
        byte[] prefix = new byte[8];
        random.nextBytes(prefix);
        byte[] header = header(prefix);
        long total = 0;
        long added = clock.getAsLong();
        boolean ok = false;
        OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(part), 128 * 1024);
        try {
            out.write(header);
            byte[] cur = readUpTo(in, CHUNK);
            int i = 0;
            while (true) {
                byte[] next = cur.length == CHUNK ? readUpTo(in, CHUNK) : new byte[0];
                boolean last = next.length == 0;
                byte[] ct = chunkCipher(Cipher.ENCRYPT_MODE, key, prefix, i, header, last, false).doFinal(cur);
                out.write(ByteBuffer.allocate(4).putInt(ct.length).array());
                out.write(ct);
                total += cur.length;
                if (last) break;
                cur = next;
                i++;
            }
            byte[] metaCt = chunkCipher(Cipher.ENCRYPT_MODE, key, prefix, -1, header, true, true)
                    .doFinal(metaJson(name, total, modified, added).getBytes(StandardCharsets.UTF_8));
            out.write(metaCt);
            out.write(ByteBuffer.allocate(4).putInt(metaCt.length).array());
            out.flush();
            out.close();
            if (!part.renameTo(target)) throw new IOException("Couldn't save the encrypted file");
            ok = true;
            return new Item(id, name, total, modified, added, target);
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed: " + e.getMessage(), e);
        } finally {
            if (!ok) {
                try { out.close(); } catch (IOException ignored) { }
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    public Item add(File source) throws IOException {
        InputStream in = new FileInputStream(source);
        try {
            return add(in, source.getName(), source.lastModified());
        } finally {
            in.close();
        }
    }

    private Item readItem(File f, byte[] key) throws IOException, GeneralSecurityException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long len = raf.length();
            if (len < HEADER_LEN + 4 + 4 + 16) throw new IOException("Not a vault file");
            byte[] header = new byte[HEADER_LEN];
            raf.readFully(header);
            for (int i = 0; i < 4; i++) if (header[i] != MAGIC[i]) throw new IOException("Not a vault file");
            byte[] prefix = Arrays.copyOfRange(header, 9, 17);
            raf.seek(len - 4);
            int metaLen = raf.readInt();
            if (metaLen < 16 || metaLen > 64 * 1024 || len - 4 - metaLen < HEADER_LEN) throw new IOException("Damaged vault file");
            byte[] metaCt = new byte[metaLen];
            raf.seek(len - 4 - metaLen);
            raf.readFully(metaCt);
            byte[] json = chunkCipher(Cipher.DECRYPT_MODE, key, prefix, -1, header, true, true).doFinal(metaCt);
            String s = new String(json, StandardCharsets.UTF_8);
            String id = f.getName().substring(0, f.getName().length() - 4);
            return new Item(id, jsonString(s, "name"), jsonLong(s, "size"), jsonLong(s, "modified"), jsonLong(s, "added"), f);
        } finally {
            raf.close();
        }
    }

    private static long jsonLong(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        if (i < 0) return 0;
        i += key.length() + 3;
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        return Long.parseLong(json.substring(i, j));
    }

    private static String jsonString(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) return "";
        i += key.length() + 4;
        StringBuilder sb = new StringBuilder();
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                if (n == 'u' && i + 4 < json.length()) {
                    sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                } else {
                    sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Everything in the vault, newest first. Files that can't be read are skipped. */
    public List<Item> list() throws IOException {
        byte[] key = key();
        List<Item> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".fbv")) continue;
            try {
                out.add(readItem(f, key));
            } catch (IOException | GeneralSecurityException ignored) { }
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(b.added, a.added); }
        });
        return out;
    }

    /** Decrypts a file to {@code out}. Throws if any part was tampered with or cut short. */
    public void decryptTo(Item item, OutputStream out) throws IOException {
        byte[] key = key();
        RandomAccessFile raf = new RandomAccessFile(item.file, "r");
        try {
            long len = raf.length();
            byte[] header = new byte[HEADER_LEN];
            raf.readFully(header);
            byte[] prefix = Arrays.copyOfRange(header, 9, 17);
            raf.seek(len - 4);
            int metaLen = raf.readInt();
            long end = len - 4 - metaLen;
            raf.seek(HEADER_LEN);
            int i = 0;
            while (raf.getFilePointer() < end) {
                int ctLen = raf.readInt();
                if (ctLen < 16 || ctLen > CHUNK + 16 || raf.getFilePointer() + ctLen > end) throw new IOException("This file is damaged");
                byte[] ct = new byte[ctLen];
                raf.readFully(ct);
                boolean last = raf.getFilePointer() >= end;
                out.write(chunkCipher(Cipher.DECRYPT_MODE, key, prefix, i, header, last, false).doFinal(ct));
                i++;
            }
            if (i == 0) throw new IOException("This file is damaged");
        } catch (GeneralSecurityException e) {
            throw new IOException("This file is damaged or has been tampered with", e);
        } catch (EOFException e) {
            throw new IOException("This file is damaged", e);
        } finally {
            raf.close();
        }
    }

    /** Restores a file to {@code destDir}, recreating any folders in its name. Never overwrites. */
    public File exportTo(Item item, File destDir) throws IOException {
        String[] parts = item.name.replace('\\', '/').split("/");
        File dir = destDir;
        for (int i = 0; i < parts.length - 1; i++) {
            String seg = parts[i];
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) continue;
            dir = new File(dir, seg);
        }
        String last = parts[parts.length - 1];
        if (last.isEmpty() || last.equals(".") || last.equals("..")) last = "file";
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir.getName());
        File target = FileOps.uniqueSibling(dir, last);
        OutputStream os = new FileOutputStream(target);
        boolean ok = false;
        try {
            decryptTo(item, os);
            ok = true;
        } finally {
            os.close();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
        if (item.modified > 0) target.setLastModified(item.modified);
        return target;
    }

    /** Decrypts into a throwaway folder so another viewer can show it. */
    public File decryptToCache(Item item, File cacheRoot) throws IOException {
        File dir = new File(cacheRoot, item.id);
        FileOps.delete(dir);
        return exportTo(item, dir);
    }

    public void delete(Item item) {
        //noinspection ResultOfMethodCallIgnored
        item.file.delete();
    }

    public long totalBytes() throws IOException {
        long t = 0;
        for (Item i : list()) t += i.size;
        return t;
    }

    /** Debug aid for tests: the raw bytes of an encrypted file. */
    byte[] rawBytes(Item item) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(item.file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return bos.toByteArray();
    }
}
