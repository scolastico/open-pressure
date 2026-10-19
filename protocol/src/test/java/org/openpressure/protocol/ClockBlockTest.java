package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;

/** Pure byte arithmetic on settings images; nothing here sends a byte. */
public final class ClockBlockTest {
    private static int checks;
    private static final LocalDateTime FIXTURE_PLUS_ONE=LocalDateTime.of(2026,1,2,3,4,6);
    public static void main(String[] args) { System.out.println("Clock block checks: "+run()); }
    public static int run() {
        checks=0;
        table();
        decode();
        checksum();
        update();
        hem7600t();
        return checks;
    }

    private static void table() {
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T"})
            layout(ClockBlock.forIdentity(id),0x80,44,16,52,58,false,true,68,id);
        for(String id:new String[]{"HEM-7380T1-EOSL","HEM-7380T1-EBK","HEM-7196T1-FLE","HEM-7196T1-FLEO"}) layout(ClockBlock.forIdentity(id),0x80,44,16,52,58,false,false,68,id);
        layout(ClockBlock.forIdentity("HEM-6232T"),0x2D0,44,16,52,58,true,true,68,"HEM-6232T");
        layout(ClockBlock.forIdentity("HEM-7530T"),0x2D0,44,16,52,58,true,false,68,"HEM-7530T");
        layout(ClockBlock.forIdentity("HEM-7600T"),0x29A,20,10,22,28,true,false,38,"HEM-7600T");
        for(String id:new String[]{"HEM-7322T","HEM-7377T1","HEM-7156T-AAP","HEM-7155T-EBK","HEM-7380T1","HEM-7196T1","M4 Connect AFib","",null})
            check(ClockBlock.forIdentity(id)==null,"no clock layout: "+id);
    }

    private static void layout(ClockBlock b,int address,int offset,int length,int clock,int sum,boolean swapped,boolean erased,int image,String id) {
        check(b!=null && b.writeAddress==address && b.imageOffset==offset && b.length==length && b.clockOffset==clock
            && b.checksumOffset==sum && b.swapped==swapped && b.erasedAllowed==erased && b.imageLength()==image,"layout: "+id);
    }

    private static void decode() {
        ClockBlock b=ClockBlock.forIdentity("HEM-7156T");
        byte[] image=image(68,44,"ffffffffffffffff1a0102030406" + "22ff");
        check(FIXTURE_PLUS_ONE.equals(b.clock(image)),"fixture clock decodes");
        check(Arrays.equals(b.block(image),hex("ffffffffffffffff1a010203040622ff")),"block copies image 44..59");
        byte[] bad=image.clone(); bad[53]=13;
        check(b.clock(bad)==null,"month 13 rejected");
        bad=image.clone(); bad[53]=0; check(b.clock(bad)==null,"month 0 rejected");
        bad=image.clone(); bad[53]=2; bad[54]=30; check(b.clock(bad)==null,"nonexistent date rejected");
        bad=image.clone(); bad[55]=24; check(b.clock(bad)==null,"hour 24 rejected");
        bad=image.clone(); bad[56]=60; check(b.clock(bad)==null,"minute 60 rejected");
        bad=image.clone(); bad[57]=60; check(b.clock(bad)==null,"second 60 rejected");
        bad=image.clone(); bad[54]=0; check(b.clock(bad)==null,"day 0 rejected");
        byte[] ffClock=image(68,44,"0000000000000000ffffffffffff" + "0000");
        check(b.clock(ffClock)==null && !b.erased(ffClock),"erased clock alone is not an erased block");
        check(b.erased(image(68,44,"ffffffffffffffffffffffffffffffff")),"16 x FF is erased");
        check(!b.erased(image),"populated block is not erased");
        rejects(() -> b.block(new byte[67]));
        rejects(() -> b.clock(null));
    }

    private static void checksum() {
        ClockBlock b=ClockBlock.forIdentity("HEM-7156T");
        byte[] image=image(68,44,"ffffffffffffffff1a01020304062" + "2ff");
        check(b.checksumValid(image),"fixture checksum valid");
        image[58]^=1;
        check(!b.checksumValid(image),"flipped checksum invalid");
        byte[] last=image(68,44,"ffffffffffffffff1a010203040622" + "00");
        check(b.checksumValid(last),"reserved last byte is outside the checksum");
    }

