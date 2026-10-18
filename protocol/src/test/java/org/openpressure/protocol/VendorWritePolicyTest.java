package org.openpressure.protocol;

import java.util.HexFormat;
import java.util.UUID;

public final class VendorWritePolicyTest {
    private static int checks;
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static void main(String[] args) { System.out.println("Vendor write policy checks: "+run()); }
    public static int run() {
        checks=0;
        standardClockOnly();
        VendorMemoryModel legacy=VendorMemoryModel.identify("HEM-7150T"),modern=VendorMemoryModel.identify("HEM-7377T1");
        UUID service=VendorWlpSession.service(legacy),tx=VendorWlpSession.txChannels(legacy).get(0),unlock=VendorWlpSession.unlockCharacteristic();
        allow(legacy,hex("0800000000100018")); allow(legacy,hex("0801000098100081")); allow(legacy,hex("080f000000000007"));
        for(VendorMemoryModel.Range range:legacy.historyRanges()) {
            allow(legacy,Wlp.read(range.start,1)); allow(legacy,Wlp.read(range.start+range.length-1,1));
            reject(legacy,Wlp.read(range.start-1,2)); reject(legacy,Wlp.read(range.start+range.length-1,2));
        }
        allow(legacy,Wlp.read(legacy.settingsStart,16));
        VendorMemoryModel two=VendorMemoryModel.identify("HEM-7155T");
        for(VendorMemoryModel.Range range:two.historyRanges()) {
            allow(two,Wlp.read(range.start,56)); allow(two,Wlp.read(range.start+range.length-56,56));
            reject(two,Wlp.read(range.start,57)); reject(two,Wlp.read(range.start+range.length-55,56));
        }
        allow(two,Wlp.read(two.settingsStart,56)); reject(two,Wlp.read(two.settingsStart+13,56));
        reject(modern,Wlp.read(modern.historyRanges().get(1).start,56)); allow(modern,Wlp.read(modern.historyRanges().get(1).start,16));
        allow(legacy,Wlp.read(legacy.settingsStart+legacy.settingsLength-1,1));
        reject(legacy,Wlp.read(legacy.settingsStart-1,2));
        reject(legacy,Wlp.read(legacy.settingsStart+legacy.settingsLength-1,2));
        // Explicitly identified models must use the model-bound entry point.
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.VENDOR_MEMORY,service,tx,hex("0800000000100018")));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.LEGACY_WLP,service,tx,hex("0800000000100018")));
        rejects(() -> DeviceWritePolicy.validateDescriptor(ProtocolProfile.VENDOR_MEMORY,service,VendorWlpSession.rxChannels(legacy).get(0),CCCD,new byte[]{1,0}));
        for(byte[] forbidden:new byte[][]{null,new byte[0],hex("08010000983900a8"),hex("0801000000100019"),hex("080100ffff100018"),hex("0801c00098100041"),hex("0800000000100019"),new byte[24]}) reject(legacy,forbidden);
        byte[] key=new byte[17];
        for(int op:new int[]{0,1,2}) {
            key[0]=(byte)op;
            final byte[] copy=key.clone();
            rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(legacy,service,unlock,copy));
            rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(modern,VendorWlpSession.service(modern),unlock,copy));
        }
        activeUnlockPermits(legacy,modern,service,unlock,tx);
        key[0]=2; key[1]=1; rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(legacy,service,unlock,key));
        key[0]=3; key[1]=0; rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(legacy,service,unlock,key));
        rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(legacy,service,unlock,new byte[16]));
        rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(legacy,VendorWlpSession.service(modern),tx,hex("0800000000100018")));
        rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(modern,service,tx,hex("0800000000100018")));
        rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(null,service,tx,hex("0800000000100018")));
        for(UUID channel:VendorWlpSession.txChannels(legacy).subList(1,4))
            rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(legacy,service,channel,hex("0800000000100018")));
        for(UUID channel:VendorWlpSession.rxChannels(legacy)) {
            DeviceWritePolicy.validateVendorDescriptor(legacy,service,channel,CCCD,new byte[]{1,0}); checks++;
            rejects(() -> DeviceWritePolicy.validateVendorDescriptor(legacy,service,channel,CCCD,new byte[]{2,0}));
        }
        DeviceWritePolicy.validateVendorDescriptor(legacy,service,unlock,CCCD,new byte[]{1,0}); checks++;
        DeviceWritePolicy.validateVendorDescriptor(modern,VendorWlpSession.service(modern),VendorWlpSession.rxChannels(modern).get(0),CCCD,new byte[]{1,0}); checks++;
        for(UUID channel:VendorWlpSession.rxChannels(legacy).subList(1,4))
            rejects(() -> DeviceWritePolicy.validateVendorDescriptor(modern,VendorWlpSession.service(modern),channel,CCCD,new byte[]{1,0}));
        rejects(() -> DeviceWritePolicy.validateVendorDescriptor(modern,VendorWlpSession.service(modern),unlock,CCCD,new byte[]{1,0}));
        rejects(() -> DeviceWritePolicy.validateVendorDescriptor(legacy,service,tx,CCCD,new byte[]{1,0}));
        rejects(() -> DeviceWritePolicy.validateVendorDescriptor(legacy,service,unlock,UUID.randomUUID(),new byte[]{1,0}));
        rejects(() -> DeviceWritePolicy.validateVendorDescriptor(legacy,service,unlock,CCCD,null));
        return checks;
    }
    private static void standardClockOnly() {
        VendorMemoryModel model=VendorMemoryModel.identify("HEM-7156T");
        UUID cts=UUID.fromString("00001805-0000-1000-8000-00805f9b34fb"),
            time=UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb"),
            command=UUID.fromString("00005045-0000-1000-8000-00805f9b34fb"),
            date=UUID.fromString("00002a08-0000-1000-8000-00805f9b34fb");
        byte[] current=hex("ea070a100e1e2d050001"),local=hex("ea070a100e1e2d");
        DeviceWritePolicy.validateVendorClock(model,cts,time,current); checks++;
        DeviceWritePolicy.validateVendorClock(model,command,date,local); checks++;
        rejects(() -> DeviceWritePolicy.validateVendorClock(null,cts,time,current));
        rejects(() -> DeviceWritePolicy.validateVendorClock(model,cts,time,local));
        rejects(() -> DeviceWritePolicy.validateVendorClock(model,cts,date,local));
        rejects(() -> DeviceWritePolicy.validateVendorClock(model,command,time,current));
        rejects(() -> DeviceWritePolicy.validateVendorClock(model,command,date,hex("ea07021e0e1e2d")));
        rejects(() -> DeviceWritePolicy.validateVendorClock(model,VendorWlpSession.service(model),VendorWlpSession.txChannels(model).get(0),hex("1801c0008010000000000000000000000000000000000000")));
        rejects(() -> DeviceWritePolicy.validateVendorClock(model,UUID.fromString("0000181c-0000-1000-8000-00805f9b34fb"),UUID.fromString("00002a9f-0000-1000-8000-00805f9b34fb"),hex("40010e02")));
    }
    private static void activeUnlockPermits(VendorMemoryModel legacy,VendorMemoryModel modern,UUID service,UUID unlock,UUID tx) {
        byte[] key=hex("00112233445566778899aabbccddeeff");
        LegacyUnlock connect=LegacyUnlock.connect(key);
        byte[] login=hex("0100112233445566778899aabbccddeeff"), program=hex("0000112233445566778899aabbccddeeff"), enter=hex("0200000000000000000000000000000000");
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,connect));
        connect.command();
        DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,connect); checks++;
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,program,connect));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,enter,connect));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,hex("0101112233445566778899aabbccddeeff"),connect));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,null));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(modern,VendorWlpSession.service(modern),unlock,login,connect));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,VendorWlpSession.service(modern),unlock,login,connect));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,tx,login,connect));
        connect.accept(hex("8100"));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,connect));
        LegacyUnlock pair=LegacyUnlock.pair(key); pair.command();
        DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,enter,pair); checks++;
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,program,pair));
        pair.accept(hex("8200"));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,enter,pair));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,program,pair));
        pair.command(); DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,program,pair); checks++;
        rejects(() -> pair.accept(hex("8200")));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,program,pair));
        LegacyUnlock disconnected=LegacyUnlock.connect(key); disconnected.command(); disconnected.close();
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,disconnected));
        LegacyUnlock finalUnlock=LegacyUnlock.pair(key); finalUnlock.command(); finalUnlock.accept(hex("8200"));
        finalUnlock.command(); finalUnlock.accept(hex("8000"));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,finalUnlock));
        finalUnlock.command();
        DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,finalUnlock); checks++;
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,program,finalUnlock));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,enter,finalUnlock));
        finalUnlock.accept(hex("8100"));
        rejects(() -> DeviceWritePolicy.validateVendorUnlock(legacy,service,unlock,login,finalUnlock));
    }
    private static void allow(VendorMemoryModel model,byte[] frame) { DeviceWritePolicy.validateVendorCharacteristic(model,VendorWlpSession.service(model),VendorWlpSession.txChannels(model).get(0),frame); checks++; }
    private static void reject(VendorMemoryModel model,byte[] frame) { rejects(() -> allow(model,frame)); }
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("unsafe vendor write accepted"); }
}
