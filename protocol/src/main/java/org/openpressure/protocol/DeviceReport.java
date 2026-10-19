package org.openpressure.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shareable technical summary of one monitor session, intended for a public support issue.
 * It records only what the session already read; it never contains readings, timestamps,
 * Bluetooth addresses, serial numbers, system IDs, memory contents, keys or names.
 */
public final class DeviceReport {
    public static final String FORMAT="open-pressure-device-report-v1";
    private static final Pattern ADDRESS=Pattern.compile("(?i)\\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}\\b");
    private static final Pattern LONG_HEX=Pattern.compile("(?i)[0-9a-f]{12,}");
    private static final int TEXT_LIMIT=200, KEY_ACK_LIMIT=8, WRITE_LIMIT=8;

    /** Fixed result of one attempted write aspect; the report never carries the written values or times. */
    public enum WriteOutcome { ACKNOWLEDGED, REJECTED, SKIPPED_WITHIN_THRESHOLD, CANCELLED, NOT_ATTEMPTED }

    public final String operation;
    private final String appVersion, phone;
    private final int androidApi;
    private String model, firmware, manufacturer, advertisedName, profile, memoryMap, status, failure;
    private byte[] advertisement;
    private int mtu=-1, decoded=-1, rejected=-1;
    private Boolean endAcknowledged;
    private final List<String> services=new ArrayList<>(), keyAcknowledgments=new ArrayList<>(), developer=new ArrayList<>();
    private final List<Object[]> writes=new ArrayList<>();

    public DeviceReport(String operation,String appVersion,int androidApi,String phone) {
        this.operation=operation; this.appVersion=appVersion; this.androidApi=androidApi; this.phone=clean(phone);
    }

    /** Only the tested HEM-7380T1-EOSL identity, including the marketing name its firmware reports. */
    public static boolean verifiedModel(String model) {
        if(model==null) return false;
        model=model.replace("\u0000","").trim();
        return model.equals("HEM-7380T1-EOSL") || model.equalsIgnoreCase("X7 Smart AFib");
    }
    public boolean verified() { return verifiedModel(model); }
    /**
     * Worth sharing once the monitor's GATT layout is known and the identity is not the tested one, or whenever a
     * write aspect was recorded: write support is validated per identity, including the tested one.
     */
    public boolean useful() { return hasWrites() || !services.isEmpty() && !verified(); }
    public String model() { return model; }

    public void advertisement(String name,byte[] manufacturerData) {
        advertisedName=maskName(name);
        advertisement=manufacturerData==null?null:manufacturerData.clone();
    }
    public void mtu(int value) { mtu=value; }
    public void service(String uuid,List<String> characteristics) {
        StringBuilder value=new StringBuilder(uuid.toLowerCase(Locale.ROOT));
        for(String characteristic:characteristics) value.append('\n').append(characteristic.toLowerCase(Locale.ROOT));
        services.add(value.toString());
    }
    public void model(String value) { model=clean(value); }
    public void firmware(String value) { firmware=clean(value); }
    public void manufacturer(String value) { manufacturer=clean(value); }
    public void protocol(String selectedProfile,String map) { profile=selectedProfile; memoryMap=map; }
    /** Opcode, status and length only. Key bodies are never passed here. */
    public void keyAcknowledgment(byte[] response) {
        if(response==null || keyAcknowledgments.size()>=KEY_ACK_LIMIT) return;
        String shape=response.length==0?"empty":String.format(Locale.ROOT,"%02x",response[0]&255);
        if(response.length>1) shape+=String.format(Locale.ROOT," %02x",response[1]&255);
        keyAcknowledgments.add(shape+" ("+response.length+" bytes)");
    }
    public void transfer(int decodedRecords,int rejectedRecords,boolean ended) {
        decoded=decodedRecords; rejected=rejectedRecords; endAcknowledged=ended;
    }
    /** Aspect, outcome and whether the index was overridden only; no values, times or addresses. */
    public void writeAspect(WriteAspectIndex.Aspect aspect,WriteOutcome outcome,boolean override) {
        if(aspect==null || outcome==null || writes.size()>=WRITE_LIMIT) return;
        writes.add(new Object[]{"aspect",lower(aspect),"outcome",lower(outcome),"override",override});
    }
    public boolean hasWrites() { return !writes.isEmpty(); }
    /** The name of a developer switch in effect for this session, such as {@code force_x7_encrypted}; names only, once each. */
    public void developer(String name) {
        if(name==null || !name.matches("[a-z0-9_]{1,40}") || developer.contains(name) || developer.size()>=WRITE_LIMIT) return;
        developer.add(name);
    }
    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    /** The latest meaningful result. Failure text is scrubbed of addresses and long identifiers. */
    public void outcome(String title,String failureDetail) { status=clean(title); failure=clean(failureDetail); }

