package org.openpressure.app.notifications;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AutomaticReadingNotificationPolicyTest {
    @Test public void newAutomaticReadingsNotifyOnceWhenTransferEndsInBackground() {
        AutomaticReadingNotificationPolicy policy=new AutomaticReadingNotificationPolicy();
        policy.begin(true);
        policy.readingInserted();
        policy.readingInserted();
        assertEquals(2,policy.finish(true));
        assertEquals(0,policy.finish(true));
    }

    @Test public void advertisementsDuplicatesAndRejectedRecordsDoNotProduceNotifications() {
        AutomaticReadingNotificationPolicy policy=new AutomaticReadingNotificationPolicy();
        assertEquals(0,policy.finish(true));
        policy.begin(true);
        // None of these inputs inserted a local reading.
        assertEquals(0,policy.finish(true));
    }

    @Test public void foregroundTransferIsConsumedWithoutLaterBackgroundNotification() {
        AutomaticReadingNotificationPolicy policy=new AutomaticReadingNotificationPolicy();
        policy.begin(true);
        policy.readingInserted();
        assertEquals(0,policy.finish(false));
        assertEquals(0,policy.finish(true));
    }

    @Test public void manualTransfersNeverNotify() {
        AutomaticReadingNotificationPolicy policy=new AutomaticReadingNotificationPolicy();
        policy.begin(false);
        policy.readingInserted();
        assertEquals(0,policy.finish(true));
    }

    @Test public void newConnectionClearsPreviousTransferCountBeforeSetupCanFail() {
        AutomaticReadingNotificationPolicy policy=new AutomaticReadingNotificationPolicy();
        policy.begin(true);
        policy.readingInserted();
        policy.begin(true);
        assertEquals(0,policy.finish(true));
    }

    @Test public void partialTransferCountsOnlySavedReadingsAndNextRetryStartsFresh() {
        AutomaticReadingNotificationPolicy policy=new AutomaticReadingNotificationPolicy();
        policy.begin(true);
        policy.readingInserted();
        // A failure or cancellation still ends the transfer with one saved reading.
        assertEquals(1,policy.finish(true));
        policy.readingInserted(); // Late callbacks after completion cannot revive it.
        assertEquals(0,policy.finish(true));
        policy.begin(true);
        assertEquals(0,policy.finish(true));
    }
}
