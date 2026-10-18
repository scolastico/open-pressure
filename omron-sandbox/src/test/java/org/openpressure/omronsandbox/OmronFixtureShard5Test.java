package org.openpressure.omronsandbox;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.util.List;

/** Shard 5 of the Omron fixture replay; see {@link OmronFixtureShard}. */
@RunWith(Parameterized.class)
public class OmronFixtureShard5Test extends OmronFixtureShard {
    public OmronFixtureShard5Test(String name) { super(name); }
    @Parameterized.Parameters(name="{0}") public static List<Object[]> fixtures() { return fixtures(5); }
}
