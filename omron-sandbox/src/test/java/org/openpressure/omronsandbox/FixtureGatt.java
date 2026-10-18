package org.openpressure.omronsandbox;

import android.bluetooth.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.FixtureCursor;
import org.openpressure.protocol.fixture.Outcome;
import org.openpressure.protocol.fixture.WlpDescribe;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A GATT peer that answers only from a fixture. Characteristic writes go to the {@link FixtureCursor}; matched
 * writes are acknowledged and followed by the scripted notifications. Everything else Omron does on the link
 * (service discovery, MTU, notification setup, Device Information reads) is answered generically and logged.
 * Callbacks are queued and delivered by the runner's pump, never re-entrantly from inside Omron's call.
 */
public final class FixtureGatt {
    private static final Map<String,UUID> DIS=Map.of(
        "model",sig(0x2a24),"serial",sig(0x2a25),"firmware",sig(0x2a26),"hardware",sig(0x2a27),
        "software",sig(0x2a28),"manufacturer",sig(0x2a29),"systemid",sig(0x2a23));
    private final Fixture fixture;
    private final FixtureCursor cursor;
    private final List<BluetoothGattService> services=new ArrayList<>();
    private final Map<UUID,BluetoothGattCharacteristic> characteristics=new HashMap<>();
    private final Deque<Runnable> pending=new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final List<String> transcript=Collections.synchronizedList(new ArrayList<>());
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private Outcome outcome;
    private boolean connected;

