package org.openpressure.protocol;

import java.util.Arrays;
import java.util.HexFormat;

/** The shareable device report must keep identifying and health details out of its text. */
public final class DeviceReportTest {
    private static int checks;
    public static int run() {
        checks=0;
        onlyTheTestedIdentityIsVerified();
        identifyingFieldsAreMasked();
        reportContainsLayoutButNoValues();
        reportsWithoutGattAreNotOffered();
        return checks;
    }
    private static void onlyTheTestedIdentityIsVerified() {
        check(DeviceReport.verifiedModel("HEM-7380T1-EOSL\u0000") && DeviceReport.verifiedModel("x7 smart afib"),"tested identity and its marketing name are verified");
        check(!DeviceReport.verifiedModel("HEM-7380T1-EBK") && !DeviceReport.verifiedModel("HEM-7380T1"),"same-map variants are not hardware-verified");
        check(!DeviceReport.verifiedModel("HEM-7155T") && !DeviceReport.verifiedModel(null),"other and unknown monitors are not verified");
    }
    private static void identifyingFieldsAreMasked() {
        check("BLEsmart_00000258xxxxxxxxxxxx".equals(DeviceReport.maskName("BLEsmart_00000258AABBCCDDEEFF")),"category/model kept, unit suffix masked");
        check("BLESmart_0000".equals(DeviceReport.maskName("BLESmart_0000")),"short name keeps only the model part");
        check("xxxxxx xx".equals(DeviceReport.maskName("Custom 12")),"names without the Omron prefix are fully masked");
        check("06 01 xx xx xx xx xx xx".equals(DeviceReport.advertisementShape(hex("0601780164000b00"))),"advertisement values masked after type and flags");
    }
    private static void reportContainsLayoutButNoValues() {
        DeviceReport report=new DeviceReport("Pair","1.2.0",35,"Synthetic Phone");
        report.advertisement("BLEsmart_00000258AABBCCDDEEFF",hex("06017801640b0064"));
        report.mtu(247);
        report.service("0000FE4A-0000-1000-8000-00805F9B34FB",Arrays.asList("db5b55e0-aee7-11e1-965e-0002a5d5c51b properties=0x04"));
        report.model("HEM-7155T-EBK"); report.firmware("1.05");
        report.protocol("VENDOR_MEMORY","HEM-7155T");
        report.keyAcknowledgment(hex("8200")); report.keyAcknowledgment(hex("810000000000"));
        report.transfer(2,1,true);
        report.outcome("Action failed","Timed out talking to AA:BB:CC:DD:EE:FF serial 0123456789abcdef");
        String json=report.toJson();
        check(report.useful() && !report.verified(),"untested monitor with a GATT layout is offered");
        check(json.contains("\"model\": \"HEM-7155T-EBK\"") && json.contains("\"memory_map\": \"HEM-7155T\""),"identity and selected map included");
        check(json.contains("db5b55e0-aee7-11e1-965e-0002a5d5c51b properties=0x04"),"GATT UUIDs and properties are kept intact");
        check(json.contains("\"type\": 6") && json.contains("06 01 xx xx xx xx xx xx"),"advertisement type kept, values masked");
        check(json.contains("81 00 (6 bytes)"),"padded key acknowledgment shape recorded");
        check(json.contains("\"decoded_records\": 2") && json.contains("\"end_acknowledged\": true"),"transfer counts recorded");
        for(String secret:new String[]{"AA:BB:CC:DD:EE:FF","AABBCCDDEEFF","0123456789abcdef","7801","0b00"})
            check(!json.contains(secret),"identifier or sequence value leaked: "+secret);
        check(json.contains("[address removed]") && json.contains("[identifier removed]"),"failure text scrubbed rather than dropped");
    }
    private static void reportsWithoutGattAreNotOffered() {
        DeviceReport report=new DeviceReport("Connect","1.2.0",35,"Synthetic Phone");
        report.outcome("Action failed","Connection timed out");
        check(!report.useful(),"a connection that never discovered services has nothing to share");
        report.service("0000180a-0000-1000-8000-00805f9b34fb",Arrays.asList());
        report.model("HEM-7380T1-EOSL");
        check(!report.useful(),"the tested monitor is never asked for a report");
        check(report.toJson().contains("\"present\": false"),"missing advertisement is explicit");
    }
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
}
