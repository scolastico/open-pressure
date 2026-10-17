package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.*;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.R;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.ExperimentalConsent;
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
    private boolean rejectEnd,rejectHistory,invalidSecondBank;
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
        VendorMemoryModel model=VendorMemoryModel.identify("HEM-7156T");
        set("vendorModel",model); set("verifiedModel","HEM-7156T"); set("negotiatedMtu",243);
        monitor.profile=ProtocolProfile.VENDOR_MEMORY; monitor.address="AA:BB:CC:DD:EE:FF"; monitor.connectedName="Synthetic monitor";
        vendor=new BluetoothGattService(VendorWlpSession.service(model),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        for(UUID id:VendorWlpSession.txChannels(model)) {
            BluetoothGattCharacteristic c=characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_WRITE);
            ((List<BluetoothGattCharacteristic>)get("vendorTx")).add(c);
        }
        for(UUID id:VendorWlpSession.rxChannels(model)) rx.add(characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_NOTIFY));
        ((List<BluetoothGattCharacteristic>)get("vendorRx")).addAll(rx);
        unlock=characteristic(vendor,VendorWlpSession.unlockCharacteristic(),BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        set("handshake",unlock);
        BluetoothGattService time=new BluetoothGattService(Uuids.CTS,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        clock=characteristic(time,Uuids.CURRENT_TIME,BluetoothGattCharacteristic.PROPERTY_WRITE);
        when(gatt.getService(Uuids.CTS)).thenReturn(time);
        when(gatt.getService(vendor.getUuid())).thenReturn(vendor);
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class),any(byte[].class),anyInt())).thenAnswer(invocation -> {
            Write write=new Write(invocation.getArgument(0),invocation.getArgument(1)); writes.add(write);
            boolean busy=write.bytes.length==8 && (rejectEnd && write.bytes[1]==15 || rejectHistory && write.characteristic.getService()==vendor);
            return busy?BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY:BluetoothStatusCodes.SUCCESS;
        });
    }
    @After public void cleanup() {
        if(monitor!=null) monitor.disconnect();
        if(store!=null) store.close();
    }
    @Test public void pairingReadsBothBanksThenUpdatesClockBeforeTheOnlyEnd() throws Exception {
        pair(); readAllHistory();
        assertSame(clock,last().characteristic);
        assertEquals(2,store.historyCount(0)); assertEquals(1,store.historyCount(1)); assertEquals(1,store.historyCount(2));
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
        pair(); readAllHistory(); writeDone(last(),BluetoothGatt.GATT_WRITE_NOT_PERMITTED);
        dataReply(last(),hex("088f000000000087"),false);
        assertImported();
        assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getString(R.string.monitor_clock_failed)));
    }
    @Test public void passthroughQueuesBothBanksWithoutRetainingHistoryHealthExportsOrPackets() throws Exception {
        String endpoint="https://example.invalid/measurements";
        ExperimentalConsent.accept(monitor);
        HttpForwardingSettings.save(monitor,endpoint,"Bearer synthetic-test-token",false);
        pair(); readAllHistory(); writeDone(last(),0);
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
        pair(); readAllHistory(); writeDone(last(),0); dataReply(last(),hex("088f000000000087"),false);
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
        pair(); dataReply(last(),startReply(),false); dataReply(last(),historyReply(last().bytes),false);
        HttpForwardingSettings.save(monitor,endpoint,"",false);
        int reads=1;
        while(last().characteristic.getService()==vendor && last().bytes.length==8 && last().bytes[1]==1) {
            Write request=last(); dataReply(request,historyReply(request.bytes),reads++%2==0);
        }
        assertEquals(120,reads); writeDone(last(),0); dataReply(last(),hex("088f000000000087"),false);
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
        pair(); readAllHistory();
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
        pair(); readAllHistory();
        assertSame(clock,last().characteristic); assertEquals(7,last().bytes.length);
        writeDone(last(),0); dataReply(last(),hex("088f000000000087"),false);
        assertImported(); assertTrue(MonitorUi.detail(monitor,monitor).contains(monitor.getString(R.string.monitor_check_clock)));
    }
    @Test public void clockTimeoutAndBusyEndPreserveReadingsAndExposeBothWarnings() throws Exception {
        pair(); readAllHistory(); Write timedOut=last(); rejectEnd=true;
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
        pair(); readAllHistory();
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
        pair(); dataReply(last(),startReply(),false); dataReply(last(),historyReply(last().bytes),false);
        callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle();
        assertEquals("Transfer interrupted",monitor.status); assertFalse(monitor.historyRunning);
        assertEquals(1,store.historyCount(0)); assertFalse(monitor.sessionFinished);
        assertEquals(monitor.getString(R.string.monitor_partial_saved),MonitorUi.detail(monitor,monitor));
    }
    @Test public void cancellationIgnoresAlreadyQueuedCallbacksFromTheClosedConnection() throws Exception {
        pair(); dataReply(last(),startReply(),false); dataReply(last(),historyReply(last().bytes),false);
        Write pending=last(); int sent=writes.size(); monitor.disconnect();
        callback.onCharacteristicWrite(gatt,pending.characteristic,0);
        byte[] reply=historyReply(pending.bytes);
        callback.onCharacteristicChanged(gatt,rx.get(0),Arrays.copyOf(reply,16));
        callback.onCharacteristicChanged(gatt,rx.get(1),Arrays.copyOfRange(reply,16,reply.length));
        idle();
        assertEquals("Disconnected",monitor.status); assertFalse(monitor.historyRunning);
        assertEquals(1,store.historyCount(0)); assertEquals(sent,writes.size());
    }
    @Test public void invalidEndIsNotReportedAsAcknowledgedEvenWhenHistoryWasSaved() throws Exception {
        pair(); readAllHistory(); writeDone(last(),0);
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
        connectWithSavedKey(); readAllHistory();
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
        invalidSecondBank=true;
        automaticAttempt(); connectWithSavedKey(); readAllHistory(1);
        dataReply(last(),hex("088f000000000087"),false);
        assertEquals("History transfer incomplete",monitor.status); assertEquals(1,store.historyCount(0));
        assertNull(((AutoImportTracker)get("automaticTracker")).completedRevision(monitor.address));
        assertNull(checkpoint()); assertNull(get("automaticAttempt"));
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
    private void readAllHistory() { readAllHistory(2); }
    private void readAllHistory(int decoded) {
        dataReply(last(),startReply(),true);
        int reads=0;
        while(last().characteristic.getService()==vendor && last().bytes.length==8 && last().bytes[1]==1) {
            Write request=last(); dataReply(request,historyReply(request.bytes),reads++%2==0);
        }
        assertEquals(120,reads); assertEquals(decoded,monitor.receivedInTransfer);
    }
    private void dataReply(Write write,byte[] reply,boolean notificationFirst) {
        if(!notificationFirst) writeDone(write,0);
        for(int i=0;i*16<reply.length;i++) {
            callback.onCharacteristicChanged(gatt,rx.get(i),Arrays.copyOfRange(reply,i*16,Math.min(reply.length,(i+1)*16))); idle();
        }
        if(notificationFirst) writeDone(write,0);
    }
    private static byte[] startReply() {
        return hex("188000000010000102030405060708090a0b0c0d0e0f0088");
    }
    private byte[] historyReply(byte[] request) {
        int address=((request[3]&255)<<8)|(request[4]&255),length=request[5]&255;
        byte[] reply=new byte[length+8]; reply[0]=(byte)reply.length; reply[1]=(byte)0x81;
        System.arraycopy(request,2,reply,2,4); Arrays.fill(reply,6,6+length,(byte)255);
        // One synthetic 130/78, pulse 67, 2025-07-09 06:14:38 record per user bank.
        if(address==0x98 || address==0x458) System.arraycopy(hex("694e4319261da6030000000000000000"),0,reply,6,16);
        // Diastolic zero cannot be a measurement; the slot is rejected rather than skipped.
        if(address==0x458 && invalidSecondBank) reply[7]=0;
        for(int i=0;i<reply.length-1;i++) reply[reply.length-1]^=reply[i];
        return reply;
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