    private static void update() {
        ClockBlock b=ClockBlock.forIdentity("HEM-7156T");
        byte[] image=new byte[68]; Arrays.fill(image,(byte)0xff);
        byte[] before=image.clone();
        byte[] block=b.updated(image,FIXTURE_PLUS_ONE);
        check(Arrays.equals(block,hex("ffffffffffffffff1a010203040622ff")),"updated fixture block");
        check(Arrays.equals(image,before),"input image untouched");
        byte[] mixed=new byte[68]; for(int i=0;i<68;i++) mixed[i]=(byte)(i*7+3);
        byte[] out=b.updated(mixed,LocalDateTime.of(2050,12,31,23,59,59));
        check(out.length==16,"block length");
        for(int i=0;i<8;i++) check(out[i]==mixed[44+i],"prefix byte unchanged: "+i);
        check(out[15]==mixed[59],"last byte unchanged");
        byte[] after=mixed.clone(); System.arraycopy(out,0,after,44,16);
        check(b.checksumValid(after) && LocalDateTime.of(2050,12,31,23,59,59).equals(b.clock(after)),"updated block round-trips");
        rejects(() -> b.updated(new byte[60],FIXTURE_PLUS_ONE));
        rejects(() -> b.updated(new byte[68],null));
        rejects(() -> b.updated(new byte[68],LocalDateTime.of(1999,12,31,0,0,0)));
        rejects(() -> b.updated(new byte[68],LocalDateTime.of(2256,1,1,0,0,0)));
    }

    private static void hem7600t() {
        ClockBlock b=ClockBlock.forIdentity("HEM-7600T");
        // Omron fixture write at 0x29a, wire 0000011a03020604002a, un-swapped to logical order.
        byte[] wire=hex("0000011a03020604002a");
        byte[] logical=new byte[38]; Arrays.fill(logical,(byte)0x55);
        for(int i=0;i<10;i+=2) { logical[20+i]=wire[i+1]; logical[21+i]=wire[i]; }
        check(Arrays.equals(b.block(logical),hex("00001a01020304062a00")),"block of 10 from image 20");
        check(FIXTURE_PLUS_ONE.equals(b.clock(logical)) && b.checksumValid(logical),"fixture clock and checksum 20..27 into 28");
        logical[28]^=1; check(!b.checksumValid(logical),"flipped checksum invalid");
        byte[] blank=new byte[38]; Arrays.fill(blank,(byte)0xff); blank[29]=0x11;
        byte[] out=b.updated(blank,FIXTURE_PLUS_ONE);
        check(Arrays.equals(out,hex("ffff1a010203040628" + "11")),"updated 7600 block keeps its last byte");
        rejects(() -> b.block(new byte[68]));
        // HEM-7530T fixture write at 0x2d0, wire 0000000000000000011a03020604002a, un-swapped.
        ClockBlock wide=ClockBlock.forIdentity("HEM-7530T");
        byte[] wire16=hex("0000000000000000011a03020604002a"), image=new byte[68];
        for(int i=0;i<16;i+=2) { image[44+i]=wire16[i+1]; image[45+i]=wire16[i]; }
        check(FIXTURE_PLUS_ONE.equals(wide.clock(image)) && wide.checksumValid(image),"HEM-7530T fixture block in logical order");
        check(Arrays.equals(wide.updated(new byte[68],FIXTURE_PLUS_ONE),wide.block(image)),"HEM-7530T updated block matches Omron's logical bytes");
    }

    private static byte[] image(int length,int offset,String block) {
        byte[] image=new byte[length]; byte[] bytes=hex(block);
        System.arraycopy(bytes,0,image,offset,bytes.length);
        return image;
    }

    private static byte[] hex(String text) {
        byte[] out=new byte[text.length()/2];
        for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return out;
    }

    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(RuntimeException expected) { return; } throw new AssertionError("expected rejection"); }
}
