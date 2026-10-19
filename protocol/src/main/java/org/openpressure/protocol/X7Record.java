package org.openpressure.protocol;

import java.time.DateTimeException;
import java.time.LocalDateTime;

/**
 * The 16-byte X7-family BP record layout independently recovered from the original APK model configurations.
 * Bank addresses and slot counts are per identity; see {@link X7Model}.
 */
public final class X7Record {
    public static final int RECORD_BYTES=X7Model.RECORD_BYTES;
    public final BloodPressure measurement;
    public final int sequence;
    private X7Record(BloodPressure measurement,int sequence) { this.measurement=measurement; this.sequence=sequence; }
    /** Whether a Device Information identity selects an X7-family map; see {@link X7Model#identify}. */
    public static boolean supportsModel(String model) { return X7Model.identify(model)!=null; }
    /**
     * Erased slots return null. Unknown timestamp bits never become invented dates. The verified model decides the
     * derived flags: where its configuration says so, irregular pulse and movement read as 0 when AFib is set.
     */
    public static X7Record parse(byte[] raw,int user,X7Model model) {
        if(model==null) throw new IllegalArgumentException("X7 record requires its verified model");
        if(raw==null || raw.length!=RECORD_BYTES || user<1 || user>2) throw new IllegalArgumentException("Invalid X7 record or bank");
        boolean erased=true; for(byte b:raw) erased&=b==(byte)255;
        if(erased) return null;
        int sys=(raw[0]&255)+25, dia=raw[1]&255, pulse=raw[2]&255;
        if(dia==0 || sys<=dia || pulse==0) throw new IllegalArgumentException("Invalid X7 pressure/pulse fields");
        int date=word(raw,4), clock=word(raw,6);
        LocalDateTime time=null;
        try { time=LocalDateTime.of(2000+(raw[3]&255),(date>>>10)&15,(date>>>5)&31,date&31,(clock>>>6)&63,clock&63); }
        catch(DateTimeException ignored) { /* Original model permits records with unavailable time. */ }
        boolean afib=(clock&0x2000)!=0;
        // Irregular pulse is word 4 bit 14, movement bit 15. The HEM-7196T1 VitalDataIndexes force both to 0 with AFib.
        int status=model.afibMasksFlags && afib?0:((date&0x4000)!=0?4:0)|((date&0x8000)!=0?1:0);
        // AFib is its own original-model field. It must never be inferred from the IHB flag.
        BloodPressure bp=new BloodPressure(sys,dia,Double.NaN,(double)pulse,user,status,time,false,afib);
        return new X7Record(bp,word(raw,10));
    }
    private static int word(byte[] raw,int offset) { return (raw[offset]&255)|((raw[offset+1]&255)<<8); }
}
