package org.openpressure.omronsandbox;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Omron's model selection, reproduced from the app: the advertised name {@code BLEsmart_<category><model>} gives
 * two 16-bit IDs, which select the {@code device_models.json} entry whose category matches and whose included
 * group ID (primary or secondary) equals the model ID. Its {@code identifier} names the config folder
 * {@code device_category/0x<category>/<identifier>}. Requires org.json, so use it inside Robolectric.
 */
public final class OmronCatalog {
    private static final Pattern NAME=Pattern.compile("^BLE[sS]mart_([a-fA-F0-9]{4})([a-fA-F0-9]{4})[a-fA-F0-9]*");
    private final JSONArray models;

    public OmronCatalog(String deviceModelsJson) {
        try { models=new JSONArray(deviceModelsJson); }
        catch(JSONException invalid) { throw new IllegalArgumentException("Unreadable device_models.json",invalid); }
    }

    public static final class Entry {
        public final int category,model;
        public final String identifier,name;
        Entry(int category,int model,String identifier,String name) { this.category=category; this.model=model; this.identifier=identifier; this.name=name; }
        public String folder() { return String.format(Locale.ROOT,"device_category/0x%04X/%s",category,identifier); }
    }

    /** Category and model ID from an advertised name, or null when the name carries none. */
    public static int[] ids(String advertisedName) {
        if(advertisedName==null) return null;
        Matcher m=NAME.matcher(advertisedName);
        return m.find()?new int[]{Integer.parseInt(m.group(1),16),Integer.parseInt(m.group(2),16)}:null;
    }
    /** The catalog entry Omron would pick for this advertised name, or null. */
    public Entry folderFor(String advertisedName) {
        int[] ids=ids(advertisedName);
        if(ids==null) return null;
        for(int i=0;i<models.length();i++) {
            JSONObject entry=models.optJSONObject(i);
            if(entry==null || entry.optInt("category",-1)!=ids[0]) continue;
            String primary=entry.optString("deviceGroupIncludedGroupIDKey",""),secondary=entry.optString("secondaryDeviceGroupIncludedGroupIDKey","");
            if(String.valueOf(ids[1]).equals(primary) || String.valueOf(ids[1]).equals(secondary))
                return new Entry(ids[0],ids[1],entry.optString("identifier"),entry.optString("name"));
        }
        return null;
    }
}
