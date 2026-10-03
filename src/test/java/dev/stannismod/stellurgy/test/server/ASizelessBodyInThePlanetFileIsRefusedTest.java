package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A body in the planet file that states no mass and no radius is REFUSED, never loaded with a size
 * nobody gave it — the overworld included.
 *
 * <p>What a body's air keeps is decided by its mass and its size, so a body without them has a
 * question with no answer, and every body in the file is held to stating both. The overworld gets no
 * exemption: an Earth of no size used to be repaired to the unit bulk on load, and that repair was a
 * size the file did not state.</p>
 *
 * <p>The witness is the dimension REGISTRY, not {@code planet info}: that verb answers an unknown id
 * with the overworld's properties, so it cannot tell a refused body from a loaded one.</p>
 *
 * <p>Manual harness lifecycle: the planet file has to be on disk before the server boots.</p>
 */
public class ASizelessBodyInThePlanetFileIsRefusedTest {

    private static final int OVERWORLD_DIM = 0;
    /** An airless planet that states everything but its bulk. */
    private static final int SIZELESS_DIM = 4;
    /** The CONTROL: the same planet, stating its bulk. */
    private static final int SIZED_DIM = 3;

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeAPlanetFileWithSizelessBodies() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-sizeless-body-");
        Path stellurgyConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(stellurgyConfigDir);

        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<galaxy>\n" +
                "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\"\n" +
                "          isBlackHole=\"false\" diskAngle=\"70\" numPlanets=\"0\" numGasGiants=\"0\">\n" +
                "        <planet name=\"Earth\" DIMID=\"" + OVERWORLD_DIM + "\">\n" +
                "            <gravitationalMultiplier>100</gravitationalMultiplier>\n" +
                "            <orbitalDistance>100</orbitalDistance>\n" +
                "            <atmosphereDensity>100</atmosphereDensity>\n" +
                "        </planet>\n" +
                planet("Sizeless", SIZELESS_DIM, "") +
                planet("Sized", SIZED_DIM,
                        "            <mass>1.0</mass>\n" +
                        "            <radius>1.0</radius>\n") +
                "    </star>\n" +
                "</galaxy>\n";

        Files.write(stellurgyConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * An AIRLESS planet: a body with air could not be realized without its size anyway, so only an
     * airless one shows that the size is required of every body, whatever else it says.
     */
    private static String planet(String name, int dim, String bulk) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n" +
                "            <orbitalDistance>140</orbitalDistance>\n" +
                bulk +
                "            <atmosphereDensity>0</atmosphereDensity>\n" +
                "        </planet>\n";
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    /**
     * red-witnessed: with {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code if (!properties.hasBulkProperties())} replaced by {@code if (false)}, this fails on the
     * sizeless airless planet: {@code "stellurgyDimensions":[3,4]} (2026-10-01).
     */
    @Test
    public void aBodyThatStatesNoSizeIsNotLoadedAndTheOverworldIsHeldToTheSameRule() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String command = "stellurgytest dim list";
        Reply dims = Reply.of(command, String.join("\n", harness.client().execute(command)));
        int[] registered = dims.intArray("stellurgyDimensions");

        // CONTROL: the file was read, and a body that states its size loads from it — so an absence
        // below is a refusal, not a file nobody opened.
        assertTrue("the planet that states its bulk must load from the file: " + dims,
                contains(registered, SIZED_DIM));

        assertFalse("a planet that states no mass and radius must be refused, not given a size: " + dims,
                contains(registered, SIZELESS_DIM));
        assertFalse("the overworld's entry is held to the same rule — an Earth of no size is refused,"
                + " not repaired to the unit bulk: " + dims, contains(registered, OVERWORLD_DIM));
    }

    private static boolean contains(int[] values, int wanted) {
        return Arrays.stream(values).anyMatch(v -> v == wanted);
    }
}
