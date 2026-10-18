package org.openpressure.app.developer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import androidx.work.WorkManager;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.protocol.WriteAspectIndex.Aspect;
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
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=DeveloperOptionsActivityTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DeveloperOptionsActivityTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private PressureApp app;
    @Before public void setup() { app=(PressureApp)RuntimeEnvironment.getApplication(); DeveloperOptions.setUnlocked(app,true); }
    @After public void close() { prefsDir(app).setWritable(true); app.store().close(); }
    /** Makes the next SharedPreferences commit fail on disk, as a full or read-only data partition would. */
    static File prefsDir(Context context) { return new File(context.getDataDir(),"shared_prefs"); }
    static void failPreferenceWrites(Context context) {
        File dir=prefsDir(context); dir.mkdirs();
        assertTrue("test needs a non-writable preferences directory",dir.setWritable(false) && !dir.canWrite());
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static View root(Activity a) { return a.findViewById(android.R.id.content); }
    private static Button button(View root,String label) {
        if(root instanceof Button && !(root instanceof Switch) && ((Button)root).getText().toString().equals(label)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { Button found=button(((ViewGroup)root).getChildAt(i),label); if(found!=null) return found; }
        return null;
    }
    private static boolean hasText(View root,String value) {
        if(root instanceof TextView && ((TextView)root).getText().toString().equals(value)) return true;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) if(hasText(((ViewGroup)root).getChildAt(i),value)) return true;
        return false;
    }
    private static Switch clockSwitch(Activity a) { return (Switch)root(a).findViewWithTag("dev_allow_clock_untested"); }
    private static Switch registrationSwitch(Activity a) { return (Switch)root(a).findViewWithTag("dev_allow_registration_untested"); }
    private static String status(Activity a,String address,Aspect aspect) {
        TextView view=(TextView)root(a).findViewWithTag("dev_index_"+address+"_"+aspect.name()); assertNotNull(view); return view.getText().toString();
    }

    @Test public void showsTheWarningFirstAndTheSwitchOff() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            assertTrue(hasText(root(a),a.getString(R.string.dev_warning)));
            assertEquals(a.getString(R.string.dev_allow_clock_untested),clockSwitch(a).getText().toString());
            assertFalse(clockSwitch(a).isChecked());
        }
    }
    @Test public void onlyAcceptEnablesTheClockSwitch() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            clockSwitch(a).performClick(); idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("Turning the switch on asks first",dialog); assertTrue(dialog.isShowing());
            assertFalse("Nothing is stored before the user accepts",DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
            assertEquals(a.getString(R.string.dev_allow_confirm_message),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());
            assertEquals(a.getString(R.string.dev_allow_confirm_accept),dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertTrue(DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
            assertTrue(clockSwitch(a).isChecked());
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.UNSENT_COUNTERS));
            clockSwitch(a).performClick(); idle();
            assertFalse("Turning it off is immediate",DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
            assertFalse(clockSwitch(a).isChecked());
        }
    }
    @Test public void registrationSwitchHasItsOwnConfirmation() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            Switch registration=registrationSwitch(a);
            assertNotNull(registration);
            assertEquals(a.getString(R.string.dev_allow_registration_untested),registration.getText().toString());
            assertFalse(registration.isChecked());
            registration.performClick(); idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("Turning the switch on asks first",dialog.isShowing());
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.REGISTRATION));
            assertEquals(a.getString(R.string.dev_allow_registration_confirm_message),((TextView)dialog.findViewById(android.R.id.message)).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            assertFalse("Cancel leaves it off",registration.isChecked());
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.REGISTRATION));
            registration.performClick(); idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertTrue(DeveloperOptions.allowsUntested(app,Aspect.REGISTRATION));
            assertTrue(registration.isChecked());
            assertFalse("The clock switch is independent",DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
            assertFalse(clockSwitch(a).isChecked());
            registration.performClick(); idle();
            assertFalse("Turning it off is immediate",DeveloperOptions.allowsUntested(app,Aspect.REGISTRATION));
        }
    }
    @Test public void hideTurnsTheRegistrationSwitchOffToo() {
        DeveloperOptions.setAllowUntested(app,Aspect.REGISTRATION,true);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            assertTrue(registrationSwitch(a).isChecked());
            button(root(a),a.getString(R.string.dev_hide)).performClick(); idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.REGISTRATION));
        }
    }
    @Test public void indexViewerShowsTheRegistrationRow() {
        app.store().remember("00:00:00:00:00:01","Afib","X7 Smart AFib",1);
        app.store().remember("00:00:00:00:00:02","Ebk","HEM-7380T1-EBK",1);
        app.store().remember("00:00:00:00:00:03","Kitchen","HEM-7155T",1);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            assertTrue(hasText(root(a),a.getString(R.string.dev_index_aspect_registration)));
            assertEquals(a.getString(R.string.dev_index_status_confirmed),status(a,"00:00:00:00:00:01",Aspect.REGISTRATION));
            assertEquals(a.getString(R.string.dev_index_status_untested),status(a,"00:00:00:00:00:02",Aspect.REGISTRATION));
            assertEquals(a.getString(R.string.dev_index_status_blocked),status(a,"00:00:00:00:00:03",Aspect.REGISTRATION));
            assertTrue(hasText(root(a),"no registration workflow for this identity"));
        }
    }
    @Test public void cancelLeavesTheSwitchOff() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            clockSwitch(a).performClick(); idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            assertFalse(clockSwitch(a).isChecked());
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
        }
    }
    @Test public void dismissingTheDialogAlsoLeavesTheSwitchOff() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            clockSwitch(a).performClick(); idle();
            ShadowAlertDialog.getLatestAlertDialog().cancel(); idle();
            assertFalse(clockSwitch(a).isChecked());
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
        }
    }
    @Test public void hideTurnsTheSwitchOffLocksAndCloses() {
        DeveloperOptions.setAllowUntested(app,Aspect.CLOCK_BLOCK,true);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            assertTrue(clockSwitch(a).isChecked());
            button(root(a),a.getString(R.string.dev_hide)).performClick(); idle();
            AlertDialog confirm=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("Hide asks first",confirm.isShowing());
            assertTrue(DeveloperOptions.isUnlocked(app));
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertFalse(DeveloperOptions.isUnlocked(app));
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
            assertEquals(a.getString(R.string.dev_hidden),ShadowToast.getTextOfLatestToast());
            assertTrue(a.isFinishing());
        }
    }
    @Test public void recreatingShowsTheStoredSwitchState() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            clockSwitch(a).performClick(); idle();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            controller.recreate(); idle();
            assertTrue(clockSwitch(controller.get()).isChecked());
            DeveloperOptions.setAllowUntested(app,Aspect.CLOCK_BLOCK,false);
            controller.recreate(); idle();
            assertFalse("Saved view state never overrides the stored choice",clockSwitch(controller.get()).isChecked());
        }
    }
    @Test public void pendingConfirmationDoesNotSurviveRecreation() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            clockSwitch(controller.get()).performClick(); idle();
            controller.recreate(); idle();
            assertFalse(clockSwitch(controller.get()).isChecked());
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
        }
    }
    @Test public void lockedOptionsCloseImmediately() {
        DeveloperOptions.setUnlocked(app,false);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            assertTrue(controller.get().isFinishing());
        }
    }
    @Test public void indexViewerListsSavedMonitorsPerAspect() {
        app.store().remember("00:11:22:33:44:55","Kitchen","HEM-7155T-EBK",1);
        app.store().remember("66:77:88:99:AA:BB","Mystery","XYZ-1",1);
        app.store().remember("CC:DD:EE:FF:00:11","X7","HEM-7380T1-EBK",1);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            assertTrue(hasText(root(a),a.getString(R.string.dev_index_title)));
            assertTrue(hasText(root(a),"Kitchen")); assertTrue(hasText(root(a),"Mystery"));
            assertEquals(a.getString(R.string.dev_index_status_untested),status(a,"00:11:22:33:44:55",Aspect.CLOCK_BLOCK));
            assertEquals(a.getString(R.string.dev_index_status_blocked),status(a,"00:11:22:33:44:55",Aspect.UNSENT_COUNTERS));
            assertEquals(a.getString(R.string.dev_index_status_blocked),status(a,"66:77:88:99:AA:BB",Aspect.CLOCK_BLOCK));
            assertEquals(a.getString(R.string.dev_index_status_blocked),status(a,"66:77:88:99:AA:BB",Aspect.UNSENT_COUNTERS));
            assertEquals(a.getString(R.string.dev_index_status_untested),status(a,"CC:DD:EE:FF:00:11",Aspect.CLOCK_BLOCK));
            assertTrue(hasText(root(a),a.getString(R.string.dev_index_aspect_clock)));
            assertTrue(hasText(root(a),a.getString(R.string.dev_index_aspect_unsent)));
            assertFalse(hasText(root(a),a.getString(R.string.dev_index_no_monitors)));
        }
    }
    @Test public void indexViewerExplainsWhenNoMonitorIsSaved() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            assertTrue(hasText(root(a),a.getString(R.string.dev_index_no_monitors)));
        }
    }
    @Test public void indexViewerResolvesAliasesAndBlocksHints() {
        app.store().remember("00:00:00:00:00:01","Afib","X7 Smart AFib",1);
        app.store().remember("00:00:00:00:00:02","Hint","HEM-7380T1",1);
        app.store().remember("00:00:00:00:00:03","Regional","HEM-7156T-AAP",1);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            // The firmware name resolves to HEM-7380T1-EOSL, whose clock block is confirmed (drawn in the accent).
            assertEquals(a.getString(R.string.dev_index_status_confirmed),status(a,"00:00:00:00:00:01",Aspect.CLOCK_BLOCK));
            assertEquals(0xff176e65,((TextView)root(a).findViewWithTag("dev_index_00:00:00:00:00:01_CLOCK_BLOCK")).getCurrentTextColor());
            assertEquals(a.getString(R.string.dev_index_status_blocked),status(a,"00:00:00:00:00:02",Aspect.CLOCK_BLOCK));
            assertEquals(a.getString(R.string.dev_index_status_blocked),status(a,"00:00:00:00:00:03",Aspect.CLOCK_BLOCK));
        }
    }
    @Test public void untestedIsDrawnAsAWarningNotTheAccent() {
        app.store().remember("00:11:22:33:44:55","Kitchen","HEM-7155T",1);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            TextView untested=(TextView)root(a).findViewWithTag("dev_index_00:11:22:33:44:55_CLOCK_BLOCK");
            TextView blocked=(TextView)root(a).findViewWithTag("dev_index_00:11:22:33:44:55_UNSENT_COUNTERS");
            assertEquals(0xff9a3412,untested.getCurrentTextColor());
            assertEquals(0xff61726b,blocked.getCurrentTextColor());
        }
    }
    @Test public void resumeDropsAMonitorForgottenMeanwhile() {
        app.store().remember("00:11:22:33:44:55","Kitchen","HEM-7155T",1);
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            assertTrue(hasText(root(a),"Kitchen"));
            controller.pause().stop();
            app.store().forget("00:11:22:33:44:55");
            controller.restart().resume(); idle();
            assertFalse(hasText(root(a),"Kitchen"));
            assertNull(root(a).findViewWithTag("dev_index_00:11:22:33:44:55_CLOCK_BLOCK"));
            assertTrue(hasText(root(a),a.getString(R.string.dev_index_no_monitors)));
            assertEquals("The section is rebuilt, not appended",1,count(root(a),a.getString(R.string.dev_index_title)));
        }
    }
    @Test public void failedSwitchSaveShowsAMessageAndKeepsTheScreen() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            clockSwitch(a).performClick(); idle();
            failPreferenceWrites(app);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertEquals(a.getString(R.string.dev_save_failed),ShadowToast.getTextOfLatestToast());
            assertFalse("A save failure does not close the screen",a.isFinishing());
            assertEquals("The switch shows the stored choice",DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK),clockSwitch(a).isChecked());
        }
    }
    @Test public void switchOnALockedScreenCloses() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            clockSwitch(a).performClick(); idle();
            DeveloperOptions.setUnlocked(app,false);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertTrue(a.isFinishing());
            assertFalse(DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
            assertNotEquals(a.getString(R.string.dev_save_failed),ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void failedHideShowsAMessageInsteadOfCrashing() {
        try(ActivityController<DeveloperOptionsActivity> controller=Robolectric.buildActivity(DeveloperOptionsActivity.class).setup()) {
            Activity a=controller.get();
            button(root(a),a.getString(R.string.dev_hide)).performClick(); idle();
            failPreferenceWrites(app);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertEquals(a.getString(R.string.dev_save_failed),ShadowToast.getTextOfLatestToast());
            assertFalse(hasText(root(a),a.getString(R.string.dev_hidden)));
        }
    }
    private static int count(View root,String value) {
        int n=root instanceof TextView && ((TextView)root).getText().toString().equals(value)?1:0;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) n+=count(((ViewGroup)root).getChildAt(i),value);
        return n;
    }
}
