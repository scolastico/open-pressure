package org.openpressure.app.profiles;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows=MainVirtualUsersTest.SchedulerShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AssignmentActivityTest {
    private ReadingStore store() { return ((PressureApp)RuntimeEnvironment.getApplication()).store(); }
    @After public void close() { store().close(); }
    private View root(Activity a) { return a.findViewById(android.R.id.content); }
    private void reading(int sys,int day) { store().addManual(sys,70,60.0,2,java.time.LocalDateTime.of(2026,1,day,10,0),"synthetic"); }
    private void click(Activity a,String name) { android.widget.Button action=MainVirtualUsersTest.button(root(a),name); assertNotNull(name,action); action.performClick(); }
    @Test public void explicitChoicesAssignOnlyOldestOneAtATimeAndNeverGuessFromBank() {
        VirtualUser ada=store().createVirtualUser("Ada"),bea=store().createVirtualUser("Bea"); store().setVirtualUsersEnabled(true);
        reading(122,2); reading(111,1);
        try(ActivityController<AssignmentActivity> controller=Robolectric.buildActivity(AssignmentActivity.class).setup()) {
            Activity a=controller.get(); assertEquals(0,store().historyCount(0)); assertEquals(2,store().pendingAssignmentsCount());
            assertTrue(((TextView)root(a).findViewWithTag("assignment_pressure")).getText().toString().contains("111"));
            click(a,"Bea"); assertEquals(1,store().historyCount(-bea.id)); assertEquals(0,store().historyCount(-ada.id));
            assertEquals(1,store().pendingAssignmentsCount()); assertFalse(a.isFinishing());
            assertTrue(((TextView)root(a).findViewWithTag("assignment_pressure")).getText().toString().contains("122"));
            click(a,"Ada"); assertEquals(1,store().historyCount(-ada.id)); assertEquals(0,store().pendingAssignmentsCount()); assertTrue(a.isFinishing());
        }
    }
    @Test public void recreationAndLaterKeepEveryUnassignedMeasurementDurable() {
        store().createVirtualUser("Ada"); store().setVirtualUsersEnabled(true); reading(111,1); reading(122,2);
        ActivityController<AssignmentActivity> controller=Robolectric.buildActivity(AssignmentActivity.class).setup();
        Bundle state=new Bundle(); controller.saveInstanceState(state).pause().stop().destroy();
        try(ActivityController<AssignmentActivity> restored=Robolectric.buildActivity(AssignmentActivity.class).create(state).start().restoreInstanceState(state).resume().visible()) {
            assertEquals(2,store().pendingAssignmentsCount()); click(restored.get(),restored.get().getString(R.string.vp_later));
            assertTrue(restored.get().isFinishing()); assertEquals(2,store().pendingAssignmentsCount()); assertEquals(0,store().historyCount(0));
        }
    }
    @Test public void removedProfileCannotReceiveMeasurementFromAStaleButton() {
        VirtualUser ada=store().createVirtualUser("Ada"),bea=store().createVirtualUser("Bea"); store().setVirtualUsersEnabled(true); reading(111,1);
        try(ActivityController<AssignmentActivity> controller=Robolectric.buildActivity(AssignmentActivity.class).setup()) {
            android.widget.Button stale=MainVirtualUsersTest.button(root(controller.get()),"Ada");
            store().deleteVirtualUser(ada.id,bea.id); stale.performClick();
            assertEquals(1,store().pendingAssignmentsCount()); assertEquals(0,store().historyCount(0)); assertFalse(controller.get().isFinishing());
            click(controller.get(),"Bea"); assertEquals(1,store().historyCount(-bea.id));
        }
    }
    @Test public void newlyArrivedOlderMeasurementRequiresFreshExplicitChoice() {
        VirtualUser ada=store().createVirtualUser("Ada"); store().setVirtualUsersEnabled(true); reading(122,2);
        try(ActivityController<AssignmentActivity> controller=Robolectric.buildActivity(AssignmentActivity.class).setup()) {
            android.widget.Button stale=MainVirtualUsersTest.button(root(controller.get()),"Ada"); reading(111,1); stale.performClick();
            assertEquals(2,store().pendingAssignmentsCount()); assertEquals(0,store().historyCount(0));
            click(controller.get(),"Ada"); assertEquals(111,store().readings(-ada.id).get(0).systolic,0.01);
        }
    }
    @Test @Config(qualifiers="ar-rEG") public void arabicPressureKeepsSystolicDiastolicOrderWithBidirectionalIsolation() {
        store().createVirtualUser("Synthetic owner"); store().setVirtualUsersEnabled(true); reading(111,1);
        try(ActivityController<AssignmentActivity> controller=Robolectric.buildActivity(AssignmentActivity.class).setup()) {
            String pressure=((TextView)root(controller.get()).findViewWithTag("assignment_pressure")).getText().toString();
            assertEquals("\u2066\u2066١١١\u2069 / \u2066٧٠\u2069 mmHg\u2069",pressure);
        }
    }

}
