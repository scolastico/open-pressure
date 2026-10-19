package org.openpressure.app.ui;

import android.app.Application;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.R;
import org.openpressure.app.ble.MonitorService;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** The completed-import summary explains, in localized plain language, how the slots were chosen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorUiTest {
    private MonitorService monitor;
    @Before public void setup() {
        monitor=Robolectric.buildService(MonitorService.class).get();
        monitor.status="History transfer complete"; monitor.storedInTransfer=3;
    }
    private String detail() { return MonitorUi.detail(monitor,monitor); }
    private String index(int count) { return monitor.getResources().getQuantityString(R.plurals.monitor_import_mode_index,count,count); }
    @Test public void indexImportReportsTheMonitorsStoredCount() {
        monitor.importMode=MonitorService.ImportMode.INDEX; monitor.indexStoredCount=7;
        String detail=detail();
        assertTrue(detail,detail.contains(index(7)));
        assertTrue(detail.contains(monitor.getResources().getQuantityString(R.plurals.monitor_readings_saved,3,3)));
        assertFalse(detail.contains(monitor.getString(R.string.monitor_import_mode_empty)));
        assertFalse("Internal mode names never reach the summary",detail.contains("INDEX"));
    }
    @Test public void indexImportWithNothingStoredSuggestsReadAll() {
        monitor.importMode=MonitorService.ImportMode.INDEX; monitor.indexStoredCount=0; monitor.storedInTransfer=0;
        String detail=detail();
        assertTrue(detail,detail.contains(monitor.getString(R.string.monitor_import_mode_empty)));
        assertFalse(detail.contains(index(0)));
    }
    @Test public void fallbackImportSaysEverySlotWasRead() {
        monitor.importMode=MonitorService.ImportMode.INDEX_FALLBACK;
        assertTrue(detail().contains(monitor.getString(R.string.monitor_import_mode_fallback)));
        assertFalse(detail().contains(monitor.getString(R.string.monitor_import_mode_sweep)));
    }
    @Test public void sweepAndFullReadSayEverySlotWasRead() {
        monitor.importMode=MonitorService.ImportMode.FULL_SWEEP;
        assertTrue(detail().contains(monitor.getString(R.string.monitor_import_mode_sweep)));
        monitor.importMode=MonitorService.ImportMode.FULL_READ;
        assertTrue(detail().contains(monitor.getString(R.string.monitor_import_mode_sweep)));
    }
    @Test public void skippedUnconfirmedBankIsMentioned() {
        monitor.importMode=MonitorService.ImportMode.FULL_SWEEP;
        assertFalse(detail().contains(monitor.getString(R.string.monitor_unconfirmed_bank_skipped)));
        monitor.unconfirmedBankSkipped=true;
        assertTrue(detail().contains(monitor.getString(R.string.monitor_unconfirmed_bank_skipped)));
    }
    @Test public void unreadableSlotsAreCountedAfterAnEverySlotRead() throws Exception {
        monitor.importMode=MonitorService.ImportMode.FULL_SWEEP;
        String unreadable=monitor.getResources().getQuantityString(R.plurals.monitor_import_slots_unreadable,2,2);
        assertEquals("2 memory slots could not be read.",unreadable);
        assertFalse(detail().contains(unreadable));
        java.lang.reflect.Field rejected=MonitorService.class.getDeclaredField("wlpRejected"); rejected.setAccessible(true); rejected.set(monitor,2);
        String detail=detail();
        assertTrue(detail,detail.contains(monitor.getString(R.string.monitor_import_mode_sweep)+"\n\n"+unreadable));
        assertEquals("1 memory slot could not be read.",monitor.getResources().getQuantityString(R.plurals.monitor_import_slots_unreadable,1,1));
        monitor.importMode=MonitorService.ImportMode.NONE;
        assertFalse("other protocols never mention memory slots",detail().contains(unreadable));
    }
    @Test public void otherProtocolsAddNoModeText() {
        monitor.importMode=MonitorService.ImportMode.NONE;
        String detail=detail();
        for(int id:new int[]{R.string.monitor_import_mode_fallback,R.string.monitor_import_mode_sweep,R.string.monitor_import_mode_empty})
            assertFalse(detail.contains(monitor.getString(id)));
    }
    @Test public void runningImportShowsNoModeText() {
        monitor.historyRunning=true; monitor.importMode=MonitorService.ImportMode.FULL_SWEEP;
        assertFalse(detail().contains(monitor.getString(R.string.monitor_import_mode_sweep)));
    }
    private String title() { return MonitorUi.title(monitor,monitor); }
    private String text(int id) { return monitor.getString(id); }
    private void syncEnded(String status) { monitor.status=status; monitor.ready=false; monitor.sessionFinished=true; }
    @Test public void clockSyncRunningAndPendingStates() {
        monitor.ready=true; monitor.clockSyncRunning=true; monitor.status="Reading monitor settings…";
        assertEquals(text(R.string.main_sync_clock_running),title());
        assertEquals(text(R.string.monitor_keep_nearby),detail());
        monitor.clockSyncPending=true; monitor.status="Confirm clock sync";
        assertEquals(text(R.string.clock_sync_title),title());
        assertEquals(text(R.string.main_sync_clock_explanation),detail());
    }
    @Test public void clockSyncCompleteAsksToCheckTheClockAndReconnect() {
        syncEnded("Clock sync complete"); monitor.clockUpdate=MonitorService.ClockUpdate.ACCEPTED;
        assertEquals(text(R.string.monitor_clock_sync_complete),title());
        assertEquals(text(R.string.monitor_check_clock)+"\n\n"+text(R.string.monitor_clock_sync_reconnect),detail());
    }
    @Test public void clockSyncSkippedExplainsTheMinute() {
        syncEnded("Clock sync skipped"); monitor.clockUpdate=MonitorService.ClockUpdate.SKIPPED;
        assertEquals(text(R.string.monitor_clock_sync_skipped),title());
        assertEquals(text(R.string.clock_sync_within_threshold)+"\n\n"+text(R.string.monitor_clock_sync_reconnect),detail());
    }
    @Test public void clockSyncRejectedSaysTheTimeWasNotUpdated() {
        syncEnded("Clock sync rejected"); monitor.clockUpdate=MonitorService.ClockUpdate.FAILED;
        assertEquals(text(R.string.monitor_clock_sync_rejected),title());
        assertEquals(text(R.string.monitor_clock_failed)+"\n\n"+text(R.string.monitor_clock_sync_reconnect),detail());
    }
    @Test public void clockSyncCancelledBeforeOrAfterTheWrite() {
        syncEnded("Clock sync cancelled");
        assertEquals(text(R.string.monitor_clock_sync_cancelled),title());
        assertEquals(text(R.string.monitor_clock_sync_reconnect),detail());
        // A disconnect or missing acknowledgment leaves the clock unverified.
        monitor.sessionFinished=false; monitor.clockUpdate=MonitorService.ClockUpdate.FAILED;
        assertEquals(text(R.string.monitor_clock_sync_cancelled),title());
        assertEquals(text(R.string.monitor_clock_failed)+"\n\n"+text(R.string.monitor_clock_sync_reconnect),detail());
    }
    @Test public void unreadableSettingsEndTheSync() {
        monitor.ready=true; monitor.clockSyncRunning=true; monitor.status="Monitor settings unreadable";
        assertEquals("The outcome is shown while the session ends",text(R.string.monitor_clock_sync_unreadable),title());
        assertEquals(text(R.string.monitor_clock_failed)+"\n\n"+text(R.string.monitor_clock_sync_reconnect),detail());
        monitor.clockSyncRunning=false; syncEnded("Monitor settings unreadable");
        assertEquals(text(R.string.monitor_clock_sync_unreadable),title());
    }
    @Test public void clockSyncTextIsLocalized() {
        android.content.res.Configuration configuration=new android.content.res.Configuration(monitor.getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.forLanguageTag("ar"));
        android.content.Context arabic=monitor.createConfigurationContext(configuration);
        syncEnded("Clock sync complete");
        assertEquals(arabic.getString(R.string.monitor_clock_sync_complete),MonitorUi.title(arabic,monitor));
        assertNotEquals(text(R.string.monitor_clock_sync_complete),MonitorUi.title(arabic,monitor));
        assertFalse(MonitorUi.detail(arabic,monitor).contains("Reconnect"));
    }
}
