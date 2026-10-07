package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem;
import dev.stannismod.stellurgy.affs.te.TileEntityFieldGenerator;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.integration.affs.AffsGuiRouter;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiHandler;
import dev.stannismod.stellurgy.libvulpes.util.ZUtils.RedstoneState;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Which client packets of the mod's channel the server believes: a machine packet, a held-item packet
 * and the decoders of both entity-shaped packets, each sent by a player the server must not take at
 * his word.
 *
 * <p>NEW-GROUP: the server half of the mod channel for packets a CLIENT sends — the checks a packet
 * passes before it touches anything: the target in the sender's own world, the sender within reach of
 * it, a held item that only names what exists, bytes that decode; and, for a packet only a screen sends,
 * that the sender has that screen open and the packet carries a value the screen can produce — the
 * machine packets of the station controllers, the rocket assembler and the warp monitor, and the
 * force-field channel's own client packets. No server group holds it: every other
 * class observes what one machine does with the packet its own screen produces, never which senders a
 * packet is taken from.</p>
 *
 * <p><b>How a packet is sent here, and what that cannot see.</b> This tier has no client, and no client
 * of ours would write these bytes anyway — each scenario is a packet our own client never produces.
 * {@code stellurgytest packet} therefore hands the bytes to the packet class as the headless test
 * player's ({@code player ensure-fake}): the class decodes them and executes them for him on the server
 * thread, which is the whole server half of the channel. Netty's framing and FML's dispatch by
 * discriminator are not exercised. The delivery is synchronous — the command returns after the packet
 * has been executed — so every reading here is taken after the fact it reads, and nothing waits.</p>
 *
 * <p><b>How a screen is open here, and what that cannot see.</b> {@code stellurgytest client screen} builds
 * the server half of a GUI through the mod's own GUI handler and makes it the headless player's open
 * container; no client ever shows it. What the server checks is that container, so the checks see it as
 * they see a real one. Opening a screen by a click — the block's own activation and the packet to the
 * client — is not exercised here.</p>
 */
public class ClientPacketSenderServerGroupTest extends AbstractSharedServerTest {

    private static final int OVERWORLD = 0;
    private static final int NETHER = -1;

    /** A block whose machine packet carries a value the server keeps: the ventilation port. */
    private static final String VENT = "stellurgy:ventilationPort";
    /** {@code TileVentilationPort#PACKET_PRIORITY_ID}: its one machine packet, a zone priority. */
    private static final int PRIORITY_PACKET = 4;
    /** {@code TileVentilationPort#PRIORITY_MAX} and {@code #PRIORITY_MIN}: values its clamp keeps as sent. */
    private static final int HIGH = 1;
    private static final int LOW = -1;
    /** A placed port's priority before anyone sets one ({@code TileVentilationPort#zonePriority}'s default). */
    private static final int UNSET = 0;

    /**
     * How far from a machine a sender stands to be NEAR it, in blocks: two — well inside the eight of
     * vanilla's container range ({@code PacketSenderCheck#CONTAINER_REACH_SQ} is 64).
     */
    private static final int NEAR = 2;
    /** How far from a machine a sender stands to be FAR from it, in blocks: twenty — beyond those eight. */
    private static final int FAR = 20;

    /**
     * A dimension id no world is loaded under — the top of the int range, which nothing registers. Each
     * scenario that names it reads {@code worldLoaded:false} back before it trusts that.
     */
    private static final int NO_WORLD = Integer.MAX_VALUE;

    private static final String PROJECTOR = "libvulpes:holoProjector";
    /**
     * The machine the projector is set to: id 0, the first {@code ItemProjector#registerMachine} call
     * in {@code Stellurgy}, the electric arc furnace. Its {@code TileElectricArcFurnace#structure} is five
     * cells square and five layers high, and its bottom layer is blast brick in all 25 cells.
     */
    private static final int ARC_FURNACE = 0;
    private static final int FURNACE_EDGE = 5;
    private static final int FURNACE_LAYERS = 5;
    /**
     * {@code EnumFacing.NORTH}'s index. Laid out facing north from a base {@code (x, y, z)}, the furnace
     * occupies x..x+4 and z-4..z ({@code ItemProjector#RebuildStructure}'s mapping).
     */
    private static final int NORTH = 2;
    /** The projector's packet ids ({@code ItemProjector#useNetworkData}): choose a machine; project it. */
    private static final int SELECT_MACHINE = 0;
    private static final int PROJECT = 2;
    /**
     * A machine id no machine has. Machine ids index the projector's list, so no negative id names one;
     * -1 is the item's own "none" ({@code ItemProjector#getMachineId}), so the one below it is used.
     */
    private static final int NO_MACHINE = -2;
    /**
     * How many updates a ghost block takes to go by itself: {@code TileSchematic#ttl} (6000), plus the
     * one on which {@code update} finds it reached.
     */
    private static final int GHOST_LIFETIME_UPDATES = 6001;

    /** The three station controllers, by the name each scenario files its readings under. */
    private static final String GRAVITY = "gravity";
    private static final String ALTITUDE = "altitude";
    private static final String ORIENTATION = "orientation";
    /**
     * A station controller's slider packet and its redstone-button packet: the ids
     * {@code TileStationGravityController#readDataFromNetwork} reads ({@code packetId == 0}, a short;
     * {@code packetId == 2}, a byte) — the altitude controller's are the same, the orientation
     * controller has the slider packet alone (three shorts, one per axis).
     */
    private static final int SLIDER_PACKET = 0;
    private static final int REDSTONE_PACKET = 2;

    private static final String ASSEMBLER = "stellurgy:rocketBuilder";
    /**
     * The assembler's packets ({@code TileRocketAssemblingMachine#readDataFromNetwork} and its screen's
     * buttons): 1 is the build button, 2 the stored energy and build progress (two ints).
     */
    private static final int ASSEMBLER_BUILD_PACKET = 1;
    private static final int ASSEMBLER_STATE_PACKET = 2;
    /** What a client claims the assembler holds: any energy and progress but the zero a placed one holds. */
    private static final int SOME_ENERGY = 5000;
    private static final int SOME_PROGRESS = 7;

    private static final String WARP_MONITOR = "stellurgy:warpMonitor";
    /** The monitor's destination packet: an int, a body's id ({@code TileWarpController#readDataFromNetwork}). */
    private static final int WARP_DESTINATION_PACKET = 1;
    /** Sol's star id ({@code DimensionManager} gives the home star id 0). */
    private static final int SOL = 0;

