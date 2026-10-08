package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;

/**
 * The coolant loop as a physical body: a machine's waste heat goes into the pipes touching it, and
 * how hot they get is decided by how much pipe there is.
 *
 * <p>Both scenarios run the same rig — a life-support plant serving a stale room, with a run of
 * coolant pipe welded to it — and differ only in the LENGTH of that run. That is the point: the
 * second scenario is the first one's discriminator, because "the ship got warm" is also what a loop
 * with no thermal mass at all would report, and only a loop whose capacity is real answers
 * differently when you build more of it.</p>
 */
public class HeatLoopTest extends AbstractSharedServerTest {

    /** The Y and Z every helper here builds on, from this scenario's own site (see {@link #stand}). */
    private int cy;
    private int cz;

    /**
     * The second rig of a two-rig scenario stands this far along from the first: the short rig ends
     * seven blocks past its room's centre, and the long rig's room begins two before its own, so this
     * leaves one block of air between them.
     */
    private static final int SECOND_RIG_OFFSET = 11;

    /**
     * Ask for this scenario's site, prove its volume empty, and answer the X its (first) room is
     * centred on. Wide enough for two rigs side by side, the second a long one.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(18, 6, what);
        cy = site.y + 2;
        cz = site.z + 2;
        return site.x + 2;
    }
    /** Three pipes on the short loop, six on the long one — the ratio the second scenario reads. */
    private static final int SHORT_LOOP_PIPES = 3;
    private static final int LONG_LOOP_PIPES = 6;
    /** Long enough for the plant to spend a visible amount of power; well inside the room's CO2. */
    private static final int SOLVE_TICKS = 300;

    /**
     * A machine cooled by a loop heats it. Nothing rejects heat yet, so the only place the energy
     * can be is in the pipes — which is exactly what a ship with no radiators should experience.
     *
     * <p>red-witnessed: with {@code HeatNetwork#collectGeneration} at {@code if (pending > 0)} collecting no machine's pending heat: "the
     * plant's waste heat must end up in the loop it touches (stored=0)", 2026-09-30. The three
     * premises at its head are arrangements and are not witnessed.</p>
     *
     * <p>No temperature is asserted: a loop's temperature is ambient plus stored over capacity, so
     * "hotter than it started" is this same verdict read through a division.</p>
     * Pins INV-HEAT-01 (A machine's waste heat ends up in the loop touching it, and raises its temperature above ambient).
     */
    @Test
    public void aMachineOnACoolantLoopWarmsIt() throws Exception {
        int cxSolo = stand("a life-support plant with a short coolant loop welded to it");
        buildRig(cxSolo, SHORT_LOOP_PIPES);

        // One tick to let the loop find itself, taken while the plant still has no power: an
        // unpowered plant does no work, so this baseline is a cold loop and not a slightly warm one.
        solve(1);
        Reply cold = loopInfo(cxSolo + 5);
        assertEquals("premise: the pipes must have formed one loop: " + cold,
                SHORT_LOOP_PIPES, cold.integer("members"));
        assertEquals("premise: every one of them is transport for the loop: " + cold,
                SHORT_LOOP_PIPES, cold.integer("cables"));
        assertEquals("premise: a loop whose machine has not run holds nothing: " + cold,
                0L, cold.longInteger("heatStored"));

        powerPlant(cxSolo);
        solve(SOLVE_TICKS);

        Reply warm = loopInfo(cxSolo + 5);
        long stored = warm.longInteger("heatStored");
        assertTrue("the plant's waste heat must end up in the loop it touches (stored="
                + stored + "): " + warm, stored > 0);
    }

