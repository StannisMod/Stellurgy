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
 * <b>A living body on a deck stands on it as it would stand on the ground</b>, whatever the craft's
 * attitude: it falls toward the deck and rests there, stays put while the craft turns under it, and a
 * mob's own walking keeps it on the deck.
 *
 * <h2>Why two subjects</h2>
 *
 * A mob wanders by itself, so how far it moved along the deck is its own business and says nothing
 * about the deck. A mob with its AI switched off does not simulate at all - vanilla skips its whole
 * movement - and would read perfectly still on any deck. So the question "does the deck hold a living
 * body still" is asked of an ARMOR STAND, which has no AI but does move itself (its own gravity, drag
 * and ground friction, a living body's), and the question "does a mob's own movement keep it on the
 * deck" is asked of a COW.
 *
 * <p>Server tier: neither subject has a client that owns its movement, so the server places it
 * entirely and a server test is the honest path.</p>
 *
 * <p>NEW-GROUP: a craft's deck holding the living bodies other than players on it. No server group
 * holds a deck's hold on a body: the jump and crossing groups carry bodies with the craft between
 * cells, which is a different mechanism from the deck's own frame, and an item is held by its own
 * rules in {@link VSItemOnADeckTest}.</p>
 */
public class VSLivingBodyOnADeckTest extends AbstractDeckBodyTest {

    /** How long a body put a tenth of a block above the deck is given to land on it. */
    private static final int LAND_TICKS = 20;

    /** How long the cow's own AI is watched on the deck, in server ticks. */
    private static final int COW_WINDOW_TICKS = 200;

    /**
     * How long the cow burns, in seconds. A burning animal panics and runs, which is the one way to
     * have a mob move itself without a player to react to; left alone, a cow's wander fires at 1/120
     * per tick and measured 2026-09-29 three cows in a row never walked at all. Fire costs it one
     * health a second of its ten.
     */
    private static final int PANIC_SECONDS = 5;

    /**
     * How far along the deck the running cow must have got for the scenario to be about a mob moving
     * itself: a third of a block, well past float noise and short of any one panic run.
     */
    private static final double COW_MUST_RUN = 0.3;

    /** Half the deck's side: the with-pilot-deck deck is 5x5 blocks with the seat at its centre. */
    private static final double DECK_HALF_WIDTH = 2.5;

    // -- a living body with no will of its own rests on the deck, at any attitude ---------------

    @NotYetRed(prose = "neither verdict has been seen red against the current code. The old ALONG witness,"
            + " 2026-09-29, was taken on the form before players were admitted, and while a travel resolver (since"
            + " deleted) could hold the stand: {@code DeckFrameTick#admissible} at"
            + " {@code return entity instanceof EntityLivingBase} answering false (no living body admitted, so the"
            + " travel resolver held the stand) - along 0.136. The ACROSS verdict runs after along and read 6e-12"
            + " in that same run.")
    @Pins("ENT-3")
    @Test
    public void anArmorStandOnADeckRolled30DegreesStaysWhereItLanded() throws Exception {
        standRestsOnADeckRolledBy(30.0);
    }

    @NotYetRed(prose = "neither verdict has been seen red against the current code. The old ALONG witness,"
            + " 2026-09-29, was taken on the form before players were admitted, and while a travel resolver (since"
            + " deleted) could hold the stand: {@code DeckFrameTick#admissible} at"
            + " {@code return entity instanceof EntityLivingBase} answering false (no living body admitted, so the"
            + " travel resolver held the stand) - along 0.136. The ACROSS verdict runs after along and read 1e-12"
            + " in that same run.")
    @Pins("ENT-3")
    @Test
    public void anArmorStandOnADeckRolled60DegreesStaysWhereItLanded() throws Exception {
        standRestsOnADeckRolledBy(60.0);
    }

