package org.openpressure.app.reminders;

import java.time.*;

/** Local wall-time recurrence, with one occurrence on an overlap and gap times moved forward. */
final class ReminderPolicy {
    private ReminderPolicy() {}
    static ZonedDateTime next(Reminder reminder,ZonedDateTime after) {
        for(int offset=0;offset<=7;offset++) {
            LocalDate date=after.toLocalDate().plusDays(offset);
            if(reminder.daysMask!=0 && (reminder.daysMask & (1<<(date.getDayOfWeek().getValue()-1)))==0) continue;
            ZonedDateTime candidate=date.atTime(reminder.hour,reminder.minute).atZone(after.getZone());
            if(candidate.toInstant().isAfter(after.toInstant())) return candidate;
        }
        throw new IllegalStateException("No next reminder occurrence");
    }
    static boolean suppressed(Reminder reminder,long due,long measured) {
        return reminder.skipMinutes>0 && measured>0 && measured<=due && measured>=due-reminder.skipMinutes*60_000L;
    }
}
