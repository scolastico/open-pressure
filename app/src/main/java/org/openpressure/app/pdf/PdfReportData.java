package org.openpressure.app.pdf;

import org.openpressure.app.data.Reading;
import org.openpressure.protocol.MonitorProduct;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable input snapshot and pagination model for a PDF report. */
public final class PdfReportData {
    private static final int CHART_PAGE_SIZE = 48;
    private static final double MMHG_PER_KPA = 7.50062;
    public final int selectedUser;
    public final boolean virtualUsers;
    public final Map<Integer,String> profileNames;
    public final LocalDate from, to;
    public final ZoneId zone;
    public final List<Entry> entries;
    public final List<Summary> summaries;
    public final List<ChartPage> charts;

    private PdfReportData(int selectedUser, LocalDate from, LocalDate to, ZoneId zone,
            List<Entry> entries, List<Summary> summaries, List<ChartPage> charts,boolean virtualUsers,Map<Integer,String> profileNames) {
        this.selectedUser = selectedUser;
        this.from = from;
        this.to = to;
        this.zone = zone;
        this.entries = immutableCopy(entries);
        this.summaries = immutableCopy(summaries);
        this.charts = immutableCopy(charts);
        this.virtualUsers=virtualUsers;
        this.profileNames=Collections.unmodifiableMap(new java.util.HashMap<>(profileNames));
    }