    private static final String FIELD_GENERATOR = "affs:field_generator";
    private static final String SHIELD_CONSOLE = "affs:shield_console";
    /**
     * How far, in chunks along both axes, a position a client names lies from the sender: twenty, five
     * times the harness's view distance of four, so nothing about the sender or the scenario has that
     * chunk loaded — and the scenario reads that it is not before it relies on it.
     */
    private static final int FAR_CHUNKS = 20;

    // ---- machine packets ----------------------------------------------------------------------

    /**
     * A machine packet is applied for a sender within reach of the machine, and not for one beyond it.
     *
     * <p>Contract: fails if {@code PacketMachine#executeServer} stops refusing a sender who is not within
     * container reach of the addressed machine ({@code PacketSenderCheck#withinContainerReach}).</p>
     * <p>red-witnessed: with {@code PacketMachine#executeServer} at {@code if (!PacketSenderCheck.withinContainerReach(player, pos))} made {@code if (false)}, fails: "a priority packet from a sender 20 blocks away — beyond the reach any screen of the port can be used from — must not change the port expected:<1> but was:<-1>" (2026-10-07).</p>
     */
    @Test
    public void aMachinePacketFromBeyondReachIsNotApplied() throws Exception {
        FixtureSite site = clearedSite(0, 1, "the ventilation port stands in this volume");
        int vx = site.x, vy = site.y + 1, vz = site.z;
        arrange("stellurgytest place " + OVERWORLD + " " + vx + " " + vy + " " + vz + " " + VENT);
        requireArranged("a placed port starts at the default priority",
                priorityAt(OVERWORLD, vx, vy, vz) == UNSET);

        standSenderAt(OVERWORLD, vx + NEAR, vy, vz);
        sendPriority(OVERWORLD, vx, vy, vz, HIGH);
        assertEquals("a sender " + NEAR + " blocks from the port must be believed: his priority packet is"
                        + " what the port's own screen sends",
                HIGH, priorityAt(OVERWORLD, vx, vy, vz));

        standSenderAt(OVERWORLD, vx + FAR, vy, vz);
        sendPriority(OVERWORLD, vx, vy, vz, LOW);
        assertEquals("a priority packet from a sender " + FAR + " blocks away — beyond the reach any"
                        + " screen of the port can be used from — must not change the port",
                HIGH, priorityAt(OVERWORLD, vx, vy, vz));
    }

    /**
     * A machine packet naming another world than the sender's is applied to no machine — neither the
     * one it names nor the one at the same coordinates in the world the sender is in.
     *
     * <p>Contract: fails if {@code PacketMachine#executeServer} stops refusing a packet whose dimension is
     * not the sender's ({@code PacketSenderCheck#inSendersWorld}), or resolves the machine in any world
     * but the sender's own.</p>
     * <p>red-witnessed: with {@code PacketMachine#executeServer} at {@code if (!PacketSenderCheck.inSendersWorld(player, dimId))} made {@code if (false)} and {@code World world = player.world;} replaced by {@code DimensionManager.getWorld(dimId)}, fails: "a packet naming the overworld port, sent by a player in the nether, must not reach the overworld port expected:<0> but was:<-1>" (2026-10-07).</p>
     */
    @Test
    public void aMachinePacketNamingAnotherWorldIsNotApplied() throws Exception {
        FixtureSite here = clearedSite(0, 1, "the overworld port stands in this volume");
        int vx = here.x, vy = here.y + 1, vz = here.z;
        // The sender goes to the nether FIRST: it is what keeps that world loaded for the rest of the
        // scenario.
        standSenderAt(NETHER, vx + NEAR, vy, vz);
        FixtureSite there = plot().inDimension(NETHER).site();
        there.requireClear(this::exec, 0, 1, "the nether port stands in this volume");
        arrange("stellurgytest place " + OVERWORLD + " " + vx + " " + vy + " " + vz + " " + VENT);
        arrange("stellurgytest place " + NETHER + " " + vx + " " + vy + " " + vz + " " + VENT);

        sendPriority(OVERWORLD, vx, vy, vz, LOW);
        assertEquals("a packet naming the overworld port, sent by a player in the nether, must not reach"
                        + " the overworld port",
                UNSET, priorityAt(OVERWORLD, vx, vy, vz));
        assertEquals("a packet naming the overworld must not be applied to whatever stands at the same"
                        + " coordinates in the nether, where its sender is",
                UNSET, priorityAt(NETHER, vx, vy, vz));

        sendPriority(NETHER, vx, vy, vz, HIGH);
        assertEquals("the same sender, naming the port in his own world, must be believed",
                HIGH, priorityAt(NETHER, vx, vy, vz));
    }

    /**
     * A machine packet whose payload is shorter than the machine reads is dropped, and the server
     * thread does not throw.
     *
     * <p>Contract: fails if {@code PacketMachine#executeServer} lets a machine's reader throw on a
     * client's bytes instead of dropping the packet — or applies what it could not read.</p>
     * <p>red-witnessed: with {@code PacketMachine#executeServer} at {@code found.readDataFromNetwork(Unpooled.wrappedBuffer(payload), packetId, nbt);} taken out of its try/catch, fails: "a priority packet with no priority in it must be dropped, not thrown on the server thread: ("error":"IndexOutOfBoundsException: null")" (2026-10-07).</p>
     */
    @Test
    public void aMachinePacketWhosePayloadDoesNotDecodeIsDroppedNotThrown() throws Exception {
        FixtureSite site = clearedSite(0, 1, "the ventilation port stands in this volume");
        int vx = site.x, vy = site.y + 1, vz = site.z;
        arrange("stellurgytest place " + OVERWORLD + " " + vx + " " + vy + " " + vz + " " + VENT);
        standSenderAt(OVERWORLD, vx + NEAR, vy, vz);
        sendPriority(OVERWORLD, vx, vy, vz, HIGH);
        requireArranged("the sender must be one the port believes before a malformed packet from him"
                + " says anything", priorityAt(OVERWORLD, vx, vy, vz) == HIGH);

        String empty = "stellurgytest packet machine " + OVERWORLD + " " + vx + " " + vy + " " + vz + " "
                + PRIORITY_PACKET;
        Reply delivered = ask(empty);
        assertTrue("a priority packet with no priority in it must be dropped, not thrown on the server"
                + " thread: " + delivered, delivered.ok());
        assertEquals("a priority packet the port could not read must leave the priority as it was",
                HIGH, priorityAt(OVERWORLD, vx, vy, vz));
    }

