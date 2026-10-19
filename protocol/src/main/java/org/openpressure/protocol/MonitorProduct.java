package org.openpressure.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display names for verified model identities. Pure data for labels such as the PDF device
 * column; it never selects a memory map, handshake or write permission. Product names are
 * listed only where the repository documents them; other identities show the reported model.
 */
public final class MonitorProduct {
    public final String identity,manufacturer,productName;

    private MonitorProduct(String identity,String manufacturer,String productName) {
        this.identity=identity; this.manufacturer=manufacturer; this.productName=productName;
    }

    private static final Map<String,MonitorProduct> REGISTRY;
    static {
        Map<String,MonitorProduct> map=new LinkedHashMap<>();
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7322T","HEM-7342T","HEM-7361T","HEM-7530T","HEM-7600T","HEM-6232T"})
            map.put(id,new MonitorProduct(id,"OMRON",null));
        map.put("HEM-7380T1",new MonitorProduct("HEM-7380T1","OMRON",null));
        map.put("HEM-7377T1",new MonitorProduct("HEM-7377T1","OMRON","BP5360"));
        map.put("HEM-7380T1-EOSL",new MonitorProduct("HEM-7380T1-EOSL","OMRON","X7 Smart AFib"));
        map.put("HEM-7380T1-EBK",new MonitorProduct("HEM-7380T1-EBK","OMRON","X7 Smart AFib"));
        // Omron Connect's catalog names (docs/compatibility.md).
        map.put("HEM-7196T1-FLE",new MonitorProduct("HEM-7196T1-FLE","OMRON",X7Model.HEM_7196T1_FLE.productName));
        map.put("HEM-7196T1-FLEO",new MonitorProduct("HEM-7196T1-FLEO","OMRON",X7Model.HEM_7196T1_FLEO.productName));
        for(X7Model model:new X7Model[]{X7Model.HEM_7143T1_E,X7Model.HEM_7143T1_D,X7Model.HEM_7143T2_E,X7Model.HEM_7188T1_LE,X7Model.HEM_7188T1_LEO})
            map.put(model.identity,new MonitorProduct(model.identity,"OMRON",model.productName));
        for(String[] row:new String[][]{{"HEM-7151T-Z","BP5250"},{"HEM-7340T-Z","BP7350"},{"HEM-7340T-CA","BP7350CAN"},
                {"HEM-7341T-Z","BP5350"},{"HEM-7343T-Z","BP5450"},{"HEM-6161T-E","RS3 Intelli IT"},{"HEM-6232T-D","RS7 Intelli IT"}})
            map.put(row[0],new MonitorProduct(row[0],"OMRON",row[1]));
        REGISTRY=Collections.unmodifiableMap(map);
    }

    /** Exact identity lookup; null when unlisted. Resolve regional aliases first. */
    public static MonitorProduct lookup(String identity) { return identity==null?null:REGISTRY.get(identity); }

    /**
     * Human-readable monitor name: manufacturer and product name for a known identity, else
     * manufacturer and the reported model. A model that resolves to no listed identity may be
     * an unverified hint, so it is never shown; the Bluetooth name (which may be null) is.
     */
    public static String displayName(String reportedModel,String bluetoothName) {
        String model=reportedModel==null?"":reportedModel.replace("\u0000","").trim();
        if(model.isEmpty()) return bluetoothName;
        MonitorProduct product=lookup(WriteAspectIndex.resolve(model));
        if(product==null) return bluetoothName;
        return product.manufacturer+" "+(product.productName!=null?product.productName:model);
    }
}
