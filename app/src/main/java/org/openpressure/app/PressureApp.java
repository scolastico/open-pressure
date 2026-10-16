package org.openpressure.app;

import android.app.Application;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.health.HealthConnectSync;

public final class PressureApp extends Application {
    private ReadingStore store;
    private HealthConnectSync healthConnect;
    @Override public void onCreate() {
        super.onCreate();
        store=new ReadingStore(this);
        healthConnect=new HealthConnectSync(this,store);
        store.setChangeListener(healthConnect::onRecordsChanged);
        healthConnect.refresh();
    }
    public ReadingStore store() { return store; }
    public HealthConnectSync healthConnect() { return healthConnect; }
}
