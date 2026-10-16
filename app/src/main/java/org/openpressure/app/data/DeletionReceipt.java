package org.openpressure.app.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Minimal stable deletion identity; never persist a raw measurement in a deletion receipt. */
final class DeletionReceipt {
    private DeletionReceipt() {}
    static String key(String fingerprint) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder(64);
            for(byte value:hash) { key.append(Character.forDigit((value>>>4)&15,16)); key.append(Character.forDigit(value&15,16)); }
            return key.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
}
