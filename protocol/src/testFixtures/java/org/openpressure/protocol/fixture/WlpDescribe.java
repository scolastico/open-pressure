package org.openpressure.protocol.fixture;

import java.util.HexFormat;

/** Human-readable field breakdown of legacy unlock, WLP and X7 system-channel frames for replay reports. */
public final class WlpDescribe {
    private WlpDescribe() {}
    public static String describe(byte[] frame) {
        if(frame==null) return "none";
        int n=frame.length;
        if(n==17 && (frame[0]&255)<=2) return "unlock cmd="+String.format("%02x",frame[0])+" key="+HexFormat.of().formatHex(frame,1,17);
        if(n==2 && (frame[0]&0xf0)==0x80) return "ack "+HexFormat.of().formatHex(frame);
        if(n>=8 && (frame[0]&255)==n) {
            byte bcc=0; for(int i=0;i<n-1;i++) bcc^=frame[i];
            int opcode=frame[1]&255,address=((frame[2]&255)<<16)|((frame[3]&255)<<8)|(frame[4]&255),length=frame[5]&255;
            String name;
            switch(opcode&0x7f) {
                case 0x00: name="start"; break;
                case 0x01: name=(frame[2]&0xc0)==0xc0?"write":"read"; break;
                case 0x0f: name="end"; break;
                default: name="opcode-"+String.format("%02x",opcode&0x7f);
            }
            if((address&0xc00000)!=0 && !name.equals("write") && (opcode&0x80)==0) name+="(write-flag)";
            String reply=(opcode&0x80)!=0?"-reply status="+String.format("%02x",frame[n-2]):"";
            return name+reply+" addr="+String.format("0x%06x",address&0x3fffff)+" len="+length+" bcc="+(bcc==frame[n-1]?"ok":"BAD");
        }
        return x7System(frame);
    }
    /** X7 SYS frames (docs/protocol.md): transaction nonce, application-pairing handshake, authorization poll, encrypted frames. */
    private static String x7System(byte[] frame) {
        int n=frame.length,op=n==0?-1:frame[0]&255;
        if(op==0x11 && n==20) return "x7-transaction nonce="+HexFormat.of().formatHex(frame,1,5);
        if(op==0x91 && n>=7) return "x7-transaction-reply status="+String.format("%02x",frame[1])+" nonce="+HexFormat.of().formatHex(frame,2,6)+" final="+String.format("%02x",frame[6]);
        if(op==0x70 && n>=2) return "x7-secure-handshake op="+String.format("%02x",frame[1])+" len="+n;
        if((op==0xf0 || op==0xff) && n>=2) return "x7-secure-handshake-reply op="+String.format("%02x",frame[1])+" len="+n;
        if(op==0x20 && n==20 && frame[1]==1) return "x7-authorization-poll";
        if(op==0xa0 && n>=4 && frame[1]==1) return "x7-authorization-reply state="+String.format("%02x",frame[3]);
        if(op==0xc0 && n>=13) return "x7-encrypted counter="+(((frame[1]&255)|(frame[2]&255)<<8|(frame[3]&255)<<16|(long)(frame[4]&255)<<24))+" len="+n;
        return "raw";
    }
}
