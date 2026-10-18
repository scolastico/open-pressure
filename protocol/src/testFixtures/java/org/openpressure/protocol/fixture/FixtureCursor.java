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
        Notification(UUID characteristic,byte[] value) { this.characteristic=characteristic; this.value=value; }
    }
    private final Fixture fixture;
    private final Fixture.Client client;
    private final List<Fixture.Step> steps;
    private final Map<Integer,byte[]> parts=new HashMap<>();
    private final List<String> transcript=new ArrayList<>();
    private int position;
    private Outcome failure;

    public FixtureCursor(Fixture fixture,Fixture.Client client) { this(fixture,client,java.util.Set.of()); }
    /** Steps on ignored aliases are outside this client's reach (e.g. the app's clock for a protocol-only replay). */
    public FixtureCursor(Fixture fixture,Fixture.Client client,java.util.Set<String> ignoredAliases) {
        this.fixture=fixture;
        this.client=client;
        List<Fixture.Step> kept=new ArrayList<>();
        for(Fixture.Step step:fixture.steps(client)) if(!ignoredAliases.contains(step.alias)) kept.add(step);
        steps=Collections.unmodifiableList(kept);
    }

    /** Null while channel parts are pending or the frame matched; otherwise the terminal divergence. */
    public Outcome onWrite(UUID characteristic,byte[] value) {
        if(failure!=null) return failure;
        String alias=fixture.aliasOf(characteristic);
        transcript.add("write "+(alias==null?characteristic:alias)+" "+java.util.HexFormat.of().formatHex(value));
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
        if(!Arrays.equals(frame,expected.bytes)) return fail(Outcome.diverged(position,expected.line,expected.bytes,frame,"frame differs"));
        position++;
        return null;
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
            transcript.add("notify "+step.alias+" "+java.util.HexFormat.of().formatHex(step.bytes));
            if(channels.size()==1) { result.add(new Notification(channels.get(0),step.bytes.clone())); continue; }
            for(int i=0;i*16<step.bytes.length;i++)
                result.add(new Notification(channels.get(i),Arrays.copyOfRange(step.bytes,i*16,Math.min(step.bytes.length,(i+1)*16))));
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
    public Outcome finish(List<Fixture.Reading> decoded) {
        if(failure!=null) return failure;
        if(position<steps.size()) return Outcome.rejected(position,"client ended at step "+position+next());
        List<Fixture.Reading> missing=new ArrayList<>(fixture.readings(client)),extra=new ArrayList<>();
        for(Fixture.Reading reading:decoded) if(!missing.remove(reading)) extra.add(reading);
        if(!missing.isEmpty() || !extra.isEmpty())
            return Outcome.diverged(position,0,null,null,"readings differ; missing "+missing+", unexpected "+extra);
        return Outcome.matches(steps.size());
    }
    private String next() { return position<steps.size()?"; next expected "+steps.get(position):""; }
}
