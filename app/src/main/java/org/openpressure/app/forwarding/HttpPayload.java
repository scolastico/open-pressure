package org.openpressure.app.forwarding;

import android.content.ContentValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned public wire contract: no raw packets, notes or clear device identifiers. */
public final class HttpPayload {
    private HttpPayload() {}
    public static String sha256(String value) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder(64);
            for(byte b:digest) { key.append(Character.forDigit((b>>>4)&15,16)); key.append(Character.forDigit(b&15,16)); }
            return key.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
    public static String create(ContentValues reading,String key) {
        try {
            JSONObject json=new JSONObject();
            json.put("schema_version",1); json.put("id",key);
            json.put("device_id",sha256(reading.getAsString("device")));
            json.put("source",reading.getAsString("source"));
            put(json,"user",reading.getAsInteger("user"));
            put(json,"measured_at",reading.getAsString("time"));
            json.put("received_at",Instant.ofEpochMilli(reading.getAsLong("received")).toString());
            json.put("systolic",reading.getAsDouble("sys")); json.put("diastolic",reading.getAsDouble("dia"));
            put(json,"mean_arterial_pressure",reading.getAsDouble("map"));
            put(json,"pulse",reading.getAsDouble("pulse")); json.put("unit",reading.getAsString("unit"));
            put(json,"status",reading.getAsInteger("status"));
            Integer afib=reading.getAsInteger("afib"); put(json,"atrial_fibrillation",afib==null?null:afib!=0);
            return json.toString();
        } catch(JSONException invalid) { throw new IllegalArgumentException("Invalid reading for forwarding",invalid); }
    }
    private static void put(JSONObject json,String name,Object value) throws JSONException { json.put(name,value==null?JSONObject.NULL:value); }
}
