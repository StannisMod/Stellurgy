package dev.stannismod.stellurgy.test.trace;

import java.util.Locale;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import dev.stannismod.stellurgy.api.RocketEvent;

/**
 * The Forge-bus half of the server's {@link ServerEventLog}: events the game already posts, turned
 * into ordered records.
 *
 * <p>Deliberately nothing but bus subscriptions: an observation a test wants is a test mixin or the
 * Forge bus, never a line added to production logic, because a shipped game must pay nothing for
 * it.</p>
 *
 * <h2>Stateless, and subscribed as a CLASS</h2>
 *
 * <p>Every handler is static and writes into the log of the server the event belongs to, resolved
 * per event — so there is no recorder object to own, and nothing here outlives a server. The class
 * is registered on the bus by {@link #attach}, which the mod's test-probe registration calls by name
 * at every server start; Forge keys a registration by its target, so registering the same class a
 * second time (a second integrated server in one client JVM) is a no-op.</p>
 *
 * <p>Registered from there and not from anything on the test side because that is the one point at
 * which the bus knows which mod is registering: Forge reads the active mod container during
 * {@code register}, and when it finds none it logs a "should be impossible" error with a stack
 * trace.</p>
 *
 * <h2>Which side a record names</h2>
 *
 * <p>On an integrated client the same bus carries both sides' events, so each record carries the
 * side of the event's own world and a reader filters on it. A client-world event is written into the
 * log of the server running in the same JVM — the only log the probe there can read — and dropped
 * when there is none. Every handler announces its instrument FIRST, above any filter, so "nothing
 * recorded" is distinguishable from "no handler ran".</p>
 *
 * <p>These are BUS subscribers, not mixins: the game posts every event below whether or not the
 * test-only mixin configuration was accepted. But the log they write into hangs on the server by a
 * test mixin, so without that configuration there is nothing to record into, and {@link #attach}
 * says so instead of subscribing.</p>
 */
public final class ServerEventRecorder {

    private ServerEventRecorder() {
    }

    /**
     * Subscribe the recorder and start {@code server}'s log. Called reflectively, by name, from the
     * mod's test-probe registration at server start — a released jar carries no such class, and the
     * caller treats its absence as "not a test JVM".
     */
    public static void attach(MinecraftServer server) {
        if (!(server instanceof SideTraceOwner)) {
            System.out.println("[stellurgytest] the server carries no test trace, so the event log"
                    + " has nowhere to record: the test mixin configuration was not applied in this"
                    + " JVM. Readers will answer recording:false, mixins:false.");
            return;
        }
        MinecraftForge.EVENT_BUS.register(ServerEventRecorder.class);
        ServerEventLog.of(server).startRecording();
    }

    /** The log an event on {@code world} is written into, or null when there is none to write to. */
    private static ServerEventLog logFor(World world) {
        if (world != null && !world.isRemote) {
            MinecraftServer server = world.getMinecraftServer();
            return server instanceof SideTraceOwner ? ServerEventLog.of(server) : null;
        }
        return ServerEventLog.current();
    }

    private static void instrument(World world, String name) {
        ServerEventLog log = logFor(world);
        if (log != null) {
            log.noteInstrumentEntered(name);
        }
    }

    private static void record(World world, String type, String payload) {
        ServerEventLog log = logFor(world);
        if (log != null) {
            log.record(sideOf(world), world == null ? 0L : world.getTotalWorldTime(), type, payload);
        }
    }

