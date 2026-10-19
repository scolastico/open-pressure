package org.openpressure.app.ble;

import android.app.Application;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import static org.junit.Assert.*;

/** The optional clock write uses the phone's current time zone, including one changed while the service runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class MonitorServiceClockTest {
    private final TimeZone original=TimeZone.getDefault();
    @After public void restore() { TimeZone.setDefault(original); }

    @Test public void clockWriteFollowsATimeZoneChangedAfterStart() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        MonitorService monitor=Robolectric.buildService(MonitorService.class).get();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        LocalDateTime expected=LocalDateTime.now(ZoneId.of("America/New_York"));
        long skew=Math.abs(java.time.Duration.between(expected,monitor.clockNow()).toMinutes());
        assertTrue("clock write uses the zone at start-up: skew "+skew+" min",skew<2);
    }

    @Test public void fixturesCanPinTheClock() {
        MonitorService monitor=Robolectric.buildService(MonitorService.class).get();
        monitor.clock=Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"),ZoneId.of("UTC"));
        assertEquals(LocalDateTime.parse("2026-01-02T03:04:05"),monitor.clockNow());
    }
}
