package dev.stannismod.stellurgy.test.server;

import org.junit.Test;
import dev.stannismod.stellurgy.test.Reply;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.exec;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.planetExists;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.planetIntField;

/**
 * {@code /ar planet generate | delete | reset} lifecycle.
 *
 * <p>Pins the registry-mutation contract: generate produces a new
 * {@code DimensionProperties} entry; delete removes it; reset restores
 * a dim's properties to its baseline. Each test rolls back any
 * registry changes it makes so the shared harness is left as found.</p>
 *
 * <p><b>Args note</b>: the three randomness factors must be positive —
 * production at {@code DimensionManager.java:281} calls
 * {@code random.nextInt(atmosphereFactor)} which throws
 * {@code IllegalArgumentException("bound must be positive")} on a
 * zero factor. Tests pass {@code 10 10 10}.</p>
 */
public class WorldCommandPlanetLifecycleContractTest extends AbstractSharedServerTest {

    /**
     * The Stellurgy dimensions in the registry right now.
     *
     * <p>Every test in this class asks about the REGISTRY — "adds exactly one entry", "removes an
     * entry", "names the new dimension from the arg" — so {@code ar planet list} was never the
     * subject here, only a place the ids could be read off. It is asked of {@code stellurgytest dim list}
     * now; both enumerate {@code DimensionManager.getInstance().getRegisteredDimensions()}
     * ({@code PlanetListCommand:28}).</p>
     */
    private static Set<Integer> dimIds() throws Exception {
        Set<Integer> ids = new HashSet<>();
        for (int dim : Reply.of("stellurgytest dim list", exec("stellurgytest dim list")).intArray("stellurgyDimensions")) {
            ids.add(dim);
        }
        return ids;
    }

    @Test
    public void planetGenerateAddsExactlyOneEntryToRegistry() throws Exception {
        Set<Integer> before = dimIds();
        exec("ar planet generate 0 GenTestA");
        Set<Integer> after = dimIds();
        try {
            after.removeAll(before);
            assertEquals("planet generate must add exactly one dim — diff was " + after,
                    1, after.size());
        } finally {
            for (Integer id : after) exec("ar planet delete " + id);
        }
    }

    /**
     * A moon made by {@code planet generate <parent> moon <name>} orbits its PARENT — above the
     * parent's surface and inside the parent's sphere of influence, the region in which a craft is
     * described against the parent at all. A moon outside that sphere is not a moon of anything: no
     * frame would ever carry a craft to it.
     *
     * <p>The sphere is the game's own law ({@code ReferenceFrames.soiRadius}) over the parent's orbit
     * and mass and its star's mass, all read from the running server. The parent is a planet this
     * method generates itself, so its bulk is the derivation's and not whatever another method of this
     * group left on a shared world; both are printed.</p>
     *
     * <p>red-witnessed: 2026-09-30, on the code as it stood before the fix — {@code PlanetGenerateCommand#execute}
     * at {@code orbit = dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator.moonOrbitOf(seed, anchor,}
     * written as the star-level {@code derivation.orbitalDistanceOf(…)} for a moon too: "a generated moon
     * at 2.7574061E7 units must orbit above its parent's surface (145.4505985111664) and inside the
     * parent's sphere of influence (356579.08867974044)".</p>
     */
    @Test
    public void aGeneratedMoonOrbitsInsideItsParentsSphereOfInfluence() throws Exception {
        Set<Integer> before = dimIds();
        exec("ar planet generate 0 GenTestParent");
        Set<Integer> made = dimIds();
        made.removeAll(before);
        try {
            dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                    "planet generate must add exactly one parent planet — diff was " + made, made.size() == 1);
            int parentDim = made.iterator().next();
            Set<Integer> beforeMoon = dimIds();
            exec("ar planet generate " + parentDim + " moon GenTestMoon");
            Set<Integer> moonDiff = dimIds();
            moonDiff.removeAll(beforeMoon);
            made.addAll(moonDiff);
            dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                    "planet generate … moon must add exactly one dim — diff was " + moonDiff, moonDiff.size() == 1);
            int moonDim = moonDiff.iterator().next();

