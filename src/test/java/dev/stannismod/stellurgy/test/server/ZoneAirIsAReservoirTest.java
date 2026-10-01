package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * A compartment's air as a HEAT reservoir: it has a temperature, it has a capacity, and gas arriving
 * from somewhere else mixes into it rather than replacing what was there.
 *
 * <p>The mixing law is the one worth a test, and it is worth stating why the arrangement is lopsided.
 * The calorimeter rule and a plain average agree exactly when the two sides are the same size, so a
 * tidy 50/50 scenario would pass on an implementation that simply split the difference — which is not
 * the rule and is wrong the moment a small bottle is vented into a large room. The room here is
 * several times the gas admitted into it, and the expected answer is computed from the two pressures
 * the probe reports rather than stated.</p>
 *
 * <p><b>What this does NOT cover, and why.</b> D266-4 speaks of zones EXCHANGING air, and there is no
 * such path in the game yet: the recirculator serves one zone, a duct stores nothing, and the only
 * place gas actually arrives from elsewhere is a separator's tank. So the rule is pinned on the one
 * live route, and the seam for the future one is correct by construction — the temperature of
 * arriving gas is a required argument, so a zone-to-zone mover cannot be written without deciding it.
 * </p>
 */
public class ZoneAirIsAReservoirTest extends AbstractSharedServerTest {

    /** The Y and Z every helper here builds on, from this scenario's own site (see {@link #stand}). */
    private int cy;
    private int cz;

