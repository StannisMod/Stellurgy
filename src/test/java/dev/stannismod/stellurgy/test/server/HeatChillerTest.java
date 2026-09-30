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
 *
 * <p>Every verdict here is read as ENERGY — what a loop is holding, what the chiller's battery is
 * holding — and never as the per-tick figures the loop publishes about its own exchanges. Those are
 * counted beside the transfer rather than by it, so a pump that reported heat plus work and deposited
 * a tenth of it would satisfy them.</p>
 */
public class HeatChillerTest extends AbstractSharedServerTest {

    /** The row the rig stands on, from this scenario's own site (see {@link #stand}). */
    private int y;
    private int z;

    /**
     * Ask for this scenario's site, prove the volume empty, and answer where the cold run starts. The
     * rig is seven blocks long along X.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(2, 3, what);
        y = site.y + 1;
        z = site.z;
        return site.x;
    }

    /** `EnumFacing.getIndex()`: 5 is EAST, so the hot side is the +X end of the run. */
    private static final String CHILLER_FACING_EAST = "5";

    /** Cold run, then the chiller, then the hot run: three pipes each side. */
    private static final int RUN_LENGTH = 3;

    /**
     * The clause: the hot loop gains what the cold loop lost PLUS what the chiller's battery paid.
     *
     * <p>All three are stores, read before and after one charged tick of the cold loop: the cold loop's
     * energy in the same call that ran the tick, the hot loop's and the battery's in the calls around
     * it. Neither loop has any other way in or out — no radiating cell, no machine, no sink, no cabin —
     * and the cold loop is charged with no more than one tick moves, so the world's own ticks between
     * the calls find it empty and move nothing.</p>
     *
     * <p>Before that, the machine's mass: a chiller counts as thermal mass of the loop on its HOT face
     * and of no other. Each half is read as the loop's capacity before the chiller was placed against
     * the same loop's capacity after, so a leak onto the cold loop reds the cold reading on its own,
     * whatever the hot one does.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NONE OF THE MASS — {@code
     * HeatNetwork:343} and {@code :352} both opened, so the chiller's mass joins every loop it
     * touches: "the loop the chiller merely draws FROM carries none of the machine's mass: …
     * expected:&lt;60&gt; but was:&lt;260&gt;". ITS OWN MASS — {@code HeatNetwork:245} leaving a
     * bolted chiller out of the loop's capacity: "a chiller bolted onto the hot loop must add its own
     * thermal mass to it … (before=60): … \"heatCapacity\":60". THE CLAUSE — {@code HeatNetwork:452}
     * depositing a tenth of heat plus work: "THE CLAUSE: the hot loop gains what the cold loop lost
     * PLUS the work … (cold lost 6000, battery paid 240, hot gained 624) expected:&lt;6240&gt; but
     * was:&lt;624&gt;". The premises are arrangements and are not witnessed.</p>
     */
    @Test
    public void theHotLoopReceivesTheHeatPlusTheWork() throws Exception {
        int x0 = stand("two coolant loops with a powered chiller between them");
        buildRuns(x0);
        solve(2);
        long coldBefore = loopInfo(coldAnchor(x0)).longInteger("heatCapacity");
        long hotBefore = loopInfo(hotAnchor(x0)).longInteger("heatCapacity");
        requireArranged("premise: both runs must be loops with mass before the chiller exists (cold="
                + coldBefore + " hot=" + hotBefore + ")", coldBefore > 0 && hotBefore > 0);

        placeChiller(x0);
        solve(2);
        Reply cold = loopInfo(coldAnchor(x0));
        Reply hot = loopInfo(hotAnchor(x0));
        assertEquals("premise: the cold run must be its own loop: " + cold,
                RUN_LENGTH, cold.integer("members"));
        assertEquals("premise: and the hot run another — a chiller between them must NOT have joined "
                + "them into one: " + hot, RUN_LENGTH, hot.integer("members"));
        assertEquals("premise: both must see the chiller beside them: " + cold, 1, cold.integer("pumps"));
        assertEquals("premise: from the hot side too: " + hot, 1, hot.integer("pumps"));

        assertEquals("the loop the chiller merely draws FROM carries none of the machine's mass: "
                + cold, coldBefore, cold.longInteger("heatCapacity"));
        assertTrue("a chiller bolted onto the hot loop must add its own thermal mass to it — the hot "
                        + "side has to climb more slowly than its pipes alone would explain (before="
                        + hotBefore + "): " + hot,
                hot.longInteger("heatCapacity") > hotBefore);

        powerChiller(x0);
        // The charging verb zeroes a bolted machine's share before it ticks, and the chiller's share
        // is part of the HOT loop's energy: only a hot loop holding nothing loses nothing to that.
        long hotStart = loopInfo(hotAnchor(x0)).longInteger("heatStored");
        requireArranged("premise: the hot loop must start holding nothing (" + hotStart + ")",
                hotStart == 0L);
        long batteryStart = chillerEnergy(x0);

        long charge = 100L * cold.longInteger("heatCapacity");
        Reply cycled = cycle(coldAnchor(x0), charge, 1);
        // The clause compares one tick's take against a hot loop read LATER, so heat the cold loop
        // still held would be pumped in between and counted on one side only.
        requireArranged("premise: one tick must have emptied the cold loop, or the reads below span "
                + "different intervals: " + cycled, cycled.longInteger("heatStored") == 0L);
        long takenFromCold = charge - cycled.longInteger("heatStored");
        long hotEnd = loopInfo(hotAnchor(x0)).longInteger("heatStored");
        long work = batteryStart - chillerEnergy(x0);

        assertTrue("premise: the chiller must have taken something off the cold loop: " + cycled,
                takenFromCold > 0);
        assertTrue("premise: and paid for it out of its battery (" + work + ")", work > 0);
        assertEquals("THE CLAUSE: the hot loop gains what the cold loop lost PLUS the work — a pump whose "
                        + "own work does not join the hot side has invented energy from nowhere (cold lost "
                        + takenFromCold + ", battery paid " + work + ", hot gained " + (hotEnd - hotStart)
                        + ")",
                takenFromCold + work, hotEnd - hotStart);
    }