    public static PdfReportData create(List<Reading> source, int selectedUser,
            LocalDate from, LocalDate to, ZoneId zone) {
        return create(source,selectedUser,from,to,zone,Collections.emptyMap());
    }
    /** {@code models} maps a saved monitor address to its reported model, for the device column. */
    public static PdfReportData create(List<Reading> source,int selectedUser,LocalDate from,LocalDate to,ZoneId zone,Map<String,String> models) {
        return createInternal(source,selectedUser,from,to,zone,false,Collections.emptyMap(),models);
    }
    private static PdfReportData createInternal(List<Reading> source,int selectedUser,LocalDate from,LocalDate to,ZoneId zone,boolean virtualUsers,Map<Integer,String> names,Map<String,String> models) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(zone, "zone");
        if (virtualUsers ? selectedUser>0 || selectedUser==Integer.MIN_VALUE : selectedUser < 0 || selectedUser > 2) {
            throw new IllegalArgumentException("Profile must be all, 1, or 2");
        }
        if ((from == null) != (to == null) || (from != null && from.isAfter(to))) {
            throw new IllegalArgumentException("Both dates are required, in chronological order");
        }
        List<Entry> entries = new ArrayList<>();
        for (Reading reading : source) {
            Entry entry = new Entry(Objects.requireNonNull(reading, "reading"), zone,virtualUsers,models.get(reading.device));
            if (selectedUser != 0 && !Integer.valueOf(selectedUser).equals(entry.user)) continue;
            LocalDate date = entry.displayTime.toLocalDate();
            if (from != null && (date.isBefore(from) || date.isAfter(to))) continue;
            entries.add(entry);
        }
        entries.sort(Comparator.comparing((Entry entry) -> entry.displayTime)
                .thenComparingLong(entry -> entry.id));
        Map<Integer, List<Entry>> profiles = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        for (Entry entry : entries) {
            profiles.computeIfAbsent(entry.user, ignored -> new ArrayList<>()).add(entry);
        }
        List<Summary> summaries = new ArrayList<>();
        List<ChartPage> charts = new ArrayList<>();
        for (Map.Entry<Integer, List<Entry>> profile : profiles.entrySet()) {
            List<Entry> rows = profile.getValue();
            summaries.add(new Summary(profile.getKey(), rows));
            for (int start = 0; start < rows.size(); start += CHART_PAGE_SIZE) {
                charts.add(new ChartPage(profile.getKey(),
                        rows.subList(start, Math.min(start + CHART_PAGE_SIZE, rows.size()))));
            }
        }
        return new PdfReportData(selectedUser, from, to, zone, entries, summaries, charts,virtualUsers,names);
    }
    public static PdfReportData createVirtual(List<Reading> source,int selectedUser,LocalDate from,LocalDate to,ZoneId zone,Map<Integer,String> names) {
        return createVirtual(source,selectedUser,from,to,zone,names,Collections.emptyMap());
    }
    public static PdfReportData createVirtual(List<Reading> source,int selectedUser,LocalDate from,LocalDate to,ZoneId zone,Map<Integer,String> names,Map<String,String> models) {
        return createInternal(source,selectedUser,from,to,zone,true,names,models);
    }

    public static final class Entry {
        public final long id, received;
        public final LocalDateTime time, displayTime;
        public final double systolic, diastolic;
        public final Double pulse, map;
        public final Integer user, status;
        public final Boolean afib;
        public final String device, name, source, notes, unit;
        /** Product name when known, else manufacturer and reported model, else the Bluetooth name. */
        public final String displayName;

        private Entry(Reading reading, ZoneId zone,boolean virtualUsers,String reportedModel) {
            id = reading.id;
            received = reading.received;
            time = parseTime(reading.time);
            displayTime = time != null ? time : LocalDateTime.ofInstant(Instant.ofEpochMilli(received), zone);
            systolic = reading.systolic;
            diastolic = reading.diastolic;
            pulse = reading.pulse;
            map = reading.map;
            user = virtualUsers ? reading.virtualUserId==null?null:-reading.virtualUserId : reading.user;
            status = reading.status;
            afib = reading.afib;
            device = safeText(reading.device);
            name = safeText(reading.name);
            displayName = safeText(MonitorProduct.displayName(reportedModel, name));
            source = safeText(reading.source);
            notes = safeText(reading.notes);
            unit = safeText(reading.unit);
        }

        public double sysMmHg() { return "kPa".equals(unit) ? systolic * MMHG_PER_KPA : systolic; }
        public double diaMmHg() { return "kPa".equals(unit) ? diastolic * MMHG_PER_KPA : diastolic; }
    }

    /** Pressure statistics are in mmHg, with NaN for unavailable statistics. */
    public static final class Summary {
        public final Integer user;
        public final int count, pressureCount, pulseCount, missingTimeCount;
        public final double systolicAverage, diastolicAverage;
        public final double systolicMin, systolicMax, diastolicMin, diastolicMax;
        public final double pulseAverage, pulseMin, pulseMax;

        private Summary(Integer user, List<Entry> entries) {
            this.user = user;
            count = entries.size();
            Statistics sys = new Statistics();
            Statistics dia = new Statistics();
            Statistics pulse = new Statistics();
            int missing = 0;
            for (Entry entry : entries) {
                double systolic = entry.sysMmHg();
                double diastolic = entry.diaMmHg();
                if (Double.isFinite(systolic) && Double.isFinite(diastolic)) {
                    sys.add(systolic);
                    dia.add(diastolic);
                }
                if (entry.pulse != null && Double.isFinite(entry.pulse)) pulse.add(entry.pulse);
                if (entry.time == null) missing++;
            }
            pressureCount = sys.count;
            pulseCount = pulse.count;
            missingTimeCount = missing;
            systolicAverage = sys.average;
            diastolicAverage = dia.average;
            systolicMin = sys.min;
            systolicMax = sys.max;
            diastolicMin = dia.min;
            diastolicMax = dia.max;
            pulseAverage = pulse.average;
            pulseMin = pulse.min;
            pulseMax = pulse.max;
        }
    }

    public static final class ChartPage {
        public final Integer user;
        public final List<Entry> entries;

        private ChartPage(Integer user, List<Entry> entries) {
            this.user = user;
            this.entries = immutableCopy(entries);
        }
    }

    private static final class Statistics {
        int count;
        double average = Double.NaN, min = Double.NaN, max = Double.NaN;

        void add(double value) {
            count++;
            if (count == 1) {
                average = min = max = value;
            } else {
                average = average * ((count - 1.0) / count) + value / count;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
    }

    private static LocalDateTime parseTime(String time) {
        if (time == null) return null;
        try {
            return LocalDateTime.parse(time);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
