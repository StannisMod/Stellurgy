package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * Going dark: what shutting every sink does to a ship, and what it cannot do.
 *
 * <p>C12 HEAT-16 is a clause about a FLOOR. Closing the radiators collapses the loud half of the
 * signature - a ship stops shedding and stops being seen from far away - but the hull it is made of
 * is still warmer than space and still glows, so silence buys RANGE and never invisibility. A model
 * that let the signature reach zero would make one build unfindable, which is the failure this pins
 * against.</p>
 *
 * <p>The cost is pinned beside it, from the loop's own side: a shut cell sheds nothing, so the energy
 * that used to leave stays aboard. Silence is an escape with a clock on it, and the clock is the
 * failure ladder.</p>
 *
 * <p>Nothing here names a temperature or a power. Every assertion is a comparison between two states
 * of the SAME rig, or between a state and zero.</p>
 *
 * <p>The rig sits on a WORLD, and that matters to how much silence is worth: a hull cannot be much
 * colder than the sky around it, so beside a warm planet the floor is high and going dark saves less
 * than it does in the dark between stars. The relations pinned here hold in both places; the ratio
 * does not, which is why none is named.</p>
 */
public class RunningSilentTest extends AbstractSharedServerTest {

    /** Where the ship's run starts, in open air so every cell has clear sky; from this scenario's
     *  own site (see {@link #buildLoop}). */
    private int xShip;
    private int y;
    private int z;

    /** `getStateFromMeta` maps this to a cell radiating UP. */
    private static final String RADIATOR_FACING_UP = "1";

    /** Accumulators first, then radiating cells: mass to hold the charge, surface to shed it. */
    private static final int ACCUMULATORS = 6;
    private static final int RADIATORS = 3;

    /** A second rig, sealed, where the hull has air in it to be warmed by: its vent, from this
     *  scenario's own site (see {@link #buildSealedRoomWithTwoLoops}). */
    private int roomX;
    private int roomY;
    private int roomZ;

