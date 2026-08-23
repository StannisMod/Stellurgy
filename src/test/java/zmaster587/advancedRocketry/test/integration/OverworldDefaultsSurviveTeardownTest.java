package zmaster587.advancedRocketry.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The overworld's catalogue entry must survive a world teardown — it is Earth's, and Earth's is not
 * the generic default of a planet.
 *
 * <p>{@code DimensionManager.overworldProperties} is a JVM-lifetime static seeded once, and
 * {@code onServerStopped()} — fired on every world exit — calls {@code resetProperties()} on it. That
 * method restores the defaults of a PLANET: gravity 1, 100 kelvin, no mass, no radius. So the first
 * world opened in a process had an Earth and every world opened after a return to the title screen
 * had a 100-kelvin body of no size. Single-player only, where the client and the integrated server
 * share one JVM; the dedicated-server harness forks a fresh JVM per boot, which is why this needed a
 * hand report rather than a harness run to surface.</p>
 *
 * <p><b>What a missing radius costs</b>, and why this is not bookkeeping: a body of no radius reaches
 * the sky renderer with {@code radiusBlocks = 0} and is drawn at the marker size at every range, and
 * its descent shell falls back to the flat 512-block proximity sphere meant for belts — 1/50 of this
 * world. Reported 2026-08-23 from a live flight as "I can see the Moon but not the Earth". It also
 * outlives the process: the planet file writes bulk only for a body that HAS it, so a world saved in
 * that state records an Earth with no radius permanently, which is what the second scenario is
 * about.</p>
 */
public class OverworldDefaultsSurviveTeardownTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @After
    public void restoreBootstrapState() {
        // onServerStopped() clears the star registry; MinecraftBootstrap.ensure() short-circuits
        // after its first call, so re-register Sol for the benefit of any later test class sharing
        // this JVM.
        if (DimensionManager.getInstance().getStar(0) == null) {
            StellarBody sol = new StellarBody();
            sol.setId(0);
            sol.setName("Sol");
            sol.setTemperature(100);
            DimensionManager.getInstance().addStar(sol);
        }
        DimensionManager.hasReachedMoon = false;
        DimensionManager.hasReachedWarp = false;
    }

    @Test
    public void theOverworldKeepsEarthsBulkAcrossAWorldTeardown() {
        DimensionManager.getInstance().onServerStopped();

        DimensionProperties earth = DimensionManager.overworldProperties;
        assertTrue("the overworld must still state a mass and a radius after a world teardown — a"
                        + " body with none is drawn at the marker size at every range and carries the"
                        + " flat proximity shell instead of an atmosphere. mass="
                        + earth.getMass() + " radius=" + earth.getRadius(),
                earth.hasBulkProperties());
        assertEquals("the overworld is the body the mass unit is DEFINED by", 1d, earth.getMass(), 0d);
        assertEquals("the overworld is the body the radius unit is DEFINED by",
                1d, earth.getRadius(), 0d);
    }

    /**
     * The rest of Earth's entry goes the same way as its bulk, and for the same reason — the reset
     * puts back a planet's generic defaults, not this planet's. The temperature is the one a reader
     * can check by eye: 286 K is Earth's, 100 K is the generic.
     */
    @Test
    public void theOverworldKeepsItsNameAndTemperatureAcrossAWorldTeardown() {
        DimensionManager.getInstance().onServerStopped();

        DimensionProperties earth = DimensionManager.overworldProperties;
        assertEquals("the overworld must still be named after a world teardown",
                "Earth", earth.getName());
        assertEquals("the overworld must still carry its own average temperature (286 K, 13 °C)"
                        + " after a world teardown, not a planet's generic 100 K",
                286, earth.getAverageTemp());
    }
}
