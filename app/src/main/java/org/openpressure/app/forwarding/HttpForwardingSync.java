package org.openpressure.app.forwarding;

import android.content.Context;
import androidx.work.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openpressure.app.data.ExperimentalConsent;
import org.openpressure.app.data.ReadingStore;

/** WorkManager owns network execution; ingestion only persists events and schedules work. */
public final class HttpForwardingSync {
    private static final String EXPORT_WORK="http-forwarding-export",RETRY_WORK="http-forwarding-retry";
    private static final int BATCH_SIZE=20;
    private final Context context;
    private final ReadingStore store;
    public HttpForwardingSync(Context context,ReadingStore store) { this.context=context.getApplicationContext(); this.store=store; }
    private boolean enabled() { return ExperimentalConsent.accepted(context) && HttpForwardingSettings.get(context).enabled(); }
    private static Constraints constraints() { return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); }
    public void refresh() {
        try {
            WorkManager manager=WorkManager.getInstance(context);
            if(!enabled()) { manager.cancelUniqueWork(EXPORT_WORK); manager.cancelUniqueWork(RETRY_WORK); return; }
            PeriodicWorkRequest periodic=new PeriodicWorkRequest.Builder(HttpForwardingWorker.class,15,TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
            manager.enqueueUniquePeriodicWork(RETRY_WORK,ExistingPeriodicWorkPolicy.KEEP,periodic);
            enqueue(ExistingWorkPolicy.KEEP);
        } catch(RuntimeException unavailable) {
            // Scheduling is best effort: committed payloads remain durable for a later refresh.
        }
    }
    public void onRecordsChanged() { enqueue(ExistingWorkPolicy.KEEP); }
    public void retryNow() { if(enabled()) { refresh(); enqueue(ExistingWorkPolicy.REPLACE); } }
    private void enqueue(ExistingWorkPolicy policy) {
        if(!enabled()) return;
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(HttpForwardingWorker.class)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        try { WorkManager.getInstance(context).enqueueUniqueWork(EXPORT_WORK,policy,request); }
        catch(RuntimeException unavailable) { /* A later refresh/retry recovers the durable outbox. */ }
    }
    /** Invoked only by background workers. False asks WorkManager to retry a bounded batch. */
    public synchronized boolean exportPending() {
        if(Thread.currentThread().isInterrupted()) return false;
        HttpForwardingSettings.Config token=HttpForwardingSettings.get(context);
        if(!token.enabled() || !ExperimentalConsent.accepted(context)) return true;
        try {
            for(HttpOutbox.Entry entry:store.httpOutbox().pending(token.endpoint,BATCH_SIZE)) {
                if(Thread.currentThread().isInterrupted()) return false;
                HttpForwardingSettings.Config current=HttpForwardingSettings.get(context);
                if(!ExperimentalConsent.accepted(context) || !current.enabled() || current.generation!=token.generation || !current.endpoint.equals(entry.endpoint)) return true;
                if(!send(entry,current)) { failed(true,token.endpoint); return false; }
                // A successful response acknowledges the original event even if settings changed in flight.
                store.httpOutbox().acknowledge(entry);
                failed(false,token.endpoint);
            }
            return store.httpOutbox().count(token.endpoint)==0;
        } catch(IOException | RuntimeException failure) {
            // URLs and exception messages may contain secrets; keep only a boolean UI status.
            failed(true,token.endpoint); return false;
        }
    }
    private boolean send(HttpOutbox.Entry entry,HttpForwardingSettings.Config config) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(entry.endpoint).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json");
            connection.setRequestProperty("Idempotency-Key",entry.key);
            if(!config.authorization.isEmpty()) connection.setRequestProperty("Authorization",config.authorization);
            byte[] body=entry.payload.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(body.length);
            try(OutputStream output=connection.getOutputStream()) { output.write(body); }
            int status=connection.getResponseCode();
            return status>=200 && status<300;
        } finally { connection.disconnect(); }
    }
    private void failed(boolean failed,String endpoint) {
        context.getSharedPreferences("http_forwarding_status",Context.MODE_PRIVATE).edit()
            .putBoolean("failed",failed).putString("destination",HttpPayload.sha256(endpoint)).apply();
    }
    public static boolean lastAttemptFailed(Context context) {
        HttpForwardingSettings.Config config=HttpForwardingSettings.get(context);
        android.content.SharedPreferences state=context.getSharedPreferences("http_forwarding_status",Context.MODE_PRIVATE);
        return config.enabled() && state.getBoolean("failed",false) && HttpPayload.sha256(config.endpoint).equals(state.getString("destination",""));
    }
}
