package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.ShipReadiness;

import org.junit.Before;

import dev.stannismod.stellurgy.api.FreeFlightPhysics;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * The arrangement and the readings shared by every "a body lying on a deck" scenario: a craft with a
 * 5x5 deck, held at a commanded attitude, and a body's position read in THAT craft's frame.
 *
 * <p>Every position is read in the ship's frame, in one snapshot per reading, so the craft's own
 * station-keeping cannot be mistaken for the body moving, and every reading is split ALONG the deck and
 * ACROSS it: a body settling the last hair onto the surface and a body sliding are different claims.
 * Every "it stayed put" reading also asks that the world was simulating the body across the window -
 * a body nobody ticks stands perfectly still.</p>
 */
public abstract class AbstractDeckBodyTest extends AbstractSharedServerTest {

    /** How far the attitude hold may park from the commanded roll, as the deck-up vector's world Y. */
    protected static final double DECK_UP_Y_TOLERANCE = 0.02;

    /**
     * The OBSERVATION WINDOW the craft is given to slew, in server ticks. A converging value with no
     * announcement to link on, so it is spent in full and read once after; 200 is what the yaw slew
     * in {@code VSSeatDummyFacesTheShipE2ETest} takes for a turn of 90 deg.
     */
    protected static final int SLEW_TICKS = 200;

    /** The window a body is watched lying on the deck, in server ticks. */
    protected static final int REST_WINDOW_TICKS = 100;

    /**
     * How far a body at rest may move along the deck, or across it, in a window.
     *
     * <p>Measured 2026-09-29 over every scenario on both classes that use it, healthy: the largest
     * movement read was 1.9e-8 (an item on a deck held at 60 deg) - about 5 ulps at the shipyard's
     * coordinates (~1.92e7, an ulp 3.7e-9), the transform's own round-trip error. The bound is 50x
     * that. The smallest movement read in a run that was WRONG is 0.0054 (a stand on a steadily
     * rolling deck, held by the travel resolver), nearly four orders above it; the bound this
     * replaced, 0.05, let that one pass.</p>
     */
    protected static final double AT_REST = 1.0e-6;

    /** The steady roll rate, rad/s: 5 s of it turns the deck through about 143 deg, past inverted. */
    protected static final double ROLL_RATE = 0.5;

    /**
     * Chunks held around the craft, in chunks: 3 covers 48 blocks, past the 32 within which vanilla
     * requires every chunk loaded before it ticks a non-player entity at all
     * ({@code World.updateEntityWithOptionalForce}). The site's own fill loads only its own volume,
     * and measured 2026-09-29 the third plot of a class had an unloaded chunk inside that range: its
     * body was never ticked once and every "it stayed put" reading about it was about nothing.
     */
    private static final int HOLD_RADIUS_CHUNKS = 3;

