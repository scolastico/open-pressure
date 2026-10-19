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
 * Replays every shared device fixture through the real MonitorService: model verification, notification setup,
 * key handling, the ATT queue, history parsing, the explicit clock sync (settings backup, clock-block write and its
 * acknowledgment) and END. Only the Bluetooth peer is simulated, and it answers strictly from the fixture.
 * Writes {@code build/fixture-replay/<fixture>.txt}.
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
    private boolean historyRequested,clockConfirmed,backupFlushed;

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
        monitor.address=ADDRESS; monitor.connectedName="Synthetic monitor";
        set("pairingRequested",fixture.flow.equals("pair"));
        // The fixture's import users are the saved selection that Connect and Pair load for this monitor.
        monitor.importUsers=fixture.importUsers;
        store.remember(ADDRESS,"Synthetic monitor",String.valueOf(fixture.dis.get("model")),1);
        store.setImportUsers(ADDRESS,fixture.importUsers);
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
        call("identifyWlp",new Class<?>[0]);
        pump();
        if(outcome==null) {
            List<Fixture.Reading> decoded=new ArrayList<>();
            for(Reading r:store.readings(0))
                decoded.add(new Fixture.Reading(r.user,(int)r.systolic,(int)r.diastolic,r.pulse.intValue(),r.time==null?null:LocalDateTime.parse(r.time)));
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
                // A user-started import (or Read all) after a manual Connect reaches "Vendor history available".
                if(!historyRequested && !fixture.flow.equals("pair") && !monitor.historyRunning && cursor.position()>0) {
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
        VendorMemoryModel model=VendorMemoryModel.identify("HEM-7156T");
        BluetoothGattService vendor=new BluetoothGattService(VendorWlpSession.service(model),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        for(UUID id:fixture.uuids("tx")) characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_WRITE,false);
        for(UUID id:fixture.uuids("rx")) characteristic(vendor,id,BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
        characteristic(vendor,fixture.uuids("unlock").get(0),BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
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
