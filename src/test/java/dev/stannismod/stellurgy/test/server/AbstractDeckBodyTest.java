package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipBirth;
import dev.stannismod.stellurgy.test.ShipInfo;

import org.junit.After;
import org.junit.Before;

import dev.stannismod.stellurgy.api.FreeFlightPhysics;
import dev.stannismod.stellurgy.test.contract.Wait;

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

    /** The server's log, read for the disposal of the craft this scenario built. */
    private final Events serverLog =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    /** Server ticks for the press to name the ship it assembled: a deadline on the record, not a dose. */
    private static final int BIRTH_LINK_TICKS = 200;

    /** The physics id of the craft {@link #buildCraft} built in this scenario, or null before it has. */
    private String builtShipId;

    @Before
    public void releaseHeldChunks() throws Exception {
        arrange("stellurgytest chunk release");
    }

    /** Dispose of this scenario's craft by its own id, so the shared server holds none afterwards. */
    @After
    public void disposeOfTheCraft() throws Exception {
        if (builtShipId != null) {
            Disposal.ship(c -> arrange(c).raw(), serverLog, 0, builtShipId);
        }
    }

    protected static final class Craft {
        final String shipId;
        final PilotSeat seat;

        Craft(String shipId, PilotSeat seat) {
            this.shipId = shipId;
            this.seat = seat;
        }
    }

    /** The open 5x5 deck: {@link #buildCraft(String)} of {@code with-pilot-deck}. */
    protected Craft buildCraft() throws Exception {
        return buildCraft("with-pilot-deck");
    }

    /**
     * Build, assemble and hold the chunks around a craft of fixture {@code variant} — one of the
     * pilot-deck family ({@code with-pilot-deck}, or {@code with-roofed-deck}: the same deck under a 5x5
     * roof), whose seat locates the deck.
     */
    protected Craft buildCraft(String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site(), c -> arrange(c).raw(), variant, 4, 12,
                "the craft whose deck the body lies on stands in this volume");
        long pressMark = serverLog.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        ShipBirth birth = ShipBirth.ofPress(serverLog, pressMark, 0, bp, asm, BIRTH_LINK_TICKS,
                "the craft whose deck the body lies on");
        builtShipId = birth.physicsId;
        requireArranged("the pilot-deck build must route to a ship, not a rocket: " + birth.rocketsBorn,
                birth.rocketsBorn.isEmpty());
        String shipId = birth.physicsId;
        PilotSeat seat = PilotSeat.byId(c -> read(c).raw(), 0, shipId)
                .requireFound("the seat locates the deck in subspace; without it there is nowhere to put a body");
        Reply held = Reply.of(arrange("stellurgytest chunk hold 0 " + seat.shipWorldX + " "
                + seat.shipWorldY + " " + seat.shipWorldZ + " " + HOLD_RADIUS_CHUNKS).raw());
        requireArranged("the chunks around the craft must be held, or the world may not tick what"
                + " lies on its deck: " + held, held.ok());
        return new Craft(shipId, seat);
    }

    /** Hold the craft at {@code rollDeg} about its X axis, and check it got there. */
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), SLEW_TICKS);",
            why = "a slew is a converging value and nothing announces its end, so the stretch is spent in full and"
                    + " the attitude is read once after it; the gate names the reading, so a slow box that did"
                    + " not finish fails as an arrangement, never as the subject.")
    protected void rollTo(Craft craft, double rollDeg) throws Exception {
        double half = Math.toRadians(rollDeg) / 2.0;
        requireArranged("the attitude hold must accept the roll command",
                Reply.of(arrange("stellurgytest vs point-by-id 0 " + craft.shipId + " "
                        + Math.cos(half) + " " + Math.sin(half) + " 0.0 0.0").raw()).bool("commanded"));
        GameTicks.advance(client(), GameTicks.server(), SLEW_TICKS);
        double upY = deckUpY(craft);
        double wanted = Math.cos(Math.toRadians(rollDeg));
        requireArranged("the deck must be held at " + rollDeg + " degrees of roll (deck-up world Y "
                + upY + ", wanted " + wanted + ")", Math.abs(upY - wanted) <= DECK_UP_Y_TOLERANCE);
    }

    protected double deckUpY(Craft craft) throws Exception {
        ShipInfo ship = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId);
        return new FreeFlightPhysics.Quat(ship.qw, ship.qx, ship.qy, ship.qz).rotate(0.0, 1.0, 0.0)[1];
    }

    /** A deck point {@code (dx, dy, dz)} from the seat, expressed in the world through this craft. */
    protected double[] toWorld(Craft craft, double dx, double dy, double dz) throws Exception {
        Reply r = Reply.of(read("stellurgytest vs to-world 0 id " + craft.shipId + " "
                + (craft.seat.seatX + dx) + " " + (craft.seat.seatY + dy) + " " + (craft.seat.seatZ + dz)).raw());
        requireArranged("the deck point must map to the world through this ship", r.ok());
        return new double[]{r.number("worldX"), r.number("worldY"), r.number("worldZ")};
    }

    /** Gate: the world is ticking the body where it lies - vanilla's own update-area test. */
    protected void requireTicked(Craft craft, int bodyId) throws Exception {
        Reply r = Reply.of(read("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId).raw());
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
            Reply r = Reply.of(read("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId).raw());
            // What it stands on, in the craft's subspace: a body grounded off the top face is standing
            // on SOMETHING, and a thin block (a snow layer is 0.125) is the first thing to rule out.
            String under = read("stellurgytest block at 0 " + (int) Math.floor(p[0]) + " "
                    + (int) Math.floor(p[1] - 0.01) + " " + (int) Math.floor(p[2])).raw();
            requireArranged("the body must have landed ON " + deck + "'s top face (ship-frame Y " + p[1]
                    + ", deck top " + craft.seat.seatY + "; ticksExisted " + p[3]
                    + "; the block under its feet " + under + "; " + r + ")", false);
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
        Reply r = Reply.of(read("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId).raw());
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
     * reading: the deck's own frame ({@code deckHeldBy}), or none - the substrate's collision.
     */
    protected String evidence(double[] from, double[] to, Craft craft, int bodyId) throws Exception {
        Reply r = Reply.of(read("stellurgytest vs player-ship-data 0 " + bodyId + " " + craft.shipId).raw());
        return " from=(" + from[0] + "," + from[1] + "," + from[2] + ") to=(" + to[0] + "," + to[1]
                + "," + to[2] + ") deckUpY=" + deckUpY(craft) + " deckHeldBy=" + r.textOr("deckHeldBy", "none");
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
        double before = Reply.of(read("stellurgytest planet info 0").raw()).number("gravity");
        try {
            arrange("ar planet set 0 gravitationalMultiplier " + multiplier);
            double now = Reply.of(read("stellurgytest planet info 0").raw()).number("gravity");
            requireArranged("the overworld's gravity multiplier must read " + multiplier + " (reads " + now
                    + ")", Math.abs(now - multiplier) <= 1e-4);
            scenario.run();
        } finally {
            arrange("ar planet set 0 gravitationalMultiplier " + before);
        }
    }
}
