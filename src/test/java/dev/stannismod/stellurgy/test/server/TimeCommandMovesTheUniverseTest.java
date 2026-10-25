package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code /time add} and {@code /time set} move the universe (C16 CLOCK-7.2), and every body's time of day
 * follows it (CLOCK-6).
 *
 * <p>Each verdict is read off one {@code dim time} reply, which carries a world's time of day, its age and the
 * space clock at the same instant. A world's age is not moved by a skip (CLOCK-5.3) and the space clock is, so
 * the clock against the age measures the skip exactly, whatever ticks ran between two reads; and those ticks
 * are themselves the change in age, which bounds the moment the command ran.</p>
 *
 * <p>What it does NOT see: a sleep (the other way in), the client's clock, a world that is not a body.
 * Leaves: the universe of this class's own server aged by what the commands moved it.</p>
 */
public class TimeCommandMovesTheUniverseTest extends AbstractSharedServerTest {

    /** A distinctive amount, short of a day so it cannot read as a whole turn. */
    private static final long ADD_TICKS = 7_777L;
    /** A time of day to set, vanilla's ticks. */
    private static final long SET_TIME = 3_333L;
    /** The overworld's day, ticks — its `rotationalPeriod` as the dim probe reports it. */
    private static final String DAY = "rotationalPeriod";

    /**
     * {@code /time add N}: the space clock gains N against the world's age, and the overworld's and a planet's
     * time of day each gain what the clock gained.
     */
    @Pins("CLOCK-7")
    @RedWitness(verdict = "add", member = "TimeCommandGuard#onCommand", at = "TimeSkip.skip(server, ticks);",
            replacedBy = "", fails = "CLOCK-7: /time add", on = "2026-10-24")
    @Test
    public void timeAddMovesTheUniverseByThatMuch() throws Exception {
        int planet = aPlanet();
        Reply home0 = dimTime(0);
        Reply planet0 = dimTime(planet);
        arrange("time add " + ADD_TICKS);
        Reply home1 = dimTime(0);
        Reply planet1 = dimTime(planet);

        assertEquals("CLOCK-7: /time add — the space clock against the overworld's age: before " + home0.raw()
                + " | after " + home1.raw(), ADD_TICKS, skipBetween(home0, home1));
        assertEquals("CLOCK-6: the overworld's time of day moved with the universe: before " + home0.raw()
                + " | after " + home1.raw(), 0L, turnedApart(home0, home1));
        assertEquals("CLOCK-6: a planet's time of day moved with the universe: before " + planet0.raw()
                + " | after " + planet1.raw(), 0L, turnedApart(planet0, planet1));
    }

    /**
     * {@code /time set T} from the console moves the universe forward to the next moment the overworld reads T:
     * the skip is the overworld's time of day, at the moment the command ran, up to T within its day — never
     * backwards.
     */
    @Pins("CLOCK-7")
    @RedWitness(verdict = "set", member = "TimeCommandGuard#onCommand",
            at = "ticks = until.isPresent() ? until.getAsLong() : 0L;", replacedBy = "ticks = amount;",
            fails = "CLOCK-7: /time set", on = "2026-10-24")
    @Test
    public void timeSetMovesTheUniverseToWhenTheWorldReadsIt() throws Exception {
        Reply before = dimTime(0);
        arrange("time set " + SET_TIME);
        Reply after = dimTime(0);

        long day = (long) before.number(DAY);
        long skip = skipBetween(before, after);
        long ran = (long) after.number("totalTime") - (long) before.number("totalTime");
        requireArranged("the world must have aged by a few ticks between the reads: " + ran, ran >= 0L && ran < day);
        boolean landed = false;
        for (long k = 0L; k <= ran && !landed; k++) {
            long readAtCommand = (long) before.number("worldTime") + k;
            landed = skip == Math.floorMod(SET_TIME - readAtCommand, day);
        }
        assertTrue("CLOCK-7: /time set — a skip of " + skip + " is not the overworld's time of day up to "
                + SET_TIME + " at any tick the command could have run in (" + ran + " ran): before " + before.raw()
                + " | after " + after.raw(), landed);
    }

    /** The skip between two reads of one world: how much more the space clock moved than the world aged. */
    private static long skipBetween(Reply before, Reply after) {
        return ((long) after.number("spaceClock") - (long) after.number("totalTime"))
                - ((long) before.number("spaceClock") - (long) before.number("totalTime"));
    }

    /**
     * How far a world's time of day moved apart from the space clock between two reads, within its day: zero
     * when the time of day is the clock plus a constant.
     */
    private static long turnedApart(Reply before, Reply after) {
        long day = (long) before.number(DAY);
        long moved = (long) after.number("worldTime") - (long) before.number("worldTime");
        long clock = (long) after.number("spaceClock") - (long) before.number("spaceClock");
        return Math.floorMod(moved - clock, day);
    }

    /** One world's time of day, age and the space clock at one instant. The verb loads the world it reads. */
    private Reply dimTime(int dim) throws Exception {
        Reply reply = arrange("stellurgytest dim time " + dim);
        requireArranged("the dim probe must read world " + dim + ": " + reply.raw(),
                reply.has("worldTime") && reply.has("spaceClock") && reply.has("totalTime"));
        return reply;
    }

    /** A Stellurgy body world other than the overworld and the station world, from the dim registry. */
    private int aPlanet() throws Exception {
        int stations = read("stellurgytest client space-dim").integer("spaceDimId");
        for (int dim : read("stellurgytest dim list").intArray("stellurgyDimensions")) {
            if (dim != 0 && dim != stations) {
                return dim;
            }
        }
        requireArranged("the default galaxy must register a planet besides the overworld", false);
        return 0;
    }
}
