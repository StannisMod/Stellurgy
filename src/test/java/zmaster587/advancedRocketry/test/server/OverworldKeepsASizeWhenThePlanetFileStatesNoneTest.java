package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * A save whose planet file states no size for the OVERWORLD still runs a world with a size.
 *
 * <p>Such saves exist, and they were made by this mod: the overworld's catalogue entry lives on a
 * JVM-lifetime static that a world teardown used to blank, and the planet-file writer emits a body's
 * mass and radius only when the body HAS them. One world exit was therefore enough to make every
 * later world in that process record an Earth of no size — permanently, because the planet file is
 * authoritative when present, so a fixed build reading that file gets the sizeless Earth back.</p>
 *
 * <p><b>What "no size" costs</b>, and why the repair is worth its warning line: a body with radius
 * zero reaches the sky renderer with {@code radiusBlocks = 0} and is drawn at the marker size at
 * every range — reported 2026-08-23 from a live flight as "I can see the Moon but not the Earth",
 * with the Earth's label sitting behind the Moon's disc — and its descent shell falls back to the
 * flat 512-block proximity sphere meant for belts, 1/50 of this world.</p>
 *
 * <p>The fixture below is the shape those saves have: an Earth with a gravity and no bulk beside a
 * Luna with both, which is exactly what the reporter's {@code planetDefs.xml} contained. The
 * assertion is on the OVERWORLD only — Luna is here as the control, to show the file is read and
 * that a body which states its bulk keeps the bulk it stated rather than the unit one.</p>
 *
 * <p>Manual harness lifecycle for the same reason as its neighbours: the planet file has to be on
 * disk before the server boots.</p>
 */
public class OverworldKeepsASizeWhenThePlanetFileStatesNoneTest {

    private static final int MOON_DIM = 2;
    /** Luna's measured bulk, the control arm — a body that states its own must keep it. */
    private static final String MOON_MASS = "0.0123";
    private static final String MOON_RADIUS = "0.2727";

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeAPlanetFileWithASizelessEarth() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-sizeless-earth-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<galaxy>\n" +
                "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\"\n" +
                "          isBlackHole=\"false\" diskAngle=\"70\" numPlanets=\"0\" numGasGiants=\"0\">\n" +
                "        <planet name=\"Earth\" DIMID=\"0\">\n" +
                "            <isKnown>true</isKnown>\n" +
                "            <gravitationalMultiplier>100</gravitationalMultiplier>\n" +
                "            <orbitalDistance>100</orbitalDistance>\n" +
                "            <atmosphereDensity>100</atmosphereDensity>\n" +
                "            <planet name=\"Luna\" DIMID=\"" + MOON_DIM + "\">\n" +
                "                <isKnown>false</isKnown>\n" +
                "                <gravitationalMultiplier>16</gravitationalMultiplier>\n" +
                "                <mass>" + MOON_MASS + "</mass>\n" +
                "                <radius>" + MOON_RADIUS + "</radius>\n" +
                "                <orbitalDistance>150</orbitalDistance>\n" +
                "                <atmosphereDensity>0</atmosphereDensity>\n" +
                "            </planet>\n" +
                "        </planet>\n" +
                "    </star>\n" +
                "</galaxy>\n";

        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    public void anOverworldWithNoStatedBulkGetsTheUnitOneAndTheMoonKeepsItsOwn() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String earth = String.join("\n", harness.client().execute("artest planet info 0"));
        assertTrue("planet info errored for the overworld: " + earth, !earth.contains("\"error\""));
        assertTrue("the overworld must run with the unit radius when its planet file states none —"
                        + " a body of radius 0 draws at the marker size at every range and carries the"
                        + " flat 512-block proximity shell instead of an atmosphere: " + earth,
                earth.contains("\"radius\":1.0"));
        assertTrue("the overworld must run with the unit mass on the same terms — mass and radius are"
                        + " stated together and derived gravity reads both: " + earth,
                earth.contains("\"mass\":1.0"));

        // CONTROL: the repair is aimed at the ONE body whose bulk is a definition. A body that stated
        // its own must come back with what it stated, or the assertion above is passing on a blanket
        // "everything is 1 Earth" rather than on the overworld's entry.
        String luna = String.join("\n", harness.client().execute("artest planet info " + MOON_DIM));
        assertTrue("planet info errored for the moon: " + luna, !luna.contains("\"error\""));
        assertTrue("a body that STATES its bulk must keep it, not be repaired to the unit one: " + luna,
                luna.contains("\"radius\":" + MOON_RADIUS) && luna.contains("\"mass\":" + MOON_MASS));
    }
}
