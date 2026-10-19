package org.openpressure.app.data;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.openpressure.app.forwarding.*;
import org.openpressure.protocol.BloodPressure;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Durable ownership and transactional isolation, using real SQLite and synthetic measurements. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class VirtualUsersTest {
    private Context context;
    private ReadingStore store;
    @Before public void setup() { context=RuntimeEnvironment.getApplication(); store=new ReadingStore(context); ExperimentalConsent.accept(context); }
    @After public void close() { store.close(); }
    @Test public void freshSchemaSupportsDurableOwnershipWithoutLosingLegacyHistory() {
        store.addManual(120,80,65.0,1,java.time.LocalDateTime.of(2026,1,2,3,4),"synthetic note");
        assertEquals("Virtual users require the recoverable ownership schema",8,store.getReadableDatabase().getVersion());
        assertEquals(1,store.historyCount(0));
    }

    private int enable() { int id=store.createVirtualUser("Alex").id; store.setVirtualUsersEnabled(true); return id; }
    private void add(int day) {
        // Manual readings provide explicit synthetic timestamps and unique event identities.
        store.addManual(120,80,65.0,2,LocalDateTime.of(2026,1,day,12,0),"synthetic private note");
    }
    private boolean cuff() { byte[] raw={0,120,0,80,0,93,0}; return store.add("synthetic","Synthetic",BloodPressure.parse(raw),raw,"vendor-history",2); }
    private int count(String table) { try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)) { c.moveToFirst(); return c.getInt(0); } }
    @Test public void profilesHaveStableUnlimitedIdsAndRequireExplicitReplacement() {
        assertFalse(store.virtualUsersEnabled());
        try { store.setVirtualUsersEnabled(true); fail("At least one person required"); } catch(IllegalStateException expected) { }
        int a=enable(),b=store.createVirtualUser("Blair").id,c=store.createVirtualUser("Casey").id;
        long revision=store.virtualUsersRevision(); assertEquals(1,revision); store.setVirtualUsersEnabled(true); assertEquals(revision,store.virtualUsersRevision());
        store.setVirtualUsersEnabled(false); store.setVirtualUsersEnabled(true); assertEquals(revision+2,store.virtualUsersRevision());
        assertEquals(3,store.virtualUsers().size());
        store.renameVirtualUser(b,"  Renamed  "); assertEquals("Renamed",store.virtualUsers().get(1).name);
        add(1); store.assignPending(store.nextPendingAssignment().id,b);
        Reading assigned=store.readings(-b).get(0); assertTrue(store.hasReadingIdentity(assigned.id,assigned.fingerprint,-b));
        store.deleteVirtualUser(b,c); assertFalse(store.hasReadingIdentity(assigned.id,assigned.fingerprint,-b)); assertTrue(store.hasReadingIdentity(assigned.id,assigned.fingerprint,-c));
        assertEquals(Integer.valueOf(c),store.readings(-c).get(0).virtualUserId);
        assertEquals("Casey",store.readings(-c).get(0).virtualUserName);
        assertEquals(0,store.historyCount(-b));
        store.deleteVirtualUser(a,c);
        try { store.deleteVirtualUser(c,c); fail("Last person cannot be removed"); } catch(IllegalArgumentException | IllegalStateException expected) { }
        assertTrue(store.virtualUsersEnabled()); assertEquals(1,store.virtualUsers().size());
        assertTrue(store.createVirtualUser("Fourth").id>c);
        try { store.createVirtualUser("  "); fail("Blank names must be rejected"); } catch(IllegalArgumentException expected) { }
    }
    @Test public void queueSurvivesModeChangeRestartAndIgnoredPromptsInMeasurementOrder() {
        int owner=enable(); add(3); add(1); add(2);
        assertEquals(3,store.pendingAssignmentsCount()); assertEquals(0,store.historyCount(0));
        assertEquals("2026-01-01T12:00",store.nextPendingAssignment().time);
        store.setVirtualUsersEnabled(false); store.close(); store=new ReadingStore(context);
        assertEquals(3,store.pendingAssignmentsCount());
        for(int day=1;day<=3;day++) { Reading pending=store.nextPendingAssignment(); assertEquals("2026-01-0"+day+"T12:00",pending.time); store.assignPending(pending.id,owner); }
        assertNull(store.nextPendingAssignment()); assertEquals(3,store.historyCount(-owner));
        assertEquals(Integer.valueOf(2),store.readings(-owner).get(0).user);
    }
    @Test public void pendingAndAssignedCuffImportsNeverPromptTwiceIncludingAfterAcknowledgment() {
        int owner=enable(); HttpForwardingSettings.save(context,"https://example.invalid/original","Bearer synthetic",false);
        assertTrue(cuff()); assertFalse(cuff()); assertEquals(1,store.pendingAssignmentsCount());
        store.assignPending(store.nextPendingAssignment().id,owner);
        HttpOutbox.Entry entry=store.httpOutbox().pending("https://example.invalid/original",10).get(0);
        store.httpOutbox().acknowledge(entry); store.close(); store=new ReadingStore(context);
        assertFalse(cuff()); assertEquals(0,store.pendingAssignmentsCount()); assertEquals(0,store.historyCount(0));
        HttpForwardingSettings.save(context,"","",true);
        assertTrue(cuff()); assertEquals(1,store.historyCount(-owner)); assertEquals(0,store.pendingAssignmentsCount());
    }
    @Test public void assignmentUsesCapturedDestinationPolicyAndStripsPassthroughDiagnostics() throws Exception {
        int owner=enable(); String original="https://example.invalid/original",changed="https://example.invalid/changed";
        HttpForwardingSettings.save(context,original,"Bearer never-stage-this",false); add(1);
        Reading pending=store.nextPendingAssignment(); assertEquals("",pending.notes); assertEquals("",pending.raw); assertTrue(pending.fingerprint.matches("[0-9a-f]{64}"));
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT payload,endpoint FROM assignment_inbox",null)) { assertTrue(c.moveToFirst()); assertFalse(c.getString(0).contains("private note")); assertFalse(c.getString(0).contains("never-stage-this")); assertEquals(original,c.getString(1)); }
        HttpForwardingSettings.save(context,changed,"",true); store.assignPending(pending.id,owner);
        assertEquals(0,store.historyCount(0)); assertEquals(0,store.httpOutbox().count(changed));
        JSONObject payload=new JSONObject(store.httpOutbox().pending(original,10).get(0).payload);
        assertEquals(owner,payload.getInt("profile_id")); assertEquals("Alex",payload.getString("profile_name")); assertEquals(2,payload.getInt("user"));
        assertEquals(16,payload.length()); assertEquals(pending.fingerprint,payload.getString("id"));
    }
    @Test public void assignmentFailurePreservesInboxAndDoesNotExposeHistoryOrQueue() {
        int owner=enable(); HttpForwardingSettings.save(context,"https://example.invalid/original","",true); add(1);
        long id=store.nextPendingAssignment().id;
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_assignment BEFORE INSERT ON readings BEGIN SELECT RAISE(ABORT,'synthetic full disk'); END");
        try { store.assignPending(id,owner); fail("Persistence failure must propagate"); } catch(SQLiteException expected) { }
        assertEquals(1,store.pendingAssignmentsCount()); assertEquals(0,store.historyCount(0)); assertEquals(0,count("http_outbox")); assertEquals(0,count("assignment_receipts"));
        store.getWritableDatabase().execSQL("DROP TRIGGER fail_assignment"); store.assignPending(id,owner);
        assertEquals(0,store.pendingAssignmentsCount()); assertEquals(1,store.historyCount(-owner)); assertEquals(1,count("http_outbox"));
    }
    @Test public void ingestionAndProfileListenersOnlyObserveCommittedRelevantChanges() {
        int owner=enable(); AtomicInteger profiles=new AtomicInteger(),locals=new AtomicInteger(),uploads=new AtomicInteger();
        store.setProfileListener(() -> { assertTrue(store.pendingAssignmentsCount()>0 || store.historyCount(0)>0); profiles.incrementAndGet(); });
        store.setChangeListener(locals::incrementAndGet); store.setForwardingListener(uploads::incrementAndGet);
        byte[] raw={0,120,0,80,0,93,0}; ReadingStore.IngestionResult result=store.addResult("synthetic","Synthetic",BloodPressure.parse(raw),raw,"vendor-history",1,false);
        assertTrue(result.pending); assertTrue(result.accepted()); assertFalse(result.stored); assertFalse(result.queued);
        assertEquals(1,profiles.get()); assertEquals(0,locals.get()); assertEquals(0,uploads.get());
        store.assignPending(store.nextPendingAssignment().id,owner); assertEquals(1,locals.get()); assertEquals(2,profiles.get()); assertEquals(0,uploads.get());
    }
    @Test public void profileFiltersAndHealthQueriesIsolateOwnersAndLegacyUnassignedHistory() {
        add(1); int owner=enable(),other=store.createVirtualUser("Blair").id;
        add(2); store.assignPending(store.nextPendingAssignment().id,owner); add(3); store.assignPending(store.nextPendingAssignment().id,other); add(4);
        assertEquals(3,store.historyCount(0)); assertEquals(1,store.healthPending(-owner,20).size()); assertEquals(1,store.healthPending(-other,20).size());
        assertEquals(0,store.healthPending(2,20).size()); assertEquals(1,store.historyCount(-owner));
        Reading own=store.readings(-owner).get(0); store.markHealthSkipped(own.id,own.fingerprint,"synthetic rejection");
        assertEquals(1,store.healthSkippedCount(-owner)); assertEquals(0,store.healthSkippedCount(-other)); assertEquals("synthetic rejection",store.healthLastError(-owner));
        store.retryHealthSkipped(-other); assertEquals(1,store.healthSkippedCount(-owner)); store.retryHealthSkipped(-owner); assertEquals(0,store.healthSkippedCount(-owner));
        assertTrue(store.export(-owner,0).contains("Alex")); assertFalse(store.export(-owner,0).contains("Blair"));
        store.renameVirtualUser(owner,"Renamed"); assertEquals("Renamed",store.readings(-owner).get(0).virtualUserName);
        assertEquals(1,store.clearHistory(-owner)); assertEquals(2,store.historyCount(0)); assertEquals(1,store.pendingAssignmentsCount()); assertEquals(1,store.historyCount(-other));
    }
    @Test public void localPolicyIsCapturedBeforeSettingsChangeAndKeepsNotesAfterAssignment() {
        int owner=enable(); add(1);
        HttpForwardingSettings.save(context,"https://example.invalid/later","",false);
        store.assignPending(store.nextPendingAssignment().id,owner);
        assertEquals("synthetic private note",store.readings(-owner).get(0).notes); assertEquals(0,count("http_outbox"));
    }
    @Test public void pendingPersistenceFailureIsNeverAcceptedAndAssignmentRejectsUnknownPerson() {
        int owner=enable();
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_pending BEFORE INSERT ON assignment_inbox BEGIN SELECT RAISE(ABORT,'synthetic full disk'); END");
        try { cuff(); fail("Persistence failure must propagate"); } catch(SQLiteException expected) { }
        assertEquals(0,store.pendingAssignmentsCount()); assertEquals(0,store.historyCount(0));
        store.getWritableDatabase().execSQL("DROP TRIGGER reject_pending"); assertTrue(cuff());
        try { store.assignPending(store.nextPendingAssignment().id,owner+500); fail("Unknown person must not consume measurement"); } catch(IllegalArgumentException expected) { }
        assertEquals(1,store.pendingAssignmentsCount());
    }
    @Test public void profileDeletionFailureRollsBackHistoryAndReceiptOwnership() {
        int owner=enable(),other=store.createVirtualUser("Blair").id; assertTrue(cuff()); store.assignPending(store.nextPendingAssignment().id,owner);
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_profile_delete BEFORE DELETE ON virtual_users BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try { store.deleteVirtualUser(owner,other); fail("Profile deletion failure must propagate"); } catch(SQLiteException expected) { }
        assertEquals(1,store.historyCount(-owner)); assertEquals(0,store.historyCount(-other)); assertEquals(2,store.virtualUsers().size());
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT profile_id FROM assignment_receipts",null)) { assertTrue(c.moveToFirst()); assertEquals(owner,c.getInt(0)); }
    }
    @Test public void olderForwardedEventsDoNotBecomeNewAssignmentPrompts() {
        String endpoint="https://example.invalid/original"; HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(cuff());
        store.httpOutbox().acknowledge(store.httpOutbox().pending(endpoint,10).get(0)); enable();
        assertFalse(cuff()); assertEquals(0,store.pendingAssignmentsCount());
    }
    @Test public void missingMeasurementTimeFallsBackToOriginalReceiptOrder() {
        enable(); assertTrue(cuff()); add(1);
        assertEquals("2026-01-01T12:00",store.nextPendingAssignment().time);
        store.assignPending(store.nextPendingAssignment().id,store.virtualUsers().get(0).id);
        assertNull(store.nextPendingAssignment().time);
    }
    @Test public void v5UpgradePreservesHistoryOutboxReceiptsAndHealthIdentity() {
        add(1); Reading legacy=store.readings(0).get(0); store.prepareHealthExport(legacy.id,legacy.fingerprint,"synthetic-client","2026-01-01T12:00:00Z",0);
        String endpoint="https://example.invalid/original"; HttpForwardingSettings.save(context,endpoint,"",false); assertTrue(cuff());
        String event=store.httpOutbox().pending(endpoint,10).get(0).key;
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
        db.execSQL("INSERT INTO http_receipts(destination,event_key) VALUES('synthetic-destination','synthetic-event')");
        db.execSQL("DROP TABLE assignment_receipts"); db.execSQL("DROP TABLE assignment_inbox"); db.execSQL("DROP TABLE profile_configuration"); db.execSQL("DROP INDEX reading_profile");
        db.execSQL("ALTER TABLE readings DROP COLUMN virtual_user_id"); db.execSQL("DROP TABLE virtual_users"); db.execSQL("ALTER TABLE devices DROP COLUMN users"); db.execSQL("ALTER TABLE devices DROP COLUMN reported_model"); db.setVersion(5);
        store.close(); store=new ReadingStore(context);
        assertEquals(8,store.getReadableDatabase().getVersion()); assertFalse(store.virtualUsersEnabled()); assertEquals(0,store.virtualUsersRevision());
        assertEquals(1,store.historyCount(0)); assertNull(store.readings(0).get(0).virtualUserId);
        assertEquals("synthetic-client",store.healthPending(2,10).get(0).healthClientId); assertEquals(event,store.httpOutbox().pending(endpoint,10).get(0).key);
        assertEquals(1,count("http_receipts")); assertEquals(0,store.pendingAssignmentsCount());
    }

    @Test public void unavailableNotificationSchedulingCannotInvalidateDurableAcceptance() {
        int owner=enable(); AtomicInteger notifications=new AtomicInteger(),forwards=new AtomicInteger();
        HttpForwardingSettings.save(context,"https://example.invalid/original","",true);
        store.setProfileListener(() -> { notifications.incrementAndGet(); throw new IllegalStateException("synthetic notification failure"); });
        assertTrue(cuff()); assertEquals(1,store.pendingAssignmentsCount());
        store.setChangeListener(() -> { throw new IllegalStateException("synthetic scheduling failure"); });
        store.setForwardingListener(() -> { forwards.incrementAndGet(); throw new IllegalStateException("synthetic worker failure"); });
        store.assignPending(store.nextPendingAssignment().id,owner);
        assertEquals(1,store.historyCount(-owner)); assertEquals(0,store.pendingAssignmentsCount());
        assertEquals(2,notifications.get()); assertEquals(1,forwards.get()); assertEquals(1,count("http_outbox"));
    }

    @Test public void acknowledgedLegacyEventRequiresOwnerBeforeForwardingToNewDestination() throws Exception { legacyEventChangingDestination(true); }
    @Test public void queuedLegacyEventRequiresOwnerBeforeForwardingToNewDestination() throws Exception { legacyEventChangingDestination(false); }
    private void legacyEventChangingDestination(boolean acknowledged) throws Exception {
        String original="https://example.invalid/original",changed="https://example.invalid/new-destination";
        HttpForwardingSettings.save(context,original,"",false); assertTrue(cuff());
        HttpOutbox.Entry originalEvent=store.httpOutbox().pending(original,10).get(0);
        if(acknowledged) store.httpOutbox().acknowledge(originalEvent);
        int owner=enable(); assertFalse(cuff()); assertEquals(0,store.pendingAssignmentsCount());
        HttpForwardingSettings.save(context,changed,"",false);
        assertTrue(cuff()); assertEquals("A new destination needs explicit ownership",1,store.pendingAssignmentsCount());
        assertEquals(0,store.httpOutbox().count(changed)); assertEquals(0,store.historyCount(0));
        store.close(); store=new ReadingStore(context); store.assignPending(store.nextPendingAssignment().id,owner);
        HttpOutbox.Entry assigned=store.httpOutbox().pending(changed,10).get(0); JSONObject payload=new JSONObject(assigned.payload);
        assertEquals(owner,payload.getInt("profile_id")); assertEquals("Alex",payload.getString("profile_name")); assertEquals(originalEvent.key,assigned.key);
        assertEquals(0,store.pendingAssignmentsCount()); assertEquals(0,store.historyCount(0));
        assertFalse(cuff()); assertEquals(0,store.pendingAssignmentsCount()); assertEquals(1,store.httpOutbox().count(changed));
    }

}
