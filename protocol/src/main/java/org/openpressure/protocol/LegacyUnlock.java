package org.openpressure.protocol;

/** Bounded legacy b305 exchange: pair enters key mode, programs a key, then unlocks for reading. */
public final class LegacyUnlock implements AutoCloseable {
    private final byte[] key;
    private final boolean pairing;
    private int phase;
    private boolean issued,failed;

    private LegacyUnlock(byte[] key,boolean pairing) {
        if(key==null || key.length!=16) throw new IllegalArgumentException("Legacy unlock requires a 16-byte key");
        this.key=key.clone(); this.pairing=pairing;
    }
    public static LegacyUnlock pair(byte[] freshKey) { return new LegacyUnlock(freshKey,true); }
    public static LegacyUnlock connect(byte[] storedKey) { return new LegacyUnlock(storedKey,false); }
    public byte[] command() {
        if(failed || complete()) throw new IllegalArgumentException("Legacy unlock is no longer active");
        issued=true;
        return frame();
    }
    private byte[] frame() {
        byte[] command=new byte[17];
        command[0]=(byte)(pairing && phase==0?2:pairing && phase==1?0:1);
        if(!pairing || phase>0) System.arraycopy(key,0,command,1,16);
        return command;
    }
    /** A permit covers only the current issued frame, and expires on ACK, failure or close. */
    public boolean allows(byte[] payload) {
        return issued && !failed && !complete() && java.util.Arrays.equals(payload,frame());
    }
    /** Checks byte 0 like the original app, plus a zero status byte when present; trailing padding is ignored. */
    public boolean accept(byte[] response) {
        int expected=pairing && phase==0?0x82:pairing && phase==1?0x80:0x81;
        if(failed || complete() || !issued || response==null || response.length<1 ||
                (response[0]&255)!=expected || response.length>=2 && response[1]!=0) {
            failed=true;
            throw new IllegalArgumentException("Invalid legacy unlock acknowledgment");
        }
        phase++; issued=false;
        return true;
    }
    public boolean complete() { return !failed && phase==(pairing?3:1); }
    /** A key becomes established only after the final successful unlock acknowledgment. */
    public byte[] key() {
        if(!complete()) throw new IllegalArgumentException("Legacy key has not been acknowledged");
        return key.clone();
    }
    @Override public void close() {
        java.util.Arrays.fill(key,(byte)0);
        failed=true; issued=false;
    }
}
