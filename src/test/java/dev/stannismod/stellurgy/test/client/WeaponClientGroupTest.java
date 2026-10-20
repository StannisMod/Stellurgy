package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import dev.stannismod.stellurgy.weapon.TurretMechanism;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Weapons as a real client sees and drives them: a beam and a round reaching the player who stands
 * near them (and not the one four kilometres off), a beam's corner, a turret's bearing, friend and foe
 * at the gun and at the sensor, the buttons and the readout of the weapon screens, the linker that names
 * a battery's target, and the repairs a player makes by hand.
 *
 * <p>NEW-GROUP: weapons as a real client sees and drives them. No existing client group holds the weapon
 * cluster: {@code MachineGuiClientGroupTest} holds the reach rule every machine screen shares, not what a
 * weapon does, and every other weapon test is on the server, where no player exists to carry a code, stand
 * near a beam or press a button. These are mechanics tests, not e2e: each arranges the weapon's state by
 * probe, and several act through verbs that name their target ({@code rightClickBlock},
 * {@code clickButtonById}).</p>
 *
 * <h2>What sharing one world changes, and what each scenario does about it</h2>
 *
 * <p>Each scenario stands on its own plot ({@link #plot()}, {@link #site()}), in the open-air band, and
 * builds its own floor where its player must stand. Nothing it builds is in a sibling's plot, with one
 * named exception: the two far controls ({@link #FAR_OFFSET}) stand four kilometres east of their own
 * plot, which is east of every plot of this class's lane.</p>
 *
 * <p>Three things a scenario does here would reach the scenarios after it, and each is undone when it
 * ends ({@link #undoWhatThisScenarioLeftInTheWorld}): a battery left charged and aimed fires on the
 * shared player wherever he is next (so every fixture is filled with air), a chunk held loaded stays held
 * (so it is released), and {@code doMobSpawning} and the game mode are world state (so they are put
 * back). An undo that fails fails the scenario that owed it.</p>
 *
 * <p><b>What it does not see.</b> A round that misses keeps flying for up to
 * {@link Weapons#ROUND_LIFETIME_TICKS} after its gun is gone; the linker's and the console's guns fire
 * east, along the lane, toward plots no scenario has built on yet. They are aimed level at a point and
 * nothing leads them for the fall, so a miss drops below the band before it can reach the next plot; that
 * is reasoned, not measured.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WeaponClientGroupTest extends AbstractSharedClientTest {

    @Override
    protected String subsystem() {
        return "weapons";
    }

    /** The harness's single client always joins under this name. */
    private static final String PLAYER = "ForgeTestClient";

    /**
     * How far east of its own gun a far control's gun or round stands: four kilometres, comfortably
     * outside the default 256-block visibility radius, and further east than the whole lane this class's
     * plots are allocated on, so no sibling's plot lies under it.
     */
    private static final int FAR_OFFSET = 4_000;

    // ---- a beam reaches the player near it, and not the one far away

    /**
     * Whether a burning beam is visible to the player standing next to the gun.
     *
     * <p>A held beam is not an entity, not a block and not a particle: it is a line the server resolves
     * every tick and forgets. Vanilla replicates none of that, so "you can see the gun burning" is entirely
     * a claim about a packet channel, and the only place it can be checked is a real client. What is
     * asserted is what the renderer draws FROM — the client's own tracker of burning beams, as its
     * {@code client_beam_drawn} record; whether it LOOKS like a laser stays a human's judgement.</p>
     *
     * <p>The far half is what makes it evidence: a beam burning four kilometres away must NOT arrive, or
     * the filter that keeps every battery in the world off every connection is doing nothing. "Nothing
     * arrived" is a reading only once two things hold. The far gun really burned — its own
     * {@code turret_beam} edge says it lit. And that announcement had time to arrive: the far gun lit
     * BEFORE the near one (the server log orders the two edges), and every packet the server sends this
     * player travels one ordered connection, so a far packet would have been drawn before the near gun's —
     * which the client DID draw. The near half is therefore the far half's instrument check as well as its
     * own claim.</p>
     *
     * <p>Each gun is charged once, after it is aimed, and the wait is a link on the edge that charge
     * produces: a gun that burns its buffer down goes dark, but the edge it announced when it lit is
     * already in the log.</p>
     */
    @Pins("INV-SHOT-09")
    @RedWitness(member = "ProximityBroadcast#sendNearSegment",
            at = "if (distanceSqToSegment(player.posX, player.posY, player.posZ, from, to) > radiusSq)",
            replacedBy = "if (false)",
            fails = "a beam burning four kilometres away was replicated to this client anyway",
            on = "2026-10-20")
    @Test
    public void aBeamBurningNearbyIsDrawnByTheClientAndOneFourKilometresAwayIsNot() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int y = site().y, z = site().z;
        int nearX = plot().x(BEAM_GUN_DX);
        int farX = nearX + FAR_OFFSET;

        long built = server.markInstrumented();
        buildBeamGun(farX, y, z);
        buildBeamGun(nearX, y, z);
        Weapons.awaitAssembled(server, built, farX, y, z, BEAM_GUN_PARTS, "the far beam gun never assembled");
        Weapons.awaitAssembled(server, built, nearX, y, z, BEAM_GUN_PARTS, "the near beam gun never assembled");
        // He stands beside the near gun for the whole scenario, so which gun is in range never changes
        // while anything burns.
        standBesideTheBeamGun(nearX, y, z);

        long clientMark = client.mark();
        long burning = server.mark();
        aimAtAnIronWall(farX, y, z);
        chargeTurret(farX, y, z);
        String farLit = server.awaitMatching(burning, "turret_beam",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(farX, y, z), "lit", "true").isEmpty(),
                "at the far gun, lit", "the far gun never burned at all, so this run proves nothing"
                        + " about the filter", Weapons.SUBJECT_TICKS);

        aimAtAnIronWall(nearX, y, z);
        chargeTurret(nearX, y, z);
        String nearLit = server.awaitMatching(burning, "turret_beam",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(nearX, y, z), "lit", "true").isEmpty(),
                "at the near gun, lit", "the near gun never burned, so nothing below is about"
                        + " replication", Weapons.SUBJECT_TICKS);
        long farSeq = (long) Events.number(
                Events.recordsWhereAll(farLit, "pos", Weapons.at(farX, y, z), "lit", "true").get(0), "seq");
        long nearSeq = (long) Events.number(
                Events.recordsWhereAll(nearLit, "pos", Weapons.at(nearX, y, z), "lit", "true").get(0), "seq");
        assertTrue("the far gun did not light before the near one (far seq " + farSeq + ", near seq "
                + nearSeq + "), so its announcement is not guaranteed to precede the near gun's on the"
                + " connection and the silence below would be about timing", farSeq < nearSeq);

        client.awaitRecordWithFields(clientMark, "client_beam_drawn",
                "a beam burning beside the player never reached the client: a held beam is a server-side"
                        + " line, so a client that is not told about one cannot draw it and the weapon fires"
                        + " invisibly. gun=" + readTurret(nearX, y, z),
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(nearX, y, z));

        String drawn = client.since(clientMark, "client_beam_drawn");
        Events.assertInstrumentRan(drawn, "client_beam_events", "no far beam was drawn");
        assertTrue("a beam burning four kilometres away was replicated to this client anyway — the"
                + " visibility filter is not filtering, and every gun in the world would be drawn on"
                + " every connection: " + drawn,
                Events.recordsWhere(drawn, "pos", Weapons.at(farX, y, z)).isEmpty());
    }

    // ---- a bent beam is drawn bent

    /**
     * A beam a mirror turned is DRAWN turned, and not straight through the mirror that turned it.
     *
     * <p>The server resolves a bent beam as a path with a corner in it. Everything about that is
     * invisible from the client's side unless the corner crosses the wire: a beam sent as two ends is still
     * one beam, still drawn — and drawn as a laser passing clean through a plate that is reflecting it. So
     * "a beam is drawn" cannot see this; what is read is the client's own record of what it drew,
     * {@code client_beam_drawn}, whose {@code bent} comes off the path the renderer holds.</p>
     *
     * <p>The control leg is the ordinary beam. A gun burning into an iron wall must be drawn with NO
     * corner: without that half, a bug that reported every beam as bent would pass this method.</p>
     *
     * <p>The gun is charged once per leg, after the thing it burns into is in place. Square-on to the
     * mirror the beam comes back down its own line and wears the gun away, so a gun gone by the end of
     * the second leg is the arrangement working; the corner is drawn on the first tick the mirror stands
     * in the beam.</p>
     */
    @Pins("INV-GUN-08")
    @RedWitness(member = "PacketBeamState#of",
            at = "packet.path.size() >= MAX_POINTS",
            replacedBy = "packet.path.size() >= 2",
            fails = "with a mirror in the beam's way the client is still drawing a straight line",
            on = "2026-10-20")
    @Test
    public void aBeamTurnedByAMirrorReachesTheClientWithItsCornerInIt() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int y = site().y, z = site().z;
        int gunX = plot().x(BEAM_GUN_DX);
        int targetX = gunX + BEAM_TARGET_RANGE;

        long built = server.markInstrumented();
        buildBeamGun(gunX, y, z);
        Weapons.awaitAssembled(server, built, gunX, y, z, BEAM_GUN_PARTS, "the beam gun never assembled");
        standBesideTheBeamGun(gunX, y, z);

        // What the beam burns into is a WALL as wide as the gun's own cone at that range, never one block:
        // every tick's beam leaves inside a cone of the spec's half-angle (`TurretFireControl#spread`), so
        // a one-block target is missed most ticks — measured 2026-10-20, 4 hits in a 40-tick burn, and a
        // whole burn that never met the mirror at all. Computed from the gun's own spread, read off it.
        Reply gun = readTurret(gunX, y, z).requireOk("read the gun's spread");
        int half = (int) Math.ceil(Math.tan(Math.toRadians(gun.number("spread"))) * BEAM_TARGET_RANGE + 0.5D);
        requireArranged("the gun's cone at its target must fit the cleared site (half-width " + half
                + ", spread " + gun.number("spread") + ")", half >= 1 && half <= 4);
        clearAtEnd(targetX, y - half, z - half, targetX + 1, y + half, z + half, "the walls the beam burns into");
        String wall = targetX + " " + (y - half) + " " + (z - half) + " " + targetX + " " + (y + half) + " " + (z + half);
        String backing = (targetX + 1) + " " + (y - half) + " " + (z - half) + " " + (targetX + 1) + " " + (y + half)
                + " " + (z + half);

        // Leg one, the control: plain iron. A beam that meets it is a straight line to its end.
        arrange("stellurgytest fill " + plot().dim + " " + wall + " minecraft:iron_block").requireOk("build the iron wall");
        long straight = client.mark();
        aimTurret(gunX, y, z, targetX + 0.5D, y + 0.5D, z + 0.5D);
        chargeTurret(gunX, y, z);
        String drawn = client.awaitMatching(straight, "client_beam_drawn",
                reply -> !Events.recordsWhere(reply, "pos", Weapons.at(gunX, y, z)).isEmpty(),
                "for this gun", "the beam never reached the client at all, so nothing here measured"
                        + " how it is drawn: " + readTurret(gunX, y, z), Weapons.SUBJECT_TICKS);
        String first = Events.recordsWhere(drawn, "pos", Weapons.at(gunX, y, z)).get(0);
        assertEquals("a beam burning into a plain iron wall was drawn with a corner in it. Nothing "
                + "turned it, so either the server invented a bend or the client is calling every "
                + "beam bent — and the mirror leg would then pass for that reason rather than for the "
                + "mirror: " + first, "false", Events.text(first, "bent"));

        // Leg two: swap the iron the beam is standing on for a mirror. Same gun, same aim, same
        // distance — the ONE thing that changes is what the beam meets.
        long mirrored = client.mark();
        long mirroredOnServer = server.mark();
        arrange("stellurgytest fill " + plot().dim + " " + wall + " stellurgy:mirrorPlatingGold").requireOk("the mirror wall");
        arrange("stellurgytest fill " + plot().dim + " " + backing + " minecraft:iron_block").requireOk("iron behind the mirror");
        chargeTurret(gunX, y, z);
        try {
            client.awaitMatching(mirrored, "client_beam_drawn",
                    reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(gunX, y, z), "bent", "true").isEmpty(),
                    "for this gun, bent", "with a mirror in the beam's way the client is still drawing a"
                            + " straight line: the corner never crossed the wire, so a player watching this sees"
                            + " a laser going clean through a plate that is reflecting it. "
                            + readTurret(gunX, y, z), Weapons.SUBJECT_TICKS);
        } catch (AssertionError never) {
            // Which side lost it: what the server's gun did and what its channel announced in the same
            // window, beside the client's silence.
            throw new AssertionError(never.getMessage() + "\n  the server's beam edges since the swap: "
                    + server.since(mirroredOnServer, "turret_beam")
                    + "\n  what the channel announced since the swap: "
                    + server.since(mirroredOnServer, "turret_beam_announced")
                    + "\n  the mirror's block now: " + read("stellurgytest damage stage " + plot().dim + " "
                    + targetX + " " + y + " " + z).raw(), never);
        }
    }

    // ---- the repairs a player makes by hand

    /**
     * A block a player PLACES into a hole a weapon left is a new block, not a destroyed one.
     *
     * <p>Every other damage test changes blocks through probe verbs, which fire no block event; the
     * damage map hears a player's placement through a {@code BlockEvent.PlaceEvent}, and a block put back
     * by hand is a real repair rather than one still recorded as destroyed. A real client places it here,
     * through its own right-click packet.</p>
     *
     * <p>The control is the half that makes it evidence: the same hole filled by a probe — which fires
     * no event — still reads as destroyed. So the map does keep a hole's record through a placement that
     * nobody announced, and what clears the subject's is the player's placement and nothing else. The
     * placement is linked on the map's own hearing of it ({@code damage_invalidation_heard}), and the
     * verdict is then read off the map once.</p>
     */
    @Pins("REPAIR-2")
    @RedWitness(member = "DamageInvalidationHandler#onBlockPlaced",
            at = "forget(event.getWorld(), event.getPos());",
            replacedBy = "",
            fails = "a block a player placed into a hole still reads as DESTROYED",
            on = "2026-10-20")
    @Test
    public void aBlockAPlayerPutsIntoAHoleIsNotRecordedAsDestroyed() throws Exception {
        Events server = serverEvents();
        int dim = plot().dim, x = site().x, y = site().y + 1, z = site().z;
        prepareRepairSite(x, y, z);
        int subjectX = x + 2, controlX = x + 4;
        post(subjectX, y, z);
        post(controlX, y, z);
        destroyPost(subjectX, y, z, 88101);
        destroyPost(controlX, y, z, 88102);

        // The control: a hole filled by something that announces nothing keeps its record. The `place`
        // verb sets the state and nothing else; `fill` is no use here, because it clears the damage map
        // itself as the harness's "this region is fresh".
        Reply controlPlaced = arrange("stellurgytest place " + dim + " " + controlX + " " + y + " " + z
                + " minecraft:stone");
        requireArranged("the control hole would not take a stone: " + controlPlaced,
                controlPlaced.bool("placed"));
        Reply control = damageStage(controlX, y, z);
        requireArranged("the control hole did not take the probe's stone: " + control,
                "minecraft:stone".equals(control.text("block")));
        assertTrue("a hole filled by a probe no longer reads as destroyed, so the map drops a hole's"
                + " record on ANY placement and the player's below proves nothing: " + control,
                control.bool("wasDestroyed"));

        // The subject: the player puts a stone into the other hole, by hand.
        standBesideThePosts(x, y, z);
        arrange("give " + PLAYER + " minecraft:stone 1");
        bot().selectHotbar(0);
        long placed = server.markInstrumented();
        bot().rightClickBlock(subjectX, y - 1, z, EnumFacing.UP, EnumHand.MAIN_HAND);
        server.awaitRecordWithFields(placed, "damage_invalidation_heard",
                "the player's placement into the hole never reached the damage map",
                Weapons.SUBJECT_TICKS, "cause", "place", "dim", String.valueOf(dim),
                "pos", Weapons.at(subjectX, y, z));

        Reply subject = damageStage(subjectX, y, z);
        requireArranged("the player's stone is not in the hole it was placed into: " + subject,
                "minecraft:stone".equals(subject.text("block")));
        assertTrue("a block a player placed into a hole still reads as DESTROYED: the placement was a"
                + " real repair paid for with the block itself, and the map charges it against the hull"
                + " anyway: " + subject, !subject.bool("wasDestroyed") && subject.integer("stage") == 0);
    }

    /**
     * A damaged block a player BREAKS takes its damage record with it: the position is empty, and
     * nothing is left there to be charged against whatever is built in it next.
     *
     * <p>The control is the same damage on a twin block removed by something that announces nothing — a
     * probe setting the block to air — which keeps its record; so the map does not drop a record merely
     * because the block under it went away, and what clears the subject's is the player's break and
     * nothing else. The break is a real attack-key press, aimed from the client's own eye and confirmed on
     * the client's crosshair before it is pressed, so it travels the path a player's does: the client's
     * click, its dig packet, the server's harvest and its {@code BlockEvent.BreakEvent}. It is linked on
     * the map's own hearing of it ({@code damage_invalidation_heard} with {@code cause} {@code break}), and
     * the verdict is then read off the map once.</p>
     *
     * <p>Creative, which is the shared base's own mode for every scenario, so there is nothing to put
     * back: ONE press is a whole break ({@code PlayerControllerMP.clickBlock} sends the dig and the
     * server's {@code onBlockClicked} harvests at once).</p>
     */
    @Pins("REPAIR-2")
    @RedWitness(member = "DamageInvalidationHandler#onBlockBroken",
            at = "forget(event.getWorld(), event.getPos());",
            replacedBy = "",
            fails = "a damaged block a player broke still carries its damage",
            on = "2026-10-20")
    @Test
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(CROSSHAIR_RECOMPUTE_TICKS);",
            why = "one client tick, the one whose start recomputes the crosshair from the rotation the placement"
                    + " applied (Minecraft.runTick recomputes it before it reads any key).")
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(ATTACK_HELD_TICKS);",
            why = "the attack key held across one client tick, as a mouse button is — long enough for the press to"
                    + " be read (Minecraft.processKeyBinds), shorter than the five ticks a creative break waits"
                    + " before it will take the next block (PlayerControllerMP.blockHitDelay).")
    public void aDamagedBlockAPlayerBreaksLeavesNoDamageBehind() throws Exception {
        int dim = plot().dim, x = site().x, y = site().y + 1, z = site().z;
        // The break and its record happen in the server's world, so the server half of the step is
        // that world's own clock; the client half counts the ticks in which it sends the press.
        Events server = new Events(c -> read(c).raw(), worldAndClient(dim), evictionReports());
        prepareRepairSite(x, y, z);
        int subjectX = x + 2, controlX = x + 6;
        post(subjectX, y, z);
        post(controlX, y, z);
        int subjectStage = damagePost(subjectX, y, z, 88104);
        int controlStage = damagePost(controlX, y, z, 88105);

        // The control: the damaged twin taken away by something that fires no event keeps its record.
        Reply controlRemoved = arrange("stellurgytest place " + dim + " " + controlX + " " + y + " " + z
                + " minecraft:air");
        requireArranged("the control post would not be removed: " + controlRemoved,
                controlRemoved.bool("placed"));
        Reply control = damageStage(controlX, y, z);
        requireArranged("the control post is still standing: " + control,
                "minecraft:air".equals(control.text("block")));
        assertEquals("a damaged block removed by a probe no longer carries its damage, so the map drops"
                + " a record whenever the block under it goes, and the player's break below proves"
                + " nothing: " + control, controlStage, control.integer("stage"));

        // The subject: the player breaks the other one by hand. Stand three blocks north of the post,
        // level with it, and face it: the rotation travels in the placement itself, so the look the
        // client applies is the one the teleport carried and no later packet can overwrite it.
        bot().selectHotbar(0);
        double standX = subjectX + 0.5D, standY = y, standZ = z - 2.5D;
        double dy = (y + 0.5D) - (standY + EYE_HEIGHT), dz = (z + 0.5D) - standZ;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dz));
        standOnFloorTheClientHolds(standX, standY, standZ, 0.0F, pitch,
                "the player must stand in front of the damaged post");
        bot().waitWorldTicks(CROSSHAIR_RECOMPUTE_TICKS);
        JsonObject pick = bot().reportMouseOver();
        requireArranged("the client's crosshair is not on the damaged post, so a press would break"
                + " something else: " + pick,
                pick.has("typeOfHit") && "BLOCK".equals(pick.get("typeOfHit").getAsString())
                        && pick.has("blockX") && pick.get("blockX").getAsInt() == subjectX
                        && pick.get("blockY").getAsInt() == y && pick.get("blockZ").getAsInt() == z);

        long broken = server.markInstrumented();
        bot().setKey(KEY_ATTACK, true);
        bot().waitWorldTicks(ATTACK_HELD_TICKS);
        bot().setKey(KEY_ATTACK, false);
        server.awaitRecordWithFields(broken, "damage_invalidation_heard",
                "the player's break of the damaged post never reached the damage map",
                ROUND_TRIP_DEADLINE_TICKS, "cause", "break", "dim", String.valueOf(dim),
                "pos", Weapons.at(subjectX, y, z));

        Reply subject = damageStage(subjectX, y, z);
        requireArranged("the player's press did not take the post away: " + subject,
                "minecraft:air".equals(subject.text("block")));
        assertTrue("a damaged block a player broke still carries its damage (" + subjectStage + " stages"
                + " before the break): the next block built there would start cracked, and the"
                + " hull is charged for a crack that was mined out: " + subject,
                subject.integer("stage") == 0 && !subject.bool("wasDestroyed"));
    }

    // ---- friend or foe at the gun

    /**
     * Whether a gun can tell a friend from a target.
     *
     * <p>The credential is CARRIED, not held about somebody: an entity is friendly for exactly as long as
     * it has the installation's code on it, and the only entity that can carry one is a player. A
     * dedicated-server test has no players, so the whole mechanic is unreachable there.</p>
     *
     * <p>A gun that never fires passes "does not shoot friendlies" trivially. So the refusal is read off
     * the gun's own fire decision — taken on target, charged, and answered "friendly" — and the same gun,
     * pointed at the same player, is then seen to fire once the code stops matching.</p>
     *
     * <p>He stands still on a floor his client holds before the gun is built twenty blocks east of him:
     * a gun tracking a FALLING target pins its elevation arc and stops firing, which is indistinguishable
     * from the refusal this is about.</p>
     */
    @Pins("INV-GUN-10")
    @RedWitness(verdict = "the gun is never permitted to fire on him",
            member = "TileTurret#canFireNow",
            at = "return !targetIsFriendly()",
            replacedBy = "return true",
            fails = "the gun was PERMITTED to fire on a player carrying its own access code",
            on = "2026-10-20")
    @Test
    public void aGunHoldsFireOnAPlayerCarryingItsCodeAndFiresOnOneWhoIsNot() throws Exception {
        Events server = serverEvents();
        int dim = plot().dim, px = site().x, py = site().y + 1, pz = site().z;
        int gx = px + GUN_OFFSET;
        standInTheCorridor(px, py, pz, gx);
        holdChunkUntilEnd(dim, gx >> 4, pz >> 4);
        long built = server.markInstrumented();
        buildBarrelGun(gx, py, pz);
        Weapons.awaitAssembled(server, built, gx, py, pz, BARREL_GUN_PARTS, "the gun never assembled");
        chargeTurret(gx, py, pz);
        arrange("stellurgytest turret code " + dim + " " + gx + " " + py + " " + pz + " ALPHA").requireOk("set the code");

        // The player carries the installation's own code, and is therefore a friend.
        arrange("stellurgytest arrange coded-item " + PLAYER + " ALPHA");
        long friend = server.mark();
        arrange("stellurgytest turret target-player " + dim + " " + gx + " " + py + " " + pz + " " + PLAYER)
                .requireOk("point the gun at the player");

        // The fire decision is asked only once the mount is on its target: the record of it taken with
        // the player carrying the code is where the gun answers.
        String held = server.awaitRecordWithFields(friend, "turret_fire_decided",
                "the gun never took a fire decision on the player — it never got onto him, so its"
                        + " silence would be about geometry rather than the credential",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(gx, py, pz), "caller", "auto",
                "friendly", "true");
        // Every decision taken on him while he carried the code, not the first one alone: the answer is a
        // conjunction (lock, cooldown, heat, energy too), so the first decision can be a refusal for another
        // term, and only the whole window says the credential held. Measured 2026-10-20: with the friend
        // term removed, the first friendly decision still read permitted:false and the gun then fired.
        String decidedOnHim = server.since(friend, "turret_fire_decided");
        Events.assertInstrumentRan(decidedOnHim, "turret_fire_events",
                "no decision on him was a permission");
        List<String> permittedOnHim = Events.recordsWhereAll(decidedOnHim,
                "pos", Weapons.at(gx, py, pz), "friendly", "true", "permitted", "true");
        assertTrue("the gun was PERMITTED to fire on a player carrying its own access code: " + permittedOnHim
                + " | its first decision on him: " + held, permittedOnHim.isEmpty());
        assertTrue("the gun shot a player carrying its own access code: " + readTurret(gx, py, pz),
                Weapons.firedSince(server, friend, gx, py, pz).isEmpty());

        // Same gun, same player, same target — only the credential changes.
        long foe = server.mark();
        arrange("clear " + PLAYER);
        arrange("stellurgytest arrange coded-item " + PLAYER + " BRAVO");
        chargeTurret(gx, py, pz);
        Weapons.awaitFired(server, foe, gx, py, pz,
                "the gun would not fire on a player carrying somebody else's code either — the hold"
                        + " was not about the credential: " + readTurret(gx, py, pz));
    }

    // ---- friend or foe at the sensor

    /**
     * Where a friend is spared: <b>before there is a target, not at the trigger.</b>
     *
     * <p>A gun already declines to fire on somebody carrying the installation's code. This is the
     * stronger statement one level up — an ally never becomes a CONTACT, so his name is never written
     * anywhere a gun could read it, and no race, stale order or second console can talk the battery into
     * shooting him. The credential is carried, and only a player can carry one.</p>
     *
     * <p>Equal windows, counted in SWEEPS: the sensor decides who is a contact once per sweep and records
     * each sweep's list ({@code sensor_swept}, with the contacts' names). The friend is watched for exactly
     * {@link #SWEEPS} sweeps taken while he carried the code, and the control — the same player, the same
     * place, only the code changed — must appear within the same number. A control allowed longer than the
     * subject proves acquisition EVENTUALLY, not within the window the friend was watched for.</p>
     *
     * <p>"The battery fired at a player carrying its own code" is DOWNSTREAM of the friend-leg sweep
     * verdict: the gun's only source of a target here is the acquisition, so a round at the friend needs
     * the friend in a sweep, which that verdict reds first. Mob spawning is off for the scenario, so no
     * creature becomes a contact the battery could fire on during the friend's window, and it is put back
     * when the scenario ends.</p>
     */
    @Pins("INV-FCS-07")
    @RedWitness(verdict = "the friend is never a contact",
            member = "TacticalScan#trackOf",
            at = "if (CodeUtils.entityHasMatchingCode(candidate, friendlyCode))",
            replacedBy = "if (false)",
            fails = "a player carrying the installation's code entered the target list",
            on = "2026-10-20")
    @RedWitness(verdict = "the foe is fired on as an acquisition",
            member = "TileFireControlSensor#publish",
            at = "state.setAcquiredTrack(contacts.get(0), world.getTotalWorldTime(), hold);",
            replacedBy = "state.setTargetEntity(contacts.get(0).getEntity());",
            fails = "it fired, but not on an acquisition",
            on = "2026-10-20")
    @Test
    public void aPlayerCarryingTheCodeNeverBecomesAContactAndOneWhoIsNotDoes() throws Exception {
        Events server = serverEvents();
        int dim = plot().dim, px = site().x, py = site().y + 1, pz = site().z;
        int bx = px + GUN_OFFSET;
        int sx = bx + 1;

        arrange("gamerule doMobSpawning false").raw();
        undoAtEnd("mob spawning back on", () -> arrange("gamerule doMobSpawning true").raw());
        standInTheCorridor(px, py, pz, bx);
        holdChunkUntilEnd(dim, bx >> 4, pz >> 4);
        long built = server.markInstrumented();
        buildBarrelGun(bx, py, pz);
        place("stellurgy:fireControlSensor", sx, py, pz);
        Weapons.awaitAssembled(server, built, bx, py, pz, BARREL_GUN_PARTS,
                "the gun never assembled, so nothing below would mean anything");
        chargeTurret(bx, py, pz);
        arrange("stellurgytest sensor charge " + dim + " " + sx + " " + py + " " + pz).requireOk("charge the sensor");
        arrange("stellurgytest turret code " + dim + " " + bx + " " + py + " " + pz + " ALPHA").requireOk("code the gun");
        arrange("stellurgytest sensor code " + dim + " " + sx + " " + py + " " + pz + " ALPHA").requireOk("code the sensor");

        // The player carries the installation's own code, and is therefore not a target at all.
        arrange("stellurgytest arrange coded-item " + PLAYER + " ALPHA");
        long friend = server.mark();
        List<String> friendSweeps = sweeps(server, friend, sx, py, pz);
        for (String sweep : friendSweeps) {
            assertTrue("a player carrying the installation's code entered the target list: " + sweep,
                    !sweptNames(sweep).contains(PLAYER));
        }

        // Same player, same place, same battery. Only the code changes.
        arrange("clear " + PLAYER).raw();
        arrange("stellurgytest arrange coded-item " + PLAYER + " BRAVO");
        // Marked AFTER the new code is in his hand, exactly as the friend leg was: every sweep of this
        // window is one taken while he carried somebody else's code.
        long foe = server.mark();
        List<String> foeSweeps = sweeps(server, foe, sx, py, pz);
        boolean acquired = false;
        for (String sweep : foeSweeps) {
            acquired |= sweptNames(sweep).contains(PLAYER);
        }
        assertTrue("the sensor did not acquire the player carrying somebody else's code within the "
                + SWEEPS + " sweeps the friend was watched for — then the exclusion above was not"
                + " about the credential: " + foeSweeps, acquired);

        String fired = Weapons.awaitFired(server, foe, bx, py, pz,
                "the battery never fired on a contact its own sensor had acquired");
        assertEquals("it fired, but not on an acquisition: " + fired, "true",
                Events.text(fired, "acquired"));

        // Only now can the friend leg's silence be read: a gun with nobody to shoot never asks the fire
        // question, so its fire recorder is first proved live by the round just fired.
        List<String> atFriend = Weapons.firedSince(server, friend, bx, py, pz);
        atFriend.removeIf(round -> Events.number(round, "seq") >= foe);
        assertTrue("the battery fired at a player carrying its own code: " + atFriend,
                atFriend.isEmpty());
    }

    // ---- the linker names a battery's target

    /**
     * A player names his battery's target with a linker: he binds it to the battery's console, looks at a
     * block twenty blocks off and right-clicks, and the gun on the console's network turns onto it and
     * fires; then he looks at a creature and right-clicks, and it turns onto that instead; then back.
     *
     * <p>Every click is a press of the right mouse button through the input pipeline, never a verb naming
     * its target: the bind is a sneaking right-click with his crosshair on the console (a plain one opens
     * the console's screen), the aim is his look, and the designation is a right-click with nothing in
     * reach under the crosshair, which vanilla turns into a use of the held item. Each pick is read before
     * the press. Probes ARRANGE only what no interface lets a player do — the blocks, the gun's charge, the
     * linker in his inventory, a creature that stands still — and otherwise only READ.</p>
     *
     * <p><b>What it does NOT see.</b> Whether the rounds HIT: nothing corrects a named target's aim for the
     * round's fall, so the verdict is that the gun is pointed at what he named and fires on it, not that the
     * round arrives. A gun linked directly rather than through a console. A ship-mounted battery, and a
     * target on a ship.</p>
     */
    @Pins("INV-LVP-22")
    @RedWitness(verdict = "the bind",
            member = "LinkerDesignation#bind",
            at = "ItemLinker.setMasterCoords(linker, tile.getPos());",
            replacedBy = "",
            fails = "the linker must carry the CONSOLE's position once he has clicked it",
            on = "2026-10-20")
    @RedWitness(verdict = "a point order replaces a creature order",
            member = "TileWeaponConsole#onLinkAimed",
            at = "state.clearTarget();",
            replacedBy = "",
            fails = "the gun must turn off the zombie and back onto the block",
            on = "2026-10-20")
    @Test
    public void aPlayerNamesTheBatteryItsTargetByLookingAtItThroughALinker() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int dim = plot().dim;

        // ---- where it stands: open air, this scenario's own plot, proved empty first
        FixtureSite site = site();
        site.requireClear(c -> arrange(c).raw(), LINKER_HALO, 4, "the battery, the player beside it and both targets");
        int x0 = site.x, y0 = site.y, z0 = site.z;
        clearAtEnd(x0 - LINKER_HALO, y0, z0 - LINKER_HALO, x0 + FixtureSite.PAD + LINKER_HALO, y0 + 4,
                z0 + FixtureSite.PAD + LINKER_HALO, "the battery, its floor and both targets");

        int gx = x0 + 2, gy = y0 + 1, gz = z0 + 2;                  // the gun's controller
        int cx = x0 + 1, cy = y0 + 1, cz = z0 + 2;                  // its console, touching it on the west
        int tx = gx + LINKER_TARGET_RANGE, ty = y0 + 1, tz = z0 + 4; // the block he names
        int zx = tx, zy = y0, zz = z0 - 2;                          // the zombie's footing

        arrange("stellurgytest fill " + dim + " " + (x0 - 1) + " " + y0 + " " + z0 + " " + (x0 + 4) + " " + y0
                + " " + (z0 + 6) + " minecraft:stone").requireOk("a floor for him");
        place("minecraft:stone", tx, ty, tz);
        place("minecraft:stone", zx, zy, zz);

        // ---- the battery: console first, then the barrel, then the controller that walks them
        long built = server.mark();
        place("stellurgy:weaponconsole", cx, cy, cz);
        place("stellurgy:gunbarrel", gx, gy + 1, gz);
        place("stellurgy:turret", gx, gy, gz);
        String assembled = server.awaitRecordWithFields(built, "turret_assembled",
                "the gun must count its build before it is anything to aim", Weapons.ARRANGEMENT_TICKS,
                "pos", Weapons.at(gx, gy, gz), "operable", "true");
        long afterAssembly = seqOf(assembled);
        server.awaitMatching(built, "subsystem_network_rebuilt",
                seen -> !laterThan(Events.recordsWhere(seen, "domain", "Weapon"), afterAssembly).isEmpty(),
                "a Weapon rebuild later than the gun's first tick",
                "the gun and its console must be joined into one network", Weapons.ARRANGEMENT_TICKS);
        Reply console = read("stellurgytest weaponconsole read " + dim + " " + cx + " " + cy + " " + cz)
                .requireOk("console read");
        requireArranged("the console must command exactly this one gun: " + console,
                console.bool("network") && console.integer("guns") == 1);
        chargeTurret(gx, gy, gz);
        Reply before = readTurret(gx, gy, gz).requireOk("turret read");
        requireArranged("before he names anything the gun must have NO target, or what follows could not"
                + " be his doing: " + before, !before.bool("hasTarget"));

        // ---- him: on the floor, behind the gun, the linker in his hand
        standOnFloorTheClientHolds(x0 + 0.5D, y0 + 1, z0 + 4.5D, 0.0F, 0.0F,
                "he stands on the floor behind his battery");
        long given = client.mark();
        arrange("give " + PLAYER + " libvulpes:linker");
        client.awaitMatching(given, "client_slot_set",
                seen -> Events.anyRecordFieldContains(seen, "item", "libvulpes:linker"),
                "holding libvulpes:linker", "the linker must reach his inventory", ROUND_TRIP_DEADLINE_TICKS);
        bot().selectHotbar(0);
        JsonObject state = bot().reportState();
        requireArranged("the linker must be in his main hand: " + state,
                state.get("heldItem").getAsString().contains("linker"));

        // ---- the bind: a sneaking right-click on the console, the way he would — he looks at the face
        // turned toward him, and the click goes wherever vanilla's own pick says it goes
        setSneaking(server, true);
        lookAt(server, new double[]{cx + 0.5D, cy + 0.5D, cz + 1.0D});
        JsonObject onConsole = bot().reportMouseOver();
        requireArranged("his crosshair must rest on the console before he clicks: " + onConsole
                        + " | he stands at: " + bot().reportState(),
                "BLOCK".equals(onConsole.get("typeOfHit").getAsString())
                        && onConsole.get("blockX").getAsInt() == cx && onConsole.get("blockY").getAsInt() == cy
                        && onConsole.get("blockZ").getAsInt() == cz);
        long binding = server.mark();
        rightClick();
        String bound = server.awaitRecordWithFields(binding, "weapon_linker_bound",
                "his sneaking right-click on the console must bind the linker to it", ROUND_TRIP_DEADLINE_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("the linker must carry the CONSOLE's position once he has clicked it: " + bound,
                Weapons.at(cx, cy, cz), (long) Events.number(bound, "linkedX") + ","
                        + (long) Events.number(bound, "linkedY") + "," + (long) Events.number(bound, "linkedZ"));
        setSneaking(server, false);

        // ---- leg 1: he looks at the block and right-clicks
        double[] faceCentre = {tx, ty + 0.5D, tz + 0.5D};
        double[] eye = lookAt(server, faceCentre);
        double range = distance(eye, faceCentre);
        requireArranged("the target must stand beyond any click on a block (" + BLOCK_CLICK_REACH
                + "), or this proves nothing a click could not do; it stands " + range, range > BLOCK_CLICK_REACH);

        requireNothingInReach("the block twenty blocks off");
        long namingBlock = server.mark();
        rightClick();
        String designated = server.awaitRecordWithFields(namingBlock, "weapon_designated",
                "his right-click must hand the console what he was looking at", ROUND_TRIP_DEADLINE_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("the console must TAKE the designation: " + designated,
                "true", Events.text(designated, "taken"));
        assertEquals("what he looked at was a BLOCK: " + designated, "BLOCK", Events.text(designated, "hit"));
        double[] hit = {Events.number(designated, "hitX"), Events.number(designated, "hitY"),
                Events.number(designated, "hitZ")};
        // Predicted, not read back: a ray aimed at the centre of the block's near face lands there.
        assertTrue("his line of sight must land on the centre of the target's near face "
                        + Arrays.toString(faceCentre) + ", not " + Arrays.toString(hit),
                distance(hit, faceCentre) < 0.05D);

        awaitGun(server, namingBlock, seqOf(designated), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn onto the block he named");
        Reply aimed = readTurret(gx, gy, gz).requireOk("turret read");
        assertTrue("the gun's target must be the point he named " + Arrays.toString(hit) + ": " + aimed,
                aimed.bool("hasTarget") && distance(hit, targetOf(aimed)) < 1.0E-6D);
        awaitGun(server, namingBlock, seqOf(designated), gx, gy, gz, "turret_fired", null, null,
                "the gun must fire on the block he named");

        // ---- leg 2: a creature, standing still, and he names it instead. A zombie and not an animal:
        // the test server runs with spawn-animals off, and vanilla then kills every animal on its first
        // tick. No AI and unkillable, so the point the gun must follow stays put while it is read; its uuid
        // is given, so the designation is checked against THIS one.
        long summoning = server.mark();
        String summoned = arrange("stellurgytest arrange held-target " + dim + " " + (zx + 0.5D) + " " + (zy + 1)
                + " " + (zz + 0.5D) + " " + ZOMBIE_UUID_MOST + " " + ZOMBIE_UUID_LEAST).raw();
        undoAtEnd("the held zombie removed", () -> arrange("kill " + ZOMBIE_UUID).raw());
        Reply zombies = read("stellurgytest entity near " + dim + " " + (zx + 0.5D) + " "
                + (zy + 1) + " " + (zz + 0.5D) + " 2 EntityZombie").requireOk("entity near");
        // the producer always writes `count` on an ok `entity near` reply, so a reply without it is a
        // broken probe and refusing here is the right failure.
        if (zombies.integer("count") != 1) {
            ArrangementFailure.arrangementFailed("exactly one zombie must stand by its footing: " + zombies
                    + " | the summon said: " + summoned
                    + " | removed since the summon: " + server.since(summoning, "entity_removed"));
        }
        // Where it actually stands, read rather than assumed from the summon's arguments.
        Reply zombie = Reply.of(zombies.objectArray("entities")[0]);
        // Shots on the block spent the charge; what the gun has left is not this leg's subject.
        chargeTurret(gx, gy, gz);
        double[] zombieCentre = {zombie.number("x"), zombie.number("y") + ZOMBIE_HEIGHT / 2.0D,
                zombie.number("z")};
        lookAt(server, zombieCentre);

        requireNothingInReach("the zombie");
        long namingZombie = server.mark();
        rightClick();
        String designatedZombie = server.awaitRecordWithFields(namingZombie, "weapon_designated",
                "his right-click on the zombie must hand the console the zombie", ROUND_TRIP_DEADLINE_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("what he looked at was a CREATURE: " + designatedZombie,
                "ENTITY", Events.text(designatedZombie, "hit"));
        assertEquals("and it was this zombie: " + designatedZombie, ZOMBIE_UUID, Events.text(designatedZombie, "entity"));
        assertEquals("the console must take it: " + designatedZombie, "true", Events.text(designatedZombie, "taken"));

        // Later than the designation, not merely later than the mark: the gun was on target and firing
        // at the block when he clicked, and an edge or a round from those last ticks would otherwise read
        // as one on the zombie.
        awaitGun(server, namingZombie, seqOf(designatedZombie), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn off the block and onto the zombie");
        Reply onZombie = readTurret(gx, gy, gz).requireOk("turret read");
        assertTrue("the gun must follow the zombie's body centre " + Arrays.toString(zombieCentre)
                + " — a designation that left the block order standing would still point at the"
                + " block: " + onZombie, onZombie.bool("hasTarget") && distance(zombieCentre, targetOf(onZombie)) < 0.05D);
        awaitGun(server, namingZombie, seqOf(designatedZombie), gx, gy, gz, "turret_fired", null, null,
                "the gun must fire on the zombie he named");

        // ---- leg 3: back to the block. A followed creature outranks a point, so this is the leg that
        // fails if naming a point leaves the creature order standing.
        lookAt(server, faceCentre);
        requireNothingInReach("the block again");
        long renamingBlock = server.mark();
        rightClick();
        String designatedAgain = server.awaitRecordWithFields(renamingBlock, "weapon_designated",
                "his right-click on the block must hand the console the block again", ROUND_TRIP_DEADLINE_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("what he looked at was the BLOCK again: " + designatedAgain,
                "BLOCK", Events.text(designatedAgain, "hit"));
        awaitGun(server, renamingBlock, seqOf(designatedAgain), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn off the zombie and back onto the block");
        Reply back = readTurret(gx, gy, gz).requireOk("turret read");
        assertTrue("the gun must point at the block again, not keep following the zombie "
                + Arrays.toString(zombieCentre) + ": " + back,
                back.bool("hasTarget") && distance(faceCentre, targetOf(back)) < 0.05D);
    }

    // ---- a round reaches the player near it, and its end with it

    /**
     * Whether a fired round reaches the person it is fired near.
     *
     * <p>A shot is a server-side record: no entity, no chunk, nothing vanilla replicates on its own. So
     * "you can see the gun firing" is entirely a claim about a packet, checkable on the real client — the
     * client's own tracker records every round it is handed to draw ({@code client_shot_drawn}), on the
     * client thread, from the packets themselves.</p>
     *
     * <p>The far round is the control and is fired FIRST, the near one second, down the one ordered
     * connection this player has: a spawn packet for the far round would therefore have been drawn before
     * the near round's — which the client did draw — so the near round is the far round's instrument check
     * as well as its own claim. The near round flies south, across the player's view and out of the lane;
     * the far one starts four kilometres east and south of him and flies further away.</p>
     */
    @Pins("INV-SHOT-10")
    @RedWitness(member = "ProximityBroadcast#sendNearSegment",
            at = "if (distanceSqToSegment(player.posX, player.posY, player.posZ, from, to) > radiusSq)",
            replacedBy = "if (false)",
            fails = "a round fired four kilometres away was replicated to this client anyway",
            on = "2026-10-20")
    @Test
    public void aRoundFiredNearbyIsDrawnByTheClientAndOneFiredFarAwayIsNot() throws Exception {
        Events client = clientEvents();
        double[] at = standOnAFloorOfHisOwn();
        double px = at[0], py = at[1], pz = at[2];

        long mark = client.mark();
        long distant = fireRound((px + FAR_OFFSET) + " " + py + " " + (pz + FAR_OFFSET) + " 4 0 4 2000 200");
        // Fired 40 blocks away, across the player's view. The launch is production's own entry point —
        // the same call a turret makes.
        long near = fireRound((px + SHOT_NEAR) + " " + py + " " + pz + " 0 0 4 2000 200");

        client.awaitRecordWithFields(mark, "client_shot_drawn",
                "a round fired 40 blocks from the player never reached the client: a shot is a server"
                        + " record, so a client that is not told about one cannot draw it and the"
                        + " turret fires invisibly", Weapons.SUBJECT_TICKS, "shot", String.valueOf(near));

        String drawn = client.since(mark, "client_shot_drawn");
        Events.assertInstrumentRan(drawn, "client_shot_events", "the far round was never drawn");
        assertTrue("a round fired four kilometres away was replicated to this client anyway — the"
                + " visibility filter is not filtering: " + drawn,
                Events.recordsWhere(drawn, "shot", String.valueOf(distant)).isEmpty());
    }

    /**
     * A round that stops in a wall in front of the player is told to the client as ENDED, for the reason
     * it ended — so the drawing stops at the wall and the flash is drawn where it hit. A round whose end
     * never reaches the client is drawn flying on through the wall it stopped in
     * ({@code client_shot_ended}).
     *
     * <p>Not separately inverted: a replication that dropped or rewrote the reason on the way. The reason
     * witness breaks the server's decision, so it shows the verdict reads the reason the client was
     * handed.</p>
     */
    @Pins("INV-SHOT-11")
    @RedWitness(verdict = "the end reaches the client",
            member = "ShotReplication#announceEnd",
            at = "if (world == null || world.isRemote || point == null)",
            replacedBy = "if (true)",
            fails = "a round that stopped in a wall beside the player was never told to the client as over",
            on = "2026-10-20")
    @RedWitness(verdict = "the reason is the structure",
            member = "ShotSubstrate#step",
            at = "shot.setPosition(structure.point.add(direction.scale(contact.distance)));"
                    + " shot.setVelocity(new Vec3d(0.0D, 0.0D, 0.0D)); return ShotEndReason.STRUCTURE_IMPACT;",
            replacedBy = "shot.setPosition(structure.point.add(direction.scale(contact.distance)));"
                    + " shot.setVelocity(new Vec3d(0.0D, 0.0D, 0.0D)); return ShotEndReason.EXPIRED;",
            fails = "the client was told the round ended, but not that it hit structure",
            on = "2026-10-20")
    @Test
    public void aRoundThatStopsNearbyIsToldToTheClientAsEnded() throws Exception {
        Events client = clientEvents();
        int dim = plot().dim;
        double[] at = standOnAFloorOfHisOwn();
        int wallX = (int) Math.floor(at[0] + SHOT_NEAR);
        int wallY = (int) Math.floor(at[1]), wallZ = (int) Math.floor(at[2]) + 6;
        clearAtEnd(wallX - 6, wallY - 1, wallZ - 1, wallX + 2, wallY + 1, wallZ + 1, "the round's lane and its wall");
        arrange("stellurgytest fill " + dim + " " + (wallX - 6) + " " + (wallY - 1) + " " + (wallZ - 1) + " "
                + (wallX + 2) + " " + (wallY + 1) + " " + (wallZ + 1) + " minecraft:air").requireOk("clear the lane");
        arrange("stellurgytest fill " + dim + " " + wallX + " " + (wallY - 1) + " " + (wallZ - 1) + " " + (wallX + 2)
                + " " + (wallY + 1) + " " + (wallZ + 1) + " minecraft:stone").requireOk("build the wall");

        // A block and a half of this wall's own price, read off the wall: enough to bore in and run out
        // inside a three-block wall. Not less — a round that cannot buy a single stage LODGES in the face
        // and lives out its lifetime there, ending EXPIRED, which is a different ending.
        Reply wall = read("stellurgytest damage stage " + dim + " " + wallX + " " + wallY + " " + wallZ);
        int budget = (int) Math.round(wall.integer("stageCost") * Math.max(1, wall.integer("maxStage")) * 1.5D);
        requireArranged("the wall has no price, so no budget here means anything: " + wall, budget > 0);

        long mark = client.mark();
        // Four blocks short of the wall, flying at it at a block a tick.
        long id = fireRound((wallX - 4.0D) + " " + (wallY + 0.5D) + " " + (wallZ + 0.5D) + " 1 0 0 " + budget
                + " 200");

        client.awaitRecordWithFields(mark, "client_shot_drawn",
                "the round fired beside the player was never drawn, so its end below would be about"
                        + " nothing", Weapons.SUBJECT_TICKS, "shot", String.valueOf(id));
        String ended = client.awaitRecordWithFields(mark, "client_shot_ended",
                "a round that stopped in a wall beside the player was never told to the client as"
                        + " over: it goes on being drawn in flight through the wall it stopped in",
                Weapons.SUBJECT_TICKS, "shot", String.valueOf(id));
        assertEquals("the client was told the round ended, but not that it hit structure: " + ended,
                "STRUCTURE_IMPACT", Events.text(ended, "reason"));
    }

    // ---- the welder in a player's hand

    /**
     * A charged welder in a player's hand, used on a damaged block with its materials in the inventory,
     * takes exactly one stage off — through the item's own use, reached by a real right-click after the
     * protection hooks have had their say. The link is the block's own stage write
     * ({@code block_stage_set}), which carries the stage it came from and the one it went to.
     *
     * <p>What is NOT pinned here is the welder's price and its refusals — those are the static
     * {@code weld}'s, and {@code RepairWelderTest} holds them. This is the wiring from a hand to it.</p>
     *
     * <p>Survival, because in creative the welder repairs for nothing, which is a different path; the game
     * mode is put back to the shared base's creative when the scenario ends.</p>
     */
    @Pins("REPAIR-1")
    @RedWitness(verdict = "the use reaches the repair",
            member = "ItemRepairWelder#onItemUse",
            at = "if (world.isRemote) {",
            replacedBy = "if (true) {",
            fails = "a charged welder in a player's hand, used on a damaged block with its materials in the"
                    + " inventory, repaired nothing",
            on = "2026-10-20")
    @RedWitness(verdict = "it starts from the block's damage",
            member = "ItemRepairWelder#weld",
            at = "setStoredEnergy(tool, storedEnergy(tool) - energyCost);",
            replacedBy = "setStoredEnergy(tool, storedEnergy(tool) - energyCost);"
                    + " DamageState.setStage(world, pos, stage + 1);",
            fails = "the stage write the welder made did not start from the block's damage",
            on = "2026-10-20")
    @RedWitness(verdict = "exactly one stage",
            member = "ItemRepairWelder#weld",
            at = "- energyCost); DamageState.setStage(world, pos, stage - 1);",
            replacedBy = "- energyCost); DamageState.setStage(world, pos, stage - 2);",
            fails = "one use of the welder must take exactly one stage off",
            on = "2026-10-20")
    @Test
    public void aWelderInAPlayersHandTakesOneStageOff() throws Exception {
        Events server = serverEvents();
        int dim = plot().dim, x = site().x, y = site().y + 1, z = site().z;
        prepareRepairSite(x, y, z);
        int blockX = x + 6;
        arrange("stellurgytest fill " + dim + " " + blockX + " " + y + " " + z + " " + blockX + " " + y + " " + z
                + " minecraft:iron_block").requireOk("place the subject");
        Reply priced = damageStage(blockX, y, z);
        int twoStages = priced.integer("stageCost") * 2;
        requireArranged("the subject has no price, so it cannot be damaged by a known amount: " + priced,
                twoStages > 0);
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = act("stellurgytest damage impact " + dim + " " + (blockX + 0.5D) + " " + (y + 3.5D) + " "
                + (z + 0.5D) + " 0 -1 0 " + twoStages + " KINETIC 88103").requireOk("damage the subject");
        int damaged = damageStage(blockX, y, z).integer("stage");
        requireArranged("the subject must be damaged and standing, or there is nothing to weld: stage "
                + damaged + " after " + shot, damaged >= 1 && damaged < priced.integer("maxStage"));

        arrange("gamemode 0 " + PLAYER).raw();
        undoAtEnd("the shared base's creative mode back", () -> arrange("gamemode 1 " + PLAYER).raw());
        standBesideThePosts(x, y, z);
        arrange("stellurgytest arrange charged-item " + PLAYER + " stellurgy:repairWelder 100000")
                .requireOk("a charged welder in his hand");
        arrange("give " + PLAYER + " minecraft:iron_ingot 64");
        bot().selectHotbar(0);

        long used = server.markInstrumented();
        bot().rightClickBlock(blockX, y, z, EnumFacing.UP, EnumHand.MAIN_HAND);
        String welded = server.awaitRecordWithFields(used, "block_stage_set",
                "a charged welder in a player's hand, used on a damaged block with its materials in the"
                        + " inventory, repaired nothing: the item's own use never reached the repair",
                Weapons.SUBJECT_TICKS, "dim", String.valueOf(dim), "pos", Weapons.at(blockX, y, z));
        assertEquals("the stage write the welder made did not start from the block's damage: " + welded,
                damaged, (int) Events.number(welded, "from"));
        assertEquals("one use of the welder must take exactly one stage off: " + welded,
                damaged - 1, (int) Events.number(welded, "to"));
    }

    // ---- the turret's bearing on the client

    /**
     * Whether a player can see which way a turret is pointing.
     *
     * <p>A block cannot be turned — it sits in a grid cell at one of a handful of fixed orientations — so
     * a turret's bearing exists only as numbers on the server and as a drawing on the client. The question
     * is therefore whether those numbers arrive, and that is answerable only on a real client. What is
     * asserted is the state the renderer draws FROM — the client tile's own mount, as its
     * {@code turret_aim} record on the CLIENT log; what is NOT asserted is that the barrel looks right,
     * which stays a human's judgement.</p>
     *
     * <p>The command travels, not the pose: the client runs the same traverse the server does, from the
     * command it was sent. So this waits for the client's own mount to report itself ON its command — the
     * arrival, not a sample of the travel — and then compares the command the client holds with the one
     * the server gave. A static target is commanded once and sent once, so the two are the same number
     * exactly.</p>
     */
    @Pins("INV-GUN-09")
    @RedWitness(verdict = "the client turns",
            member = "TileTurret#getUpdateTag",
            at = "nbt.setTag(\"mount\", mount);",
            replacedBy = "",
            fails = "the client's turret never turned onto its command",
            on = "2026-10-20")
    @RedWitness(verdict = "on target means pointing at the command",
            member = "TurretMechanism#isOnTarget",
            at = "return dYaw <= AIM_TOLERANCE_DEGREES && dPitch <= AIM_TOLERANCE_DEGREES;",
            replacedBy = "return true;",
            fails = "the client reports itself on target yet points elsewhere than its command",
            on = "2026-10-20")
    @Test
    public void theBearingTheServerCommandsArrivesAtTheClient() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int dim = plot().dim, x = site().x, y = site().y + 1, z = site().z;
        arrange("stellurgytest chunk warmup " + dim + " " + ((x - 16) >> 4) + " " + ((z - 16) >> 4) + " "
                + ((x + 16) >> 4) + " " + ((z + 16) >> 4)).requireOk("warm the site's chunks");
        clearAtEnd(x - 3, y - 1, z - 3, x + 5, y + 6, z + 3, "the turret and the floor beside it");
        arrange("stellurgytest fill " + dim + " " + (x - 3) + " " + (y - 1) + " " + (z - 3) + " " + (x + 3) + " "
                + (y + 6) + " " + (z + 3) + " minecraft:air").requireOk("clear the site");
        // Stand next to it, so the client is tracking this chunk and its tile.
        arrange("stellurgytest fill " + dim + " " + (x + 3) + " " + (y - 1) + " " + (z - 1) + " " + (x + 5) + " "
                + (y - 1) + " " + (z + 1) + " minecraft:stone").requireOk("a floor beside the turret");
        standOnFloorTheClientHolds(x + 4.5D, y, z + 0.5D, 0.0F, 0.0F, "he stands beside the turret");

        long placed = client.mark();
        long built = server.markInstrumented();
        place("stellurgy:turret", x, y, z);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", x, y + i, z);
        }
        Weapons.awaitAssembled(server, built, x, y, z, 4, "the gun never assembled");
        // The client's own copy of the tile ticks its mount; its first record is where it starts.
        String start = client.awaitRecordWithFields(placed, "turret_aim",
                "the client never ticked a turret tile here, so it has nothing to draw",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(x, y, z));
        double startYaw = Events.number(start, "yaw");

        // Point it hard to one side: a bearing the mount has to travel to, not one it is already at.
        long aimed = client.mark();
        long ordered = server.mark();
        aimTurret(x, y, z, x + 40.5D, y + 0.5D, z + 0.5D);

        String serverArrived = Weapons.awaitOnTarget(server, ordered, x, y, z,
                "the server's own mount never reached the bearing it was given, so there is nothing"
                        + " for the client to have been told");
        double commanded = Events.number(serverArrived, "commandedYaw");
        // "Has to travel" is production's own line: within the aim tolerance the mount already reports
        // itself on target without moving.
        requireArranged("the command is not a bearing the mount has to travel to (start " + startYaw
                + ", commanded " + commanded + ", tolerance " + TurretMechanism.AIM_TOLERANCE_DEGREES
                + "), so arriving at it would prove nothing",
                Math.abs(wrapDegrees(commanded - startYaw)) > TurretMechanism.AIM_TOLERANCE_DEGREES);

        String clientArrived = Weapons.awaitOnTarget(client, aimed, x, y, z,
                "the client's turret never turned onto its command: the server commanded a bearing "
                        + commanded + " and the client is still where it started (" + startYaw + ")."
                        + " A gun whose barrel does not move is a gun a player cannot read");
        assertEquals("the client turned, but onto a command that is not the one the server gave: "
                + clientArrived + " | server: " + serverArrived,
                commanded, Events.number(clientArrived, "commandedYaw"), 1.0E-9D);
        assertEquals("the client reports itself on target yet points elsewhere than its command: "
                + clientArrived, commanded, Events.number(clientArrived, "yaw"),
                TurretMechanism.AIM_TOLERANCE_DEGREES);
    }

    // ---- the weapon screens

    /**
     * The console's Hold Fire button makes the battery hold fire on the server — the gun, on target and
     * charged, answers its own hold question with {@code held:true}.
     *
     * <p>A player has no probe: the screen is the only way he has to hold fire, and what a button press
     * does is decided on the CLIENT — the screen's button hands the press to the client's copy of the tile.
     * So whether a press reaches the server is a question only a real client connected to a separate server
     * can ask. The verdict is a link on the server's own record of the thing the press was meant to change
     * ({@code turret_hold_decided}).</p>
     */
    @Pins("INV-GUN-11")
    @RedWitness(member = "TileWeaponConsole#onInventoryButtonPressed",
            at = "PacketHandler.sendToServer(new PacketMachine(this, NET_TOGGLE_HOLD_FIRE));",
            replacedBy = "",
            fails = "the console's Hold Fire button was pressed and the battery never held fire on the server",
            on = "2026-10-20")
    @Test
    public void theConsolesHoldFireButtonHoldsTheBatteryOnTheServer() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int dim = plot().dim, x = plot().x(GUI_SITE_DX), y = site().y + 1, z = site().z;
        prepareMachineRow(x, y, z);
        int gunX = x + 3, consoleX = x + 4;
        long built = server.markInstrumented();
        buildBarrelGun(gunX, y, z);
        Weapons.awaitAssembled(server, built, gunX, y, z, BARREL_GUN_PARTS, "the gun never assembled");
        place("stellurgy:weaponConsole", consoleX, y, z);
        chargeTurret(gunX, y, z);

        // The console's target is the arrangement, not the subject. The gun firing on it proves the
        // console commands the gun before any button.
        long ordered = server.mark();
        Reply applied = arrange("stellurgytest weaponconsole target " + dim + " " + consoleX + " " + y + " " + z
                + " " + (gunX + 40.5D) + " " + (y + 0.5D) + " " + (z + 0.5D)).requireOk("give the console a target");
        requireArranged("the console refused the target: " + applied, applied.bool("applied"));
        Weapons.awaitFired(server, ordered, gunX, y, z,
                "the gun never fired on the console's target, so a hold below would prove nothing");

        standBesideTheMachines(x, y, z);
        String screen = ClientGuiTestSupport.openGuiByRightClick(bot(), client, consoleX, y, z);
        requireArranged("right-clicking the console opened no screen: " + screen, !screen.isEmpty());

        long pressed = server.mark();
        bot().clickButtonById(CONSOLE_HOLD_FIRE_BUTTON);
        chargeTurret(gunX, y, z);
        server.awaitRecordWithFields(pressed, "turret_hold_decided",
                "the console's Hold Fire button was pressed and the battery never held fire on the"
                        + " server: the press never left the client",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(gunX, y, z), "held", "true");
        bot().closeScreen();
    }

    /**
     * The console's screen says what the SERVER's network says — its gun count and status — and follows it
     * when it changes: Hold Fire pressed, the screen says the network is holding.
     *
     * <p>The screen's readout is the client log's {@code client_console_readout}, written where the
     * server's readout is handed to the screen's lines. The status is checked against a WINDOW of two
     * server reads, one before the screen opened and one after its readout landed: a network's status may
     * legitimately move in between (a gun finishing its charge), and the screen is right if it shows either
     * of the states the server was in while it was being told.</p>
     */
    @Pins("INV-GUN-12")
    @RedWitness(verdict = "the screen is told the server's battery",
            member = "TileWeaponConsole#getModules",
            at = "modules.add(new ReadoutSync(this));",
            replacedBy = "",
            fails = "the console's screen never showed the server's battery",
            on = "2026-10-20")
    @RedWitness(verdict = "the status is the server's",
            member = "TileWeaponConsole#readoutTag",
            at = "tag.setString(\"status\", networkStatusKey());",
            replacedBy = "tag.setString(\"status\", \"msg.weaponConsole.status.unknown\");",
            fails = "the console's screen shows network status",
            on = "2026-10-20")
    @RedWitness(verdict = "the screen follows a change",
            member = "ReadoutSync#isUpdateRequired",
            at = "return world != null && !world.isRemote && !console.readoutTag().equals(sent);",
            replacedBy = "return world != null && !world.isRemote && !console.readoutTag().equals(sent) && sent == null;",
            fails = "Hold Fire was pressed and the console's screen never said the network is holding",
            on = "2026-10-20")
    @Test
    public void theConsolesScreenShowsTheServersNetwork() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int dim = plot().dim, x = plot().x(GUI_SITE_DX), y = site().y + 1, z = site().z;
        prepareMachineRow(x, y, z);
        int gunX = x + 3, consoleX = x + 4;
        long built = server.markInstrumented();
        buildBarrelGun(gunX, y, z);
        Weapons.awaitAssembled(server, built, gunX, y, z, BARREL_GUN_PARTS, "the gun never assembled");
        place("stellurgy:weaponConsole", consoleX, y, z);
        chargeTurret(gunX, y, z);
        String console = dim + " " + consoleX + " " + y + " " + z;
        Reply before = read("stellurgytest weaponconsole read " + console).requireOk("read the console on the server");
        requireArranged("the console is not commanding the one gun beside it on the server, so its"
                + " screen has nothing to show: " + before, before.bool("network") && before.integer("guns") == 1);

        standBesideTheMachines(x, y, z);
        long opened = client.mark();
        String screen = ClientGuiTestSupport.openGuiByRightClick(bot(), client, consoleX, y, z);
        requireArranged("right-clicking the console opened no screen: " + screen, !screen.isEmpty());
        String shown = client.awaitRecordWithFields(opened, "client_console_readout",
                "the console's screen never showed the server's battery — one gun, not holding",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(consoleX, y, z), "guns", "1", "holding", "false");
        Reply after = read("stellurgytest weaponconsole read " + console).requireOk("read the console again");
        String screenStatus = Events.text(shown, "status");
        assertTrue("the console's screen shows network status '" + screenStatus + "', which the server's"
                        + " network was in neither before the screen opened ('" + before.text("status")
                        + "') nor after its readout landed ('" + after.text("status") + "')",
                screenStatus.equals(before.text("status")) || screenStatus.equals(after.text("status")));

        long pressed = client.mark();
        bot().clickButtonById(CONSOLE_HOLD_FIRE_BUTTON);
        client.awaitRecordWithFields(pressed, "client_console_readout",
                "Hold Fire was pressed and the console's screen never said the network is holding",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(consoleX, y, z), "holding", "true");
        bot().closeScreen();
    }

    /**
     * The sensor's mode button switches the SERVER's sensor to illuminating — its next sweeps are taken in
     * {@code ACTIVE} mode. Same question as the console's button: the press is decided on the client, and
     * the mode it flips is read by the sweep on the server. The verdict is a link on the sensor sweeping in
     * the mode it was switched to ({@code sensor_swept}).
     */
    @Pins("INV-FCS-08")
    @RedWitness(member = "TileFireControlSensor#onInventoryButtonPressed",
            at = "PacketHandler.sendToServer(new PacketMachine(this, NET_TOGGLE_MODE));",
            replacedBy = "",
            fails = "the sensor's mode button was pressed and the sensor on the server never swept in ACTIVE mode",
            on = "2026-10-20")
    @Test
    public void theSensorsModeButtonSwitchesTheSensorOnTheServer() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int dim = plot().dim, x = plot().x(GUI_SITE_DX), y = site().y + 1, z = site().z;
        prepareMachineRow(x, y, z);
        int sensorX = x + 7;
        long placed = server.markInstrumented();
        place("stellurgy:fireControlSensor", sensorX, y, z);
        arrange("stellurgytest sensor charge " + dim + " " + sensorX + " " + y + " " + z).requireOk("charge the sensor");
        String passive = server.awaitRecordWithFields(placed, "sensor_swept",
                "the sensor never swept, so its mode was never decided on the server",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(sensorX, y, z));
        requireArranged("a fresh sensor is not listening, so switching it proves nothing: " + passive,
                "PASSIVE".equals(Events.text(passive, "mode")));

        standBesideTheMachines(x, y, z);
        String screen = ClientGuiTestSupport.openGuiByRightClick(bot(), client, sensorX, y, z);
        requireArranged("right-clicking the sensor opened no screen: " + screen, !screen.isEmpty());

        long pressed = server.mark();
        bot().clickButtonById(SENSOR_MODE_BUTTON);
        server.awaitRecordWithFields(pressed, "sensor_swept",
                "the sensor's mode button was pressed and the sensor on the server never swept in"
                        + " ACTIVE mode: the press never left the client",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(sensorX, y, z), "mode", "ACTIVE");
        bot().closeScreen();
    }

    // =====================================================================================================
    // What this class's scenarios share: instruments, each named for what it builds or reads.
    // =====================================================================================================

    // ---- undoing what a scenario left in the shared world

    /** One thing a scenario put into the shared world, and how it is taken back out. */
    private interface Undo {
        void run() throws Exception;
    }

    /**
     * What THIS scenario put into the world that a later scenario must not meet, newest first. Per
     * instance, and JUnit makes one instance per scenario, so it is this scenario's.
     */
    private final Deque<Object[]> owedAtEnd = new ArrayDeque<>();

    /** Declare {@code undo}, named {@code what}, owed when this scenario ends — declared before the thing it undoes can fail. */
    private void undoAtEnd(String what, Undo undo) {
        owedAtEnd.push(new Object[]{what, undo});
    }

    /**
     * Runs every undo the scenario declared, newest first, and fails the scenario naming each one that did
     * not run: a scenario that cannot give the world back owes the scenarios after it an explanation, and
     * a silent undo is one nobody can tell from no undo. Runs after the scenario's own verdict, so its
     * failure is reported beside that verdict, never instead of it.
     */
    @After
    public void undoWhatThisScenarioLeftInTheWorld() throws Exception {
        List<String> failed = new ArrayList<>();
        while (!owedAtEnd.isEmpty()) {
            Object[] owed = owedAtEnd.pop();
            try {
                ((Undo) owed[1]).run();
            } catch (Exception | AssertionError e) {
                failed.add(owed[0] + ": " + e);
            }
        }
        if (!failed.isEmpty()) {
            throw new AssertionError("this scenario could not give the shared world back, and the scenarios"
                    + " after it meet what it left: " + failed);
        }
    }

    /** Declare a box this scenario builds in, filled with air when it ends: what it built cannot act after it. */
    private void clearAtEnd(int x1, int y1, int z1, int x2, int y2, int z2, String what) {
        int dim = plot().dim;
        undoAtEnd(what + " filled with air", () -> arrange("stellurgytest fill " + dim + " " + x1 + " " + y1 + " "
                + z1 + " " + x2 + " " + y2 + " " + z2 + " minecraft:air").requireOk(what + " filled with air"));
    }

    /** Hold one chunk loaded for this scenario, and let it go when the scenario ends. */
    private void holdChunkUntilEnd(int dim, int cx, int cz) throws Exception {
        arrange("stellurgytest chunk forceload " + dim + " " + cx + " " + cz).requireOk("hold a chunk");
        undoAtEnd("chunk " + cx + "," + cz + " released", () -> arrange("stellurgytest chunk release " + dim + " "
                + cx + " " + cz).requireOk("release chunk " + cx + "," + cz));
    }

    // ---- building

    /** Place one block by probe; a block that does not place is a failed arrangement. */
    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = arrange("stellurgytest place " + plot().dim + " " + x + " " + y + " " + z + " " + block);
        requireArranged("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    /** How far into its plot a beam gun stands: room behind it, and its twenty-block range plus a wall ahead. */
    private static final int BEAM_GUN_DX = 8;
    /** How far east of a beam gun the block it burns into stands. */
    private static final int BEAM_TARGET_RANGE = 20;
    /** A beam gun's parts: three emitters and two cooling jackets; the controller is not a part. */
    private static final int BEAM_GUN_PARTS = 5;

    /**
     * The reference beam gun: a controller with three emitters on it and cooling around it, in a cleared
     * volume reaching from eight blocks behind it to forty ahead, every chunk of it held until the scenario
     * ends — and the whole volume filled with air then.
     */
    private void buildBeamGun(int bx, int y, int z) throws Exception {
        int dim = plot().dim;
        arrange("stellurgytest chunk warmup " + dim + " " + ((bx - 16) >> 4) + " " + ((z - 16) >> 4) + " "
                + ((bx + 48) >> 4) + " " + ((z + 16) >> 4)).requireOk("warm the gun's chunks");
        clearAtEnd(bx - 8, y - 2, z - 4, bx + 40, y + 12, z + 4, "the beam gun at " + bx);
        arrange("stellurgytest fill " + dim + " " + (bx - 8) + " " + (y - 2) + " " + (z - 4) + " "
                + (bx + 40) + " " + (y + 12) + " " + (z + 4) + " minecraft:air").requireOk("clear the beam gun's site");
        for (int cx = (bx - 16) >> 4; cx <= (bx + 40) >> 4; cx++) {
            holdChunkUntilEnd(dim, cx, z >> 4);
        }
        place("stellurgy:turret", bx, y, z);
        for (int i = 1; i <= 3; i++) {
            place("stellurgy:gunBeamEmitter", bx, y + i, z);
        }
        place("stellurgy:gunCooling", bx, y, z + 1);
        place("stellurgy:gunCooling", bx, y, z - 1);
    }

    /**
     * He stands four blocks east of the beam gun and three south of its line, on a floor his client
     * holds: beside the beam, close enough to see it, and out of its way — a floor under him on the
     * gun's own line would be the thing it burns into.
     */
    private void standBesideTheBeamGun(int bx, int y, int z) throws Exception {
        arrange("stellurgytest fill " + plot().dim + " " + (bx + 3) + " " + y + " " + (z + 3) + " " + (bx + 5) + " "
                + y + " " + (z + 4) + " minecraft:stone").requireOk("a floor beside the beam");
        standOnFloorTheClientHolds(bx + 4.5D, y + 1, z + 3.5D, 0.0F, 0.0F, "he stands beside the beam gun");
    }

    /** An iron wall twenty blocks east of the beam gun, and the gun ordered onto it. */
    private void aimAtAnIronWall(int bx, int y, int z) throws Exception {
        int wallX = bx + BEAM_TARGET_RANGE;
        arrange("stellurgytest fill " + plot().dim + " " + wallX + " " + y + " " + z + " " + (wallX + 5) + " " + y
                + " " + z + " minecraft:iron_block").requireOk("build the wall");
        aimTurret(bx, y, z, wallX + 0.5D, y + 0.5D, z + 0.5D);
    }

    /** A barrel gun's parts: four barrels and two cooling jackets; the controller is not a part. */
    private static final int BARREL_GUN_PARTS = 6;

    /** A controller with four barrels on it and a cooling jacket either side. */
    private void buildBarrelGun(int gx, int gy, int gz) throws Exception {
        place("stellurgy:turret", gx, gy, gz);
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", gx, gy + i, gz);
        }
        place("stellurgy:gunCooling", gx, gy, gz + 1);
        place("stellurgy:gunCooling", gx, gy, gz - 1);
    }

    /** How far east of the player a gun that is to shoot at him stands: not in his block, near enough to track. */
    private static final int GUN_OFFSET = 20;

    /**
     * He stands still on a floor his client holds, at {@code (px, py, pz)}, with the corridor between him
     * and a gun at {@code gx} cleared: the muzzle sits five and a half blocks along the aim, and the
     * line-of-fire check refuses a shot into terrain. Everything here is filled with air when the
     * scenario ends.
     */
    private void standInTheCorridor(int px, int py, int pz, int gx) throws Exception {
        int dim = plot().dim;
        arrange("stellurgytest chunk warmup " + dim + " " + ((px - 16) >> 4) + " " + ((pz - 16) >> 4) + " "
                + ((gx + 16) >> 4) + " " + ((pz + 16) >> 4)).requireOk("warm the site's chunks");
        clearAtEnd(px - 2, py - 1, pz - 2, gx + 4, py + 8, pz + 2, "the corridor, the battery and his floor");
        arrange("stellurgytest fill " + dim + " " + (px - 2) + " " + py + " " + (pz - 2) + " " + (gx + 4) + " "
                + (py + 8) + " " + (pz + 2) + " minecraft:air").requireOk("clear the corridor");
        arrange("stellurgytest fill " + dim + " " + (px - 1) + " " + (py - 1) + " " + (pz - 1) + " " + (px + 1) + " "
                + (py - 1) + " " + (pz + 1) + " minecraft:stone").requireOk("a floor for him");
        standOnFloorTheClientHolds(px + 0.5D, py, pz + 0.5D, 0.0F, 0.0F, "he stands still in the corridor");
    }

    /**
     * Air round a row of posts, a stone floor to stand on, and the chunks under it held: the posts are
     * shot before the player stands here, and a chunk nobody holds can be unloaded in between — the damage
     * engine then refuses the blow with TARGET_UNLOADED instead of landing it.
     */
    private void prepareRepairSite(int x, int y, int z) throws Exception {
        int dim = plot().dim;
        arrange("stellurgytest chunk warmup " + dim + " " + ((x - 16) >> 4) + " " + ((z - 16) >> 4) + " "
                + ((x + 16) >> 4) + " " + ((z + 16) >> 4)).requireOk("warm the site's chunks");
        for (int cx = (x - 3) >> 4; cx <= (x + 9) >> 4; cx++) {
            for (int cz = (z - 4) >> 4; cz <= (z + 3) >> 4; cz++) {
                holdChunkUntilEnd(dim, cx, cz);
            }
        }
        clearAtEnd(x - 3, y - 1, z - 4, x + 9, y + 5, z + 3, "the posts and their floor");
        arrange("stellurgytest fill " + dim + " " + (x - 3) + " " + y + " " + (z - 4) + " " + (x + 9) + " " + (y + 5)
                + " " + (z + 3) + " minecraft:air").requireOk("clear the site");
        Reply floor = arrange("stellurgytest fill " + dim + " " + (x - 3) + " " + (y - 1) + " " + (z - 4) + " "
                + (x + 9) + " " + (y - 1) + " " + (z + 3) + " minecraft:stone").requireOk("lay the floor");
        requireArranged("the floor placed nothing: " + floor, floor.integer("placed") > 0);
    }

    /** One stone block on the floor, the thing a weapon will empty. */
    private void post(int x, int y, int z) throws Exception {
        arrange("stellurgytest fill " + plot().dim + " " + x + " " + y + " " + z + " " + x + " " + y + " " + z
                + " minecraft:stone").requireOk("place a post");
    }

    /** Destroy the post at {@code x} with exactly one block's worth, straight down from above. */
    private void destroyPost(int x, int y, int z, long impactId) throws Exception {
        Reply priced = damageStage(x, y, z);
        int oneBlock = priced.integer("stageCost") * Math.max(1, priced.integer("maxStage"));
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = act("stellurgytest damage impact " + plot().dim + " " + (x + 0.5D) + " " + (y + 3.5D) + " "
                + (z + 0.5D) + " 0 -1 0 " + oneBlock + " KINETIC " + impactId).requireOk("destroy the post");
        Reply hole = damageStage(x, y, z);
        requireArranged("the post at " + x + " was not destroyed into a recorded hole, so there is no hole"
                + " to fill: " + hole + " after " + shot,
                hole.bool("wasDestroyed") && "minecraft:air".equals(hole.text("block")));
    }

    /**
     * Damage the post at {@code x} by two stages' worth — the {@code damage stage} verb reports what one
     * stage of it costs the engine — from above, and answer its stage, which is read back and must leave
     * it damaged and standing.
     */
    private int damagePost(int x, int y, int z, long impactId) throws Exception {
        Reply priced = damageStage(x, y, z);
        int twoStages = priced.integer("stageCost") * 2;
        requireArranged("the post has no price, so it cannot be damaged by a known amount: " + priced,
                twoStages > 0);
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = act("stellurgytest damage impact " + plot().dim + " " + (x + 0.5D) + " " + (y + 3.5D) + " "
                + (z + 0.5D) + " 0 -1 0 " + twoStages + " KINETIC " + impactId).requireOk("damage the post");
        Reply damaged = damageStage(x, y, z);
        requireArranged("the post at " + x + " must be damaged and standing: " + damaged + " after " + shot,
                damaged.integer("stage") >= 1 && damaged.integer("stage") < damaged.integer("maxStage")
                        && "minecraft:stone".equals(damaged.text("block")));
        return damaged.integer("stage");
    }

    /** Where he stands for a repair: on the floor, two blocks in front of the posts, within reach. */
    private void standBesideThePosts(int x, int y, int z) throws Exception {
        standOnFloorTheClientHolds(x + 4.5D, y, z - 2.5D, 0.0F, 30.0F, "he stands in front of the posts");
    }

    /** Where the weapon screens' machines stand in their plot: room for the console's target forty blocks east. */
    private static final int GUI_SITE_DX = 3;

    /** Air round a row of machines and the lane east of it, a stone floor to stand on, the chunks held. */
    private void prepareMachineRow(int x, int y, int z) throws Exception {
        int dim = plot().dim;
        arrange("stellurgytest chunk warmup " + dim + " " + ((x - 16) >> 4) + " " + ((z - 16) >> 4) + " "
                + ((x + 64) >> 4) + " " + ((z + 16) >> 4)).requireOk("warm the site's chunks");
        clearAtEnd(x - 3, y - 1, z - 4, x + 60, y + 8, z + 3, "the machines, their lane and their floor");
        arrange("stellurgytest fill " + dim + " " + (x - 3) + " " + y + " " + (z - 4) + " " + (x + 60) + " "
                + (y + 8) + " " + (z + 3) + " minecraft:air").requireOk("clear the site");
        arrange("stellurgytest fill " + dim + " " + (x - 3) + " " + (y - 1) + " " + (z - 4) + " " + (x + 12) + " "
                + (y - 1) + " " + (z + 3) + " minecraft:stone").requireOk("lay the floor");
        for (int cx = (x - 16) >> 4; cx <= (x + 64) >> 4; cx++) {
            holdChunkUntilEnd(dim, cx, z >> 4);
        }
    }

    /** On the floor, a couple of blocks in front of the row, within reach of every block in it. */
    private void standBesideTheMachines(int x, int y, int z) throws Exception {
        standOnFloorTheClientHolds(x + 4.5D, y, z - 2.5D, 0.0F, 30.0F, "he stands in front of the machines");
    }

    /** The console's hold-fire button, as the console's own screen numbers it. */
    private static final int CONSOLE_HOLD_FIRE_BUTTON = 0;
    /** The sensor's mode button, as the sensor's own screen numbers it. */
    private static final int SENSOR_MODE_BUTTON = 0;

    // ---- the guns and the sensor

    private void aimTurret(int x, int y, int z, double tx, double ty, double tz) throws Exception {
        arrange("stellurgytest turret target " + plot().dim + " " + x + " " + y + " " + z + " "
                + tx + " " + ty + " " + tz).requireOk("aim the gun");
    }

    private void chargeTurret(int x, int y, int z) throws Exception {
        arrange("stellurgytest turret charge " + plot().dim + " " + x + " " + y + " " + z).requireOk("charge the gun");
    }

    private Reply readTurret(int x, int y, int z) throws Exception {
        return read("stellurgytest turret read " + plot().dim + " " + x + " " + y + " " + z);
    }

    /**
     * How many sweeps each leg of the sensor's friend-or-foe is watched for. More than one, so a single
     * sweep that happened to miss the player for an unrelated reason cannot pass the friend leg alone; the
     * control leg is held to the same count, which is what makes the two windows equal.
     */
    private static final int SWEEPS = 3;

    /** The first {@link #SWEEPS} sweeps of the sensor at {@code (x,y,z)} recorded since {@code mark}. */
    private List<String> sweeps(Events server, long mark, int x, int y, int z) throws Exception {
        String swept = server.awaitMatching(mark, "sensor_swept",
                reply -> Events.recordsWhere(reply, "pos", Weapons.at(x, y, z)).size() >= SWEEPS,
                "at the sensor, " + SWEEPS + " times", "the sensor did not sweep " + SWEEPS
                        + " times, so the list it keeps was never re-decided in this window",
                Weapons.SUBJECT_TICKS);
        return Events.recordsWhere(swept, "pos", Weapons.at(x, y, z)).subList(0, SWEEPS);
    }

    /** The names a sweep put in its contact list. */
    private static List<String> sweptNames(String sweep) {
        return Arrays.asList(String.valueOf(Events.text(sweep, "names")).split(","));
    }

    // ---- shots

    /** How far east of the player the near round, and the wall a round stops in, stand: inside his view. */
    private static final double SHOT_NEAR = 40.0D;

    /** He stands on three-by-three of stone at this scenario's site, his client holding it; answers where. */
    private double[] standOnAFloorOfHisOwn() throws Exception {
        int dim = plot().dim, x = site().x, y = site().y, z = site().z;
        clearAtEnd(x - 1, y, z - 1, x + 1, y, z + 1, "his floor");
        arrange("stellurgytest fill " + dim + " " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 1) + " " + y
                + " " + (z + 1) + " minecraft:stone").requireOk("a floor for him");
        double[] at = {x + 0.5D, y + 1, z + 0.5D};
        standOnFloorTheClientHolds(at[0], at[1], at[2], 0.0F, 0.0F, "he stands on his own floor");
        return at;
    }

    /** One round through production's own entry point; the id, or a failed arrangement. */
    private long fireRound(String spec) throws Exception {
        Reply fired = act("stellurgytest shot fire " + plot().dim + " " + spec).requireOk("fire a round");
        long id = fired.longInteger("id");
        requireArranged("the launch was refused, so nothing else here means anything: " + fired, id >= 0);
        return id;
    }

    // ---- damage

    private Reply damageStage(int x, int y, int z) throws Exception {
        return read("stellurgytest damage stage " + plot().dim + " " + x + " " + y + " " + z).requireOk("read a stage");
    }

    /** The attack key's code: mouse buttons enter {@code KeyBinding} as {@code -100 + button}, LMB 0. */
    private static final int KEY_ATTACK = -100;

    /** The one client tick whose start recomputes the crosshair from the rotation just applied. */
    private static final int CROSSHAIR_RECOMPUTE_TICKS = 1;

    /** How long the attack key is held: one client tick, as a mouse button is. */
    private static final int ATTACK_HELD_TICKS = 1;

    /**
     * The deadline for something one client-server round trip away — his pose reaching the server, a
     * click being handled, a slot write reaching him, a dig packet harvested. A round trip is a few ticks;
     * this is a deadline far past one, never an estimate of it.
     */
    private static final int ROUND_TRIP_DEADLINE_TICKS = 200;

    /** A standing player's eye height (EntityPlayer.getDefaultEyeHeight), which both sides add to posY for the line of sight. */
    private static final double EYE_HEIGHT = 1.62D;

    // ---- the linker

    /** Vanilla's default sneak binding, left shift. */
    private static final int SNEAK_KEY = 42;

    /** Vanilla's default use-item binding, the right mouse button ({@code GameSettings.keyBindUseItem}). */
    private static final int USE_ITEM_BUTTON = -99;

    /** How far out of the linker's site the working volume reaches: room for both targets east of the gun. */
    private static final int LINKER_HALO = 20;

    /** How far east of the gun the block he names stands. */
    private static final int LINKER_TARGET_RANGE = 22;

    /**
     * Vanilla's server-side reach for a click on a block: the reach attribute (5 in survival) plus 3 of
     * latency slack, {@code NetHandlerPlayServer#processTryUseItemOnBlock}. A target past it is one no click
     * on a block could ever name.
     */
    private static final double BLOCK_CLICK_REACH = 5.0D + 3.0D;

    /**
     * How close the server's look must be to the one set. At the 22-block range a hundredth of a degree
     * moves the line of sight by under 0.004 blocks — far inside the 0.05 the hit is checked to, and more
     * than twice the 0.004-degree difference measured between the look set and the look held (2026-10-04:
     * pitch 2.7286277 set, 2.7246094 held, yaw -90.0 exact).
     */
    private static final double LOOK_TOLERANCE_DEGREES = 0.01D;

    /** An adult zombie's height ({@code EntityZombie}'s own size), for its body centre — the point a gun follows on a creature it tracks. */
    private static final double ZOMBIE_HEIGHT = 1.95D;

    private static final long ZOMBIE_UUID_MOST = 0x5a11e5ab1e000615L;
    private static final long ZOMBIE_UUID_LEAST = 0x8000000000000615L;
    /** A constant: a String. */
    private static final String ZOMBIE_UUID = new java.util.UUID(ZOMBIE_UUID_MOST, ZOMBIE_UUID_LEAST).toString();

    /**
     * Waits for a record of the gun at {@code (x,y,z)} that is LATER than {@code afterSeq} — the
     * designation that is to have caused it — optionally carrying {@code field = value}.
     */
    private static void awaitGun(Events server, long mark, long afterSeq, int x, int y, int z,
                                 String type, String field, String value, String what) throws Exception {
        String[] pairs = field == null ? new String[]{"pos", Weapons.at(x, y, z)}
                : new String[]{"pos", Weapons.at(x, y, z), field, value};
        server.awaitMatching(mark, type,
                seen -> !laterThan(Events.recordsWhereAll(seen, pairs), afterSeq).isEmpty(),
                "carrying " + Arrays.toString(pairs) + " later than seq " + afterSeq, what,
                Weapons.SUBJECT_TICKS);
    }

    /**
     * One press of the right mouse button through the client's input pipeline: the key edge vanilla's
     * keybind poll turns into {@code rightClickMouse()}, which then decides from its own pick whether this
     * is a click on a block, on a creature, or a use of the held item. Released at once, so the held-button
     * repeat never adds a second click.
     */
    private void rightClick() throws Exception {
        bot().setKey(USE_ITEM_BUTTON, true);
        bot().setKey(USE_ITEM_BUTTON, false);
    }

    /**
     * His crosshair rests on nothing within reach, which is what sends vanilla's right-click down the
     * item's own use path rather than onto a block or a creature: the target is named by the linker's long
     * trace, never by the click's pick.
     */
    private void requireNothingInReach(String target) throws Exception {
        JsonObject pick = bot().reportMouseOver();
        requireArranged("looking at " + target + ", nothing within reach may be under his crosshair, or the"
                + " click is not a use of the linker: " + pick, "MISS".equals(pick.get("typeOfHit").getAsString()));
    }

    /**
     * Presses or releases sneak, and waits for the SERVER to hold it: the click that follows is decided on
     * the server's idea of whether he is sneaking, and travels in a different packet.
     */
    private void setSneaking(Events server, boolean sneaking) throws Exception {
        long pressed = server.mark();
        bot().setKey(SNEAK_KEY, sneaking);
        server.awaitMatching(pressed, "player_pose_received",
                seen -> !Events.recordsWhereAll(seen, "who", PLAYER, "sneaking", String.valueOf(sneaking)).isEmpty(),
                "sneaking " + sneaking, "the server must hold his sneak before he clicks", ROUND_TRIP_DEADLINE_TICKS);
    }

    /**
     * Turns his head to look from his eye at {@code point}, and waits for the SERVER to hold that look: the
     * item-use packet carries no rotation, so a click sent before the look arrives is traced along his
     * previous one.
     *
     * <p>And then one client tick more, before anything reads his crosshair. The crosshair is traced
     * along his HEAD's yaw ({@code EntityLivingBase#getLook} reads {@code rotationYawHead} and, in every
     * frame between two ticks, interpolates from {@code prevRotationYawHead}); the turn moves neither. The
     * head takes the new yaw in the tick that also sends the pose the server confirmed
     * ({@code EntityPlayer#updateEntityActionState}), and its previous value only at the START of the
     * tick after it ({@code EntityLivingBase#onEntityUpdate}). Measured 2026-10-20, 2 of 5 runs without
     * it: his state held yaw -146.3 / pitch 31.9 while the crosshair was traced at the pitch and at a yaw
     * of about -90, part way between the two, and landed on the floor beside him.</p>
     *
     * @return his eye, as the look was computed from it
     */
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(HEAD_YAW_CATCH_UP_TICKS);",
            why = "the look is a stimulus that lands in the head across two client ticks: the head yaw in the tick"
                    + " that sent the confirmed pose, its previous value at the start of the next one. One tick"
                    + " after the confirmation the crosshair is traced along the look and nothing else.")
    private double[] lookAt(Events server, double[] point) throws Exception {
        JsonObject state = bot().reportState();
        double[] eye = {state.get("playerX").getAsDouble(), state.get("playerY").getAsDouble() + EYE_HEIGHT,
                state.get("playerZ").getAsDouble()};
        double dx = point[0] - eye[0], dy = point[1] - eye[1], dz = point[2] - eye[2];
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        long turned = server.mark();
        bot().setLook(yaw, pitch);
        server.awaitMatching(turned, "player_pose_received",
                seen -> holdsLook(seen, yaw, pitch),
                "yaw " + yaw + " pitch " + pitch, "the server must hold his look before he clicks",
                ROUND_TRIP_DEADLINE_TICKS);
        bot().waitWorldTicks(HEAD_YAW_CATCH_UP_TICKS);
        return eye;
    }

    /** The one client tick after a confirmed look whose start gives the head's previous yaw the look too. */
    private static final int HEAD_YAW_CATCH_UP_TICKS = 1;

    /** Within {@link #LOOK_TOLERANCE_DEGREES}: the look the server holds is not bit-identical to the one set. */
    private static boolean holdsLook(String sinceReply, float yaw, float pitch) {
        for (String record : Events.recordsWhere(sinceReply, "who", PLAYER)) {
            if (Math.abs(Events.number(record, "yaw") - yaw) < LOOK_TOLERANCE_DEGREES
                    && Math.abs(Events.number(record, "pitch") - pitch) < LOOK_TOLERANCE_DEGREES) {
                return true;
            }
        }
        return false;
    }

    private static List<String> laterThan(List<String> records, long seq) {
        List<String> later = new ArrayList<>();
        for (String record : records) {
            if (seqOf(record) > seq) {
                later.add(record);
            }
        }
        return later;
    }

    private static long seqOf(String record) {
        return (long) Events.number(record, "seq");
    }

    private static double[] targetOf(Reply turretRead) {
        return new double[]{turretRead.number("targetX"), turretRead.number("targetY"),
                turretRead.number("targetZ")};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        return wrapped <= -180.0D ? wrapped + 360.0D : (wrapped > 180.0D ? wrapped - 360.0D : wrapped);
    }
}
