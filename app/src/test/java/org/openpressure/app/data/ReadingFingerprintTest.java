package org.openpressure.app.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingFingerprintTest {
    @Test public void fixedMemoryRecordsWithoutClockDeduplicateAcrossRetries() {
        assertEquals("AA|2|null|010203",ReadingFingerprint.create("AA",2,null,"010203","vendor-history"));
        assertEquals(ReadingFingerprint.create("AA",2,null,"010203","vendor-history"),ReadingFingerprint.create("AA",2,null,"010203","vendor-history"));
        assertNotEquals(ReadingFingerprint.create("AA",1,null,"010203","vendor-history"),ReadingFingerprint.create("AA",2,null,"010203","vendor-history"));
        assertNotEquals(ReadingFingerprint.create("AA",1,null,"010203","vendor-history"),ReadingFingerprint.create("AA",1,null,"010204","vendor-history"));
    }
    @Test public void preservesExistingIdentitiesAndUndatedLiveRecordsStayDistinct() {
        assertEquals("AA|1|2026-10-16T14:30|abcd",ReadingFingerprint.create("AA",1,"2026-10-16T14:30","abcd","history"));
        assertEquals("AA|1|null|abcd",ReadingFingerprint.create("AA",1,null,"abcd","wlp-history"));
        assertNotEquals(ReadingFingerprint.create("AA",1,null,"abcd","bluetooth"),ReadingFingerprint.create("AA",1,null,"abcd","bluetooth"));
    }
}