    /**
     * A right-click that REACHED the server. Its absence is the signal: the reach check upstream
     * drops a click from too far away before this event is ever fired.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        BlockPos p = event.getPos();
        record(event.getWorld(), "right_click_block",
                "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                        + ",\"player\":\"" + event.getEntityPlayer().getName() + "\"");
    }

    /**
     * The bed attempt, WITH the result a handler put on it.
     *
     * <p>{@code LOWEST} on purpose: the result is what the last handler leaves, and Stellurgy's own
     * planet handler is one of them. Reading it earlier would record a verdict nobody reached.
     * A result of {@code null} means no handler objected — which is not the same as "he slept",
     * because vanilla's own gates run after this event; that half of the story is
     * {@code player_wake_up}.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSleepInBed(PlayerSleepInBedEvent event) {
        BlockPos p = event.getPos();
        record(event.getEntityPlayer().world, "sleep_in_bed",
                "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                        + ",\"result\":\"" + (event.getResultStatus() == null
                                ? "none" : event.getResultStatus().name()) + "\""
                        + ",\"player\":\"" + event.getEntityPlayer().getName() + "\"");
    }

    /** He was asleep and is not any more — the only bus-visible proof the sleep happened. */
    @SubscribeEvent
    public static void onWakeUp(PlayerWakeUpEvent event) {
        record(event.getEntityPlayer().world, "player_wake_up",
                "\"player\":\"" + event.getEntityPlayer().getName() + "\"");
    }

    /**
     * A container GUI was OPENED for a player — the server-side fact behind every machine
     * GUI test ({@code PlayerContainerEvent.Open}, fired from
     * {@code EntityPlayerMP.displayGUIChest/displayGui} after the open-window packet is sent,
     * and for a modded GUI from {@code FMLNetworkHandler.openGui}). The client's
     * {@code GuiScreen} is a separate fact this says nothing about.
     *
     * <p><b>Its blind spot is the whole reason it is paired with {@code gui_container_served}.</b>
     * {@code FMLNetworkHandler.openGui} posts this event only INSIDE the branch where the mod's
     * GUI handler returned a non-null container; a handler that answers null opens nothing and
     * fires nothing, so an absence here cannot tell "the player never asked" from "the handler
     * refused". {@code gui_container_served} is recorded at the handler's own return and
     * separates the two.</p>
     */
    @SubscribeEvent
    public static void onContainerOpened(PlayerContainerEvent.Open event) {
        instrument(event.getEntityPlayer().world, "server_bus_container_opened");
        recordContainer("container_opened", event);
    }

    /**
     * The matching CLOSE ({@code PlayerContainerEvent.Close}, from {@code EntityPlayerMP.closeContainer}
     * and {@code closeScreen}). A logout closes the open container too and lands here.
     */
    @SubscribeEvent
    public static void onContainerClosed(PlayerContainerEvent.Close event) {
        instrument(event.getEntityPlayer().world, "server_bus_container_closed");
        recordContainer("container_closed", event);
    }

    private static void recordContainer(String type, PlayerContainerEvent event) {
        EntityPlayer who = event.getEntityPlayer();
        record(who.world, type,
                "\"who\":\"" + str(who.getName()) + "\""
                        + ",\"container\":\"" + str(event.getContainer() == null ? "null"
                                : event.getContainer().getClass().getSimpleName()) + "\""
                        + ",\"windowId\":" + (event.getContainer() == null
                                ? -1 : event.getContainer().windowId));
    }

    /**
     * An entity was ADDED to a server world ({@code EntityJoinWorldEvent}) — the moment a spawn,
     * a dimension arrival or a chunk load makes it exist there. Deliberately SERVER ONLY: the
     * client's copy of the same entity joins its world on its own clock and would double every
     * record on an integrated client. {@code EntityItem} and {@code EntityXPOrb} are skipped,
     * because a block break or a mob death spawns dozens of them and the ring is bounded; every
     * other class is kept, so a reader filters on {@code cls}. Silent about WHY the entity
     * joined (spawn vs. load vs. transfer) — the event does not carry that.
     */
    @SubscribeEvent
    public static void onEntityJoinedWorld(EntityJoinWorldEvent event) {
        World world = event.getWorld();
        instrument(world, "server_bus_entity_joined_world");
        Entity e = event.getEntity();
        if (world == null || world.isRemote || e == null
                || e instanceof EntityItem || e instanceof EntityXPOrb) {
            return;
        }
        record(world, "entity_joined_world",
                "\"e\":" + e.getEntityId()
                        + ",\"cls\":\"" + str(e.getClass().getSimpleName()) + "\""
                        + ",\"x\":" + num(e.posX) + ",\"y\":" + num(e.posY)
                        + ",\"z\":" + num(e.posZ)
                        + ",\"dim\":" + world.provider.getDimension());
    }

