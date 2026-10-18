package org.openpressure.app.ble;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import androidx.work.WorkManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.ui.MonitorUi;
import org.openpressure.protocol.AutoImportTracker;
import org.openpressure.protocol.OmronAdvertisement;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBluetoothLeScanner;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Fetch now through the real service: its bounded scan, queue and the automatic connection path with real
 * callback ordering. Only the Bluetooth scanner results and the GATT peer's connection events are simulated.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MonitorServiceFetchNowTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceFetchNowTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private static final String X7="AA:BB:CC:DD:EE:01",VENDOR="AA:BB:CC:DD:EE:02";
    /** Plain type 6 record summaries; the second has a newer sequence for user 1. */
    private static final String PLAIN="0601010001010001",NEWER="0601020002010001",ENCRYPTED="08410007340278010100000000";
    private PressureApp app;
    private MonitorService monitor;
    private ReadingStore store;
    private BluetoothAdapter adapter;

    @Before public void setup() {
        app=(PressureApp)RuntimeEnvironment.getApplication();
        ExperimentalConsent.accept(app); AutoImportSettings.setMode(app,AutoImportSettings.OFF);
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT);
        adapter=app.getSystemService(BluetoothManager.class).getAdapter(); shadowOf(adapter).setEnabled(true);
        monitor=Robolectric.buildService(MonitorService.class).create().get();
        store=app.store();
        store.remember(X7,"BLEsmart_00000001","HEM-7380T1-EOSL",1);
        store.remember(VENDOR,"BLEsmart_00000002","HEM-7155T",1);
        // Only the presence of a key saved by an explicit Pair is checked before connecting.
        monitor.getSharedPreferences("pairing",Context.MODE_PRIVATE).edit().putString(VENDOR+"/vendor/HEM-7155T","synthetic").commit();
    }
    @After public void cleanup() { monitor.onDestroy(); store.close(); }

    @Test public void fetchScansEverySavedMonitorEvenWithAutomaticImportsOff() {
        monitor.fetchNow(); idle();
        assertTrue(monitor.fetchRunning); assertEquals(MonitorService.FetchState.LOOKING,monitor.fetchState);
        ShadowBluetoothLeScanner.ScanParams scan=fetchScan();
        Set<String> filtered=new HashSet<>();
        for(ScanFilter filter:scan.scanFilters()) filtered.add(filter.getDeviceAddress());
        assertEquals(new HashSet<>(Arrays.asList(X7,VENDOR)),filtered);
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY,scan.scanSettings().getScanMode());
        assertEquals("one scan: the fetch scan only",1,activeScans().size());
        assertEquals("the mode is never changed",AutoImportSettings.OFF,AutoImportSettings.getMode(app));
        assertEquals(app.getString(R.string.fetch_looking),MonitorUi.automatic(app,monitor));
        assertFalse(monitor.scanning);
    }
    @Test public void onlyQualifyingAdvertisementsAreQueuedAndEachOnlyOnce() throws Exception {
        monitor.fetchNow(); idle();
        ScanCallback scan=fetchScan().scanCallback();
        scan.onScanResult(1,result(X7,ENCRYPTED,true)); idle();
        scan.onScanResult(1,result(VENDOR,PLAIN,false)); idle();
        scan.onScanResult(1,result("AA:BB:CC:DD:EE:99",PLAIN,true)); idle();
        assertNull("encrypted, unbonded and unsaved monitors are ignored",get("gatt"));
        scan.onBatchScanResults(Arrays.asList(result(X7,PLAIN,true),result(X7,NEWER,true))); idle();
        BluetoothGatt first=gatt(X7);
        assertEquals(MonitorService.FetchState.IMPORTING,monitor.fetchState);
        assertEquals(app.getString(R.string.fetch_importing,"OMRON X7 Smart AFib"),MonitorUi.automatic(app,monitor));
        AutoImportTracker.Attempt attempt=(AutoImportTracker.Attempt)get("automaticAttempt");
        assertEquals("the payload captured when queued",advertisement(PLAIN).revision(),attempt.revision);
        assertTrue((Boolean)get("automaticConnection"));
        assertFalse("never a Pair",(Boolean)get("pairingRequested"));
        disconnected(first);
        // Scanning resumes between imports; a monitor already handled in this fetch is not queued again.
        fetchScan().scanCallback().onScanResult(1,result(X7,NEWER,true)); idle();
        assertNull(get("gatt")); assertTrue(monitor.fetchRunning);
    }
    @Test public void importsRunOneAtATimeAndAFailureContinuesWithTheNext() throws Exception {
        monitor.fetchNow(); idle();
        fetchScan().scanCallback().onBatchScanResults(Arrays.asList(result(X7,PLAIN,true),result(VENDOR,PLAIN,true))); idle();
        BluetoothGatt first=gatt(X7);
        assertEquals("the second monitor waits; no scan runs during an import",0,activeScans().size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertSame("still the first connection",first,get("gatt"));
        disconnected(first);
        BluetoothGatt second=gatt(VENDOR);
        assertNotSame(first,second);
        assertEquals("the saved user and Readings to import choice",3,monitor.importUsers);
        validatedEnd(second);
        assertNull(get("gatt"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertFalse(monitor.fetchRunning);
        assertEquals(1,monitor.fetchImported); assertEquals(1,monitor.fetchIncomplete);
        assertEquals(MonitorService.FetchState.INCOMPLETE,monitor.fetchState);
        assertEquals(advertisement(PLAIN).revision(),checkpoint(VENDOR)); assertNull(checkpoint(X7));
        assertTrue("the failed forced import sets the automatic cooldown",tracker().offer(X7,advertisement(NEWER),android.os.SystemClock.elapsedRealtime())==null);
        assertEquals(0,activeScans().size());
        assertTrue(MonitorUi.automatic(app,monitor).startsWith(app.getString(R.string.fetch_incomplete)));
    }
    @Test public void aSuccessfulImportEndsWithTheMonitorCount() throws Exception {
        monitor.fetchNow(); idle();
        fetchScan().scanCallback().onScanResult(1,result(X7,PLAIN,true)); idle();
        validatedEnd(gatt(X7));
        assertTrue("the window is still open",monitor.fetchRunning); assertNotNull(fetchScan());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        assertFalse(monitor.fetchRunning); assertEquals(MonitorService.FetchState.DONE,monitor.fetchState);
        assertEquals(1,monitor.fetchImported); assertEquals(0,monitor.fetchIncomplete);
        assertEquals(advertisement(PLAIN).revision(),checkpoint(X7));
        assertTrue(MonitorUi.automatic(app,monitor).startsWith(app.getString(R.string.fetch_done,1)));
    }
    @Test public void aMonitorAdvertisingLaterInTheWindowIsStillImported() throws Exception {
        monitor.fetchNow(); idle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        fetchScan().scanCallback().onScanResult(1,result(VENDOR,PLAIN,true)); idle();
        assertNotNull(gatt(VENDOR));
    }
    @Test public void windowExpiryWithoutAMonitorEndsTheFetch() {
        monitor.fetchNow(); idle();
        ScanCallback scan=fetchScan().scanCallback();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MonitorService.FETCH_WINDOW_MS));
        assertFalse(monitor.fetchRunning); assertEquals(MonitorService.FetchState.NONE_NEARBY,monitor.fetchState);
        assertTrue("the scan callback is released",activeScans().isEmpty());
        assertTrue(MonitorUi.automatic(app,monitor).startsWith(app.getString(R.string.fetch_none_nearby)));
        scan.onScanResult(1,result(X7,PLAIN,true)); idle();
        assertNull("a late result from the ended fetch is ignored",getQuiet("gatt"));
    }
    @Test public void userDisconnectCancelsTheQueue() throws Exception {
        monitor.fetchNow(); idle();
        fetchScan().scanCallback().onBatchScanResults(Arrays.asList(result(X7,PLAIN,true),result(VENDOR,PLAIN,true))); idle();
        BluetoothGatt first=gatt(X7);
        monitor.disconnect(); idle();
        assertFalse(monitor.fetchRunning); assertEquals(MonitorService.FetchState.NONE,monitor.fetchState);
        callback().onConnectionStateChange(first,0,BluetoothProfile.STATE_DISCONNECTED);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertNull("the queued monitor is not imported",get("gatt"));
        assertTrue(activeScans().isEmpty());
        assertFalse(tracker().busy());
    }
    @Test public void manualScanAndBluetoothOffEndTheFetch() {
        monitor.fetchNow(); idle();
        monitor.scan(); idle();
        assertFalse(monitor.fetchRunning); assertTrue(monitor.scanning);
        assertEquals("only the manual scan runs",1,activeScans().size()); assertNull(getQuiet("fetchScan"));
        monitor.stopScan(); idle();
        monitor.fetchNow(); idle(); assertTrue(monitor.fetchRunning);
        shadowOf(adapter).setEnabled(false);
        app.sendBroadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)); idle();
        assertFalse(monitor.fetchRunning); assertEquals(MonitorService.FetchState.BLUETOOTH_OFF,monitor.fetchState);
        assertTrue(activeScans().isEmpty());
    }
    @Test public void callbacksFromAnEarlierFetchAreIgnored() throws Exception {
        monitor.fetchNow(); idle();
        fetchScan().scanCallback().onScanResult(1,result(X7,PLAIN,true)); idle();
        BluetoothGatt old=gatt(X7);
        monitor.disconnect(); idle();
        monitor.fetchNow(); idle();
        ScanCallback current=fetchScan().scanCallback();
        callback().onConnectionStateChange(old,0,BluetoothProfile.STATE_DISCONNECTED); idle();
        assertTrue("the old connection's end does not touch the new fetch",monitor.fetchRunning);
        assertNull(get("gatt")); assertSame(current,fetchScan().scanCallback());
        ScanCallback stale=(ScanCallback)get("fetchScan");
        monitor.disconnect(); idle(); monitor.fetchNow(); idle();
        stale.onScanResult(1,result(VENDOR,PLAIN,true)); idle();
        assertNull("a result from the previous fetch's scan is ignored",get("gatt"));
        fetchScan().scanCallback().onScanResult(1,result(VENDOR,PLAIN,true)); idle();
        assertNotNull(gatt(VENDOR));
    }
    @Test public void fetchIsRefusedDuringAManualConnectionOrWithoutConsent() throws Exception {
        set("gatt",mock(BluetoothGatt.class));
        monitor.fetchNow(); idle();
        assertFalse(monitor.fetchRunning); assertEquals(MonitorService.FetchState.BUSY,monitor.fetchState);
        assertTrue(activeScans().isEmpty());
        assertEquals(app.getString(R.string.fetch_busy)+"\n\n"+app.getString(R.string.monitor_auto_off),MonitorUi.automatic(app,monitor));
        set("gatt",null);
        app.getSharedPreferences("experimental_consent",Context.MODE_PRIVATE).edit().clear().commit();
        assertFalse(ExperimentalConsent.accepted(app));
        monitor.fetchNow(); idle();
        assertFalse(monitor.fetchRunning); assertEquals(MonitorService.FetchState.PERMISSION,monitor.fetchState);
        assertTrue(activeScans().isEmpty());
    }
    @Test public void fetchIsRefusedWithoutAnEligibleMonitor() {
        store.forget(X7); store.forget(VENDOR); store.remember(X7,"Standard","Other monitor",1);
        monitor.fetchNow(); idle();
        assertFalse(monitor.fetchRunning); assertEquals(MonitorService.FetchState.NO_MONITOR,monitor.fetchState);
    }
    @Test public void theForcedAttemptImportsACompletedRevision() throws Exception {
        String revision=advertisement(PLAIN).revision();
        app.getSharedPreferences("automatic-checkpoints",Context.MODE_PRIVATE).edit().putString(X7,revision).commit();
        tracker().restore(X7,revision);
        assertNull("an advertisement would not import it again",tracker().offer(X7,advertisement(PLAIN),0));
        monitor.fetchNow(); idle();
        fetchScan().scanCallback().onScanResult(1,result(X7,PLAIN,true)); idle();
        assertNotNull(gatt(X7));
        assertEquals(revision,((AutoImportTracker.Attempt)get("automaticAttempt")).revision);
    }

    private BluetoothGatt gatt(String address) {
        BluetoothGatt gatt=(BluetoothGatt)getQuiet("gatt");
        assertNotNull("a connection to "+address,gatt);
        assertEquals(address,gatt.getDevice().getAddress()); assertEquals(address,monitor.address);
        return gatt;
    }
    /** The monitor drops the link before the transfer completed. */
    private void disconnected(BluetoothGatt gatt) throws Exception {
        callback().onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle();
    }
    /** A validated END, then the monitor's normal disconnect: the order of a completed automatic import. */
    private void validatedEnd(BluetoothGatt gatt) throws Exception {
        Method finish=MonitorService.class.getDeclaredMethod("finishAutomaticAttempt",boolean.class); finish.setAccessible(true); finish.invoke(monitor,true);
        set("vendorSessionEnded",true);
        callback().onConnectionStateChange(gatt,0,BluetoothProfile.STATE_DISCONNECTED); idle();
    }
    private BluetoothGattCallback callback() throws Exception { return (BluetoothGattCallback)get("callback"); }
    private AutoImportTracker tracker() { return (AutoImportTracker)getQuiet("automaticTracker"); }
    private String checkpoint(String address) { return app.getSharedPreferences("automatic-checkpoints",Context.MODE_PRIVATE).getString(address,null); }
    private List<ShadowBluetoothLeScanner.ScanParams> activeScans() { return shadowOf(adapter.getBluetoothLeScanner()).getActiveScans(); }
    private ShadowBluetoothLeScanner.ScanParams fetchScan() {
        Object current=getQuiet("fetchScan");
        for(ShadowBluetoothLeScanner.ScanParams scan:activeScans()) if(scan.scanCallback()==current) return scan;
        return null;
    }
    private static ScanResult result(String address,String payload,boolean bonded) {
        BluetoothDevice device=mock(BluetoothDevice.class);
        when(device.getAddress()).thenReturn(address); when(device.getBondState()).thenReturn(bonded?BluetoothDevice.BOND_BONDED:BluetoothDevice.BOND_NONE);
        ScanRecord record=mock(ScanRecord.class); when(record.getManufacturerSpecificData(0x020e)).thenReturn(HexFormat.of().parseHex(payload));
        ScanResult result=mock(ScanResult.class); when(result.getDevice()).thenReturn(device); when(result.getScanRecord()).thenReturn(record);
        return result;
    }
    private static OmronAdvertisement advertisement(String payload) { return OmronAdvertisement.parseManufacturerPayload(HexFormat.of().parseHex(payload)); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private Object get(String name) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); return field.get(monitor); }
    private Object getQuiet(String name) { try { return get(name); } catch(Exception failure) { throw new AssertionError(failure); } }
    private void set(String name,Object value) throws Exception { Field field=MonitorService.class.getDeclaredField(name); field.setAccessible(true); field.set(monitor,value); }
}
