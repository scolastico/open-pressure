package org.openpressure.app.reminders;

import android.app.*;
import android.Manifest;
import android.content.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class ReminderSchedulerTest {
    private Context context;
    private ReminderStore store;
    private ReminderScheduler scheduler;
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-19T06:00:00Z"),ZoneOffset.UTC);
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("measurement_reminders",0).edit().clear().commit();
        shadowOf((Application)context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        store=new ReminderStore(context);
        scheduler=new ReminderScheduler(context,clock);
        shadowOf(context.getSystemService(AlarmManager.class)).setCanScheduleExactAlarms(false);
    }
    private Reminder create(int days,int skip) {
        Reminder reminder=store.create(8,0,days,skip);
        scheduler.reschedule();
        return reminder;
    }
    private Intent intent(int id) { return scheduler.alarmIntent(store.state().find(id)); }
    @Test public void persistsUniqueConfigsAndRejectsInvalidEdits() {
        Reminder one=create(0,120),two=store.create(9,30,127,0);
        assertNotEquals(one.id,two.id);
        assertEquals(2,new ReminderStore(context).list().size());
        store.save(new Reminder(one.id,10,15,3,45,false));
        Reminder saved=store.list().get(0);
        assertEquals(10,saved.hour); assertFalse(saved.enabled);
        store.delete(two.id); assertEquals(1,store.list().size());
        assertThrows(IllegalArgumentException.class,()->store.save(new Reminder(999,8,0,0,120,true)));
    }
    @Test public void refreshRetainsOverdueOccurrenceBeforeReceiverStarts() {
        Reminder reminder=create(0,120);
        Intent original=intent(reminder.id);
        new ReminderScheduler(context,Clock.offset(clock,Duration.ofHours(4))).refresh();
        assertEquals(original.getLongExtra("due",0),intent(reminder.id).getLongExtra("due",0));
        deliver(original);
        assertFalse(store.list().get(0).enabled);
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
        deliver(original);
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void editsDisablesAndDeletesRejectStaleAlarms() {
        Reminder reminder=create(127,120);
        Intent old=intent(reminder.id);
        store.save(new Reminder(reminder.id,9,0,127,120,true));
        deliver(old);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
        Intent edited=intent(reminder.id);
        store.save(new Reminder(reminder.id,9,0,127,120,false));
        deliver(edited);
        store.delete(reminder.id); deliver(edited);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void suppressesBeforeDueDespiteMoreRecentAfterDueReading() {
        Reminder reminder=create(0,120);
        Intent occurrence=intent(reminder.id);
        long due=occurrence.getLongExtra("due",0);
        record(due-60_000,due,"bluetooth");
        record(due+60_000,due+60_000,"bluetooth");
        deliver(occurrence);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
        assertFalse(store.list().get(0).enabled);
    }
    @Test public void oldFutureAndUndatedHistoryDoNotSuppressButUndatedLiveDoes() {
        Reminder reminder=create(0,120);
        Intent occurrence=intent(reminder.id); long due=occurrence.getLongExtra("due",0);
        record(due-7_200_001,due,"manual"); record(due+60_000,due,"bluetooth");
        ReminderMeasurements.record(context,null,due,"history");
        deliver(occurrence);
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
        Reminder live=store.create(8,0,0,120); scheduler.reschedule();
        long liveDue=intent(live.id).getLongExtra("due",0);
        ReminderMeasurements.record(context,null,liveDue-1000,"bluetooth");
        deliver(intent(live.id));
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void disabledSuppressionStillNotifiesAndRecurrenceAdvances() {
        Reminder reminder=create(127,0);
        Intent first=intent(reminder.id); long due=first.getLongExtra("due",0);
        record(due-1000,due,"manual"); deliver(first);
        assertTrue(store.list().get(0).enabled);
        assertTrue(intent(reminder.id).getLongExtra("due",0)>due);
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void deniedExactPermissionUsesAnInexactAlarmAndResetInvalidatesToken() {
        Reminder reminder=create(127,120);
        ShadowAlarmManager alarms=shadowOf(context.getSystemService(AlarmManager.class));
        ShadowAlarmManager.ScheduledAlarm scheduled=alarms.getNextScheduledAlarm();
        assertEquals(AlarmManager.RTC_WAKEUP,scheduled.type);
        assertNotEquals(0,scheduled.getWindowLengthMs());
        assertTrue(scheduled.isAllowWhileIdle());
        Intent old=intent(reminder.id); scheduler.reschedule(); deliver(old);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void failedClockResetRetainsTheDurableOccurrenceAndInstalledAlarm() {
        Reminder reminder=create(127,120);
        Intent original=intent(reminder.id);
        Context failing=failingWrites();
        assertThrows(IllegalStateException.class,()->new ReminderScheduler(failing,clock).reschedule());
        assertEquals(original.getLongExtra("token",0),intent(reminder.id).getLongExtra("token",0));
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
    }
    @Test public void durableConfigurationFailuresAreReportedWithoutChangingPriorConfig() {
        Reminder reminder=create(127,120);
        ReminderStore failing=new ReminderStore(failingWrites());
        assertThrows(IllegalStateException.class,()->failing.create(9,0,0,120));
        assertThrows(IllegalStateException.class,()->failing.save(new Reminder(reminder.id,9,0,0,120,true)));
        assertThrows(IllegalStateException.class,()->failing.delete(reminder.id));
        assertEquals(1,store.list().size()); assertEquals(8,store.list().get(0).hour);
    }
    @Test public void afterClockMovesBackwardsNewMeasurementReplacesAnOtherwiseFutureLatest() {
        record(20_000_000,20_000_000,"manual");
        record(10_000_000,10_000_000,"manual");
        assertEquals(10_000_000,ReminderMeasurements.latest(context));
    }
    @Test public void failedDeliveryCommitKeepsAlarmAndCanRetryOnce() {
        Reminder reminder=create(0,120);
        Intent original=intent(reminder.id);
        Clock dueClock=Clock.fixed(Instant.ofEpochMilli(original.getLongExtra("due",0)),ZoneOffset.UTC);
        assertThrows(IllegalStateException.class,()->new ReminderScheduler(failingWrites(),dueClock).deliver(original));
        assertTrue(store.list().get(0).enabled);
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
        deliver(original);
        assertFalse(store.list().get(0).enabled);
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void creationCommitsConfigurationAndOccurrenceTogetherBeforeInstallingAlarm() {
        AtomicInteger commits=new AtomicInteger();
        Reminder reminder=new ReminderStore(secondCommitFails(commits)).create(8,0,0,120);
        assertEquals(1,commits.get());
        ReminderStore.Entry persisted=store.state().find(reminder.id);
        assertTrue(persisted.due>0); assertTrue(persisted.token>0);
        assertEquals(1,store.list().size());
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
    }
    @Test public void editCommitsReplacementConfigurationAndOccurrenceTogether() {
        Reminder reminder=store.create(8,0,127,120);
        long oldToken=store.state().find(reminder.id).token;
        AtomicInteger commits=new AtomicInteger();
        new ReminderStore(secondCommitFails(commits)).save(new Reminder(reminder.id,9,30,3,0,true));
        assertEquals(1,commits.get());
        ReminderStore.Entry persisted=store.state().find(reminder.id);
        assertEquals(9,persisted.reminder.hour); assertEquals(30,persisted.reminder.minute);
        assertTrue(persisted.due>0); assertNotEquals(oldToken,persisted.token);
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
    }
    @Test public void clockResetDiscardsPreviouslyAcceptedFutureMeasurementWithoutANewReading() {
        Reminder reminder=create(0,120);
        long future=Instant.parse("2026-10-19T07:00:00Z").toEpochMilli();
        record(future,future,"manual");
        scheduler.reschedule();
        assertEquals(0,ReminderMeasurements.latest(context));
        assertEquals(0,store.state().find(reminder.id).suppressedDue);
        deliver(intent(reminder.id));
        assertEquals(1,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    private Context secondCommitFails(AtomicInteger commits) {
        SharedPreferences real=context.getSharedPreferences("measurement_reminders",0);
        SharedPreferences preferences=spy(real);
        doAnswer(call->{
            SharedPreferences.Editor actual=real.edit();
            SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class,RETURNS_SELF);
            doAnswer(put->{ actual.putString(put.getArgument(0),put.getArgument(1)); return editor; })
                    .when(editor).putString(anyString(),anyString());
            when(editor.commit()).thenAnswer(commit->commits.incrementAndGet()==2?false:actual.commit());
            return editor;
        }).when(preferences).edit();
        return new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return "measurement_reminders".equals(name)?preferences:super.getSharedPreferences(name,mode);
            }
        };
    }
    private Context failingWrites() {
        SharedPreferences real=context.getSharedPreferences("measurement_reminders",0);
        SharedPreferences preferences=spy(real);
        SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class,RETURNS_SELF);
        when(editor.commit()).thenReturn(false);
        doReturn(editor).when(preferences).edit();
        return new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return "measurement_reminders".equals(name)?preferences:super.getSharedPreferences(name,mode);
            }
        };
    }
    private void deliver(Intent intent) {
        new ReminderScheduler(context,Clock.fixed(Instant.ofEpochMilli(Math.max(intent.getLongExtra("due",0),clock.millis())),ZoneOffset.UTC)).deliver(intent);
    }
    private void record(long measured,long received,String source) {
        ReminderMeasurements.record(context,LocalDateTime.ofInstant(Instant.ofEpochMilli(measured),ZoneId.systemDefault()),received,source);
    }
}
