package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipInfo;

import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.NotYetRed;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;

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
 *
 * <p>NEW-GROUP: a craft's deck holding the items lying on it. No server group holds a deck's hold on
 * a body: the jump and crossing groups carry bodies with the craft between cells, which is a
 * different mechanism from the deck's own frame, and a living body is held by its own rules in
 * {@link VSLivingBodyOnADeckTest}.</p>
 */
public class VSItemOnADeckTest extends AbstractDeckBodyTest {

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

    @NotYetRed(prose = "no single-fragment witness. On 2026-09-29 the along verdict went red (along 0.13) with"
            + " two breaks at once: the deck point re-derived from the world position on every entry"
            + " ({@code DeckFrameTick#runInDeckFrame} at {@code boolean mappedIn = !episode.held ||"
            + " episode.worldWritten} made always true; it stood in the entity update then and has moved verbatim)"
            + " AND no end-of-tick re-image ({@code DeckFrameTick#followShipPoses} at"
            + " {@code entity.setPosition(pos[0], pos[1], pos[2])} removed). With the substrate holding the item it"
            + " fails as an ARRANGEMENT instead: the item rests 0.061 off the face.")
    @Pins("ENT-3")
    @Test
    public void anItemDroppedOnADeckRolled30DegreesStaysWhereItLanded() throws Exception {
        restsOnADeckRolledBy(30.0);
    }

