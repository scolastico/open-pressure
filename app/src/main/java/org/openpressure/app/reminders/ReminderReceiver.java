package org.openpressure.app.reminders;

import android.app.AlarmManager;
import android.content.*;

/** Alarm delivery and Android's wall-clock/lifecycle recovery broadcasts. */
public final class ReminderReceiver extends BroadcastReceiver {
    public static final String ACTION_ALARM="org.openpressure.app.reminders.ALARM";
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null) return;
        ReminderScheduler scheduler=new ReminderScheduler(context);
        try {
            String action=intent.getAction();
            if(ACTION_ALARM.equals(action)) scheduler.deliver(intent);
            else if(Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) scheduler.reschedule();
            else if(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) scheduler.refresh();
        } catch(IllegalStateException unavailable) {
            // Leave durable pending occurrences intact so a later app refresh can retry.
        }
    }
}
