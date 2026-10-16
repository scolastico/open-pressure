package org.openpressure.app.data;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;

public final class Reading {
    public long id,received;
    public String device,name,time,source,notes,unit,raw;
    public double systolic,diastolic;
    public Double pulse,map;
    public Integer user,status;
    public Integer virtualUserId;
    public String virtualUserName;
    public Boolean afib;
    public String fingerprint,healthClientId,healthTime;
    public Integer healthOffset;
    public LocalDateTime displayTime() {
        return time==null ? LocalDateTime.ofInstant(Instant.ofEpochMilli(received),ZoneId.systemDefault()) : LocalDateTime.parse(time);
    }
    public double sysMmHg() { return "kPa".equals(unit) ? systolic*7.50062 : systolic; }
    public double diaMmHg() { return "kPa".equals(unit) ? diastolic*7.50062 : diastolic; }
}
