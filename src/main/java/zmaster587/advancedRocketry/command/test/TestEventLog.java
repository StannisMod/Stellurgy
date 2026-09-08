package zmaster587.advancedRocketry.command.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
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

import zmaster587.advancedRocketry.api.RocketEvent;

/**
 * An ORDERED log of things that HAPPENED on this side, so a test can wait for an event instead of
 * sampling a value.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every other waiting primitive in this suite polls: it asks "is this true now?" every N ticks.
 * Four things follow, and all four have cost real time. A poll cannot see anything that does not
 * PERSIST — an event that fires and is over between two samples never existed. A poll races its own
 * start: {@code doThing(); pollUntil(X)} cannot tell "X happened before the first sample" from "X
 * never happened". A poll cannot express ORDER, which is the shape of nearly every contract here — a
 * boarding, a crossing, a jump, a re-seat. And a poll's failure carries only its last sample.</p>
 *
 * <p>The worked example: a bed scenario whose only witness was the world clock stayed red for a month
 * reporting {@code worldTime=20622}, while the actual chain was <i>client has no chunk &rarr; player
 * falls &rarr; he is out of reach &rarr; the server drops his click silently &rarr; the client still
 * reports SUCCESS from its own prediction &rarr; the sleep never starts</i>. Six of those seven links
 * were observable and none was observed.</p>
 *
 * <h2>The shape</h2>
 *
 * <p>{@link #mark()} is taken BEFORE the action. That is what removes the start race, and it is the
 * whole reason this is not a poll with better manners: records are BUFFERED, so a reader that arrives
 * late still sees everything that happened after its mark.</p>
 *
 * <p>{@link #since(long)} returns them in order, so a chain and its ORDER are directly assertable —
 * and, on a failure, the caller prints what DID happen rather than one stale number.</p>
 *
 * <p><b>An ABSENCE is a first-class answer.</b> {@code PlayerInteractEvent.RightClickBlock} is not
 * fired at all when the server rejects a click on its reach check
 * ({@code NetHandlerPlayServer.processTryUseItemOnBlock} guards the call behind the distance test),
 * so "no such record since the mark" is a precise statement about the game, not a gap in the
 * instrument.</p>
 *
 * <h2>Costs nothing in a shipped game</h2>
 *
 * <p>The subscriber is registered only from {@link TestProbeCommandRegistration}, i.e. only under
 * {@code -Dadvancedrocketry.tests=true} or inside a harness-spawned JVM. Nothing here is referenced
 * from production paths, and with the recorder unregistered no event handler runs and no record is
 * built. Observation belongs to the test side; a shipped game must not pay for it.</p>
 */
public final class TestEventLog {

    /**
     * How many records are kept OF EACH TYPE. Bounded so a long session cannot grow the log without
     * limit; what fell off the end is REPORTED, because a truncated log that reads as a quiet one is
     * the same false negative a recorder that cannot say it was off produces.
     *
     * <p><b>Per type, not per log, and that is the whole point.</b> One shared ring is emptied by
     * whichever type is chattiest, so a rare event is evicted by a common one and the log then
     * answers "it never happened" about something it merely threw away. Measured 2026-08-21 on the
     * client half: a ship crossing loads a thousand chunks, {@code chunk_data_applied} filled the
     * ring, and the position writes the timeline exists for were gone before anything read them —
     * with {@code dropped} honestly reporting 173, which made the log honest and useless at the same
     * time. A ring per type costs a small map and leaves a chatty type unable to silence a quiet
     * one.</p>
     */
    public static final int CAPACITY_PER_TYPE = 256;

    private static final Object LOCK = new Object();
    /** One ring per type, insertion-ordered so a dump lists types in first-seen order. */
    private static final Map<String, Deque<Record>> RECORDS = new LinkedHashMap<>();
    /** Evictions per type. WHICH type is being truncated is the half a reader can act on. */
    private static final Map<String, Long> DROPPED = new LinkedHashMap<>();
    private static long nextSeq;

    /**
     * Whether anything is subscribed. Reported on every read, on the same principle as the client
     * harness's {@code managerLoaded}: "nothing happened" and "nobody was listening" must never be
     * the same reply, or an empty log reads as a finding.
     */
    private static volatile boolean recording;

