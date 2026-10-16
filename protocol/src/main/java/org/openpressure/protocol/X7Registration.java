package org.openpressure.protocol;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * A selected-user X7 settings registration with two fixed staging writes.
 * The caller must persist backup() and obtain the user's selection before
 * authorize(), and send the existing WLP END only after complete().
 * This class cannot authorize any other memory range or construct END.
 */
public final class X7Registration {
    private final int user;
    private final byte[] backup,updated;
    private final byte[][] commands;
    private final byte[] response=new byte[32];
    private int phase,received;
    private boolean authorized,cancelled,awaiting;

    public X7Registration(String model,byte[] preimage,int user,LocalDateTime time) {
        if(!"HEM-7380T1-EOSL".equalsIgnoreCase(model) && !"X7 Smart AFib".equalsIgnoreCase(model))
            throw new IllegalArgumentException("Registration requires the exact X7 Smart AFib model");
        if(user<1 || user>2) throw new IllegalArgumentException("Select X7 user 1 or 2");
        if(time==null || time.getYear()<2024 || time.getYear()>2050)
            throw new IllegalArgumentException("X7 registration time must be in 2024–2050");
        LocalDateTime clock;
        try { clock=time.plusSeconds(1); }
        catch(DateTimeException e) { throw new IllegalArgumentException("Invalid X7 registration time",e); }
        if(clock.getYear()>2050) throw new IllegalArgumentException("X7 registration time exceeds 2050 after adjustment");
        this.user=user;
        if(preimage==null) throw new IllegalArgumentException("X7 registration requires a fresh settings backup");
        backup=preimage.clone();
        X7MemoryIndex.parse(backup);
        updated=backup.clone();
        // Original p4 adds 0024 for only the selected user and 100e for now+1 s.
        // The model's write map places those fields in settings block 3.
        updated[48]|=(byte)(1<<(user-1));
        updated[52]=(byte)(clock.getYear()-2000);
        updated[53]=(byte)clock.getMonthValue();
        updated[54]=(byte)clock.getDayOfMonth();
        updated[55]=(byte)clock.getHour();
        updated[56]=(byte)clock.getMinute();
        updated[57]=(byte)clock.getSecond();
        int sum=0;
        for(int i=44;i<58;i++) sum+=updated[i]&255;
        updated[58]=(byte)sum;
        // Original M always includes the 24-byte pointer prefix at staging 0x54,
        // followed here by the changed 16-byte block 3 at staging 0x80.
        byte[] prefix=new byte[32];
        prefix[0]=32; prefix[1]=1; prefix[2]=(byte)0xc0; prefix[4]=0x54; prefix[5]=24;
        System.arraycopy(updated,0,prefix,6,24);
        finishBcc(prefix);
        byte[] settings=new byte[24];
        settings[0]=24; settings[1]=1; settings[2]=(byte)0xc0; settings[4]=(byte)0x80; settings[5]=16;
        System.arraycopy(updated,44,settings,6,16);
        finishBcc(settings);
        commands=new byte[][]{prefix,settings};
    }

    public int user() { return user; }
    public byte[] backup() { return backup.clone(); }
    public byte[] updated() { return updated.clone(); }

    /** Call only after the backup is durably stored and the user confirms the profile. */
    public void authorize() {
        if(cancelled || phase==2 || authorized) throw new IllegalArgumentException("X7 registration cannot be authorized");
        authorized=true;
    }

    public byte[] command() {
        if(!authorized || cancelled || phase==2) throw new IllegalArgumentException("X7 registration write is not authorized");
        awaiting=true;
        return commands[phase].clone();
    }

    /** Permits the exact current frame, never an arbitrary addressed write. */
    public boolean allows(byte[] command) {
        return authorized && awaiting && !cancelled && phase<2 && Arrays.equals(commands[phase],command);
    }

    /** Accepts a compact ACK or the cuff's full data echo, matching every echoed byte. */
    public boolean accept(byte[] fragment) {
        if(!authorized || cancelled || phase==2 || !awaiting || fragment==null || fragment.length==0 || fragment.length>response.length-received)
            throw invalidResponse();
        System.arraycopy(fragment,0,response,received,fragment.length);
        received+=fragment.length;
        byte[] request=commands[phase];
        int declared=response[0]&255;
        if((declared!=8 && declared!=request.length) || received>declared) throw invalidResponse();
        if(received<declared) return false;
        int bcc=0;
        for(int i=0;i<declared;i++) bcc^=response[i]&255;
        if((response[1]&255)!=0x81 || response[2]!=request[2] || response[3]!=request[3]
                || response[4]!=request[4] || response[5]!=request[5] || response[declared-2]!=0 || bcc!=0)
            throw invalidResponse();
        if(declared!=8) for(int i=6;i<declared-2;i++) if(response[i]!=request[i]) throw invalidResponse();
        phase++;
        received=0;
        awaiting=false;
        Arrays.fill(response,(byte)0);
        return true;
    }

    public boolean complete() { return phase==2 && !cancelled; }

    public void cancel() {
        cancelled=true;
        authorized=false;
        awaiting=false;
        received=0;
        Arrays.fill(response,(byte)0);
    }

    private IllegalArgumentException invalidResponse() {
        cancel();
        return new IllegalArgumentException("Invalid or unexpected X7 registration write acknowledgment");
    }

    private static void finishBcc(byte[] frame) {
        int bcc=0;
        for(int i=0;i<frame.length-1;i++) bcc^=frame[i]&255;
        frame[frame.length-1]=(byte)bcc;
    }
}
