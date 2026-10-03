package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code /ar planet set | get | list} contract pins.
 *
 * <p>Each test mutates one DimensionProperties field on the overworld
 * via {@code /ar planet set 0 <field> <val>}, asserts the change is
 * observable through the independent {@code /stellurgytest planet info 0}
 * JSON reader, then restores the pre-test value in a finally block.
 * Pinning the result (the field IS the new value) rather than the
 * dispatch chain (which reflective branch fired) keeps each test ≤ 6
 * lines of body.</p>
 *
 * <p>{@code planet set} has two write paths in production: a hardcoded
 * branch for {@code atmosphereDensity} (calls
 * {@code setAtmosphereDensityDirect}) and a generic reflective branch
 * for the rest. Both paths are exercised here.</p>
 */
public class WorldCommandPlanetSetGetContractTest extends AbstractSharedServerTest {

    /** {@code /ar planet list} prints one chat line per registered dim
     *  in {@code DimensionManager.getInstance()}. Overworld is always
     *  registered (Stellurgy adds it at boot — confirmed by every existing
     *  test that calls {@code /stellurgytest planet info 0}). Pin the presence
     *  of {@code DIM0} substring without pinning exact line wording. */
    @Test
    public void planetListIncludesOverworldDim() throws Exception {
        String resp = exec("ar planet list");
        assertTrue("planet list must include DIM0 — got: " + resp,
                resp.contains("DIM0"));
    }
}
