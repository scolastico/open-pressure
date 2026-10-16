package org.openpressure.app.notifications;

/** Counts locally inserted readings in a single automatic transfer. */
public final class AutomaticReadingNotificationPolicy {
    private boolean automatic;
    private int inserted;

    /** Reset before connection setup, including attempts that fail before history starts. */
    public void begin(boolean automatic) {
        this.automatic=automatic;
        inserted=0;
    }

    public void readingInserted() {
        if(automatic) inserted++;
    }

    /** Consume once on completion, failure, or cancellation; foreground results are discarded. */
    public int finish(boolean background) {
        int count=automatic && background?inserted:0;
        automatic=false;
        inserted=0;
        return count;
    }
}
