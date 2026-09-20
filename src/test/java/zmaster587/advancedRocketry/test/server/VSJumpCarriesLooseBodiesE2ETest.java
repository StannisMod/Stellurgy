package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipInfo;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

/**
 * <b>A jump carries what is lying on the deck, not only who is sitting on it.</b> The half of JUMP-11
 * that is not a crew member: a dropped item aboard a ship is at the destination after the jump, and
 * it is aboard the SHIP there rather than merely somewhere in the cell.
 *
 * <h2>Why this tier</h2>
 *
 * There is no client in this contract. A dropped item has no client that owns its movement, reports
 * nothing about itself, and is placed entirely by the server — so a server test IS the honest path,
 * and a client e2e would only add a boot to watch the same server state through a longer pipe.
 *
 * <h2>Why an item rather than a mob</h2>
 *
 * An item has no AI. A mob that ended up somewhere else after the jump could have walked there, and
 * separating "was not carried" from "was carried and then wandered" would need a second measurement;
 * an item that moved was moved by something. It is also the body a player is most likely to have
 * lying about — the thing he dropped while building.
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSJumpCarriesLooseBodiesE2ETest extends AbstractSharedServerTest {

    /** How close to the ship the body must land to count as aboard it rather than merely in the cell. */
    private static final double ABOARD_RADIUS = 8.0;

    /**
     * Budgets in SERVER TICKS, none fork-scaled: 400 is the twenty seconds the old 80 x 250 ms meant
     * on an idle box, 300 the fifteen of 60 x 250 ms for the retry-based placement, 200 the ten of
     * 40 x 250 ms for a ship becoming loadable.
     */
    private static final int ARRIVAL_TICKS = 400;
    private static final int PLACEMENT_TICKS = 300;

    @Test
    public void aJumpCarriesTheBodiesLyingOnItsDeck() throws Exception {


        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        requireArranged("the origin ship never assembled/loaded (dim " + originDim + ")",
                loadedShips(originDim) >= 1);

        // The ship the setup just assembled, by the name the setup reports. Every scenario in this
        // tier builds at the SAME anchor in the SAME pooled slot, so "the ship at (1,64,1)" is a
        // question with several right answers and the yard lookup takes the first — measured
        // elsewhere as seatFound:false on a craft that had just been built.
        String shipId = setup.requireShipId();

        // Where the ship actually is in its cell — the deck the body is dropped onto.
        PilotSeat seat = PilotSeat.byId(this::exec, originDim, shipId)
                .requireFound("the ship must resolve a world position for the body to be dropped at");
        double shipX = seat.shipWorldX;
        double shipY = seat.shipWorldY;
        double shipZ = seat.shipWorldZ;

        // Dropped AT the hull, not above it. A body spawned over a deck is a body falling, and this
        // fixture sits in a void cell: by the time the cut runs it can be well past the ship, which
        // makes "it was not carried" indistinguishable from "it was not there". The ship's own
        // identity is handed in so the probe can answer production's question rather than a proxy.
        String dropped = exec("artest space loose-body " + originDim + " " + shipX + " " + shipY + " "
                + shipZ + " " + shipId);
        requireArranged("the body must be dropped: " + dropped, Reply.of(dropped).ok());

        // CONTROL, and it is production's OWN aboard test rather than a proximity proxy: the body has
        // to be inside the ship's stay region — the same volume the crossing enumerates by, and the
        // same one the hyperspace void judges a crew member by. A green here means a later red is
        // about the carry.
        requireArranged("the dropped body must be ABOARD by the definition the crossing uses,"
                + " not merely near the ship: " + dropped, Reply.of(dropped).bool("aboard"));

        // Marked BEFORE the command whose effect is awaited.
        long transitMark = events.mark();
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + HYPERSPACE_JUMP_SPEED);
        assertTrue("the transit must begin: " + begin, Reply.of(begin).bool("began"));

        // No pump: the server advances the jump. Waited for as the arrival production announces.
        String arrivedRecord = events.awaitRecordWithFields(transitMark, "ship_transit_ended",
                "the jump never completed; the transit now reads "
                        + exec("artest space transit-status"),
                ARRIVAL_TICKS, "ship", setup.requireDurableId(), "route", "HYPERSPACE");
        int targetDim = extractInt(arrivedRecord, "dim");
        assertTrue("the arrival was announced but names no dimension: " + arrivedRecord,
                targetDim >= 0);

        // Still a POLL, and legitimately: the placement retries until the body is put down, which is
        // a converging state and not an event -- nothing announces it. What is gone is the pump that
        // used to sit INSIDE this predicate: a condition is asked and must change nothing, and the
        // arrival it was driving is advanced by the server anyway.
        //
        // The cell's ship count NAMES what it counted, so the arrived hull is identified rather than
        // approached; the count is still read on every iteration, because the ship is still arriving
        // and "how many are in there" is precisely what changes while the loop runs.
        final String[] arrived = {""};
        boolean carried = GameTicks.until(client(), GameTicks.server(), PLACEMENT_TICKS, () -> {
            String counted = exec("artest vs ship-count " + targetDim);
            String[] named = Reply.of("artest vs ship-count", counted).textArray("ships");
            if (extractInt(counted, "count") != 1 || named.length != 1) {
                return false; // not arrived yet, or not alone — either way not a nameable answer
            }
            arrived[0] = exec("artest vs ship-info " + targetDim + " id " + named[0]);
            if (!ShipInfo.isLoaded(arrived[0])) {
                return false; // the craft is not in this world yet — keep waiting, do not read a pose
            }
            ShipInfo ship = ShipInfo.of(arrived[0]);
            double px = ship.x;
            double py = ship.y;
            double pz = ship.z;
            return extractInt(exec("artest space loose-body-count " + targetDim + " " + px + " "
                    + py + " " + pz + " " + ABOARD_RADIUS), "count") >= 1;
        });

        assertTrue("a body lying on the deck must arrive WITH the ship — the crew is not the only "
                + "thing aboard a jump. Ship report at the destination: " + arrived[0], carried);

        // ...and it is not still lying in the cell it left, which is the failure this replaces: a body
        // left behind is also "somewhere", and only asking both ends tells the two apart.
        int leftBehind = extractInt(exec("artest space loose-body-count " + originDim + " " + shipX
                + " " + shipY + " " + shipZ + " " + ABOARD_RADIUS), "count");
        assertEquals("nothing may be left standing in the origin cell where the ship used to be",
                0, leftBehind);
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

    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }

    private static int extractInt(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, -1);
    }

    private static String extractString(String json, String key) {
        assertTrue("expected string \"" + key + "\" in: " + json, Reply.of(json).has(key));
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).textOr(key, null);
    }

    private static double extractDouble(String json, String key) {
        assertTrue("expected number \"" + key + "\" in: " + json, Reply.of(json).has(key));
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, Double.NaN);
    }
}
