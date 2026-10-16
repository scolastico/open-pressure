package org.openpressure.app.profiles;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.Looper;
import java.time.LocalDateTime;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.R;
import org.openpressure.app.data.*;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ProfileAssignmentCoordinatorTest {
    public static class ForegroundActivity extends Activity { }
    private Application app;
    private ReadingStore store;
    private ProfileAssignmentCoordinator coordinator;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication(); store=new ReadingStore(app);
        store.createVirtualUser("Synthetic person"); store.setVirtualUsersEnabled(true);
        ExperimentalConsent.accept(app);
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        coordinator=new ProfileAssignmentCoordinator(app,store);
        store.setProfileListener(coordinator::recordsChanged);
    }
    @After public void close() { store.close(); }
    private void add(int minutes) { store.addManual(120,80,null,1,LocalDateTime.now().minusMinutes(minutes),"synthetic private note"); }
    @Test public void backgroundReadingsProduceCountOnlyNotificationOpeningDurablePicker() {
        add(10); add(5); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2,store.pendingAssignmentsCount());
        NotificationManager manager=app.getSystemService(NotificationManager.class);
        assertEquals(1,shadowOf(manager).size());
        Notification notification=shadowOf(manager).getAllNotifications().get(0);
        assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility);
        assertEquals(app.getString(R.string.vp_pending_notification_text,2),notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(AssignmentActivity.class.getName(),shadowOf(notification.contentIntent).getSavedIntent().getComponent().getClassName());
        // Swiping away the alert must never consume queued measurement data.
        manager.cancelAll(); assertEquals(2,store.pendingAssignmentsCount());
    }
    @Test public void foregroundReadingOpensPickerWithoutDependingOnBleScreenVisibility() {
        try(ActivityController<ForegroundActivity> screen=Robolectric.buildActivity(ForegroundActivity.class).setup()) {
            add(10); shadowOf(Looper.getMainLooper()).idle();
            Intent intent=shadowOf(screen.get()).getNextStartedActivity();
            assertNotNull(intent); assertEquals(AssignmentActivity.class.getName(),intent.getComponent().getClassName());
            assertEquals(1,store.pendingAssignmentsCount());
            assertEquals(0,shadowOf(app.getSystemService(NotificationManager.class)).size());
        }
    }
    @Test public void ignoredNotificationIsRecoveredOnNextForegroundWithAllRowsIntact() {
        add(10); add(5); shadowOf(Looper.getMainLooper()).idle(); app.getSystemService(NotificationManager.class).cancelAll();
        try(ActivityController<ForegroundActivity> screen=Robolectric.buildActivity(ForegroundActivity.class).setup()) {
            shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(shadowOf(screen.get()).getNextStartedActivity());
            assertEquals(2,store.pendingAssignmentsCount());
            assertTrue(store.nextPendingAssignment().displayTime().isBefore(LocalDateTime.now().minusMinutes(8)));
        }
    }
    @Test public void laterDefersUntilAnotherForegroundSessionWithoutDeletingAnything() {
        try(ActivityController<ForegroundActivity> screen=Robolectric.buildActivity(ForegroundActivity.class).setup()) {
            coordinator.deferUntilNextForeground(); add(10); shadowOf(Looper.getMainLooper()).idle();
            // A newly arriving reading is allowed to prompt despite an earlier Later choice.
            assertNotNull(shadowOf(screen.get()).getNextStartedActivity());
            coordinator.deferUntilNextForeground(); coordinator.recordsChanged(); shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(screen.get()).getNextStartedActivity());
            screen.pause().stop(); screen.start().resume(); shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(shadowOf(screen.get()).getNextStartedActivity());
            assertEquals(1,store.pendingAssignmentsCount());
        }
    }
    @Test public void deniedNotificationsDoNotDiscardRowsOrPreventNextForegroundPrompt() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        add(5); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,shadowOf(app.getSystemService(NotificationManager.class)).size());
        try(ActivityController<ForegroundActivity> screen=Robolectric.buildActivity(ForegroundActivity.class).setup()) {
            shadowOf(Looper.getMainLooper()).idle(); assertNotNull(shadowOf(screen.get()).getNextStartedActivity());
            assertEquals(1,store.pendingAssignmentsCount());
        }
    }
}
