package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * The chiller: two coolant loops with a heat pump between them, and the one clause a pump breaks
 * without anything complaining.
 *
 * <p>The ship's machines heat the cold loop; the chiller moves that heat into the hot loop and pays
 * electricity; the hot loop therefore runs hot, and radiated power is quartic in temperature, so its
 * radiators shed several times what the cold loop's could. <b>Nobody sets the hot loop's
 * temperature.</b> Energy piles up in it against its own capacity and the temperature follows — which
 * is what makes it a real reservoir rather than a number added to another number.</p>
 *
 * <p>What the pump costs joins the HOT side: the hot loop receives the heat PLUS the work, and only
 * the heat comes off the cold one. A pump implemented the obvious way moves `Q` and delivers `Q`,
 * which looks right in every readout and hands the player free thermodynamics.</p>
 */
public class HeatChillerTest extends AbstractSharedServerTest {

    /** The row the rig stands on, from this scenario's own site (see {@link #stand}). */
    private int y;
    private int z;

    /**
     * Ask for this scenario's site in open air, so a radiating cell has nothing over it, prove the
     * volume empty, and answer where the cold run starts. The rig is seven blocks long along X.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(2, 3, what);
        y = site.y + 1;
        z = site.z;
        return site.x;
    }

    /** `EnumFacing.getIndex()`: 5 is EAST, so the hot side is the +X end of the run. */
    private static final String CHILLER_FACING_EAST = "5";
    private static final String RADIATOR_FACING_UP = "1";

    /** Cold run, then the chiller, then the hot run: three pipes each side. */
    private static final int COLD_LENGTH = 3;

    /**
     * The clause, as three numbers from ONE tick of ONE loop: what came off the cold side, what was
     * handed to the hot side, and what was paid. The first plus the last must equal the middle.
     *
     * <p>Read from the cold loop deliberately. The receiving loop is a separate component and is solved
     * in whatever order the solver reaches it, so a test that compared one loop's tick against the
     * other's would be measuring the visit order as much as the physics. The hot loop's own arrival
     * figure is asserted too, as an independent witness that the energy really landed.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. OWN THERMAL MASS — {@code
     * HeatNetwork:245} leaving a bolted chiller out of the loop's capacity: "a chiller bolted onto the
     * hot loop must add its own thermal mass to it … (cold=60 hot=60)". HOLDING — {@code
     * HeatNetwork:452} depositing nothing in the hot loop: "the hot loop must be HOLDING the energy
     * that was handed to it (delivered=6240): … \"heatStored\":0". Not witnessed: ONLY TO THE HOT SIDE
     * — the chiller's mass leaking onto the cold loop ({@code HeatNetwork:343} and {@code :352} both
     * opened) reds OWN THERMAL MASS first, "(cold=260 hot=260)", because the two runs are equal and a
     * leak makes them tie; only a change to the pipe's own capacity reaches this one. THE CLAUSE —
     * it compares the {@code delivered} figure, which is counted apart from what is deposited: with
     * {@code HeatNetwork:452} depositing a tenth of heat plus work, the whole method stays green.
     * ABOVE AMBIENT — follows from HOLDING, since a loop's temperature is ambient plus stored over
     * capacity. The four premises at its head and the two after the cycle are arrangements and are
     * not witnessed.</p>
     */
    @Test
    public void theHotLoopReceivesTheHeatPlusTheWork() throws Exception {
        int x0 = stand("two coolant loops with a powered chiller between them");
        buildTwoLoops(x0);
        solve(2);

        Reply cold = loopInfo(coldAnchor(x0));
        Reply hot = loopInfo(hotAnchor(x0));
        assertEquals("premise: the cold run must be its own loop: " + cold,
                COLD_LENGTH, cold.integer("members"));
        assertEquals("premise: and the hot run another — a chiller between them must NOT have joined "
                + "them into one: " + hot, COLD_LENGTH, hot.integer("members"));
        assertEquals("premise: both must see the chiller beside them: " + cold, 1, cold.integer("pumps"));
        assertEquals("premise: from the hot side too: " + hot, 1, hot.integer("pumps"));

        // The chiller's own metal counts as the HOT loop's thermal mass, and only the hot loop's: it is
        // a lump of refrigerant in contact with that coolant. Both runs are the same length, so if the
        // machine's mass were being ignored the two capacities would simply match.
        long coldCapacity = cold.longInteger("heatCapacity");
        long hotCapacity = hot.longInteger("heatCapacity");
        assertTrue("a chiller bolted onto the hot loop must add its own thermal mass to it — the hot "
                        + "side has to climb more slowly than its pipes alone would explain (cold="
                        + coldCapacity + " hot=" + hotCapacity + ")",
                hotCapacity > coldCapacity);
        assertEquals("and only to the hot side — the loop it merely draws FROM carries none of the "
                        + "machine: " + cold, COLD_LENGTH * 20L, coldCapacity);

        powerChiller(x0);
        long charge = 100L * coldCapacity;
        // ONE tick, on a tick where the loop actually holds heat. These are per-tick figures: run the
        // loop dry over many ticks and the last one reports zeros, which says nothing about the pump.
        Reply cycled = cycle(coldAnchor(x0), charge, 1);

        long movedOut = cycled.longInteger("pumpedOut");
        long delivered = cycled.longInteger("delivered");
        long work = cycled.longInteger("work");

        assertTrue("premise: the chiller must have shifted something: " + cycled, movedOut > 0);
        assertTrue("premise: and paid for it: " + cycled, work > 0);
        assertEquals("THE CLAUSE: the hot loop receives the heat PLUS the work — a pump whose own work "
                + "does not join the hot side has invented energy from nowhere: " + cycled,
                delivered, movedOut + work);

        // The receiving end, independently — witnessed by the hot loop's STATE and not by a per-tick
        // counter. `pumpedIn` is drained on the hot loop's own tick, and the world ticks between probe
        // calls, so by the time a second command can read it the figure is legitimately zero again.
        // What is durable is that the energy is sitting there.
        Reply hotAfter = loopInfo(hotAnchor(x0));
        assertTrue("the hot loop must be HOLDING the energy that was handed to it (delivered="
                        + delivered + "): " + hotAfter, hotAfter.longInteger("heatStored") > 0);
        assertTrue("and be above ambient because of it: " + hotAfter,
                hotAfter.longInteger("temperatureMilliK") > 1000L * ambientKelvinFrom(cold));
    }

