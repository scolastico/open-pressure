package org.openpressure.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.work.WorkManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.ble.MonitorService;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.updates.UpdateCheck;
import org.openpressure.protocol.DeviceReport;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Monitors tab: the Pair registration choice names the identity, warns under the developer override and offers the report. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainRegistrationTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainRegistrationTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private static final String ADDRESS="00:11:22:33:44:55";
    private PressureApp app;
    private MonitorService monitor;
    @Before public void setup() {
        app=(PressureApp)RuntimeEnvironment.getApplication();
        ExperimentalConsent.accept(app); UpdateCheck.setEnabled(app,false); AutoImportSettings.setMode(app,AutoImportSettings.OFF);
        // The activity talks to a spy, so the UI is checked without driving a Bluetooth session.
        monitor=spy(Robolectric.buildService(MonitorService.class).create().get());
        MonitorService.LocalBinder binder=mock(MonitorService.LocalBinder.class);
        when(binder.get()).thenReturn(monitor);
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,MonitorService.class),binder);
        doReturn(false).when(monitor).canClockSync();
        doReturn(false).when(monitor).clockSyncOffered();
        doReturn(false).when(monitor).deviceReportPromptDue();
        doNothing().when(monitor).registerX7User(anyInt());
        ShadowDialog.reset();
    }
    @After public void close() { app.store().close(); }
    private void choosing(String identity,boolean override) {
        monitor.profile=ProtocolProfile.X7_WLP; monitor.address=ADDRESS; monitor.connectedName="Synthetic X7";
        monitor.registrationPending=true; monitor.registrationIdentity=identity; monitor.registrationOverride=override;
        monitor.status="Choose your X7 user";
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Activity open(ActivityController<MainActivity> controller) {
        controller.setup(); idle();
        Activity a=controller.get(); button(root(a),a.getString(R.string.main_devices)).performClick(); idle();
        return a;
    }
    private static View root(Activity a) { return a.findViewById(android.R.id.content); }
    private static Button button(View root,String label) {
        if(root instanceof Button && ((Button)root).getText().toString().equals(label)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { Button found=button(((ViewGroup)root).getChildAt(i),label); if(found!=null) return found; }
        return null;
    }
    private static boolean hasText(View root,String value) {
        if(root instanceof TextView && !(root instanceof Button) && !(root instanceof EditText) && ((TextView)root).getText().toString().equals(value)) return true;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) if(hasText(((ViewGroup)root).getChildAt(i),value)) return true;
        return false;
    }
    private static String message(AlertDialog dialog) { return ((TextView)dialog.findViewById(android.R.id.message)).getText().toString(); }

    @Test public void untestedIdentityIsNamedAndWarned() {
        choosing("HEM-7380T1-EBK",true);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertTrue("The card warns while the override is in use",hasText(root(a),a.getString(R.string.registration_untested_warning)));
            button(root(a),a.getString(R.string.main_user_number,"1")).performClick(); idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog); assertTrue(dialog.isShowing());
            assertEquals(a.getString(R.string.main_x7_register_explanation,"HEM-7380T1-EBK")+"\n\n"+a.getString(R.string.registration_untested_warning),message(dialog));
            assertTrue(message(dialog).contains("HEM-7380T1-EBK"));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            verify(monitor).registerX7User(1);
        }
    }
    @Test public void confirmedIdentityShowsNoWarning() {
        choosing("HEM-7380T1-EOSL",false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertFalse(hasText(root(a),a.getString(R.string.registration_untested_warning)));
            button(root(a),a.getString(R.string.main_user_number,"2")).performClick(); idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(a.getString(R.string.main_x7_register_explanation,"HEM-7380T1-EOSL"),message(dialog));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            verify(monitor,never()).registerX7User(anyInt());
        }
    }
    @Test public void forcedRegistrationOffersTheDeviceReportOnceInsteadOfThePairQuestion() throws Exception {
        monitor.profile=ProtocolProfile.X7_WLP; monitor.address=ADDRESS; monitor.connectedName="Synthetic X7";
        monitor.registrationDone=true; monitor.sessionFinished=true; monitor.status="User 1 registration acknowledged";
        DeviceReport report=new DeviceReport("Pair","1.0",35,"Synthetic Phone");
        report.service("0000fe4a-0000-1000-8000-00805f9b34fb",java.util.Collections.emptyList());
        report.model("HEM-7380T1-EBK");
        report.writeAspect(org.openpressure.protocol.WriteAspectIndex.Aspect.REGISTRATION,DeviceReport.WriteOutcome.ACKNOWLEDGED,true);
        java.lang.reflect.Field field=MonitorService.class.getDeclaredField("report"); field.setAccessible(true); field.set(monitor,report);
        java.lang.reflect.Field target=MonitorService.class.getDeclaredField("reportAddress"); target.setAccessible(true); target.set(monitor,ADDRESS);
        monitor.offerReportAfterRegistration=true;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            AlertDialog offer=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(offer); assertTrue(offer.isShowing());
            assertEquals(a.getString(R.string.registration_report_prompt),message(offer));
            assertFalse("Offered once",monitor.offerReportAfterRegistration);
            verify(monitor).deviceReportPrompted();
            int shown=ShadowDialog.getShownDialogs().size();
            offer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            button(root(a),a.getString(R.string.main_overview)).performClick(); idle();
            assertEquals(shown,ShadowDialog.getShownDialogs().size());
        }
    }
}
