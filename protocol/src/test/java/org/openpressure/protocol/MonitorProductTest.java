package org.openpressure.protocol;

/** Display names only; the registry never authorizes a model's memory map or writes. */
public final class MonitorProductTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("Monitor product checks: "+run()); }
    public static int run() {
        checks=0;
        names();
        registry();
        return checks;
    }

    private static void names() {
        check("OMRON X7 Smart AFib".equals(MonitorProduct.displayName("HEM-7380T1-EOSL","BLEsmart_0000")),"EOSL product name");
        check("OMRON X7 Smart AFib".equals(MonitorProduct.displayName("HEM-7380T1-EBK",null)),"EBK product name");
        check("OMRON X7 Smart AFib".equals(MonitorProduct.displayName("x7 smart afib","BLEsmart_0000")),"firmware name resolves");
        check("OMRON BP5360".equals(MonitorProduct.displayName("HEM-7377T1-ZAZ","BLEsmart_0000")),"BP5360 product name");
        check("OMRON HEM-7155T-EBK".equals(MonitorProduct.displayName("HEM-7155T-EBK","BLEsmart_0000")),"regional model kept as reported");
        check("OMRON HEM-7155T-EBK".equals(MonitorProduct.displayName(" HEM-7155T-EBK\u0000","BLEsmart_0000")),"reported model trimmed");
        check("OMRON HEM-7156T".equals(MonitorProduct.displayName("HEM-7156T",null)),"table id");
        check("OMRON HEM-7380T1".equals(MonitorProduct.displayName("HEM-7380T1","BLEsmart_0000")),"bare X7 identity without product name");
        check("BLEsmart_0000".equals(MonitorProduct.displayName("Some Cuff 9","BLEsmart_0000")),"unresolved model: Bluetooth name");
        check("BLEsmart_0000".equals(MonitorProduct.displayName("X7 Smart AFib \u00b7 HEM-7380T1","BLEsmart_0000")),"model hint never printed");
        check(MonitorProduct.displayName("HEM-7155T-EBX",null)==null,"similar name unresolved");
        check("BLEsmart_0000".equals(MonitorProduct.displayName(null,"BLEsmart_0000")),"no model: Bluetooth name");
        check("BLEsmart_0000".equals(MonitorProduct.displayName("  ","BLEsmart_0000")),"blank model: Bluetooth name");
        check(MonitorProduct.displayName("",null)==null,"nothing known");
        check("OMRON M4 Connect AFib".equals(MonitorProduct.displayName("HEM-7196T1-FLE","BLEsmart_0000")) && "OMRON X4 Connect AFib".equals(MonitorProduct.displayName("HEM-7196T1-FLEO",null)),"7196T1 catalog names");
        check("BLEsmart_0000".equals(MonitorProduct.displayName("M4 Connect AFib","BLEsmart_0000")),"a catalog name is not a verified identity");
        check("BLEsmart_0000".equals(MonitorProduct.displayName("HEM-7196T1","BLEsmart_0000")),"bare 7196T1 never named");
        for(String[] row:new String[][]{{"HEM-7151T-Z","BP5250"},{"HEM-7340T-Z","BP7350"},{"HEM-7340T-CA","BP7350CAN"},
                {"HEM-7341T-Z","BP5350"},{"HEM-7343T-Z","BP5450"},{"HEM-6161T-E","RS3 Intelli IT"},{"HEM-6232T-D","RS7 Intelli IT"},
                {"HEM-7143T1-E","M2 Intelli IT"},{"HEM-7143T1-D","M300 Intelli IT"},{"HEM-7143T2-E","X2 Smart"},
                {"HEM-7188T1-LE","M2+ Connect"},{"HEM-7188T1-LEO","X2+ Connect"}})
            check(("OMRON "+row[1]).equals(MonitorProduct.displayName(row[0],"BLEsmart_0000")),"catalog product label "+row[0]);
    }

    private static void registry() {
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7322T","HEM-7342T","HEM-7361T","HEM-7377T1","HEM-7530T","HEM-7600T","HEM-6232T","HEM-7380T1","HEM-7380T1-EOSL","HEM-7380T1-EBK","HEM-7196T1-FLE","HEM-7196T1-FLEO"}) {
            MonitorProduct product=MonitorProduct.lookup(id);
            check(product!=null && product.identity.equals(id) && "OMRON".equals(product.manufacturer),"listed: "+id);
            check(VendorMemoryModel.identify(id)!=null || X7Model.identify(id)!=null && X7Model.identify(id).identity.equals(id),"identity is a model definition: "+id);
        }
        int named=0; for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7322T","HEM-7342T","HEM-7361T","HEM-7530T","HEM-7600T","HEM-6232T"}) if(MonitorProduct.lookup(id).productName!=null) named++;
        check(named==0,"no undocumented product names");
        check(MonitorProduct.lookup("HEM-7380T1").productName==null && MonitorProduct.lookup("HEM-7155T-EBK")==null && MonitorProduct.lookup(null)==null,"exact identities only");
    }

    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
}
