package org.openpressure.protocol;

import java.util.Arrays;
import java.util.HexFormat;

public final class VendorWlpSessionTest {
    private static int checks;
    private static final byte[] START=hex("188000000010000102030405060708090a0b0c0d0e0f0088");
    public static void main(String[] args) { System.out.println("Vendor WLP checks: "+run()); }
    public static int run() {
        checks=0;
        orderedChannelsAndFullHistory();
        malformedResponsesNeverAdvance();
        modernSingleChannel();
        everyModelReadsEveryBank();
        failedReadAndEndStatuses();
        rejects(() -> new VendorWlpSession(null));
        rejects(() -> new VendorWlpSession(model("HEM-7150T"),0));
        rejects(() -> new VendorWlpSession(model("HEM-7150T"),17));
        check(VendorWlpSession.chunks(new byte[64],4).size()==4,"four channels bound a 64-byte frame");
        check(VendorWlpSession.chunks(new byte[24],1).get(0).length==24,"modern frame stays in one ATT notification");
        rejects(() -> VendorWlpSession.chunks(new byte[65],4));
        rejects(() -> VendorWlpSession.chunks(new byte[1],2));
        return checks;
    }
    private static void orderedChannelsAndFullHistory() {
        VendorWlpSession s=new VendorWlpSession(model("HEM-7150T"));
        same("0800000000100018",s.command());
        check(!s.accept(1,Arrays.copyOfRange(START,16,24)),"channel 1 waits for header");
        check(s.accept(0,Arrays.copyOf(START,16)),"channels concatenate by index");
        same("0801000098100081",s.command());
        check(s.received()==0,"identity bytes are not history");
        check(Arrays.equals(hex("000102030405060708090a0b0c0d0e0f"),s.identity()),"identity payload retained");
        int reads=0;
        while((s.command()[1]&255)==1) {
            byte[] command=s.command();
            check((command[2]&255)==0 && (command[5]&255)==16,"bounded read-only command");
            byte[] response=reply(command);
            check(!s.accept(0,Arrays.copyOf(response,16)),"first channel cannot fabricate missing bytes");
            byte[] padded=Arrays.copyOfRange(response,16,32); padded[15]=99;
            check(s.accept(1,padded),"last channel permits bytes beyond declared frame");
            reads++;
        }
        check(reads==60 && s.received()==960,"reads all 60 model slots");
        same("080f000000000007",s.command());
        check(s.accept(0,hex("088f000000000087")) && s.complete(),"END zero status completes");
        check(s.data().length==960,"complete data excludes session controls");
        byte[] data=s.data(); data[0]=99; check(s.data()[0]==0,"data is immutable to callers");
        rejects(s::command); rejects(() -> s.accept(0,hex("088f000000000087")));
    }
    private static void malformedResponsesNeverAdvance() {
        for(int offset:new int[]{1,2,3,4,5,22,23}) {
            VendorWlpSession s=new VendorWlpSession(model("HEM-7150T")); s.command();
            byte[] corrupt=START.clone(); corrupt[offset]^=1;
            if(offset!=23) corrupt[23]^=1;
            s.accept(0,Arrays.copyOf(corrupt,16));
            rejects(() -> s.accept(1,Arrays.copyOfRange(corrupt,16,24)));
            check(s.received()==0 && !s.complete(),"bad START cannot expose history"); rejects(s::command);
        }
        VendorWlpSession unsolicited=new VendorWlpSession(model("HEM-7150T"));
        rejects(() -> unsolicited.accept(0,Arrays.copyOf(START,16)));
        VendorWlpSession duplicate=new VendorWlpSession(model("HEM-7150T")); duplicate.command(); duplicate.accept(1,new byte[8]);
        rejects(() -> duplicate.accept(1,new byte[8])); rejects(duplicate::command);
        for(int channel:new int[]{-1,2,3,4}) {
            VendorWlpSession s=new VendorWlpSession(model("HEM-7150T")); s.command();
            s.accept(0,Arrays.copyOf(START,16));
            rejects(() -> s.accept(channel,new byte[8]));
        }
        for(byte[] fragment:new byte[][]{null,new byte[0],new byte[17],hex("07800000001000"),hex("41800000001000")}) {
            VendorWlpSession s=new VendorWlpSession(model("HEM-7150T")); s.command(); rejects(() -> s.accept(0,fragment));
        }
        VendorWlpSession shortRead=started(); byte[] command=shortRead.command(); byte[] missing=reply(command);
        missing[0]=8; missing=Arrays.copyOf(missing,8); fix(missing); final byte[] bad=missing;
        rejects(() -> shortRead.accept(0,bad)); check(shortRead.received()==0,"short read is never replaced with FF bytes");
        VendorWlpSession address=started(); command=address.command(); byte[] wrongAddress=reply(command); wrongAddress[4]^=1; fix(wrongAddress);
        address.accept(0,Arrays.copyOf(wrongAddress,16));
        rejects(() -> address.accept(1,Arrays.copyOfRange(wrongAddress,16,24)));
        VendorWlpSession truncated=new VendorWlpSession(model("HEM-7150T")); truncated.command(); truncated.accept(0,Arrays.copyOf(START,16));
        rejects(() -> truncated.accept(1,new byte[7]));
    }
    private static void modernSingleChannel() {
        VendorWlpSession s=new VendorWlpSession(model("HEM-7377T1"),8); s.command();
        check(s.accept(0,START),"modern START is a single complete ATT value");
        byte[] command=s.command(); check((command[5]&255)==8,"selected read block supports smaller replies");
        check(s.accept(0,reply(command)) && s.received()==8,"modern response uses one channel");
        s.command(); rejects(() -> s.accept(1,new byte[8]));
        VendorWlpSession partial=new VendorWlpSession(model("HEM-7377T1")); partial.command();
        rejects(() -> partial.accept(0,Arrays.copyOf(START,16)));
    }
    private static void everyModelReadsEveryBank() {
        String[] names={"HEM-7155T","HEM-7156T","HEM-7322T","HEM-7342T","HEM-7361T","HEM-7377T1","HEM-7530T","HEM-7600T","HEM-6232T"};
        int[] lengths={1920,1920,2800,3200,3200,3200,1260,1400,2800};
        int[] secondBank={0x458,0x458,0x824,0x6d8,0x6d8,0x80c,-1,-1,0x860};
        for(int i=0;i<names.length;i++) {
            VendorMemoryModel model=model(names[i]); VendorWlpSession s=new VendorWlpSession(model);
            s.command(); acceptFrame(s,model,START); boolean sawSecond=false;
            while((s.command()[1]&255)==1) {
                byte[] command=s.command();
                DeviceWritePolicy.validateVendorCharacteristic(model,VendorWlpSession.service(model),VendorWlpSession.txChannels(model).get(0),command);
                int address=((command[3]&255)<<8)|(command[4]&255);
                if(address==secondBank[i]) sawSecond=true;
                check((command[5]&255)<=16,"all models keep reads bounded");
                acceptFrame(s,model,reply(command));
            }
            check(s.received()==lengths[i],"all banks read for "+names[i]);
            check(secondBank[i]<0 || sawSecond,"second bank reached for "+names[i]);
            acceptFrame(s,model,hex("088f000000000087")); check(s.complete(),"model END validated");
        }
    }
    private static void failedReadAndEndStatuses() {
        for(int offset:new int[]{1,2,3,4,5,22,23}) {
            VendorWlpSession s=started(); byte[] response=reply(s.command()); response[offset]^=1;
            if(offset!=23) fix(response);
            s.accept(0,Arrays.copyOf(response,16));
            rejects(() -> s.accept(1,Arrays.copyOfRange(response,16,24)));
            check(s.received()==0,"incorrect read header, status or BCC never adds bytes");
        }
        for(int offset:new int[]{1,2,3,4,5,6,7}) {
            VendorMemoryModel model=model("HEM-7150T"); VendorWlpSession s=started();
            while((s.command()[1]&255)==1) acceptFrame(s,model,reply(s.command()));
            byte[] end=hex("088f000000000087"); end[offset]^=1; if(offset!=7) fix(end);
            rejects(() -> s.accept(0,end)); check(!s.complete(),"invalid END cannot establish session success");
        }
    }
    private static void acceptFrame(VendorWlpSession session,VendorMemoryModel model,byte[] frame) {
        if(!model.legacyUnlock) { check(session.accept(0,frame),"modern complete response advances"); return; }
        int parts=(frame.length+15)/16;
        for(int i=0;i<parts;i++) check(session.accept(i,Arrays.copyOfRange(frame,i*16,Math.min((i+1)*16,frame.length)))==(i==parts-1),"legacy response advances only at complete frame");
    }
    private static VendorWlpSession started() {
        VendorWlpSession s=new VendorWlpSession(model("HEM-7150T")); s.command();
        s.accept(0,Arrays.copyOf(START,16)); s.accept(1,Arrays.copyOfRange(START,16,24)); return s;
    }
    private static VendorMemoryModel model(String name) { VendorMemoryModel m=VendorMemoryModel.identify(name); if(m==null) throw new AssertionError("missing model "+name); return m; }
    /** Independent peer fixture: response header echoes requested address and count. */
    private static byte[] reply(byte[] request) {
        int n=request[5]&255; byte[] r=new byte[n+8]; r[0]=(byte)r.length; r[1]=(byte)0x81;
        System.arraycopy(request,2,r,2,4); for(int i=0;i<n;i++) r[i+6]=(byte)i; fix(r); return r;
    }
    private static void fix(byte[] bytes) { bytes[bytes.length-1]=0; for(int i=0;i<bytes.length-1;i++) bytes[bytes.length-1]^=bytes[i]; }
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
    private static void same(String s,byte[] v) { check(Arrays.equals(hex(s),v),"unexpected wire command"); }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("invalid WLP accepted"); }
}
