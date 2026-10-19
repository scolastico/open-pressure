package org.openpressure.omronsandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openpressure.protocol.VendorMemoryModel;
import org.openpressure.protocol.X7MemoryIndex;
import org.openpressure.protocol.X7Record;

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

    /**
     * The record areas of a single-channel WLD configuration ({@code connect_type} WLD3.0/WLD4.0), which has no
     * {@code index_num_of_datatypes}: every consecutive {@code data_<n>_address}, counted by
     * {@code index_<n>_data_num_of_memories}. Areas 1 and 2 are the users' BP banks; the others hold pulse-wave data
     * and error logs.
     */
    public List<Area> wldAreas() {
        List<Area> result=new ArrayList<>();
        for(int n=1;memory.containsKey("data_"+n+"_address");n++)
            result.add(new Area(n,Integer.parseInt(memory.get("data_"+n+"_address"),16),Integer.parseInt(memory.getOrDefault("data_"+n+"_address_step","0")),
                Integer.parseInt(memory.getOrDefault("data_"+n+"_size","0")),Integer.parseInt(memory.getOrDefault("index_"+n+"_data_num_of_memories","0"))));
        return result;
    }

    /**
     * The candidate name OpenPressure's X7-family identification ({@link X7Record#supportsModel}) accepts: the folder
     * identifier, the config's {@code model_id} or the catalog name; null when none is accepted. Every identity the
     * gate accepts is compared, so a newly accepted X7-family identity joins the comparison without a list here.
     */
    public static String acceptedX7Name(String identifier,String modelId,String catalogName) {
        for(String candidate:new String[]{identifier,modelId,catalogName}) if(X7Record.supportsModel(candidate)) return candidate;
        return null;
    }
    /** OpenPressure's X7 BP banks for an accepted identity: the ranges its index yields with every slot of both users populated. */
    public static List<X7MemoryIndex.Range> x7Banks(String acceptedName) {
        if(!X7Record.supportsModel(acceptedName)) throw new IllegalArgumentException("not an accepted X7 identity: "+acceptedName);
        byte[] full=new byte[X7MemoryIndex.SETTINGS_BYTES];
        // Full flag (bit 14 of each user's pointer word); an all-zero block 3 carries a valid zero checksum.
        full[1]=0x40; full[3]=0x40;
        return X7MemoryIndex.parse(full).historyRanges();
    }
    /** Omron's BP areas (users 1 and 2) of a WLD configuration against OpenPressure's X7 banks. */
    public static String compareX7(String identifier,OmronConfig omron,String acceptedName) {
        StringBuilder out=new StringBuilder(identifier).append(" (").append(omron.modelId).append(", ").append(omron.connectType).append(") -> ours X7 map\n");
        List<Area> areas=omron.wldAreas();
        for(Area a:areas) out.append("  Omron ").append(a.user>2?a.toString().replaceFirst("^user","area")+" (not BP history)":a.toString()).append('\n');
        List<X7MemoryIndex.Range> banks=x7Banks(acceptedName);
        for(X7MemoryIndex.Range r:banks)
            out.append(String.format(Locale.ROOT,"  Ours  user %d: 0x%X, %d records of %d bytes%n",r.user,r.start,r.length/X7Record.RECORD_BYTES,X7Record.RECORD_BYTES));
        List<String> differences=new ArrayList<>();
        for(X7MemoryIndex.Range r:banks) {
            Area a=r.user<=areas.size()?areas.get(r.user-1):null;
            if(a==null) differences.add("user "+r.user+" has no Omron area");
            else if(a.start!=r.start || a.size!=X7Record.RECORD_BYTES || a.step!=X7Record.RECORD_BYTES || a.count*a.step!=r.length) differences.add("user "+r.user+" layout differs");
        }
        out.append(differences.isEmpty()?"  same layout\n":"  DIFFERENT: "+String.join("; ",differences)+"\n");
        return out.toString();
    }

    /**
     * OpenPressure's model for an Omron catalog identity: the first of the folder identifier (as is and with
     * {@code _} as {@code -}), the config's {@code model_id} and the catalog name that our exact identification
     * accepts; null when none is accepted.
     */
    public static VendorMemoryModel ours(String identifier,String modelId,String catalogName) {
        return VendorMemoryModel.identify(acceptedName(identifier,modelId,catalogName));
    }
    /** The candidate name OpenPressure's identification accepts, or null. */
    public static String acceptedName(String identifier,String modelId,String catalogName) {
        for(String candidate:new String[]{identifier,identifier==null?null:identifier.replace('_','-'),modelId,catalogName})
            if(VendorMemoryModel.identify(candidate)!=null) return candidate;
        return null;
    }

    /** Human-readable comparison of Omron's areas for one catalog identifier with OpenPressure's history ranges. */
    public static String compare(String identifier,OmronConfig omron,VendorMemoryModel ours) {
        StringBuilder out=new StringBuilder(identifier).append(" (").append(omron.modelId).append(", ").append(omron.connectType).append(") -> ours ").append(ours==null?"none":ours.id).append('\n');
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
