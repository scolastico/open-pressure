package org.openpressure.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.work.WorkManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.ble.MonitorService;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.updates.UpdateCheck;
import org.openpressure.protocol.ProtocolProfile;
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
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/** Monitors tab: which users' readings to import, and the Read all action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainImportControlsTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainImportControlsTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private static final String ADDRESS="00:11:22:33:44:55",SAVED="66:77:88:99:AA:BB";
    private PressureApp app;
    private MonitorService monitor;
    @Before public void setup() {
        app=(PressureApp)RuntimeEnvironment.getApplication();
        ExperimentalConsent.accept(app); UpdateCheck.setEnabled(app,false); AutoImportSettings.setMode(app,AutoImportSettings.OFF);
        monitor=Robolectric.buildService(MonitorService.class).create().get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,MonitorService.class),monitor.onBind(new Intent(app,MonitorService.class)));
    }
    @After public void close() { store().close(); }
    private ReadingStore store() { return app.store(); }
    private void connected(String model) throws Exception {
        monitor.ready=true; monitor.canHistory=true; monitor.profile=ProtocolProfile.VENDOR_MEMORY;
        monitor.address=ADDRESS; monitor.connectedName=model; monitor.status="Connected";
        Field verified=MonitorService.class.getDeclaredField("verifiedModel"); verified.setAccessible(true); verified.set(monitor,model);
        store().remember(ADDRESS,model,model,1);
    }
    private Activity open(ActivityController<MainActivity> controller) {
        controller.setup(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        Activity a=controller.get(); button(root(a),a.getString(R.string.main_devices)).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        return a;
    }
    private static View root(Activity a) { return a.findViewById(android.R.id.content); }
    private static Button button(View root,String label) {
        if(root instanceof Button && ((Button)root).getText().toString().equals(label)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { Button found=button(((ViewGroup)root).getChildAt(i),label); if(found!=null) return found; }
        return null;
    }
    private static boolean hasText(View root,String value) {
        if(root instanceof TextView && !(root instanceof Button) && ((TextView)root).getText().toString().equals(value)) return true;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) if(hasText(((ViewGroup)root).getChildAt(i),value)) return true;
        return false;
    }
    private static Button choice(Activity a,int users) { return (Button)root(a).findViewWithTag("import_users_"+users); }
    private static Button savedChoice(Activity a,String address,int users) { return (Button)root(a).findViewWithTag("import_users_"+address+"_"+users); }

    @Test public void twoUserMonitorOffersTheChoiceAndReadAll() throws Exception {
        connected("HEM-7155T");
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertTrue("The choice is introduced in plain language",hasText(root(a),a.getString(R.string.main_import_users)));
            assertNotNull(choice(a,1)); assertNotNull(choice(a,2)); assertNotNull(choice(a,3));
            assertEquals(a.getString(R.string.main_user_number,"1"),choice(a,1).getText().toString());
            assertEquals(a.getString(R.string.main_user_number,"2"),choice(a,2).getText().toString());
            assertEquals(a.getString(R.string.main_import_users_both),choice(a,3).getText().toString());
            assertTrue("Both users is the default",choice(a,3).isSelected()); assertFalse(choice(a,2).isSelected());
            choice(a,2).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2,store().importUsers(ADDRESS)); assertEquals(2,monitor.importUsers);
            assertNull("The connected monitor has one choice, not a second one on its saved card",savedChoice(a,ADDRESS,2));
            assertTrue(choice(a,2).isSelected()); assertFalse(choice(a,3).isSelected());
            Button readAll=button(root(a),a.getString(R.string.main_read_all));
            assertNotNull("Read all sits with the import action",readAll); assertTrue(readAll.isEnabled());
            readAll.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog confirm=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(a.getString(R.string.main_read_all_explanation),((TextView)confirm.findViewById(android.R.id.message)).getText().toString());
        }
    }
    @Test public void oneUserMonitorShowsNoChoice() throws Exception {
        connected("HEM-7156T");
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertNull(choice(a,1)); assertNull(choice(a,2)); assertNull(choice(a,3));
            assertNull(savedChoice(a,ADDRESS,2));
            assertNotNull("Read all is still offered for one user",button(root(a),a.getString(R.string.main_read_all)));
        }
    }
    @Test public void readAllIsDisabledWhileHistoryRuns() throws Exception {
        connected("HEM-7155T"); monitor.historyRunning=true; monitor.importMode=MonitorService.ImportMode.FULL_SWEEP;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            Button readAll=button(root(a),a.getString(R.string.main_reading_all));
            assertNotNull(readAll); assertFalse(readAll.isEnabled());
            assertFalse("The choice cannot change during an import",choice(a,2).isEnabled());
        }
    }
    @Test public void savedAndActiveCardsShowTheProductName() throws Exception {
        store().remember(SAVED,"BLEsmart_00000001","HEM-7380T1-EOSL",1);
        store().remember("CC:DD:EE:FF:00:11","BLEsmart_00000002","X7 Smart AFib \u00b7 HEM-7380T1",1);
        connected("HEM-7155T-EBK"); monitor.connectedName="BLEsmart_00000003"; store().remember(ADDRESS,"BLEsmart_00000003","HEM-7155T-EBK",1);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            String user1=a.getString(R.string.main_user_number,"1");
            assertTrue("X7 product name as title",hasText(root(a),"OMRON X7 Smart AFib"));
            assertTrue("the Bluetooth name stays beside the address",hasText(root(a),a.getString(R.string.main_saved_monitor_detail,"BLEsmart_00000001",a.getString(R.string.main_saved_monitor_detail,SAVED,user1))));
            assertTrue("an unverified hint falls back to the Bluetooth name",hasText(root(a),"BLEsmart_00000002"));
            assertFalse(hasText(root(a),"X7 Smart AFib \u00b7 HEM-7380T1"));
            assertTrue(hasText(root(a),a.getString(R.string.main_saved_monitor_detail,"CC:DD:EE:FF:00:11",user1)));
            assertTrue("active card: manufacturer and model",hasText(root(a),"OMRON HEM-7155T-EBK"));
            assertTrue("active card keeps the Bluetooth name",hasText(root(a),"BLEsmart_00000003"));
        }
    }
    @Test public void reportedModelNamesASavedStandardMonitorWithoutCapabilities() throws Exception {
        String hint="X7 Smart AFib \u00b7 HEM-7380T1",standard="BLEsmart_00000004";
        store().remember(SAVED,standard,hint,1); store().setReportedModel(SAVED,"HEM-7155T-EBK");
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            String user1=a.getString(R.string.main_user_number,"1");
            assertTrue("the reported model names the saved card",hasText(root(a),"OMRON HEM-7155T-EBK"));
            assertTrue(hasText(root(a),a.getString(R.string.main_saved_monitor_detail,standard,a.getString(R.string.main_saved_monitor_detail,SAVED,user1))));
            assertNull("no Readings to import choice from a reported model",savedChoice(a,SAVED,1));
            assertNull(savedChoice(a,SAVED,3));
            button(root(a),a.getString(R.string.main_forget_monitor)).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog forget=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(a.getString(R.string.main_forget_title,"OMRON HEM-7155T-EBK"),Shadows.shadowOf(forget).getTitle().toString());
            forget.dismiss();
        }
        assertTrue("no companion listing from a reported model",new org.openpressure.app.automatic.CompanionLinks(app).knownMonitors().isEmpty());
        assertEquals("the capability model is unchanged",hint,store().devices().get(0)[2]);
    }
    @Test public void savedTwoUserMonitorChoiceWritesOnlyTheStore() throws Exception {
        store().remember(SAVED,"Kitchen","HEM-7361T",1); store().remember("CC:DD:EE:FF:00:11","Hall","HEM-7150T",1);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertNull("One-user saved monitors have no choice",savedChoice(a,"CC:DD:EE:FF:00:11",1));
            Button user1=savedChoice(a,SAVED,1); assertNotNull(user1); assertTrue(savedChoice(a,SAVED,3).isSelected());
            user1.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,store().importUsers(SAVED)); assertEquals(3,monitor.importUsers);
            assertTrue(savedChoice(a,SAVED,1).isSelected());
        }
    }
    @Test public void savedX7OffersTheChoice() {
        store().remember(SAVED,"X7","HEM-7380T1",1);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertNotNull(savedChoice(a,SAVED,2));
        }
    }
}
