package dev.stannismod.stellurgy.test.unit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.PlanetaryTravelHelper;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PlanetaryTravelHelper static surface.
 *
 * <p>Four public static methods on
 * {@link PlanetaryTravelHelper} govern whether a rocket needs a
 * transbody-injection burn (extra fuel), and whether a space-elevator
 * link is allowed (geostationary geometry). Both are player-facing
 * contracts:</p>
 *
 * <ul>
 *   <li>{@code isTravelBetweenBodiesWithinPlanetarySystem} — used by
 *       {@code RocketLaunchEvent.fueling} to decide whether the
 *       transbody-injection burn fuel cost applies. Wrong answer means
 *       either "rocket can't reach destination" or "rocket has free
 *       fuel".</li>
 *   <li>{@code isTravelWithinGeostationaryOrbit} — used by
 *       {@code TileSpaceElevator.onLinkComplete} to refuse linking
 *       stations not in geostationary orbit. Wrong answer breaks
 *       elevator setup.</li>
 *   <li>{@code isTravelWithinOrbit} — trivial same-dim equality. Pinned
 *       because the +1 from any of these methods feeds the rocket-fuel
 *       calculation; flipping reflexivity silently breaks short hops.</li>
 *   <li>{@code getTransbodyInjectionBurn} — composes the above with
 *       config values. Pinned as a relational contract (intra-system
 *       burn is positive, cross-system burn falls back to warp-mult).</li>
 * </ul>
 *
 * <p>Test dims are registered at high ids (800-803) to avoid colliding
 * with any other test's dim registry. All dims are
 * {@code registerWithForge=false} so Forge's own DimensionManager
 * isn't touched.</p>
 */
public class PlanetaryTravelHelperTest {

    /** Standalone host planet. */
    private static final int PLANET_A = 800;
    /** Moon orbiting PLANET_A. */
    private static final int MOON_OF_A = 801;
    /** Second moon orbiting PLANET_A (used for moon&rarr;sibling-moon check). */
    private static final int MOON2_OF_A = 802;
    /** Unrelated planet (no parent/child link to A). */
    private static final int PLANET_C = 803;
    /** Orbital distance set on MOON_OF_A; the burn-multiplier divides
     *  by 100 so the input here drives a non-zero distance multiplier. */
    private static final int MOON_ORBITAL_DIST = 250;

    /** Test-only injection for transBodyInjection. The default in
     *  {@link StellurgyConfiguration} is 0, which makes the intra-system burn
     *  formula collapse to 0 and obscures the multiplier branch. We
     *  set this to a non-zero value in @BeforeClass and restore on
     *  @AfterClass so the contract is observable. */
    private static final int TEST_BASE_INJECTION = 100;
    private static int savedBaseInjection;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();

        DimensionManager dm = DimensionManager.getInstance();

        DimensionProperties planetA = new DimensionProperties(PLANET_A);
        planetA.setName("PlanetA");

        DimensionProperties moonOfA = new DimensionProperties(MOON_OF_A);
        moonOfA.setName("MoonOfA");
        moonOfA.setParentPlanet(planetA);
        moonOfA.orbitalDist = MOON_ORBITAL_DIST;

        DimensionProperties moon2OfA = new DimensionProperties(MOON2_OF_A);
        moon2OfA.setName("Moon2OfA");
        moon2OfA.setParentPlanet(planetA);
        moon2OfA.orbitalDist = 150;

        DimensionProperties planetC = new DimensionProperties(PLANET_C);
        planetC.setName("PlanetC");

        dm.registerDimNoUpdate(planetA, false);
        dm.registerDimNoUpdate(moonOfA, false);
        dm.registerDimNoUpdate(moon2OfA, false);
        dm.registerDimNoUpdate(planetC, false);