    /**
     * The same machine into twice the pipe. The long loop must take at least as much heat as the
     * short one and still be COLDER — which cannot happen unless capacity is a real quantity rather
     * than a decoration on the readout.
     *
     * <p>The heat each loop actually received is asserted as a PREMISE, not read past: a longer
     * loop that simply collected less would also come out colder, and that would say nothing about
     * capacity at all.</p>
     *
     * <p>red-witnessed: with {@code HeatNetwork#tickThermodynamics} at {@code state.setThermalState(stored, capacity, temperature(stored, capacity),} publishing the temperature against one block's
     * capacity instead of the loop's: "a loop with twice the thermal mass must warm markedly less on
     * the same heat (short rose 226500 milliK, long rose 226500)", 2026-09-30. The four premises
     * before it are arrangements and are not witnessed.</p>
     * Pins INV-HEAT-02 (Capacity is a real quantity: the same heat in a loop of twice the mass is a markedly smaller temperature rise).
     * Pins HEAT-3 (one connected network is one thermodynamic object: the same heat in a longer loop is a lower temperature).
     */
    @Test
    public void theSameHeatInALongerLoopIsALowerTemperature() throws Exception {
        int cxShort = stand("two plant rigs side by side, one loop twice the other's length");
        int cxLong = cxShort + SECOND_RIG_OFFSET;
        buildRig(cxShort, SHORT_LOOP_PIPES);
        buildRig(cxLong, LONG_LOOP_PIPES);

        solve(1);
        Reply coldShort = loopInfo(cxShort + 5);
        Reply coldLong = loopInfo(cxLong + 5);
        long ambient = coldShort.longInteger("temperatureMilliK");
        assertEquals("premise: the two loops must sit at the same ambient before anything runs: "
                + coldShort + " | " + coldLong, ambient, coldLong.longInteger("temperatureMilliK"));
        assertEquals("premise: the long loop is built to hold exactly twice as much: "
                + coldShort + " | " + coldLong,
                2 * coldShort.longInteger("heatCapacity"), coldLong.longInteger("heatCapacity"));

        powerPlant(cxShort);
        powerPlant(cxLong);
        solve(SOLVE_TICKS);

        Reply warmShort = loopInfo(cxShort + 5);
        Reply warmLong = loopInfo(cxLong + 5);
        long storedShort = warmShort.longInteger("heatStored");
        long storedLong = warmLong.longInteger("heatStored");
        assertTrue("premise: both loops must have picked heat up at all (short=" + storedShort
                + " long=" + storedLong + "): " + warmShort + " | " + warmLong,
                storedShort > 0 && storedLong > 0);
        assertTrue("premise: the long loop must not be colder merely because it was given less "
                + "heat (short=" + storedShort + " long=" + storedLong + "): "
                + warmShort + " | " + warmLong, storedLong >= storedShort);

        long riseShort = warmShort.longInteger("temperatureMilliK") - ambient;
        long riseLong = warmLong.longInteger("temperatureMilliK") - ambient;
        assertTrue("a loop with twice the thermal mass must warm markedly less on the same heat "
                + "(short rose " + riseShort + " milliK, long rose " + riseLong + "): "
                + warmShort + " | " + warmLong, riseLong < riseShort);
    }

