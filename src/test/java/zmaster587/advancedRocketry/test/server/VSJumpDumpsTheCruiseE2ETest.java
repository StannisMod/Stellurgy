package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipReadiness;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.DIRECT_JUMP_SPEED;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * E2E: a craft leaves HYPERSPACE at rest, and keeps its cruise across every other kind of crossing.
 *
 * <h2>The rule this pins, and why it needs two legs</h2>
 *
 * <p>A craft keeps the cruise its pilot set — leaving a planet, landing on one, crossing from one
 * cell to the next. Hyperspace is the one exception: speed is lost entering and leaving it, and a
 * craft comes out at rest, by a hyperspace velocity dump.</p>
 *
 * <p>So a single leg asserting "the cruise is zero after the jump" would be satisfied just as well
 * by a regression that zeroed every cruise everywhere — which is the likelier defect of the two,
 * since the retention is the general rule and the dump is the carve-out. The CONTROL is therefore
 * not decoration: the same fixture, the same command, the same acceptance, and only the SPEED
 * different, so that the jump takes the direct cell-to-cell crossing instead of a hyperspace flight.
 * The cruise must survive that one. One variable moves between the two legs and it is the route.</p>
 *
 * <p>The route is asserted rather than inferred — the arrival event names which mechanism carried
 * the ship — so a leg whose jump went the other way fails instead of quietly measuring its sibling's
 * subject.</p>
 *
 * <h2>What this cannot see</h2>
 *
 * <p>The craft's VELOCITY on arrival is zero either way and says nothing: both routes rebuild the
 * physics body, so it starts at rest and then accelerates back toward whatever setpoint survived.
 * That is exactly the observation that made a cell-seam red look like dropped cargo for a day. What
 * is read here is the SETPOINT, through the read-only probe, which is the quantity the rule is
 * about.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSJumpDumpsTheCruiseE2ETest extends AbstractSharedServerTest {

    /** How much WORLD an arrival gets, in server ticks — the twenty seconds this tier's siblings use. */
    private static final int ARRIVAL_TICKS = 400;

    /**
     * The cruise this scenario commands, in blocks per SECOND — the flight computer's own unit
     * ({@code commandCruise}), which is not the unit a ship's {@code velY} is reported in. Half of
     * the drive's maximum: unambiguously under way, and nowhere near a clamp that could make a
     * retained value look like a dumped one.
     */
    private static final double COMMANDED_CRUISE = 20.0;

    /** Tolerance on a cruise read back, in blocks per second. The two outcomes are 20 apart. */
    private static final double CRUISE_EPSILON = 1e-9;

    @Test
    public void aDirectCrossingKeepsTheCruise() throws Exception {
        Arrived arrived = jumpUnderCruise(DIRECT_JUMP_SPEED, "DIRECT");

        String cruise = exec("artest vs ff-cruise-read-by-id " + arrived.dim + " " + arrived.vsId);
        assertTrue("the arrived craft has no flight computer to answer about, so this leg pins "
                + "nothing: " + cruise, cruise.contains("\"afcResolved\":true"));
        assertEquals("a craft keeps its cruise across a cell-to-cell crossing — if this is zero, the "
                        + "dump is not a hyperspace carve-out but a regression that empties every "
                        + "setpoint everywhere, and its sibling leg would pass on it: " + cruise,
                COMMANDED_CRUISE, extractDouble(cruise, "cruiseUp"), CRUISE_EPSILON);
    }

    @Test
    public void aHyperspaceJumpLeavesTheCraftAtRest() throws Exception {
        Arrived arrived = jumpUnderCruise(HYPERSPACE_JUMP_SPEED, "HYPERSPACE");

        String cruise = exec("artest vs ff-cruise-read-by-id " + arrived.dim + " " + arrived.vsId);
        assertTrue("the arrived craft has no flight computer to answer about: " + cruise,
                cruise.contains("\"afcResolved\":true"));
        assertEquals("a craft leaves hyperspace at rest, and this one arrived still carrying the "
                        + "cruise it entered with — so it will accelerate back to it within seconds "
                        + "of dropping out: " + cruise,
                0.0, extractDouble(cruise, "cruiseUp"), CRUISE_EPSILON);
        assertEquals("and the other two axes with it: " + cruise,
                0.0, extractDouble(cruise, "cruiseForward"), CRUISE_EPSILON);
        assertEquals("and the other two axes with it: " + cruise,
                0.0, extractDouble(cruise, "cruiseRight"), CRUISE_EPSILON);
    }

    // ─── the shared arrangement ─────────────────────────────────────────────────

    /** A craft that jumped, named by the identity it arrived under. */
    private static final class Arrived {
        final int dim;
        final String vsId;

        Arrived(int dim, String vsId) {
            this.dim = dim;
            this.vsId = vsId;
        }
    }

    /**
     * Build a piloted craft, put it under a COMMANDED cruise, jump it at {@code speed}, and hand back
     * what arrived. Both legs run this body: two mechanisms with two copies of "did it get there"
     * drift apart, and the copy nobody maintains is the one that breaks silently.
     */
    private Arrived jumpUnderCruise(long speed, String route) throws Exception {

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup failed: " + setup, setup.contains("\"ok\":true"));
        int originDim = extractInt(setup, "originDim");
        assertTrue("origin ship never assembled/loaded in the pool-slot cell (dim " + originDim + ")",
                loadedShips(originDim) >= 1);

        // The origin cell is freshly minted and holds this craft alone, so "the only loaded ship
        // here" is an identification rather than a proximity guess. This fixture carries TWO ids —
        // the substrate's and a durable one it mints off the computer — and the cruise verbs resolve
        // the FIRST, so the other one is deliberately not used here.
        String originVsId = ShipIdentity.theOnlyLoadedShipIn(this::exec, originDim);

        String commanded = exec("artest vs ff-cruise-by-id " + originDim + " " + originVsId
                + " 0 0 " + COMMANDED_CRUISE);
        assertTrue("the craft has no flight computer to command, so nothing below is about a cruise: "
                + commanded, commanded.contains("\"afcResolved\":true"));
        assertEquals("PREMISE: the craft must actually be under way before it jumps, or both legs "
                        + "would be asking about a setpoint that was never there: " + commanded,
                COMMANDED_CRUISE, extractDouble(commanded, "cruiseUp"), CRUISE_EPSILON);

        // Marked BEFORE the command whose effect is awaited.
        long jumpMark = events.mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + speed);
        assertTrue("the jump must begin: " + begin, begin.contains("\"began\":true"));

        String arrived = events.awaitCarrying(jumpMark, "ship_transit_ended",
                "\"route\":\"" + route + "\"",
                "the ship never reached the target cell by the " + route + " route, so this leg has "
                        + "no arrival to read a cruise off; the durable record now reads "
                        + exec("artest space transit-export"),
                ARRIVAL_TICKS);
        int targetDim = extractInt(arrived, "dim");
        assertTrue("the arrival was announced but names no dimension: " + arrived, targetDim >= 0);
        assertTrue("the ship never (re)loaded in the target cell (dim " + targetDim + "); countAll="
                + exec("artest vs ship-count-all " + targetDim), loadedShips(targetDim) >= 1);
        // Identified off the ARRIVAL'S OWN RECORD, not off "the only loaded ship in this cell". That
        // read worked only while an earlier scenario's hull unloaded itself once nobody was near it;
        // with a test server holding ships loaded, the target cell holds two and a count cannot say
        // which one this jump produced.
        String durableId = Events.lastField(arrived, "ship");
        assertTrue("the arrival must name the craft that made it, or the cruise read below is about"
                + " whichever hull the cell happens to hold: " + arrived,
                durableId != null && !durableId.trim().isEmpty());
        return new Arrived(targetDim, ShipIdentity.physicsIdOf(this::exec, targetDim, durableId.trim()));
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
    }

    // ─── plumbing, mirroring this tier's siblings ───────────────────────────────

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advanceWorld(client(), 0, ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.eE+\\-]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }
}
