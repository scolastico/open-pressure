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
    private static byte[] readResource(String name) {
        try(java.io.InputStream in=FixtureReplayTest.class.getResourceAsStream("/org/openpressure/protocol/fixtures/"+name+".fixture")) { return in.readAllBytes(); }
        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
}