    /**
     * An entity packet and a held-item packet that name a dimension with no world are dropped, and
     * decoding them does not throw.
     *
     * <p>Contract: fails if {@code PacketEntity#read} or {@code PacketItemModifcation#read} looks the
     * named world up while decoding, or if their {@code executeServer} lets a world other than the
     * sender's through.</p>
     * <p>red-witnessed: with {@code PacketEntity#decode} at {@code dimId = in.readInt();} preceded by an unchecked {@code DimensionManager.getWorld(dimId)} dereference outside its try, fails: "an entity packet naming a dimension with no world must be dropped, not thrown: ("error":"NullPointerException: null")" (2026-10-07).</p>
     */
    @Test
    public void anEntityOrItemPacketNamingNoWorldIsDroppedNotThrown() throws Exception {
        FixtureSite site = site();
        standSenderAt(OVERWORLD, site.x, site.y + 1, site.z);

        // The verdict before the precondition, because the reply that carries the precondition is
        // the one a throw replaces: a decoder that throws answers an error, which names no world.
        Reply entity = ask("stellurgytest packet entity " + NO_WORLD + " 0 0");
        assertTrue("an entity packet naming a dimension with no world must be dropped, not thrown: "
                + entity, entity.ok());
        requireArranged("the named dimension must have no world, or this is not the case it claims: "
                + entity, !entity.bool("worldLoaded"));

        Reply item = ask("stellurgytest packet item 0 dim=" + NO_WORLD);
        assertTrue("a held-item packet naming a dimension with no world must be dropped, not thrown: "
                + item, item.ok());
        requireArranged("the named dimension must have no world, or this is not the case it claims: "
                + item, !item.bool("worldLoaded"));
    }

    // ---- the holo projector -------------------------------------------------------------------

    /**
     * The projector takes a machine id from its holder's client only when a machine has it, and an id
     * that names none leaves the item as it was.
     *
     * <p>Contract: fails if {@code ItemProjector#useNetworkData} stops refusing a machine id outside its
     * machine list — which used to write the id onto the item, throw, and leave every client that later
     * showed the item's tooltip to throw in turn.</p>
     * <p>red-witnessed: with {@code ItemProjector#useNetworkData} at {@code PacketSenderCheck.refuse(player, describe(id), "there is no machine " + machineId);} never reached (its {@code isMachineId} condition made false), fails: "choosing machine -2, which no machine has, must leave the projector on the machine it had expected:<0> but was:<-2>" (2026-10-07).</p>
     */
    @Test
    public void aProjectorTakesOnlyAMachineIdThatExists() throws Exception {
        FixtureSite site = site();
        standSenderAt(OVERWORLD, site.x, site.y + 1, site.z);
        arrange("stellurgytest packet hold " + PROJECTOR);

        arrange("stellurgytest packet item " + SELECT_MACHINE + " " + ARC_FURNACE);
        assertEquals("choosing machine " + ARC_FURNACE + ", which exists, must be taken",
                ARC_FURNACE, heldMachineId());

        Reply refused = ask("stellurgytest packet item " + SELECT_MACHINE + " " + NO_MACHINE);
        assertEquals("choosing machine " + NO_MACHINE + ", which no machine has, must leave the projector"
                + " on the machine it had", ARC_FURNACE, heldMachineId());
        assertTrue("a machine id the projector refuses must be dropped, not thrown: " + refused,
                refused.ok());
    }

    /**
     * The projector projects where its holder could have pointed, and nowhere else.
     *
     * <p>Contract: fails if {@code ItemProjector#useNetworkData} stops refusing a projection whose
     * footprint is out of its holder's reach of a click ({@code PacketSenderCheck#withinItemUseReach}).</p>
     * <p>red-witnessed: with {@code ItemProjector#project} at {@code if(!PacketSenderCheck.withinItemUseReach(player, footprint.grow(1.0D)))} made {@code if(false)}, fails: "a projection thirty blocks from its holder — where he could not have pointed — must lay no ghost expected:<0> but was:<25>" (2026-10-07).</p>
     */
    @Test
    public void aProjectorProjectsOnlyWhereItsHolderCouldHavePointed() throws Exception {
        FixtureSite near = clearedSite(0, FURNACE_LAYERS, "the near projection is laid into this volume");
        // Thirty blocks east of the near site, inside this plot: further from the holder than his
        // reach plus the server's tolerance (5 + 3) and the furnace's own five cells together.
        FixtureSite far = plot().siteAt(50, 20);
        far.requireClear(this::exec, 0, FURNACE_LAYERS, "the far projection would be laid into this volume");
        standSenderAt(OVERWORLD, near.x, near.y + 1, near.z);
        arrange("stellurgytest packet hold " + PROJECTOR);
        arrange("stellurgytest packet item " + SELECT_MACHINE + " " + ARC_FURNACE);
        requireArranged("the projector must hold the furnace before it is pointed anywhere",
                heldMachineId() == ARC_FURNACE);

        project(far);
        assertEquals("a projection thirty blocks from its holder — where he could not have pointed — must"
                + " lay no ghost", 0, ghostsIn(far));

        project(near);
        assertTrue("a projection at its holder's feet must lay the furnace's ghost: the projector works"
                + " for this holder at all", ghostsIn(near) > 0);
    }

