package org.openpressure.omronsandbox;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.openpressure.protocol.fixture.Fixture;
import org.openpressure.protocol.fixture.Outcome;
import org.junit.Assume;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/**
 * Runs one fixture (system property {@value #FIXTURE}) through Omron's code; started by {@link OmronFixtureShard}
 * in a fresh class loader because Omron keeps static singletons that Robolectric does not reset. Omron
 * disagreeing with an exploratory fixture is a finding. A fixture with {@code expect omron matches} is a reference
 * regression and fails after its transcript and result are saved. Exploration mode still reports all findings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows={SandboxBluetoothDevice.class,ShadowOmsecNatives.class,ShadowOmronClock.class},
    instrumentedPackages={"jp.co.omron.healthcare.communicationlibrary","com.omronhealthcare.connectivitylibrary"})
@LooperMode(LooperMode.Mode.PAUSED)
public class OmronFixtureRun {
    static final String FIXTURE="omron.sandbox.fixture";

    @Test public void omronRunsFixture() throws Exception {
        String name=System.getProperty(FIXTURE,"");
        Assume.assumeFalse("started by OmronFixtureShard",name.isEmpty());
        OmronApk apk=OmronApk.assumePresent();
        Path transcript=Paths.get("build","omron-sandbox","transcripts",apk.version.replaceAll("[^A-Za-z0-9._-]+","_").replaceAll("^_|_$",""),name+".txt");
        Fixture fixture=Fixture.load(name);
        Outcome outcome=OmronRunner.run(fixture,apk,transcript);
        Path result=Paths.get("build","omron-sandbox","results",name+".txt");
        Files.createDirectories(result.getParent());
        Files.write(result,("omron "+apk.version+": "+outcome.report()+"\n  transcript "+transcript.toAbsolutePath()+"\n").getBytes(StandardCharsets.UTF_8));
        requireExpectedOutcome(fixture,outcome);
    }

    static void requireExpectedOutcome(Fixture fixture,Outcome outcome) {
        assertNotNull(outcome);
        if(fixture.expectOmronMatch && !Boolean.getBoolean("omron.sandbox.explore"))
            assertEquals(fixture.name+": "+outcome.report(),Outcome.Kind.MATCHES,outcome.kind);
    }
}
