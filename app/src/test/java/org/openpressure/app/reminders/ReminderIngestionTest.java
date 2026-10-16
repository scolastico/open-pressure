package org.openpressure.app.reminders;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.forwarding.HttpForwardingSettings;
import org.openpressure.protocol.BloodPressure;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockStatic;

/** Reminder timing observes committed ingestion, including temporary-only forwarding. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class ReminderIngestionTest {
    private Context context;
    private ReadingStore store;
    private final byte[] packet={0,120,0,80,0,93,0};
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); store=new ReadingStore(context);
        ExperimentalConsent.accept(context);
    }
    @After public void close() { store.close(); }
    private long epoch(LocalDateTime time) { return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    @Test public void manualMeasurementUsesItsTimeAndNotImportReceiptTime() {
        LocalDateTime measured=LocalDateTime.now().minusMinutes(45).withNano(0);
        store.addManual(120,80,60.0,2,measured,"");
        assertEquals(epoch(measured),ReminderMeasurements.latest(context));
        store.addManual(121,81,null,1,measured.minusDays(1),"");
        assertEquals(epoch(measured),ReminderMeasurements.latest(context));
    }
    @Test public void undatedStoredHistoryDoesNotCountButLiveReadingDoes() {
        assertTrue(store.add("synthetic","Synthetic",BloodPressure.parse(packet),packet,"vendor-history",1));
        assertEquals(0,ReminderMeasurements.latest(context));
        long before=System.currentTimeMillis();
        assertTrue(store.add("synthetic","Synthetic",BloodPressure.parse(packet),packet,"bluetooth",1));
        assertTrue(ReminderMeasurements.latest(context)>=before);
    }
    @Test public void passthroughTimingSurvivesPayloadAcknowledgmentWithoutLocalHistory() {
        String endpoint="https://example.invalid/reminder-test";
        HttpForwardingSettings.save(context,endpoint,"",false);
        LocalDateTime measured=LocalDateTime.now().minusMinutes(20).withNano(0);
        store.addManual(120,80,null,1,measured,"synthetic private note");
        assertEquals(0,store.historyCount(0));
        assertEquals(epoch(measured),ReminderMeasurements.latest(context));
        store.httpOutbox().acknowledge(store.httpOutbox().pending(endpoint,1).get(0));
        assertEquals(0,store.httpOutbox().count(endpoint));
        assertEquals(epoch(measured),ReminderMeasurements.latest(context));
    }
    @Test public void failedTransactionDoesNotMarkAMeasurement() {
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_reminder_test BEFORE INSERT ON readings BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try { store.addManual(120,80,null,1,LocalDateTime.now().minusMinutes(1),""); fail("Expected rollback"); }
        catch(SQLiteException expected) { }
        assertEquals(0,ReminderMeasurements.latest(context));
    }
    @Test public void trackerRunsOnlyAfterTheReadingTransactionHasCommitted() {
        AtomicBoolean observed=new AtomicBoolean();
        try(MockedStatic<ReminderMeasurements> tracker=mockStatic(ReminderMeasurements.class)) {
            tracker.when(() -> ReminderMeasurements.record(any(Context.class),any(LocalDateTime.class),anyLong(),eq("manual")))
                    .thenAnswer(call -> {
                        assertFalse(store.getWritableDatabase().inTransaction());
                        assertEquals(1,store.historyCount(0)); observed.set(true); return null;
                    });
            store.addManual(120,80,null,1,LocalDateTime.now().minusMinutes(1),"");
            assertTrue(observed.get());
        }
    }
    @Test public void reminderFailureCannotUndoCommittedReadings() {
        try(MockedStatic<ReminderMeasurements> tracker=mockStatic(ReminderMeasurements.class)) {
            tracker.when(() -> ReminderMeasurements.record(any(Context.class),any(LocalDateTime.class),anyLong(),eq("manual")))
                    .thenThrow(new IllegalStateException("synthetic preferences failure"));
            store.addManual(120,80,null,1,LocalDateTime.now().minusMinutes(1),"");
            assertEquals(1,store.historyCount(0));
        }
    }
    @Test public void existingDatedHistoryCanSeedTimingAfterAnAppUpgrade() {
        LocalDateTime measured=LocalDateTime.now().minusMinutes(30).withNano(0);
        try(MockedStatic<ReminderMeasurements> tracker=mockStatic(ReminderMeasurements.class)) {
            // Simulate a reading saved by the app version before reminder metadata existed.
            store.addManual(120,80,null,1,measured,"");
            store.addManual(121,81,null,1,measured.plusDays(1),"");
        }
        assertEquals(0,ReminderMeasurements.latest(context));
        store.restoreReminderMeasurement();
        assertEquals(epoch(measured),ReminderMeasurements.latest(context));
    }
    @Test public void oldLocalHistoryCannotReplaceNewerPassthroughTimingOnRestart() {
        store.addManual(120,80,null,1,LocalDateTime.now().minusDays(1),"");
        store.getWritableDatabase().execSQL("UPDATE readings SET received=?",new Object[]{System.currentTimeMillis()-3_600_000});
        HttpForwardingSettings.save(context,"https://example.invalid/reminder-test","",false);
        LocalDateTime recent=LocalDateTime.now().minusMinutes(5).withNano(0);
        store.addManual(121,81,null,1,recent,"");
        assertEquals(epoch(recent),ReminderMeasurements.latest(context));
        store.restoreReminderMeasurement();
        assertEquals(epoch(recent),ReminderMeasurements.latest(context));
    }
}
