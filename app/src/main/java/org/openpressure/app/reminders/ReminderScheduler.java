package org.openpressure.app.reminders;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import java.time.*;

/** Persists an occurrence before installing its one-shot phone alarm. Never communicates with a cuff. */
public final class ReminderScheduler {
    private final Context context;
    private final Clock clock;
    public ReminderScheduler(Context context) { this(context,Clock.systemDefaultZone()); }
    ReminderScheduler(Context context,Clock clock) { this.context=context.getApplicationContext(); this.clock=clock; }
    public static boolean exactAllowed(Context context) {
        AlarmManager manager=context.getSystemService(AlarmManager.class);
        try { return manager!=null && (Build.VERSION.SDK_INT<31 || manager.canScheduleExactAlarms()); }
        catch(SecurityException revoked) { return false; }
    }
    /** Retains pending (including overdue) occurrences when Application starts before the alarm receiver. */
    public void refresh() {
        synchronized(ReminderStore.LOCK) {
            ReminderStore store=new ReminderStore(context); ReminderStore.State state=store.state();
            boolean changed=discardFutureMeasurement(state);
            for(ReminderStore.Entry entry:state.entries) {
                if(entry.reminder.enabled && entry.due==0) { advance(state,entry,clock.instant()); changed=true; }
            }
            if(changed) store.write(state);
            for(ReminderStore.Entry entry:state.entries) if(entry.reminder.enabled) schedule(entry);
        }
    }
    /** Recalculate wall times after reboot, clock/timezone change or an app update. */
    public void reschedule() {
        synchronized(ReminderStore.LOCK) {
            ReminderStore store=new ReminderStore(context); ReminderStore.State state=store.state();
            ReminderStore.State previous=store.state();
            discardFutureMeasurement(state);
            for(ReminderStore.Entry entry:state.entries) {
                entry.due=0; entry.suppressedDue=0;
                if(entry.reminder.enabled) advance(state,entry,clock.instant());
            }
            store.write(state);
            for(ReminderStore.Entry entry:previous.entries) cancel(entry);
            for(ReminderStore.Entry entry:state.entries) if(entry.reminder.enabled) schedule(entry);
        }
    }
    /** Prepare config and occurrence together before the store commits either one. */
    void prepare(ReminderStore.State state,ReminderStore.Entry entry) {
        discardFutureMeasurement(state);
        if(entry.reminder.enabled) advance(state,entry,clock.instant());
    }
    private boolean discardFutureMeasurement(ReminderStore.State state) {
        if(state.latest<=clock.millis()) return false;
        state.latest=0;
        for(ReminderStore.Entry entry:state.entries) entry.suppressedDue=0;
        return true;
    }
    private void advance(ReminderStore.State state,ReminderStore.Entry entry,Instant after) {
        discardFutureMeasurement(state);
        entry.due=ReminderPolicy.next(entry.reminder,after.atZone(clock.getZone())).toInstant().toEpochMilli();
        entry.token=state.nextToken++;
        entry.suppressedDue=ReminderPolicy.suppressed(entry.reminder,entry.due,state.latest)?entry.due:0;
    }
    Intent alarmIntent(ReminderStore.Entry entry) {
        return new Intent(context,ReminderReceiver.class).setAction(ReminderReceiver.ACTION_ALARM)
                .setData(Uri.parse("openpressure://reminder/"+entry.reminder.id+"/"+entry.token))
                .putExtra("id",entry.reminder.id).putExtra("due",entry.due).putExtra("token",entry.token);
    }
    private PendingIntent pending(ReminderStore.Entry entry,int flags) {
        return PendingIntent.getBroadcast(context,entry.reminder.id,alarmIntent(entry),PendingIntent.FLAG_IMMUTABLE|flags);
    }
    void cancel(ReminderStore.Entry entry) {
        if(entry.due==0) return;
        PendingIntent operation=pending(entry,PendingIntent.FLAG_NO_CREATE);
        if(operation==null) return;
        AlarmManager manager=context.getSystemService(AlarmManager.class);
        if(manager!=null) manager.cancel(operation);
        operation.cancel();
    }
    void schedule(ReminderStore.Entry entry) {
        AlarmManager manager=context.getSystemService(AlarmManager.class);
        if(manager==null) return;
        PendingIntent operation=pending(entry,PendingIntent.FLAG_UPDATE_CURRENT);
        if(exactAllowed(context)) {
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,entry.due,operation); return; }
            catch(SecurityException revoked) { /* Access may change between the check and scheduling. */ }
        }
        try { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,entry.due,operation); }
        catch(SecurityException unavailable) { /* The next foreground refresh can retry. */ }
    }
    void deliver(Intent intent) {
        synchronized(ReminderStore.LOCK) {
            ReminderStore store=new ReminderStore(context); ReminderStore.State state=store.state();
            ReminderStore.Entry entry=state.find(intent.getIntExtra("id",0));
            if(entry==null || !entry.reminder.enabled || entry.due==0 || entry.due!=intent.getLongExtra("due",0)
                    || entry.token!=intent.getLongExtra("token",0) || clock.millis()<entry.due) return;
            Reminder reminder=entry.reminder;
            boolean suppress=entry.suppressedDue==entry.due || ReminderPolicy.suppressed(reminder,entry.due,state.latest);
            long previousDue=entry.due;
            ReminderStore.Entry previous=store.state().find(reminder.id);
            if(reminder.daysMask==0) {
                entry.reminder=new Reminder(reminder.id,reminder.hour,reminder.minute,reminder.daysMask,reminder.skipMinutes,false);
                entry.due=0; entry.suppressedDue=0;
            } else advance(state,entry,Instant.ofEpochMilli(Math.max(clock.millis(),previousDue)));
            // Commit consumption before posting so duplicate broadcasts never repeat an occurrence.
            store.write(state);
            cancel(previous);
            if(entry.reminder.enabled) schedule(entry);
            if(!suppress) ReminderNotifications.show(context,reminder.id);
        }
    }
}
