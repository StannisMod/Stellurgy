package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code PlanetEventHandler.playerTick} WENT_TO_THE_MOON trigger — server
 * tier. Relabeled down the pyramid from the old client-harness
 * {@code AdvancementsE2ETest} the contract
 * (name gate "Luna", distanceSq &lt; 512 of (2347,80,67), %20-tick window,
 * advancement grant) is entirely server-side; the old client test drove it
 * exclusively through server probes anyway.
 *
 * <p>Player supply: {@code artest player ensure-fake} stations a persistent
 * FakePlayer in the target dim; {@code artest player tick-living} posts one
 * {@code LivingUpdateEvent} per server tick (Forge's FakePlayer no-ops
 * {@code onUpdate}), reproducing a ticking player's cadence so the
 * {@code worldTime % 20 == 0} gate is crossed naturally.</p>
 */
public class AdvancementsTriggerTest {

    /** World the advancement is given to fire in - the old 15 s ceiling, said in ticks. */
    private static final int GRANT_TICKS = 300;

    private static final int DIM_LUNA = 9511;
    private static final int DIM_OTHER = 9512;
    private static final String ADV_WENT = "advancedrocketry:normal/wenttothemoon";
    private static final String IS_DONE = "isDone";

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-advancements-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                + planetXml("Luna", DIM_LUNA)
                + planetXml("AlsoNotLuna", DIM_OTHER)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
    }

    private static String planetXml(String name, int dim) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>24000</rotationalPeriod>\n"
                + "            <atmosphereDensity>0</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopServer() throws Exception {
        if (harness != null) harness.close();
    }

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(harness.client(), GameTicks.server(), ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    /** Stations the fake player and runs {@code ticks} living-updates worth of
     *  real server ticks. A forceload ticket keeps the otherwise-empty planet
     *  dim loaded AND TICKING — without it AR's per-tick unload flickers the
     *  world and its clock never crosses the %20 trigger window. */
    private void stationAndTick(int dim, double x, double y, double z, int ticks) throws Exception {
        String fake = exec("artest player ensure-fake " + dim + " " + x + " " + y + " " + z);
        assertTrue("ensure-fake must succeed: " + fake, Reply.of(fake).ok());
        exec("artest chunk forceload " + dim + " " + (((int) x) >> 4) + " " + (((int) z) >> 4));
        assertTrue("tick-living must succeed",
                Reply.of(exec("artest player tick-living " + ticks)).ok());
        // EXPERIMENT: the dose is `ticks` living updates, and the callers' assertions are about what
        // that many did (a name gate, a distance gate: "none of them granted it"). The ticker posts
        // one update per SERVER tick and stops at `ticks`; this world's clock can only advance on a
        // server tick, so `ticks + 10` of it deliver every update, and cross the same %20 windows
        // the updates are judged against. Overshoot adds no updates — the dose is capped by the
        // ticker, not by this wait — so the verdict does not move with the box's speed. Measured
        // on the world's clock so a world that is not ticking fails here, naming itself.
        GameTicks.advanceWorld(harness.client(), dim, ticks + 10);
    }

    private boolean isDone(String src) {
        Reply mReply = Reply.of(src);
        assertTrue("isDone field missing in: " + src, mReply.has(IS_DONE));
        return Boolean.parseBoolean(mReply.text(IS_DONE));
    }

    /** Standing on Luna within the distance gate grants WENT_TO_THE_MOON
     *  within 1–2 %20-tick trigger windows. Baseline asserted first. */
    @Test
    public void standingNearLanderOnLunaFiresWentToTheMoon() throws Exception {
        // Station only, with NO living update: this spot is already inside the distance gate, so a
        // single update that happened to land on a %20 window granted the very advancement the
        // baseline below says is not granted yet — about one run in twenty.
        stationAndTick(DIM_LUNA, 2347, 95, 67, 0);
        assertEquals("baseline: WENT_TO_THE_MOON must not be granted yet",
                false, isDone(exec("artest player advancement " + ADV_WENT)));

        // Marked BEFORE the ticks that can grant it: the grant is announced once, and a mark taken
        // after it would be waiting for a second one that will never come.
        long grantMark = events.mark();
        // Δy=15 from (2347,80,67) -> distSq=225 < 512 ✓. 60 ticks ≥ 3 windows.
        assertTrue(Reply.of(exec("artest player tick-living 60")).ok());

        // Linked on the grant Forge publishes. Vanilla posts AdvancementEvent from
        // PlayerAdvancements.grantCriterion inside `if (!flag1 && progress.isDone())` — once, on
        // the tick it becomes done — so this ends on the moment the advancement was EARNED. The
        // poll it replaces asked "is it done yet" one reading at a time, which answers about
        // whenever it happened to look and needs a budget to say how long it is willing to keep
        // looking.
        events.awaitField(grantMark, "advancement_granted", "id", ADV_WENT,
                "standing near (2347,80,67) on Luna must grant WENT_TO_THE_MOON", GRANT_TICKS);
        assertEquals("the advancement was announced as granted, so the player's own record must"
                        + " agree", true, isDone(exec("artest player advancement " + ADV_WENT)));
    }

    /** Name gate: an AR dim NOT named "Luna" never fires, same coords. */
    @Test
    public void nonLunaArDimDoesNotFireWentToTheMoon() throws Exception {
        stationAndTick(DIM_OTHER, 2347, 95, 67, 50);
        assertEquals("non-Luna AR dim must NOT fire WENT_TO_THE_MOON at the magic coords",
                false, isDone(exec("artest player advancement " + ADV_WENT)));
    }

    /** Distance gate: Luna but distSq ≥ 512 (100 blocks off in z) never fires. */
    @Test
    public void farFromLanderCoordsOnLunaDoesNotFire() throws Exception {
        stationAndTick(DIM_LUNA, 2347, 95, 167, 50);
        assertEquals("far from lander coords on Luna must NOT grant WENT_TO_THE_MOON",
                false, isDone(exec("artest player advancement " + ADV_WENT)));
    }
}
