package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.List;
import org.openpressure.protocol.WriteAspectIndex.Aspect;
import org.openpressure.protocol.WriteAspectIndex.Status;

/** Pure-data checks: the index authorizes nothing by itself and sends no bytes. */
public final class WriteAspectIndexTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("Write aspect index checks: "+run()); }
    public static int run() {
        checks=0;
        statuses();
        registration();
        gate();
        identities();
        resolution();
        return checks;
    }

    private static void statuses() {
        check(WriteAspectIndex.lookup("HEM-7155T",Aspect.CLOCK_BLOCK).status==Status.UNTESTED,"legacy clock block untested");
        WriteAspectIndex.Entry eosl=WriteAspectIndex.lookup("HEM-7380T1-EOSL",Aspect.CLOCK_BLOCK);
        check(eosl.status==Status.CONFIRMED && eosl.evidence.contains("docs/hardware-validation.md") && eosl.evidence.contains("2026-10-18"),"X7 EOSL standalone clock confirmed with evidence");
        check(WriteAspectIndex.lookup("HEM-7380T1-EBK",Aspect.CLOCK_BLOCK).status==Status.UNTESTED,"X7 EBK standalone clock still untested");
        for(Aspect aspect:Aspect.values()) {
            int confirmed=0;
            for(String id:WriteAspectIndex.knownIdentities()) if(WriteAspectIndex.lookup(id,aspect).status==Status.CONFIRMED) confirmed++;
            check(confirmed==(aspect==Aspect.UNSENT_COUNTERS?0:1),"exactly one confirmed entry for "+aspect);
        }
        check(WriteAspectIndex.lookup("HEM-7322T",Aspect.CLOCK_BLOCK).status==Status.BLOCKED && WriteAspectIndex.lookup("HEM-7377T1",Aspect.CLOCK_BLOCK).status==Status.BLOCKED,"no clock-block evidence");
        check(WriteAspectIndex.lookup("HEM-7156T-AAP",Aspect.CLOCK_BLOCK).status==Status.BLOCKED && WriteAspectIndex.lookup(null,Aspect.CLOCK_BLOCK).status==Status.BLOCKED,"unknown identities are blocked");
        for(String id:WriteAspectIndex.knownIdentities()) check(WriteAspectIndex.lookup(id,Aspect.UNSENT_COUNTERS).status==Status.BLOCKED,"unsent counters not implemented: "+id);
        check(!WriteAspectIndex.lookup("HEM-7155T",Aspect.CLOCK_BLOCK).evidence.isEmpty(),"evidence text present");
        // Full table apart from the confirmed EOSL: every untested entry names the confirmation-run guide.
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T","HEM-7530T","HEM-7600T","HEM-6232T","HEM-7380T1-EBK","HEM-7196T1-FLE","HEM-7196T1-FLEO"}) {
            WriteAspectIndex.Entry entry=WriteAspectIndex.lookup(id,Aspect.CLOCK_BLOCK);
            check(entry.status==Status.UNTESTED && entry.evidence.contains("docs/hardware-validation.md"),"untested clock block with evidence: "+id);
        }
        check(WriteAspectIndex.lookup("HEM-7322T",Aspect.CLOCK_BLOCK).evidence.contains("docs/safety.md"),"blocked entry names the safety rule");
        check(WriteAspectIndex.lookup("HEM-7155T",Aspect.UNSENT_COUNTERS).evidence.equals("not implemented"),"unsent counters evidence");
        for(String id:new String[]{null,"","HEM-7155T-EBK"," HEM-7155T","hem-7155t","HEM-7380T1","BP5360"}) {
            for(Aspect aspect:Aspect.values()) {
                WriteAspectIndex.Entry entry=WriteAspectIndex.lookup(id,aspect);
                check(entry.status==Status.BLOCKED && entry.evidence.equals("no evidence for this identity"),"unlisted identity blocked: "+id+" "+aspect);
            }
        }
        rejects(() -> WriteAspectIndex.lookup("HEM-7155T",null));
    }

    private static void registration() {
        WriteAspectIndex.Entry eosl=WriteAspectIndex.lookup("HEM-7380T1-EOSL",Aspect.REGISTRATION);
        check(eosl.status==Status.CONFIRMED && eosl.evidence.equals("tested registration including its clock on the maintainer's cuff; see docs/hardware-validation.md"),"EOSL registration confirmed with evidence");
        WriteAspectIndex.Entry ebk=WriteAspectIndex.lookup("HEM-7380T1-EBK",Aspect.REGISTRATION);
        check(ebk.status==Status.UNTESTED && ebk.evidence.equals("identical EOSL/EBK configuration except model_id, no run yet; see docs/hardware-validation.md"),"EBK registration untested with evidence");
        for(String id:new String[]{"HEM-7196T1-FLE","HEM-7196T1-FLEO"}) {
            WriteAspectIndex.Entry entry=WriteAspectIndex.lookup(id,Aspect.REGISTRATION);
            check(entry.status==Status.UNTESTED && entry.evidence.contains("HEM-7380T1-EOSL settings write layout") && entry.evidence.contains("no run yet; see docs/hardware-validation.md"),"7196T1 registration untested with evidence: "+id);
        }
        for(String id:WriteAspectIndex.knownIdentities()) {
            if(id.startsWith("HEM-7380T1-") || id.startsWith("HEM-7196T1-")) continue;
            WriteAspectIndex.Entry entry=WriteAspectIndex.lookup(id,Aspect.REGISTRATION);
            check(entry.status==Status.BLOCKED && entry.evidence.equals("no registration workflow for this identity"),"no registration workflow: "+id);
        }
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve("X7 Smart AFib"),Aspect.REGISTRATION).status==Status.CONFIRMED,"firmware name resolves to the confirmed registration");
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve("HEM-7380T1"),Aspect.REGISTRATION).status==Status.BLOCKED,"bare X7 hint cannot register");
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve("HEM-7196T1"),Aspect.REGISTRATION).status==Status.BLOCKED,"bare 7196T1 hint cannot register");
        // Every listed row carries all three aspects.
        for(String id:WriteAspectIndex.knownIdentities()) for(Aspect aspect:Aspect.values())
            check(!WriteAspectIndex.lookup(id,aspect).evidence.equals("no evidence for this identity"),"row has aspect "+aspect+": "+id);
        check(Aspect.values().length==3,"three write aspects");
    }

    private static void gate() {
        check(WriteAspectIndex.permits(Status.CONFIRMED,false) && WriteAspectIndex.permits(Status.UNTESTED,true),"confirmed or overridden untested pass");
        check(!WriteAspectIndex.permits(Status.UNTESTED,false) && !WriteAspectIndex.permits(Status.BLOCKED,true),"untested without override and blocked never pass");
        check(WriteAspectIndex.permits(Status.CONFIRMED,true) && !WriteAspectIndex.permits(Status.BLOCKED,false) && !WriteAspectIndex.permits(null,true),"remaining gate cases");
    }

    private static void identities() {
        List<String> ids=WriteAspectIndex.knownIdentities();
        check(ids.size()==14,"ten legacy identities plus four X7-family identities");
        List<String> sorted=new ArrayList<>(ids); java.util.Collections.sort(sorted);
        check(sorted.equals(ids),"identities sorted for the viewer");
        int legacy=0;
        for(String id:ids) {
            VendorMemoryModel model=VendorMemoryModel.identify(id);
            if(model!=null) { check(model.id.equals(id),"legacy key is the verified model id: "+id); legacy++; }
        }
        check(legacy==10 && ids.contains("HEM-7380T1-EOSL") && ids.contains("HEM-7380T1-EBK") && ids.contains("HEM-7196T1-FLE") && ids.contains("HEM-7196T1-FLEO"),"identity set");
        rejects(() -> ids.add("HEM-0000T"));
    }

    private static void resolution() {
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve("HEM-7155T-EBK"),Aspect.CLOCK_BLOCK).status==Status.UNTESTED,"regional legacy name resolves");
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve("x7 smart afib"),Aspect.CLOCK_BLOCK).status==Status.CONFIRMED,"X7 firmware name resolves");
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve("HEM-7380T1"),Aspect.CLOCK_BLOCK).status==Status.BLOCKED,"bare X7 hint stays blocked");
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve("HEM-7156T-AAP"),Aspect.CLOCK_BLOCK).status==Status.BLOCKED,"unmapped regional name stays blocked");
        check(WriteAspectIndex.lookup(WriteAspectIndex.resolve(null),Aspect.CLOCK_BLOCK).status==Status.BLOCKED,"null stays blocked");
        check(WriteAspectIndex.resolve(null)==null,"null stays null");
        check("HEM-7380T1-EOSL".equals(WriteAspectIndex.resolve(" X7 Smart AFib\u0000")),"NULs and whitespace trimmed");
        check("HEM-7380T1-EBK".equals(WriteAspectIndex.resolve("HEM-7380T1-EBK\u0000")) && "HEM-7380T1-EOSL".equals(WriteAspectIndex.resolve("HEM-7380T1-EOSL")),"X7 identities pass through");
        check("HEM-7155T".equals(WriteAspectIndex.resolve("HEM-7155T\u0000 ")),"legacy table id");
        check("HEM-7380T1".equals(WriteAspectIndex.resolve("HEM-7380T1")) && "BP5360".equals(WriteAspectIndex.resolve("BP5360")),"unknown input unchanged");
        for(String id:WriteAspectIndex.knownIdentities()) check(id.equals(WriteAspectIndex.resolve(id)),"listed identity resolves to itself: "+id);
        check("HEM-7196T1-FLEO".equals(WriteAspectIndex.resolve("HEM-7196T1-FLEO\u0000")),"7196T1 identities pass through");
        for(String hint:new String[]{"HEM-7196T1","HEM-7196T1-FL","hem-7196t1-fle","M4 Connect","M4 Connect AFib","X4 Connect AFib","X4 Connect AFib 2"}) {
            check(hint.equals(WriteAspectIndex.resolve(hint)),"unknown 7196T1 spelling unchanged: "+hint);
            for(Aspect aspect:Aspect.values()) check(WriteAspectIndex.lookup(WriteAspectIndex.resolve(hint),aspect).status==Status.BLOCKED,"unknown 7196T1 spelling blocked: "+hint);
        }
    }

    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(RuntimeException expected) { return; } throw new AssertionError("expected rejection"); }
}
