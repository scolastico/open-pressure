package org.openpressure.app.reminders;

import android.Manifest;
import android.app.*;
import android.content.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import java.time.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class ReminderReceiverTest {
    private Context context;
    private ReminderStore store;
    private ReminderScheduler scheduler;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("measurement_reminders",0).edit().clear().commit();
        shadowOf((Application)context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        store=new ReminderStore(context); scheduler=new ReminderScheduler(context);
    }
    private Intent overdue(int days,int skip) {
        Reminder reminder=store.create(8,0,days,skip);
        ReminderStore.State state=store.state(); ReminderStore.Entry entry=state.find(reminder.id);
        scheduler.cancel(entry); entry.due=System.currentTimeMillis()-1000;
        entry.token=state.nextToken++; store.write(state);
        scheduler.refresh();
        return shadowOf(shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().get(0).operation).getSavedIntent();
    }
    @Test public void realReceiverConsumesOnceAfterStartupRefreshAndOpensOverviewPrivately() {
        Intent alarm=overdue(0,120);
        new ReminderScheduler(context).refresh();
        new ReminderReceiver().onReceive(context,alarm);
        assertFalse(new ReminderStore(context).list().get(0).enabled);
        Notification notification=shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().get(0);
        assertEquals(ReminderNotifications.CHANNEL_ID,notification.getChannelId());
        assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility);
        assertEquals(0,notification.flags & Notification.FLAG_ONGOING_EVENT);
        Intent open=shadowOf(notification.contentIntent).getSavedIntent();
        assertTrue(open.getBooleanExtra("open_reminders_overview",false));
        assertFalse(open.hasExtra("open_history"));
        new ReminderReceiver().onReceive(context,alarm);
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void permissionDeniedConsumesOnceWithoutPostingAndRecurrenceStillAdvances() {
        shadowOf((Application)context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Intent alarm=overdue(127,120);
        long due=alarm.getLongExtra("due",0);
        new ReminderReceiver().onReceive(context,alarm);
        assertTrue(store.list().get(0).enabled);
        assertTrue(store.state().entries.get(0).due>due);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void channelDisabledPreventsNotificationWithoutDisablingConfiguration() {
        ReminderNotifications.createChannel(context);
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(ReminderNotifications.CHANNEL_ID,"Synthetic",NotificationManager.IMPORTANCE_NONE));
        assertFalse(ReminderNotifications.enabled(context));
        new ReminderReceiver().onReceive(context,overdue(127,0));
        assertTrue(store.list().get(0).enabled);
        assertEquals(0,shadowOf(manager).size());
    }
    @Test public void clockAndLifecycleBroadcastsInvalidateOldTokenButExactPermissionRefreshRetainsIt() {
        for(String action:new String[]{Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED}) {
            Intent old=overdue(127,120);
            new ReminderReceiver().onReceive(context,new Intent(action));
            assertNotEquals(old.getLongExtra("token",0),store.state().find(old.getIntExtra("id",0)).token);
            new ReminderReceiver().onReceive(context,old);
            assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
            store.delete(old.getIntExtra("id",0));
        }
        Intent pending=overdue(127,120);
        new ReminderReceiver().onReceive(context,new Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED));
        assertEquals(pending.getLongExtra("token",0),store.state().find(pending.getIntExtra("id",0)).token);
    }
    @Test public void exactPermissionRevokedBetweenCheckAndSetFallsBackToRealInexactAlarm() {
        AlarmManager actual=context.getSystemService(AlarmManager.class);
        AlarmManager revoked=mock(AlarmManager.class);
        when(revoked.canScheduleExactAlarms()).thenReturn(true);
        doThrow(new SecurityException("Synthetic revocation")).when(revoked).setExactAndAllowWhileIdle(anyInt(),anyLong(),any(PendingIntent.class));
        doAnswer(call->{ actual.setAndAllowWhileIdle(call.getArgument(0),call.getArgument(1),call.getArgument(2)); return null; })
                .when(revoked).setAndAllowWhileIdle(anyInt(),anyLong(),any(PendingIntent.class));
        Context race=new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) { return Context.ALARM_SERVICE.equals(name)?revoked:super.getSystemService(name); }
        };
        new ReminderStore(race).create(8,0,0,120);
        ShadowAlarmManager.ScheduledAlarm installed=shadowOf(actual).getNextScheduledAlarm();
        assertNotNull(installed); assertNotEquals(0,installed.getWindowLengthMs()); assertTrue(installed.isAllowWhileIdle());
    }
    @Test @Config(sdk=26) public void android8SchedulesExactlyWithoutNewerAlarmOrNotificationPermissions() {
        store.create(8,0,0,120);
        assertTrue(ReminderScheduler.exactAllowed(context));
        assertTrue(ReminderNotifications.enabled(context));
        ShadowAlarmManager.ScheduledAlarm installed=shadowOf(context.getSystemService(AlarmManager.class)).getNextScheduledAlarm();
        assertEquals(0,installed.getWindowLengthMs());
    }
    @Test public void earlyDeliveryCannotConsumeOrNotifyAFutureOccurrence() {
        Reminder reminder=store.create(8,0,0,120);
        Intent future=scheduler.alarmIntent(store.state().find(reminder.id));
        new ReminderReceiver().onReceive(context,future);
        assertTrue(store.list().get(0).enabled);
        assertEquals(future.getLongExtra("due",0),store.state().find(reminder.id).due);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void corruptPreferencesAreNotSilentlyDiscardedByReceiver() {
        context.getSharedPreferences("measurement_reminders",0).edit().putString("state","broken synthetic JSON").commit();
        new ReminderReceiver().onReceive(context,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertThrows(IllegalStateException.class,()->store.list());
    }
}
