package dev.stannismod.stellurgy.test.integration;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.util.XMLPlanetLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * XML planet definitions — deep parsing path that needs
 * {@link MinecraftBootstrap#ensure()}.
 *
 * <p>The simple {@code loadFile}/{@code isValid} sanity checks live in
 * {@code unit/XMLPlanetLoaderTest}. This class drives {@code readAllPlanets()}
 * through actual XML fixtures and verifies every parsed field (DIMID
 * resolution, atmosphere/gravity clamping, weather field preservation,
 * defaults, parent/child planet hierarchy).</p>
 */
public class XMLPlanetLoaderTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static String galaxy(String stars) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<galaxy>\n" + stars + "</galaxy>\n";
    }

    private static String star(String name, String body) {
        return "<star name=\"" + name + "\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\""
                + " isBlackHole=\"false\" diskAngle=\"70\""
                + " numPlanets=\"1\" numGasGiants=\"0\">\n"
                + body
                + "</star>\n";
    }

    // ---- Star/planet discovery -----------------------------------------------

    // ---- Weather fields ------------------------------------------------------

    // ---- Clamping ------------------------------------------------------------

    // ---- Write -> read full round-trip ---------------------------------------

    /**
     * Minimal IGalaxy fixture wrapping a single star. Only {@link #getStars()}
     * is consumed by {@link XMLPlanetLoader#writeXML}; all other methods throw
     * so that if the write path expands its API usage we fail loudly.
     */
    private static final class SingleStarGalaxyFixture
            implements dev.stannismod.stellurgy.api.dimension.solar.IGalaxy {
        private final dev.stannismod.stellurgy.api.dimension.solar.StellarBody star;
        SingleStarGalaxyFixture(dev.stannismod.stellurgy.api.dimension.solar.StellarBody s) {
            this.star = s;
        }

        @Override
        public java.util.Collection<dev.stannismod.stellurgy.api.dimension.solar.StellarBody>
        getStars() {
            return java.util.Collections.singletonList(star);
        }
        @Override public Integer[] getRegisteredDimensions() { throw new UnsupportedOperationException(); }
        @Override public dev.stannismod.stellurgy.api.satellite.SatelliteBase getSatellite(long satId) { throw new UnsupportedOperationException(); }
        @Override public boolean canTravelTo(int dimId) { throw new UnsupportedOperationException(); }
        @Override public dev.stannismod.stellurgy.api.dimension.IDimensionProperties getDimensionProperties(int dimId) { throw new UnsupportedOperationException(); }
        @Override public dev.stannismod.stellurgy.api.dimension.solar.StellarBody getStar(int id) { throw new UnsupportedOperationException(); }
        @Override public boolean isDimensionCreated(int dimId) { throw new UnsupportedOperationException(); }
        @Override public boolean areDimensionsInSamePlanetMoonSystem(int a, int b) { throw new UnsupportedOperationException(); }
    }

    // ---- laser drill ores: tolerant ore-name resolution ----------------------

    // ---- fault tolerance: skip bad planet, crash loudly on broken file -------

    /**
     * Issue #77 broader fix (C) — a completely unparseable planetDefs file is a
     * genuinely fatal/structural error. It must throw so that Forge produces a
     * normal crash report at server start, rather than the old silent
     * {@code FMLCommonHandler.exitJava} that closed the window with no report.
     * Catching a {@link RuntimeException} here (instead of the test JVM dying)
     * is the testable proxy for "crashes with a report, doesn't exit silently".
     */
    @Test
    public void completelyMalformedXmlThrowsForCrashReportInsteadOfSilentExit() throws Exception {
        File garbage = tempFolder.newFile("garbage-planetDefs.xml");
        Files.write(garbage.toPath(),
                "this is not xml <<< &&& >>>".getBytes(StandardCharsets.UTF_8));

        XMLPlanetLoader loader = new XMLPlanetLoader();
        try {
            loader.loadPlanetsOrThrow(garbage, new dev.stannismod.stellurgy.dimension.DimensionManager(
                    new dev.stannismod.stellurgy.api.StellurgyConfiguration().minDimension));
            fail("unparseable planetDefs XML must throw so Forge generates a crash "
                    + "report — it must not be swallowed or trigger a silent exitJava");
        } catch (RuntimeException expected) {
            assertNotNull("fatal load failure must carry a diagnostic message",
                    expected.getMessage());
            assertTrue("the message should point at the planetDefs XML file: "
                            + expected.getMessage(),
                    expected.getMessage().contains("planetDefs XML"));
        }
    }

    // ---- oregen persistence (issue #73) --------------------------------------

    // ---- terrainSource -------------------------------------------------------

    // ---- the procedural galaxy ----------------------------------------------

    /**
     * red-witnessed: with {@code DimensionPropertyCoupling#galaxyGenConfig} at
     * {@code GalaxyGenConfig.defaults()} replaced by {@code GalaxyGenConfig.nonProcedural()}, the first
     * verdict fails with "no <galaxyGen> is the shipped procedural galaxy, not an empty one"; replaced by
     * the defaults with one more reserved galaxy, the second fails with "and the shipped configuration,
     * knob for knob expected:<[54856f457186f8ee]> but was:<[d2ed657bccc48b66]>", 2026-10-05.
     */
    @Test
    public void aPackThatStatesNoGalaxyGenIsGivenTheShippedConfiguration() throws Exception {
        XMLPlanetLoader.DimensionPropertyCoupling read =
                load("no-galaxygen.xml", galaxy(star("Sol", "")));

        assertTrue("no <galaxyGen> is the shipped procedural galaxy, not an empty one",
                read.galaxyGenConfig.procedural);
        assertEquals("and the shipped configuration, knob for knob",
                GalaxyGenConfig.defaults().fingerprint(), read.galaxyGenConfig.fingerprint());
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#readGalaxyGen}'s branch at
     * {@code if ("false".equalsIgnoreCase(value))} removed, this fails with "procedural="false" must be
     * read as a galaxy with nothing between its anchors", 2026-10-05.
     */
    @Test
    public void proceduralFalseIsAGalaxyOfAuthoredAnchorsOnly() throws Exception {
        XMLPlanetLoader.DimensionPropertyCoupling read = load("opt-out.xml",
                galaxy("<galaxyGen procedural=\"false\"/>\n" + star("Sol", "")));

        assertFalse("procedural=\"false\" must be read as a galaxy with nothing between its anchors",
                read.galaxyGenConfig.procedural);
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#nonProcedural}'s {@code if (!knobs.isEmpty())} made
     * never true, this fails with "a density beside procedural="false" tunes a population the pack asked
     * not to have — it must be refused, not ignored"; with its message's {@code "; remove " + knobs}
     * dropped, with "the refusal must name the knob the pack must remove", 2026-10-05.
     */
    @Test
    public void proceduralFalseBesideAKnobIsRefusedAndTheKnobNamed() throws Exception {
        try {
            load("opt-out-with-density.xml",
                    galaxy("<galaxyGen procedural=\"false\" density=\"0.4\"/>\n" + star("Sol", "")));
            fail("a density beside procedural=\"false\" tunes a population the pack asked not to have"
                    + " — it must be refused, not ignored");
        } catch (RuntimeException refused) {
            assertTrue("the refusal must name the knob the pack must remove: " + refused.getMessage(),
                    String.valueOf(refused.getMessage()).contains("density"));
        }
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#readGalaxyGen}'s {@code if (!"true".equalsIgnoreCase(value))}
     * made never true, this fails with "procedural="no" is neither answer — reading it as either would be
     * a guess"; with that refusal's message not naming the attribute, with "the refusal must name the
     * attribute", 2026-10-05.
     */
    @Test
    public void aProceduralValueThatIsNotTrueOrFalseIsRefused() throws Exception {
        try {
            load("opt-out-misspelt.xml", galaxy("<galaxyGen procedural=\"no\"/>\n" + star("Sol", "")));
            fail("procedural=\"no\" is neither answer — reading it as either would be a guess");
        } catch (RuntimeException refused) {
            assertTrue("the refusal must name the attribute: " + refused.getMessage(),
                    String.valueOf(refused.getMessage()).contains("procedural"));
        }
    }

    private XMLPlanetLoader.DimensionPropertyCoupling load(String name, String xml) throws Exception {
        File file = tempFolder.newFile(name);
        Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        return new XMLPlanetLoader().loadPlanetsOrThrow(file,
                new dev.stannismod.stellurgy.dimension.DimensionManager(
                        new dev.stannismod.stellurgy.api.StellurgyConfiguration().minDimension));
    }

    // ---- helpers -------------------------------------------------------------

}
