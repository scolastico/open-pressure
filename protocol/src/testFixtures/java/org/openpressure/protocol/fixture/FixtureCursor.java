package org.openpressure.protocol.fixture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checks one client's writes against a fixture and hands back the scripted replies. Writes split across
 * legacy 16-byte channels (a multi-UUID alias) are reassembled by channel index before comparison.
 */
public final class FixtureCursor {
    public static final class Notification {
        public final UUID characteristic;
        public final byte[] value;
        /** The plaintext of an encrypted notification, for transcripts; null when {@link #value} is sent as is. */
        public final byte[] plain;
        Notification(UUID characteristic,byte[] value) { this(characteristic,value,null); }
        Notification(UUID characteristic,byte[] value,byte[] plain) { this.characteristic=characteristic; this.value=value; this.plain=plain; }
    }
    private final Fixture fixture;
    private final Fixture.Client client;
    private final List<Fixture.Step> steps;
    private final Map<Integer,byte[]> parts=new HashMap<>();
    private final List<String> transcript=new ArrayList<>();
    private int position;
    /** The token captured by the last matched {@code nonce} write, echoed into its scripted reply. */
    private byte[] token;
    private Outcome failure;
    /** The cuff side of the application encryption, for fixtures with a {@code secure} handshake; null otherwise. */
    private final SecurePeer secure;
    /** The peer's reply to the last matched handshake write, delivered as the following handshake notification. */
    private byte[] handshakeReply;
    /** The plaintext of the last write the peer decrypted; null when the last write was not encrypted. */
    private byte[] lastPlaintext;

    public FixtureCursor(Fixture fixture,Fixture.Client client) {
        this.fixture=fixture;
        this.client=client;
        steps=fixture.steps(client);
        secure=fixture.secureMode==null?null:new SecurePeer(fixture.secureMode.equals("saved")?fixture.key:null,fixture.secureReserved);
    }
    /** The live cuff-side encryption peer (also used by exploration replies), or null on a plain route. */
    public SecurePeer secure() { return secure; }
    /** The plaintext the peer decrypted from the last write, or null when it was not an encrypted frame. */
    public byte[] lastPlaintext() { return lastPlaintext==null?null:lastPlaintext.clone(); }

    /** Null while channel parts are pending or the frame matched; otherwise the terminal divergence. */
    public Outcome onWrite(UUID characteristic,byte[] value) {
        if(failure!=null) return failure;
        lastPlaintext=null;
        String alias=fixture.aliasOf(characteristic);
        String hex=java.util.HexFormat.of().formatHex(value);
        if(position<steps.size() && steps.get(position).encrypted() && secure!=null && secure.established() && value.length>=13 && (value[0]&255)==0xc0) {
            // Decrypt for the transcript and the comparison; the counter check is part of the match below.
            try {
                lastPlaintext=secure.decrypt(value);
                hex="[c0 #"+secure.phoneCounter()+"] "+java.util.HexFormat.of().formatHex(lastPlaintext);
            } catch(IllegalArgumentException refused) {
                transcript.add("write "+(alias==null?characteristic:alias)+" "+hex);
                Fixture.Step expected=steps.get(position);
                return fail(Outcome.diverged(position,expected.line,expected.bytes,value,"encrypted frame refused by the cuff: "+refused.getMessage()));
            }
        }
        transcript.add("write "+(alias==null?characteristic:alias)+" "+hex);
        if(position>=steps.size()) return fail(Outcome.diverged(position,0,null,value,"unexpected write after the last step"));
        Fixture.Step expected=steps.get(position);
        if(expected.kind!=Fixture.Step.Kind.WRITE) return fail(Outcome.diverged(position,expected.line,expected.bytes,value,"wrote before the scripted notification was delivered"));
        if(alias==null || !alias.equals(expected.alias)) return fail(Outcome.diverged(position,expected.line,expected.bytes,value,"wrote to "+(alias==null?characteristic:alias)+", expected "+expected.alias));
        byte[] frame=value;
        List<UUID> channels=fixture.uuids(alias);
        if(channels.size()>1) {
            int channel=channels.indexOf(characteristic);
            if(parts.containsKey(channel)) return fail(Outcome.diverged(position,expected.line,expected.bytes,value,"channel "+channel+" written twice for one frame"));
            parts.put(channel,value.clone());
            frame=assemble();
            if(frame==null) return null;
            int used=Math.max(1,((parts.get(0)[0]&255)+15)/16);
            for(int extra:parts.keySet()) if(extra>=used) {
                byte[] stray=parts.get(extra);
                parts.clear();
                return fail(Outcome.diverged(position,expected.line,expected.bytes,stray,"channel "+extra+" written beyond the "+used+"-part frame"));
            }
            parts.clear();
        }
        if(expected.handshake>0) {
            if(frame.length!=expected.bytes.length || frame[0]!=expected.bytes[0] || frame[1]!=expected.bytes[1])
                return fail(Outcome.diverged(position,expected.line,expected.bytes,frame,"expected handshake frame 70 "+String.format("%02x",expected.handshake)+" of "+expected.bytes.length+" bytes"));
            try { handshakeReply=secure.handshake(frame); }
            catch(IllegalArgumentException refused) { return fail(Outcome.diverged(position,expected.line,expected.bytes,frame,"handshake refused by the cuff: "+refused.getMessage())); }
            position++;
            return null;
        }
        if(expected.encrypted()) {
            if(lastPlaintext==null) return fail(Outcome.diverged(position,expected.line,expected.bytes,frame,"expected a C0-framed encrypted frame"));
            frame=lastPlaintext.clone();
        }
        if(!matches(expected,frame)) return fail(Outcome.diverged(position,expected.line,expected.bytes,frame,
            expected.tokenOffset<0?"frame differs":"frame differs (nonce token bytes "+expected.tokenOffset+"-"+(expected.tokenOffset+expected.tokenLength-1)+" may hold any value)"));
        if(expected.tokenOffset>=0) token=Arrays.copyOfRange(frame,expected.tokenOffset,expected.tokenOffset+expected.tokenLength);
        position++;
        return null;
    }
    /** Exact bytes, except a {@code nonce} write's token bytes, which may hold any value. */
    private static boolean matches(Fixture.Step expected,byte[] frame) {
        if(expected.tokenOffset<0) return Arrays.equals(frame,expected.bytes);
        if(frame.length!=expected.bytes.length) return false;
        for(int i=0;i<frame.length;i++)
            if((i<expected.tokenOffset || i>=expected.tokenOffset+expected.tokenLength) && frame[i]!=expected.bytes[i]) return false;
        return true;
    }
    private byte[] assemble() {
        byte[] first=parts.get(0);
        if(first==null || first.length==0) return null;
        int declared=first[0]&255,count=Math.max(1,(declared+15)/16);
        byte[] frame=new byte[declared];
        for(int i=0;i<count;i++) {
            byte[] part=parts.get(i);
            if(part==null) return null;
            System.arraycopy(part,0,frame,i*16,Math.min(part.length,Math.min(16,declared-i*16)));
        }
        return frame;
    }
    private Outcome fail(Outcome outcome) { failure=outcome; return outcome; }

