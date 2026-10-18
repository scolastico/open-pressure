package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.*;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.VendorPairingKeys;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.protocol.*;
import org.openpressure.protocol.DeviceReport.WriteOutcome;
import org.openpressure.protocol.WriteAspectIndex.Aspect;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.lang.reflect.*;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The explicit clock-sync session through the real service, ATT queue, validators and plan; only the Bluetooth
 * peer is simulated. Legacy four-channel monitors (16- and 14-byte maps) and the plain-WLP X7 are covered.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceClockSyncTest {
    private static final LocalDateTime NOW=LocalDateTime.of(2026,3,4,5,6,7);
    private static final byte[] END_REPLY=hex("088f000000000087");
    private MonitorService monitor;
    private ReadingStore store;
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private BluetoothGattService vendor;
    private BluetoothGattCharacteristic unlock,wlpTx,wlpRx,system;
    private final List<Write> writes=new ArrayList<>();
    private final List<BluetoothGattCharacteristic> tx=new ArrayList<>(),rx=new ArrayList<>();
    private final byte[] memory=new byte[0x10000];
    private DeviceReport report;
    private static final class Write {
        final BluetoothGattCharacteristic characteristic;
        final byte[] bytes;
        Write(BluetoothGattCharacteristic characteristic,byte[] bytes) { this.characteristic=characteristic; this.bytes=bytes.clone(); }
    }

    @Before public void setup() throws Exception {
        monitor=Robolectric.buildService(MonitorService.class).get();
        AutoImportSettings.setMode(monitor,AutoImportSettings.OFF);
        store=new ReadingStore(monitor);
        gatt=mock(BluetoothGatt.class);
        BluetoothDevice device=mock(BluetoothDevice.class);
        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(gatt.getDevice()).thenReturn(device);
        set("store",store); set("gatt",gatt);
        set("operations",new OperationQueue(error -> call("fail",new Class<?>[]{String.class},error)));
        callback=(BluetoothGattCallback)get("callback");
        monitor.clock=Clock.fixed(NOW.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        monitor.clockBackupSync=directory -> {};
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class),any(byte[].class),anyInt())).thenAnswer(invocation -> {
            writes.add(new Write(invocation.getArgument(0),invocation.getArgument(1)));
            return BluetoothStatusCodes.SUCCESS;
        });
        report=new DeviceReport("Connect","test",35,"Synthetic phone");
        set("report",report);
        Arrays.fill(memory,(byte)255);
    }
    @After public void cleanup() {
        if(monitor!=null) monitor.disconnect();
        if(store!=null) store.close();
    }

    @Test public void legacySyncWritesBlockThreeAndEnds() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually();
        assertTrue(monitor.canClockSync());
        monitor.beginClockSync();
        readSettings();
        assertTrue(monitor.clockSyncPending); assertNull(monitor.clockSyncMonitorClock);
        assertEquals(NOW,monitor.clockSyncPhoneClock); assertTrue(monitor.clockSyncOverride);
        assertEquals("nothing is written before confirmation",0,clockWrites());
        List<byte[]> expected=new VendorClockWrite("HEM-7155T",image(),NOW,true,4).chunks();
        monitor.confirmClockSync(null); flushIo();
        Write first=last();
        assertSame(tx.get(0),first.characteristic); assertEquals(16,first.bytes.length); assertArrayEquals(expected.get(0),first.bytes);
        writeDone(first,0);
        Write second=last();
        assertSame(tx.get(1),second.characteristic); assertEquals(8,second.bytes.length); assertArrayEquals(expected.get(1),second.bytes);
        writeDone(second,0);
        legacyReply(echo(expected));
        assertEquals("END follows the acknowledgment",15,last().bytes[1]);
        legacyReply(END_REPLY);
        assertEquals("Clock sync complete",monitor.status);
        assertEquals(MonitorService.ClockUpdate.ACCEPTED,monitor.clockUpdate);
        assertEquals(WriteOutcome.ACKNOWLEDGED,monitor.clockSyncOutcome);
        assertTrue(monitor.sessionFinished); assertFalse(monitor.ready); assertFalse(monitor.canHistory);
        File backup=monitor.clockSyncBackupFile();
        assertNotNull(backup); assertTrue(backup.isFile());
        assertTrue(backup.getName(),backup.getName().startsWith("clock-sync-settings-") && backup.getName().endsWith(".json"));
        assertNull("never offered as the registration backup",monitor.registrationBackupFile());
        assertNull(org.openpressure.app.data.SettingsBackupStore.latestRegistrationBackup(backup.getParentFile()));
        String saved=new String(java.nio.file.Files.readAllBytes(backup.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"kind\": \"clock-sync\"")); assertTrue(saved.contains(ReadingStore.hex(image())));
        String json=report.toJson().replace(" ","");
        assertTrue(json,json.contains("\"aspect\":\"clock_block\",\"outcome\":\"acknowledged\",\"override\":true"));
        assertTrue(monitor.offerReportAfterWrite);
        assertEquals(1,clockWrites()); assertEquals(1,countOpcode(15));
    }
    @Test public void fourteenByteMapWritesSwappedBlock() throws Exception {
        allowUntested();
        // HEM-7530T refuses an erased block 3; an all-zero block has a valid checksum.
        attach("HEM-7530T"); settings(new byte[16]);
        connectManually(); monitor.beginClockSync(); readSettings();
        List<byte[]> expected=new VendorClockWrite("HEM-7530T",image(),NOW,true,4).chunks();
        monitor.confirmClockSync(null); flushIo();
        assertSame(tx.get(0),last().characteristic); assertArrayEquals(expected.get(0),last().bytes);
        assertEquals("write address 0x2D0",0x02,last().bytes[3]); assertEquals((byte)0xd0,last().bytes[4]);
        writeDone(last(),0);
        assertSame(tx.get(1),last().characteristic); assertArrayEquals(expected.get(1),last().bytes);
        writeDone(last(),0);
        legacyReply(echo(expected));
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals("Clock sync complete",monitor.status);
    }
    @Test public void x7SyncUsesSingleWriteAndEnds() throws Exception {
        // The confirmed EOSL needs no developer option: locked options, no override, no chosen-time field.
        attachX7("HEM-7380T1-EOSL"); x7Settings(clockBlock(NOW.minusDays(3),true));
        assertFalse(DeveloperOptions.isUnlocked(monitor));
        assertTrue(monitor.clockSyncOffered()); assertTrue(monitor.canClockSync());
        monitor.beginClockSync(); readX7Settings();
        assertTrue(monitor.clockSyncPending);
        assertFalse("a confirmed model never relies on the override",monitor.clockSyncOverride);
        assertFalse("locked options offer no chosen time",monitor.clockSyncChosenTimeAllowed);
        byte[] frame=new VendorClockWrite("HEM-7380T1-EOSL",x7Image(),NOW,false,1).frame();
        monitor.confirmClockSync(null); flushIo();
        assertSame(wlpTx,last().characteristic); assertEquals(24,last().bytes.length); assertArrayEquals(frame,last().bytes);
        writeDone(last(),0);
        x7Reply(compactAck(frame,0));
        assertArrayEquals("END follows the acknowledgment",hex("080f000000000007"),last().bytes);
        writeDone(last(),0); x7Reply(END_REPLY);
        assertEquals("Clock sync complete",monitor.status);
        assertEquals(WriteOutcome.ACKNOWLEDGED,monitor.clockSyncOutcome);
        assertEquals(1,clockWrites());
        String json=report.toJson().replace(" ","");
        assertTrue(json,json.contains("\"aspect\":\"clock_block\",\"outcome\":\"acknowledged\",\"override\":false"));
        assertFalse("a confirmed write does not offer the report",monitor.offerReportAfterWrite);
    }
    @Test public void x7ChosenTimeOnTheConfirmedModelNeedsOnlyUnlockedOptions() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        assertFalse(DeveloperOptions.allowsUntested(monitor,Aspect.CLOCK_BLOCK));
        attachX7("HEM-7380T1-EOSL"); x7Settings(clockBlock(NOW.minusDays(3),true));
        monitor.beginClockSync(); readX7Settings();
        assertTrue(monitor.clockSyncPending);
        assertTrue(monitor.clockSyncChosenTimeAllowed); assertFalse(monitor.clockSyncOverride);
        LocalDateTime chosen=LocalDateTime.of(2027,8,9,10,11,12);
        byte[] frame=new VendorClockWrite("HEM-7380T1-EOSL",x7Image(),chosen,false,1).frame();
        monitor.confirmClockSync(chosen); flushIo();
        assertSame(wlpTx,last().characteristic); assertArrayEquals(frame,last().bytes);
        assertArrayEquals("the chosen time plus one second",new byte[]{27,8,9,10,11,13},Arrays.copyOfRange(last().bytes,14,20));
        writeDone(last(),0); x7Reply(compactAck(frame,0));
        writeDone(last(),0); x7Reply(END_REPLY);
        assertEquals("Clock sync complete",monitor.status);
        assertTrue(report.toJson().replace(" ","").contains("\"outcome\":\"acknowledged\",\"override\":false"));
        assertFalse(monitor.offerReportAfterWrite);
    }
    @Test public void x7ChosenTimeIsRefusedOnceOptionsAreLocked() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        attachX7("HEM-7380T1-EOSL"); x7Settings(clockBlock(NOW.minusDays(3),true));
        monitor.beginClockSync(); readX7Settings();
        assertTrue(monitor.clockSyncChosenTimeAllowed);
        DeveloperOptions.setUnlocked(monitor,false);
        int sent=writes.size(); monitor.confirmClockSync(LocalDateTime.of(2027,8,9,10,11,12)); flushIo();
        assertEquals("a chosen time is refused while locked",sent,writes.size());
        assertTrue("the confirmation stays open",monitor.clockSyncPending);
        // The phone time is still allowed on the confirmed model.
        byte[] frame=new VendorClockWrite("HEM-7380T1-EOSL",x7Image(),NOW,false,1).frame();
        monitor.confirmClockSync(null); flushIo();
        assertArrayEquals(frame,last().bytes); assertEquals(1,clockWrites());
    }
    @Test public void x7EbkStillNeedsTheDeveloperSwitch() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        attachX7("HEM-7380T1-EBK"); x7Settings(clockBlock(NOW.minusDays(3),true));
        assertFalse("untested EBK without the switch: not offered",monitor.clockSyncOffered());
        assertFalse(monitor.canClockSync());
        int sent=writes.size(); monitor.beginClockSync(); idle();
        assertEquals(sent,writes.size()); assertFalse(monitor.clockSyncRunning);
        allowUntested();
        assertTrue(monitor.clockSyncOffered()); assertTrue(monitor.canClockSync());
        monitor.beginClockSync(); readX7Settings();
        assertTrue(monitor.clockSyncPending); assertTrue(monitor.clockSyncOverride); assertTrue(monitor.clockSyncChosenTimeAllowed);
        monitor.cancelClockSync(); idle();
        assertEquals(0,clockWrites());
    }
    @Test public void withinThresholdWritesNothing() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(clockBlock(NOW,true));
        connectManually(); monitor.beginClockSync(); readSettings();
        assertEquals(NOW,monitor.clockSyncMonitorClock); assertEquals(0,monitor.clockSyncDriftSeconds);
        monitor.confirmClockSync(null); flushIo();
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals(0,clockWrites());
        assertEquals("Clock sync skipped",monitor.status);
        assertEquals(WriteOutcome.SKIPPED_WITHIN_THRESHOLD,monitor.clockSyncOutcome);
        assertEquals(MonitorService.ClockUpdate.SKIPPED,monitor.clockUpdate);
        assertFalse("nothing was written, so no report is offered",monitor.offerReportAfterWrite);
    }
    @Test public void rejectedAcknowledgmentStillEnds() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        monitor.confirmClockSync(null); flushIo();
        writeDone(last(),0); byte[] frame=frameOf(); writeDone(last(),0);
        legacyReply(compactAck(frame,1));
        assertEquals("END is still sent",15,last().bytes[1]);
        legacyReply(END_REPLY);
        assertEquals("Clock sync rejected",monitor.status);
        assertEquals(WriteOutcome.REJECTED,monitor.clockSyncOutcome);
        assertEquals(MonitorService.ClockUpdate.FAILED,monitor.clockUpdate);
        assertEquals("no retry",1,clockWrites());
    }
    @Test public void disconnectWhileWritingCancels() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        monitor.confirmClockSync(null); flushIo();
        Write first=last(); assertSame(tx.get(0),first.characteristic);
        callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle();
        assertEquals(WriteOutcome.CANCELLED,monitor.clockSyncOutcome);
        assertFalse(monitor.clockSyncPending);
        assertTrue(report.toJson().replace(" ","").contains("\"outcome\":\"cancelled\""));
        // A stale completion for the first chunk and a fresh connection never send the second chunk.
        callback.onCharacteristicWrite(gatt,first.characteristic,0); idle();
        attach("HEM-7155T"); connectManually();
        idle(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(1,clockWrites());
        assertTrue(writes.stream().noneMatch(write -> write.characteristic==tx.get(1)));
    }
    @Test public void refusedDuringAutomaticImportAndWhileHistoryRuns() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        set("automaticConnection",true);
        connectWithSavedKey();
        assertFalse(monitor.canClockSync());
        int sent=writes.size(); monitor.beginClockSync(); idle();
        assertEquals(sent,writes.size()); assertTrue(monitor.historyRunning);
        monitor.disconnect(); writes.clear();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.syncHistory(-1);
        assertFalse(monitor.canClockSync());
        sent=writes.size(); monitor.beginClockSync(); idle();
        assertEquals(sent,writes.size()); assertFalse(monitor.clockSyncRunning);
    }
    @Test public void chosenTimeRequiresDeveloperToggle() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        assertTrue(monitor.clockSyncChosenTimeAllowed); assertTrue(monitor.clockSyncOverride);
        LocalDateTime chosen=LocalDateTime.of(2027,8,9,10,11,12);
        DeveloperOptions.setUnlocked(monitor,false);
        int sent=writes.size(); monitor.confirmClockSync(chosen); flushIo();
        assertEquals("a chosen time is refused while locked",sent,writes.size()); assertTrue(monitor.clockSyncPending);
        allowUntested();
        monitor.confirmClockSync(chosen); flushIo();
        Write first=last(); assertSame(tx.get(0),first.characteristic);
        writeDone(first,0); Write second=last(); assertSame(tx.get(1),second.characteristic);
        byte[] frame=new byte[24]; System.arraycopy(first.bytes,0,frame,0,16); System.arraycopy(second.bytes,0,frame,16,8);
        // Frame bytes 14..19 carry the clock (block offset 8): the chosen time plus one second.
        assertArrayEquals(new byte[]{27,8,9,10,11,13},Arrays.copyOfRange(frame,14,20));
        assertTrue("the untested model still relies on the override",monitor.clockSyncOverride);
    }
    @Test public void untestedWithoutOverrideIsNotOffered() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually();
        assertFalse(monitor.canClockSync());
        int sent=writes.size(); monitor.beginClockSync(); idle();
        assertEquals(sent,writes.size()); assertFalse(monitor.clockSyncRunning);
        // An override withdrawn after the preview ends the session without a write.
        allowUntested(); monitor.beginClockSync(); readSettings();
        DeveloperOptions.setAllowUntested(monitor,Aspect.CLOCK_BLOCK,false);
        monitor.confirmClockSync(null); flushIo();
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals(0,clockWrites()); assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        assertFalse(monitor.offerReportAfterWrite);
        assertTrue("the withdrawn override is recorded",report.toJson().replace(" ","").contains("\"outcome\":\"not_attempted\",\"override\":false"));
    }
    @Test public void previewTimeoutEndsWithoutWriting() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        assertTrue(monitor.clockSyncPending);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(91));
        assertFalse(monitor.clockSyncPending);
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals("Clock sync cancelled",monitor.status);
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome); assertEquals(0,clockWrites());
        monitor.confirmClockSync(null); flushIo();
        assertEquals("a late confirmation writes nothing",0,clockWrites());
    }
    @Test public void gattErrorOnTheFirstChunkCancelsWithoutRetry() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        monitor.confirmClockSync(null); flushIo();
        Write first=last(); assertSame(tx.get(0),first.characteristic);
        writeDone(first,BluetoothGatt.GATT_WRITE_NOT_PERMITTED);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(WriteOutcome.CANCELLED,monitor.clockSyncOutcome);
        assertEquals(MonitorService.ClockUpdate.FAILED,monitor.clockUpdate);
        assertNull(get("gatt")); assertSame("no retry and no second chunk",first,last());
        assertTrue(writes.stream().noneMatch(write -> write.characteristic==tx.get(1)));
        assertEquals(1,clockWrites());
    }
    @Test public void refusedWhilePairing() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); set("pairingRequested",true);
        assertFalse(monitor.canClockSync());
        int sent=writes.size(); monitor.beginClockSync(); idle();
        assertEquals(sent,writes.size()); assertFalse(monitor.clockSyncRunning);
    }
    @Test public void endTimeoutAfterAcknowledgmentKeepsTheAcceptedClock() throws Exception {
        attachX7("HEM-7380T1-EOSL"); x7Settings(clockBlock(NOW.minusDays(3),true));
        monitor.beginClockSync(); readX7Settings();
        byte[] frame=new VendorClockWrite("HEM-7380T1-EOSL",x7Image(),NOW,false,1).frame();
        monitor.confirmClockSync(null); flushIo(); writeDone(last(),0);
        x7Reply(compactAck(frame,0));
        assertEquals(15,last().bytes[1]); writeDone(last(),0);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertEquals("Action failed",monitor.status);
        assertTrue(monitor.detail,monitor.detail.startsWith("Clock sync response timed out"));
        assertEquals(WriteOutcome.ACKNOWLEDGED,monitor.clockSyncOutcome);
        assertEquals(MonitorService.ClockUpdate.ACCEPTED,monitor.clockUpdate);
        assertTrue(report.toJson().replace(" ","").contains("\"outcome\":\"acknowledged\""));
    }
    @Test public void unreadableSettingsEndWithoutWriting() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(clockBlock(NOW.minusDays(3),false));
        connectManually(); monitor.beginClockSync(); readSettings();
        assertFalse(monitor.clockSyncPending);
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals("Monitor settings unreadable",monitor.status);
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        assertEquals(0,clockWrites());
    }
    @Test public void cancelWhilePendingEndsWithoutWriting() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        monitor.cancelClockSync();
        assertFalse(monitor.clockSyncPending);
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals("Clock sync cancelled",monitor.status);
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        assertEquals(0,clockWrites());
    }
    @Test public void backupFailureEndsWithoutWriting() throws Exception {
        allowUntested();
        monitor.clockBackupSync=directory -> { throw new java.io.IOException("synthetic sync failure"); };
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        monitor.confirmClockSync(null); flushIo();
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals(0,clockWrites()); assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        assertEquals("Clock sync cancelled",monitor.status);
    }
    @Test public void missingAcknowledgmentCancelsWithoutRetry() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        monitor.confirmClockSync(null); flushIo();
        writeDone(last(),0); writeDone(last(),0);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertEquals(WriteOutcome.CANCELLED,monitor.clockSyncOutcome);
        assertNull(get("gatt")); assertEquals(1,clockWrites()); assertEquals(0,countOpcode(15));
        assertTrue("an unacknowledged forced write offers the report",monitor.offerReportAfterWrite);
    }
    @Test public void staleBackupCompletionAfterDisconnectWritesNothing() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        java.util.concurrent.CountDownLatch gate=holdBackup();
        monitor.confirmClockSync(null); idle();
        callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle();
        gate.countDown(); flushIo();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals("a backup finishing after the disconnect sends nothing",0,clockWrites());
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        assertFalse(monitor.offerReportAfterWrite);
        assertTrue(report.toJson().replace(" ","").contains("\"outcome\":\"not_attempted\""));
        File backup=monitor.clockSyncBackupFile();
        assertNotNull("the completed backup is kept",backup); assertTrue(backup.isFile());
    }
    @Test public void staleBackupCompletionNeverWritesForANewPreview() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        java.util.concurrent.CountDownLatch gate=holdBackup();
        monitor.confirmClockSync(null); idle();
        callback.onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle();
        // A new connection reaches its own preview while the old backup is still being saved.
        attach("HEM-7155T"); connectManually(); monitor.beginClockSync(); readSettings();
        assertTrue(monitor.clockSyncPending);
        gate.countDown(); flushIo();
        assertEquals("the old backup never authorizes the new preview",0,clockWrites());
        assertTrue("the new preview still waits for its own confirmation",monitor.clockSyncPending);
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        File backup=monitor.clockSyncBackupFile();
        assertNotNull(backup); assertTrue(backup.isFile());
        monitor.cancelClockSync();
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals(0,clockWrites());
    }
    @Test public void acknowledgmentBeforeTheLastChunkCallbackIsAccepted() throws Exception {
        allowUntested();
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        List<byte[]> expected=new VendorClockWrite("HEM-7155T",image(),NOW,true,4).chunks();
        monitor.confirmClockSync(null); flushIo();
        writeDone(last(),0);
        Write second=last(); assertSame(tx.get(1),second.characteristic);
        // The monitor answers before Android reports the last chunk's write as complete.
        legacyReply(echo(expected));
        assertEquals(WriteOutcome.ACKNOWLEDGED,monitor.clockSyncOutcome);
        writeDone(second,0);
        assertEquals("END follows once the write completes",15,last().bytes[1]);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertEquals("no acknowledgment timeout after the early acknowledgment",WriteOutcome.ACKNOWLEDGED,monitor.clockSyncOutcome);
        legacyReply(END_REPLY);
        assertEquals("Clock sync complete",monitor.status);
        assertEquals(1,clockWrites());
    }
    @Test public void encryptedX7AndSmallMtuAreRefused() throws Exception {
        attachX7("HEM-7380T1-EOSL"); x7Settings(clockBlock(NOW.minusDays(3),true));
        assertTrue(monitor.canClockSync());
        set("negotiatedMtu",26);
        assertFalse("the 24-byte frame needs an ATT MTU of at least 27",monitor.canClockSync());
        set("negotiatedMtu",27);
        assertTrue(monitor.canClockSync());
        monitor.profile=ProtocolProfile.X7_SECURE_WLP;
        assertFalse(monitor.canClockSync());
        assertTrue("offered but unavailable on the encrypted path",monitor.clockSyncOffered());
        int sent=writes.size(); monitor.beginClockSync(); idle();
        assertEquals(sent,writes.size()); assertFalse(monitor.clockSyncRunning);
    }
    @Test public void erasedX7BlockIsUnreadable() throws Exception {
        attachX7("HEM-7380T1-EOSL"); x7Settings(erasedBlock());
        monitor.beginClockSync(); readX7Settings();
        assertFalse(monitor.clockSyncPending);
        assertArrayEquals(hex("080f000000000007"),last().bytes);
        writeDone(last(),0); x7Reply(END_REPLY);
        assertEquals("Monitor settings unreadable",monitor.status);
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        assertEquals(0,clockWrites());
    }
    @Test public void phoneDateOutsideTheRangeHasItsOwnOutcome() throws Exception {
        allowUntested();
        monitor.clock=Clock.fixed(LocalDateTime.of(2051,6,7,8,9,10).toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually(); monitor.beginClockSync(); readSettings();
        assertFalse(monitor.clockSyncPending);
        assertEquals(15,last().bytes[1]); legacyReply(END_REPLY);
        assertEquals("Phone date out of range",monitor.status);
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.clockSyncOutcome);
        assertEquals(0,clockWrites());
        assertEquals(monitor.getString(org.openpressure.app.R.string.monitor_clock_sync_unreadable),org.openpressure.app.ui.MonitorUi.title(monitor,monitor));
        String detail=org.openpressure.app.ui.MonitorUi.detail(monitor,monitor);
        assertTrue(detail,detail.startsWith(monitor.getString(org.openpressure.app.R.string.monitor_clock_sync_out_of_range)));
    }
    @Test public void offeredOnlyWithConfirmationOrTheDeveloperSwitch() throws Exception {
        attach("HEM-7155T"); settings(erasedBlock());
        connectManually();
        assertFalse("untested and the switch off: not offered",monitor.clockSyncOffered());
        allowUntested();
        assertTrue(monitor.clockSyncOffered());
        set("verifiedModel","HEM-7322T");
        assertFalse("no clock layout: never offered",monitor.clockSyncOffered());
        set("verifiedModel","HEM-7155T"); monitor.profile=ProtocolProfile.STANDARD_BP;
        assertFalse(monitor.clockSyncOffered());
    }

    // ---- legacy peer ----
    @SuppressWarnings("unchecked")
    private void attach(String name) throws Exception {
        VendorMemoryModel model=VendorMemoryModel.identify(name);
        set("gatt",gatt); set("vendorModel",model); set("verifiedModel",name); set("negotiatedMtu",243);
        monitor.profile=ProtocolProfile.VENDOR_MEMORY; monitor.address="AA:BB:CC:DD:EE:FF"; monitor.connectedName="Synthetic monitor";
        vendor=new BluetoothGattService(VendorWlpSession.service(model),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        List<BluetoothGattCharacteristic> vendorTx=(List<BluetoothGattCharacteristic>)get("vendorTx"),vendorRx=(List<BluetoothGattCharacteristic>)get("vendorRx");
        vendorTx.clear(); vendorRx.clear(); tx.clear(); rx.clear();
        for(UUID id:VendorWlpSession.txChannels(model)) tx.add(characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_WRITE));
        for(UUID id:VendorWlpSession.rxChannels(model)) rx.add(characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_NOTIFY));
        vendorTx.addAll(tx); vendorRx.addAll(rx);
        unlock=characteristic(vendor,VendorWlpSession.unlockCharacteristic(),BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        set("handshake",unlock);
        when(gatt.getService(vendor.getUuid())).thenReturn(vendor);
    }
    private void connectManually() throws Exception {
        call("vendorMemoryReady");
        assertEquals("Vendor history available",monitor.status);
    }
    private void connectWithSavedKey() throws Exception {
        byte[] saved=new byte[16]; Arrays.fill(saved,(byte)5);
        Map<String,byte[]> keys=new HashMap<>();
        VendorPairingKeys.Backend backend=new VendorPairingKeys.Backend() {
            public byte[] get(String name) { byte[] value=keys.get(name); return value==null?null:value.clone(); }
            public void put(String name,byte[] value) { keys.put(name,value.clone()); }
            public void forget(String name) { keys.remove(name); }
        };
        VendorPairingKeys vendorKeys=new VendorPairingKeys(backend,monitor.address,model().id); vendorKeys.stage(saved); vendorKeys.confirm(saved);
        set("vendorKeys",vendorKeys); set("vendorKeyCandidates",vendorKeys.candidates());
        set("vendorUnlock",LegacyUnlock.connect(saved)); call("sendLegacyUnlock");
        writeDone(last(),0); callback.onCharacteristicChanged(gatt,unlock,hex("8100")); idle();
    }
    /** Answers START and the 56- and 12-byte settings reads from the synthetic memory. */
    private void readSettings() {
        assertEquals(0,last().bytes[1]); writeDone(last(),0);
        legacyReply(hex("188000000010000102030405060708090a0b0c0d0e0f0088"));
        VendorMemoryModel model=model();
        assertArrayEquals(Wlp.read(model.settingsStart,56),last().bytes); writeDone(last(),0); legacyReply(memoryReply(last().bytes));
        assertArrayEquals(Wlp.read(model.settingsStart+56,12),last().bytes); writeDone(last(),0); legacyReply(memoryReply(last().bytes));
    }
    private void legacyReply(byte[] reply) {
        for(int i=0;i*16<reply.length;i++) { callback.onCharacteristicChanged(gatt,rx.get(i),Arrays.copyOfRange(reply,i*16,Math.min(reply.length,(i+1)*16))); idle(); }
    }
    /** Writes a 68-byte settings image (wire order) whose logical block 3 (bytes 44..59) is {@code block}. */
    private void settings(byte[] block) {
        VendorMemoryModel model=model(); byte[] logical=new byte[68];
        System.arraycopy(block,0,logical,44,16);
        System.arraycopy(model.logical(logical),0,memory,model.settingsStart,68);
    }
    private byte[] image() { VendorMemoryModel model=model(); return Arrays.copyOfRange(memory,model.settingsStart,model.settingsStart+68); }
    private byte[] frameOf() { return new VendorClockWrite(model().id,image(),NOW,true,4).frame(); }
    private VendorMemoryModel model() { try { return (VendorMemoryModel)get("vendorModel"); } catch(Exception failure) { throw new AssertionError(failure); } }

    // ---- X7 peer ----
    private void attachX7(String model) throws Exception {
        set("gatt",gatt); set("verifiedModel",model); set("negotiatedMtu",243);
        monitor.profile=ProtocolProfile.X7_WLP; monitor.address="AA:BB:CC:DD:EE:FF"; monitor.connectedName="Synthetic X7";
        BluetoothGattService alias=new BluetoothGattService(Uuids.VENDOR_ALIAS,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        wlpTx=characteristic(alias,Uuids.WLP_TX,BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
        wlpRx=characteristic(alias,Uuids.WLP_RX,BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        system=characteristic(alias,Uuids.HANDSHAKE,BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE|BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        set("wlpTx",wlpTx); set("wlpRx",wlpRx); set("handshake",system);
        when(gatt.getService(Uuids.VENDOR_ALIAS)).thenReturn(alias);
        // The real nonce transaction: x7Ready() creates the WlpConnection.
        call("beginX7Transaction"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        Write request=last(); assertSame(system,request.characteristic); assertEquals(0x11,request.bytes[0]);
        byte[] reply=new byte[7]; reply[0]=(byte)0x91; System.arraycopy(request.bytes,1,reply,2,4);
        writeDone(request,0); callback.onCharacteristicChanged(gatt,system,reply); idle();
        assertTrue(monitor.ready); assertNotNull(get("wlpConnection"));
    }
    private void readX7Settings() {
        assertArrayEquals(hex("0800000000100018"),last().bytes); writeDone(last(),0); x7Reply(hex("0880000000100098"));
        for(int at=0x10;at<0x54;at+=16) {
            assertArrayEquals(Wlp.read(at,Math.min(16,0x54-at)),last().bytes); writeDone(last(),0); x7Reply(memoryReply(last().bytes));
        }
    }
    private void x7Reply(byte[] reply) { callback.onCharacteristicChanged(gatt,wlpRx,reply); idle(); }
    private void x7Settings(byte[] block) { byte[] image=new byte[68]; System.arraycopy(block,0,image,44,16); System.arraycopy(image,0,memory,0x10,68); }
    private byte[] x7Image() { return Arrays.copyOfRange(memory,0x10,0x54); }

    // ---- shared helpers ----
    private void allowUntested() {
        DeveloperOptions.setUnlocked(monitor,true);
        DeveloperOptions.setAllowUntested(monitor,Aspect.CLOCK_BLOCK,true);
    }
    /** Blocks the single backup thread until the returned gate opens. */
    private java.util.concurrent.CountDownLatch holdBackup() throws Exception {
        java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);
        ((ExecutorService)get("dumpIo")).execute(() -> { try { gate.await(10,TimeUnit.SECONDS); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); } });
        return gate;
    }
    private void flushIo() throws Exception {
        ((ExecutorService)get("dumpIo")).submit(() -> {}).get(10,TimeUnit.SECONDS); idle();
    }
    /** Writes that carry a clock-block frame (opcode 01, address high byte C0) or its continuation chunk. */
    private int clockWrites() {
        return (int)writes.stream().filter(write -> write.bytes.length>2 && write.bytes[1]==1 && write.bytes[2]==(byte)0xc0).count();
    }
    private int countOpcode(int opcode) { return (int)writes.stream().filter(write -> write.bytes.length==8 && write.bytes[1]==opcode).count(); }
    private static byte[] erasedBlock() { byte[] block=new byte[16]; Arrays.fill(block,(byte)255); return block; }
    /** A block-3 image with {@code time} at offset 8 and an additive checksum at 14 (valid or off by one). */
    private static byte[] clockBlock(LocalDateTime time,boolean valid) {
        byte[] block=new byte[16];
        for(int i=0;i<8;i++) block[i]=(byte)(0x11*i+3);
        block[8]=(byte)(time.getYear()-2000); block[9]=(byte)time.getMonthValue(); block[10]=(byte)time.getDayOfMonth();
        block[11]=(byte)time.getHour(); block[12]=(byte)time.getMinute(); block[13]=(byte)time.getSecond();
        int sum=0; for(int i=0;i<14;i++) sum+=block[i]&255;
        block[14]=(byte)(valid?sum:sum+1); block[15]=0x5a;
        return block;
    }
    private static byte[] echo(List<byte[]> chunks) {
        byte[] frame=new byte[chunks.stream().mapToInt(chunk -> chunk.length).sum()]; int at=0;
        for(byte[] chunk:chunks) { System.arraycopy(chunk,0,frame,at,chunk.length); at+=chunk.length; }
        frame[1]=(byte)0x81; frame[frame.length-2]=0; bcc(frame); return frame;
    }
    private static byte[] compactAck(byte[] frame,int status) {
        byte[] ack=new byte[8]; ack[0]=8; ack[1]=(byte)0x81; System.arraycopy(frame,2,ack,2,4); ack[6]=(byte)status; bcc(ack); return ack;
    }
    private byte[] memoryReply(byte[] request) {
        int at=((request[2]&255)<<16)|((request[3]&255)<<8)|(request[4]&255),length=request[5]&255;
        byte[] payload=Arrays.copyOfRange(memory,at,at+length);
        byte[] reply=new byte[payload.length+8]; reply[0]=(byte)reply.length; reply[1]=(byte)0x81;
        System.arraycopy(request,2,reply,2,4); System.arraycopy(payload,0,reply,6,payload.length);
        bcc(reply); return reply;
    }
    private static void bcc(byte[] frame) { frame[frame.length-1]=0; for(int i=0;i<frame.length-1;i++) frame[frame.length-1]^=frame[i]; }
    private void writeDone(Write write,int status) { callback.onCharacteristicWrite(gatt,write.characteristic,status); idle(); }
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
