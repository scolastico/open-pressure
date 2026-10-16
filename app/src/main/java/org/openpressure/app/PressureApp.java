package org.openpressure.app;

import android.app.Application;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.health.HealthConnectSync;
import org.openpressure.app.forwarding.HttpForwardingSync;
import org.openpressure.app.reminders.ReminderScheduler;

public final class PressureApp extends Application {
    private ReadingStore store;
    private HealthConnectSync healthConnect;
    private HttpForwardingSync httpForwarding;
    @Override public void onCreate() {
        super.onCreate();
        store=new ReadingStore(this);
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
}
