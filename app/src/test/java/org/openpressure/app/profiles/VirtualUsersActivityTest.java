package org.openpressure.app.profiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.VirtualUser;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainVirtualUsersTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VirtualUsersActivityTest {
    private ReadingStore store() { return ((PressureApp)RuntimeEnvironment.getApplication()).store(); }
    @After public void close() { store().close(); }
    private View root(Activity a) { return a.findViewById(android.R.id.content); }
    private <T extends View> T first(View root,Class<T> type) {
        if(type.isInstance(root)) return type.cast(root);
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { T result=first(((ViewGroup)root).getChildAt(i),type); if(result!=null) return result; }
        return null;
    }
    private void click(Activity a,int key) { android.widget.Button button=MainVirtualUsersTest.button(root(a),a.getString(key)); assertNotNull(a.getString(key),button); button.performClick(); }
    private void name(AlertDialog dialog,String value) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); first(dialog.getWindow().getDecorView(),EditText.class).setText(value); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }
    @Test public void enablingRequiresExplicitFirstNameAndCancelLeavesModeOff() {
        try(ActivityController<VirtualUsersActivity> controller=Robolectric.buildActivity(VirtualUsersActivity.class).setup()) {
            Activity activity=controller.get(); Switch mode=first(root(activity),Switch.class); assertNotNull("Mode must be optional",mode);
            mode.setChecked(true); assertFalse(store().virtualUsersEnabled());
            AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog(); name(editor,"  ");
            assertTrue(editor.isShowing()); assertTrue(store().virtualUsers().isEmpty());
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); assertFalse(store().virtualUsersEnabled());
            first(root(activity),Switch.class).setChecked(true); editor=ShadowAlertDialog.getLatestAlertDialog(); name(editor,"  Ada  ");
            assertFalse(editor.isShowing()); assertTrue(store().virtualUsersEnabled()); assertEquals("Ada",store().virtualUsers().get(0).name);
            first(root(activity),Switch.class).setChecked(false); assertFalse(store().virtualUsersEnabled()); assertEquals(1,store().virtualUsers().size());
        }
    }
    @Test public void firstUserDraftSurvivesRecreationWithoutEnablingOrCreatingTwice() {
        ActivityController<VirtualUsersActivity> controller=Robolectric.buildActivity(VirtualUsersActivity.class).setup();
        first(root(controller.get()),Switch.class).setChecked(true);
        AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog(); first(editor.getWindow().getDecorView(),EditText.class).setText("Draft name");
        Bundle state=new Bundle(); controller.saveInstanceState(state).pause().stop().destroy();
        assertFalse(store().virtualUsersEnabled()); assertTrue(store().virtualUsers().isEmpty());
        try(ActivityController<VirtualUsersActivity> restored=Robolectric.buildActivity(VirtualUsersActivity.class).create(state).start().restoreInstanceState(state).resume().visible()) {
            editor=ShadowAlertDialog.getLatestAlertDialog(); assertEquals("Draft name",first(editor.getWindow().getDecorView(),EditText.class).getText().toString());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertTrue(store().virtualUsersEnabled()); assertEquals(1,store().virtualUsers().size());
        }
    }
    @Test public void namesCanBeAddedBeyondTwoRenamedAndRemovedOnlyWithExplicitReplacement() {
        VirtualUser ada=store().createVirtualUser("Ada"),bea=store().createVirtualUser("Bea"); store().setVirtualUsersEnabled(true);
        store().addManual(120,80,65.0,1,java.time.LocalDateTime.of(2026,1,1,10,0),"synthetic");
        store().assignPending(store().nextPendingAssignment().id,ada.id);
        try(ActivityController<VirtualUsersActivity> controller=Robolectric.buildActivity(VirtualUsersActivity.class).setup()) {
            Activity activity=controller.get(); click(activity,R.string.vp_add); name(ShadowAlertDialog.getLatestAlertDialog(),"Chen");
            assertEquals(3,store().virtualUsers().size());
            View adaCard=root(activity).findViewWithTag("virtual_user_"+ada.id); assertNotNull(adaCard);
            MainVirtualUsersTest.button(adaCard,activity.getString(R.string.vp_rename)).performClick(); name(ShadowAlertDialog.getLatestAlertDialog(),"Ada changed");
            assertEquals("Ada changed",store().virtualUsers().get(0).name);
            adaCard=root(activity).findViewWithTag("virtual_user_"+ada.id);
            MainVirtualUsersTest.button(adaCard,activity.getString(R.string.vp_delete)).performClick(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); AlertDialog removal=ShadowAlertDialog.getLatestAlertDialog();
            assertFalse(removal.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            removal.getListView().performItemClick(removal.getListView().getChildAt(0),0,0);
            removal.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(2,store().virtualUsers().size()); assertEquals(1,store().historyCount(-bea.id)); assertEquals(1,store().historyCount(0));
        }
    }
    @Test public void onlyRemainingProfileCannotBeRemoved() {
        VirtualUser ada=store().createVirtualUser("Ada"); store().setVirtualUsersEnabled(true);
        try(ActivityController<VirtualUsersActivity> controller=Robolectric.buildActivity(VirtualUsersActivity.class).setup()) {
            android.widget.Button delete=MainVirtualUsersTest.button(root(controller.get()),controller.get().getString(R.string.vp_delete));
            assertNotNull(delete); assertFalse(delete.isEnabled()); assertEquals(1,store().virtualUsers().size());
        }
    }
    @Test public void deniedNotificationPermissionKeepsModeAndOffersPersistentSettingsAction() {
        store().createVirtualUser("Ada");
        try(ActivityController<VirtualUsersActivity> controller=Robolectric.buildActivity(VirtualUsersActivity.class).setup()) {
            Activity activity=controller.get(); first(root(activity),Switch.class).setChecked(true);
            assertTrue(store().virtualUsersEnabled());
            assertArrayEquals(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},org.robolectric.Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
            activity.onRequestPermissionsResult(org.openpressure.app.notifications.NewReadingNotifications.PERMISSION_REQUEST,new String[]{android.Manifest.permission.POST_NOTIFICATIONS},new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
            click(activity,R.string.settings_notice_allow);
            android.content.Intent intent=org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,intent.getAction());
            assertEquals(ProfileAssignmentNotifications.CHANNEL_ID,intent.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID));
            assertNotNull(activity.getSystemService(android.app.NotificationManager.class).getNotificationChannel(ProfileAssignmentNotifications.CHANNEL_ID));
            assertTrue(store().virtualUsersEnabled());
        }
    }

    @Test public void failedEnableAfterFirstUserCreationRetriesAcrossRecreationWithoutDuplicatingProfile() {
        store().getWritableDatabase().execSQL("CREATE TRIGGER block_profile_enable BEFORE UPDATE ON profile_configuration WHEN NEW.enabled=1 BEGIN SELECT RAISE(FAIL,'synthetic failure'); END");
        ActivityController<VirtualUsersActivity> controller=Robolectric.buildActivity(VirtualUsersActivity.class).setup();
        first(root(controller.get()),Switch.class).setChecked(true); AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog(); name(editor,"Ada");
        assertTrue(editor.isShowing()); assertFalse(store().virtualUsersEnabled()); assertEquals(1,store().virtualUsers().size());
        Bundle state=new Bundle(); controller.saveInstanceState(state).pause().stop().destroy();
        store().getWritableDatabase().execSQL("DROP TRIGGER block_profile_enable");
        try(ActivityController<VirtualUsersActivity> restored=Robolectric.buildActivity(VirtualUsersActivity.class).create(state).start().restoreInstanceState(state).resume().visible()) {
            editor=ShadowAlertDialog.getLatestAlertDialog(); name(editor,"Ada corrected");
            assertTrue(store().virtualUsersEnabled()); assertEquals("Retry must reuse the already committed profile",1,store().virtualUsers().size());
            assertEquals("Ada corrected",store().virtualUsers().get(0).name);
        }
    }

}
