package org.openpressure.protocol;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A single-use plan for the one clock-block write OpenPressure may send to a vendor-memory or
 * X7 monitor (docs/omron-sandbox.md). The frame is built only from the fresh settings image
 * read in the same session and the written clock (target + 1 s): every other block byte is
 * copied unchanged. Nothing here sends a byte; the GATT boundary permits each chunk through
 * {@link #allowsChunk} only after {@link #authorize()}, in order, on its own TX channel.
 * The caller reports each chunk with {@link #chunkSent} when it is handed to GATT, before
 * routing the acknowledgment to {@link #accept}. Any unexpected acknowledgment revokes the permit.
 */
public final class VendorClockWrite {
    public enum State { PREVIEW, AUTHORIZED, WRITING, ACKNOWLEDGED, REJECTED, CANCELLED }

    /** Largest |monitor clock - target| in seconds that is left alone. */
    public static final long THRESHOLD_SECONDS=60;

    private final String identity;
    private final boolean override;
    private final int channels;
    private final LocalDateTime target,written,monitorClock;
    private final long driftSeconds;
    private final byte[] frame;
    private final List<byte[]> chunks;
    private final byte[][] received=new byte[4][];
    private State state=State.PREVIEW;
    private int nextChunk;

    public VendorClockWrite(String identity,byte[] image,LocalDateTime target,boolean override,int channels) {
        String resolved=WriteAspectIndex.resolve(identity);
        ClockBlock block=ClockBlock.forIdentity(resolved);
        if(block==null) throw new IllegalArgumentException("No verified clock block for this monitor");
        VendorMemoryModel model=VendorMemoryModel.identify(resolved);
        // Four-channel legacy maps or the single X7 TX channel, never a mix.
        int expectedChannels=model==null?1:VendorWlpSession.txChannels(model).size();
        if(channels!=expectedChannels) throw new IllegalArgumentException("Clock write channel count does not match the monitor");
        if(block.swapped && (model==null || model.recordBytes!=14))
            throw new IllegalArgumentException("Swapped clock layout requires its 14-byte model map");
        if(image==null || image.length!=block.imageLength())
            throw new IllegalArgumentException("Clock write requires exactly "+block.imageLength()+" settings bytes");
        if(target==null) throw new IllegalArgumentException("Clock write requires a target time");
        LocalDateTime clock;
        try { clock=target.plusSeconds(1); }
        catch(DateTimeException invalid) { throw new IllegalArgumentException("Invalid clock target",invalid); }
        if(clock.getYear()<2024 || clock.getYear()>2050) throw new IllegalArgumentException("Clock time must be in 2024–2050");
        byte[] logical=block.swapped?model.logical(image):image.clone();
        if(!block.checksumValid(logical) && !block.erased(logical)) throw new IllegalArgumentException("monitor settings unreadable");
        this.identity=resolved; this.override=override; this.channels=channels;
        this.target=target; this.written=clock;
        monitorClock=block.clock(logical);
        driftSeconds=monitorClock==null?Long.MAX_VALUE:Duration.between(target,monitorClock).getSeconds();
        byte[] data=block.updated(logical,clock);
        if(block.swapped) for(int i=0;i+1<data.length;i+=2) { byte b=data[i]; data[i]=data[i+1]; data[i+1]=b; }
        frame=new byte[8+block.length];
        frame[0]=(byte)frame.length; frame[1]=1; frame[2]=(byte)0xc0;
        frame[3]=(byte)(block.writeAddress>>8); frame[4]=(byte)block.writeAddress; frame[5]=(byte)block.length;
        System.arraycopy(data,0,frame,6,data.length);
        int bcc=0;
        for(int i=0;i<frame.length-1;i++) bcc^=frame[i]&255;
        frame[frame.length-1]=(byte)bcc;
        chunks=VendorWlpSession.chunks(frame,channels);
    }

    public String identity() { return identity; }
    public boolean override() { return override; }
    public LocalDateTime target() { return target; }
    /** The clock bytes in the frame: target + 1 s. */
    public LocalDateTime written() { return written; }
    /** The monitor's stored clock, or null when unset or undecodable. */
    public LocalDateTime monitorClock() { return monitorClock; }
    /** Monitor clock minus target; {@link Long#MAX_VALUE} without a monitor clock. */
    public long driftSeconds() { return driftSeconds; }
    /** The monitor already keeps the target within the threshold; no write may be authorized. */
    public boolean writesNothing() { return monitorClock!=null && Math.abs(driftSeconds)<=THRESHOLD_SECONDS; }
    public synchronized State state() { return state; }
    public synchronized boolean complete() { return state==State.ACKNOWLEDGED; }

    /** Once, from the preview, and only when the write changes the clock. */
    public synchronized void authorize() {
        if(state!=State.PREVIEW || writesNothing()) throw new IllegalArgumentException("Clock write cannot be authorized");
        state=State.AUTHORIZED;
    }

    /** The full write frame in wire order. */
    public synchronized byte[] frame() {
        requireUsable();
        return frame.clone();
    }

    /** The ATT values of {@link #frame()}: one for X7, 16-byte pieces by TX channel for legacy monitors. */
    public synchronized List<byte[]> chunks() {
        requireUsable();
        List<byte[]> copy=new ArrayList<>();
        for(byte[] chunk:chunks) copy.add(chunk.clone());
        return Collections.unmodifiableList(copy);
    }

    /** Permits exactly the next unsent chunk on its own channel index while authorized. */
    public synchronized boolean allowsChunk(int channelIndex,byte[] payload) {
        return (state==State.AUTHORIZED || state==State.WRITING) && channelIndex==nextChunk
            && nextChunk<chunks.size() && Arrays.equals(chunks.get(nextChunk),payload);
    }

    /** Records that the permitted chunk was handed to GATT; anything out of order revokes the plan. */
    public synchronized void chunkSent(int channelIndex) {
        if((state!=State.AUTHORIZED && state!=State.WRITING) || channelIndex!=nextChunk || nextChunk>=chunks.size())
            throw reject();
        nextChunk++;
        state=State.WRITING;
    }

    /**
     * Legacy fragments are indexed by RX channel and joined in channel order; X7 replies arrive as
     * one notification. Accepts the 8-byte compact acknowledgment or the full echo of the frame.
     */
    public synchronized boolean accept(int channel,byte[] fragment) {
        if(state!=State.WRITING || nextChunk!=chunks.size() || fragment==null || fragment.length==0) throw reject();
        byte[] response;
        if(channels==1) {
            if(channel!=0 || fragment.length!=(fragment[0]&255)) throw reject();
            response=fragment.clone();
        } else {
            int maxCount=(frame.length+15)/16;
            if(channel<0 || channel>=maxCount || fragment.length>16 || received[channel]!=null) throw reject();
            received[channel]=fragment.clone();
            if(received[0]==null) return false;
            int declared=received[0][0]&255;
            if(declared!=8 && declared!=frame.length) throw reject();
            int count=(declared+15)/16;
            for(int i=count;i<received.length;i++) if(received[i]!=null) throw reject();
            for(int i=0;i<count;i++) {
                if(received[i]==null) return false;
                int needed=Math.min(16,declared-i*16);
                if(received[i].length!=needed) throw reject();
            }
            response=new byte[declared];
            for(int i=0;i<count;i++) System.arraycopy(received[i],0,response,i*16,received[i].length);
        }
        int declared=response.length;
        if(declared!=8 && declared!=frame.length) throw reject();
        int bcc=0;
        for(byte b:response) bcc^=b&255;
        if((response[1]&255)!=0x81 || response[2]!=frame[2] || response[3]!=frame[3] || response[4]!=frame[4]
                || response[5]!=frame[5] || response[declared-2]!=0 || bcc!=0) throw reject();
        if(declared!=8) for(int i=6;i<declared-2;i++) if(response[i]!=frame[i]) throw reject();
        Arrays.fill(received,null);
        state=State.ACKNOWLEDGED;
        return true;
    }

    /** Revokes any permit; an acknowledged or rejected outcome stays as it is. */
    public synchronized void cancel() {
        Arrays.fill(received,null);
        if(state!=State.ACKNOWLEDGED && state!=State.REJECTED) state=State.CANCELLED;
    }

    private void requireUsable() {
        if(state==State.ACKNOWLEDGED || state==State.REJECTED || state==State.CANCELLED)
            throw new IllegalArgumentException("Clock write plan is no longer usable");
    }

    private IllegalArgumentException reject() {
        Arrays.fill(received,null);
        // A stale call after completion or cancellation keeps that outcome.
        if(state==State.PREVIEW || state==State.AUTHORIZED || state==State.WRITING) state=State.REJECTED;
        return new IllegalArgumentException("Invalid or unexpected clock write acknowledgment");
    }
}
