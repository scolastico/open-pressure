package org.openpressure.app.ble;

import android.app.Application;
import org.junit.*;
import org.junit.runner.RunWith;
import org.openpressure.app.data.PairingKeys;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.app.developer.DeveloperOptions.Experiment;
import org.openpressure.protocol.ProtocolProfile;
import org.openpressure.protocol.WriteAspectIndex.Aspect;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.*;
import static org.junit.Assert.*;

/**
 * The experimental encrypted X7 paths through the real service callbacks against a simulated encrypted cuff: a Pair
 * forced onto the encrypted route by the developer switch, and the switch being ignored everywhere else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceSecureX7Test {
    private X7TestPeer peer;
    private MonitorService monitor;

    @Before public void setup() throws Exception { peer=new X7TestPeer(); monitor=peer.monitor; }
    @After public void cleanup() { if(peer!=null) peer.close(); }

    @Test public void forcedPairRunsTheEncryptedHandshakeAndNeverRegisters() throws Exception {
        force();
        // Registration would be permitted here: the forced route must still never send it.
        DeveloperOptions.setAllowUntested(monitor,Aspect.REGISTRATION,true);
        peer.pair("HEM-7380T1-EOSL",X7TestPeer.plainAdvertisement());
        assertEquals(ProtocolProfile.X7_SECURE_WLP,monitor.profile);
        assertEquals("X7 authenticated for reads",monitor.status);
        assertTrue(monitor.ready); assertTrue(monitor.canHistory);
        assertTrue(peer.established);
        // SYS nonce transaction, fresh application pairing (70 01, 70 05, 70 06), then the authorization poll.
        List<String> system=peer.systemOpcodes();
        assertEquals(system.toString(),5,system.size());
        assertTrue(system.get(0).startsWith("11"));
        assertEquals(Arrays.asList("7001","7005","7006","2001"),system.subList(1,5));
        assertFalse(monitor.registrationReading); assertFalse(monitor.registrationPending);
        assertEquals("",monitor.registrationIdentity);
        assertEquals("no registration frame and no settings read",0,peer.addressedWrites().size());
        assertTrue(peer.wlpTranscript().isEmpty());
        verifyNoBondChange();
        byte[] saved=new PairingKeys(monitor).get(X7TestPeer.ADDRESS);
        assertArrayEquals("the derived application key is saved for Connect",peer.ltk,saved);
        String json=peer.report.toJson().replace(" ","").replace("\n","");
        assertTrue(json,json.contains("\"developer\":[\"force_x7_encrypted\"]"));
        assertTrue(json.contains("\"profile\":\"X7_SECURE_WLP\""));
        assertFalse(json.contains("\"aspect\":\"registration\""));
    }
    @Test public void withoutTheSwitchPairTakesThePlainPath() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        peer.pair("HEM-7380T1-EOSL",X7TestPeer.plainAdvertisement());
        assertEquals(ProtocolProfile.X7_WLP,monitor.profile);
        assertTrue("the plain EOSL registration flow",monitor.registrationPending);
        assertFalse(peer.systemOpcodes().stream().anyMatch(op -> op.startsWith("70")));
        assertTrue(peer.report.toJson().replace(" ","").replace("\n","").contains("\"developer\":[]"));
    }
    @Test public void lockedOptionsIgnoreAStaleSwitch() throws Exception {
        force();
        DeveloperOptions.setUnlocked(monitor,false);
        assertTrue(monitor.getSharedPreferences(DeveloperOptions.PREFERENCES,0).edit().putBoolean("experimental_force_x7_encrypted",true).commit());
        peer.pair("HEM-7380T1-EOSL",X7TestPeer.plainAdvertisement());
        assertEquals(ProtocolProfile.X7_WLP,monitor.profile);
    }
    @Test public void connectIgnoresTheSwitch() throws Exception {
        force();
        peer.connect("HEM-7380T1-EOSL",X7TestPeer.plainAdvertisement());
        assertEquals(ProtocolProfile.X7_WLP,monitor.profile);
        assertTrue(monitor.ready);
        assertFalse(peer.systemOpcodes().stream().anyMatch(op -> op.startsWith("70")));
    }
    @Test public void automaticImportsAndFetchIgnoreTheSwitch() throws Exception {
        force();
        peer.automatic("HEM-7380T1-EOSL",X7TestPeer.plainAdvertisement());
        assertEquals(ProtocolProfile.X7_WLP,monitor.profile);
        assertFalse(peer.systemOpcodes().stream().anyMatch(op -> op.startsWith("70")));
        assertEquals("the read-only history import starts with START","0800000000100018",peer.wlpTranscript().get(0));
        assertEquals(0,peer.addressedWrites().size());
    }
    @Test public void encryptedAdvertisementIsUnchanged() throws Exception {
        DeveloperOptions.setUnlocked(monitor,true);
        peer.pair("HEM-7380T1-EOSL",X7TestPeer.hex("0801780164000b00"));
        assertEquals(ProtocolProfile.X7_SECURE_WLP,monitor.profile);
        assertEquals("X7 authenticated for reads",monitor.status);
        assertTrue("no switch was in effect",peer.report.toJson().replace(" ","").replace("\n","").contains("\"developer\":[]"));
    }

    private void force() {
        DeveloperOptions.setUnlocked(monitor,true);
        DeveloperOptions.setAllowExperimental(monitor,Experiment.FORCE_X7_ENCRYPTED,true);
    }
    private void verifyNoBondChange() {
        org.mockito.Mockito.verify(peer.device,org.mockito.Mockito.never()).createBond();
    }
}
