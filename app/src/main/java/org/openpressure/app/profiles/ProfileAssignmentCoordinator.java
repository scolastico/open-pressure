package org.openpressure.app.profiles;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.ReadingStore;

/** Routes durable assignment work to a foreground picker or a background notification. */
public final class ProfileAssignmentCoordinator implements Application.ActivityLifecycleCallbacks {
    private final Application app;
    private final ReadingStore store;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable update=this::reconcile;
    private Activity resumed;
    private int started,lastPending;
    private boolean deferred,launching;
    public ProfileAssignmentCoordinator(Application app,ReadingStore store) {
        this.app=app; this.store=store; app.registerActivityLifecycleCallbacks(this); recordsChanged();
    }
    /** Storage callbacks may originate on Bluetooth or worker threads. */
    public void recordsChanged() { handler.removeCallbacks(update); handler.post(update); }
    public void deferUntilNextForeground() {
        deferred=true; launching=false;
        try { lastPending=store.pendingAssignmentsCount(); } catch(RuntimeException unavailable) { }
    }
    private void reconcile() {
        try {
            int pending=store.pendingAssignmentsCount();
            if(pending>lastPending) deferred=false;
            lastPending=pending;
            if(pending==0 || !ExperimentalConsent.accepted(app)) { ProfileAssignmentNotifications.cancel(app); return; }
            if(started==0) { ProfileAssignmentNotifications.show(app,pending); return; }
            ProfileAssignmentNotifications.cancel(app);
            Activity current=resumed;
            if(current==null || current.isFinishing() || current.isDestroyed()) return;
            if(current instanceof AssignmentActivity) { ((AssignmentActivity)current).refreshPending(); return; }
            if(current instanceof VirtualUsersActivity || deferred || launching) return;
            launching=true;
            try { current.startActivity(new Intent(current,AssignmentActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
            catch(RuntimeException unavailable) { launching=false; }
        } catch(RuntimeException unavailable) {
            // The durable inbox remains authoritative; a later foreground transition retries.
        }
    }
    @Override public void onActivityCreated(Activity activity,Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { started++; }
    @Override public void onActivityResumed(Activity activity) { resumed=activity; launching=false; recordsChanged(); }
    @Override public void onActivityPaused(Activity activity) { if(resumed==activity) resumed=null; }
    @Override public void onActivityStopped(Activity activity) {
        started=Math.max(0,started-1);
        if(started==0 && !activity.isChangingConfigurations()) { deferred=false; launching=false; }
        recordsChanged();
    }
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { if(resumed==activity) resumed=null; }
}
