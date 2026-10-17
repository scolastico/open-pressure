package org.openpressure.protocol;

import java.time.DateTimeException;
import java.time.LocalDateTime;

/** HEM-7380T1 BP layout independently recovered from its original APK model configurations. */
public final class X7Record {
    public static final int START=0x01c4, RECORD_BYTES=16, RECORDS_PER_USER=100, HISTORY_BYTES=3200;
    public final BloodPressure measurement;
    public final int sequence;
    private X7Record(BloodPressure measurement,int sequence) { this.measurement=measurement; this.sequence=sequence; }
    public static boolean supportsModel(String model) {
        if(model==null) return false;
        model=model.replace("\u0000","").trim();
        // The original EBK and EOSL configurations are identical except for their model_id line.
        return model.equals("HEM-7380T1-EOSL") || model.equals("HEM-7380T1-EBK") || model.equals("HEM-7380T1") || model.equalsIgnoreCase("X7 Smart Afib");
    }
    /** Erased slots return null. Unknown timestamp bits never become invented dates. */
    public static X7Record parse(byte[] raw,int user) {
        if(raw==null || raw.length!=RECORD_BYTES || user<1 || user>2) throw new IllegalArgumentException("Invalid X7 record or bank");
        boolean erased=true; for(byte b:raw) erased&=b==(byte)255;
        if(erased) return null;
        int sys=(raw[0]&255)+25, dia=raw[1]&255, pulse=raw[2]&255;
        if(dia==0 || sys<=dia || pulse==0) throw new IllegalArgumentException("Invalid X7 pressure/pulse fields");
        int date=word(raw,4), clock=word(raw,6);
        LocalDateTime time=null;
        try { time=LocalDateTime.of(2000+(raw[3]&255),(date>>>10)&15,(date>>>5)&31,date&31,(clock>>>6)&63,clock&63); }
        catch(DateTimeException ignored) { /* Original model permits records with unavailable time. */ }
        int status=((date&0x4000)!=0?4:0)|((date&0x8000)!=0?1:0);
        // AFib is its own original-model field. It must never be inferred from the IHB flag.
        BloodPressure bp=new BloodPressure(sys,dia,Double.NaN,(double)pulse,user,status,time,false,(clock&0x2000)!=0);
        return new X7Record(bp,word(raw,10));
    }
    private static int word(byte[] raw,int offset) { return (raw[offset]&255)|((raw[offset+1]&255)<<8); }
}