    /** Ask for this scenario's site, prove its volume empty, and answer the X its room is centred on. */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(2, 6, what);
        cy = site.y + 2;
        cz = site.z + 2;
        return site.x + 2;
    }

    /** Hot enough that the mix is unmistakable, and nowhere near any threshold this test cares about. */
    private static final int HOT_MILLI_K = 400_000;

    /**
     * Gas arriving at a different temperature mixes by HOW MUCH of each there is, not by halves.
     *
     * <p>A hot room, and oxygen let in from a tank at ordinary temperature. The room must end up
     * between the two and much nearer its own starting point, because there is far more room-air than
     * admitted gas — and the test derives exactly where from the pressures before and after, so the
     * assertion is the law and not a number.</p>
     *
     * <p>red-witnessed: with {@code AirState#mixIn} at {@code double mixed = ((double) here * getTemperatureKelvin() + (double) amountArriving * incomingKelvin)} mixing by the plain average of the two
     * temperatures: "the room must end up at the enthalpy-weighted mean … expected 314.648… K …
     * measured 293.001 K", 2026-09-30. The four premises are arrangements and are not witnessed.</p>
     *
     * <p>Not asserted: that the room is NOT at the plain average, because the second premise puts the
     * expected value more than twice the bound from the average, so any reading the verdict accepts
     * is already more than the bound from it — the check could not go red on its own.</p>
     */
    @Test
    public void gasArrivingMixesByHowMuchOfEachThereIs() throws Exception {
        int cx = stand("a hot room with a combining separator in it");
        buildRoomWithVent(cx, 16000);
        // Oxygen-poor so the governor leaves plenty of headroom to admit into.
        setAir(cx, 790_000, 60_000, 0, HOT_MILLI_K);

        // Everything that enters this room while it is watched arrives at ONE temperature: the
        // separator's tank gas and the vent's top-up are both admitted at `AirState.ambientKelvin()`
        // (`TileGasSeparator`, `TileOxygenVent.replenishOxygen`). So however many admissions there
        // are, the calorimeter rule composes into one: T = (P0·T0 + ΔP·Ta) / (P0 + ΔP), with P the
        // total partial pressure — which is what `AirState.mixIn` weights by. The weights are read as
        // the exact partial pressures, not the centi-atm `airPressure`, which is truncated to
        // hundredths of an atmosphere and would put a percent of error into each weight.
        long tickBefore = WorldCommandFixtures.serverTick();
        Reply before = ventInfo(cx);
        long gasBefore = totalGas(before);
        long tempBefore = before.longInteger("airTempMilliK");
        assertTrue("premise: the room must hold air at all: " + before, gasBefore > 0);

        int ambient = configInt("shipHeatAmbientKelvin");

        runCombinerInto(cx);

        Reply after = ventInfo(cx);
        long ticksWatched = WorldCommandFixtures.serverTick() - tickBefore;
        long gasAfter = totalGas(after);
        long tempAfter = after.longInteger("airTempMilliK");
        assertTrue("premise: the combiner must actually have put gas in (before=" + gasBefore
                + " after=" + gasAfter + "): " + after, gasAfter > gasBefore);

        double t0 = tempBefore / 1000.0D;
        double admitted = gasAfter - gasBefore;
        double expected = (gasBefore * t0 + admitted * ambient) / (gasBefore + admitted);
        double plainAverage = (t0 + ambient) / 2.0D;
        double measured = tempAfter / 1000.0D;

        // The ONLY slack: each `mixIn` rounds the result to a milli-kelvin, half a milli-kelvin per
        // call, and an earlier call's error only shrinks under later mixing. Calls are bounded by
        // admissions: at most one per separator tick — the forced ones plus one per world tick — and
        // one per vent tick, which is one per world tick.
        double bound = 0.0005D * (COMBINER_TICKS + 2L * ticksWatched);
        assertTrue("premise: the rule under test must be distinguishable from doing nothing, by more "
                        + "than the rounding can hide (room " + t0 + " K, arriving " + ambient
                        + " K, expected " + expected + " K, bound " + bound + " K)",
                Math.abs(expected - t0) > bound);
        assertTrue("premise: and from a plain average, by more than twice the rounding — or the "
                        + "verdict below could not tell the rule from splitting the difference (expected "
                        + expected + " K, average " + plainAverage + " K, bound " + bound + " K)",
                Math.abs(expected - plainAverage) > 2.0D * bound);

        assertTrue("the room must end up at the enthalpy-weighted mean of what was there and what "
                        + "arrived: expected " + expected + " K from " + gasBefore + " of air at " + t0
                        + " K meeting " + admitted + " at " + ambient + " K, measured " + measured
                        + " K, allowed " + bound + " K of rounding over " + ticksWatched + " ticks",
                Math.abs(measured - expected) <= bound);
    }

    /** The separator is force-ticked this many times while combining (see {@link #runCombinerInto}). */
    private static final int COMBINER_TICKS = 200;

    /** The room's total partial pressure — the weight `AirState.mixIn` uses. Only these three gases
     *  are ever put in this room, by `setair`, the vent and the separator. */
    private static long totalGas(Reply vent) {
        return vent.longInteger("airN2") + vent.longInteger("airO2") + vent.longInteger("airCO2");
    }

    /**
     * Taking gas OUT leaves the temperature where it was and lowers the capacity.
     *
     * <p>It reads as though removing air should cool the room, and it must not: what is left is the
     * same gas at the same temperature, there is simply less of it. The capacity falling is the other
     * half — without it the assertion would also pass on an implementation where nothing happened at
     * all.</p>
     *
     * <p>red-witnessed: with {@code AirState#draw} at {@code set(gas, partialPressure(gas) - taken);} ({@code draw}) cooling what is left by a percent on
     * every draw: "what is left is the same gas at the same temperature — removing part of a body
     * does not cool the rest: … \"airTempMilliK\":361749", 2026-09-30. The three premises are
     * arrangements and are not witnessed.</p>
     */
    @Test
    public void drawingGasOutLeavesTheTemperatureAndLowersTheCapacity() throws Exception {
        int cx = stand("a hot room with a splitting separator in it");
        buildRoomWithVent(cx, 16000);
        setAir(cx, 790_000, 210_000, 0, HOT_MILLI_K);

        Reply before = ventInfo(cx);
        long tempBefore = before.longInteger("airTempMilliK");
        long capacityBefore = before.longInteger("airHeatCapacity");
        assertEquals("premise: the room must start hot: " + before, HOT_MILLI_K, tempBefore);
        assertTrue("premise: and must have a real capacity to lose: " + before, capacityBefore > 0);

        // The separator's default direction: gas out of the room and into its tank.
        placeSeparator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        forceTick(cx + 1, 200);

        Reply after = ventInfo(cx);
        long capacityAfter = after.longInteger("airHeatCapacity");
        long tempAfter = after.longInteger("airTempMilliK");
        assertTrue("premise: the separator must actually have taken gas out (capacity before="
                        + capacityBefore + " after=" + capacityAfter + "): " + after,
                capacityAfter < capacityBefore);
        assertEquals("what is left is the same gas at the same temperature — removing part of a body "
                + "does not cool the rest: " + after, tempBefore, tempAfter);
    }

    /**
     * Air that is not there has no temperature of its own.
     *
     * <p>A zone pumped down to vacuum must report the ambient every other reader assumes, not the
     * number it was holding when it still had air in it. A stale reading here would hand the failure
     * ladder a hot compartment where there is nothing to be hot.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, in the dry-vent room. AMBIENT — {@code AirState#getTemperatureMilliK} at {@code return getTotalPressure() <= 0L ? ambientKelvin() * 1000 : temperatureMilliK;}'s empty-air rule removed, so the reading is whatever the gas last held: "a zone
     * holding nothing must read ambient, not what it was at when it still had air: … \"airO2\":0 …
     * expected:&lt;293000&gt; but was:&lt;400000&gt;". NO HEAT — {@code AirState#getHeatCapacity} at {@code return getTotalPressure() / PER_PPM * Math.max(0, volumeBlocks) * perBlockAtOneAtm / 1_000_000L;} giving air a
     * heat capacity whatever its pressure: "and must hold no heat at all: … \"airO2\":0 …
     * expected:&lt;0&gt; but was:&lt;760&gt;". The hot-room and empty-room premises are arrangements
     * and are not witnessed.</p>
     */
    @Test
    public void airThatIsNotThereHasNoTemperature() throws Exception {
        int cx = stand("a hot room pumped down to vacuum");
        // A DRY vent: it seals the room into a zone, which is all this needs, and has no oxygen to top
        // the emptied room up with. A fuelled one puts gas back at ambient before the read, and then
        // "reads ambient" is about the gas that arrived rather than about air that is not there.
        buildRoomWithVent(cx, 0);
        setAir(cx, 790_000, 210_000, 0, HOT_MILLI_K);
        Reply hot = ventInfo(cx);
        assertEquals("premise: the room must be hot while it still holds air: " + hot,
                HOT_MILLI_K, hot.longInteger("airTempMilliK"));

        setAir(cx, 0, 0, 0, HOT_MILLI_K);

        Reply empty = ventInfo(cx);
        requireArranged("premise: the room must hold no gas at all when it is read: " + empty,
                totalGas(empty) == 0L);
        int ambient = configInt("shipHeatAmbientKelvin");
        assertEquals("a zone holding nothing must read ambient, not what it was at when it still had "
                + "air: " + empty, ambient * 1000L, empty.longInteger("airTempMilliK"));
        assertEquals("and must hold no heat at all: " + empty, 0L, empty.longInteger("airHeatCapacity"));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * @param ventOxygenMb oxygen put in the vent's tank; 0 leaves it dry, and a dry vent still seals
     *                     the room into a zone but never tops its air up
     */
    private void buildRoomWithVent(int cx, int ventOxygenMb) throws Exception {
        int by = cy, bz = cz;
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by - 1) + " " + (bz - 2)
                + " " + (cx + 2) + " " + by + " " + (bz + 2) + " minecraft:stone");
        for (int yy = by + 1; yy <= by + 2; yy++) {
            arrange("stellurgytest fill 0 " + (cx - 2) + " " + yy + " " + (bz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (bz + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (cx - 1) + " " + yy + " " + (bz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (bz + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by + 3) + " " + (bz - 2)
                + " " + (cx + 2) + " " + (by + 3) + " " + (bz + 2) + " minecraft:stone");

        place(cx, "stellurgy:oxygenVent");
        injectEnergyAt(cx, 1_000_000);
        if (ventOxygenMb > 0) {
            arrange("stellurgytest fluid inject 0 " + cx + " " + cy + " " + cz + " oxygen " + ventOxygenMb);
        }
        forceTick(cx, 1);
        arrange("stellurgytest vent reseal 0 " + cx + " " + cy + " " + cz);
        forceTick(cx, 5);
    }

    /** A separator in combine mode, with oxygen in its tank, run long enough to empty it. */
    private void runCombinerInto(int cx) throws Exception {
        placeSeparator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        arrange("stellurgytest fluid inject 0 " + (cx + 1) + " " + cy + " " + cz + " oxygen 8000");
        Reply flip = ask("stellurgytest block activate 0 " + (cx + 1) + " " + cy + " " + cz + " true");
        assertTrue("sneak-click failed: " + flip, flip.bool("handled"));
        forceTick(cx + 1, COMBINER_TICKS);
    }

    private void placeSeparator(int cx) throws Exception {
        place(cx + 1, "stellurgy:gasSeparator");
    }

    private void place(int x, String block) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + cy + " " + cz + " " + block);
        assertTrue(block + " place failed: " + resp, resp.bool("placed"));
    }

    /** Gases in parts per million of an atmosphere, which is how a room's mix is quoted. */
    private void setAir(int cx, int n2, int o2, int co2, int milliK) throws Exception {
        arrange("stellurgytest vent setair 0 " + cx + " " + cy + " " + cz
                + " " + ppm(n2) + " " + ppm(o2) + " " + ppm(co2) + " " + milliK);
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        arrange("stellurgytest energy inject 0 " + x + " " + cy + " " + cz + " " + amount);
    }

    /** Force-ticks a machine and CHECKS it was there: a missing tile reads exactly like inaction. */
    private void forceTick(int x, int ticks) throws Exception {
        arrange("stellurgytest tile force-tick 0 " + x + " " + cy + " " + cz + " " + ticks);
    }

    private Reply ventInfo(int cx) throws Exception {
        return ask("stellurgytest vent info 0 " + cx + " " + cy + " " + cz);
    }

    private int configInt(String key) throws Exception {
        return arrange("stellurgytest config get " + key).integer("value");
    }
}