    /**
     * The mechanic's flag, off. Every block is still there and the plant still works — what must
     * stop is the heat: nothing stored, and the loop sitting at ambient. An assertion that the loop
     * reads zero would pass on a rig that simply never ran, so the same rig is driven again with
     * the flag back on and required to warm up.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. STORES NO HEAT — {@code HeatNetwork#enabled} at {@code return StellurgyConfiguration.getCurrentConfig().shipHeat;} ignoring the flag: "with the thermal system off a loop must store no heat: …
     * \"heatStored\":4530". NO CAPACITY — {@code HeatNetwork#tickThermodynamics} at {@code if (!enabled())} and {@code TileHeatPipe#getHeatCapacity} at {@code if (!HeatNetwork.enabled())} both
     * ignoring the flag, the waste-heat guard left standing: "and must report no capacity to store it
     * in: … \"heatCapacity\":60". HEATS AGAIN — {@code HeatNetwork#collectGeneration} at {@code if (pending > 0)} collecting no machine's
     * pending heat: "the same rig with the flag back on must heat, or the assertions above measured
     * nothing: … \"heatStored\":0". AT AMBIENT — {@code HeatNetwork#tickThermodynamics} at {@code state.setThermalState(0L, 0L, ambientKelvin(), 0);} writing
     * 0 K instead of the ambient for a switched-off loop: "and must read the cabin's ambient, not a
     * number of its own: … expected:&lt;293000&gt; but was:&lt;0&gt;".</p>
     *
     * <p>The temperature IS asserted here, unlike the flag-on scenarios: with the flag off the loop's
     * temperature is not derived from stored heat at all, it is written directly, so neither stored
     * verdict implies it.</p>
     * Pins INV-HEAT-03 (With shipHeat off nothing stores heat, no loop reports capacity, and every loop reads ambient).
     */
    @Test
    public void withTheThermalSystemOffNothingHeats() throws Exception {
        int cxOff = stand("a plant rig driven with the thermal system switched off, then on");
        buildRig(cxOff, SHORT_LOOP_PIPES);
        solve(1);

        setConfig("shipHeat", "false");
        try {
            powerPlant(cxOff);
            solve(SOLVE_TICKS);

            Reply off = loopInfo(cxOff + 5);
            assertEquals("with the thermal system off a loop must store no heat: " + off,
                    0L, off.longInteger("heatStored"));
            assertEquals("and must report no capacity to store it in: " + off,
                    0L, off.longInteger("heatCapacity"));
            long ambient = ask("stellurgytest config get shipHeatAmbientKelvin").longInteger("value");
            assertEquals("and must read the cabin's ambient, not a number of its own: " + off,
                    ambient * 1000L, off.longInteger("temperatureMilliK"));
        } finally {
            setConfig("shipHeat", "true");
        }

        // The control: the very same rig, flag on. Without this the assertions above would also
        // pass on a rig that was never driven, or on a plant that had run out of power.
        solve(SOLVE_TICKS);
        Reply on = loopInfo(cxOff + 5);
        assertTrue("the same rig with the flag back on must heat, or the assertions above measured "
                + "nothing: " + on, on.longInteger("heatStored") > 0);
    }

