package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** Synthetic images only; every expected frame is spelled out byte for byte. */
public final class VendorClockWriteTest {
    private static int checks;
    private static final LocalDateTime TARGET=LocalDateTime.of(2026,1,2,3,4,5);
    private static final String FRAME_7156="1801c0008010ffffffffffffffff1a010203040622ff008c";

    public static void main(String[] args) { System.out.println("Vendor clock write checks: "+run()); }
    public static int run() {
        checks=0;
        legacyFrameAndChunks();
        compactAcknowledgment();
        echoAcknowledgment();
        rejectedAcknowledgments();
        swappedLayouts();
        x7Frame();
        threshold();
        subSecondTarget();
        preconditions();
        lifecycle();
        return checks;
    }

    private static byte[] ff(int length) { byte[] b=new byte[length]; Arrays.fill(b,(byte)0xff); return b; }
    private static VendorClockWrite plan7156() { return new VendorClockWrite("HEM-7156T",ff(68),TARGET,false,4); }

    private static void legacyFrameAndChunks() {
        VendorClockWrite plan=plan7156();
        check(plan.state()==VendorClockWrite.State.PREVIEW,"starts in preview");
        check(!plan.writesNothing(),"erased block writes");
        check(plan.monitorClock()==null && plan.driftSeconds()==Long.MAX_VALUE,"erased clock is unset");
        check(TARGET.equals(plan.target()) && TARGET.plusSeconds(1).equals(plan.written()),"written is target + 1 s");
        check("HEM-7156T".equals(plan.identity()) && !plan.override(),"identity and override kept");
        same(FRAME_7156,plan.frame());
        check(xor(plan.frame())==0,"frame XOR closes to zero");
        List<byte[]> chunks=plan.chunks();
        check(chunks.size()==2,"two legacy chunks");
        same(FRAME_7156.substring(0,32),chunks.get(0));
        same(FRAME_7156.substring(32),chunks.get(1));
        check(!plan.allowsChunk(0,chunks.get(0)),"nothing permitted before authorization");
        plan.authorize();
        check(plan.state()==VendorClockWrite.State.AUTHORIZED,"authorized");
        check(!plan.allowsChunk(1,chunks.get(1)),"chunk 1 not before chunk 0 was permitted");
        check(!plan.allowsChunk(1,chunks.get(0)) && !plan.allowsChunk(0,chunks.get(1)),"chunk bound to its channel");
        byte[] altered=chunks.get(0).clone(); altered[10]^=1;
        check(!plan.allowsChunk(0,altered) && !plan.allowsChunk(0,null) && !plan.allowsChunk(-1,chunks.get(0)),"exact bytes only");
        check(plan.state()==VendorClockWrite.State.AUTHORIZED,"refused checks consume nothing");
        check(plan.allowsChunk(0,chunks.get(0)),"chunk 0 on channel 0");
        check(plan.state()==VendorClockWrite.State.WRITING,"writing once chunk 0 was permitted");
        check(!plan.allowsChunk(0,chunks.get(0)),"chunk 0 permitted only once");
        check(plan.allowsChunk(1,chunks.get(1)),"chunk 1 after chunk 0");
        check(!plan.allowsChunk(1,chunks.get(1)) && !plan.allowsChunk(0,chunks.get(0)),"nothing after the last chunk");
        byte[] frame=plan.frame(); frame[0]=0;
        same(FRAME_7156,plan.frame());
    }

    private static VendorClockWrite sent7156() {
        VendorClockWrite plan=plan7156();
        plan.authorize();
        List<byte[]> chunks=plan.chunks();
        check(plan.allowsChunk(0,chunks.get(0)) && plan.allowsChunk(1,chunks.get(1)),"both chunks permitted");
        return plan;
    }