    /**
     * A ghost laid into a cell that held a replaceable block puts that block back when it fades.
     *
     * <p>Contract: fails if {@code TileSchematic#vanish} leaves air where the ghost displaced something,
     * or if {@code ItemProjector#RebuildStructure} stops telling the ghost what it displaced.</p>
     * <p>red-witnessed: with {@code TileSchematic#vanish} at {@code world.setBlockState(pos, displaced, 3);} setting air, fails: "org.junit.ComparisonFailure: a ghost that faded where snow lay must leave the snow it was laid over expected:<minecraft:[snow_laye]r> but was:<minecraft:[ai]r>" (2026-10-07).</p>
     */
    @Test
    public void aProjectionPutsBackWhatItStoodInWhenItFades() throws Exception {
        FixtureSite site = clearedSite(0, FURNACE_LAYERS, "the projection is laid into this volume");
        int x2 = site.x + FURNACE_EDGE - 1, z2 = site.z + FURNACE_EDGE - 1;
        int floor = site.y, snow = site.y + 1;
        int footprint = FURNACE_EDGE * FURNACE_EDGE;
        Reply stone = arrange("stellurgytest fill " + OVERWORLD + " " + site.x + " " + floor + " " + site.z
                + " " + x2 + " " + floor + " " + z2 + " minecraft:stone");
        requireArranged("the floor must be laid into air, all " + footprint + " cells of it: " + stone,
                stone.integer("placed") == footprint);
        Reply snowLaid = arrange("stellurgytest fill " + OVERWORLD + " " + site.x + " " + snow + " " + site.z
                + " " + x2 + " " + snow + " " + z2 + " minecraft:snow_layer");
        requireArranged("snow must lie on every cell of the furnace's bottom layer: " + snowLaid,
                snowLaid.integer("placed") == footprint);

        standSenderAt(OVERWORLD, site.x, snow, site.z);
        arrange("stellurgytest packet hold " + PROJECTOR);
        arrange("stellurgytest packet item " + SELECT_MACHINE + " " + ARC_FURNACE);
        project(site);

        Reply ghosts = ask(ghostsCommand(site));
        int[] ghost = null;
        for (int[] at : ghosts.blockPosArray("at")) {
            if (at[1] == snow) {
                ghost = at;
                break;
            }
        }
        requireArranged("the furnace's bottom layer must have been projected into the snow: " + ghosts,
                ghost != null);

        arrange("stellurgytest tile force-tick " + OVERWORLD + " " + ghost[0] + " " + ghost[1] + " " + ghost[2]
                + " " + GHOST_LIFETIME_UPDATES);
        String after = ask("stellurgytest block at " + OVERWORLD + " " + ghost[0] + " " + ghost[1] + " "
                + ghost[2]).text("block");
        assertEquals("a ghost that faded where snow lay must leave the snow it was laid over",
                "minecraft:snow_layer", after);
    }

    // ---- what a machine takes from the screen it is used through --------------------------------

    /**
     * A station controller takes a new target from a sender only while he has THAT controller's screen
     * open, and only a target its slider can produce.
     *
     * <p>Contract: fails if {@code TileStationGravityController#useNetworkData},
     * {@code TileStationAltitudeController#useNetworkData} or
     * {@code TileStationOrientationController#useNetworkData} applies a slider packet from a sender who
     * does not have that controller's screen open ({@code PacketSenderCheck#hasScreenOpen}), or a target
     * outside its slider's 0..total.</p>
     * <p>What this does not see: the target reaching a station — each controller stands alone, so it
     * keeps the target as its own slider position, which is the value it hands the station when it has
     * one ({@code setProgress}).</p>
     * <p>red-witnessed: with {@code PacketSenderCheck#hasScreenOpen} at {@code return sender != null && sender.openContainer instanceof ContainerModular} cut to {@code return sender != null;}, fails: "a slider packet from a sender with no screen open moved a controller's target expected:&lt;{gravity=0, altitude=0, orientation=60}&gt; but was:&lt;{gravity=90, altitude=190, orientation=120}&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code ContainerModular#isScreenOf} at {@code return modularInventory == inventory;} made {@code return true;}, fails: "a slider packet sent through ANOTHER controller's screen moved this one's target expected:&lt;{altitude=0, orientation=60}&gt; but was:&lt;{altitude=190, orientation=120}&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code PacketSenderCheck#hasScreenOpen} at {@code return sender != null && sender.openContainer instanceof ContainerModular} made {@code return false;}, fails: "a slider packet from a sender with the controller's own screen open must set its target — or the refusals above say nothing about the screen expected:&lt;{gravity=90, altitude=190, orientation=120}&gt; but was:&lt;{gravity=0, altitude=0, orientation=60}&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code TileStationGravityController#refusal} and {@code TileStationAltitudeController#refusal} at {@code return target >= 0 && target <= getTotalProgress(0) ? null} made {@code return true ? null}, and {@code TileStationOrientationController#refusal} at {@code if (targets[axis] < 0 || targets[axis] > getTotalProgress(axis))} made {@code if (false)}, fails: "a target past the end of a controller's slider was taken expected:&lt;{gravity=90, altitude=190, orientation=120}&gt; but was:&lt;{gravity=91, altitude=191, orientation=121}&gt;" (2026-10-07).</p>
     */
    @Test
    public void aStationControllerTakesATargetOnlyFromItsOwnOpenScreen() throws Exception {
        FixtureSite site = clearedSite(0, 1, "the three station controllers stand in this volume");
        int y = site.y + 1;
        Map<String, int[]> at = new LinkedHashMap<>();
        at.put(GRAVITY, new int[]{site.x, y, site.z});
        at.put(ALTITUDE, new int[]{site.x + 1, y, site.z});
        at.put(ORIENTATION, new int[]{site.x + 2, y, site.z});
        for (Map.Entry<String, int[]> controller : at.entrySet()) {
            int[] p = controller.getValue();
            arrange("stellurgytest place " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " "
                    + blockOf(controller.getKey()));
        }
        standSenderAt(OVERWORLD, site.x + 1, y, site.z + NEAR);
        arrange("stellurgytest client close-screen");

        Map<String, Integer> held = targets(at);
        Map<String, Integer> totals = totals(at);
        for (String name : at.keySet()) {
            requireArranged("a placed " + name + " controller must not already hold its slider's end, or"
                    + " a refused target could not be told from a taken one: held " + held + ", ends "
                    + totals, !held.get(name).equals(totals.get(name)));
        }

        for (String name : at.keySet()) {
            sendTarget(at.get(name), name, totals.get(name));
        }
        assertEquals("a slider packet from a sender with no screen open moved a controller's target",
                held, targets(at));

        openScreen(at.get(GRAVITY));
        Map<String, int[]> others = new LinkedHashMap<>(at);
        others.remove(GRAVITY);
        for (String name : others.keySet()) {
            sendTarget(at.get(name), name, totals.get(name));
        }
        Map<String, Integer> othersHeld = new LinkedHashMap<>(held);
        othersHeld.remove(GRAVITY);
        assertEquals("a slider packet sent through ANOTHER controller's screen moved this one's target",
                othersHeld, targets(others));

        for (String name : at.keySet()) {
            openScreen(at.get(name));
            sendTarget(at.get(name), name, totals.get(name));
        }
        assertEquals("a slider packet from a sender with the controller's own screen open must set its"
                + " target — or the refusals above say nothing about the screen", totals, targets(at));

        for (String name : at.keySet()) {
            openScreen(at.get(name));
            sendTarget(at.get(name), name, totals.get(name) + 1);
        }
        assertEquals("a target past the end of a controller's slider was taken", totals, targets(at));
        arrange("stellurgytest client close-screen");
    }

