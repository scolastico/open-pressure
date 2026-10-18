package org.openpressure.protocol;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.FixtureCursor;
import org.openpressure.protocol.fixture.Outcome;

/** Shared fixture syntax, step expansion and the replay cursor used by every client runner. */
public final class FixtureTest {
    private static int checks;
    private static final String TX="db5b55e0-aee7-11e1-965e-0002a5d5c51b,e0b8a060-aee7-11e1-92f4-0002a5d5c51b,0ae12b00-aee8-11e1-a192-0002a5d5c51b,10e1ba60-aee8-11e1-89e5-0002a5d5c51b";
    private static final String RX="49123040-aee8-11e1-a74d-0002a5d5c51b,4d0bf320-aee8-11e1-a0d9-0002a5d5c51b,5128ce60-aee8-11e1-b84b-0002a5d5c51b,560f1420-aee8-11e1-8184-0002a5d5c51b";
    private static final String HEAD="name test/sample\n"+
        "advertise name=BLEsmart_0000057A services=ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b\n"+
        "dis model=HEM-7156T\n"+
        "time 2026-01-02T03:04:05 zone=UTC\n"+
        "key 05050505050505050505050505050505\n"+
        "alias unlock b305b680-aee7-11e1-a730-0002a5d5c51b\n"+
        "alias tx "+TX+"\n"+
        "alias rx "+RX+"\n"+
        "memory fill=ff\n"+
        "memory 0x98 694e4319261da6030000000000000000\n";
    public static void main(String[] args) { System.out.println("Fixture checks: "+run()); }
    public static int run() {
        checks=0;
        parsesHeaderAndExpandsReads();
        filtersOnlyStepsPerClient();
        rejectsMalformedLinesWithLineNumbers();
        reassemblesLegacyChannelWritesIntoOneFrame();
        strayChannelWriteIsADivergence();
        incompleteFixturesFailAtParseTime();
        divergenceReportsStepAndDecodedFields();
        finishComparesReadings();
        finishComparesOnlyTheClientsReadings();
        return checks;
    }
    private static void parsesHeaderAndExpandsReads() {
        Fixture f=Fixture.parse("test/sample",HEAD+"reads 0x98 32 chunk=16\n");
        check("BLEsmart_0000057A".equals(f.advertisedName),"advertised name");
        check(f.advertisedServices.equals(List.of(UUID.fromString("ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b"))),"advertised services");
        check("HEM-7156T".equals(f.dis.get("model")),"dis model");
        check(f.time.toString().equals("2026-01-02T03:04:05") && f.zone.getId().equals("UTC"),"pinned time");
        check(f.aliases.get("tx").size()==4,"multi-UUID alias");
        List<Fixture.Step> steps=f.steps(Fixture.Client.OURS_PROTOCOL);
        check(steps.size()==4,"two chunks expand to write+notify pairs");
        check(steps.get(0).kind==Fixture.Step.Kind.WRITE && Arrays.equals(steps.get(0).bytes,Wlp.read(0x98,16)),"read frame equals Wlp.read");
        byte[] reply=steps.get(1).bytes;
        check(steps.get(1).kind==Fixture.Step.Kind.NOTIFY && reply.length==24 && (reply[1]&255)==0x81,"reply framing");
        check(Arrays.equals(Arrays.copyOfRange(reply,6,22),hex("694e4319261da6030000000000000000")),"reply carries memory bytes");
        check(Arrays.equals(Wlp.payload(reply,1),hex("694e4319261da6030000000000000000")),"reply checksum valid");
        byte[] second=steps.get(3).bytes;
        check(Arrays.equals(Wlp.payload(second,1),hex("ffffffffffffffffffffffffffffffff")),"unset memory uses the fill byte");
        check(Arrays.equals(f.memory(0x96,6),hex("ffff694e4319")),"memory exposes declared bytes and the fill around them");
        check(f.registerUser==1,"pairing registers user 1 unless told otherwise");
        check(Fixture.parse("test/sample",HEAD+"flow pair\nregister user=2\n").registerUser==2,"register user directive");
        try { Fixture.parse("test/sample",HEAD+"register user=3\n"); throw new AssertionError("user 3 accepted"); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().contains("1 or 2"),"register user range"); }
    }
    private static void filtersOnlyStepsPerClient() {
        Fixture f=Fixture.parse("test/sample",HEAD+"[omron-only: Omron confirms encryption] write unlock 02 00000000000000000000000000000000\nwrite unlock 01 05050505050505050505050505050505\n");
        check(f.steps(Fixture.Client.OMRON).size()==2,"omron sees its only-step");
        check(f.steps(Fixture.Client.OURS_PROTOCOL).size()==1 && f.steps(Fixture.Client.OURS_APP).size()==1,"ours skips omron-only");
        check(f.annotated().size()==1 && "Omron confirms encryption".equals(f.annotated().get(0).reason) && "omron".equals(f.annotated().get(0).only),"annotation recorded");
    }
    private static void rejectsMalformedLinesWithLineNumbers() {
        String head="name test/bad\nalias unlock b305b680-aee7-11e1-a730-0002a5d5c51b\n";
        for(String line:new String[]{"[omron-only] write unlock 01","write nowhere 01","write unlock 012",
            "[ours-only] expect reading user=1 sys=130 dia=78 pulse=67 time=2025-07-09T06:14:38",
            "[omron-only: unsupported annotation] expect ours rejected",
            "[ours-only: not a step or reading] flow pair"}) {
            checks++;
            try { Fixture.parse("test/bad",head+line+"\n"); }
            catch(IllegalArgumentException expected) {
                if(!expected.getMessage().contains("line 3") || !expected.getMessage().contains("test/bad")) throw new AssertionError("message lacks location: "+expected.getMessage());
                continue;
            }
            throw new AssertionError("malformed line accepted: "+line);
        }
    }
    private static void reassemblesLegacyChannelWritesIntoOneFrame() {
        byte[] frame=hex("18010000000010000102030405060708090a0b0c0d0e0f0000");
        frame=Arrays.copyOf(frame,24); frame[0]=24; frame[23]=0; for(int i=0;i<23;i++) frame[23]^=frame[i];
        Fixture f=Fixture.parse("test/sample",HEAD+"write tx "+HexFormat.of().formatHex(frame)+"\nnotify rx "+HexFormat.of().formatHex(frame)+"\n");
        FixtureCursor cursor=new FixtureCursor(f,Fixture.Client.OURS_APP);
        List<UUID> tx=f.aliases.get("tx"),rx=f.aliases.get("rx");
        check(cursor.onWrite(tx.get(1),Arrays.copyOfRange(frame,16,24))==null,"second channel alone is pending");
        check(cursor.pendingNotifications().isEmpty(),"no reply before the frame is complete");
        check(cursor.onWrite(tx.get(0),Arrays.copyOfRange(frame,0,16))==null,"reassembled frame matches");
        List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
        check(replies.size()==2 && replies.get(0).characteristic.equals(rx.get(0)) && replies.get(1).characteristic.equals(rx.get(1)),"reply split over rx channels");
        check(replies.get(0).value.length==16 && replies.get(1).value.length==8,"16-byte channel parts");
        check(cursor.finished(),"all steps consumed");
    }
    private static void strayChannelWriteIsADivergence() {
        byte[] frame=new byte[24]; frame[0]=24; frame[1]=1; for(int i=0;i<23;i++) frame[23]^=frame[i];
        Fixture f=Fixture.parse("test/sample",HEAD+"write tx "+HexFormat.of().formatHex(frame)+"\n");
        FixtureCursor cursor=new FixtureCursor(f,Fixture.Client.OURS_APP);
        List<UUID> tx=f.aliases.get("tx");
        check(cursor.onWrite(tx.get(2),new byte[]{1,2,3})==null,"a channel part is pending until the frame is known");
        check(cursor.onWrite(tx.get(1),Arrays.copyOfRange(frame,16,24))==null,"second part pending");
        Outcome outcome=cursor.onWrite(tx.get(0),Arrays.copyOfRange(frame,0,16));
        check(outcome!=null && outcome.kind==Outcome.Kind.DIVERGED && outcome.detail.contains("channel 2"),"extra channel part beyond the frame: "+outcome);
    }
    private static void incompleteFixturesFailAtParseTime() {
        String noKey=HEAD.replace("key 05050505050505050505050505050505\n","");
        String noTime=HEAD.replace("time 2026-01-02T03:04:05 zone=UTC\n","")+"alias clock 00002a2b-0000-1000-8000-00805f9b34fb\n";
        String badSystemId=HEAD.replace("dis model=HEM-7156T","dis model=HEM-7156T systemid=xyz");
        String longNotify=HEAD+"notify rx "+"00".repeat(65)+"\n";
        for(String[] c:new String[][]{{noKey,"key"},{noTime,"time"},{badSystemId,"systemid"},{longNotify,"rx"}}) {
            checks++;
            try { Fixture.parse("test/sample",c[0]); }
            catch(IllegalArgumentException expected) {
                if(!expected.getMessage().contains("test/sample") || !expected.getMessage().contains(c[1])) throw new AssertionError("unhelpful message: "+expected.getMessage());
                continue;
            }
            throw new AssertionError("incomplete fixture accepted: missing or invalid "+c[1]);
        }
    }
    private static void divergenceReportsStepAndDecodedFields() {
        Fixture f=Fixture.parse("test/sample",HEAD+"write unlock 01 05050505050505050505050505050505\nnotify unlock 8100\nreads 0x98 16 chunk=16\n");
        FixtureCursor cursor=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL);
        check(cursor.onWrite(f.aliases.get("unlock").get(0),hex("0105050505050505050505050505050505"))==null,"unlock matches");
        check(cursor.pendingNotifications().size()==1,"unlock ACK delivered");
        Outcome outcome=cursor.onWrite(f.aliases.get("tx").get(0),Wlp.read(0xa8,16));
        check(outcome!=null && outcome.kind==Outcome.Kind.DIVERGED && outcome.step==2 && outcome.line==13,"divergence at the read step");
        String report=outcome.report();
        check(report.contains("addr=0x000098") && report.contains("addr=0x0000a8"),"report decodes both frames: "+report);
        Outcome stalled=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL).stalled();
        check(stalled.kind==Outcome.Kind.REJECTED && stalled.detail.contains("no further write after step 0"),"stall is a rejection");
    }
    private static void finishComparesReadings() {
        Fixture f=Fixture.parse("test/sample",HEAD+"write unlock 01 05050505050505050505050505050505\nexpect reading user=1 sys=130 dia=78 pulse=67 time=2025-07-09T06:14:38\n");
        FixtureCursor cursor=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL);
        cursor.onWrite(f.aliases.get("unlock").get(0),hex("0105050505050505050505050505050505"));
        Outcome missing=cursor.finish(List.of());
        check(missing.kind==Outcome.Kind.DIVERGED && missing.detail.contains("user=1 sys=130 dia=78 pulse=67"),"missing reading named");
        Fixture.Reading reading=f.readings().get(0);
        check(cursor.finish(List.of(reading)).kind==Outcome.Kind.MATCHES,"matching readings pass");
        FixtureCursor early=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL);
        check(early.finish(List.of(reading)).kind==Outcome.Kind.REJECTED,"unconsumed steps cannot match");
    }
    private static void finishComparesOnlyTheClientsReadings() {
        Fixture f=Fixture.parse("test/sample",HEAD+
            "[ours-only: Omron pairing does not transfer history] expect reading user=1 sys=130 dia=78 pulse=67 time=2025-07-09T06:14:38\n"+
            "[omron-only: synthetic client-specific reading] expect reading user=2 sys=120 dia=80 pulse=70 time=2025-08-01T07:30:00\n"+
            "expect reading user=1 sys=110 dia=70 pulse=60 time=2025-08-02T07:30:00\n");
        Fixture.Reading ours=f.readings().get(0),omron=f.readings().get(1),shared=f.readings().get(2);
        for(Fixture.Client client:Fixture.Client.values()) {
            FixtureCursor cursor=new FixtureCursor(f,client);
            Fixture.Reading own=client==Fixture.Client.OMRON?omron:ours;
            Fixture.Reading other=client==Fixture.Client.OMRON?ours:omron;
            check(cursor.finish(List.of(own,shared)).kind==Outcome.Kind.MATCHES,"client sees its own and shared readings: "+client);
            check(cursor.finish(List.of(shared)).kind==Outcome.Kind.DIVERGED,"missing own reading rejected: "+client);
            check(cursor.finish(List.of(own)).kind==Outcome.Kind.DIVERGED,"missing shared reading rejected: "+client);
            check(cursor.finish(List.of(own,shared,other)).kind==Outcome.Kind.DIVERGED,"another client's reading is unexpected: "+client);
            check(cursor.finish(List.of(own,shared,own)).kind==Outcome.Kind.DIVERGED,"duplicate reading rejected: "+client);
        }
        check(f.annotated().size()==2 && f.annotated().get(0).line==11 &&
            "Omron pairing does not transfer history".equals(f.annotated().get(0).reason),"reading differences are reported with evidence and location");
        Fixture pair=Fixture.parse("test/sample",HEAD+
            "[ours-only: Omron pairing does not transfer history] expect reading user=1 sys=130 dia=78 pulse=67 time=2025-07-09T06:14:38\n");
        check(new FixtureCursor(pair,Fixture.Client.OMRON).finish(List.of()).kind==Outcome.Kind.MATCHES,"Omron pairing may return no readings");
        check(new FixtureCursor(pair,Fixture.Client.OURS_APP).finish(List.of()).kind==Outcome.Kind.DIVERGED,"OpenPressure pairing still requires its reading");
    }
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
}