        // Stash + override transBodyInjection so the intra-system
        // burn formula has a non-zero base to multiply.
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        savedBaseInjection = cfg.transBodyInjection;
        cfg.transBodyInjection = TEST_BASE_INJECTION;
    }

    @AfterClass
    public static void cleanup() {
        // Restore config — leave the dim registry alone (high IDs
        // 800-803 are unique to this class; calling
        // DimensionManager.deleteDimension triggers PacketHandler
        // .sendToAll which crashes without a server-tier network
        // pipeline). The dims sit harmlessly until JVM exit.
        StellurgyConfiguration.getCurrentConfig().transBodyInjection = savedBaseInjection;
    }

    /** Construct a SpaceStationObject with {@code created=true} so
     *  {@link SpaceStationObject#getOrbitingPlanetId()} returns the
     *  configured planet instead of {@link
     *  dev.stannismod.stellurgy.api.Constants#INVALID_PLANET}.
     *  The flag is normally flipped by {@code onModuleUnpack} (heavy
     *  fixture) — reflection here is the lightest test-only path. */
    private static SpaceStationObject newStationOrbiting(int planetId,
                                                         float orbitalDistance) throws Exception {
        SpaceStationObject station = new SpaceStationObject();
        Field createdField = SpaceStationObject.class.getDeclaredField("created");
        createdField.setAccessible(true);
        createdField.setBoolean(station, true);
        station.setOrbitingBody(planetId);
        station.setOrbitalDistance(orbitalDistance);
        return station;
    }

    // ── isTravelBetweenBodiesWithinPlanetarySystem ───────────────────────

    @Test
    public void planetToOwnMoonIsWithinPlanetarySystem() {
        // Planet -> its moon: the destination is in the planet's
        // childPlanets set. Production line 30-35.
        assertTrue("planet -> own-moon must report intra-system travel",
                PlanetaryTravelHelper
                        .isTravelBetweenBodiesWithinPlanetarySystem(PLANET_A, MOON_OF_A));
    }

    @Test
    public void moonToParentPlanetIsWithnPlanetarySystem() {
        // Moon -> its parent planet: production line 21-22 checks
        // destination == parentPlanet.
        assertTrue("moon -> parent-planet must report intra-system travel",
                PlanetaryTravelHelper
                        .isTravelBetweenBodiesWithinPlanetarySystem(MOON_OF_A, PLANET_A));
    }

    @Test
    public void moonToSiblingMoonIsWithinPlanetarySystem() {
        // Two moons sharing a parent planet: production line 23-28
        // walks parent's childPlanets set, so sibling-moon travel is
        // intra-system.
        assertTrue("moon -> sibling-moon must report intra-system travel",
                PlanetaryTravelHelper
                        .isTravelBetweenBodiesWithinPlanetarySystem(MOON_OF_A, MOON2_OF_A));
    }

    @Test
    public void planetToUnrelatedPlanetIsNotWithinPlanetarySystem() {
        // Different parent stars / disconnected planets — the
        // unrelated planet is not in planetA's childPlanets and not
        // its parent. Cross-system travel.
        assertFalse("planet -> unrelated-planet must NOT report intra-system",
                PlanetaryTravelHelper
                        .isTravelBetweenBodiesWithinPlanetarySystem(PLANET_A, PLANET_C));
    }

    // ── isTravelWithinOrbit ──────────────────────────────────────────────

    @Test
    public void sameDimensionReportsWithinOrbit() {
        assertTrue("same dim id must report within-orbit (reflexive)",
                PlanetaryTravelHelper.isTravelWithinOrbit(PLANET_A, PLANET_A));
        assertFalse("different dim ids must NOT report within-orbit",
                PlanetaryTravelHelper.isTravelWithinOrbit(PLANET_A, MOON_OF_A));
    }

    // ── isTravelAnywhereInPlanetarySystem ────────────────────────────────

    @Test
    public void isTravelAnywhereInPlanetarySystemUnionsOrbitAndBodies() {
        // The method is a logical OR of isTravelWithinOrbit and
        // isTravelBetweenBodiesWithinPlanetarySystem. Pin both
        // branches contributing.
        assertTrue("same-dim must report anywhere-in-system",
                PlanetaryTravelHelper
                        .isTravelAnywhereInPlanetarySystem(PLANET_A, PLANET_A));
        assertTrue("planet -> own-moon must report anywhere-in-system",
                PlanetaryTravelHelper
                        .isTravelAnywhereInPlanetarySystem(PLANET_A, MOON_OF_A));
        assertFalse("cross-system must NOT report anywhere-in-system",
                PlanetaryTravelHelper
                        .isTravelAnywhereInPlanetarySystem(PLANET_A, PLANET_C));
    }

    // ── getTransbodyInjectionBurn ────────────────────────────────────────

    @Test
    public void transbodyInjectionBurnIsPositiveForIntraSystemTravel() {
        // If intra-system, the burn is baseInjectionHeight * sqrt(distanceMultiplier).
        // Pin "positive" without coupling to the exact integer —
        // any regression returning 0 or negative is caught.
        int burn = PlanetaryTravelHelper
                .getTransbodyInjectionBurn(PLANET_A, MOON_OF_A, false);
        assertTrue("intra-system burn must be > 0 (got " + burn + "); "
                        + "config.transBodyInjection="
                        + StellurgyConfiguration.getCurrentConfig().transBodyInjection
                        + " moon.orbitalDist=" + MOON_ORBITAL_DIST,
                burn > 0);
    }

    /**
     * A trip between a planet and its moon, in either direction, is lengthened by the moon's
     * distance read as it was read while Luna stood at 150: {@code moonViewUnits / 100}, so 1.5 for
     * Luna and in proportion for any other moon.
     *
     * <p>Acceptance, stated before the code: the moon here stands at {@code MOON_ORBITAL_DIST} = 250
     * units, so both directions read {@code 150 x 250 / 3 844 / 100} (about 0.0976), within 1e-12 of
     * double rounding — one verdict over both. Read raw, both read 2.5.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code PlanetaryTravelHelper#moonBurnMultiplier} at
     * {@code return AstronomicalBodyHelper.moonViewUnits(moon.getOrbitalDist()) / MOON_VIEW_UNITS_PER_BASE_BURN}
     * back on the raw distance ({@code moon.getOrbitalDist() / 100d}), this fails with "planet to
     * moon, then moon to planet: [2.5, 2.5]: arrays first differed at element [0];
     * expected:&lt;0.09755463059313214&gt; but was:&lt;2.5&gt;"; and with
     * {@code AstronomicalBodyHelper#moonViewUnits} at
     * {@code return MOON_VIEW_UNITS_AT_LUNA * (orbitalDistance / (double) MOON_REFERENCE_UNITS)}
     * replaced by {@code return MOON_VIEW_UNITS_AT_LUNA}, with "[1.5, 1.5] … but was:&lt;1.5&gt;".</p>
     */
    @Test
    public void aBurnBetweenAPlanetAndItsMoonReadsTheMoonAsLunaWasReadAt150() {
        // 150 and 100 are the contract, not production's constants read back: Luna's view distance
        // and the view units per base burn, as they stood before Luna's distance was corrected.
        double expected = 150d * MOON_ORBITAL_DIST
                / dev.stannismod.stellurgy.util.AstronomicalBodyHelper.MOON_REFERENCE_UNITS / 100d;
        double[] actual = {
                PlanetaryTravelHelper.getBodyDistanceMultiplier(PLANET_A, MOON_OF_A, false),
                PlanetaryTravelHelper.getBodyDistanceMultiplier(MOON_OF_A, PLANET_A, false)};
        org.junit.Assert.assertArrayEquals("planet to moon, then moon to planet: "
                + java.util.Arrays.toString(actual), new double[]{expected, expected}, actual, 1e-12);
    }

    @Test
    public void transbodyInjectionBurnFallsBackToWarpMultForCrossSystem() {
        // Production line 53 else-branch:
        //   warpTBIBurnMult * baseInjectionHeight.
        // The cross-system burn value depends only on config and not
        // on per-dim orbitalDist, so we pin the relational contract
        // "cross-system burn equals warpMult * baseInjection".
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        int expected = (int) cfg.warpTBIBurnMult * cfg.transBodyInjection;
        int actual = PlanetaryTravelHelper
                .getTransbodyInjectionBurn(PLANET_A, PLANET_C, false);
        assertEquals("cross-system burn must follow warpMult * baseInjection",
                expected, actual);
    }

    // ── isTravelWithinGeostationaryOrbit ─────────────────────────────────

    @Test
    public void stationAtOrAboveGeostationaryDistanceLinksToParent() throws Exception {
        // Production line 110: returns true iff station's orbiting
        // planet matches the queried planet AND orbital distance is
        // >= 177. The exact 177 is documented in the production
        // comment (between 36300 and 35500 km), making it part of
        // the player-visible contract for "can the tether be set up?".
        SpaceStationObject atBoundary = newStationOrbiting(PLANET_A, 177f);
        assertTrue("station at exactly 177 km must allow geostationary link",
                PlanetaryTravelHelper
                        .isTravelWithinGeostationaryOrbit(atBoundary, PLANET_A));

        SpaceStationObject highOrbit = newStationOrbiting(PLANET_A, 250f);
        assertTrue("station above 177 km must allow geostationary link",
                PlanetaryTravelHelper
                        .isTravelWithinGeostationaryOrbit(highOrbit, PLANET_A));
    }

    @Test
    public void stationBelowGeostationaryDistanceCannotLink() throws Exception {
        SpaceStationObject station = newStationOrbiting(PLANET_A, 50f);

        assertFalse("station at sub-geostationary distance must NOT allow link",
                PlanetaryTravelHelper
                        .isTravelWithinGeostationaryOrbit(station, PLANET_A));
    }

    @Test
    public void stationOrbitingDifferentBodyCannotLink() throws Exception {
        SpaceStationObject station = newStationOrbiting(PLANET_A, 200f);

        assertFalse("station orbiting A must NOT allow link to unrelated planet C",
                PlanetaryTravelHelper
                        .isTravelWithinGeostationaryOrbit(station, PLANET_C));
    }
}
