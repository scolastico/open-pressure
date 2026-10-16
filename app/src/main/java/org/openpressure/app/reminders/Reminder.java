package org.openpressure.app.reminders;

/** A phone-local reminder. Weekday bits run from Monday (bit 0) through Sunday. */
public final class Reminder {
    public final int id,hour,minute,daysMask,skipMinutes;
    public final boolean enabled;
    public Reminder(int id,int hour,int minute,int daysMask,int skipMinutes,boolean enabled) {
        if(id<1 || hour<0 || hour>23 || minute<0 || minute>59 || daysMask<0 || daysMask>127 || skipMinutes<0)
            throw new IllegalArgumentException("Invalid reminder");
        this.id=id; this.hour=hour; this.minute=minute; this.daysMask=daysMask;
        this.skipMinutes=skipMinutes; this.enabled=enabled;
    }
}
