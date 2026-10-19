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
        parsesClockSyncFlowAndExpectation();
        parsesMtuAndManufacturerPayload();
        nonceMatchesAnyTokenAndEchoesIt();
        describesX7SystemFrames();
        comparesReadingFlagsOnlyWhenNamed();
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
        check(Fixture.parse("test/sample",HEAD+"flow read-all\n").flow.equals("read-all"),"read-all flow");
        try { Fixture.parse("test/sample",HEAD+"flow sweep\n"); throw new AssertionError("unknown flow accepted"); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().contains("read-all"),"unknown flow rejected"); }
        check(f.importUsers==3,"imports both users unless told otherwise");
        check(Fixture.parse("test/sample",HEAD+"import users=1\n").importUsers==1,"import users=1");
        check(Fixture.parse("test/sample",HEAD+"import users=2\n").importUsers==2,"import users=2");
        check(Fixture.parse("test/sample",HEAD+"import users=both\n").importUsers==3,"import users=both");
        for(String bad:new String[]{"import users=3","import users=0","import user=1","import users=1 extra=2","import"}) {
            try { Fixture.parse("test/sample",HEAD+bad+"\n"); throw new AssertionError("accepted: "+bad); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains("line 11"),"rejects "+bad); }
        }
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
    private static void parsesClockSyncFlowAndExpectation() {
        check(Fixture.parse("test/sample",HEAD).expectClock==null,"no clock expectation unless declared");
        for(String value:new String[]{"acknowledged","skipped","rejected"}) {
            Fixture f=Fixture.parse("test/sample",HEAD+"flow sync-clock\nexpect clock "+value+"\n");
            check(f.flow.equals("sync-clock") && value.equals(f.expectClock),"expect clock "+value);
        }
        // The expectation may precede the flow line; both are checked once the whole fixture is read.
        check(Fixture.parse("test/sample",HEAD+"expect clock skipped\nflow sync-clock\n").expectClock.equals("skipped"),"order-independent");
        String[][] bad={
            {"flow sync-clock\n","expect clock"},
            {"expect clock acknowledged\n","expect clock"},
            {"flow pair\nexpect clock acknowledged\n","expect clock"},
            {"flow sync-clock\nexpect clock written\n","line 12"},
            {"flow sync-clock\nexpect clock\n","line 12"},
            {"flow sync-clock\nexpect clock skipped\nexpect clock skipped\n","line 13"},
            {"flow sync-clock\n[ours-only: not allowed] expect clock skipped\n","line 12"}};
        for(String[] c:bad) {
            checks++;
            try { Fixture.parse("test/sample",HEAD+c[0]); }
            catch(IllegalArgumentException expected) {
                if(!expected.getMessage().contains("test/sample") || !expected.getMessage().contains(c[1])) throw new AssertionError("unhelpful message: "+expected.getMessage());
                continue;
            }
            throw new AssertionError("accepted: "+c[0]);
        }
        try { Fixture.parse("test/sample",HEAD.replace("time 2026-01-02T03:04:05 zone=UTC\n","")+"flow sync-clock\nexpect clock skipped\n"); throw new AssertionError("sync-clock without time accepted"); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().contains("time"),"sync-clock needs the target time"); }
        // The cursor compares the client's clock outcome with the expectation once every step is consumed.
        Fixture f=Fixture.parse("test/sample",HEAD+"flow sync-clock\nexpect clock acknowledged\n");
        check(new FixtureCursor(f,Fixture.Client.OURS_APP).finish(List.of(),"acknowledged").kind==Outcome.Kind.MATCHES,"expected clock outcome matches");
        Outcome wrong=new FixtureCursor(f,Fixture.Client.OURS_APP).finish(List.of(),"rejected");
        check(wrong.kind==Outcome.Kind.DIVERGED && wrong.detail.contains("rejected") && wrong.detail.contains("acknowledged"),"different clock outcome diverges: "+wrong);
        check(new FixtureCursor(f,Fixture.Client.OURS_APP).finish(List.of()).kind==Outcome.Kind.DIVERGED,"a missing clock sync diverges");
        Fixture plain=Fixture.parse("test/sample",HEAD);
        check(new FixtureCursor(plain,Fixture.Client.OURS_APP).finish(List.of(),"acknowledged").kind==Outcome.Kind.DIVERGED,"an unexpected clock sync diverges");
    }
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s); }
    private static void parsesMtuAndManufacturerPayload() {
        check(Fixture.parse("test/sample",HEAD).mtu==23,"the monitor's MTU defaults to the ATT minimum");
        check(Fixture.parse("test/sample",HEAD+"mtu 247\n").mtu==247,"mtu directive");
        for(String bad:new String[]{"mtu 22","mtu 518","mtu"}) {
            try { Fixture.parse("test/sample",HEAD+bad+"\n"); throw new AssertionError("accepted: "+bad); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains("line 11"),"rejects "+bad); }
        }
        check(Fixture.parse("test/sample",HEAD).omronManufacturerPayload()==null,"no manufacturer data");
        String omron=HEAD.replace("services=ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b","services=ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b msd=0e0201010100010100");
        check(Arrays.equals(Fixture.parse("test/sample",omron).omronManufacturerPayload(),hex("01010100010100")),"Android strips the 0x020e company ID");
        check(Fixture.parse("test/sample",omron.replace("msd=0e02","msd=0f02")).omronManufacturerPayload()==null,"other company IDs are not Omron data");
    }
    private static void nonceMatchesAnyTokenAndEchoesIt() {
        // The X7 system channel uses the legacy unlock characteristic's UUID; a fixture names it once.
        String single=HEAD.replace("alias unlock ","alias sys ");
        String x7=single+"nonce sys\nwrite tx 0800000000100018\n";
        Fixture f=Fixture.parse("test/sample",x7);
        List<Fixture.Step> steps=f.steps(Fixture.Client.OMRON);
        check(steps.size()==3 && steps.get(0).kind==Fixture.Step.Kind.WRITE && steps.get(1).kind==Fixture.Step.Kind.NOTIFY,"nonce expands to a write and its reply");
        check(steps.get(0).hex().equals("11????????"+"00".repeat(15)),"token bytes print as ??: "+steps.get(0).hex());
        UUID sys=f.aliases.get("sys").get(0);
        for(String token:new String[]{"00000000","a1b2c3d4"}) {
            FixtureCursor cursor=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL);
            check(cursor.onWrite(sys,hex("11"+token+"00".repeat(15)))==null,"any token matches: "+token);
            List<FixtureCursor.Notification> replies=cursor.pendingNotifications();
            check(replies.size()==1 && replies.get(0).characteristic.equals(sys) && Arrays.equals(replies.get(0).value,hex("9100"+token+"00".repeat(14))),"reply echoes "+token);
        }
        for(String wrong:new String[]{"12a1b2c3d4"+"00".repeat(15),"11a1b2c3d4"+"00".repeat(14)+"01","11a1b2c3d4"+"00".repeat(14)}) {
            Outcome outcome=new FixtureCursor(f,Fixture.Client.OURS_PROTOCOL).onWrite(sys,hex(wrong));
            check(outcome!=null && outcome.kind==Outcome.Kind.DIVERGED && outcome.detail.contains("any value"),"only the token is free: "+outcome);
        }
        try { Fixture.parse("test/sample",HEAD+"nonce tx\n"); throw new AssertionError("nonce on a multi-channel alias accepted"); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().contains("single-characteristic"),"nonce needs one characteristic"); }
        try { Fixture.parse("test/sample",HEAD+"nonce clock\n"); throw new AssertionError("nonce on an unknown alias accepted"); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().contains("unknown alias"),"nonce alias must exist"); }
        Fixture only=Fixture.parse("test/sample",single+"[omron-only: reason] nonce sys\n");
        check(only.steps(Fixture.Client.OMRON).size()==2 && only.steps(Fixture.Client.OURS_APP).isEmpty(),"nonce steps can be annotated");
    }
    private static void describesX7SystemFrames() {
        check(org.openpressure.protocol.fixture.WlpDescribe.describe(hex("11a1b2c3d4"+"00".repeat(15))).equals("x7-transaction nonce=a1b2c3d4"),"nonce request");
        check(org.openpressure.protocol.fixture.WlpDescribe.describe(hex("9100a1b2c3d400"+"00".repeat(13))).startsWith("x7-transaction-reply status=00 nonce=a1b2c3d4"),"nonce reply");
        check(org.openpressure.protocol.fixture.WlpDescribe.describe(hex("2001"+"00".repeat(18))).equals("x7-authorization-poll"),"authorization poll");
        check(org.openpressure.protocol.fixture.WlpDescribe.describe(hex("c00100000000"+"00".repeat(15))).startsWith("x7-encrypted counter=1"),"encrypted frame counter");
        check(org.openpressure.protocol.fixture.WlpDescribe.describe(hex("0800000000100018")).startsWith("start"),"WLP frames keep their description");
    }
    private static void comparesReadingFlagsOnlyWhenNamed() {
        java.time.LocalDateTime t=java.time.LocalDateTime.parse("2025-07-09T06:14:38");
        Fixture f=Fixture.parse("test/sample",HEAD+"expect reading user=1 sys=130 dia=78 pulse=67 time=2025-07-09T06:14:38 irregular=0 afib=1\n");
        Fixture.Reading expected=f.readings().get(0);
        check(expected.irregular==0 && expected.movement==null && expected.afib==1,"optional flags parsed");
        Fixture.Reading plain=new Fixture.Reading(1,130,78,67,t);
        check(!expected.accepts(plain),"a named flag must be reported");
        check(expected.accepts(plain.withFlags(0,1,1)),"unnamed movement is not compared");
        check(!expected.accepts(plain.withFlags(1,0,1)),"irregular compared");
        check(new FixtureCursor(f,Fixture.Client.OURS_APP).finish(List.of(plain.withFlags(0,0,1))).kind==Outcome.Kind.MATCHES,"finish uses the flags");
        check(new FixtureCursor(f,Fixture.Client.OURS_APP).finish(List.of(plain.withFlags(0,0,0))).kind==Outcome.Kind.DIVERGED,"finish reports a flag difference");
        check(Fixture.parse("test/sample",HEAD+"expect reading user=1 sys=130 dia=78 pulse=67 time=2025-07-09T06:14:38\n").readings().get(0).accepts(plain.withFlags(1,1,1)),"flags ignored unless named");
        for(String bad:new String[]{" afib=2"," colour=1"}) {
            try { Fixture.parse("test/sample",HEAD+"expect reading user=1 sys=130 dia=78 pulse=67 time=2025-07-09T06:14:38"+bad+"\n"); throw new AssertionError("accepted"+bad); }
            catch(IllegalArgumentException expectedError) { check(expectedError.getMessage().contains("line 11"),"rejects"+bad); }
        }
    }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
}
