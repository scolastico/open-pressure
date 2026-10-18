package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.protocol.DeviceReport.WriteOutcome;
import org.openpressure.protocol.X7Registration;
import org.openpressure.protocol.WriteAspectIndex.Aspect;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Pair registration through the real service callbacks, gated by the write-aspect index: confirmed on HEM-7380T1-EOSL,
 * untested on HEM-7380T1-EBK (developer override only), blocked on every other identity.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceRegistrationGateTest {
    private X7TestPeer peer;
    private MonitorService monitor;

    @Before public void setup() throws Exception { peer=new X7TestPeer(); monitor=peer.monitor; }
    @After public void cleanup() { if(peer!=null) peer.close(); }

    @Test public void confirmedEoslRegistersWithoutDeveloperOptions() throws Exception {
        assertFalse(DeveloperOptions.isUnlocked(monitor));
        List<String> transcript=register("HEM-7380T1-EOSL");
        assertEquals("User 1 registration acknowledged",monitor.status);
        assertEquals(expectedTranscript(),transcript);
        assertEquals(WriteOutcome.ACKNOWLEDGED,monitor.registrationOutcome);
        assertFalse("a confirmed identity never relies on the override",monitor.registrationOverride);
        assertFalse(monitor.offerReportAfterRegistration);
        assertTrue(json().contains("\"aspect\":\"registration\",\"outcome\":\"acknowledged\",\"override\":false"));
        monitor.disconnect();
        assertFalse("the tested identity is never asked after Pair",monitor.deviceReportPromptDue());
        assertNotNull("its recorded write keeps the report available on the card",monitor.deviceReport());
    }
    @Test public void firmwareNameResolvesToTheConfirmedIdentity() throws Exception {
        register("X7 Smart AFib");
        assertEquals("User 1 registration acknowledged",monitor.status);
        assertEquals("HEM-7380T1-EOSL",monitor.registrationIdentity);
        assertEquals(2,peer.addressedWrites().size());
    }
    @Test public void ebkWithTheSwitchRunsTheSameFramesAndRecordsTheOverride() throws Exception {
        allowRegistration();
        peer.pair("HEM-7380T1-EBK",X7TestPeer.plainAdvertisement());
        assertTrue(monitor.registrationPending);
        assertEquals("HEM-7380T1-EBK",monitor.registrationIdentity);
        assertTrue(monitor.registrationOverride);
        assertTrue(monitor.detail,monitor.detail.contains("HEM-7380T1-EBK") && monitor.detail.contains("developer option"));
        assertEquals("nothing is written before the user confirms",0,peer.addressedWrites().size());
        monitor.registerX7User(1); peer.pump();
        assertEquals("User 1 registration acknowledged",monitor.status);
        assertEquals("the same frame sequence as the confirmed EOSL",expectedTranscript(),peer.wlpTranscript());
        assertEquals(WriteOutcome.ACKNOWLEDGED,monitor.registrationOutcome);
        assertTrue(json().contains("\"aspect\":\"registration\",\"outcome\":\"acknowledged\",\"override\":true"));
        assertTrue("an overridden write offers the report",monitor.offerReportAfterRegistration);
    }
    @Test public void ebkWithoutTheSwitchStaysReadOnly() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        DeveloperOptions.setAllowUntested(monitor,Aspect.CLOCK_BLOCK,true);
        peer.pair("HEM-7380T1-EBK",X7TestPeer.plainAdvertisement());
        assertReadOnly("HEM-7380T1-EBK");
    }
    @Test public void blockedIdentitiesStayReadOnlyEvenWithTheSwitch() throws Exception {
        allowRegistration();
        peer.pair("HEM-7380T1",X7TestPeer.plainAdvertisement());
        assertReadOnly("HEM-7380T1");
    }
    @Test public void switchTurnedOffBeforeConfirmationEndsWithoutAWrite() throws Exception {
        allowRegistration();
        peer.pair("HEM-7380T1-EBK",X7TestPeer.plainAdvertisement());
        assertTrue(monitor.registrationPending); assertTrue(monitor.registrationOverride);
        DeveloperOptions.setAllowUntested(monitor,Aspect.REGISTRATION,false);
        monitor.registerX7User(1); peer.pump();
        assertEquals(0,peer.addressedWrites().size());
        assertEquals("Action failed",monitor.status);
        assertTrue(monitor.detail,monitor.detail.contains("No settings writes were sent"));
        assertNull("the connection ends",peer.get("gatt"));
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.registrationOutcome);
        assertFalse(monitor.offerReportAfterRegistration);
        assertTrue("the withdrawn override is recorded",json().contains("\"aspect\":\"registration\",\"outcome\":\"not_attempted\",\"override\":false"));
    }
    @Test public void hiddenOptionsBeforeConfirmationEndWithoutAWrite() throws Exception {
        allowRegistration();
        peer.pair("HEM-7380T1-EBK",X7TestPeer.plainAdvertisement());
        DeveloperOptions.setUnlocked(monitor,false);
        monitor.registerX7User(2); peer.pump();
        assertEquals(0,peer.addressedWrites().size());
        assertEquals(WriteOutcome.NOT_ATTEMPTED,monitor.registrationOutcome);
    }
    @Test public void disconnectAfterTheFirstWriteCancelsWithoutRetry() throws Exception {
        allowRegistration();
        peer.addressedReply=X7TestPeer.WriteReply.SILENT;
        peer.pair("HEM-7380T1-EBK",X7TestPeer.plainAdvertisement());
        monitor.registerX7User(1); peer.pump();
        assertEquals(1,peer.addressedWrites().size());
        assertTrue(monitor.registrationWriting);
        peer.callback.onConnectionStateChange(peer.gatt,0,BluetoothProfile.STATE_DISCONNECTED); X7TestPeer.idle();
        assertEquals("Action failed",monitor.status);
        assertTrue(monitor.detail,monitor.detail.contains("registration"));
        assertEquals(WriteOutcome.CANCELLED,monitor.registrationOutcome);
        assertTrue(json().contains("\"aspect\":\"registration\",\"outcome\":\"cancelled\",\"override\":true"));
        assertTrue("an unacknowledged forced write offers the report",monitor.offerReportAfterRegistration);
        // A stale acknowledgment and write completion after the disconnect send nothing further.
        byte[] first=peer.addressedWrites().get(0);
        byte[] ack=new byte[8]; ack[0]=8; ack[1]=(byte)0x81; System.arraycopy(first,2,ack,2,4); X7TestPeer.bcc(ack);
        peer.callback.onCharacteristicChanged(peer.gatt,peer.rx,ack);
        peer.callback.onCharacteristicWrite(peer.gatt,peer.tx,BluetoothGatt.GATT_SUCCESS);
        X7TestPeer.idle(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(1,peer.addressedWrites().size());
        assertEquals("recorded once",1,json().split("\"aspect\":\"registration\"",-1).length-1);
    }
    @Test public void rejectedAcknowledgmentStopsBeforeTheSecondWrite() throws Exception {
        allowRegistration();
        peer.addressedReply=X7TestPeer.WriteReply.REJECT;
        peer.pair("HEM-7380T1-EBK",X7TestPeer.plainAdvertisement());
        monitor.registerX7User(1); peer.pump();
        assertEquals(1,peer.addressedWrites().size());
        assertEquals("Action failed",monitor.status);
        assertEquals(WriteOutcome.REJECTED,monitor.registrationOutcome);
        assertTrue(json().contains("\"aspect\":\"registration\",\"outcome\":\"rejected\",\"override\":true"));
        assertEquals("recorded once",1,json().split("\"aspect\":\"registration\"",-1).length-1);
        assertTrue(monitor.offerReportAfterRegistration);
    }
    @Test public void acknowledgmentTimeoutCancels() throws Exception {
        peer.addressedReply=X7TestPeer.WriteReply.SILENT;
        peer.pair("HEM-7380T1-EOSL",X7TestPeer.plainAdvertisement());
        monitor.registerX7User(1); peer.pump();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertEquals("Action failed",monitor.status);
        assertEquals(WriteOutcome.CANCELLED,monitor.registrationOutcome);
        assertFalse("a confirmed identity offers no report after the write",monitor.offerReportAfterRegistration);
        assertEquals(1,peer.addressedWrites().size());
    }
    @Test public void connectNeverRegisters() throws Exception {
        allowRegistration();
        peer.connect("HEM-7380T1-EBK",X7TestPeer.plainAdvertisement());
        assertTrue(monitor.ready); assertFalse(monitor.registrationReading); assertFalse(monitor.registrationPending);
        assertEquals("",monitor.registrationIdentity);
        assertEquals(0,peer.addressedWrites().size());
    }

    private List<String> register(String model) throws Exception {
        peer.pair(model,X7TestPeer.plainAdvertisement());
        assertTrue(monitor.registrationPending);
        assertNotNull("the backup precedes the user choice",monitor.registrationBackupFile());
        monitor.registerX7User(1); peer.pump();
        return peer.wlpTranscript();
    }
    /** START, the 68-byte settings read, both planned registration frames and END. */
    private static List<String> expectedTranscript() {
        List<String> expected=new ArrayList<>();
        expected.add("0800000000100018");
        for(int at=0x10;at<0x54;at+=16) {
            int length=Math.min(16,0x54-at);
            byte[] read={8,1,0,0,(byte)at,(byte)length,0,0}; X7TestPeer.bcc(read);
            expected.add(HexFormat.of().formatHex(read));
        }
        X7Registration plan=new X7Registration("HEM-7380T1-EOSL",X7TestPeer.SETTINGS,1,X7TestPeer.NOW);
        plan.authorize();
        byte[] prefix=plan.command(); expected.add(HexFormat.of().formatHex(prefix));
        byte[] ack=new byte[8]; ack[0]=8; ack[1]=(byte)0x81; System.arraycopy(prefix,2,ack,2,4); X7TestPeer.bcc(ack); plan.accept(ack);
        expected.add(HexFormat.of().formatHex(plan.command()));
        expected.add("080f000000000007");
        return expected;
    }
    private void assertReadOnly(String identity) throws Exception {
        assertEquals("X7 read mode available",monitor.status);
        assertEquals("Profile registration is not enabled for "+identity+". Connect remains available for read-only access.",monitor.detail);
        assertTrue(monitor.ready); assertTrue(monitor.canHistory);
        assertFalse(monitor.registrationReading); assertFalse(monitor.registrationPending); assertFalse(monitor.registrationWriting);
        assertEquals(0,peer.addressedWrites().size());
        assertTrue("not even the registration settings read was sent",peer.wlpTranscript().isEmpty());
        assertFalse(json().contains("\"aspect\":\"registration\""));
        assertFalse(monitor.offerReportAfterRegistration);
    }
    private void allowRegistration() {
        DeveloperOptions.setUnlocked(monitor,true);
        DeveloperOptions.setAllowUntested(monitor,Aspect.REGISTRATION,true);
    }
    private String json() { return peer.report.toJson().replace(" ",""); }
}
