package org.openpressure.app.pdf;

import org.junit.Test;
import org.openpressure.app.data.Reading;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The PDF device column names the product from the saved monitor's reported model and keeps the address. */
public class PdfDeviceNameTest {
    private static final String X7="00:11:22:33:44:01",LEGACY="00:11:22:33:44:02",UNKNOWN="00:11:22:33:44:03";

    @Test public void deviceCellShowsProductNameAboveTheAddress() {
        Map<String,String> models=new HashMap<>();
        models.put(X7,"HEM-7380T1-EOSL"); models.put(LEGACY,"HEM-7155T-EBK");
        List<Reading> readings=Arrays.asList(reading(1,X7),reading(2,LEGACY),reading(3,UNKNOWN));
        PdfReportData report=PdfReportData.create(readings,0,null,null,ZoneId.of("UTC"),models);
        assertEquals("OMRON X7 Smart AFib\n"+X7+"\nMonitor",PdfReportWriter.deviceCell(entry(report,1),"Monitor"));
        assertEquals("OMRON HEM-7155T-EBK\n"+LEGACY+"\nMonitor",PdfReportWriter.deviceCell(entry(report,2),"Monitor"));
        assertEquals("not saved: Bluetooth name","BLEsmart_00000003\n"+UNKNOWN+"\nMonitor",PdfReportWriter.deviceCell(entry(report,3),"Monitor"));
    }
    @Test public void reportsWithoutModelsKeepTheBluetoothName() {
        PdfReportData report=PdfReportData.create(Arrays.asList(reading(1,X7)),0,null,null,ZoneId.of("UTC"));
        assertEquals("BLEsmart_00000001",entry(report,1).displayName);
        PdfReportData virtual=PdfReportData.createVirtual(Arrays.asList(reading(1,X7)),0,null,null,ZoneId.of("UTC"),Map.of(),Map.of(X7,"HEM-7380T1-EBK"));
        assertEquals("OMRON X7 Smart AFib",entry(virtual,1).displayName);
    }

    private static PdfReportData.Entry entry(PdfReportData report,long id) {
        for(PdfReportData.Entry entry:report.entries) if(entry.id==id) return entry;
        throw new AssertionError("missing entry "+id);
    }
    private static Reading reading(long id,String address) {
        Reading reading=new Reading();
        reading.id=id; reading.user=1; reading.time="2026-01-0"+id+"T08:00:00";
        reading.systolic=120; reading.diastolic=80; reading.unit="mmHg";
        reading.device=address; reading.name="BLEsmart_0000000"+id; reading.source="monitor";
        return reading;
    }
}
