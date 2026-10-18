package org.openpressure.app.developer;

import android.app.Application;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.protocol.WriteAspectIndex.Aspect;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class DeveloperOptionsTest {
    private Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication(); }

    @Test public void lockedByDefaultWithNoAspectAllowed() {
        assertFalse(DeveloperOptions.isUnlocked(context));
        for(Aspect aspect:Aspect.values()) assertFalse(DeveloperOptions.allowsUntested(context,aspect));
    }
    @Test public void unlockPersistsAcrossANewContextRead() {
        DeveloperOptions.setUnlocked(context,true);
        Context fresh=RuntimeEnvironment.getApplication().getApplicationContext();
        assertTrue(DeveloperOptions.isUnlocked(fresh));
        assertTrue(fresh.getSharedPreferences(DeveloperOptions.PREFERENCES,Context.MODE_PRIVATE).getBoolean("unlocked",false));
    }
    @Test public void allowUntestedIsStoredPerAspect() {
        DeveloperOptions.setUnlocked(context,true);
        DeveloperOptions.setAllowUntested(context,Aspect.CLOCK_BLOCK,true);
        assertTrue(DeveloperOptions.allowsUntested(context,Aspect.CLOCK_BLOCK));
        assertFalse(DeveloperOptions.allowsUntested(context,Aspect.UNSENT_COUNTERS));
        assertTrue(context.getSharedPreferences(DeveloperOptions.PREFERENCES,Context.MODE_PRIVATE).getBoolean("allow_untested_clock_block",false));
        DeveloperOptions.setAllowUntested(context,Aspect.CLOCK_BLOCK,false);
        assertFalse(DeveloperOptions.allowsUntested(context,Aspect.CLOCK_BLOCK));
    }
    @Test public void lockingClearsEveryAspectToggle() {
        DeveloperOptions.setUnlocked(context,true);
        for(Aspect aspect:Aspect.values()) DeveloperOptions.setAllowUntested(context,aspect,true);
        DeveloperOptions.setUnlocked(context,false);
        assertFalse(DeveloperOptions.isUnlocked(context));
        for(Aspect aspect:Aspect.values()) assertFalse(DeveloperOptions.allowsUntested(context,aspect));
        DeveloperOptions.setUnlocked(context,true);
        for(Aspect aspect:Aspect.values()) assertFalse(DeveloperOptions.allowsUntested(context,aspect));
    }
    @Test public void unlockingStartsWithEveryToggleOff() {
        DeveloperOptions.setUnlocked(context,true);
        DeveloperOptions.setAllowUntested(context,Aspect.CLOCK_BLOCK,true);
        DeveloperOptions.setUnlocked(context,false);
        assertTrue(context.getSharedPreferences(DeveloperOptions.PREFERENCES,Context.MODE_PRIVATE).edit().putBoolean("allow_untested_clock_block",true).commit());
        DeveloperOptions.setUnlocked(context,true);
        assertFalse(DeveloperOptions.allowsUntested(context,Aspect.CLOCK_BLOCK));
        assertFalse(context.getSharedPreferences(DeveloperOptions.PREFERENCES,Context.MODE_PRIVATE).contains("allow_untested_clock_block"));
    }
    @Test public void chosenClockTimeFollowsTheUnlockedState() {
        assertFalse("locked by default",DeveloperOptions.allowsChosenClockTime(context));
        DeveloperOptions.setUnlocked(context,true);
        assertTrue("unlocked options suffice, without the clock switch",DeveloperOptions.allowsChosenClockTime(context));
        DeveloperOptions.setAllowUntested(context,Aspect.CLOCK_BLOCK,true);
        assertTrue(DeveloperOptions.allowsChosenClockTime(context));
        DeveloperOptions.setAllowUntested(context,Aspect.CLOCK_BLOCK,false);
        assertTrue("the clock switch does not affect it",DeveloperOptions.allowsChosenClockTime(context));
        DeveloperOptions.setUnlocked(context,false);
        assertFalse(DeveloperOptions.allowsChosenClockTime(context));
        assertTrue(context.getSharedPreferences(DeveloperOptions.PREFERENCES,Context.MODE_PRIVATE).edit().putBoolean("allow_untested_clock_block",true).commit());
        assertFalse("a stale clock switch never allows it while locked",DeveloperOptions.allowsChosenClockTime(context));
    }
    @Test public void staleToggleIsIgnoredWhileLocked() {
        assertTrue(context.getSharedPreferences(DeveloperOptions.PREFERENCES,Context.MODE_PRIVATE).edit().putBoolean("allow_untested_clock_block",true).commit());
        assertFalse(DeveloperOptions.allowsUntested(context,Aspect.CLOCK_BLOCK));
    }
    @Test public void allowUntestedWhileLockedThrows() {
        assertThrows(IllegalStateException.class,()->DeveloperOptions.setAllowUntested(context,Aspect.CLOCK_BLOCK,true));
        assertThrows(IllegalStateException.class,()->DeveloperOptions.setAllowUntested(context,Aspect.CLOCK_BLOCK,false));
        assertFalse(DeveloperOptions.allowsUntested(context,Aspect.CLOCK_BLOCK));
    }
    @Test public void nullAspectIsRejected() {
        DeveloperOptions.setUnlocked(context,true);
        assertThrows(NullPointerException.class,()->DeveloperOptions.allowsUntested(context,null));
        assertThrows(NullPointerException.class,()->DeveloperOptions.setAllowUntested(context,null,true));
    }

    @Test public void tapConstants() {
        assertEquals(5,DeveloperOptions.TAP_TARGET);
        assertEquals(3000L,DeveloperOptions.TAP_WINDOW_MS);
    }
    @Test public void fiveQuickTapsUnlock() {
        DeveloperOptions.TapCounter counter=new DeveloperOptions.TapCounter();
        assertEquals(4,counter.tap(1000));
        assertEquals(3,counter.tap(1100));
        assertEquals(2,counter.tap(1200));
        assertEquals(1,counter.tap(1300));
        assertEquals(0,counter.tap(1400));
    }
    @Test public void gapLongerThanWindowRestartsTheCount() {
        DeveloperOptions.TapCounter counter=new DeveloperOptions.TapCounter();
        for(int i=0;i<4;i++) counter.tap(1000+i*100);
        assertEquals(4,counter.tap(1300+3500));
        assertEquals(3,counter.tap(4900));
        assertEquals(2,counter.tap(5000));
        assertEquals(1,counter.tap(5100));
        assertEquals(0,counter.tap(5200));
    }
    @Test public void gapExactlyAtWindowStillCounts() {
        DeveloperOptions.TapCounter counter=new DeveloperOptions.TapCounter();
        counter.tap(0);
        assertEquals(3,counter.tap(3000));
        assertEquals(4,counter.tap(6001));
    }
    @Test public void counterRestartsAfterUnlocking() {
        DeveloperOptions.TapCounter counter=new DeveloperOptions.TapCounter();
        for(int i=0;i<5;i++) counter.tap(i*100);
        assertEquals(4,counter.tap(600));
    }
    @Test public void backwardsClockRestartsTheCount() {
        DeveloperOptions.TapCounter counter=new DeveloperOptions.TapCounter();
        counter.tap(5000);
        counter.tap(5100);
        assertEquals(4,counter.tap(100));
    }
}
