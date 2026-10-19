package org.openpressure.omronsandbox;

import android.bluetooth.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.FixtureCursor;
import org.openpressure.protocol.fixture.Outcome;
import org.openpressure.protocol.fixture.SecurePeer;
import org.openpressure.protocol.fixture.WlpDescribe;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A GATT peer that answers only from a fixture. Characteristic writes go to the {@link FixtureCursor}; matched
 * writes are acknowledged and followed by the scripted notifications. Everything else Omron does on the link
 * (service discovery, MTU up to the fixture's {@code mtu}, notification setup, Device Information reads) is answered
 * generically and logged.
 * Callbacks are queued and delivered by the runner's pump, never re-entrantly from inside Omron's call.
 *
 * <p>With {@code -Domron.sandbox.explore=true} the peer keeps answering after a divergence, with synthetic
 * acknowledgments: WLP reads from the fixture memory, writes and END echoed, key frames acknowledged and X7
 * system-transaction nonces echoed. The outcome stays the first divergence; the transcript then shows everything
 * Omron would have sent, which saves one run per fixture step when tuning. Exploration replies are synthetic and
 * prove nothing.
 */
public final class FixtureGatt {
    private static final Map<String,UUID> DIS=Map.of(
        "model",sig(0x2a24),"serial",sig(0x2a25),"firmware",sig(0x2a26),"hardware",sig(0x2a27),
        "software",sig(0x2a28),"manufacturer",sig(0x2a29),"systemid",sig(0x2a23));
    static final boolean EXPLORE=Boolean.getBoolean("omron.sandbox.explore");
    private final Fixture fixture;
    private final FixtureCursor cursor;
    private final List<BluetoothGattService> services=new ArrayList<>();
    private final Map<UUID,BluetoothGattCharacteristic> characteristics=new HashMap<>();
    private final Deque<Runnable> pending=new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final List<String> transcript=Collections.synchronizedList(new ArrayList<>());
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private volatile Outcome outcome;
    private volatile boolean connected;

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
            // Legacy unlock and the X7 system channel (sys) both carry the phone's commands and the monitor's replies.
            boolean notify=alias.getKey().equals("rx") || alias.getKey().equals("unlock") || alias.getKey().equals("sys");
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
            // The peer supports up to the fixture's mtu (23 by default); the link uses the smaller of the two.
            int mtu=i.getArgument(0),negotiated=Math.max(23,Math.min(mtu,fixture.mtu));
            event("requestMtu "+mtu+" -> "+negotiated);
            pending.add(() -> callback.onMtuChanged(g,negotiated,BluetoothGatt.GATT_SUCCESS));
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
        if(outcome!=null) return explore(c,bytes,null);
        Outcome diverged=cursor.onWrite(c.getUuid(),bytes);
        if(diverged!=null) {
            outcome=diverged;
            if(EXPLORE) event("-- exploring past the divergence with synthetic replies --");
            // The cursor already reassembled (and, when encrypted, decrypted) the diverging frame; later frames are
            // reassembled and decrypted here.
            byte[] plain=cursor.lastPlaintext();
            return explore(c,bytes,plain!=null?plain:diverged.actual!=null && diverged.kind==Outcome.Kind.DIVERGED && diverged.actual.length>0 && diverged.actual[0]!=0x70 && (diverged.actual[0]&255)!=0xc0?diverged.actual:null);
        }
        byte[] plain=cursor.lastPlaintext();
        if(plain!=null) event("  decrypted "+hex(plain)+"  "+WlpDescribe.describe(plain));
        pending.add(() -> callback.onCharacteristicWrite(gatt,c,BluetoothGatt.GATT_SUCCESS));
        for(FixtureCursor.Notification n:cursor.pendingNotifications()) {
            BluetoothGattCharacteristic target=characteristics.get(n.characteristic);
            pending.add(() -> {
                event("notify "+name(n.characteristic)+" "+hex(n.value)+(n.plain==null?"":"  (plaintext "+hex(n.plain)+"  "+WlpDescribe.describe(n.plain)+")"));
                target.setValue(n.value);
                callback.onCharacteristicChanged(gatt,target,n.value);
            });
        }
        return true;
    }
    /** After a divergence in exploration mode: acknowledge and answer from the fixture memory; otherwise stay silent. */
    private final Map<Integer,byte[]> exploreParts=new HashMap<>();
    private boolean explore(BluetoothGattCharacteristic c,byte[] part,byte[] assembled) {
        if(!EXPLORE) return true;
        pending.add(() -> callback.onCharacteristicWrite(gatt,c,BluetoothGatt.GATT_SUCCESS));
        String alias=fixture.aliasOf(c.getUuid());
        if(alias==null) return true;
        List<UUID> channels=fixture.uuids(alias);
        byte[] bytes=assembled;
        if(bytes==null && channels.size()>1) {
            exploreParts.put(channels.indexOf(c.getUuid()),part.clone());
            byte[] first=exploreParts.get(0);
            if(first==null || first.length==0) return true;
            int declared=first[0]&255,count=Math.max(1,(declared+15)/16);
            bytes=new byte[declared];
            for(int i=0;i<count;i++) {
                byte[] piece=exploreParts.get(i);
                if(piece==null) return true;
                System.arraycopy(piece,0,bytes,i*16,Math.min(piece.length,Math.min(16,declared-i*16)));
            }
            exploreParts.clear();
        } else if(bytes==null) bytes=part;
        else exploreParts.clear();
        byte[] reply=null;
        String replyAlias=alias;
        SecurePeer secure=cursor.secure();
        if(secure!=null && alias.equals(fixture.secureAlias) && bytes.length>1 && bytes[0]==0x70) {
            // Application handshake: the live cuff peer answers if the frame fits its state.
            try { reply=secure.handshake(bytes); }
            catch(IllegalArgumentException refused) { event("  (exploration: cuff peer refused the handshake frame: "+refused.getMessage()+")"); return true; }
            sendExplored(alias,reply);
            return true;
        }
        if(secure!=null && secure.established() && assembled==null) {
            if(bytes.length<13 || (bytes[0]&255)!=0xc0) { event("  (exploration: plaintext frame after the encrypted session was established)"); return true; }
            try { bytes=secure.decrypt(bytes); }
            catch(IllegalArgumentException refused) { event("  (exploration: cuff peer refused the frame: "+refused.getMessage()+")"); return true; }
            event("  decrypted "+hex(bytes)+"  "+WlpDescribe.describe(bytes));
        }
        if(alias.equals("unlock") && bytes.length>0) reply=new byte[]{(byte)(bytes[0]|0x80),0};
        else if(alias.equals("sys") && bytes.length==20 && bytes[0]==0x20 && bytes[1]==1) {
            // X7 authorization poll: report COMPLETE.
            reply=new byte[20]; reply[0]=(byte)0xa0; reply[1]=1; reply[3]=1;
        }
        else if(alias.equals("sys") && bytes.length==20 && bytes[0]==0x11) {
            // X7 system transaction: echo the nonce with a zero status.
            reply=new byte[20]; reply[0]=(byte)0x91; System.arraycopy(bytes,1,reply,2,4);
        }
        else if(alias.equals("tx") && bytes.length>=8) {
            int declared=bytes[0]&255,op=bytes[1]&255;
            replyAlias="rx";
            if(op==0x01 && (bytes[2]&0xc0)==0 && declared==8) {
                int address=((bytes[2]&0x3f)<<16)|((bytes[3]&255)<<8)|(bytes[4]&255),length=bytes[5]&255;
                byte[] data=fixture.memory(address,length);
                reply=new byte[length+8];
                reply[0]=(byte)reply.length; reply[1]=(byte)0x81;
                System.arraycopy(bytes,2,reply,2,4);
                System.arraycopy(data,0,reply,6,length);
            } else {
                // Write and END: full echo with the response opcode and a zero status.
                reply=Arrays.copyOf(bytes,Math.max(8,Math.min(declared,bytes.length)));
                reply[0]=(byte)reply.length; reply[1]=(byte)(op|0x80);
                reply[reply.length-2]=0;
            }
            reply[reply.length-1]=0;
            for(int i=0;i<reply.length-1;i++) reply[reply.length-1]^=reply[i];
        }
        if(reply==null || !fixture.aliases.containsKey(replyAlias)) return true;
        sendExplored(replyAlias,secure!=null && secure.established()?reply:null,secure!=null && secure.established()?secure.encrypt(reply):reply);
        return true;
    }
    private void sendExplored(String alias,byte[] value) { sendExplored(alias,null,value); }
    /** Queues a synthetic exploration notification, split over the alias's channels; {@code plain} only labels it. */
    private void sendExplored(String replyAlias,byte[] plain,byte[] value) {
        List<UUID> targets=fixture.uuids(replyAlias);
        String label=plain==null?"":"  (plaintext "+hex(plain)+"  "+WlpDescribe.describe(plain)+")";
        for(int i=0;i*16<value.length && i<targets.size();i++) {
            BluetoothGattCharacteristic target=characteristics.get(targets.get(i));
            byte[] piece=targets.size()==1?value:Arrays.copyOfRange(value,i*16,Math.min(value.length,(i+1)*16));
            pending.add(() -> { event("notify "+name(target.getUuid())+" "+hex(piece)+label+"  (synthetic exploration reply)"); target.setValue(piece); callback.onCharacteristicChanged(gatt,target,piece); });
        }
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
