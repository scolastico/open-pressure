package org.openpressure.protocol;

import java.io.ByteArrayOutputStream;

/** A bounded WLP1 read transaction. It can never construct a memory-write command. */
public final class WlpReadSession {
    private int start,length,rangeReceived;
    private final int blockSize;
    private final ByteArrayOutputStream data=new ByteArrayOutputStream();
    private final ByteArrayOutputStream response=new ByteArrayOutputStream();
    private int phase; // start, reads, end, complete
    private boolean endRequested;
    public WlpReadSession(int start,int length,int blockSize) {
        validateRange(start,length);
        if(blockSize<1 || blockSize>16)
            throw new IllegalArgumentException("Read must stay within 0x0000–0xFFFF, with blocks of 1–16 bytes");
        this.start=start; this.length=length; this.blockSize=blockSize;
    }
    public byte[] command() {
        if(phase==0) return new byte[]{8,0,0,0,0,0x10,0,0x18};
        if(phase==1) return Wlp.read(start+rangeReceived,Math.min(blockSize,length-rangeReceived));
        if(phase==2) { endRequested=true; return new byte[]{8,0x0f,0,0,0,0,0,7}; }
        throw new IllegalArgumentException("WLP read already completed");
    }
    /** Returns true only once a complete, validated reply advances the transaction. */
    public boolean accept(byte[] fragment) {
        if(complete() || fragment==null || fragment.length==0) throw new IllegalArgumentException("Unexpected WLP notification");
        if(phase==2 && !endRequested) throw new IllegalArgumentException("WLP END was not requested");
        response.write(fragment,0,fragment.length);
        byte[] bytes=response.toByteArray();
        int declared=bytes[0]&255;
        if(declared<8 || declared>24 || bytes.length>declared) throw new IllegalArgumentException("Invalid WLP response length");
        if(bytes.length<declared) return false;
        byte[] request=command();
        byte[] payload=Wlp.payload(bytes,request[1]&255);
        if(phase==1) {
            if(bytes[2]!=request[2] || bytes[3]!=request[3] || bytes[4]!=request[4])
                throw new IllegalArgumentException("WLP response address or access flags do not match the read");
            int wanted=request[5]&255;
            if((bytes[5]&255)!=wanted || payload.length!=wanted) throw new IllegalArgumentException("WLP read returned missing or unexpected data");
            data.write(payload,0,payload.length);
            rangeReceived+=payload.length;
            if(rangeReceived==length) phase=2;
        } else {
            if(payload.length!=0) throw new IllegalArgumentException("Unexpected WLP session-control payload");
            phase=phase==0?1:3;
        }
        response.reset();
        return true;
    }
    public int received() { return data.size(); }
    public byte[] data() { return data.toByteArray(); }
    public boolean rangeComplete() { return phase==2; }
    /** Continue to another bounded range before requesting the session end. Data remains cumulative. */
    public void appendRange(int start,int length) {
        if(!rangeComplete() || endRequested || response.size()!=0)
            throw new IllegalArgumentException("Append requires a completed range before the session end");
        validateRange(start,length);
        if(length>65536-data.size()) throw new IllegalArgumentException("Combined WLP reads exceed 65536 bytes");
        this.start=start; this.length=length; rangeReceived=0; phase=1;
    }
    public boolean complete() { return phase==3; }
    private static void validateRange(int start,int length) {
        if(start<0 || start>65535 || length<1 || length>65536-start)
            throw new IllegalArgumentException("Read must stay within 0x0000–0xFFFF");
    }
}