    /**
     * A redstone mode none of a controller's button presses can produce is dropped, and does not throw
     * on the server thread; a mode the button produces is taken.
     *
     * <p>Contract: fails if {@code TileStationGravityController#useNetworkData} or
     * {@code TileStationAltitudeController#useNetworkData} indexes {@code RedstoneState.values()} with a
     * client's byte it has not checked.</p>
     * <p>red-witnessed: with {@code TileStationGravityController#refusal} and {@code TileStationAltitudeController#refusal} at {@code return mode >= 0 && mode < RedstoneState.values().length ? null} made {@code return true ? null}, fails: "a redstone mode no button press produces must be dropped, not thrown on the server thread: {gravity=(error: ArrayIndexOutOfBoundsException: 3), altitude=(error: ArrayIndexOutOfBoundsException: 3)}" (2026-10-07).</p>
     * <p>red-witnessed: with the same two conditions made {@code return true ? null} and {@code TileStationGravityController#useNetworkData} and {@code TileStationAltitudeController#useNetworkData} at {@code state = RedstoneState.values()[nbt.getByte("state")];} clamping the byte to the last mode instead, fails: "a redstone mode no button press produces changed a controller's mode expected:&lt;{gravity=1, altitude=1}&gt; but was:&lt;{gravity=2, altitude=2}&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code PacketSenderCheck#hasScreenOpen} at {@code return sender != null && sender.openContainer instanceof ContainerModular} made {@code return false;}, fails: "a mode the button produces, from its screen, must be taken — or the drop above says nothing about the mode expected:&lt;{gravity=2, altitude=2}&gt; but was:&lt;{gravity=1, altitude=1}&gt;" (2026-10-07).</p>
     */
    @Test
    public void aRedstoneModeNoButtonProducesIsDroppedNotThrown() throws Exception {
        FixtureSite site = clearedSite(0, 1, "the two redstone-driven controllers stand in this volume");
        int y = site.y + 1;
        Map<String, int[]> at = new LinkedHashMap<>();
        at.put(GRAVITY, new int[]{site.x, y, site.z});
        at.put(ALTITUDE, new int[]{site.x + 1, y, site.z});
        for (Map.Entry<String, int[]> controller : at.entrySet()) {
            int[] p = controller.getValue();
            arrange("stellurgytest place " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " "
                    + blockOf(controller.getKey()));
        }
        standSenderAt(OVERWORLD, site.x, y, site.z + NEAR);
        Map<String, Integer> modes = modes(at);
        for (String name : at.keySet()) {
            requireArranged("a placed " + name + " controller starts in redstone mode OFF: " + modes,
                    modes.get(name) == RedstoneState.OFF.ordinal());
        }

        int noMode = RedstoneState.values().length;
        Map<String, String> thrown = new LinkedHashMap<>();
        for (String name : at.keySet()) {
            openScreen(at.get(name));
            Reply sent = sendMode(at.get(name), noMode);
            if (!sent.ok()) {
                thrown.put(name, sent.toString());
            }
        }
        assertTrue("a redstone mode no button press produces must be dropped, not thrown on the server"
                + " thread: " + thrown, thrown.isEmpty());
        Map<String, Integer> off = new LinkedHashMap<>();
        for (String name : at.keySet()) {
            off.put(name, RedstoneState.OFF.ordinal());
        }
        assertEquals("a redstone mode no button press produces changed a controller's mode", off, modes(at));

        Map<String, Integer> inverted = new LinkedHashMap<>();
        for (String name : at.keySet()) {
            openScreen(at.get(name));
            sendMode(at.get(name), RedstoneState.INVERTED.ordinal());
            inverted.put(name, RedstoneState.INVERTED.ordinal());
        }
        assertEquals("a mode the button produces, from its screen, must be taken — or the drop above says"
                + " nothing about the mode", inverted, modes(at));
        arrange("stellurgytest client close-screen");
    }

    /**
     * A rocket assembler takes its stored energy and build progress from nobody — they are the server's
     * to tell a client — and its build button only from a sender with its screen open.
     *
     * <p>Contract: fails if {@code TileRocketAssemblingMachine#useNetworkData} takes packet 2 (energy and
     * progress) from a client at all, or packet 1 (build) from a sender who does not have its screen
     * open.</p>
     * <p>Not asserted: packet 3 (link a rocket to the assembler's infrastructure). Its effect needs an
     * infrastructure block and a rocket, neither of which stands here, so a taken packet 3 would change
     * nothing this scenario can read; it is refused by the same predicate as packet 2, and nothing here
     * goes red if that predicate is changed for 3 alone.</p>
     * <p>red-witnessed: with {@code TileRocketAssemblingMachine#acceptsFromClient} at {@code return (id == 0 || id == 1) && PacketSenderCheck.hasScreenOpen(player, this);} made {@code return true;}, fails: "a client's packet set the assembler's stored energy and build progress, which are the server's to send expected:&lt;{pwr=0.0, tik=0.0}&gt; but was:&lt;{pwr=5000.0, tik=7.0}&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code PacketSenderCheck#hasScreenOpen} at {@code return sender != null && sender.openContainer instanceof ContainerModular} cut to {@code return sender != null;}, fails: "a build press from a sender with no screen open started the assembler building" (2026-10-07).</p>
     * <p>red-witnessed: with {@code PacketSenderCheck#hasScreenOpen} at {@code return sender != null && sender.openContainer instanceof ContainerModular} made {@code return false;}, fails: "a build press from a sender with the assembler's screen open must be taken — or the refusal above says nothing about the screen" (2026-10-07).</p>
     */
    @Test
    public void anAssemblerTakesNoStateFromAClientAndItsButtonsOnlyFromItsScreen() throws Exception {
        FixtureSite site = clearedSite(0, 1, "the assembler stands in this volume");
        int[] p = {site.x, site.y + 1, site.z};
        arrange("stellurgytest place " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " " + ASSEMBLER);
        standSenderAt(OVERWORLD, site.x, site.y + 1, site.z + NEAR);

        Map<String, Double> state = assemblerState(p);
        requireArranged("a placed assembler holds no energy and no progress, so the values below stand"
                + " apart from it: " + state, state.get("pwr") == 0d && state.get("tik") == 0d);
        openScreen(p);
        arrange("stellurgytest client machine " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " "
                + ASSEMBLER_STATE_PACKET + " int:" + SOME_ENERGY + " int:" + SOME_PROGRESS);
        assertEquals("a client's packet set the assembler's stored energy and build progress, which are the"
                + " server's to send", state, assemblerState(p));

        arrange("stellurgytest client close-screen");
        requireArranged("a placed assembler is not building", !building(p));
        arrange("stellurgytest client machine " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " "
                + ASSEMBLER_BUILD_PACKET);
        assertFalse("a build press from a sender with no screen open started the assembler building",
                building(p));

        openScreen(p);
        arrange("stellurgytest client machine " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " "
                + ASSEMBLER_BUILD_PACKET);
        assertTrue("a build press from a sender with the assembler's screen open must be taken — or the"
                + " refusal above says nothing about the screen", building(p));
        arrange("stellurgytest client close-screen");
    }

