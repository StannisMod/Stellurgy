package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.junit.AbstractOwnBootClientTest;
import dev.stannismod.stellurgy.probe.FirstProbe;
import dev.stannismod.stellurgy.test.EvictionReports;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Window;
import dev.stannismod.stellurgy.test.contract.OwnBoot;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The first probe's one fall over a real save: a dedicated server with one real client logged in on the
 * overworld, the player the probe falls around.
 *
 * <p><b>Why a boot of its own</b> ({@link OwnBoot.Reason#GLOBAL_MUTATION}): the fall happens once per save
 * and nothing undoes it, so on a shared server it would be the premise of every class that ran after it.
 * Each method boots a fresh save.</p>
 *
 * <p><b>Why the client tier</b>: the driver lets the probe in around a player standing on a planet, and only
 * a connected client puts one in the server's player list. What is observed is the server's decision; the
 * client is the player it is made around.</p>
 *
 * <p><b>What the arrangement supplies, and why the contract is the same under it.</b> The delay after the
 * first login is set to zero ({@code config set firstProbeDelayTicks 0}): it only moves WHEN the save is first
 * due, and a server that ran a day of play to reach it would wait twenty minutes for the same decision.
 * Night is set with vanilla's {@code time} as an owner of a world skips it. The fall's end is reached by
 * moving the overworld's total clock to the tick the trajectory lands on ({@code space set-world-clock}),
 * which is the clock the driver reads the fall's progress from; the eleven minutes in between draw a fall in
 * the sky and decide nothing about whether another enters.</p>
 *
 * <p>Does NOT see: a second planet — the save's state being global rather than a world's is asserted only
 * where a single world holds both nights; the client's picture of the fall; the crater.</p>
 */
@OwnBoot(reason = OwnBoot.Reason.GLOBAL_MUTATION)
public class FirstProbeFallTest extends AbstractOwnBootClientTest {

    /** The record {@code MixinProbeFallEvents} writes on every offer of a fall to the save. */
    private static final String ENTRY = "probe_entry";

    /** The record it writes when the fall reaches the ground. */
    private static final String TOUCHDOWN = "probe_touchdown";

    /**
     * The deadline of a link that follows one arrangement: the driver decides on the first server tick
     * after it, so this only has to cover the commands' round trips and one tick in which up to 64 columns
     * of terrain are generated for the landing. A deadline, never a verdict.
     */
    private static final int LINK_TICKS = 200;

    /**
     * How long each night is watched for a second entry. The driver offers the fall on every tick the save
     * is due, so a save that admitted a second probe would do so on the first tick of the window; the length
     * only has to hold several of those ticks inside one night.
     */
    private static final int NIGHT_WATCH_TICKS = 60;

    /**
     * The window over the night the probe falls in, one tick: the save is due on every tick of that night,
     * so a save that admitted a second probe would do so on the first tick after the first. Kept to that one
     * tick because each admitted entry generates up to 64 columns of terrain within its tick: a save that
     * re-admits on every tick slows the server to about a second a tick (measured 2026-10-27, 45 ticks in
     * 57 s), and a longer window would end on the stall net instead of on this verdict.
     */
    private static final int FALLING_WATCH_TICKS = 1;

    /** The record {@code MixinProbeImpactEvents} writes when the crater and wreck are laid. */
    private static final String LAID = "probe_laid";

    /** The wreck's registry name, as {@code block at} reports it. */
    private static final String WRECK_BLOCK = "stellurgy:probewreck";

    /**
     * How far below the landing block the column is searched for the wreck: the bowl is dug into the ground
     * the probe lands on, and this reaches past any bowl a few blocks deep so a wreck laid too deep is found
     * as well as one laid where it should be.
     */
    private static final int WRECK_SEARCH_DEPTH = 6;

    private final EvictionReports evictions = new EvictionReports();

    @Test
    @Pins("INV-PRB-05")
    @RedWitness(verdict = "nothing enters while it falls", member = "ProbeFall#enter",
            at = "phase = Phase.FALLING;", replacedBy = "",
            fails = "while the probe is falling, nothing enters again", on = "2026-10-27")
    @RedWitness(verdict = "nothing enters once it is down", member = "ProbeFall#touchDown",
            at = "phase = Phase.DOWN;", replacedBy = "phase = Phase.WAITING;",
            fails = "over two nights exactly one probe has entered", on = "2026-10-27")
    @Wait(shape = Wait.Shape.WINDOW, at = "fallingNight = Window.over(",
            why = "the verdict is that the number of admitted entries does not change over a night while the"
                    + " probe is falling: no record links to an entry that did not happen")
    @Wait(shape = Wait.Shape.WINDOW, at = "secondNight = Window.over(",
            why = "the same, over a later night once the probe is down")
    public void exactlyOneProbeFallsOverTwoNights() throws Exception {
        Events.Step bothSides = bothSides();
        Events events = events(bothSides);
        long mark = events.markInstrumented();

        letTheProbeIn(events, mark);

        Window<Integer> fallingNight = Window.over(() -> admitted(events, mark), serverClient(),
                GameTicks.server(), bothSides, FALLING_WATCH_TICKS);
        assertEquals("while the probe is falling, nothing enters again — admitted entries " + fallingNight
                + "; recorded: " + events.since(mark, ENTRY), 0d, fallingNight.change(count -> count), 0d);
        assertEquals("exactly one probe has entered: " + events.since(mark, ENTRY), 1, admitted(events, mark));

        bringItDown(events);

        arrange("time add 24000");
        requireArranged("the second night must be deep night too", deepNight());
        Window<Integer> secondNight = Window.over(() -> admitted(events, mark), serverClient(),
                GameTicks.server(), bothSides, NIGHT_WATCH_TICKS);
        requireArranged("the second night must last the whole window", deepNight());
        assertEquals("once the probe is down, nothing enters on a later night — admitted entries " + secondNight
                + " (the save after touchdown: " + fallState() + ")", 0d, secondNight.change(count -> count), 0d);
        assertEquals("over two nights exactly one probe has entered: " + events.since(mark, ENTRY), 1,
                admitted(events, mark));
    }

    /**
     * The band and the place only. The third half of the clause, dry land where any is drawn, is not judged
     * here: around this world's spawn the draws are dry, so the choice of dry land has nothing to choose
     * between — inverting it to prefer water left the landing dry (2026-10-27). Judging it needs a player
     * standing where the band holds water and land both.
     */
    @Test
    @Pins("INV-PRB-06")
    @RedWitness(verdict = "in the band", member = "ProbeFallDriver#fallAround",
            at = "double[] offset = FirstProbe.landingOffset(draw);",
            replacedBy = "double[] offset = FirstProbe.landingOffset(draw); offset[0] *= 4; offset[1] *= 4;",
            fails = "it lands in the band around the player", on = "2026-10-27")
    @RedWitness(verdict = "where its fall ends", member = "ProbeFallDriver#onServerTick",
            at = "ProbeImpact.lay(ground, down.landing, down.record());",
            replacedBy = "ProbeImpact.lay(ground, down.landing.east(8), down.record());",
            fails = "the crater is laid on the block the fall ended on", on = "2026-10-27")
    public void itLandsWhereItsFallEndsInTheBandAroundThePlayer() throws Exception {
        Events events = events(bothSides());
        long mark = events.markInstrumented();

        String entry = letTheProbeIn(events, mark);
        int x = (int) Events.number(entry, "x");
        int y = (int) Events.number(entry, "y");
        int z = (int) Events.number(entry, "z");
        // The driver floors the player's place plus the drawn offset to a block, so the landing block's corner
        // lies within a block of the drawn point on each axis: the band is widened by that diagonal, no more.
        double fromPlayer = Math.hypot(x - Events.number(entry, "playerX"), z - Events.number(entry, "playerZ"));
        double flooring = Math.sqrt(2d);
        assertTrue("it lands in the band around the player at the entry, " + FirstProbe.LANDING_MIN_BLOCKS + ".."
                        + FirstProbe.LANDING_MAX_BLOCKS + " blocks: " + fromPlayer + " away — " + entry,
                fromPlayer >= FirstProbe.LANDING_MIN_BLOCKS - flooring
                        && fromPlayer <= FirstProbe.LANDING_MAX_BLOCKS + flooring);

        bringItDown(events);
        long laidMark = events.mark();
        for (int cx = (x >> 4) - 1; cx <= (x >> 4) + 1; cx++) {
            for (int cz = (z >> 4) - 1; cz <= (z >> 4) + 1; cz++) {
                arrange("stellurgytest chunk forceload 0 " + cx + " " + cz);
            }
        }
        String laid = Events.lastRecord(events.await(laidMark, LAID,
                "the ground around the landing being loaded lets the crater be laid", LINK_TICKS));
        assertEquals("the crater is laid on the block the fall ended on — laid " + laid + ", fell " + entry,
                x + "," + y + "," + z,
                (int) Events.number(laid, "x") + "," + (int) Events.number(laid, "y") + "," + (int) Events.number(laid, "z"));
        int wrecks = 0;
        StringBuilder column = new StringBuilder();
        for (int below = 0; below <= WRECK_SEARCH_DEPTH; below++) {
            String command = "stellurgytest block at 0 " + x + " " + (y - below) + " " + z;
            String block = Reply.answering(command, serverClient().read(command)).text("block");
            column.append(y - below).append('=').append(block).append(' ');
            if (WRECK_BLOCK.equals(block)) {
                wrecks++;
            }
        }
        assertEquals("the wreck lies in the landing's own column, once — the column below it: " + column, 1, wrecks);
    }

    /** Lets the probe in at the save's first night and answers its admitted entry record. A class instrument:
     *  the save is due from its first login with the delay at zero, and night is set by {@code time}. */
    private String letTheProbeIn(Events events, long mark) throws Exception {
        Reply before = fallState();
        requireArranged("nothing may have fallen before the scenario makes the save due: " + before,
                before.bool("stored") && "WAITING".equals(before.text("phase")));
        requireArranged("the save's first login must have started the delay: " + before,
                before.longInteger("firstLoginTick") >= 0L);

        arrange("stellurgytest config set firstProbeDelayTicks 0");
        arrange("time set 18000");
        requireArranged("the overworld must stand in deep night once it is set to midnight", deepNight());

        String reply = events.awaitField(mark, ENTRY, "admitted", true,
                "the save being due under the player's night sky lets the probe in", LINK_TICKS);
        Reply falling = fallState();
        requireArranged("the probe's fall must be over the overworld, the world whose clock ends it: " + falling,
                falling.object("fall") != null && Reply.of(falling.object("fall")).integer("dim") == 0);
        return Events.recordsWhere(reply, "admitted", "true").get(0);
    }

    /** Brings the fall down by moving the overworld's total clock to the tick it lands on, and waits for the
     *  touchdown. A class instrument: the driver reads the fall's progress off that clock. */
    private void bringItDown(Events events) throws Exception {
        Reply fall = Reply.of(fallState().object("fall"));
        long landsAt = fall.longInteger("entryTick") + fall.integer("ticks");
        long touchdownMark = events.mark();
        arrange("stellurgytest space set-world-clock " + landsAt);
        events.await(touchdownMark, TOUCHDOWN, "the overworld's clock past the fall's end brings it down",
                LINK_TICKS);
    }

    private Events.Step bothSides() {
        return GameTicks.serverAndClient(serverClient(), GameTicks.server(), ticks -> bot().waitWorldTicks(ticks));
    }

    private Events events(Events.Step step) {
        return new Events(command -> String.join("\n", serverClient().read(command)), step, evictions);
    }

    /** The admitted entries recorded since {@code mark}. */
    private static int admitted(Events events, long mark) throws Exception {
        return Events.recordsWhere(events.since(mark, ENTRY), "admitted", "true").size();
    }

    /** The save's fall as its storage holds it. */
    private Reply fallState() throws Exception {
        String command = "stellurgytest first-probe fall";
        return Reply.answering(command, serverClient().read(command)).requireOk(command);
    }

    /**
     * Whether the overworld's sky, as its provider computes it at the world's own time, is well inside the
     * night — a margin from the dusk and dawn the probe's own rule is drawn at, so that this premise never
     * restates that rule's threshold. 0 is noon and 0.5 midnight.
     */
    private boolean deepNight() throws Exception {
        String timeCommand = "stellurgytest dim time 0";
        long worldTime = Reply.answering(timeCommand, serverClient().arrange(timeCommand)).longInteger("worldTime");
        String angleCommand = "stellurgytest dim celestial-angle 0 " + worldTime;
        double angle = Reply.answering(angleCommand, serverClient().read(angleCommand)).number("angle");
        double turn = angle - Math.floor(angle);
        return turn > 0.35d && turn < 0.65d;
    }

    private void arrange(String command) throws Exception {
        Reply reply = Reply.answering(command, serverClient().arrange(command));
        if (command.startsWith("stellurgytest ")) {
            reply.requireOk(command);
        }
    }
}
