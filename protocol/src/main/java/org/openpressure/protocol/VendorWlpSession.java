package org.openpressure.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Read-only, model-bound vendor memory session; each reply must match the outstanding command. */
public final class VendorWlpSession {
    private static final UUID LEGACY_SERVICE=UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b");
    private static final UUID MODERN_SERVICE=UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb");
    private static final UUID UNLOCK=UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b");
    private static final List<UUID> TX=Collections.unmodifiableList(Arrays.asList(
        UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b"),
        UUID.fromString("e0b8a060-aee7-11e1-92f4-0002a5d5c51b"),
        UUID.fromString("0ae12b00-aee8-11e1-a192-0002a5d5c51b"),
        UUID.fromString("10e1ba60-aee8-11e1-89e5-0002a5d5c51b")));
    private static final List<UUID> RX=Collections.unmodifiableList(Arrays.asList(
        UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b"),
        UUID.fromString("4d0bf320-aee8-11e1-a0d9-0002a5d5c51b"),
        UUID.fromString("5128ce60-aee8-11e1-b84b-0002a5d5c51b"),
        UUID.fromString("560f1420-aee8-11e1-8184-0002a5d5c51b")));
    private final VendorMemoryModel model;
    private final List<VendorMemoryModel.Range> ranges;
    private final int blockSize;
    private final byte[][] channels=new byte[4][];
    private final ByteArrayOutputStream data=new ByteArrayOutputStream();
    private byte[] identity;
    private int phase,rangeIndex,rangeReceived;
    private boolean issued,failed;

    public VendorWlpSession(VendorMemoryModel model) { this(model,16); }
    public VendorWlpSession(VendorMemoryModel model,int blockSize) {
        requireModel(model);
        if(blockSize<1 || blockSize>16) throw new IllegalArgumentException("Vendor read blocks must contain 1–16 bytes");
        this.model=model; this.blockSize=blockSize; ranges=model.historyRanges();
        if(ranges.isEmpty()) throw new IllegalArgumentException("Model has no verified history ranges");
    }
    public static UUID service(VendorMemoryModel model) { requireModel(model); return model.legacyUnlock?LEGACY_SERVICE:MODERN_SERVICE; }
    public static List<UUID> txChannels(VendorMemoryModel model) { requireModel(model); return model.legacyUnlock?TX:TX.subList(0,1); }
    public static List<UUID> rxChannels(VendorMemoryModel model) { requireModel(model); return model.legacyUnlock?RX:RX.subList(0,1); }
    public static UUID unlockCharacteristic() { return UNLOCK; }
    static void requireModel(VendorMemoryModel model) {
        if(model==null || VendorMemoryModel.identify(model.id)!=model) throw new IllegalArgumentException("Unknown vendor memory model");
    }
    public byte[] command() {
        if(failed || complete()) throw new IllegalArgumentException("Vendor read session is no longer active");
        issued=true;
        if(phase==0) return new byte[]{8,0,0,0,0,16,0,24};
        if(phase==2) return new byte[]{8,15,0,0,0,0,0,7};
        VendorMemoryModel.Range range=ranges.get(rangeIndex);
        return Wlp.read(range.start+rangeReceived,Math.min(blockSize,range.length-rangeReceived));
    }
    /** Legacy fragments are indexed by their RX characteristic, never by arrival order. */
    public boolean accept(int channel,byte[] fragment) {
        try {
            if(failed || complete() || !issued || fragment==null || fragment.length==0 || channel<0 || channel>=rxChannels(model).size()) invalid();
            byte[] response;
            if(!model.legacyUnlock) {
                if(fragment.length<8 || fragment.length>64 || (fragment[0]&255)!=fragment.length) invalid();
                response=fragment.clone();
            } else {
                if(fragment.length>16 || channels[channel]!=null) invalid();
                channels[channel]=fragment.clone();
                if(channels[0]==null) return false;
                int declared=channels[0][0]&255;
                if(declared<8 || declared>64) invalid();
                int count=(declared+15)/16;
                for(int i=count;i<4;i++) if(channels[i]!=null) invalid();
                for(int i=0;i<count;i++) {
                    if(channels[i]==null) return false;
                    int needed=Math.min(16,declared-i*16);
                    if(channels[i].length<needed || (i<count-1 && channels[i].length!=16)) invalid();
                }
                response=new byte[declared];
                for(int i=0;i<count;i++) System.arraycopy(channels[i],0,response,i*16,Math.min(16,declared-i*16));
            }
            byte[] request=command();
            byte[] payload=Wlp.payload(response,request[1]&255);
            if(response[2]!=request[2] || response[3]!=request[3] || response[4]!=request[4] || response[5]!=request[5]) invalid();
            int wanted=request[5]&255;
            if(payload.length!=wanted) invalid();
            if(phase==0) { identity=payload.clone(); phase=1; }
            else if(phase==1) {
                data.write(payload,0,payload.length); rangeReceived+=payload.length;
                if(rangeReceived==ranges.get(rangeIndex).length) {
                    rangeReceived=0; rangeIndex++;
                    if(rangeIndex==ranges.size()) phase=2;
                }
            } else phase=3;
            Arrays.fill(channels,null); issued=false;
            return true;
        } catch(IllegalArgumentException error) {
            failed=true; Arrays.fill(channels,null); throw error;
        }
    }
    public boolean complete() { return !failed && phase==3; }
    public int received() { return data.size(); }
    public byte[] data() { return data.toByteArray(); }
    public byte[] identity() { return identity==null?null:identity.clone(); }
    /** ATT values for one complete frame. Single-channel devices require an adequate negotiated MTU. */
    public static List<byte[]> chunks(byte[] frame,int channelCount) {
        if(frame==null || frame.length==0 || frame.length>64 || (channelCount!=1 && channelCount!=4)) invalid();
        List<byte[]> result=new ArrayList<>();
        int width=channelCount==1?frame.length:16;
        for(int i=0;i<frame.length;i+=width) result.add(Arrays.copyOfRange(frame,i,Math.min(i+width,frame.length)));
        return Collections.unmodifiableList(result);
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid or unexpected vendor WLP response"); }
}
