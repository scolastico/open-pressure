package org.openpressure.protocol;

public enum ProtocolProfile {
    STANDARD_BP, STP, X7_WLP, X7_SECURE_WLP, VENDOR_MEMORY, LEGACY_WLP, UNSUPPORTED;
    public boolean isX7() { return this==X7_WLP || this==X7_SECURE_WLP; }
    public static ProtocolProfile select(boolean bp,boolean optionalService,boolean vendorService,boolean handshake,boolean wlpDataChannels) {
        if (bp && optionalService) return STANDARD_BP;
        if (vendorService && wlpDataChannels) return LEGACY_WLP;
        if (bp && vendorService && handshake) return STP;
        if (bp) return STANDARD_BP;
        if (vendorService) return LEGACY_WLP;
        return UNSUPPORTED;
    }
}
