package org.openpressure.omronsandbox;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;

/**
 * Pins the wall clock Omron's settings write uses. {@code OGSCWlpDevice.setDeviceSettings} creates an
 * {@code OCLTimeSetting}, whose constructor takes {@code Calendar.getInstance()}; the JDK clock is outside
 * Robolectric's virtual time, so without this shadow Omron would write the real current time and the fixture
 * could not pin the frame. The pinned instant is the fixture's {@code time} in its zone.
 */
@Implements(className=OmronAnchors.LIB+".ogsc.OCLTimeSetting")
public class ShadowOmronClock {
    private static volatile Calendar pinned;
    @RealObject private Object real;

    static void configure(java.time.LocalDateTime time,java.time.ZoneId zone) {
        if(time==null) { pinned=null; return; }
        Calendar calendar=new GregorianCalendar(TimeZone.getTimeZone(zone));
        calendar.setTimeInMillis(time.atZone(zone).toInstant().toEpochMilli());
        pinned=calendar;
    }
    @Resetter public static void reset() { pinned=null; }

    /** Omron's constructor runs first (the shadow is called after it); only the Calendar field is replaced. */
    @Implementation protected void __constructor__() {
        Calendar at=pinned;
        if(at==null) throw new IllegalStateException("Omron asked for the wall clock, but the fixture pins no time");
        Field clock=null;
        for(Field f:real.getClass().getDeclaredFields()) if(f.getType()==Calendar.class) {
            if(clock!=null) throw new IllegalStateException("OCLTimeSetting has several Calendar fields");
            clock=f;
        }
        if(clock==null) throw new IllegalStateException("OCLTimeSetting has no Calendar field");
        try { clock.setAccessible(true); clock.set(real,(Calendar)at.clone()); }
        catch(IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}
