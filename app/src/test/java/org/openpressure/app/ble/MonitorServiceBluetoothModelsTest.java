package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.data.PairingKeys;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.protocol.*;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Real service callbacks with synthetic one-user peers; no hardware compatibility claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceBluetoothModelsTest {
    private X7TestPeer peer;
    private MonitorService monitor;
    private X7Model model;
    @After public void cleanup() { if(peer!=null) peer.close(); }

    private void prepare(String identity) throws Exception {
        if(peer!=null) peer.close();
        org.robolectric.RuntimeEnvironment.getApplication().deleteDatabase("pressure.db");
        peer=new X7TestPeer(); monitor=peer.monitor; model=X7Model.identify(identity);
        Arrays.fill(peer.memory,(byte)255);
        byte[] index=new byte[68]; index[0]=2; index[2]=(byte)255;
        System.arraycopy(index,0,peer.memory,model.settingsStart,index.length);
        for(int i=0;i<2;i++) {
            byte[] raw=Arrays.copyOf(X7TestPeer.hex("694e4319261da6030000010000000000"),model.recordBytes);
            raw[2]+=i; raw[6]+=i;
            raw[model.recordBytes-2]=(byte)VendorMemoryModel.checksum(raw,model.recordBytes-2);
            System.arraycopy(raw,0,peer.memory,model.bankStart(1)+i*model.recordBytes,raw.length);
        }
        peer.store.setImportUsers(X7TestPeer.ADDRESS,2); // A stale two-user preference cannot create another bank.
    }
    @Test public void allNewSingleChannelIdentitiesImportOnlyTheirAvailableBank() throws Exception {
        for(String identity:new String[]{"HEM-7143T1-E","HEM-7143T1-D","HEM-7143T2-E","HEM-7188T1-LE","HEM-7188T1-LEO"}) {
            prepare(identity); peer.connect(identity,X7TestPeer.hex("0100010001"));
            assertTrue(monitor.detail,monitor.ready);
            assertFalse(identity,monitor.offersImportUsers());
            assertFalse(MonitorService.offersImportUsers(identity));
            assertFalse("no unbounded dump on a new map",monitor.canDump);
            assertFalse(monitor.clockSyncOffered());
            monitor.syncHistory(-1); peer.pump();
            assertEquals(identity+": "+monitor.detail,"History transfer complete",monitor.status);
            assertEquals(2,monitor.receivedInTransfer); assertEquals(2,peer.store.readings(0).size());
            assertTrue(peer.store.readings(0).stream().allMatch(r -> r.user==1 && r.afib==null));
            assertTrue(peer.wlpTranscript().contains(java.util.HexFormat.of().formatHex(Wlp.read(model.settingsStart,16))));
            assertTrue(peer.wlpTranscript().stream().anyMatch(c -> c.startsWith("080100"+String.format("%04x",model.bankStart(1)))));
            assertTrue(peer.addressedWrites().isEmpty());
        }
    }
    @Test public void pairAndDeveloperOverridesNeverGrantMemoryWrites() throws Exception {
        prepare("HEM-7188T1-LE");
        DeveloperOptions.setUnlocked(monitor,true);
        DeveloperOptions.setAllowUntested(monitor,WriteAspectIndex.Aspect.REGISTRATION,true);
        DeveloperOptions.setAllowUntested(monitor,WriteAspectIndex.Aspect.CLOCK_BLOCK,true);
        peer.pair(model.identity,X7TestPeer.hex("0108010001"));
        assertTrue(monitor.ready); assertNull(monitor.registrationBackupFile());
        assertFalse(monitor.registrationPending); assertFalse(monitor.clockSyncOffered());
        int writes=peer.writes.size(); monitor.beginClockSync(); monitor.dumpEeprom(0,16); peer.pump();
        assertEquals(writes,peer.writes.size()); assertTrue(peer.addressedWrites().isEmpty());
    }
    @Test public void aForeignBankIsRejectedAtTheActualGattBoundary() throws Exception {
        prepare("HEM-7143T1-E"); peer.connect(model.identity,X7TestPeer.hex("0100010001"));
        int writes=peer.writes.size();
        peer.call("write",new Class<?>[]{BluetoothGattCharacteristic.class,byte[].class,Runnable.class},peer.tx,Wlp.read(0x804,16),(Runnable)() -> {});
        peer.pump();
        assertEquals(writes,peer.writes.size()); assertEquals("Action failed",monitor.status);
    }
    @Test public void checksumFailureRejectsOnlyThatSlotAcrossFourteenByteReads() throws Exception {
        prepare("HEM-7143T1-E"); peer.memory[model.bankStart(1)+12]++;
        peer.connect(model.identity,X7TestPeer.hex("0100010001")); monitor.syncHistory(-1); peer.pump();
        assertEquals("History transfer incomplete",monitor.status); assertEquals(1,monitor.receivedInTransfer);
        assertEquals(1,peer.store.readings(0).size()); assertTrue(monitor.sessionFinished);
    }
    @Test public void badIndexNeverReadsTheRecordBank() throws Exception {
        prepare("HEM-7143T2-E"); peer.memory[model.settingsStart+58]++;
        peer.connect(model.identity,X7TestPeer.hex("0100010001")); monitor.syncHistory(-1); peer.pump();
        assertEquals("Action failed",monitor.status); assertTrue(peer.store.readings(0).isEmpty());
        assertFalse(peer.wlpTranscript().stream().anyMatch(c -> c.startsWith("08010002e8")));
    }
    @Test public void cancellationIgnoresAlreadyQueuedWriteAndNotificationCallbacks() throws Exception {
        prepare("HEM-7143T1-D"); peer.connect(model.identity,X7TestPeer.hex("0100010001"));
        monitor.syncHistory(-1); int writes=peer.writes.size(); monitor.disconnect(); peer.pump();
        assertEquals(writes,peer.writes.size()); assertTrue(peer.store.readings(0).isEmpty()); assertFalse(monitor.historyRunning);
    }
    @Test public void disconnectDuringImportStopsFurtherCommands() throws Exception {
        prepare("HEM-7188T1-LEO"); peer.connect(model.identity,X7TestPeer.hex("0100010001"));
        monitor.syncHistory(-1); int writes=peer.writes.size();
        peer.callback.onConnectionStateChange(peer.gatt,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_DISCONNECTED); peer.pump();
        assertEquals(writes,peer.writes.size()); assertFalse(monitor.historyRunning); assertFalse(monitor.ready);
    }
    @Test public void encryptedWriteFailureIsNotRetriedOnNewMap() throws Exception {
        prepare("HEM-7188T1-LE"); peer.ltk=X7TestPeer.hex("05050505050505050505050505050505");
        new PairingKeys(monitor).put(X7TestPeer.ADDRESS,peer.ltk);
        peer.writeStatus=value -> (value[0]&255)==0xc0?BluetoothGatt.GATT_FAILURE:BluetoothGatt.GATT_SUCCESS;
        peer.connect(model.identity,X7TestPeer.hex("0900000000de000100"));
        assertEquals("Action failed",monitor.status);
        assertEquals(1,peer.writes.stream().filter(w -> (w.bytes[0]&255)==0xc0).count());
        assertTrue(peer.store.readings(0).isEmpty()); assertTrue(peer.addressedWrites().isEmpty());
    }
    @Test public void anotherCatalogFamilyIsRefusedBeforeWrites() throws Exception {
        prepare("HEM-7188T1-LE"); peer.advertisedName="BLEsmart_000000D40011223344";
        peer.connect(model.identity,X7TestPeer.hex("0100010001"));
        assertEquals("Action failed",monitor.status); assertTrue(peer.writes.isEmpty()); assertTrue(peer.descriptorWrites.isEmpty());
    }
    @Test public void missingSystemChannelIsRefusedBeforeWrites() throws Exception {
        prepare("HEM-7143T1-E"); peer.alias.getCharacteristics().remove(peer.system);
        peer.connect(model.identity,X7TestPeer.hex("0100010001"));
        assertEquals("Action failed",monitor.status); assertTrue(peer.writes.isEmpty());
    }
}
