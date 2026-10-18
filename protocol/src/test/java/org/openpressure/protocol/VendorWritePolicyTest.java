package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.openpressure.protocol.fixture.WlpDescribe;
import java.util.UUID;

public final class VendorWritePolicyTest {
    private static int checks;
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static void main(String[] args) { System.out.println("Vendor write policy checks: "+run()); }
    public static int run() {
        checks=0;
        writeAspects();
        vendorClockBlock();
        x7ClockBlock();
        describeClockFrames();
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
    private static void writeAspects() {
        DeviceWritePolicy.requireWriteAspect("HEM-7155T",WriteAspectIndex.Aspect.CLOCK_BLOCK,true); checks++;
        rejects(() -> DeviceWritePolicy.requireWriteAspect("HEM-7155T",WriteAspectIndex.Aspect.CLOCK_BLOCK,false));
        rejects(() -> DeviceWritePolicy.requireWriteAspect("HEM-7322T",WriteAspectIndex.Aspect.CLOCK_BLOCK,true));
        rejects(() -> DeviceWritePolicy.requireWriteAspect("HEM-7155T",WriteAspectIndex.Aspect.UNSENT_COUNTERS,true));
        rejects(() -> DeviceWritePolicy.requireWriteAspect(null,WriteAspectIndex.Aspect.CLOCK_BLOCK,true));
        try { DeviceWritePolicy.requireWriteAspect("HEM-7322T",WriteAspectIndex.Aspect.CLOCK_BLOCK,true); throw new AssertionError("blocked aspect accepted"); }
        catch(IllegalArgumentException expected) { if(!"Blocked device write: aspect not permitted for this model".equals(expected.getMessage())) throw new AssertionError("aspect rejection message"); checks++; }
        // A passed aspect gate authorizes nothing by itself: addressed writes on the legacy TX stay blocked.
        VendorMemoryModel model=VendorMemoryModel.identify("HEM-7155T");
        DeviceWritePolicy.requireWriteAspect("HEM-7155T",WriteAspectIndex.Aspect.CLOCK_BLOCK,true); checks++;
        reject(model,hex("0801c00098100041"));
        reject(model,hex("0801c000080200c3"));
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
    private static final LocalDateTime TARGET=LocalDateTime.of(2026,10,16,14,30,45);
    private static byte[] ff(int length) { byte[] b=new byte[length]; Arrays.fill(b,(byte)0xff); return b; }
    private static VendorClockWrite authorized(String identity,boolean override,int channels) {
        // An all-zero block has a valid checksum on every layout (an erased one is refused on X7).
        VendorClockWrite plan=new VendorClockWrite(identity,new byte[ClockBlock.forIdentity(identity).imageLength()],TARGET,override,channels);
        plan.authorize();
        return plan;
    }
    private static void vendorClockBlock() {
        VendorMemoryModel model=VendorMemoryModel.identify("HEM-7155T"),other=VendorMemoryModel.identify("HEM-7156T");
        UUID service=VendorWlpSession.service(model),unlock=VendorWlpSession.unlockCharacteristic();
        List<UUID> tx=VendorWlpSession.txChannels(model);
        VendorClockWrite plan=authorized("HEM-7155T",true,tx.size());
        List<byte[]> chunks=plan.chunks();
        if(chunks.size()<2) throw new AssertionError("HEM-7155T clock frame spans two channels");
        byte[] c0=chunks.get(0),c1=chunks.get(1);
        // Every rejection below happens before the permit is consumed.
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),c1,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(1),c1,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(1),c0,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,unlock,c0,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,VendorWlpSession.rxChannels(model).get(0),c0,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,UUID.randomUUID(),tx.get(0),c0,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),null,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),c0,null));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(null,service,tx.get(0),c0,plan));
        // A plan made for one model never authorizes a frame on another, even with the same service.
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(other,VendorWlpSession.service(other),VendorWlpSession.txChannels(other).get(0),c0,plan));
        // A read frame is not a clock chunk.
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),Wlp.read(model.settingsStart,16),plan));
        check(plan.state()==VendorClockWrite.State.AUTHORIZED,"rejected writes consume no permit");
        DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),c0,plan); checks++;
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),c0,plan));
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),c1,plan));
        DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(1),c1,plan); checks++;
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(1),c1,plan));
        // UNTESTED without this request's override: blocked even with a valid chunk, permit untouched.
        VendorClockWrite plain=authorized("HEM-7155T",false,tx.size());
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),plain.chunks().get(0),plain));
        check(plain.state()==VendorClockWrite.State.AUTHORIZED,"aspect gate runs before the permit");
        // An unauthorized preview grants nothing.
        VendorClockWrite preview=new VendorClockWrite("HEM-7155T",ff(ClockBlock.forIdentity("HEM-7155T").imageLength()),TARGET,true,tx.size());
        rejects(() -> DeviceWritePolicy.validateVendorClockBlock(model,service,tx.get(0),preview.chunks().get(0),preview));
        // HEM-7322T has no clock layout: no plan exists and the aspect gate stays shut.
        rejects(() -> new VendorClockWrite("HEM-7322T",new byte[38],TARGET,true,4));
        rejects(() -> DeviceWritePolicy.requireWriteAspect("HEM-7322T",WriteAspectIndex.Aspect.CLOCK_BLOCK,true));
        // The read-path validator keeps rejecting every addressed write.
        VendorClockWrite fresh=authorized("HEM-7155T",true,tx.size());
        for(byte[] chunk:fresh.chunks()) for(UUID channel:tx)
            rejects(() -> DeviceWritePolicy.validateVendorCharacteristic(model,service,channel,chunk));
        check(fresh.state()==VendorClockWrite.State.AUTHORIZED,"read validator never touches the plan");
    }
    private static void x7ClockBlock() {
        UUID alias=UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb"),
            wlpTx=UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b"),
            handshake=UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b");
        VendorClockWrite plan=authorized("HEM-7380T1-EOSL",true,1);
        byte[] frame=plan.frame();
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.VENDOR_MEMORY,alias,wlpTx,frame,plan));
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_SECURE_WLP,alias,wlpTx,frame,plan));
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(null,alias,wlpTx,frame,plan));
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,handshake,frame,plan));
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,UUID.randomUUID(),wlpTx,frame,plan));
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,frame,null));
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,hex("0801000098100081"),plan));
        rejects(() -> DeviceWritePolicy.validateCharacteristic(ProtocolProfile.X7_WLP,alias,wlpTx,frame));
        check(plan.state()==VendorClockWrite.State.AUTHORIZED,"rejected X7 writes consume no permit");
        DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,frame,plan); checks++;
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,frame,plan));
        // The untested EBK still needs the override; the gate runs before the permit is consumed.
        VendorClockWrite plain=authorized("HEM-7380T1-EBK",false,1);
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,plain.frame(),plain));
        check(plain.state()==VendorClockWrite.State.AUTHORIZED,"X7 aspect gate runs before the permit");
        // The confirmed EOSL passes without an override, once.
        VendorClockWrite confirmed=authorized("HEM-7380T1-EOSL",false,1);
        DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,confirmed.frame(),confirmed); checks++;
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,confirmed.frame(),confirmed));
        // A legacy-map plan never authorizes its first chunk on the X7 transport.
        VendorClockWrite legacy=authorized("HEM-7155T",true,4);
        rejects(() -> DeviceWritePolicy.validateX7ClockBlock(ProtocolProfile.X7_WLP,alias,wlpTx,legacy.chunks().get(0),legacy));
        check(legacy.state()==VendorClockWrite.State.AUTHORIZED,"legacy plan untouched by the X7 validator");
    }
    private static void describeClockFrames() {
        check(WlpDescribe.describe(hex("1801c0008010a0b1c2d302e5f6071a0a100e1e2e589900a0")).startsWith("write addr=0x000080 len=16"),"clock frame described as write");
        check(WlpDescribe.describe(hex("0881c00054180005")).startsWith("write-reply"),"clock acknowledgment described as write reply");
        check(WlpDescribe.describe(hex("0801000098100081")).startsWith("read addr=0x000098"),"read frame unchanged");
    }
    private static void check(boolean condition,String name) { checks++; if(!condition) throw new AssertionError(name); }
    private static void allow(VendorMemoryModel model,byte[] frame) { DeviceWritePolicy.validateVendorCharacteristic(model,VendorWlpSession.service(model),VendorWlpSession.txChannels(model).get(0),frame); checks++; }
    private static void reject(VendorMemoryModel model,byte[] frame) { rejects(() -> allow(model,frame)); }
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("unsafe vendor write accepted"); }
}