    /**
     * Nobody sets the hot loop's temperature: it is what its own capacity makes of the energy it has
     * been given. So a chiller run for a while must leave the hot loop measurably hotter than the cold
     * one — which is the whole reason the tier exists, since rejection is quartic in temperature.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. SHIFTS NOTHING — {@code
     * TileHeatChiller:92} and {@code :104} both answering as if powered: "an unpowered chiller shifts
     * nothing: … expected:&lt;0&gt; but was:&lt;6000&gt;". WITH POWER — {@code TileHeatChiller:94}
     * offering no throughput: "the same chiller with power must shift heat, or the zeros above
     * measured nothing: … \"pumpedOut\":0". HOTTER THAN AMBIENT — {@code HeatNetwork:452} depositing
     * nothing in the hot loop: "the hot loop must be hotter than it was left at ambient (293000 →
     * 293000)". Not witnessed: PAYS NOTHING — the pump moves heat exactly when it is paid, so any
     * fault that pays reds SHIFTS NOTHING first. HOTTER THAN THE COLD LOOP — one tick of chiller
     * throughput is the whole 100 K charge, so the cold loop reads ambient after the cycle and this
     * follows from HOTTER THAN AMBIENT: with {@code HeatNetwork:452} depositing a tenth, it stays
     * green. The premise at its head is an arrangement and is not witnessed.</p>
     */
    @Test
    public void theHotLoopIsHotterBecauseEnergyAccumulatesInIt() throws Exception {
        int x0 = stand("two coolant loops with a chiller that is powered only later");
        buildTwoLoops(x0);
        solve(2);
        long capacity = loopInfo(coldAnchor(x0)).longInteger("heatCapacity");

        // An unpowered chiller first: it must shift nothing, and the hot loop must stay at ambient.
        Reply starved = cycle(coldAnchor(x0), 100L * capacity, 1);
        assertEquals("premise: the loop must see the chiller: " + starved, 1, starved.integer("pumps"));
        assertEquals("an unpowered chiller shifts nothing: " + starved, 0L, starved.longInteger("pumpedOut"));
        assertEquals("and pays nothing: " + starved, 0L, starved.longInteger("work"));
        long hotAmbient = loopInfo(hotAnchor(x0)).longInteger("temperatureMilliK");

        // Power it and run ONE tick: the transfer is a per-tick figure and must be read on a tick
        // where the cold loop still held something. What the hot loop does with the energy afterwards
        // is a STATE, and that is what the rest of this test reads.
        powerChiller(x0);
        Reply driven = cycle(coldAnchor(x0), 100L * capacity, 1);
        assertTrue("the same chiller with power must shift heat, or the zeros above measured nothing: "
                + driven, driven.longInteger("pumpedOut") > 0);

        Reply hotAfter = loopInfo(hotAnchor(x0));
        long hotNow = hotAfter.longInteger("temperatureMilliK");
        assertTrue("the hot loop must be hotter than it was left at ambient (" + hotAmbient + " → "
                + hotNow + "): " + hotAfter, hotNow > hotAmbient);
        assertTrue("and hotter than the cold loop it is fed from — the pump works AGAINST the gradient, "
                        + "which is what its electricity buys: " + hotAfter + " | " + driven,
                hotNow > driven.longInteger("temperatureMilliK"));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * Cold run, chiller, hot run, in a straight line along X. The chiller faces east, so its hot side
     * is the far run and its cold side the near one — and because it is not a network node, the two
     * runs stay two loops with it sitting between them.
     */
    private void buildTwoLoops(int x0) throws Exception {
        for (int i = 0; i < COLD_LENGTH; i++) {
            place(x0 + i, "stellurgy:heatPipe", null);
        }
        place(x0 + COLD_LENGTH, "stellurgy:heatChiller", CHILLER_FACING_EAST);
        // The hot run: two pipes and a radiating cell, so it can actually shed what it is given.
        place(x0 + COLD_LENGTH + 1, "stellurgy:heatPipe", null);
        place(x0 + COLD_LENGTH + 2, "stellurgy:heatPipe", null);
        place(x0 + COLD_LENGTH + 3, "stellurgy:heatRadiator", RADIATOR_FACING_UP);
    }

    private int coldAnchor(int x0) {
        return x0;
    }

    private int hotAnchor(int x0) {
        return x0 + COLD_LENGTH + 1;
    }

    private void powerChiller(int x0) throws Exception {
        arrange("stellurgytest energy inject 0 " + (x0 + COLD_LENGTH) + " " + y + " " + z + " 100000000");
    }

    /** Charge a loop and advance it, atomically — the world ticks between probe calls. */
    private Reply cycle(int x, long charge, int ticks) throws Exception {
        Reply resp = arrange("stellurgytest heat cycle 0 " + x + " " + y + " " + z + " " + charge + " " + ticks);
        requireArranged("heat cycle found no loop at " + x + ": " + resp, resp.bool("inLoop"));
        assertEquals("premise: the loop must hold exactly what was asked: " + resp,
                charge, resp.longInteger("charged"));
        return resp;
    }

    private void place(int x, String block, String meta) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + y + " " + z + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + ": " + resp, resp.bool("placed"));
    }

    private void solve(int ticks) throws Exception {
        Reply solved = arrange("stellurgytest subnet solve heat 0 " + ticks);
        assertEquals("solve failed: " + solved, ticks, solved.integer("ticksSolved"));
    }

    private Reply loopInfo(int x) throws Exception {
        return ask("stellurgytest subnet info heat 0 " + x + " " + y + " " + z);
    }

    /** Ambient in kelvin, read off a loop that is holding nothing rather than restated as a number. */
    private static long ambientKelvinFrom(Reply coldLoopWhileEmpty) {
        return coldLoopWhileEmpty.longInteger("temperatureMilliK") / 1000L;
    }
}