    static String maskName(String name) {
        if(name==null) return null;
        String value=name.replace("\u0000","").trim();
        int start=value.regionMatches(true,0,"BLEsmart_",0,9)?9:0;
        // The first eight characters after the prefix name the category and model; the remainder identifies the unit.
        int keep=Math.min(value.length(),start==9?start+8:0);
        StringBuilder masked=new StringBuilder(value.substring(0,keep));
        for(int i=keep;i<value.length();i++) masked.append(Character.isLetterOrDigit(value.charAt(i))?'x':value.charAt(i));
        return clean(masked.toString());
    }
    /** Type and flags stay visible; per-user sequences, counts and trailers are masked. */
    static String advertisementShape(byte[] data) {
        StringBuilder value=new StringBuilder();
        for(int i=0;i<data.length;i++) {
            if(i>0) value.append(' ');
            value.append(i<2?String.format(Locale.ROOT,"%02x",data[i]&255):"xx");
        }
        return value.toString();
    }
    private static String clean(String value) {
        if(value==null) return null;
        String result=value.replace("\u0000","").trim();
        result=ADDRESS.matcher(result).replaceAll("[address removed]");
        result=LONG_HEX.matcher(result).replaceAll("[identifier removed]");
        return result.length()>TEXT_LIMIT?result.substring(0,TEXT_LIMIT)+"…":result;
    }

    public String toJson() {
        Json json=new Json();
        json.field("format",FORMAT).field("app_version",appVersion).field("android_api",androidApi).field("phone",phone).field("operation",operation);
        json.field("verified_model",verified());
        json.open("monitor").field("model",model).field("firmware",firmware).field("manufacturer",manufacturer).field("advertised_name",advertisedName).close();
        json.open("advertisement");
        if(advertisement==null) json.field("present",false);
        else {
            json.field("present",true).field("length",advertisement.length).field("shape",advertisementShape(advertisement));
            try {
                OmronAdvertisement parsed=OmronAdvertisement.parseManufacturerPayload(advertisement);
                json.field("type",parsed.type).field("users",parsed.users.size()).field("record_counts",parsed.hasRecordCounts());
                if(parsed.category>=0) json.field("category",parsed.category).field("model_id",String.format(Locale.ROOT,"0x%04x",parsed.modelId));
            } catch(IllegalArgumentException unknown) { json.field("parse_error",clean(unknown.getMessage())); }
        }
        json.close();
        json.field("att_mtu",mtu<0?null:mtu).list("gatt",services);
        json.open("protocol").field("profile",profile).field("memory_map",memoryMap).close();
        json.list("key_acknowledgments",keyAcknowledgments);
        json.open("transfer");
        if(endAcknowledged!=null) json.field("decoded_records",decoded).field("rejected_records",rejected).field("end_acknowledged",endAcknowledged);
        json.close();
        json.objects("writes",writes);
        json.list("developer",developer);
        json.open("outcome").field("status",status).field("failure",failure).close();
        json.field("excluded","Readings, measurement times, Bluetooth address, serial number, system ID, memory contents, keys and names");
        return json.finish();
    }

    /** Minimal ordered JSON writer; the protocol module has no Android or library dependency. */
    private static final class Json {
        private final StringBuilder out=new StringBuilder("{");
        private final List<Boolean> first=new ArrayList<>(Collections.singletonList(true));
        private int depth=1;
        private void key(String name) {
            int last=first.size()-1;
            if(!first.get(last)) out.append(',');
            first.set(last,false);
            out.append('\n').append(indent(depth)).append(quote(name)).append(": ");
        }
        Json field(String name,Object value) {
            key(name);
            out.append(value==null?"null":value instanceof String?quote((String)value):value.toString());
            return this;
        }
        Json open(String name) { key(name); out.append('{'); first.add(true); depth++; return this; }
        Json close() {
            boolean empty=first.remove(first.size()-1); depth--;
            if(!empty) out.append('\n').append(indent(depth));
            out.append('}'); return this;
        }
        Json list(String name,List<String> values) {
            key(name); out.append('[');
            for(int i=0;i<values.size();i++) out.append(i==0?"":",").append('\n').append(indent(depth+1)).append(quote(values.get(i)));
            if(!values.isEmpty()) out.append('\n').append(indent(depth));
            out.append(']'); return this;
        }
        /** Each row is key/value pairs rendered as one compact object per line. */
        Json objects(String name,List<Object[]> rows) {
            key(name); out.append('[');
            for(int i=0;i<rows.size();i++) {
                Object[] row=rows.get(i);
                out.append(i==0?"":",").append('\n').append(indent(depth+1)).append('{');
                for(int j=0;j<row.length;j+=2)
                    out.append(j==0?"":",").append(quote((String)row[j])).append(':').append(row[j+1] instanceof String?quote((String)row[j+1]):String.valueOf(row[j+1]));
                out.append('}');
            }
            if(!rows.isEmpty()) out.append('\n').append(indent(depth));
            out.append(']'); return this;
        }
        private static String indent(int levels) {
            StringBuilder value=new StringBuilder();
            for(int i=0;i<levels;i++) value.append("  ");
            return value.toString();
        }
        String finish() { return out.append("\n}\n").toString(); }
        private static String quote(String value) {
            StringBuilder result=new StringBuilder("\"");
            for(char c:value.toCharArray()) {
                if(c=='"' || c=='\\') result.append('\\').append(c);
                else if(c=='\n') result.append("\\n");
                else if(c<0x20) result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                else result.append(c);
            }
            return result.append('"').toString();
        }
    }
}
