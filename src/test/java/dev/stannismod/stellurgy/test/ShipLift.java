package dev.stannismod.stellurgy.test;

import com.github.stannismod.forge.testing.server.TestClient;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * Move one assembled craft, by identity, to an altitude — clear of the launchpad it was built on —
 * and hand it back to the solver there.
 *
 * <p>A craft stands on its pad when it is assembled, and the pad is WORLD blocks, not part of the
 * ship: a craft released there falls one block onto it and stops, which reads exactly like a craft
 * that is not falling. Every scenario whose subject is a released or a driven craft therefore starts
 * by taking it off the pad.</p>
 *
 * <p>The move is the substrate's own rigid teleport, which leaves the ship PARKED; the unpark is
 * what hands it back to physics — and only once the game tick has ADOPTED the written pose. The
 * teleport writes the craft's stored transform and flags the loaded physics object to take it on its
 * next tick ({@code PhysicsObject.onTick}'s forced branch). Unparked before that, the physics thread
 * steps the craft from its own old pose and it is back on its pad. The physics thread steps only
 * unparked craft, so for a parked one that tick is the whole of the adoption, and it is a link:
 * {@code ship_pose_adopted} for this craft, from a mark taken before the teleport.</p>
 */
public final class ShipLift {

    /** A LINK budget for the adoption, in ticks of the log's clock: its expiry means no tick adopted. */
    static final int ADOPTION_LINK_TICKS = 200;

    /**
     * Ticks of physics running unparked before the arrival is read: a craft whose adoption did not
     * take is back at its pad by then, and the read below names where it was sent and where it is.
     */
    static final int UNPARKED_TICKS = 10;

    /**
     * How far from the target altitude the craft may report and still count as having arrived.
     * Measured 2026-09-30, three lifts of the seat fixture to y=250: each reported exactly 250.0 after
     * the unparked stretch, and its pad stood 96.7 blocks below. The refusal it guards is a craft
     * back on that pad; the bar sits far inside the gap on both sides.
     */
    static final double ARRIVED_WITHIN_BLOCKS = 20.0;

    private ShipLift() {
    }

    /**
     * Teleport the craft named {@code shipId} in {@code dim} to {@code toY} above where it stands,
     * unpark it, and answer its report once it has flown unparked there.
     *
     * @param serverLog the SERVER's event log, where {@code ship_pose_adopted} is recorded
     * @param server    the harness client whose clock the stretches are counted on
     * @param what      a scenario-facing sentence for why the craft has to be up there
     */
    public static ShipInfo toAltitude(Events.Probe probe, Events serverLog, TestClient server, int dim,
                                      String shipId, int toY, String what) throws Exception {
        ShipInfo onThePad = ShipInfo.byId(probe::exec, dim, shipId);
        long teleportMark = serverLog.markInstrumented();
        String moved = probe.exec("stellurgytest vs teleport-ship-by-id " + dim + " " + shipId
                + " " + onThePad.x + " " + toY + " " + onThePad.z);
        requireArranged(what + " — the lift to y=" + toY + " must take: " + moved, Reply.of(moved).ok());
        ArrangementFailure.arranged(() -> serverLog.awaitRecordWithFields(teleportMark, "ship_pose_adopted",
                what + " — the game tick must adopt the lifted pose before the craft is unparked",
                ADOPTION_LINK_TICKS, "vsShip", shipId, "dim", String.valueOf(dim)));
        String unparked = probe.exec("stellurgytest vs unpark-by-id " + dim + " " + shipId);
        requireArranged(what + " — the rigid teleport leaves the ship PARKED, and a parked ship is"
                + " not simulated: " + unparked, Reply.of(unparked).ok());
        // WINDOW: from the teleport's write to the read below, over ticks of physics running
        // unparked; the gate names where the craft was sent and where it is.
        GameTicks.advanceWorld(server, dim, UNPARKED_TICKS);
        ShipInfo lifted = ShipInfo.byId(probe::exec, dim, shipId);
        System.out.println("[lift] dim " + dim + " sent to y=" + toY + ", reports y=" + lifted.y
                + " after " + UNPARKED_TICKS + " ticks unparked (stood at y=" + onThePad.y + ")");
        requireArranged(what + " — the craft was sent to y=" + toY + " and reports y=" + lifted.y
                + " (it stood at y=" + onThePad.y + ")", Math.abs(lifted.y - toY) < ARRIVED_WITHIN_BLOCKS);
        return lifted;
    }
}
