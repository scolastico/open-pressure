package org.openpressure.app.reminders;

import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

public class ReminderPolicyTest {
    @Test public void weekdaysAndOnceUseStrictlyNextPhoneLocalTime() {
        ZoneId zone=ZoneId.of("Europe/Berlin");
        ZonedDateTime monday=ZonedDateTime.of(2026,10,19,8,0,0,0,zone);
        assertEquals("2026-10-26T08:00+01:00[Europe/Berlin]",ReminderPolicy.next(new Reminder(1,8,0,1,120,true),monday).toString());
        assertEquals("2026-10-20T08:00+02:00[Europe/Berlin]",ReminderPolicy.next(new Reminder(1,8,0,0,120,true),monday).toString());
        assertEquals("2026-10-21T07:30+02:00[Europe/Berlin]",ReminderPolicy.next(new Reminder(1,7,30,4,120,true),monday).toString());
    }
    @Test public void daylightSavingGapMovesForwardAndOverlapFiresOnce() {
        ZoneId zone=ZoneId.of("Europe/Berlin");
        Reminder sunday=new Reminder(1,2,30,64,120,true);
        assertEquals("2026-03-29T03:30+02:00[Europe/Berlin]",ReminderPolicy.next(sunday,ZonedDateTime.of(2026,3,28,12,0,0,0,zone)).toString());
        assertEquals("2026-11-01T02:30+01:00[Europe/Berlin]",ReminderPolicy.next(sunday,ZonedDateTime.of(2026,10,25,2,45,0,0,zone).withEarlierOffsetAtOverlap()).toString());
    }
    @Test public void suppressionIncludesWindowStartAndDueButNeverAfterDue() {
        Reminder reminder=new Reminder(1,8,0,0,120,true);
        long due=10_000_000L;
        assertTrue(ReminderPolicy.suppressed(reminder,due,due-7_200_000));
        assertTrue(ReminderPolicy.suppressed(reminder,due,due));
        assertFalse(ReminderPolicy.suppressed(reminder,due,due-7_200_001));
        assertFalse(ReminderPolicy.suppressed(reminder,due,due+1));
        assertFalse(ReminderPolicy.suppressed(new Reminder(1,8,0,0,0,true),due,due));
    }
    @Test public void validatesTimeDaysAndNonnegativeWindow() {
        for(int[] invalid:new int[][]{{24,0,0,120},{0,60,0,120},{0,0,128,120},{0,0,0,-1}}) {
            assertThrows(IllegalArgumentException.class,()->new Reminder(1,invalid[0],invalid[1],invalid[2],invalid[3],true));
        }
    }
}