    /**
     * A right-click WITH AN ITEM that reached this side ({@code PlayerInteractEvent.RightClickItem},
     * fired from {@code PlayerInteractionManager.processRightClick} on the server and from the
     * client's own use path) — the sibling of {@code right_click_block} above, for an item used
     * in the air. Records the item's registry name, or {@code empty}. Silent about whether the
     * item's use succeeded: that is the item's own result, which the event does not carry.
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        instrument(event.getWorld(), "server_bus_right_click_item");
        EntityPlayer who = event.getEntityPlayer();
        ItemStack stack = event.getItemStack();
        String item = stack == null || stack.isEmpty() || stack.getItem().getRegistryName() == null
                ? "empty" : stack.getItem().getRegistryName().toString();
        record(event.getWorld(), "right_click_item",
                "\"who\":\"" + str(who.getName()) + "\""
                        + ",\"hand\":\"" + (event.getHand() == null ? "null" : event.getHand().name()) + "\""
                        + ",\"item\":\"" + str(item) + "\"");
    }

    /**
     * A PLAYER took damage ({@code LivingHurtEvent}, fired from {@code EntityLivingBase.damageEntity}
     * after armour and before absorption) — the witness a fall-through or a vacuum test wants,
     * with the damage type it came with ({@code fall}, {@code outOfWorld}, Stellurgy's own suit and
     * atmosphere sources). Players only: mobs would turn this ring over on any surface with a
     * cactus. Silent about the damage actually APPLIED — later handlers may still change
     * {@code amount}, and this records it as it stood at default priority.
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        instrument(event.getEntityLiving() == null ? null : event.getEntityLiving().world,
                "server_bus_living_hurt");
        if (!(event.getEntityLiving() instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer who = (EntityPlayer) event.getEntityLiving();
        String source = event.getSource() == null ? "null" : event.getSource().getDamageType();
        record(who.world, "living_hurt",
                "\"who\":\"" + str(who.getName()) + "\""
                        + ",\"source\":\"" + str(source) + "\""
                        + ",\"amount\":" + num(event.getAmount()));
    }

    /**
     * A rocket TOUCHED DOWN — Stellurgy's own {@code RocketEvent.RocketLandedEvent}, posted by
     * {@code EntityRocket} on the server at the free-flight touchdown, at the classic descent's
     * touchdown, and once after a load for a rocket that is already on the ground. The client
     * re-posts it on receipt of the land packet, so on an integrated client the same landing
     * appears twice with different sides; a reader filters on the side it means. Silent about
     * WHICH of the three server sites posted it — the event carries only the entity.
     */
    @SubscribeEvent
    public static void onRocketLanded(RocketEvent.RocketLandedEvent event) {
        Entity e = event.getEntity();
        World world = e == null ? event.world : e.world;
        instrument(world, "server_bus_rocket_landed");
        if (world == null) {
            return;
        }
        record(world, "rocket_landed",
                "\"e\":" + (e == null ? -1 : e.getEntityId())
                        + ",\"y\":" + num(e == null ? Double.NaN : e.posY)
                        + ",\"onGround\":" + (e != null && e.onGround));
    }

    /**
     * A rocket REFUSED to launch — Stellurgy's own {@code RocketEvent.RocketAbortEvent}, which
     * {@code EntityRocket.setError} posts on the server for every launch refusal (cannot get there,
     * outside the planetary system, too heavy, not enough fuel, worn parts, …) and posts nowhere else.
     * The record carries the entity and the {@code reason} exactly as production packed it — the
     * translation key, followed by {@code |}-separated arguments when the refusal has any — so a test
     * compares the key, never a rendering of it.
     *
     * <p>Silent about a refusal that never reaches {@code setError} — a pre-launch event cancelled by
     * another subscriber, a free-flight start rejected through {@code messagePilot} — and about a
     * client-side copy: {@code setError} posts only when the rocket's world is the server's.</p>
     */
    @SubscribeEvent
    public static void onRocketAborted(RocketEvent.RocketAbortEvent event) {
        Entity e = event.getEntity();
        World world = e == null ? event.world : e.world;
        instrument(world, "server_bus_rocket_aborted");
        if (world == null) {
            return;
        }
        record(world, "rocket_aborted",
                "\"e\":" + (e == null ? -1 : e.getEntityId())
                        + ",\"dim\":" + world.provider.getDimension()
                        + ",\"reason\":\"" + str(String.valueOf(event.reason)) + "\"");
    }

