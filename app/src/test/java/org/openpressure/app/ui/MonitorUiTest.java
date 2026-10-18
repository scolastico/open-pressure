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
}