    /**
     * The deck rolls over while the stand is on it: past a right angle world-down points away from
     * the deck, so "falls toward the deck" and "falls toward the world" give different answers.
     */
    @RedWitness(verdict = "must not slide along it while the craft rolls over", member = "DeckFrameTick#runInDeckFrame",
            at = "entity.motionX - episode.writtenX, entity.motionY - episode.writtenY,"
                    + " entity.motionZ - episode.writtenZ",
            replacedBy = "entity.motionX, entity.motionY, entity.motionZ",
            fails = "an armor stand on a deck must not slide along it while the craft rolls over: it moved",
            on = "2026-09-29")
    @NotYetRed(prose = "THE ACROSS verdict, asserted first, has not been seen red: it passed (2e-11) under the"
            + " break of {@code DeckFrameTick#runInDeckFrame} above, and (7e-12) on 2026-09-29 under a break taken"
            + " on the form before players were admitted, which is not a witness of the current code either:"
            + " {@code DeckFrameTick#admissible} at {@code return entity instanceof EntityLivingBase} answering"
            + " false (no living body admitted) - along 0.226.")
    @Pins({"ENT-3", "ENT-8"})
    @dev.stannismod.stellurgy.test.contract.Regions(of = "ENT-8", value = "held body")
    @Test
    public void anArmorStandOnADeckStaysOnItWhileTheCraftRollsOver() throws Exception {
        Craft craft = buildCraft();
        int standId = putOnDeck(craft, "minecraft:armor_stand", 1.5, 0.5);
        double[] landed = onTheDeckTop(craft, standId, "the level deck");

        rollTo(craft, 150.0);
        requireArranged("the deck must have rolled past a right angle, or world-down and deck-down"
                + " still agree and nothing below can fail (deck-up world Y " + deckUpY(craft) + ")",
                deckUpY(craft) < -0.5);

        double[] later = deckPoint(craft, standId);
        simulatedBetween(landed, later, SLEW_TICKS);
        String evidence = evidence(landed, later, craft, standId);
        System.out.println("[deck-living] standRollover along=" + along(landed, later) + " across="
                + across(landed, later) + evidence);
        assertTrue("an armor stand on a deck must still be standing on it after the craft rolls over: it"
                + " is " + across(landed, later) + " blocks off the top face;" + evidence,
                across(landed, later) <= AT_REST);
        assertTrue("an armor stand on a deck must not slide along it while the craft rolls over: it moved "
                + along(landed, later) + " blocks along the deck;" + evidence, along(landed, later) <= AT_REST);
    }