    /**
     * A launch was ACCEPTED — {@code RocketEvent.RocketLaunchEvent}. {@code EntityRocket.launch}
     * posts it on the server only after every refusal that would reach {@link #onRocketAborted} has
     * been passed, so for a tier-1 rocket the two are the two outcomes of one decision. The elevator
     * capsule and the station-deployed rocket post the same event from their own launch paths, which
     * is why the record names the entity: a reader narrows by {@code e}.
     *
     * <p>Silent about the destination (the event carries only the entity) and about a free-flight
     * start, which does not post this event.</p>
     */
    @SubscribeEvent
    public static void onRocketLaunched(RocketEvent.RocketLaunchEvent event) {
        Entity e = event.getEntity();
        World world = e == null ? event.world : e.world;
        instrument(world, "server_bus_rocket_launched");
        if (world == null || world.isRemote) {
            return;
        }
        record(world, "rocket_launched",
                "\"e\":" + (e == null ? -1 : e.getEntityId())
                        + ",\"dim\":" + world.provider.getDimension());
    }

    /**
     * An advancement was GRANTED to a player — the completion edge, not a step towards it.
     *
     * <p>Vanilla posts this from {@code PlayerAdvancements.grantCriterion} inside
     * {@code if (!flag1 && advancementprogress.isDone())}, so it fires once, on the tick the
     * advancement becomes done, and never for a criterion that merely advanced the progress.
     * That is exactly what a test asking "was it granted" wants, and it is why this is a
     * subscription rather than a mixin: Forge already publishes it for a non-test audience.</p>
     *
     * <p>Records the advancement's registry id and the player's name. Silent about WHY it was
     * granted — which criterion completed it is not on the event.</p>
     */
    @SubscribeEvent
    public static void onAdvancementGranted(
            net.minecraftforge.event.entity.player.AdvancementEvent event) {
        EntityPlayer who = event.getEntityPlayer();
        instrument(who == null ? null : who.world, "server_bus_advancement_granted");
        net.minecraft.advancements.Advancement advancement = event.getAdvancement();
        if (who == null || who.world == null || who.world.isRemote || advancement == null) {
            return;
        }
        record(who.world, "advancement_granted",
                "\"id\":\"" + str(String.valueOf(advancement.getId())) + "\""
                        + ",\"who\":\"" + str(who.getName()) + "\""
                        + ",\"dim\":" + who.world.provider.getDimension());
    }

    /**
     * A world was UNLOADED by Forge's own sweep.
     *
     * <p>{@code WorldEvent.Unload} is posted from {@code DimensionManager.unloadWorlds} after
     * the world has been saved and removed from the map, so by the time a reader sees this
     * record the world is gone — which is the claim a test about unloading is making. A poll on
     * "is the slot still loaded" asks the same question one sample at a time and can only ever
     * answer about the moment it happened to look.</p>
     *
     * <p>Server side only: an integrated client unloads its own copy on its own clock and would
     * double every record.</p>
     */
    @SubscribeEvent
    public static void onWorldUnloaded(net.minecraftforge.event.world.WorldEvent.Unload event) {
        World world = event.getWorld();
        instrument(world, "server_bus_world_unloaded");
        if (world == null || world.isRemote) {
            return;
        }
        // The world's own clock is read BEFORE anything else here: it is being torn down, and a
        // provider dereferenced a moment later is not guaranteed to answer.
        long tick = world.getTotalWorldTime();
        ServerEventLog log = logFor(world);
        if (log != null) {
            log.record("server", tick, "world_unloaded", "\"dim\":" + world.provider.getDimension());
        }
    }