    /**
     * A station's warp monitor takes a destination from a sender only while he has one of its screens
     * open.
     *
     * <p>Contract: fails if {@code TileWarpController#useNetworkData} takes a destination (packet 1) from a
     * sender who has none of its screens open.</p>
     * <p>red-witnessed: with {@code PacketSenderCheck#hasScreenOpen} at {@code return sender != null && sender.openContainer instanceof ContainerModular} cut to {@code return sender != null;}, fails: "a destination sent by a player with none of the monitor's screens open was taken expected:&lt;0&gt; but was:&lt;10000&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code PacketSenderCheck#hasScreenOpen} at {@code return sender != null && sender.openContainer instanceof ContainerModular} made {@code return false;}, fails: "a destination sent through the monitor's own selection screen must be taken — or the refusal above says nothing about the screen expected:&lt;10000&gt; but was:&lt;0&gt;" (2026-10-07).</p>
     */
    @Test
    public void aWarpMonitorTakesADestinationOnlyFromItsOpenScreen() throws Exception {
        int spaceDim = ask("stellurgytest client space-dim").integer("spaceDimId");
        Reply created = arrange("stellurgytest station create " + OVERWORLD);
        int station = created.integer("id");
        arrange("stellurgytest station set-parent " + station + " " + OVERWORLD);
        Reply info = ask("stellurgytest station info " + station);
        requireArranged("a created station reports where it stands: " + info, info.has("spawnX"));
        int[] p = {info.integer("spawnX"), info.integer("spawnY"), info.integer("spawnZ")};
        int sol = Constants.STAR_ID_OFFSET + SOL;
        requireArranged("the station must not already be headed for Sol, or a refused destination could"
                + " not be told from a taken one: " + info, info.integer("destOrbitingBody") != sol);
        int dest = info.integer("destOrbitingBody");

        standSenderAt(spaceDim, p[0], p[1], p[2] + NEAR);
        // Nothing keeps a station's chunk loaded for a headless sender, and a machine packet for a block
        // whose chunk is not loaded is dropped without a word — which would read here as a refusal. The
        // chunk is held loaded for the scenario, and read as loaded before each packet relies on it.
        arrange("stellurgytest chunk forceload " + spaceDim + " " + (p[0] >> 4) + " " + (p[2] >> 4));
        arrange("stellurgytest place " + spaceDim + " " + p[0] + " " + p[1] + " " + p[2] + " " + WARP_MONITOR);
        // The monitor's planet selector is built with its selection screen, and a destination is taken
        // into it; the sender opens it once and closes it, so the monitor has a selector to take one into.
        openScreen(spaceDim, GuiHandler.guiId.MODULARFULLSCREEN.ordinal(), p);
        arrange("stellurgytest client close-screen");

        requireMonitorLoaded(spaceDim, p);
        sendDestination(spaceDim, p, sol);
        assertEquals("a destination sent by a player with none of the monitor's screens open was taken",
                dest, ask("stellurgytest station info " + station).integer("destOrbitingBody"));

        openScreen(spaceDim, GuiHandler.guiId.MODULARFULLSCREEN.ordinal(), p);
        requireMonitorLoaded(spaceDim, p);
        sendDestination(spaceDim, p, sol);
        assertEquals("a destination sent through the monitor's own selection screen must be taken — or the"
                + " refusal above says nothing about the screen",
                sol, ask("stellurgytest station info " + station).integer("destOrbitingBody"));
        arrange("stellurgytest client close-screen");
        arrange("stellurgytest chunk release " + spaceDim + " " + (p[0] >> 4) + " " + (p[2] >> 4));
    }

    private void requireMonitorLoaded(int dim, int[] p) throws Exception {
        Reply chunk = arrange("stellurgytest chunk loaded " + dim + " " + (p[0] >> 4) + " " + (p[2] >> 4));
        requireArranged("the monitor's chunk must be loaded, or its packet is dropped before the monitor"
                + " sees it: " + chunk, chunk.bool("loaded"));
    }

    // ---- the force-field channel --------------------------------------------------------------