    /**
     * The craft keeps rolling at a steady rate under the stand; read while it is still turning.
     */
    @NotYetRed(prose = "neither verdict has been seen red against the current code. The old ALONG reading,"
            + " 2026-09-29, was taken on the form before players were admitted: {@code DeckFrameTick#admissible}"
            + " at {@code return entity instanceof EntityLivingBase} answering false (no living body admitted) -"
            + " along 0.0054. That run was read against the earlier 0.05 bound and PASSED there; it would be red"
            + " against {@code AT_REST} as now measured. The ACROSS verdict has not been seen red.")
    @Pins({"ENT-3", "ENT-8"})
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "a stretch of steady rolling; the read is taken with the craft still turning.")
    public void anArmorStandOnADeckStaysOnItWhileTheCraftKeepsRolling() throws Exception {
        Craft craft = buildCraft();
        int standId = putOnDeck(craft, "minecraft:armor_stand", 1.5, 0.5);
        double[] landed = onTheDeckTop(craft, standId, "the level deck");

        double upBefore = deckUpY(craft);
        Reply spun = Reply.of(arrange("stellurgytest vs force-rot-by-id 0 " + craft.shipId + " "
                + ROLL_RATE + " 0 0").raw());
        requireArranged("the craft must accept the roll command: " + spun, spun.bool("afcResolved"));
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, standId);
        ShipInfo turning = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        requireArranged("the craft must still be turning when the stand is read (omega "
                + turning.omega + ")", turning.omega > ROLL_RATE / 4.0);
        requireArranged("the deck must have turned well away from level (deck-up world Y " + upBefore
                + " -> " + deckUpY(craft) + ")", Math.abs(deckUpY(craft) - upBefore) > 0.5);

        String evidence = evidence(landed, later, craft, standId) + " omega=" + turning.omega;
        System.out.println("[deck-living] standRolling along=" + along(landed, later) + " across="
                + across(landed, later) + evidence);
        arrange("stellurgytest vs force-clear-by-id 0 " + craft.shipId);
        assertTrue("an armor stand on a rolling deck must stay on its top face: " + across(landed, later)
                + " blocks off;" + evidence, across(landed, later) <= AT_REST);
        assertTrue("an armor stand on a rolling deck must not slide along it: " + along(landed, later)
                + " blocks;" + evidence, along(landed, later) <= AT_REST);
    }

    /**
     * The deck is level and the world around it has no gravity. A craft carries its own for what
     * stands on its deck, so a stand let go a tenth of a block above it falls onto it. The landing
     * is the subject, so it is asserted, not gated.
     */
    @RedWitness(member = "GravityHandler#apply",
            at = "if (dev.stannismod.stellurgy.integration.vs.DeckFrameTick.holds(entity))", replacedBy = "if (false)",
            fails = "an armor stand let go a tenth of a block above a deck, in a world without gravity, must fall"
                    + " onto the deck: it is", on = "2026-09-29")
    @Pins("ENT-3")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "the fall takes a couple of ticks; overshoot only gives a hanging stand longer.")
    public void anArmorStandLetGoAboveADeckInAWorldWithoutGravityFallsOntoIt() throws Exception {
        withOverworldGravity(0.0, () -> {
            Craft craft = buildCraft();
            int standId = putOnDeck(craft, "minecraft:armor_stand", 1.5, 0.5);
            double[] first = deckPoint(craft, standId);
            GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
            double[] later = deckPoint(craft, standId);
            simulatedBetween(first, later, REST_WINDOW_TICKS);
            double offTheFace = Math.abs(later[1] - craft.seat.seatY);
            String evidence = evidence(first, later, craft, standId);
            System.out.println("[deck-living] standZeroG offTheFace=" + offTheFace + evidence);
            assertTrue("an armor stand let go a tenth of a block above a deck, in a world without gravity,"
                    + " must fall onto the deck: it is " + offTheFace + " blocks off the top face;" + evidence,
                    offTheFace <= AT_REST);
        });
    }

    // -- a mob's own movement keeps it on the deck ---------------------------------------------

    /**
     * A cow running about a deck tilted 60 deg is, wherever it ran, still on the deck.
     *
     * <p>Not witnessed red. With {@code DeckFrameTick:370} admitting no living body it fails as an
     * ARRANGEMENT - under the travel resolver the cow could not run at all (0.29 along the slope, its
     * body never turning), because its pathfinder plans in the world, where the tilted deck is not -
     * and an arrangement failure is not this verdict failing. The inversion owed is one that lets a
     * running cow leave the deck.</p>
     */
    @Pins("ENT-3")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), COW_WINDOW_TICKS);",
            why = "the cow's own AI, running from the fire, for the whole window.")
    public void aCowRunningAboutADeckRolled60DegreesIsStillOnTheDeck() throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 60.0);
        int cowId = putOnDeck(craft, "minecraft:cow", 1.5, 1.5);
        double[] landed = onTheDeckTop(craft, cowId, "the tilted deck");
        panic(cowId);
        GameTicks.advance(client(), GameTicks.server(), COW_WINDOW_TICKS);
        requireStillOnTheDeck(craft, cowId, landed, COW_WINDOW_TICKS, "tilted60");
    }

    /**
     * A cow running about a deck while the craft rolls over is, afterwards, still on the deck.
     *
     * <p>Not witnessed red: the travel resolver passed it too (ran 3.08, on the deck). The inversion
     * owed is one that lets a running cow leave the deck.</p>
     */
    @Pins({"ENT-3", "ENT-8"})
    @Test
    public void aCowRunningAboutADeckIsStillOnItAfterTheCraftRollsOver() throws Exception {
        Craft craft = buildCraft();
        int cowId = putOnDeck(craft, "minecraft:cow", 1.5, 1.5);
        double[] landed = onTheDeckTop(craft, cowId, "the level deck");
        panic(cowId);
        rollTo(craft, 150.0);
        requireArranged("the deck must have rolled past a right angle (deck-up world Y " + deckUpY(craft)
                + ")", deckUpY(craft) < -0.5);
        requireStillOnTheDeck(craft, cowId, landed, SLEW_TICKS, "rollover");
    }

    // -- arrangement and the reading shared by the cow scenarios ------------------------------------

    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "two reads in the ship's frame with a stretch of standing between them; overshoot gives a"
                    + " sliding stand longer to slide, so it can only turn a green red.")
    private void standRestsOnADeckRolledBy(double rollDeg) throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, rollDeg);
        int standId = putOnDeck(craft, "minecraft:armor_stand", 1.5, 0.5);
        double[] landed = onTheDeckTop(craft, standId, "the tilted deck");

        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, standId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        String evidence = evidence(landed, later, craft, standId);
        System.out.println("[deck-living] standRoll=" + rollDeg + " along=" + along(landed, later)
                + " across=" + across(landed, later) + evidence);
        assertTrue("an armor stand on a tilted deck must not slide along it: it moved " + along(landed, later)
                + " blocks in " + REST_WINDOW_TICKS + " ticks;" + evidence, along(landed, later) <= AT_REST);
        assertTrue("an armor stand on a tilted deck must stay on its top face: " + across(landed, later)
                + " blocks off;" + evidence, across(landed, later) <= AT_REST);
    }

    /**
     * Wherever the cow's AI took it, it stands on this craft's deck: on the ground, not below the
     * deck's top face and not far above it, and inside the deck's footprint. Its path is not read.
     */
    private void requireStillOnTheDeck(Craft craft, int cowId, double[] landed, int window, String label)
            throws Exception {
        double[] later = deckPoint(craft, cowId);
        simulatedBetween(landed, later, window);
        Reply r = Reply.of(read("stellurgytest vs player-ship-data 0 " + cowId + " " + craft.shipId).raw());
        double height = later[1] - craft.seat.seatY;
        double offX = Math.abs(later[0] - (craft.seat.seatX + 0.5));
        double offZ = Math.abs(later[2] - (craft.seat.seatZ + 0.5));
        String evidence = evidence(landed, later, craft, cowId) + " onGround=" + r.bool("playerOnGround")
                + " yaw=" + r.textOr("rotationYaw", "?") + " hasPath=" + r.textOr("hasPath", "?");
        System.out.println("[deck-living] cow" + label + " height=" + height + " offX=" + offX + " offZ="
                + offZ + " along=" + along(landed, later) + evidence);
        requireArranged("the cow must have moved itself, or nothing here is about a mob's own movement"
                + " (it is " + along(landed, later) + " blocks along the deck from where it landed);" + evidence,
                along(landed, later) >= COW_MUST_RUN);
        assertTrue("a cow on a deck must still be standing on something after " + window + " ticks;"
                + evidence, r.bool("playerOnGround"));
        assertTrue("a cow on a deck must not be below its top face: " + height + " blocks from it;"
                + evidence, height >= -AT_REST);
        assertTrue("a cow on a deck must not have been thrown up off it: " + height + " blocks above it;"
                + evidence, height <= 1.0 + AT_REST);
        assertTrue("a cow on a deck must still be over the deck: " + offX + " / " + offZ
                + " blocks from its centre, half-width " + DECK_HALF_WIDTH + ";" + evidence,
                offX <= DECK_HALF_WIDTH && offZ <= DECK_HALF_WIDTH);
    }

    /** Set the cow on fire, so that its own AI runs it about the deck. */
    private void panic(int cowId) throws Exception {
        Reply lit = Reply.of(arrange("stellurgytest entity ignite 0 " + cowId + " " + PANIC_SECONDS).raw());
        requireArranged("the cow must be burning: " + lit, lit.bool("burning"));
    }

    // -- a living body on the OUTER hull stays where it landed ------------------------------------

    /**
     * The craft's velocity while a body lies on its outer hull, blocks per second along world X: over
     * the window the craft moves about ten blocks, twice the deck's width, so a body the hull does not
     * carry ends up over nothing.
     */
    private static final String HULL_FLIGHT_VELOCITY = "2.0 0.0 0.0";

    /** The window must have carried the craft further than its own deck is wide (5 blocks). */
    private static final double HULL_FLIGHT_MIN_TRAVEL = 2.0 * DECK_HALF_WIDTH;

    /** How far off the outer face a stand "on it" may lie. Measured 2026-10-07: 0.0036 resting on the
     *  face; 1.0 with the substrate's ship collision broken (it fell to the next surface, a block on). */
    private static final double ON_THE_OUTER_FACE_BLOCKS = 0.5;

    /**
     * How far a stand lying still on the OUTER hull may move in a window, along the face or across it.
     * Not {@code AT_REST}: that bound is a deck's (the transform's round-trip error on a body the deck
     * holds), and no deck holds a body on the outer hull — the physics substrate collides it, with its
     * own noise. Measured 2026-10-07 on the substrate: along 7.8e-7 hovering and 1.56e-6 flying, across
     * 3e-13; the smallest real defect, a stand crept along the hull by the since-deleted hull-stand,
     * 0.0063. The bound sits about 60x above the first and 60x below the second.
     */
    private static final double OUTER_HULL_STILL = 1.0e-4;

    /** Deck-up world Y below which the craft still counts as upside down. Measured 2026-10-07: -0.9999
     *  held at the commanded 180 deg roll. */
    private static final double UPSIDE_DOWN_UP_Y = -0.9;

    /**
     * An armor stand lying on the OUTER hull of an upside-down craft - the underside of its deck,
     * which faces the sky - stays where it landed while the craft hovers.
     *
     * <p>Whatever holds it there is not asserted: on the outer hull a living body keeps world
     * semantics (it is not the deck's), and which mechanism collides it with the hull is the
     * question the readings printed beside the verdict answer.</p>
     */
    @RedWitness(verdict = "must come to rest on it", member = "EntityCollisionInjector#alterEntityMovement",
            at = "final double origDx = dx;", replacedBy = "if (true) return null; final double origDx = dx;",
            fails = "a stand put on the hull's outer face must come to rest on it (ship-frame Y 128.0, face 127.0)",
            on = "2026-10-07")
    @NotYetRed(prose = "THE ALONG verdict has not been seen red against the current code, where the outer hull"
            + " is held by {@code EntityCollisionInjector#alterEntityMovement}. The old witness, 2026-10-07, was"
            + " the tree before that change (HEAD fdc813556), where a hull-stand, since deleted, crept the stand"
            + " along the hull: \"a living body on the outer hull must not slide along it: 0.009586752811068293"
            + " blocks\".")
    @Pins("ENT-7")
    @Test
    public void anArmorStandOnTheOuterHullOfAnInvertedCraftStaysWhereItLanded() throws Exception {
        // R11-OK: the outer hull's hold of a living body - the substrate's
        // EntityCollisionInjector#alterEntityMovement; asserted in standOnTheOuterHull.
        standOnTheOuterHull(null);
    }

    /**
     * As above, while the craft flies sideways under the stand.
     */
    @RedWitness(verdict = "must come to rest on it", member = "EntityCollisionInjector#alterEntityMovement",
            at = "final double origDx = dx;", replacedBy = "if (true) return null; final double origDx = dx;",
            fails = "a stand put on the hull's outer face must come to rest on it (ship-frame Y 127.9999999999998,"
                    + " face 127.0)", on = "2026-10-07")
    @NotYetRed(prose = "THE ALONG verdict has not been seen red against the current code, where the outer hull"
            + " is held by {@code EntityCollisionInjector#alterEntityMovement}. The old witness, 2026-10-07, was"
            + " the tree before that change (HEAD fdc813556): \"a living body on the outer hull must not slide"
            + " along it: 0.006340703694149852 blocks\".")
    @Pins("ENT-7")
    @Test
    public void anArmorStandOnTheOuterHullOfAFlyingInvertedCraftRidesWithIt() throws Exception {
        // R11-OK: as above, with the craft's carry of the body in flight; asserted in standOnTheOuterHull.
        standOnTheOuterHull(HULL_FLIGHT_VELOCITY);
    }

    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);",
            why = "a tenth of a block at a living body's 0.08 blocks/tick^2 is about two ticks.")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);",
            why = "between `landed` above and `later` below, whose difference is asserted; flying, it is also the"
                    + " EXPERIMENT, the flight being the dose.")
    private void standOnTheOuterHull(String velocity) throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 180.0);
        // The middle of the deck's -Z edge: its column holds nothing below the deck, and neither do
        // its neighbours' (the with-pilot-deck fixture hangs its motors under the four corners, its
        // engines and tanks on the seat's row and its reaction wheel beside the seat at +Z), so on the
        // inverted craft the outer face over this cell is the deck block's underside, at ship-frame
        // Y seatY - 1.
        double face = craft.seat.seatY - 1.0;
        double[] at = toWorld(craft, 0.5, -1.0, -1.5);
        Reply spawned = Reply.of(arrange("stellurgytest vs drop-living 0 minecraft:armor_stand " + at[0] + " "
                + (at[1] + 0.1) + " " + at[2]).raw());
        requireArranged("the armor stand must have been spawned: " + spawned, spawned.boolOr("found", false));
        int standId = spawned.integer("entityId");
        GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);
        requireTicked(craft, standId);
        double[] landed = deckPoint(craft, standId);
        Reply rest = Reply.of(read("stellurgytest vs player-ship-data 0 " + standId + " " + craft.shipId).raw());
        double gap = face - landed[1];
        // On THIS face and no other: the next surface in any direction is a block away. A VERDICT, not
        // an arrangement: it was spawned a tenth of a block over the face, so the hull taking it and
        // holding it there is the subject - a face that lets it through has failed before the window.
        assertTrue("a stand put on the hull's outer face must come to rest on it (ship-frame Y "
                + landed[1] + ", face " + face + "): " + rest,
                rest.bool("playerOnGround") && Math.abs(gap) < ON_THE_OUTER_FACE_BLOCKS);

        double shipXBefore = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId).x;
        if (velocity != null) {
            Reply flown = Reply.of(arrange("stellurgytest vs force-vel-by-id 0 " + craft.shipId + " " + velocity).raw());
            requireArranged("the craft must accept the flight command: " + flown, flown.bool("afcResolved"));
        }
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, standId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        double flownX = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId).x - shipXBefore;
        String evidence = evidence(landed, later, craft, standId) + " gap=" + gap + " craftMovedX=" + flownX;
        System.out.println("[deck-living] hull" + (velocity == null ? "Static" : "Flying") + " along="
                + along(landed, later) + " across=" + across(landed, later) + evidence);
        requireArranged("the craft must still be upside down (deck-up world Y " + deckUpY(craft) + ")",
                deckUpY(craft) < UPSIDE_DOWN_UP_Y);
        if (velocity != null) {
            requireArranged("the craft must have flown further than its deck is wide while the stand lay"
                    + " on it;" + evidence, Math.abs(flownX) > HULL_FLIGHT_MIN_TRAVEL);
        }
        arrange("stellurgytest vs force-clear-by-id 0 " + craft.shipId);
        assertTrue("a living body on the outer hull must stay on it: " + across(landed, later)
                + " blocks off where it lay;" + evidence, across(landed, later) <= OUTER_HULL_STILL);
        assertTrue("a living body on the outer hull must not slide along it: " + along(landed, later)
                + " blocks;" + evidence, along(landed, later) <= OUTER_HULL_STILL);
    }

    /**
     * Put a living body of {@code type} a tenth of a block above the deck cell {@code (dx, dz)} from
     * the seat, and give it time to land. A STIMULUS of fixed length, for the same reason as the item
     * drop: what a landing leaves behind depends on which mechanism holds the body, so a link on it
     * would decide the verdict by mechanism. Every caller follows it with a reading of where it is.
     */
    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);",
            why = "a tenth of a block at a living body's 0.08 blocks/tick^2 is about two ticks.")
    private int putOnDeck(Craft craft, String type, double dx, double dz) throws Exception {
        double[] at = toWorld(craft, dx, 0.1, dz);
        // A mob through drop-living, with its AI left ON: the plain spawn verb leaves an animal to
        // the harness's spawn-animals=false, which vanilla enforces by killing it on its first
        // update, and drop-living's default switches the AI off, which stops all its movement.
        Reply spawned = Reply.of(type.equals("minecraft:armor_stand")
                ? arrange("stellurgytest entity spawn 0 " + at[0] + " " + at[1] + " " + at[2] + " " + type).raw()
                : arrange("stellurgytest vs drop-living 0 " + type + " " + at[0] + " " + at[1] + " " + at[2]
                        + " ai").raw());
        requireArranged("the " + type + " must have been spawned: " + spawned,
                spawned.boolOr("spawned", false) || spawned.boolOr("found", false));
        int id = spawned.integer("entityId");
        GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);
        requireTicked(craft, id);
        if (!type.equals("minecraft:armor_stand")) {
            Reply mob = Reply.of(read("stellurgytest vs player-ship-data 0 " + id + " " + craft.shipId).raw());
            requireArranged("the " + type + "'s AI must be running, or it does not move itself at all and"
                    + " reads still on anything: " + mob, !mob.bool("aiDisabled"));
            System.out.println("[deck-living] mobLanded yaw=" + mob.textOr("rotationYaw", "?"));
        }
        return id;
    }
}