    /**
     * A chunk LEFT the server's memory — {@code chunk_unloaded}.
     *
     * <p>Posted from {@code Chunk#onUnload}, which the chunk provider calls for a chunk it is dropping,
     * immediately before it writes that chunk to disk and forgets it, all inside one tick. So once
     * this record exists the chunk's tile entities are gone from the world, and whatever is read at
     * that position afterwards was read back from what was saved. That is what a persistence scenario
     * needs to know and could not otherwise tell: a reload that found the chunk still in memory reads
     * the very objects it wrote, and a save that was never exercised looks exactly like one that
     * worked.</p>
     *
     * <p>Server side only, for the same reason as {@link #onWorldUnloaded}. {@code cx}/{@code cz} are
     * chunk coordinates.</p>
     */
    @SubscribeEvent
    public static void onChunkUnloaded(net.minecraftforge.event.world.ChunkEvent.Unload event) {
        World world = event.getWorld();
        instrument(world, "server_bus_chunk_unloaded");
        if (world == null || world.isRemote || event.getChunk() == null) {
            return;
        }
        record(world, "chunk_unloaded", "\"dim\":" + world.provider.getDimension()
                + ",\"cx\":" + event.getChunk().x + ",\"cz\":" + event.getChunk().z);
    }

    /**
     * A ship became USABLE — its physics will be stepped from now on.
     *
     * <p>Production's own event, subscribed to like any other consumer would rather than
     * observed by a test mixin: this fact has a non-test audience and is published for it
     * ({@code ShipLifecycleEvent.ShipUsable}). That is why the recorded type is named for USABILITY
     * and not "loaded" — {@code ship_loaded} is already taken by the test mixin on the physics
     * object's CONSTRUCTOR, which is a weaker claim: a ship exists there and does not move yet.
     * A test that means "I can fly this now" wants this one.</p>
     *
     * <p>{@code ship} is the DURABLE id and {@code vsShip} the physics id; they are the same value
     * for a craft that kept its identity (one ship, one identity), and both are recorded so a reader
     * can match on either spelling without knowing that.</p>
     */
    @SubscribeEvent
    public static void onShipUsable(dev.stannismod.stellurgy.api.event.ShipLifecycleEvent.ShipUsable event) {
        World world = event.world;
        instrument(world, "server_bus_ship_usable");
        if (world == null) {
            return;
        }
        record(world, "ship_usable",
                "\"ship\":\"" + str(event.durableId == null ? null : event.durableId.toString()) + "\""
                        + ",\"vsShip\":\"" + event.shipUuid + "\""
                        + ",\"dim\":" + world.provider.getDimension());
    }

    /**
     * A ship was NAMED or UNNAMED, with the transition that did it — {@code ship_lifecycle}.
     *
     * <p>Production's own announcement, subscribed to like any consumer rather than counted by a
     * recorder of its own: the mass trigger arms on it, so it has a non-test audience. One record
     * per announcement, so "exactly once per transition" is a count over a window rather than a
     * tally somebody had to reset. {@code edge} is which half ({@code named}/{@code unnamed});
     * {@code durable} is the vessel's id, which survives a crossing where {@code ship} does not; and
     * a departure carries {@code destinationDim}, the one cause that has a destination.</p>
     *
     * <p>The usable edge is the third member of the family and is NOT recorded here: it carries no
     * cause, and it has its own type ({@code ship_usable}, above) that more than forty waits already
     * read.</p>
     */
    @SubscribeEvent
    public static void onShipNamed(dev.stannismod.stellurgy.api.event.ShipLifecycleEvent.ShipNamed event) {
        recordLifecycle(event, "named", event.cause, "");
    }

    @SubscribeEvent
    public static void onShipUnnamed(dev.stannismod.stellurgy.api.event.ShipLifecycleEvent.ShipUnnamed event) {
        recordLifecycle(event, "unnamed", event.cause,
                event instanceof dev.stannismod.stellurgy.api.event.ShipLifecycleEvent.ShipDeparted
                        ? ",\"destinationDim\":" + ((dev.stannismod.stellurgy.api.event.ShipLifecycleEvent
                                .ShipDeparted) event).destinationDim
                        : "");
    }