    /** Scripted notifications following the last matched write, split per channel for multi-UUID aliases. */
    public List<Notification> pendingNotifications() {
        List<Notification> result=new ArrayList<>();
        if(failure!=null) return result;
        while(position<steps.size() && steps.get(position).kind==Fixture.Step.Kind.NOTIFY) {
            Fixture.Step step=steps.get(position++);
            List<UUID> channels=fixture.uuids(step.alias);
            byte[] bytes=step.bytes.clone();
            if(step.handshake>0) {
                // The handshake parser always places a handshake write before its reply.
                if(handshakeReply==null) throw new IllegalStateException("handshake reply without a matched request");
                bytes=handshakeReply; handshakeReply=null;
                transcript.add("notify "+step.alias+" "+java.util.HexFormat.of().formatHex(bytes));
                result.add(new Notification(channels.get(0),bytes));
                continue;
            }
            if(step.encrypted()) {
                byte[] sealed=secure.encrypt(bytes);
                transcript.add("notify "+step.alias+" [c0 #"+secure.cuffCounter()+"] "+java.util.HexFormat.of().formatHex(bytes));
                result.add(new Notification(channels.get(0),sealed,bytes));
                continue;
            }
            if(step.tokenOffset>=0) {
                // The fixture parser always places a nonce write before its reply.
                if(token==null || token.length!=step.tokenLength) throw new IllegalStateException("nonce reply without a captured token");
                System.arraycopy(token,0,bytes,step.tokenOffset,step.tokenLength);
            }
            transcript.add("notify "+step.alias+" "+java.util.HexFormat.of().formatHex(bytes));
            if(channels.size()==1) { result.add(new Notification(channels.get(0),bytes)); continue; }
            for(int i=0;i*16<bytes.length;i++)
                result.add(new Notification(channels.get(i),Arrays.copyOfRange(bytes,i*16,Math.min(bytes.length,(i+1)*16))));
        }
        return result;
    }
    public boolean finished() { return failure==null && position==steps.size(); }
    public int position() { return position; }
    public Outcome failure() { return failure; }
    public List<String> transcript() { return Collections.unmodifiableList(transcript); }
    /** The client stopped writing while steps remain. */
    public Outcome stalled() {
        return failure!=null?failure:Outcome.rejected(position,"no further write after step "+position+next());
    }
    /** Final verdict once the client is done; compares decoded readings with the fixture's expectations. */
    public Outcome finish(List<Fixture.Reading> decoded) { return finish(decoded,null); }
    /**
     * Final verdict that also compares the client's clock-sync outcome ({@code acknowledged}, {@code skipped},
     * {@code rejected}, or another lower-case outcome name; null when the client ran no clock sync) with the
     * fixture's {@code expect clock}.
     */
    public Outcome finish(List<Fixture.Reading> decoded,String clock) {
        if(failure!=null) return failure;
        if(position<steps.size()) return Outcome.rejected(position,"client ended at step "+position+next());
        if(!java.util.Objects.equals(clock,fixture.expectClock))
            return Outcome.diverged(position,0,null,null,"clock sync outcome "+clock+", expected "+fixture.expectClock);
        List<Fixture.Reading> missing=new ArrayList<>(fixture.readings(client)),extra=new ArrayList<>();
        for(Fixture.Reading reading:decoded) {
            Fixture.Reading match=null;
            for(Fixture.Reading expected:missing) if(expected.accepts(reading)) { match=expected; break; }
            if(match==null) extra.add(reading); else missing.remove(match);
        }
        if(!missing.isEmpty() || !extra.isEmpty())
            return Outcome.diverged(position,0,null,null,"readings differ; missing "+missing+", unexpected "+extra);
        return Outcome.matches(steps.size());
    }
    private String next() { return position<steps.size()?"; next expected "+steps.get(position):""; }
}
