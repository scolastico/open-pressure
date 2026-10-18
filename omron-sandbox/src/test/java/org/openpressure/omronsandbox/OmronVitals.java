package org.openpressure.omronsandbox;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import org.openpressure.protocol.fixture.Fixture;

/** Maps the vital-data Bundles Omron delivers to fixture readings. */
final class OmronVitals {
    private OmronVitals() {}
    static List<Fixture.Reading> readings(List<Bundle> vitals,List<String> log) {
        List<Fixture.Reading> result=new ArrayList<>();
        for(Bundle b:vitals) log.add("vital "+OmronRunner.bundle(b));
        return result;
    }
}
