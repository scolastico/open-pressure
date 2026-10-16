package org.openpressure.app.data;

import java.util.UUID;

/** Retains fixed memory-record identities while undated live packets remain separate events. */
public final class ReadingFingerprint {
    private ReadingFingerprint() {}
    public static String create(String device,Integer user,String timestamp,String rawHex,String source) {
        boolean storedMemory="wlp-history".equals(source) || "vendor-history".equals(source);
        return timestamp==null && !storedMemory?UUID.randomUUID().toString():device+"|"+user+"|"+timestamp+"|"+rawHex;
    }
}
