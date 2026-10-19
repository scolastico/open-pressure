package org.openpressure.protocol;

import java.time.LocalDateTime;
import java.util.Arrays;

/** Literal wire examples follow the original single-access command layout. */
public final class X7RegistrationTest {
    private static int checks;
    private static final String MODEL="HEM-7380T1-EOSL";
    private static final LocalDateTime NOW=LocalDateTime.of(2026,10,16,14,30,45);
    private static final String PREIMAGE="63000b00038002000102030405060708090a0b0c0d0e0f105a0611a5123456789abc6203095afedcba987654a0b1c2d302e5f60718090a0b0c0d19992132435465768798";
    private static final String UPDATED="63000b00038002000102030405060708090a0b0c0d0e0f105a0611a5123456789abc6203095afedcba987654a0b1c2d303e5f6071a0a100e1e2e59992132435465768798";
    private static final String PREFIX="2001c000541863000b00038002000102030405060708090a0b0c0d0e0f100054";
    private static final String SETTINGS="1801c0008010a0b1c2d303e5f6071a0a100e1e2e599900a0";
    private static final String PREFIX_ACK="0881c00054180005", SETTINGS_ACK="0881c000801000d9";

    public static int run() {
        checks=0;
        preparationPreservesUnselectedSettings();
        authorizationBoundsEveryWrite();
        acknowledgedWritesCompleteWithoutEnd();
        echoedWriteAcknowledgments();
        invalidInputNeverCreatesARegistration();
        invalidAcknowledgmentsRevokeAuthorization();
        identityAndOverrideGateTheWrite();
        return checks;
    }

    private static X7Registration registration() {
        return new X7Registration(MODEL,hex(PREIMAGE),1,NOW);
    }

    private static void preparationPreservesUnselectedSettings() {
        byte[] supplied=hex(PREIMAGE);
        X7Registration r=new X7Registration(MODEL,supplied,1,NOW);
        supplied[0]=0;
        same(PREIMAGE,r.backup()); same(UPDATED,r.updated());
        byte[] backup=r.backup(),updated=r.updated();
        backup[1]=99; updated[48]=0;
        same(PREIMAGE,r.backup()); same(UPDATED,r.updated());
        check(r.user()==1,"selected user retained");
        byte[] before=r.backup(),after=r.updated();
        for(int i=0;i<before.length;i++) {
            if(i!=48 && (i<52 || i>58))
                check(before[i]==after[i],"only selected flag, clock and checksum may change at "+i);
        }
        check((after[48]&2)==(before[48]&2),"user 1 registration preserves user 2 flag");
        check(Arrays.equals(Arrays.copyOf(before,24),Arrays.copyOf(after,24)),"all pointers and unread counts preserved");
        X7MemoryIndex.parse(after);
        before[48]=1; checksum(before);
        X7Registration second=new X7Registration("X7 Smart AFib",before,2,NOW);
        check(second.user()==2 && second.updated()[48]==3,"user 2 sets its own bit and preserves user 1 flag");
        before[48]=(byte)0xfc; checksum(before);
        second=new X7Registration(MODEL,before,2,NOW);
        check((second.updated()[48]&255)==0xfe,"reserved flag bits stay unchanged");
        X7Registration rollover=new X7Registration(MODEL,hex(PREIMAGE),1,LocalDateTime.of(2024,12,31,23,59,59));
        check(Arrays.equals(hex("190101000000"),Arrays.copyOfRange(rollover.updated(),52,58)),"clock offset handles year rollover");
        X7Registration lower=new X7Registration(MODEL,hex(PREIMAGE),1,LocalDateTime.of(2024,1,1,0,0));
        check(lower.updated()[52]==24 && lower.updated()[57]==1,"clock accepts model lower year");
        X7Registration upper=new X7Registration(MODEL,hex(PREIMAGE),1,LocalDateTime.of(2050,12,31,23,59,58));
        check(upper.updated()[52]==50 && upper.updated()[57]==59,"clock accepts final representable second");
    }

    private static void authorizationBoundsEveryWrite() {
        X7Registration r=registration();
        check(!r.complete() && !r.allows(hex(PREFIX)),"preparation grants no write permit");
        rejects(r::command);
        check(!r.allows(null) && !r.allows(new byte[0]),"empty values never authorized");
        r.authorize();
        check(!r.allows(hex(PREFIX)),"authorization alone cannot permit a frame before command issuance");
        same(PREFIX,r.command());
        check(r.allows(hex(PREFIX)) && !r.allows(hex(SETTINGS)),"only current prefix frame authorized");
        byte[] changed=r.command(); changed[6]^=1;
        check(!r.allows(changed),"modified data cannot use permit");
        check(!r.allows(hex("080f000000000007")),"registration never authorizes END");
        check(!r.allows(hex("0801000010100009")),"registration never authorizes a read");
        r.cancel();
        check(!r.allows(hex(PREFIX)) && !r.complete(),"cancel revokes current permit");
        rejects(r::command); rejects(r::authorize); rejects(() -> r.accept(hex(PREFIX_ACK)));
        X7Registration early=registration(); early.cancel(); rejects(early::authorize);
        X7Registration unsolicited=registration(); unsolicited.authorize();
        rejects(() -> unsolicited.accept(hex(PREFIX_ACK)));
        check(!unsolicited.allows(hex(PREFIX)),"unsolicited ACK revokes permission");
    }

