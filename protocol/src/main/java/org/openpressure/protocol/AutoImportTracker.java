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

    /** A plain record summary with stored records: neither pairing nor streaming, and at least one populated bank. */
    public static boolean importable(OmronAdvertisement advertisement) {
        if(advertisement==null || advertisement.pairingMode || advertisement.streamingMode || !advertisement.hasRecordCounts()) return false;
        for(OmronAdvertisement.User user:advertisement.users) {
            if(user.lastSequence>0 && user.recordCount>0) return true;
        }
        return false;
    }

    public synchronized Attempt offer(String address,OmronAdvertisement advertisement,long nowMillis) {
        if(active!=null || address==null || address.isEmpty() || !importable(advertisement)) return null;
        String revision=advertisement.revision();
        if(revision.equals(completed.get(address))) return null;
        Long failedAt=failures.get(address);
        if(failedAt!=null && (nowMillis<failedAt || nowMillis-failedAt<RETRY_DELAY_MILLIS)) return null;
        active=new Attempt(address,revision,++nextToken);
        return active;
    }

    /**
     * A user-requested import: like {@link #offer} but it ignores the completed revision and the failure cooldown.
     * Its attempt finishes exactly like an offered one, so a validated END still checkpoints and a failure still
     * starts the cooldown for advertisement-triggered imports.
     */
    public synchronized Attempt force(String address,OmronAdvertisement advertisement,long nowMillis) {
        if(active!=null || address==null || address.isEmpty() || !importable(advertisement)) return null;
        active=new Attempt(address,advertisement.revision(),++nextToken);
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