            Reply parent = Reply.of(exec("stellurgytest planet info " + parentDim));
            Reply moon = Reply.of(exec("stellurgytest planet info " + moonDim));
            Reply star = Reply.of(exec("stellurgytest star get " + parent.integer("starId")));
            dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                    "the generated body must be a moon of the generated planet: " + moon,
                    moon.integer("parent") == parentDim);

            double soi = dev.stannismod.stellurgy.space.ReferenceFrames.soiRadius(
                    Double.parseDouble(parent.text("orbitalDistance")), Double.parseDouble(parent.text("mass")),
                    Double.parseDouble(star.text("massEarths")));
            double surface = Double.parseDouble(parent.text("radius"))
                    * dev.stannismod.stellurgy.util.AstronomicalBodyHelper.EARTH_RADIUS_BLOCKS
                    / dev.stannismod.stellurgy.util.AstronomicalBodyHelper.BLOCKS_PER_DISTANCE_UNIT;
            dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                    "the parent must have a sphere of influence wider than itself (surface " + surface
                            + ", sphere " + soi + "): " + parent + " / " + star, soi > surface);

            double orbit = Double.parseDouble(moon.text("orbitalDistance"));
            assertTrue("a generated moon at " + orbit + " units must orbit above its parent's surface ("
                    + surface + ") and inside the parent's sphere of influence (" + soi + "): " + moon
                    + " / parent " + parent, orbit > surface && orbit < soi);
        } finally {
            for (Integer id : made) exec("ar planet delete " + id);
        }
    }

    @Test
    public void planetGenerateNamesNewDimensionFromArg() throws Exception {
        Set<Integer> before = dimIds();
        exec("ar planet generate 0 GenTestNamed");
        Set<Integer> diff = dimIds();
        diff.removeAll(before);
        try {
            assertEquals(1, diff.size());
            String list = exec("ar planet list");
            assertTrue("list must include the supplied name — got: " + list,
                    list.contains("GenTestNamed"));
        } finally {
            for (Integer id : diff) exec("ar planet delete " + id);
        }
    }

    @Test
    public void planetDeleteRemovesEntryFromRegistry() throws Exception {
        Set<Integer> before = dimIds();
        exec("ar planet generate 0 GenTestDel");
        Set<Integer> diff = dimIds();
        diff.removeAll(before);
        assertEquals(1, diff.size());
        int newId = diff.iterator().next();
        assertTrue("precondition: planetExists must be true after generate",
                planetExists(newId));

        exec("ar planet delete " + newId);

        assertFalse("planetExists must be false after delete",
                planetExists(newId));
        assertFalse("planet list must no longer include the dim",
                dimIds().contains(newId));
    }

    /** {@code planet reset <dimId>} calls
     *  {@code DimensionProperties.resetProperties} which on the overworld
     *  baseline restores {@code atmosphereDensity = 100} (set by
     *  {@code DimensionManager} ctor line 84). Mutate to a non-default
     *  value, reset, observe baseline restored. */
    @Test
    public void planetResetRestoresAtmosphereDensityBaselineForOverworld() throws Exception {
        int original = planetIntField(0, "atmosphereDensity");
        try {
            exec("ar planet set 0 atmosphereDensity 37");
            assertEquals(37, planetIntField(0, "atmosphereDensity"));
            exec("ar planet reset 0");
            assertEquals("after reset the field must equal the Stellurgy-init baseline",
                    100, planetIntField(0, "atmosphereDensity"));
        } finally {
            // Restore to whatever the harness had before — defends against
            // a future test ordering that depends on the pre-test value.
            exec("ar planet set 0 atmosphereDensity " + original);
        }
    }
}
