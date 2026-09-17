package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        String setup = setUpPilotedShip();
        int originDim = extractInt(setup, "originDim");

        // Marked BEFORE the command whose effect is awaited.
        long jumpMark = events.mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 "
                + DIRECT_JUMP_SPEED);
        assertTrue("the short jump must begin: " + begin, begin.contains("\"began\":true"));
        assertEquals("a direct crossing is not a flight — nothing may be in transit the moment it "
                        + "starts, because there is no flight to be in the middle of: " + begin,
                0, extractInt(begin, "inTransit"));

        // The arrival names the route it was flown by, so this leg asserts the MECHANISM rather
        // than inferring it from an in-transit count that happens to read zero. A jump that went
        // through hyperspace no longer satisfies it.
        String arrived = arrivesInTheTargetCell(jumpMark, "DIRECT");
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

        String setup = setUpPilotedShip();
        int originDim = extractInt(setup, "originDim");

        // Marked BEFORE the command whose effect is awaited.
        long jumpMark = events.mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 "
                + HYPERSPACE_JUMP_SPEED);
        assertTrue("the jump must begin: " + begin, begin.contains("\"began\":true"));
        assertEquals("a slow jump IS a flight, and reports one: " + begin,
                1, extractInt(begin, "inTransit"));

        arrivesInTheTargetCell(jumpMark, "HYPERSPACE");
    }

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
    private String arrivesInTheTargetCell(long mark, String route) throws Exception {
        String arrived = events.awaitRecordWithField(mark, "ship_transit_ended","route", route,
                "the ship never reached the target cell by the " + route + " route; the durable"
                        + " record now reads " + exec("artest space transit-export"),
                ARRIVAL_TICKS);
        int targetDim = extractInt(arrived, "dim");
        assertTrue("the arrival was announced but names no dimension: " + arrived, targetDim >= 0);
        assertTrue("the ship never (re)loaded in the target cell (dim " + targetDim + "); countAll="
                + exec("artest vs ship-count-all " + targetDim), loadedShips(targetDim) >= 1);
        // Identified off the ARRIVAL'S OWN RECORD, which names the craft that arrived. "The only
        // loaded ship in this cell" stood here and was an accident: it worked while an earlier
        // scenario's hull unloaded once nobody was near it, and stopped the day a test server began
        // holding ships loaded — the cell then held two and the read could not say which was this
        // jump's. A count is a premise about the cell; the record is an identity.
        String durableId = Events.text(arrived, "ship");
        assertTrue("the arrival must name the craft that made it, or nothing below is addressed to"
                + " this jump's ship: " + arrived, durableId != null && !durableId.trim().isEmpty());
        String arrivedId = ShipIdentity.physicsIdOf(this::exec, targetDim, durableId.trim());
        assertTrue("the arrived ship is not VS-managed in the target cell; id=" + arrivedId,
                ShipInfo.loadedIn(this::exec, targetDim, arrivedId));
        return arrived;
    }

    private String setUpPilotedShip() throws Exception {
        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup failed: " + setup, setup.contains("\"ok\":true"));
        int originDim = extractInt(setup, "originDim");
        assertTrue("the fixture must mint a durable id — a crossing resolves its ship by identity, "
                + "never by the anchor every transit fixture shares: " + setup,
                setup.contains("\"durableId\":\"") && !setup.contains("\"durableId\":\"\""));
        // ONE SHIP, ONE IDENTITY — production's rule, now pinned on the fixture that used to break
        // it. This build was once assembled off a STONE block of its own deck, so the computer's
        // durable name was never found and the substrate minted a second id; the reply then carried
        // two values for one craft, and a wait keyed on the wrong one expired with the log full of
        // records about the right one. The footprint form of the assembly fixed it by making the
        // mistake inexpressible — it finds the computer inside the pasted extent — and this
        // assertion is what keeps that true rather than leaving it to a comment.
        assertEquals("the fixture's two ids must be ONE value: a craft assembled off its own flight "
                        + "computer is named by it, and two different ids here mean the assembly "
                        + "found no computer and took a substrate-minted name instead: " + setup,
                extractString(setup, "durableId"), extractString(setup, "shipId"));
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
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }
}
