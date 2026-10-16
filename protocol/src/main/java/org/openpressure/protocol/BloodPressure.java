package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** SIG 0x2A35. The original unit and absent fields are preserved. */
public final class BloodPressure {
    public final double systolic, diastolic, meanArterialPressure;
    public final Double pulse;
    public final Integer userId, status;
    public final LocalDateTime time;
    public final boolean kpa;
    public final Boolean atrialFibrillation;
    BloodPressure(double sys, double dia, double map, Double pulse, Integer user, Integer status, LocalDateTime time, boolean kpa) {
        this(sys,dia,map,pulse,user,status,time,kpa,null);
    }
    BloodPressure(double sys, double dia, double map, Double pulse, Integer user, Integer status, LocalDateTime time, boolean kpa, Boolean afib) {
        this.systolic=sys; this.diastolic=dia; this.meanArterialPressure=map; this.pulse=pulse;
        this.userId=user; this.status=status; this.time=time; this.kpa=kpa;
        this.atrialFibrillation=afib;
    }
    public static BloodPressure parse(byte[] bytes) {
        Packet p = new Packet(bytes);
        int flags=p.u8();
        if ((flags & 0xe0) != 0) throw new IllegalArgumentException("Unknown BP flags");
        double sys=decodeSfloat(p.u16()), dia=decodeSfloat(p.u16()), map=decodeSfloat(p.u16());
        if (!Double.isFinite(sys) || !Double.isFinite(dia) || sys <= 0 || dia <= 0)
            throw new IllegalArgumentException("Pressure unavailable");
        LocalDateTime time=(flags & 2)!=0 ? p.dateTime() : null;
        Double pulse=(flags & 4)!=0 ? decodeSfloat(p.u16()) : null;
        if (pulse != null && !Double.isFinite(pulse)) pulse=null;
        Integer user=(flags & 8)!=0 ? p.u8() : null;
        Integer status=(flags & 16)!=0 ? p.u16() : null;
        if (p.position != bytes.length) throw new IllegalArgumentException("Unrecognized BP extension; retain raw packet");
        return new BloodPressure(sys,dia,map,pulse,user,status,time,(flags & 1)!=0);
    }
    public static double decodeSfloat(int raw) {
        raw &= 0xffff;
        if (raw==0x07fe || raw==0x07ff || raw==0x0800 || raw==0x0801 || raw==0x0802) return Double.NaN;
        int mantissa=raw & 0xfff, exponent=(raw >>> 12) & 15;
        if ((mantissa & 0x800)!=0) mantissa-=4096;
        if ((exponent & 8)!=0) exponent-=16;
        return mantissa * Math.pow(10,exponent);
    }
    public double systolicMmHg() { return kpa ? systolic * 7.50062 : systolic; }
    public double diastolicMmHg() { return kpa ? diastolic * 7.50062 : diastolic; }
    public List<String> statusLabels() { return statusLabels(status); }
    public static List<String> statusLabels(Integer status) {
        List<String> result=new ArrayList<>();
        if (status==null) return result;
        if ((status & 1)!=0) result.add("Movement detected");
        if ((status & 2)!=0) result.add("Cuff too loose");
        if ((status & 4)!=0) result.add("Irregular pulse");
        int range=(status >>> 3) & 3;
        if (range==1) result.add("Pulse above range");
        if (range==2) result.add("Pulse below range");
        if (range==3) result.add("Pulse range unavailable");
        if ((status & 32)!=0) result.add("Position error");
        if ((status & ~63)!=0) result.add("Additional device status: 0x" + Integer.toHexString(status & ~63));
        return result;
    }
}
