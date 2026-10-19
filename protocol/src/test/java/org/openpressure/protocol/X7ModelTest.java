package org.openpressure.protocol;

import java.util.Arrays;
import java.util.List;

/** Synthetic checks of the per-identity X7-family read maps; no hardware evidence is implied. */
public final class X7ModelTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("X7 model checks: "+run()); }
    public static int run() {
        checks=0;
        identities();
        maps();
        sixtySlotIndex();
        afibFlags();
        advertisements();
        return checks;
    }

    private static void identities() {
        check(X7Model.identify("HEM-7380T1-EOSL")==X7Model.HEM_7380T1_EOSL && X7Model.identify("HEM-7380T1-EBK")==X7Model.HEM_7380T1_EBK,"X7 identities");
        check(X7Model.identify("X7 smart afib\u0000")==X7Model.HEM_7380T1_EOSL && X7Model.identify("HEM-7380T1")==X7Model.HEM_7380T1,"X7 firmware name and base identity");
        check(X7Model.identify("HEM-7196T1-FLE")==X7Model.HEM_7196T1_FLE && X7Model.identify(" HEM-7196T1-FLEO\u0000")==X7Model.HEM_7196T1_FLEO,"exact HEM-7196T1 identities, NUL and whitespace stripped");
        check("M4 Connect AFib".equals(X7Model.HEM_7196T1_FLE.productName) && "X4 Connect AFib".equals(X7Model.HEM_7196T1_FLEO.productName),"catalog names are display names");
        for(String denied:new String[]{"HEM-7196T1","HEM-7196T","HEM-7196T1-FL","HEM-7196T1-FLE2","hem-7196t1-fle","HEM-7196T1_FLE","HEM-7188T1-LE",
                "M4 Connect","M4 Connect AFib","x4 connect afib","X4 Connect AFib+","M4 / X4","M4 Connect AFib HEM-7196T1","HEM-7381T1-AZ","HEM-7155T","BLEsmart_000000EB","",null})
            check(X7Model.identify(denied)==null,"denied identity: "+denied);
        check(X7Record.supportsModel("HEM-7196T1-FLE") && !X7Record.supportsModel("HEM-7196T1"),"record support follows the model table");
        check(VendorMemoryModel.identify("HEM-7196T1-FLE")==null && VendorMemoryModel.identify("HEM-7196T1-FLEO")==null,"7196T1 never reaches a legacy map");
    }

    private static void maps() {
        check(X7Model.HEM_7380T1_EOSL.recordsPerUser==100 && X7Model.HEM_7380T1_EOSL.bankStart(1)==0x1c4 && X7Model.HEM_7380T1_EOSL.bankStart(2)==0x804,"X7 banks");
        check(X7Model.HEM_7380T1_EBK.bankStart(2)==0x804 && X7Model.HEM_7380T1.bankStart(2)==0x804,"EBK and base share the X7 map");
        for(X7Model m:new X7Model[]{X7Model.HEM_7196T1_FLE,X7Model.HEM_7196T1_FLEO}) {
            check(m.recordsPerUser==60 && m.bankStart(1)==0x1c4 && m.bankStart(2)==0x584 && m.bankBytes()==960,"7196T1 banks: "+m.identity);
            check(m.containsReadRange(0x10,68) && m.containsReadRange(0x1c4,960) && m.containsReadRange(0x584,960),"settings image and both banks are readable: "+m.identity);
            check(!m.containsReadRange(0x584,961) && !m.containsReadRange(0x944,16) && !m.containsReadRange(0x934,32) && !m.containsReadRange(0x574,32) && !m.containsReadRange(0x0f,2),"reads outside or across banks are not: "+m.identity);
            check(m.afibMasksFlags && "HEM-7196T1".equals(m.mapName),"7196T1 family facts: "+m.identity);
        }
        check(!X7Model.HEM_7380T1_EOSL.afibMasksFlags && !X7Model.HEM_7380T1_EBK.afibMasksFlags,"X7 keeps raw flags");
        check(X7Model.HEM_7380T1_EOSL.containsReadRange(0x804,1600) && !X7Model.HEM_7380T1_EOSL.containsReadRange(0x804,1601),"X7 bank 2 bound");
        rejects(() -> X7Model.HEM_7196T1_FLE.bankStart(0)); rejects(() -> X7Model.HEM_7196T1_FLE.bankStart(3));
    }

    private static void sixtySlotIndex() {
        byte[] settings=new byte[68]; settings[0]=59; settings[2]=12;
        X7MemoryIndex index=X7MemoryIndex.parse(X7Model.HEM_7196T1_FLE,settings);
        check(index.model()==X7Model.HEM_7196T1_FLE && index.count(1)==59 && index.count(2)==12,"7196T1 pointers");
        List<X7MemoryIndex.Range> ranges=index.historyRanges();
        check(ranges.get(0).start==0x1c4 && ranges.get(0).length==59*16,"user 1 range");
        check(ranges.get(1).start==0x584 && ranges.get(1).length==12*16 && ranges.get(1).user==2,"user 2 starts at 0x584, never 0x804");
        check(index.historyRanges(2).get(0).start==0x584 && index.selectedCount(3)==71,"user 2 selection");
        byte[] full=new byte[68]; full[1]=0x40; full[3]=0x40;
        X7MemoryIndex fullIndex=X7MemoryIndex.parse(X7Model.HEM_7196T1_FLEO,full);
        check(fullIndex.count(1)==60 && fullIndex.count(2)==60,"full flag exposes sixty slots");
        for(X7MemoryIndex.Range range:fullIndex.historyRanges())
            check(X7Model.HEM_7196T1_FLEO.containsReadRange(range.start,range.length) && range.start+range.length<=0x944,"full ranges stay inside the 60-slot banks");
        for(int pointer=0;pointer<=100;pointer++) {
            byte[] image=new byte[68]; image[2]=(byte)pointer;
            if(pointer<=60) check(X7MemoryIndex.parse(X7Model.HEM_7196T1_FLE,image).count(2)==pointer,"7196T1 pointer "+pointer);
            else {
                rejects(() -> X7MemoryIndex.parse(X7Model.HEM_7196T1_FLE,image));
                check(X7MemoryIndex.parse(X7Model.HEM_7380T1_EOSL,image).count(2)==pointer,"the same pointer is valid only on the 100-slot map");
            }
        }
        rejects(() -> X7MemoryIndex.parse(null,new byte[68]));
    }

    private static void afibFlags() {
        // Synthetic record: 120/80/72, 2026-10-16 14:30:45, sequence 0x1234.
        byte[] raw=hex("5f50481a0e2aad070000341200000000");
        byte[] flagged=raw.clone(); flagged[5]|=(byte)0xc0;
        byte[] afib=flagged.clone(); afib[7]|=0x20;
        check(X7Record.parse(afib,1,X7Model.HEM_7380T1_EOSL).measurement.status==5,"X7 keeps irregular and movement with AFib");
        for(X7Model m:new X7Model[]{X7Model.HEM_7196T1_FLE,X7Model.HEM_7196T1_FLEO}) {
            BloodPressure masked=X7Record.parse(afib,2,m).measurement;
            check(masked.status==0 && Boolean.TRUE.equals(masked.atrialFibrillation) && masked.userId==2,"7196T1 masks irregular and movement when AFib is set: "+m.identity);
            BloodPressure kept=X7Record.parse(flagged,1,m).measurement;
            check(kept.status==5 && Boolean.FALSE.equals(kept.atrialFibrillation),"7196T1 keeps the flags without AFib: "+m.identity);
        }
        rejects(() -> X7Record.parse(raw,1,null));
    }

    private static void advertisements() {
        check(X7Model.advertised("BLEsmart_000000EB0011223344",null)==X7Model.HEM_7196T1_FLE,"FLE catalog name");
        check(X7Model.advertised("BLESmart_000000e1AABB",null)==X7Model.HEM_7196T1_FLEO,"FLEO catalog name, any case");
        check(X7Model.advertised("BLEsmart_000000D4",null)==X7Model.HEM_7380T1_EOSL && X7Model.advertised("BLEsmart_000000D3",null)==X7Model.HEM_7380T1_EBK,"X7 catalog names");
        check(X7Model.advertised("BLEsmart_000100EB",null)==null && X7Model.advertised("BLEsmart_0000057A",null)==null,"other categories and non-X7 models are not X7-family IDs");
        check(X7Model.advertised("BLEsmart_+00000EB",null)==null && X7Model.advertised("BLEsmart_00000",null)==null && X7Model.advertised(null,null)==null,"malformed names name nothing");
        // Type 8, one user: flags, reserved byte, category 0, model 0x00EB little endian, sequence, trailer.
        byte[] fle=hex("08000000eb0001000000"), eosl=hex("08000000d40001000000");
        check(X7Model.advertised(null,fle)==X7Model.HEM_7196T1_FLE && X7Model.advertised(null,eosl)==X7Model.HEM_7380T1_EOSL,"type 8 model IDs");
        byte[] type9=hex("0900000000e1000100");
        check(X7Model.advertised(null,type9)==X7Model.HEM_7196T1_FLEO,"type 9 model ID");
        check(X7Model.advertised(null,hex("0100010002"))==null && X7Model.advertised(null,hex("08010000eb"))==null,"plain and malformed payloads carry no model ID");
        check(X7Model.HEM_7196T1_FLE.contradictedBy("BLEsmart_000000D40011",null) && X7Model.HEM_7380T1_EOSL.contradictedBy(null,fle),"a different family contradicts");
        check(X7Model.HEM_7196T1_FLE.contradictedBy("BLEsmart_000000EB0011",eosl),"a contradicting payload is not hidden by a matching name");
        check(!X7Model.HEM_7196T1_FLE.contradictedBy("BLEsmart_000000E10011",fle) && !X7Model.HEM_7196T1_FLEO.contradictedBy(null,fle),"FLE and FLEO share one map");
        check(!X7Model.HEM_7196T1_FLE.contradictedBy("BLEsmart_0000057A",hex("0100010002")) && !X7Model.HEM_7380T1_EOSL.contradictedBy(null,null),"no X7-family ID, no contradiction");
        check(Arrays.equals(fle,hex("08000000eb0001000000")),"payload is not modified");
    }

    private static byte[] hex(String value) { return java.util.HexFormat.of().parseHex(value); }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid X7 model operation accepted");
    }
}
