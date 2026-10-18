package org.openpressure.omronsandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openpressure.protocol.VendorMemoryModel;

/**
 * The {@code [model]} and {@code [memory_map]} sections of an Omron {@code DeviceConfig.sys}. Addresses are hex
 * ({@code data_1_address=98} is 0x98, matching OpenPressure's map); sizes, steps and pointer limits are decimal.
 */
public final class OmronConfig {
    public final String modelId,connectType;
    private final Map<String,String> memory;

    private OmronConfig(Map<String,Map<String,String>> sections) {
        Map<String,String> model=sections.getOrDefault("model",Map.of());
        modelId=model.get("model_id"); connectType=model.get("connect_type");
        memory=sections.getOrDefault("memory_map",Map.of());
    }

    public static OmronConfig parse(String text) {
        Map<String,Map<String,String>> sections=new LinkedHashMap<>();
        Map<String,String> current=null;
        for(String raw:text.split("\r?\n")) {
            String line=raw.trim();
            if(line.isEmpty() || line.startsWith(";")) continue;
            if(line.startsWith("[") && line.endsWith("]")) { current=new LinkedHashMap<>(); sections.put(line.substring(1,line.length()-1),current); continue; }
            int eq=line.indexOf('=');
            if(current!=null && eq>0) current.put(line.substring(0,eq).trim(),line.substring(eq+1).trim());
        }
        return new OmronConfig(sections);
    }

    public static final class Area {
        public final int user,start,step,size,count;
        Area(int user,int start,int step,int size,int count) { this.user=user; this.start=start; this.step=step; this.size=size; this.count=count; }
        @Override public String toString() { return String.format(Locale.ROOT,"user %d: 0x%X, %d records of %d bytes (step %d)",user,start,count,size,step); }
    }

    /** One record area per data type ({@code index_num_of_datatypes}), with its record count from the pointer limit. */
    public List<Area> areas() {
        List<Area> result=new ArrayList<>();
        int types=Integer.parseInt(memory.getOrDefault("index_num_of_datatypes","0"));
        for(int n=1;n<=types;n++) {
            String address=memory.get("data_"+n+"_address");
            if(address==null) continue;
            int max=Integer.parseInt(memory.getOrDefault("index_"+n+"_data_pointer_max","-1"));
            result.add(new Area(n,Integer.parseInt(address,16),Integer.parseInt(memory.getOrDefault("data_"+n+"_address_step","0")),
                Integer.parseInt(memory.getOrDefault("data_"+n+"_size","0")),max+1));
        }
        return result;
    }

    /** Human-readable comparison of Omron's areas for one catalog identifier with OpenPressure's history ranges. */
    public static String compare(String identifier,OmronConfig omron,VendorMemoryModel ours) {
        StringBuilder out=new StringBuilder(identifier).append(" (").append(omron.modelId).append(", ").append(omron.connectType).append(")\n");
        List<Area> areas=omron.areas();
        for(Area a:areas) out.append("  Omron ").append(a).append('\n');
        if(ours==null) return out.append("  OpenPressure: no accepted map\n").toString();
        for(VendorMemoryModel.Range r:ours.historyRanges())
            out.append(String.format(Locale.ROOT,"  Ours  user %d: 0x%X, %d records of %d bytes%n",r.user,r.start,r.length/ours.recordBytes,ours.recordBytes));
        List<String> differences=new ArrayList<>();
        if(areas.size()!=ours.historyRanges().size()) differences.add("Omron "+areas.size()+" area(s) / ours "+ours.historyRanges().size()+" bank(s)");
        for(int i=0;i<Math.min(areas.size(),ours.historyRanges().size());i++) {
            Area a=areas.get(i); VendorMemoryModel.Range r=ours.historyRanges().get(i);
            if(a.start!=r.start || a.size!=ours.recordBytes || a.count*a.step!=r.length) differences.add("user "+a.user+" layout differs");
        }
        out.append(differences.isEmpty()?"  same layout\n":"  DIFFERENT: "+String.join("; ",differences)+"\n");
        return out.toString();
    }
}
