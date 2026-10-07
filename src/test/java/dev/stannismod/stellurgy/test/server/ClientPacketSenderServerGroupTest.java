package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which client packets of the mod's channel the server believes: a machine packet, a held-item packet
 * and the decoders of both entity-shaped packets, each sent by a player the server must not take at
 * his word.
 *
 * <p>NEW-GROUP: the server half of the mod channel for packets a CLIENT sends — the checks a packet
 * passes before it touches anything: the target in the sender's own world, the sender within reach of
 * it, a held item that only names what exists, bytes that decode. No server group holds it: every other
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

    // ---- instruments --------------------------------------------------------------------------

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
