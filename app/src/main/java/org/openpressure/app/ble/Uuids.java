package org.openpressure.app.ble;

import java.util.UUID;

public final class Uuids {
    public static UUID sig(int value) { return UUID.fromString(String.format(java.util.Locale.ROOT,"0000%04x-0000-1000-8000-00805f9b34fb",value)); }
    public static final UUID BP=sig(0x1810), MEASUREMENT=sig(0x2a35), FEATURE=sig(0x2a49), CCCD=sig(0x2902),
        OPTIONAL=UUID.fromString("5df5e817-a945-4f81-89c0-3d4e9759c07c"), RACP=sig(0x2a52),
        UDS=sig(0x181c), USER_CONTROL=sig(0x2a9f), USER_INDEX=sig(0x2a9a), DATABASE_INCREMENT=sig(0x2a99),
        CTS=sig(0x1805), CURRENT_TIME=sig(0x2a2b), DATE_TIME=sig(0x2a08), COMMAND=sig(0x5045),
        VENDOR=UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"), VENDOR_ALIAS=sig(0xfe4a),
        HANDSHAKE=UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b"),
        WLP_TX=UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b"),
        WLP_RX=UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b");
    private Uuids() {}
}
