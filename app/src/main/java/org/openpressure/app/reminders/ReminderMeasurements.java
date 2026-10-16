package org.openpressure.app.reminders;

import android.content.Context;
import java.time.*;

/** Keeps only one latest epoch and per-occurrence skip markers, including in passthrough mode. */
public final class ReminderMeasurements {
    private ReminderMeasurements() {}
    public static long latest(Context context) {
        synchronized(ReminderStore.LOCK) { return new ReminderStore(context).state().latest; }
    }
    public static void record(Context context,LocalDateTime measured,long received,String source) {
        long timestamp;
        if(measured==null) {
            if(!"bluetooth".equals(source)) return;
            timestamp=received;
        } else {
            try { timestamp=measured.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
            catch(DateTimeException | ArithmeticException invalid) { return; }
        }
        if(timestamp<=0 || timestamp>received) return;
        synchronized(ReminderStore.LOCK) {
            ReminderStore store=new ReminderStore(context); ReminderStore.State state=store.state();
            // A manual clock correction can place the previously accepted epoch in the future.
            if(state.latest>received) state.latest=0;
            boolean changed=timestamp>state.latest;
            state.latest=Math.max(state.latest,timestamp);
            for(ReminderStore.Entry entry:state.entries) {
                if(entry.reminder.enabled && entry.due!=0 && ReminderPolicy.suppressed(entry.reminder,entry.due,timestamp) && entry.suppressedDue!=entry.due) {
                    entry.suppressedDue=entry.due; changed=true;
                }
            }
            if(changed) store.write(state);
        }
    }
}