    private static void compactAcknowledgment() {
        VendorClockWrite plan=sent7156();
        check(plan.accept(0,closed("0881c000801000")),"compact acknowledgment completes");
        check(plan.complete() && plan.state()==VendorClockWrite.State.ACKNOWLEDGED,"acknowledged");
        rejects(plan::authorize);
        rejects(plan::frame);
        rejects(() -> plan.accept(0,closed("0881c000801000")));
        check(!plan.allowsChunk(0,plan7156().chunks().get(0)),"no permit after acknowledgment");
        plan.cancel();
        check(plan.complete() && plan.state()==VendorClockWrite.State.ACKNOWLEDGED,"cancel after completion keeps the outcome");
        VendorClockWrite early=plan7156(); early.authorize(); early.allowsChunk(0,early.chunks().get(0));
        rejects(() -> early.accept(0,closed("0881c000801000")));
        check(early.state()==VendorClockWrite.State.REJECTED && !early.complete(),"acknowledgment before the last chunk rejected");
    }

    private static void echoAcknowledgment() {
        byte[] echo=hex(FRAME_7156); echo[1]=(byte)0x81; fix(echo);
        VendorClockWrite plan=sent7156();
        check(!plan.accept(1,Arrays.copyOfRange(echo,16,24)),"RX1 alone waits for RX0");
        check(plan.accept(0,Arrays.copyOfRange(echo,0,16)),"echo split over RX0/RX1 completes");
        check(plan.complete(),"echo acknowledged");
        VendorClockWrite ordered=sent7156();
        check(!ordered.accept(0,Arrays.copyOfRange(echo,0,16)),"RX0 alone waits for RX1");
        check(ordered.accept(1,Arrays.copyOfRange(echo,16,24)),"then complete");
    }

    private static void rejectedAcknowledgments() {
        byte[] echo=hex(FRAME_7156); echo[1]=(byte)0x81;
        byte[] data=echo.clone(); data[12]^=1; fix(data);
        expectRejected(data,"changed data byte");
        byte[] status=echo.clone(); status[22]=1; fix(status);
        expectRejected(status,"non-zero status in echo");
        expectRejected(closed("0881c000801001"),"non-zero compact status");
        expectRejected(closed("0801c000801000"),"compact without 0x81");
        expectRejected(closed("0881c000811000"),"wrong address echo");
        expectRejected(closed("0881c000800f00"),"wrong count echo");
        byte[] bcc=closed("0881c000801000"); bcc[7]^=1;
        expectRejected(bcc,"bad XOR");
        expectRejected(closed("0981c00080100000"),"unexpected declared length");
        byte[] echoFixed=echo.clone(); fix(echoFixed);
        VendorClockWrite plan=sent7156();
        rejects(() -> plan.accept(2,Arrays.copyOfRange(echoFixed,0,16)));
        check(plan.state()==VendorClockWrite.State.REJECTED,"channel 2 without channel 0..1 frame rejected");
        VendorClockWrite twice=sent7156();
        check(!twice.accept(1,Arrays.copyOfRange(echoFixed,16,24)),"waiting");
        rejects(() -> twice.accept(1,Arrays.copyOfRange(echoFixed,16,24)));
        VendorClockWrite empty=sent7156();
        rejects(() -> empty.accept(0,new byte[0]));
        VendorClockWrite tooLong=sent7156();
        rejects(() -> tooLong.accept(0,new byte[17]));
    }

    private static void expectRejected(byte[] ack,String message) {
        VendorClockWrite plan=sent7156();
        boolean thrown=false;
        try { plan.accept(0,Arrays.copyOfRange(ack,0,Math.min(16,ack.length))); if(ack.length>16) plan.accept(1,Arrays.copyOfRange(ack,16,ack.length)); }
        catch(IllegalArgumentException expected) { thrown=true; }
        check(thrown,"rejected: "+message);
        check(plan.state()==VendorClockWrite.State.REJECTED && !plan.complete(),"state rejected: "+message);
        check(!plan.allowsChunk(0,plan7156().chunks().get(0)),"no permit after rejection: "+message);
        rejects(plan::authorize);
    }

