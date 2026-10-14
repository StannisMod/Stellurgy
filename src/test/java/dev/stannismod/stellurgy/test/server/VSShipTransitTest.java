package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.StellurgyTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertTrue;

/**
 * E2E: does the transit subsystem move a live VS ship between bubble cells? A ship assembled in a fresh
 * origin cell (pool slot world) departs into the shared hyperspace world, {@code ShipTransit} advances
 * its coordinate, and on arrival it crosses into a fresh target cell and re-VSes there. Proves the wiring
 * - the two per-ship crossings, the hyperspace hosting, and the origin&rarr;target refcount handoff -
 * composes in real, dynamically-registered worlds (not just the pure state machine's unit tests). Builds
 * on the proven per-ship crossing ({@code VSShipCrossingSpikeTest}) and on VS surviving in a pool-slot
 * world; the state machine itself is pinned deterministically by {@code ShipTransitManagerTest}.
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSShipTransitTest extends AbstractSharedServerTest {

    /**
     * Budgets in SERVER TICKS — 400 is the twenty seconds the old {@code 80 x 250 ms} meant on an idle
     * box, 200 the ten seconds of {@code 40 x 250 ms}. Neither carries a fork multiplier: how much of
     * the machine this test shares says nothing about how much world an arrival needs.
     */
    private static final int ARRIVAL_TICKS = 400;

    @Test
    public void aVsShipTransitsFromOneCellToAnotherThroughHyperspace() throws Exception {

        // Headless: pin ships loaded so a freshly assembled ship does not auto-unload between probe calls.

        // Build a real craft in a fresh origin cell (a pool slot world) + the whole transit stack.
        buildMark = events.mark();
        TransitSetup setup = TransitSetup.piloted(c -> arrange(c).raw());
        builtDurable = setup.requireDurableId();
        int originDim = setup.originDim;
        int ax = setup.anchorX, ay = setup.anchorY, az = setup.anchorZ;

        // The origin ship must exist + load before we depart it (the departure crossing snapshots it):
        // linked on THIS craft's own announcement that it is usable in the origin cell.
        ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events,
                originDim, setup.requireDurableId(), USABLE_TICKS));

        // Depart: begin the jump. The ship leaves the origin cell for hyperspace — at a speed that
        // makes it a real flight, because a fast enough jump is performed as a single crossing instead
        // and this test is about the hyperspace path. The speed is what chooses between them; the
        // fixture is a real craft and could take either.
        // Marked BEFORE the command whose effect is awaited.
        long transitMark = events.mark();
        String begin = act("stellurgytest space transit-begin " + originDim + " " + ax + " " + ay + " " + az
                + " " + HYPERSPACE_JUMP_SPEED).raw();
        assertTrue("transit did not begin (departure crossing failed): " + begin, Reply.of(begin).bool("began"));

        // NO PUMP. The fixture now runs on the server's own subsystem, so the jump is advanced by
        // SpaceSubsystemEvents like any other -- and what this waits for is the arrival production
        // announces, not a counter sampled until it reads zero. Both halves matter: the wait proves
        // the server drives a transit (the old loop drove it by hand and could not have noticed if
        // production stopped), and the record proves the arrival happened rather than that a sample
        // caught a moment.
        String arrived = events.awaitRecordWithFields(transitMark, "ship_transit_ended",
                "the jump never completed; the durable record now reads "
                        + read("stellurgytest space transit-export").raw(),
                ARRIVAL_TICKS, "ship", setup.requireDurableId(), "route", "HYPERSPACE");
        // The dimension the arrival event was posted IN, which is the slot holding the target cell.
        int targetDim = extractInt(arrived, "dim");
        assertTrue("the arrival was announced but names no dimension: " + arrived, targetDim >= 0);

        // The re-assembled ship must load + be VS-managed in the TARGET cell (arrival pastes near
        // 0,200,0): THIS craft, by the durable name the jump carried, linked on its own announcement
        // that it is usable there.
        String arrivedId = ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events, targetDim,
                setup.requireDurableId(), USABLE_TICKS);
        assertTrue("arrived ship is not VS-managed in the target cell (transit did not re-VS);"
                + " id=" + arrivedId, ShipInfo.loadedIn(c -> read(c).raw(), targetDim, arrivedId));
    }

    /** Server ticks for the craft to become usable where it was built or has arrived. */
    private static final int USABLE_TICKS = 200;

    /** The mark taken before this scenario's setup, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
    }

    // --- helpers (mirror VSShipCrossingSpikeTest) ---------------------------------------------------

    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }
}