    /**
     * Whether the test-only mixin configuration was ACCEPTED in this JVM.
     *
     * <p>Separate from {@link #recording}, because they can fail independently and their failures
     * look identical from a test: the Forge-bus recorder covers events the bus already fires, while
     * everything observed by a mixin — a position write, a mount refusal — appears only if the
     * launch-time coremod queued the config. Set from the config's own plugin at the moment mixin
     * prepares it, so it reports a checkable fact rather than an intention. A shipped game never
     * queues the config and this stays false, which is correct: nothing there is instrumented.</p>
     */
    private static volatile boolean mixinsInstalled;

    private TestEventLog() {}

    /** Called from the test mixin config's plugin when mixin prepares that configuration. */
    public static void markMixinsInstalled() {
        mixinsInstalled = true;
    }

    /** Whether the test-only mixin configuration was accepted — see the field's own note. */
    public static boolean areMixinsInstalled() {
        return mixinsInstalled;
    }

    /**
     * Observation points that have EXECUTED at least once, by name.
     *
     * <p>"The configuration was accepted" and "this code ran" are different claims, and until both can
     * be asked, an empty log means nothing: a mixin that was never woven, one that was woven but whose
     * method never ran, and one that ran and saw nothing are indistinguishable — and the first two
     * read as the third, which is the answer a test then believes. So an instrument announces itself
     * on ENTRY, before any threshold or condition, and a reader can tell "nothing happened" from
     * "nobody was looking".</p>
     *
     * <p>Names are free-form and belong to the instrument, not to the event type: one observation
     * point may emit several types, or none on a given run.</p>
     */
    private static final java.util.Set<String> INSTRUMENTS_ENTERED =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    /** Called by an observation point the first thing it does, whether or not it goes on to record. */
    public static void noteInstrumentEntered(String name) {
        if (name != null && !name.isEmpty()) {
            INSTRUMENTS_ENTERED.add(name);
        }
    }

    /** The names of every observation point that has executed, as a JSON array. */
    public static String instrumentsEntered() {
        StringBuilder out = new StringBuilder("[");
        synchronized (INSTRUMENTS_ENTERED) {
            for (String name : INSTRUMENTS_ENTERED) {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append('"').append(name).append('"');
            }
        }
        return out.append(']').toString();
    }

    /** One thing that happened, in order. */
    public static final class Record {
        public final long seq;
        public final long tick;
        public final String side;
        public final String type;
        /** A JSON fragment (no braces) describing this event, or empty. */
        public final String payload;

        Record(long seq, long tick, String side, String type, String payload) {
            this.seq = seq;
            this.tick = tick;
            this.side = side;
            this.type = type;
            this.payload = payload;
        }
    }

    /**
     * The sequence a reader should ask {@link #since(long)} for. Taken BEFORE the action under test:
     * everything recorded from now on has a sequence greater than or equal to this.
     */
    public static long mark() {
        synchronized (LOCK) {
            return nextSeq;
        }
    }

    /** Append one record. No-op unless a recorder is registered. */
    public static void record(String side, long tick, String type, String payload) {
        if (!recording) {
            return;
        }
        synchronized (LOCK) {
            Deque<Record> ring = RECORDS.get(type);
            if (ring == null) {
                ring = new ArrayDeque<>();
                RECORDS.put(type, ring);
            }
            ring.addLast(new Record(nextSeq++, tick, side, type, payload == null ? "" : payload));
            while (ring.size() > CAPACITY_PER_TYPE) {
                ring.removeFirst();
                Long was = DROPPED.get(type);
                DROPPED.put(type, was == null ? 1L : was + 1L);
            }
        }
    }

