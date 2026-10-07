package dev.stannismod.stellurgy.test.integration;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.TestUniverse;
import dev.stannismod.stellurgy.universe.BodyProfile;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.PlanetarySystem;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import dev.stannismod.stellurgy.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The joint between the generator, which states a procedural moon's size for the sky, and the
 * registry's realization half, which derives the world a descent materializes for it.
 *
 * <p>Wired as production wires them: a stock {@link ClusteredGalaxyGenerator} attached to a registry
 * this test builds and owns ({@link TestUniverse}), the moon taken from the system's body list, and the
 * realization's key read through the registry's own lookups — {@code anchorForCell},
 * {@code variantOf}, {@code starAt} — into {@code UniverseRegistry#derivedProfileOf}, the derivation
 * {@code PlanetRealizer} materializes. What it does not see is the rest of a realization: minting the
 * dimension and writing the profile into it.</p>
 */
public class TheMoonInTheSkyIsTheMoonLandedOnTest {

    /** The test's own world seed; any seed derives moons, and this one is fixed so a red reproduces. */
    private static final long SEED = 0x5EED_0697L;

    /** Moons the sweep must compare before its verdict means anything — the test's own sample bar. */
    private static final int MIN_MOONS = 20;

    /**
     * A body's orbit is measured against the configuration, whose class initializer touches vanilla's
     * block registry; without the vanilla bootstrap that throws and poisons the class for every later
     * test in this JVM.
     */
    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * A procedural moon's size in the sky is the size of the world realized for it.
     *
     * <p>Fails if {@code ClusteredGalaxyGenerator#addMoons} stops deriving a moon's bulk under the key
     * a realization derives it with — the moon's own cell and its variant in that cell (it drew from
     * the PARENT's cell and the moon's number, so the moon in the sky and the moon landed on were two
     * different draws).</p>
     *
     * <p>Compared exactly: the sky's number and the world's are one derivation or they are two. The
     * sample must hold moons named in a zone, the case the two keys differed in.</p>
     * <p>red-witnessed: with {@code ClusteredGalaxyGenerator#addMoons} at {@code variantInCell(bodies, moonCell), star, true, parentOrbit, reports);} given the parent's cell and variant 1 instead, fails: "the moon at 6385406_1568216_-2549800.-1_0_0 must be as massive on the ground as in the sky expected:<0.00869550573553636> but was:<0.0037079045198368577>" (2026-10-07).</p>
     */
    @Test
    public void aMoonsSizeInTheSkyIsTheSizeOfTheWorldRealizedForIt() {
        TestUniverse universe = new TestUniverse();
        ClusteredGalaxyGenerator generator =
                new ClusteredGalaxyGenerator(new ReportOnce(), GalaxyGenConfig.defaults());
        universe.attachGenerator(generator);
        UniverseRegistry registry = universe.newRegistry();
        registry.bindWorldSeed(SEED);

        long reach = 2L * generator.minSpacingCells();
        Map<GalacticCoord, PlanetarySystem> systems = generator.systemsInRegion(SEED,
                GalacticCoord.ofSectorLocal(-reach, -reach, -reach, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(reach, reach, reach, 0L, 0L, 0L));

        int compared = 0;
        int zoned = 0;
        for (Map.Entry<GalacticCoord, PlanetarySystem> system : systems.entrySet()) {
            if (!system.getValue().star().isPresent()) {
                continue; // a starless system has nothing a descent can be lit by; it is never realized
            }
            for (SystemBody moon : registry.systemBodiesAt(system.getKey())) {
                if (moon.kind() != SystemBodyKind.MOON) {
                    continue;
                }
                Optional<GalacticCoord> anchor = registry.anchorForCell(moon.name());
                OptionalInt variant = registry.variantOf(moon);
                Optional<StellarBody> star = registry.starAt(moon.name());
                assertTrue("the moon at " + moon.name().cellKey() + " must resolve to a system, a place in "
                                + "its cell and a star, or no descent can realize it",
                        anchor.isPresent() && variant.isPresent() && star.isPresent());
                BodyProfile landed = registry.derivedProfileOf(anchor.get(), moon, variant.getAsInt(),
                        star.get());
                assertEquals("the moon at " + moon.name().cellKey() + " must be as massive on the ground "
                        + "as in the sky", moon.massEarths(), landed.massEarths(), 0d);
                assertEquals("the moon at " + moon.name().cellKey() + " must be as large on the ground as "
                        + "in the sky", moon.radiusEarths(), landed.radiusEarths(), 0d);
                compared++;
                if (moon.name().zone() != null) {
                    zoned++;
                }
            }
        }
        ArrangementFailure.requireArranged("the sweep must compare at least " + MIN_MOONS + " moons, saw "
                + compared + " in " + systems.size() + " systems", compared >= MIN_MOONS);
        ArrangementFailure.requireArranged("the sweep must hold moons named in a zone, or it never reached "
                + "the case the two derivations disagreed in", zoned > 0);
    }
}
