package org.openpressure.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.List;

/** Android-independent selection, identity, unit and wall-time mapping for optional BP exports. */
public final class HealthExport {
    private HealthExport() {}
    public static final class Payload {
        public final String clientRecordId;
        public final Instant time;
        public final ZoneOffset offset;
        public final double systolicMmHg,diastolicMmHg;
        private Payload(String id,Instant time,ZoneOffset offset,double sys,double dia) {
            clientRecordId=id; this.time=time; this.offset=offset; systolicMmHg=sys; diastolicMmHg=dia;
        }
    }
    public static Payload prepare(String fingerprint,Integer user,int selectedUser,String localTime,double systolic,double diastolic,String unit,ZoneId zone,Instant now) {
        if(selectedUser<1 || selectedUser>2 || user==null || user!=selectedUser) throw new IllegalArgumentException("Reading is not from the selected user bank");
        return prepareValues(fingerprint,localTime,systolic,diastolic,unit,zone,now);
    }
    public static Payload prepareVirtual(String fingerprint,Integer user,int selectedUser,String localTime,double systolic,double diastolic,String unit,ZoneId zone,Instant now) {
        if(selectedUser<1 || user==null || user!=selectedUser) throw new IllegalArgumentException("Reading is not from the selected virtual user");
        return prepareValues(fingerprint,localTime,systolic,diastolic,unit,zone,now);
    }
    private static Payload prepareValues(String fingerprint,String localTime,double systolic,double diastolic,String unit,ZoneId zone,Instant now) {
        if(fingerprint==null || fingerprint.isEmpty()) throw new IllegalArgumentException("Reading has no stable identity");
        if(localTime==null || zone==null || now==null) throw new IllegalArgumentException("Reading has no usable device timestamp");
        double factor;
        if("mmHg".equals(unit)) factor=1;
        else if("kPa".equals(unit)) factor=7.50062;
        else throw new IllegalArgumentException("Unknown pressure unit");
        double sys=systolic*factor, dia=diastolic*factor;
        if(!Double.isFinite(sys) || !Double.isFinite(dia) || sys<=0 || sys>300 || dia<=0 || dia>200 || sys<=dia)
            throw new IllegalArgumentException("Pressure values are outside the supported Health Connect export range");
        LocalDateTime wall;
        try { wall=LocalDateTime.parse(localTime); }
        catch(DateTimeException ex) { throw new IllegalArgumentException("Invalid device timestamp",ex); }
        List<ZoneOffset> offsets=zone.getRules().getValidOffsets(wall);
        if(offsets.isEmpty()) throw new IllegalArgumentException("Timestamp falls in a daylight-saving clock gap");
        ZoneOffset offset=offsets.get(0); // Earlier valid offset for an unresolvable autumn overlap.
        Instant instant=wall.toInstant(offset);
        if(instant.isAfter(now)) throw new IllegalArgumentException("Reading timestamp is in the future");
        StringBuilder id=new StringBuilder("open-pressure-bp-");
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            final char[] alphabet="0123456789abcdef".toCharArray();
            for(byte b:hash) { id.append(alphabet[(b&255)>>>4]); id.append(alphabet[b&15]); }
        } catch(NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
        return new Payload(id.toString(),instant,offset,sys,dia);
    }
}