    /**
     * A machine built AFTER the loop is still cooled by it.
     *
     * <p>This is the contract the whole caching design has to earn. A machine is not a network
     * node, so placing one against a finished pipe run marks nothing dirty on its own — a loop that
     * worked out its neighbours once and never again would go on believing it is cooling nothing.
     * The loop block's own neighbour notification is what closes it, and this is what would fail if
     * that were removed.</p>
     *
     * <p>red-witnessed: with {@code HeatNetwork#onLoopNeighbourChanged} at {@code SubsystemNetworkManager.of(world).markDirty(DOMAIN, world);} no longer marking the domain dirty when a loop's
     * neighbour changes: "a machine placed against a finished loop must be found by it (stored=0)",
     * 2026-09-30, taken on the pre-merge static form {@code SubsystemNetworkManager.markDirty(DOMAIN, world)}. The premise at its head is an arrangement and is not witnessed.</p>
     *
     * <p>No temperature is asserted: a loop's temperature is ambient plus stored over capacity, so
     * "it warms" is the stored verdict read through a division.</p>
     */
    @Test
    public void aMachineBuiltAfterTheLoopIsStillPickedUp() throws Exception {
        int cxLate = stand("a coolant loop that settles before its machine is built");
        buildStaleRoom(cxLate);
        placeDuct(cxLate + 1);
        placeDuct(cxLate + 2);
        placeDuct(cxLate + 3);
        for (int i = 0; i < SHORT_LOOP_PIPES; i++) {
            placePipe(cxLate + 5 + i);
        }

        // The loop settles with nothing beside it, and works its neighbours out while that is true.
        solve(1);
        Reply alone = loopInfo(cxLate + 5);
        assertEquals("premise: the loop must exist before the machine does: " + alone,
                SHORT_LOOP_PIPES, alone.integer("members"));

        placePlant(cxLate + 4);
        powerPlant(cxLate);
        solve(SOLVE_TICKS);

        Reply after = loopInfo(cxLate + 5);
        long stored = after.longInteger("heatStored");
        assertTrue("a machine placed against a finished loop must be found by it (stored="
                + stored + "): " + after, stored > 0);
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * A sealed stale room with a vent, ducts out to a life-support plant, and a run of coolant pipe
     * welded to the plant. The plant is the heat source because it is the machine this tier already
     * has: it spends real power doing real work, and a share of what it spends comes back as heat.
     */
    private void buildRig(int cx, int pipes) throws Exception {
        buildStaleRoom(cx);
        placeDuct(cx + 1);
        placeDuct(cx + 2);
        placeDuct(cx + 3);
        placePlant(cx + 4);
        for (int i = 0; i < pipes; i++) {
            placePipe(cx + 5 + i);
        }
    }

    /** Powering the plant is what starts the heat, so it is deliberately separate from building. */
    private void powerPlant(int cx) throws Exception {
        injectEnergyAt(cx + 4, 1_000_000);
    }

    /**
     * Every network, once per tick, the way the game does it. Solving one domain to completion and
     * then the other would not reproduce this rig at all: the plant holds only a moment's waste
     * heat and sheds the rest to the room, so a loop that turns up 300 ticks late finds almost
     * nothing.
     */
    private void solve(int ticks) throws Exception {
        Reply solved = arrange("stellurgytest subnet solve all 0 " + ticks);
        assertEquals("solve failed: " + solved, ticks, solved.integer("ticksSolved"));
    }

    private void buildStaleRoom(int cx) throws Exception {
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (cy - 1) + " " + (cz - 2)
                + " " + (cx + 2) + " " + cy + " " + (cz + 2) + " minecraft:stone");
        for (int yy = cy + 1; yy <= cy + 2; yy++) {
            arrange("stellurgytest fill 0 " + (cx - 2) + " " + yy + " " + (cz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (cz + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (cx - 1) + " " + yy + " " + (cz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (cz + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (cy + 3) + " " + (cz - 2)
                + " " + (cx + 2) + " " + (cy + 3) + " " + (cz + 2) + " minecraft:stone");

        place(cx, "stellurgy:oxygenVent");
        injectEnergyAt(cx, 1_000_000);
        arrange("stellurgytest fluid inject 0 " + cx + " " + cy + " " + cz + " oxygen 16000");

        arrange("stellurgytest tile force-tick 0 " + cx + " " + cy + " " + cz + " 1");
        arrange("stellurgytest vent reseal 0 " + cx + " " + cy + " " + cz);
        arrange("stellurgytest tile force-tick 0 " + cx + " " + cy + " " + cz + " 5");

        arrange("stellurgytest vent setair 0 " + cx + " " + cy + " " + cz
                + " " + ppm(790_000) + " " + ppm(60_000) + " " + ppm(150_000));
    }

    private void placeDuct(int x) throws Exception {
        place(x, "stellurgy:ventilationDuct");
    }

    private void placePlant(int x) throws Exception {
        place(x, "stellurgy:lifeSupportPlant");
    }

    private void placePipe(int x) throws Exception {
        place(x, "stellurgy:heatPipe");
    }

    private void place(int x, String block) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + cy + " " + cz + " " + block);
        assertTrue(block + " place failed at " + x + ": " + resp, resp.bool("placed"));
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        arrange("stellurgytest energy inject 0 " + x + " " + cy + " " + cz + " " + amount);
    }

    private void setConfig(String key, String value) throws Exception {
        arrange("stellurgytest config set " + key + " " + value);
    }

    private Reply loopInfo(int x) throws Exception {
        return ask("stellurgytest subnet info heat 0 " + x + " " + cy + " " + cz);
    }
}