    private static void recordLifecycle(dev.stannismod.stellurgy.api.event.ShipLifecycleEvent event,
                                        String edge,
                                        dev.stannismod.stellurgy.api.event.ShipLifecycleEvent.Cause cause,
                                        String extra) {
        World world = event.world;
        instrument(world, "server_bus_ship_lifecycle");
        if (world == null) {
            return;
        }
        record(world, "ship_lifecycle",
                "\"ship\":\"" + event.shipUuid + "\""
                        + ",\"durable\":" + (event.durableId == null
                                ? "null" : "\"" + event.durableId + "\"")
                        + ",\"cause\":\"" + cause + "\""
                        + ",\"edge\":\"" + edge + "\""
                        + extra
                        + ",\"dim\":" + world.provider.getDimension());
    }

    /**
     * A flight computer REBUILT its ship's flight model — {@code flight_model_changed}.
     *
     * <p>Production's own announcement ({@code ShipEvent.FlightModelChangedEvent}), posted after
     * every survey the computer makes: a change to the hull, the load round, or a computer that had
     * no model yet. One record per announcement, so "the model has caught up with what I did to the
     * hull" is a link on the first record after a mark, and the readout is then read once.</p>
     *
     * <p>{@code ship} is the craft's durable name, {@code afcX/Y/Z} the computer's own address (a
     * subspace one on an assembled ship), {@code revision} this computer's rebuild count, and
     * {@code totalKg} the mass the new model was solved for — the one figure a cargo scenario links
     * on — and {@code liveSurgeN} the sustained forward authority of the actuators working now, the
     * figure a scenario that changes the hull's motors links on.</p>
     */
    @SubscribeEvent
    public static void onFlightModelChanged(
            dev.stannismod.stellurgy.api.event.ShipEvent.FlightModelChangedEvent event) {
        World world = event.world;
        instrument(world, "server_bus_flight_model_changed");
        if (world == null || event.readout == null) {
            return;
        }
        BlockPos p = event.pos;
        record(world, "flight_model_changed",
                "\"ship\":\"" + str(event.shipId) + "\""
                        + ",\"dim\":" + world.provider.getDimension()
                        + ",\"afcX\":" + p.getX() + ",\"afcY\":" + p.getY() + ",\"afcZ\":" + p.getZ()
                        + ",\"revision\":" + event.readout.revision()
                        + ",\"totalKg\":" + num(event.readout.totalMass())
                        + ",\"liveSurgeN\":" + num(event.readout.authority(
                                dev.stannismod.stellurgy.ship.control.ShipReadout.View.LIVE,
                                dev.stannismod.stellurgy.ship.control.ControlDirection.SURGE_POSITIVE,
                                dev.stannismod.stellurgy.ship.control.Endurance.SUSTAINED)));
    }

