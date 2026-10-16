package org.openpressure.app.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeletionReceiptTest {
    @Test public void matchesSha256KnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",DeletionReceipt.key("abc"));
    }
    @Test public void retainsOnlyFixedSizeDigest() {
        String fingerprint="D7:11:22:33:44:55|2|2026-10-16T12:30|007812003421";
        String key=DeletionReceipt.key(fingerprint);
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertFalse(key.contains(fingerprint));
        assertEquals(key,DeletionReceipt.key(fingerprint));
        assertNotEquals(key,DeletionReceipt.key(fingerprint+"01"));
    }
}
