package org.openpressure.protocol;

import java.util.HashMap;
import java.util.Map;

/** Claims one advertisement-triggered import at a time and checkpoints only successful imports. */
public final class AutoImportTracker {
    private static final long RETRY_DELAY_MILLIS=60_000;
    private final Map<String,String> completed=new HashMap<>();
    private final Map<String,Long> failures=new HashMap<>();
    private long nextToken;
    private Attempt active;

    /** Saved identities with a verified read-only history path; encrypted transport is checked separately. */
    public static boolean supportsModel(String disModel) {
        return X7Record.supportsModel(disModel) || VendorMemoryModel.identify(disModel)!=null;
    }

    public synchronized void restore(String address,String revision) {
        if(revision==null) completed.remove(address);
        else completed.put(address,revision);
    }

    public synchronized String completedRevision(String address) { return completed.get(address); }

    public synchronized Attempt offer(String address,OmronAdvertisement advertisement,long nowMillis) {
        if(active!=null || address==null || address.isEmpty() || advertisement==null || advertisement.pairingMode || advertisement.streamingMode
                || !advertisement.hasRecordCounts()) return null;
        boolean hasRecords=false;
        for(OmronAdvertisement.User user:advertisement.users) {
            if(user.lastSequence>0 && user.recordCount>0) { hasRecords=true; break; }
        }
        if(!hasRecords) return null;
        String revision=advertisement.revision();
        if(revision.equals(completed.get(address))) return null;
        Long failedAt=failures.get(address);
        if(failedAt!=null && (nowMillis<failedAt || nowMillis-failedAt<RETRY_DELAY_MILLIS)) return null;
        active=new Attempt(address,revision,++nextToken);
        return active;
    }

    /** Returns false for duplicate callbacks, cancelled imports, or another tracker's attempt. */
    public synchronized boolean finish(Attempt attempt,boolean success,long nowMillis) {
        if(attempt==null || attempt!=active) return false;
        active=null;
        if(success) {
            completed.put(attempt.address,attempt.revision);
            failures.remove(attempt.address);
        } else failures.put(attempt.address,nowMillis);
        return true;
    }

    public synchronized void cancel(long nowMillis) {
        if(active!=null) finish(active,false,nowMillis);
    }

    public synchronized boolean busy() { return active!=null; }

    public static final class Attempt {
        public final String address, revision;
        public final long token;
        private Attempt(String address,String revision,long token) {
            this.address=address; this.revision=revision; this.token=token;
        }
    }
}
