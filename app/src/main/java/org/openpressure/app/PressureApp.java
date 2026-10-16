package org.openpressure.app;

import android.app.Application;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.health.HealthConnectSync;
import org.openpressure.app.forwarding.HttpForwardingSync;
import org.openpressure.app.reminders.ReminderScheduler;
import org.openpressure.app.profiles.ProfileAssignmentCoordinator;

public final class PressureApp extends Application {
    private ReadingStore store;
    private HealthConnectSync healthConnect;
    private HttpForwardingSync httpForwarding;
    private ProfileAssignmentCoordinator assignments;
    @Override public void onCreate() {
        super.onCreate();
        store=new ReadingStore(this);
        assignments=new ProfileAssignmentCoordinator(this,store);
        store.setProfileListener(assignments::recordsChanged);
        store.restoreReminderMeasurement();
        healthConnect=new HealthConnectSync(this,store);
        store.setChangeListener(healthConnect::onRecordsChanged);
        healthConnect.refresh();
        httpForwarding=new HttpForwardingSync(this,store);
        store.setForwardingListener(httpForwarding::onRecordsChanged);
        httpForwarding.refresh();
        try { new ReminderScheduler(this).refresh(); }
        catch(IllegalStateException unavailable) { /* Retry durable reminders on the next foreground refresh. */ }
    }
    public ReadingStore store() { return store; }
    public HealthConnectSync healthConnect() { return healthConnect; }
    public HttpForwardingSync httpForwarding() { return httpForwarding; }
    public ProfileAssignmentCoordinator assignments() { return assignments; }
}
