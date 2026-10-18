package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.R;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.PairingKeys;
import org.openpressure.app.data.VendorPairingKeys;
import org.openpressure.app.forwarding.HttpForwardingSettings;
import org.openpressure.app.ui.MonitorUi;
import org.openpressure.protocol.*;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Real service, ATT queue, protocol parser and SQLite store; only the Bluetooth peer is simulated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceImportTest {
    private MonitorService monitor;
    private ReadingStore store;
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private BluetoothGattService vendor;
    private BluetoothGattCharacteristic clock,unlock;
    private final List<Write> writes=new ArrayList<>();
    private final List<BluetoothGattCharacteristic> rx=new ArrayList<>();
    private boolean rejectEnd,rejectHistory;
    private final byte[] memory=new byte[0x10000];
    private int errorAddress=-1;
    // Synthetic 130/78 mmHg, pulse 67, 2025-07-09 06:14:38/39/40 records with valid additive checksums (byte 14).
    private static final String RECORD_A="694e4319261da603000000000000ff00",RECORD_B="694e4319261da7030000000000000000",RECORD_C="694e4319261da8030000000000000100";
    private static final class Write {
        final BluetoothGattCharacteristic characteristic;
        final byte[] bytes;
        Write(BluetoothGattCharacteristic characteristic,byte[] bytes) { this.characteristic=characteristic; this.bytes=bytes.clone(); }
    }
    @SuppressWarnings("unchecked")
    @Before public void setup() throws Exception {
        monitor=Robolectric.buildService(MonitorService.class).get();
        AutoImportSettings.setMode(monitor,AutoImportSettings.OFF);
        store=new ReadingStore(monitor);
        gatt=mock(BluetoothGatt.class);
        set("store",store); set("gatt",gatt);
        set("operations",new OperationQueue(error -> call("fail",new Class<?>[]{String.class},error)));
        callback=(BluetoothGattCallback)get("callback");
        attach("HEM-7156T");
        BluetoothGattService time=new BluetoothGattService(Uuids.CTS,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        clock=characteristic(time,Uuids.CURRENT_TIME,BluetoothGattCharacteristic.PROPERTY_WRITE);
        when(gatt.getService(Uuids.CTS)).thenReturn(time);
        when(gatt.getService(vendor.getUuid())).thenReturn(vendor);
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class),any(byte[].class),anyInt())).thenAnswer(invocation -> {
            Write write=new Write(invocation.getArgument(0),invocation.getArgument(1)); writes.add(write);
            boolean busy=write.bytes.length==8 && (rejectEnd && write.bytes[1]==15 || rejectHistory && write.characteristic.getService()==vendor);
            return busy?BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY:BluetoothStatusCodes.SUCCESS;
        });
        // Default memory: two consecutive records in bank 1 that the index reports as populated.
        index(new int[]{2},new long[]{2});
        put(0x98,RECORD_A); put(0xa8,RECORD_B);
    }
    /** Binds the service to a fresh verified vendor session for {@code name}, with erased synthetic memory. */
    @SuppressWarnings("unchecked")
    private void attach(String name) throws Exception {
        VendorMemoryModel model=VendorMemoryModel.identify(name);
        set("gatt",gatt); set("vendorModel",model); set("verifiedModel",name); set("negotiatedMtu",243);
        monitor.profile=ProtocolProfile.VENDOR_MEMORY; monitor.address="AA:BB:CC:DD:EE:FF"; monitor.connectedName="Synthetic monitor";
        vendor=new BluetoothGattService(VendorWlpSession.service(model),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        List<BluetoothGattCharacteristic> tx=(List<BluetoothGattCharacteristic>)get("vendorTx"),vendorRx=(List<BluetoothGattCharacteristic>)get("vendorRx");
        tx.clear(); vendorRx.clear(); rx.clear();
        for(UUID id:VendorWlpSession.txChannels(model)) tx.add(characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_WRITE));
        for(UUID id:VendorWlpSession.rxChannels(model)) rx.add(characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_NOTIFY));
        vendorRx.addAll(rx);
        unlock=characteristic(vendor,VendorWlpSession.unlockCharacteristic(),BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        set("handshake",unlock);
        when(gatt.getService(vendor.getUuid())).thenReturn(vendor);
        Arrays.fill(memory,(byte)255); errorAddress=-1;
    }
    @After public void cleanup() {
        if(monitor!=null) monitor.disconnect();
        if(store!=null) store.close();
    }
    @Test public void pairingReadsPopulatedSlotsThenUpdatesClockBeforeTheOnlyEnd() throws Exception {
        pair(); List<byte[]> reads=importHistory();
        assertEquals(1,reads.size()); assertArrayEquals("both populated records in one 32-byte block",Wlp.read(0x98,32),reads.get(0));
        assertSame(clock,last().characteristic);
        assertEquals(2,store.historyCount(0)); assertEquals(2,store.historyCount(1)); assertEquals(0,store.historyCount(2));
        assertEquals(MonitorService.ImportMode.INDEX,monitor.importMode); assertEquals(2,monitor.indexStoredCount);
        assertTrue(monitor.historyRunning); assertEquals(0,countOpcode(15));
        writeDone(last(),BluetoothGatt.GATT_SUCCESS);
        assertEquals(15,last().bytes[1]);
        dataReply(last(),hex("088f000000000087"),true);
        assertImported();
        assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getString(R.string.monitor_check_clock)));
        assertEquals(1,countOpcode(0)); assertEquals(1,countOpcode(15));
        // A late error for END and the cuff's normal disconnect must retain the completed result.
        callback.onCharacteristicWrite(gatt,last().characteristic,133); idle();
        assertImported();
        callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle(); assertImported();
    }
    @Test public void rejectedClockDoesNotAbortHistoryAndItsWarningIsVisible() throws Exception {
        pair(); importHistory(); writeDone(last(),BluetoothGatt.GATT_WRITE_NOT_PERMITTED);
        dataReply(last(),hex("088f000000000087"),false);
        assertImported();
        assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getString(R.string.monitor_clock_failed)));
    }
    @Test public void passthroughQueuesBothBanksWithoutRetainingHistoryHealthExportsOrPackets() throws Exception {
        String endpoint="https://example.invalid/measurements";
        ExperimentalConsent.accept(monitor);
        HttpForwardingSettings.save(monitor,endpoint,"Bearer synthetic-test-token",false);
        pair(); importHistory(); writeDone(last(),0);
        dataReply(last(),hex("088f000000000087"),false);
        assertEquals("History transfer complete",monitor.status);
        assertEquals(2,monitor.addedInTransfer); assertEquals(2,monitor.receivedInTransfer);
        assertEquals(0,store.historyCount(0)); assertEquals(2,store.httpOutbox().count(endpoint));
        assertTrue(store.healthPending(1,10).isEmpty()); assertTrue(store.healthPending(2,10).isEmpty());
        assertEquals("received,device,characteristic,payload,note\n",store.diagnostics());
        String detail=MonitorUi.detail(monitor,monitor);
        assertTrue(detail.contains(monitor.getString(R.string.http_import_count,2)));
        assertFalse(detail.contains(monitor.getResources().getQuantityString(R.plurals.monitor_readings_saved,2,2)));
        assertFalse(detail.contains("synthetic-test-token"));
        HttpForwardingSettings.save(monitor,endpoint,"",true);
        assertEquals("A completed result must retain its actual storage outcome",detail,MonitorUi.detail(monitor,monitor));
    }
    @Test public void virtualUsersRetainBothBanksUntilExplicitAssignmentWithoutEarlyForwarding() throws Exception {
        String endpoint="https://example.invalid/measurements";
        ExperimentalConsent.accept(monitor); HttpForwardingSettings.save(monitor,endpoint,"",false);
        store.createVirtualUser("Synthetic A"); store.createVirtualUser("Synthetic B");
        int person=store.createVirtualUser("Synthetic C").id; store.setVirtualUsersEnabled(true);
        pair(); importHistory(); writeDone(last(),0); dataReply(last(),hex("088f000000000087"),false);
        assertEquals("History transfer complete",monitor.status);
        assertEquals(2,monitor.addedInTransfer); assertEquals(2,monitor.pendingInTransfer);
        assertEquals(0,monitor.storedInTransfer); assertEquals(0,monitor.queuedInTransfer);
        assertEquals(2,store.pendingAssignmentsCount()); assertEquals(0,store.historyCount(0));
        assertEquals(0,store.httpOutbox().count(endpoint));
        assertTrue(store.healthPending(-person,10).isEmpty());
        String detail=MonitorUi.detail(monitor,monitor);
        assertTrue(detail.contains(monitor.getString(R.string.vp_pending_count,2)));
        store.assignPending(store.nextPendingAssignment().id,person);
        store.assignPending(store.nextPendingAssignment().id,person);
        assertEquals(0,store.pendingAssignmentsCount()); assertEquals(2,store.httpOutbox().count(endpoint));
        assertEquals(0,store.historyCount(0)); assertEquals(detail,MonitorUi.detail(monitor,monitor));
    }
    @Test public void changingStoragePolicyDuringImportReportsActualSavedAndQueuedCounts() throws Exception {
        String endpoint="https://example.invalid/measurements";
        ExperimentalConsent.accept(monitor); HttpForwardingSettings.save(monitor,endpoint,"",true);
        spreadRecords();
        pair(); dataReply(last(),startReply(),false); indexReply(image()); dataReply(last(),memoryReply(last().bytes),false);
        HttpForwardingSettings.save(monitor,endpoint,"",false);
        assertEquals(1,answerReads().size()); writeDone(last(),0); dataReply(last(),hex("088f000000000087"),false);
        assertEquals("History transfer complete",monitor.status);
        assertEquals(1,store.historyCount(0)); assertEquals(2,store.httpOutbox().count(endpoint));
        String detail=MonitorUi.detail(monitor,monitor);
        assertTrue(detail.contains(monitor.getResources().getQuantityString(R.plurals.monitor_readings_saved,1,1)));
        assertTrue(detail.contains(monitor.getString(R.string.http_import_count,2)));
    }
    @Test public void queueStorageFailureDuringStandardMeasurementDisconnectsWithoutClaimingSuccess() throws Exception {
        String endpoint="https://example.invalid/measurements";
        ExperimentalConsent.accept(monitor); HttpForwardingSettings.save(monitor,endpoint,"",false);
        monitor.profile=ProtocolProfile.STANDARD_BP; monitor.ready=true; monitor.canHistory=true;
        BluetoothGattService bp=new BluetoothGattService(Uuids.BP,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic measurement=characteristic(bp,Uuids.MEASUREMENT,BluetoothGattCharacteristic.PROPERTY_INDICATE);
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_upload BEFORE INSERT ON http_outbox BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END");
        callback.onCharacteristicChanged(gatt,measurement,hex("00780050005d00")); idle();
        assertEquals("Action failed",monitor.status); assertFalse(monitor.ready); assertFalse(monitor.canHistory);
        assertEquals(0,store.historyCount(0)); assertEquals(0,store.httpOutbox().count(endpoint)); assertEquals(0,monitor.addedInTransfer);
    }
    @Test public void missingClockIsReportedAsSkippedWithoutAnyClockWrite() throws Exception {
        when(gatt.getService(Uuids.CTS)).thenReturn(null);
        pair(); importHistory();
        assertEquals(15,last().bytes[1]);
        dataReply(last(),hex("088f000000000087"),false); assertImported();
        assertTrue(writes.stream().noneMatch(write -> write.characteristic==clock));
        assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getString(R.string.monitor_clock_skipped)));
    }
    @Test public void omronDateTimeFallbackRunsBeforeEndWhenCurrentTimeIsUnavailable() throws Exception {
        when(gatt.getService(Uuids.CTS)).thenReturn(null);
        BluetoothGattService time=new BluetoothGattService(Uuids.COMMAND,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        clock=characteristic(time,Uuids.DATE_TIME,BluetoothGattCharacteristic.PROPERTY_WRITE);
        when(gatt.getService(Uuids.COMMAND)).thenReturn(time);
        pair(); importHistory();
        assertSame(clock,last().characteristic); assertEquals(7,last().bytes.length);
        writeDone(last(),0); dataReply(last(),hex("088f000000000087"),false);
        assertImported(); assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getString(R.string.monitor_check_clock)));
    }
    @Test public void clockTimeoutAndBusyEndPreserveReadingsAndExposeBothWarnings() throws Exception {
        pair(); importHistory(); Write timedOut=last(); rejectEnd=true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));
        assertImported();
        String detail=MonitorUi.detail(monitor,monitor);
        assertTrue(detail.contains(monitor.getString(R.string.monitor_clock_failed)));
        assertTrue(detail.contains(monitor.getString(R.string.monitor_session_unconfirmed)));
        assertFalse(monitor.ready); assertNull(get("gatt"));
        callback.onCharacteristicWrite(gatt,timedOut.characteristic,0); idle();
        assertEquals(detail,MonitorUi.detail(monitor,monitor)); assertImported();
    }
    @Test public void disconnectDuringClockRetainsLocalizedWarningsAfterCleanup() throws Exception {
        pair(); importHistory();
        callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle(); assertImported();
        for(String language:new String[]{"en","de","ar"}) {
            Configuration configuration=new Configuration(monitor.getResources().getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(language));
            Context context=monitor.createConfigurationContext(configuration);
            String detail=MonitorUi.detail(context,monitor);
            assertTrue(detail.contains(context.getString(R.string.monitor_clock_failed)));
            assertTrue(detail.contains(context.getString(R.string.monitor_session_unconfirmed)));
            assertFalse(detail.contains("END")); assertFalse(detail.contains("GATT"));
            if(!language.equals("en")) assertNotEquals(monitor.getString(R.string.monitor_clock_failed),context.getString(R.string.monitor_clock_failed));
        }
    }
    @Test public void disconnectBeforeAllHistoryArrivesRemainsAnInterruptedImport() throws Exception {
        spreadRecords();
        pair(); dataReply(last(),startReply(),false); indexReply(image()); dataReply(last(),memoryReply(last().bytes),false);
        callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle();
        assertEquals("Transfer interrupted",monitor.status); assertFalse(monitor.historyRunning);
        assertEquals(1,store.historyCount(0)); assertFalse(monitor.sessionFinished);
        assertEquals(monitor.getString(R.string.monitor_partial_saved),MonitorUi.detail(monitor,monitor));
    }
    @Test public void cancellationIgnoresAlreadyQueuedCallbacksFromTheClosedConnection() throws Exception {
        spreadRecords();
        pair(); dataReply(last(),startReply(),false); indexReply(image()); dataReply(last(),memoryReply(last().bytes),false);
        Write pending=last(); int sent=writes.size(); monitor.disconnect();
        callback.onCharacteristicWrite(gatt,pending.characteristic,0);
        byte[] reply=memoryReply(pending.bytes);
        callback.onCharacteristicChanged(gatt,rx.get(0),Arrays.copyOf(reply,16));
        callback.onCharacteristicChanged(gatt,rx.get(1),Arrays.copyOfRange(reply,16,reply.length));
        idle();
        assertEquals("Disconnected",monitor.status); assertFalse(monitor.historyRunning);
        assertEquals(1,store.historyCount(0)); assertEquals(sent,writes.size());
    }
    @Test public void invalidEndIsNotReportedAsAcknowledgedEvenWhenHistoryWasSaved() throws Exception {
        pair(); importHistory(); writeDone(last(),0);
        dataReply(last(),hex("088f000000000186"),false);
        assertEquals("Action failed",monitor.status); assertEquals(2,store.historyCount(0));
        assertFalse(monitor.sessionFinished);
    }
    @Test public void standaloneClockFailureLeavesHistoryAvailableAndHasSpecificLocalizedStatus() throws Exception {
        call("vendorMemoryReady"); monitor.setClock(LocalDateTime.of(2026,10,16,12,0));
        writeDone(last(),BluetoothGatt.GATT_WRITE_NOT_PERMITTED);
        assertTrue(monitor.ready); assertTrue(monitor.canHistory);
        assertEquals(monitor.getString(R.string.monitor_time_not_updated),MonitorUi.title(monitor,monitor));
        assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getString(R.string.monitor_clock_failed)));
    }
    @Test public void standaloneClockTimeoutRequiresReconnectBeforeAnyHistoryStart() throws Exception {
        call("vendorMemoryReady"); monitor.setClock(LocalDateTime.of(2026,10,16,12,0));
        rejectHistory=true; // The missing clock callback leaves Android's ATT transport busy.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));
        assertFalse(monitor.ready); assertFalse(monitor.canHistory); assertNull(get("gatt"));
        int sent=writes.size(); monitor.syncHistory(-1);
        assertEquals(sent,writes.size()); assertEquals(0,store.historyCount(0));
        assertEquals(monitor.getString(R.string.monitor_time_not_updated),MonitorUi.title(monitor,monitor));
        String detail=MonitorUi.detail(monitor,monitor);
        assertTrue(detail.contains(monitor.getString(R.string.monitor_clock_failed)));
        assertTrue(detail.contains(monitor.getString(R.string.monitor_reconnect_next_time)));
    }
    @Test public void automaticImportUnlocksWithTheSavedKeySkipsTheClockAndCheckpointsAfterEnd() throws Exception {
        AutoImportTracker.Attempt attempt=automaticAttempt();
        connectWithSavedKey(); importHistory();
        assertEquals(15,last().bytes[1]);
        assertTrue(writes.stream().noneMatch(write -> write.characteristic==clock));
        dataReply(last(),hex("088f000000000087"),false);
        assertImported(); assertNull(get("automaticAttempt"));
        assertEquals(attempt.revision,((AutoImportTracker)get("automaticTracker")).completedRevision(monitor.address));
        assertEquals(attempt.revision,checkpoint());
        // A monitor that keeps the link open is released so listening can resume.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertNull(get("gatt")); assertImported();
    }
    @Test public void automaticImportWithARejectedSlotKeepsReadingsWithoutCheckpoint() throws Exception {
        byte[] invalid=hex(RECORD_B); invalid[1]=0; put(0xa8,HexFormat.of().formatHex(invalid)); // diastolic zero is rejected, not skipped
        automaticAttempt(); connectWithSavedKey(); importHistory(); assertEquals(1,monitor.receivedInTransfer);
        dataReply(last(),hex("088f000000000087"),false);
        assertEquals("History transfer incomplete",monitor.status); assertEquals(1,store.historyCount(0));
        assertNull(((AutoImportTracker)get("automaticTracker")).completedRevision(monitor.address));
        assertNull(checkpoint()); assertNull(get("automaticAttempt"));
    }
    @Test public void pairSessionFillsAShareableReportAndPromptsOnceAfterItEnds() throws Exception {
        DeviceReport report=new DeviceReport("Pair","test",35,"Synthetic phone");
        report.service(vendor.getUuid().toString(),Collections.emptyList()); report.model("HEM-7156T");
        set("report",report); set("reportAddress",monitor.address);
        pair(); importHistory(); writeDone(last(),0); dataReply(last(),hex("088f000000000087"),false);
        String json=monitor.deviceReport().toJson();
        assertTrue(json.contains("82 00 (2 bytes)") && json.contains("80 00 (2 bytes)") && json.contains("81 00 (2 bytes)"));
        assertTrue(json.contains("\"decoded_records\": 2") && json.contains("\"end_acknowledged\": true"));
        assertTrue(json.contains("\"status\": \"History transfer complete\""));
        assertFalse(json.contains(monitor.address)); assertFalse(json.contains("130"));
        assertFalse("no prompt while the monitor is still connected",monitor.deviceReportPromptDue());
        monitor.disconnect();
        assertTrue(monitor.deviceReportPromptDue());
        assertTrue("the last result survives the disconnect",monitor.deviceReport().toJson().contains("History transfer complete"));
        monitor.deviceReportPrompted();
        assertFalse(monitor.deviceReportPromptDue());
        DeviceReport repeat=new DeviceReport("Pair","test",35,"Synthetic phone");
        repeat.service(vendor.getUuid().toString(),Collections.emptyList());
        set("report",repeat); set("reportPrompted",false);
        assertFalse("each monitor is asked only once",monitor.deviceReportPromptDue());
        repeat.model("HEM-7380T1-EOSL");
        assertNull("the tested monitor is never asked for a report",monitor.deviceReport());
    }
    @Test public void paddedNegativeUnlockOnConnectTriesTheNextSavedKey() throws Exception {
        byte[] prior=new byte[16],pending=new byte[16]; Arrays.fill(prior,(byte)5); Arrays.fill(pending,(byte)7);
        VendorPairingKeys keys=vendorKeys(); keys.stage(prior); keys.confirm(prior); keys.stage(pending);
        List<byte[]> candidates=keys.candidates();
        set("vendorKeys",keys); set("vendorKeyCandidates",candidates);
        set("vendorUnlock",LegacyUnlock.connect(candidates.get(0))); call("sendLegacyUnlock");
        assertSame(unlock,last().characteristic); assertArrayEquals(unlockFrame(pending),last().bytes);
        writeDone(last(),0); callback.onCharacteristicChanged(gatt,unlock,hex("810100")); idle();
        assertSame(unlock,last().characteristic); assertArrayEquals("the padded rejection tries the prior key",unlockFrame(prior),last().bytes);
        writeDone(last(),0); callback.onCharacteristicChanged(gatt,unlock,hex("8100ff")); idle();
        assertTrue("a padded positive acknowledgment unlocks",(Boolean)get("ready"));
        assertNull(get("vendorUnlock"));
        List<byte[]> remaining=keys.candidates();
        assertEquals("the accepted prior key is confirmed and the pending one dropped",1,remaining.size());
        assertArrayEquals(prior,remaining.get(0));
        assertEquals("Connect never sends key programming",2,writes.stream().filter(write -> write.characteristic==unlock).count());
    }
    @Test public void paddedNegativeUnlockDuringPairNeverTriesAnotherKey() throws Exception {
        byte[] prior=new byte[16],pending=new byte[16]; Arrays.fill(prior,(byte)5); Arrays.fill(pending,(byte)7);
        VendorPairingKeys keys=vendorKeys(); keys.stage(prior); keys.confirm(prior); keys.stage(pending);
        set("pairingRequested",true);
        set("vendorKeys",keys); set("vendorKeyCandidates",keys.candidates());
        set("vendorUnlock",LegacyUnlock.pair(pending)); call("sendLegacyUnlock");
        for(byte[] reply:new byte[][]{hex("8200"),hex("8000"),hex("810100")}) {
            assertSame(unlock,last().characteristic); writeDone(last(),0);
            callback.onCharacteristicChanged(gatt,unlock,reply); idle();
        }
        assertFalse(monitor.historyRunning);
        assertEquals("a rejected Pair unlock sends no further key frame",3,writes.stream().filter(write -> write.characteristic==unlock).count());
        assertTrue("Pair never falls back to another saved key",writes.stream().noneMatch(write -> Arrays.equals(unlockFrame(prior),write.bytes)));
    }
    @Test public void connectImportsOnlyPopulatedSlots() throws Exception {
        attach("HEM-7155T");
        index(new int[]{2,1},new long[]{2,1});
        put(0x98,RECORD_A); put(0xa8,RECORD_B); put(0x458,RECORD_C);
        connectManually(); monitor.syncHistory(-1);
        List<byte[]> reads=importHistory();
        assertEquals(2,reads.size());
        assertArrayEquals("user 1: two populated slots in one 32-byte block",Wlp.read(0x98,32),reads.get(0));
        assertArrayEquals("user 2: one populated slot",Wlp.read(0x458,16),reads.get(1));
        assertEquals(MonitorService.ImportMode.INDEX,monitor.importMode); assertEquals(3,monitor.indexStoredCount);
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status);
        assertEquals(2,store.historyCount(1)); assertEquals(1,store.historyCount(2));
        assertTrue(monitor.detail.contains("INDEX"));
    }
    @Test public void erasedSlotInsidePopulatedRangeIsSkipped() throws Exception {
        put(0xa8,"ffffffffffffffffffffffffffffffff");
        pair(); List<byte[]> reads=importHistory();
        assertArrayEquals(Wlp.read(0x98,32),reads.get(0));
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status);
        assertEquals(0,(int)(Integer)get("wlpRejected")); assertEquals(1,store.historyCount(0)); assertEquals(1,monitor.receivedInTransfer);
    }
    @Test public void untrustedIndexFallsBackToFullSweep() throws Exception {
        index(new int[]{5},new long[]{1}); // position 5 cannot follow sequence 1 below capacity
        connectManually(); monitor.syncHistory(-1);
        List<byte[]> reads=importHistory();
        assertEquals(MonitorService.ImportMode.INDEX_FALLBACK,monitor.importMode); assertEquals(-1,monitor.indexStoredCount);
        assertEquals("every slot of bank 1 in 48-byte blocks",20,reads.size());
        for(int i=0;i<reads.size();i++) assertArrayEquals(Wlp.read(0x98+48*i,48),reads.get(i));
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(2,store.historyCount(0));
        assertTrue(store.diagnostics().contains("Index not trusted"));
    }
    @Test public void indexStatusErrorFallsBackToFullSweep() throws Exception {
        errorAddress=model().settingsStart;
        connectManually(); monitor.syncHistory(-1);
        dataReply(last(),startReply(),true);
        assertArrayEquals(Wlp.read(model().settingsStart,56),last().bytes);
        dataReply(last(),memoryReply(last().bytes),false);
        assertEquals(MonitorService.ImportMode.INDEX_FALLBACK,monitor.importMode);
        List<byte[]> reads=answerReads();
        assertEquals("every slot of bank 1 in 48-byte blocks",20,reads.size());
        for(int i=0;i<reads.size();i++) assertArrayEquals(Wlp.read(0x98+48*i,48),reads.get(i));
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(2,store.historyCount(0));
        assertEquals("the index is never read again",1,countReadsAt(model().settingsStart));
        assertTrue(store.diagnostics().contains("Index not read (device status)"));
    }
    @Test public void indexTimeoutFallsBackToFullSweep() throws Exception {
        connectManually(); monitor.syncHistory(-1);
        dataReply(last(),startReply(),true);
        Write index=last(); assertArrayEquals(Wlp.read(model().settingsStart,56),index.bytes);
        writeDone(index,0);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertEquals(MonitorService.ImportMode.INDEX_FALLBACK,monitor.importMode); assertNotSame(index,last());
        List<byte[]> reads=answerReads();
        assertEquals(20,reads.size()); assertArrayEquals(Wlp.read(0x98,48),reads.get(0));
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(2,store.historyCount(0));
        assertTrue(store.diagnostics().contains("Index not read (no reply)"));
    }
    @Test public void indexFallbackSweepErrorStillFails() throws Exception {
        errorAddress=0x98;
        connectManually(); monitor.syncHistory(-1);
        dataReply(last(),startReply(),true);
        writeDone(last(),0);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertArrayEquals(Wlp.read(0x98,48),last().bytes);
        dataReply(last(),memoryReply(last().bytes),false);
        assertEquals("Action failed",monitor.status); assertFalse(monitor.historyRunning);
    }
    @Test public void badIndexChecksumFallsBack() throws Exception {
        attach("HEM-7530T");
        index(new int[]{1},new long[]{1});
        memory[0x260+59]^=1; // logical byte 58 (block 3 checksum) sits at wire offset 59 in swapped pairs
        put(0x2e8,record14());
        connectManually(); monitor.syncHistory(-1);
        List<byte[]> reads=importHistory();
        assertEquals(MonitorService.ImportMode.INDEX_FALLBACK,monitor.importMode);
        assertEquals("1260-byte bank in 56-byte blocks",23,reads.size());
        assertArrayEquals(Wlp.read(0x2e8,56),reads.get(0)); assertArrayEquals(Wlp.read(0x2e8+22*56,28),reads.get(22));
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(1,store.historyCount(0));
    }
    @Test public void importUsersTwoReadsOnlyBankTwo() throws Exception {
        attach("HEM-7155T");
        index(new int[]{1,1},new long[]{1,1});
        put(0x98,RECORD_A); put(0x458,RECORD_C);
        selectUsers(2);
        connectManually(); assertEquals(2,monitor.importUsers); monitor.syncHistory(-1);
        List<byte[]> reads=importHistory();
        assertEquals(1,reads.size()); assertArrayEquals(Wlp.read(0x458,16),reads.get(0));
        assertEquals(1,monitor.indexStoredCount);
        finishWithClockAndEnd();
        assertEquals(0,store.historyCount(1)); assertEquals(1,store.historyCount(2));
    }
    @Test public void importUsersTwoOnOneUserModelReadsBankOne() throws Exception {
        index(new int[]{1},new long[]{1});
        selectUsers(2);
        connectManually(); monitor.syncHistory(-1);
        List<byte[]> reads=importHistory();
        assertEquals(1,reads.size()); assertArrayEquals("the only offered user is read",Wlp.read(0x98,16),reads.get(0));
        finishWithClockAndEnd();
        assertEquals(1,store.historyCount(1));
    }
    @Test public void sequenceZeroImportsNothingAndReadAllFindsRecords() throws Exception {
        index(new int[]{0},new long[]{0});
        connectManually(); monitor.syncHistory(-1);
        assertTrue(importHistory().isEmpty());
        assertEquals(MonitorService.ImportMode.INDEX,monitor.importMode); assertEquals(0,monitor.indexStoredCount);
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(0,store.historyCount(0));
        monitor.disconnect(); attach("HEM-7156T"); put(0x98,RECORD_A); put(0xa8,RECORD_B);
        connectManually(); monitor.readAllHistory();
        List<byte[]> reads=sweepHistory();
        assertEquals(MonitorService.ImportMode.FULL_SWEEP,monitor.importMode);
        assertEquals("bank 1, then the unconfirmed bank 2",40,reads.size());
        assertArrayEquals(Wlp.read(0x98,48),reads.get(0)); assertArrayEquals(Wlp.read(0x458,48),reads.get(20));
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(2,store.historyCount(0));
        assertFalse(monitor.unconfirmedBankSkipped);
    }
    @Test public void readAllWithACorruptSlotEndsCompleteAndCountsIt() throws Exception {
        byte[] corrupt=hex(RECORD_B); corrupt[14]^=1; put(0xa8,HexFormat.of().formatHex(corrupt));
        connectManually(); monitor.readAllHistory();
        assertEquals(40,sweepHistory().size());
        finishWithClockAndEnd();
        assertEquals(MonitorService.ImportMode.FULL_SWEEP,monitor.importMode);
        assertEquals("every slot was read to END","History transfer complete",monitor.status);
        assertEquals(1,monitor.rejectedSlots()); assertEquals(1,store.historyCount(0));
        assertEquals(monitor.getString(R.string.monitor_import_complete),MonitorUi.title(monitor,monitor));
        assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getResources().getQuantityString(R.plurals.monitor_import_slots_unreadable,1,1)));
    }
    @Test public void readAllRefusedDuringAutomaticImport() throws Exception {
        automaticAttempt(); connectWithSavedKey();
        assertFalse(monitor.canReadAll());
        int sent=writes.size(); monitor.readAllHistory(); idle();
        assertEquals(sent,writes.size()); assertEquals(MonitorService.ImportMode.INDEX,monitor.importMode);
    }
    @Test public void readAllRefusedWhileHistoryRuns() throws Exception {
        connectManually(); monitor.syncHistory(-1);
        int sent=writes.size(); monitor.readAllHistory();
        assertEquals(sent,writes.size()); assertEquals(MonitorService.ImportMode.INDEX,monitor.importMode);
    }
    @Test public void automaticImportUsesTheCurrentReadingsToImportChoice() throws Exception {
        store.remember(monitor.address,"Synthetic monitor","HEM-7155T",1);
        monitor.refreshAutomaticImports(); // caches the saved row (both users) while automatic imports are off
        store.setImportUsers(monitor.address,2); // the choice changes after the row was cached
        ExperimentalConsent.accept(monitor);
        shadowOf(monitor.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT);
        BluetoothAdapter adapter=monitor.getSystemService(BluetoothManager.class).getAdapter(); shadowOf(adapter).setEnabled(true);
        set("adapter",adapter); set("keys",new PairingKeys(monitor));
        // Only the presence of a key saved by an explicit Pair is checked before connecting.
        monitor.getSharedPreferences("pairing",Context.MODE_PRIVATE).edit().putString(monitor.address+"/vendor/HEM-7155T","synthetic").commit();
        AutoImportSettings.setMode(monitor,AutoImportSettings.NOTIFICATION);
        ScanCallback scan=new ScanCallback() {};
        set("automaticScan",scan); set("autoWatching",true); set("gatt",null);
        BluetoothDevice device=mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(monitor.address); when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        ScanRecord record=mock(ScanRecord.class); when(record.getManufacturerSpecificData(0x020e)).thenReturn(hex("0601010001010001"));
        ScanResult result=mock(ScanResult.class); when(result.getDevice()).thenReturn(device); when(result.getScanRecord()).thenReturn(record);
        call("acceptAutomaticAdvertisement",new Class<?>[]{ScanCallback.class,ScanResult.class},scan,result);
        AutoImportTracker.Attempt attempt=(AutoImportTracker.Attempt)get("automaticAttempt");
        assertNotNull("the advertisement started an automatic import",attempt); assertTrue((Boolean)get("automaticConnection"));
        assertEquals("the stored choice, not the cached row",2,monitor.importUsers);
        attach("HEM-7155T");
        index(new int[]{1,1},new long[]{1,1});
        put(0x98,RECORD_A); put(0x458,RECORD_C);
        connectWithSavedKey();
        List<byte[]> reads=importHistory();
        assertEquals(MonitorService.ImportMode.INDEX,monitor.importMode);
        assertEquals("bank 2 only",1,reads.size()); assertArrayEquals(Wlp.read(0x458,16),reads.get(0));
        assertEquals(15,last().bytes[1]); dataReply(last(),hex("088f000000000087"),false);
        assertEquals("History transfer complete",monitor.status); assertEquals(1,store.historyCount(2)); assertEquals(0,store.historyCount(1));
        assertEquals(attempt.revision,checkpoint());
    }
    @Test public void automaticCheckpointRequiresNoRejectedRecordsInFallback() throws Exception {
        index(new int[]{5},new long[]{1});
        byte[] corrupt=hex(RECORD_B); corrupt[14]^=1; put(0xa8,HexFormat.of().formatHex(corrupt));
        automaticAttempt(); connectWithSavedKey();
        importHistory();
        assertEquals(MonitorService.ImportMode.INDEX_FALLBACK,monitor.importMode);
        assertEquals(15,last().bytes[1]); dataReply(last(),hex("088f000000000087"),false);
        assertEquals("History transfer incomplete",monitor.status); assertEquals(1,store.historyCount(0));
        assertNull(checkpoint()); assertNull(get("automaticAttempt"));
    }
    @Test public void readAllSkipsUnconfirmedSecondBankOnError() throws Exception {
        errorAddress=0x458;
        connectManually(); monitor.readAllHistory();
        dataReply(last(),startReply(),true);
        assertEquals(20,answerReadsUntil(0x458).size());
        assertArrayEquals(Wlp.read(0x458,48),last().bytes);
        dataReply(last(),memoryReply(last().bytes),false);
        assertTrue(monitor.unconfirmedBankSkipped);
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(2,store.historyCount(0));
        assertEquals(0,countReadsAt(0x458+48)); assertTrue(monitor.unconfirmedBankSkipped);
    }
    @Test public void readAllSkipsUnconfirmedSecondBankOnTimeout() throws Exception {
        connectManually(); monitor.readAllHistory();
        dataReply(last(),startReply(),true);
        answerReadsUntil(0x458);
        writeDone(last(),0);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertTrue(monitor.unconfirmedBankSkipped); assertSame(clock,last().characteristic);
        finishWithClockAndEnd();
        assertEquals("History transfer complete",monitor.status); assertEquals(2,store.historyCount(0));
    }
    @Test public void errorStatusOnAConfirmedBankStillFails() throws Exception {
        errorAddress=0x98;
        connectManually(); monitor.readAllHistory();
        dataReply(last(),startReply(),true);
        dataReply(last(),memoryReply(last().bytes),false);
        assertEquals("Action failed",monitor.status); assertFalse(monitor.unconfirmedBankSkipped);
    }
    @Test public void indexImportNeverReadsTheUnconfirmedBank() throws Exception {
        index(new int[]{1,1},new long[]{1,1}); // a stray user-2 index entry on the one-user model
        connectManually(); monitor.syncHistory(-1);
        List<byte[]> reads=importHistory();
        assertEquals(1,reads.size()); assertArrayEquals(Wlp.read(0x98,16),reads.get(0));
    }
    @Test public void modelWithoutIndexSupportSweepsDirectly() throws Exception {
        attach("HEM-7600T");
        connectManually(); monitor.syncHistory(-1);
        List<byte[]> reads=sweepHistory();
        assertEquals(MonitorService.ImportMode.FULL_READ,monitor.importMode);
        assertArrayEquals("no index read; 56-byte record blocks",Wlp.read(0x2ac,56),reads.get(0));
        assertEquals(25,reads.size());
    }
    private void connectManually() throws Exception {
        call("vendorMemoryReady");
        assertEquals("Vendor history available",monitor.status); assertTrue(monitor.canReadAll());
    }
    private void selectUsers(int users) {
        store.remember(monitor.address,"Synthetic monitor",model().id,1); store.setImportUsers(monitor.address,users);
    }
    /** Accepts the clock write when one is sent, then acknowledges the single END. */
    private void finishWithClockAndEnd() {
        if(last().characteristic==clock) writeDone(last(),0);
        assertEquals(15,last().bytes[1]); dataReply(last(),hex("088f000000000087"),false);
    }
    private int countReadsAt(int at) { return (int)writes.stream().filter(write -> isRead(write) && address(write.bytes)==at).count(); }
    /** A 14-byte big-endian record (130/78, pulse 67, 2025-07-09 06:14:38) with its logical checksum, in wire order. */
    private static String record14() {
        byte[] wire=hex("4e6919431d2603a60000000000ff"); // dia, sys-25, year, pulse, date and time words
        byte[] logical=VendorMemoryModel.identify("HEM-7530T").logical(wire);
        logical[12]=(byte)VendorMemoryModel.checksum(logical,12);
        return HexFormat.of().formatHex(VendorMemoryModel.identify("HEM-7530T").logical(logical));
    }
    private static byte[] unlockFrame(byte[] key) {
        byte[] frame=new byte[17]; frame[0]=1; System.arraycopy(key,0,frame,1,16); return frame;
    }
    private AutoImportTracker.Attempt automaticAttempt() throws Exception {
        AutoImportSettings.setMode(monitor,AutoImportSettings.VISIBLE);
        OmronAdvertisement advertisement=OmronAdvertisement.parseManufacturerPayload(hex("0601010001010001"));
        AutoImportTracker.Attempt attempt=((AutoImportTracker)get("automaticTracker")).offer(monitor.address,advertisement,0);
        assertNotNull(attempt);
        set("automaticConnection",true); set("automaticAttempt",attempt);
        return attempt;
    }
    private void connectWithSavedKey() throws Exception {
        byte[] saved=new byte[16]; Arrays.fill(saved,(byte)5);
        VendorPairingKeys keys=vendorKeys(); keys.stage(saved); keys.confirm(saved);
        set("vendorKeys",keys); set("vendorKeyCandidates",keys.candidates());
        set("vendorUnlock",LegacyUnlock.connect(saved)); call("sendLegacyUnlock");
        assertSame(unlock,last().characteristic); assertEquals(1,last().bytes[0]);
        writeDone(last(),0); callback.onCharacteristicChanged(gatt,unlock,hex("8100")); idle();
        assertTrue(monitor.historyRunning); assertEquals(0,last().bytes[1]);
        assertEquals("Connect never sends key programming",1,writes.stream().filter(write -> write.characteristic==unlock).count());
    }
    private String checkpoint() { return monitor.getSharedPreferences("automatic-checkpoints",Context.MODE_PRIVATE).getString(monitor.address,null); }
    private VendorPairingKeys vendorKeys() {
        Map<String,byte[]> keys=new HashMap<>();
        VendorPairingKeys.Backend backend=new VendorPairingKeys.Backend() {
            public byte[] get(String name) { byte[] value=keys.get(name); return value==null?null:value.clone(); }
            public void put(String name,byte[] value) { keys.put(name,value.clone()); }
            public void forget(String name) { keys.remove(name); }
        };
        return new VendorPairingKeys(backend,monitor.address,"HEM-7156T");
    }
    private void pair() throws Exception {
        set("pairingRequested",true);
        set("vendorKeys",vendorKeys());
        set("vendorUnlock",LegacyUnlock.pair(new byte[16])); call("sendLegacyUnlock");
        for(byte[] reply:new byte[][]{hex("8200"),hex("8000"),hex("8100")}) {
            assertSame(unlock,last().characteristic); writeDone(last(),0);
            callback.onCharacteristicChanged(gatt,unlock,reply); idle();
        }
        assertTrue(monitor.historyRunning); assertEquals(0,last().bytes[1]);
    }
    /** Answers START, the two index reads and every populated-slot read from the synthetic memory. */
    private List<byte[]> importHistory() {
        dataReply(last(),startReply(),true);
        indexReply(image());
        return answerReads();
    }
    /** Answers START and every bank read of a sweep (Read all, or a model without index support). */
    private List<byte[]> sweepHistory() {
        dataReply(last(),startReply(),true);
        return answerReads();
    }
    /** Answers the 56-byte and 12-byte index reads with {@code image}, the first one spread over all four RX channels. */
    private void indexReply(byte[] image) {
        VendorMemoryModel model=model();
        assertArrayEquals("index read in one 56-byte block",Wlp.read(model.settingsStart,56),last().bytes);
        dataReply(last(),frame(last().bytes,Arrays.copyOf(image,56)),true);
        assertArrayEquals("remainder of the index image",Wlp.read(model.settingsStart+56,model.settingsLength-56),last().bytes);
        dataReply(last(),frame(last().bytes,Arrays.copyOfRange(image,56,image.length)),false);
    }
    /** Answers record reads from the synthetic memory until the service sends anything else; returns the read requests. */
    private List<byte[]> answerReads() { return answerReadsUntil(-1); }
    private List<byte[]> answerReadsUntil(int stopAddress) {
        List<byte[]> reads=new ArrayList<>();
        while(isRead(last()) && address(last().bytes)!=stopAddress) {
            Write request=last(); reads.add(request.bytes); dataReply(request,memoryReply(request.bytes),reads.size()%2==0);
            assertNotSame("the service answered the read with another command: "+monitor.status+" — "+monitor.detail,request,last());
        }
        return reads;
    }
    private boolean isRead(Write write) { return write.characteristic.getService()==vendor && write.bytes.length==8 && write.bytes[1]==1; }
    private static int address(byte[] request) { return ((request[2]&255)<<16)|((request[3]&255)<<8)|(request[4]&255); }
    private void dataReply(Write write,byte[] reply,boolean notificationFirst) {
        if(!notificationFirst) writeDone(write,0);
        for(int i=0;i*16<reply.length;i++) {
            callback.onCharacteristicChanged(gatt,rx.get(i),Arrays.copyOfRange(reply,i*16,Math.min(reply.length,(i+1)*16))); idle();
        }
        if(notificationFirst) writeDone(write,0);
    }
    private byte[] startReply() {
        // HEM-7600T checks the identity's byte 14 (sum of bytes 0..13).
        return hex(model().id.equals("HEM-7600T")?"188000000010000102030405060708090a0b0c0d5b0f00dd":"188000000010000102030405060708090a0b0c0d0e0f0088");
    }
    /** The synthetic monitor's reply to a read: memory contents, or device status E3 at {@link #errorAddress}. */
    private byte[] memoryReply(byte[] request) {
        int at=address(request),length=request[5]&255;
        if(at==errorAddress) { byte[] error=new byte[8]; error[0]=8; error[1]=(byte)0x81; System.arraycopy(request,2,error,2,4); error[6]=(byte)0xe3; bcc(error); return error; }
        return frame(request,Arrays.copyOfRange(memory,at,at+length));
    }
    private static byte[] frame(byte[] request,byte[] payload) {
        byte[] reply=new byte[payload.length+8]; reply[0]=(byte)reply.length; reply[1]=(byte)0x81;
        System.arraycopy(request,2,reply,2,4); System.arraycopy(payload,0,reply,6,payload.length);
        bcc(reply); return reply;
    }
    private static void bcc(byte[] frame) { frame[frame.length-1]=0; for(int i=0;i<frame.length-1;i++) frame[frame.length-1]^=frame[i]; }
    private VendorMemoryModel model() { try { return (VendorMemoryModel)get("vendorModel"); } catch(Exception failure) { throw new AssertionError(failure); } }
    /** Writes a logical index prefix (positions, sequences) for the current model into the settings image. */
    private void index(int[] positions,long[] sequences) {
        VendorMemoryModel model=model(); byte[] logical=new byte[model.settingsLength];
        for(int u=0;u<positions.length;u++) { logical[2*u]=(byte)positions[u]; logical[2*u+1]=(byte)(positions[u]>>8); }
        for(int u=0;u<sequences.length;u++) for(int b=0;b<4;b++) logical[8+4*u+b]=(byte)(sequences[u]>>(8*b));
        // HEM-7530T checks block 3 (logical bytes 44..57) against byte 58.
        if(model.settingsLength>58) logical[58]=(byte)VendorMemoryModel.checksum(Arrays.copyOfRange(logical,44,58),14);
        System.arraycopy(model.logical(logical),0,memory,model.settingsStart,model.settingsLength);
    }
    private byte[] image() { VendorMemoryModel model=model(); return Arrays.copyOfRange(memory,model.settingsStart,model.settingsStart+model.settingsLength); }
    private void put(int at,String record) { byte[] bytes=hex(record); System.arraycopy(bytes,0,memory,at,bytes.length); }
    /** Records in slots 0 and 3 of bank 1 with erased slots between, read as a 48-byte and a 16-byte block. */
    private void spreadRecords() {
        index(new int[]{4},new long[]{4});
        put(0xa8,"ffffffffffffffffffffffffffffffff"); put(0xc8,RECORD_B);
    }
    private void writeDone(Write write,int status) { callback.onCharacteristicWrite(gatt,write.characteristic,status); idle(); }
    private void assertImported() {
        assertEquals("History transfer complete",monitor.status); assertFalse(monitor.historyRunning);
        assertEquals(2,store.historyCount(0)); assertEquals(2,monitor.addedInTransfer);
        assertEquals(monitor.getString(R.string.monitor_import_complete),MonitorUi.title(monitor,monitor));
    }
    private int countOpcode(int opcode) { return (int)writes.stream().filter(write -> write.characteristic.getService()==vendor && write.bytes.length==8 && write.bytes[1]==opcode).count(); }
    private Write last() { return writes.get(writes.size()-1); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static byte[] hex(String hex) { return HexFormat.of().parseHex(hex); }
    private static BluetoothGattCharacteristic characteristic(BluetoothGattService service,UUID id,int properties) {
        BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(id,properties,BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(c); return c;
    }
    private Object get(String name) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); return field.get(monitor); }
    private void set(String name,Object value) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); field.set(monitor,value); }
    private void call(String name) { call(name,new Class<?>[0]); }
    private void call(String name,Class<?>[] types,Object... args) {
        try { Method method=MonitorService.class.getDeclaredMethod(name,types); method.setAccessible(true); method.invoke(monitor,args); }
        catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
