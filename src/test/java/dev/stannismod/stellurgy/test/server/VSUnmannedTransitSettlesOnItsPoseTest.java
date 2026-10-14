package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.StellurgyTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * E2E: a jump with <b>nobody aboard and nobody nearby</b> must still finish on the pose realizing its
 * target coordinate — not in the arrival paste band.
 *
 * <p>This is the case the existing transit e2e cannot see. That test runs with the server's ships
 * held permanently loaded (as every test does, unless it says otherwise) and calls
 * {@code stellurgytest vs load-ships} while it waits, then asserts the ship is VS-managed at
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
public class VSUnmannedTransitSettlesOnItsPoseTest extends AbstractSharedServerTest {

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

        ShipReadiness.letShipsUnload(c -> envelope(client().arrange(c)),
                "an unmanned arrival must establish its own loadedness; held up by the harness,"
                + " \"the pose is realized\" would be asserted about a craft that was never let go");

        // A real craft — a deck, a flight computer, a pilot seat linked to it and a durable id. UNMANNED
        // is about who is ABOARD, not about what the hull is: a craft nobody can sit in could not be
        // flown manned either, so it cannot carry the contrast this test is named for.
        // Marked before the fixture is built, because the registry add it is waited on below happens
        // INSIDE that call: a mark taken after it would be waiting for a second ship.
        buildMark = events.mark();
        TransitSetup setup = TransitSetup.piloted(c -> envelope(client().arrange(c)));
        builtDurable = setup.requireDurableId();
        int originDim = setup.originDim;
        int ax = setup.anchorX, ay = setup.anchorY, az = setup.anchorZ;

        // The origin ship must be claimed by VS before the departure snapshots and cuts it — and it
        // is THIS craft, by its durable id, not "a ship appeared in that dim". The registry's own
        // add is the record (`ship_spawned`, from the queryable registry's `addShip`), so this ends
        // on the substrate claiming the hull rather than on a count that happened to be read after
        // it did. Claiming does not make the ship loaded, which is what this leg needs.
        events.awaitField(buildMark, "ship_spawned", "stellurgyShip", setup.durableId,
                "the origin ship must be registered before the departure can snapshot and cut it",
                REGISTER_TICKS);

        // Marked BEFORE the command whose effect is awaited.
        long transitMark = events.mark();
        String begin = envelope(client().act("stellurgytest space transit-begin " + originDim + " " + ax + " " + ay + " " + az
                + " " + HYPERSPACE_JUMP_SPEED));
        assertTrue("transit did not begin (departure crossing failed): " + begin,
                Reply.of(begin).bool("began"));

        // No pump: the server advances the jump. Waited for as the arrival production announces.
        String arrivedRecord = events.awaitRecordWithFields(transitMark, "ship_transit_ended",
                "the ship never arrived at all; the transit now reads "
                        + envelope(client().arrange("stellurgytest space transit-status")),
                ARRIVAL_TICKS, "ship", setup.requireDurableId(), "route", "HYPERSPACE");
        String lastTick = envelope(client().arrange("stellurgytest space transit-status"));

        // Positive control for the instrument: the probe must have RESOLVED this jump's own craft, by
        // its durable id, in the target world's registry. Without this, an assertion about where the
        // ship is would also pass on a run where the registry answered nothing.
        TransitStatus settled = TransitStatus.of(lastTick);
        String position = settled.craftPos;
        assertTrue("the target world registers no craft of this jump's name, so nothing below measures"
                + " the arrival: " + lastTick, !position.isEmpty());

        // The whole assertion, asked WITHOUT a position-keyed lookup: the ship's own transform position
        // must be the pose realizing the target coordinate. The arrival paste column sits at y=200 while
        // a cell's pose band is millions of blocks up, so a ship left in the paste lane is not "a bit
        // off" — it is a different world region, and its address inverts through the pose mapping into a
        // neighbouring cell. Compared as text on purpose: these are exact integers, and a tolerance here
        // would quietly accept the paste band on some future cell whose pose happens to be low.
        String expected = settled.poseX + "," + settled.poseY + "," + settled.poseZ;
        assertEquals("an unmanned arrival must settle ON the pose realizing its target coordinate; this"
                + " craft stands at " + position + ": " + lastTick, expected, position);
    }

    /** The durable name of the craft the setup built, and the mark taken before it; disposed of at the end. */
    private long buildMark;
    private String builtDurable;

    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> envelope(client().read(c)), c -> envelope(client().arrange(c)), events,
                    buildMark, builtDurable);
            builtDurable = null;
        }
    }

    /**
     * Run a probe and return ONLY its JSON envelope. The server writes its own log lines to the same
     * stream, so joining every returned line hands the assertions whatever unrelated line happened to
     * land in the window — a failure message quoting a shield-network rebuild is how this was found, and
     * a PASS read off such a line would have been just as wrong and just as silent.
     */
    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> envelope(client().read(c)), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    /** ONLY the JSON envelope of a probe's reply lines (each caller sends in its role). */
    private static String envelope(java.util.List<String> lines) {
        String envelope = "";
        for (String line : lines) {
            int brace = line.indexOf('{');
            if (brace >= 0 && line.endsWith("}")) {
                envelope = line.substring(brace);
            }
        }
        return envelope;
    }

    // The registry poll that stood here is gone, and its `extractInt` reader with it: `ship_spawned`
    // is the registry's own add, and waiting on it names THIS craft instead of counting whatever is
    // in a dimension.
}
