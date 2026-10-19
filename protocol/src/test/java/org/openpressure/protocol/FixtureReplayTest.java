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
        clockSyncOutcomeIsCompared();
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
        // Read-all rule: every slot of the selected users' banks (historyRanges(users)) followed by every unconfirmed
        // bank (HEM-7156T bank 2), without the index. Each connect fixture's OpenPressure steps are replaced by that
        // sweep, written independently as reads lines, and must match; dropping the unconfirmed bank must not.
        for(String name:Fixture.all()) {
            if(!name.endsWith("/connect-read-history")) continue;
            Fixture f=Fixture.load(name);
            if(f.expectRejected(Fixture.Client.OURS_PROTOCOL)) continue;
            VendorMemoryModel model=VendorMemoryModel.identify(f.dis.get("model"));
            for(int users:new int[]{1,2,3}) {
                java.util.List<VendorMemoryModel.Range> sweep=new java.util.ArrayList<>(model.historyRanges(users));
                for(VendorMemoryModel.Range bank:model.historyRanges()) if(model.unconfirmedBank(bank.user) && !sweep.contains(bank)) sweep.add(bank);
                Outcome outcome=ProtocolReplay.run(Fixture.parse(name,readAll(name,model,sweep,users)));
                check(outcome.kind==Outcome.Kind.MATCHES,name+" read-all users="+users+": "+outcome.report());
                if(sweep.size()>model.historyRanges(users).size()) {
                    Outcome without=ProtocolReplay.run(Fixture.parse(name,readAll(name,model,model.historyRanges(users),users)));
                    check(without.kind!=Outcome.Kind.MATCHES,name+" read-all users="+users+" skipped the unconfirmed bank: "+without.report());
                }
            }
        }
        // One user on a two-bank model: a sweep that also reads user 1's bank must diverge.
        String name="hem-7155t/connect-read-history";
        VendorMemoryModel model=VendorMemoryModel.identify("HEM-7155T-EBK");
        Outcome both=ProtocolReplay.run(Fixture.parse(name,readAll(name,model,model.historyRanges(),2)));
        check(both.kind!=Outcome.Kind.MATCHES,"read-all users=2 still read user 1: "+both.report());
    }
    private static void clockSyncOutcomeIsCompared() {
        String text=new String(readResource("hem-7155t/sync-clock"),java.nio.charset.StandardCharsets.UTF_8);
        Outcome wrong=ProtocolReplay.run(Fixture.parse("hem-7155t/sync-clock",text.replace("expect clock acknowledged","expect clock skipped")));
        check(wrong.kind==Outcome.Kind.DIVERGED && wrong.detail.contains("acknowledged"),"clock outcome compared: "+wrong.report());
        // A written clock other than the fixture time + 1 s is a divergence at the write step.
        Outcome late=ProtocolReplay.run(Fixture.parse("hem-7155t/sync-clock",text.replace("time 2026-01-02T03:04:05","time 2026-01-02T03:04:06")));
        check(late.kind==Outcome.Kind.DIVERGED && late.detail.equals("frame differs"),"written clock is the fixture time + 1 s: "+late.report());
    }
    /** The connect fixture with its read steps replaced by full reads of {@code banks} as a read-all flow. */
    private static String readAll(String name,VendorMemoryModel model,java.util.List<VendorMemoryModel.Range> banks,int users) {
        StringBuilder text=new StringBuilder();
        java.util.Set<Integer> read=new java.util.HashSet<>();
        for(VendorMemoryModel.Range bank:banks) read.add(bank.user);
        for(String line:new String(readResource(name),java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            String step=line.replaceFirst("^\\[[^\\]]*\\]\\s*","");
            if(line.startsWith("[omron-only") || step.startsWith("reads ")) continue;
            if(step.startsWith("expect reading user=") && !read.contains(Integer.parseInt(step.replaceFirst("^expect reading user=(\\d+).*$","$1")))) continue;
            if(step.startsWith("write tx 08 0f")) for(VendorMemoryModel.Range bank:banks)
                text.append("reads 0x").append(Integer.toHexString(bank.start)).append(' ').append(bank.length).append(" chunk=").append(VendorWlpSession.recordBlock(model)).append('\n');
            text.append(line).append('\n');
        }
        return text.append("flow read-all\nimport users=").append(users==3?"both":String.valueOf(users)).append('\n').toString();
    }
    private static byte[] readResource(String name) {
        try(java.io.InputStream in=FixtureReplayTest.class.getResourceAsStream("/org/openpressure/protocol/fixtures/"+name+".fixture")) { return in.readAllBytes(); }
        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
}
