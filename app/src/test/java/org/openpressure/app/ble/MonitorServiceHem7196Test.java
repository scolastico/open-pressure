package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.data.PairingKeys;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.app.developer.DeveloperOptions.Experiment;
import org.openpressure.protocol.ProtocolProfile;
import org.openpressure.protocol.VendorClockWrite;
import org.openpressure.protocol.Wlp;
import org.openpressure.protocol.WriteAspectIndex.Aspect;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

/**
 * Experimental, hardware-untested HEM-7196T1 (M4 / X4 Connect AFib) support through the real service callbacks
 * against a simulated single-channel cuff: the 60-slot banks, the advertisement cross-check, Android's bond on the
 * encrypted route and the write-aspect gates. Synthetic data only; none of this establishes physical compatibility.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceHem7196Test {
    private static final byte[] ENCRYPTED_FLE=X7TestPeer.hex("08000000eb0001000000"), ENCRYPTED_EOSL=X7TestPeer.hex("08000000d40001000000");
    private X7TestPeer peer;
    private MonitorService monitor;

    @Before public void setup() throws Exception {
        peer=new X7TestPeer(); monitor=peer.monitor;
        // A 60-slot index: three readings for user 1 and two for user 2 (block 3 and its checksum unchanged).
        byte[] settings=X7TestPeer.SETTINGS.clone(); settings[0]=3; settings[1]=0; settings[2]=2; settings[3]=0;
        System.arraycopy(settings,0,peer.memory,0x10,settings.length);
        for(int i=0;i<3;i++) record(0x1c4+16*i,i);
        for(int i=0;i<2;i++) record(0x584+16*i,10+i);
        // Where the 100-slot X7 map would put user 2: never read on this model.
        for(int i=0;i<2;i++) record(0x804+16*i,20+i);
        peer.store.setImportUsers(X7TestPeer.ADDRESS,3);
    }
    @After public void cleanup() { if(peer!=null) peer.close(); }

    @Test public void encryptedConnectImportsBothSixtySlotBanks() throws Exception {
        connectEncrypted("HEM-7196T1-FLE",ENCRYPTED_FLE);
        monitor.syncHistory(-1); peer.pump();
        assertEquals(monitor.detail,"History transfer complete",monitor.status);
        assertEquals(5,monitor.receivedInTransfer);
        List<String> reads=reads();
        assertTrue(reads.toString(),reads.contains(read(0x1c4,16)) && reads.contains(read(0x1e4,16)));
        assertTrue("user 2 is read at 0x584",reads.contains(read(0x584,16)) && reads.contains(read(0x594,16)));
        for(String command:reads) assertFalse("nothing is read at the X7 bank 2 address: "+command,command.startsWith("08010008"));
        assertTrue("every TX frame is encrypted on the air",peer.writes.stream().filter(w -> w.characteristic==peer.tx).allMatch(w -> (w.bytes[0]&255)==0xc0));
        assertEquals(0,peer.addressedWrites().size());
        String json=peer.report.toJson().replace(" ","").replace("\n","");
        assertTrue(json,json.contains("\"memory_map\":\"HEM-7196T1\""));
        assertNotNull("an untested model offers its device report",monitor.deviceReport());
        verify(peer.device,never()).createBond();
    }
    @Test public void fleoSelectsTheSameMap() throws Exception {
        connectEncrypted("HEM-7196T1-FLEO",X7TestPeer.hex("08000000e10001000000"));
        monitor.syncHistory(-1); peer.pump();
        assertEquals("History transfer complete",monitor.status);
        assertTrue(reads().contains(read(0x584,16)));
    }
    @Test public void plainConnectImportsTheSameBanks() throws Exception {
        peer.connect("HEM-7196T1-FLEO",X7TestPeer.plainAdvertisement());
        assertEquals(ProtocolProfile.X7_WLP,monitor.profile);
        assertEquals("X7 read mode available",monitor.status);
        monitor.syncHistory(-1); peer.pump();
        assertEquals("History transfer complete",monitor.status);
        assertEquals(5,monitor.receivedInTransfer);
        assertTrue(reads().contains(read(0x584,16)));
        for(String command:reads()) assertFalse(command,command.startsWith("08010008"));
        assertFalse(peer.systemOpcodes().stream().anyMatch(op -> op.startsWith("70")));
    }
    @Test public void badRecordChecksumIsARejectedSlot() throws Exception {
        // User 2's second record loses its additive sum: that slot is rejected, the four others are kept.
        peer.memory[0x594+14]++;
        peer.connect("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        monitor.syncHistory(-1); peer.pump();
        assertEquals("History transfer incomplete",monitor.status);
        assertTrue(monitor.detail,monitor.detail.contains("4 / 5 decoded") && monitor.detail.contains("1 rejected slots"));
        assertEquals(4,monitor.receivedInTransfer);
        assertEquals(4,peer.store.readings(0).size());
        assertTrue("the session still ends normally",monitor.sessionFinished);
        assertEquals(0,peer.addressedWrites().size());
    }
    @Test public void bareModelNumberIsRefusedBeforeAnyWrite() throws Exception {
        peer.connect("HEM-7196T1",ENCRYPTED_FLE);
        assertEquals("Action failed",monitor.status);
        assertTrue(monitor.detail,monitor.detail.contains("not enabled"));
        assertTrue(peer.writes.isEmpty()); assertTrue(peer.descriptorWrites.isEmpty());
    }
    @Test public void catalogNamesAreNotDeviceInformationIdentities() throws Exception {
        for(String name:new String[]{"M4 Connect AFib","X4 Connect AFib"}) {
            peer.connect(name,ENCRYPTED_FLE);
            assertEquals(name,"Action failed",monitor.status);
            assertTrue(monitor.detail,monitor.detail.contains("not enabled"));
        }
        assertTrue(peer.writes.isEmpty()); assertTrue(peer.descriptorWrites.isEmpty());
    }
    @Test public void contradictingAdvertisementIsRefusedBeforeAnyWrite() throws Exception {
        // Device Information says HEM-7196T1, the encrypted advertisement names the X7 (EOSL) catalog model.
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_EOSL);
        assertRefused();
    }
    @Test public void contradictingNameIsRefusedBeforeAnyWrite() throws Exception {
        peer.advertisedName="BLEsmart_000000EB0011223344";
        peer.connect("HEM-7380T1-EOSL",X7TestPeer.plainAdvertisement());
        assertRefused();
    }
    @Test public void matchingOrUnknownAdvertisementsProceed() throws Exception {
        peer.advertisedName="BLEsmart_000000E10011223344";
        peer.connect("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        assertTrue("FLE and FLEO share one map",monitor.ready);
    }
    @Test public void encryptedRouteWaitsForAndroidsBondThenRetriesOnce() throws Exception {
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        assertEquals(ProtocolProfile.X7_SECURE_WLP,monitor.profile);
        assertEquals("Pair on your phone",monitor.status);
        assertEquals("only the first CCCD write so far",1,peer.descriptorWrites.size());
        assertTrue("nothing written while waiting",peer.writes.isEmpty());
        // A stale broadcast (the live state disagrees) and an intermediate state do nothing.
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        android.content.Intent stale=new android.content.Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        stale.putExtra(BluetoothDevice.EXTRA_DEVICE,peer.device); stale.putExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_BONDED);
        ((android.content.BroadcastReceiver)peer.get("bondReceiver")).onReceive(monitor,stale);
        peer.bond(BluetoothDevice.BOND_BONDING,BluetoothDevice.BOND_NONE); peer.pump();
        assertEquals(1,peer.descriptorWrites.size());
        assertEquals("Pair on your phone",monitor.status);
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); peer.pump();
        assertEquals("the held CCCD write ran exactly once more",peer.descriptorWrites.get(0),peer.descriptorWrites.get(1));
        assertEquals(3,peer.descriptorWrites.size());
        assertEquals("X7 authenticated for reads",monitor.status);
        assertEquals(Arrays.asList("7005","7006","2001"),peer.systemOpcodes().subList(1,4));
        verify(peer.device,never()).createBond();
        // The bond timeout of the finished wait is gone: idling past it never reports a failed pairing.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(70));
        assertFalse(monitor.detail,monitor.detail.contains("pairing did not complete"));
        assertNull(peer.get("secureBondTimeout"));
    }
    @Test public void bondThatNeverArrivesEndsCleanly() throws Exception {
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        assertEquals("Pair on your phone",monitor.status);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        assertEquals("Action failed",monitor.status);
        assertTrue(monitor.detail,monitor.detail.contains("pairing did not complete"));
        assertNull("the connection ends",peer.get("gatt"));
        assertEquals(1,peer.descriptorWrites.size()); assertTrue(peer.writes.isEmpty());
        // A late bond after the failure retries nothing.
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); X7TestPeer.idle();
        assertEquals(1,peer.descriptorWrites.size());
        verify(peer.device,never()).createBond();
    }
    @Test public void cancelledBondEndsCleanly() throws Exception {
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDING);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        peer.bond(BluetoothDevice.BOND_NONE,BluetoothDevice.BOND_BONDING); X7TestPeer.idle();
        assertEquals("Action failed",monitor.status);
        assertNull(peer.get("gatt"));
        assertTrue(peer.writes.isEmpty());
    }
    @Test public void secondLinkSecurityFailureIsNotRetried() throws Exception {
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); peer.pump();
        assertEquals("Action failed",monitor.status);
        assertEquals("one retry only",2,peer.descriptorWrites.size());
        assertTrue(peer.writes.isEmpty());
    }
    @Test public void plainRouteNeverHoldsForTheBond() throws Exception {
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        peer.connect("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        assertEquals("Action failed",monitor.status);
        assertEquals(1,peer.descriptorWrites.size());
    }
    @Test public void encryptedWriteIsNeverRetried() throws Exception {
        // The first encrypted frame (the authorization poll) fails for link security: a retry would re-encrypt it under
        // the next counter, so the session fails instead of waiting.
        boolean[] failed={false};
        peer.writeStatus=value -> !failed[0] && (value[0]&255)==0xc0 && (failed[0]=true)?BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:BluetoothGatt.GATT_SUCCESS;
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        assertEquals("Action failed",monitor.status);
        assertNull(peer.get("gatt"));
        assertEquals("one encrypted frame, never resent",1,peer.writes.stream().filter(w -> (w.bytes[0]&255)==0xc0).count());
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); peer.pump();
        assertEquals(1,peer.writes.stream().filter(w -> (w.bytes[0]&255)==0xc0).count());
    }
    @Test public void heldPlaintextWriteIsRetriedWithTheSameBytes() throws Exception {
        boolean[] failed={false};
        peer.writeStatus=value -> !failed[0] && value[0]==0x11 && (failed[0]=true)?BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:BluetoothGatt.GATT_SUCCESS;
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        assertEquals("Pair on your phone",monitor.status);
        assertEquals(1,peer.writes.size());
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); peer.pump();
        assertArrayEquals("the nonce frame is sent again unchanged",peer.writes.get(0).bytes,peer.writes.get(1).bytes);
        assertEquals("X7 authenticated for reads",monitor.status);
    }
    @Test public void modelReadIsHeldWhenTheEncryptedRouteIsExpected() throws Exception {
        peer.modelReadResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        assertEquals("Pair on your phone",monitor.status);
        assertEquals(1,peer.modelReads);
        assertTrue(peer.descriptorWrites.isEmpty()); assertTrue(peer.writes.isEmpty());
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); peer.pump();
        assertEquals("the held read ran once more, then the information read after authentication",3,peer.modelReads);
        assertEquals(ProtocolProfile.X7_SECURE_WLP,monitor.profile);
        assertEquals("X7 authenticated for reads",monitor.status);
        verify(peer.device,never()).createBond();
    }
    @Test public void savedKeyAlsoHoldsTheModelRead() throws Exception {
        peer.modelReadResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        stageKey();
        peer.connect("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        assertEquals("Pair on your phone",monitor.status);
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); peer.pump();
        assertEquals("X7 authenticated for reads",monitor.status);
    }
    @Test public void modelReadIsNotHeldWithoutAnExpectedEncryptedRoute() throws Exception {
        peer.modelReadResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        peer.connect("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        assertEquals("Action failed",monitor.status);
        assertEquals(1,peer.modelReads);
    }
    @Test public void lateNoneBroadcastKeepsWaiting() throws Exception {
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        org.mockito.Mockito.when(peer.device.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        peer.bond(BluetoothDevice.BOND_NONE,BluetoothDevice.BOND_NONE); X7TestPeer.idle();
        assertEquals("a NONE that never left NONE is not a cancelled bond","Pair on your phone",monitor.status);
        assertNotNull(peer.get("gatt"));
        peer.bond(BluetoothDevice.BOND_BONDED,BluetoothDevice.BOND_BONDING); peer.pump();
        assertEquals("X7 authenticated for reads",monitor.status);
    }
    @Test public void bondedMonitorFailsWithoutWaiting() throws Exception {
        peer.descriptorResults.add(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION);
        stageKey();
        peer.connect("HEM-7196T1-FLE",ENCRYPTED_FLE);
        assertEquals("Action failed",monitor.status);
        assertEquals(1,peer.descriptorWrites.size());
    }

    @Test public void pairWithoutTheOverrideStaysReadOnly() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        peer.pair("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        assertEquals(ProtocolProfile.X7_WLP,monitor.profile);
        assertEquals("X7 read mode available",monitor.status);
        assertTrue(monitor.detail,monitor.detail.contains("HEM-7196T1-FLE"));
        assertFalse(monitor.registrationPending); assertFalse(monitor.registrationReading);
        assertEquals(0,peer.addressedWrites().size());
        assertTrue("no settings read before a refused registration",peer.wlpTranscript().isEmpty());
    }
    @Test public void pairWithTheOverrideRegistersTheSelectedUser() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        DeveloperOptions.setAllowUntested(monitor,Aspect.REGISTRATION,true);
        peer.pair("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        assertTrue(monitor.registrationPending);
        assertEquals("HEM-7196T1-FLE",monitor.registrationIdentity);
        assertTrue(monitor.registrationOverride);
        assertEquals("nothing is written before the user confirms",0,peer.addressedWrites().size());
        monitor.registerX7User(2); peer.pump();
        assertEquals("User 2 registration acknowledged",monitor.status);
        List<byte[]> frames=peer.addressedWrites();
        assertEquals(2,frames.size());
        assertEquals("the 0x54 prefix, then block 3 at 0x80",0x54,frames.get(0)[4]&255);
        assertEquals(0x80,frames.get(1)[4]&255);
        assertTrue(peer.report.toJson().replace(" ","").contains("\"aspect\":\"registration\",\"outcome\":\"acknowledged\",\"override\":true"));
    }
    @Test public void encryptedPairNeverRegistersEvenWithTheOverride() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        DeveloperOptions.setAllowUntested(monitor,Aspect.REGISTRATION,true);
        peer.pair("HEM-7196T1-FLE",ENCRYPTED_FLE);
        assertEquals(ProtocolProfile.X7_SECURE_WLP,monitor.profile);
        assertEquals("X7 authenticated for reads",monitor.status);
        assertEquals(Arrays.asList("7001","7005","7006","2001"),peer.systemOpcodes().subList(1,5));
        assertEquals(0,peer.addressedWrites().size());
        assertFalse(monitor.registrationPending);
    }
    @Test public void clockSyncNeedsTheUntestedOverride() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        peer.connect("HEM-7196T1-FLE",X7TestPeer.plainAdvertisement());
        assertFalse("untested clock block without the override",monitor.clockSyncOffered());
        int sent=peer.writes.size(); monitor.beginClockSync(); X7TestPeer.idle();
        assertEquals(sent,peer.writes.size());
        DeveloperOptions.setAllowUntested(monitor,Aspect.CLOCK_BLOCK,true);
        assertTrue(monitor.clockSyncOffered()); assertTrue(monitor.canClockSync());
        monitor.beginClockSync(); peer.pump();
        assertTrue(monitor.clockSyncPending);
        byte[] image=Arrays.copyOfRange(peer.memory,0x10,0x54);
        byte[] frame=new VendorClockWrite("HEM-7196T1-FLE",image,X7TestPeer.NOW,true,1).frame();
        monitor.confirmClockSync(null); peer.pump();
        assertEquals(1,peer.addressedWrites().size());
        assertArrayEquals("block 3 at 0x80",frame,peer.addressedWrites().get(0));
        assertEquals("Clock sync complete",monitor.status);
        assertTrue(peer.report.toJson().replace(" ","").contains("\"aspect\":\"clock_block\",\"outcome\":\"acknowledged\",\"override\":true"));
    }
    @Test public void encryptedClockSyncNeedsBothSwitches() throws Exception {
        connectEncrypted("HEM-7196T1-FLE",ENCRYPTED_FLE);
        DeveloperOptions.setAllowExperimental(monitor,Experiment.CLOCK_SYNC_ENCRYPTED_X7,true);
        assertFalse("the encrypted switch alone does not pass the untested clock block",monitor.clockSyncOffered());
        DeveloperOptions.setAllowUntested(monitor,Aspect.CLOCK_BLOCK,true);
        assertTrue(monitor.clockSyncOffered());
    }

    private void assertRefused() throws Exception {
        assertEquals("Action failed",monitor.status);
        assertTrue(monitor.detail,monitor.detail.contains("advertisement names a different model"));
        assertTrue("no characteristic write",peer.writes.isEmpty());
        assertTrue("no descriptor write",peer.descriptorWrites.isEmpty());
        assertNull(peer.get("gatt"));
    }
    private void stageKey() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        byte[] key=new byte[16]; for(int i=0;i<16;i++) key[i]=(byte)(0x40+i);
        new PairingKeys(monitor).put(X7TestPeer.ADDRESS,key);
        peer.ltk=key.clone();
    }
    /** Connect with a saved application key over the encrypted route. */
    private void connectEncrypted(String model,byte[] advertisement) throws Exception {
        stageKey();
        peer.connect(model,advertisement);
        assertEquals(ProtocolProfile.X7_SECURE_WLP,monitor.profile);
        assertEquals("X7 authenticated for reads",monitor.status);
    }
    /** WLP READ commands the cuff saw, as hex. */
    private List<String> reads() {
        List<String> result=new ArrayList<>();
        for(String command:peer.wlpTranscript()) if(command.startsWith("080100")) result.add(command);
        return result;
    }
    private static String read(int address,int length) { return HexFormat.of().formatHex(Wlp.read(address,length)); }
    /** A synthetic valid reading (120/80/72 on 2026-10-16 14:30) with distinct seconds and sequence. */
    private void record(int address,int index) {
        byte[] raw=X7TestPeer.hex("5f50481a0e2aad070000341200000000");
        raw[6]=(byte)(0x80+index); raw[10]=(byte)index;
        // Byte 14: the 8-bit sum of bytes 0-13, which every X7-family record carries.
        raw[14]=(byte)org.openpressure.protocol.VendorMemoryModel.checksum(raw,14);
        System.arraycopy(raw,0,peer.memory,address,16);
    }
}
