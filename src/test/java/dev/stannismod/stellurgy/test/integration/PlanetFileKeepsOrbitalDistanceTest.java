package dev.stannismod.stellurgy.test.integration;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import dev.stannismod.stellurgy.api.dimension.IDimensionProperties;
import dev.stannismod.stellurgy.api.dimension.solar.IGalaxy;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.universe.UniverseScale;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import dev.stannismod.stellurgy.util.XMLPlanetLoader;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The planet file the loader WRITES is read back by the loader to the same orbital distances — the
 * joint between {@code XMLPlanetLoader.writeXML} and {@code XMLPlanetLoader.readAllPlanets}, which a
 * world's save and its next start both go through.
 *
 * <p>Two distances, because they fail two ways: an ordinary two AU, which breaks if the writer renders
 * the number in a form the integer reader rejects; and the model's widest named reach, 5 000 AU, which
 * breaks if either side narrows it to an {@code int}. Not ONE AU: that is what a planet reads as when
 * its distance fails to parse, so a planet written there cannot tell a round trip from a fallback.</p>
 *
 * <p>red-witnessed: 2026-09-30, twice. With the writer's {@code long} overload,
 * {@code XMLPlanetLoader#createTextNode} at {@code return createTextNode(doc, nodeName, Long.toString(nodeText))},
 * removed (a long then resolves to the double one and is written "2991958.0"): "a planet at two AU
 * expected:&lt;2991958&gt; but was:&lt;1495979&gt;". With the companion's reader,
 * {@code XMLPlanetLoader#readSubStar} at {@code star.setOrbitalDistance(Long.parseLong(nameNode.getNodeValue()))},
 * back on {@code Integer.parseInt}: "a companion at 5 000 AU expected:&lt;7479895000&gt; but
 * was:&lt;74799&gt;" — the unparsed attribute leaves the 0.05 AU default.</p>
 */
public class PlanetFileKeepsOrbitalDistanceTest {

    private static final long TWO_AU = 2L * AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
    private static final long WIDEST_NAMED_ORBIT = UniverseScale.MAX_NAMED_ORBIT_UNITS;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void planetAndCompanionDistancesSurviveAWriteAndARead() throws Exception {
        assertTrue("the widest named orbit must lie past the int range, or this cannot see a narrowing",
                WIDEST_NAMED_ORBIT > Integer.MAX_VALUE);

        StellarBody star = new StellarBody();
        star.setName("RoundTripStar");
        star.setId(0);
        StellarBody companion = new StellarBody();
        companion.setOrbitalDistance(WIDEST_NAMED_ORBIT);
        star.addSubStar(companion);

        DimensionProperties near = new DimensionProperties(-7401, "RoundTripNear");
        near.orbitalDist = TWO_AU;
        near.setStar(star);
        DimensionProperties far = new DimensionProperties(-7402, "RoundTripFar");
        far.orbitalDist = WIDEST_NAMED_ORBIT;
        far.setStar(star);

        File file = folder.newFile("planetDefs.xml");
        try (Writer out = new OutputStreamWriter(Files.newOutputStream(file.toPath()),
                StandardCharsets.UTF_8)) {
            out.write(XMLPlanetLoader.writeXML(galaxyOf(star), null));
        }

        XMLPlanetLoader.DimensionPropertyCoupling read = new XMLPlanetLoader().loadPlanetsOrThrow(file);

        assertEquals("a planet at two AU", TWO_AU, planetNamed(read, "RoundTripNear").getOrbitalDist());
        assertEquals("a planet at 5 000 AU", WIDEST_NAMED_ORBIT,
                planetNamed(read, "RoundTripFar").getOrbitalDist());

        StellarBody readStar = starNamed(read, "RoundTripStar");
        assertEquals("the star's one companion", 1, readStar.getSubStars().size());
        assertEquals("a companion at 5 000 AU", WIDEST_NAMED_ORBIT,
                readStar.getSubStars().get(0).getOrbitalDistance());
    }

    private static DimensionProperties planetNamed(XMLPlanetLoader.DimensionPropertyCoupling read,
                                                   String name) {
        for (DimensionProperties props : read.dims) {
            if (name.equals(props.getName())) {
                return props;
            }
        }
        throw new AssertionError("the file read back holds no planet named " + name
                + "; it holds " + read.dims.size() + " planets");
    }

    private static StellarBody starNamed(XMLPlanetLoader.DimensionPropertyCoupling read, String name) {
        for (StellarBody s : read.stars) {
            if (name.equals(s.getName())) {
                return s;
            }
        }
        throw new AssertionError("the file read back holds no star named " + name
                + "; it holds " + read.stars.size() + " stars");
    }

    /** The one star this test writes, as the writer's galaxy; the writer asks for nothing else. */
    private static IGalaxy galaxyOf(StellarBody star) {
        assertNotNull(star);
        return new IGalaxy() {
            @Override
            public Collection<StellarBody> getStars() {
                return Collections.singletonList(star);
            }

            @Override
            public Integer[] getRegisteredDimensions() {
                throw new UnsupportedOperationException("the writer is not expected to ask");
            }

            @Override
            public SatelliteBase getSatellite(long satId) {
                throw new UnsupportedOperationException("the writer is not expected to ask");
            }

            @Override
            public boolean canTravelTo(int dimId) {
                throw new UnsupportedOperationException("the writer is not expected to ask");
            }

            @Override
            public IDimensionProperties getDimensionProperties(int dimId) {
                throw new UnsupportedOperationException("the writer is not expected to ask");
            }

            @Override
            public StellarBody getStar(int id) {
                throw new UnsupportedOperationException("the writer is not expected to ask");
            }

            @Override
            public boolean isDimensionCreated(int dimId) {
                throw new UnsupportedOperationException("the writer is not expected to ask");
            }

            @Override
            public boolean areDimensionsInSamePlanetMoonSystem(int destinationDimId, int dimension) {
                throw new UnsupportedOperationException("the writer is not expected to ask");
            }
        };
    }
}
