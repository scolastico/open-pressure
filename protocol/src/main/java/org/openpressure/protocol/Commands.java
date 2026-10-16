package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;

public final class Commands {
    private Commands() {}
    public static byte[] dateTime(LocalDateTime time) {
        if(time.getYear()<1582 || time.getYear()>9999) throw new IllegalArgumentException("Bluetooth Date Time year must be 1582–9999");
        return new byte[]{(byte)time.getYear(),(byte)(time.getYear()>>8),(byte)time.getMonthValue(),(byte)time.getDayOfMonth(),(byte)time.getHour(),(byte)time.getMinute(),(byte)time.getSecond()};
    }
    public static byte[] currentTime(LocalDateTime time) {
        byte[] bytes=Arrays.copyOf(dateTime(time),10);
        bytes[7]=(byte)time.getDayOfWeek().getValue(); bytes[8]=0; bytes[9]=1;
        return bytes;
    }
    public static LocalDateTime readDateTime(byte[] bytes) { return new Packet(bytes).dateTime(); }
    public static byte[] countAll() { return new byte[]{4,1}; }
    public static byte[] reportAll() { return new byte[]{1,1}; }
    public static byte[] latestSequence() { return new byte[]{0x10,0}; }
    public static byte[] reportFrom(int sequence) { return filtered(1,sequence); }
    public static byte[] countFrom(int sequence) { return filtered(4,sequence); }
    private static byte[] filtered(int operation, int sequence) {
        if (sequence<0 || sequence>65535) throw new IllegalArgumentException("Sequence must be 0–65535");
        return new byte[]{(byte)operation,3,1,(byte)sequence,(byte)(sequence>>8)};
    }
    public static byte[] consent(int user,int code) { return userCommand(2,user,code); }
    public static byte[] registerUser(int user,int code) { return userCommand(0x40,user,code); }
    private static byte[] userCommand(int opcode,int user,int code) {
        if (user<1 || user>254 || code<0 || code>9999) throw new IllegalArgumentException("Invalid user or consent code");
        return new byte[]{(byte)opcode,(byte)user,(byte)code,(byte)(code>>8)};
    }
    public static RacpResponse racp(byte[] bytes) {
        Packet p=new Packet(bytes);
        int opcode=p.u8(), operator=p.u8();
        if (operator!=0 || bytes.length!=4) throw new IllegalArgumentException("Invalid RACP response");
        if (opcode==5) return new RacpResponse(opcode,0,0,p.u16(),-1);
        if (opcode==0x11) return new RacpResponse(opcode,0,0,-1,p.u16());
        if (opcode==6) return new RacpResponse(opcode,p.u8(),p.u8(),-1,-1);
        throw new IllegalArgumentException("Unknown RACP response opcode");
    }
    public static final class RacpResponse {
        public final int opcode, request, result, count, sequence;
        RacpResponse(int op,int request,int result,int count,int sequence) { this.opcode=op; this.request=request; this.result=result; this.count=count; this.sequence=sequence; }
        public boolean success() { return opcode==6 && result==1; }
    }
}