    /**
     * Everything recorded at or after {@code fromSeq}, oldest first.
     *
     * <p>Merged across the per-type rings and re-ordered by sequence, because ORDER is what a chain
     * assertion reads and the sequence is the only thing that carries it once the rings are
     * separate.</p>
     */
    public static List<Record> since(long fromSeq) {
        List<Record> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Deque<Record> ring : RECORDS.values()) {
                for (Record r : ring) {
                    if (r.seq >= fromSeq) {
                        out.add(r);
                    }
                }
            }
        }
        Collections.sort(out, new Comparator<Record>() {
            @Override
            public int compare(Record a, Record b) {
                return Long.compare(a.seq, b.seq);
            }
        });
        return out;
    }

    /**
     * The recorded chain as one readable line — {@code type t=<tick> <payload>}, oldest first,
     * separated by {@code |}.
     *
     * <p>For a failure message, where a caller wants the whole timeline in a sentence rather than a
     * structure to parse. Filters to {@code types} when any are given.</p>
     */
    public static String dump(String... types) {
        StringBuilder sb = new StringBuilder();
        for (Record r : since(0)) {
            if (types != null && types.length > 0 && !matches(r.type, types)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(r.type).append(" t=").append(r.tick);
            if (!r.payload.isEmpty()) {
                sb.append(' ').append(r.payload.replace('"', '\''));
            }
        }
        return sb.toString();
    }

    /** How many records of {@code types} the log currently holds. */
    public static int count(String... types) {
        int n = 0;
        for (Record r : since(0)) {
            if (types == null || types.length == 0 || matches(r.type, types)) {
                n++;
            }
        }
        return n;
    }

    private static boolean matches(String type, String[] types) {
        for (String t : types) {
            if (type.equals(t)) {
                return true;
            }
        }
        return false;
    }

    /** How many records fell off the end of any ring since the process started. */
    public static long dropped() {
        long total = 0;
        synchronized (LOCK) {
            for (Long n : DROPPED.values()) {
                total += n;
            }
        }
        return total;
    }

    /**
     * Which types were truncated and by how much, as a JSON object body — {@code "chunk_data_applied":173}.
     *
     * <p>A bare total says a log is incomplete; this says WHERE, which is the difference between a
     * reader who knows to raise a cap and one who quietly believes a short answer.</p>
     */
    public static String droppedByType() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (Map.Entry<String, Long> e : DROPPED.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
        }
        return sb.toString();
    }

    /** Whether a recorder is subscribed — see the field's own note on why this is reported. */
    public static boolean isRecording() {
        return recording;
    }

    /**
     * Forget everything and start again. For a harness that reuses a JVM across scenarios; a test
     * that only wants "since here" should use {@link #mark()} instead, which costs nothing.
     */
    public static void reset() {
        synchronized (LOCK) {
            RECORDS.clear();
            DROPPED.clear();
            nextSeq = 0;
        }
    }

    /**
     * The Forge-bus recorder. Registered ONLY in test mode, from
     * {@link TestProbeCommandRegistration}.
     *
     * <p>Deliberately nothing but bus subscriptions: under the project's rule an observation a test
     * wants is a harness mixin or the Forge bus, never a line added to production logic, because a
     * shipped game must pay nothing for it.</p>
     */
    public static final class ServerRecorder {

        private static boolean registered;

        private ServerRecorder() {}

        public static void ensureRegistered() {
            if (registered) {
                return;
            }
            MinecraftForge.EVENT_BUS.register(new ServerRecorder());
            registered = true;
            recording = true;
        }

        /**
         * A right-click that REACHED the server. Its absence is the signal: the reach check upstream
         * drops a click from too far away before this event is ever fired.
         */
        @SubscribeEvent
        public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            BlockPos p = event.getPos();
            record(event.getWorld().isRemote ? "client" : "server",
                    event.getWorld().getTotalWorldTime(), "right_click_block",
                    "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                            + ",\"player\":\"" + event.getEntityPlayer().getName() + "\"");
        }

        /**
         * The bed attempt, WITH the result a handler put on it.
         *
         * <p>{@code LOWEST} on purpose: the result is what the last handler leaves, and AR's own
         * planet handler is one of them. Reading it earlier would record a verdict nobody reached.
         * A result of {@code null} means no handler objected — which is not the same as "he slept",
         * because vanilla's own gates run after this event; that half of the story is
         * {@code player_wake_up}.</p>
         */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onSleepInBed(PlayerSleepInBedEvent event) {
            BlockPos p = event.getPos();
            record(event.getEntityPlayer().world.isRemote ? "client" : "server",
                    event.getEntityPlayer().world.getTotalWorldTime(), "sleep_in_bed",
                    "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                            + ",\"result\":\"" + (event.getResultStatus() == null
                                    ? "none" : event.getResultStatus().name()) + "\""
                            + ",\"player\":\"" + event.getEntityPlayer().getName() + "\"");
        }

        /** He was asleep and is not any more — the only bus-visible proof the sleep happened. */
        @SubscribeEvent
        public void onWakeUp(PlayerWakeUpEvent event) {
            record(event.getEntityPlayer().world.isRemote ? "client" : "server",
                    event.getEntityPlayer().world.getTotalWorldTime(), "player_wake_up",
                    "\"player\":\"" + event.getEntityPlayer().getName() + "\"");
        }

        // ------------------------------------------------------------------------------------
        // The bus vocabulary below is recorded on whichever side the event's world belongs to,
        // exactly like the three handlers above: this recorder is registered in every test-mode
        // JVM, and on an integrated client the same bus carries both sides' events. A reader
        // filters on the record's own side. Every handler announces its instrument FIRST, above
        // any filter, so "nothing recorded" is distinguishable from "no handler ran".
        //
        // These are BUS subscribers, not mixins, so they are covered by `recording` ALONE and
        // never by `mixinsInstalled`: the game posts every event below whether or not the
        // test-only mixin configuration was accepted. A reader checking whether these records
        // could have been taken asks `isRecording()`; `areMixinsInstalled()` says nothing about
        // them in either direction, and a false there is not a reason to distrust a quiet log
        // of these types.
        // ------------------------------------------------------------------------------------

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
        public void onContainerOpened(PlayerContainerEvent.Open event) {
            noteInstrumentEntered("server_bus_container_opened");
            recordContainer("container_opened", event);
        }

        /**
         * The matching CLOSE ({@code PlayerContainerEvent.Close}, from {@code EntityPlayerMP.closeContainer}
         * and {@code closeScreen}). A logout closes the open container too and lands here.
         */
        @SubscribeEvent
        public void onContainerClosed(PlayerContainerEvent.Close event) {
            noteInstrumentEntered("server_bus_container_closed");
            recordContainer("container_closed", event);
        }

        private static void recordContainer(String type, PlayerContainerEvent event) {
            EntityPlayer who = event.getEntityPlayer();
            record(sideOf(who.world), who.world.getTotalWorldTime(), type,
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
         * because a block break or a mob death spawns dozens of them and the ring is 256; every
         * other class is kept, so a reader filters on {@code cls}. Silent about WHY the entity
         * joined (spawn vs. load vs. transfer) — the event does not carry that.
         */
        @SubscribeEvent
        public void onEntityJoinedWorld(EntityJoinWorldEvent event) {
            noteInstrumentEntered("server_bus_entity_joined_world");
            World world = event.getWorld();
            Entity e = event.getEntity();
            if (world == null || world.isRemote || e == null
                    || e instanceof EntityItem || e instanceof EntityXPOrb) {
                return;
            }
            record("server", world.getTotalWorldTime(), "entity_joined_world",
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
        public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
            noteInstrumentEntered("server_bus_right_click_item");
            EntityPlayer who = event.getEntityPlayer();
            ItemStack stack = event.getItemStack();
            String item = stack == null || stack.isEmpty() || stack.getItem().getRegistryName() == null
                    ? "empty" : stack.getItem().getRegistryName().toString();
            record(sideOf(event.getWorld()), event.getWorld().getTotalWorldTime(), "right_click_item",
                    "\"who\":\"" + str(who.getName()) + "\""
                            + ",\"hand\":\"" + (event.getHand() == null ? "null" : event.getHand().name()) + "\""
                            + ",\"item\":\"" + str(item) + "\"");
        }

        /**
         * A PLAYER took damage ({@code LivingHurtEvent}, fired from {@code EntityLivingBase.damageEntity}
         * after armour and before absorption) — the witness a fall-through or a vacuum test wants,
         * with the damage type it came with ({@code fall}, {@code outOfWorld}, AR's own suit and
         * atmosphere sources). Players only: mobs would turn this ring over on any surface with a
         * cactus. Silent about the damage actually APPLIED — later handlers may still change
         * {@code amount}, and this records it as it stood at default priority.
         */
        @SubscribeEvent
        public void onLivingHurt(LivingHurtEvent event) {
            noteInstrumentEntered("server_bus_living_hurt");
            if (!(event.getEntityLiving() instanceof EntityPlayer)) {
                return;
            }
            EntityPlayer who = (EntityPlayer) event.getEntityLiving();
            String source = event.getSource() == null ? "null" : event.getSource().getDamageType();
            record(sideOf(who.world), who.world.getTotalWorldTime(), "living_hurt",
                    "\"who\":\"" + str(who.getName()) + "\""
                            + ",\"source\":\"" + str(source) + "\""
                            + ",\"amount\":" + num(event.getAmount()));
        }

        /**
         * A rocket TOUCHED DOWN — AR's own {@code RocketEvent.RocketLandedEvent}, posted by
         * {@code EntityRocket} on the server at the free-flight touchdown, at the classic descent's
         * touchdown, and once after a load for a rocket that is already on the ground. The client
         * re-posts it on receipt of the land packet, so on an integrated client the same landing
         * appears twice with different sides; a reader filters on the side it means. Silent about
         * WHICH of the three server sites posted it — the event carries only the entity.
         */
        @SubscribeEvent
        public void onRocketLanded(RocketEvent.RocketLandedEvent event) {
            noteInstrumentEntered("server_bus_rocket_landed");
            Entity e = event.getEntity();
            World world = e == null ? event.world : e.world;
            if (world == null) {
                return;
            }
            record(sideOf(world), world.getTotalWorldTime(), "rocket_landed",
                    "\"e\":" + (e == null ? -1 : e.getEntityId())
                            + ",\"y\":" + num(e == null ? Double.NaN : e.posY)
                            + ",\"onGround\":" + (e != null && e.onGround));
        }

        /**
         * A ship became USABLE — its physics will be stepped from now on.
         *
         * <p>Production's own event, subscribed to like any other consumer would rather than
         * observed by a test mixin: this fact has a non-test audience and is published for it
         * ({@code ShipEvent.ShipLoadedEvent}). That is why the recorded type is named for USABILITY
         * and not "loaded" — {@code ship_loaded} is already taken by the test mixin on the physics
         * object's CONSTRUCTOR, which is a weaker claim: a ship exists there and does not move yet.
         * A test that means "I can fly this now" wants this one.</p>
         *
         * <p>Both ids are recorded even though they are the same value (one ship, one identity), so a
         * reader can match on either spelling without knowing that.</p>
         *
         * <p>A bus subscription, so it is covered by the log's {@code recording} flag and never by
         * {@code mixins}.</p>
         */
        @SubscribeEvent
        public void onShipUsable(zmaster587.advancedRocketry.api.event.ShipEvent.ShipLoadedEvent event) {
            noteInstrumentEntered("server_bus_ship_usable");
            World world = event.world;
            if (world == null) {
                return;
            }
            record(sideOf(world), world.getTotalWorldTime(), "ship_usable",
                    "\"ship\":\"" + str(event.shipId) + "\""
                            + ",\"vsShip\":\"" + str(event.substrateId) + "\""
                            + ",\"dim\":" + world.provider.getDimension());
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
        public void onShipCrossing(
                zmaster587.advancedRocketry.api.event.ShipCrossingEvent event) {
            noteInstrumentEntered("server_bus_ship_crossing");
            World world = event.world;
            if (world == null) {
                return;
            }
            StringBuilder payload = new StringBuilder();
            payload.append("\"ship\":\"").append(str(event.shipId)).append('"')
                    .append(",\"dim\":").append(world.provider.getDimension())
                    .append(",\"crew\":").append(event.crew.size());
            if (event instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.PlanetTrip) {
                zmaster587.advancedRocketry.api.event.ShipCrossingEvent.PlanetTrip trip =
                        (zmaster587.advancedRocketry.api.event.ShipCrossingEvent.PlanetTrip) event;
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
            record(sideOf(world), world.getTotalWorldTime(),
                    typeNameOf(event), payload.toString());
        }

        /** {@code LeftCell} → {@code ship_left_cell}: the event's own name, as a recorded type. */
        private static String typeNameOf(
                zmaster587.advancedRocketry.api.event.ShipCrossingEvent event) {
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

        private static String cellKeyOf(zmaster587.advancedRocketry.space.GalacticCoord coord) {
            return coord == null ? "" : coord.cellKey();
        }

        private static zmaster587.advancedRocketry.space.GalacticCoord originOf(
                zmaster587.advancedRocketry.api.event.ShipCrossingEvent e) {
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.LeftCell) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.LeftCell) e).origin;
            }
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.EnteredCell) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.EnteredCell) e).origin;
            }
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitBegan) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitBegan) e).origin;
            }
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitEnded) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitEnded) e).origin;
            }
            return null;
        }

        private static zmaster587.advancedRocketry.space.GalacticCoord destinationOf(
                zmaster587.advancedRocketry.api.event.ShipCrossingEvent e) {
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.LeftCell) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.LeftCell) e).destination;
            }
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.EnteredCell) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.EnteredCell) e).destination;
            }
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitBegan) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitBegan) e).destination;
            }
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitEnded) {
                return ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitEnded) e).destination;
            }
            return null;
        }

        private static String routeOf(
                zmaster587.advancedRocketry.api.event.ShipCrossingEvent e) {
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitBegan) {
                return String.valueOf(
                        ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitBegan) e).route);
            }
            if (e instanceof zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitEnded) {
                return String.valueOf(
                        ((zmaster587.advancedRocketry.api.event.ShipCrossingEvent.TransitEnded) e).route);
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
}