    private static void swappedLayouts() {
        // Omron fixture write at 0x2d0 (wire order); a zero block is valid (checksum 0).
        VendorClockWrite wide=new VendorClockWrite("HEM-7530T",new byte[68],TARGET,false,4);
        byte[] frame=wide.frame();
        same("1801c002d010"+"0000000000000000011a03020604002a"+"00"+hexByte(xor(Arrays.copyOf(frame,23))),frame);
        check(xor(frame)==0,"7530 XOR");
        // An image in wire order is un-swapped before decoding: wire pairs 1a01 0302 0604 -> logical 2026-...
        byte[] wireImage=new byte[68];
        byte[] wireBlock=hex("0000000000000000011a03020604002a");
        System.arraycopy(wireBlock,0,wireImage,44,16);
        VendorClockWrite read=new VendorClockWrite("HEM-7530T",wireImage,TARGET.plusSeconds(1),false,4);
        check(TARGET.plusSeconds(1).equals(read.monitorClock()) && read.driftSeconds()==0 && read.writesNothing(),"wire image un-swapped before decoding");
        check(!new VendorClockWrite("HEM-6232T",new byte[68],TARGET,false,4).writesNothing(),"6232 zero block writes");
        same("1801c002d010"+"0000000000000000011a03020604002a"+"00"+hexByte(xor(Arrays.copyOf(frame,23))),
            new VendorClockWrite("HEM-6232T",new byte[68],TARGET,false,4).frame());
        VendorClockWrite small=new VendorClockWrite("HEM-7600T",new byte[38],TARGET,false,4);
        byte[] f=small.frame();
        check(f.length==18,"7600 frame is 18 bytes");
        same("1201c0029a0a"+"0000011a03020604002a"+"00"+hexByte(xor(Arrays.copyOf(f,17))),f);
        check(small.chunks().size()==2 && small.chunks().get(1).length==2,"7600 chunks 16 + 2");
        byte[] reserved=new byte[38]; reserved[29]=0x11; // wire[29] is logical[28], the checksum
        rejects(() -> new VendorClockWrite("HEM-7600T",reserved,TARGET,false,4));
        byte[] keep=new byte[38]; keep[28]=0x11; // wire[28] -> logical[29], the reserved byte
        byte[] k=new VendorClockWrite("HEM-7600T",keep,TARGET,false,4).frame();
        check(k[6+8]==0x11 && k[6+9]==0x2a,"reserved byte survives the round trip in wire position");
    }

