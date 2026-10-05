package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.EmptyGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.IGalaxyGenerator;
import dev.stannismod.stellurgy.universe.PlanetTypes;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.UniverseSchemaV0;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which generator the world model hands a pack's galaxy configuration: the pack states whether space
 * between its anchors is populated, and the version picks the implementation that answers it.
 */
public class UniverseSchemaGeneratorChoiceTest {

    private static IGalaxyGenerator generatorFor(GalaxyGenConfig config) {
        return new UniverseSchemaV0().generator(config, PlanetTypes.stock(), new ReportOnce());
    }

    /**
     * red-witnessed: with {@code UniverseSchemaV0#generator} at {@code return config.procedural}
     * negated, the first verdict fails with "a pack that states no <galaxyGen> must get the procedural
     * galaxy, got EmptyGalaxyGenerator"; with its {@code new ClusteredGalaxyGenerator(reports, config, …)}
     * built from that config at half its density, the second fails with "and built from the shipped
     * configuration expected:<[54856f457186f8ee]> but was:<[24e7d5054e6fa26d]>", 2026-10-05.
     */
    @Test
    public void aPackThatStatesNoGalaxyIsGivenTheShippedProceduralOne() {
        IGalaxyGenerator generator = generatorFor(GalaxyGenConfig.defaults());

        assertTrue("a pack that states no <galaxyGen> must get the procedural galaxy, got "
                        + generator.getClass().getSimpleName(),
                generator instanceof ClusteredGalaxyGenerator);
        assertEquals("and built from the shipped configuration",
                GalaxyGenConfig.defaults().fingerprint(),
                ((ClusteredGalaxyGenerator) generator).config().fingerprint());
    }

    /**
     * red-witnessed: with {@code UniverseSchemaV0#generator} at {@code return config.procedural}
     * negated, this fails with "procedural="false" must leave nothing between the authored anchors, got
     * ClusteredGalaxyGenerator", 2026-10-05.
     */
    @Test
    public void aPackThatAsksForNoProceduralPopulationIsGivenTheVoid() {
        IGalaxyGenerator generator = generatorFor(GalaxyGenConfig.nonProcedural());

        assertTrue("procedural=\"false\" must leave nothing between the authored anchors, got "
                        + generator.getClass().getSimpleName(),
                generator instanceof EmptyGalaxyGenerator);
    }
}
