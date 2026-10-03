package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.atmosphere.AirState;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A planet's outdoors is its AIR, judged by the band a room is judged by: a trace of oxygen reads as
 * too little oxygen, not as none — and the air a world was given is the air it has after a restart.
 *
 * <p>The distinction this pins existed in rooms and died at the planet boundary: outdoors was a
 * yes/no flag beside a pressure, so a world could hold oxygen or not, and never "some".</p>
 *
 * <p>The world is a carbon-dioxide planet from the planet file (no oxygen of its own). The trace
 * arrives through the planet's own gas exchange — the one a terraformer step uses — so the arrangement
 * supplies gas, never the reading. Manual harness lifecycle: the planet file must be on disk before the
 * first boot, and the second boot runs against the same directory.</p>
 */
public class ATraceOfOxygenOutdoorsReadsAsTooLittleTest {

    private static final int DIM = 9731;
    /** A trace: a thousandth of an atmosphere, two orders under the breathing band's floor. */
    private static final long TRACE_PPM = 1000L;
    /** Outdoors, well above any terrain, where no sealed zone can contain the point. */
    private static final String OUTDOORS = "0 220 0";

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeAPlanetFileWithACarbonDioxideWorld() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-trace-oxygen-");
        Path stellurgyConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(stellurgyConfigDir);

        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<galaxy>\n" +
                "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\"\n" +
                "          isBlackHole=\"false\" diskAngle=\"70\" numPlanets=\"0\" numGasGiants=\"0\">\n" +
                "        <planet name=\"Carbonia\" DIMID=\"" + DIM + "\">\n" +
                "            <hasOxygen>false</hasOxygen>\n" +
                "            <mass>1.0</mass>\n" +
                "            <radius>1.0</radius>\n" +
                "            <orbitalDistance>100</orbitalDistance>\n" +
                "            <atmosphereDensity>100</atmosphereDensity>\n" +
                "        </planet>\n" +
                "    </star>\n" +
                "</galaxy>\n";

        Files.write(stellurgyConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    /**
     * red-witnessed: with {@code DimensionProperties#hasOxygen} at {@code return air.getOxygen() > 0L;}
     * replaced by {@code return false;}, this fails after the trace is added with
     * {@code expected:<[low]O2> but was:<[No]O2>} (2026-10-01).
     * red-witnessed: with {@code DimensionProperties#copyData} lacking
     * {@code this.air = props.air.copy();}, this fails after the restart with
     * {@code expected:<[lowO2]> but was:<[air]>} — the world re-read from its planet file came back as
     * Earth's mix (2026-10-01, the first run of this test, before that line existed).
     */
    @Test
    public void aTraceOfOxygenReadsAsTooLittleOutdoorsAndIsStillThereAfterARestart() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        Reply before = outdoors();
        // The positive precondition of the verdict below: this world starts with NO oxygen, so a
        // reading of "too little" afterwards is the trace and not something the world always had.
        assertEquals("the carbon-dioxide world must start with no oxygen outdoors: " + before,
                "NoO2", before.text("type"));

        String add = "stellurgytest planet add-gas " + DIM + " oxygen " + TRACE_PPM * AirState.PER_PPM;
        Reply.of(add, String.join("\n", harness.client().execute(add))).requireOk("add a trace of oxygen");

        Reply after = outdoors();
        assertEquals("a trace of oxygen outdoors must read as too little oxygen, not as none: " + after,
                "lowO2", after.text("type"));

        harness.close();
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        Reply restarted = outdoors();
        assertEquals("after a restart the world must still hold its trace, and read it the same way: "
                + restarted, "lowO2", restarted.text("type"));

        String info = "stellurgytest planet info " + DIM;
        Reply planet = Reply.of(info, String.join("\n", harness.client().execute(info)));
        String composition = planet.object("gases");
        assertTrue("planet info must report the world's composition: " + planet, composition != null);
        Reply gases = Reply.of(composition);
        assertEquals("the saved air must hold exactly the oxygen that was put in: " + planet,
                TRACE_PPM * AirState.PER_PPM, gases.longInteger("oxygen"));
    }

    /** The atmosphere at a point outdoors on the fixture world, as the world's own handler answers it. */
    private Reply outdoors() throws Exception {
        String load = "stellurgytest dim load " + DIM;
        Reply loaded = Reply.of(load, String.join("\n", harness.client().execute(load)));
        assertTrue("the fixture world must load: " + loaded, loaded.bool("loaded"));

        String get = "stellurgytest atmosphere get " + DIM + " " + OUTDOORS;
        Reply atmosphere = Reply.of(get, String.join("\n", harness.client().execute(get)));
        // Arrangement: the reading comes from the world's atmosphere handler — the path a player
        // standing there is judged by — and not from the probe's fallback to the dimension default.
        assertEquals("the outdoor reading must come from the world's own handler: " + atmosphere,
                "block-handler", atmosphere.text("source"));
        return atmosphere;
    }
}
