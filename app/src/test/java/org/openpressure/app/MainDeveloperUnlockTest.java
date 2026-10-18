package org.openpressure.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.work.WorkManager;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.ble.MonitorService;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.app.updates.UpdateCheck;
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
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/** Devices tab: the version label unlocks the hidden developer options after five quick taps. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainDeveloperUnlockTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainDeveloperUnlockTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private PressureApp app;
    @Before public void setup() {
        app=(PressureApp)RuntimeEnvironment.getApplication();
        ExperimentalConsent.accept(app); UpdateCheck.setEnabled(app,false); AutoImportSettings.setMode(app,AutoImportSettings.OFF);
        MonitorService monitor=Robolectric.buildService(MonitorService.class).create().get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,MonitorService.class),monitor.onBind(new Intent(app,MonitorService.class)));
    }
    @After public void close() { app.store().close(); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Activity openAdvanced(ActivityController<MainActivity> controller) {
        controller.setup(); idle();
        Activity a=controller.get(); button(root(a),a.getString(R.string.main_devices)).performClick(); idle();
        button(root(a),a.getString(R.string.main_advanced)).performClick(); idle();
        return a;
    }
    private static View root(Activity a) { return a.findViewById(android.R.id.content); }
    private static Button button(View root,String label) {
        if(root instanceof Button && ((Button)root).getText().toString().equals(label)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { Button found=button(((ViewGroup)root).getChildAt(i),label); if(found!=null) return found; }
        return null;
    }
    private static TextView plainText(View root,String value) {
        if(root instanceof TextView && !(root instanceof Button) && ((TextView)root).getText().toString().equals(value)) return (TextView)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { TextView found=plainText(((ViewGroup)root).getChildAt(i),value); if(found!=null) return found; }
        return null;
    }
    private static String versionText(Activity a) { return a.getString(R.string.dev_version_label,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE); }
    private static void tap(Activity a) { plainText(root(a),versionText(a)).performClick(); idle(); }

    @Test public void advancedShowsThePlainVersionLabelAndNoDeveloperButtonWhileLocked() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=openAdvanced(controller);
            assertNotNull("The version and build number are shown as plain information",plainText(root(a),versionText(a)));
            assertNull(button(root(a),a.getString(R.string.dev_open)));
            assertFalse(DeveloperOptions.isUnlocked(app));
        }
    }
    @Test public void developerLabelIsAbsentWhileAdvancedIsCollapsed() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            controller.setup(); idle(); Activity a=controller.get();
            button(root(a),a.getString(R.string.main_devices)).performClick(); idle();
            assertNull(plainText(root(a),versionText(a)));
        }
    }
    @Test public void fiveQuickTapsUnlockAndShowTheButton() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=openAdvanced(controller);
            tap(a); tap(a); assertNull("No hint before the third tap",ShadowToast.getLatestToast());
            tap(a); assertEquals(a.getResources().getQuantityString(R.plurals.dev_taps_remaining,2,2),ShadowToast.getTextOfLatestToast());
            tap(a); assertEquals(a.getResources().getQuantityString(R.plurals.dev_taps_remaining,1,1),ShadowToast.getTextOfLatestToast());
            assertFalse(DeveloperOptions.isUnlocked(app));
            tap(a);
            assertTrue(DeveloperOptions.isUnlocked(app));
            assertEquals(a.getString(R.string.dev_unlocked),ShadowToast.getTextOfLatestToast());
            for(Aspect aspect:Aspect.values()) assertFalse("Unlocking never enables a write aspect",DeveloperOptions.allowsUntested(app,aspect));
            Button open=button(root(a),a.getString(R.string.dev_open));
            assertNotNull("The Developer options button appears once unlocked",open);
            open.performClick();
            Intent started=Shadows.shadowOf(a).getNextStartedActivity();
            assertEquals("org.openpressure.app.developer.DeveloperOptionsActivity",started.getComponent().getClassName());
        }
    }
    @Test public void slowTapsDoNotUnlock() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=openAdvanced(controller);
            tap(a); tap(a); tap(a); tap(a);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500));
            tap(a);
            assertFalse("A gap longer than the tap window restarts the count",DeveloperOptions.isUnlocked(app));
            assertNull(button(root(a),a.getString(R.string.dev_open)));
        }
    }
    @Test public void tappingWhileUnlockedKeepsTheStoredSwitches() {
        DeveloperOptions.setUnlocked(app,true); DeveloperOptions.setAllowUntested(app,Aspect.CLOCK_BLOCK,true);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class)) {
            Activity a=openAdvanced(controller);
            assertNotNull(button(root(a),a.getString(R.string.dev_open)));
            for(int i=0;i<5;i++) tap(a);
            assertTrue("Unlock is only written on a state change",DeveloperOptions.allowsUntested(app,Aspect.CLOCK_BLOCK));
        }
    }
}
