package org.openpressure.app.ble;

import android.bluetooth.*;
import android.os.Looper;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.data.PairingKeys;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.protocol.DeviceReport;
import org.openpressure.protocol.OperationQueue;
import org.openpressure.protocol.fixture.FakeAndroidKeyStore;
import org.openpressure.protocol.fixture.SecurePeer;
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
 * Every characteristic write is recorded as it reached the GATT mock. On the encrypted route it also plays the
 * cuff's side of the application handshake ({@link SecurePeer}: fresh pairing or a saved key), answers the
 * authorization poll and exchanges authenticated frames, so {@link #plaintextWrites()} shows what the cuff decrypted.
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
    final List<Write> writes=new ArrayList<>(),plain=new ArrayList<>();
    /** The cuff's application key: derived during a fresh pairing, or preset for a saved-key Connect. */
    byte[] ltk;
    boolean established;
    /** The cuff side of the application encryption for the current session, created by its first handshake frame. */
    private SecurePeer stp;
    final Deque<Runnable> peer=new ArrayDeque<>();
    final byte[] memory=new byte[0x10000];
    WriteReply addressedReply=WriteReply.ACK;
    String modelName="HEM-7380T1-EOSL";
    /** The Bluetooth name the session was started with; a catalog-style name carries a model ID. */
    String advertisedName="Synthetic X7";
    /** Status codes for the next descriptor writes, in order; success once empty. */
    final Deque<Integer> descriptorResults=new ArrayDeque<>();
    final List<BluetoothGattDescriptor> descriptorWrites=new ArrayList<>();
    /** Status codes for the next Device Information model reads, in order; success once empty. */
    final Deque<Integer> modelReadResults=new ArrayDeque<>();
    int modelReads;
    /** Completion status of each characteristic write as sent on the air; a failed write gets no reply. */
    java.util.function.ToIntFunction<byte[]> writeStatus=value -> BluetoothGatt.GATT_SUCCESS;
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
            Integer result=c==model?modelReadResults.poll():null;
            if(c==model) modelReads++;
            int status=result==null?BluetoothGatt.GATT_SUCCESS:result;
            peer.add(() -> callback.onCharacteristicRead(gatt,c,status==BluetoothGatt.GATT_SUCCESS?value:new byte[0],status));
            return true;
        });
        when(gatt.writeDescriptor(any(BluetoothGattDescriptor.class),any(byte[].class))).thenAnswer(invocation -> {
            BluetoothGattDescriptor d=invocation.getArgument(0);
            descriptorWrites.add(d);
            Integer result=descriptorResults.poll();
            int status=result==null?BluetoothGatt.GATT_SUCCESS:result;
            peer.add(() -> callback.onDescriptorWrite(gatt,d,status));
            return BluetoothStatusCodes.SUCCESS;
        });
        when(gatt.writeCharacteristic(any(BluetoothGattCharacteristic.class),any(byte[].class),anyInt())).thenAnswer(invocation -> {
            BluetoothGattCharacteristic c=invocation.getArgument(0);
            byte[] value=((byte[])invocation.getArgument(1)).clone();
            writes.add(new Write(c,value));
            int status=writeStatus.applyAsInt(value);
            peer.add(() -> callback.onCharacteristicWrite(gatt,c,status));
            if(status==BluetoothGatt.GATT_SUCCESS) answer(c,value);
            return BluetoothStatusCodes.SUCCESS;
        });
        System.arraycopy(SETTINGS,0,memory,0x10,SETTINGS.length);
    }

    /** The peer's reply to one write, queued after the write's own completion callback. */
    void answer(BluetoothGattCharacteristic c,byte[] value) {
        if(c==system && value.length>1 && value[0]==0x70) { plain.add(new Write(c,value)); handshake(value); return; }
        if(established && (c==system || c==tx) && value.length>0 && (value[0]&255)==0xc0) {
            byte[] decrypted=decrypt(value);
            plain.add(new Write(c,decrypted));
            if(c==system && decrypted.length==20 && decrypted[0]==0x20 && decrypted[1]==1) {
                byte[] state=new byte[20]; state[0]=(byte)0xa0; state[1]=1; state[3]=1;
                notify(system,encrypt(state));
            } else if(c==tx) {
                byte[] reply=wlpReply(decrypted);
                if(reply!=null) notify(rx,encrypt(reply));
            }
            return;
        }
        plain.add(new Write(c,value));
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
    /** Android's bond state changes to {@code state}; the broadcast reaches the service's bond receiver. */
    void bond(int state,int previous) throws Exception {
        when(device.getBondState()).thenReturn(state);
        android.content.Intent intent=new android.content.Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE,device);
        intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE,state);
        intent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,previous);
        ((android.content.BroadcastReceiver)get("bondReceiver")).onReceive(monitor,intent);
    }

    /** A user-started Pair that already completed its fresh Android bond, from the model read on. */
    void pair(String name,byte[] manufacturer) throws Exception { start(name,manufacturer,true); }
    /** A user-started Connect on the existing bond, from the model read on. */
    void connect(String name,byte[] manufacturer) throws Exception { start(name,manufacturer,false); }
    /** An automatic import (or Fetch now) on the existing bond: read-only, never Pair. */
    void automatic(String name,byte[] manufacturer) throws Exception { prepare(name,manufacturer,false); set("automaticConnection",true); call("identifyWlp",new Class<?>[0]); pump(); }
    private void start(String name,byte[] manufacturer,boolean pair) throws Exception { prepare(name,manufacturer,pair); call("identifyWlp",new Class<?>[0]); pump(); }
    void prepare(String name,byte[] manufacturer,boolean pair) throws Exception {
        modelName=name; established=false; stp=null;
        set("gatt",gatt);
        monitor.address=ADDRESS; monitor.connectedName=advertisedName;
        set("pairingRequested",pair); set("x7FreshBondEstablished",pair); set("automaticConnection",false);
        set("negotiatedMtu",247); set("connectionManufacturer",manufacturer==null?null:manufacturer.clone());
        monitor.profile=org.openpressure.protocol.ProtocolProfile.LEGACY_WLP;
        report=new DeviceReport(pair?"Pair":"Connect","test",35,"Synthetic phone");
        report.service(Uuids.VENDOR_ALIAS.toString(),Collections.emptyList());
        set("report",report); set("reportAddress",ADDRESS); set("reportPrompted",false);
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
    /** Writes as the monitor understood them: decrypted on the encrypted route, unchanged otherwise. */
    List<Write> plaintextWrites() { return plain; }

    // ---- encrypted route (cuff side of SecureSession, shared with the fixture replays) ----
    private void handshake(byte[] request) {
        // A 70 01 starts a fresh application pairing; otherwise the cuff authenticates with the key it already has.
        if(stp==null) stp=new SecurePeer(request[1]==1?null:ltk);
        byte[] reply;
        try { reply=stp.handshake(request); }
        catch(IllegalArgumentException refused) { throw new AssertionError("cuff refused "+HexFormat.of().formatHex(request)+": "+refused.getMessage(),refused); }
        if(stp.established()) { established=true; ltk=stp.ltk(); }
        notify(system,reply);
    }
    private byte[] decrypt(byte[] packet) { return stp.decrypt(packet); }
    byte[] encrypt(byte[] value) { return stp.encrypt(value); }
    /** System commands the cuff saw, opcode bytes as hex (first two bytes), in order. */
    List<String> systemOpcodes() {
        List<String> result=new ArrayList<>();
        for(Write w:plain) if(w.characteristic==system) result.add(HexFormat.of().formatHex(Arrays.copyOf(w.bytes,2)));
        return result;
    }
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
