package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.*;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.data.PairingKeys;
import org.openpressure.app.data.Reading;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.VendorPairingKeys;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.protocol.DeviceReport;
import org.openpressure.protocol.OperationQueue;
import org.openpressure.protocol.VendorMemoryModel;
import org.openpressure.protocol.VendorWlpSession;
import org.openpressure.protocol.WriteAspectIndex;
import org.openpressure.protocol.fixture.FakeAndroidKeyStore;
import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.FixtureCursor;
import org.openpressure.protocol.fixture.Outcome;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Replays every shared device fixture through the real MonitorService from the connection callback on: MTU
 * negotiation (the fixture's {@code mtu}), service discovery, model verification, the X7 route choice from the
 * fixture's advertisement, notification setup, key handling, the ATT queue (including the delayed X7 system
 * transaction), the encrypted X7 route (application handshake, authorization poll and encrypted frames, answered by
 * the fixture's cuff-side {@code SecurePeer}; a saved-key Connect starts with the fixture key in PairingKeys), history parsing, X7 profile registration, the explicit clock sync (settings backup, clock-block write
 * and its acknowledgment) and END. Only the Bluetooth peer is simulated, and it answers strictly from the fixture.
 * A Pair starts after Android's fresh bond completed (bonding exchanges no GATT bytes). Writes
 * {@code build/fixture-replay/<fixture>.txt}.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class FixtureReplayTest {
    private static final String ADDRESS="AA:BB:CC:DD:EE:FF";
    private final String name;
    private Fixture fixture;
    private FixtureCursor cursor;
    private MonitorService monitor;
    private ReadingStore store;
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private final Map<UUID,BluetoothGattCharacteristic> characteristics=new HashMap<>();
    private final Deque<Runnable> peer=new ArrayDeque<>();
    private Outcome outcome;
    private boolean historyRequested,clockConfirmed,backupFlushed,registrationChosen;
    /** A single-channel X7-family fixture (FE4A layout) rather than a legacy four-channel one. */
    private boolean x7;

    public FixtureReplayTest(String name) { this.name=name; }

    @ParameterizedRobolectricTestRunner.Parameters(name="{0}")
    public static List<Object[]> fixtures() {
        String only=System.getProperty("fixture");
        List<Object[]> result=new ArrayList<>();
        for(String name:Fixture.all()) if(only==null || only.isEmpty() || only.equals("all") || java.util.Arrays.asList(only.split(",")).contains(name)) result.add(new Object[]{name});
        return result;
    }

    @Before public void setup() throws Exception {
        FakeAndroidKeyStore.install();
        fixture=Fixture.load(name);
        cursor=new FixtureCursor(fixture,Fixture.Client.OURS_APP);
        monitor=Robolectric.buildService(MonitorService.class).get();
        AutoImportSettings.setMode(monitor,AutoImportSettings.OFF);
        store=new ReadingStore(monitor);
        monitor.clock=Clock.fixed(fixture.time.atZone(fixture.zone).toInstant(),fixture.zone);
        gatt=mock(BluetoothGatt.class);
        BluetoothDevice device=mock(BluetoothDevice.class);
        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(device.getAddress()).thenReturn(ADDRESS);
        when(gatt.getDevice()).thenReturn(device);
        // onCreate is not run for the service under test; install the same key backend it would create.
        set("store",store); set("gatt",gatt); set("keys",new PairingKeys(monitor));
        set("operations",new OperationQueue(error -> call("fail",new Class<?>[]{String.class},error)));
        callback=(BluetoothGattCallback)get("callback");
        // connect() names the session after the scan result; X7 identities check it against Device Information.
        monitor.address=ADDRESS; monitor.connectedName=fixture.advertisedName==null?"Synthetic monitor":fixture.advertisedName;
        set("pairingRequested",fixture.flow.equals("pair"));
        // The fixture's import users are the saved selection that Connect and Pair load for this monitor.
        monitor.importUsers=fixture.importUsers;
        store.remember(ADDRESS,"Synthetic monitor",String.valueOf(fixture.dis.get("model")),1);
        store.setImportUsers(ADDRESS,fixture.importUsers);
        // A single-channel fixture (no legacy unlock alias) describes an X7-family monitor on FE4A.
        x7=!fixture.aliases.containsKey("unlock");
        // connect() copies the selected scan result's Omron manufacturer data (company prefix removed by Android).
        byte[] manufacturer=fixture.omronManufacturerPayload();
        set("connectionManufacturer",manufacturer);
        // Pair continues on the reconnection after Android's fresh bond, as MonitorService does after X7Bonding.
        set("x7FreshBondEstablished",fixture.flow.equals("pair"));
        // Robolectric cannot fsync a directory; the registration backup file itself is still written before any write.
        monitor.registrationBackupSync=directory -> {};
        buildServices();
        stageKey();
        stubPeer();
        if(fixture.flow.equals("sync-clock")) {
            // Every clock-block entry is untested: the developer override lets the explicit sync run, as in a forced sync.
            DeveloperOptions.setUnlocked(monitor,true);
            DeveloperOptions.setAllowUntested(monitor,WriteAspectIndex.Aspect.CLOCK_BLOCK,true);
            // Robolectric cannot fsync a directory; the backup file itself is still written before the write.
            monitor.clockBackupSync=directory -> {};
        }
    }
    @After public void cleanup() {
        if(monitor!=null) monitor.disconnect();
        if(store!=null) store.close();
    }

    @Test public void everyFixtureMatchesOrIsExpectedRejected() throws Exception {
        // As after connectGatt(): Android reports the connection, MonitorService negotiates the MTU and discovers services.
        callback.onConnectionStateChange(gatt,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_CONNECTED);
        pump();
        if(outcome==null) {
            List<Fixture.Reading> decoded=new ArrayList<>();
            for(Reading r:store.readings(0))
                decoded.add(new Fixture.Reading(r.user,(int)r.systolic,(int)r.diastolic,r.pulse.intValue(),r.time==null?null:LocalDateTime.parse(r.time))
                    .withFlags(r.status==null?null:(r.status&4)!=0?1:0,r.status==null?null:(r.status&1)!=0?1:0,r.afib==null?null:r.afib?1:0));
            String clock=fixture.flow.equals("sync-clock")?clockOutcome(monitor.clockSyncOutcome):null;
            outcome=cursor.finished() && !monitor.historyRunning && !monitor.clockSyncRunning?cursor.finish(decoded,clock)
                :Outcome.rejected(cursor.position(),"MonitorService stopped: "+monitor.status+" — "+monitor.detail+" · "+cursor.stalled().detail);
        }
        writeReport();
        Outcome.Kind wanted=fixture.expectRejected(Fixture.Client.OURS_APP)?Outcome.Kind.REJECTED:Outcome.Kind.MATCHES;
        assertEquals(outcome.report(),wanted,outcome.kind);
        // A refused identity must be refused before any byte reaches the monitor.
        if(wanted==Outcome.Kind.REJECTED) assertTrue(outcome.report(),cursor.transcript().stream().noneMatch(line -> line.startsWith("write ")));
        // Negative fixtures: the corrupt slot is counted and the session still ends; an untrusted index falls back to a sweep.
        if(name.endsWith("/connect-bad-record-checksum")) {
            assertEquals(1,(int)(Integer)get("wlpRejected"));
            assertEquals("History transfer incomplete",monitor.status);
        }
        if(name.endsWith("/connect-bad-index-checksum")) assertEquals(MonitorService.ImportMode.INDEX_FALLBACK,monitor.importMode);
        if(x7 && fixture.secureMode!=null && wanted==Outcome.Kind.MATCHES) {
            // The application key MonitorService keeps is the cuff peer's: the saved one, or the freshly derived one.
            assertArrayEquals("saved application key",cursor.secure().ltk(),new PairingKeys(monitor).get(ADDRESS));
            assertNull("the encrypted route never registers a profile",monitor.registrationBackupFile());
        } else if(x7 && fixture.flow.equals("pair") && wanted==Outcome.Kind.MATCHES) {
            String identity=WriteAspectIndex.resolve(fixture.dis.get("model"));
            if(WriteAspectIndex.permits(WriteAspectIndex.lookup(identity,WriteAspectIndex.Aspect.REGISTRATION).status,false)) {
                // A permitted registration keeps its backup and ends acknowledged before END; nothing else is imported.
                assertNotNull("registration backup",monitor.registrationBackupFile());
                assertEquals("User "+fixture.registerUser+" registration acknowledged",monitor.status);
            } else {
                // Untested registration without the developer override: read-only ready state, no backup, no write.
                assertNull("no registration backup",monitor.registrationBackupFile());
                assertEquals("X7 read mode available",monitor.status);
            }
        }
        if(fixture.flow.equals("read-all")) assertEquals(MonitorService.ImportMode.FULL_SWEEP,monitor.importMode);
        if(fixture.flow.equals("sync-clock")) {
            assertTrue("the clock-sync session ends with END",monitor.sessionFinished);
            // A backup precedes every write; nothing is saved when nothing is written.
            assertEquals(!"skipped".equals(fixture.expectClock),monitor.clockSyncBackupFile()!=null);
        }
    }
    private static String clockOutcome(DeviceReport.WriteOutcome outcome) {
        switch(outcome) {
            case ACKNOWLEDGED: return "acknowledged";
            case SKIPPED_WITHIN_THRESHOLD: return "skipped";
            case REJECTED: return "rejected";
            default: return outcome.name().toLowerCase(Locale.ROOT);
        }
    }

    /** Runs scripted peer events until the service has nothing left to do. */
    private void pump() {
        for(int guard=0;guard<100000 && outcome==null;guard++) {
            idle();
            if(peer.isEmpty()) {
                // Off-main-thread saves (the registration backup), then the short delays MonitorService schedules
                // itself, such as the 500 ms wake-up wait before the plain X7 system transaction; timeouts stay pending.
                flushIo();
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
                if(!peer.isEmpty()) continue;
                // X7 Pair: choose the fixture's user once the backup is saved and the app asks for the user.
                if(monitor.registrationPending && !registrationChosen) { registrationChosen=true; monitor.registerX7User(fixture.registerUser); continue; }
                // A user-started import (or Read all) after a manual Connect reaches "Vendor history available"; after an
                // encrypted X7 Pair, which never registers, the user starts Import once the cuff authorized the app.
                boolean securePair=fixture.flow.equals("pair") && fixture.secureMode!=null && "X7 authenticated for reads".equals(monitor.status);
                if(!historyRequested && (!fixture.flow.equals("pair") || securePair) && !monitor.historyRunning && cursor.position()>0) {
                    historyRequested=true;
                    if(fixture.flow.equals("sync-clock")) monitor.beginClockSync();
                    else if(fixture.flow.equals("read-all")) monitor.readAllHistory(); else monitor.syncHistory(-1);
                    continue;
                }
                // Sync clock: confirm the preview with the phone time (the pinned fixture time) once it is shown.
                if(monitor.clockSyncPending && !clockConfirmed) { clockConfirmed=true; monitor.confirmClockSync(null); continue; }
                // The settings backup is saved off the main thread before the write is authorized.
                if(clockConfirmed && !backupFlushed && monitor.clockSyncRunning) { backupFlushed=true; flushIo(); continue; }
                return;
            }
            peer.removeFirst().run();
        }
    }

    private void stubPeer() {
        when(gatt.requestMtu(anyInt())).thenAnswer(invocation -> {
            int negotiated=Math.max(23,Math.min((Integer)invocation.getArgument(0),fixture.mtu));
            peer.add(() -> callback.onMtuChanged(gatt,negotiated,BluetoothGatt.GATT_SUCCESS));
            return true;
        });
        when(gatt.discoverServices()).thenAnswer(invocation -> { peer.add(() -> callback.onServicesDiscovered(gatt,BluetoothGatt.GATT_SUCCESS)); return true; });
        when(gatt.setCharacteristicNotification(any(),anyBoolean())).thenReturn(true);
        when(gatt.readCharacteristic(any())).thenAnswer(invocation -> {
            BluetoothGattCharacteristic c=invocation.getArgument(0);
            String value=c.getUuid().equals(sig(0x2a24))?fixture.dis.get("model"):null;
            byte[] bytes=value==null?new byte[0]:value.getBytes(StandardCharsets.UTF_8);
            peer.add(() -> callback.onCharacteristicRead(gatt,c,bytes,BluetoothGatt.GATT_SUCCESS));
            return true;
        });
        when(gatt.writeDescriptor(any(BluetoothGattDescriptor.class),any(byte[].class))).thenAnswer(invocation -> {
            BluetoothGattDescriptor d=invocation.getArgument(0);
            peer.add(() -> callback.onDescriptorWrite(gatt,d,BluetoothGatt.GATT_SUCCESS));
            return BluetoothStatusCodes.SUCCESS;
        });
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class),any(byte[].class),anyInt())).thenAnswer(invocation -> {
            BluetoothGattCharacteristic c=invocation.getArgument(0);
            byte[] value=((byte[])invocation.getArgument(1)).clone();
            Outcome diverged=cursor.onWrite(c.getUuid(),value);
            if(diverged!=null) { outcome=diverged; return BluetoothStatusCodes.SUCCESS; }
            peer.add(() -> callback.onCharacteristicWrite(gatt,c,BluetoothGatt.GATT_SUCCESS));
            for(FixtureCursor.Notification n:cursor.pendingNotifications()) {
                BluetoothGattCharacteristic target=characteristics.get(n.characteristic);
                peer.add(() -> callback.onCharacteristicChanged(gatt,target,n.value));
            }
            return BluetoothStatusCodes.SUCCESS;
        });
    }

    private void buildServices() {
        BluetoothGattService vendor;
        if(x7) {
            // FE4A single-channel layout: TX and SYS accept writes without response, RX and SYS notify.
            vendor=new BluetoothGattService(Uuids.VENDOR_ALIAS,BluetoothGattService.SERVICE_TYPE_PRIMARY);
            int write=BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
            characteristic(vendor,fixture.uuids("tx").get(0),write,false);
            characteristic(vendor,fixture.uuids("rx").get(0),BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
            characteristic(vendor,fixture.uuids("sys").get(0),write|BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
        } else {
            VendorMemoryModel model=VendorMemoryModel.identify("HEM-7156T");
            vendor=new BluetoothGattService(VendorWlpSession.service(model),BluetoothGattService.SERVICE_TYPE_PRIMARY);
            for(UUID id:fixture.uuids("tx")) characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_WRITE,false);
            for(UUID id:fixture.uuids("rx")) characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
            characteristic(vendor,fixture.uuids("unlock").get(0),BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
        }
        BluetoothGattService dis=new BluetoothGattService(sig(0x180a),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        characteristic(dis,sig(0x2a24),BluetoothGattCharacteristic.PROPERTY_READ,false);
        when(gatt.getService(vendor.getUuid())).thenReturn(vendor);
        when(gatt.getService(dis.getUuid())).thenReturn(dis);
        if(fixture.aliases.containsKey("clock")) {
            BluetoothGattService time=new BluetoothGattService(Uuids.CTS,BluetoothGattService.SERVICE_TYPE_PRIMARY);
            characteristic(time,fixture.uuids("clock").get(0),BluetoothGattCharacteristic.PROPERTY_WRITE,false);
            when(gatt.getService(Uuids.CTS)).thenReturn(time);
        }
        List<BluetoothGattService> services=new ArrayList<>(List.of(vendor,dis));
        when(gatt.getServices()).thenReturn(services);
    }
    private void stageKey() throws Exception {
        // Plain X7 sessions use no application key (a saved one would select the encrypted route); an encrypted Connect
        // starts with the application key an earlier pairing saved, which Pair forgets before its fresh pairing.
        if(x7) {
            if("saved".equals(fixture.secureMode)) new PairingKeys(monitor).put(ADDRESS,fixture.key);
            return;
        }
        VendorMemoryModel model=VendorMemoryModel.identify(fixture.dis.get("model"));
        if(model==null || fixture.key==null) return;
        VendorPairingKeys keys=new VendorPairingKeys(new PairingKeys(monitor),ADDRESS,model.id);
        // Pair reuses a staged candidate instead of generating a random key; Connect uses the saved key.
        if(fixture.flow.equals("pair")) keys.stage(fixture.key); else keys.confirm(fixture.key);
    }
    private void writeReport() throws IOException {
        Path out=Paths.get("build","fixture-replay",name+".txt");
        Files.createDirectories(out.getParent());
        StringBuilder text=new StringBuilder("ours (app): ").append(outcome.report()).append('\n');
        for(String line:cursor.transcript()) text.append("  ").append(line).append('\n');
        Files.write(out,text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private BluetoothGattCharacteristic characteristic(BluetoothGattService service,UUID id,int properties,boolean cccd) {
        BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(id,properties,BluetoothGattCharacteristic.PERMISSION_WRITE|BluetoothGattCharacteristic.PERMISSION_READ);
        if(cccd) c.addDescriptor(new BluetoothGattDescriptor(Uuids.CCCD,BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(c); characteristics.put(id,c); return c;
    }
    private void flushIo() {
        try { ((ExecutorService)get("dumpIo")).submit(() -> {}).get(10,TimeUnit.SECONDS); }
        catch(Exception failure) { throw new AssertionError(failure); }
        idle();
    }
    private static UUID sig(int id) { return Uuids.sig(id); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private Object get(String field) throws Exception { Field f=MonitorService.class.getDeclaredField(field); f.setAccessible(true); return f.get(monitor); }
    private void set(String field,Object value) throws Exception { Field f=MonitorService.class.getDeclaredField(field); f.setAccessible(true); f.set(monitor,value); }
    private void call(String method,Class<?>[] types,Object... args) {
        try { Method m=MonitorService.class.getDeclaredMethod(method,types); m.setAccessible(true); m.invoke(monitor,args); }
        catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
