package org.openpressure.protocol.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.openpressure.protocol.Wlp;

/**
 * One synthetic, deterministic connection script shared by every client runner. Each client is replayed
 * against the same steps, so the fixture is the common reference; see docs/omron-sandbox.md for the syntax.
 */
public final class Fixture {
    public enum Client { OMRON, OURS_PROTOCOL, OURS_APP }
    private static final String ROOT="/org/openpressure/protocol/fixtures/";

    public final String name;
    public String advertisedName;
    public final List<UUID> advertisedServices=new ArrayList<>();
    public byte[] manufacturerData;
    public final Map<String,String> dis=new LinkedHashMap<>();
    public LocalDateTime time;
    public ZoneId zone=ZoneId.of("UTC");
    public byte[] key;
    /**
     * "connect" reconnects with a saved key; "pair" registers the key first; "read-all" connects and reads every selected
     * bank; "sync-clock" connects, reads the settings image and runs the explicit clock-block write.
     */
    public String flow="connect";
    /** Clock-sync outcome a {@code sync-clock} flow must reach ("acknowledged", "skipped" or "rejected"); null otherwise. */
    public String expectClock;
    public byte[] random=new byte[0];
    /**
     * The monitor's largest ATT MTU ({@code mtu <n>}); a client's request negotiates the smaller of the two. The default
     * 23 is the ATT minimum, which every legacy fixture uses.
     */
    public int mtu=23;
    /** User a pairing session registers (Omron registers exactly one; the legacy key is not per user). */
    public int registerUser=1;
    /** Users an import covers as a bitmask (1 = user 1, 2 = user 2, 3 = both). */
    public int importUsers=3;
    public final Map<String,List<UUID>> aliases=new LinkedHashMap<>();
    /**
     * The X7 application encryption ({@code secure <alias> fresh|saved}): "fresh" for a new application pairing,
     * "saved" for authentication with the fixture's {@link #key} as the saved LTK; null on plain routes. Every step
     * after the handshake is plaintext in the fixture and encrypted on the link by the cuff-side {@link SecurePeer}.
     */
    public String secureMode;
    /** The single-characteristic alias that carries the handshake ({@code sys} on the X7). */
    public String secureAlias;
    /** The 16 reserved bytes at the end of the cuff's {@code F0 85} reply ({@code reserved=}; zero by default). */
    public byte[] secureReserved=new byte[16];
    private final List<Step> steps=new ArrayList<>();
    private final List<Reading> readings=new ArrayList<>();
    private final Set<Client> rejectedBy=EnumSet.noneOf(Client.class);
    private final TreeMap<Integer,Byte> memory=new TreeMap<>();
    private byte fill=(byte)0xff;

    private Fixture(String name) { this.name=name; }

