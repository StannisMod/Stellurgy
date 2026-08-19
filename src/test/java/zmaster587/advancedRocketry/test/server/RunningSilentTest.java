package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

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

    private static final Pattern POWER = Pattern.compile("\"radiatedPowerMilli\":(-?\\d+)");
    private static final Pattern RADIANCE = Pattern.compile("\"radianceMilli\":(-?\\d+)");
    private static final Pattern RANGE = Pattern.compile("\"detectionRangeMilli\":(-?\\d+)");
    private static final Pattern CELLS = Pattern.compile("\"radiatingCells\":(-?\\d+)");
    private static final Pattern LOOPS = Pattern.compile("\"loops\":(-?\\d+)");
    private static final Pattern SKIN = Pattern.compile("\"skinMilliK\":(-?\\d+)");
    private static final Pattern CABIN = Pattern.compile("\"cabinMilliK\":(-?\\d+)");
    private static final Pattern SIZE = Pattern.compile("\"sizeBlocks\":(-?\\d+)");
    private static final Pattern CYCLE_REJECTED = Pattern.compile("\"rejected\":(-?\\d+)");
    private static final Pattern HEAT_STORED = Pattern.compile("\"heatStored\":(-?\\d+)");
    private static final Pattern HEAT_CAPACITY = Pattern.compile("\"heatCapacity\":(-?\\d+)");
    private static final Pattern CHANGED = Pattern.compile("\"changed\":(-?\\d+)");

    /** High and in the open, so every cell has clear sky and nothing else is nearby. */
    private static final int Y = 100;
    private static final int Z = 2880;
    private static final int X_SHIP = 1200;

    /** `getStateFromMeta` maps this to a cell radiating UP. */
    private static final String RADIATOR_FACING_UP = "1";

    /** Accumulators first, then radiating cells: mass to hold the charge, surface to shed it. */
    private static final int ACCUMULATORS = 6;
    private static final int RADIATORS = 3;

    /** A second rig, sealed, where the hull has air in it to be warmed by. */
    private static final int ROOM_X = 1260;
    private static final int ROOM_Y = 100;
    private static final int ROOM_Z = 2940;

    /**
     * A ship with its radiators open is a beacon; the same ship with them shut is quiet, closer to
     * find, and still there.
     *
     * <p>All three halves matter. If the power did not collapse, going dark would be pointless. If it
     * collapsed to zero, going dark would be a cloak. And the range must move with the power, or the
     * detection term is not the one the clause names.</p>
     */
    @Test
    public void aShipRunningSilentIsFoundCloserAndIsStillFound() throws Exception {
        buildLoop();
        solve(1);
        assertTrue("premise: the body must be made of something: " + signature(),
                longOf(signature(), SIZE) > 0);

        long charge = 500L * longOf(loopInfo(), HEAT_CAPACITY);
        String litCycle = cycle(charge);
        assertTrue("premise: an open array must genuinely be shedding, or 'silent' means nothing: "
                + litCycle, longOf(litCycle, CYCLE_REJECTED) > 0);

        String lit = signature();
        long litPower = longOf(lit, POWER);
        long litRange = longOf(lit, RANGE);
        assertEquals("premise: every cell must be working while the ship is lit: " + lit,
                RADIATORS, longOf(lit, CELLS));

        String shut = exec("artest heat silent 0 " + X_SHIP + " " + Y + " " + Z + " on");
        assertTrue("silencing failed: " + shut, shut.contains("\"silent\":true"));
        assertEquals("every cell of the array must have been shut by one order: " + shut,
                RADIATORS, longOf(shut, CHANGED));

        String silentCycle = cycle(charge);
        String silent = signature();
        long silentPower = longOf(silent, POWER);
        long silentRange = longOf(silent, RANGE);
        assertEquals("a shut cell is not working surface: " + silent, 0, longOf(silent, CELLS));

        assertTrue("shutting the sinks must take most of what the ship radiates away with them, or"
                        + " there is no reason to ever do it: lit=" + litPower
                        + " silent=" + silentPower, silentPower * 2L < litPower);

        // What is LEFT must be the hull and nothing else, which is checkable without restating the
        // curve: empty the loop entirely and the silent ship must look exactly the same. A floor
        // that moved with the loop's temperature would be reading heat that has no way out.
        cycle(0L);
        long silentAndCold = longOf(signature(), POWER);
        assertEquals("with every sink shut, what a ship shows may not depend on how much heat it is"
                        + " carrying - that is what makes it a FLOOR: hot=" + silentPower
                        + " cold=" + silentAndCold,
                silentPower, silentAndCold, Math.max(1.0D, silentPower * 0.01D));
        assertTrue("and the range must fall with it, since range is what the power term buys:"
                + " lit=" + litRange + " silent=" + silentRange, silentRange < litRange);
        assertTrue("but the hull is still warmer than space, so a silent ship is found CLOSER and"
                        + " never not at all: " + silent, silentPower > 0 && silentRange > 0);
        assertTrue("and it is still a thing a seeker can lock, for the same reason: " + silent,
                longOf(silent, RADIANCE) > 0);

        // The cost, from the loop's own side: nothing left, so all of it is still aboard.
        assertEquals("a shut array may shed nothing: " + silentCycle,
                0, longOf(silentCycle, CYCLE_REJECTED));
        assertEquals("so the heat that used to leave is still on the ship: " + silentCycle,
                charge, longOf(silentCycle, HEAT_STORED));

        // And it is a state a pilot can leave: opening the sinks must restore the ship it was.
        String opened = exec("artest heat silent 0 " + X_SHIP + " " + Y + " " + Z + " off");
        assertTrue("opening the sinks failed: " + opened, opened.contains("\"silent\":false"));
        assertTrue("a ship that went dark must be able to come back: " + opened,
                longOf(cycle(charge), CYCLE_REJECTED) > 0);
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
     */
    @Test
    public void theHullGlowsWithTheAirItEncloses() throws Exception {
        buildSealedRoomWithTwoLoops();
        solve(1);

        String cool = signature(ROOM_X - 1, ROOM_Y + 1, ROOM_Z - 1);
        assertEquals("two loops in one sealed room are ONE body - one hull cannot be counted twice:"
                + " " + cool, 2, longOf(cool, LOOPS));
        assertTrue("premise: the hull must be sized by the air it encloses as well as its plumbing:"
                + " " + cool, longOf(cool, SIZE) > 2);
        assertTrue("premise: a skin at cabin temperature would leave nothing for insulation to do:"
                        + " " + cool, longOf(cool, SKIN) < longOf(cool, CABIN));

        setAir(500_000);
        String hot = signature(ROOM_X - 1, ROOM_Y + 1, ROOM_Z - 1);

        assertTrue("premise: the room must actually have been heated: cool=" + longOf(cool, CABIN)
                + " hot=" + longOf(hot, CABIN), longOf(hot, CABIN) > longOf(cool, CABIN));
        assertTrue("a hotter cabin must show a hotter skin - the floor is the air it encloses, not a"
                        + " constant: cool=" + longOf(cool, SKIN) + " hot=" + longOf(hot, SKIN),
                longOf(hot, SKIN) > longOf(cool, SKIN));
        assertTrue("so a ship that cooks itself while hiding is found further away the longer it"
                        + " hides: cool=" + longOf(cool, POWER) + " hot=" + longOf(hot, POWER),
                longOf(hot, POWER) > longOf(cool, POWER));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /** Mass, then surface: a straight run whose far end is radiating cells facing open sky. */
    private void buildLoop() throws Exception {
        for (int i = 0; i < ACCUMULATORS; i++) {
            place(X_SHIP + i, Y, Z, "advancedrocketry:heatAccumulator", null);
        }
        for (int i = 0; i < RADIATORS; i++) {
            place(X_SHIP + ACCUMULATORS + i, Y, Z, "advancedrocketry:heatRadiator", RADIATOR_FACING_UP);
        }
    }

    /**
     * A sealed room with a vent and TWO separate coolant runs in it, laid diagonally so they never
     * touch each other. One hull, two loops - which is the arrangement a chiller makes on a real
     * ship, built here without one.
     */
    private void buildSealedRoomWithTwoLoops() throws Exception {
        exec("artest fill 0 " + (ROOM_X - 2) + " " + (ROOM_Y - 1) + " " + (ROOM_Z - 2)
                + " " + (ROOM_X + 2) + " " + ROOM_Y + " " + (ROOM_Z + 2) + " minecraft:stone");
        for (int yy = ROOM_Y + 1; yy <= ROOM_Y + 2; yy++) {
            exec("artest fill 0 " + (ROOM_X - 2) + " " + yy + " " + (ROOM_Z - 2)
                    + " " + (ROOM_X + 2) + " " + yy + " " + (ROOM_Z + 2) + " minecraft:stone");
            exec("artest fill 0 " + (ROOM_X - 1) + " " + yy + " " + (ROOM_Z - 1)
                    + " " + (ROOM_X + 1) + " " + yy + " " + (ROOM_Z + 1) + " minecraft:air");
        }
        exec("artest fill 0 " + (ROOM_X - 2) + " " + (ROOM_Y + 3) + " " + (ROOM_Z - 2)
                + " " + (ROOM_X + 2) + " " + (ROOM_Y + 3) + " " + (ROOM_Z + 2) + " minecraft:stone");

        place(ROOM_X - 1, ROOM_Y + 1, ROOM_Z - 1, "advancedrocketry:heatPipe", null);
        place(ROOM_X + 1, ROOM_Y + 1, ROOM_Z + 1, "advancedrocketry:heatPipe", null);

        String vent = exec("artest place 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + vent, vent.contains("\"placed\":true"));
        String energy = exec("artest energy inject 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " 1000000");
        assertTrue("energy inject failed: " + energy, energy.contains("\"ok\":true"));
        String oxygen = exec("artest fluid inject 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " oxygen 16000");
        assertTrue("oxygen inject failed: " + oxygen, oxygen.contains("\"ok\":true"));
        exec("artest tile force-tick 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " 1");
        exec("artest vent reseal 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z);
        exec("artest tile force-tick 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " 5");
    }

    /** Put the room's air at a stated temperature, leaving its gases alone. */
    private void setAir(int milliK) throws Exception {
        String set = exec("artest vent setair 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " 790000 210000 0 " + milliK);
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));
    }

    private String signature() throws Exception {
        return signature(X_SHIP, Y, Z);
    }

    private String signature(int x, int y, int z) throws Exception {
        String resp = exec("artest heat signature 0 " + x + " " + y + " " + z);
        assertTrue("no body at " + x + " " + y + " " + z + ": " + resp,
                resp.contains("\"isBody\":true"));
        return resp;
    }

    /** Charge the loop and advance one tick in ONE call - see `heat cycle` on why it must be one. */
    private String cycle(long charge) throws Exception {
        String cycled = exec("artest heat cycle 0 " + X_SHIP + " " + Y + " " + Z + " " + charge + " 1");
        assertTrue("heat cycle failed: " + cycled, cycled.contains("\"inLoop\":true"));
        return cycled;
    }

    private String loopInfo() throws Exception {
        return exec("artest subnet info heat 0 " + X_SHIP + " " + Y + " " + Z);
    }

    private void place(int x, int y, int z, String block, String meta) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + z + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + " " + y + " " + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void solve(int ticks) throws Exception {
        String solved = exec("artest subnet solve heat 0 " + ticks);
        assertTrue("solve failed: " + solved, solved.contains("\"ticksSolved\":" + ticks));
    }

    private static long longOf(String json, Pattern pattern) {
        Matcher m = pattern.matcher(json);
        assertTrue("no " + pattern.pattern() + " in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }
}