    private static void x7Frame() {
        byte[] preimage=hex("63000b00038002000102030405060708090a0b0c0d0e0f105a0611a5123456789abc6203095afedcba987654a0b1c2d302e5f60718090a0b0c0d19992132435465768798");
        LocalDateTime now=LocalDateTime.of(2026,10,16,14,30,45);
        VendorClockWrite plan=new VendorClockWrite("HEM-7380T1-EOSL",preimage,now,true,1);
        // X7Registration's block-3 frame with byte 48 left at 02 (no user bit): data and checksum differ by one bit each.
        same("1801c0008010a0b1c2d302e5f6071a0a100e1e2e589900a0",plan.frame());
        check(plan.chunks().size()==1 && Arrays.equals(plan.chunks().get(0),plan.frame()),"X7 sends one chunk");
        check(plan.override(),"override kept");
        byte[] withBit=preimage.clone(); withBit[48]|=1; withBit[58]=(byte)(withBit[58]+1);
        X7Registration registration=new X7Registration("HEM-7380T1-EOSL",withBit,1,now);
        registration.authorize();
        registration.command();
        registration.accept(hex("0881c00054180005"));
        check(Arrays.equals(registration.command(),new VendorClockWrite("HEM-7380T1-EOSL",withBit,now,true,1).frame()),"identical to X7Registration block-3 frame");
        VendorClockWrite alias=new VendorClockWrite("x7 smart afib",preimage,now,true,1);
        check("HEM-7380T1-EOSL".equals(alias.identity()),"firmware name resolves");
        same("1801c0008010a0b1c2d302e5f6071a0a100e1e2e589900a0",alias.frame());
        alias.authorize();
        check(alias.allowsChunk(0,alias.frame()) && !alias.allowsChunk(1,alias.frame()),"X7 single channel");
        rejects(() -> alias.accept(1,closed("0881c000801000")));
        VendorClockWrite x7=new VendorClockWrite("HEM-7380T1-EOSL",preimage,now,true,1);
        x7.authorize(); check(x7.allowsChunk(0,x7.frame()),"X7 permit");
        check(x7.accept(0,closed("0881c000801000")),"X7 compact ack");
        VendorClockWrite echo=new VendorClockWrite("HEM-7380T1-EOSL",preimage,now,true,1);
        echo.authorize(); check(echo.allowsChunk(0,echo.frame()),"X7 permit");
        byte[] reply=echo.frame(); reply[1]=(byte)0x81; fix(reply);
        check(echo.accept(0,reply),"X7 full echo in one notification");
        VendorClockWrite split=new VendorClockWrite("HEM-7380T1-EOSL",preimage,now,true,1);
        split.authorize(); check(split.allowsChunk(0,split.frame()),"X7 permit");
        rejects(() -> split.accept(0,Arrays.copyOf(reply,16)));
        rejects(() -> new VendorClockWrite("HEM-7380T1-EOSL",preimage,now,true,4));
        rejects(() -> new VendorClockWrite("HEM-7156T",ff(68),TARGET,false,1));
        rejects(() -> new VendorClockWrite("HEM-7156T",ff(68),TARGET,false,2));
    }

    private static byte[] withClock(String identity,LocalDateTime clock) {
        ClockBlock block=ClockBlock.forIdentity(identity);
        byte[] image=new byte[block.imageLength()];
        System.arraycopy(block.updated(image,clock),0,image,block.imageOffset,block.length);
        return image;
    }

    private static void threshold() {
        VendorClockWrite near=new VendorClockWrite("HEM-7156T",withClock("HEM-7156T",TARGET.plusSeconds(60)),TARGET,false,4);
        check(near.driftSeconds()==60 && near.writesNothing(),"60 s ahead writes nothing");
        rejects(near::authorize);
        check(near.state()==VendorClockWrite.State.PREVIEW,"still preview");
        VendorClockWrite behind=new VendorClockWrite("HEM-7156T",withClock("HEM-7156T",TARGET.minusSeconds(60)),TARGET,false,4);
        check(behind.driftSeconds()==-60 && behind.writesNothing(),"60 s behind writes nothing");
        VendorClockWrite far=new VendorClockWrite("HEM-7156T",withClock("HEM-7156T",TARGET.plusSeconds(61)),TARGET,false,4);
        check(far.driftSeconds()==61 && !far.writesNothing(),"61 s writes");
        far.authorize();
        // Review Focus 2: an unset (FF) clock with a valid checksum is undecodable and proceeds.
        byte[] unset=withClock("HEM-7156T",TARGET);
        for(int i=52;i<58;i++) unset[i]=(byte)0xff;
        int sum=0; for(int i=44;i<58;i++) sum+=unset[i]&255; unset[58]=(byte)sum;
        VendorClockWrite blank=new VendorClockWrite("HEM-7156T",unset,TARGET,false,4);
        check(blank.monitorClock()==null && blank.driftSeconds()==Long.MAX_VALUE && !blank.writesNothing(),"unset clock proceeds");
        blank.authorize();
        // Review Focus 3: a developer target one hour off a monitor clock equal to phone time writes the chosen time.
        LocalDateTime phone=LocalDateTime.of(2026,5,6,7,8,9), chosen=phone.plusHours(1);
        VendorClockWrite dev=new VendorClockWrite("HEM-7156T",withClock("HEM-7156T",phone),chosen,true,4);
        check(dev.driftSeconds()==-3600 && !dev.writesNothing() && chosen.plusSeconds(1).equals(dev.written()),"chosen target written");
        byte[] f=dev.frame();
        check(Arrays.equals(Arrays.copyOfRange(f,14,20),hex("1a050608080a")),"frame carries chosen + 1 s");
    }

