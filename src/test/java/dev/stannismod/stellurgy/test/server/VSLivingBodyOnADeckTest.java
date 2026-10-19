package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
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

    // -- one mover per tick: the substrate's carry keeps off a body the deck holds ------------------

    /**
     * The craft's speed once the deck has taken the body, blocks per second along world X, away from
     * where the world support stood. Any non-zero translation makes the substrate's carry of a body
     * associated with the craft non-zero (it moves the body by the craft's own between-tick motion,
     * here 0.05 blocks a tick, five orders above {@link #AT_REST}); slow enough that a body carried
     * twice over the substrate's whole association lifetime (one block) is still on the deck.
     */
    private static final double CARRY_FLIGHT_SPEED = 1.0;

    /** How far below the feet the deck frame looks for a floor, and for world terrain that declines a body ({@code DeckFrameTick.SUPPORT_PROBE}). */
    private static final double DECK_SUPPORT_PROBE = 0.30;

    /**
     * How many ticks the substrate keeps carrying a body after it last touched a hull
     * ({@code VSConfig.ticksToStickToShip}).
     */
    private static final int SUBSTRATE_STICK_TICKS = 20;

    /**
     * How long the substrate's carry is watched from the jump, in server ticks: three times its
     * association lifetime, so the whole stretch in which it would still carry the stand lies inside.
     */
    private static final int CARRY_WINDOW_TICKS = 3 * SUBSTRATE_STICK_TICKS;

    /**
     * The upward velocity the stand leaves the edge with, blocks per tick. At a living body's 0.08
     * blocks/tick^2 and 0.98 drag it rises about six ticks (1.3 blocks) - time for the support to be
     * taken away and the craft to start under it - and is back at the deck after about twelve, inside
     * the substrate's 20 ticks, so the deck takes it in the air with the association still alive.
     */
    private static final double JUMP_MOTION = 0.5;

    /**
     * How far below the deck's top the world support's top is put, in blocks. Inside the deck
     * frame's support probe ({@code DeckFrameTick.SUPPORT_PROBE}, 0.30) - so a body over it is on
     * world terrain and the deck declines it - and above zero, so the body rests on the deck and the
     * substrate collides it with the hull. The support is built to within 1/16 of this (a snow layer
     * is 1/8 high), so its top lies 0.09 to 0.21 below the deck.
     */
    private static final double SUPPORT_BELOW_DECK_TOP = 0.15;

    /** How far a deck axis may lean off the world's for the craft to count as axis-aligned. */
    private static final double AXIS_ALIGNED = 1.0e-3;

    /** The server's log, read for the substrate carry's own records about the held body. */
    private final Events carryLog =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    /**
     * An armor stand a deck takes is moved by the deck alone from then on: the physics substrate's
     * carry, which moves a body for a while after it last touched a hull, never moves it once the
     * deck holds it - though the substrate had associated it with this craft the moment before, and
     * the craft is flying.
     *
     * <p>The substrate carries a body only while it remembers a hull the body touched, and it forgets
     * one the moment the body's own update ends on something - which a held body's update, run on
     * the deck, does every tick it lies there. So the one state in which the decision can be seen is a
     * body the deck takes IN THE AIR, soon after the substrate collided it with the hull: a mob
     * jumping from a pier onto a deck. Arranged so: the stand stands on the deck's edge with its other
     * half over a world block just below the deck's top, so the deck declines it (a body over world
     * terrain is the world's) and the substrate collides it with the hull; it is given a jump, the
     * block is taken away and the craft flies, and the deck takes it on the way down. Measured
     * 2026-10-17: a body lowered onto a deck from above never gets an association at all (the deck
     * takes it before it touches), and one that steps off the support onto the deck loses it on its
     * first held update, so neither could show the substrate carrying it, held or not.</p>
     *
     * <p>Read where the substrate decides: its per-body carry step and the one call that moves a body
     * ({@code MixinSubstrateCarryEvents}). Not seen here: the client's own copy of the carry
     * ({@code EventsClient}), which moves the client's copy of a body; a player, whose movement the
     * server replays rather than runs; a body boarding from another craft's hull, or from this one's
     * outer hull.</p>
     */
    @RedWitness(member = "DeckFrameTick#mapOut", at = "VSIntegration.suppressShipDrag(entity);", replacedBy = "",
            fails = "a body a deck holds must be moved by the deck alone (C30 ENT-7): the substrate's carry moved"
                    + " the held stand 1 times", on = "2026-10-17")
    @Pins("ENT-7")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);",
            why = "a tenth of a block at a living body's 0.08 blocks/tick^2 is about two ticks; what a landing"
                    + " leaves depends on which mechanism holds the body, so a link on it would decide by mechanism.")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), CARRY_WINDOW_TICKS);",
            why = "the flight is the dose: the substrate's carry runs every tick of it, and the verdict is over"
                    + " every carry record of the stretch, not a state at its end.")
    public void anArmorStandTheDeckTakesFromAWorldSupportIsNeverCarriedByTheSubstrate() throws Exception {
        Craft craft = buildCraft();
        // The deck's +X and -X edges (5x5, the seat's column 2 blocks from the -X edge), on the row
        // beside the seat's. A craft stands where its physics put it, so an edge is rarely on a block
        // boundary; a world block reaching under the deck would sit inside the hull and the world's
        // collision would shove the craft. So the support goes in the first whole column OUTSIDE an
        // edge, on the side where the gap to it is under half a block, and the stand (half a block
        // wide) is centred over the gap: one half over the deck, the other over the support.
        double[] plusEdge = toWorld(craft, 3.0, 0.0, -0.5);
        double[] oneOut = toWorld(craft, 4.0, 0.0, -0.5);
        requireArranged("the craft must stand axis-aligned, its deck +X along world +X (one block out maps to "
                + (oneOut[0] - plusEdge[0]) + "," + (oneOut[1] - plusEdge[1]) + "," + (oneOut[2] - plusEdge[2]) + ")",
                Math.abs(oneOut[0] - plusEdge[0] - 1.0) <= AXIS_ALIGNED && Math.abs(oneOut[1] - plusEdge[1]) <= AXIS_ALIGNED
                        && Math.abs(oneOut[2] - plusEdge[2]) <= AXIS_ALIGNED);
        double plusGap = Math.ceil(plusEdge[0]) - plusEdge[0];
        int side = plusGap < 0.5 ? 1 : -1;
        double edgeX = side > 0 ? 3.0 : -2.0;
        double[] edgeTop = toWorld(craft, edgeX, 0.0, -0.5);
        double gap = side > 0 ? plusGap : edgeTop[0] - Math.floor(edgeTop[0]);
        requireArranged("one of the deck's X edges must lie under half a block from a block boundary (gap " + gap
                + ")", gap < 0.5);
        int bx = side > 0 ? (int) Math.ceil(edgeTop[0]) : (int) Math.floor(edgeTop[0]) - 1;
        int bz = (int) Math.floor(edgeTop[2]);
        double standX = edgeX + side * gap / 2.0;
        double supportTop = edgeTop[1] - SUPPORT_BELOW_DECK_TOP;
        int y0 = (int) Math.floor(supportTop);
        int layers = (int) Math.round((supportTop - y0) * 8.0);
        // A snow layer of meta m collides m/8 high and needs a solid block under it.
        int stoneY = layers == 8 ? y0 : y0 - 1;
        String snow = layers == 0 || layers == 8 ? null : "setblock " + bx + " " + y0 + " " + bz
                + " minecraft:snow_layer " + layers;
        arrange("setblock " + bx + " " + stoneY + " " + bz + " minecraft:stone");
        if (snow != null) {
            arrange(snow);
        }
        double builtTop = layers == 8 ? y0 + 1.0 : snow == null ? y0 : y0 + layers / 8.0;
        requireArranged("the world support must stand inside the deck's support probe and below its top (top "
                + builtTop + ", deck top " + edgeTop[1] + ")",
                builtTop < edgeTop[1] && edgeTop[1] - builtTop < DECK_SUPPORT_PROBE);

        long mark = carryLog.markInstrumented();
        double[] at = toWorld(craft, standX, 0.1, -0.5);
        Reply spawned = Reply.of(arrange("stellurgytest entity spawn 0 " + at[0] + " " + at[1] + " " + at[2]
                + " minecraft:armor_stand").raw());
        requireArranged("the armor stand must have been spawned: " + spawned, spawned.boolOr("spawned", false));
        int standId = spawned.integer("entityId");
        String id = String.valueOf(standId);
        GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);
        requireTicked(craft, standId);
        onTheDeckTop(craft, standId, "the deck's edge");
        String touches = carryLog.since(mark, "entity_touched_ship");
        Events.assertInstrumentRan(touches, "entity_ship_touch_events",
                "the substrate associated the stand with the craft");
        requireArranged("the substrate must have associated the stand with THIS craft as it landed on the edge,"
                + " or it would never carry it, held or not: " + touches,
                Events.anyRecordHasAll(touches, "entity", id, "vsShip", craft.shipId));
        String before = carryLog.since(mark, "substrate_carry_visit");
        requireArranged("the deck must have declined the stand over the world support, or it took a body the"
                + " substrate had never been allowed to carry: " + before,
                Events.recordsWhere(before, "e", id).isEmpty());

        long taken = carryLog.mark();
        double jumpedAt = deckPoint(craft, standId)[3];
        Reply jumped = Reply.of(arrange("stellurgytest entity set-motion 0 " + standId + " 0.0 " + JUMP_MOTION
                + " 0.0").raw());
        requireArranged("the stand must have been given its jump: " + jumped, jumped.ok());
        if (snow != null) {
            arrange("setblock " + bx + " " + y0 + " " + bz + " minecraft:air");
        }
        arrange("setblock " + bx + " " + stoneY + " " + bz + " minecraft:air");
        Reply flown = Reply.of(arrange("stellurgytest vs force-vel-by-id 0 " + craft.shipId + " "
                + (-side * CARRY_FLIGHT_SPEED) + " 0.0 0.0").raw());
        requireArranged("the craft must accept the flight command: " + flown, flown.bool("afcResolved"));
        double shipXBefore = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId).x;
        GameTicks.advance(client(), GameTicks.server(), CARRY_WINDOW_TICKS);
        double flownX = ShipInfo.byId(c -> read(c).raw(), 0, craft.shipId).x - shipXBefore;
        arrange("stellurgytest vs force-clear-by-id 0 " + craft.shipId);

        String visits = carryLog.since(taken, "substrate_carry_visit");
        String applied = carryLog.since(taken, "substrate_carry_applied");
        Events.assertInstrumentRan(visits, "substrate_carry_events",
                "the substrate's carry never moved the held stand");
        // A quarter of the commanded travel: the craft starts from rest and ramps up (measured 2026-10-17,
        // healthy: 1.70 of 3.0), and all this premise needs is that its pose was moving between ticks.
        double commanded = CARRY_FLIGHT_SPEED * CARRY_WINDOW_TICKS / 20.0;
        requireArranged("the craft must have flown while the stand lay on it (moved " + flownX + " blocks of a"
                + " commanded " + commanded + ")", Math.abs(flownX) >= commanded / 4.0);
        java.util.List<String> heldVisits = Events.recordsWhereAll(visits, "e", id, "heldShip", craft.shipId);
        requireArranged("the deck must have held the stand for most of the window, or a carry it escaped is"
                + " about nothing (held at " + heldVisits.size() + " of " + CARRY_WINDOW_TICKS + " carry steps): "
                + visits, heldVisits.size() >= CARRY_WINDOW_TICKS / 2);
        // The one state in which the substrate would carry a held body: taken by the deck while still in
        // the air, within the substrate's association lifetime of its last touch (the jump) - once a
        // held body's own update ends on the deck, the substrate's own bookkeeping forgets the hull.
        String firstHeld = heldVisits.get(0);
        double heldAfter = Events.number(firstHeld, "ticks") - jumpedAt;
        requireArranged("the deck must have taken the stand in the air, within " + SUBSTRATE_STICK_TICKS
                + " ticks of its jump off the hull (taken " + heldAfter + " ticks after): " + firstHeld,
                "false".equals(Events.text(firstHeld, "onGround")) && heldAfter < SUBSTRATE_STICK_TICKS);

        java.util.List<String> carriedWhileHeld = Events.recordsWhereAll(applied, "e", id, "held", "true");
        System.out.println("[deck-living] substrateCarry heldVisits=" + heldVisits.size() + " carriedWhileHeld="
                + carriedWhileHeld.size() + " flownX=" + flownX + " firstVisit="
                + (heldVisits.isEmpty() ? "none" : heldVisits.get(0)));
        assertTrue("a body a deck holds must be moved by the deck alone (C30 ENT-7): the substrate's carry"
                + " moved the held stand " + carriedWhileHeld.size() + " times: " + carriedWhileHeld,
                carriedWhileHeld.isEmpty());
    }

    // -- an enclosed interior takes a body with nothing under its feet (C8 CREW-C12) ---------------

    /**
     * How long a body let go mid-cavity is given to be taken and seated, in server ticks: a chicken,
     * flapping, falls at about 0.11 blocks a tick at most ((v - 0.08) * 0.98 * 0.6 = v), so the two
     * blocks to a deck take it about twenty ticks; five times that.
     */
    private static final int CAVITY_SETTLE_TICKS = 100;

    /** The highest layer above the deck searched for the roof's underside, in blocks. */
    private static final int ROOF_SEARCH_LAYERS = 8;

    /** The chicken's height, blocks ({@code EntityChicken}'s size): the cavity must take it twice over. */
    private static final double CHICKEN_HEIGHT = 0.7;

    /** The server's log, read for the deck's own record of taking the body. */
    private final Events boardingLog =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    /**
     * A living body let go in the middle of an upside-down craft's enclosed cabin - nothing under
     * its feet, a roof over its head - is taken by the deck and seated on it: it ends standing on
     * the deck, held, against the world's gravity, which pulls it toward the roof.
     *
     * <p>Only the enclosure takes it: it hangs half the cabin's height above the deck, out of reach
     * of the standing probe (0.30 below the feet) and declared by nothing, so the deck that holds it
     * at the end is the interior gate's ({@code DeckBoarding#interiorCandidate}, asked by the deck's
     * admission). Without that gate the world drops it onto the roof's inner face, where in the
     * craft's frame there is no floor beneath it and nothing takes it.</p>
     *
     * <p>A chicken and not an armor stand: upside down, a body's world box hangs from its feet toward
     * the deck while its box in the craft's frame stands up toward the roof, and only a body shorter
     * than half the cabin fits both ways round. Its AI is on - a mob with it off does not move at
     * all - and how it wanders on the deck is not read. Not seen here: a player, whose client admits
     * him; the roofed keep past the deck frame's six-block floor probe.</p>
     */
    @RedWitness(verdict = "must be held by this craft's deck", member = "DeckFrameTick#admissionFor",
            at = "? DeckBoarding.interiorCandidate((EntityLivingBase) entity) : null;", replacedBy = "? null : null;",
            fails = "a body inside an enclosed cabin must be held by this craft's deck even with nothing under its"
                    + " feet (C8 CREW-C12): heldBy=none", on = "2026-10-18")
    @NotYetRed(prose = "the seating verdict: the same inversion stops at the hold verdict before it, the chicken"
            + " lying on the roof's inner face at 3.997 above the deck (2026-10-18).")
    @Pins("CREW-C12")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), CAVITY_SETTLE_TICKS);",
            why = "where the body comes to rest is a converging value with no announcement of its end; the"
                    + " stretch is spent in full and the body read once after, its own tick count read at both"
                    + " ends to show the world simulated it.")
    public void aChickenLetGoInsideAnInvertedCabinIsSeatedOnTheDeck() throws Exception {
        Craft craft = buildCraft("with-roofed-deck");
        rollTo(craft, 180.0);
        // The cabin's height at the cell one off the seat on each axis, read layer by layer from the
        // deck's top in the craft's subspace: the first block that is not air is the roof's underside.
        int cellX = (int) Math.floor(craft.seat.seatX + 1.5);
        int cellZ = (int) Math.floor(craft.seat.seatZ - 0.5);
        int deckTop = (int) Math.round(craft.seat.seatY);
        int roofAt = -1;
        String column = "";
        for (int k = 0; k < ROOF_SEARCH_LAYERS; k++) {
            Reply block = Reply.of(read("stellurgytest block at 0 " + cellX + " " + (deckTop + k) + " " + cellZ).raw());
            String id = block.textOr("block", "?");
            column += k + "=" + id + " ";
            if (roofAt < 0 && !"minecraft:air".equals(id)) {
                roofAt = k;
            }
        }
        requireArranged("the cabin must be open above the deck and roofed within " + ROOF_SEARCH_LAYERS
                + " blocks, at least twice a chicken high (" + column + ")", roofAt >= 2.0 * CHICKEN_HEIGHT + 0.1);
        double feet = roofAt / 2.0;

        long mark = boardingLog.markInstrumented();
        double[] at = toWorld(craft, 1.5, feet, -0.5);
        Reply spawned = Reply.of(arrange("stellurgytest vs drop-living 0 minecraft:chicken " + at[0] + " " + at[1]
                + " " + at[2] + " ai").raw());
        requireArranged("the chicken must have been spawned: " + spawned, spawned.boolOr("found", false));
        int chickenId = spawned.integer("entityId");
        double[] first = deckPoint(craft, chickenId);
        // Upside down its world box hangs CHICKEN_HEIGHT from its feet toward the deck, which also puts
        // the feet past the standing probe's 0.30.
        requireArranged("the chicken must hang mid-cabin, its world box clear of the deck and out of the standing"
                + " probe's reach (deck-frame height above the deck " + (first[1] - craft.seat.seatY) + ")",
                first[1] - craft.seat.seatY > CHICKEN_HEIGHT && first[1] - craft.seat.seatY + CHICKEN_HEIGHT < roofAt);
        GameTicks.advance(client(), GameTicks.server(), CAVITY_SETTLE_TICKS);
        requireTicked(craft, chickenId);
        double[] settled = deckPoint(craft, chickenId);
        simulatedBetween(first, settled, CAVITY_SETTLE_TICKS);
        Reply held = Reply.of(read("stellurgytest vs player-ship-data 0 " + chickenId + " " + craft.shipId).raw());
        requireArranged("the craft must still be upside down (deck-up world Y " + deckUpY(craft) + ")",
                deckUpY(craft) < UPSIDE_DOWN_UP_Y);

        String claims = boardingLog.since(mark, "interior_claimed");
        String entered = boardingLog.since(mark, "deck_entered");
        System.out.println("[deck-living] cabin roofAt=" + roofAt + " letGoAt=" + (first[1] - craft.seat.seatY)
                + " settledAt=" + (settled[1] - craft.seat.seatY) + " heldBy=" + held.textOr("deckHeldBy", "none")
                + " interiorClaims=" + Events.recordsWhere(claims, "e", String.valueOf(chickenId)).size()
                + " entered=" + Events.recordsWhere(entered, "e", String.valueOf(chickenId)));
        assertTrue("a body inside an enclosed cabin must be held by this craft's deck even with nothing under its"
                + " feet (C8 CREW-C12): heldBy=" + held.textOr("deckHeldBy", "none") + " " + held,
                craft.shipId.equals(held.textOr("deckHeldBy", null)));
        assertTrue("the deck's gravity must seat it on the deck, against the world's (deck-frame height above"
                + " the deck " + (settled[1] - craft.seat.seatY) + ", the roof's underside at " + roofAt + ")",
                Math.abs(settled[1] - craft.seat.seatY) <= AT_REST);
    }

    // -- a medium aboard is the craft's; only another owner ends a hold (C30 ENT-1, R4) ------------

    /**
     * How long a held body is watched in a medium or a state, in server ticks. Which states end a
     * hold is judged on every update of the body, so a state that ends it does so on the body's first
     * update in it; three seconds also outlasts the levitation dose below.
     */
    private static final int MEDIUM_WINDOW_TICKS = 60;

    /** The levitation dose, ticks: about 1.8 blocks of rise, inside the deck frame's six-block floor probe. */
    private static final int LEVITATION_TICKS = 40;

    /** The cell of the deck the medium cases use, from the seat's corner: one off the seat on each axis. */
    private static final double MEDIUM_CELL_DX = 1.5;
    private static final double MEDIUM_CELL_DZ = -0.5;

    /**
     * Put a living body of {@code type} on the medium cases' deck cell and gate that the deck holds it
     * there - the positive precondition every "it stays held" verdict below needs.
     */
    private int heldOnTheMediumCell(Craft craft, String type) throws Exception {
        int id = putOnDeck(craft, type, MEDIUM_CELL_DX, MEDIUM_CELL_DZ);
        Reply r = Reply.of(read("stellurgytest vs player-ship-data 0 " + id + " " + craft.shipId).raw());
        requireArranged("the deck must hold the " + type + " before the medium is introduced: " + r,
                craft.shipId.equals(r.textOr("deckHeldBy", null)));
        return id;
    }

    /** Premise: a deck took body {@code id} at some point since {@code mark} (the deck frame's own record). */
    private void requireTakenSince(int id, long mark) throws Exception {
        String entered = boardingLog.since(mark, "deck_entered");
        requireArranged("a deck must have taken the body, or \"it was never let go\" is about nothing: " + entered,
                !Events.recordsWhere(entered, "e", String.valueOf(id)).isEmpty());
    }

    /** The subspace block cell the medium cases use, at {@code layer} blocks from the deck's top. */
    private int[] mediumCell(Craft craft, int layer) {
        return new int[]{(int) Math.floor(craft.seat.seatX + MEDIUM_CELL_DX),
                (int) Math.round(craft.seat.seatY) + layer, (int) Math.floor(craft.seat.seatZ + MEDIUM_CELL_DZ)};
    }

    /** One block id at a subspace or world cell. */
    private String blockAt(int[] cell) throws Exception {
        return Reply.of(read("stellurgytest block at 0 " + cell[0] + " " + cell[1] + " " + cell[2]).raw())
                .textOr("block", "?");
    }

    /**
     * Verdict: since {@code mark} - taken while the deck held the body - no deck has let it go. The
     * window is spent by the caller; the release record is the deck frame's own, written at the one
     * place a hold ends.
     */
    private void assertNeverLetGo(Craft craft, int id, long mark, String clause) throws Exception {
        requireTicked(craft, id);
        String released = boardingLog.since(mark, "deck_released");
        Events.assertInstrumentRan(released, "deck_capture_events", "the deck never let the body go");
        java.util.List<String> mine = Events.recordsWhere(released, "e", String.valueOf(id));
        Reply now = Reply.of(read("stellurgytest vs player-ship-data 0 " + id + " " + craft.shipId).raw());
        System.out.println("[deck-living] medium releases=" + mine + " now=" + now);
        assertTrue(clause + ": the deck let the body go " + mine, mine.isEmpty());
    }

    /**
     * A body in the craft's own water stays held: S's water acts on it in S's frame, through its own
     * update. Arranged as a one-block pool sunk into the deck - water where a deck block was, stone
     * under it, the deck's own blocks around it - so it cannot spread.
     */
    @RedWitness(member = "DeckBoarding#excludedStateOf", at = "if (entity.isRiding() || entity.isElytraFlying()) {",
            replacedBy = "if (entity.isRiding() || entity.isElytraFlying() || entity.isInWater()) {",
            fails = "a body in the craft's own water must stay held (C30 ENT-1, R4): the deck let the body go",
            on = "2026-10-19")
    @Pins("ENT-1")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);",
            why = "the stretch is the dose: the hold is judged on every update in the water, and the verdict is"
                    + " over every release record of it.")
    public void anArmorStandInAPoolAboardStaysHeld() throws Exception {
        Craft craft = buildCraft();
        int[] pool = mediumCell(craft, -1);
        int[] floor = mediumCell(craft, -2);
        arrange("setblock " + floor[0] + " " + floor[1] + " " + floor[2] + " minecraft:stone");
        arrange("setblock " + pool[0] + " " + pool[1] + " " + pool[2] + " minecraft:water");
        requireArranged("the pool must be water over stone (" + blockAt(pool) + " over " + blockAt(floor) + ")",
                "minecraft:water".equals(blockAt(pool)) && "minecraft:stone".equals(blockAt(floor)));
        // Marked BEFORE the stand lands: it lands in the water, so a release on landing is the defect.
        long mark = boardingLog.markInstrumented();
        int id = putOnDeck(craft, "minecraft:armor_stand", MEDIUM_CELL_DX, MEDIUM_CELL_DZ);
        GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);
        requireTakenSince(id, mark);
        assertNeverLetGo(craft, id, mark, "a body in the craft's own water must stay held (C30 ENT-1, R4)");
    }

    /** A body on the craft's own ladder stays held: S's ladder acts on it in S's frame. */
    @NotYetRed(prose = "green before the R4 change as after it (measured 2026-10-19): the old exclusion asked"
            + " {@code isOnLadder} at the body's WORLD position, where the craft's ladder is not, so it never let a"
            + " body on a craft's ladder go. No plausible regression of our code reaches this verdict; it stands as"
            + " the measurement that a ladder aboard is the craft's.")
    @Pins("ENT-1")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);",
            why = "the stretch is the dose: the hold is judged on every update on the ladder.")
    public void anArmorStandOnALadderAboardStaysHeld() throws Exception {
        Craft craft = buildCraft();
        int[] ladder = mediumCell(craft, 0);
        int[] wall = {ladder[0] + 1, ladder[1], ladder[2]};
        arrange("setblock " + wall[0] + " " + wall[1] + " " + wall[2] + " minecraft:stone");
        // Facing west (meta 4): its back against the stone at +X.
        arrange("setblock " + ladder[0] + " " + ladder[1] + " " + ladder[2] + " minecraft:ladder 4");
        requireArranged("the ladder must stand on the deck against its wall (" + blockAt(ladder) + " beside "
                + blockAt(wall) + ")", "minecraft:ladder".equals(blockAt(ladder)) && "minecraft:stone".equals(blockAt(wall)));
        // Marked BEFORE the stand lands: it lands on the ladder, so a release on landing is the defect.
        long mark = boardingLog.markInstrumented();
        int id = putOnDeck(craft, "minecraft:armor_stand", MEDIUM_CELL_DX, MEDIUM_CELL_DZ);
        GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);
        requireTakenSince(id, mark);
        assertNeverLetGo(craft, id, mark, "a body on the craft's own ladder must stay held (C30 ENT-1, R4)");
    }

    /** A levitating body stays held: levitation runs in S's frame, along the deck's up. */
    @RedWitness(member = "DeckBoarding#excludedStateOf", at = "if (entity.isRiding() || entity.isElytraFlying()) {",
            replacedBy = "if (entity.isRiding() || entity.isElytraFlying()"
                    + " || entity.isPotionActive(net.minecraft.init.MobEffects.LEVITATION)) {",
            fails = "a levitating body must stay held (C30 ENT-1, R4): the deck let the body go", on = "2026-10-19")
    @Pins("ENT-1")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);",
            why = "the stretch is the dose: the hold is judged on every update while the effect runs.")
    public void aCowLevitatingOnADeckStaysHeld() throws Exception {
        Craft craft = buildCraft();
        int id = heldOnTheMediumCell(craft, "minecraft:cow");
        long mark = boardingLog.markInstrumented();
        Reply effect = Reply.of(arrange("stellurgytest entity add-effect 0 " + id + " minecraft:levitation "
                + LEVITATION_TICKS).raw());
        requireArranged("the cow must be levitating: " + effect, effect.bool("active"));
        GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);
        assertNeverLetGo(craft, id, mark, "a levitating body must stay held (C30 ENT-1, R4)");
    }

    /** A body with no gravity stays held: it runs in S's frame, keeping the deck's orientation. */
    @RedWitness(member = "DeckBoarding#excludedStateOf", at = "if (entity.isRiding() || entity.isElytraFlying()) {",
            replacedBy = "if (entity.isRiding() || entity.isElytraFlying() || entity.hasNoGravity()) {",
            fails = "a body with no gravity must stay held (C30 ENT-1, R4): the deck let the body go", on = "2026-10-19")
    @Pins("ENT-1")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);",
            why = "the stretch is the dose: the hold is judged on every update with no gravity.")
    public void anArmorStandWithNoGravityOnADeckStaysHeld() throws Exception {
        Craft craft = buildCraft();
        int id = heldOnTheMediumCell(craft, "minecraft:armor_stand");
        long mark = boardingLog.markInstrumented();
        Reply off = Reply.of(arrange("stellurgytest entity set-no-gravity 0 " + id + " true").raw());
        requireArranged("the stand must have no gravity: " + off, off.bool("hasNoGravity"));
        GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);
        assertNeverLetGo(craft, id, mark, "a body with no gravity must stay held (C30 ENT-1, R4)");
    }

    /**
     * World water where the body stands in the WORLD: one source block at the cell the held body's world
     * position falls in, on {@code craft}, which hovers. Returns that world cell, for its removal.
     */
    private int[] worldWaterAt(Craft craft, int id) throws Exception {
        Reply r = Reply.of(read("stellurgytest vs player-ship-data 0 " + id + " " + craft.shipId).raw());
        int[] cell = {(int) Math.floor(r.number("playerX")), (int) Math.floor(r.number("playerY") + 0.1),
                (int) Math.floor(r.number("playerZ"))};
        arrange("setblock " + cell[0] + " " + cell[1] + " " + cell[2] + " minecraft:water");
        requireArranged("a world water source must stand where the body is in the world (" + blockAt(cell) + ")",
                "minecraft:water".equals(blockAt(cell)));
        return cell;
    }

    /**
     * World water acts on a held body where the craft does not enclose it: an armor stand on an OPEN
     * deck that the world's water reaches is let go to the world, reason {@code worldMedium}.
     */
    @RedWitness(verdict = "must be let go to the world", member = "DeckFrameTick#runInDeckFrame",
            at = "if (DeckBoarding.worldMediumReaches(entity, shipId, local)) {", replacedBy = "if (false) {",
            fails = "a held body the world's water reaches on an open deck must be let go to the world (C30 ENT-1, R4)",
            on = "2026-10-19")
    @RedWitness(verdict = "must not be taken back", member = "DeckFrameTick#admissionFor",
            at = "if (DeckBoarding.worldMediumReaches(entity, shipId, local)) {", replacedBy = "if (false) {",
            fails = "a body let go to the world's water must not be taken back while the water still reaches it:"
                    + " released 66 times, taken again 66 times", on = "2026-10-19")
    @Pins("ENT-1")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);",
            why = "the stretch is the dose: the body stands in the world's water for all of it, and the verdict"
                    + " reads every release record of it.")
    public void anArmorStandOnAnOpenDeckInTheWorldsWaterIsLetGoToTheWorld() throws Exception {
        Craft craft = buildCraft();
        int id = heldOnTheMediumCell(craft, "minecraft:armor_stand");
        long mark = boardingLog.markInstrumented();
        int[] water = worldWaterAt(craft, id);
        try {
            GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);
            requireTicked(craft, id);
            String released = boardingLog.since(mark, "deck_released");
            Events.assertInstrumentRan(released, "deck_capture_events", "the deck let the body go to the world");
            java.util.List<String> mine = Events.recordsWhereAll(released, "e", String.valueOf(id),
                    "reason", "worldMedium");
            System.out.println("[deck-living] worldWaterOpen releases=" + Events.recordsWhere(released, "e",
                    String.valueOf(id)));
            assertTrue("a held body the world's water reaches on an open deck must be let go to the world"
                    + " (C30 ENT-1, R4): " + released, !mine.isEmpty());
            // Let go means STAYS let go: a deck that takes it straight back and lets it go again is
            // two owners fighting over it every tick, not a hand-over.
            String retaken = boardingLog.since(mark, "deck_entered");
            java.util.List<String> again = Events.recordsWhere(retaken, "e", String.valueOf(id));
            assertTrue("a body let go to the world's water must not be taken back while the water still reaches"
                    + " it: released " + Events.recordsWhere(released, "e", String.valueOf(id)).size()
                    + " times, taken again " + again.size() + " times", again.isEmpty());
        } finally {
            arrange("setblock " + water[0] + " " + water[1] + " " + water[2] + " minecraft:air");
        }
    }

    /**
     * Under the craft's roof the world's water does not reach a held body: an armor stand on a cabin's
     * deck, where the world has water, stays held.
     */
    @RedWitness(member = "DeckBoarding#worldMediumReaches",
            at = "return !DeckFrameTick.craftCovers(entity.world, entity, local[0], local[1], local[2]);",
            replacedBy = "return true;",
            fails = "a body under the craft's roof must stay held where the world has water (C30 ENT-1, R4)",
            on = "2026-10-19")
    @Pins("ENT-1")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);",
            why = "the stretch is the dose: the body stands where the world has water for all of it.")
    public void anArmorStandUnderTheCraftsRoofWhereTheWorldHasWaterStaysHeld() throws Exception {
        Craft craft = buildCraft("with-roofed-deck");
        int id = heldOnTheMediumCell(craft, "minecraft:armor_stand");
        long mark = boardingLog.markInstrumented();
        int[] water = worldWaterAt(craft, id);
        try {
            GameTicks.advance(client(), GameTicks.server(), MEDIUM_WINDOW_TICKS);
            assertNeverLetGo(craft, id, mark, "a body under the craft's roof must stay held where the world has"
                    + " water (C30 ENT-1, R4)");
        } finally {
            arrange("setblock " + water[0] + " " + water[1] + " " + water[2] + " minecraft:air");
        }
    }

    /** Server ticks for the deck to let go of a body falling out through a hole in it: a deadline on the record. */
    private static final int HOLE_RELEASE_LINK_TICKS = 200;

    /**
     * The release record's height resolution, blocks: it is written to six significant figures
     * ({@code TestTrace.fmt}), so a world height under 10 000 is rounded to 0.01 at worst. A body let go
     * just past the craft's bottom may read up to this above it; one let go at the hole reads a whole
     * deck block or more above it.
     */
    private static final double RELEASE_HEIGHT_RESOLUTION = 0.01;

    /**
     * A living body under a craft's roof is not let go for want of a floor while it is still inside
     * the craft: a chicken standing on a cabin's deck when the deck block under it is taken away falls
     * through the hole and is released only once it has left the craft's block region - never at the
     * hole, where no floor lies within the deck frame's six-block probe below it.
     *
     * <p>The decision is the keep's second branch, {@code DeckBoarding#deckMayKeep}: inside the craft's
     * own block region under a roof. Upright, so the world's gravity and the deck's pull the same way and
     * the fall is the chicken's own. Read from the deck frame's release record, whose height says where
     * it was let go. Not seen here: a body that stays inside a roofed cavity taller than the probe, which
     * no fixture builds; a player.</p>
     */
    @RedWitness(member = "DeckBoarding#deckMayKeep", at = "&& hasRoofAboveAt(entity, shipId, local);",
            replacedBy = "&& false;",
            fails = "a body under the craft's roof must not be let go for want of a floor while it is inside the"
                    + " craft (C8 CREW-C12)", on = "2026-10-18")
    @Pins("CREW-C12")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);",
            why = "a tenth of a block at a living body's 0.08 blocks/tick^2 is about two ticks; the hold that"
                    + " follows is read as a premise.")
    public void aChickenFallingThroughAHoleInACabinDeckIsHeldUntilItLeavesTheCraft() throws Exception {
        Craft craft = buildCraft("with-roofed-deck");
        Reply spot = Reply.of(read("stellurgytest vs deck-spot 0 id " + craft.shipId).raw());
        requireArranged("the deck must have a free cell beside the seat: " + spot, spot.ok());
        int cx = spot.integer("subX");
        int cy = spot.integer("subY");
        int cz = spot.integer("subZ");
        int regionBottom = cy - spot.integer("offY");
        // The column through the cell: the deck block under it, nothing below it down to the region's
        // bottom, and a roof above - read whole, then judged.
        String under = "";
        boolean clearBelow = true;
        for (int y = cy - 2; y >= regionBottom; y--) {
            String id = Reply.of(read("stellurgytest block at 0 " + cx + " " + y + " " + cz).raw()).textOr("block", "?");
            under += y + "=" + id + " ";
            clearBelow &= "minecraft:air".equals(id);
        }
        String deckBlock = Reply.of(read("stellurgytest block at 0 " + cx + " " + (cy - 1) + " " + cz).raw())
                .textOr("block", "?");
        String above = "";
        boolean roofed = false;
        for (int k = 1; k <= ROOF_SEARCH_LAYERS; k++) {
            String id = Reply.of(read("stellurgytest block at 0 " + cx + " " + (cy + k) + " " + cz).raw()).textOr("block", "?");
            above += (cy + k) + "=" + id + " ";
            roofed |= !"minecraft:air".equals(id);
        }
        requireArranged("the cell must stand on a deck block, over nothing down to the craft's bottom (layer "
                + regionBottom + "), under a roof: deck " + deckBlock + "; below " + under + "; above " + above,
                !"minecraft:air".equals(deckBlock) && clearBelow && roofed && regionBottom < cy - 1);

        double dx = cx + 0.5 - craft.seat.seatX;
        double dz = cz + 0.5 - craft.seat.seatZ;
        double[] at = toWorld(craft, dx, 0.1, dz);
        Reply spawned = Reply.of(arrange("stellurgytest vs drop-living 0 minecraft:chicken " + at[0] + " " + at[1]
                + " " + at[2] + " ai").raw());
        requireArranged("the chicken must have been spawned: " + spawned, spawned.boolOr("found", false));
        int chickenId = spawned.integer("entityId");
        GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);
        requireTicked(craft, chickenId);
        Reply before = Reply.of(read("stellurgytest vs player-ship-data 0 " + chickenId + " " + craft.shipId).raw());
        requireArranged("the deck must hold the chicken standing on it before the hole opens: " + before,
                craft.shipId.equals(before.textOr("deckHeldBy", null)));

        long mark = boardingLog.markInstrumented();
        arrange("setblock " + cx + " " + (cy - 1) + " " + cz + " minecraft:air");
        String released = boardingLog.awaitField(mark, "deck_released", "e", chickenId,
                "the chicken falls out through the hole, so the deck must let it go at some point",
                HOLE_RELEASE_LINK_TICKS);
        String release = Events.recordsWhere(released, "e", String.valueOf(chickenId)).get(0);
        requireArranged("the craft must still stand upright (deck-up world Y " + deckUpY(craft) + ")",
                deckUpY(craft) > 1.0 - DECK_UP_Y_TOLERANCE);
        double deckTopWorld = toWorld(craft, dx, 0.0, dz)[1];
        double letGoAt = Events.number(release, "y") - deckTopWorld;
        double bottomAt = regionBottom - craft.seat.seatY;
        System.out.println("[deck-living] hole letGoAt=" + letGoAt + " regionBottom=" + bottomAt + " release=" + release);
        assertTrue("a body under the craft's roof must not be let go for want of a floor while it is inside the"
                + " craft (C8 CREW-C12): let go " + letGoAt + " from the deck's top, the craft's bottom at "
                + bottomAt + ": " + release, letGoAt <= bottomAt + RELEASE_HEIGHT_RESOLUTION);
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
