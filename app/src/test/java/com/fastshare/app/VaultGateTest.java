package com.fastshare.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

public class VaultGateTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Vault real;
    private Vault decoy;
    private long now = 1_000_000;

    private static char[] c(String s) { return s.toCharArray(); }

    @Before public void setUp() throws Exception {
        real = new Vault(tmp.newFolder("real"), Vault.NO_OUTER, 1000);
        decoy = new Vault(tmp.newFolder("decoy"), Vault.NO_OUTER, 1000);
        real.clock = () -> now;
        decoy.clock = () -> now;
        real.create(c("2468"));
        real.lock();
        decoy.create(c("1357"));
        decoy.lock();
    }

    @Test public void eachPinOpensItsOwnVaultAndLocksTheOther() throws Exception {
        assertEquals(VaultGate.Result.DECOY, VaultGate.unlock(real, decoy, c("1357")));
        assertTrue(decoy.isUnlocked());
        assertFalse(real.isUnlocked());
        assertEquals(VaultGate.Result.REAL, VaultGate.unlock(real, decoy, c("2468")));
        assertTrue(real.isUnlocked());
        assertFalse("the decoy is closed again", decoy.isUnlocked());
        real.lock();
        assertEquals(VaultGate.Result.WRONG, VaultGate.unlock(real, decoy, c("0000")));
        assertFalse(real.isUnlocked() || decoy.isUnlocked());
    }

    @Test public void withoutADecoyItBehavesLikeAnOrdinaryVault() throws Exception {
        Vault none = new Vault(tmp.newFolder("none"), Vault.NO_OUTER, 1000);
        assertEquals(VaultGate.Result.REAL, VaultGate.unlock(real, none, c("2468")));
        real.lock();
        assertEquals(VaultGate.Result.REAL, VaultGate.unlock(real, null, c("2468")));
        real.lock();
        assertEquals(VaultGate.Result.WRONG, VaultGate.unlock(real, none, c("1357")));
    }

    @Test public void decoyPinsDoNotCountAsMissesOrResetThem() throws Exception {
        for (int i = 0; i < 4; i++) assertEquals(VaultGate.Result.WRONG, VaultGate.unlock(real, decoy, c("bad" + i)));
        for (int i = 0; i < 12; i++) {
            assertEquals(VaultGate.Result.DECOY, VaultGate.unlock(real, decoy, c("1357")));
            decoy.lock();
        }
        // four misses are still on the books: the fifth starts the lockout, so guessing through the decoy buys nothing
        assertEquals(VaultGate.Result.WRONG, VaultGate.unlock(real, decoy, c("bad5")));
        assertTrue(real.lockoutMillisLeft() > 0);
    }

    @Test public void whileLockedOutNeitherPinWorksAndTheMessageIsTheSame() throws Exception {
        for (int i = 0; i < 5; i++) VaultGate.unlock(real, decoy, c("bad" + i));
        for (String pin : new String[]{"1357", "2468", "nope"}) {
            try {
                VaultGate.unlock(real, decoy, c(pin));
                fail("locked out");
            } catch (Vault.LockedOutException e) {
                assertTrue(e.millisLeft > 0);
            }
        }
        now += 31_000;
        assertEquals(VaultGate.Result.DECOY, VaultGate.unlock(real, decoy, c("1357")));
    }

    @Test public void theRealPinClearsEarlierMisses() throws Exception {
        for (int i = 0; i < 4; i++) VaultGate.unlock(real, decoy, c("bad" + i));
        assertEquals(VaultGate.Result.REAL, VaultGate.unlock(real, decoy, c("2468")));
        real.lock();
        for (int i = 0; i < 4; i++) assertEquals(VaultGate.Result.WRONG, VaultGate.unlock(real, decoy, c("again" + i)));
        assertEquals(0, real.lockoutMillisLeft());
    }

    @Test public void filesStayInTheirOwnVault() throws Exception {
        real.unlock(c("2468"));
        real.add(new ByteArrayInputStream("real data".getBytes()), "real.txt", 1);
        real.lock();
        decoy.unlock(c("1357"));
        decoy.add(new ByteArrayInputStream("fake data".getBytes()), "holiday.jpg", 1);
        assertEquals(1, decoy.list().size());
        assertEquals("holiday.jpg", decoy.list().get(0).name);
        decoy.lock();
        real.unlock(c("2468"));
        assertEquals(1, real.list().size());
        assertEquals("real.txt", real.list().get(0).name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        real.decryptTo(real.list().get(0), out);
        assertArrayEquals("real data".getBytes(), out.toByteArray());
    }

    @Test public void thePinCheckChangesNothing() throws Exception {
        assertTrue(real.checkPin(c("2468")));
        assertFalse(real.isUnlocked());
        for (int i = 0; i < 8; i++) assertFalse(real.checkPin(c("wrong")));
        assertEquals("no misses recorded", 0, real.lockoutMillisLeft());
        assertFalse(VaultGate.differsFromReal(real, c("2468")));
        assertTrue(VaultGate.differsFromReal(real, c("1357")));
    }

    @Test public void deletingTheDecoyRemovesItCompletely() throws Exception {
        decoy.unlock(c("1357"));
        decoy.add(new ByteArrayInputStream("x".getBytes()), "a.txt", 1);
        decoy.deleteAll();
        assertFalse(decoy.exists());
        assertFalse(new File(tmp.getRoot(), "decoy").exists());
        assertEquals(VaultGate.Result.REAL, VaultGate.unlock(real, decoy, c("2468")));
    }

    @Test public void theWrongPinLogKeepsTimesAcrossRestartsAndIsCapped() throws Exception {
        File f = new File(tmp.getRoot(), "sub/intruders.txt");
        IntruderLog log = new IntruderLog(f);
        log.record(100);
        log.record(200);
        assertEquals(java.util.Arrays.asList(100L, 200L), new IntruderLog(f).list());
        for (int i = 0; i < IntruderLog.MAX + 10; i++) log.record(1000 + i);
        assertEquals(IntruderLog.MAX, new IntruderLog(f).list().size());
        assertEquals(Long.valueOf(1000 + IntruderLog.MAX + 9), new IntruderLog(f).list().get(IntruderLog.MAX - 1));
        log.clear();
        assertTrue(new IntruderLog(f).list().isEmpty());
    }
}
