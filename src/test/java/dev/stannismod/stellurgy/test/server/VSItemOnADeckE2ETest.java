package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.ShipReadiness;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.api.FreeFlightPhysics;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * <b>An item lying on a deck behaves as it would lying on the ground</b>, whatever the craft's
 * attitude: it falls toward the deck and rests there, it stays where anything that places it puts it,
 * and a push it is given moves it and then runs out.
 *
 * <h2>Why this tier, and why an item</h2>
 *
 * A dropped item has no AI and no client that owns its movement; the server places it entirely, so a
 * server test is the honest path and any displacement it shows has exactly one author. It also has
 * its own gravity and ground friction, distinct from a living body's, so it asks whether the deck
 * holds an entity by the rules of THAT entity rather than by a copy written for crew members.
 *
 * <h2>How it reads</h2>
 *
 * Every position is read in the SHIP's frame, in one snapshot per reading, so the craft's own
 * station-keeping cannot be mistaken for the item moving, and every reading is split ALONG the deck
 * and ACROSS it: an item settling the last hair onto the surface and an item sliding are different
 * claims.
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSItemOnADeckE2ETest extends AbstractSharedServerTest {

    /** How far the attitude hold may park from the commanded roll, as the deck-up vector's world Y. */
    private static final double DECK_UP_Y_TOLERANCE = 0.02;

    /**
     * The OBSERVATION WINDOW the craft is given to slew, in server ticks. A converging value with no
     * announcement to link on, so it is spent in full and read once after; 200 is what the yaw slew
     * in {@code VSSeatDummyFacesTheShipE2ETest} takes for a turn of 90 deg.
     */
    private static final int SLEW_TICKS = 200;

    /** How long the dropped item is given to land on the deck half a block below it. */
    private static final int LAND_TICKS = 20;

    /** The window the item is watched lying on the deck, in server ticks. */
    private static final int REST_WINDOW_TICKS = 100;

    /**
     * How far an item at rest may move along the deck, or across it, in a window. An item lying on
     * level ground moves by float noise; anything past this is something moving it.
     */
    private static final double AT_REST = 0.05;

    /**
     * The push given in the impulse scenario, in blocks per tick, and the least it must carry the
     * item. On ground friction an item keeps 0.588 of its speed per tick, so the push is worth about
     * {@code 0.3 / (1 - 0.588) ~ 0.73} blocks; a third of that separates "the push was felt" from
     * "the push was discarded" with room for the first tick being spent airborne.
     */
    private static final double PUSH = 0.3;
    private static final double PUSH_MUST_CARRY = 0.25;

    @Before
    public void clearCraft() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, 0);
        exec("stellurgytest chunk release");
    }

    /**
     * Chunks held around the craft, in chunks: 3 covers 48 blocks, past the 32 within which vanilla
     * requires every chunk loaded before it ticks a non-player entity at all
     * ({@code World.updateEntityWithOptionalForce}). The site's own fill loads only its own volume,
     * and measured 2026-09-29 the third plot of this class had an unloaded chunk inside that range:
     * its item was never ticked once and every "it stayed put" reading about it was about nothing.
     */
    private static final int HOLD_RADIUS_CHUNKS = 3;

    // -- an item falls toward the deck and rests on it, at any attitude ------------------------------------------------------

    /**
     * red-witnessed: 2026-09-29, with the deck point re-derived from the world position on every
     * entry to {@code DeckFrameTick.update} and no end-of-tick re-image - along 0.13. (With the
     * substrate holding the item it fails as an ARRANGEMENT instead: the item rests 0.061 off the
     * face.)
     */
    @Test
    public void anItemDroppedOnADeckRolled30DegreesStaysWhereItLanded() throws Exception {
        restsOnADeckRolledBy(30.0);
    }

    /**
     * red-witnessed: 2026-09-29, with the end-of-tick re-image but the deck point re-derived on entry
     * - along 0.26. (With the substrate holding the item: an ARRANGEMENT failure, 0.107 off the face.)
     */
    @Test
    public void anItemDroppedOnADeckRolled60DegreesStaysWhereItLanded() throws Exception {
        restsOnADeckRolledBy(60.0);
    }

    /**
     * The deck rolls over while the item is lying on it. Past a right angle the world's down points
     * away from the deck, so this is the arrangement in which "falls toward the deck" and "falls
     * toward the world" give different answers: a static deck below that cannot tell them apart, and
     * measured on 2026-09-29 the substrate's own collision held an item still at both 30 deg and 60 deg.
     *
     * <p>red-witnessed: 2026-09-29, with {@code MixinWorldDeckFrameTick} unregistered (the substrate
     * holding the item) - along 0.42, across 0.28; and with the deck point re-derived from the world
     * position on every entry to {@code DeckFrameTick.update} - along 0.53.</p>
     */
    @Test
    public void anItemLyingOnADeckStaysOnItWhileTheCraftRollsOver() throws Exception {
        Craft craft = buildCraft();
        int itemId = dropOnDeck(craft, 1.5, 0.5, false);
        double[] landed = onTheDeckTop(craft, itemId, "the level deck");

        rollTo(craft, 150.0);
        requireArranged("the deck must have rolled past a right angle, or world-down and deck-down"
                + " still agree and nothing below can fail (deck-up world Y " + deckUpY(craft) + ")",
                deckUpY(craft) < -0.5);

        double[] later = deckPoint(craft, itemId);
        simulatedBetween(landed, later, SLEW_TICKS);
        String evidence = evidence(landed, later, craft, itemId);
        double along = along(landed, later), across = across(landed, later);
        System.out.println("[deck-rest] rollover along=" + along + " across=" + across + evidence);
        assertTrue("an item lying on a deck must still be lying on it after the craft rolls over: it is "
                + across + " blocks off the deck's top face;" + evidence, across <= AT_REST);
        assertTrue("an item lying on a deck must not slide along it while the craft rolls over: it moved "
                + along + " blocks along the deck;" + evidence, along <= AT_REST);
    }

    /**
     * The craft keeps ROLLING at a steady rate under an item lying on its deck - no hold to settle
     * into, the deck carrying the item round through every attitude, inverted included. The slew
     * scenario above ends at rest; this one is read while the craft is still turning.
     *
     * <p>red-witnessed: 2026-09-29, with {@code MixinWorldDeckFrameTick} unregistered - along 1.39,
     * across 0.27.</p>
     */
    @Test
    public void anItemLyingOnADeckStaysOnItWhileTheCraftKeepsRolling() throws Exception {
        Craft craft = buildCraft();
        int itemId = dropOnDeck(craft, 1.5, 0.5, false);
        double[] landed = onTheDeckTop(craft, itemId, "the level deck");

        double upBefore = deckUpY(craft);
        Reply spun = Reply.of(exec("stellurgytest vs force-rot-by-id 0 " + craft.shipId + " "
                + ROLL_RATE + " 0 0"));
        requireArranged("the craft must accept the roll command: " + spun, spun.bool("afcResolved"));
        // EXPERIMENT: a stretch of steady rolling; the read is taken with the craft still turning.
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, itemId);
        ShipInfo turning = ShipInfo.byId(this::exec, 0, craft.shipId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        requireArranged("the craft must still be turning when the item is read (omega "
                + turning.omega + ")", turning.omega > ROLL_RATE / 4.0);
        requireArranged("the deck must have turned well away from level, or a deck-down and a"
                + " world-down fall read alike (deck-up world Y " + upBefore + " -> " + deckUpY(craft)
                + ")", Math.abs(deckUpY(craft) - upBefore) > 0.5);

        String evidence = evidence(landed, later, craft, itemId) + " omega=" + turning.omega;
        System.out.println("[deck-rest] rolling along=" + along(landed, later) + " across="
                + across(landed, later) + evidence);
        assertTrue("an item lying on a rolling deck must stay on its top face: " + across(landed, later)
                + " blocks off;" + evidence, across(landed, later) <= AT_REST);
        assertTrue("an item lying on a rolling deck must not slide along it: " + along(landed, later)
                + " blocks;" + evidence, along(landed, later) <= AT_REST);
        exec("stellurgytest vs force-clear-by-id 0 " + craft.shipId);
    }

    /** The steady roll rate, rad/s: 5 s of it turns the deck through about 143 deg, past inverted. */
    private static final double ROLL_RATE = 0.5;

    // -- a position somebody writes is where the item is ----------------------------------------------

    /**
     * Something places an item already lying on the deck somewhere else on it - the way a teleport
     * or another mod would. The item is where it was put, and stays there: nothing the deck
     * remembers about where the item used to be may pull it back.
     *
     * <p>red-witnessed: 2026-09-29, without {@code DeckFrameTick.clearOfMappingNoise} - the write came
     * back 2e-11 inside the face, vanilla's {@code pushOutOfBlocks} ejected the item and it rolled
     * 0.849 blocks along the deck; and with {@code DeckFrameTick.noteWrite} made a no-op - the item
     * went back to where it lay before, 1.005 blocks from its new place.</p>
     */
    @Test
    public void anItemPlacedElsewhereOnTheDeckStaysWhereItWasPut() throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 30.0);
        int itemId = dropOnDeck(craft, 1.5, 0.5, false);
        double[] landed = onTheDeckTop(craft, itemId, "the tilted deck");

        // One deck cell along, EXACTLY on the top face, mapped through this craft rather than guessed.
        // Exactly on it on purpose: the write comes back through the transform with its rounding in
        // it and lands a hair inside the face as often as above it, and an item placed there must lie
        // where it was put, as it does on the ground.
        double[] target = {craft.seat.seatX + 1.5, craft.seat.seatY, craft.seat.seatZ + 1.5};
        Reply world = Reply.of(exec("stellurgytest vs to-world 0 id " + craft.shipId + " "
                + target[0] + " " + target[1] + " " + target[2]));
        requireArranged("the target must map to the world through this craft", world.ok());
        requireArranged("the target must be a different deck point from where the item landed, or"
                + " being pulled back and staying put read the same", along(landed, target) > 0.9);
        Reply placed = Reply.of(exec("stellurgytest entity set-pos 0 " + itemId + " "
                + world.number("worldX") + " " + world.number("worldY") + " " + world.number("worldZ")));
        requireArranged("the item must have been placed: " + placed, placed.ok());

        // WINDOW: an upper bound on how far the item is from where it was put; overshoot gives a
        // pull-back longer to act, so it can only turn a green red.
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, itemId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        String evidence = evidence(target, later, craft, itemId);
        System.out.println("[deck-rest] placed along=" + along(target, later) + " across="
                + across(target, later) + evidence);
        assertTrue("an item placed on the deck must stay where it was put, not return to where it"
                + " lay before: it is " + along(target, later) + " blocks along the deck from its"
                + " new place;" + evidence, along(target, later) <= AT_REST);
        assertTrue("an item placed on the deck's top face must stay on it: " + across(target, later)
                + " blocks off;" + evidence, across(target, later) <= AT_REST);
    }

    // -- a push the world gives the item survives --------------------------------------------------------------

    /**
     * An item lying on a tilted deck is given a push. It moves - the push is the world's, and nothing
     * about the deck may swallow it - and then friction stops it, on the deck.
     *
     * <p>Not witnessed red. The prediction it met (0.728 carried, 0.7282 read) is the evidence the
     * mechanism is understood, not that the test can fail; the inversion owed is dropping the
     * world motion on entry to the deck frame.</p>
     */
    @Test
    public void aPushMovesAnItemAlongTheDeckAndFrictionStopsIt() throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 30.0);
        int itemId = dropOnDeck(craft, 1.5, 0.5, false);
        double[] landed = onTheDeckTop(craft, itemId, "the tilted deck");

        // Along the deck's own X axis, expressed in the world: a push into the deck would be
        // stopped by it and could not tell a felt push from a discarded one.
        Reply axis = Reply.of(exec("stellurgytest vs to-world 0 id " + craft.shipId + " "
                + (craft.seat.seatX + 1.0) + " " + craft.seat.seatY + " " + craft.seat.seatZ));
        Reply origin = Reply.of(exec("stellurgytest vs to-world 0 id " + craft.shipId + " "
                + craft.seat.seatX + " " + craft.seat.seatY + " " + craft.seat.seatZ));
        requireArranged("the deck's X axis must map to the world", axis.ok() && origin.ok());
        double ax = axis.number("worldX") - origin.number("worldX");
        double ay = axis.number("worldY") - origin.number("worldY");
        double az = axis.number("worldZ") - origin.number("worldZ");
        Reply pushed = Reply.of(exec("stellurgytest entity set-motion 0 " + itemId + " "
                + ax * PUSH + " " + ay * PUSH + " " + az * PUSH));
        requireArranged("the push must have been applied: " + pushed, pushed.ok());

        // EXPERIMENT: the push's whole run, then a second stretch in which it must be over.
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] stopped = deckPoint(craft, itemId);
        simulatedBetween(landed, stopped, REST_WINDOW_TICKS);
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, itemId);
        simulatedBetween(stopped, later, REST_WINDOW_TICKS);
        String evidence = evidence(landed, stopped, craft, itemId) + " later=(" + later[0] + "," + later[1]
                + "," + later[2] + ")";
        System.out.println("[deck-rest] push carried=" + along(landed, stopped) + " thenMoved="
                + along(stopped, later) + " across=" + across(landed, later) + evidence);
        assertTrue("a push must move an item lying on a deck: it moved " + along(landed, stopped)
                + " blocks (at least " + PUSH_MUST_CARRY + ");" + evidence,
                along(landed, stopped) >= PUSH_MUST_CARRY);
        assertTrue("friction must then stop it: it moved a further " + along(stopped, later)
                + " blocks;" + evidence, along(stopped, later) <= AT_REST);
        assertTrue("and it must still be on the deck's top face: " + across(landed, later)
                + " blocks off;" + evidence, across(landed, later) <= AT_REST);
    }

    // -- what an item's own update asks of the world, asked from the deck -----------------------

    /**
     * Two items lying side by side on a tilted deck merge into one stack, as on the ground.
     *
     * <p>The merge is the item's own update looking for its neighbours with a box around itself. On
     * a deck that box is expressed in the deck's frame while the neighbour lives in the world, so
     * this is the reading for whether the world's answers to an entity's queries still reach it once
     * its update runs in the deck's frame.</p>
     *
     * <p>Not witnessed red, and not a discriminator: the substrate merges them too. It guards the
     * frame swap against breaking an entity's own neighbour query.</p>
     */
    @Test
    public void twoItemsSideBySideOnATiltedDeckMergeIntoOneStack() throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 30.0);
        int first = dropOnDeck(craft, 1.5, 0.5, true);
        int second = dropOnDeck(craft, 1.5, 0.9, true);

        // EXPERIMENT: vanilla looks for neighbours every 25 ticks of an item's life; 60 covers two
        // looks for each.
        GameTicks.advance(client(), GameTicks.server(), 60);
        Reply a = Reply.of(exec("stellurgytest entity info 0 " + first));
        Reply b = Reply.of(exec("stellurgytest entity info 0 " + second));
        boolean aAlive = a.bool("isAlive") && !a.bool("isDead");
        boolean bAlive = b.bool("isAlive") && !b.bool("isDead");
        Reply survivor = aAlive ? a : b;
        String evidence = " first=" + a + " second=" + b;
        System.out.println("[deck-rest] merge aAlive=" + aAlive + " bAlive=" + bAlive + evidence);
        assertTrue("two items side by side on a deck must merge into one: both are still separate"
                + " (or both gone);" + evidence, aAlive != bAlive);
        requireTicked(craft, aAlive ? first : second);
        requireArranged("the surviving item must be lying on the deck, or the merge happened"
                + " somewhere else;" + evidence,
                Math.abs(deckPoint(craft, aAlive ? first : second)[1] - craft.seat.seatY) <= AT_REST);
        assertTrue("the surviving item must carry both stacks;" + evidence,
                survivor.integer("itemCount") == 2);
    }

    // -- the OUTER hull - the substrate's to hold, if it can ---------------------------------

    /**
     * An item lying on the OUTER hull of an upside-down craft - the underside of its deck, which
     * now faces the sky - while the craft hovers. No deck owns that surface (there is no floor below
     * it in the craft's frame), so whatever holds the item here is the substrate's own collision.
     * Maintainer ruling 2026-09-29: the outer hull stays the substrate's if it holds everything but
     * the player there more cheaply than hull-stand does. This is that reading.
     */
    @Test
    @org.junit.Ignore("The pilot-deck fixture's blocks below the deck are of unknown extent: dropped over"
            + " the corner column of the inverted craft, the item ends inside a gap at ship-frame Y 122.4,"
            + " falling at 0.33 blocks/tick and not moving, with no ship association and no deck episode."
            + " This reading needs a craft whose outer surface is a known plain slab. Re-enable on one.")
    public void anItemOnTheOuterHullOfAnInvertedCraftStaysWhereItLanded() throws Exception {
        outerHull(null);
    }

    /** As above, with the craft flying sideways under the item. */
    @Test
    @org.junit.Ignore("Same arrangement as the static outer-hull scenario; see its reason.")
    public void anItemOnTheOuterHullOfAFlyingInvertedCraftRidesWithIt() throws Exception {
        outerHull(HULL_FLIGHT_VELOCITY);
    }

    /** How far above the deck's underside, in the world, the outer-hull item is released. */
    private static final double HULL_DROP_HEIGHT = 10.0;

    /** The sideways flight, world frame, blocks per second. */
    private static final String HULL_FLIGHT_VELOCITY = "3.0 0.0 0.0";

    private void outerHull(String velocity) throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 180.0);
        // Dropped from well above the column over the deck's corner cell, so it lands on whatever
        // the hull presents uppermost there - the arrangement asks only that it came to rest on a
        // face of THIS craft, not which one (the fixture carries blocks below its deck whose extent
        // this scenario does not need to know).
        Reply drop = Reply.of(exec("stellurgytest vs to-world 0 id " + craft.shipId + " "
                + (craft.seat.seatX + 2.5) + " " + (craft.seat.seatY - 1.0) + " " + (craft.seat.seatZ + 2.5)));
        requireArranged("the drop point must map to the world through this ship", drop.ok());
        int itemId = Reply.of(exec("stellurgytest vs drop-item 0 " + drop.number("worldX") + " "
                + (drop.number("worldY") + HULL_DROP_HEIGHT) + " " + drop.number("worldZ"))).integer("entityId");
        // STIMULUS: a fall of HULL_DROP_HEIGHT blocks and more; 60 ticks cover it with room to settle.
        GameTicks.advance(client(), GameTicks.server(), 60);
        requireTicked(craft, itemId);
        double[] landed = deckPoint(craft, itemId);
        Reply rest = Reply.of(exec("stellurgytest vs player-ship-data 0 " + itemId + " " + craft.shipId));
        requireArranged("the item must have come to rest ON this craft's outer hull: " + rest,
                rest.bool("playerOnGround") && craft.shipId.equals(rest.textOr("lastTouchedShip", ""))
                        && Math.abs(landed[1] - Math.rint(landed[1])) <= AT_REST);

        double shipXBefore = ShipInfo.byId(this::exec, 0, craft.shipId).x;
        if (velocity != null) {
            Reply flown = Reply.of(exec("stellurgytest vs force-vel-by-id 0 " + craft.shipId + " " + velocity));
            requireArranged("the craft must accept the flight command: " + flown, flown.bool("afcResolved"));
        }
        // WINDOW (or, flying, EXPERIMENT): the item is read again after a stretch of it lying there.
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, itemId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        ShipInfo ship = ShipInfo.byId(this::exec, 0, craft.shipId);
        double flownX = ship.x - shipXBefore;
        String evidence = evidence(landed, later, craft, itemId) + " craftMovedX=" + flownX;
        System.out.println("[deck-rest] hull" + (velocity == null ? "Static" : "Flying") + " along="
                + along(landed, later) + " across=" + across(landed, later) + evidence);
        requireArranged("the craft must still be upside down (deck-up world Y " + deckUpY(craft) + ")",
                deckUpY(craft) < -0.9);
        if (velocity != null) {
            requireArranged("the craft must have flown while the item lay on it;" + evidence,
                    Math.abs(flownX) > 5.0);
        }
        exec("stellurgytest vs force-clear-by-id 0 " + craft.shipId);
        assertTrue("an item on the outer hull must stay on it: " + across(landed, later)
                + " blocks off;" + evidence, across(landed, later) <= AT_REST);
        assertTrue("an item on the outer hull must not slide along it: " + along(landed, later)
                + " blocks;" + evidence, along(landed, later) <= AT_REST);
    }

    // -- the scenario body shared by the static tilts --------------------------------------------

    private void restsOnADeckRolledBy(double rollDeg) throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, rollDeg);
        int itemId = dropOnDeck(craft, 1.5, 0.5, false);
        double[] landed = onTheDeckTop(craft, itemId, "the tilted deck");

        // WINDOW: two reads of the item in the ship's frame with a stretch of lying still between
        // them, and the assertions name both. An upper bound: overshoot gives a sliding item longer
        // to slide, so it can only turn a green red.
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, itemId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        String evidence = evidence(landed, later, craft, itemId);
        double along = along(landed, later), across = across(landed, later);
        System.out.println("[deck-rest] roll=" + rollDeg + " along=" + along + " across=" + across + evidence);
        assertTrue("an item lying on a tilted deck must not slide along it: it moved " + along
                + " blocks along the deck in " + REST_WINDOW_TICKS + " ticks (at rest: " + AT_REST + ");"
                + evidence, along <= AT_REST);
        assertTrue("an item lying on a tilted deck must stay on its top face: it moved " + across
                + " blocks across the deck;" + evidence, across <= AT_REST);
    }

    // -- arrangement -----------------------------------------------------------------------------

    private static final class Craft {
        final String shipId;
        final PilotSeat seat;

        Craft(String shipId, PilotSeat seat) {
            this.shipId = shipId;
            this.seat = seat;
        }
    }

    private Craft buildCraft() throws Exception {
        int[] bp = RocketFixture.placeAt(site(), this::exec, "with-pilot-deck", 4, 12,
                "the craft whose deck the item lies on stands in this volume");
        String asm = exec("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
        requireArranged("the pilot-deck build must route to a ship, not a rocket: " + asm,
                Reply.of(asm).integer("rocketCount") == 0);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, ShipIdentity.nameFromAssembly(asm));
        PilotSeat seat = PilotSeat.byId(this::exec, 0, shipId)
                .requireFound("the seat locates the deck in subspace; without it there is nowhere to drop");
        Reply held = Reply.of(exec("stellurgytest chunk hold 0 " + seat.shipWorldX + " "
                + seat.shipWorldY + " " + seat.shipWorldZ + " " + HOLD_RADIUS_CHUNKS));
        requireArranged("the chunks around the craft must be held, or the world may not tick what"
                + " lies on its deck: " + held, held.ok());
        return new Craft(shipId, seat);
    }

    /** Hold the craft at {@code rollDeg} about its X axis, and check it got there. */
    private void rollTo(Craft craft, double rollDeg) throws Exception {
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

    private double deckUpY(Craft craft) throws Exception {
        ShipInfo ship = ShipInfo.byId(this::exec, 0, craft.shipId);
        return new FreeFlightPhysics.Quat(ship.qw, ship.qx, ship.qy, ship.qz).rotate(0.0, 1.0, 0.0)[1];
    }

    /**
     * Drop an item a tenth of a block over a deck cell at {@code (dx, dz)} from the seat, and give it
     * time to land. Low on purpose: a longer fall is spent under whatever holds the item BEFORE the
     * deck does, and on a steep deck that fall carries it off the cell it was aimed at.
     *
     * <p>Not a link, and the reason is the subject of this class: the record a landing leaves
     * depends on WHICH mechanism holds the item - the substrate's collision announces
     * {@code entity_touched_ship}, a deck that has taken the item into its own frame never calls
     * that collision at all. A link on either would decide the verdict by mechanism. So the fall is
     * a STIMULUS of fixed length, and every caller follows it with {@link #onTheDeckTop}.</p>
     */
    private int dropOnDeck(Craft craft, double dx, double dz, boolean mergeable) throws Exception {
        Reply drop = Reply.of(exec("stellurgytest vs to-world 0 id " + craft.shipId + " "
                + (craft.seat.seatX + dx) + " " + (craft.seat.seatY + 0.1) + " "
                + (craft.seat.seatZ + dz)));
        requireArranged("the drop point must map to the world through this ship", drop.ok());
        String dropped = exec("stellurgytest vs drop-item 0 " + drop.number("worldX") + " "
                + drop.number("worldY") + " " + drop.number("worldZ") + (mergeable ? " mergeable" : ""));
        int itemId = Reply.of(dropped).integer("entityId");
        // STIMULUS: a tenth of a block of fall at 0.04 blocks/tick^2 lands in about three ticks.
        GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);
        if (!mergeable) {
            // A mergeable item may already have been absorbed by its neighbour here; that
            // scenario asks the same question of the survivor after its window instead.
            requireTicked(craft, itemId);
        }
        return itemId;
    }

    /** Gate: the world is ticking the item where it lies - vanilla's own update-area test. */
    private void requireTicked(Craft craft, int itemId) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + itemId + " " + craft.shipId));
        requireArranged("the world must be ticking the item where it lies (vanilla's own update-area"
                + " test), or nothing below is a reading of what the deck does with it: " + r,
                r.bool("updateAreaLoaded"));
    }

    /**
     * The item's deck point, gated on its lying ON the deck's top face - within {@link #AT_REST} of
     * it, not merely somewhere above. An item hanging where it was dropped is an item that never
     * landed, and every "it stayed put" read after that is about nothing.
     */
    private double[] onTheDeckTop(Craft craft, int itemId, String deck) throws Exception {
        double[] p = deckPoint(craft, itemId);
        if (Math.abs(p[1] - craft.seat.seatY) > AT_REST) {
            Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + itemId + " " + craft.shipId));
            requireArranged("the item must have landed ON " + deck + "'s top face (ship-frame Y " + p[1]
                    + ", deck top " + craft.seat.seatY + "; ticksExisted " + p[3] + " after " + LAND_TICKS
                    + " ticks; ships containing it " + exec("stellurgytest vs ships-at 0 "
                    + r.number("playerX") + " " + r.number("playerY") + " " + r.number("playerZ"))
                    + "; " + r + ")", false);
        }
        return p;
    }

    /**
     * The item's position in the craft's frame, from one snapshot, as {x, y, z, ticksExisted}.
     *
     * <p>The fourth value is what lets a reading say it was blind: an item the world is not ticking
     * stands perfectly still, so every "it did not move" verdict below also asks that it WAS moved -
     * see {@link #simulatedBetween}.</p>
     */
    private double[] deckPoint(Craft craft, int itemId) throws Exception {
        Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + itemId + " " + craft.shipId));
        requireArranged("the item must exist and be readable in the ship's frame: " + r,
                r.bool("shipResolved"));
        return new double[]{r.number("bodyShipFrameX"), r.number("bodyShipFrameY"),
                r.number("bodyShipFrameZ"), r.integer("ticksExisted")};
    }

    /** Gate: the world simulated the item for most of the window between two readings. */
    private static void simulatedBetween(double[] first, double[] second, int window) {
        double ticked = second[3] - first[3];
        requireArranged("the world must have been simulating the item across the window, or a"
                + " reading of \"it stayed put\" is about nothing (ticked " + ticked + " of " + window
                + ")", ticked >= window / 2.0);
    }

    private static double along(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[2] - a[2]);
    }

    private static double across(double[] a, double[] b) {
        return Math.abs(b[1] - a[1]);
    }

    private String evidence(double[] from, double[] to, Craft craft, int itemId) throws Exception {
        return " from=(" + from[0] + "," + from[1] + "," + from[2] + ") to=(" + to[0] + "," + to[1]
                + "," + to[2] + ") deckUpY=" + deckUpY(craft) + " deckHeldBy="
                + Reply.of(exec("stellurgytest vs player-ship-data 0 " + itemId + " " + craft.shipId))
                        .textOr("deckHeldBy", "none");
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
