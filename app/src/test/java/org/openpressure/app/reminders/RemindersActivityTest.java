package org.openpressure.app.reminders;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TimePicker;
import androidx.work.WorkManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.MainActivity;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
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
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=RemindersActivityTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RemindersActivityTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    @After public void closeStore() { ((PressureApp)RuntimeEnvironment.getApplication()).store().close(); }
    private ActivityController<RemindersActivity> controller() { return Robolectric.buildActivity(RemindersActivity.class); }
    private <T extends View> List<T> views(View root,Class<T> type) {
        List<T> result=new ArrayList<>(); collect(root,type,result); return result;
    }
    private <T extends View> void collect(View root,Class<T> type,List<T> result) {
        if(type.isInstance(root)) result.add(type.cast(root));
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) collect(((ViewGroup)root).getChildAt(i),type,result);
    }
    private View root(Activity activity) { return activity.findViewById(android.R.id.content); }
    private void click(Activity activity,int resource) {
        for(Button button:views(root(activity),Button.class)) if(button.getText().toString().equals(activity.getString(resource))) { button.performClick(); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); return; }
        fail("Missing action "+activity.getString(resource));
    }
    private AlertDialog add(Activity activity) { click(activity,R.string.reminder_add); return ShadowAlertDialog.getLatestAlertDialog(); }
    private void save(AlertDialog dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }
    @Test public void addEditDisableAndConfirmedDeletePersistAndScheduleWithoutBluetoothPermissions() {
        try(ActivityController<RemindersActivity> controller=controller().setup()) {
            Activity activity=controller.get(); ReminderStore store=new ReminderStore(activity);
            assertTrue(store.list().isEmpty());
            assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
            AlertDialog editor=add(activity); View form=editor.getWindow().getDecorView();
            TimePicker time=views(form,TimePicker.class).get(0); time.setHour(8); time.setMinute(35);
            assertEquals("120",((EditText)form.findViewWithTag("reminder_skip")).getText().toString());
            // Weekday controls carry their fixed Monday-based bit independently of locale order.
            for(CheckBox day:views(form,CheckBox.class)) if(((Integer)day.getTag())==0 || ((Integer)day.getTag())==4) day.setChecked(true);
            save(editor);
            assertFalse(editor.isShowing());
            Reminder saved=store.list().get(0); assertEquals(8,saved.hour); assertEquals(35,saved.minute);
            assertEquals(17,saved.daysMask); assertEquals(120,saved.skipMinutes); assertTrue(saved.enabled);
            assertFalse(Shadows.shadowOf((AlarmManager)activity.getSystemService(Context.ALARM_SERVICE)).getScheduledAlarms().isEmpty());
            views(root(activity),Switch.class).get(0).setChecked(false); assertFalse(store.list().get(0).enabled);
            click(activity,R.string.reminder_edit); editor=ShadowAlertDialog.getLatestAlertDialog(); form=editor.getWindow().getDecorView();
            for(CheckBox day:views(form,CheckBox.class)) day.setChecked(false);
            ((EditText)form.findViewWithTag("reminder_skip")).setText("0"); save(editor);
            assertEquals(0,store.list().get(0).daysMask); assertEquals(0,store.list().get(0).skipMinutes);
            assertFalse(store.list().get(0).enabled);
            click(activity,R.string.reminder_delete); ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertEquals(1,store.list().size());
            click(activity,R.string.reminder_delete); ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(store.list().isEmpty());
        }
    }
    @Test public void editorDraftSurvivesRotationAndInvalidMinutesCannotSave() {
        ActivityController<RemindersActivity> controller=controller().setup(); Activity activity=controller.get();
        AlertDialog editor=add(activity); View form=editor.getWindow().getDecorView();
        TimePicker time=views(form,TimePicker.class).get(0); time.setHour(21); time.setMinute(47);
        CheckBox first=views(form,CheckBox.class).get(0); int day=(Integer)first.getTag(); first.setChecked(true);
        ((EditText)form.findViewWithTag("reminder_skip")).setText("99999999999999999999");
        save(editor); assertTrue(editor.isShowing()); assertTrue(new ReminderStore(activity).list().isEmpty());
        Bundle state=new Bundle(); controller.saveInstanceState(state).pause().stop().destroy();
        try(ActivityController<RemindersActivity> restored=controller().create(state).start().restoreInstanceState(state).resume().visible()) {
            editor=ShadowAlertDialog.getLatestAlertDialog(); assertTrue(editor.isShowing()); form=editor.getWindow().getDecorView();
            time=views(form,TimePicker.class).get(0); assertEquals(21,time.getHour()); assertEquals(47,time.getMinute());
            assertEquals("99999999999999999999",((EditText)form.findViewWithTag("reminder_skip")).getText().toString());
            for(CheckBox toggle:views(form,CheckBox.class)) assertEquals(((Integer)toggle.getTag())==day,toggle.isChecked());
            ((EditText)form.findViewWithTag("reminder_skip")).setText("90"); save(editor);
            Reminder saved=new ReminderStore(restored.get()).list().get(0); assertEquals(90,saved.skipMinutes); assertEquals(1<<day,saved.daysMask);
        }
    }
    @Test public void permissionButtonsAreExplicitAndReturningFromSettingsRefreshesStatus() {
        try(ActivityController<RemindersActivity> controller=controller().setup()) {
            Activity activity=controller.get(); assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
            click(activity,R.string.reminder_notifications_allow);
            assertArrayEquals(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
            controller.pause().resume();
            assertTrue(new ReminderStore(activity).list().isEmpty());
        }
    }
    @Test public void unreadableReminderStorageShowsFailureInsteadOfCrashing() {
        RuntimeEnvironment.getApplication().getSharedPreferences("measurement_reminders",Context.MODE_PRIVATE).edit().putString("state","invalid").commit();
        try(ActivityController<RemindersActivity> controller=controller().setup()) {
            Activity activity=controller.get(); boolean found=false;
            for(android.widget.TextView text:views(root(activity),android.widget.TextView.class)) if(text.getText().toString().equals(activity.getString(R.string.reminder_save_failed))) found=true;
            assertTrue(found);
        }
    }
    @Test public void deniedExactAccessShowsHelpAndExplicitSettingsAction() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false);
        try(ActivityController<RemindersActivity> controller=controller().setup()) {
            Activity activity=controller.get(); click(activity,R.string.reminder_exact_allow);
            Intent settings=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,settings.getAction());
            assertEquals("package:"+activity.getPackageName(),settings.getDataString());
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);
            controller.pause().resume();
            for(Button button:views(root(activity),Button.class)) if(button.getText().toString().equals(activity.getString(R.string.reminder_exact_allow))) assertEquals(View.GONE,button.getVisibility());
        }
    }
    @Test @Config(qualifiers="ar-rEG") public void arabicEditorKeepsLocaleDayOrderAndAcceptsLocaleDigits() {
        try(ActivityController<RemindersActivity> controller=controller().setup()) {
            Activity activity=controller.get(); AlertDialog editor=add(activity); View form=editor.getWindow().getDecorView();
            List<CheckBox> days=views(form,CheckBox.class);
            java.util.Locale locale=activity.getResources().getConfiguration().getLocales().get(0);
            int firstDay=java.util.Calendar.getInstance(locale).getFirstDayOfWeek();
            assertEquals((firstDay+5)%7,((Integer)days.get(0).getTag()).intValue());
            ((EditText)form.findViewWithTag("reminder_skip")).setText("١٢٣"); save(editor);
            assertEquals(123,new ReminderStore(activity).list().get(0).skipMinutes);
            assertEquals(0,new ReminderStore(activity).list().get(0).daysMask);
        }
    }
    @Test @Config(sdk=28) public void disabledChannelOpensChannelSettingsOnOlderPhones() {
        Context context=RuntimeEnvironment.getApplication();
        android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
        manager.createNotificationChannel(new android.app.NotificationChannel(ReminderNotifications.CHANNEL_ID,"Synthetic",android.app.NotificationManager.IMPORTANCE_NONE));
        try(ActivityController<RemindersActivity> controller=controller().setup()) {
            Activity activity=controller.get(); click(activity,R.string.reminder_notifications_allow);
            assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
            Intent settings=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,settings.getAction());
            assertEquals(ReminderNotifications.CHANNEL_ID,settings.getStringExtra(Settings.EXTRA_CHANNEL_ID));
        }
    }
    @Test public void overviewOffersReminderEntryBeforeAnyMonitorIsConnected() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); click(activity,R.string.reminder_title);
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent); assertEquals(RemindersActivity.class.getName(),intent.getComponent().getClassName());
        }
    }
}
