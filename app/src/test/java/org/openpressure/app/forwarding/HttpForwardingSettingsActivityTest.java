package org.openpressure.app.forwarding;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import androidx.work.WorkManager;
import android.os.Bundle;
import android.os.Parcel;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.ReadingStore;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=HttpForwardingSettingsActivityTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HttpForwardingSettingsActivityTest {
    /** Robolectric does not start AndroidX initializer providers. Prevent background delivery here. */
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        private static WorkManager scheduler;
        @Implementation public static WorkManager getInstance(Context context) {
            if(scheduler==null) scheduler=mock(WorkManager.class);
            return scheduler;
        }
    }
    @After public void closeStore() { ((PressureApp)RuntimeEnvironment.getApplication()).store().close(); }
    private ActivityController<HttpForwardingSettingsActivity> controller() { return Robolectric.buildActivity(HttpForwardingSettingsActivity.class); }
    private <T extends View> List<T> views(Activity activity,Class<T> type) {
        List<T> result=new ArrayList<>(); collect(activity.findViewById(android.R.id.content),type,result); return result;
    }
    private <T extends View> void collect(View view,Class<T> type,List<T> result) {
        if(type.isInstance(view)) result.add(type.cast(view));
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) collect(((ViewGroup)view).getChildAt(i),type,result);
    }
    private void save(Activity activity) {
        for(Button button:views(activity,Button.class)) if(button.getText().toString().equals(activity.getString(R.string.main_save))) { button.performClick(); return; }
        fail("Save action is missing");
    }
    @Test public void savePersistsChosenDestinationAndPassthroughAndBlankRestoresHistory() throws Exception {
        try(ActivityController<HttpForwardingSettingsActivity> controller=controller().setup()) {
            Activity activity=controller.get(); ExperimentalConsent.accept(activity);
            List<EditText> fields=views(activity,EditText.class);
            fields.get(0).setText("https://example.invalid/measurements"); fields.get(1).setText("Bearer synthetic-only");
            views(activity,Switch.class).get(0).setChecked(false); save(activity);
            HttpForwardingSettings.Config saved=HttpForwardingSettings.get(activity);
            assertEquals("https://example.invalid/measurements",saved.endpoint); assertEquals("Bearer synthetic-only",saved.authorization);
            assertFalse(saved.keepLocal);
            fields.get(0).setText(""); save(activity);
            assertFalse(HttpForwardingSettings.get(activity).enabled()); assertTrue(HttpForwardingSettings.get(activity).keepLocal);
        }
    }
    @Test public void invalidUrlOrHeaderNeverReplacesSavedConfiguration() throws Exception {
        try(ActivityController<HttpForwardingSettingsActivity> controller=controller().setup()) {
            Activity activity=controller.get(); ExperimentalConsent.accept(activity);
            List<EditText> fields=views(activity,EditText.class);
            fields.get(0).setText("https://example.invalid/original"); save(activity);
            fields.get(0).setText("https://private:secret@example.invalid"); save(activity);
            assertEquals("https://example.invalid/original",HttpForwardingSettings.get(activity).endpoint);
            fields.get(0).setText("https://example.invalid/replacement"); fields.get(1).setText("Bearer x\r\nX-Injected: yes"); save(activity);
            assertEquals("https://example.invalid/original",HttpForwardingSettings.get(activity).endpoint);
            assertEquals("",HttpForwardingSettings.get(activity).authorization);
        }
    }
    @Test public void directActivityCannotEnableUploadsWithoutExperimentalConsent() throws Exception {
        try(ActivityController<HttpForwardingSettingsActivity> controller=controller().setup()) {
            Activity activity=controller.get(); views(activity,EditText.class).get(0).setText("https://example.invalid/measurements");
            save(activity); assertFalse(HttpForwardingSettings.get(activity).enabled());
            AlertDialog consent=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(consent);
            consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(HttpForwardingSettings.get(activity).enabled());
        }
    }
    @Test public void localHistoryStaysOnUntilValidDestinationAndReturnsOnWhenCleared() throws Exception {
        try(ActivityController<HttpForwardingSettingsActivity> controller=controller().setup()) {
            Activity activity=controller.get(); Switch local=views(activity,Switch.class).get(0);
            EditText url=views(activity,EditText.class).get(0);
            assertTrue(local.isChecked()); assertEquals(View.GONE,local.getVisibility());
            url.setText("https://example.invalid/measurements");
            assertEquals(View.VISIBLE,local.getVisibility()); assertTrue(local.isEnabled()); local.setChecked(false);
            url.setText("invalid"); assertFalse(local.isEnabled());
            url.setText(""); assertEquals(View.GONE,local.getVisibility()); assertTrue(local.isChecked());
        }
    }
    @Test public void pendingStatusCountsSavedDestinationWhileUrlEditsRemainUnsaved() throws Exception {
        try(ActivityController<HttpForwardingSettingsActivity> controller=controller().setup()) {
            Activity activity=controller.get(); ExperimentalConsent.accept(activity);
            ReadingStore store=((PressureApp)activity.getApplication()).store();
            HttpForwardingSettings.save(activity,"https://example.invalid/first","",true);
            store.addManual(120,80,60.0,1,LocalDateTime.of(2026,1,1,10,0),"");
            store.addManual(121,81,61.0,1,LocalDateTime.of(2026,1,1,11,0),"");
            HttpForwardingSettings.save(activity,"https://example.invalid/second","",true);
            store.addManual(122,82,62.0,1,LocalDateTime.of(2026,1,1,12,0),"");
            controller.pause().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            views(activity,EditText.class).get(0).setText("https://example.invalid/unsaved");
            boolean statusFound=false;
            for(TextView view:views(activity,TextView.class)) if(view.getText().toString().equals(activity.getString(R.string.http_status,1))) {
                assertEquals(View.VISIBLE,view.getVisibility()); statusFound=true;
            }
            assertTrue("Pending status must count only the saved destination",statusFound);
            assertEquals(2,store.httpOutbox().count("https://example.invalid/first"));
        }
    }
    @Test public void retryUsesSavedConfigurationWhileUrlAndAuthorizationEditsRemainUnsaved() throws Exception {
        try(ActivityController<HttpForwardingSettingsActivity> controller=controller().setup()) {
            Activity activity=controller.get(); ExperimentalConsent.accept(activity);
            List<EditText> fields=views(activity,EditText.class);
            fields.get(0).setText("https://example.invalid/saved"); fields.get(1).setText("Bearer saved-only"); save(activity);
            ReadingStore store=((PressureApp)activity.getApplication()).store();
            store.addManual(120,80,60.0,1,LocalDateTime.of(2026,1,1,10,0),"");
            controller.pause().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            fields.get(0).setText("https://example.invalid/unsaved"); fields.get(1).setText("Bearer unsaved-only");
            boolean retried=false;
            for(Button button:views(activity,Button.class)) if(button.getText().toString().equals(activity.getString(R.string.http_retry))) {
                assertTrue(button.isEnabled()); button.performClick(); retried=true;
            }
            assertTrue(retried);
            assertEquals("https://example.invalid/saved",HttpForwardingSettings.get(activity).endpoint);
            assertEquals("Bearer saved-only",HttpForwardingSettings.get(activity).authorization);
            assertEquals(1,store.httpOutbox().count("https://example.invalid/saved"));
            assertEquals(0,store.httpOutbox().count("https://example.invalid/unsaved"));
        }
    }
    @Test public void unsavedUrlAndChoiceSurviveRecreationButSecretDoesNot() throws Exception {
        ActivityController<HttpForwardingSettingsActivity> controller=controller().setup(); Activity first=controller.get();
        List<EditText> fields=views(first,EditText.class);
        fields.get(0).setText("https://example.invalid/unsaved"); fields.get(1).setText("Bearer synthetic-private");
        assertTrue(fields.get(1).getTransformationMethod() instanceof PasswordTransformationMethod);
        assertFalse(fields.get(1).isSaveEnabled());
        views(first,Switch.class).get(0).setChecked(false);
        Bundle state=new Bundle(); controller.saveInstanceState(state).pause().stop().destroy();
        Parcel parcel=Parcel.obtain();
        try {
            parcel.writeBundle(state);
            String savedBytes=new String(parcel.marshall(),StandardCharsets.ISO_8859_1);
            String secretBytes=new String("Bearer synthetic-private".getBytes(StandardCharsets.UTF_16LE),StandardCharsets.ISO_8859_1);
            assertFalse("Authorization must not enter Android saved screen state",savedBytes.contains(secretBytes));
        } finally { parcel.recycle(); }
        try(ActivityController<HttpForwardingSettingsActivity> restored=controller().create(state).start().restoreInstanceState(state).resume().visible()) {
            List<EditText> after=views(restored.get(),EditText.class);
            assertEquals("https://example.invalid/unsaved",after.get(0).getText().toString());
            assertEquals("",after.get(1).getText().toString()); assertFalse(views(restored.get(),Switch.class).get(0).isChecked());
        }
    }
}
