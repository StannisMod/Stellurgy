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
 * what hands it back to physics. This is the server tier's form of the client base's
 * {@code liftClearOfThePad}, and it keeps that helper's two stretches for the same reasons.</p>
 */
public final class ShipLift {

    /**
     * Ticks of the craft's world clock between the teleport and the unpark. The teleport writes the
     * GAME-side transform and the loaded physics object adopts it on a later tick —
     * {@code VSBridge.teleportShip} says its caller unparks "once the adoption has propagated".
     * Unparked sooner, the physics re-integrates its own pose and the craft is back on its pad. The
     * 30 the client lift has always given it.
     */
    static final int ADOPTION_TICKS = 30;

    /**
     * Ticks of physics running unparked before the arrival is read: a craft whose adoption did not
     * take is back at its pad by then, and the read below names where it was sent and where it is.
     */
    static final int UNPARKED_TICKS = 10;

    /**
     * How far from the target altitude the craft may report and still count as having arrived. A
     * ship's reported position is its centre of mass, which sits wherever its hull puts it; this is
     * the client lift's bound, and the refusal it guards is a craft back on a pad tens of blocks
     * below.
     */
    static final double ARRIVED_WITHIN_BLOCKS = 20.0;

    private ShipLift() {
    }

    /**
     * Teleport the craft named {@code shipId} in {@code dim} to {@code toY} above where it stands,
     * unpark it, and answer its report once it has flown unparked there.
     *
     * @param server the harness client whose clock the stretches are counted on
     * @param what   a scenario-facing sentence for why the craft has to be up there
     */
    public static ShipInfo toAltitude(Events.Probe probe, TestClient server, int dim, String shipId,
                                      int toY, String what) throws Exception {
        ShipInfo onThePad = ShipInfo.byId(probe::exec, dim, shipId);
        String moved = probe.exec("stellurgytest vs teleport-ship-by-id " + dim + " " + shipId
                + " " + onThePad.x + " " + toY + " " + onThePad.z);
        requireArranged(what + " — the lift to y=" + toY + " must take: " + moved, Reply.of(moved).ok());
        // EXPERIMENT: the adoption stretch, see ADOPTION_TICKS; the read below reports one too short.
        GameTicks.advanceWorld(server, dim, ADOPTION_TICKS);
        String unparked = probe.exec("stellurgytest vs unpark-by-id " + dim + " " + shipId);
        requireArranged(what + " — the rigid teleport leaves the ship PARKED, and a parked ship is"
                + " not simulated: " + unparked, Reply.of(unparked).ok());
        // WINDOW: from the teleport's write to the read below, over ticks of physics running
        // unparked; the gate names where the craft was sent and where it is.
        GameTicks.advanceWorld(server, dim, UNPARKED_TICKS);
        ShipInfo lifted = ShipInfo.byId(probe::exec, dim, shipId);
        requireArranged(what + " — the craft was sent to y=" + toY + " and reports y=" + lifted.y
                + " (it stood at y=" + onThePad.y + ")", Math.abs(lifted.y - toY) < ARRIVED_WITHIN_BLOCKS);
        return lifted;
    }
}