    /**
     * A player's break of a block, as it STANDS — {@code block_broken}.
     *
     * <p>{@code LOWEST} and not receiving cancelled events, so a record is written only for a
     * break no handler refused; vanilla removes the block right after this event returns. The
     * position is whatever the interaction path handed the server, which for a block of an
     * assembled ship is its SUBSPACE address — the one a test aimed at.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBroken(net.minecraftforge.event.world.BlockEvent.BreakEvent event) {
        World world = event.getWorld();
        instrument(world, "server_bus_block_broken");
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos p = event.getPos();
        record(world, "block_broken",
                "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                        + ",\"dim\":" + world.provider.getDimension()
                        + ",\"block\":\"" + event.getState().getBlock().getRegistryName() + "\""
                        + ",\"player\":\"" + (event.getPlayer() == null ? "" : event.getPlayer().getName())
                        + "\"");
    }

    /**
     * A player's placement of a block, as it STANDS — {@code block_placed}. Same priority and
     * the same reason as {@link #onBlockBroken}: a cancelled placement is reverted, so only one
     * no handler refused is recorded.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlaced(net.minecraftforge.event.world.BlockEvent.PlaceEvent event) {
        World world = event.getWorld();
        instrument(world, "server_bus_block_placed");
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos p = event.getPos();
        record(world, "block_placed",
                "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                        + ",\"dim\":" + world.provider.getDimension()
                        + ",\"block\":\"" + event.getPlacedBlock().getBlock().getRegistryName() + "\""
                        + ",\"player\":\"" + (event.getPlayer() == null ? "" : event.getPlayer().getName())
                        + "\"");
    }

    /**
     * A ship changed WHERE IT IS — the crossing's own account of it.
     *
     * <p>One subscription for all six crossing events, on their common base, which Forge's event
     * hierarchy makes possible and which is the reason they have one. The recorded TYPE is the
     * event's own simple name lower-cased, so a test awaits {@code ship_entered_cell} or
     * {@code ship_transit_ended} and gets exactly the moment production published, not a
     * mixin's reading of an internal step on the way there.</p>
     *
     * <p>Every payload field here is one the event carries. A cell coordinate is recorded by its
     * cell key rather than its full address because that is what a test asserts on; the address
     * is in the log line the crossing itself writes.</p>
     */
    @SubscribeEvent
    public static void onShipCrossing(
            dev.stannismod.stellurgy.api.event.ShipCrossingEvent event) {
        World world = event.world;
        instrument(world, "server_bus_ship_crossing");
        if (world == null) {
            return;
        }
        StringBuilder payload = new StringBuilder();
        payload.append("\"ship\":\"").append(str(event.shipId)).append('"')
                .append(",\"dim\":").append(world.provider.getDimension())
                .append(",\"crew\":").append(event.crew.size());
        if (event instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.PlanetTrip) {
            dev.stannismod.stellurgy.api.event.ShipCrossingEvent.PlanetTrip trip =
                    (dev.stannismod.stellurgy.api.event.ShipCrossingEvent.PlanetTrip) event;
            payload.append(",\"planetDim\":").append(trip.planetDim)
                    .append(",\"cellDim\":").append(trip.cellDim)
                    .append(",\"cell\":\"").append(cellKeyOf(trip.cell)).append('"');
        } else {
            payload.append(",\"origin\":\"").append(cellKeyOf(originOf(event))).append('"')
                    .append(",\"destination\":\"")
                    .append(cellKeyOf(destinationOf(event))).append('"');
            String route = routeOf(event);
            if (route != null) {
                payload.append(",\"route\":\"").append(route).append('"');
            }
        }
        record(world, typeNameOf(event), payload.toString());
    }

    /** {@code LeftCell} → {@code ship_left_cell}: the event's own name, as a recorded type. */
    private static String typeNameOf(
            dev.stannismod.stellurgy.api.event.ShipCrossingEvent event) {
        String simple = event.getClass().getSimpleName();
        StringBuilder out = new StringBuilder("ship");
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String cellKeyOf(dev.stannismod.stellurgy.space.GalacticCoord coord) {
        return coord == null ? "" : coord.cellKey();
    }

    private static dev.stannismod.stellurgy.space.GalacticCoord originOf(
            dev.stannismod.stellurgy.api.event.ShipCrossingEvent e) {
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.LeftCell) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.LeftCell) e).origin;
        }
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.EnteredCell) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.EnteredCell) e).origin;
        }
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitBegan) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitBegan) e).origin;
        }
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitEnded) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitEnded) e).origin;
        }
        return null;
    }

    private static dev.stannismod.stellurgy.space.GalacticCoord destinationOf(
            dev.stannismod.stellurgy.api.event.ShipCrossingEvent e) {
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.LeftCell) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.LeftCell) e).destination;
        }
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.EnteredCell) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.EnteredCell) e).destination;
        }
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitBegan) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitBegan) e).destination;
        }
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitEnded) {
            return ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitEnded) e).destination;
        }
        return null;
    }

    private static String routeOf(
            dev.stannismod.stellurgy.api.event.ShipCrossingEvent e) {
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitBegan) {
            return String.valueOf(
                    ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitBegan) e).route);
        }
        if (e instanceof dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitEnded) {
            return String.valueOf(
                    ((dev.stannismod.stellurgy.api.event.ShipCrossingEvent.TransitEnded) e).route);
        }
        return null;
    }

    private static String sideOf(World world) {
        return world != null && world.isRemote ? "client" : "server";
    }

    /** Six significant figures — the same rendering the test-side trace uses for a double. */
    private static String num(double v) {
        return String.format(Locale.ROOT, "%.6g", v);
    }

    /** JSON-safe: a name that carries a quote must not break the record it sits in. */
    private static String str(String raw) {
        return raw == null ? "" : raw.replace('\\', '/').replace('"', '\'');
    }
}
