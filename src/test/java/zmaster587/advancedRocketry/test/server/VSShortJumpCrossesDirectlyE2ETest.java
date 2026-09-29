package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;
import zmaster587.advancedRocketry.space.ShipEntryController;

import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.DIRECT_JUMP_SPEED;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;

/**
 * E2E: a jump short enough to have no cruise moves a real VS ship between two cells in ONE crossing.
 *
 * <p>The arrival acceptance here is deliberately the SAME body for both mechanisms
 * ({@link #arrivesInTheTargetCell}), run once at a speed that selects the direct crossing and once at a
 * speed that selects a hyperspace flight. Two mechanisms with two copies of "did it arrive" drift apart
 * within weeks, and the copy that stops being maintained is the one whose mechanism nobody is changing
 * — which is the one that will break silently.</p>
 *
 * <p>What is asserted about the direct path beyond arriving: it never reports a flight in progress.
 * That is the whole claim — no lane, no park, no mid-flight for a restart to resume — and it is read
 * off the probe's own {@code inTransit}/{@code crossing} pair rather than off how long anything
 * took.</p>
 */
public class VSShortJumpCrossesDirectlyE2ETest extends AbstractSharedServerTest {

    /**
     * How much WORLD a jump gets to complete in: 400 server ticks, the twenty seconds the old
     * 80 x 250 ms poll loop meant on an idle box, with no fork multiplier.
     */
    private static final int ARRIVAL_TICKS = 400;

    /** The same, for a ship becoming loadable in its slot. */

    @Test
    public void aShortJumpArrivesWithoutEverBeingInFlight() throws Exception {

        TransitSetup setup = setUpPilotedShip();
        int originDim = setup.originDim;

        // Marked BEFORE the command whose effect is awaited.
        long jumpMark = events.mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 "
                + DIRECT_JUMP_SPEED);
        assertTrue("the short jump must begin: " + begin, Reply.of(begin).bool("began"));
        assertEquals("a direct crossing is not a flight — nothing may be in transit the moment it "
                        + "starts, because there is no flight to be in the middle of: " + begin,
                0, extractInt(begin, "inTransit"));

