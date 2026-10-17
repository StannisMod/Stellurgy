package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.universe.*;
import dev.stannismod.stellurgy.test.contract.RedWitness;
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

    @RedWitness(verdict = "procedural", member = "UniverseSchemaV0#generator", at = "return config.procedural",
            replacedBy = "return !config.procedural",
            fails = "a pack that states no <galaxyGen> must get the procedural galaxy", on = "2026-10-17")
    @RedWitness(verdict = "the shipped configuration", member = "UniverseSchemaV0#generator",
            at = "new ClusteredGalaxyGenerator(reports, config,",
            replacedBy = "new ClusteredGalaxyGenerator(reports, new GalaxyGenConfig(config.minSpacing, config.density / 2,"
                    + " config.galaxySpacing, config.galaxyDensity, config.starTypes, config.galaxyTypes,"
                    + " config.reservedGalaxies),",
            fails = "and built from the shipped configuration", on = "2026-10-17")
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

    @RedWitness(member = "UniverseSchemaV0#generator", at = "return config.procedural", replacedBy = "return !config.procedural",
            fails = "procedural=\"false\" must leave nothing between the authored anchors", on = "2026-10-17")
    @Test
    public void aPackThatAsksForNoProceduralPopulationIsGivenTheVoid() {
        IGalaxyGenerator generator = generatorFor(GalaxyGenConfig.nonProcedural());

        assertTrue("procedural=\"false\" must leave nothing between the authored anchors, got "
                        + generator.getClass().getSimpleName(),
                generator instanceof EmptyGalaxyGenerator);
    }
}
