package org.openpressure.app.ble;

import android.app.Application;
import android.bluetooth.*;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Looper;
import android.os.ParcelUuid;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.data.Reading;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.pdf.PdfReportData;
import org.openpressure.protocol.OperationQueue;
import org.openpressure.protocol.ProtocolProfile;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Real service and SQLite store: scan results leave saved monitors out; standard profiles keep the reported model for display only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceScanListTest {
    private static final String SAVED="00:11:22:33:44:55",UNKNOWN="00:11:22:33:44:66",NAME="BLEsmart_00000000SAVED";
    private MonitorService monitor;
    private ReadingStore store;

    @Before public void setup() throws Exception {
        monitor=Robolectric.buildService(MonitorService.class).get();
        store=new ReadingStore(monitor);
        set("store",store);
        store.remember(SAVED,NAME,"HEM-7155T",1);
    }
    @After public void cleanup() { store.close(); }

    @Test public void savedMonitorsAreNotListedAndReappearAfterForget() throws Exception {
        startScan();
        call("addScan",ScanResult.class,result(SAVED)); call("addScan",ScanResult.class,result(UNKNOWN));
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("a saved monitor is reconnected from its saved card",monitor.discovered.containsKey(SAVED));
        assertTrue("an unknown monitor is offered",monitor.discovered.containsKey(UNKNOWN));
        store.forget(SAVED); monitor.monitorForgotten(SAVED);
        call("addScan",ScanResult.class,result(SAVED));
        assertTrue("a forgotten monitor appears with its next advertisement",monitor.discovered.containsKey(SAVED));
    }
    @Test public void monitorSavedDuringTheScanLeavesTheList() throws Exception {
        store.forget(SAVED); startScan();
        call("addScan",ScanResult.class,result(SAVED)); assertTrue(monitor.discovered.containsKey(SAVED));
        // Connect from the scan list: the session saves the monitor, which leaves the list.
        monitor.address=SAVED; monitor.connectedName=NAME;
        call("rememberMonitor",String.class,"HEM-7155T");
        assertEquals(1,store.devices().size()); assertFalse(monitor.discovered.containsKey(SAVED));
        call("addScan",ScanResult.class,result(SAVED));
        assertFalse("later advertisements keep it off the list",monitor.discovered.containsKey(SAVED));
    }
    @Test public void standardProfileReportedModelNamesTheMonitorWithoutGrantingAutomaticImports() throws Exception {
        store.forget(SAVED);
        BluetoothGatt gatt=mock(BluetoothGatt.class);
        BluetoothGattService dis=new BluetoothGattService(Uuids.sig(0x180a),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic model=new BluetoothGattCharacteristic(Uuids.sig(0x2a24),BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ);
        dis.addCharacteristic(model);
        when(gatt.getService(Uuids.sig(0x180a))).thenReturn(dis);
        when(gatt.readCharacteristic(any(BluetoothGattCharacteristic.class))).thenReturn(true);
        set("gatt",gatt); set("operations",new OperationQueue(error -> {}));
        monitor.profile=ProtocolProfile.STANDARD_BP; monitor.address=SAVED; monitor.connectedName=NAME;
        assertEquals("X7 Smart AFib · HEM-7380T1",monitor.modelHint);
        Method configureNext=MonitorService.class.getDeclaredMethod("configureNext",List.class,int.class); configureNext.setAccessible(true);
        configureNext.invoke(monitor,new ArrayList<BluetoothGattCharacteristic>(),0);
        assertEquals("standard profiles save the hint",monitor.modelHint,store.devices().get(0)[2]);
        assertEquals("an unverified hint is never printed",NAME,pdfName());
        BluetoothGattCallback callback=(BluetoothGattCallback)get("callback");
        callback.onCharacteristicRead(gatt,model,"HEM-7155T-EBK\u0000".getBytes(StandardCharsets.UTF_8),BluetoothGatt.GATT_SUCCESS);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the capability model stays the hint",monitor.modelHint,store.devices().get(0)[2]);
        assertEquals("HEM-7155T-EBK",store.devices().get(0)[5]);
        assertEquals("OMRON HEM-7155T-EBK",pdfName());
        assertEquals("HEM-7155T-EBK",monitor.reportedModel());
        // A later save in the same session (user authentication) keeps both.
        call("rememberMonitor",String.class,monitor.modelHint);
        assertEquals(monitor.modelHint,store.devices().get(0)[2]); assertEquals("HEM-7155T-EBK",store.devices().get(0)[5]);
        monitor.refreshAutomaticImports();
        assertFalse("a reported model never makes a monitor automatic-import capable",((Map<?,?>)get("automaticDevices")).containsKey(SAVED));
    }

    private String pdfName() {
        Reading reading=new Reading(); reading.id=1; reading.user=1; reading.time="2026-01-01T08:00:00";
        reading.systolic=120; reading.diastolic=80; reading.unit="mmHg"; reading.device=SAVED; reading.name=NAME;
        Map<String,String> models=new HashMap<>(); for(String[] device:store.devices()) models.put(device[0],ReadingStore.displayModel(device));
        return PdfReportData.create(Collections.singletonList(reading),0,null,null,ZoneId.of("UTC"),models).entries.get(0).displayName;
    }
    private void startScan() throws Exception { set("scanning",true); call("loadSavedAddresses",null,null); }
    private static ScanResult result(String address) {
        BluetoothDevice device=mock(BluetoothDevice.class); when(device.getAddress()).thenReturn(address);
        ScanRecord record=mock(ScanRecord.class);
        when(record.getDeviceName()).thenReturn("BLEsmart_00000000"+address.substring(12).replace(":",""));
        when(record.getServiceUuids()).thenReturn(Collections.singletonList(new ParcelUuid(Uuids.BP)));
        ScanResult result=mock(ScanResult.class); when(result.getDevice()).thenReturn(device); when(result.getScanRecord()).thenReturn(record); when(result.getRssi()).thenReturn(-60);
        return result;
    }
    private Object get(String name) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); return field.get(monitor); }
    private void set(String name,Object value) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); field.set(monitor,value); }
    private Object call(String name,Class<?> type,Object argument) {
        try {
            Method method=type==null?MonitorService.class.getDeclaredMethod(name):MonitorService.class.getDeclaredMethod(name,type);
            method.setAccessible(true); return type==null?method.invoke(monitor):method.invoke(monitor,argument);
        } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