        // The arrival names the route it was flown by, so this leg asserts the MECHANISM rather
        // than inferring it from an in-transit count that happens to read zero. A jump that went
        // through hyperspace no longer satisfies it.
        String arrived = arrivesInTheTargetCell(jumpMark, setup.requireDurableId(), "DIRECT");
        assertEquals("and nothing was ever in transit while it settled: " + arrived,
                0, extractInt(begin, "inTransit"));
    }

    /**
     * The control leg, and it is not decoration: it is what makes the assertion above mean "the SPEED
     * chose this" rather than "this fixture always does this". Same ship, same cells, same acceptance —
     * only the drive is slower, and the jump becomes a flight with a lane under it.
     */
    @Test
    public void theSameJumpFlownSlowlyStillGoesThroughHyperspace() throws Exception {

        TransitSetup setup = setUpPilotedShip();
        int originDim = setup.originDim;

        // Marked BEFORE the command whose effect is awaited.
        long jumpMark = events.mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 "
                + HYPERSPACE_JUMP_SPEED);
        assertTrue("the jump must begin: " + begin, Reply.of(begin).bool("began"));
        assertEquals("a slow jump IS a flight, and reports one: " + begin,
                1, extractInt(begin, "inTransit"));

        arrivesInTheTargetCell(jumpMark, setup.requireDurableId(), "HYPERSPACE");
    }

    /**
     * A short jump AIMED AT A BODY comes out standing OFF it, and stays in space.
     *
     * <p>An arrival lands on the coordinate it was sent to unless something moves it, and a body's
     * address is exactly such a coordinate — so an unmoved arrival sits at distance zero, inside the
     * descent radius, and the flight computer's own proximity trigger takes the ship down on its
     * first settled tick with nobody at the controls. The standoff ring exists to prevent exactly that,
     * and this pins it on the path a SHORT jump takes: the direct crossing.</p>
     *
     * <p>The claim is read off the ledger rather than off the absence of a descent: the ship must
     * still HAVE a row (a descent removes it — the ship is no longer in space), and every
     * descend-target body of the cell it stands in must be farther than the trigger's radius, by the
     * same centre distance the trigger compares. A cell with no body in it would make both trivially
     * true, so the body being there is required first.</p>
     *
     * <p>red-witnessed: with {@code SpaceSubsystem}'s direct crosser passing the raw {@code target}
     * to {@code requestDirectJump}, this failed with "Nearest descend-target centre: 0" — the ship
     * SETTLED in cell 19_0_0 at bearing [0,0,0] from the planet; 2026-09-23.</p>
     */
    @Test
    public void aShortJumpAtABodyArrivesOnItsStandoffRingAndStaysInSpace() throws Exception {

        TransitSetup setup = setUpPilotedShip();
        String durableId = setup.requireDurableId();

        long jumpMark = events.markInstrumented();
        // `body 0` aims at the home planet's own address; the speed makes any distance one tick of
        // flight, so the route is the direct crossing — asserted below off the arrival record.
        String begin = exec("artest space transit-begin " + setup.originDim + " 1 64 1 "
                + ONE_TICK_JUMP_SPEED + " body 0");
        assertTrue("the short jump at the home planet must begin: " + begin,
                Reply.of(begin).bool("began"));
        String arrived = events.awaitRecordWithFields(jumpMark, "ship_transit_ended",
                "the jump at the home planet never ended; the durable record reads "
                        + exec("artest space transit-export"),
                ARRIVAL_TICKS, "ship", durableId, "route", "DIRECT");
        int slotDim = extractInt(arrived, "dim");

        // WINDOW: from the arrival record to the ledger read below, STANDOFF_WATCH_TICKS of the
        // arrival cell's own clock — the descent trigger runs on its flight computer's tick there,
        // and a ship inside the radius is taken down on the first of them.
        GameTicks.advanceWorld(client(), slotDim, STANDOFF_WATCH_TICKS);
        String bodies = exec("artest space bodies");
        String row = null;
        for (String ship : Reply.of("artest space bodies", bodies).objectArray("ships")) {
            if (durableId.equals(Reply.of(ship).text("ship"))) {
                row = ship;
            }
        }
        assertTrue("a ship that jumped AT a body must still be in space afterwards — its ledger row is"
                        + " gone, which is what a descent does. Descents since the jump: "
                        + events.since(jumpMark, "descent_requested") + " | arrival=" + arrived
                        + " | bodies=" + bodies,
                row != null);
        long nearest = Long.MAX_VALUE;
        int descendTargets = 0;
        for (String body : Reply.of("one ship's cell", row).objectArray("cellBodies")) {
            Reply b = Reply.of("one cell body", body);
            if (b.bool("descendTarget")) {
                descendTargets++;
                nearest = Math.min(nearest, (long) b.number("distance"));
            }
        }
        assertTrue("ARRANGEMENT: the cell the jump ended in must hold the body it was aimed at, or"
                        + " nothing here could have been stood off from: " + row,
                descendTargets > 0);
        assertTrue("a short jump aimed at a body must come out OFF it — outside the "
                        + ShipEntryController.DESCENT_RADIUS_BLOCKS + "-block descent radius — or the"
                        + " flight computer takes the ship down with nobody asking. Nearest"
                        + " descend-target centre: " + nearest + " | row=" + row,
                nearest > ShipEntryController.DESCENT_RADIUS_BLOCKS);
    }

    /**
     * A jump speed that makes ANY distance one tick of flight — so the route is always the direct
     * crossing, whatever the distance from the fixture's origin to the body aimed at.
     */
    private static final long ONE_TICK_JUMP_SPEED = 1L << 40;

    /** Ticks of the arrival cell watched after the arrival: the trigger fires on the first one a
     *  ship inside the radius spends settled there, and forty is that many times over. */
    private static final int STANDOFF_WATCH_TICKS = 40;

    /**
     * The shared acceptance: wait for the arrival production announces, then require the ship to be
     * VS-managed at the target cell's pose. Returns the arrival record so a caller can assert on the
     * mechanism too.
     *
     * <p><b>The route is a parameter because it is the subject.</b> This class exists to show that
     * the SPEED chooses the route, and the arrival event carries which one was taken — so the
     * acceptance now asserts the mechanism directly instead of inferring it from an in-transit count
     * that happens to be zero. A jump that went the other way no longer satisfies the other leg.</p>
     *
     * <p>No pump. The fixture runs on the server's own subsystem, so the jump is advanced by the
     * server tick like any other — and if it stops being, this fails.</p>
     */
    private String arrivesInTheTargetCell(long mark, String durableId, String route)
            throws Exception {
        // Narrowed by the CRAFT as well as the route: a route alone is a description, and a
        // sibling scenario arriving by the same route in the same window satisfies it.
        String arrived = events.awaitRecordWithFields(mark, "ship_transit_ended",
                "the ship never reached the target cell by the " + route + " route; the durable"
                        + " record now reads " + exec("artest space transit-export"),
                ARRIVAL_TICKS, "ship", durableId, "route", route);
        int targetDim = extractInt(arrived, "dim");
        assertTrue("the arrival was announced but names no dimension: " + arrived, targetDim >= 0);
        assertTrue("the ship never (re)loaded in the target cell (dim " + targetDim + "); countAll="
                + exec("artest vs ship-count-all " + targetDim), loadedShips(targetDim) >= 1);
        // Identified by the craft this scenario BUILT, which is now what the wait above is keyed
        // on. "The only loaded ship in this cell" stood here and was an accident: it worked while
        // an earlier scenario's hull unloaded once nobody was near it, and stopped the day a test
        // server began holding ships loaded — the cell then held two and the read could not say
        // which was this jump's. Re-reading the id off the record stood here next, and it answered
        // whichever ship the route-only wait had matched.
        String arrivedId = ShipIdentity.physicsIdOf(this::exec, targetDim, durableId.trim());
        assertTrue("the arrived ship is not VS-managed in the target cell; id=" + arrivedId,
                ShipInfo.loadedIn(this::exec, targetDim, arrivedId));
        return arrived;
    }

    private TransitSetup setUpPilotedShip() throws Exception {
        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        // A crossing resolves its ship by IDENTITY, never by the anchor every transit fixture
        // shares, so a setup that minted no durable name cannot be crossed at all.
        setup.requireDurableId();
        // ONE SHIP, ONE IDENTITY — production's rule, now pinned on the fixture that used to break
        // it. This build was once assembled off a STONE block of its own deck, so the computer's
        // durable name was never found and the substrate minted a second id; the reply then carried
        // two values for one craft, and a wait keyed on the wrong one expired with the log full of
        // records about the right one. The footprint form of the assembly fixed it by making the
        // mistake inexpressible — it finds the computer inside the pasted extent — and this
        // assertion is what keeps that true rather than leaving it to a comment.
        assertEquals("the fixture's two ids must be ONE value: a craft assembled off its own flight "
                        + "computer is named by it, and two different ids here mean the assembly "
                        + "found no computer and took a substrate-minted name instead: "
                        + setup.raw(),
                setup.durableId, setup.shipId);
        assertTrue("origin ship never assembled/loaded in the pool-slot cell (dim " + originDim + ")",
                loadedShips(originDim) >= 1);
        return setup;
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
    }

    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advanceWorld(client(), 0, ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** On the SERVER's clock: the world asked about is the one that may not be ticking yet. */
    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }

    private static String extractString(String json, String key) {
        return Reply.of(json).text(key);
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }
}
