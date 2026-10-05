package dev.stannismod.stellurgy.test.client;


import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.PilotSeat;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A player can board an ALREADY ASSEMBLED physics ship by aiming at its pilot seat and pressing the
 * real use key - the same two actions a human performs, through the same two code paths.
 *
 * <p><b>Why this test exists.</b> The harness was believed unable to reach an assembled ship's
 * blocks at all, so every crew test boards through a server-side probe that spawns the seat's mount
 * and mounts the bot onto it. That belief rests on the harness's {@code interactBlock}, which calls
 * {@code PlayerControllerMP.processRightClickBlock} DIRECTLY with a caller-supplied world position -
 * and an assembled ship's blocks do not live at any world position, they live in the ship's own
 * subspace. But that is a property of THAT shortcut, not of the client: vanilla never reaches
 * {@code processRightClickBlock} with a hand-picked position either. It polls the use KEY BINDING
 * every tick ({@code Minecraft.processKeyBinds}), and {@code rightClickMouse()} then feeds it
 * whatever the crosshair raytrace ({@code mc.objectMouseOver}) resolved - which on a physics ship is
 * the ship's own block, found by the physics mod's own hook on the raytrace. So the position problem
 * never arises: nobody supplies a position, the game finds one.</p>
 *
 * <p><b>The claim under test</b> is therefore narrow and mechanical: aiming with {@code setLook} and
 * pressing the use key ({@code -99}, the code the mouse handler itself writes for RMB and the default
 * binding of {@code keyBindUseItem}) boards the pilot seat of an assembled ship. Nothing about the
 * assembly, the flight computer or the flight itself is under test here - the arrangement uses
 * probes freely, and only the aim-and-press hop is measured.</p>
 *
 * <p><b>Every hop is observed, so a red names one.</b> An aim that missed and a click the server
 * refused are otherwise indistinguishable, which is exactly how the original limit came to be
 * mis-attributed. So the test asserts (1) the crosshair is on a block, (2) that block is the PILOT
 * SEAT in the client's own world, (3) at the seat's SUBSPACE position - the physics mod's raytrace
 * returning a subspace position is the whole reason a world-position shortcut could never work - and
 * only then presses, and finally asserts (4) the server's own ordered log carries the click and then
 * the mount ({@code right_click_block} &rarr; {@code mount}, which tells a press the server never
 * saw from one the seat refused), and (5) the CLIENT reports itself riding the seat's mount, with
 * the server's own view cross-checked.</p>
 *
 * <p><b>The recipe, for any test that wants to board an assembled ship for real.</b> Empty the hand
 * and confirm it from the client; read the seat's live WORLD position and its SUBSPACE address from
 * the same probe reading; stand within a couple of blocks of the seat (see {@link #VARIANT} - aiming
 * from far away loses the hit to a distance comparison, not to any missing capability); aim with
 * {@code setLook} computed from the CLIENT's own eye position; confirm the crosshair with
 * {@code reportMouseOver} before pressing; then {@code setKey(-99, true)} / wait / release.</p>
 *
 * <p>On the shared VS client base. It ran its own server + client pair per method until 2026-08-23,
 * "matching the other ship-boarding e2e tests" — a reason to resemble its neighbours, never a reason
 * to boot: its root was a fresh empty temp dir handed to a harness that makes one of those itself,
 * and nothing was written into it before the server started. Off the shared base an arrangement
 * failure here could not be TYPED as one either, and that is the half that mattered.</p>
 *
 * <h2>The two clicks that EDIT an assembled ship</h2>
 *
 * <p>Activation is only one of the three things a player does with a block, and it is the one that
 * needs the least from the click: the position alone decides everything. Breaking additionally needs
 * the server's digging path to accept a position that exists in no world chunk the player stands in,
 * and placing additionally needs the hit vector — which vanilla computes as {@code hitVec - blockPos},
 * a subtraction of a WORLD-frame point from a SUBSPACE-frame position once a ship is involved. A
 * player who reports "blocks on my ship do not react" is reporting about all three, so all three live
 * here.</p>
 *
 * <p>The aim is proven, never assumed: both edit legs point the crosshair down at the deck the bot is
 * standing on and read {@code reportMouseOver} to learn WHICH block was resolved — the ship's own
 * subspace address — before any key is pressed. And the verdict is the SERVER's: a creative break
 * clears the block client-side immediately and independently of whether the server agreed, so each
 * leg waits for the server's own record that the break or the placement STOOD
 * ({@code block_broken} / {@code block_placed}, at the subspace position aimed at).</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSAssembledShipRealRightClickBoardingTest extends AbstractSharedVsClientTest {

    @Override
    protected String subsystem() {
        return "vs-assembled-ship-right-click-boarding";
    }


    /** This scenario's ship, by identity — captured at its build site before anything moves. */
    private String shipUuid;

    /**
     * The decked variant, because the pilot must stand NEXT TO the seat rather than squint up at it
     * from the pad. That is not cosmetic: the physics mod only prefers a ship hit over the world
     * result when it is STRICTLY nearer, and a world raytrace that hits nothing still reports the
     * last block boundary it crossed - which, for a ray aimed at a target near the end of its reach,
     * sits exactly ON the ship surface. Aimed from the pad the two distances tie and the ship hit is
     * discarded; aimed from the deck two blocks away the ship surface is struck early and wins by
     * blocks. The seat block itself is identical in both variants. The edit legs want the deck for
     * the plainer reason: the bot has to STAND on the ship for their clicks to be the player's.
     */
    private static final String VARIANT = "with-pilot-deck";

    /**
     * The use key's code. Mouse buttons enter {@code KeyBinding} as {@code -100 + button}, so RMB is
     * {@code -99} - the code the real mouse handler writes and the default binding of
     * {@code keyBindUseItem}. Injecting it therefore drives the identical poll the human's click does.
     */
    private static final int KEY_USE_ITEM = -99;

    /** The attack key's code, by the same rule: LMB is {@code -100}. */
    private static final int KEY_ATTACK = -100;


    /**
     * Where the bot stands to board, as an offset from the seat's LIVE world position: on the deck
     * (which is the block directly beneath the seat, so its top surface is the seat block's floor),
     * a block and a half along +X. Close enough that the seat is struck early in the ray, far enough
     * that the bot is not inside the seat block.
     */
    private static final double STAND_OFF_X = 1.5;

    /** Vanilla eye height for a standing player - the raytrace starts here, not at the feet. */
    private static final double EYE_HEIGHT = 1.62;

    /** The server drops a block interaction beyond (reach + 3), so the bot must observably be closer. */
    private static final double MAX_INTERACT_DIST_SQ = 64.0;

    /**
     * A real use-key press at an assembled ship's seat boards the pilot.
     *
     * <p>red-witnessed: with the vendored {@code MixinWorld#preRayTraceBlocks} at
     * {@code callbackInfo.setReturnValue(rayTraceBlocksIgnoreShip(vec31, vec32, stopOnLiquid,} (Valkyrien
     * Skies) no longer intercepting the ray, so it passes through the ship: "HOP 1 (aim):
     * the client's crosshair must resolve to a BLOCK", 2026-09-28. The two waits before it are
     * arrangement links (the ship usable, the client standing at the seat).</p>
     */
    @Test
    public void aRealUseKeyPressOnAnAssembledShipsSeatBoardsThePilot() throws Exception {

        // THE MULTIPLIER STAYS. What it waits on is VS building the ship on its OWN thread, off the
        // game loop: that work finishes in wall-clock time, so a busy box genuinely needs more game
        // ticks to elapse before it is done. Measured at 8 forks on the sibling gate test.
        int budget = 40;

        // ---- ARRANGEMENT: build, assemble, and get the ship LOADED with the client present. ------
        // WHERE THIS SCENARIO STANDS IS ASKED FOR, NOT CHOSEN: the plot is this scenario's own, and
        // the height is the open-air band because the site has no Y to pass.
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;
        // Where the fixture puts the seat before assembly; assembly carries it into the ship's
        // subspace. Derived from the allocated base, never written as an address.
        final int buildSeatX = bx + 3, buildSeatY = by + 5, buildSeatZ = bz + 3;
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The mark is taken BEFORE the assembly is queued, so the registry record that follows is
        // THIS scenario's ship by construction and never a neighbour's on a shared world. It also
        // splits the wait below in two: the ship COMING INTO EXISTENCE is an event the registry
        // itself records, and only what is left — the client-present LOAD — is a state worth polling
        // for. A red now says which of the two never happened.
        Events events = serverEvents();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(site, VARIANT);
        scenario().requireArranged("a " + VARIANT + " build must route to a ship: " + assemble,
                Reply.of(assemble).ok());
        shipUuid = awaitShipSpawned(events, spawnMark, "the assembly must create a VS ship in the"
                + " queryable registry before anything can be aimed at it (the spawn is asynchronous)");

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 8) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what loads the ship here, so the settle below is measuring"
                        + " a craft only an arrived client can have brought into being");
        // The LOAD is production's own record (`ship_usable`, later than every unload of the ship),
        // from the pre-assembly mark; then ONE read confirming it is loaded now.
        awaitShipUsable(events, spawnMark, shipUuid, budget * 5);
        String atBase = shipInfoAtBase();
        scenario().requireArranged("the ship must LOAD with the client present: " + atBase,
                ShipInfo.isLoaded(atBase));

        // The seat's SUBSPACE address (stationary, what the raytrace should report) and its live
        // WORLD position (what the bot has to aim at). Both come from the same probe reading.
        PilotSeat seatReply = findSeat()
                .requireFound("find-seat must resolve the assembled ship's subspace seat");
        int seatSubX = seatReply.seatX;
        int seatSubY = seatReply.seatY;
        int seatSubZ = seatReply.seatZ;

        // ---- ARRANGEMENT: an EMPTY hand, or a held stack consumes the press before the block. ----
        // A freshly joined player does not start empty-handed (mods hand out items on first join),
        // so the hand is cleared and then VERIFIED from the client, never assumed.
        emptyTheHandOnClient("the bot's main hand must be EMPTY so the use press reaches the seat"
                + " block rather than being consumed by a held item");

        // ---- HOP 1-3: put the crosshair on the seat, and PROVE it landed there. ------------------
        // Re-derived every attempt from the seat's LIVE world position: a freshly assembled ship
        // settles for a while, so an aim computed once against a stale pose misses by design.
        //
        // CLASSIFIED, and it stays a loop: each pass PERFORMS the stand and the aim against a pose
        // that has moved since the last one, then reads back what the crosshair hit. Delete it and
        // the standing and aiming stop HAPPENING; and no link could replace it, because nothing in
        // production decides that a crosshair is on a block. The order inside the iteration is
        // load-bearing — teleport first, aim last — since a teleport arrives as a pos-look packet
        // that vanilla applies with setPositionAndRotation, overwriting any aim set before it.
        JsonObject aim = null;
        PilotSeat pose = null;
        double[] seatWorld = null;
        double distSq = Double.POSITIVE_INFINITY;
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        long lastStandMark = -1L;
        // STIMULUS: each pass stands and aims against the ship's live pose, and ends on the crosshair
        // resting on the seat — the argument is above.
        for (int attempt = 0; attempt < budget; attempt++) {
            pose = findSeat();
            // A READ, not a wait. This branch used to sleep five ticks and retry, as if the ship
            // might not have a world pose yet — but the seat was resolved above this loop, off the
            // same ship, before any aiming began. A seat that resolves and then has no pose is a
            // ship whose transform went away mid-arrangement, and that is news, not a pause.
            scenario().requireArranged("the seat resolved before aiming began, so the ship must still"
                    + " report its world pose on attempt " + attempt + ": " + pose.raw(),
                    !Double.isNaN(pose.shipWorldX));
            seatWorld = new double[]{pose.shipWorldX, pose.shipWorldY, pose.shipWorldZ};

            // Put the bot on the deck beside the seat, re-derived from the seat's LIVE position: a
            // freshly assembled ship settles for a while, and a stand computed once against a stale
            // pose leaves the bot in mid-air beside a ship that has since moved.
            // The stand REACHING the client is a link: the reading below is of where he was put, and
            // twenty ticks stood here as a guess at the trip.
            long standMark = clientEvents().mark();
            lastStandMark = standMark;
            double standX = seatWorld[0] + STAND_OFF_X;
            double standZ = seatWorld[2];
            exec("tp @a " + standX + " " + (seatWorld[1] + 1.0) + " " + standZ + " 0 0");
            awaitClientPlacedNear(standMark, standX, standZ,
                    "the stand beside the seat must reach the client before he aims from it");
            JsonObject state = bot().reportState();
            // A READ, not a wait: the teleport stays in this world, so the client cannot have lost
            // it, and a client that reports no world here is a finding rather than a reason to retry.
            scenario().requireArranged("a same-world teleport must leave the client's world ready: "
                    + state, isWorldReady(state));
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();

            double dx = seatWorld[0] - px;
            double dy = seatWorld[1] - (py + EYE_HEIGHT);
            double dz = seatWorld[2] - pz;
            distSq = dx * dx + dy * dy + dz * dz;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
            bot().setLook(yaw, pitch);
            // STIMULUS: the controller's step. MEASURED that one tick is not enough (2026-09-23:
            // deterministic red, with the stand's own pos-look the only move applied, and still red with
            // twenty ticks after the stand); the mechanism is NOT established.
            bot().waitWorldTicks(5);

            aim = bot().reportMouseOver();
            if (isSeatUnderCrosshair(aim, seatSubX, seatSubY, seatSubZ)) {
                break;
            }
        }

        // Every server-driven move the client applied since the LAST stand, in order: a correction
        // landing after the aim carries the server's copy of the rotation and overwrites the look,
        // and this is the only reading that shows one did.
        String movesSinceStand = lastStandMark < 0 ? "(no stand)"
                : clientEvents().since(lastStandMark, "client_pos_look_applied");
        String aimDiag = " movesSinceLastStand=" + movesSinceStand
                + " observedPlayer=(" + px + "," + py + "," + pz + ")"
                + " seatWorld=" + java.util.Arrays.toString(seatWorld)
                + " seatSubspace=(" + seatSubX + "," + seatSubY + "," + seatSubZ + ")"
                + " buildSeat=(" + buildSeatX + "," + buildSeatY + "," + buildSeatZ + ")"
                + " distSq=" + distSq + " mouseOver=" + aim + " findSeat=" + pose;

        scenario().requireArranged("the bot must OBSERVABLY stand within the server's interaction reach "
                + "of the seat, or the press is discarded before the seat block ever sees it."
                + aimDiag, distSq < MAX_INTERACT_DIST_SQ);

        assertTrue("HOP 1 (aim): the client's crosshair must resolve to a BLOCK. A MISS here means "
                + "the aim maths or the sightline is wrong, not that the ship is unclickable."
                + aimDiag,
                aim != null && aim.has("typeOfHit") && "BLOCK".equals(aim.get("typeOfHit").getAsString()));

        assertTrue("HOP 2 (aim): the block under the crosshair must be the PILOT SEAT as the CLIENT's "
                + "own world reports it - the ship's blocks are reachable by the raytrace." + aimDiag,
                aim.has("block") && aim.get("block").getAsString().toLowerCase(java.util.Locale.ROOT)
                        .contains("pilotseat"));

        assertTrue("HOP 3 (aim): the raytrace must report the seat's SUBSPACE position. This is the "
                + "hop that makes a world-position shortcut impossible and a real key press possible: "
                + "the physics mod resolves the pick against the ship, so the position handed to the "
                + "interaction is the ship's own block address, not a rendered world coordinate."
                + aimDiag,
                aim.has("blockX")
                        && aim.get("blockX").getAsInt() == seatSubX
                        && aim.get("blockY").getAsInt() == seatSubY
                        && aim.get("blockZ").getAsInt() == seatSubZ);

        // ---- HOP 4: press the real use key, exactly as the human's right mouse button does. ------
        // The mark goes BEFORE the press. A use press is over inside a tick, so a poll arriving
        // afterwards cannot tell "the click never reached the server" from "it did and something
        // undid it"; taken first, nothing between the two can be missed. markInstrumented, because
        // the mount half is recorded by a test-only mixin, and an un-woven one answers with exactly
        // the empty log a refused click does.
        long pressMark = events.markInstrumented();
        // The CLIENT's own mark beside it. His client PERFORMS the mount when the server tells it
        // who is riding what, so the replication half below is a record on this log — and with the
        // mark taken here it cannot be missed however the two sides interleave.
        long pressOnClient = clientEvents().mark();
        bot().setKey(KEY_USE_ITEM, true);
        // STIMULUS: the use key held down across client ticks, as a mouse button is.
        bot().waitWorldTicks(5);
        bot().setKey(KEY_USE_ITEM, false);

        // The two links a boarding IS, in the order the game commits them: Forge fires
        // RightClickBlock inside processRightClickBlock BEFORE the block's own activation runs, and
        // the seat's activation is what mounts the player. Their SEPARATION is the diagnosis this
        // class exists to make and the one a riding poll can never report - NO right_click_block at
        // all means the server dropped the press before the seat ever saw it (the reach check, an
        // unconfirmed teleport, a held stack), while a right_click_block with no mount means the
        // seat itself refused the boarding. The physics mod routes a ship click through the ordinary
        // packet handler with the player transformed for the call, so the subspace address changes
        // nothing about which links fire.
        events.assertChain(pressMark, "a real use-key press aimed at an ASSEMBLED ship's pilot seat "
                        + "must reach the server and board the player - the crosshair was proven to "
                        + "be on that very seat block, so a break here is the interaction itself, "
                        + "not a missed aim." + aimDiag,
                5 * budget, "right_click_block", "mount");

        // The replication, as the LINK it is: his client performing the mount. A poll of
        // `reportRidingEntity` stood here, and it could only ever sample the state the record
        // announces — a read that lands in the gap between the tear-down and the rebuild answers
        // `riding:false` for a pilot who is about to be seated.
        JsonObject riding = awaitClientMount(pressOnClient,
                "the CLIENT must mount the player after a boarding the SERVER has already recorded"
                        + " (the chain above), or the pilot sees himself standing on a deck he is in"
                        + " fact strapped into", 5 * budget,
                " serverMountRecord=" + events.since(pressMark, "mount") + aimDiag);
        String serverRiding = exec("stellurgytest player riding-entity");
        String boardDiag = " clientRiding=" + riding + " serverRiding=" + serverRiding
                + " mouseOverAfter=" + bot().reportMouseOver() + aimDiag;

        // ...and he is still on it, read ONCE now that the link above has established the mount.
        assertTrue("the pilot must still be aboard when the seat is read — his client mounted him"
                + " (the link above) and must not have taken him off again." + boardDiag,
                isRiding(riding));

        assertTrue("the client must be riding the SEAT's mount, not some other entity it happened "
                + "to board." + boardDiag,
                riding.has("entityClass")
                        && riding.get("entityClass").getAsString().endsWith("EntityDummy"));

        // The server's own view, so a client-only ghost mount cannot pass for a boarding.
        // The class, read off the field that carries it and compared as a name — the same shape
        // the client's own reading uses two lines above. As a substring it was satisfied by the
        // name appearing anywhere in the reply, and by a class merely ENDING in it.
        // the producer always writes `ridingEntityClass`; it is empty for a player riding
        // nothing, which is a reading and not an absence.
        assertEquals("the SERVER must agree the player is riding the seat's mount - a client-side-only "
                + "mount would render a pilot who is not aboard anything." + boardDiag,
                "EntityDummy", Reply.of("stellurgytest player riding-entity", serverRiding)
                        .simpleClassName("ridingEntityClass"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * THIS scenario's ship, by name — used by the arrangement's load poll, which is a question about
     * time and not about which craft is being waited for.
     */
    private String shipInfoAtBase() throws Exception {
        return exec("stellurgytest vs ship-info 0 id " + shipUuid);
    }

    /** The seat's subspace address + live world position, resolved from the craft's IDENTITY. */
    private PilotSeat findSeat() throws Exception {
        return PilotSeat.byId(this::exec, 0, shipUuid);
    }

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private static boolean isSeatUnderCrosshair(JsonObject aim, int seatX, int seatY, int seatZ) {
        return aim != null
                && aim.has("typeOfHit") && "BLOCK".equals(aim.get("typeOfHit").getAsString())
                && aim.has("blockX")
                && aim.get("blockX").getAsInt() == seatX
                && aim.get("blockY").getAsInt() == seatY
                && aim.get("blockZ").getAsInt() == seatZ;
    }

    private String assembleFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed` — the number the
        // pre-clear it replaces was throwing away. Open air, so this ASSERTS rather than digs, and
        // it matters for a RIGHT-CLICK subject in particular: a player on a pit's rim aims over the
        // block he means, and the miss reads as the interaction path refusing him.
        return RocketFixture.assembleAt(site, this::exec, variant, 2, 16,
                "the hull, and the air the player stands and right-clicks in beside its seat");
    }

    // ---- the edit legs -------------------------------------------------------------------------

    /**
     * Where the bot stands to EDIT, as an offset from the seat's LIVE world position. A WHOLE number
     * of blocks from the block-centred seat, so the bot stands on a block CENTRE: the crosshair runs
     * straight along +Z, its X is the bot's X, and at 1.5 that X was a block BOUNDARY — measured
     * 2026-09-29, the pick flipped between two deck blocks from one read to the next.
     */
    private static final double EDIT_STAND_OFF_X = 2.0;

    /**
     * Pitches tried, in order, when looking for a deck block to work on. Straight down (90) resolves
     * the block the bot is standing ON, which cannot take a placement — its up face is where the bot
     * is. The shallower entries reach a block in FRONT of the bot, whose up face is free.
     *
     * <p>A constant: written only by its initialiser, read only by iteration in this class, and
     * never handed out — an array is mutable, so that is what holds it.</p>
     */
    private static final float[] AIM_PITCHES = {55.0F, 65.0F, 75.0F, 45.0F, 85.0F};

    /**
     * How many stand-and-aim passes the edit arrangement makes against a hull that is still settling.
     * Measured 2026-10-04: both edit legs found a deck block on the FIRST pass; the rest is room for a
     * hull still settling when the aim begins.
     */
    private static final int AIM_ATTEMPTS = 40;

    /**
     * A LINK budget for one discrete record — a spawn, a load, a slot write, a break or a placement
     * standing on the server — in ticks. Its expiry means the thing never happened. Measured
     * 2026-10-04, server ticks from each link's stimulus to its record: the ship usable 3 and 52 (two
     * runs), the given stone in the slot 7, the break 12, the placement 6.
     */
    private static final int LINK_BUDGET_TICKS = 200;

    /**
     * How far from the seat a deck block can be, in blocks on each axis: the decked fixture's deck is
     * a 5x5 of iron (25 blocks) the seat stands on, so wherever on it the seat is, no block of it is
     * more than four away.
     */
    private static final int DECK_REACH_FROM_SEAT = 4;

    /**
     * A real attack-key press on a block of an assembled ship removes that block from the ship.
     *
     * <p>Creative mode — the shared base's default — so one press is one break and the test measures
     * the interaction rather than a mining-speed budget. The block read back is the one the crosshair
     * itself named.</p>
     *
     * <p>red-witnessed: one break per verdict — with {@code MixinCPacketPlayerDigging#getPacketParent} at {@code if (physicsObject.isPresent())}
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

        Events events = serverEvents();
        long pressMark = events.markInstrumented();
        scenario().asserting("a real attack-key press on the aimed ship block, and the server's verdict on it");
        bot().setKey(KEY_ATTACK, true);
        // STIMULUS: the attack key held across client ticks, as a mouse button is.
        bot().waitWorldTicks(10);
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
     * the packet transform ({@code MixinCPacketPlayerTryUseItemOnBlock#getPacketParent} at {@code if (physicsObject.isPresent())}, {@code getPacketParent}
     * answering null) and the ship-aware distance ({@code MixinEntity#getDistanceSq} at {@code if (vanilla < 64.0D)}, whose {@code @Overwrite}
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
        bot().waitWorldTicks(5);
        JsonObject aim = bot().reportMouseOver();
        scenario().requireArranged("the crosshair must still be on the same ship block after the hand"
                + " was filled. aim=" + aim + deck.diag, isBlockAt(aim, deck.x, deck.y, deck.z));

        String target = blockAt(deck.x, deck.y + 1, deck.z);
        scenario().requireArranged("the space the placement would fill must be EMPTY beforehand, or a"
                + " green would mean nothing. target=" + target + deck.diag, Reply.of(target).bool("isAir"));

        Events events = serverEvents();
        long pressMark = events.markInstrumented();
        scenario().asserting("a real use-key press with stone in hand, and the server's verdict on it");
        bot().setKey(KEY_USE_ITEM, true);
        // STIMULUS: the use key held across client ticks, as a mouse button is.
        bot().waitWorldTicks(5);
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

        Events events = serverEvents();
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
        String atBase = shipInfoAtBase();
        scenario().requireArranged("the ship must LOAD with the client present: " + atBase,
                ShipInfo.isLoaded(atBase));

        PilotSeat seat = findSeat()
                .requireFound("find-seat must resolve the assembled ship's subspace seat");
        int[] seatSub = {seat.seatX, seat.seatY, seat.seatZ};

        JsonObject aim = null;
        double[] seatWorld = null;
        float usedPitch = 0.0F;
        // STIMULUS: each pass stands and aims against the ship's LIVE pose and ends on the crosshair
        // resting on a deck block. Delete the loop and the standing and aiming stop HAPPENING; no link
        // could replace it, because nothing in production decides that a crosshair is on a block. The
        // order inside is load-bearing — teleport first, aim last — since a teleport arrives as a
        // pos-look that vanilla applies with setPositionAndRotation, overwriting any aim set before it.
        for (int attempt = 0; attempt < AIM_ATTEMPTS && aim == null; attempt++) {
            PilotSeat pose = findSeat();
            scenario().requireArranged("the seat resolved before aiming began, so the ship must still"
                    + " report its world pose on attempt " + attempt + ": " + pose.raw(),
                    !Double.isNaN(pose.shipWorldX));
            seatWorld = new double[]{pose.shipWorldX, pose.shipWorldY, pose.shipWorldZ};

            long standMark = clientEvents().mark();
            double standX = seatWorld[0] + EDIT_STAND_OFF_X;
            exec("tp @a " + standX + " " + (seatWorld[1] + 1.0) + " " + seatWorld[2] + " 0 0");
            awaitClientPlacedNear(standMark, standX, seatWorld[2],
                    "the stand on the deck must reach the client before he aims from it");

            for (float pitch : AIM_PITCHES) {
                bot().setLook(0.0F, pitch);
                // STIMULUS: the raytrace refreshes once per client tick, so the new rotation needs a tick.
                bot().waitWorldTicks(5);
                JsonObject candidate = bot().reportMouseOver();
                if (isShipDeckHit(candidate, seatSub)) {
                    aim = candidate;
                    usedPitch = pitch;
                    break;
                }
            }
        }

        String diag = " seatWorld=" + java.util.Arrays.toString(seatWorld) + " seatSubY=" + seat.seatY
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

    private static boolean isBlockAt(JsonObject aim, int x, int y, int z) {
        return aim != null && aim.has("blockX")
                && aim.get("blockX").getAsInt() == x
                && aim.get("blockY").getAsInt() == y
                && aim.get("blockZ").getAsInt() == z;
    }
}
