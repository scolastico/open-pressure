package org.openpressure.protocol;

/** Security advertised by Omron manufacturer data, with Android's company ID removed. */
public final class X7Security {
    public enum Mode { UNKNOWN, PLAIN, ENCRYPTED }
    private X7Security() {}

    public static Mode fromManufacturerPayload(byte[] payload) {
        if(payload==null || payload.length==0) return Mode.UNKNOWN;
        // The original advertisement filter recognizes 1, 2, 3, 6, 8 and 9;
        // its WLP selector requires application encryption for types 8 and 9.
        switch(payload[0]&255) {
            case 1: case 2: case 3: case 6: return Mode.PLAIN;
            case 8: case 9: return Mode.ENCRYPTED;
            default: return Mode.UNKNOWN;
        }
    }
}
