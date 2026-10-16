package org.openpressure.app.pdf;

import java.time.ZoneId;
import java.util.*;
import org.junit.Test;
import org.openpressure.app.data.Reading;
import static org.junit.Assert.*;

public class VirtualUserPdfTest {
    private Reading reading(long id,int bank,Integer profile) {
        Reading r=new Reading(); r.id=id; r.user=bank; r.virtualUserId=profile;
        r.time="2026-10-16T12:00:00"; r.systolic=120; r.diastolic=80; r.unit="mmHg"; return r;
    }
    @Test public void namedProfilesFilterAndGroupIndependentlyOfMonitorBanks() {
        Reading one=reading(1,1,7),two=reading(2,2,7),other=reading(3,1,8),legacy=reading(4,2,null);
        Map<Integer,String> names=new HashMap<>(); names.put(-7,"Synthetic Alice"); names.put(-8,"Synthetic Bob");
        PdfReportData report=PdfReportData.createVirtual(Arrays.asList(one,two,other,legacy),-7,null,null,ZoneId.of("UTC"),names);
        assertEquals(2,report.entries.size()); assertEquals(-7,report.selectedUser);
        assertEquals(Integer.valueOf(-7),report.summaries.get(0).user);
        assertEquals("Synthetic Alice",report.profileNames.get(-7));
        names.put(-7,"Renamed later"); one.virtualUserId=8;
        assertEquals("Synthetic Alice",report.profileNames.get(-7));
        assertEquals(Integer.valueOf(-7),report.entries.get(0).user);
        assertEquals(Integer.valueOf(1),one.user);
    }
    @Test public void allProfilesKeepUnassignedHistorySeparateFromNamedPeople() {
        PdfReportData report=PdfReportData.createVirtual(Arrays.asList(reading(1,1,7),reading(2,2,8),reading(3,1,null)),0,null,null,ZoneId.of("UTC"),Map.of(-7,"A",-8,"B"));
        assertEquals(3,report.summaries.size());
        assertTrue(report.virtualUsers);
        assertNull(report.summaries.get(2).user);
        assertThrows(UnsupportedOperationException.class,()->report.profileNames.put(-9,"C"));
    }
}
