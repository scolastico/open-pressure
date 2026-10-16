package org.openpressure.app.health;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.work.WorkManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.VirtualUser;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=PressureApp.class,qualifiers="en",shadows={HealthConnectVirtualUsersTest.SchedulerShadow.class,HealthConnectVirtualUsersTest.SchedulerCompanionShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class HealthConnectVirtualUsersTest {
    @Implements(value=WorkManager.class,isInAndroidSdk=false)
    public static class SchedulerShadow {
        @Implementation public static WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    @Implements(value=WorkManager.Companion.class,isInAndroidSdk=false)
    public static class SchedulerCompanionShadow {
        @Implementation public WorkManager getInstance(Context context) { return mock(WorkManager.class); }
    }
    private PressureApp app() { return (PressureApp)RuntimeEnvironment.getApplication(); }
    private SharedPreferences prefs() { return app().getSharedPreferences("health_connect",Context.MODE_PRIVATE); }
    @After public void closeStore() { app().store().close(); }
    private void grantSyntheticExport() { prefs().edit().putBoolean("enabled",true).putBoolean("permission_pending",false).commit(); }

    @Test public void modeRoundTripInvalidatesLegacyConsentAndRequiresExplicitChoiceAgain() {
        ReadingStore store=app().store(); ExperimentalConsent.accept(app());
        HealthConnectSync sync=app().healthConnect();
        sync.prepareEnable(1); grantSyntheticExport(); assertTrue(sync.enabled());
        store.createVirtualUser("Alice"); store.setVirtualUsersEnabled(true); assertFalse(sync.enabled());
        store.setVirtualUsersEnabled(false); assertFalse("Returning to monitor banks must not reactivate prior consent",sync.enabled());
        sync.prepareEnable(2); grantSyntheticExport(); assertTrue(sync.enabled()); assertEquals(2,sync.selectedUser());
    }

    @Test public void explicitVirtualSelectionPersistsAndDeletingSelectedPersonInvalidatesConsent() {
        ReadingStore store=app().store(); ExperimentalConsent.accept(app());
        VirtualUser own=store.createVirtualUser("Alice"),other=store.createVirtualUser("Bob");
        store.setVirtualUsersEnabled(true); HealthConnectSync sync=app().healthConnect();
        sync.prepareEnable(-own.id); grantSyntheticExport(); assertTrue(sync.enabled());
        assertEquals(-own.id,new HealthConnectSync(app(),store).selectedUser());
        assertTrue(new HealthConnectSync(app(),store).enabled());
        store.deleteVirtualUser(own.id,other.id); assertFalse(sync.enabled());
        try { sync.prepareEnable(-own.id); fail("Deleted person cannot be selected"); } catch(IllegalArgumentException expected) { }
        try { sync.prepareEnable(1); fail("Physical banks cannot select virtual ownership"); } catch(IllegalArgumentException expected) { }
    }

    @Test public void settingsShowNamedProfilesAndRefreshRenameAdditionAndDeletionOnResume() {
        ReadingStore store=app().store();
        VirtualUser own=store.createVirtualUser("Alice"),other=store.createVirtualUser("Bob");
        store.setVirtualUsersEnabled(true);
        try(ActivityController<HealthConnectSettingsActivity> controller=Robolectric.buildActivity(HealthConnectSettingsActivity.class).setup()) {
            assertEquals(java.util.Arrays.asList("Alice","Bob"),labels(controller.get()));
            List<RadioButton> radios=views(controller.get(),RadioButton.class); radios.get(0).setChecked(true);
            store.renameVirtualUser(own.id,"Renamed Alice"); store.createVirtualUser("Charlie"); store.deleteVirtualUser(other.id,own.id);
            controller.pause().resume(); org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(java.util.Arrays.asList("Renamed Alice","Charlie"),labels(controller.get()));
            assertTrue(views(controller.get(),RadioButton.class).get(0).isChecked());
            store.setVirtualUsersEnabled(false); store.setVirtualUsersEnabled(true); controller.pause().resume();
            for(RadioButton radio:views(controller.get(),RadioButton.class)) assertFalse("A mode roundtrip requires choosing again",radio.isChecked());
            assertTrue(texts(controller.get()).contains(controller.get().getString(R.string.vp_health_intro)));
            assertFalse(texts(controller.get()).contains(controller.get().getString(R.string.health_selection_off)));
            store.setVirtualUsersEnabled(false); controller.pause().resume();
            assertEquals(java.util.Arrays.asList(controller.get().getString(R.string.health_user_one),controller.get().getString(R.string.health_user_two)),labels(controller.get()));
            for(RadioButton radio:views(controller.get(),RadioButton.class)) assertFalse("Mode changes require a fresh choice",radio.isChecked());
        }
    }

    @Test public void localizedSummaryNamesSelectedProfileWithoutMonitorBankNumbers() {
        ReadingStore store=app().store(); ExperimentalConsent.accept(app());
        VirtualUser own=store.createVirtualUser("Alice"); store.setVirtualUsersEnabled(true);
        HealthConnectSync sync=app().healthConnect(); sync.prepareEnable(-own.id); grantSyntheticExport();
        // A fresh sync reflects persisted enabled state without dispatching a provider write.
        sync=new HealthConnectSync(app(),store);
        try {
            java.lang.reflect.Field status=HealthConnectSync.class.getDeclaredField("statusText"); status.setAccessible(true); status.set(sync,"Automatic export enabled");
        } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
        assertEquals(app().getString(R.string.vp_health_summary,"Alice",0,0),HealthConnectSettingsActivity.localizedStatus(app(),sync));
    }
    @Test public void disablingDoesNotWaitForStoreMonitorHeldByConcurrentIngestion() throws Exception {
        ReadingStore store=app().store(); HealthConnectSync sync=app().healthConnect();
        java.util.concurrent.CountDownLatch held=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newCachedThreadPool();
        Thread[] snapshotThread=new Thread[1];
        try {
            java.util.concurrent.Future<?> holding=pool.submit(() -> { synchronized(store) { held.countDown(); try { release.await(); } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); } } });
            assertTrue(held.await(2,java.util.concurrent.TimeUnit.SECONDS));
            java.lang.reflect.Method snapshot=HealthConnectSync.class.getDeclaredMethod("snapshot"); snapshot.setAccessible(true);
            java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<?> takingSnapshot=pool.submit(() -> { snapshotThread[0]=Thread.currentThread(); started.countDown(); try { snapshot.invoke(sync); } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); } });
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(snapshotThread[0].getState()!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.yield();
            assertEquals("Snapshot must be waiting for the store monitor",Thread.State.BLOCKED,snapshotThread[0].getState());
            java.util.concurrent.Future<?> disabling=pool.submit(sync::disable);
            try { disabling.get(2,java.util.concurrent.TimeUnit.SECONDS); }
            catch(java.util.concurrent.TimeoutException deadlock) { fail("Disable must remain available while storage is busy; do not acquire the store under stateLock"); }
            finally { release.countDown(); }
            takingSnapshot.get(2,java.util.concurrent.TimeUnit.SECONDS); holding.get(2,java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS)); }
    }

    private List<String> labels(Activity activity) { List<String> labels=new ArrayList<>(); for(RadioButton view:views(activity,RadioButton.class)) labels.add(view.getText().toString()); return labels; }
    private List<String> texts(Activity activity) { List<String> texts=new ArrayList<>(); for(TextView view:views(activity,TextView.class)) texts.add(view.getText().toString()); return texts; }
    private <T extends View> List<T> views(Activity activity,Class<T> type) { List<T> result=new ArrayList<>(); collect(activity.findViewById(android.R.id.content),type,result); return result; }
    private <T extends View> void collect(View view,Class<T> type,List<T> result) {
        if(type.isInstance(view)) result.add(type.cast(view));
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) collect(((ViewGroup)view).getChildAt(i),type,result);
    }
}