    @Before
    public void clearCraft() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, 0);
        exec("stellurgytest chunk release");
    }

    protected static final class Craft {
        final String shipId;
        final PilotSeat seat;

        Craft(String shipId, PilotSeat seat) {
            this.shipId = shipId;
            this.seat = seat;
        }
    }

    protected Craft buildCraft() throws Exception {
        int[] bp = RocketFixture.placeAt(site(), this::exec, "with-pilot-deck", 4, 12,
                "the craft whose deck the body lies on stands in this volume");
        String asm = exec("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
        requireArranged("the pilot-deck build must route to a ship, not a rocket: " + asm,
                Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, ShipIdentity.nameFromAssembly(asm));
        PilotSeat seat = PilotSeat.byId(this::exec, 0, shipId)
                .requireFound("the seat locates the deck in subspace; without it there is nowhere to put a body");
        Reply held = Reply.of(exec("stellurgytest chunk hold 0 " + seat.shipWorldX + " "
                + seat.shipWorldY + " " + seat.shipWorldZ + " " + HOLD_RADIUS_CHUNKS));
        requireArranged("the chunks around the craft must be held, or the world may not tick what"
                + " lies on its deck: " + held, held.ok());
        return new Craft(shipId, seat);
    }

    /** Hold the craft at {@code rollDeg} about its X axis, and check it got there. */
    protected void rollTo(Craft craft, double rollDeg) throws Exception {
        double half = Math.toRadians(rollDeg) / 2.0;
        requireArranged("the attitude hold must accept the roll command",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + craft.shipId + " "
                        + Math.cos(half) + " " + Math.sin(half) + " 0.0 0.0")).bool("commanded"));
        // WINDOW: a slew is a converging value and nothing announces its end, so the stretch is
        // spent in full and the attitude is read once after it; the gate names the reading, so a
        // slow box that did not finish fails as an arrangement, never as the subject.
        GameTicks.advance(client(), GameTicks.server(), SLEW_TICKS);
        double upY = deckUpY(craft);
        double wanted = Math.cos(Math.toRadians(rollDeg));
        requireArranged("the deck must be held at " + rollDeg + " degrees of roll (deck-up world Y "
                + upY + ", wanted " + wanted + ")", Math.abs(upY - wanted) <= DECK_UP_Y_TOLERANCE);
    }

    protected double deckUpY(Craft craft) throws Exception {
        ShipInfo ship = ShipInfo.byId(this::exec, 0, craft.shipId);
        return new FreeFlightPhysics.Quat(ship.qw, ship.qx, ship.qy, ship.qz).rotate(0.0, 1.0, 0.0)[1];
    }

    /** A deck point {@code (dx, dy, dz)} from the seat, expressed in the world through this craft. */
    protected double[] toWorld(Craft craft, double dx, double dy, double dz) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs to-world 0 id " + craft.shipId + " "
                + (craft.seat.seatX + dx) + " " + (craft.seat.seatY + dy) + " " + (craft.seat.seatZ + dz)));
        requireArranged("the deck point must map to the world through this ship", r.ok());
        return new double[]{r.number("worldX"), r.number("worldY"), r.number("worldZ")};
    }

    /** Gate: the world is ticking the body where it lies - vanilla's own update-area test. */
    protected void requireTicked(Craft craft, int bodyId) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId));
        requireArranged("the world must be ticking the body where it lies (vanilla's own update-area"
                + " test), or nothing below is a reading of what the deck does with it: " + r,
                r.bool("updateAreaLoaded"));
    }

    /**
     * The body's deck point, gated on its lying ON the deck's top face - within {@link #AT_REST} of
     * it, not merely somewhere above. A body hanging where it was put is a body that never landed,
     * and every "it stayed put" read after that is about nothing.
     */
    protected double[] onTheDeckTop(Craft craft, int bodyId, String deck) throws Exception {
        double[] p = deckPoint(craft, bodyId);
        if (Math.abs(p[1] - craft.seat.seatY) > AT_REST) {
            Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId));
            requireArranged("the body must have landed ON " + deck + "'s top face (ship-frame Y " + p[1]
                    + ", deck top " + craft.seat.seatY + "; ticksExisted " + p[3] + "; ships containing it "
                    + exec("stellurgytest vs ships-at 0 " + r.number("playerX") + " " + r.number("playerY")
                    + " " + r.number("playerZ")) + "; " + r + ")", false);
        }
        return p;
    }

    /**
     * The body's position in the craft's frame, from one snapshot, as {x, y, z, ticksExisted}.
     *
     * <p>The fourth value is what lets a reading say it was blind: a body the world is not ticking
     * stands perfectly still, so every "it did not move" verdict also asks that it WAS moved - see
     * {@link #simulatedBetween}.</p>
     */
    protected double[] deckPoint(Craft craft, int bodyId) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId));
        requireArranged("the body must exist and be readable in the ship's frame: " + r,
                r.bool("shipResolved"));
        return new double[]{r.number("bodyShipFrameX"), r.number("bodyShipFrameY"),
                r.number("bodyShipFrameZ"), r.integer("ticksExisted")};
    }

    /** Gate: the world simulated the body for most of the window between two readings. */
    protected static void simulatedBetween(double[] first, double[] second, int window) {
        double ticked = second[3] - first[3];
        requireArranged("the world must have been simulating the body across the window, or a"
                + " reading of \"it stayed put\" is about nothing (ticked " + ticked + " of " + window
                + ")", ticked >= window / 2.0);
    }

    protected static double along(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[2] - a[2]);
    }

    protected static double across(double[] a, double[] b) {
        return Math.abs(b[1] - a[1]);
    }

    /**
     * The two readings and the deck's attitude, plus WHICH mechanism held the body at the second
     * reading: the deck's own frame ({@code deckHeldBy}), the travel resolver ({@code resolverHeldBy}),
     * or neither - the substrate's collision.
     */
    protected String evidence(double[] from, double[] to, Craft craft, int bodyId) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId));
        return " from=(" + from[0] + "," + from[1] + "," + from[2] + ") to=(" + to[0] + "," + to[1]
                + "," + to[2] + ") deckUpY=" + deckUpY(craft) + " deckHeldBy=" + r.textOr("deckHeldBy", "none")
                + " resolverHeldBy=" + r.textOr("resolverHeldBy", "none");
    }

    /** A stretch of a scenario that may throw. */
    protected interface Scenario {
        void run() throws Exception;
    }

    /**
     * Run {@code scenario} with the overworld's gravity multiplier set to {@code multiplier}, and put
     * back what was there, whatever the scenario did. A craft carries its own gravity for what lies
     * on its deck; a dimension whose gravity differs from 1 is the only arrangement in which "the
     * craft's gravity" and "the dimension's gravity" give different answers.
     */
    protected void withOverworldGravity(double multiplier, Scenario scenario) throws Exception {
        double before = Reply.of(exec("stellurgytest planet info 0")).number("gravity");
        try {
            exec("ar planet set 0 gravitationalMultiplier " + multiplier);
            double now = Reply.of(exec("stellurgytest planet info 0")).number("gravity");
            requireArranged("the overworld's gravity multiplier must read " + multiplier + " (reads " + now
                    + ")", Math.abs(now - multiplier) <= 1e-4);
            scenario.run();
        } finally {
            exec("ar planet set 0 gravitationalMultiplier " + before);
        }
    }
}