    /**
     * A ship with its radiators open is a beacon; the same ship with them shut is quiet, closer to
     * find, and still there.
     *
     * <p>All three halves matter. If the power did not collapse, going dark would be pointless. If it
     * collapsed to zero, going dark would be a cloak. And the range must move with the power, or the
     * detection term is not the one the clause names.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. SILENCED — {@code ThermalBody:294}
     * stopping after the first cell it shuts: "silencing failed: … \"changed\":1,\"silent\":false".
     * NOT WORKING SURFACE — {@code TileHeatRadiator:75} ignoring the shut flag: "a shut cell is not
     * working surface: … expected:&lt;0&gt; but was:&lt;3&gt;". MOST OF IT — {@code ThermalBody:259}
     * counting every radiator, shut or not, at the loop's temperature: "shutting the sinks must take
     * most of what the ship radiates away … lit=6504393 silent=6557149". A FLOOR — {@code
     * ThermalBody:259} leaving a hundredth of a cell per shut radiator at the loop's temperature:
     * "with every sink shut, what a ship shows may not depend on how much heat it is carrying … hot=919598
     * cold=863715". RANGE FALLS — {@code ThermalSignature:129} answering the sensor's own range for
     * any power: "and the range must fall with it … lit=2000000 silent=2000000". STILL FOUND — {@code
     * ThermalBody:257} dropping the hull term: "but the hull is still warmer than space, so a silent
     * ship is found CLOSER and never not at all: … \"radiatedPowerMilli\":0". SHEDS NOTHING — {@code
     * HeatNetwork:733} and {@code :747} rejecting through every exchanger, shut or not: "a shut array
     * may shed nothing: … expected:&lt;0&gt; but was:&lt;5598&gt;". STILL ABOARD — {@code
     * HeatNetwork:286} losing one unit a tick outside rejection: "so the heat that used to leave is
     * still on the ship: … expected:&lt;3030000&gt; but was:&lt;3029999&gt;". REOPENED — {@code
     * TileHeatRadiator:85} refusing to open a shut cell: "opening the sinks failed: …
     * \"silent\":true". Not witnessed: ONE ORDER, since three cells all open before and all shut after
     * make the count three and SILENCED already reds on anything less; LOCKABLE, since radiance is
     * the cell power at the peak temperature and the hull surface carries its skin temperature
     * whenever it carries power, so it follows from STILL FOUND; COMES BACK, since the same cells
     * were shedding before they were shut and it follows from REOPENED. The three premises at its
     * head are arrangements and are not witnessed.</p>
     */
    @Test
    public void aShipRunningSilentIsFoundCloserAndIsStillFound() throws Exception {
        buildLoop();
        solve(1);
        Reply body = signature();
        assertTrue("premise: the body must be made of something: " + body, body.integer("sizeBlocks") > 0);

        long charge = 500L * loopInfo().longInteger("heatCapacity");
        Reply litCycle = cycle(charge);
        assertTrue("premise: an open array must genuinely be shedding, or 'silent' means nothing: "
                + litCycle, litCycle.longInteger("rejected") > 0);

        Reply lit = signature();
        long litPower = lit.longInteger("radiatedPowerMilli");
        long litRange = lit.longInteger("detectionRangeMilli");
        assertEquals("premise: every cell must be working while the ship is lit: " + lit,
                RADIATORS, lit.integer("radiatingCells"));

        Reply shut = arrange("stellurgytest heat silent 0 " + xShip + " " + y + " " + z + " on");
        assertTrue("silencing failed: " + shut, shut.bool("silent"));
        assertEquals("every cell of the array must have been shut by one order: " + shut,
                RADIATORS, shut.integer("changed"));

        Reply silentCycle = cycle(charge);
        Reply silent = signature();
        long silentPower = silent.longInteger("radiatedPowerMilli");
        long silentRange = silent.longInteger("detectionRangeMilli");
        assertEquals("a shut cell is not working surface: " + silent, 0, silent.integer("radiatingCells"));

        assertTrue("shutting the sinks must take most of what the ship radiates away with them, or"
                        + " there is no reason to ever do it: lit=" + litPower
                        + " silent=" + silentPower, silentPower * 2L < litPower);

        // What is LEFT must be the hull and nothing else, which is checkable without restating the
        // curve: empty the loop entirely and the silent ship must look exactly the same. A floor
        // that moved with the loop's temperature would be reading heat that has no way out.
        // EXACT, not within a tolerance: `ThermalBody.signature()` is the hull term (size, cabin and
        // environment temperature) plus each loop's WORKING cells at the loop's temperature, and with
        // every sink shut there are no working cells — the loop's heat enters neither term, so the
        // same inputs give the same milli-units. A tolerance would pass exactly the leak this pins:
        // a shut cell still radiating a little of a hot loop.
        cycle(0L);
        long silentAndCold = signature().longInteger("radiatedPowerMilli");
        assertEquals("with every sink shut, what a ship shows may not depend on how much heat it is"
                        + " carrying - that is what makes it a FLOOR: hot=" + silentPower
                        + " cold=" + silentAndCold,
                silentPower, silentAndCold);
        assertTrue("and the range must fall with it, since range is what the power term buys:"
                + " lit=" + litRange + " silent=" + silentRange, silentRange < litRange);
        assertTrue("but the hull is still warmer than space, so a silent ship is found CLOSER and"
                        + " never not at all: " + silent, silentPower > 0 && silentRange > 0);
        assertTrue("and it is still a thing a seeker can lock, for the same reason: " + silent,
                silent.longInteger("radianceMilli") > 0);

        // The cost, from the loop's own side: nothing left, so all of it is still aboard.
        assertEquals("a shut array may shed nothing: " + silentCycle,
                0L, silentCycle.longInteger("rejected"));
        assertEquals("so the heat that used to leave is still on the ship: " + silentCycle,
                charge, silentCycle.longInteger("heatStored"));

        // And it is a state a pilot can leave: opening the sinks must restore the ship it was.
        Reply opened = arrange("stellurgytest heat silent 0 " + xShip + " " + y + " " + z + " off");
        assertFalse("opening the sinks failed: " + opened, opened.bool("silent"));
        Reply reopened = cycle(charge);
        assertTrue("a ship that went dark must be able to come back: " + reopened,
                reopened.longInteger("rejected") > 0);
    }

