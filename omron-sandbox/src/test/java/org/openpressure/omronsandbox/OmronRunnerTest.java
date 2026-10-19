package org.openpressure.omronsandbox;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.Outcome;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows={SandboxBluetoothDevice.class,ShadowOmsecNatives.class,ShadowOmronClock.class},
    instrumentedPackages={"jp.co.omron.healthcare.communicationlibrary","com.omronhealthcare.connectivitylibrary"})
@LooperMode(LooperMode.Mode.PAUSED)
public class OmronRunnerTest {
    @Test public void confirmedReferenceFixturesRequireCompleteMatch() {
        String key="key 05050505050505050505050505050505\n";
        Fixture exploratory=Fixture.parse("test/probe","name test/probe\n"+key);
        Fixture confirmed=Fixture.parse("test/confirmed","name test/confirmed\n"+key+"expect omron matches\n");
        String previous=System.getProperty("omron.sandbox.explore");
        try {
            System.setProperty("omron.sandbox.explore","false");
            for(Outcome outcome:new Outcome[]{Outcome.rejected(0,"synthetic failure"),Outcome.unsupported("missing catalog entry")}) {
                OmronFixtureRun.requireExpectedOutcome(exploratory,outcome);
                assertThrows(AssertionError.class,() -> OmronFixtureRun.requireExpectedOutcome(confirmed,outcome));
            }
            OmronFixtureRun.requireExpectedOutcome(confirmed,Outcome.matches(0));
        } finally {
            if(previous==null) System.clearProperty("omron.sandbox.explore");
            else System.setProperty("omron.sandbox.explore",previous);
        }
    }
    @Test public void explorationStillReportsConfirmedFixtureFindings() {
        Fixture fixture=Fixture.parse("test/probe","name test/probe\nkey 05050505050505050505050505050505\nexpect omron matches\n");
        String previous=System.getProperty("omron.sandbox.explore");
        try {
            System.setProperty("omron.sandbox.explore","true");
            OmronFixtureRun.requireExpectedOutcome(fixture,Outcome.rejected(0,"exploration finding"));
        } finally {
            if(previous==null) System.clearProperty("omron.sandbox.explore");
            else System.setProperty("omron.sandbox.explore",previous);
        }
    }
    private static final String CATALOG="[{\"category\":0,\"deviceGroupIncludedGroupIDKey\":\"1146\",\"identifier\":\"HEM-7156T_AAP\",\"name\":\"HEM-7156T-AAP\",\"noOfUsers\":2,\"deviceSeries\":\"ppw\"},"+
        "{\"category\":0,\"deviceGroupIncludedGroupIDKey\":\"1402\",\"identifier\":\"HEM-7156T_AP\",\"name\":\"HEM-7156T\"},"+
        "{\"category\":0,\"deviceGroupIncludedGroupIDKey\":\"600\",\"secondaryDeviceGroupIncludedGroupIDKey\":\"601\",\"identifier\":\"HEM-7361T_ESL\",\"name\":\"X7\"}]";

    @Test public void catalogPicksAPForBaseName() {
        OmronCatalog catalog=new OmronCatalog(CATALOG);
        assertEquals("device_category/0x0000/HEM-7156T_AP",catalog.folderFor("BLEsmart_0000057A0011223344").folder());
        assertEquals("HEM-7156T_AAP",catalog.folderFor("BLESmart_0000047a").identifier);
        assertEquals("user count from noOfUsers",2,catalog.folderFor("BLESmart_0000047a").users);
        assertEquals("ppw",catalog.folderFor("BLESmart_0000047a").series);
        assertEquals("one user when the catalog says nothing",1,catalog.folderFor("BLEsmart_0000057A").users);
        assertEquals("secondary group ID matches too","HEM-7361T_ESL",catalog.folderFor("BLEsmart_00000259").identifier);
    }
    @Test public void unknownModelIdIsUnsupported() {
        OmronCatalog catalog=new OmronCatalog(CATALOG);
        assertNull(catalog.folderFor("BLEsmart_00000999"));
        assertNull("other categories never match",catalog.folderFor("BLEsmart_0001057A"));
        assertNull("names without IDs carry no model",catalog.folderFor("Synthetic monitor"));
        Fixture f=Fixture.parse("x/y","name x/y\nadvertise name=BLEsmart_00000999 services=ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b\nkey 05050505050505050505050505050505\n");
        Outcome outcome=OmronRunner.select(f,catalog);
        assertEquals(Outcome.Kind.UNSUPPORTED,outcome.kind);
        assertTrue(outcome.detail,outcome.detail.contains("category 0x0000 model 0x0999"));
    }
    @Test public void clockSyncFlowIsUnsupported() {
        Fixture f=Fixture.load("hem-7155t/sync-clock");
        Outcome outcome=OmronRunner.select(f,new OmronCatalog(CATALOG));
        assertEquals(Outcome.Kind.UNSUPPORTED,outcome.kind);
        assertEquals("Omron has no standalone clock sync",outcome.detail);
    }
    @Test public void profileDefaultReplacesOnlyReportedBirthdays() {
        android.os.Bundle settings=new android.os.Bundle(),device=new android.os.Bundle(),user1=new android.os.Bundle(),user2=new android.os.Bundle(),birthday=new android.os.Bundle(),other=new android.os.Bundle();
        birthday.putString("data","0"); other.putString("data","1");
        user1.putBundle("1005",birthday); user1.putBundle("1026",other);
        device.putBundle("1001",new android.os.Bundle());
        settings.putBundle("0",device); settings.putBundle("1",user1); settings.putBundle("2",user2);
        OmronRunner.applyProfile(settings);
        assertEquals("19000101000000",settings.getBundle("1").getBundle("1005").getString("data"));
        assertEquals("other fields untouched","1",settings.getBundle("1").getBundle("1026").getString("data"));
        assertFalse("no birthday invented for a user without one",settings.getBundle("2").containsKey("1005"));
        assertFalse("device-wide block untouched",settings.getBundle("0").containsKey("1005"));
    }
    @Test public void networkIsBlockedForOmronCode() throws Exception {
        OmronRunner.blockNetwork();
        try { new java.net.URL("https://example.invalid/track").openConnection(); fail("HTTP connection was allowed"); }
        catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("network disabled")); }
    }
    @Test public void runsHem7156tFixtureToAnOutcome() throws Exception {
        OmronApk apk=OmronApk.assumePresent();
        Path transcript=Paths.get("build","omron-sandbox","runner-test","connect-read-history.txt");
        Outcome outcome=OmronRunner.run(Fixture.load("hem-7156t/connect-read-history"),apk,transcript);
        String text=new String(Files.readAllBytes(transcript),java.nio.charset.StandardCharsets.UTF_8);
        System.out.println(text);
        assertNotNull(outcome);
        assertTrue("Omron reached the unlock characteristic:\n"+text,text.contains("write unlock "));
        assertEquals(text,Outcome.Kind.MATCHES,outcome.kind);
    }
}
