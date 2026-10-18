package org.openpressure.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.WorkManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.ble.MonitorService;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.updates.UpdateCheck;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Field;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/** Overview: pulling the page down starts Fetch now, and the indicator follows the service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainOverviewRefreshTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainOverviewRefreshTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private PressureApp app;
    private MonitorService monitor;
    @Before public void setup() {
        app=(PressureApp)RuntimeEnvironment.getApplication();
        ExperimentalConsent.accept(app); UpdateCheck.setEnabled(app,false); AutoImportSettings.setMode(app,AutoImportSettings.OFF);
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf(app.getSystemService(BluetoothManager.class).getAdapter()).setEnabled(true);
        monitor=Robolectric.buildService(MonitorService.class).create().get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,MonitorService.class),monitor.onBind(new Intent(app,MonitorService.class)));
        app.store().remember("AA:BB:CC:DD:EE:01","BLEsmart_00000001","HEM-7380T1-EOSL",1);
    }
    @After public void close() { monitor.onDestroy(); app.store().close(); }

    @Test public void thePullIsOfferedOnOverviewOnly() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            SwipeRefreshLayout pull=pull(a); assertNotNull(pull);
            assertNotNull("the page scroll is inside the pull layout",find(pull,ScrollView.class));
            assertTrue(pull.isEnabled());
            click(a,R.string.main_history); assertFalse("History scrolls normally",pull(a).isEnabled());
            click(a,R.string.main_devices); assertFalse("Devices scrolls normally",pull(a).isEnabled());
            click(a,R.string.main_overview); assertTrue(pull(a).isEnabled());
        }
    }
    @Test public void releasingStartsFetchNowAndTheIndicatorFollowsTheService() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            SwipeRefreshLayout pull=pull(a);
            release(pull);
            assertTrue("the service is fetching",monitor.fetchRunning);
            assertTrue(pull.isRefreshing());
            assertTrue(text(a,a.getString(R.string.fetch_looking)));
            // The service ends the fetch: the window passes without an advertising monitor.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MonitorService.FETCH_WINDOW_MS+500));
            assertFalse(monitor.fetchRunning);
            assertFalse("the indicator clears with the fetch",pull(a).isRefreshing());
            assertTrue(text(a,a.getString(R.string.fetch_none_nearby)+"\n\n"+a.getString(R.string.monitor_auto_off)));
        }
    }
    @Test public void aRunningFetchKeepsTheIndicatorAfterRecreation() {
        monitor.fetchNow(); assertTrue(monitor.fetchRunning);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertTrue(pull(a).isRefreshing());
            click(a,R.string.main_history); assertFalse("no spinner over History",pull(a).isRefreshing());
            click(a,R.string.main_overview); assertTrue(pull(a).isRefreshing());
            monitor.disconnect(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertFalse(pull(a).isRefreshing());
        }
    }
    @Test public void withoutConsentTheIndicatorClearsAndTheNoticeAppears() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            app.getSharedPreferences("experimental_consent",Context.MODE_PRIVATE).edit().clear().commit();
            assertFalse(ExperimentalConsent.accepted(app));
            SwipeRefreshLayout pull=pull(a);
            release(pull);
            assertFalse(monitor.fetchRunning);
            assertFalse("the indicator clears at once",pull.isRefreshing());
            AlertDialog notice=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(notice); assertTrue("the first-use notice is shown",notice.isShowing());
        }
    }

    private Activity open(ActivityController<MainActivity> controller) {
        controller.setup(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }
    /** What a released pull past the trigger distance does: show the spinner and call the listener. */
    private static void release(SwipeRefreshLayout pull) throws Exception {
        pull.setRefreshing(true);
        Field listener=SwipeRefreshLayout.class.getDeclaredField("mListener"); listener.setAccessible(true);
        ((SwipeRefreshLayout.OnRefreshListener)listener.get(pull)).onRefresh();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void click(Activity a,int label) {
        Button button=find(root(a),Button.class,a.getString(label)); assertNotNull(button);
        button.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static View root(Activity a) { return a.findViewById(android.R.id.content); }
    private static SwipeRefreshLayout pull(Activity a) { return find(root(a),SwipeRefreshLayout.class); }
    private static boolean text(Activity a,String value) { return find(root(a),android.widget.TextView.class,value)!=null; }
    private static <T extends View> T find(View root,Class<T> type) { return find(root,type,null); }
    private static <T extends View> T find(View root,Class<T> type,String label) {
        if(type.isInstance(root) && (label==null || ((android.widget.TextView)root).getText().toString().equals(label))) return type.cast(root);
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { T found=find(((ViewGroup)root).getChildAt(i),type,label); if(found!=null) return found; }
        return null;
    }
}
