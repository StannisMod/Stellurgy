package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.client.GameDirSeed;
import org.junit.Test;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;

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
 * <p>Player supply: {@code stellurgytest player ensure-fake} stations a persistent
 * FakePlayer in the target dim; {@code stellurgytest player tick-living} posts one
 * {@code LivingUpdateEvent} per server tick (Forge's FakePlayer no-ops
 * {@code onUpdate}), reproducing a ticking player's cadence so the
 * {@code worldTime % 20 == 0} gate is crossed naturally.</p>
 *
 * <p>One server for the class, over the galaxy {@link Galaxy} declares. The grant is the one thing a
 * scenario leaves on the shared fake player, and it is undone: every scenario starts by revoking it
 * ({@code player advancement reset}), so "not granted" is a premise each one establishes for itself.</p>
 */
@SeededWorld(AdvancementsTriggerTest.Galaxy.class)
public class AdvancementsTriggerTest extends AbstractSharedServerTest {

    /** World the advancement is given to fire in - the old 15 s ceiling, said in ticks. */
    private static final int GRANT_TICKS = 300;

    private static final int DIM_LUNA = 9511;
    private static final int DIM_OTHER = 9512;
    private static final String ADV_WENT = "stellurgy:normal/wenttothemoon";
    private static final String IS_DONE = "isDone";

    /** Luna, and a planet identical to it in everything but its name. */
    public static final class Galaxy implements WorldSeed {
        @Override
        public void seed(GameDirSeed seed) {
            seed.planetDefs("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<galaxy>\n"
                    + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                    + "          isBlackHole=\"false\" diskAngle=\"70\" "
                    + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                    + planetXml("Luna", DIM_LUNA)
                    + planetXml("AlsoNotLuna", DIM_OTHER)
                    + "    </star>\n"
                    + "</galaxy>\n", AdvancementsTriggerTest.class);
        }
    }

    private static String planetXml(String name, int dim) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>" + dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU + "</orbitalDistance>\n"
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

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** Stations the fake player and runs {@code ticks} living-updates worth of
     *  real server ticks. A forceload ticket keeps the otherwise-empty planet
     *  dim loaded AND TICKING — without it Stellurgy's per-tick unload flickers the
     *  world and its clock never crosses the %20 trigger window. */
    private void stationAndTick(int dim, double x, double y, double z, int ticks) throws Exception {
        String fake = exec("stellurgytest player ensure-fake " + dim + " " + x + " " + y + " " + z);
        assertTrue("ensure-fake must succeed: " + fake, Reply.of(fake).ok());
        revokeTheAdvancement();
        exec("stellurgytest chunk forceload " + dim + " " + (((int) x) >> 4) + " " + (((int) z) >> 4));
        assertTrue("tick-living must succeed",
                Reply.of(exec("stellurgytest player tick-living " + ticks)).ok());
        // EXPERIMENT: the dose is `ticks` living updates, and the callers' assertions are about what
        // that many did (a name gate, a distance gate: "none of them granted it"). The ticker posts
        // one update per SERVER tick and stops at `ticks`; this world's clock can only advance on a
        // server tick, so `ticks + 10` of it deliver every update, and cross the same %20 windows
        // the updates are judged against. Overshoot adds no updates — the dose is capped by the
        // ticker, not by this wait — so the verdict does not move with the box's speed. Measured
        // on the world's clock so a world that is not ticking fails here, naming itself.
        GameTicks.advanceWorld(client(), dim, ticks + 10);
    }

    /** The fake player starts without the advancement, whatever a sibling scenario earned it. */
    private void revokeTheAdvancement() throws Exception {
        String reset = exec("stellurgytest player advancement reset " + ADV_WENT);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the advancement must be revocable on the fake player before this scenario: " + reset,
                !Reply.of(reset).has("error"));
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
                false, isDone(exec("stellurgytest player advancement " + ADV_WENT)));

        // Marked BEFORE the ticks that can grant it: the grant is announced once, and a mark taken
        // after it would be waiting for a second one that will never come.
        long grantMark = events.mark();
        // Δy=15 from (2347,80,67) -> distSq=225 < 512 ✓. 60 ticks ≥ 3 windows.
        assertTrue(Reply.of(exec("stellurgytest player tick-living 60")).ok());

        // Linked on the grant Forge publishes. Vanilla posts AdvancementEvent from
        // PlayerAdvancements.grantCriterion inside `if (!flag1 && progress.isDone())` — once, on
        // the tick it becomes done — so this ends on the moment the advancement was EARNED.
        events.awaitField(grantMark, "advancement_granted", "id", ADV_WENT,
                "standing near (2347,80,67) on Luna must grant WENT_TO_THE_MOON", GRANT_TICKS);
        assertEquals("the advancement was announced as granted, so the player's own record must"
                        + " agree", true, isDone(exec("stellurgytest player advancement " + ADV_WENT)));
    }

    /** Name gate: a Stellurgy dim NOT named "Luna" never fires, same coords. */
    @Test
    public void nonLunaStellurgyDimDoesNotFireWentToTheMoon() throws Exception {
        stationAndTick(DIM_OTHER, 2347, 95, 67, 50);
        assertEquals("non-Luna Stellurgy dim must NOT fire WENT_TO_THE_MOON at the magic coords",
                false, isDone(exec("stellurgytest player advancement " + ADV_WENT)));
    }

    /** Distance gate: Luna but distSq ≥ 512 (100 blocks off in z) never fires. */
    @Test
    public void farFromLanderCoordsOnLunaDoesNotFire() throws Exception {
        stationAndTick(DIM_LUNA, 2347, 95, 167, 50);
        assertEquals("far from lander coords on Luna must NOT grant WENT_TO_THE_MOON",
                false, isDone(exec("stellurgytest player advancement " + ADV_WENT)));
    }
}
