package org.openpressure.omronsandbox;

import android.os.Bundle;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.openpressure.protocol.fixture.Fixture;

/**
 * Maps the Bundles Omron's {@code onGetVitalData} delivers to fixture readings. Each record is a nested Bundle
 * keyed by its ordinal; measurement codes follow Omron's VitalDataIndexes.json ({@code 0001} systolic,
 * {@code 0002} diastolic, {@code 0003} pulse, each with a {@code measurement} value) and {@code startDate} is
 * {@code yyyyMMddHHmmss}.
 */
final class OmronVitals {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private OmronVitals() {}

    static final class Delivery {
        final int user;
        final Bundle data;
        Delivery(int user,Bundle data) { this.user=user; this.data=data; }
    }

    static List<Fixture.Reading> readings(List<Delivery> vitals,List<String> log) {
        List<Fixture.Reading> result=new ArrayList<>();
        for(Delivery d:vitals) for(String key:d.data.keySet()) {
            Object value=d.data.get(key);
            if(!(value instanceof Bundle) || !key.matches("\\d+")) continue;
            Bundle record=(Bundle)value;
            Integer sys=measurement(record,"0001"),dia=measurement(record,"0002"),pulse=measurement(record,"0003");
            String start=record.getString("startDate");
            if(sys==null || dia==null || pulse==null || start==null) { log.add("vital record "+key+" lacks fields: "+OmronRunner.bundle(record)); continue; }
            result.add(new Fixture.Reading(d.user,sys,dia,pulse,LocalDateTime.parse(start,DATE)));
        }
        return result;
    }
    private static Integer measurement(Bundle record,String code) {
        Object field=record.get(code);
        if(!(field instanceof Bundle)) return null;
        Object value=((Bundle)field).get("measurement");
        return value instanceof Number?((Number)value).intValue():null;
    }
}