    private static void subSecondTarget() {
        LocalDateTime fine=TARGET.withNano(999_000_000);
        VendorClockWrite plan=new VendorClockWrite("HEM-7156T",ff(68),fine,false,4);
        check(TARGET.equals(plan.target()) && TARGET.plusSeconds(1).equals(plan.written()),"target truncated to whole seconds");
        same(FRAME_7156,plan.frame());
        VendorClockWrite ahead=new VendorClockWrite("HEM-7156T",withClock("HEM-7156T",TARGET.minusSeconds(60)),fine,false,4);
        check(ahead.driftSeconds()==-60 && ahead.writesNothing(),"sub-second target keeps a symmetric threshold");
        VendorClockWrite behind=new VendorClockWrite("HEM-7156T",withClock("HEM-7156T",TARGET.plusSeconds(60)),TARGET.withNano(1),false,4);
        check(behind.driftSeconds()==60 && behind.writesNothing(),"sub-second target, monitor ahead");
        // An acknowledgment right after the last permit is accepted without any other call.
        VendorClockWrite x7=new VendorClockWrite("HEM-7380T1-EOSL",withClock("HEM-7380T1-EOSL",TARGET.minusDays(3)),fine,true,1);
        x7.authorize();
        check(x7.allowsChunk(0,x7.frame()) && x7.accept(0,closed("0881c000801000")),"acknowledged right after the permit");
    }

    private static void preconditions() {
        byte[] corrupt=withClock("HEM-7156T",TARGET); corrupt[58]^=1;
        boolean unreadable=false;
        try { new VendorClockWrite("HEM-7156T",corrupt,TARGET,false,4); }
        catch(IllegalArgumentException e) { unreadable="monitor settings unreadable".equals(e.getMessage()); }
        check(unreadable,"invalid checksum and not erased");
        // An erased block 3 is written only where Omron accepts one (the legacy 16-byte maps and HEM-6232T).
        for(String id:new String[]{"HEM-7380T1-EOSL","HEM-7380T1-EBK","HEM-7196T1-FLE","HEM-7196T1-FLEO","HEM-7530T","HEM-7600T"}) {
            int length=ClockBlock.forIdentity(id).imageLength(), channels=X7Model.identify(id)!=null?1:4;
            boolean refused=false;
            try { new VendorClockWrite(id,ff(length),TARGET,false,channels); }
            catch(IllegalArgumentException e) { refused="monitor settings unreadable".equals(e.getMessage()); }
            check(refused,"erased block refused: "+id);
        }
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T","HEM-6232T"})
            check(!new VendorClockWrite(id,ff(68),TARGET,false,4).writesNothing(),"erased block accepted: "+id);
        // A target outside 2024..2050 is its own failure, not unreadable settings.
        boolean range=false;
        try { new VendorClockWrite("HEM-7156T",ff(68),LocalDateTime.of(2051,1,1,0,0,0),false,4); }
        catch(VendorClockWrite.TargetOutOfRange e) { range=true; }
        check(range,"target out of range has its own exception");
        range=false;
        try { new VendorClockWrite("HEM-7156T",ff(68),LocalDateTime.MAX,false,4); }
        catch(VendorClockWrite.TargetOutOfRange e) { range=true; }
        check(range,"overflowing target is out of range");
        boolean notRange=true;
        try { new VendorClockWrite("HEM-7156T",corrupt,TARGET,false,4); }
        catch(VendorClockWrite.TargetOutOfRange e) { notRange=false; }
        catch(IllegalArgumentException expected) { }
        check(notRange,"unreadable settings are not a range failure");
        rejects(() -> new VendorClockWrite("HEM-7156T",ff(68),LocalDateTime.of(2050,12,31,23,59,59),false,4));
        new VendorClockWrite("HEM-7156T",ff(68),LocalDateTime.of(2050,12,31,23,59,58),false,4);
        rejects(() -> new VendorClockWrite("HEM-7156T",ff(68),LocalDateTime.of(2023,12,31,23,59,58),false,4));
        new VendorClockWrite("HEM-7156T",ff(68),LocalDateTime.of(2023,12,31,23,59,59),false,4);
        rejects(() -> new VendorClockWrite("HEM-7156T",ff(68),LocalDateTime.MAX,false,4));
        rejects(() -> new VendorClockWrite("HEM-7322T",new byte[38],TARGET,false,4));
        rejects(() -> new VendorClockWrite("HEM-7380T1",new byte[68],TARGET,false,1));
        rejects(() -> new VendorClockWrite(null,ff(68),TARGET,false,4));
        rejects(() -> new VendorClockWrite("HEM-7156T",ff(67),TARGET,false,4));
        rejects(() -> new VendorClockWrite("HEM-7156T",null,TARGET,false,4));
        rejects(() -> new VendorClockWrite("HEM-7156T",ff(68),null,false,4));
        byte[] supplied=ff(68);
        VendorClockWrite plan=new VendorClockWrite("HEM-7156T",supplied,TARGET,false,4);
        supplied[44]=0;
        same(FRAME_7156,plan.frame());
    }

