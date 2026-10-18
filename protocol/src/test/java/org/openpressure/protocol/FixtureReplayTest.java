package org.openpressure.protocol;

import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.Outcome;
import org.openpressure.protocol.fixture.ProtocolReplay;

/** Every shared fixture replayed against the Android-independent unlock and vendor read sessions. */
public final class FixtureReplayTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("Fixture replay checks: "+run()); }
    public static int run() {
        checks=0;
        ownFixturesMatchAtProtocolLevel();
        stalledClientIsRejectedNotHung();
        readAllSweepsTheSelectedUsers();
        return checks;
    }
    private static void ownFixturesMatchAtProtocolLevel() {
        check(!Fixture.all().isEmpty(),"fixture index lists fixtures");
        for(String name:Fixture.all()) {
            Fixture f=Fixture.load(name);
            Outcome outcome=ProtocolReplay.run(f);
            Outcome.Kind wanted=f.expectRejected(Fixture.Client.OURS_PROTOCOL)?Outcome.Kind.REJECTED:Outcome.Kind.MATCHES;
            check(outcome.kind==wanted,name+": "+outcome.report());
            // A refused identity must be refused before any byte reaches the monitor.
            if(wanted==Outcome.Kind.REJECTED) check(outcome.step==0,name+" wrote before refusing: "+outcome.report());
        }
    }
    private static void stalledClientIsRejectedNotHung() {
        String text=new String(readResource("hem-7156t/connect-read-history"))+"write tx 08 0f 00 00 00 00 00 07\n";
        Outcome outcome=ProtocolReplay.run(Fixture.parse("hem-7156t/connect-read-history",text));
        check(outcome.kind==Outcome.Kind.REJECTED && outcome.detail.contains("ended at step"),"extra scripted write after END: "+outcome.report());
    }
    private static void readAllSweepsTheSelectedUsers() {
        // Both users: where every bank belongs to a selectable user, the read-all sweep issues the same full-bank
        // reads as a connect history read; otherwise (HEM-7156T's unselectable second bank) it reads fewer banks.
        for(String name:Fixture.all()) {
            if(!name.endsWith("/connect-read-history")) continue;
            Fixture f=Fixture.load(name);
            if(f.expectRejected(Fixture.Client.OURS_PROTOCOL)) continue;
            VendorMemoryModel model=VendorMemoryModel.identify(f.dis.get("model"));
            boolean everyBank=model.historyRanges(3).equals(model.historyRanges());
            Outcome outcome=ProtocolReplay.run(Fixture.parse(name,new String(readResource(name))+"flow read-all\nimport users=both\n"));
            check((outcome.kind==Outcome.Kind.MATCHES)==everyBank,name+" read-all both users: "+outcome.report());
        }
        // One user on a two-bank model: the sweep must skip user 1's bank, so the scripted user-1 reads diverge.
        String name="hem-7155t/connect-read-history";
        Outcome only2=ProtocolReplay.run(Fixture.parse(name,new String(readResource(name))+"flow read-all\nimport users=2\n"));
        check(only2.kind!=Outcome.Kind.MATCHES,"read-all users=2 still read user 1: "+only2.report());
    }
    private static byte[] readResource(String name) {
        try(java.io.InputStream in=FixtureReplayTest.class.getResourceAsStream("/org/openpressure/protocol/fixtures/"+name+".fixture")) { return in.readAllBytes(); }
        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
}
