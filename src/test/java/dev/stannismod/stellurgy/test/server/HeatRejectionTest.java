package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * How heat gets off a ship: the radiating cell, and the two things that decide how much it sheds.
 *
 * <p>Both are laws rather than tuned numbers, which is why they can be asserted at all. Area is
 * linear — twice the cells, twice the rejection — and temperature is QUARTIC, so a loop a hundred
 * degrees hotter sheds far more than twice as much. The difference between those two is the entire
 * reason a chiller is worth building later, so a test that could not tell them apart would be
 * pinning nothing.</p>
 *
 * <p>The third scenario is the clearance rule, and it is asserted from the loop's side: a blocked
 * cell must leave the loop's stored energy untouched, not merely report a zero.</p>
 */
public class HeatRejectionTest extends AbstractSharedServerTest {

    /** The row every loop stands on, in open air so a radiator facing up has nothing over it but
     *  sky; from this scenario's own site (see {@link #stand}). */
    private int y;
    private int z;

    /** Half of one step of a figure the probe rounds to thousandths — the rounding it can hide. */
    private static final double HALF_MILLI = 0.0005D;

    /** The second loop of the area scenario stands this far along from the first. */
    private static final int SECOND_LOOP_OFFSET = 10;

    /** Ask for this scenario's site, prove its volume empty, and answer where the first loop starts. */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(9, 3, what);
        y = site.y + 1;
        z = site.z;
        return site.x;
    }

    /** `getStateFromMeta` maps this to a cell radiating UP, so obstructions go straight above. */
    private static final String RADIATOR_FACING_UP = "1";

    /** Both loops are four blocks long, so the same energy in each is the same temperature. */
    private static final int LOOP_LENGTH = 4;

    /**
     * Twice the radiating surface sheds twice the heat. Both loops are built to the same LENGTH and
     * given the same energy, so they sit at the same temperature and the only thing that differs is
     * how much of them is radiator — which is what makes the ratio mean area and nothing else.
     *
     * <p>red-witnessed: with {@code HeatNetwork#rejectHeat} at {@code double gross = cellPowerAt(temperature(stored, capacity));} dividing each cell's gross power by the square
     * root of the loop's cell count: "three cells must shed three times what one does (one=82
     * three=99) expected:&lt;246&gt; but was:&lt;99&gt;", 2026-09-30. The four premises before it
     * are arrangements and are not witnessed.</p>
     * Pins INV-HEAT-07 (Rejection scales linearly with the number of radiating cells, at equal loop capacity and equal stored energy).
     * Pins HEAT-7 (rejection scales linearly with area and with the fourth power of temperature).
     */
    @Test
    public void rejectionScalesWithTheAreaBuilt() throws Exception {
        // Same four blocks in each, one radiator against three.
        int xOne = stand("two loops of one length, one radiating cell against three");
        int xThree = xOne + SECOND_LOOP_OFFSET;
        buildLoop(xOne, 1);
        buildLoop(xThree, 3);
        solve(1);

        Reply one = loopInfo(xOne);
        Reply three = loopInfo(xThree);
        assertEquals("premise: both loops must be the same size: " + one + " | " + three,
                one.longInteger("heatCapacity"), three.longInteger("heatCapacity"));
        assertEquals("premise: one radiating cell on the first loop: " + one, 1, one.integer("radiatingCells"));
        assertEquals("premise: three on the second: " + three, 3, three.integer("radiatingCells"));

        long capacity = one.longInteger("heatCapacity");
        long charge = 100L * capacity; // a hundred kelvin above ambient, in both
        long rejectedByOne = shedInOneTickFrom(xOne, charge);
        long rejectedByThree = shedInOneTickFrom(xThree, charge);
        assertTrue("premise: the single cell must shed something at all, or the ratio below is "
                + "meaningless (shed=" + rejectedByOne + ")", rejectedByOne > 0);
        // EXACT. `HeatNetwork.rejectHeat` truncates per EXCHANGER, `(long) (perCell * cells)`, and each
        // radiator here is an exchanger of one cell at the same loop temperature under the same sky —
        // so the three shares are three copies of the one share, truncation and all. Slack here would
        // pass a surface law that is merely close to linear.
        assertEquals("three cells must shed three times what one does (one=" + rejectedByOne
                        + " three=" + rejectedByThree + ")",
                3L * rejectedByOne, rejectedByThree);
    }

    /**
     * The same cell, a hotter loop. What a cell RADIATES follows the fourth power of its own
     * temperature, so raising the loop from a hundred degrees over ambient to two hundred must do
     * markedly more than double it.
     *
     * <p>What the loop NETS is that minus whatever the environment is putting back into the cell, so
     * the law is recovered by adding the incident flux back on — and stating it that way is what keeps
     * this a pin on the law rather than on a config number. Both halves are measured: the shed comes
     * from the cycle, the flux from the same call's readout, and the only thing the test supplies is
     * the two temperatures it asked for. `T_amb` deliberately does not appear — there is no such term
     * any more, and a test still written around one would be asserting a model the code left behind.</p>
     *
     * <p>red-witnessed: with {@code HeatNetwork#cellPowerAt} at {@code pow4(kelvin) / pow4(reference)}
     * putting a cell's power on the square of its
     * temperature instead of the fourth power: "what the cell radiates must follow the fourth power
     * of its temperature: expected 2.4763785142736783..2.476383626828728 … measured
     * 1.5640392037839332..1.5779011582219171", 2026-09-30. The three premises before it are
     * arrangements and are not witnessed.</p>
     * Pins INV-HEAT-08 (Rejection follows the fourth power of temperature).
     * Pins HEAT-7 (rejection scales linearly with area and with the fourth power of temperature).
     */
    @Test
    public void rejectionFollowsTheFourthPowerOfTemperature() throws Exception {
        int xQuartic = stand("one loop with one radiating cell, charged twice");
        buildLoop(xQuartic, 1);
        solve(1);

        Reply cold = loopInfo(xQuartic);
        long capacity = cold.longInteger("heatCapacity");
        double ambient = cold.longInteger("temperatureMilliK") / 1000.0D;
        assertEquals("premise: a loop that has done nothing holds nothing: " + cold,
                0L, cold.longInteger("heatStored"));

        Reply hundred = cycle(xQuartic, 100L * capacity);
        Reply twoHundred = cycle(xQuartic, 200L * capacity);
        long shedAtHundred = hundred.longInteger("rejected");
        long shedAtTwoHundred = twoHundred.longInteger("rejected");
        // What the outside puts into ONE cell per tick, in thousandths — the other half of the net.
        // Per cell and per tick, and there is one cell here — the same units the shed is in.
        double flux = hundred.longInteger("incidentFluxMilli") / 1000.0D;
        assertEquals("premise: the environment must be the same in both legs, or adding it back is "
                        + "not an identity: " + hundred + " | " + twoHundred,
                hundred.longInteger("incidentFluxMilli"), twoHundred.longInteger("incidentFluxMilli"));

        assertTrue("premise: both legs must shed something (100K=" + shedAtHundred + " 200K="
                + shedAtTwoHundred + ")", shedAtHundred > 0 && shedAtTwoHundred > 0);

        // The only slack is production's own quantisation, carried through as an interval rather
        // than guessed as a percentage. A shed is `(long)` of the true net, so the true net lies in
        // [shed, shed + 1); the flux readout is `Math.round(flux * 1000)`, so ±0.0005; and ambient was
        // read off a milli-kelvin figure, so ±0.0005 K. The measured interval and the predicted one
        // must overlap. The loop temperatures themselves are exact: `HeatNetwork.temperature` is
        // ambient + stored / capacity, and the charges are whole multiples of the capacity.
        double lo = (shedAtTwoHundred + flux - HALF_MILLI) / (shedAtHundred + 1 + flux + HALF_MILLI);
        double hi = (shedAtTwoHundred + 1 + flux + HALF_MILLI) / (shedAtHundred + flux - HALF_MILLI);
        // The fourth-power ratio falls as ambient rises, so the high ambient bounds it from below.
        double expectedLo = pow4((ambient + HALF_MILLI + 200.0D) / (ambient + HALF_MILLI + 100.0D));
        double expectedHi = pow4((ambient - HALF_MILLI + 200.0D) / (ambient - HALF_MILLI + 100.0D));
        assertTrue("what the cell radiates must follow the fourth power of its temperature: expected "
                        + expectedLo + ".." + expectedHi + " from " + (ambient + 100.0D) + " K and "
                        + (ambient + 200.0D) + " K, measured " + lo + ".." + hi + " (shed "
                        + shedAtHundred + " and " + shedAtTwoHundred + " against an incident flux of "
                        + flux + ") — a linear law would put it near 2, and nothing here allows that",
                expectedLo <= hi && lo <= expectedHi);
    }

    /**
     * A cell with something in front of it sheds nothing, and says where the obstruction is.
     *
     * <p>Asserted from the LOOP's side as well as the cell's: the energy must still be there after
     * the tick. A cell that reported zero while the heat quietly left anyway would pass a test that
     * only read the cell.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. SHEDS NOTHING — {@code TileHeatRadiator#getExchangeCells} at {@code return !closed && getObstruction() == 0 ? 1 : 0;} ignoring the obstruction: "nothing may be shed by an obstructed cell: …
     * expected:&lt;0&gt; but was:&lt;82&gt;". STILL IN THE LOOP — {@code HeatNetwork#tickThermodynamics} at {@code stored = Math.max(0L, stored - rejected);} losing one
     * unit a tick outside rejection: "and the energy must still be in the loop, not quietly gone: …
     * expected:&lt;8000&gt; but was:&lt;7999&gt;". ONE BLOCK AWAY — {@code TileHeatRadiator#getObstruction} at {@code return HullClearance.obstructionDistance(world, pos, getRadiatingFacing(),}
     * reporting twice the distance: "the cell must report the obstruction one block away … expected:&lt;1&gt;
     * but was:&lt;2&gt;". NO RADIATING SURFACE — {@code TileHeatRadiator#getExchangeCells} at {@code return !closed && getObstruction() == 0 ? 1 : 0;} counting the blocked
     * cell and {@code TileHeatRadiator#exchange} at {@code rejectedThisTick = amount;} shedding nothing through it: "and must count as no radiating surface: …
     * expected:&lt;0&gt; but was:&lt;1&gt;". STILL SEES THE MACHINE — {@code HeatNetwork#exchangerCount} at {@code if (node instanceof IHeatExchanger)}
     * counting only working exchangers: "the loop must still see the machine — it is obstructed, not
     * gone: … expected:&lt;1&gt; but was:&lt;0&gt;". NO WORKING SURFACE — {@code HeatNetwork#radiatingCells} at {@code cells += Math.max(0, ((IHeatExchanger) node).getExchangeCells());}
     * counting every exchanger as a cell: "with no working surface between them: …
     * expected:&lt;0&gt; but was:&lt;1&gt;". The three premises before the obstruction is placed are
     * arrangements and are not witnessed.</p>
     * Pins INV-HEAT-09 (An obstructed cell sheds NOTHING and reports the obstruction's distance, and the loop keeps its energy).
     * Pins HEAT-9 (an obstructed radiator sheds nothing and reports where the block is).
     */
    @Test
    public void anObstructedCellShedsNothingAndSaysWhereTheBlockIs() throws Exception {
        int xBlocked = stand("one loop whose radiating cell is later obstructed");
        buildLoop(xBlocked, 1);
        solve(1);
        long capacity = loopInfo(xBlocked).longInteger("heatCapacity");
        int radiatorX = xBlocked + LOOP_LENGTH - 1;

        // Control first, with the sky still clear: the same rig must genuinely shed.
        Reply clear = heatRead(radiatorX);
        requireArranged("premise: the cell must be a radiator: " + clear, clear.bool("isRadiator"));
        assertEquals("premise: nothing above it yet: " + clear, 0, clear.integer("obstruction"));
        long shedWhileClear = shedInOneTickFrom(xBlocked, 100L * capacity);
        assertTrue("premise: an unobstructed cell must genuinely shed, or the zero below is not "
                + "evidence of anything (shed=" + shedWhileClear + ")", shedWhileClear > 0);

        // Now put a block in its way, one above — inside any clearance the config can be set to.
        Reply placed = arrange("stellurgytest place 0 " + radiatorX + " " + (y + 1) + " " + z + " minecraft:stone");
        assertTrue("obstruction place failed: " + placed, placed.bool("placed"));

        long charge = 100L * capacity;
        Reply cycled = cycle(xBlocked, charge);
        assertEquals("nothing may be shed by an obstructed cell: " + cycled,
                0L, cycled.longInteger("rejected"));
        assertEquals("and the energy must still be in the loop, not quietly gone: " + cycled,
                charge, cycled.longInteger("heatStored"));

        Reply blockedCell = heatRead(radiatorX);
        assertEquals("the cell must report the obstruction one block away, so a player can go and "
                + "find it: " + blockedCell, 1, blockedCell.integer("obstruction"));
        assertEquals("and must count as no radiating surface: " + blockedCell,
                0, blockedCell.integer("radiatingCells"));

        assertEquals("the loop must still see the machine — it is obstructed, not gone: " + cycled,
                1, cycled.integer("exchangers"));
        assertEquals("with no working surface between them: " + cycled, 0, cycled.integer("radiatingCells"));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * A straight run of {@value #LOOP_LENGTH} blocks: pipes first, then radiating cells at the far
     * end, all facing up. Every loop is the same length whatever the mix, because a comparison
     * between two loops is only about area if their capacity is equal.
     */
    private void buildLoop(int x0, int radiators) throws Exception {
        for (int i = 0; i < LOOP_LENGTH - radiators; i++) {
            place(x0 + i, "stellurgy:heatPipe", null);
        }
        for (int i = LOOP_LENGTH - radiators; i < LOOP_LENGTH; i++) {
            place(x0 + i, "stellurgy:heatRadiator", RADIATOR_FACING_UP);
        }
        // `subnet info` answers ok for a position in no network too, with every quantity zero, so
        // "the run is built" is `inNetwork` and not `ok`.
        Reply info = loopInfo(x0);
        requireArranged("the run must be built before it is solved: " + info, info.bool("inNetwork"));
    }

    /**
     * Charge the loop to a known energy and advance exactly one tick, in ONE probe call; answers
     * what left.
     *
     * <p>The single call is the whole point. Between probe calls the world ticks normally and the
     * heat domain ticks with it, so charging in one command and measuring in the next measures
     * whatever survived some natural ticks — and two loops with different radiating area lose
     * different amounts in that gap, which corrupts precisely the ratio this test is about.</p>
     */
    private long shedInOneTickFrom(int x0, long charge) throws Exception {
        return cycle(x0, charge).longInteger("rejected");
    }

    /** The whole readout of one charged tick, for a caller that needs more than what left. */
    private Reply cycle(int x0, long charge) throws Exception {
        Reply cycled = arrange("stellurgytest heat cycle 0 " + x0 + " " + y + " " + z + " " + charge + " 1");
        requireArranged("heat cycle found no loop at " + x0 + ": " + cycled, cycled.bool("inLoop"));
        assertEquals("premise: the loop must have been charged with exactly what was asked: " + cycled,
                charge, cycled.longInteger("charged"));
        return cycled;
    }

    private Reply heatRead(int x) throws Exception {
        return arrange("stellurgytest heat read 0 " + x + " " + y + " " + z);
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

    private static double pow4(double v) {
        return v * v * v * v;
    }
}