    /**
     * What the floor is MADE of: the air the hull encloses.
     *
     * <p>A ship cooking itself while it hides gets steadily easier to see, because the only thing
     * keeping it dim is how much colder its skin is than its cabin - and the cabin is what fills up.
     * So the same rig, at two room temperatures, must show two different floors. That is a
     * consequence of the model rather than a rule in it, which is exactly why it is worth pinning.</p>
     *
     * <p>The second half is what a body IS off a ship: two loops threading one sealed room are one
     * hull, not two. If they were two, each would claim the whole floor and a sensor summing them
     * would see a ship twice as bright as the one that is there.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. ONE BODY — {@code ThermalBody:133}
     * never looking past the loop asked about: "two loops in one sealed room are ONE body … expected:&lt;2&gt;
     * but was:&lt;1&gt;". HOTTER SKIN — {@code ThermalBody:239} holding the skin at the environment's
     * temperature: "a hotter cabin must show a hotter skin … cool=286000 hot=286000". FOUND FURTHER
     * — {@code ThermalBody:257} dropping the hull term: "so a ship that cooks itself while hiding is
     * found further away the longer it hides: cool=0 hot=0". The three premises are arrangements and
     * are not witnessed.</p>
     */
    @Test
    public void theHullGlowsWithTheAirItEncloses() throws Exception {
        buildSealedRoomWithTwoLoops();
        solve(1);

        Reply cool = signature(roomX - 1, roomY + 1, roomZ - 1);
        assertEquals("two loops in one sealed room are ONE body - one hull cannot be counted twice:"
                + " " + cool, 2, cool.integer("loops"));
        assertTrue("premise: the hull must be sized by the air it encloses as well as its plumbing:"
                + " " + cool, cool.integer("sizeBlocks") > 2);
        assertTrue("premise: a skin at cabin temperature would leave nothing for insulation to do:"
                        + " " + cool, cool.longInteger("skinMilliK") < cool.longInteger("cabinMilliK"));

        setAir(500_000);
        Reply hot = signature(roomX - 1, roomY + 1, roomZ - 1);

        assertTrue("premise: the room must actually have been heated: cool=" + cool.longInteger("cabinMilliK")
                + " hot=" + hot.longInteger("cabinMilliK"),
                hot.longInteger("cabinMilliK") > cool.longInteger("cabinMilliK"));
        assertTrue("a hotter cabin must show a hotter skin - the floor is the air it encloses, not a"
                        + " constant: cool=" + cool.longInteger("skinMilliK")
                        + " hot=" + hot.longInteger("skinMilliK"),
                hot.longInteger("skinMilliK") > cool.longInteger("skinMilliK"));
        assertTrue("so a ship that cooks itself while hiding is found further away the longer it"
                        + " hides: cool=" + cool.longInteger("radiatedPowerMilli")
                        + " hot=" + hot.longInteger("radiatedPowerMilli"),
                hot.longInteger("radiatedPowerMilli") > cool.longInteger("radiatedPowerMilli"));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /** Mass, then surface: a straight run whose far end is radiating cells facing open sky. */
    private void buildLoop() throws Exception {
        FixtureSite site = clearedSite(4, 3, "a nine-block coolant run with its radiators at the far end");
        xShip = site.x;
        y = site.y + 1;
        z = site.z;
        for (int i = 0; i < ACCUMULATORS; i++) {
            place(xShip + i, y, z, "stellurgy:heatAccumulator", null);
        }
        for (int i = 0; i < RADIATORS; i++) {
            place(xShip + ACCUMULATORS + i, y, z, "stellurgy:heatRadiator", RADIATOR_FACING_UP);
        }
    }

    /**
     * A sealed room with a vent and TWO separate coolant runs in it, laid diagonally so they never
     * touch each other. One hull, two loops - which is the arrangement a chiller makes on a real
     * ship, built here without one.
     */
    private void buildSealedRoomWithTwoLoops() throws Exception {
        FixtureSite site = clearedSite(2, 6, "a sealed room with two coolant runs inside it");
        roomX = site.x + 2;
        roomY = site.y + 2;
        roomZ = site.z + 2;
        arrange("stellurgytest fill 0 " + (roomX - 2) + " " + (roomY - 1) + " " + (roomZ - 2)
                + " " + (roomX + 2) + " " + roomY + " " + (roomZ + 2) + " minecraft:stone");
        for (int yy = roomY + 1; yy <= roomY + 2; yy++) {
            arrange("stellurgytest fill 0 " + (roomX - 2) + " " + yy + " " + (roomZ - 2)
                    + " " + (roomX + 2) + " " + yy + " " + (roomZ + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (roomX - 1) + " " + yy + " " + (roomZ - 1)
                    + " " + (roomX + 1) + " " + yy + " " + (roomZ + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (roomX - 2) + " " + (roomY + 3) + " " + (roomZ - 2)
                + " " + (roomX + 2) + " " + (roomY + 3) + " " + (roomZ + 2) + " minecraft:stone");

        place(roomX - 1, roomY + 1, roomZ - 1, "stellurgy:heatPipe", null);
        place(roomX + 1, roomY + 1, roomZ + 1, "stellurgy:heatPipe", null);

        place(roomX, roomY, roomZ, "stellurgy:oxygenVent", null);
        arrange("stellurgytest energy inject 0 " + roomX + " " + roomY + " " + roomZ + " 1000000");
        arrange("stellurgytest fluid inject 0 " + roomX + " " + roomY + " " + roomZ + " oxygen 16000");
        arrange("stellurgytest tile force-tick 0 " + roomX + " " + roomY + " " + roomZ + " 1");
        arrange("stellurgytest vent reseal 0 " + roomX + " " + roomY + " " + roomZ);
        arrange("stellurgytest tile force-tick 0 " + roomX + " " + roomY + " " + roomZ + " 5");
    }

    /** Put the room's air at a stated temperature, leaving its gases alone. */
    private void setAir(int milliK) throws Exception {
        arrange("stellurgytest vent setair 0 " + roomX + " " + roomY + " " + roomZ
                + " " + ppm(790_000) + " " + ppm(210_000) + " 0 " + milliK);
    }

    private Reply signature() throws Exception {
        return signature(xShip, y, z);
    }

    /** The probe answers `isBody:false` with every figure zero where there is no body, so that is
     *  refused here rather than read as a ship that radiates nothing. */
    private Reply signature(int x, int y, int z) throws Exception {
        Reply resp = arrange("stellurgytest heat signature 0 " + x + " " + y + " " + z);
        requireArranged("no body at " + x + " " + y + " " + z + ": " + resp, resp.bool("isBody"));
        return resp;
    }

    /** Charge the loop and advance one tick in ONE call - see `heat cycle` on why it must be one. */
    private Reply cycle(long charge) throws Exception {
        Reply cycled = arrange("stellurgytest heat cycle 0 " + xShip + " " + y + " " + z + " " + charge + " 1");
        requireArranged("heat cycle found no loop: " + cycled, cycled.bool("inLoop"));
        return cycled;
    }

    private Reply loopInfo() throws Exception {
        return ask("stellurgytest subnet info heat 0 " + xShip + " " + y + " " + z);
    }

    private void place(int x, int y, int z, String block, String meta) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + y + " " + z + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + " " + y + " " + z + ": " + resp, resp.bool("placed"));
    }

    private void solve(int ticks) throws Exception {
        Reply solved = arrange("stellurgytest subnet solve heat 0 " + ticks);
        assertEquals("solve failed: " + solved, ticks, solved.integer("ticksSolved"));
    }
}
