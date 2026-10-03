package dev.stannismod.stellurgy.test.integration;

import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.gas.GasRegistry;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.XMLPlanetLoader;
import dev.stannismod.stellurgy.util.XMLPlanetLoader.DimensionPropertyCoupling;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A planet file may state a body's air outright — the gases, whole — or name another body of the same
 * file and take a COPY of its air. A name that leads nowhere, to two bodies, or round in a circle
 * refuses the load, and a stated composition beside a stated total refuses the body: authored air is
 * never silently replaced by a default.
 *
 * <p>The joint pinned is the loader's file reading against the planet's creation door, wired as
 * production wires them: a real file, the real loader, the properties it returns.</p>
 */
public class PlanetFileStatesItsAirTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private DimensionPropertyCoupling read(String planets) throws Exception {
        File file = folder.newFile();
        String xml = "<galaxy>\n  <star name=\"Sol\" temp=\"100\" size=\"1.0\" numPlanets=\"0\""
                + " numGasGiants=\"0\">\n" + planets + "  </star>\n</galaxy>\n";
        Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        XMLPlanetLoader loader = new XMLPlanetLoader();
        assertTrue("the fixture file must parse as XML", loader.loadFile(file));
        return loader.readAllPlanets();
    }

    /** A sized planet with whatever air element(s) the case gives it. */
    private static String planet(String name, String air) {
        return "    <planet name=\"" + name + "\">\n"
                + "      <mass>1.0</mass>\n      <radius>1.0</radius>\n"
                + air
                + "    </planet>\n";
    }

    private static String stated(long co2Ppm, long n2Ppm) {
        return "      <atmosphere>\n"
                + "        <gas name=\"carbondioxide\" ppm=\"" + co2Ppm + "\"/>\n"
                + "        <gas name=\"nitrogen\" ppm=\"" + n2Ppm + "\"/>\n"
                + "      </atmosphere>\n";
    }

    private static String copyOf(String exemplar) {
        return "      <atmosphere copyOf=\"" + exemplar + "\"/>\n";
    }

    private static DimensionProperties named(DimensionPropertyCoupling read, String name) {
        for (DimensionProperties body : read.dims) {
            if (name.equals(body.getName())) {
                return body;
            }
        }
        return null;
    }

    /**
     * red-witnessed: with {@code DimensionProperties#authorAtmosphere} at {@code air = authored;} made
     * {@code air = AirState.vacuum();}, this fails with "the carbon dioxide stated expected:&lt;965000000&gt;
     * but was:&lt;0&gt;" (2026-10-03).
     */
    @Test
    public void aStatedCompositionIsExactlyTheWorldsAir() throws Exception {
        DimensionProperties venusia = named(read(planet("Venusia", stated(965_000L, 35_000L))), "Venusia");
        assertNotNull("the planet must load", venusia);
        AirState air = venusia.getAir();
        assertEquals("the carbon dioxide stated", 965_000L * AirState.PER_PPM,
                air.partialPressure(GasRegistry.CARBON_DIOXIDE));
        assertEquals("the nitrogen stated", 35_000L * AirState.PER_PPM, air.partialPressure(GasRegistry.NITROGEN));
        assertEquals("nothing but what was stated", 2, air.composition().size());
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#resolveCopy} at {@code copy.body.authorAtmosphere(air);}
     * commented out, this fails with "a forward copy holds the exemplar's air" — the copy kept the
     * constructor's Earth air (2026-10-03).
     */
    @Test
    public void aCopyTakesTheNamedBodysAirForwardAndThroughAnotherCopy() throws Exception {
        // The copies come FIRST in the file and the second copies the first: the name is resolved
        // once the whole file is known, and through a chain.
        DimensionPropertyCoupling read = read(planet("Second", copyOf("First"))
                + planet("First", copyOf("Exemplar"))
                + planet("Exemplar", stated(600_000L, 400_000L)));
        DimensionProperties exemplar = named(read, "Exemplar");
        assertNotNull("the exemplar must load", exemplar);
        assertEquals("a forward copy holds the exemplar's air",
                exemplar.getAir().composition(), named(read, "First").getAir().composition());
        assertEquals("a copy of a copy holds the exemplar's air too",
                exemplar.getAir().composition(), named(read, "Second").getAir().composition());
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#resolveCopy} at {@code if (chain.contains(self))} made
     * {@code if (false && …)}, this fails with a {@code StackOverflowError} (2026-10-03).
     */
    @Test
    public void aCircleOfCopiesRefusesTheLoad() throws Exception {
        assertRefused(planet("Ping", copyOf("Pong")) + planet("Pong", copyOf("Ping")), "Ping");
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#resolveCopy} at {@code if (named == null || named.isEmpty())}
     * made {@code if (false && …)}, this fails with "the refusal must name the body whose air could not
     * be resolved: null" — an NPE, not a refusal (2026-10-03).
     */
    @Test
    public void aCopyOfANameNoBodyHasRefusesTheLoad() throws Exception {
        assertRefused(planet("Orphan", copyOf("Nowhere")), "Orphan");
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#resolveCopy} at {@code if (named.size() > 1)} made
     * {@code if (false && …)}, this fails with "the load must be refused, not completed with a default
     * air for 'Reader'" (2026-10-03).
     */
    @Test
    public void aCopyOfANameTwoBodiesShareRefusesTheLoad() throws Exception {
        assertRefused(planet("Twin", stated(1L, 1L)) + planet("Twin", stated(2L, 2L))
                + planet("Reader", copyOf("Twin")), "Reader");
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code if ((statedAir != null || airCopyOf != null) && totalStated)} made {@code if (false && …)},
     * this fails with "a body stating a composition AND a total must be refused" — `Both` loaded, the
     * control passing (2026-10-03).
     */
    @Test
    public void aCompositionBesideATotalRefusesThatBodyAndNoOther() throws Exception {
        DimensionPropertyCoupling read = read(
                planet("Both", stated(1_000L, 1_000L) + "      <atmosphereDensity>100</atmosphereDensity>\n")
                + planet("Control", stated(1_000L, 1_000L)));
        assertNotNull("CONTROL: a body stating only its composition loads from the same file",
                named(read, "Control"));
        assertNull("a body stating a composition AND a total must be refused, not given either one",
                named(read, "Both"));
    }

    private void assertRefused(String planets, String body) throws Exception {
        try {
            read(planets);
        } catch (RuntimeException refused) {
            assertTrue("the refusal must name the body whose air could not be resolved: "
                    + refused.getMessage(), String.valueOf(refused.getMessage()).contains("'" + body + "'")
                    || String.valueOf(refused.getMessage()).contains(body + " "));
            return;
        }
        fail("the load must be refused, not completed with a default air for '" + body + "'");
    }
}
