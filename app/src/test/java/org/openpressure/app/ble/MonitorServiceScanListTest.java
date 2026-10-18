package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Looper;
import android.os.ParcelUuid;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.data.ReadingStore;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.*;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Scan results feed the real service and SQLite store; saved monitors stay on their saved card. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceScanListTest {
    private static final String SAVED="00:11:22:33:44:55",UNKNOWN="00:11:22:33:44:66";
    private MonitorService monitor;
    private ReadingStore store;

    @Before public void setup() throws Exception {
        monitor=Robolectric.buildService(MonitorService.class).get();
        store=new ReadingStore(monitor);
        set("store",store); set("scanning",true);
        store.remember(SAVED,"BLEsmart_00000000SAVED","HEM-7155T",1);
    }
    @Test public void savedMonitorsAreNotListedAndReappearAfterForget() throws Exception {
        call("addScan",result(SAVED)); call("addScan",result(UNKNOWN));
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("a saved monitor is reconnected from its saved card",monitor.discovered.containsKey(SAVED));
        assertTrue("an unknown monitor is offered",monitor.discovered.containsKey(UNKNOWN));
        store.forget(SAVED);
        call("addScan",result(SAVED));
        assertTrue("a forgotten monitor appears with its next advertisement",monitor.discovered.containsKey(SAVED));
    }
    @Test public void monitorSavedDuringTheScanLeavesTheList() throws Exception {
        store.forget(SAVED);
        call("addScan",result(SAVED)); assertTrue(monitor.discovered.containsKey(SAVED));
        // Connect from the scan list: verifying the model saves the monitor, which leaves the list.
        monitor.address=SAVED; monitor.connectedName="BLEsmart_00000000SAVED";
        try { Method remember=MonitorService.class.getDeclaredMethod("rememberMonitor",String.class); remember.setAccessible(true); remember.invoke(monitor,"HEM-7155T"); }
        catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
        assertTrue(store.isSaved(SAVED)); assertFalse(monitor.discovered.containsKey(SAVED));
        call("addScan",result(SAVED));
        assertFalse("later advertisements keep it off the list",monitor.discovered.containsKey(SAVED));
    }
    private static ScanResult result(String address) {
        BluetoothDevice device=mock(BluetoothDevice.class); when(device.getAddress()).thenReturn(address);
        ScanRecord record=mock(ScanRecord.class);
        when(record.getDeviceName()).thenReturn("BLEsmart_00000000"+address.substring(12).replace(":",""));
        when(record.getServiceUuids()).thenReturn(Collections.singletonList(new ParcelUuid(Uuids.BP)));
        ScanResult result=mock(ScanResult.class); when(result.getDevice()).thenReturn(device); when(result.getScanRecord()).thenReturn(record); when(result.getRssi()).thenReturn(-60);
        return result;
    }
    private void set(String name,Object value) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); field.set(monitor,value); }
    private void call(String name,ScanResult result) {
        try { Method method=MonitorService.class.getDeclaredMethod(name,ScanResult.class); method.setAccessible(true); method.invoke(monitor,result); }
        catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
