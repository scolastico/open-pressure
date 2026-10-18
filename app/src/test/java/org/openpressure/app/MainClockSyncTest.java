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
import java.time.LocalDateTime;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Monitors tab: the explicit Sync clock action, its confirmation dialog and the report offer after a forced write. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainClockSyncTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainClockSyncTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private static final String ADDRESS="00:11:22:33:44:55";
    private static final LocalDateTime MONITOR=LocalDateTime.of(2026,3,4,4,6,7),PHONE=LocalDateTime.of(2026,3,4,5,6,7);
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
        doNothing().when(monitor).beginClockSync();
        doAnswer(invocation -> { monitor.clockSyncPending=false; return null; }).when(monitor).confirmClockSync(any());
        doAnswer(invocation -> { monitor.clockSyncPending=false; return null; }).when(monitor).cancelClockSync();
        ShadowDialog.reset();
    }
    @After public void close() { app.store().close(); }
    private void connected(ProtocolProfile profile) {
        monitor.ready=true; monitor.canHistory=true; monitor.canTime=true; monitor.profile=profile;
        monitor.address=ADDRESS; monitor.connectedName="HEM-7155T"; monitor.status="Connected";
        app.store().remember(ADDRESS,"HEM-7155T","HEM-7155T",1);
    }
    /** An overridden untested preview implies unlocked developer options, so it also allows a chosen time. */
    private void pending(LocalDateTime monitorClock,long drift,boolean override) { pending(monitorClock,drift,override,override); }
    private void pending(LocalDateTime monitorClock,long drift,boolean override,boolean chosenTimeAllowed) {
        connected(ProtocolProfile.VENDOR_MEMORY);
        monitor.clockSyncRunning=true; monitor.clockSyncPending=true; monitor.status="Confirm clock sync";
        monitor.clockSyncMonitorClock=monitorClock; monitor.clockSyncPhoneClock=PHONE; monitor.clockSyncDriftSeconds=drift;
        monitor.clockSyncOverride=override; monitor.clockSyncChosenTimeAllowed=chosenTimeAllowed;
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
    private static EditText field(View root) {
        if(root instanceof EditText) return (EditText)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { EditText found=field(((ViewGroup)root).getChildAt(i)); if(found!=null) return found; }
        return null;
    }
    private static View content(Dialog dialog) { return dialog.getWindow().getDecorView(); }
    private static int clockDialogs(Activity a) {
        int count=0;
        for(Dialog d:ShadowDialog.getShownDialogs()) if(hasText(content(d),a.getString(R.string.clock_sync_title))) count++;
        return count;
    }
    private String drift(Activity a,int seconds) { return a.getResources().getQuantityString(R.plurals.clock_sync_drift,seconds,seconds); }

    @Test public void vendorMonitorOffersSyncClockInsteadOfSetTime() {
        connected(ProtocolProfile.VENDOR_MEMORY); doReturn(true).when(monitor).clockSyncOffered(); doReturn(true).when(monitor).canClockSync();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertNull("The Current Time dialog is not offered here",button(root(a),a.getString(R.string.main_set_monitor_time)));
            Button sync=button(root(a),a.getString(R.string.main_sync_clock));
            assertNotNull(sync); assertTrue(sync.isEnabled());
            assertTrue("The action is explained in plain language",hasText(root(a),a.getString(R.string.main_sync_clock_explanation)));
            sync.performClick(); idle();
            verify(monitor).beginClockSync();
        }
    }
    @Test public void x7OffersSyncClockDisabledUntilAllowed() {
        connected(ProtocolProfile.X7_WLP); doReturn(true).when(monitor).clockSyncOffered();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            Button sync=button(root(a),a.getString(R.string.main_sync_clock));
            assertNotNull(sync); assertFalse(sync.isEnabled());
            sync.performClick(); idle();
            verify(monitor,never()).beginClockSync();
            assertNull(button(root(a),a.getString(R.string.main_set_monitor_time)));
        }
    }
    @Test public void notOfferedShowsNoClockControl() {
        for(ProtocolProfile profile:new ProtocolProfile[]{ProtocolProfile.VENDOR_MEMORY,ProtocolProfile.X7_WLP,ProtocolProfile.X7_SECURE_WLP}) {
            connected(profile);
            try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
                Activity a=open(controller);
                assertNull(profile.name(),button(root(a),a.getString(R.string.main_sync_clock)));
                assertNull(profile.name(),button(root(a),a.getString(R.string.main_set_monitor_time)));
                assertFalse(profile.name(),hasText(root(a),a.getString(R.string.main_sync_clock_explanation)));
            }
        }
    }
    @Test public void clockSyncBackupCanBeExported() throws Exception {
        connected(ProtocolProfile.VENDOR_MEMORY);
        java.io.File backup=new java.io.File(app.getFilesDir(),"clock-sync-settings-test.json");
        java.nio.file.Files.write(backup.toPath(),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        doReturn(backup).when(monitor).clockSyncBackupFile();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            button(root(a),a.getString(R.string.main_advanced)).performClick(); idle();
            assertTrue(hasText(root(a),a.getString(R.string.main_clock_sync_backup)));
            assertTrue(hasText(root(a),a.getString(R.string.main_clock_sync_backup_explanation)));
            assertFalse("no registration backup exists",hasText(root(a),a.getString(R.string.main_registration_backup)));
            Button export=button(root(a),a.getString(R.string.main_export_settings_backup));
            assertNotNull(export); assertTrue(export.isEnabled());
        }
    }
    @Test public void runningSyncShowsItsLabel() {
        connected(ProtocolProfile.VENDOR_MEMORY); monitor.clockSyncRunning=true;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            Button running=button(root(a),a.getString(R.string.main_sync_clock_running));
            assertNotNull(running); assertFalse(running.isEnabled());
            assertNull(button(root(a),a.getString(R.string.main_sync_clock)));
        }
    }
    @Test public void standardProfileKeepsSetMonitorTime() {
        connected(ProtocolProfile.STANDARD_BP);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            assertNotNull(button(root(a),a.getString(R.string.main_set_monitor_time)));
            assertNull(button(root(a),a.getString(R.string.main_sync_clock)));
            assertFalse(hasText(root(a),a.getString(R.string.main_sync_clock_explanation)));
        }
    }
    @Test public void pendingPreviewOpensOnceAndConfirmsThePhoneTime() {
        pending(MONITOR,-3600,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            button(root(a),a.getString(R.string.main_overview)).performClick(); idle();
            button(root(a),a.getString(R.string.main_devices)).performClick(); idle();
            assertEquals("render() on every change opens the dialog only once",1,clockDialogs(a));
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing());
            View view=content(dialog);
            assertTrue(hasText(view,a.getString(R.string.clock_sync_monitor_time,format(a,MONITOR))));
            assertTrue(hasText(view,a.getString(R.string.clock_sync_phone_time,format(a,PHONE))));
            assertTrue("The difference is shown as a positive number of seconds",hasText(view,drift(a,3600)));
            assertFalse(hasText(view,a.getString(R.string.clock_sync_within_threshold)));
            assertFalse(hasText(view,a.getString(R.string.clock_sync_untested_warning)));
            assertNull("Only unlocked developer options offer a chosen time",field(view));
            assertFalse(hasText(view,a.getString(R.string.clock_sync_choose_time)));
            assertEquals(a.getString(R.string.clock_sync_confirm),dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            verify(monitor).confirmClockSync(null);
            assertFalse(dialog.isShowing());
            verify(monitor,never()).cancelClockSync();
            button(root(a),a.getString(R.string.main_overview)).performClick(); idle();
            assertEquals("Confirming never reopens the dialog",1,clockDialogs(a));
        }
    }
    @Test public void unsetMonitorClockOmitsTheDifference() {
        pending(null,Long.MAX_VALUE,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            View view=content(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(hasText(view,a.getString(R.string.clock_sync_monitor_time_unset)));
            assertTrue(hasText(view,a.getString(R.string.clock_sync_phone_time,format(a,PHONE))));
            assertFalse(hasText(view,a.getString(R.string.clock_sync_within_threshold)));
            for(int seconds:new int[]{0,1,2,Integer.MAX_VALUE}) assertFalse(hasText(view,drift(a,seconds)));
        }
    }
    @Test public void smallDifferenceExplainsThatNothingChanges() {
        pending(PHONE.minusSeconds(60),-60,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            View view=content(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(hasText(view,drift(a,60)));
            assertTrue(hasText(view,a.getString(R.string.clock_sync_within_threshold)));
        }
    }
    @Test public void developerOverrideWarnsAndAllowsAChosenTime() {
        pending(MONITOR,-3600,true);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            View view=content(dialog);
            assertTrue(hasText(view,a.getString(R.string.clock_sync_untested_warning)));
            assertTrue(hasText(view,a.getString(R.string.clock_sync_choose_time)));
            EditText time=field(view); assertNotNull(time);
            assertEquals("Prefilled with the phone time",format(a,PHONE),time.getText().toString());
            time.setText("not a time");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            verify(monitor,never()).confirmClockSync(any());
            assertTrue("Invalid input keeps the dialog open",dialog.isShowing());
            assertEquals(a.getString(R.string.main_date_invalid,format(a,PHONE)),ShadowToast.getTextOfLatestToast());
            time.setText("2026-01-02T03:04:05");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            verify(monitor).confirmClockSync(LocalDateTime.of(2026,1,2,3,4,5));
            assertFalse(dialog.isShowing());
        }
    }
    @Test public void confirmedModelWithUnlockedOptionsOffersOnlyTheChosenTime() {
        pending(MONITOR,-3600,false,true);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            View view=content(dialog);
            assertFalse("A confirmed monitor shows no untested warning",hasText(view,a.getString(R.string.clock_sync_untested_warning)));
            assertTrue(hasText(view,a.getString(R.string.clock_sync_choose_time)));
            EditText time=field(view); assertNotNull(time);
            assertEquals(format(a,PHONE),time.getText().toString());
            time.setText("2026-01-02T03:04:05");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            verify(monitor).confirmClockSync(LocalDateTime.of(2026,1,2,3,4,5));
            assertFalse(dialog.isShowing());
        }
    }
    @Test public void warningWithoutChosenTimeShowsNoField() {
        pending(MONITOR,-3600,true,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            View view=content(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(hasText(view,a.getString(R.string.clock_sync_untested_warning)));
            assertFalse(hasText(view,a.getString(R.string.clock_sync_choose_time)));
            assertNull(field(view));
        }
    }
    @Test public void unchangedPrefillConfirmsTheCurrentPhoneTime() {
        pending(MONITOR,-3600,true);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            open(controller);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            // The prefill is the preview's phone time; an untouched field must not write that older time.
            verify(monitor).confirmClockSync(null);
        }
    }
    @Test public void refusedConfirmationKeepsTheDialogOpen() {
        pending(MONITOR,-3600,true);
        doNothing().when(monitor).confirmClockSync(any());
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            field(content(dialog)).setText("1999-01-02T03:04:05");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            verify(monitor).confirmClockSync(LocalDateTime.of(1999,1,2,3,4,5));
            assertTrue("A time the service refuses keeps the dialog open",dialog.isShowing());
            assertEquals(a.getString(R.string.main_date_invalid,format(a,PHONE)),ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void cancelButtonCancelsTheSync() {
        pending(MONITOR,-3600,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            open(controller);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            verify(monitor).cancelClockSync(); verify(monitor,never()).confirmClockSync(any());
            assertFalse(dialog.isShowing());
        }
    }
    @Test public void backCancelsTheSync() {
        pending(MONITOR,-3600,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            open(controller);
            ShadowAlertDialog.getLatestAlertDialog().cancel(); idle();
            verify(monitor).cancelClockSync(); verify(monitor,never()).confirmClockSync(any());
        }
    }
    @Test public void endedPreviewClosesTheDialogWithoutCancelling() {
        pending(MONITOR,-3600,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            monitor.clockSyncPending=false; monitor.status="Ending clock sync…";
            button(root(a),a.getString(R.string.main_overview)).performClick(); idle();
            assertFalse(dialog.isShowing());
            verify(monitor,never()).cancelClockSync();
        }
    }
    @Test public void forcedWriteOffersTheDeviceReportOnce() {
        connected(ProtocolProfile.VENDOR_MEMORY);
        monitor.ready=false; monitor.sessionFinished=true; monitor.status="Clock sync complete";
        monitor.offerReportAfterWrite=true;
        doReturn(mock(DeviceReport.class)).when(monitor).deviceReport();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            AlertDialog offer=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(offer); assertTrue(offer.isShowing());
            assertEquals(a.getString(R.string.clock_sync_report_prompt),((TextView)offer.findViewById(android.R.id.message)).getText().toString());
            assertFalse("Offered once",monitor.offerReportAfterWrite);
            int shown=ShadowDialog.getShownDialogs().size();
            offer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            button(root(a),a.getString(R.string.main_overview)).performClick(); idle();
            assertEquals(shown,ShadowDialog.getShownDialogs().size());
        }
    }
    @Test public void forcedWriteOnTheVerifiedX7OffersTheDeviceReportOnce() throws Exception {
        connected(ProtocolProfile.X7_WLP);
        monitor.connectedName="X7 Smart AFib";
        monitor.ready=false; monitor.sessionFinished=true; monitor.status="Clock sync complete";
        // A real report of the tested identity: only the recorded clock-block write makes it worth sharing.
        DeviceReport report=new DeviceReport("Connect","1.0",35,"Synthetic Phone");
        report.service("0000fe4a-0000-1000-8000-00805f9b34fb",java.util.Collections.emptyList());
        report.model("HEM-7380T1-EOSL");
        java.lang.reflect.Field field=MonitorService.class.getDeclaredField("report"); field.setAccessible(true); field.set(monitor,report);
        assertNull("No report without a write on the tested identity",monitor.deviceReport());
        report.writeAspect(org.openpressure.protocol.WriteAspectIndex.Aspect.CLOCK_BLOCK,DeviceReport.WriteOutcome.ACKNOWLEDGED,true);
        monitor.offerReportAfterWrite=true;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=open(controller);
            AlertDialog offer=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(offer); assertTrue(offer.isShowing());
            assertEquals(a.getString(R.string.clock_sync_report_prompt),((TextView)offer.findViewById(android.R.id.message)).getText().toString());
            assertFalse("Offered once",monitor.offerReportAfterWrite);
            int shown=ShadowDialog.getShownDialogs().size();
            offer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            button(root(a),a.getString(R.string.main_overview)).performClick(); idle();
            assertEquals(shown,ShadowDialog.getShownDialogs().size());
        }
    }
    @Test public void confirmedX7WriteOffersNoDeviceReport() throws Exception {
        connected(ProtocolProfile.X7_WLP);
        monitor.connectedName="X7 Smart AFib";
        monitor.ready=false; monitor.sessionFinished=true; monitor.status="Clock sync complete";
        // The confirmed EOSL write did not rely on the override: the report exists but is not offered.
        DeviceReport report=new DeviceReport("Connect","1.0",35,"Synthetic Phone");
        report.service("0000fe4a-0000-1000-8000-00805f9b34fb",java.util.Collections.emptyList());
        report.model("HEM-7380T1-EOSL");
        report.writeAspect(org.openpressure.protocol.WriteAspectIndex.Aspect.CLOCK_BLOCK,DeviceReport.WriteOutcome.ACKNOWLEDGED,false);
        java.lang.reflect.Field field=MonitorService.class.getDeclaredField("report"); field.setAccessible(true); field.set(monitor,report);
        monitor.offerReportAfterWrite=false;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            open(controller);
            assertTrue(ShadowDialog.getShownDialogs().isEmpty());
        }
    }
    @Test public void noReportOfferWithoutAForcedWrite() {
        connected(ProtocolProfile.VENDOR_MEMORY);
        monitor.ready=false; monitor.sessionFinished=true; monitor.status="Clock sync complete";
        doReturn(mock(DeviceReport.class)).when(monitor).deviceReport();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            open(controller);
            assertTrue(ShadowDialog.getShownDialogs().isEmpty());
        }
    }
    private static String format(Activity a,LocalDateTime time) {
        return time.format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT,java.time.format.FormatStyle.MEDIUM)
                .withLocale(a.getResources().getConfiguration().getLocales().get(0)));
    }
}
