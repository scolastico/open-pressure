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
@Config(sdk=35,shadows={SandboxBluetoothDevice.class,ShadowOmsecNatives.class},
    instrumentedPackages={"jp.co.omron.healthcare.communicationlibrary","com.omronhealthcare.connectivitylibrary"})
@LooperMode(LooperMode.Mode.PAUSED)
public class OmronRunnerTest {
    private static final String CATALOG="[{\"category\":0,\"deviceGroupIncludedGroupIDKey\":\"1146\",\"identifier\":\"HEM-7156T_AAP\",\"name\":\"HEM-7156T-AAP\"},"+
        "{\"category\":0,\"deviceGroupIncludedGroupIDKey\":\"1402\",\"identifier\":\"HEM-7156T_AP\",\"name\":\"HEM-7156T\"},"+
        "{\"category\":0,\"deviceGroupIncludedGroupIDKey\":\"600\",\"secondaryDeviceGroupIncludedGroupIDKey\":\"601\",\"identifier\":\"HEM-7361T_ESL\",\"name\":\"X7\"}]";

    @Test public void catalogPicksAPForBaseName() {
        OmronCatalog catalog=new OmronCatalog(CATALOG);
        assertEquals("device_category/0x0000/HEM-7156T_AP",catalog.folderFor("BLEsmart_0000057A0011223344").folder());
        assertEquals("HEM-7156T_AAP",catalog.folderFor("BLESmart_0000047a").identifier);
        assertEquals("secondary group ID matches too","HEM-7361T_ESL",catalog.folderFor("BLEsmart_00000259").identifier);
    }
    @Test public void unknownModelIdIsUnsupported() {
        OmronCatalog catalog=new OmronCatalog(CATALOG);
        assertNull(catalog.folderFor("BLEsmart_00000999"));
        assertNull("other categories never match",catalog.folderFor("BLEsmart_0001057A"));
        assertNull("names without IDs carry no model",catalog.folderFor("Synthetic monitor"));
        Fixture f=Fixture.parse("x/y","name x/y\nadvertise name=BLEsmart_00000999 services=ecbe3980-c9a2-11e1-b1bd-0002a5d5c51b\n");
        Outcome outcome=OmronRunner.select(f,catalog);
        assertEquals(Outcome.Kind.UNSUPPORTED,outcome.kind);
        assertTrue(outcome.detail,outcome.detail.contains("category 0x0000 model 0x0999"));
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
