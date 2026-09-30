package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipInfo;

import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

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
 */
public class VSLivingBodyOnADeckE2ETest extends AbstractDeckBodyE2ETest {

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

    @Test
    public void anArmorStandOnADeckRolled30DegreesStaysWhereItLanded() throws Exception {
        standRestsOnADeckRolledBy(30.0);
    }

    @Test
    public void anArmorStandOnADeckRolled60DegreesStaysWhereItLanded() throws Exception {
        standRestsOnADeckRolledBy(60.0);
    }

    /**
     * The deck rolls over while the stand is on it: past a right angle world-down points away from
     * the deck, so "falls toward the deck" and "falls toward the world" give different answers.
     */
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

    /** The craft keeps rolling at a steady rate under the stand; read while it is still turning. */
    @Test
    public void anArmorStandOnADeckStaysOnItWhileTheCraftKeepsRolling() throws Exception {
        Craft craft = buildCraft();
        int standId = putOnDeck(craft, "minecraft:armor_stand", 1.5, 0.5);
        double[] landed = onTheDeckTop(craft, standId, "the level deck");

        double upBefore = deckUpY(craft);
        Reply spun = Reply.of(exec("stellurgytest vs force-rot-by-id 0 " + craft.shipId + " "
                + ROLL_RATE + " 0 0"));
        requireArranged("the craft must accept the roll command: " + spun, spun.bool("afcResolved"));
        // EXPERIMENT: a stretch of steady rolling; the read is taken with the craft still turning.
        GameTicks.advance(client(), GameTicks.server(), REST_WINDOW_TICKS);
        double[] later = deckPoint(craft, standId);
        ShipInfo turning = ShipInfo.byId(this::exec, 0, craft.shipId);
        simulatedBetween(landed, later, REST_WINDOW_TICKS);
        requireArranged("the craft must still be turning when the stand is read (omega "
                + turning.omega + ")", turning.omega > ROLL_RATE / 4.0);
        requireArranged("the deck must have turned well away from level (deck-up world Y " + upBefore
                + " -> " + deckUpY(craft) + ")", Math.abs(deckUpY(craft) - upBefore) > 0.5);

        String evidence = evidence(landed, later, craft, standId) + " omega=" + turning.omega;
        System.out.println("[deck-living] standRolling along=" + along(landed, later) + " across="
                + across(landed, later) + evidence);
        exec("stellurgytest vs force-clear-by-id 0 " + craft.shipId);
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
    @Test
    public void anArmorStandLetGoAboveADeckInAWorldWithoutGravityFallsOntoIt() throws Exception {
        withOverworldGravity(0.0, () -> {
            Craft craft = buildCraft();
            int standId = putOnDeck(craft, "minecraft:armor_stand", 1.5, 0.5);
            double[] first = deckPoint(craft, standId);
            // WINDOW: the fall takes a couple of ticks; overshoot only gives a hanging stand longer.
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

    /** A cow running about a deck tilted 60 deg is, wherever it ran, still on the deck. */
    @Test
    public void aCowRunningAboutADeckRolled60DegreesIsStillOnTheDeck() throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, 60.0);
        int cowId = putOnDeck(craft, "minecraft:cow", 1.5, 1.5);
        double[] landed = onTheDeckTop(craft, cowId, "the tilted deck");
        panic(cowId);
        // EXPERIMENT: the cow's own AI, running from the fire, for the whole window.
        GameTicks.advance(client(), GameTicks.server(), COW_WINDOW_TICKS);
        requireStillOnTheDeck(craft, cowId, landed, COW_WINDOW_TICKS, "tilted60");
    }

    /** A cow running about a deck while the craft rolls over is, afterwards, still on the deck. */
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

    private void standRestsOnADeckRolledBy(double rollDeg) throws Exception {
        Craft craft = buildCraft();
        rollTo(craft, rollDeg);
        int standId = putOnDeck(craft, "minecraft:armor_stand", 1.5, 0.5);
        double[] landed = onTheDeckTop(craft, standId, "the tilted deck");

        // WINDOW: two reads in the ship's frame with a stretch of standing between them; overshoot
        // gives a sliding stand longer to slide, so it can only turn a green red.
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
        Reply r = Reply.of(exec("stellurgytest vs player-ship-data 0 " + cowId + " " + craft.shipId));
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
        Reply lit = Reply.of(exec("stellurgytest entity ignite 0 " + cowId + " " + PANIC_SECONDS));
        requireArranged("the cow must be burning: " + lit, lit.bool("burning"));
    }

    /**
     * Put a living body of {@code type} a tenth of a block above the deck cell {@code (dx, dz)} from
     * the seat, and give it time to land. A STIMULUS of fixed length, for the same reason as the item
     * drop: what a landing leaves behind depends on which mechanism holds the body, so a link on it
     * would decide the verdict by mechanism. Every caller follows it with a reading of where it is.
     */
    private int putOnDeck(Craft craft, String type, double dx, double dz) throws Exception {
        double[] at = toWorld(craft, dx, 0.1, dz);
        // A mob through drop-living, with its AI left ON: the plain spawn verb leaves an animal to
        // the harness's spawn-animals=false, which vanilla enforces by killing it on its first
        // update, and drop-living's default switches the AI off, which stops all its movement.
        Reply spawned = Reply.of(type.equals("minecraft:armor_stand")
                ? exec("stellurgytest entity spawn 0 " + at[0] + " " + at[1] + " " + at[2] + " " + type)
                : exec("stellurgytest vs drop-living 0 " + type + " " + at[0] + " " + at[1] + " " + at[2]
                        + " ai"));
        requireArranged("the " + type + " must have been spawned: " + spawned,
                spawned.boolOr("spawned", false) || spawned.boolOr("found", false));
        int id = spawned.integer("entityId");
        // STIMULUS: a tenth of a block at a living body's 0.08 blocks/tick^2 is about two ticks.
        GameTicks.advance(client(), GameTicks.server(), LAND_TICKS);
        requireTicked(craft, id);
        if (!type.equals("minecraft:armor_stand")) {
            Reply mob = Reply.of(exec("stellurgytest vs player-ship-data 0 " + id + " " + craft.shipId));
            requireArranged("the " + type + "'s AI must be running, or it does not move itself at all and"
                    + " reads still on anything: " + mob, !mob.bool("aiDisabled"));
            System.out.println("[deck-living] mobLanded yaw=" + mob.textOr("rotationYaw", "?"));
        }
        return id;
    }
}
