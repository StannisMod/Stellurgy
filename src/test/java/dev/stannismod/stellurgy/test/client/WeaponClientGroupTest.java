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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Weapons as a real client sees and drives them: beam, shot and aim replication, the friend-or-foe
 * decision on a player who carries a credential, the weapon screens' buttons, the linker, and the two
 * repairs a player's hand makes. One server and one client for every scenario.
 *
 * <p><b>Why a group of its own.</b> No existing client group holds the weapon cluster, and every
 * scenario here needs a real client for the same reason: what is asked is either what a packet tells
 * the client (a beam, a round, a bearing, a readout), or what only a PLAYER can do — carry a code, press
 * a screen's button, click through a linker, place or break a block by hand. A mechanics test, not an
 * e2e: each arranges the weapon's state by probe.</p>
 *
 * <p><b>Where each stands, and what one world costs.</b> Each scenario builds on its own plot of the
 * open-air lane the base allocates, and stands the player on a floor it lays and his client holds —
 * the plot is in open air, so a placement without one is a fall. Two scenarios need something FAR from
 * the player (a gun and a round four kilometres off, the control of a visibility filter); those are put
 * four thousand blocks off the lane in Z, which runs along X, so no plot of this class reaches them.
 * Whatever acts on its own tick — a gun, a sensor, a console, the zombie a linker names — is taken away
 * when the scenario ends ({@link #takeDownWhatActsOnItsOwn}): a gun left standing with a charge and a
 * target would go on firing into the next scenario's player. A game rule a scenario changes is put back
 * by that scenario.</p>
 *
 * <p><b>What it does NOT see.</b> The input pipeline wherever a verb that names its target acts
 * ({@code rightClickBlock}, a button pressed by id); the path into each state, which a probe arranged;
 * and whether anything LOOKS right — a beam, a barrel, a flash — which stays a human's judgement.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WeaponClientGroupTest extends AbstractSharedClientTest {

    private static final int DIM = 0;

    /** The harness's single client always joins under this name. */
    private static final String PLAYER = "ForgeTestClient";

    /**
     * How far off the lane the far half of a visibility control stands: far outside the default
     * 256-block visibility radius ({@code shotVisibilityRadius}), and in Z, where the lane — plots
     * along X — has none.
     */
    private static final int FAR = 4_000;

    /** A standing player's eye height ({@code EntityPlayer.getDefaultEyeHeight}). */
    private static final double EYE_HEIGHT = 1.62D;

    /** Controller + three emitters + two cooling jackets; the controller is not a part. */
    private static final int BEAM_GUN_PARTS = 5;

    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int BARREL_GUN_PARTS = 6;

    /**
     * How often a beam gun's supply is looked at while a wait runs, in ticks. A starved beam goes dark
     * and saves up a one-second quantum before it relights ({@code TileTurret#BEAM_QUANTUM_TICKS}, 20),
     * so looking more often than that buys nothing.
     */
    private static final int SUPPLY_LOOK_EVERY_TICKS = 20;

    /**
     * The deadline for something one client-server round trip away — a placement, a pose, a click. A
     * few ticks when healthy; a deadline far past that, never an estimate of it.
     */
    private static final int ROUND_TRIP_TICKS = 200;

    /** What this scenario built that acts on its own tick, as the commands that take it away. */
    private final List<String> takeDown = new ArrayList<>();

    @Override
    protected String subsystem() {
        return "weapons";
    }

    /**
     * Every gun, sensor, console and creature this scenario left standing goes before the next one
     * runs. Not on a dead harness: there is nobody to send a command to, and the scope reports the
     * death itself.
     */
    @After
    public void takeDownWhatActsOnItsOwn() throws Exception {
        if (scope() == null || scope().harnessDead) {
            return;
        }
        for (String command : takeDown) {
            arrange(command);
        }
    }

    // ===================================================================================== beams

    /**
     * A beam a mirror turned is DRAWN turned, and not straight through the mirror that turned it.
     *
     * <p>The server resolves a bent beam as a path with a corner in it. Everything about that is
     * invisible from the client's side unless the corner crosses the wire: a beam sent as two ends is
     * still one beam, still drawn, still counted — and drawn as a laser passing clean through a plate
     * that is, in fact, reflecting it. So "a beam is drawn" cannot see this; what is read is the
     * client's own record of what it drew, {@code client_beam_drawn}, whose {@code points} and
     * {@code bent} come off the path the renderer holds.</p>
     *
     * <p>The control leg is the ordinary beam. A gun burning into an iron wall must be drawn with NO
     * corner: without that half, a bug that reported every beam as bent would pass.</p>
     */
    @RedWitness(member = "PacketBeamState#of", at = "for (Vec3d point : path) {",
            replacedBy = "for (Vec3d point : java.util.Arrays.asList(path.get(0), path.get(path.size() - 1))) {",
            fails = "with a mirror in the beam's way the client is still drawing a straight line",
            on = "2026-10-16")
    @Wait(shape = Wait.Shape.STIMULUS, at = "String drawn = client.awaitMatching(straight, \"client_beam_drawn\",",
            why = "the link is client_beam_drawn itself, and it is what this waits on; the act between its reads is"
                    + " the gun's supply, refilled only when the gun reports it has gone dark to save up"
                    + " (beamRecharging) -- the weapon network has no source a player could wire, so the probe is"
                    + " the supply, and a gun left starved would go dark across the wait")
    @Wait(shape = Wait.Shape.STIMULUS, at = "client.awaitMatching(mirrored, \"client_beam_drawn\",",
            why = "as the straight leg: client_beam_drawn is the link, and the supply refills the gun only when it"
                    + " reports it is saving up; a gun the reflected beam wore away answers no reading and is not"
                    + " fed")
    @Pins("MECH-GUN-37")
    @Test
    public void aBeamTurnedByAMirrorReachesTheClientWithItsCornerInIt() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        FixtureSite site = site();
        final int gx = plot().x(10), y = site.y, z = site.z;
        int targetX = gx + 20;

        long built = server.markInstrumented();
        buildBeamGun(gx, y, z);
        standBeside(gx + 4, y, z + 3, "the player stands beside the gun, out of its line of fire");
        Weapons.awaitAssembled(server, built, gx, y, z, BEAM_GUN_PARTS, "the beam gun never assembled");

        // Leg one, the control: plain iron. A beam that meets it is a straight line to its end.
        arrange("stellurgytest fill " + DIM + " " + targetX + " " + y + " " + z + " " + (targetX + 5) + " "
                + y + " " + z + " minecraft:iron_block").requireOk("build the iron wall");
        long straight = client.mark();
        arrange(turretCommand("target", gx, y, z) + " " + (targetX + 0.5D) + " " + (y + 0.5D) + " "
                + (z + 0.5D)).requireOk("aim the gun");
        String drawn = client.awaitMatching(straight, "client_beam_drawn",
                reply -> !Events.recordsWhere(reply, "pos", Weapons.at(gx, y, z)).isEmpty(),
                "for this gun", "the beam never reached the client at all, so nothing here measured"
                        + " how it is drawn: " + read(turretCommand("read", gx, y, z)), Weapons.SUBJECT_TICKS,
                () -> {
                    Reply gun = read(turretCommand("read", gx, y, z));
                    if (gun.ok() && gun.bool("beamRecharging")) {
                        arrange("stellurgytest turret charge " + DIM + " " + gx + " " + y + " " + z);
                    }
                }, SUPPLY_LOOK_EVERY_TICKS);
        String first = Events.recordsWhere(drawn, "pos", Weapons.at(gx, y, z)).get(0);
        assertEquals("a beam burning into a plain iron wall was drawn with a corner in it. Nothing "
                + "turned it, so either the server invented a bend or the client is calling every "
                + "beam bent — and the real assertion below would then pass for that reason "
                + "rather than for the mirror: " + first, "false", Events.text(first, "bent"));

        // Leg two: swap the iron the beam is standing on for a mirror. Same gun, same aim, same
        // distance — the ONE thing that changes is what the beam meets.
        long mirrored = client.mark();
        arrange("stellurgytest fill " + DIM + " " + targetX + " " + y + " " + z + " " + (targetX + 5) + " "
                + y + " " + z + " minecraft:air").requireOk("take the iron away");
        place("stellurgy:mirrorPlatingGold", targetX, y, z);
        place("minecraft:iron_block", targetX + 1, y, z);

        client.awaitMatching(mirrored, "client_beam_drawn",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(gx, y, z), "bent", "true")
                        .isEmpty(),
                "for this gun, bent", "with a mirror in the beam's way the client is still drawing a"
                        + " straight line: the corner never crossed the wire, so a player watching"
                        + " this sees a laser going clean through a plate that is reflecting it. "
                        + read(turretCommand("read", gx, y, z)), Weapons.SUBJECT_TICKS,
                () -> {
                    // A gun the reflected beam wore away answers no reading, and is not fed: that is
                    // the arrangement working (the beam comes back down its own line square-on).
                    Reply gun = read(turretCommand("read", gx, y, z));
                    if (gun.ok() && gun.bool("beamRecharging")) {
                        arrange("stellurgytest turret charge " + DIM + " " + gx + " " + y + " " + z);
                    }
                }, SUPPLY_LOOK_EVERY_TICKS);
    }

    /**
     * Whether a burning beam is visible to the player standing next to the gun.
     *
     * <p>A held beam is not an entity, not a block and not a particle: it is a line the server
     * resolves every tick and forgets. Vanilla replicates none of that, so "you can see the gun
     * burning" is entirely a claim about a packet channel, and the only place that claim can be
     * checked is a real client. What is asserted is what the renderer draws FROM — the client's own
     * tracker of burning beams, as its {@code client_beam_drawn} record.</p>
     *
     * <p><b>Why the far half is evidence.</b> A beam burning four kilometres away must NOT arrive, or
     * the filter that keeps every battery in the world off every connection is doing nothing. "Nothing
     * arrived" is only a reading once two things are established. The far gun really burned — its own
     * {@code turret_beam} edge says it lit, and a gun announces the tick it lights. And that
     * announcement had time to arrive: the far gun lit BEFORE the near one (the server log orders the
     * two edges), and every packet the server sends this player travels one ordered connection, so a
     * far packet sent at the far gun's lighting would have been drawn before the near gun's first
     * packet — which the client DID draw. The near half is the far half's instrument check as well as
     * its own claim.</p>
     */
    @RedWitness(member = "ProximityBroadcast#sendNearSegment",
            at = "if (distanceSqToSegment(player.posX, player.posY, player.posZ, from, to) > radiusSq) {",
            replacedBy = "if (false) {",
            fails = "a beam burning four kilometres away was replicated to this client anyway",
            on = "2026-10-16")
    @Wait(shape = Wait.Shape.STIMULUS, at = "String farLit = server.awaitMatching(burning, \"turret_beam\",",
            why = "the link is the far gun's own lit edge on turret_beam, and it is what this waits on; the act"
                    + " between its reads is the gun's supply, refilled only when the gun reports it has gone dark"
                    + " to save up -- the weapon network has no source a player could wire")
    @Wait(shape = Wait.Shape.STIMULUS, at = "String nearLit = server.awaitMatching(burning, \"turret_beam\",",
            why = "the link is the near gun's lit edge; both guns' supplies are refilled only when each reports it"
                    + " is saving up, so the far gun stays lit for the whole of the near gun's window")
    @Pins({"MECH-GUN-34", "MECH-SHOT-29"})
    @Test
    public void aBeamBurningNearbyIsDrawnByTheClientAndOneFourKilometresAwayIsNot() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        FixtureSite site = site();
        final int nx = plot().x(10), y = site.y, nz = site.z;
        final int fx = nx, fz = nz + FAR;

        long built = server.markInstrumented();
        buildBeamGun(fx, y, fz);
        buildBeamGun(nx, y, nz);
        // The player stands beside the near gun for the whole scenario, so which gun is in range
        // never changes while anything burns.
        standBeside(nx + 4, y, nz + 3, "the player stands beside the near gun, out of its line of fire");
        Weapons.awaitAssembled(server, built, fx, y, fz, BEAM_GUN_PARTS, "the far beam gun never assembled");
        Weapons.awaitAssembled(server, built, nx, y, nz, BEAM_GUN_PARTS, "the near beam gun never assembled");

        long clientMark = client.mark();
        long burning = server.mark();
        aimAlongTheWall(fx, y, fz);
        arrange(turretCommand("charge", fx, y, fz)).requireOk("charge the far gun");
        String farLit = server.awaitMatching(burning, "turret_beam",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(fx, y, fz), "lit", "true")
                        .isEmpty(),
                "at the far gun, lit", "the far gun never burned at all, so this run proves nothing"
                        + " about the filter", Weapons.SUBJECT_TICKS,
                () -> {
                    Reply far = read(turretCommand("read", fx, y, fz));
                    if (far.ok() && far.bool("beamRecharging")) {
                        arrange("stellurgytest turret charge " + DIM + " " + fx + " " + y + " " + fz);
                    }
                }, SUPPLY_LOOK_EVERY_TICKS);

        // Now the one the player is standing beside. Both are kept supplied: a gun with no supply
        // burns its buffer down and goes dark to save up, and the point here is the packet, not the
        // duty cycle.
        aimAlongTheWall(nx, y, nz);
        arrange(turretCommand("charge", nx, y, nz)).requireOk("charge the near gun");
        String nearLit = server.awaitMatching(burning, "turret_beam",
                reply -> !Events.recordsWhereAll(reply, "pos", Weapons.at(nx, y, nz), "lit", "true")
                        .isEmpty(),
                "at the near gun, lit", "the near gun never burned, so nothing below is about"
                        + " replication", Weapons.SUBJECT_TICKS,
                () -> {
                    Reply far = read(turretCommand("read", fx, y, fz));
                    if (far.ok() && far.bool("beamRecharging")) {
                        arrange("stellurgytest turret charge " + DIM + " " + fx + " " + y + " " + fz);
                    }
                    Reply near = read(turretCommand("read", nx, y, nz));
                    if (near.ok() && near.bool("beamRecharging")) {
                        arrange("stellurgytest turret charge " + DIM + " " + nx + " " + y + " " + nz);
                    }
                }, SUPPLY_LOOK_EVERY_TICKS);
        long farSeq = (long) Events.number(
                Events.recordsWhereAll(farLit, "pos", Weapons.at(fx, y, fz), "lit", "true").get(0), "seq");
        long nearSeq = (long) Events.number(
                Events.recordsWhereAll(nearLit, "pos", Weapons.at(nx, y, nz), "lit", "true").get(0), "seq");
        assertTrue("the far gun did not light before the near one (far seq " + farSeq + ", near seq "
                + nearSeq + "), so its announcement is not guaranteed to precede the near gun's on the"
                + " connection and the silence below would be about timing", farSeq < nearSeq);

        client.awaitRecordWithFields(clientMark, "client_beam_drawn",
                "a beam burning beside the player never reached the client: a held beam is a server-side"
                        + " line, so a client that is not told about one cannot draw it and the weapon fires"
                        + " invisibly. gun=" + read(turretCommand("read", nx, y, nz)),
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(nx, y, nz));

        String drawn = client.since(clientMark, "client_beam_drawn");
        Events.assertInstrumentRan(drawn, "client_beam_events", "no far beam was drawn");
        assertTrue("a beam burning four kilometres away was replicated to this client anyway — the"
                + " visibility filter is not filtering, and every gun in the world would be drawn on"
                + " every connection: " + drawn,
                Events.recordsWhere(drawn, "pos", Weapons.at(fx, y, fz)).isEmpty());
    }

    // ===================================================================================== rounds

    /**
     * Whether a fired round reaches the person it is fired near.
     *
     * <p>A shot is a server-side record: no entity, no chunk, nothing vanilla replicates on its own.
     * So "you can see the gun firing" is a claim about a packet, checkable on the real client — the
     * client's own tracker records every round it is handed to draw ({@code client_shot_drawn}), on the
     * client thread, from the packets themselves.</p>
     *
     * <p>The control is the far round: fired FIRST, the near one second, down the one ordered
     * connection this player has, so a spawn packet for the far round would have been drawn before the
     * near round's — which the client did draw.</p>
     */
    @RedWitness(member = "ProximityBroadcast#sendNearSegment",
            at = "if (distanceSqToSegment(player.posX, player.posY, player.posZ, from, to) > radiusSq) {",
            replacedBy = "if (false) {",
            fails = "a round fired four kilometres away was replicated to this client anyway",
            on = "2026-10-16")
    @Pins("MECH-SHOT-14")
    @Test
    public void aRoundFiredNearbyIsDrawnByTheClientAndOneFiredFarAwayIsNot() throws Exception {
        Events client = clientEvents();
        FixtureSite site = site();
        double px = plot().x(4) + 0.5D, py = site.y, pz = site.z + 0.5D;
        standOnAPad(px, py, pz, "the player stands where both rounds are fired from");

        long mark = client.mark();
        // The control first. Without it the test would pass just as well against a replication
        // layer that told everybody about everything.
        long distant = fire((px + FAR) + " " + py + " " + (pz + FAR) + " 4 0 4 2000 200");
        // Fired 40 blocks away, across the player's view. The launch is production's own entry
        // point — the same call a turret makes.
        long near = fire((px + 40.0D) + " " + py + " " + pz + " 0 0 4 2000 200");

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
     * A round that stops in a wall in front of the player is told to the client as ENDED, for the
     * reason it ended — so the drawing stops at the wall and the flash is drawn where it hit. A round
     * whose end never reaches the client is drawn flying on through the wall it stopped in.
     */
    @RedWitness(verdict = "the end is told", member = "ShotReplication#announceEnd",
            at = "if (world == null || world.isRemote || point == null) {", replacedBy = "if (true) {",
            fails = "a round that stopped in a wall beside the player was never told to the client as over",
            on = "2026-10-16")
    @RedWitness(verdict = "the reason is the structure", member = "ShotSubstrate#step",
            at = "shot.setPosition(structure.point.add(direction.scale(contact.distance)));"
                    + " shot.setVelocity(new Vec3d(0.0D, 0.0D, 0.0D)); return ShotEndReason.STRUCTURE_IMPACT;",
            replacedBy = "shot.setPosition(structure.point.add(direction.scale(contact.distance)));"
                    + " shot.setVelocity(new Vec3d(0.0D, 0.0D, 0.0D)); return ShotEndReason.EXPIRED;",
            fails = "the client was told the round ended, but not that it hit structure",
            on = "2026-10-16")
    @Pins("MECH-SHOT-13")
    @Test
    public void aRoundThatStopsNearbyIsToldToTheClientAsEnded() throws Exception {
        Events client = clientEvents();
        FixtureSite site = site();
        double px = plot().x(4) + 0.5D, py = site.y, pz = site.z + 0.5D;
        standOnAPad(px, py, pz, "the player stands in front of the wall the round will stop in");
        int wallX = (int) Math.floor(px + 40.0D);
        int wallY = (int) Math.floor(py), wallZ = (int) Math.floor(pz) + 6;
        arrange("stellurgytest fill " + DIM + " " + (wallX - 6) + " " + (wallY - 1) + " " + (wallZ - 1) + " "
                + (wallX + 2) + " " + (wallY + 1) + " " + (wallZ + 1) + " minecraft:air").requireOk("clear the lane");
        arrange("stellurgytest fill " + DIM + " " + wallX + " " + (wallY - 1) + " " + (wallZ - 1) + " " + (wallX + 2)
                + " " + (wallY + 1) + " " + (wallZ + 1) + " minecraft:stone").requireOk("build the wall");

        // A block and a half of this wall's own price, read off the wall: enough to bore in and run
        // out inside a three-block wall. Not less — a round that cannot buy a single stage LODGES in
        // the face and lives out its lifetime there, ending EXPIRED (measured 2026-09-29), which is a
        // different ending from the one this is about.
        Reply wall = read("stellurgytest damage stage " + DIM + " " + wallX + " " + wallY + " " + wallZ);
        int budget = (int) Math.round(wall.integer("stageCost") * Math.max(1, wall.integer("maxStage")) * 1.5D);
        assertTrue("the wall has no price, so no budget here means anything: " + wall, budget > 0);

        long mark = client.mark();
        // Four blocks short of the wall, flying at it at a block a tick.
        long id = fire((wallX - 4.0D) + " " + (wallY + 0.5D) + " " + (wallZ + 0.5D) + " 1 0 0 " + budget
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

    // ===================================================================================== aim

    /**
     * Whether a player can see which way a turret is pointing.
     *
     * <p>A block cannot be turned, so a turret's bearing exists only as numbers on the server and as a
     * drawing on the client. What is asserted is the state the renderer draws FROM — the client tile's
     * own mount, as its {@code turret_aim} record on the CLIENT log.</p>
     *
     * <p>The client runs the same traverse the server does, from the command it was sent. So the test
     * waits for the client's own mount to report itself ON its command — the arrival, not a sample of
     * the travel — and then compares the command the client holds with the one the server gave. A
     * static target is commanded once and sent once, so the two are the same number exactly.</p>
     */
    @RedWitness(verdict = "the client turns", member = "TileTurret#getUpdateTag",
            at = "nbt.setTag(\"mount\", mount);", replacedBy = "",
            fails = "the client's turret never turned onto its command", on = "2026-10-16")
    @RedWitness(verdict = "on target means pointing at the command", member = "TurretMechanism#isOnTarget",
            at = "return dYaw <= AIM_TOLERANCE_DEGREES && dPitch <= AIM_TOLERANCE_DEGREES;",
            replacedBy = "return true;",
            fails = "the client reports itself on target yet points elsewhere than its command",
            on = "2026-10-16")
    @Pins("MECH-GUN-18")
    @Test
    public void theBearingTheServerCommandsArrivesAtTheClient() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        FixtureSite site = site();
        int x = site.x, y = site.y, z = site.z;
        arrange("stellurgytest fill " + DIM + " " + (x - 3) + " " + (y - 1) + " " + (z - 3) + " " + (x + 3) + " "
                + (y + 6) + " " + (z + 3) + " minecraft:air").requireOk("clear the site");
        // Beside it, so the client is tracking this chunk and its tile.
        standBeside(x + 4, y, z, "the player stands next to the gun");

        long placed = client.mark();
        long built = server.markInstrumented();
        place("stellurgy:turret", x, y, z);
        takeDown.add(fillAir(x, y, z, x, y + 4, z));
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
        arrange(turretCommand("target", x, y, z) + " " + (x + 40.5D) + " " + (y + 0.5D) + " " + (z + 0.5D))
                .requireOk("aim the gun");

        String serverArrived = Weapons.awaitOnTarget(server, ordered, x, y, z,
                "the server's own mount never reached the bearing it was given, so there is nothing"
                        + " for the client to have been told");
        double commanded = Events.number(serverArrived, "commandedYaw");
        // "Has to travel" is production's own line: within the aim tolerance the mount already
        // reports itself on target without moving.
        requireArranged("the command is not a bearing the mount has to travel to (start " + startYaw
                + ", commanded " + commanded + ", tolerance " + TurretMechanism.AIM_TOLERANCE_DEGREES
                + "), so arriving at it would prove nothing",
                Math.abs(wrap(commanded - startYaw)) > TurretMechanism.AIM_TOLERANCE_DEGREES);

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

    // ===================================================================================== friend or foe

    /**
     * Whether a gun can tell a friend from a target.
     *
     * <p>The credential is CARRIED: an entity is friendly for exactly as long as it has the
     * installation's code on it, and the only entity that can carry one is a player — so the mechanic
     * is unreachable on a dedicated server with nobody on it.</p>
     *
     * <p>A gun that never fires passes "does not shoot friendlies" trivially. So the refusal is read
     * off the gun's own fire decision — taken on target, charged, and answered "friendly" — and the same
     * gun, pointed at the same player, is then seen to fire once the code stops matching.</p>
     */
    @RedWitness(member = "TileTurret#canFireNow", at = "return !targetIsFriendly()",
            replacedBy = "return true",
            fails = "the gun was PERMITTED to fire on a player carrying its own access code",
            on = "2026-10-16")
    @Pins({"MECH-GUN-21", "MECH-GUN-22"})
    @Test
    public void aGunHoldsFireOnAPlayerCarryingItsCodeAndFiresOnOneWhoIsNot() throws Exception {
        Events server = serverEvents();
        FixtureSite site = site();
        int px = site.x, py = site.y, pz = site.z;
        int gx = px + 20;
        // He stands still on his floor before the gun exists: a gun tracking a FALLING target pins its
        // elevation arc and stops firing, which is indistinguishable from the refusal this is about.
        standOnAPad(px + 0.5D, py, pz + 0.5D, "the player stands still on his floor, twenty blocks from the gun");
        // The whole corridor between him and the gun, not just the gun's footprint: the muzzle sits
        // `reach + 1.5` blocks along the aim — about five and a half blocks towards him — and the
        // line-of-fire check refuses a shot into terrain.
        arrange("stellurgytest fill " + DIM + " " + (px - 2) + " " + py + " " + (pz - 2) + " " + (gx + 4) + " "
                + (py + 8) + " " + (pz + 2) + " minecraft:air").requireOk("clear the corridor");
        long built = server.markInstrumented();
        buildBarrelGun(gx, py, pz);
        Weapons.awaitAssembled(server, built, gx, py, pz, BARREL_GUN_PARTS, "the gun never assembled");
        arrange(turretCommand("charge", gx, py, pz)).requireOk("charge the gun");
        arrange(turretCommand("code", gx, py, pz) + " ALPHA").requireOk("set the code");

        // He carries the installation's own code, and is therefore a friend.
        arrange("clear " + PLAYER).raw();
        arrange("stellurgytest arrange coded-item " + PLAYER + " ALPHA");
        long friend = server.mark();
        arrange(turretCommand("target-player", gx, py, pz) + " " + PLAYER).requireOk("point the gun at the player");

        // The fire decision is asked only once the mount is on its target: the record of it taken
        // with the player carrying the code is where the gun answers.
        String held = server.awaitRecordWithFields(friend, "turret_fire_decided",
                "the gun never took a fire decision on the player — it never got onto him, so its"
                        + " silence would be about geometry rather than the credential",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(gx, py, pz), "caller", "auto",
                "friendly", "true");
        assertEquals("the gun was PERMITTED to fire on a player carrying its own access code: " + held,
                "false", Events.text(held, "permitted"));
        assertTrue("the gun shot a player carrying its own access code: " + read(turretCommand("read", gx, py, pz)),
                Weapons.firedSince(server, friend, gx, py, pz).isEmpty());

        // Same gun, same player, same target — only the credential changes.
        long foe = server.mark();
        arrange("clear " + PLAYER);
        arrange("stellurgytest arrange coded-item " + PLAYER + " BRAVO");
        arrange(turretCommand("charge", gx, py, pz)).requireOk("recharge the gun");
        Weapons.awaitFired(server, foe, gx, py, pz,
                "the gun would not fire on a player carrying somebody else's code either — the hold"
                        + " was not about the credential: " + read(turretCommand("read", gx, py, pz)));
    }

    /**
     * Where a friend is spared: <b>before there is a target, not at the trigger.</b>
     *
     * <p>An ally never becomes a CONTACT, so his name is never written anywhere a gun could read it.
     * The sensor decides who is a contact once per sweep and records each sweep's list
     * ({@code sensor_swept}, with the contacts' names). So the friend is watched for exactly
     * {@link #SWEEPS} sweeps taken while he carried the code, and the control — the same player, the
     * same place, only the code changed — must appear within the same number of sweeps. A control
     * allowed longer than the subject proves acquisition EVENTUALLY, not within the window the friend
     * was watched for.</p>
     *
     * <p>"The battery fired at a player carrying its own code" is DOWNSTREAM of the friend-leg sweep
     * verdict: the gun's only source of a target here is the acquisition, so a round at the friend needs
     * the friend in a sweep, which that verdict reds first.</p>
     */
    @RedWitness(verdict = "the friend is never a contact", member = "TacticalScan#trackOf",
            at = "if (CodeUtils.entityHasMatchingCode(candidate, friendlyCode)) {", replacedBy = "if (false) {",
            fails = "a player carrying the installation's code entered the target list", on = "2026-10-16")
    @RedWitness(verdict = "the round is fired on an acquisition", member = "TileFireControlSensor#publish",
            at = "state.setAcquiredTrack(contacts.get(0), world.getTotalWorldTime(), hold);",
            replacedBy = "state.setTargetEntity(contacts.get(0).getEntity());",
            fails = "it fired, but not on an acquisition", on = "2026-10-16")
    @Pins("MECH-FCS-03")
    @Test
    public void aPlayerCarryingTheCodeNeverBecomesAContactAndOneWhoIsNotDoes() throws Exception {
        Events server = serverEvents();
        FixtureSite site = site();
        int px = site.x, py = site.y, pz = site.z;
        int bx = px + 20;
        int sx = bx + 1;

        // No creature may wander into the sweeps: the game rule is read first and put back at the end.
        Reply mobSpawning = read("stellurgytest gamerule get doMobSpawning").requireOk("read doMobSpawning");
        takeDown.add("gamerule doMobSpawning " + mobSpawning.text("value"));
        arrange("gamerule doMobSpawning false");

        standOnAPad(px + 0.5D, py, pz + 0.5D, "the player stands still on his floor, twenty blocks from the battery");
        // The whole corridor, not just the battery's footprint: the muzzle sits five and a half
        // blocks along the aim and the line-of-fire check refuses a shot into terrain.
        arrange("stellurgytest fill " + DIM + " " + (px - 2) + " " + py + " " + (pz - 2) + " " + (bx + 4) + " "
                + (py + 8) + " " + (pz + 2) + " minecraft:air").requireOk("clear the corridor");
        long built = server.markInstrumented();
        buildBarrelGun(bx, py, pz);
        place("stellurgy:fireControlSensor", sx, py, pz);
        takeDown.add(fillAir(sx, py, pz, sx, py, pz));
        Weapons.awaitAssembled(server, built, bx, py, pz, BARREL_GUN_PARTS,
                "the gun never assembled, so nothing below would mean anything");
        arrange(turretCommand("charge", bx, py, pz)).requireOk("charge the gun");
        arrange("stellurgytest sensor charge " + DIM + " " + sx + " " + py + " " + pz).requireOk("charge the sensor");
        arrange(turretCommand("code", bx, py, pz) + " ALPHA").requireOk("code the gun");
        arrange("stellurgytest sensor code " + DIM + " " + sx + " " + py + " " + pz + " ALPHA").requireOk("code the sensor");

        // He carries the installation's own code, and is therefore not a target at all.
        arrange("clear " + PLAYER).raw();
        arrange("stellurgytest arrange coded-item " + PLAYER + " ALPHA");
        long friend = server.mark();
        List<String> friendSweeps = sweeps(server, friend, sx, py, pz);
        for (String sweep : friendSweeps) {
            assertTrue("a player carrying the installation's code entered the target list: " + sweep,
                    !names(sweep).contains(PLAYER));
        }

        // Same player, same place, same battery. Only the code changes.
        arrange("clear " + PLAYER).raw();
        arrange("stellurgytest arrange coded-item " + PLAYER + " BRAVO");
        // Marked AFTER the new code is in his hand, exactly as the friend leg was: every sweep of
        // this window is one taken while he carried somebody else's code.
        long foe = server.mark();
        List<String> foeSweeps = sweeps(server, foe, sx, py, pz);
        boolean acquired = false;
        for (String sweep : foeSweeps) {
            acquired |= names(sweep).contains(PLAYER);
        }
        assertTrue("the sensor did not acquire the player carrying somebody else's code within the "
                + SWEEPS + " sweeps the friend was watched for — then the exclusion above was not"
                + " about the credential: " + foeSweeps, acquired);

        String fired = Weapons.awaitFired(server, foe, bx, py, pz,
                "the battery never fired on a contact its own sensor had acquired");
        assertEquals("it fired, but not on an acquisition: " + fired, "true",
                Events.text(fired, "acquired"));

        // Only now can the friend leg's silence be read: a gun with nobody to shoot never asks the
        // fire question, so its fire recorder is first proved live by the round just fired.
        List<String> atFriend = Weapons.firedSince(server, friend, bx, py, pz);
        atFriend.removeIf(round -> Events.number(round, "seq") >= foe);
        assertTrue("the battery fired at a player carrying its own code: " + atFriend,
                atFriend.isEmpty());
    }

    /**
     * How many sweeps each friend-or-foe leg is watched for. More than one, so a single sweep that
     * happened to miss the player for an unrelated reason cannot pass the friend leg alone; the control
     * leg is held to the same count, which is what makes the two windows equal.
     */
    private static final int SWEEPS = 3;

    /** The first {@link #SWEEPS} sweeps of the sensor at {@code (x,y,z)} since {@code mark}. */
    private static List<String> sweeps(Events server, long mark, int x, int y, int z) throws Exception {
        String swept = server.awaitMatching(mark, "sensor_swept",
                reply -> Events.recordsWhere(reply, "pos", Weapons.at(x, y, z)).size() >= SWEEPS,
                "at the sensor, " + SWEEPS + " times", "the sensor did not sweep " + SWEEPS
                        + " times, so the list it keeps was never re-decided in this window",
                Weapons.SUBJECT_TICKS);
        return Events.recordsWhere(swept, "pos", Weapons.at(x, y, z)).subList(0, SWEEPS);
    }

    /** The names a sweep put in its contact list. */
    private static List<String> names(String sweep) {
        return Arrays.asList(String.valueOf(Events.text(sweep, "names")).split(","));
    }

    // ===================================================================================== the linker

    /**
     * A player names his battery's target with a linker: he binds it to the battery's console, looks
     * at a block twenty blocks off and right-clicks, and the gun on the console's network turns onto it
     * and fires; then he looks at a creature and right-clicks, and it turns onto that instead; then
     * back onto the block.
     *
     * <p>Every click a press of the right mouse button through the input pipeline, never a verb naming
     * its target: the bind is a sneaking right-click with his crosshair on the console (a plain one
     * opens the console's screen), the aim is his look, and the designation is a right-click with
     * nothing in reach under the crosshair, which vanilla turns into a use of the held item. Each pick
     * is read before the press. Probes ARRANGE only what no interface lets a player do — the blocks, the
     * gun's charge, the linker in his inventory, a creature that stands still.</p>
     *
     * <p><b>What it does NOT see.</b> Whether the rounds HIT: nothing corrects a named target's aim for
     * the round's fall, so the verdict is that the gun is pointed at what he named and fires on it. A
     * gun linked directly rather than through a console; a ship-mounted battery; a target on a ship.</p>
     */
    @RedWitness(verdict = "the bind", member = "LinkerDesignation#bind",
            at = "ItemLinker.setMasterCoords(linker, tile.getPos());", replacedBy = "",
            fails = "the linker must carry the CONSOLE's position once he has clicked it", on = "2026-10-16")
    @RedWitness(verdict = "leg 3, back onto the block", member = "TileWeaponConsole#onLinkAimed",
            at = "state.clearTarget();", replacedBy = "",
            fails = "the gun must turn off the zombie and back onto the block", on = "2026-10-16")
    @Pins("MECH-GUN-39")
    @Test
    public void aPlayerNamesTheBatteryItsTargetByLookingAtItThroughALinker() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();

        // ---- where it stands: open air, this scenario's plot, proved empty first
        FixtureSite site = site();
        site.requireClear(c -> arrange(c).raw(), LINKER_HALO, 4, "the battery, the player beside it and both targets");
        int x0 = site.x, y0 = site.y, z0 = site.z;

        int gx = x0 + 2, gy = y0 + 1, gz = z0 + 2;           // the gun's controller
        int cx = x0 + 1, cy = y0 + 1, cz = z0 + 2;           // its console, touching it on the west
        int tx = gx + LINKER_TARGET_RANGE, ty = y0 + 1, tz = z0 + 4; // the block he names
        int zx = tx, zy = y0, zz = z0 - 2;                   // the zombie's footing

        arrange("stellurgytest fill " + DIM + " " + (x0 - 1) + " " + y0 + " " + z0 + " " + (x0 + 4) + " " + y0
                + " " + (z0 + 6) + " minecraft:stone").requireOk("a floor for him");
        place("minecraft:stone", tx, ty, tz);
        place("minecraft:stone", zx, zy, zz);

        // ---- the battery: console first, then the barrel, then the controller that walks them
        long built = server.mark();
        place("stellurgy:weaponconsole", cx, cy, cz);
        place("stellurgy:gunbarrel", gx, gy + 1, gz);
        place("stellurgy:turret", gx, gy, gz);
        takeDown.add(fillAir(cx, cy, cz, gx, gy + 1, gz));
        String assembled = server.awaitRecordWithFields(built, "turret_assembled",
                "the gun must count its build before it is anything to aim", Weapons.ARRANGEMENT_TICKS,
                "pos", Weapons.at(gx, gy, gz), "operable", "true");
        long afterAssembly = seqOf(assembled);
        server.awaitMatching(built, "subsystem_network_rebuilt",
                seen -> !laterThan(Events.recordsWhere(seen, "domain", "Weapon"), afterAssembly).isEmpty(),
                "a Weapon rebuild later than the gun's first tick",
                "the gun and its console must be joined into one network", Weapons.ARRANGEMENT_TICKS);
        Reply console = read("stellurgytest weaponconsole read " + DIM + " " + cx + " " + cy + " " + cz)
                .requireOk("console read");
        requireArranged("the console must command exactly this one gun: " + console,
                console.bool("network") && console.integer("guns") == 1);
        arrange(turretCommand("charge", gx, gy, gz)).requireOk("the gun's charge");
        Reply before = read(turretCommand("read", gx, gy, gz)).requireOk("turret read");
        requireArranged("before he names anything the gun must have NO target, or what follows could not"
                + " be his doing: " + before, !before.bool("hasTarget"));

        // ---- him: on the floor, behind the gun, the linker in his hand
        standOnFloorTheClientHolds(x0 + 0.5D, y0 + 1, z0 + 4.5D, 0.0F, 0.0F,
                "he stands on the floor behind his battery");
        long given = client.mark();
        arrange("give " + PLAYER + " libvulpes:linker");
        client.awaitMatching(given, "client_slot_set",
                seen -> Events.anyRecordFieldContains(seen, "item", "libvulpes:linker"),
                "holding libvulpes:linker", "the linker must reach his inventory", ROUND_TRIP_TICKS);
        bot().selectHotbar(0);
        JsonObject state = bot().reportState();
        requireArranged("the linker must be in his main hand: " + state,
                state.get("heldItem").getAsString().contains("linker"));

        // ---- the bind: a sneaking right-click on the console, the way he would — he looks at the
        // face turned toward him, and the click goes wherever vanilla's own pick says it goes
        setSneaking(server, true);
        double[] consoleFace = {cx + 0.5D, cy + 0.5D, cz + 1.0D};
        double[] bindEye = lookAt(server, consoleFace);
        JsonObject onConsole = bot().reportMouseOver();
        requireArranged("his crosshair must rest on the console before he clicks: " + onConsole
                        + " | looked from " + Arrays.toString(bindEye) + " at " + Arrays.toString(consoleFace)
                        + " | he is now " + bot().reportState(),
                "BLOCK".equals(onConsole.get("typeOfHit").getAsString())
                        && onConsole.get("blockX").getAsInt() == cx && onConsole.get("blockY").getAsInt() == cy
                        && onConsole.get("blockZ").getAsInt() == cz);
        long binding = server.mark();
        rightClick();
        String bound = server.awaitRecordWithFields(binding, "weapon_linker_bound",
                "his sneaking right-click on the console must bind the linker to it", ROUND_TRIP_TICKS,
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
                "his right-click must hand the console what he was looking at", ROUND_TRIP_TICKS,
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
        Reply aimed = read(turretCommand("read", gx, gy, gz)).requireOk("turret read");
        assertTrue("the gun's target must be the point he named " + Arrays.toString(hit) + ": " + aimed,
                aimed.bool("hasTarget") && distance(hit, targetOf(aimed)) < 1.0E-6D);
        awaitGun(server, namingBlock, seqOf(designated), gx, gy, gz, "turret_fired", null, null,
                "the gun must fire on the block he named");

        // ---- leg 2: a creature, standing still, and he names it instead
        // A zombie and not an animal: the test server runs with spawn-animals off, and vanilla then
        // kills every animal on its first tick, Invulnerable or not (measured 2026-10-04). No AI and
        // unkillable, so the point the gun must follow stays put while it is read; its uuid is given,
        // so the designation is checked against THIS one.
        long summoning = server.mark();
        takeDown.add("kill " + ZOMBIE_UUID);
        String summoned = arrange("stellurgytest arrange held-target " + DIM + " " + (zx + 0.5D) + " " + (zy + 1)
                + " " + (zz + 0.5D) + " " + ZOMBIE_UUID_MOST + " " + ZOMBIE_UUID_LEAST).raw();
        Reply zombies = read("stellurgytest entity near " + DIM + " " + (zx + 0.5D) + " " + (zy + 1) + " "
                + (zz + 0.5D) + " 2 EntityZombie").requireOk("entity near");
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
        arrange(turretCommand("charge", gx, gy, gz)).requireOk("the gun's charge");
        double[] zombieCentre = {zombie.number("x"), zombie.number("y") + ZOMBIE_HEIGHT / 2.0D,
                zombie.number("z")};
        lookAt(server, zombieCentre);

        requireNothingInReach("the zombie");
        long namingZombie = server.mark();
        rightClick();
        String designatedZombie = server.awaitRecordWithFields(namingZombie, "weapon_designated",
                "his right-click on the zombie must hand the console the zombie", ROUND_TRIP_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("what he looked at was a CREATURE: " + designatedZombie,
                "ENTITY", Events.text(designatedZombie, "hit"));
        assertEquals("and it was this zombie: " + designatedZombie, ZOMBIE_UUID, Events.text(designatedZombie, "entity"));
        assertEquals("the console must take it: " + designatedZombie, "true", Events.text(designatedZombie, "taken"));

        // Later than the designation, not merely later than the mark: the gun was on target and
        // firing at the block when he clicked, and an edge or a round from those last ticks would
        // otherwise read as one on the zombie.
        awaitGun(server, namingZombie, seqOf(designatedZombie), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn off the block and onto the zombie");
        Reply onZombie = read(turretCommand("read", gx, gy, gz)).requireOk("turret read");
        assertTrue("the gun must follow the zombie's body centre " + Arrays.toString(zombieCentre)
                + " — a designation that left the block order standing would still point at the"
                + " block: " + onZombie, onZombie.bool("hasTarget") && distance(zombieCentre, targetOf(onZombie)) < 0.05D);
        awaitGun(server, namingZombie, seqOf(designatedZombie), gx, gy, gz, "turret_fired", null, null,
                "the gun must fire on the zombie he named");

        // ---- leg 3: back to the block. A followed creature outranks a point, so this is the leg
        // that fails if naming a point leaves the creature order standing.
        lookAt(server, faceCentre);
        requireNothingInReach("the block again");
        long renamingBlock = server.mark();
        rightClick();
        String designatedAgain = server.awaitRecordWithFields(renamingBlock, "weapon_designated",
                "his right-click on the block must hand the console the block again", ROUND_TRIP_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("what he looked at was the BLOCK again: " + designatedAgain,
                "BLOCK", Events.text(designatedAgain, "hit"));
        awaitGun(server, renamingBlock, seqOf(designatedAgain), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn off the zombie and back onto the block");
        Reply back = read(turretCommand("read", gx, gy, gz)).requireOk("turret read");
        assertTrue("the gun must point at the block again, not keep following the zombie "
                + Arrays.toString(zombieCentre) + ": " + back,
                back.bool("hasTarget") && distance(faceCentre, targetOf(back)) < 0.05D);
    }

    /** How far out of the linker's site its working volume reaches: room for both targets east of the gun. */
    private static final int LINKER_HALO = 20;

    /** How far east of the gun the block the linker names stands. */
    private static final int LINKER_TARGET_RANGE = 22;

    /** Vanilla's default sneak binding, left shift. */
    private static final int SNEAK_KEY = 42;

    /** Vanilla's default use-item binding, the right mouse button ({@code GameSettings.keyBindUseItem}). */
    private static final int USE_ITEM_BUTTON = -99;

    /**
     * Vanilla's server-side reach for a click on a block: the reach attribute (5 in survival) plus 3
     * of latency slack, {@code NetHandlerPlayServer#processTryUseItemOnBlock}. A target past it is one
     * no click on a block could ever name.
     */
    private static final double BLOCK_CLICK_REACH = 5.0D + 3.0D;

    /**
     * How close the server's look must be to the one set. At the 22-block range a hundredth of a
     * degree moves the line of sight by under 0.004 blocks — far inside the 0.05 the hit is checked to,
     * and more than twice the 0.004-degree difference measured between the look set and the look held
     * (2026-10-04: pitch 2.7286277 set, 2.7246094 held, yaw -90.0 exact).
     */
    private static final double LOOK_TOLERANCE_DEGREES = 0.01D;

    /** An adult zombie's height ({@code EntityZombie}'s own size), for its body centre — the point a
     *  gun follows on a creature it tracks. */
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
     * keybind poll turns into {@code rightClickMouse()}, which then decides from its own pick whether
     * this is a click on a block, on a creature, or a use of the held item. Released at once, so the
     * held-button repeat never adds a second click.
     */
    private void rightClick() throws Exception {
        bot().setKey(USE_ITEM_BUTTON, true);
        bot().setKey(USE_ITEM_BUTTON, false);
    }

    /**
     * His crosshair rests on nothing within reach, which is what sends vanilla's right-click down the
     * item's own use path rather than onto a block or a creature: the target is named by the linker's
     * long trace, never by the click's pick.
     */
    private void requireNothingInReach(String target) throws Exception {
        JsonObject pick = bot().reportMouseOver();
        requireArranged("looking at " + target + ", nothing within reach may be under his crosshair, or the"
                + " click is not a use of the linker: " + pick, "MISS".equals(pick.get("typeOfHit").getAsString()));
    }

    /**
     * Presses or releases sneak, and waits for the SERVER to hold it: the click that follows is
     * decided on the server's idea of whether he is sneaking, and travels in a different packet.
     */
    private void setSneaking(Events server, boolean sneaking) throws Exception {
        long pressed = server.mark();
        bot().setKey(SNEAK_KEY, sneaking);
        server.awaitMatching(pressed, "player_pose_received",
                seen -> !Events.recordsWhereAll(seen, "who", PLAYER, "sneaking", String.valueOf(sneaking))
                        .isEmpty(),
                "sneaking " + sneaking, "the server must hold his sneak before he clicks", ROUND_TRIP_TICKS);
    }

    /**
     * Turns his head to look from his eye at {@code point}, and waits for the SERVER to hold that
     * look: the item-use packet carries no rotation, so a click sent before the look arrives is traced
     * along his previous one. Then one more client tick, so the CLIENT's crosshair is his new look too:
     * every rendered frame re-picks it ({@code EntityRenderer.java:1294}) along
     * {@code EntityLivingBase.getLook(partialTicks)}, which interpolates the HEAD yaw from
     * {@code prevRotationYawHead} ({@code :2782}), and that catches up with the turn only at the start of
     * the tick after the one that sent it ({@code :402}). Read inside that window, the pick carries the
     * new pitch and a yaw part-way round — measured 2026-10-16: two of four group runs picked the floor
     * behind him with yaw -146.3 and pitch 31.85 held on both sides.
     *
     * @return his eye, as the look was computed from it
     */
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(HEAD_YAW_CATCH_UP_TICKS);",
            why = "the client tick that sets prevRotationYawHead to the turned head yaw (EntityLivingBase:402);"
                    + " no record publishes it, and the server's player_pose_received above proves only the tick"
                    + " that sent the look, after which frames still interpolate the head yaw")
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
                ROUND_TRIP_TICKS);
        bot().waitWorldTicks(HEAD_YAW_CATCH_UP_TICKS);
        return eye;
    }

    /** The one client tick after the look was sent, whose start brings the head yaw's previous value round. */
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

    // ===================================================================================== a player's hand

    /**
     * A block a player PLACES into a hole a weapon left is a new block, not a destroyed one.
     *
     * <p>Every other damage test changes blocks through probe verbs, which fire no block event. This is
     * the door a player uses: the damage map hears a placement through a {@code BlockEvent.PlaceEvent},
     * and a block put back by hand is a real repair rather than one still recorded as destroyed.</p>
     *
     * <p>The control is the half that makes it evidence: the same hole filled by a probe — which fires
     * no event — still reads as destroyed. So the map does keep a hole's record through a placement
     * nobody announced, and what clears the subject's is the player's placement and nothing else. The
     * placement is linked on the map's own hearing of it ({@code damage_invalidation_heard}), and the
     * verdict is then read off the map once.</p>
     */
    @RedWitness(member = "DamageInvalidationHandler#onBlockPlaced", at = "forget(event.getWorld(), event.getPos());",
            replacedBy = "", fails = "a block a player placed into a hole still reads as DESTROYED", on = "2026-10-16")
    @Pins("MECH-DMG-15")
    @Test
    public void aBlockAPlayerPutsIntoAHoleIsNotRecordedAsDestroyed() throws Exception {
        Events server = serverEvents();
        FixtureSite site = site();
        int x = site.x, y = site.y, z = site.z;
        prepareRepairSite(x, y, z);
        int subjectX = x + 2, controlX = x + 4;
        post(subjectX, y, z);
        post(controlX, y, z);
        destroyPost(subjectX, y, z, 88101);
        destroyPost(controlX, y, z, 88102);

        // The control: a hole filled by something that announces nothing keeps its record. The
        // `place` verb sets the state and nothing else; `fill` is no use here, because it clears the
        // damage map itself as the harness's "this region is fresh".
        place("minecraft:stone", controlX, y, z);
        Reply control = stage(controlX, y, z);
        requireArranged("the control hole did not take the probe's stone: " + control,
                "minecraft:stone".equals(control.text("block")));
        assertTrue("a hole filled by a probe no longer reads as destroyed, so the map drops a hole's"
                + " record on ANY placement and the player's below proves nothing: " + control,
                control.bool("wasDestroyed"));

        // The subject: the player puts a stone into the other hole, by hand.
        standOnFloorTheClientHolds(x + 4.5D, y, z - 2.5D, 0.0F, 30.0F, "the player stands in front of the holes");
        arrange("give " + PLAYER + " minecraft:stone 1");
        bot().selectHotbar(0);
        long placed = server.markInstrumented();
        bot().rightClickBlock(subjectX, y - 1, z, EnumFacing.UP, EnumHand.MAIN_HAND);
        server.awaitRecordWithFields(placed, "damage_invalidation_heard",
                "the player's placement into the hole never reached the damage map",
                Weapons.SUBJECT_TICKS, "cause", "place", "dim", String.valueOf(DIM),
                "pos", Weapons.at(subjectX, y, z));

        Reply subject = stage(subjectX, y, z);
        requireArranged("the player's stone is not in the hole it was placed into: " + subject,
                "minecraft:stone".equals(subject.text("block")));
        assertTrue("a block a player placed into a hole still reads as DESTROYED: the placement was a"
                + " real repair paid for with the block itself, and the map charges it against the hull"
                + " anyway: " + subject, !subject.bool("wasDestroyed") && subject.integer("stage") == 0);
    }

    /**
     * A charged welder in a player's hand, used on a damaged block with its materials in the
     * inventory, takes exactly one stage off — through the item's own use, reached by a real
     * right-click after the protection hooks have had their say. The link is the block's own stage
     * write ({@code block_stage_set}), which carries the stage it came from and the one it went to.
     * The welder's price and its refusals are the static {@code weld}'s and are pinned in the fast tier;
     * this is the wiring from a hand to it.
     */
    @RedWitness(verdict = "the use reaches the repair", member = "ItemRepairWelder#onItemUse",
            at = "if (world.isRemote) {", replacedBy = "if (true) {",
            fails = "a charged welder in a player's hand, used on a damaged block with its materials in the"
                    + " inventory, repaired nothing",
            on = "2026-10-16")
    @RedWitness(verdict = "the write starts from the damage", member = "ItemRepairWelder#weld",
            at = "setStoredEnergy(tool, storedEnergy(tool) - energyCost); DamageState.setStage(world, pos, stage - 1);",
            replacedBy = "setStoredEnergy(tool, storedEnergy(tool) - energyCost);"
                    + " DamageState.setStage(world, pos, stage + 1); DamageState.setStage(world, pos, stage - 1);",
            fails = "the stage write the welder made did not start from the block's damage", on = "2026-10-16")
    @RedWitness(verdict = "exactly one stage", member = "ItemRepairWelder#weld",
            at = "setStoredEnergy(tool, storedEnergy(tool) - energyCost); DamageState.setStage(world, pos, stage - 1);",
            replacedBy = "setStoredEnergy(tool, storedEnergy(tool) - energyCost); DamageState.setStage(world, pos, stage - 2);",
            fails = "one use of the welder must take exactly one stage off", on = "2026-10-16")
    @Pins("MECH-DMG-16")
    @Test
    public void aWelderInAPlayersHandTakesOneStageOff() throws Exception {
        Events server = serverEvents();
        FixtureSite site = site();
        int x = site.x, y = site.y, z = site.z;
        prepareRepairSite(x, y, z);
        int blockX = x + 6;
        arrange("stellurgytest fill " + DIM + " " + blockX + " " + y + " " + z + " " + blockX + " " + y + " " + z
                + " minecraft:iron_block").requireOk("place the subject");
        Reply priced = stage(blockX, y, z);
        int twoStages = priced.integer("stageCost") * 2;
        requireArranged("the subject has no price, so it cannot be damaged by a known amount: " + priced,
                twoStages > 0);
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = act("stellurgytest damage impact " + DIM + " " + (blockX + 0.5D) + " " + (y + 3.5D) + " "
                + (z + 0.5D) + " 0 -1 0 " + twoStages + " KINETIC 88103").requireOk("damage the subject");
        int damaged = stage(blockX, y, z).integer("stage");
        requireArranged("the subject must be damaged and standing, or there is nothing to weld: stage "
                + damaged + " after " + shot, damaged >= 1 && damaged < priced.integer("maxStage"));

        // Survival: in creative the welder repairs for nothing, which is a different path. The base
        // puts the shared player back into creative before the next scenario.
        arrange("gamemode 0 " + PLAYER).raw();
        standOnFloorTheClientHolds(x + 4.5D, y, z - 2.5D, 0.0F, 30.0F, "the player stands in front of the subject");
        arrange("clear " + PLAYER);
        arrange("stellurgytest arrange charged-item " + PLAYER + " stellurgy:repairWelder 100000")
                .requireOk("a charged welder in his hand");
        arrange("give " + PLAYER + " minecraft:iron_ingot 64");
        bot().selectHotbar(0);

        long used = server.markInstrumented();
        bot().rightClickBlock(blockX, y, z, EnumFacing.UP, EnumHand.MAIN_HAND);
        String welded = server.awaitRecordWithFields(used, "block_stage_set",
                "a charged welder in a player's hand, used on a damaged block with its materials in the"
                        + " inventory, repaired nothing: the item's own use never reached the repair",
                Weapons.SUBJECT_TICKS, "dim", String.valueOf(DIM), "pos", Weapons.at(blockX, y, z));
        assertEquals("the stage write the welder made did not start from the block's damage: " + welded,
                damaged, (int) Events.number(welded, "from"));
        assertEquals("one use of the welder must take exactly one stage off: " + welded,
                damaged - 1, (int) Events.number(welded, "to"));
    }

    /**
     * A damaged block a player BREAKS takes its damage record with it: the position is empty, and
     * nothing is left there to be charged against whatever is built in it next.
     *
     * <p>The control is the same damage on a twin block removed by something that announces nothing —
     * a probe setting the block to air — which keeps its record; so the map does not drop a record
     * merely because the block under it went away, and what clears the subject's is the player's break
     * and nothing else. The break is a real attack-key press, aimed from the client's own eye and
     * confirmed on the client's crosshair before it is pressed, so it travels the path a player's does:
     * the client's click, its dig packet, the server's harvest and its {@code BlockEvent.BreakEvent}. It
     * is linked on the map's own hearing of it ({@code damage_invalidation_heard} with {@code cause}
     * {@code break}), and the verdict is then read off the map once.</p>
     */
    @RedWitness(member = "DamageInvalidationHandler#onBlockBroken", at = "forget(event.getWorld(), event.getPos());",
            replacedBy = "", fails = "a damaged block a player broke still carries its damage", on = "2026-10-16")
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(CROSSHAIR_RECOMPUTE_TICKS);",
            why = "one client tick, the one whose start recomputes the crosshair from the rotation the placement"
                    + " applied (Minecraft.runTick recomputes it before it reads any key).")
    @Wait(shape = Wait.Shape.STIMULUS, at = "bot().waitWorldTicks(ATTACK_HELD_TICKS);",
            why = "the attack key held across one client tick, as a mouse button is — long enough for the press to"
                    + " be read (Minecraft.processKeyBinds), shorter than the five ticks a creative break waits"
                    + " before it will take the next block (PlayerControllerMP.blockHitDelay).")
    @Pins("MECH-DMG-15")
    @Test
    public void aDamagedBlockAPlayerBreaksLeavesNoDamageBehind() throws Exception {
        // The break and its record happen in the server's world, so the server half of the step is
        // that world's own clock; the client half counts the ticks in which it sends the press.
        Events server = new Events(c -> read(c).raw(), worldAndClient(DIM), evictionReports());
        FixtureSite site = site();
        int x = site.x, y = site.y, z = site.z;
        prepareRepairSite(x, y, z);
        int subjectX = x + 2, controlX = x + 6;
        post(subjectX, y, z);
        post(controlX, y, z);
        int subjectStage = damagePost(subjectX, y, z, 88104);
        int controlStage = damagePost(controlX, y, z, 88105);

        // The control: the damaged twin taken away by something that fires no event keeps its record.
        place("minecraft:air", controlX, y, z);
        Reply control = stage(controlX, y, z);
        requireArranged("the control post is still standing: " + control,
                "minecraft:air".equals(control.text("block")));
        assertEquals("a damaged block removed by a probe no longer carries its damage, so the map drops"
                + " a record whenever the block under it goes, and the player's break below proves"
                + " nothing: " + control, controlStage, control.integer("stage"));

        // The subject: the player breaks the other one by hand. Creative — the base's mode — so ONE
        // press is a whole break (PlayerControllerMP.clickBlock sends the dig and the server's
        // onBlockClicked harvests at once, firing the BreakEvent from tryHarvestBlock) rather than a dig
        // held for as long as the block's hardness asks.
        bot().selectHotbar(0);
        // Stand three blocks north of the post, level with it, and face it: the rotation travels in
        // the placement itself, so the look the client applies is the one the teleport carried and
        // no later packet can overwrite it.
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
                ROUND_TRIP_TICKS, "cause", "break", "dim", String.valueOf(DIM),
                "pos", subjectX + "," + y + "," + z);

        Reply subject = stage(subjectX, y, z);
        requireArranged("the player's press did not take the post away: " + subject,
                "minecraft:air".equals(subject.text("block")));
        assertTrue("a damaged block a player broke still carries its damage (" + subjectStage + " stages"
                + " before the break): the next block built there would start cracked, and the"
                + " hull is charged for a crack that was mined out: " + subject,
                subject.integer("stage") == 0 && !subject.bool("wasDestroyed"));
    }

    /** The attack key's code: mouse buttons enter {@code KeyBinding} as {@code -100 + button}, LMB 0. */
    private static final int KEY_ATTACK = -100;

    /** The client tick whose start recomputes the crosshair ({@code Minecraft.runTick}). */
    private static final int CROSSHAIR_RECOMPUTE_TICKS = 1;

    /** How long the attack key is held: one client tick, as a mouse button is. */
    private static final int ATTACK_HELD_TICKS = 1;

    /**
     * Air round the repair site, a stone floor to stand on, and its chunks held: the posts are shot
     * before the player stands there, and a chunk nobody holds can be unloaded in between — the damage
     * engine then refuses the blow with TARGET_UNLOADED instead of landing it.
     */
    private void prepareRepairSite(int x, int y, int z) throws Exception {
        for (int cx = (x - 3) >> 4; cx <= (x + 9) >> 4; cx++) {
            for (int cz = (z - 4) >> 4; cz <= (z + 3) >> 4; cz++) {
                arrange("stellurgytest chunk forceload " + DIM + " " + cx + " " + cz).requireOk("hold the site's chunk");
            }
        }
        arrange("stellurgytest fill " + DIM + " " + (x - 3) + " " + y + " " + (z - 4) + " " + (x + 9) + " " + (y + 5)
                + " " + (z + 3) + " minecraft:air").requireOk("clear the site");
        Reply floor = arrange("stellurgytest fill " + DIM + " " + (x - 3) + " " + (y - 1) + " " + (z - 4) + " "
                + (x + 9) + " " + (y - 1) + " " + (z + 3) + " minecraft:stone").requireOk("lay the floor");
        requireArranged("the floor placed nothing: " + floor, floor.integer("placed") > 0);
    }

    /** One stone block on the floor, the thing a weapon will empty. */
    private void post(int x, int y, int z) throws Exception {
        arrange("stellurgytest fill " + DIM + " " + x + " " + y + " " + z + " " + x + " " + y + " " + z
                + " minecraft:stone").requireOk("place a post");
    }

    /** Destroy the post at {@code x} with exactly one block's worth, straight down from above. */
    private void destroyPost(int x, int y, int z, long impactId) throws Exception {
        Reply priced = stage(x, y, z);
        int oneBlock = priced.integer("stageCost") * Math.max(1, priced.integer("maxStage"));
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = act("stellurgytest damage impact " + DIM + " " + (x + 0.5D) + " " + (y + 3.5D) + " "
                + (z + 0.5D) + " 0 -1 0 " + oneBlock + " KINETIC " + impactId).requireOk("destroy the post");
        Reply hole = stage(x, y, z);
        requireArranged("the post at " + x + " was not destroyed into a recorded hole, so there is no hole"
                + " to fill: " + hole + " after " + shot,
                hole.bool("wasDestroyed") && "minecraft:air".equals(hole.text("block")));
    }

    /**
     * Damage the post at {@code x} by two stages' worth — the {@code damage stage} verb reports what
     * one stage of it costs the engine — from above, and answer its stage, which is read back and must
     * leave it damaged and standing.
     */
    private int damagePost(int x, int y, int z, long impactId) throws Exception {
        Reply priced = stage(x, y, z);
        int twoStages = priced.integer("stageCost") * 2;
        requireArranged("the post has no price, so it cannot be damaged by a known amount: " + priced,
                twoStages > 0);
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = act("stellurgytest damage impact " + DIM + " " + (x + 0.5D) + " " + (y + 3.5D) + " "
                + (z + 0.5D) + " 0 -1 0 " + twoStages + " KINETIC " + impactId).requireOk("damage the post");
        Reply damaged = stage(x, y, z);
        requireArranged("the post at " + x + " must be damaged and standing: " + damaged + " after " + shot,
                damaged.integer("stage") >= 1 && damaged.integer("stage") < damaged.integer("maxStage")
                        && "minecraft:stone".equals(damaged.text("block")));
        return damaged.integer("stage");
    }

    private Reply stage(int x, int y, int z) throws Exception {
        return read("stellurgytest damage stage " + DIM + " " + x + " " + y + " " + z).requireOk("read a stage");
    }

    // ===================================================================================== the screens

    /**
     * The console's Hold Fire button makes the battery hold fire on the server — the gun, on target and
     * charged, answers its own hold question with {@code held:true}.
     *
     * <p>A player has no probe: the screen is the only way he has to hold fire, and what a button press
     * does is decided on the CLIENT — the screen's button hands the press to the client's copy of the
     * tile. So whether a press reaches the server is a question only a real client connected to a
     * separate server can ask. It shipped red: the press called the client copy's own setter and no
     * packet carried it (measured 2026-09-30).</p>
     */
    @RedWitness(member = "TileWeaponConsole#onInventoryButtonPressed",
            at = "PacketHandler.sendToServer(new PacketMachine(this, NET_TOGGLE_HOLD_FIRE));", replacedBy = "",
            fails = "the console's Hold Fire button was pressed and the battery never held fire on the server",
            on = "2026-10-16")
    @Pins("MECH-GUN-19")
    @Test
    public void theConsolesHoldFireButtonHoldsTheBatteryOnTheServer() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int x = plot().x(3), y = site().y, z = site().z;
        prepareScreenSite(x, y, z);
        int gunX = x + 3, consoleX = x + 4;
        long built = server.markInstrumented();
        buildBarrelGun(gunX, y, z);
        Weapons.awaitAssembled(server, built, gunX, y, z, BARREL_GUN_PARTS, "the gun never assembled");
        place("stellurgy:weaponConsole", consoleX, y, z);
        takeDown.add(fillAir(consoleX, y, z, consoleX, y, z));
        arrange(turretCommand("charge", gunX, y, z)).requireOk("charge the gun");

        // The console's target is the arrangement, not the subject. The gun firing on it proves the
        // console commands the gun before any button.
        long ordered = server.mark();
        Reply applied = arrange("stellurgytest weaponconsole target " + DIM + " " + consoleX + " " + y + " " + z
                + " " + (gunX + 40.5D) + " " + (y + 0.5D) + " " + (z + 0.5D)).requireOk("give the console a target");
        requireArranged("the console refused the target: " + applied, applied.bool("applied"));
        Weapons.awaitFired(server, ordered, gunX, y, z,
                "the gun never fired on the console's target, so a hold below would prove nothing");

        standInFrontOfTheRow(x, y, z);
        String screen = ClientGuiTestSupport.openGuiByRightClick(bot(), client, consoleX, y, z);
        requireArranged("right-clicking the console opened no screen: " + screen, !screen.isEmpty());

        long pressed = server.mark();
        bot().clickButtonById(CONSOLE_HOLD_FIRE_BUTTON);
        arrange(turretCommand("charge", gunX, y, z)).requireOk("recharge the gun");
        server.awaitRecordWithFields(pressed, "turret_hold_decided",
                "the console's Hold Fire button was pressed and the battery never held fire on the"
                        + " server: the press never left the client",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(gunX, y, z), "held", "true");
        bot().closeScreen();
    }

    /**
     * The console's screen says what the SERVER's network says — its gun count and status — and
     * follows it when it changes: Hold Fire pressed, the screen says the network is holding.
     *
     * <p>The screen's readout is the client log's {@code client_console_readout}, written where the
     * server's readout is handed to the screen's lines. The status is checked against a WINDOW of two
     * server reads, one before the screen opened and one after its readout landed: a network's status
     * may legitimately move in between (a gun finishing its charge), and the screen is right if it shows
     * either of the states the server was in while it was being told.</p>
     */
    @RedWitness(verdict = "the screen shows the server's battery", member = "TileWeaponConsole#useNetworkData",
            at = "showReadout(nbt.getCompoundTag(\"readout\"));", replacedBy = "",
            fails = "the console's screen never showed the server's battery", on = "2026-10-16")
    @RedWitness(verdict = "the status is the server's", member = "TileWeaponConsole#readoutTag",
            at = "tag.setString(\"status\", networkStatusKey());",
            replacedBy = "tag.setString(\"status\", \"msg.weaponConsole.status.unknown\");",
            fails = "the console's screen shows network status", on = "2026-10-16")
    @RedWitness(verdict = "the screen follows the hold", member = "ReadoutSync#isUpdateRequired",
            at = "return world != null && !world.isRemote && !console.readoutTag().equals(sent);",
            replacedBy = "return world != null && !world.isRemote && sent == null;",
            fails = "Hold Fire was pressed and the console's screen never said the network is holding",
            on = "2026-10-16")
    @Pins("MECH-GUN-38")
    @Test
    public void theConsolesScreenShowsTheServersNetwork() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int x = plot().x(3), y = site().y, z = site().z;
        prepareScreenSite(x, y, z);
        int gunX = x + 3, consoleX = x + 4;
        long built = server.markInstrumented();
        buildBarrelGun(gunX, y, z);
        Weapons.awaitAssembled(server, built, gunX, y, z, BARREL_GUN_PARTS, "the gun never assembled");
        place("stellurgy:weaponConsole", consoleX, y, z);
        takeDown.add(fillAir(consoleX, y, z, consoleX, y, z));
        arrange(turretCommand("charge", gunX, y, z)).requireOk("charge the gun");
        String console = DIM + " " + consoleX + " " + y + " " + z;
        Reply before = read("stellurgytest weaponconsole read " + console).requireOk("read the console on the server");
        requireArranged("the console is not commanding the one gun beside it on the server, so its"
                + " screen has nothing to show: " + before, before.bool("network") && before.integer("guns") == 1);

        standInFrontOfTheRow(x, y, z);
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
     * The sensor's mode button switches the SERVER's sensor to illuminating — its next sweeps are taken
     * in {@code ACTIVE} mode. It shipped red for the same reason as the console's: the press set the
     * client copy's mode and sent nothing (measured 2026-09-30).
     */
    @RedWitness(member = "TileFireControlSensor#onInventoryButtonPressed",
            at = "PacketHandler.sendToServer(new PacketMachine(this, NET_TOGGLE_MODE));", replacedBy = "",
            fails = "the sensor's mode button was pressed and the sensor on the server never swept in",
            on = "2026-10-16")
    @Pins("MECH-FCS-14")
    @Test
    public void theSensorsModeButtonSwitchesTheSensorOnTheServer() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        int x = plot().x(3), y = site().y, z = site().z;
        prepareScreenSite(x, y, z);
        int sensorX = x + 7;
        long placed = server.markInstrumented();
        place("stellurgy:fireControlSensor", sensorX, y, z);
        takeDown.add(fillAir(sensorX, y, z, sensorX, y, z));
        arrange("stellurgytest sensor charge " + DIM + " " + sensorX + " " + y + " " + z).requireOk("charge the sensor");
        String passive = server.awaitRecordWithFields(placed, "sensor_swept",
                "the sensor never swept, so its mode was never decided on the server",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(sensorX, y, z));
        requireArranged("a fresh sensor is not listening, so switching it proves nothing: " + passive,
                "PASSIVE".equals(Events.text(passive, "mode")));

        standInFrontOfTheRow(x, y, z);
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

    /** The console's hold-fire button, as the console's own screen numbers it. */
    private static final int CONSOLE_HOLD_FIRE_BUTTON = 0;
    /** The sensor's mode button, as the sensor's own screen numbers it. */
    private static final int SENSOR_MODE_BUTTON = 0;

    /**
     * Air along the row and the console's line of fire — its target stands forty blocks east of the
     * gun — a stone floor in front of the row, and the row's chunks held.
     */
    private void prepareScreenSite(int x, int y, int z) throws Exception {
        arrange("stellurgytest fill " + DIM + " " + (x - 3) + " " + y + " " + (z - 4) + " " + (x + 60) + " "
                + (y + 8) + " " + (z + 3) + " minecraft:air").requireOk("clear the site");
        arrange("stellurgytest fill " + DIM + " " + (x - 3) + " " + (y - 1) + " " + (z - 4) + " " + (x + 12) + " "
                + (y - 1) + " " + (z + 3) + " minecraft:stone").requireOk("lay the floor");
        for (int cx = (x - 3) >> 4; cx <= (x + 60) >> 4; cx++) {
            arrange("stellurgytest chunk forceload " + DIM + " " + cx + " " + (z >> 4)).requireOk("hold a chunk");
        }
    }

    /** On the floor, a couple of blocks in front of the row, within reach of every block in it. */
    private void standInFrontOfTheRow(int x, int y, int z) throws Exception {
        standOnFloorTheClientHolds(x + 4.5D, y, z - 2.5D, 0.0F, 30.0F, "the player stands in front of the row");
    }

    // ===================================================================================== shared arrangement

    /** The reference beam gun: a controller with emitters on it and cooling around it, its site cleared and held. */
    private void buildBeamGun(int bx, int y, int z) throws Exception {
        arrange("stellurgytest fill " + DIM + " " + (bx - 8) + " " + (y - 2) + " " + (z - 4) + " "
                + (bx + 40) + " " + (y + 12) + " " + (z + 4) + " minecraft:air").requireOk("clear the gun's site");
        for (int cx = (bx - 8) >> 4; cx <= (bx + 40) >> 4; cx++) {
            arrange("stellurgytest chunk forceload " + DIM + " " + cx + " " + (z >> 4)).requireOk("hold a chunk");
        }
        place("stellurgy:turret", bx, y, z);
        takeDown.add(fillAir(bx, y, z - 1, bx, y + 3, z + 1));
        for (int i = 1; i <= 3; i++) {
            place("stellurgy:gunBeamEmitter", bx, y + i, z);
        }
        place("stellurgy:gunCooling", bx, y, z + 1);
        place("stellurgy:gunCooling", bx, y, z - 1);
    }

    /** Something for a beam gun to burn into, and an order to burn into it. */
    private void aimAlongTheWall(int bx, int y, int z) throws Exception {
        int wallX = bx + 20;
        arrange("stellurgytest fill " + DIM + " " + wallX + " " + y + " " + z + " " + (wallX + 5) + " " + y
                + " " + z + " minecraft:iron_block").requireOk("build the wall");
        arrange(turretCommand("target", bx, y, z) + " " + (wallX + 0.5D) + " " + (y + 0.5D) + " " + (z + 0.5D))
                .requireOk("aim the gun at its wall");
    }

    /** A controller with four barrels on it and cooling either side — a gun that throws rounds. */
    private void buildBarrelGun(int gx, int gy, int gz) throws Exception {
        arrange("stellurgytest chunk forceload " + DIM + " " + (gx >> 4) + " " + (gz >> 4)).requireOk("hold the chunk");
        place("stellurgy:turret", gx, gy, gz);
        takeDown.add(fillAir(gx, gy, gz - 1, gx, gy + 4, gz + 1));
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", gx, gy + i, gz);
        }
        place("stellurgy:gunCooling", gx, gy, gz + 1);
        place("stellurgy:gunCooling", gx, gy, gz - 1);
    }

    /**
     * Stand the player still on a stone pad at {@code (x, y, z)} (his feet; the pad one block under),
     * laid here and held by his client before he is placed on it.
     */
    private void standOnAPad(double x, double y, double z, String what) throws Exception {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        arrange("stellurgytest fill " + DIM + " " + (bx - 1) + " " + (by - 1) + " " + (bz - 1) + " " + (bx + 1)
                + " " + (by - 1) + " " + (bz + 1) + " minecraft:stone").requireOk("lay his pad");
        standOnFloorTheClientHolds(x, y, z, 0.0F, 0.0F, what);
    }

    /** {@link #standOnAPad} at a block's centre, for a scenario that names him by the block he stands in. */
    private void standBeside(int x, int y, int z, String what) throws Exception {
        standOnAPad(x + 0.5D, y, z + 0.5D, what);
    }

    /** One round through production's own entry point; the id, or a failed arrangement. */
    private long fire(String spec) throws Exception {
        Reply fired = act("stellurgytest shot fire " + DIM + " " + spec).requireOk("fire a round");
        long id = fired.longInteger("id");
        assertTrue("the launch was refused, so nothing else here means anything: " + fired, id >= 0);
        return id;
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = arrange("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        requireArranged("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    private static String turretCommand(String verb, int x, int y, int z) {
        return "stellurgytest turret " + verb + " " + DIM + " " + x + " " + y + " " + z;
    }

    private static String fillAir(int x1, int y1, int z1, int x2, int y2, int z2) {
        return "stellurgytest fill " + DIM + " " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2
                + " minecraft:air";
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360.0D;
        return wrapped <= -180.0D ? wrapped + 360.0D : (wrapped > 180.0D ? wrapped - 360.0D : wrapped);
    }
}
