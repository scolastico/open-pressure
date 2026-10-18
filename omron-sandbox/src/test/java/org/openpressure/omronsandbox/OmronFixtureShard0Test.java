package org.openpressure.omronsandbox;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.util.List;

/** Shard 0 of the Omron fixture replay; see {@link OmronFixtureShard}. */
@RunWith(Parameterized.class)
public class OmronFixtureShard0Test extends OmronFixtureShard {
    public OmronFixtureShard0Test(String name) { super(name); }
    @Parameterized.Parameters(name="{0}") public static List<Object[]> fixtures() { return fixtures(0); }
}
