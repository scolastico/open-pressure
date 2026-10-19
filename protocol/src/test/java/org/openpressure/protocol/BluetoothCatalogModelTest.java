package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** Independently constructed records and exact bounds for the validated Bluetooth catalog additions. */
public final class BluetoothCatalogModelTest {
    private static int checks;
    private static final String[] LEGACY={"HEM-7151T-Z","HEM-7340T-Z","HEM-7340T-CA","HEM-7341T-Z","HEM-7343T-Z","HEM-6161T-E","HEM-6232T-D"};
    public static int run() {
        checks=0;
        legacyMaps(); legacyRecords(); deniedIdentities(); singleChannelMaps();
        return checks;
    }
    private static void legacyMaps() {
        int[][] starts={{0x98},{0x98,0x458},{0x98,0x458},{0x98,0x458},{0x98,0x6d8},{0x2e8},{0x2e8,0x860}};
        int[] capacities={80,60,60,60,100,30,100};
        for(int i=0;i<LEGACY.length;i++) {
            VendorMemoryModel m=VendorMemoryModel.identify(LEGACY[i]);
            check(m!=null,"new exact legacy identity "+LEGACY[i]);
            check(m.capacity()==capacities[i] && m.userCount==starts[i].length && m.selectableUsers()==starts[i].length,"capacity/users "+LEGACY[i]);
            check(m.recordBytes==(i<5?16:14) && m.settingsStart==(i<5?0x10:0x260) && m.settingsLength==68,"record/index layout "+LEGACY[i]);
            check(m.legacyUnlock && m.recordChecksum && m.indexSupported(),"legacy transport and integrity "+LEGACY[i]);
            List<VendorMemoryModel.Range> ranges=m.historyRanges();
            for(int u=0;u<ranges.size();u++) {
                VendorMemoryModel.Range r=ranges.get(u);
                check(r.start==starts[i][u] && r.length==capacities[i]*m.recordBytes && r.user==u+1,"bank "+LEGACY[i]+"/"+u);
                new VendorWlpSession(m,List.of(r),16);
                rejects(() -> new VendorWlpSession(m,List.of(VendorMemoryModel.range(r.start+r.length-1,2,r.user)),16));
            }
            for(WriteAspectIndex.Aspect aspect:WriteAspectIndex.Aspect.values()) {
                String identity=WriteAspectIndex.resolve(LEGACY[i]);
                check(!WriteAspectIndex.permits(WriteAspectIndex.lookup(identity,aspect).status,true),"no write override "+LEGACY[i]+"/"+aspect);
            }
            byte[] index=new byte[68]; index[0]=(byte)capacities[i];
            index=wire(index,m.recordBytes==14);
            check(VendorMemoryIndex.parse(m,index).populated(1)==capacities[i],"index uses exact capacity "+LEGACY[i]);
        }
    }
    private static void legacyRecords() {
        for(int i=0;i<LEGACY.length;i++) {
            VendorMemoryModel m=VendorMemoryModel.identify(LEGACY[i]);
            byte[] logical=hex("694e43d926dda6030000010000000000");
            logical=Arrays.copyOf(logical,m.recordBytes); logical[m.recordBytes-2]=(byte)VendorMemoryModel.checksum(logical,m.recordBytes-2);
            byte[] raw=wire(logical,m.recordBytes==14);
            BloodPressure bp=m.parse(raw,1);
            check(bp.systolic==130 && bp.diastolic==78 && bp.pulse==67,"decode pressure/pulse "+m.id);
            check(LocalDateTime.of(2025,7,9,6,14,38).equals(bp.time),"six-bit year, logical byte order "+m.id);
            check(bp.status==4 && bp.atrialFibrillation==null,"source masks movement or lacks it; never invent AFib "+m.id);
            raw[0]++; byte[] invalid=raw;
            rejects(() -> m.parse(invalid,1));
            byte[] erased=new byte[m.recordBytes]; Arrays.fill(erased,(byte)255);
            check(m.parse(erased,1)==null,"erased record "+m.id);
        }
    }
    private static void deniedIdentities() {
        for(String name:new String[]{"HEM-7151T","HEM-7151T-CA","HEM-7340T","HEM-7340T_K4-Z","HEM-7341T","HEM-7343T","HEM-6161T","HEM-6161T-D","HEM-6232T-E","BP5250","BP7350","RS3 Intelli IT","hem-7151t-z","HEM-7151T-Z-extra"})
            check(VendorMemoryModel.identify(name)==null,"unknown identity "+name);
    }
    private static void singleChannelMaps() {
        String[] identities={"HEM-7143T1-E","HEM-7143T1-D","HEM-7143T2-E","HEM-7188T1-LE","HEM-7188T1-LEO"};
        for(int i=0;i<identities.length;i++) {
            X7Model m=X7Model.identify(identities[i]);
            check(m!=null,"new exact single-channel identity "+identities[i]);
            int start=i<3?0x260:0x10, bank=i<3?0x2e8:0x1c4, size=i<3?14:16;
            check(m.bankStart(1)==bank && m.bankBytes()==30*size && m.recordsPerUser==30,"one 30-slot bank "+m.identity);
            check(m.containsReadRange(start,68) && m.containsReadRange(bank,size*30),"model-bound index/bank "+m.identity);
            check(!m.containsReadRange(bank+size*30,1) && !m.containsReadRange(bank-1,2) && !m.containsReadRange(0x804,16),"no adjacent or phantom bank "+m.identity);
            rejects(() -> m.bankStart(2));
            java.util.UUID service=java.util.UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb");
            java.util.UUID tx=java.util.UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b");
            DeviceWritePolicy.validateX7Characteristic(ProtocolProfile.X7_WLP,m,service,tx,Wlp.read(start,16));
            DeviceWritePolicy.validateX7Characteristic(ProtocolProfile.X7_SECURE_WLP,m,service,tx,Wlp.read(bank,size));
            rejects(() -> DeviceWritePolicy.validateX7Characteristic(ProtocolProfile.X7_WLP,m,service,tx,Wlp.read(0x804,16)));
            rejects(() -> DeviceWritePolicy.validateX7Characteristic(ProtocolProfile.X7_SECURE_WLP,m,service,tx,Wlp.read(bank+size*30-1,2)));
            rejects(() -> DeviceWritePolicy.validateX7Characteristic(ProtocolProfile.X7_WLP,null,service,tx,Wlp.read(bank,size)));
            rejects(() -> DeviceWritePolicy.validateX7Characteristic(ProtocolProfile.STANDARD_BP,m,service,tx,Wlp.read(bank,size)));
            byte[] settings=new byte[68]; settings[0]=2; settings[2]=(byte)255;
            X7MemoryIndex index=X7MemoryIndex.parse(m,settings);
            check(index.count(1)==2 && index.historyRanges().size()==1 && index.historyRanges().get(0).length==2*size,"one-user index ignores absent user fields "+m.identity);
            check(index.historyRanges(2).size()==1 && index.historyRanges(2).get(0).user==1,"old user-2 preference falls back to available user "+m.identity);
            rejects(() -> index.count(2));
            settings[0]=31; rejects(() -> X7MemoryIndex.parse(m,settings));
            byte[] raw=Arrays.copyOf(hex("694e43d926dda6230000010000000000"),size);
            raw[size-2]=(byte)VendorMemoryModel.checksum(raw,size-2);
            BloodPressure bp=X7Record.parse(raw,1,m).measurement;
            check(bp.systolic==130 && bp.diastolic==78 && bp.pulse==67 && bp.status==5,"unswapped single-channel record "+m.identity);
            check(bp.time.getYear()==(i<3?2025:2217) && bp.atrialFibrillation==null,"model-specific year and absent AFib "+m.identity);
            rejects(() -> X7Record.parse(raw,2,m));
            raw[size-2]++; rejects(() -> X7Record.parse(raw,1,m));
            for(WriteAspectIndex.Aspect aspect:WriteAspectIndex.Aspect.values())
                check(!WriteAspectIndex.permits(WriteAspectIndex.lookup(WriteAspectIndex.resolve(m.identity),aspect).status,true),"no new settings writes "+m.identity);
        }
        for(String denied:new String[]{"HEM-7143T1","HEM-7143T2","HEM-7188T1","M2+ Connect","X2+ Connect","M2 Intelli IT","HEM-7188T1-LE-extra","HEM-7143T1-EBK"})
            check(X7Model.identify(denied)==null,"single-channel identity stays exact "+denied);
    }
    static byte[] wire(byte[] logical,boolean swapped) {
        byte[] raw=logical.clone();
        if(swapped) for(int i=0;i+1<raw.length;i+=2) {byte v=raw[i];raw[i]=raw[i+1];raw[i+1]=v;}
        return raw;
    }
    static byte[] hex(String s) { return java.util.HexFormat.of().parseHex(s); }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void rejects(Runnable call) { checks++; try { call.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("invalid operation accepted"); }
}
