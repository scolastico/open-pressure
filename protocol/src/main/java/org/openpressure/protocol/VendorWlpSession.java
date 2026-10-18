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
    private static final int MAX_DATA=65536;
    private final List<VendorMemoryModel.Range> ranges=new ArrayList<>();
    private final int blockSize;
    private final byte[][] channels=new byte[4][];
    private final ByteArrayOutputStream data=new ByteArrayOutputStream();
    private byte[] identity;
    private int phase,rangeIndex,rangeReceived;
    private int total;
    private boolean issued,failed,endIssued,statusError,abandoned;

    public VendorWlpSession(VendorMemoryModel model) { this(model,16); }
    /** Full sweep of every bank in blocks of 1–16 bytes. */
    public VendorWlpSession(VendorMemoryModel model,int blockSize) { this(model,allBanks(model),sweepBlock(blockSize)); }
    /**
     * Reads the given ranges in order; each must lie wholly inside the settings region or one bank.
     * Settings ranges use {@code blockSize}; bank ranges use at most {@link #recordBlock}.
     */
    public VendorWlpSession(VendorMemoryModel model,List<VendorMemoryModel.Range> ranges,int blockSize) {
        requireModel(model);
        if(blockSize<1 || blockSize>maxBlock(model)) throw new IllegalArgumentException("Vendor read block exceeds the model's channel capacity");
        if(ranges==null || ranges.isEmpty()) throw new IllegalArgumentException("Model has no verified history ranges");
        this.model=model; this.blockSize=blockSize;
        for(VendorMemoryModel.Range range:ranges) add(range);
    }
    private static List<VendorMemoryModel.Range> allBanks(VendorMemoryModel model) { requireModel(model); return model.historyRanges(); }
    private static int sweepBlock(int blockSize) {
        if(blockSize<1 || blockSize>16) throw new IllegalArgumentException("Vendor read blocks must contain 1–16 bytes");
        return blockSize;
    }
    /** Four 16-byte legacy channels carry 64 reply bytes (8 framing + 56 data); one channel carries 16 data bytes. */
    public static int maxBlock(VendorMemoryModel model) { requireModel(model); return model.legacyUnlock?56:16; }
    /** The largest whole number of records that fits in one read. */
    public static int recordBlock(VendorMemoryModel model) { int max=maxBlock(model); return max-max%model.recordBytes; }
    private void add(VendorMemoryModel.Range range) {
        if(range==null || range.length<1 || total+range.length>MAX_DATA || !mapped(range)) throw new IllegalArgumentException("Vendor read range is outside the model map");
        ranges.add(range); total+=range.length;
    }
    private boolean mapped(VendorMemoryModel.Range range) {
        if(inside(range,model.settingsStart,model.settingsLength)) return true;
        for(VendorMemoryModel.Range bank:model.historyRanges()) if(inside(range,bank.start,bank.length)) return true;
        return false;
    }
    private static boolean inside(VendorMemoryModel.Range range,int start,int length) { return range.start>=start && range.start+range.length<=start+length; }
    public List<VendorMemoryModel.Range> ranges() { return Collections.unmodifiableList(new ArrayList<>(ranges)); }
    /** Every current range was received and END has not been issued, so more ranges may be appended. */
    public boolean rangeComplete() { return !failed && !abandoned && phase==1 && rangeIndex==ranges.size() && !endIssued; }
    /** Continues reading after the current ranges; refused once END was issued or a reply is outstanding. */
    public void appendRange(VendorMemoryModel.Range range) {
        if(!rangeComplete() || issued) throw new IllegalArgumentException("Vendor read session cannot take more ranges");
        for(byte[] channel:channels) if(channel!=null) throw new IllegalArgumentException("Vendor read session cannot take more ranges");
        add(range);
    }
    /** The range whose read is outstanding, or whose last read the device answered with an error status; null otherwise. */
    public VendorMemoryModel.Range currentRange() {
        return phase==1 && rangeIndex<ranges.size() && (!failed || statusError)?ranges.get(rangeIndex):null;
    }
    /** The last reply was a well-formed, checksummed read reply carrying a non-zero device status (the session failed). */
    public boolean readStatusError() { return failed && statusError; }
    /**
     * Stops reading after the current range's read went unanswered or returned a device error status. The current
     * range keeps only the bytes already validated, later ranges are dropped and the next command is END. Nothing
     * can be appended afterwards. Callers decide which ranges may end this way; any other failure stays final.
     */
    public void abandonRemainingRanges() {
        boolean outstanding=!failed && issued && phase==1 && rangeIndex<ranges.size();
        if(!outstanding && !readStatusError()) throw new IllegalArgumentException("Vendor read session has no range to abandon");
        VendorMemoryModel.Range current=ranges.get(rangeIndex);
        while(ranges.size()>rangeIndex) total-=ranges.remove(ranges.size()-1).length;
        if(rangeReceived>0) { ranges.add(VendorMemoryModel.range(current.start,rangeReceived,current.user)); total+=rangeReceived; }
        rangeIndex=ranges.size(); rangeReceived=0;
        Arrays.fill(channels,null); issued=false; failed=false; statusError=false; abandoned=true;
    }
    /** Byte offset of range {@code index} inside {@link #data()}. */
    public int rangeOffset(int index) {
        if(index<0 || index>=ranges.size()) throw new IllegalArgumentException("Unknown vendor read range");
        int offset=0;
        for(int i=0;i<index;i++) offset+=ranges.get(i).length;
        return offset;
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
        if(rangeIndex==ranges.size()) { endIssued=true; return new byte[]{8,15,0,0,0,0,0,7}; }
        VendorMemoryModel.Range range=ranges.get(rangeIndex);
        int block=range.user==0?blockSize:Math.min(blockSize,recordBlock(model));
        return Wlp.read(range.start+rangeReceived,Math.min(block,range.length-rangeReceived));
    }
    /** Legacy fragments are indexed by their RX characteristic, never by arrival order. */
    public boolean accept(int channel,byte[] fragment) {
        try {
            if(failed || complete() || !issued || fragment==null || fragment.length==0 || channel<0 || channel>=rxChannels(model).size()) invalid();
            statusError=false;
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
            if(phase==1 && rangeIndex<ranges.size()) statusError=deviceStatus(response,request);
            byte[] payload=Wlp.payload(response,request[1]&255);
            if(response[2]!=request[2] || response[3]!=request[3] || response[4]!=request[4] || response[5]!=request[5]) invalid();
            int wanted=request[5]&255;
            if(payload.length!=wanted) invalid();
            if(phase==0) {
                // Omron validates this identity checksum on the HEM-7600T only.
                if(model.id.equals("HEM-7600T") && (payload[14]&255)!=VendorMemoryModel.checksum(payload,14)) invalid();
                identity=payload.clone(); phase=1;
            } else if(rangeIndex<ranges.size()) {
                data.write(payload,0,payload.length); rangeReceived+=payload.length;
                if(rangeReceived==ranges.get(rangeIndex).length) { rangeReceived=0; rangeIndex++; }
            } else phase=3;
            Arrays.fill(channels,null); issued=false;
            return true;
        } catch(IllegalArgumentException error) {
            failed=true; Arrays.fill(channels,null); throw error;
        }
    }
    /** A framed, checksummed reply to this read whose status byte reports a device error. */
    private static boolean deviceStatus(byte[] response,byte[] request) {
        int n=response.length;
        if(n<8 || (response[0]&255)!=n || (response[1]&255)!=((request[1]&255)|0x80) || response[n-2]==0) return false;
        for(int i=2;i<5;i++) if(response[i]!=request[i]) return false;
        byte bcc=0; for(int i=0;i<n-1;i++) bcc^=response[i];
        return bcc==response[n-1];
    }
    public boolean complete() { return !failed && phase==3; }
    /** All mapped bytes have been validated; optional work may run before requesting END. */
    public boolean historyComplete() { return !failed && (phase==3 || phase==1 && rangeIndex==ranges.size()); }
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