    /**
     * A field generator takes a new radius only from a sender with its screen open.
     *
     * <p>Contract: fails if {@code PacketSetFieldRadius.Handler#apply} applies a radius for a sender who
     * does not have that generator's screen open — the radius used to be taken for any loaded generator
     * of the sender's world, from anywhere in it.</p>
     * <p>red-witnessed: with {@code Handler#apply} (of {@code PacketSetFieldRadius}) at {@code if (generator == null)} preceded by taking the generator from {@code player.world.getTileEntity(message.pos)} when no screen shows one, fails: "a radius sent by a player with no screen open changed the generator expected:&lt;4&gt; but was:&lt;16&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code ContainerFieldGenerator#generatorAt} at {@code return tile != null && tile.getPos().equals(pos) && canInteractWith(player) ? tile : null;} made {@code return null;}, fails: "a radius sent through the generator's own screen must be taken — or the refusal above says nothing about the screen expected:&lt;16&gt; but was:&lt;4&gt;" (2026-10-07).</p>
     */
    @Test
    public void aFieldGeneratorTakesARadiusOnlyFromItsOpenScreen() throws Exception {
        FixtureSite site = clearedSite(0, 1, "the field generator stands in this volume");
        int[] p = {site.x, site.y + 1, site.z};
        arrange("stellurgytest place " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " " + FIELD_GENERATOR);
        standSenderAt(OVERWORLD, p[0], p[1], p[2] + NEAR);
        arrange("stellurgytest client close-screen");
        int declared = declaredRadius(p);
        requireArranged("a placed generator holds the default radius, below the largest it can take: "
                + declared, declared == TileEntityFieldGenerator.DEFAULT_RADIUS
                && declared != TileEntityFieldGenerator.MAX_RADIUS);

        arrange("stellurgytest client affs radius " + p[0] + " " + p[1] + " " + p[2] + " "
                + TileEntityFieldGenerator.MAX_RADIUS);
        assertEquals("a radius sent by a player with no screen open changed the generator",
                declared, declaredRadius(p));

        openScreen(OVERWORLD, AffsGuiRouter.AFFS_GUI_BASE + AdvancedForceFieldSystem.GUI_FIELD_GENERATOR, p);
        arrange("stellurgytest client affs radius " + p[0] + " " + p[1] + " " + p[2] + " "
                + TileEntityFieldGenerator.MAX_RADIUS);
        assertEquals("a radius sent through the generator's own screen must be taken — or the refusal above"
                + " says nothing about the screen", TileEntityFieldGenerator.MAX_RADIUS, declaredRadius(p));
        arrange("stellurgytest client close-screen");
    }

    /**
     * A shield console takes a resistance bias only from a sender with its screen open, and neither
     * that packet nor a request to open its network map makes the server load the chunk the client
     * names.
     *
     * <p>Contract: fails if {@code PacketSetShieldResistanceBias.Handler#apply} applies a bias for a
     * sender who does not have that console's screen open, or if it or {@code PacketOpenGui.Handler#apply}
     * looks a client's position up in the world — which loads, or generates, the chunk there.</p>
     * <p>red-witnessed: with {@code Handler#apply} (of {@code PacketSetShieldResistanceBias}) at {@code if (console == null)} preceded by taking the console from {@code player.world.getTileEntity(message.pos)}, where that block is loaded, when no screen shows one, fails: "a bias sent by a player with no screen open changed the console expected:&lt;0.5&gt; but was:&lt;0.0&gt;" (2026-10-07).</p>
     * <p>red-witnessed: with {@code Handler#apply} (of {@code PacketSetShieldResistanceBias}) at {@code if (console == null)} preceded by a bare {@code player.world.getTileEntity(message.pos);}, fails: "a bias packet naming a far position made the server load the chunk there" (2026-10-07).</p>
     * <p>red-witnessed: with {@code Handler#apply} (of {@code PacketOpenGui}) at {@code if (message.guiId != AdvancedForceFieldSystem.GUI_NETWORK_MAP)} preceded by a bare {@code player.world.getTileEntity(message.pos);}, fails: "a request to open a console's map at a far position made the server load the chunk there" (2026-10-07).</p>
     * <p>red-witnessed: with {@code ContainerShieldConsole#consoleAt} at {@code return tile != null && tile.getPos().equals(pos) && canInteractWith(player) ? tile : null;} made {@code return null;}, fails: "a bias sent through the console's own screen must be taken — or the refusal above says nothing about the screen expected:&lt;0.0&gt; but was:&lt;0.5&gt;" (2026-10-07).</p>
     */
    @Test
    public void aShieldConsoleTakesItsPacketsOnlyFromItsOpenScreen() throws Exception {
        FixtureSite site = clearedSite(0, 1, "the shield console stands in this volume");
        int[] p = {site.x, site.y + 1, site.z};
        arrange("stellurgytest place " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " " + SHIELD_CONSOLE);
        standSenderAt(OVERWORLD, p[0], p[1], p[2] + NEAR);
        arrange("stellurgytest client close-screen");
        double bias = consoleBias(p);
        double other = bias < 0.5d ? 1d : 0d;

        arrange("stellurgytest client affs bias " + p[0] + " " + p[1] + " " + p[2] + " " + other);
        assertEquals("a bias sent by a player with no screen open changed the console", bias, consoleBias(p), 0d);

        int farX = p[0] + FAR_CHUNKS * 16, farZ = p[2] + FAR_CHUNKS * 16;
        requireArranged("the far position's chunk must not be loaded yet, or nothing below could show a"
                + " load", !chunkLoaded(farX, farZ));
        requireArranged("the sender's own chunk reads as loaded, so the instrument can say loaded at all",
                chunkLoaded(p[0], p[2]));
        arrange("stellurgytest client affs bias " + farX + " " + p[1] + " " + farZ + " " + other);
        assertFalse("a bias packet naming a far position made the server load the chunk there",
                chunkLoaded(farX, farZ));
        arrange("stellurgytest client affs open " + AdvancedForceFieldSystem.GUI_NETWORK_MAP + " " + farX
                + " " + p[1] + " " + farZ);
        assertFalse("a request to open a console's map at a far position made the server load the chunk"
                + " there", chunkLoaded(farX, farZ));

        openScreen(OVERWORLD, AffsGuiRouter.AFFS_GUI_BASE + AdvancedForceFieldSystem.GUI_SHIELD_CONSOLE, p);
        arrange("stellurgytest client affs bias " + p[0] + " " + p[1] + " " + p[2] + " " + other);
        assertEquals("a bias sent through the console's own screen must be taken — or the refusal above says"
                + " nothing about the screen", other, consoleBias(p), 0d);
        arrange("stellurgytest client close-screen");
    }

    // ---- instruments --------------------------------------------------------------------------

    /** Give the sender the screen of the machine at {@code p} in the overworld, as its block opens it. */
    private void openScreen(int[] p) throws Exception {
        openScreen(OVERWORLD, GuiHandler.guiId.MODULAR.ordinal(), p);
    }

    private void openScreen(int dim, int guiId, int[] p) throws Exception {
        arrange("stellurgytest client screen " + guiId + " " + dim + " " + p[0] + " " + p[1] + " " + p[2]);
    }