    private static void acknowledgedWritesCompleteWithoutEnd() {
        X7Registration r=registration(); r.authorize();
        same(PREFIX,r.command());
        check(r.command().length==32,"prefix fits ATT MTU 35");
        check(!r.accept(hex("0881c0")) && !r.complete(),"partial prefix ACK cannot advance");
        same(PREFIX,r.command());
        check(r.accept(hex("0054180005")),"remaining prefix ACK advances exactly once");
        same(SETTINGS,r.command());
        check(!r.allows(hex(PREFIX)) && r.allows(hex(SETTINGS)),"old frame permit revoked after its ACK");
        check(r.command().length==24,"settings frame stays bounded");
        check(!r.accept(hex("08")),"one byte ACK fragment held");
        check(r.accept(hex("81c000801000d9")) && r.complete(),"only both valid ACKs complete registration writes");
        check(!r.allows(hex(SETTINGS)) && !r.allows(hex(PREFIX)),"completion revokes every write permit");
        rejects(r::command); rejects(r::authorize); rejects(() -> r.accept(hex(SETTINGS_ACK)));
    }

    private static void echoedWriteAcknowledgments() {
        // Same format as the supplied 0.1.9 capture, with the existing synthetic pointer/settings data.
        String prefixEcho="2081c000541863000b00038002000102030405060708090a0b0c0d0e0f1000d4";
        String settingsEcho="1881c0008010a0b1c2d303e5f6071a0a100e1e2e59990020";
        for(int split=1;split<32;split++) {
            X7Registration r=registration(); r.authorize(); r.command();
            byte[] reply=hex(prefixEcho);
            check(!r.accept(Arrays.copyOfRange(reply,0,split)),"partial echoed write cannot advance at "+split);
            check(r.allows(hex(PREFIX)),"partial echo retains only the current write permit");
            check(r.accept(Arrays.copyOfRange(reply,split,reply.length)),"full captured-shape echo acknowledges prefix");
            same(SETTINGS,r.command());
            check(r.accept(hex(settingsEcho)) && r.complete(),"both validated echoes complete the writes");
        }
        X7Registration mixed=registration(); mixed.authorize(); mixed.command();
        mixed.accept(hex(prefixEcho)); mixed.command();
        check(mixed.accept(hex(SETTINGS_ACK)) && mixed.complete(),"echo and compact ACK may be mixed");
        for(int offset:new int[]{6,15,29}) {
            X7Registration r=registration(); r.authorize(); r.command();
            byte[] corrupted=hex(prefixEcho); corrupted[offset]^=1; corrupted[corrupted.length-1]^=1;
            rejects(() -> r.accept(corrupted));
            check(!r.allows(hex(PREFIX)),"valid BCC cannot excuse an altered payload echo");
        }
        for(String value:new String[]{
                "2081c000541863000b00038002000102030405060708090a0b0c0d0e0f10e337",
                "2081c000541863000b00038002000102030405060708090a0b0c0d0e0f1000d5",
                "2081c000541863000b00038002000102030405060708090a0b0c0d0e0f1000d400"}) {
            X7Registration r=registration(); r.authorize(); r.command();
            rejects(() -> r.accept(hex(value)));
        }
        X7Registration truncated=registration(); truncated.authorize(); truncated.command();
        check(!truncated.accept(hex("2081c00054180005")) && !truncated.complete(),"declared echo cannot be acknowledged by an eight-byte prefix alone");
        check(!truncated.allows(hex(SETTINGS)),"incomplete echo never permits second write");
    }

    private static void invalidInputNeverCreatesARegistration() {
        for(String model:new String[]{null,"","HEM-7380T1","hem-7380t1-ebk","HEM-7155T","X7","X7 Smart AFib other"}) {
            rejects(() -> new X7Registration(model,hex(PREIMAGE),1,NOW));
            rejects(() -> new X7Registration(model,hex(PREIMAGE),1,NOW,true));
        }
        for(int user:new int[]{-1,0,3,255}) rejects(() -> new X7Registration(MODEL,hex(PREIMAGE),user,NOW));
        rejects(() -> new X7Registration(MODEL,null,1,NOW));
        for(int size:new int[]{0,67,69,65536}) rejects(() -> new X7Registration(MODEL,new byte[size],1,NOW));
        byte[] bad=hex(PREIMAGE); bad[58]^=1;
        rejects(() -> new X7Registration(MODEL,bad,1,NOW));
        byte[] pointer=hex(PREIMAGE); pointer[0]=101;
        rejects(() -> new X7Registration(MODEL,pointer,1,NOW));
        rejects(() -> new X7Registration(MODEL,hex(PREIMAGE),1,null));
        for(LocalDateTime time:new LocalDateTime[]{LocalDateTime.of(2023,12,31,23,59,59),LocalDateTime.of(2050,12,31,23,59,59),LocalDateTime.of(2051,1,1,0,0),LocalDateTime.MAX})
            rejects(() -> new X7Registration(MODEL,hex(PREIMAGE),1,time));
    }

