package org.openpressure.app.profiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.TextView;
import org.openpressure.app.R;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.VirtualUser;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.ArrayList;
import java.util.List;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.work.WorkManager;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.MainActivity;
import org.openpressure.app.PressureApp;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainVirtualUsersTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainVirtualUsersTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    @After public void close() { ((PressureApp)RuntimeEnvironment.getApplication()).store().close(); }
    static Button button(View root,String label) {
        if(root instanceof Button && ((Button)root).getText().toString().equals(label)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { Button found=button(((ViewGroup)root).getChildAt(i),label); if(found!=null) return found; }
        return null;
    }
    @Test public void overviewOffersVirtualUsersWithoutMonitorConnection() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            Activity activity=controller.get(); Button action=button(activity.findViewById(android.R.id.content),"Virtual users");
            assertNotNull("Overview must offer named virtual users",action); action.performClick();
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals("org.openpressure.app.profiles.VirtualUsersActivity",intent.getComponent().getClassName());
        }
    }
    private ReadingStore store() { return ((PressureApp)RuntimeEnvironment.getApplication()).store(); }
    private View root(Activity a) { return a.findViewById(android.R.id.content); }
    private <T extends View> List<T> views(View root,Class<T> type) {
        List<T> result=new ArrayList<>();
        if(type.isInstance(root)) result.add(type.cast(root));
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) result.addAll(views(((ViewGroup)root).getChildAt(i),type));
        return result;
    }
    private void choose(Activity a,int index) {
        Button chooser=(Button)root(a).findViewWithTag("virtual_profile_filter"); assertNotNull("Named profiles need a scalable chooser",chooser); chooser.performClick();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); dialog.getListView().performItemClick(dialog.getListView().getChildAt(index),index,index);
    }
    @Test public void arbitraryProfileChooserFiltersHistoryAndRefreshesNamesAfterReturning() {
        VirtualUser ada=store().createVirtualUser("Ada"),bea=store().createVirtualUser("Bea"),chen=store().createVirtualUser("Chen"); store().setVirtualUsersEnabled(true);
        for(int n=1;n<=12;n++) store().createVirtualUser("Person "+n);
        store().addManual(123,80,65.0,1,java.time.LocalDateTime.of(2026,1,1,10,0),"synthetic"); store().assignPending(store().nextPendingAssignment().id,chen.id);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            Activity a=controller.get(); assertNull(button(root(a),"User 1")); assertNull(button(root(a),"User 2"));
            choose(a,3); assertEquals("Chen",((Button)root(a).findViewWithTag("virtual_profile_filter")).getText().toString());
            assertEquals(-chen.id,a.getPreferences(Context.MODE_PRIVATE).getInt("selected_virtual_profile",0));
            store().renameVirtualUser(chen.id,"Chen renamed"); controller.pause().resume();
            assertEquals("Chen renamed",((Button)root(a).findViewWithTag("virtual_profile_filter")).getText().toString());
            store().deleteVirtualUser(chen.id,ada.id); controller.pause().resume();
            assertEquals(a.getString(R.string.main_all_users),((Button)root(a).findViewWithTag("virtual_profile_filter")).getText().toString());
        }
    }
    @Test public void changingModesPreservesSeparateLegacyAndVirtualSelections() {
        VirtualUser ada=store().createVirtualUser("Ada");
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            Activity a=controller.get(); button(root(a),a.getString(R.string.main_user_number,"2")).performClick();
            store().setVirtualUsersEnabled(true); controller.pause().resume(); choose(a,1);
            store().setVirtualUsersEnabled(false); controller.pause().resume();
            assertEquals(2,a.getPreferences(Context.MODE_PRIVATE).getInt("selected_profile",0));
            store().setVirtualUsersEnabled(true); controller.pause().resume();
            assertEquals("Ada",((Button)root(a).findViewWithTag("virtual_profile_filter")).getText().toString());
        }
    }
    @Test public void virtualManualEntryHidesPhysicalBankAndStagesReadingWithoutImplicitAssignment() {
        store().createVirtualUser("Ada"); store().setVirtualUsersEnabled(true);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            Activity a=controller.get(); button(root(a),a.getString(R.string.main_add_reading)).performClick(); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); List<EditText> fields=views(dialog.getWindow().getDecorView(),EditText.class);
            assertEquals("Only pressures, pulse, time and notes belong in the virtual manual form",5,fields.size());
            fields.get(0).setText("121"); fields.get(1).setText("81"); fields.get(2).setText("65"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(dialog.isShowing()); assertEquals(0,store().historyCount(0)); assertEquals(1,store().pendingAssignmentsCount());
            assertEquals(Integer.valueOf(0),store().nextPendingAssignment().user);
        }
    }

    @Test public void virtualClearScopeUsesNamedProfileAndExistingHistoryShowsUnassigned() {
        store().addManual(110,70,65.0,1,java.time.LocalDateTime.of(2026,1,1,10,0),"old synthetic");
        store().createVirtualUser("Ada"); store().createVirtualUser("Bea"); VirtualUser chen=store().createVirtualUser("Chen"); store().setVirtualUsersEnabled(true);
        store().addManual(123,80,65.0,2,java.time.LocalDateTime.of(2026,1,2,10,0),"new synthetic"); store().assignPending(store().nextPendingAssignment().id,chen.id);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            Activity a=controller.get(); button(root(a),a.getString(R.string.main_history)).performClick();
            boolean unassigned=false,named=false;
            for(TextView text:views(root(a),TextView.class)) { unassigned|=text.getText().toString().contains(a.getString(R.string.vp_unassigned)); named|=text.getText().toString().contains("Chen"); }
            assertTrue("Old bank history must not be guessed into a virtual owner",unassigned); assertTrue(named);
            button(root(a),a.getString(R.string.main_clear_history)).performClick(); AlertDialog scope=ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(4,scope.getListView().getAdapter().getCount()); assertEquals("Chen",scope.getListView().getAdapter().getItem(3));
            scope.getListView().performItemClick(scope.getListView().getChildAt(3),3,3); scope.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(((TextView)confirmation.findViewById(android.R.id.message)).getText().toString().contains("Chen"));
            assertEquals(1,store().historyCount(-chen.id)); assertEquals(2,store().historyCount(0));
        }
    }

}
