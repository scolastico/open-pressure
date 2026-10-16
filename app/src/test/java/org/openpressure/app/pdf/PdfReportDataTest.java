package org.openpressure.app.pdf;

import org.junit.Test;
import org.openpressure.app.data.Reading;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PdfReportDataTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test public void inclusiveRangeAcrossYearsFiltersProfileAndSortsEqualTimesById() {
        Reading before = reading(1, 1, "2025-12-30T23:59:59", 110, 70, 60.0);
        Reading start = reading(3, 1, "2025-12-31T00:00:00", 120, 80, 70.0);
        Reading end = reading(4, 1, "2026-01-01T23:59:59.999999999", 130, 90, 80.0);
        Reading tie = reading(2, 1, "2025-12-31T00:00:00", 121, 81, 71.0);
        Reading after = reading(5, 1, "2026-01-02T00:00:00", 140, 90, null);
        Reading other = reading(6, 2, "2026-01-01T12:00:00", 140, 90, null);
        PdfReportData report = PdfReportData.create(Arrays.asList(end, before, start, other, after, tie),
                1, LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), BERLIN);

        assertEquals(Arrays.asList(2L, 3L, 4L), ids(report.entries));
        assertEquals(1, report.selectedUser);
        assertEquals(LocalDate.of(2025, 12, 31), report.from);
        assertEquals(LocalDate.of(2026, 1, 1), report.to);
        assertEquals(BERLIN, report.zone);
        assertEquals(Integer.valueOf(1), report.summaries.get(0).user);
    }

    @Test public void profileTwoCanBeSelectedIndependently() {
        PdfReportData report = create(Arrays.asList(reading(1, 1, "2026-01-01T00:00", 120, 80, null),
                reading(2, 2, "2026-01-01T00:00", 130, 90, null)), 2);
        assertEquals(Collections.singletonList(2L), ids(report.entries));
    }

    @Test public void missingAndInvalidMeasurementTimeUseReceiptInRequestedZoneForFiltering() {
        Reading missing = reading(1, 1, null, 120, 80, null);
        missing.received = Instant.parse("2026-01-01T23:30:00Z").toEpochMilli();
        Reading invalid = reading(2, 1, "not-a-time", 125, 85, null);
        invalid.received = Instant.parse("2026-01-01T22:30:00Z").toEpochMilli();
        PdfReportData report = PdfReportData.create(Arrays.asList(missing, invalid), 0,
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2), BERLIN);

        assertEquals(Collections.singletonList(1L), ids(report.entries));
        assertNull(report.entries.get(0).time);
        assertEquals(LocalDateTime.of(2026, 1, 2, 0, 30), report.entries.get(0).displayTime);
        PdfReportData all = create(Arrays.asList(missing, invalid), 0);
        assertEquals(2, all.summaries.get(0).missingTimeCount);
        assertEquals(LocalDateTime.of(2026, 1, 1, 23, 30), all.entries.get(0).displayTime);
        assertNull(all.entries.get(0).time);
    }

    @Test public void receiptFallbackObservesDstAndMeasurementTimesRemainLocal() {
        Reading beforeJump = reading(1, 1, null, 120, 80, null);
        beforeJump.received = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli();
        Reading afterJump = reading(2, 1, null, 120, 80, null);
        afterJump.received = Instant.parse("2026-03-29T01:30:00Z").toEpochMilli();
        Reading measured = reading(3, 1, "2026-03-29T02:30:00", 120, 80, null);
        PdfReportData report = create(Arrays.asList(afterJump, measured, beforeJump), 0);

        assertEquals(Arrays.asList(1L, 3L, 2L), ids(report.entries));
        assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30), report.entries.get(0).displayTime);
        assertEquals(LocalDateTime.of(2026, 3, 29, 2, 30), report.entries.get(1).time);
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), report.entries.get(2).displayTime);
    }

    @Test public void chartPagesKeepProfilesSeparateAndSplitAtFortyEightRows() {
        List<Reading> readings = new ArrayList<>();
        for (int i = 97; i >= 1; i--) {
            readings.add(reading(i, 1, LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i).toString(), 120, 80, null));
            if (i <= 49) readings.add(reading(1000 + i, 2,
                    LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i).toString(), 130, 90, null));
        }
        PdfReportData report = create(readings, 0);

        assertEquals(5, report.charts.size());
        assertEquals(48, report.charts.get(0).entries.size());
        assertEquals(48, report.charts.get(1).entries.size());
        assertEquals(Collections.singletonList(97L), ids(report.charts.get(2).entries));
        assertEquals(48, report.charts.get(3).entries.size());
        assertEquals(Collections.singletonList(1049L), ids(report.charts.get(4).entries));
        assertEquals(1, report.charts.get(0).entries.get(0).id);
        for (PdfReportData.ChartPage page : report.charts) {
            for (PdfReportData.Entry entry : page.entries) assertEquals(page.user, entry.user);
        }
    }

    @Test public void allActualProfilesHaveStableOrderIncludingGuestAndUnknown() {
        PdfReportData report = create(Arrays.asList(reading(1, null, "2026-01-01T00:00", 120, 80, null),
                reading(2, 255, "2026-01-01T00:00", 120, 80, null),
                reading(3, 2, "2026-01-01T00:00", 120, 80, null),
                reading(4, 1, "2026-01-01T00:00", 120, 80, null)), 0);
        assertEquals(4, report.summaries.size());
        assertEquals(Integer.valueOf(1), report.summaries.get(0).user);
        assertEquals(Integer.valueOf(2), report.summaries.get(1).user);
        assertEquals(Integer.valueOf(255), report.summaries.get(2).user);
        assertNull(report.summaries.get(3).user);
        assertNull(report.charts.get(3).user);
    }

    @Test public void statisticsNormalizeKpaAndExcludeNonfinitePressurePairsButCountPulseIndependently() {
        Reading kpa = reading(1, 1, "2026-01-01T00:00", 16, 10, 60.0);
        kpa.unit = "kPa";
        Reading mmhg = reading(2, 1, "2026-01-01T01:00", 140, 90, 80.0);
        Reading invalidDia = reading(3, 1, "2026-01-01T02:00", 200, Double.NaN, 100.0);
        Reading invalidSys = reading(4, 1, "2026-01-01T03:00", Double.POSITIVE_INFINITY, 100, Double.NaN);
        Reading invalidPulse = reading(5, 1, "2026-01-01T04:00", Double.NaN, 80, Double.NEGATIVE_INFINITY);
        PdfReportData report = create(Arrays.asList(kpa, mmhg, invalidDia, invalidSys, invalidPulse), 0);
        PdfReportData.Summary summary = report.summaries.get(0);

        assertEquals(5, summary.count);
        assertEquals(2, summary.pressureCount);
        assertEquals(3, summary.pulseCount);
        assertEquals(130.00496, summary.systolicAverage, 0.000001);
        assertEquals(82.5031, summary.diastolicAverage, 0.000001);
        assertEquals(120.00992, summary.systolicMin, 0.000001);
        assertEquals(140, summary.systolicMax, 0);
        assertEquals(75.0062, summary.diastolicMin, 0.000001);
        assertEquals(90, summary.diastolicMax, 0);
        assertEquals(80, summary.pulseAverage, 0);
        assertEquals(60, summary.pulseMin, 0);
        assertEquals(100, summary.pulseMax, 0);
        assertEquals(16, report.entries.get(0).systolic, 0);
        assertEquals(120.00992, report.entries.get(0).sysMmHg(), 0.000001);
        assertEquals(75.0062, report.entries.get(0).diaMmHg(), 0.000001);
        assertEquals(5, report.charts.get(0).entries.size());
    }

    @Test public void missingStatisticsRemainNanAndEmptySelectionHasNoPages() {
        PdfReportData empty = create(Collections.emptyList(), 0);
        assertTrue(empty.entries.isEmpty());
        assertTrue(empty.summaries.isEmpty());
        assertTrue(empty.charts.isEmpty());
        PdfReportData report = create(Collections.singletonList(reading(1, 1, null, Double.NaN, Double.NaN, null)), 0);
        PdfReportData.Summary summary = report.summaries.get(0);
        assertEquals(0, summary.pressureCount);
        assertEquals(0, summary.pulseCount);
        assertTrue(Double.isNaN(summary.systolicAverage));
        assertTrue(Double.isNaN(summary.diastolicAverage));
        assertTrue(Double.isNaN(summary.systolicMin));
        assertTrue(Double.isNaN(summary.systolicMax));
        assertTrue(Double.isNaN(summary.diastolicMin));
        assertTrue(Double.isNaN(summary.diastolicMax));
        assertTrue(Double.isNaN(summary.pulseAverage));
        assertTrue(Double.isNaN(summary.pulseMin));
        assertTrue(Double.isNaN(summary.pulseMax));
    }

    @Test public void snapshotCopiesEveryExportedFieldAndDoesNotReorderSource() {
        Reading original = reading(2, 2, "2026-02-03T04:05", 130, 85, 72.0);
        original.received = 123456L;
        original.device = "device";
        original.name = "monitor";
        original.source = "bluetooth";
        original.notes = "Long note\n".repeat(10000);
        original.map = 100.0;
        original.status = 7;
        original.afib = true;
        Reading earlier = reading(1, 1, "2026-01-01T00:00", 120, 80, null);
        List<Reading> source = new ArrayList<>(Arrays.asList(original, earlier));
        PdfReportData report = create(source, 0);
        PdfReportData.Entry entry = report.entries.get(1);
        assertSame(original, source.get(0));
        original.systolic = 999;
        original.name = "changed";
        original.notes = "changed";
        original.user = 1;
        source.clear();

        assertEquals(2, entry.id);
        assertEquals(123456L, entry.received);
        assertEquals(LocalDateTime.of(2026, 2, 3, 4, 5), entry.time);
        assertEquals(130, entry.systolic, 0);
        assertEquals(85, entry.diastolic, 0);
        assertEquals(Double.valueOf(72), entry.pulse);
        assertEquals(Double.valueOf(100), entry.map);
        assertEquals(Integer.valueOf(2), entry.user);
        assertEquals(Integer.valueOf(7), entry.status);
        assertEquals(Boolean.TRUE, entry.afib);
        assertEquals("device", entry.device);
        assertEquals("monitor", entry.name);
        assertEquals("bluetooth", entry.source);
        assertEquals("Long note\n".repeat(10000), entry.notes);
        assertEquals("mmHg", entry.unit);
        assertEquals(2, report.entries.size());
        assertThrows(UnsupportedOperationException.class, () -> report.entries.clear());
        assertThrows(UnsupportedOperationException.class, () -> report.summaries.clear());
        assertThrows(UnsupportedOperationException.class, () -> report.charts.clear());
        assertThrows(UnsupportedOperationException.class, () -> report.charts.get(0).entries.clear());
    }

    @Test public void nullableTextIsSafeForRendering() {
        Reading reading = reading(1, null, null, 120, 80, null);
        reading.unit = null;
        PdfReportData.Entry entry = create(Collections.singletonList(reading), 0).entries.get(0);
        assertEquals("", entry.device);
        assertEquals("", entry.name);
        assertEquals("", entry.source);
        assertEquals("", entry.notes);
        assertEquals("", entry.unit);
        assertEquals(120, entry.sysMmHg(), 0);
    }

    @Test public void rejectsPartialOrReversedRangesAndUnsupportedProfile() {
        LocalDate date = LocalDate.of(2026, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> PdfReportData.create(Collections.emptyList(), 0, date, null, BERLIN));
        assertThrows(IllegalArgumentException.class, () -> PdfReportData.create(Collections.emptyList(), 0, null, date, BERLIN));
        assertThrows(IllegalArgumentException.class, () -> PdfReportData.create(Collections.emptyList(), 0, date.plusDays(1), date, BERLIN));
        assertThrows(IllegalArgumentException.class, () -> PdfReportData.create(Collections.emptyList(), 3, null, null, BERLIN));
    }

    private static PdfReportData create(List<Reading> readings, int selectedUser) {
        return PdfReportData.create(readings, selectedUser, null, null, BERLIN);
    }

    private static Reading reading(long id, Integer user, String time, double systolic, double diastolic, Double pulse) {
        Reading reading = new Reading();
        reading.id = id;
        reading.user = user;
        reading.time = time;
        reading.systolic = systolic;
        reading.diastolic = diastolic;
        reading.pulse = pulse;
        reading.unit = "mmHg";
        return reading;
    }

    private static List<Long> ids(List<PdfReportData.Entry> entries) {
        List<Long> ids = new ArrayList<>();
        for (PdfReportData.Entry entry : entries) ids.add(entry.id);
        return ids;
    }
}