    @RedWitness(verdict = "must not slide along it", member = "DeckFrameTick#runInDeckFrame",
            at = "boolean mappedIn = !episode.held || episode.worldWritten", replacedBy = "boolean mappedIn = true",
            fails = "an item lying on a tilted deck must not slide along it: it moved", on = "2026-09-29")
    @Pins("ENT-3")
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
    @RedWitness(member = "GravityHandler#apply",
            at = "if (dev.stannismod.stellurgy.integration.vs.DeckFrameTick.holds(entity))", replacedBy = "if (false)",
            fails = "an item let go a tenth of a block above a deck, in a world without gravity, must fall onto the"
                    + " deck and lie on it: it is", on = "2026-09-29")
    @Pins("ENT-3")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "a tenth of a block at a deck's 0.04 blocks/tick^2 is about three ticks; the window is the"
                    + " resting one, and overshoot only gives a hanging item longer to hang.")
    public void anItemLetGoAboveADeckInAWorldWithoutGravityFallsOntoIt() throws Exception {
        withOverworldGravity(0.0, () -> {
            Craft craft = buildCraft();
            int itemId = dropOnDeck(craft, 1.5, 0.5, false);
            double[] first = deckPoint(craft, itemId);
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
     */
    @RedWitness(verdict = "must not slide along it while the craft rolls over", member = "DeckFrameTick#runInDeckFrame",
            at = "boolean mappedIn = !episode.held || episode.worldWritten", replacedBy = "boolean mappedIn = true",
            fails = "an item lying on a deck must not slide along it while the craft rolls over: it moved",
            on = "2026-09-29")
    @NotYetRed(prose = "THE ACROSS verdict has no replayable witness. On 2026-09-29 it went red (across 0.28,"
            + " along 0.42) only with the mixin MixinWorldDeckFrameTick unregistered, so the world tick never"
            + " called {@code DeckFrameTick#update} and the substrate held the item - a configuration change, not"
            + " a fragment replacement.")
    @Pins({"ENT-3", "ENT-8"})
    @dev.stannismod.stellurgy.test.contract.Regions(of = "ENT-8", value = "held body")
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
     */
    @NotYetRed(prose = "no replayable witness. On 2026-09-29 both verdicts went red (along 1.39, across 0.27)"
            + " only with the mixin MixinWorldDeckFrameTick unregistered, so the world tick never called"
            + " {@code DeckFrameTick#update} - a configuration change, not a fragment replacement.")
    @Pins({"ENT-3", "ENT-8"})
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "a stretch of steady rolling; the read is taken with the craft still turning.")
    public void anItemLyingOnADeckStaysOnItWhileTheCraftKeepsRolling() throws Exception {
        Craft craft = buildCraft();
        int itemId = dropOnDeck(craft, 1.5, 0.5, false);
        double[] landed = onTheDeckTop(craft, itemId, "the level deck");

        double upBefore = deckUpY(craft);
        Reply spun = Reply.of(arrange("stellurgytest vs force-rot-by-id 0 " + craft.shipId + " "
                + ROLL_RATE + " 0 0").raw());
        requireArranged("the craft must accept the roll command: " + spun, spun.bool("afcResolved"));
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, itemId);
        ShipInfo turning = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId);
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
        arrange("stellurgytest vs force-clear-by-id 0 " + craft.shipId);
    }

    // -- a position somebody writes is where the item is ----------------------------------------------

    /**
     * Something places an item already lying on the deck somewhere else on it - the way a teleport
     * or another mod would. The item is where it was put, and stays there: nothing the deck
     * remembers about where the item used to be may pull it back.
     */
    @RedWitness(verdict = "must stay where it was put", member = "DeckFrameTick#runInDeckFrame",
            at = "clearOfMappingNoise(entity, local)", replacedBy = "",
            fails = "an item placed on the deck must stay where it was put, not return to where it lay before: it is",
            on = "2026-09-29")
    @RedWitness(verdict = "must stay where it was put", member = "DeckFrameTick#noteWrite",
            at = "episode.worldWritten = true", replacedBy = "",
            fails = "an item placed on the deck must stay where it was put, not return to where it lay before: it is",
            on = "2026-09-29")
    @Pins("ENT-4")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "an upper bound on how far the item is from where it was put; overshoot gives a pull-back longer"
                    + " to act, so it can only turn a green red.")
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
        Reply placed = Reply.of(arrange("stellurgytest entity set-pos 0 " + itemId + " "
                + world[0] + " " + world[1] + " " + world[2]).raw());
        requireArranged("the item must have been placed: " + placed, placed.ok());

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
    @Pins("ENT-5")
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
        Reply pushed = Reply.of(arrange("stellurgytest entity set-motion 0 " + itemId + " "
                + (axis[0] - origin[0]) * PUSH + " " + (axis[1] - origin[1]) * PUSH + " "
                + (axis[2] - origin[2]) * PUSH).raw());
        requireArranged("the push must have been applied: " + pushed, pushed.ok());

        pushRestWindow();
        double[] stopped = deckPoint(craft, itemId);
        simulatedBetween(landed, stopped, REST_WINDOW_TICKS);
        pushRestWindow();
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

    /** One stretch of the pushed item's run: called twice, the push's whole run and then a second stretch. */
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "the push's whole run, then a second stretch in which it must be over.")
    private void pushRestWindow() throws Exception {
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
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
    @Pins("ENT-9")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), 60);",
            why = "vanilla looks for neighbours every 25 ticks of an item's life; 60 covers two looks for each.")
    public void twoItemsSideBySideOnATiltedDeckMergeIntoOneStack() throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 30.0);
        int first = dropOnDeck(craft, 1.5, 0.5, true);
        int second = dropOnDeck(craft, 1.5, 0.9, true);

        GameTicks.advance(client(), GameTicks.server(), 60);
        Reply a = Reply.of(read("stellurgytest entity info 0 " + first).raw());
        Reply b = Reply.of(read("stellurgytest entity info 0 " + second).raw());
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
    @Pins("ENT-7")
    @Test
    public void anItemOnTheOuterHullOfAnInvertedCraftStaysWhereItLanded() throws Exception {
        outerHull(null);
    }

    /** As above, with the craft flying sideways under the item. */
    @Pins("ENT-7")
    @Test
    public void anItemOnTheOuterHullOfAFlyingInvertedCraftRidesWithIt() throws Exception {
        outerHull(HULL_FLIGHT_VELOCITY);
    }

    /** How far above the deck's underside, in the world, the outer-hull item is released. */
    private static final double HULL_DROP_HEIGHT = 0.1;

    /** The sideways flight, world frame, blocks per second. */
    private static final String HULL_FLIGHT_VELOCITY = "3.0 0.0 0.0";

    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), 60);",
            why = "a fall of HULL_DROP_HEIGHT blocks and more; 60 ticks cover it with room to settle.")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "the item is read again after a stretch of it lying there (while the craft flies, when it is"
                    + " told to); the verdicts compare the two reads, and overshoot only lengthens the stretch.")
    private void outerHull(String velocity) throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 180.0);
        // Over the middle of the deck's -Z edge, whose column holds nothing below the deck in the
        // craft's frame, and neither do its neighbours': the with-pilot-deck fixture hangs its motors
        // under the four corners, its engines and tanks on the seat's row and its reaction wheel
        // beside the seat at +Z. So the outer face here is the deck block's underside. Dropped from
        // HULL_DROP_HEIGHT above it.
        double[] drop = toWorld(craft, 0.5, -1.0, -1.5);
        int itemId = Reply.of(arrange("stellurgytest vs drop-item 0 " + drop[0] + " "
                + (drop[1] + HULL_DROP_HEIGHT) + " " + drop[2]).raw()).integer("entityId");
        GameTicks.advance(client(), GameTicks.server(), 60);
        requireTicked(craft, itemId);
        double[] landed = deckPoint(craft, itemId);
        Reply rest = Reply.of(read("stellurgytest vs player-ship-data 0 " + itemId + " " + craft.shipId).raw());
        // Lying on THIS craft is the arrangement; how close to the face it lies is not. No deck holds
        // a body here, so whatever holds it is the physics substrate, which rests a body a hair off
        // the face it touches - that gap is a reading about the subject, printed below.
        requireArranged("the item must have come to rest ON this craft's outer hull: " + rest,
                rest.bool("playerOnGround") && craft.shipId.equals(rest.textOr("lastTouchedShip", "")));
        double offTheFace = Math.abs(landed[1] - Math.rint(landed[1]));

        double shipXBefore = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId).x;
        if (velocity != null) {
            Reply flown = Reply.of(arrange("stellurgytest vs force-vel-by-id 0 " + craft.shipId + " " + velocity).raw());
            requireArranged("the craft must accept the flight command: " + flown, flown.bool("afcResolved"));
        }
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, itemId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        ShipInfo ship = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId);
        double flownX = ship.x - shipXBefore;
        String evidence = evidence(landed, later, craft, itemId) + " craftMovedX=" + flownX;
        System.out.println("[deck-rest] hull" + (velocity == null ? "Static" : "Flying") + " along="
                + along(landed, later) + " across=" + across(landed, later) + " offTheFace=" + offTheFace
                + evidence);
        requireArranged("the craft must still be upside down (deck-up world Y " + deckUpY(craft) + ")",
                deckUpY(craft) < -0.9);
        if (velocity != null) {
            requireArranged("the craft must have flown while the item lay on it;" + evidence,
                    Math.abs(flownX) > 5.0);
        }
        arrange("stellurgytest vs force-clear-by-id 0 " + craft.shipId);
        assertTrue("an item on the outer hull must stay on it: " + across(landed, later)
                + " blocks off;" + evidence, across(landed, later) <= AT_REST);
        assertTrue("an item on the outer hull must not slide along it: " + along(landed, later)
                + " blocks;" + evidence, along(landed, later) <= AT_REST);
    }

    // -- the scenario body shared by the static tilts --------------------------------------------

    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "two reads of the item in the ship's frame with a stretch of lying still between them, and the"
                    + " assertions name both. An upper bound: overshoot gives a sliding item longer to slide,"
                    + " so it can only turn a green red.")
    private void restsOnADeckRolledBy(double rollDeg) throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, rollDeg);
        int itemId = dropOnDeck(craft, 1.5, 0.5, false);
        double[] landed = onTheDeckTop(craft, itemId, "the tilted deck");

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
    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);",
            why = "a tenth of a block of fall at 0.04 blocks/tick^2 lands in about three ticks.")
    private int dropOnDeck(Craft craft, double dx, double dz, boolean mergeable) throws Exception {
        double[] drop = toWorld(craft, dx, 0.1, dz);
        String dropped = arrange("stellurgytest vs drop-item 0 " + drop[0] + " " + drop[1] + " " + drop[2]
                + (mergeable ? " mergeable" : "")).raw();
        int itemId = Reply.of(dropped).integer("entityId");
        GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);
        if (!mergeable) {
            // A mergeable item may already have been absorbed by its neighbour here; that
            // scenario asks the same question of the survivor after its window instead.
            requireTicked(craft, itemId);
        }
        return itemId;
    }
}
