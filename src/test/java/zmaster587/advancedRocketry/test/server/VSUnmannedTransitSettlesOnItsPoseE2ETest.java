package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.TransitStatus;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipReadiness;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertTrue;

/**
 * E2E: a jump with <b>nobody aboard and nobody nearby</b> must still finish on the pose realizing its
 * target coordinate — not in the arrival paste band.
 *
 * <p>This is the case the existing transit e2e cannot see. That test runs with the server's ships
 * held permanently loaded (as every test does, unless it says otherwise) and calls
 * {@code artest vs load-ships} while it waits, then asserts the ship is VS-managed at
 * {@code (0,200,0)} — the paste column. So it is handed the loadedness the arrival is supposed to
 * establish for itself, and it pins the paste band AS success. An arrival that never reaches its
 * pose passes it.</p>
 *
 * <p>Here neither affordance is used: no permaload, no forced load. That matters because the real
 * deferral in an arrival is not asynchrony but a POLICY — Valkyrien Skies loads a ship only when a
 * player is within its load distance, and queues an unload every tick for one that is not — so an
 * unmanned arrival is precisely the case a readiness gate on "is the ship loaded" can never satisfy.
 * The observation side is safe to leave un-forced: the probe reads through the queryable ship registry,
 * which answers for an unloaded ship.</p>
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSUnmannedTransitSettlesOnItsPoseE2ETest extends AbstractSharedServerTest {

    /**
     * Each poll ticks the transit once, so this must exceed the arrival retry budget (200) or the run
     * ends while the ship is still trying and never reaches the question this test asks. A healthy
     * arrival exits the loop after a tick or two; only a stalled one spends the whole budget.
     */
    /**
     * How much WORLD an unmanned arrival gets: 260 server ticks, the thirteen seconds the old
     * 260 x 50 ms poll loop meant on an idle box, with no fork multiplier. And 200 for a ship
     * appearing in the registry - ten seconds, as the old 40 x 250 ms meant.
     */
    private static final int ARRIVAL_TICKS = 260;
    private static final int REGISTER_TICKS = 200;

    @Test
    public void anUnmannedJumpEndsOnItsPoseNotInThePasteBand() throws Exception {

        ShipReadiness.letShipsUnload(this::exec,
                "an unmanned arrival must establish its own loadedness; held up by the harness,"
                + " \"the pose is realized\" would be asserted about a craft that was never let go");

        // A real craft — a deck, a flight computer, a pilot seat linked to it and a durable id. UNMANNED
        // is about who is ABOARD, not about what the hull is: a craft nobody can sit in could not be
        // flown manned either, so it cannot carry the contrast this test is named for.
        TransitSetup setup = TransitSetup.piloted(this::exec);
        int originDim = setup.originDim;
        int ax = setup.anchorX, ay = setup.anchorY, az = setup.anchorZ;

        // The origin ship must be claimed by VS before the departure snapshots and cuts it. Asked through
        // the queryable registry, so this waits for the ship to EXIST without making it loaded.
        assertTrue("origin ship never registered in the pool-slot cell (dim " + originDim + ")",
                waitForRegisteredShip(originDim));

        // Marked BEFORE the command whose effect is awaited.
        long transitMark = events.mark();
        String begin = exec("artest space transit-begin " + originDim + " " + ax + " " + ay + " " + az
                + " " + HYPERSPACE_JUMP_SPEED);
        assertTrue("transit did not begin (departure crossing failed): " + begin,
                Reply.of(begin).bool("began", false));

        // No pump: the server advances the jump. Waited for as the arrival production announces.
        String arrivedRecord = events.awaitField(transitMark, "ship_transit_ended","route", "HYPERSPACE",
                "the ship never arrived at all; the transit now reads "
                        + exec("artest space transit-status"),
                ARRIVAL_TICKS);
        String lastTick = exec("artest space transit-status");

        // Positive control for the instrument: the probe must have RESOLVED the arrived ship at all.
        // Without this, an assertion about where the ship is would also pass on a run where the registry
        // answered nothing — which is the opposite of what we mean to assert.
        // Control first: the target world must actually hold a ship, or "its position is not X" below
        // would pass on a run where the ship had vanished — the opposite of what this asserts.
        String positions = Reply.of("artest space transit-tick", lastTick).text("ships");
        assertTrue("the probe reported no ships field at all: " + lastTick, positions != null);
        assertTrue("the target world holds no ship, so nothing below measures the arrival: " + lastTick,
                !positions.isEmpty());

        // The whole assertion, asked WITHOUT a position-keyed lookup: the ship's own transform position
        // must be the pose realizing the target coordinate. The arrival paste column sits at y=200 while
        // a cell's pose band is millions of blocks up, so a ship left in the paste lane is not "a bit
        // off" — it is a different world region, and its address inverts through the pose mapping into a
        // neighbouring cell. Compared as text on purpose: these are exact integers, and a tolerance here
        // would quietly accept the paste band on some future cell whose pose happens to be low.
        TransitStatus settled = TransitStatus.of(lastTick);
        String expected = settled.poseX + "," + settled.poseY + "," + settled.poseZ;
        assertTrue("an unmanned arrival must settle ON the pose realizing its target coordinate; expected "
                + "a ship at " + expected + " but the world holds " + positions + ": " + lastTick,
                positions.contains(expected));
    }

    /**
     * Run a probe and return ONLY its JSON envelope. The server writes its own log lines to the same
     * stream, so joining every returned line hands the assertions whatever unrelated line happened to
     * land in the window — a failure message quoting a shield-network rebuild is how this was found, and
     * a PASS read off such a line would have been just as wrong and just as silent.
     */
    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advanceWorld(client(), 0, ticks));

    private String exec(String cmd) throws Exception {
        String envelope = "";
        for (String line : client().execute(cmd)) {
            int brace = line.indexOf('{');
            if (brace >= 0 && line.endsWith("}")) {
                envelope = line.substring(brace);
            }
        }
        return envelope;
    }

    /** Poll until VS's queryable registry holds a ship in {@code dim}; never forces a load. */
    private boolean waitForRegisteredShip(int dim) throws Exception {
        return GameTicks.until(client(), GameTicks.server(), REGISTER_TICKS,
                () -> extractInt(exec("artest vs ship-count-all " + dim), "count") >= 1);
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }
}
