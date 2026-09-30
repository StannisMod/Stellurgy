package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipInfo;

import org.junit.Test;

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
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSItemOnADeckE2ETest extends AbstractDeckBodyE2ETest {

    /** How long the dropped item is given to land on the deck a tenth of a block below it. */
    private static final int LAND_TICKS = 20;

    /**
     * The push given in the impulse scenario, in blocks per tick, and the least it must carry the
     * item. On ground friction an item keeps 0.588 of its speed per tick, so the push is worth about
     * {@code 0.3 / (1 - 0.588) ~ 0.73} blocks; a third of that separates "the push was felt" from
     * "the push was discarded" with room for the first tick being spent airborne.
     */
    private static final double PUSH = 0.3;
    private static final double PUSH_MUST_CARRY = 0.25;

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
     * The deck is level and the world around it has no gravity at all. A craft carries its own for
     * what lies on its deck, so an item let go a tenth of a block above it still falls onto it and
     * lies there; the world's gravity is the world's.
     *
     * <p>The landing is the SUBJECT here, so it is asserted rather than gated: with no pull toward
     * the deck the item hangs where it was let go, which is exactly the failure this reads.</p>
     */
    @Test
    public void anItemLetGoAboveADeckInAWorldWithoutGravityFallsOntoIt() throws Exception {
        withOverworldGravity(0.0, () -> {
            Craft craft = buildCraft();
            int itemId = dropOnDeck(craft, 1.5, 0.5, false);
            double[] first = deckPoint(craft, itemId);
            // WINDOW: a tenth of a block at a deck's 0.04 blocks/tick^2 is about three ticks; the
            // window is the resting one, and overshoot only gives a hanging item longer to hang.
            GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
            double[] later = deckPoint(craft, itemId);
            simulatedBetween(first, later, REST_WINDOW_TICKS);
            double offTheFace = Math.abs(later[1] - craft.seat.seatY);
            String evidence = evidence(first, later, craft, itemId);
            System.out.println("[deck-rest] zeroG offTheFace=" + offTheFace + " along=" + along(first, later)
                    + evidence);
            assertTrue("an item let go a tenth of a block above a deck, in a world without gravity, must"
                    + " fall onto the deck and lie on it: it is " + offTheFace + " blocks off the top face;"
                    + evidence, offTheFace <= AT_REST);
        });
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
        double[] world = toWorld(craft, 1.5, 0.0, 1.5);
        requireArranged("the target must be a different deck point from where the item landed, or"
                + " being pulled back and staying put read the same", along(landed, target) > 0.9);
        Reply placed = Reply.of(exec("stellurgytest entity set-pos 0 " + itemId + " "
                + world[0] + " " + world[1] + " " + world[2]));
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
        double[] axis = toWorld(craft, 1.0, 0.0, 0.0);
        double[] origin = toWorld(craft, 0.0, 0.0, 0.0);
        Reply pushed = Reply.of(exec("stellurgytest entity set-motion 0 " + itemId + " "
                + (axis[0] - origin[0]) * PUSH + " " + (axis[1] - origin[1]) * PUSH + " "
                + (axis[2] - origin[2]) * PUSH));
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
        double[] drop = toWorld(craft, 2.5, -1.0, 2.5);
        int itemId = Reply.of(exec("stellurgytest vs drop-item 0 " + drop[0] + " "
                + (drop[1] + HULL_DROP_HEIGHT) + " " + drop[2])).integer("entityId");
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
        double[] drop = toWorld(craft, dx, 0.1, dz);
        String dropped = exec("stellurgytest vs drop-item 0 " + drop[0] + " " + drop[1] + " " + drop[2]
                + (mergeable ? " mergeable" : ""));
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
}
