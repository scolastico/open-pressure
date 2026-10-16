package org.openpressure.app.forwarding;

import android.app.Application;
import android.content.Context;
import androidx.work.WorkManager;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mockStatic;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.data.*;
import org.openpressure.protocol.BloodPressure;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real SQLite transactions; all identifiers and readings are synthetic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class HttpForwardingStoreTest {
    private Context context;
    private ReadingStore store;
    private final String endpoint="https://example.invalid/measurements";
    private final byte[] packet={0,120,0,80,0,93,0};
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        store=new ReadingStore(context);
        ExperimentalConsent.accept(context);
    }
    @After public void close() { store.close(); }
    private boolean add() { return store.add("synthetic-cuff","Synthetic",BloodPressure.parse(packet),packet,"vendor-history",1); }
    private int count(String table) {
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)) { c.moveToFirst(); return c.getInt(0); }
    }
    @Test public void passthroughQueuesWithoutHistoryHealthOrPacketDiagnostics() {
        HttpForwardingSettings.save(context,endpoint,"Bearer synthetic-secret",false);
        AtomicInteger localChanges=new AtomicInteger(); store.setChangeListener(localChanges::incrementAndGet);
        assertTrue(add());
        assertEquals(0,store.historyCount(0));
        assertEquals(0,localChanges.get());
        assertEquals(0,store.healthPending(1,10).size());
        store.packet("synthetic-cuff","synthetic-characteristic",packet,"synthetic note");
        assertEquals(0,count("packets"));
        assertEquals(1,count("http_outbox"));
    }
    @Test public void forwardingDefaultsOffAndNeverBackfillsHistory() {
        assertTrue(add());
        HttpForwardingSettings.save(context,endpoint,"",true);
        assertFalse(add());
        assertEquals(1,store.historyCount(0));
        assertEquals(0,count("http_outbox"));
    }
    @Test public void manualPassthroughAlsoQueuesWithoutNotesOrHistory() {
        HttpForwardingSettings.save(context,endpoint,"",false);
        store.addManual(120,80,65.0,1,LocalDateTime.of(2026,1,2,3,4),"must stay private");
        assertEquals(0,store.historyCount(0));
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT payload FROM http_outbox",null)) {
            assertTrue(c.moveToFirst()); assertFalse(c.getString(0).contains("must stay private"));
        }
    }
    @Test public void storageFailureRollsBackQueueAndHistoryAndPropagates() {
        HttpForwardingSettings.save(context,endpoint,"",true);
        SQLiteDatabase db=store.getWritableDatabase();
        db.execSQL("CREATE TRIGGER reject_reading BEFORE INSERT ON readings BEGIN SELECT RAISE(ABORT,'synthetic disk failure'); END");
        try { add(); fail("A database failure must propagate"); } catch(android.database.sqlite.SQLiteException expected) { }
        assertEquals(0,count("http_outbox")); assertEquals(0,store.historyCount(0));
    }
    @Test public void queueFailureRollsBackHistoryAndPropagates() {
        HttpForwardingSettings.save(context,endpoint,"",true);
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_queue BEFORE INSERT ON http_outbox BEGIN SELECT RAISE(ABORT,'synthetic disk failure'); END");
        try { add(); fail("A database failure must propagate"); } catch(android.database.sqlite.SQLiteException expected) { }
        assertEquals(0,count("http_outbox")); assertEquals(0,store.historyCount(0));
    }
    @Test public void automaticImportsHonorDeletionReceiptsWhileExplicitRestoreCanQueue() {
        assertTrue(add()); store.delete(store.readings(0).get(0).id);
        HttpForwardingSettings.save(context,endpoint,"",false);
        assertFalse(store.add("synthetic-cuff","Synthetic",BloodPressure.parse(packet),packet,"vendor-history",1,true));
        assertEquals(0,count("http_outbox"));
        assertTrue(add()); assertEquals(1,count("http_outbox"));
    }
    @Test public void forwardingListenerSeesOnlyCommittedNewEventsAndLocalListenerOnlyHistory() {
        HttpForwardingSettings.save(context,endpoint,"",false);
        AtomicInteger forwards=new AtomicInteger(),locals=new AtomicInteger();
        store.setChangeListener(locals::incrementAndGet);
        store.setForwardingListener(() -> { assertEquals(1,store.httpOutbox().count(endpoint)); forwards.incrementAndGet(); });
        assertTrue(add()); assertFalse(add()); assertEquals(1,forwards.get()); assertEquals(0,locals.get());
        HttpForwardingSettings.save(context,endpoint,"",true);
        assertTrue(add()); assertEquals(1,forwards.get()); assertEquals(1,locals.get());
        assertEquals(1,store.httpOutbox().count(endpoint));
    }
    @Test public void missingExperimentalConsentPreservesLocalHistoryInsteadOfPassthrough() {
        HttpForwardingSettings.save(context,endpoint,"",false);
        context.getSharedPreferences("experimental_consent",0).edit().clear().commit();
        assertTrue(add()); assertEquals(1,store.historyCount(0)); assertEquals(0,store.httpOutbox().count(endpoint));
    }
    @Test public void ingestionResultRecordsCommittedStorageAndQueueOutcomesAcrossSettingsChanges() throws Exception {
        HttpForwardingSettings.save(context,endpoint,"",false);
        ReadingStore.IngestionResult queued=addResult("synthetic-cuff");
        HttpForwardingSettings.save(context,"","",true);
        assertOutcome(queued,false,true);
        assertOutcome(addResult("synthetic-cuff"),true,false);
        HttpForwardingSettings.save(context,endpoint,"",true);
        assertOutcome(addResult("synthetic-cuff-2"),true,true);
        assertOutcome(addResult("synthetic-cuff-2"),false,false);
        assertOutcome(queued,false,true);
    }
    private ReadingStore.IngestionResult addResult(String device) {
        return store.addResult(device,"Synthetic",BloodPressure.parse(packet),packet,"vendor-history",1,false);
    }
    private void assertOutcome(ReadingStore.IngestionResult result,boolean stored,boolean queued) {
        assertEquals(stored,result.stored); assertEquals(queued,result.queued); assertEquals(stored || queued,result.accepted());
    }
    @Test public void unavailableSchedulerCannotUndoCommittedPassthroughAcceptance() {
        HttpForwardingSettings.save(context,endpoint,"",false);
        HttpForwardingSync sync=new HttpForwardingSync(context,store); store.setForwardingListener(sync::onRecordsChanged);
        try(MockedStatic<WorkManager> unavailable=mockStatic(WorkManager.class)) {
            unavailable.when(() -> WorkManager.getInstance(context)).thenThrow(new IllegalStateException("synthetic scheduler unavailable"));
            assertTrue(add()); assertEquals(1,store.httpOutbox().count(endpoint)); assertEquals(0,store.historyCount(0));
            sync.refresh(); sync.retryNow();
            assertEquals(1,store.httpOutbox().count(endpoint));
        }
    }
    @Test public void upgradingV4KeepsHistoryAndHealthMetadata() {
        assertTrue(add());
        long id=store.readings(0).get(0).id;
        store.prepareHealthExport(id,store.readings(0).get(0).fingerprint,"synthetic-client","2026-01-02T03:04:00Z",0);
        store.remember("synthetic-cuff","Synthetic","synthetic-model",1);
        store.packet("synthetic-cuff","synthetic-characteristic",packet,"synthetic note");
        SQLiteDatabase db=store.getWritableDatabase();
        db.execSQL("INSERT INTO reading_tombstones(receipt) VALUES('synthetic-old-hash')");
        db.execSQL("DROP TABLE IF EXISTS http_outbox"); db.execSQL("DROP TABLE IF EXISTS http_receipts"); db.setVersion(4);
        store.close(); store=new ReadingStore(context);
        assertEquals(5,store.getReadableDatabase().getVersion());
        assertEquals(1,store.historyCount(0)); assertEquals("synthetic-client",store.healthPending(1,10).get(0).healthClientId);
        assertEquals(0,count("http_outbox")); assertEquals(0,count("http_receipts"));
        assertEquals(1,count("packets")); assertEquals(1,count("devices")); assertEquals(1,count("reading_tombstones"));
    }
}
