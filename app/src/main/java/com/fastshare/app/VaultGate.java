package com.fastshare.app;

import java.io.IOException;

/**
 * Decides which vault a PIN opens. If a decoy vault exists, its PIN opens that one instead of the real one, and nothing
 * on screen tells the two apart. Only wrong PINs count towards the lockout, and only on the real vault, so the decoy
 * can't be used to guess without being slowed down.
 */
public final class VaultGate {
    public enum Result { REAL, DECOY, WRONG }

    private VaultGate() { }

    /** @throws Vault.LockedOutException while waiting after too many wrong tries (for either PIN). */
    public static Result unlock(Vault real, Vault decoy, char[] pin) throws IOException {
        long left = real.lockoutMillisLeft();
        if (left > 0) throw new Vault.LockedOutException(left);
        if (decoy != null && decoy.exists() && decoy.unlockQuietly(pin)) {
            real.lock();
            return Result.DECOY;
        }
        if (real.unlock(pin)) {
            if (decoy != null) decoy.lock();
            return Result.REAL;
        }
        return Result.WRONG;
    }

    /** Whether {@code pin} could be used for a decoy: it must differ from the real PIN. */
    public static boolean differsFromReal(Vault real, char[] pin) throws IOException {
        return !real.checkPin(pin);
    }
}
