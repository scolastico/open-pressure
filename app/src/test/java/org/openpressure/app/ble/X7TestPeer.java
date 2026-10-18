package org.openpressure.app.ble;

import android.bluetooth.*;
import android.os.Looper;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.data.PairingKeys;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.protocol.DeviceReport;
import org.openpressure.protocol.OperationQueue;
import org.openpressure.protocol.fixture.FakeAndroidKeyStore;
import org.robolectric.Robolectric;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * A simulated X7 for service integration tests: the real MonitorService, ATT queue, validators and plans run
 * unchanged from model verification on; only the Bluetooth peer is simulated. It answers the SYS nonce
 * transaction, START/READ/END from a synthetic 64 KiB memory and addressed writes with a compact acknowledgment.
 * Every characteristic write is recorded as it reached the GATT mock.
 */
final class X7TestPeer {
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,16,14,30,45);
    static final String ADDRESS="AA:BB:CC:DD:EE:FF";
    /** A synthetic 68-byte settings/index image with valid pointers and a valid block-3 checksum. */
    static final byte[] SETTINGS=hex("63000b00038002000102030405060708090a0b0c0d0e0f105a0611a5123456789abc6203095afedcba987654a0b1c2d302e5f60718090a0b0c0d19992132435465768798");
    static final byte[] START=hex("0800000000100018"), START_REPLY=hex("0880000000100098"), END=hex("080f000000000007"), END_REPLY=hex("088f000000000087");

    static final class Write {
        final BluetoothGattCharacteristic characteristic; final byte[] bytes;
        Write(BluetoothGattCharacteristic characteristic,byte[] bytes) { this.characteristic=characteristic; this.bytes=bytes.clone(); }
    }
    /** How the peer answers an addressed (C0) write. */
    enum WriteReply { ACK, REJECT, SILENT }

    final MonitorService monitor;
    final ReadingStore store;
    final BluetoothGatt gatt;
    final BluetoothDevice device;
    final BluetoothGattCallback callback;
    final BluetoothGattService alias,dis;
    final BluetoothGattCharacteristic tx,rx,system,model;
    final List<Write> writes=new ArrayList<>();
    final Deque<Runnable> peer=new ArrayDeque<>();
    final byte[] memory=new byte[0x10000];
    WriteReply addressedReply=WriteReply.ACK;
    String modelName="HEM-7380T1-EOSL";
    DeviceReport report;

    X7TestPeer() throws Exception {
        FakeAndroidKeyStore.install();
        monitor=Robolectric.buildService(MonitorService.class).get();
        AutoImportSettings.setMode(monitor,AutoImportSettings.OFF);
        store=new ReadingStore(monitor);
        gatt=mock(BluetoothGatt.class);
        device=mock(BluetoothDevice.class);
        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(device.getAddress()).thenReturn(ADDRESS);
        when(gatt.getDevice()).thenReturn(device);
        set("store",store); set("gatt",gatt); set("keys",new PairingKeys(monitor));
        set("operations",new OperationQueue(error -> call("fail",new Class<?>[]{String.class},error)));
        callback=(BluetoothGattCallback)get("callback");
        monitor.clock=Clock.fixed(NOW.toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        monitor.clockBackupSync=directory -> {};
        monitor.registrationBackupSync=directory -> {};
        alias=new BluetoothGattService(Uuids.VENDOR_ALIAS,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        tx=characteristic(alias,Uuids.WLP_TX,BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,false);
        rx=characteristic(alias,Uuids.WLP_RX,BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
        system=characteristic(alias,Uuids.HANDSHAKE,BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE|BluetoothGattCharacteristic.PROPERTY_NOTIFY,true);
        dis=new BluetoothGattService(Uuids.sig(0x180a),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        model=characteristic(dis,Uuids.sig(0x2a24),BluetoothGattCharacteristic.PROPERTY_READ,false);
        when(gatt.getService(Uuids.VENDOR_ALIAS)).thenReturn(alias);
        when(gatt.getService(Uuids.sig(0x180a))).thenReturn(dis);
        when(gatt.getServices()).thenReturn(new ArrayList<>(List.of(alias,dis)));
        when(gatt.setCharacteristicNotification(any(),anyBoolean())).thenReturn(true);
        when(gatt.readCharacteristic(any())).thenAnswer(invocation -> {
            BluetoothGattCharacteristic c=invocation.getArgument(0);
            byte[] value=c==model?modelName.getBytes(StandardCharsets.UTF_8):new byte[0];
            peer.add(() -> callback.onCharacteristicRead(gatt,c,value,BluetoothGatt.GATT_SUCCESS));
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
            writes.add(new Write(c,value));
            peer.add(() -> callback.onCharacteristicWrite(gatt,c,BluetoothGatt.GATT_SUCCESS));
            answer(c,value);
            return BluetoothStatusCodes.SUCCESS;
        });
        System.arraycopy(SETTINGS,0,memory,0x10,SETTINGS.length);
    }

    /** The peer's reply to one write, queued after the write's own completion callback. */
    void answer(BluetoothGattCharacteristic c,byte[] value) {
        if(c==system && value.length==20 && value[0]==0x11) {
            byte[] reply=new byte[7]; reply[0]=(byte)0x91; System.arraycopy(value,1,reply,2,4);
            notify(system,reply);
        } else if(c==tx) {
            byte[] reply=wlpReply(value);
            if(reply!=null) notify(rx,reply);
        }
    }
    /** The plaintext WLP answer to a plaintext command, or null to stay silent. */
    byte[] wlpReply(byte[] command) {
        if(Arrays.equals(command,START)) return START_REPLY.clone();
        if(Arrays.equals(command,END)) return END_REPLY.clone();
        if(command.length==8 && command[1]==1 && command[2]==0) return memoryReply(command);
        if(command.length>8 && command[1]==1 && command[2]==(byte)0xc0) {
            if(addressedReply==WriteReply.SILENT) return null;
            byte[] ack=new byte[8]; ack[0]=8; ack[1]=(byte)0x81; System.arraycopy(command,2,ack,2,4);
            ack[6]=(byte)(addressedReply==WriteReply.REJECT?1:0); bcc(ack); return ack;
        }
        return null;
    }
    void notify(BluetoothGattCharacteristic c,byte[] value) { peer.add(() -> callback.onCharacteristicChanged(gatt,c,value)); }

    /** A user-started Pair that already completed its fresh Android bond, from the model read on. */
    void pair(String name,byte[] manufacturer) throws Exception { start(name,manufacturer,true); }
    /** A user-started Connect on the existing bond, from the model read on. */
    void connect(String name,byte[] manufacturer) throws Exception { start(name,manufacturer,false); }
    private void start(String name,byte[] manufacturer,boolean pair) throws Exception {
        modelName=name;
        set("gatt",gatt);
        monitor.address=ADDRESS; monitor.connectedName="Synthetic X7";
        set("pairingRequested",pair); set("x7FreshBondEstablished",pair); set("automaticConnection",false);
        set("negotiatedMtu",247); set("connectionManufacturer",manufacturer==null?null:manufacturer.clone());
        monitor.profile=org.openpressure.protocol.ProtocolProfile.LEGACY_WLP;
        report=new DeviceReport(pair?"Pair":"Connect","test",35,"Synthetic phone");
        report.service(Uuids.VENDOR_ALIAS.toString(),Collections.emptyList());
        set("report",report); set("reportAddress",ADDRESS); set("reportPrompted",false);
        call("identifyWlp",new Class<?>[0]);
        pump();
    }

    /** Runs scripted peer events and pending backup saves until the service waits for something else. */
    void pump() {
        for(int guard=0;guard<10000;guard++) {
            idle();
            if(peer.isEmpty()) {
                flushIo();
                // The plain SYS transaction starts after a short delay.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
                if(peer.isEmpty()) return;
            }
            peer.removeFirst().run();
        }
        throw new AssertionError("peer did not settle");
    }
    void flushIo() {
        try { ((ExecutorService)get("dumpIo")).submit(() -> {}).get(10,TimeUnit.SECONDS); }
        catch(Exception failure) { throw new AssertionError(failure); }
        idle();
    }
    void close() {
        monitor.disconnect();
        store.close();
    }

    /** Addressed writes (opcode 01, flags C0) that reached the GATT boundary. */
    List<byte[]> addressedWrites() {
        List<byte[]> result=new ArrayList<>();
        for(Write w:plaintextWrites()) if(w.characteristic==tx && w.bytes.length>8 && w.bytes[1]==1 && w.bytes[2]==(byte)0xc0) result.add(w.bytes);
        return result;
    }
    /** Writes as the monitor understood them; plain sessions send plaintext unchanged. */
    List<Write> plaintextWrites() { return writes; }
    /** WLP TX commands in order, as hex, for transcript comparison. */
    List<String> wlpTranscript() {
        List<String> result=new ArrayList<>();
        for(Write w:plaintextWrites()) if(w.characteristic==tx) result.add(HexFormat.of().formatHex(w.bytes));
        return result;
    }

    static byte[] plainAdvertisement() { return hex("06017801640b0064"); }
    byte[] memoryReply(byte[] request) {
        int at=((request[3]&255)<<8)|(request[4]&255),length=request[5]&255;
        byte[] reply=new byte[length+8]; reply[0]=(byte)reply.length; reply[1]=(byte)0x81;
        System.arraycopy(request,2,reply,2,4); System.arraycopy(memory,at,reply,6,length);
        bcc(reply); return reply;
    }
    static void bcc(byte[] frame) { frame[frame.length-1]=0; for(int i=0;i<frame.length-1;i++) frame[frame.length-1]^=frame[i]; }
    static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static BluetoothGattCharacteristic characteristic(BluetoothGattService service,UUID id,int properties,boolean cccd) {
        BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(id,properties,BluetoothGattCharacteristic.PERMISSION_WRITE|BluetoothGattCharacteristic.PERMISSION_READ);
        if(cccd) c.addDescriptor(new BluetoothGattDescriptor(Uuids.CCCD,BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(c); return c;
    }
    Object get(String name) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); return field.get(monitor); }
    void set(String name,Object value) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); field.set(monitor,value); }
    void call(String name,Class<?>[] types,Object... args) {
        try { Method method=MonitorService.class.getDeclaredMethod(name,types); method.setAccessible(true); method.invoke(monitor,args); }
        catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
