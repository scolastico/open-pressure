package org.openpressure.protocol;

import java.util.Arrays;

/** Documented framing only. Model-specific memory maps must be verified before use. */
public final class Wlp {
    private Wlp() {}
    public static byte[] read(int address,int length) {
        if (address<0 || address>0x3fffff || length<1 || length>255) throw new IllegalArgumentException("Invalid WLP read range");
        byte[] frame={8,1,(byte)(address>>16),(byte)(address>>8),(byte)address,(byte)length,0,0};
        frame[7]=checksum(frame,7); return frame;
    }
    private static byte checksum(byte[] bytes,int length) { byte b=0; for(int i=0;i<length;i++) b^=bytes[i]; return b; }
    public static byte[] payload(byte[] response,int requestOpcode) {
        if (response==null || response.length<8 || (response[0]&255)!=response.length || (response[1]&255)!=(requestOpcode|0x80))
            throw new IllegalArgumentException("Invalid WLP response framing");
        int n=response.length;
        if (response[n-1]!=checksum(response,n-1)) throw new IllegalArgumentException("WLP checksum mismatch");
        if (response[n-2]!=0) throw new IllegalArgumentException("WLP device error " + (response[n-2]&255));
        return Arrays.copyOfRange(response,6,n-2);
    }
}