    public FixtureGatt(Fixture fixture,FixtureCursor cursor) {
        this.fixture=fixture; this.cursor=cursor;
        UUID vendorService=fixture.advertisedServices.isEmpty()?UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"):fixture.advertisedServices.get(0);
        BluetoothGattService vendor=new BluetoothGattService(vendorService,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattService time=new BluetoothGattService(sig(0x1805),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattService dis=new BluetoothGattService(sig(0x180a),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        // Every BLE peripheral exposes GAP; its device name mirrors the advertised name.
        BluetoothGattService gap=new BluetoothGattService(sig(0x1800),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        add(gap,sig(0x2a00),BluetoothGattCharacteristic.PROPERTY_READ,false);
        for(Map.Entry<String,List<UUID>> alias:fixture.aliases.entrySet()) {
            boolean notify=alias.getKey().equals("rx") || alias.getKey().equals("unlock");
            boolean write=!alias.getKey().equals("rx");
            int properties=(write?BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE:0)|(notify?BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE:0);
            for(UUID id:alias.getValue()) add(alias.getKey().equals("clock")?time:vendor,id,properties,notify);
        }
        for(Map.Entry<String,String> e:fixture.dis.entrySet()) if(DIS.containsKey(e.getKey())) add(dis,DIS.get(e.getKey()),BluetoothGattCharacteristic.PROPERTY_READ,false);
        services.add(gap);
        services.add(vendor);
        if(!time.getCharacteristics().isEmpty()) services.add(time);
        if(!dis.getCharacteristics().isEmpty()) services.add(dis);
    }

    BluetoothGatt connect(BluetoothGattCallback callback,BluetoothDevice device) {
        event("connectGatt");
        this.callback=callback;
        if(gatt==null) gatt=mockGatt();
        when(gatt.getDevice()).thenReturn(device);
        connected=true;
        pending.add(() -> callback.onConnectionStateChange(gatt,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_CONNECTED));
        return gatt;
    }
    /** Delivers one queued peer event; false when nothing is queued. */
    public boolean deliverNext() {
        Runnable next=pending.pollFirst();
        if(next==null) return false;
        next.run();
        return true;
    }
    public Outcome outcome() { return outcome; }
    public boolean connected() { return connected; }
    public List<String> transcript() { return transcript; }
    void event(String text) { transcript.add(text); }

    private BluetoothGatt mockGatt() {
        BluetoothGatt g=mock(BluetoothGatt.class);
        when(g.getServices()).thenReturn(services);
        for(BluetoothGattService s:services) when(g.getService(s.getUuid())).thenReturn(s);
        when(g.connect()).thenAnswer(i -> { event("connect"); return true; });
        when(g.discoverServices()).thenAnswer(i -> {
            event("discoverServices");
            pending.add(() -> callback.onServicesDiscovered(g,BluetoothGatt.GATT_SUCCESS));
            return true;
        });
        when(g.requestMtu(anyInt())).thenAnswer(i -> {
            int mtu=i.getArgument(0);
            event("requestMtu "+mtu+" -> 23");
            pending.add(() -> callback.onMtuChanged(g,23,BluetoothGatt.GATT_SUCCESS));
            return true;
        });
        when(g.requestConnectionPriority(anyInt())).thenReturn(true);
        when(g.setCharacteristicNotification(any(),anyBoolean())).thenAnswer(i -> {
            BluetoothGattCharacteristic c=i.getArgument(0);
            event("setCharacteristicNotification "+name(c.getUuid())+" "+i.getArgument(1));
            return true;
        });
        when(g.writeDescriptor(any(BluetoothGattDescriptor.class))).thenAnswer(i -> descriptor(i.getArgument(0),((BluetoothGattDescriptor)i.getArgument(0)).getValue())?Boolean.TRUE:Boolean.FALSE);
        when(g.writeDescriptor(any(BluetoothGattDescriptor.class),any(byte[].class))).thenAnswer(i -> descriptor(i.getArgument(0),i.getArgument(1))?BluetoothStatusCodes.SUCCESS:BluetoothStatusCodes.ERROR_UNKNOWN);
        when(g.readCharacteristic(any())).thenAnswer(i -> {
            BluetoothGattCharacteristic c=i.getArgument(0);
            byte[] value=new byte[0];
            if(c.getUuid().equals(sig(0x2a00)) && fixture.advertisedName!=null) value=fixture.advertisedName.getBytes(StandardCharsets.UTF_8);
            for(Map.Entry<String,UUID> e:DIS.entrySet()) if(e.getValue().equals(c.getUuid()) && fixture.dis.containsKey(e.getKey())) {
                String text=fixture.dis.get(e.getKey());
                // System ID is binary; the fixture writes it as hex.
                value=e.getKey().equals("systemid")?HexFormat.of().parseHex(text):text.getBytes(StandardCharsets.UTF_8);
            }
            byte[] answer=value;
            event("read "+name(c.getUuid())+" -> "+hex(answer));
            pending.add(() -> { c.setValue(answer); callback.onCharacteristicRead(g,c,answer,BluetoothGatt.GATT_SUCCESS); });
            return true;
        });
        when(g.writeCharacteristic(any(BluetoothGattCharacteristic.class))).thenAnswer(i -> {
            BluetoothGattCharacteristic c=i.getArgument(0);
            return write(c,c.getValue());
        });
        when(g.writeCharacteristic(any(BluetoothGattCharacteristic.class),any(byte[].class),anyInt())).thenAnswer(i ->
            write(i.getArgument(0),i.getArgument(1))?BluetoothStatusCodes.SUCCESS:BluetoothStatusCodes.ERROR_UNKNOWN);
        doAnswer(i -> {
            event("disconnect");
            if(connected) { connected=false; pending.add(() -> callback.onConnectionStateChange(g,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_DISCONNECTED)); }
            return null;
        }).when(g).disconnect();
        doAnswer(i -> { event("close"); connected=false; return null; }).when(g).close();
        return g;
    }
    private boolean descriptor(BluetoothGattDescriptor d,byte[] value) {
        event("writeDescriptor "+name(d.getCharacteristic().getUuid())+" "+hex(value));
        pending.add(() -> callback.onDescriptorWrite(gatt,d,BluetoothGatt.GATT_SUCCESS));
        return true;
    }
    private boolean write(BluetoothGattCharacteristic c,byte[] value) {
        byte[] bytes=value==null?new byte[0]:value.clone();
        event("write "+name(c.getUuid())+" "+hex(bytes)+"  "+WlpDescribe.describe(bytes));
        if(outcome!=null) return true;
        Outcome diverged=cursor.onWrite(c.getUuid(),bytes);
        if(diverged!=null) { outcome=diverged; return true; }
        pending.add(() -> callback.onCharacteristicWrite(gatt,c,BluetoothGatt.GATT_SUCCESS));
        for(FixtureCursor.Notification n:cursor.pendingNotifications()) {
            BluetoothGattCharacteristic target=characteristics.get(n.characteristic);
            pending.add(() -> {
                event("notify "+name(n.characteristic)+" "+hex(n.value));
                target.setValue(n.value);
                callback.onCharacteristicChanged(gatt,target,n.value);
            });
        }
        return true;
    }
    private void add(BluetoothGattService service,UUID id,int properties,boolean cccd) {
        BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(id,properties,BluetoothGattCharacteristic.PERMISSION_READ|BluetoothGattCharacteristic.PERMISSION_WRITE);
        if(cccd) c.addDescriptor(new BluetoothGattDescriptor(sig(0x2902),BluetoothGattDescriptor.PERMISSION_READ|BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(c); characteristics.put(id,c);
    }
    private String name(UUID id) {
        String alias=fixture.aliasOf(id);
        if(alias==null) return id.toString();
        List<UUID> ids=fixture.uuids(alias);
        return ids.size()==1?alias:alias+ids.indexOf(id);
    }
    static UUID sig(int value) { return UUID.fromString(String.format(Locale.ROOT,"0000%04x-0000-1000-8000-00805f9b34fb",value)); }
    static String hex(byte[] bytes) { return bytes==null?"null":HexFormat.of().formatHex(bytes); }
}
