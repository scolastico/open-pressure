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
    public enum Aspect { CLOCK_BLOCK, UNSENT_COUNTERS, REGISTRATION }
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
    private static final Entry CLOCK_CONFIRMED_X7_EOSL=new Entry(Status.CONFIRMED,
        "confirmed 2026-10-18: acknowledged clock-block write on X7 Smart AFib (HEM-7380T1-EOSL, firmware SI.W03.00.03), app 1.2.1; see docs/hardware-validation.md");
    private static final Entry CLOCK_NO_EVIDENCE=new Entry(Status.BLOCKED,
        "no documented clock-block layout for this model; see docs/safety.md");
    private static final Entry REGISTRATION_CONFIRMED_X7_EOSL=new Entry(Status.CONFIRMED,
        "tested registration including its clock on the maintainer's cuff; see docs/hardware-validation.md");
    private static final Entry REGISTRATION_UNTESTED_X7_EBK=new Entry(Status.UNTESTED,
        "identical EOSL/EBK configuration except model_id, no run yet; see docs/hardware-validation.md");
    private static final Entry NO_REGISTRATION=new Entry(Status.BLOCKED,"no registration workflow for this identity");
    private static final Map<String,Map<Aspect,Entry>> TABLE;
    private static final List<String> IDENTITIES;

    static {
        Map<String,Map<Aspect,Entry>> table=new HashMap<>();
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7342T","HEM-7361T",
                "HEM-7530T","HEM-7600T","HEM-6232T"})
            put(table,id,CLOCK_UNTESTED,NO_REGISTRATION);
        put(table,"HEM-7380T1-EBK",CLOCK_UNTESTED,REGISTRATION_UNTESTED_X7_EBK);
        put(table,"HEM-7380T1-EOSL",CLOCK_CONFIRMED_X7_EOSL,REGISTRATION_CONFIRMED_X7_EOSL);
        for(String id:new String[]{"HEM-7322T","HEM-7377T1"}) put(table,id,CLOCK_NO_EVIDENCE,NO_REGISTRATION);
        TABLE=Collections.unmodifiableMap(table);
        List<String> ids=new ArrayList<>(table.keySet());
        Collections.sort(ids);
        IDENTITIES=Collections.unmodifiableList(ids);
    }

    private WriteAspectIndex() {}

    /** Every row carries every aspect: the clock block, the unimplemented unsent counters and profile registration. */
    private static void put(Map<String,Map<Aspect,Entry>> table,String id,Entry clock,Entry registration) {
        Map<Aspect,Entry> aspects=new EnumMap<>(Aspect.class);
        aspects.put(Aspect.CLOCK_BLOCK,clock);
        aspects.put(Aspect.UNSENT_COUNTERS,NOT_IMPLEMENTED);
        aspects.put(Aspect.REGISTRATION,registration);
        table.put(id,Collections.unmodifiableMap(aspects));
    }

    /**
     * Maps a verified model string to its index identity. NULs and surrounding whitespace are
     * removed; Device Information names resolve through {@link VendorMemoryModel#identify} to
     * the table id; "X7 Smart AFib" (any case) is the firmware name of HEM-7380T1-EOSL, matching
     * {@link DeviceReport#verifiedModel}. Anything else, including a bare "HEM-7380T1", is
     * returned unchanged (null stays null) so {@link #lookup} blocks it.
     */
    public static String resolve(String verifiedModel) {
        if(verifiedModel==null) return null;
        String model=verifiedModel.replace("\u0000","").trim();
        if(model.equalsIgnoreCase("X7 Smart AFib")) return "HEM-7380T1-EOSL";
        if(model.equals("HEM-7380T1-EOSL") || model.equals("HEM-7380T1-EBK")) return model;
        VendorMemoryModel known=VendorMemoryModel.identify(model);
        return known!=null?known.id:verifiedModel;
    }

    /** Never null. Exact identity match only; regional aliases must be resolved first (see {@link #resolve}). */
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
