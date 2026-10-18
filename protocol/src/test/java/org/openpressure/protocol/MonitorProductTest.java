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
        check("HEM-7380T1".equals(MonitorProduct.displayName("HEM-7380T1","BLEsmart_0000")),"unlisted identity as reported");
        check("Some Cuff 9".equals(MonitorProduct.displayName("Some Cuff 9","BLEsmart_0000")),"unknown model as reported, no manufacturer");
        check("BLEsmart_0000".equals(MonitorProduct.displayName(null,"BLEsmart_0000")),"no model: Bluetooth name");
        check("BLEsmart_0000".equals(MonitorProduct.displayName("  ","BLEsmart_0000")),"blank model: Bluetooth name");
        check(MonitorProduct.displayName("",null)==null,"nothing known");
    }

    private static void registry() {
        for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7322T","HEM-7342T","HEM-7361T","HEM-7377T1","HEM-7530T","HEM-7600T","HEM-6232T","HEM-7380T1-EOSL","HEM-7380T1-EBK"}) {
            MonitorProduct product=MonitorProduct.lookup(id);
            check(product!=null && product.identity.equals(id) && "OMRON".equals(product.manufacturer),"listed: "+id);
            check(VendorMemoryModel.identify(id)!=null || id.startsWith("HEM-7380T1-"),"identity is a model definition: "+id);
        }
        int named=0; for(String id:new String[]{"HEM-7150T","HEM-7155T","HEM-7156T","HEM-7322T","HEM-7342T","HEM-7361T","HEM-7530T","HEM-7600T","HEM-6232T"}) if(MonitorProduct.lookup(id).productName!=null) named++;
        check(named==0,"no undocumented product names");
        check(MonitorProduct.lookup("HEM-7155T-EBK")==null && MonitorProduct.lookup(null)==null,"exact identities only");
    }

    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
}
