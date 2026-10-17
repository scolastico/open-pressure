package org.openpressure.app.data;

import org.junit.Test;
import java.security.GeneralSecurityException;
import java.util.*;
import static org.junit.Assert.*;

public class VendorPairingKeysTest {
    private static class Memory implements VendorPairingKeys.Backend {
        final Map<String,byte[]> values=new HashMap<>(); boolean failWrite;
        public byte[] get(String key) { byte[] value=values.get(key); return value==null?null:value.clone(); }
        public void put(String key,byte[] value) throws GeneralSecurityException {
            if(failWrite) throw new GeneralSecurityException("Storage unavailable");
            values.put(key,value.clone());
        }
        public void forget(String key) { values.remove(key); }
    }
    @Test public void recoveryKeepsOldAndPendingKeysUntilAcknowledged() throws Exception {
        Memory memory=new Memory(); VendorPairingKeys keys=new VendorPairingKeys(memory,"AA:BB","HEM-7600T");
        byte[] old=new byte[16],fresh=new byte[16]; Arrays.fill(fresh,(byte)7);
        keys.stage(old); keys.confirm(old); keys.stage(fresh);
        VendorPairingKeys restarted=new VendorPairingKeys(memory,"AA:BB","HEM-7600T");
        assertEquals(2,restarted.candidates().size());
        assertArrayEquals(fresh,restarted.candidates().get(0)); assertArrayEquals(old,restarted.candidates().get(1));
        restarted.confirm(fresh); assertEquals(1,restarted.candidates().size()); assertArrayEquals(fresh,restarted.candidates().get(0));
    }
    @Test public void failedPromotionLeavesRecoverableCandidate() throws Exception {
        Memory memory=new Memory(); VendorPairingKeys keys=new VendorPairingKeys(memory,"AA:BB","HEM-7155T");
        byte[] fresh=new byte[16]; keys.stage(fresh); memory.failWrite=true;
        assertThrows(GeneralSecurityException.class,() -> keys.confirm(fresh)); memory.failWrite=false;
        assertArrayEquals(fresh,new VendorPairingKeys(memory,"AA:BB","HEM-7155T").candidates().get(0));
    }
    @Test public void storageFailureStopsStagingAndScopesCannotReuseOtherKeys() throws Exception {
        Memory memory=new Memory(); VendorPairingKeys keys=new VendorPairingKeys(memory,"AA:BB","HEM-7155T");
        memory.failWrite=true; assertThrows(GeneralSecurityException.class,() -> keys.stage(new byte[16]));
        assertTrue(keys.candidates().isEmpty()); memory.failWrite=false;keys.stage(new byte[16]);
        assertTrue(new VendorPairingKeys(memory,"CC:DD","HEM-7155T").candidates().isEmpty());
        assertTrue(new VendorPairingKeys(memory,"AA:BB","HEM-7600T").candidates().isEmpty());
        assertThrows(IllegalArgumentException.class,() -> keys.stage(new byte[15]));
    }
    @Test public void retryCannotOverwriteTheOnlyPossiblyProgrammedKey() throws Exception {
        Memory memory=new Memory(); VendorPairingKeys keys=new VendorPairingKeys(memory,"AA:BB","HEM-7600T");
        byte[] first=new byte[16],second=new byte[16];Arrays.fill(first,(byte)3);Arrays.fill(second,(byte)9);
        keys.stage(first);
        assertThrows(IllegalStateException.class,() -> keys.stage(second));
        VendorPairingKeys restarted=new VendorPairingKeys(memory,"AA:BB","HEM-7600T");
        assertArrayEquals(first,restarted.candidates().get(0));
        assertArrayEquals(first,restarted.provisioningKey());
    }
    @Test public void candidatePresenceIsScopedAndCoversPendingKeys() throws Exception {
        Memory memory=new Memory(); VendorPairingKeys keys=new VendorPairingKeys(memory,"AA:BB","HEM-7155T");
        assertFalse(keys.hasCandidate());
        keys.stage(new byte[16]); assertTrue(keys.hasCandidate());
        keys.confirm(new byte[16]); assertTrue(keys.hasCandidate());
        assertFalse(new VendorPairingKeys(memory,"CC:DD","HEM-7155T").hasCandidate());
        assertFalse(new VendorPairingKeys(memory,"AA:BB","HEM-7361T").hasCandidate());
    }
}
