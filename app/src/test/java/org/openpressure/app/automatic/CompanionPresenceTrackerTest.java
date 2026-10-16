package org.openpressure.app.automatic;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CompanionPresenceTrackerTest {
    @Test public void bluetoothDisconnectKeepsLeaseUntilBleAlsoDisappears() {
        CompanionPresenceTracker tracker=new CompanionPresenceTracker();
        tracker.update("AA",1,true);
        tracker.update("AA",2,true);
        assertTrue(tracker.update("AA",2,false));
        assertFalse(tracker.update("AA",1,false));
    }
    @Test public void monitorsHaveIndependentPresence() {
        CompanionPresenceTracker tracker=new CompanionPresenceTracker();
        tracker.update("AA",1,true);
        tracker.update("BB",1,true);
        assertFalse(tracker.update("AA",1,false));
        assertTrue(tracker.present("BB"));
    }
    @Test public void revokedAuthorizationClearsAllPresenceSources() {
        CompanionPresenceTracker tracker=new CompanionPresenceTracker();
        tracker.update("AA",1,true);
        tracker.update("AA",2,true);
        assertFalse(tracker.update("AA",2,false,false));
        assertFalse(tracker.present("AA"));
    }
    @Test public void unauthorizedAppearanceCannotAcquireLease() {
        CompanionPresenceTracker tracker=new CompanionPresenceTracker();
        assertFalse(tracker.update("AA",1,true,false));
        assertFalse(tracker.present("AA"));
    }
    @Test public void disappearanceWithoutAppearanceDoesNotAcquireLease() {
        assertFalse(new CompanionPresenceTracker().update("AA",2,false));
    }
}