    /** The plan is bound to an exact X7 identity; the write-aspect gate runs before the live permit. */
    private static void identityAndOverrideGateTheWrite() {
        java.util.UUID service=java.util.UUID.fromString("0000fe4a-0000-1000-8000-00805f9b34fb"),tx=java.util.UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b");
        X7Registration eosl=new X7Registration("X7 Smart AFib",hex(PREIMAGE),1,NOW);
        check("HEM-7380T1-EOSL".equals(eosl.identity()) && !eosl.override(),"firmware name binds the EOSL identity without override");
        eosl.authorize(); byte[] first=eosl.command();
        DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,first,eosl);
        checks++;
        X7Registration ebk=new X7Registration("HEM-7380T1-EBK\u0000",hex(PREIMAGE),1,NOW);
        check("HEM-7380T1-EBK".equals(ebk.identity()) && !ebk.override(),"EBK binds its own identity");
        check(Arrays.equals(new X7Registration(MODEL,hex(PREIMAGE),1,NOW).updated(),ebk.updated()),"EBK plans the same bytes as the EOSL");
        ebk.authorize(); byte[] ebkFirst=ebk.command();
        rejects(() -> DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,ebkFirst,ebk));
        check(ebk.allows(ebkFirst),"the untested gate rejects before the permit is touched");
        X7Registration forced=new X7Registration("HEM-7380T1-EBK",hex(PREIMAGE),1,NOW,true);
        check(forced.override(),"override carried by the plan");
        forced.authorize(); byte[] forcedFirst=forced.command();
        same(PREFIX,forcedFirst);
        DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,forcedFirst,forced);
        checks++;
        rejects(() -> DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,hex(SETTINGS),forced));
        rejects(() -> DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_SECURE_WLP,service,tx,forcedFirst,forced));
        X7Registration eoslForced=new X7Registration(MODEL,hex(PREIMAGE),1,NOW,true);
        eoslForced.authorize();
        DeviceWritePolicy.validateX7Registration(ProtocolProfile.X7_WLP,service,tx,eoslForced.command(),eoslForced);
        checks++;
    }

    private static void invalidAcknowledgmentsRevokeAuthorization() {
        // Headers/BCC are independent literal reply examples; matching BCC does not excuse incorrect echoes/status.
        for(String ack:new String[]{"0981c00054180004","0880c00054180004","08810000541800c5","0881c10054180004","0881c00154180004","0881c00055180004","0881c0005410000d","0881c0005418e3e6","0881c00054180004","0881c0005418000500"}) {
            X7Registration r=registration(); r.authorize(); r.command();
            rejects(() -> r.accept(hex(ack)));
            check(!r.allows(hex(PREFIX)) && !r.complete(),"invalid ACK permanently revokes permit: "+ack);
            rejects(r::authorize); rejects(r::command);
        }
        for(byte[] ack:new byte[][]{null,new byte[0],new byte[100000]}) {
            X7Registration r=registration(); r.authorize(); r.command();
            rejects(() -> r.accept(ack)); check(!r.allows(hex(PREFIX)),"bad fragment revokes permit");
        }
        X7Registration r=registration(); r.authorize(); r.command(); r.accept(hex("0881"));
        rejects(() -> r.accept(hex("c0005418000500")));
        check(!r.allows(hex(PREFIX)),"overlong combined ACK revokes permit");
        X7Registration wrongStage=registration(); wrongStage.authorize(); wrongStage.command(); wrongStage.accept(hex(PREFIX_ACK)); wrongStage.command();
        rejects(() -> wrongStage.accept(hex(PREFIX_ACK)));
        check(!wrongStage.allows(hex(SETTINGS)),"stale first ACK cannot acknowledge second write");
        X7Registration unauthorized=registration(); rejects(() -> unauthorized.accept(hex(PREFIX_ACK))); rejects(unauthorized::authorize);
    }

    private static void checksum(byte[] bytes) {
        int sum=0; for(int i=44;i<58;i++) sum+=bytes[i]&255; bytes[58]=(byte)sum;
    }
    private static byte[] hex(String value) { return java.util.HexFormat.of().parseHex(value); }
    private static void same(String expected,byte[] actual) { check(Arrays.equals(hex(expected),actual),"wire/preimage mismatch: "+expected); }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("unsafe or invalid registration operation accepted");
    }
}