    private static void lifecycle() {
        VendorClockWrite plan=plan7156();
        plan.authorize();
        rejects(plan::authorize);
        byte[] chunk0=plan.chunks().get(0);
        check(plan.allowsChunk(0,chunk0),"chunk 0 permitted");
        // A failed GATT write leaves the plan writing with no retry; cancel() ends it.
        check(!plan.allowsChunk(0,chunk0) && plan.state()==VendorClockWrite.State.WRITING,"no retry of a consumed permit");
        plan.cancel();
        check(plan.state()==VendorClockWrite.State.CANCELLED && !plan.allowsChunk(1,plan7156().chunks().get(1)),"cancel ends a stalled write");
        VendorClockWrite cancelled=plan7156();
        cancelled.authorize();
        cancelled.cancel();
        check(cancelled.state()==VendorClockWrite.State.CANCELLED && !cancelled.complete(),"cancelled");
        check(!cancelled.allowsChunk(0,chunk0),"cancel revokes the permit");
        rejects(cancelled::authorize);
        rejects(() -> cancelled.accept(0,closed("0881c000801000")));
        check(cancelled.state()==VendorClockWrite.State.CANCELLED,"stale calls keep the cancelled outcome");
        rejects(cancelled::frame);
        VendorClockWrite unsent=plan7156();
        unsent.authorize();
        rejects(() -> unsent.accept(0,closed("0881c000801000")));
        check(unsent.state()==VendorClockWrite.State.REJECTED,"acknowledgment before any permit rejected");
        VendorClockWrite noAck=plan7156();
        rejects(() -> noAck.accept(0,closed("0881c000801000")));
        VendorClockWrite preview=plan7156();
        preview.cancel();
        rejects(preview::authorize);
    }

    /** Appends the closing XOR to {@code body}. */
    private static byte[] closed(String body) {
        byte[] b=hex(body), out=Arrays.copyOf(b,b.length+1);
        out[b.length]=(byte)xor(b);
        return out;
    }
    private static void fix(byte[] frame) { frame[frame.length-1]=(byte)xor(Arrays.copyOf(frame,frame.length-1)); }
    private static int xor(byte[] bytes) { int x=0; for(byte b:bytes) x^=b&255; return x; }
    private static String hexByte(int value) { return String.format("%02x",value&255); }
    private static void same(String expected,byte[] actual) { check(Arrays.equals(hex(expected),actual),"expected "+expected); }
    private static byte[] hex(String text) {
        byte[] out=new byte[text.length()/2];
        for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return out;
    }
    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(IllegalArgumentException expected) { return; } throw new AssertionError("expected rejection"); }
}