    /** The block each station controller is, by the name its readings are filed under. */
    private static String blockOf(String controller) {
        if (GRAVITY.equals(controller)) {
            return "stellurgy:gravityController";
        }
        if (ALTITUDE.equals(controller)) {
            return "stellurgy:altitudeController";
        }
        return "stellurgy:orientationController";
    }

    /**
     * A controller's slider packet carrying {@code target}. The orientation controller's carries all three
     * axes; its first gets the target and the other two what they already hold.
     */
    private void sendTarget(int[] p, String name, int target) throws Exception {
        String words = ORIENTATION.equals(name)
                ? "short:" + target + " short:" + sliderAt(p, 1).integer("progress")
                        + " short:" + sliderAt(p, 2).integer("progress")
                : "short:" + target;
        arrange("stellurgytest client machine " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " "
                + SLIDER_PACKET + " " + words);
    }

    private Reply sendMode(int[] p, int mode) throws Exception {
        return ask("stellurgytest client machine " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " "
                + REDSTONE_PACKET + " byte:" + mode);
    }

    private void sendDestination(int dim, int[] p, int body) throws Exception {
        arrange("stellurgytest client machine " + dim + " " + p[0] + " " + p[1] + " " + p[2] + " "
                + WARP_DESTINATION_PACKET + " int:" + body);
    }

    /** Each controller's target: its slider position, the first axis for the orientation controller. */
    private Map<String, Integer> targets(Map<String, int[]> at) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> controller : at.entrySet()) {
            out.put(controller.getKey(), slider(controller.getValue()).integer("progress"));
        }
        return out;
    }

    private Map<String, Integer> totals(Map<String, int[]> at) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> controller : at.entrySet()) {
            out.put(controller.getKey(), slider(controller.getValue()).integer("total"));
        }
        return out;
    }

    private Reply slider(int[] p) throws Exception {
        return sliderAt(p, 0);
    }

    private Reply sliderAt(int[] p, int id) throws Exception {
        return arrange("stellurgytest client slider " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2] + " " + id);
    }

    /** Each controller's redstone mode as its own {@code writeToNBT} saves it. */
    private Map<String, Integer> modes(Map<String, int[]> at) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> controller : at.entrySet()) {
            int[] p = controller.getValue();
            Reply saved = arrange("stellurgytest client tile-byte " + OVERWORLD + " " + p[0] + " " + p[1] + " "
                    + p[2] + " redstoneState");
            requireArranged("a controller saves its redstone mode: " + saved, saved.bool("present"));
            out.put(controller.getKey(), saved.integer("value"));
        }
        return out;
    }

    /** The assembler's stored energy and build progress, as its own writer sends them to a client. */
    private Map<String, Double> assemblerState(int[] p) throws Exception {
        Reply read = arrange("stellurgytest client machine-state " + OVERWORLD + " " + p[0] + " " + p[1] + " "
                + p[2] + " " + ASSEMBLER_STATE_PACKET);
        Map<String, Double> out = new LinkedHashMap<>();
        Reply values = Reply.of(read.object("read"));
        out.put("pwr", values.number("pwr"));
        out.put("tik", values.number("tik"));
        return out;
    }

    private boolean building(int[] p) throws Exception {
        Reply saved = arrange("stellurgytest client tile-byte " + OVERWORLD + " " + p[0] + " " + p[1] + " "
                + p[2] + " building");
        requireArranged("an assembler saves whether it is building: " + saved, saved.bool("present"));
        return saved.integer("value") != 0;
    }

    private int declaredRadius(int[] p) throws Exception {
        Reply read = ask("stellurgytest shield read " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2]);
        requireArranged("the block must read as a field generator: " + read, read.has("declaredRadius"));
        return read.integer("declaredRadius");
    }

    private double consoleBias(int[] p) throws Exception {
        return arrange("stellurgytest shield console-info " + OVERWORLD + " " + p[0] + " " + p[1] + " " + p[2])
                .number("resistanceBias");
    }

    /** Whether the overworld chunk holding block {@code (x, z)} is in memory, asked without loading it. */
    private boolean chunkLoaded(int x, int z) throws Exception {
        return arrange("stellurgytest chunk loaded " + OVERWORLD + " " + (x >> 4) + " " + (z >> 4)).bool("loaded");
    }

    /** Put the headless test player — the sender of every packet here — at a point of a world. */
    private void standSenderAt(int dim, int x, int y, int z) throws Exception {
        arrange("stellurgytest player ensure-fake " + dim + " " + (x + 0.5) + " " + y + " " + (z + 0.5));
    }

    /** The sender's priority packet for the port at {@code (x, y, z)} of {@code dim}, delivered. */
    private void sendPriority(int dim, int x, int y, int z, int priority) throws Exception {
        arrange("stellurgytest packet machine " + dim + " " + x + " " + y + " " + z + " " + PRIORITY_PACKET
                + " " + priority);
    }

    /** The zone priority the port at that position holds. */
    private int priorityAt(int dim, int x, int y, int z) throws Exception {
        return arrange("stellurgytest vent priority " + dim + " " + x + " " + y + " " + z).integer("priority");
    }

    /** The machine id on the projector in the sender's hand; refuses when the item carries none. */
    private int heldMachineId() throws Exception {
        Reply held = arrange("stellurgytest packet held machineId");
        requireArranged("the sender must be holding the projector, with a machine id on it: " + held,
                PROJECTOR.equalsIgnoreCase(held.text("item")) && held.bool("present"));
        return held.integer("value");
    }

    /** Project the furnace facing north with its base at the near corner of {@code site}'s volume. */
    private void project(FixtureSite site) throws Exception {
        arrange("stellurgytest packet item " + PROJECT + " " + site.x + " " + (site.y + 1) + " "
                + (site.z + FURNACE_EDGE - 1) + " " + NORTH);
    }

    /** How many ghost blocks stand where the furnace would be laid at {@code site}. */
    private int ghostsIn(FixtureSite site) throws Exception {
        return arrange(ghostsCommand(site)).integer("count");
    }

    private static String ghostsCommand(FixtureSite site) {
        return "stellurgytest packet ghosts " + site.dim + " " + site.x + " " + (site.y + 1) + " " + site.z
                + " " + (site.x + FURNACE_EDGE - 1) + " " + (site.y + FURNACE_LAYERS) + " "
                + (site.z + FURNACE_EDGE - 1);
    }
}
