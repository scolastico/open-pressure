package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** Literal, independently constructed protocol fixtures; no upstream executable code. */
public final class VendorMemoryModelTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("Vendor memory tests passed: "+run()+" assertions"); }
    public static int run() {
        checks=0;
        identificationAndRanges();
        verifiedRegionalAliases();
        recordFormats();
        rejectsMalformedRecords();
        recordChecksums();
        bp5360Attribution();
        return checks;
    }

    private static void identificationAndRanges() {
        // Each literal catches reading an adjacent model's bank or capacity.
        map("HEM-7150T",16,0x10,68,true,new int[]{0x98},new int[]{960});
        map("HEM-7155T",16,0x10,68,true,new int[]{0x98,0x458},new int[]{960,960});
        map("HEM-7156T",16,0x10,68,true,new int[]{0x98,0x458},new int[]{960,960});
        map("HEM-7322T",14,0x260,38,true,new int[]{0x2ac,0x824},new int[]{1400,1400});
        map("HEM-7342T",16,0x10,68,true,new int[]{0x98,0x6d8},new int[]{1600,1600});
        map("HEM-7361T",16,0x10,68,true,new int[]{0x98,0x6d8},new int[]{1600,1600});
        map("HEM-7377T1",16,0x40,24,false,new int[]{0x1cc,0x80c},new int[]{1600,1600});
        map("HEM-7530T",14,0x260,68,true,new int[]{0x2e8},new int[]{1260});
        map("HEM-7600T",14,0x260,38,true,new int[]{0x2ac},new int[]{1400});
        map("HEM-6232T",14,0x260,68,true,new int[]{0x2e8,0x860},new int[]{1400,1400});
        check(model("HEM-7377T1-ZAZ").id.equals("HEM-7377T1"),"documented BP5360 regional identity");
        check(model("HEM-7530T-Z").id.equals("HEM-7530T"),"documented Complete regional identity");
        check(model("HEM-7530T_E3").id.equals("HEM-7530T"),"documented Complete underscore identity");
        check(model(" HEM-7600T\u0000").id.equals("HEM-7600T"),"DIS padding removed");
        for(String id:new String[]{null,"","BP5360","Omron Evolv","HEM-7380T1","HEM-7196T","HEM-7196T1","HEM-7196T1-FLE","HEM-7196T1-FLEO","M4 Connect AFib","HEM-7155TUNKNOWN","Other HEM-7155T","HEM-7155T-UNKNOWN","HEM-7377T1-ZAZ-extra","HEM-7600T\u0000ignored"})
            check(VendorMemoryModel.identify(id)==null,"unverified identity must not select a memory map: "+id);
    }

    private static void verifiedRegionalAliases() {
        for(String alias:new String[]{"HEM-7150T-Z","HEM-7150T-CA"})
            alias(alias,"HEM-7150T",hex("694e43d9269da6030000000000003f00"),new int[]{0x98},960,1);
        for(String alias:new String[]{"HEM-7155T-EBK","HEM-7155T-D","HEM-7155T_ESL","HEM-7155T-ESL","HEM-7155T_AP","HEM-7155T-AP"})
            alias(alias,"HEM-7155T",hex("694e43d9265da603000000000000ff00"),new int[]{0x98,0x458},960,4);
        for(String alias:new String[]{"HEM-7342T-Z","HEM-7342T-CA"})
            alias(alias,"HEM-7342T",hex("694e43d9269da6030000000000003f00"),new int[]{0x98,0x6d8},1600,1);
        for(String alias:new String[]{"HEM-7361T-EBK","HEM-7361T_ESL","HEM-7361T-E","HEM-7361T-D","HEM-7361T-AP"})
            alias(alias,"HEM-7361T",hex("694e43d9265da603000000000000ff00"),new int[]{0x98,0x6d8},1600,4);
        for(String alias:new String[]{"HEM-7530T-Z","HEM-7530T_E3","HEM-7530T-E3","HEM-7530T_J3","HEM-7530T-J3","HEM-7530T_AP3","HEM-7530T-AP3","HEM-7530T_JT3"})
            alias(alias,"HEM-7530T",hex("4e69d9439d2603a600000000003f"),new int[]{0x2e8},1260,1);
        // Nearby identities have either contradictory fields/maps, different security,
        // or no exact identity evidence. None may inherit a map through a prefix.
        for(String denied:new String[]{"HEM-7155T_K4-D","HEM-7155T_K4-EBK","HEM-7155T_K4-ESL","HEM-7155T-K4-EBK","HEM-7156T_AP","HEM-7156T-AP","HEM-7156T_AAP","HEM-7156T-AAP","HEM-7322T-D","HEM-7322T-E","HEM-7600T-Z","HEM-7600T-E","HEM-7600T-ZCD6BK","HEM-7600T2-JF","HEM-6232T-AP","HEM-6232T-D","HEM-6232T-E","HEM-6232T-Z","HEM-7196T-ESL","HEM-7342T1-ACACD6","HEM-7361T1-BS","HEM-7530T1-BR3","HEM-7150T-Z-encrypted","HEM-7155T-EBK-extra","HEM-7361T-EBK-extra","HEM-7530T-E3-extra","HEM-7530T-JT3"})
            check(VendorMemoryModel.identify(denied)==null,"unverified or incompatible regional variant denied: "+denied);
    }

    private static void alias(String alias,String canonical,byte[] raw,int[] starts,int length,int status) {
        VendorMemoryModel m=model(alias);
        check(m==model(canonical),"regional identity uses the verified canonical map: "+alias);
        check(m.legacyUnlock,"regional identity keeps evidenced legacy transport: "+alias);
        List<VendorMemoryModel.Range> ranges=m.historyRanges();
        check(ranges.size()==starts.length,"regional bank count: "+alias);
        for(int i=0;i<starts.length;i++) {
            VendorMemoryModel.Range r=ranges.get(i);
            check(r.start==starts[i] && r.length==length && r.user==i+1,"regional range: "+alias);
            reading(m.parse(raw,r.user),2025,i+1,status);
        }
    }

    private static void recordFormats() {
        // 130/78, pulse 67, 2025-07-09 06:14:38. The two bytes 1d26 are
        // month/day/hour; 03a6 is minute/second. Logical byte 14 (16-byte) or 12 (14-byte)
        // is the additive checksum; the remaining bytes are metadata.
        byte[] little=hex("694e4319261da603000000000000ff00");
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T"}) {
            reading(model(id).parse(little,1),2025,1,0);
            reading(model(id).parse(hex("694e43d926dda6030000000000007f00"),1),2025,1,5);
        }
        for(String id:new String[]{"HEM-7322T","HEM-7600T"}) {
            reading(model(id).parse(hex("4e6919431d2603a60000000000ff"),1),2025,1,0);
            reading(model(id).parse(hex("4e69d943dd2603a600000000007f"),1),2217,1,5);
        }
        reading(model("HEM-7530T").parse(hex("4e69d9439d2603a600000000003f"),1),2025,1,1);
        reading(model("HEM-6232T").parse(hex("4e69d9439d2603a600000000003f"),2),2025,2,4);
        reading(model("HEM-6232T").parse(hex("4e69d9435d2603a60000000000ff"),1),2025,1,1);
        for(String id:new String[]{"HEM-7150T","HEM-7322T","HEM-7530T","HEM-6232T"}) {
            VendorMemoryModel m=model(id);
            byte[] invalidSecond=m.recordBytes==16?hex("694e4319261dbf030000000000001800"):hex("4e6919431d2603bf000000000018");
            check(m.parse(invalidSecond,1).time==null,"seconds63 must remain unavailable: "+id);
            byte[] invalidMonth=m.recordBytes==16?hex("694e43192601a603000000000000e300"):hex("4e691943012603a60000000000e3");
            check(m.parse(invalidMonth,1).time==null,"invalid calendar preserved without inventing date: "+id);
        }
    }

    private static void rejectsMalformedRecords() {
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7322T","HEM-7342T","HEM-7361T","HEM-7377T1","HEM-7530T","HEM-7600T","HEM-6232T"}) {
            VendorMemoryModel m=model(id);
            byte[] erased=new byte[m.recordBytes]; Arrays.fill(erased,(byte)255);
            check(m.parse(erased,1)==null,"only erased slot omitted: "+id);
            rejects(() -> m.parse(null,1));
            rejects(() -> m.parse(new byte[m.recordBytes-1],1));
            rejects(() -> m.parse(new byte[m.recordBytes+1],1));
            rejects(() -> m.parse(erased,0));
            rejects(() -> m.parse(erased,m.userCount+1));
            rejects(() -> m.parse(new byte[m.recordBytes],1));
            byte[] bad=m.recordBytes==16?hex("694e0019261da603000000000001bd00"):hex("4e6919001d2603a60000000000bc");
            rejects(() -> m.parse(bad,1));
            byte[] inverted=m.recordBytes==16?hex("01504319261da6030000000000019a00"):hex("500119431d2603a6000000000099");
            rejects(() -> m.parse(inverted,1));
        }
    }

    private static void recordChecksums() {
        // 16-byte: fixture record of hem-7155t user 2 is valid (checksum dd at byte 14)
        check(model("HEM-7155T").parse(hex("5f50461927208007000001000000dd00"),2).systolic==120,"valid 16-byte checksum accepted");
        rejects(() -> model("HEM-7155T").parse(hex("5f50461927208007000001000000de00"),2)); // byte 14 wrong by one
        // 14-byte wire record of hem-6232t user 1 (pairs swapped on the wire, checksum 00 at logical byte 12)
        check(model("HEM-6232T").parse(hex("4e6919431d2603a6000000010000"),1).diastolic==78,"valid 14-byte checksum accepted");
        rejects(() -> model("HEM-6232T").parse(hex("4e6919431d2603a600000001ffff"),1)); // last pair carries the checksum on the wire
        check(VendorMemoryModel.checksum(hex("694e4319261da6030000010000"),12)==0x00,"checksum helper");
        check(Arrays.equals(model("HEM-6232T").logical(hex("4e69")),hex("694e")),"14-byte wire pairs un-swapped");
        check(Arrays.equals(model("HEM-7155T").logical(hex("694e")),hex("694e")),"16-byte maps unchanged");
        // erased slots are still null, never checksum-rejected
        check(model("HEM-6232T").parse(hex("ffffffffffffffffffffffffffff"),1)==null,"erased 14-byte slot");
        // Models without sandbox checksum evidence accept any checksum byte.
        check(!model("HEM-7322T").recordChecksum && !model("HEM-7377T1").recordChecksum && model("HEM-7600T").recordChecksum,"checksum only where evidenced");
        reading(model("HEM-7322T").parse(hex("4e6919431d2603a60000000000fe"),1),2025,1,0);
        reading(model("HEM-7377T1").parse(hex("694e4319261da6030001000000010200"),2),2025,1,0);
        rejects(() -> model("HEM-7600T").parse(hex("4e6919431d2603a60000000000fe"),1));
    }

    private static void bp5360Attribution() {
        // The upstream BP5360 map has no checksum evidence; its records keep field checks only.
        VendorMemoryModel m=model("HEM-7377T1-ZAZ");
        reading(m.parse(hex("694e4319261da6030001000000010000"),2),2025,1,0);
        reading(m.parse(hex("694e4319261da603ffffffffffffffff"),2),2025,2,0);
        check(m.parse(hex("694e4319261dbf030001000000010000"),2).time==null,"BP5360 valid record with missing seconds keeps unavailable time");
        rejects(() -> m.parse(hex("694e4319261da6030001000000000000"),2));
        rejects(() -> m.parse(hex("694e4313261da6030001000000010000"),1));
        rejects(() -> m.parse(hex("694e43192601a6030001000000010000"),1));
        rejects(() -> m.parse(hex("ff4e4319261da6030001000000010000"),2));
    }

    private static void map(String id,int recordBytes,int settingsStart,int settingsLength,boolean unlock,int[] starts,int[] lengths) {
        VendorMemoryModel m=model(id);
        check(m.id.equals(id) && m.recordBytes==recordBytes && m.userCount==starts.length,"model-specific format: "+id);
        check(m.settingsStart==settingsStart && m.settingsLength==settingsLength && m.legacyUnlock==unlock,"model-specific read-only settings/transport: "+id);
        List<VendorMemoryModel.Range> ranges=m.historyRanges();
        check(ranges.size()==starts.length,"bank count: "+id);
        for(int i=0;i<starts.length;i++) {
            VendorMemoryModel.Range r=ranges.get(i);
            check(r.start==starts[i] && r.length==lengths[i] && r.user==i+1,"bank address/length/user: "+id);
        }
    }
    private static void reading(BloodPressure bp,int year,int user,int status) {
        check(bp!=null && bp.systolic==130 && bp.diastolic==78 && bp.pulse==67,"pressure/pulse byte order");
        check(bp.time.equals(LocalDateTime.of(year,7,9,6,14,38)),"packed date and model-specific year");
        check(bp.userId==user && bp.status==status,"actual user and movement/IHB attribution");
        check(bp.atrialFibrillation==null && Double.isNaN(bp.meanArterialPressure) && !bp.kpa,"unavailable AFib/MAP not invented");
    }
    private static VendorMemoryModel model(String id) {
        VendorMemoryModel m=VendorMemoryModel.identify(id);
        if(m==null) throw new AssertionError("Expected supported model: "+id);
        return m;
    }
    private static byte[] hex(String text) { return java.util.HexFormat.of().parseHex(text); }
    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void rejects(Runnable call) {
        boolean rejected=false; try { call.run(); } catch(IllegalArgumentException expected) { rejected=true; }
        check(rejected,"malformed slot must be rejected");
    }
}
