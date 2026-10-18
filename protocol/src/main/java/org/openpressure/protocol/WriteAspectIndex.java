package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-model record of whether a memory-write aspect is confirmed on hardware, untested or
 * blocked. Pure data: it builds no frame and authorizes nothing by itself; every outgoing
 * byte still passes the model-bound command policy. Identities are exact verified model
 * strings (VendorMemoryModel.id, or the X7 identities); anything unlisted is blocked.
 */
public final class WriteAspectIndex {
    public enum Aspect { CLOCK_BLOCK, UNSENT_COUNTERS }
    public enum Status { CONFIRMED, UNTESTED, BLOCKED }

    public static final class Entry {
        public final Status status;
        public final String evidence;
        private Entry(Status status,String evidence) { this.status=status; this.evidence=evidence; }
    }

    private static final Entry UNKNOWN=new Entry(Status.BLOCKED,"no evidence for this identity");
    private static final Entry NOT_IMPLEMENTED=new Entry(Status.BLOCKED,"not implemented");
    private static final Entry CLOCK_UNTESTED=new Entry(Status.UNTESTED,
        "no confirmation run on this model yet; see docs/hardware-validation.md");
    private static final Entry CLOCK_NO_EVIDENCE=new Entry(Status.BLOCKED,
        "no documented clock-block layout for this model; see docs/safety.md");
    private static final Map<String,Map<Aspect,Entry>> TABLE;
    private static final List<String> IDENTITIES;

    static {
        Map<String,Map<Aspect,Entry>> table=new HashMap<>();
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T",
                "HEM-7530T","HEM-7600T","HEM-6232T","HEM-7380T1-EOSL","HEM-7380T1-EBK"})
            put(table,id,CLOCK_UNTESTED);
        for(String id:new String[]{"HEM-7322T","HEM-7377T1"}) put(table,id,CLOCK_NO_EVIDENCE);
        TABLE=Collections.unmodifiableMap(table);
        List<String> ids=new ArrayList<>(table.keySet());
        Collections.sort(ids);
        IDENTITIES=Collections.unmodifiableList(ids);
    }

    private WriteAspectIndex() {}

    private static void put(Map<String,Map<Aspect,Entry>> table,String id,Entry clock) {
        Map<Aspect,Entry> aspects=new EnumMap<>(Aspect.class);
        aspects.put(Aspect.CLOCK_BLOCK,clock);
        aspects.put(Aspect.UNSENT_COUNTERS,NOT_IMPLEMENTED);
        table.put(id,Collections.unmodifiableMap(aspects));
    }

    /** Never null. Exact identity match only; regional aliases must be resolved by the caller first. */
    public static Entry lookup(String modelIdentity,Aspect aspect) {
        if(aspect==null) throw new IllegalArgumentException("write aspect required");
        Map<Aspect,Entry> aspects=modelIdentity==null?null:TABLE.get(modelIdentity);
        Entry entry=aspects==null?null:aspects.get(aspect);
        return entry==null?UNKNOWN:entry;
    }

    /** CONFIRMED passes; UNTESTED only with this request's explicit override; BLOCKED never. */
    public static boolean permits(Status status,boolean override) {
        return status==Status.CONFIRMED || (status==Status.UNTESTED && override);
    }

    /** Sorted, immutable list of listed identities, for the viewer. */
    public static List<String> knownIdentities() { return IDENTITIES; }
}
