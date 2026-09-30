package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipInfo;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.assertTrue;

/**
 * The two clicks that EDIT an assembled ship: breaking one of its blocks and placing a block on it,
 * performed with the real attack and use keys against whatever the crosshair actually resolved.
 *
 * <p><b>Why this test exists.</b> Its sibling
 * {@link VSAssembledShipRealRightClickBoardingE2ETest} proves that a real use-key press ACTIVATES a
 * block of an assembled ship (the pilot seat). Activation is only one of the three things a player
 * does with a block, and it is the one that needs the least from the click: the position alone
 * decides everything. Breaking additionally needs the server's digging path to accept a position
 * that exists in no world chunk the player stands in, and placing additionally needs the hit vector
 * — which vanilla computes as {@code hitVec - blockPos}, a subtraction of a WORLD-frame point from a
 * SUBSPACE-frame position once a ship is involved. Neither is covered by an activation test, and a
 * player who reports "blocks on my ship do not react" is reporting about all three.</p>
 *
 * <p><b>The aim is proven, never assumed.</b> Both legs point the crosshair down at the deck the bot
 * is standing on and read {@code reportMouseOver} to learn WHICH block was resolved — the ship's own
 * subspace address — before any key is pressed. So a red names its own hop: a MISS is the raytrace
 * failing to reach the ship, and a resolved block that then refuses to break or to accept a placement
 * is the interaction being refused.</p>
 *
 * <p><b>The verdict is the SERVER's, twice.</b> A creative break clears the block client-side
 * immediately and independently of whether the server agreed, so a client-side reading of the deck
 * would go green on a click the server discarded. Each leg waits for the server's own record that
 * the break or the placement STOOD ({@code block_broken} / {@code block_placed}, at the subspace
 * position aimed at), then reads the block back once.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSAssembledShipBlockEditE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-assembled-ship-block-edit";
    }

    /** The decked variant: the bot has to STAND on the ship for these clicks to be the player's. */
    private static final String VARIANT = "with-pilot-deck";

    /** Mouse buttons enter {@code KeyBinding} as {@code -100 + button}: LMB attack, RMB use. */
    private static final int KEY_ATTACK = -100;
    private static final int KEY_USE_ITEM = -99;

    /**
     * Where the bot stands to work, as an offset from the seat's LIVE world position. A WHOLE number
     * of blocks from the block-centred seat, so the bot stands on a block CENTRE: the crosshair runs
     * straight along +Z, its X is the bot's X, and at 1.5 that X was a block BOUNDARY — measured
     * 2026-09-29, the pick flipped between two deck blocks from one read to the next.
     */
    private static final double STAND_OFF_X = 2.0;

    /**
     * Pitches tried, in order, when looking for a deck block to work on. Straight down (90) resolves
     * the block the bot is standing ON, which cannot take a placement — its up face is where the bot
     * is. The shallower entries reach a block in FRONT of the bot, whose up face is free.
     */
    private static final float[] AIM_PITCHES = {55.0F, 65.0F, 75.0F, 45.0F, 85.0F};

    /** How many stand-and-aim passes the arrangement makes against a hull that is still settling. */
    private static final int AIM_ATTEMPTS = 40;

    /**
     * A LINK budget for one discrete record — a spawn, a load, a slot write, a break or a placement
     * standing on the server — in ticks. Its expiry means the thing never happened.
     */
    private static final int LINK_BUDGET_TICKS = 200;

    /** This scenario's ship, by identity. */
    private String shipUuid;

    /**
     * A real attack-key press on a block of an assembled ship removes that block from the ship.
     *
     * <p>Creative mode — the shared base's default — so one press is one break and the test measures
     * the interaction rather than a mining-speed budget. The block read back is the one the crosshair
     * itself named.</p>
     *
     * <p>red-witnessed, one break per verdict: with {@code MixinCPacketPlayerDigging:37}
     * ({@code getPacketParent}, Valkyrien Skies, vendored) answering null, so the digging packet is
     * served with the player left in the world frame, the wait fails — no {@code block_broken} at the
     * aimed subspace position — 2026-09-29. With {@code MixinChunk:62} refusing to write AIR into a
     * ship's chunk, the break event still stands and the read-back fails — "the block must be gone
     * now" ({@code minecraft:iron_block}) — 2026-09-30.</p>
     */
    @Test
    public void aRealAttackKeyPressBreaksABlockOfAnAssembledShip() throws Exception {

        Deck deck = standOnTheDeckAndAimAtIt();

        String before = blockAt(deck.x, deck.y, deck.z);
        scenario().requireArranged("the crosshair's block must be a REAL block on the server before"
                + " the break, or the leg measures nothing: " + before + deck.diag,
                !Reply.of(before).bool("isAir"));

        Events events = events();
        long pressMark = events.markInstrumented();
        scenario().asserting("a real attack-key press on the aimed ship block, and the server's verdict on it");
        bot().setKey(KEY_ATTACK, true);
        // STIMULUS: the attack key held across client ticks, as a mouse button is.
        bot().waitTicks(10);
        bot().setKey(KEY_ATTACK, false);

        events.awaitRecordWithFields(pressMark, "block_broken",
                "a real attack-key press aimed at an ASSEMBLED ship's block must BREAK it on the server."
                        + " The crosshair was proven to be on that very block and the server confirmed"
                        + " it was solid, so a failure here is the digging path refusing a subspace"
                        + " position - not a missed aim." + deck.diag,
                LINK_BUDGET_TICKS, "x", String.valueOf(deck.x), "y", String.valueOf(deck.y),
                "z", String.valueOf(deck.z));
        String after = blockAt(deck.x, deck.y, deck.z);
        assertTrue("the server recorded the break standing, so the block must be gone now: " + after
                + deck.diag, Reply.of(after).bool("isAir"));
    }

    /**
     * A real use-key press with a block in hand, aimed at an assembled ship's deck, places that block
     * onto the ship — at the subspace position the crosshair's own side-hit names.
     *
     * <p>red-witnessed: the placement passes the server's reach check by TWO routes, and only with
     * both removed does the wait fail — no {@code block_placed} at the aimed position — 2026-09-30:
     * the packet transform ({@code MixinCPacketPlayerTryUseItemOnBlock:38}, {@code getPacketParent}
     * answering null) and the ship-aware distance ({@code MixinEntity:133}, whose {@code @Overwrite}
     * of {@code getDistanceSq} maps a subspace position to the world). The transform alone was broken
     * on 2026-09-29 and the leg stayed GREEN, which is how the second route was found.</p>
     */
    @Test
    public void aRealUseKeyPressPlacesABlockOnAnAssembledShip() throws Exception {

        Deck deck = standOnTheDeckAndAimAtIt();

        scenario().requireArranged("the crosshair must report the face it struck, or there is no"
                + " position for a placement to land on." + deck.diag, "up".equalsIgnoreCase(deck.sideHit));

        // The hand: emptied first (a link on the client's own slot write, in the base), then given the
        // stone — and the GIVE is a link too, on the slot write that carries it.
        emptyTheHandOnClient("the bot's hand must be empty before it is given the stone to place");
        long giveMark = clientEvents().mark();
        exec("give @a minecraft:stone 8");
        ArrangementFailure.arranged(() -> clientEvents().awaitField(giveMark, "client_slot_set", "item",
                "minecraft:stone", "the given stone must reach the client's inventory before it can be"
                        + " placed", LINK_BUDGET_TICKS));
        JsonObject items = bot().reportPlayerItems();
        String heldId = items.has("held") && items.getAsJsonObject("held").has("id")
                ? items.getAsJsonObject("held").get("id").getAsString() : "?";
        scenario().requireArranged("the bot must be HOLDING the stone it is about to place: " + items
                + deck.diag, heldId.contains("stone"));

        // Re-aim: filling the hand does not move the crosshair, but a settling ship does.
        bot().setLook(deck.yaw, deck.pitch);
        // STIMULUS: the raytrace refreshes once per client tick, so the new rotation needs a tick.
        bot().waitTicks(5);
        JsonObject aim = bot().reportMouseOver();
        scenario().requireArranged("the crosshair must still be on the same ship block after the hand"
                + " was filled. aim=" + aim + deck.diag, isBlockAt(aim, deck.x, deck.y, deck.z));

        String target = blockAt(deck.x, deck.y + 1, deck.z);
        scenario().requireArranged("the space the placement would fill must be EMPTY beforehand, or a"
                + " green would mean nothing. target=" + target + deck.diag, Reply.of(target).bool("isAir"));

        Events events = events();
        long pressMark = events.markInstrumented();
        scenario().asserting("a real use-key press with stone in hand, and the server's verdict on it");
        bot().setKey(KEY_USE_ITEM, true);
        // STIMULUS: the use key held across client ticks, as a mouse button is.
        bot().waitTicks(5);
        bot().setKey(KEY_USE_ITEM, false);

        events.awaitRecordWithFields(pressMark, "block_placed",
                "a real use-key press with a block in hand, aimed at an ASSEMBLED ship's deck, must"
                        + " place that block ON the ship - one block above the face the crosshair"
                        + " struck, in the ship's own subspace. A failure here is the placement path"
                        + " losing the position, not a missed aim." + deck.diag,
                LINK_BUDGET_TICKS, "x", String.valueOf(deck.x), "y", String.valueOf(deck.y + 1),
                "z", String.valueOf(deck.z));
        // No read-back of the block here, unlike the break leg. A placement's event fires AFTER the
        // block is in the world, and an uncancelled one at LOWEST is the placement standing — so a
        // read after it could fail only if something removed the block later, which is not this
        // contract. The break's event fires BEFORE the removal, which is why that leg reads back.
    }

    // ---- arrangement ---------------------------------------------------------------------------

    /** What the crosshair resolved on the ship, plus the look that put it there. */
    private static final class Deck {
        final int x, y, z;
        final String sideHit;
        final float yaw, pitch;
        final String diag;

        Deck(int x, int y, int z, String sideHit, float yaw, float pitch, String diag) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.sideHit = sideHit;
            this.yaw = yaw;
            this.pitch = pitch;
            this.diag = diag;
        }
    }

    /**
     * Builds and assembles the fixture, puts the bot on the deck beside the seat, and aims it at the
     * deck until {@code reportMouseOver} names a ship block. Returns that block's SUBSPACE address —
     * the raytrace's own answer, never a computed one.
     */
    private Deck standOnTheDeckAndAimAtIt() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The assembly runs with no observer near it, and the observer is a client.
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it");

        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = RocketFixture.assembleAt(site, this::exec, VARIANT, 2, 16,
                "the hull, and the air the player stands and clicks in on its deck");
        scenario().requireArranged("a " + VARIANT + " build must route to a ship: " + assemble,
                Reply.of(assemble).ok());
        shipUuid = ArrangementFailure.arranged(() -> awaitShipSpawned(events, spawnMark, "the assembly"
                + " must create a VS ship before anything can be aimed at it (the spawn is asynchronous)"));

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 8) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's arrival is what loads the ship here");
        ArrangementFailure.arranged(() -> awaitShipUsable(events, spawnMark, shipUuid, LINK_BUDGET_TICKS));
        String atBase = exec("stellurgytest vs ship-info 0 id " + shipUuid);
        scenario().requireArranged("the ship must LOAD with the client present: " + atBase,
                ShipInfo.isLoaded(atBase));

        PilotSeat seat = PilotSeat.byId(this::exec, 0, shipUuid)
                .requireFound("find-seat must resolve the assembled ship's subspace seat");
        int[] seatSub = {seat.seatX, seat.seatY, seat.seatZ};
        int seatSubY = seat.seatY;

        JsonObject aim = null;
        double[] seatWorld = null;
        float usedPitch = 0.0F;
        // STIMULUS: each pass stands and aims against the ship's LIVE pose and ends on the crosshair
        // resting on a deck block. Delete the loop and the standing and aiming stop HAPPENING; no link
        // could replace it, because nothing in production decides that a crosshair is on a block. The
        // order inside is load-bearing — teleport first, aim last — since a teleport arrives as a
        // pos-look that vanilla applies with setPositionAndRotation, overwriting any aim set before it.
        for (int attempt = 0; attempt < AIM_ATTEMPTS && aim == null; attempt++) {
            PilotSeat pose = PilotSeat.byId(this::exec, 0, shipUuid);
            scenario().requireArranged("the seat resolved before aiming began, so the ship must still"
                    + " report its world pose on attempt " + attempt + ": " + pose.raw(),
                    !Double.isNaN(pose.shipWorldX));
            seatWorld = new double[]{pose.shipWorldX, pose.shipWorldY, pose.shipWorldZ};

            long standMark = clientEvents().mark();
            double standX = seatWorld[0] + STAND_OFF_X;
            exec("tp @a " + standX + " " + (seatWorld[1] + 1.0) + " " + seatWorld[2] + " 0 0");
            awaitClientPlacedNear(standMark, standX, seatWorld[2],
                    "the stand on the deck must reach the client before he aims from it");

            for (float pitch : AIM_PITCHES) {
                bot().setLook(0.0F, pitch);
                // STIMULUS: the raytrace refreshes once per client tick, so the new rotation needs a tick.
                bot().waitTicks(5);
                JsonObject candidate = bot().reportMouseOver();
                if (isShipDeckHit(candidate, seatSub)) {
                    aim = candidate;
                    usedPitch = pitch;
                    break;
                }
            }
        }

        String diag = " seatWorld=" + java.util.Arrays.toString(seatWorld) + " seatSubY=" + seatSubY
                + " lastMouseOver=" + bot().reportMouseOver();
        scenario().requireArranged("the crosshair must resolve a BLOCK of the assembled ship when aimed"
                + " at the deck the bot is standing on. A MISS here means the raytrace never reaches the"
                + " ship, which is a finding in its own right - and it makes every click below"
                + " unmeasurable." + diag, aim != null);

        return new Deck(aim.get("blockX").getAsInt(), aim.get("blockY").getAsInt(),
                aim.get("blockZ").getAsInt(),
                aim.has("sideHit") ? aim.get("sideHit").getAsString() : "",
                0.0F, usedPitch, diag + " aim=" + aim);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The SERVER's reading of a block at a SUBSPACE position. */
    private String blockAt(int x, int y, int z) throws Exception {
        return exec("stellurgytest block at 0 " + x + " " + y + " " + z);
    }

    /**
     * A crosshair reading that is an up face of the SHIP's deck rather than of the world: the physics
     * mod reports a ship hit at the ship's own subspace address, so the hit must lie within
     * {@link #DECK_REACH_FROM_SEAT} of the seat on ALL THREE subspace axes. A world block cannot: the
     * subspace sits millions of blocks out along X (measured 2026-09-29: a deck hit at X 19200003
     * against the bot's world X 2125).
     */
    private static boolean isShipDeckHit(JsonObject aim, int[] seatSub) {
        if (aim == null || !aim.has("typeOfHit") || !"BLOCK".equals(aim.get("typeOfHit").getAsString())) {
            return false;
        }
        if (!aim.has("blockX") || !aim.has("sideHit")) {
            return false;
        }
        return Math.abs(aim.get("blockX").getAsInt() - seatSub[0]) <= DECK_REACH_FROM_SEAT
                && Math.abs(aim.get("blockY").getAsInt() - seatSub[1]) <= DECK_REACH_FROM_SEAT
                && Math.abs(aim.get("blockZ").getAsInt() - seatSub[2]) <= DECK_REACH_FROM_SEAT
                && "up".equalsIgnoreCase(aim.get("sideHit").getAsString());
    }

    /**
     * How far from the seat a deck block can be, in blocks on each axis: the decked fixture's deck is
     * a 5x5 of iron (25 blocks) the seat stands on, so wherever on it the seat is, no block of it is
     * more than four away.
     */
    private static final int DECK_REACH_FROM_SEAT = 4;

    private static boolean isBlockAt(JsonObject aim, int x, int y, int z) {
        return aim != null && aim.has("blockX")
                && aim.get("blockX").getAsInt() == x
                && aim.get("blockY").getAsInt() == y
                && aim.get("blockZ").getAsInt() == z;
    }
}
