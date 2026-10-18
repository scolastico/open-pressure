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
        gate();
        identities();
        return checks;
    }

    private static void statuses() {
        check(WriteAspectIndex.lookup("HEM-7155T",Aspect.CLOCK_BLOCK).status==Status.UNTESTED,"legacy clock block untested");
        check(WriteAspectIndex.lookup("HEM-7380T1-EOSL",Aspect.CLOCK_BLOCK).status==Status.UNTESTED,"X7 standalone clock untested");
        check(WriteAspectIndex.lookup("HEM-7322T",Aspect.CLOCK_BLOCK).status==Status.BLOCKED && WriteAspectIndex.lookup("HEM-7377T1",Aspect.CLOCK_BLOCK).status==Status.BLOCKED,"no clock-block evidence");
        check(WriteAspectIndex.lookup("HEM-7156T-AAP",Aspect.CLOCK_BLOCK).status==Status.BLOCKED && WriteAspectIndex.lookup(null,Aspect.CLOCK_BLOCK).status==Status.BLOCKED,"unknown identities are blocked");
        for(String id:WriteAspectIndex.knownIdentities()) check(WriteAspectIndex.lookup(id,Aspect.UNSENT_COUNTERS).status==Status.BLOCKED,"unsent counters not implemented: "+id);
        check(!WriteAspectIndex.lookup("HEM-7155T",Aspect.CLOCK_BLOCK).evidence.isEmpty(),"evidence text present");
        // Full initial table: every untested entry names the confirmation-run guide.
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T","HEM-7530T","HEM-7600T","HEM-6232T","HEM-7380T1-EOSL","HEM-7380T1-EBK"}) {
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

    private static void gate() {
        check(WriteAspectIndex.permits(Status.CONFIRMED,false) && WriteAspectIndex.permits(Status.UNTESTED,true),"confirmed or overridden untested pass");
        check(!WriteAspectIndex.permits(Status.UNTESTED,false) && !WriteAspectIndex.permits(Status.BLOCKED,true),"untested without override and blocked never pass");
        check(WriteAspectIndex.permits(Status.CONFIRMED,true) && !WriteAspectIndex.permits(Status.BLOCKED,false) && !WriteAspectIndex.permits(null,true),"remaining gate cases");
    }

    private static void identities() {
        List<String> ids=WriteAspectIndex.knownIdentities();
        check(ids.size()==12,"ten legacy identities plus two X7 identities");
        List<String> sorted=new ArrayList<>(ids); java.util.Collections.sort(sorted);
        check(sorted.equals(ids),"identities sorted for the viewer");
        int legacy=0;
        for(String id:ids) {
            VendorMemoryModel model=VendorMemoryModel.identify(id);
            if(model!=null) { check(model.id.equals(id),"legacy key is the verified model id: "+id); legacy++; }
        }
        check(legacy==10 && ids.contains("HEM-7380T1-EOSL") && ids.contains("HEM-7380T1-EBK"),"identity set");
        rejects(() -> ids.add("HEM-0000T"));
    }

    private static void check(boolean ok,String message) { checks++; if(!ok) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch(RuntimeException expected) { return; } throw new AssertionError("expected rejection"); }
}