    /**
     * Nobody sets the hot loop's temperature: energy accumulates in it, and a chiller keeps adding to
     * it after it is already the hotter of the two — which is what the electricity buys, and the whole
     * reason the tier exists, since rejection is quartic in temperature.
     *
     * <p>So the last leg charges the cold loop to HALF of the hot loop's measured rise above ambient,
     * which puts it below the hot loop, and requires heat to go on leaving it. The cold loop has no way
     * out but the chiller, so the energy it lost is energy the chiller moved up the gradient.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. TAKES NOTHING — {@code
     * TileHeatChiller:92} and {@code :104} both answering as if powered: "an unpowered chiller takes
     * nothing out of the loop it draws from: … expected:&lt;6000&gt; but was:&lt;0&gt;". WITH POWER
     * — {@code TileHeatChiller:94} offering no throughput: "the same chiller with power must take
     * heat out, or the reading above measured nothing: … \"heatStored\":6000". UP THE GRADIENT — a
     * line after {@code HeatNetwork:435} skipping a pump whose hot side is the hotter: "heat must go
     * on leaving the cold loop although the hot loop is already hotter (cold charged to 886 at half
     * the hot loop's rise; hot 322538 milliK) … \"heatStored\":886". The premises are arrangements and
     * are not witnessed.</p>
     *
     * <p>What the unpowered chiller PAYS is not asserted: it moves heat exactly when work is paid, so
     * a payment is a transfer and TAKES NOTHING already reads it.</p>
     */
    @Test
    public void theHotLoopIsHotterBecauseEnergyAccumulatesInIt() throws Exception {
        int x0 = stand("two coolant loops with a chiller that is powered only later");
        buildRuns(x0);
        placeChiller(x0);
        solve(2);
        Reply coldEmpty = loopInfo(coldAnchor(x0));
        long capacity = coldEmpty.longInteger("heatCapacity");
        long ambientMilliK = coldEmpty.longInteger("temperatureMilliK");
        requireArranged("premise: the cold loop must start holding nothing, so it reads ambient: "
                + coldEmpty, coldEmpty.longInteger("heatStored") == 0L);

        long charge = 100L * capacity;
        Reply starved = cycle(coldAnchor(x0), charge, 1);
        assertEquals("premise: the loop must see the chiller: " + starved, 1, starved.integer("pumps"));
        assertEquals("an unpowered chiller takes nothing out of the loop it draws from: " + starved,
                charge, starved.longInteger("heatStored"));

        powerChiller(x0);
        Reply driven = cycle(coldAnchor(x0), charge, 1);
        assertTrue("the same chiller with power must take heat out, or the reading above measured "
                + "nothing: " + driven, driven.longInteger("heatStored") < charge);

        Reply hot = loopInfo(hotAnchor(x0));
        long gradientCharge = capacity * (hot.longInteger("temperatureMilliK") - ambientMilliK) / 2000L;
        requireArranged("premise: the hot loop must be above ambient, or there is no gradient to work "
                + "against: " + hot, gradientCharge > 0);
        Reply set = arrange("stellurgytest heat set 0 " + coldAnchor(x0) + " " + y + " " + z + " "
                + gradientCharge);
        requireArranged("premise: the cold loop's block must take the charge: " + set,
                set.bool("isLoopBlock") && set.longInteger("heatStored") == gradientCharge);
        solve(1);
        Reply coldAfter = loopInfo(coldAnchor(x0));
        assertTrue("heat must go on leaving the cold loop although the hot loop is already hotter "
                        + "(cold charged to " + gradientCharge + " at half the hot loop's rise; hot "
                        + hot.longInteger("temperatureMilliK") + " milliK) — a pump that only works "
                        + "downhill is not a chiller: " + coldAfter,
                coldAfter.longInteger("heatStored") < gradientCharge);
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * The two runs, in a straight line along X with one block of air between them where the chiller
     * goes. Pipes only: a radiating cell would be a way out of the hot loop that the energy readings
     * above would have to account for.
     */
    private void buildRuns(int x0) throws Exception {
        for (int i = 0; i < RUN_LENGTH; i++) {
            place(x0 + i, "stellurgy:heatPipe", null);
            place(x0 + RUN_LENGTH + 1 + i, "stellurgy:heatPipe", null);
        }
    }

    /**
     * The chiller in the gap, facing east, so its hot side is the far run and its cold side the near
     * one — and because it is not a network node, the two runs stay two loops with it between them.
     */
    private void placeChiller(int x0) throws Exception {
        place(x0 + RUN_LENGTH, "stellurgy:heatChiller", CHILLER_FACING_EAST);
    }

    private int coldAnchor(int x0) {
        return x0;
    }

    private int hotAnchor(int x0) {
        return x0 + RUN_LENGTH + 1;
    }

    private void powerChiller(int x0) throws Exception {
        arrange("stellurgytest energy inject 0 " + (x0 + RUN_LENGTH) + " " + y + " " + z + " 100000000");
    }

    /**
     * What the chiller's battery holds: the work it pays comes out of here and nowhere else. The verb
     * answers no {@code ok}; a reply that is not a battery reading carries no {@code hasEnergy:true}.
     */
    private long chillerEnergy(int x0) throws Exception {
        Reply stored = ask("stellurgytest energy stored 0 " + (x0 + RUN_LENGTH) + " " + y + " " + z);
        requireArranged("premise: the chiller must expose its battery: " + stored, stored.bool("hasEnergy"));
        return stored.longInteger("energyStored");
    }

    /** Charge a loop and advance it, atomically — the world ticks between probe calls. */
    private Reply cycle(int x, long charge, int ticks) throws Exception {
        Reply resp = arrange("stellurgytest heat cycle 0 " + x + " " + y + " " + z + " " + charge + " " + ticks);
        requireArranged("heat cycle found no loop at " + x + ": " + resp, resp.bool("inLoop"));
        requireArranged("premise: the loop must hold exactly what was asked: " + resp,
                charge == resp.longInteger("charged"));
        return resp;
    }

    private void place(int x, String block, String meta) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + y + " " + z + " " + block
                + (meta == null ? "" : " " + meta));
        requireArranged(block + " place failed at " + x + ": " + resp, resp.bool("placed"));
    }

    private void solve(int ticks) throws Exception {
        Reply solved = arrange("stellurgytest subnet solve heat 0 " + ticks);
        requireArranged("solve failed: " + solved, ticks == solved.integer("ticksSolved"));
    }

    private Reply loopInfo(int x) throws Exception {
        return ask("stellurgytest subnet info heat 0 " + x + " " + y + " " + z);
    }
}