    public static Fixture load(String name) {
        try(InputStream in=Fixture.class.getResourceAsStream(ROOT+name+".fixture")) {
            if(in==null) throw new IllegalArgumentException("Unknown fixture "+name);
            return parse(name,new String(in.readAllBytes(),StandardCharsets.UTF_8));
        } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    public static List<String> all() {
        try(InputStream in=Fixture.class.getResourceAsStream(ROOT+"index")) {
            if(in==null) throw new IllegalStateException("Fixture index missing");
            List<String> names=new ArrayList<>();
            for(String line:new String(in.readAllBytes(),StandardCharsets.UTF_8).split("\n")) {
                line=strip(line);
                if(!line.isEmpty()) names.add(line);
            }
            return Collections.unmodifiableList(names);
        } catch(IOException failure) { throw new IllegalStateException(failure); }
    }

    public static Fixture parse(String name,String text) {
        Fixture f=new Fixture(name);
        List<Object[]> pending=new ArrayList<>();
        String[] lines=text.split("\n",-1);
        for(int i=0;i<lines.length;i++) {
            int number=i+1;
            String line=strip(lines[i]);
            if(line.isEmpty()) continue;
            try { f.directive(line,number,pending); }
            catch(RuntimeException error) { throw new IllegalArgumentException(name+" line "+number+": "+error.getMessage(),error); }
        }
        for(Object[] entry:pending) {
            int number=(Integer)entry[0];
            try { f.expand(entry); }
            catch(RuntimeException error) { throw new IllegalArgumentException(name+" line "+number+": "+error.getMessage(),error); }
        }
        // Runners need these; report them here instead of failing inside a replay.
        if(f.key==null) throw new IllegalArgumentException(name+": missing key (every flow unlocks with it)");
        if(f.time==null && f.aliases.containsKey("clock")) throw new IllegalArgumentException(name+": missing time for the clock alias");
        if(f.flow.equals("sync-clock")!=(f.expectClock!=null))
            throw new IllegalArgumentException(name+": a sync-clock flow needs exactly one expect clock line, and only that flow may have one");
        if(f.flow.equals("sync-clock") && f.time==null) throw new IllegalArgumentException(name+": missing time (the clock-sync target)");
        if(f.secureMode!=null) {
            if(f.secureMode.equals("fresh")!=f.flow.equals("pair")) throw new IllegalArgumentException(name+": secure fresh belongs to a pair flow, secure saved to the other flows");
            for(Map.Entry<String,List<UUID>> alias:f.aliases.entrySet())
                if(alias.getValue().size()!=1) throw new IllegalArgumentException(name+": encrypted fixtures need single-characteristic aliases, "+alias.getKey()+" has "+alias.getValue().size());
        }
        return f;
    }

    private void directive(String line,int number,List<Object[]> pending) {
        String only=null,reason=null;
        if(line.startsWith("[")) {
            int close=line.indexOf(']');
            if(close<0) throw new IllegalArgumentException("unclosed annotation");
            String annotation=line.substring(1,close).trim();
            int colon=annotation.indexOf(':');
            if(colon<0 || annotation.substring(colon+1).isBlank()) throw new IllegalArgumentException("annotation needs a reason: [omron-only: why]");
            String kind=annotation.substring(0,colon).trim();
            if(kind.equals("omron-only")) only="omron"; else if(kind.equals("ours-only")) only="ours";
            else throw new IllegalArgumentException("unknown annotation "+kind);
            reason=annotation.substring(colon+1).trim();
            line=line.substring(close+1).trim();
        }
        String[] words=line.split("\\s+",2);
        String rest=words.length>1?words[1].trim():"";
        if(only!=null && !Set.of("write","notify","reads","nonce","expect").contains(words[0])) throw new IllegalArgumentException("only steps and reading expectations can be annotated");
        if(secureMode!=null && words[0].equals("nonce")) throw new IllegalArgumentException("the nonce transaction precedes the encryption handshake");
        switch(words[0]) {
            case "name": if(!rest.equals(name)) throw new IllegalArgumentException("name "+rest+" does not match file "+name); break;
            case "advertise":
                for(Map.Entry<String,String> e:pairs(rest).entrySet()) {
                    if(e.getKey().equals("name")) advertisedName=e.getValue();
                    else if(e.getKey().equals("services")) for(String id:e.getValue().split(",")) advertisedServices.add(UUID.fromString(id));
                    else if(e.getKey().equals("msd")) manufacturerData=hex(e.getValue());
                    else throw new IllegalArgumentException("unknown advertise field "+e.getKey());
                }
                break;
            case "dis":
                dis.putAll(pairs(rest));
                if(dis.containsKey("systemid") && !dis.get("systemid").matches("([0-9a-fA-F]{2})+"))
                    throw new IllegalArgumentException("systemid must be hex bytes, got "+dis.get("systemid"));
                break;
            case "time": {
                String[] parts=rest.split("\\s+");
                time=LocalDateTime.parse(parts[0]);
                for(int i=1;i<parts.length;i++) {
                    if(!parts[i].startsWith("zone=")) throw new IllegalArgumentException("unknown time field "+parts[i]);
                    zone=ZoneId.of(parts[i].substring(5));
                }
                break;
            }
            case "flow":
                if(!Set.of("connect","pair","read-all","sync-clock").contains(rest)) throw new IllegalArgumentException("flow must be connect, pair, read-all or sync-clock");
                flow=rest; break;
            case "key": key=hex(rest); if(key.length!=16) throw new IllegalArgumentException("key must be 16 bytes"); break;
            case "register": {
                Map<String,String> v=pairs(rest);
                if(v.size()!=1 || !v.containsKey("user")) throw new IllegalArgumentException("register needs: register user=<n>");
                registerUser=Integer.parseInt(v.get("user"));
                if(registerUser<1 || registerUser>2) throw new IllegalArgumentException("register user must be 1 or 2");
                break;
            }
            case "import": {
                Map<String,String> v=pairs(rest);
                if(v.size()!=1 || !v.containsKey("users")) throw new IllegalArgumentException("import needs: import users=<1|2|both>");
                switch(v.get("users")) {
                    case "1": importUsers=1; break;
                    case "2": importUsers=2; break;
                    case "both": importUsers=3; break;
                    default: throw new IllegalArgumentException("import users must be 1, 2 or both");
                }
                break;
            }
            case "random": random=hex(rest); break;
            case "mtu":
                mtu=number(rest);
                if(mtu<23 || mtu>517) throw new IllegalArgumentException("mtu must be 23 to 517");
                break;
            case "nonce": {
                if(rest.isEmpty() || rest.contains(" ")) throw new IllegalArgumentException("nonce needs: nonce <alias>");
                alias(rest);
                pending.add(new Object[]{number,"nonce",rest,null,only,reason});
                break;
            }
            case "secure": {
                String[] parts=rest.split("\\s+");
                if(parts.length<2 || !Set.of("fresh","saved").contains(parts[1])) throw new IllegalArgumentException("secure needs: secure <alias> fresh|saved [reserved=<16 bytes hex>]");
                if(secureMode!=null) throw new IllegalArgumentException("secure given twice");
                alias(parts[0]);
                if(aliases.get(parts[0]).size()!=1) throw new IllegalArgumentException("secure needs a single-characteristic alias");
                for(int i=2;i<parts.length;i++) {
                    if(!parts[i].startsWith("reserved=")) throw new IllegalArgumentException("unknown secure field "+parts[i]);
                    secureReserved=hex(parts[i].substring(9));
                    if(secureReserved.length!=16) throw new IllegalArgumentException("reserved must be 16 bytes");
                }
                secureMode=parts[1]; secureAlias=parts[0];
                pending.add(new Object[]{number,"secure",parts[0],null,null,null});
                break;
            }
            case "alias": {
                String[] parts=rest.split("\\s+");
                if(parts.length!=2) throw new IllegalArgumentException("alias needs a name and UUIDs");
                List<UUID> ids=new ArrayList<>();
                for(String id:parts[1].split(",")) ids.add(UUID.fromString(id));
                aliases.put(parts[0],Collections.unmodifiableList(ids));
                break;
            }
            case "memory":
                if(rest.startsWith("fill=")) fill=(byte)Integer.parseInt(rest.substring(5),16);
                else {
                    String[] parts=rest.split("\\s+",2);
                    int address=number(parts[0]);
                    byte[] bytes=hex(parts.length>1?parts[1]:"");
                    for(int i=0;i<bytes.length;i++) memory.put(address+i,bytes[i]);
                }
                break;
            case "write": case "notify": {
                String[] parts=rest.split("\\s+",2);
                alias(parts[0]);
                pending.add(new Object[]{number,words[0],parts[0],hex(parts.length>1?parts[1]:""),only,reason});
                break;
            }
            case "reads": {
                String[] parts=rest.split("\\s+");
                if(parts.length!=3 || !parts[2].startsWith("chunk=")) throw new IllegalArgumentException("reads needs: reads <address> <length> chunk=<n>");
                alias("tx"); alias("rx");
                pending.add(new Object[]{number,"reads",new int[]{number(parts[0]),number(parts[1]),number(parts[2].substring(6))},null,only,reason});
                break;
            }
            case "expect": {
                String[] parts=rest.split("\\s+",2);
                if(parts[0].equals("reading")) readings.add(Reading.parse(parts.length>1?parts[1]:"",only,reason,number));
                else if(only!=null) throw new IllegalArgumentException("only reading expectations can be annotated");
                else if(parts[0].equals("clock")) {
                    String value=parts.length>1?parts[1]:"";
                    if(!Set.of("acknowledged","skipped","rejected").contains(value)) throw new IllegalArgumentException("expect clock must be acknowledged, skipped or rejected");
                    if(expectClock!=null) throw new IllegalArgumentException("expect clock given twice");
                    expectClock=value;
                }
                else if(parts.length==2 && parts[1].equals("rejected")) {
                    if(parts[0].equals("ours")) { rejectedBy.add(Client.OURS_PROTOCOL); rejectedBy.add(Client.OURS_APP); }
                    else if(parts[0].equals("omron")) rejectedBy.add(Client.OMRON);
                    else throw new IllegalArgumentException("unknown client "+parts[0]);
                } else throw new IllegalArgumentException("unknown expectation "+rest);
                break;
            }
            default: throw new IllegalArgumentException("unknown directive "+words[0]);
        }
    }
    private void expand(Object[] entry) {
        int number=(Integer)entry[0];
        String kind=(String)entry[1],only=(String)entry[4],reason=(String)entry[5];
        if(kind.equals("secure")) {
            // The handshake as the cuff sees it: each phone frame is checked for its opcode, length and fixed bytes and
            // answered by the live peer, because both sides' randoms make the frames themselves unpinnable.
            String alias=(String)entry[2];
            int[][] frames=secureMode.equals("fresh")?new int[][]{{1,89},{5,46},{6,46}}:new int[][]{{5,46},{6,46}};
            for(int[] frame:frames) {
                byte[] request=new byte[frame[1]],reply=new byte[frame[1]];
                request[0]=0x70; request[1]=(byte)frame[0];
                reply[0]=(byte)0xf0; reply[1]=(byte)(0x80|frame[0]);
                steps.add(new Step(Step.Kind.WRITE,alias,request,-1,0,frame[0],null,null,number));
                steps.add(new Step(Step.Kind.NOTIFY,alias,reply,-1,0,frame[0],null,null,number));
            }
            encrypting=true;
            return;
        }
        if(kind.equals("nonce")) {
            // The X7 system transaction: the phone writes 11 + a random 4-byte token + 15 zeros; the monitor echoes it
            // as 91 00 token 00 plus zero padding. The token is fresh random data in both clients, so it is matched by
            // position and echoed into the reply instead of being pinned.
            byte[] request=new byte[20],reply=new byte[20];
            request[0]=0x11; reply[0]=(byte)0x91;
            String alias=(String)entry[2];
            if(aliases.get(alias).size()!=1) throw new IllegalArgumentException("nonce needs a single-characteristic alias");
            steps.add(new Step(Step.Kind.WRITE,alias,request,1,4,-1,only,reason,number));
            steps.add(new Step(Step.Kind.NOTIFY,alias,reply,2,4,-1,only,reason,number));
            return;
        }
        if(!kind.equals("reads")) {
            int limit=16*aliases.get((String)entry[2]).size();
            if(kind.equals("notify") && aliases.get((String)entry[2]).size()>1 && ((byte[])entry[3]).length>limit)
                throw new IllegalArgumentException("notify on "+entry[2]+" exceeds "+limit+" bytes");
            steps.add(new Step(kind.equals("write")?Step.Kind.WRITE:Step.Kind.NOTIFY,(String)entry[2],(byte[])entry[3],only,reason,number).encrypted(encrypting));
            return;
        }
        int[] range=(int[])entry[2];
        if(range[1]<1 || range[2]<1 || range[2]>56) throw new IllegalArgumentException("invalid reads range");
        for(int done=0;done<range[1];done+=range[2]) {
            int address=range[0]+done,length=Math.min(range[2],range[1]-done);
            byte[] request=Wlp.read(address,length);
            byte[] reply=new byte[length+8];
            reply[0]=(byte)reply.length; reply[1]=(byte)0x81;
            System.arraycopy(request,2,reply,2,4);
            for(int i=0;i<length;i++) reply[6+i]=memory.getOrDefault(address+i,fill);
            for(int i=0;i<reply.length-1;i++) reply[reply.length-1]^=reply[i];
            steps.add(new Step(Step.Kind.WRITE,"tx",request,only,reason,number).encrypted(encrypting));
            steps.add(new Step(Step.Kind.NOTIFY,"rx",reply,only,reason,number).encrypted(encrypting));
        }
    }
    /** Set once the {@code secure} handshake is expanded: later steps travel encrypted. */
    private boolean encrypting;
    private List<UUID> alias(String name) {
        List<UUID> ids=aliases.get(name);
        if(ids==null) throw new IllegalArgumentException("unknown alias "+name);
        return ids;
    }

    /** Steps a client must follow: shared steps plus that client's own annotated steps. */
    public List<Step> steps(Client client) {
        List<Step> result=new ArrayList<>();
        for(Step step:steps) if(includes(step,client)) result.add(step);
        return Collections.unmodifiableList(result);
    }
    /** Every annotated difference; reported as open findings rather than silently accepted. */
    public List<Annotation> annotated() {
        List<Annotation> result=new ArrayList<>();
        Set<Integer> lines=new java.util.HashSet<>();
        for(Step step:steps) if(step.only!=null && lines.add(step.line)) result.add(step);
        for(Reading reading:readings) if(reading.only!=null) result.add(reading);
        result.sort(java.util.Comparator.comparingInt(annotation -> annotation.line));
        return Collections.unmodifiableList(result);
    }
    /** All declared readings, also used to choose the synthetic session's users. */
    public List<Reading> readings() { return Collections.unmodifiableList(readings); }
    /** Readings this client must decode, including shared expectations. */
    public List<Reading> readings(Client client) {
        List<Reading> result=new ArrayList<>();
        for(Reading reading:readings) if(includes(reading,client)) result.add(reading);
        return Collections.unmodifiableList(result);
    }
    private static boolean includes(Annotation annotation,Client client) {
        return annotation.only==null || annotation.only.equals(client==Client.OMRON?"omron":"ours");
    }
    /** The synthetic memory behind {@code reads}: declared bytes, the fill byte elsewhere. */
    public byte[] memory(int address,int length) {
        byte[] out=new byte[length];
        for(int i=0;i<length;i++) out[i]=memory.getOrDefault(address+i,fill);
        return out;
    }
    /**
     * The Omron manufacturer data as Android's {@code getManufacturerSpecificData(0x020e)} returns it: the
     * {@code advertise msd=} bytes after the little-endian company ID {@code 0e 02}; null without Omron data.
     */
    public byte[] omronManufacturerPayload() {
        if(manufacturerData==null || manufacturerData.length<2 || manufacturerData[0]!=0x0e || manufacturerData[1]!=0x02) return null;
        return Arrays.copyOfRange(manufacturerData,2,manufacturerData.length);
    }
    /** True when the fixture expects this client to refuse the device instead of following the steps. */
    public boolean expectRejected(Client client) { return rejectedBy.contains(client); }
    public List<UUID> uuids(String alias) { return alias(alias); }
    /** Alias that contains this characteristic, or null. */
    public String aliasOf(UUID characteristic) {
        for(Map.Entry<String,List<UUID>> e:aliases.entrySet()) if(e.getValue().contains(characteristic)) return e.getKey();
        return null;
    }

    public static class Annotation {
        public final String only,reason;
        public final int line;
        Annotation(String only,String reason,int line) { this.only=only; this.reason=reason; this.line=line; }
    }

    public static final class Step extends Annotation {
        public enum Kind { WRITE, NOTIFY }
        public final Kind kind;
        public final String alias;
        public final byte[] bytes;
        /**
         * A {@code nonce} step's token position: in a write, these {@link #tokenLength} bytes from {@link #tokenOffset}
         * may hold anything and are captured; in a notification they are filled with the captured token. -1 otherwise.
         */
        public final int tokenOffset,tokenLength;
        /**
         * A {@code secure} handshake step's phone opcode (1, 5 or 6), -1 otherwise. Its write matches any frame with the
         * same first two bytes and length (the peer checks the rest), and its notification is the peer's live reply.
         */
        public final int handshake;
        private boolean encrypted;
        Step(Kind kind,String alias,byte[] bytes,String only,String reason,int line) { this(kind,alias,bytes,-1,0,-1,only,reason,line); }
        Step(Kind kind,String alias,byte[] bytes,int tokenOffset,int tokenLength,int handshake,String only,String reason,int line) {
            super(only,reason,line);
            this.kind=kind; this.alias=alias; this.bytes=bytes; this.tokenOffset=tokenOffset; this.tokenLength=tokenLength; this.handshake=handshake;
        }
        private Step encrypted(boolean value) { encrypted=value; return this; }
        /** True after a {@code secure} handshake: the bytes are the plaintext of a {@code C0}-framed encrypted frame. */
        public boolean encrypted() { return encrypted; }
        /** The step's hex, with {@code ??} for each token byte. */
        public String hex() {
            if(handshake>0) return HexFormat.of().formatHex(bytes,0,2)+"<"+(bytes.length-2)+(kind==Kind.WRITE?" phone":" cuff")+" handshake bytes>";
            String hex=HexFormat.of().formatHex(bytes);
            if(tokenOffset<0) return hex;
            return hex.substring(0,2*tokenOffset)+"??".repeat(tokenLength)+hex.substring(2*(tokenOffset+tokenLength));
        }
        @Override public String toString() {
            return (only==null?"":"["+only+"-only] ")+kind.name().toLowerCase()+" "+alias+" "+(encrypted?"encrypted ":"")+hex()+" (line "+line+")";
        }
    }

    public static final class Reading extends Annotation {
        public final int user,sys,dia,pulse;
        public final LocalDateTime time;
        /**
         * Optional measurement flags (0 or 1): irregular pulse, body movement and AFib. In an expectation, null means
         * "not compared"; in a decoded reading, null means the client did not report the flag.
         */
        public final Integer irregular,movement,afib;
        public Reading(int user,int sys,int dia,int pulse,LocalDateTime time) {
            this(user,sys,dia,pulse,time,null,null,null,null,null,0);
        }
        private Reading(int user,int sys,int dia,int pulse,LocalDateTime time,Integer irregular,Integer movement,Integer afib,String only,String reason,int line) {
            super(only,reason,line);
            this.user=user; this.sys=sys; this.dia=dia; this.pulse=pulse; this.time=time;
            this.irregular=irregular; this.movement=movement; this.afib=afib;
        }
        /** This decoded reading with the flags the client reported (null when it reports none). */
        public Reading withFlags(Integer irregular,Integer movement,Integer afib) {
            return new Reading(user,sys,dia,pulse,time,irregular,movement,afib,only,reason,line);
        }
        static Reading parse(String text,String only,String reason,int line) {
            Map<String,String> v=pairs(text);
            for(String field:new String[]{"user","sys","dia","pulse","time"}) if(!v.containsKey(field)) throw new IllegalArgumentException("reading needs "+field);
            for(String field:v.keySet()) if(!Set.of("user","sys","dia","pulse","time","irregular","movement","afib").contains(field))
                throw new IllegalArgumentException("unknown reading field "+field);
            return new Reading(Integer.parseInt(v.get("user")),Integer.parseInt(v.get("sys")),Integer.parseInt(v.get("dia")),
                Integer.parseInt(v.get("pulse")),LocalDateTime.parse(v.get("time")),flag(v,"irregular"),flag(v,"movement"),flag(v,"afib"),only,reason,line);
        }
        private static Integer flag(Map<String,String> v,String name) {
            String value=v.get(name);
            if(value==null) return null;
            if(!value.equals("0") && !value.equals("1")) throw new IllegalArgumentException(name+" must be 0 or 1");
            return Integer.parseInt(value);
        }
        /** Whether a decoded reading satisfies this expectation: equal values, and equal flags where this one names them. */
        public boolean accepts(Reading decoded) {
            return user==decoded.user && sys==decoded.sys && dia==decoded.dia && pulse==decoded.pulse && Objects.equals(time,decoded.time)
                && (irregular==null || irregular.equals(decoded.irregular)) && (movement==null || movement.equals(decoded.movement))
                && (afib==null || afib.equals(decoded.afib));
        }
        @Override public boolean equals(Object other) {
            return other instanceof Reading && toString().equals(other.toString());
        }
        @Override public int hashCode() { return Objects.hash(user,sys,dia,pulse,time,irregular,movement,afib); }
        @Override public String toString() {
            return "user="+user+" sys="+sys+" dia="+dia+" pulse="+pulse+" time="+time
                +(irregular==null?"":" irregular="+irregular)+(movement==null?"":" movement="+movement)+(afib==null?"":" afib="+afib);
        }
    }

    private static Map<String,String> pairs(String text) {
        Map<String,String> result=new LinkedHashMap<>();
        if(text.isBlank()) return result;
        for(String part:text.trim().split("\\s+")) {
            int eq=part.indexOf('=');
            if(eq<1) throw new IllegalArgumentException("expected key=value, got "+part);
            result.put(part.substring(0,eq),part.substring(eq+1));
        }
        return result;
    }
    private static int number(String text) {
        return text.startsWith("0x")?Integer.parseInt(text.substring(2),16):Integer.parseInt(text);
    }
    private static byte[] hex(String text) {
        String compact=text.replaceAll("\\s+","");
        if(compact.length()%2!=0 || !compact.matches("[0-9a-fA-F]*")) throw new IllegalArgumentException("invalid hex "+text);
        return HexFormat.of().parseHex(compact);
    }
    private static String strip(String line) {
        int hash=line.indexOf('#');
        return (hash<0?line:line.substring(0,hash)).trim();
    }
    static byte[] copy(byte[] bytes) { return bytes==null?null:Arrays.copyOf(bytes,bytes.length); }
}
