package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Holds a body on the deck it belongs on while nothing else can: the server pins it every tick and
 * asks its client to capture, so it is never handed to world gravity during a window in which its
 * ship is absent, unloaded or still being re-assembled.
 *
 * <h2>Who needs holding</h2>
 *
 * <ul>
 *   <li>a player <b>returning from a logout</b> aboard a ship — {@link #onPlayerLoggedIn} arms the
 *       hold from his durable aboard record. This is the original consumer and the shape everything
 *       below reuses.</li>
 *   <li>a crew member <b>on his feet across a crossing</b> — his ship is cut out from under him at
 *       the departure and re-assembled asynchronously at the far end, so for both windows he is a
 *       body in a world with no ship under it. {@link #pinInPlace} covers the near side (there is
 *       nothing left to resolve against) and {@link #holdOnDeck} the far one (the crossing already
 *       knows which ship and which point).</li>
 * </ul>
 *
 * <p>The ABOARD capture is in-memory; what survives the relog is the durable aboard record
 * ({@link zmaster587.advancedRocketry.space.ShipAboardTag}: the ship's DURABLE id plus his deck
 * point relative to its flight computer). Without this hold the returning player is a fresh entity
 * under WORLD gravity from his first tick - on a non-upright ship world-down points away from the
 * deck and he falls off (or through) before any first-contact gate could fire.</p>
 *
 * <p><b>Why the hold resolves lazily.</b> The record names the ship by the id that outlives a
 * re-assembly, not by the physics mod's own (re-minted) one, so the deck point cannot be turned
 * into a subspace triple until that ship's flight computer is loaded - and after a restart into a
 * space cell the ship is still being rebuilt on the tick the player logs in. The hold therefore
 * starts unresolved, pins the body where it is, and re-tries until the ship answers.</p>
 *
 * <p>The hold mirrors the dismount deck hold ({@code EntityDummy}): crew movement is
 * client-authoritative, so the server cannot capture him directly - instead it pins the body
 * each tick (against vanilla gravity on both sides: the per-tick position set replicates to the
 * client) and re-sends the SUBSPACE deck point in {@code PacketDeckCapture}; the client maps it
 * through its own ship transform and seeds once its ship is loaded. The hold ends the moment the
 * server sees the capture resolving (the client seeded and the server-side follow took over), on
 * an excluded state (the player relogs into creative flight - his movement is his own), or when
 * the window expires (ship gone: clean vanilla handover, never a half-capture).</p>
 */
public final class DeckHold {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /**
     * How long a hold is kept when NOTHING ends it — not a wait, a GIVE-UP.
     *
     * <p>Every real end of a hold is now an event: the capture landing on the hold's own ship, the
     * body entering a state that owns its own movement, or (for a hold that starts without a ship)
     * {@link ShipEvent.ShipLoadedEvent} for the craft it names. What is left for a clock is the case
     * where none of those ever happens — and the reason a clock is still the only answer to THAT is
     * in {@link ShipLoadedAnnouncer}'s own javadoc: there is no disappearance event. A ship in a
     * world that stops ticking is never reported as gone, so "the craft this body is waiting for will
     * never come" is not observable, and a hold with no give-up would pin a player in place for the
     * rest of the session.</p>
     *
     * <p>It is therefore a REFUSAL, and it says so — see {@link #giveUp}. The number is not a
     * prediction of how long anything takes; nothing is being predicted any more.</p>
     */
    private static final int HOLD_WINDOW_TICKS = 200;

    /**
     * One returning player's hold. It starts as a DURABLE ship id plus a flight-computer-relative
     * deck point - the only shape that survives a re-assembly - and becomes a physics-mod ship id
     * plus a subspace point once that ship is loaded and can be asked where its computer is.
     */
    private static final class Hold {
        final UUID durableShipId;
        final double dx, dy, dz;
        int ticksLeft = HOLD_WINDOW_TICKS;
        /** Whether the returning client has been ASKED to capture yet. The hold may not conclude
         *  before it has: see the exit rule in {@link DeckHold#onPlayerTick}. */
        boolean seedSent;

        /** Non-null once the ship has been found; the pin and the capture packet need this shape. */
        String shipId;
        double subX, subY, subZ;

        Hold(UUID durableShipId, double dx, double dy, double dz) {
            this.durableShipId = durableShipId;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        boolean resolved() {
            return shipId != null;
        }

        void resolveOn(String shipId, double subX, double subY, double subZ) {
            this.shipId = shipId;
            this.subX = subX;
            this.subY = subY;
            this.subZ = subZ;
        }

        /** A hold whose ship is already known — the displaced-pilot case, where the seat itself
         *  answered which ship it belongs to and no lookup is needed. */
        static Hold on(String shipId, double subX, double subY, double subZ) {
            Hold hold = new Hold(null, 0.0, 0.0, 0.0);
            hold.resolveOn(shipId, subX, subY, subZ);
            return hold;
        }
    }

    /**
     * The live holds, keyed by player UUID — and STATIC, because the callers that arm one are not
     * events on this handler. A crossing decides mid-tick that a body has to be held; it holds no
     * reference to the single instance Forge's event bus owns, and handing one around would be a
     * second way to reach the same map. One instance is registered ({@code AdvancedRocketry}), so
     * the tick that services these entries is the tick that would have serviced an instance field.
     */
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    /**
     * Pin {@code player} exactly where he is, with no ship to resolve against — the shape a crew
     * member on his feet needs while his ship is being CUT out from under him.
     *
     * <p>It deliberately carries no ship id. The ship this body belongs to is, for the length of
     * this window, in no world at all: it has been cut here and not yet re-assembled there. A hold
     * that named it would spend the window pumping a ship load in the world it just left, and find
     * nothing every time. What the body needs meanwhile is only to stop falling, which is exactly
     * what an unresolved hold does. The far side re-arms it with {@link #holdOnDeck} once there is
     * a ship to be held against.</p>
     */
    public static void pinInPlace(EntityPlayerMP player) {
        if (player != null) {
            HOLDS.put(player.getUniqueID(), new Hold(null, 0.0, 0.0, 0.0));
        }
    }

    /**
     * Hold {@code player} on a KNOWN ship's deck point: pin him to the current world image of the
     * SUBSPACE point {@code (subX,subY,subZ)} on ship {@code vsShipId}, and keep asking his client
     * to capture it until it does.
     *
     * <p>This is the far side of a crossing. The caller has already resolved which ship arrived and
     * where on it the body belongs, so no lookup is needed — and re-arming an existing hold is
     * harmless: the window restarts and the pin lands on the same point.</p>
     */
    public static void holdOnDeck(EntityPlayerMP player, String vsShipId,
                                  double subX, double subY, double subZ) {
        if (player != null && vsShipId != null) {
            HOLDS.put(player.getUniqueID(), Hold.on(vsShipId, subX, subY, subZ));
        }
    }

    /** Whether a hold is currently pinning {@code player}. */
    public static boolean isHeld(EntityPlayerMP player) {
        return player != null && HOLDS.containsKey(player.getUniqueID());
    }

    /**
     * The ship a live hold is holding {@code entity} FOR, or {@code null} when nothing holds it (or
     * the hold has not yet found its ship).
     *
     * <p>This is a DECLARATION, and it is the reason the class exposes it: an arrival, a relog or a
     * displaced pilot has already established which craft this body belongs to and put it on that
     * craft's deck point. Anything that would otherwise GUESS the ship from where the body is
     * standing must ask here first — where two hulls overlap, a spatial guess and a declaration can
     * differ, and the declaration is the one that knows.</p>
     *
     * <p>The named twin of {@link #isHeld}, which answers whether a body is held and never for
     * which craft. Both are read on the server; a hold has no client half.</p>
     */
    public static String heldShipId(net.minecraft.entity.Entity entity) {
        if (entity == null) {
            return null;
        }
        Hold hold = HOLDS.get(entity.getUniqueID());
        return hold == null ? null : hold.shipId;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP) || event.player instanceof FakePlayer
                || event.player.world == null || event.player.world.isRemote) {
            return;
        }
        // Only a crew member who was ON HIS FEET needs holding: a seated one comes back on his
        // mount (vanilla re-spawns it, and reconcileSeatMount below settles who owns the chair).
        zmaster587.advancedRocketry.space.ShipAboardTag.Aboard aboard =
                zmaster587.advancedRocketry.space.ShipAboardTag.of(event.player);
        if (aboard != null
                && aboard.posture == zmaster587.advancedRocketry.space.ShipAboardTag.Posture.STANDING) {
            Hold hold = new Hold(aboard.shipId, aboard.standDx, aboard.standDy, aboard.standDz);
            HOLDS.put(event.player.getUniqueID(), hold);
            armDurable((EntityPlayerMP) event.player, hold);
        }
        // AFTER the anchor hold: a displaced pilot's hold below must win over the (older) anchor.
        reconcileSeatMount((EntityPlayerMP) event.player);
    }

    /**
     * One seat — ONE dummy, across a relog. Vanilla persists a seated pilot's mount dummy inside
     * his own player data ({@code RootVehicle}) and re-spawns it fresh at login — it cannot know
     * the seat may have acquired a NEW bound dummy while he was offline (every mount path spawns
     * through the one-seat-one-dummy recipe, and his own dummy left the world with him). Without
     * this reconciliation the login quietly ends with TWO invisible mounts bound to one seat: the
     * empty twin clears the ship's pilot input every tick (a control tug-of-war nobody can
     * attribute), and if the seat was TAKEN while he was away, two riders both hold "the" chair.
     *
     * <p>Resolution follows who sits on the seat's RESIDENT dummy (the one that stayed with the
     * ship): empty — the duplicate is folded into it and the pilot keeps his seat (the ordinary
     * relog promise); occupied — the occupant keeps the seat, the returner is restored STANDING
     * aboard at his post (the same deck-hold that carries a standing relog) and is told, by name,
     * who took it. A plain (non-pilot) world seat carries no seat binding and is left to vanilla.</p>
     */
    private void reconcileSeatMount(EntityPlayerMP player) {
        if (!(player.getRidingEntity()
                instanceof zmaster587.advancedRocketry.entity.EntityDummy)) {
            return;
        }
        zmaster587.advancedRocketry.entity.EntityDummy respawned =
                (zmaster587.advancedRocketry.entity.EntityDummy) player.getRidingEntity();
        net.minecraft.util.math.BlockPos seatPos = respawned.getSeatPos();
        if (seatPos == null) {
            return; // an ordinary seat's dummy: no ship binding, vanilla behaviour untouched
        }
        zmaster587.advancedRocketry.entity.EntityDummy resident =
                otherBoundDummy(player.world, respawned, seatPos);
        if (resident == null) {
            return; // his re-spawned mount IS the seat's only dummy: the normal seated relog
        }
        if (resident.getPassengers().isEmpty()) {
            // The resident is EMPTY (whoever took the seat left again): fold the duplicate into
            // it — the pilot keeps his seat, the seat keeps one dummy.
            player.dismountRidingEntity();
            respawned.setDead();
            player.startRiding(resident, true);
            return;
        }
        net.minecraft.entity.Entity occupant = resident.getPassengers().get(0);
        if (occupant == player) {
            return; // defensive: he cannot occupy the resident, he just logged in
        }
        // Seat TAKEN while he was offline: the occupant keeps it. Restore the returner STANDING
        // aboard at his post and tell him who took the chair.
        player.dismountRidingEntity();
        respawned.setDead();
        String shipId = VSIntegration.shipIdManagingBlock(player.world, seatPos);
        if (shipId != null) {
            double subX = seatPos.getX() + 0.5, subY = seatPos.getY(), subZ = seatPos.getZ() + 0.5;
            double[] deck = VSIntegration.toWorldFrameFor(player.world, shipId, subX, subY, subZ);
            if (deck != null) {
                player.setPositionAndUpdate(deck[0], deck[1], deck[2]);
            }
            // The same hold a standing relog gets: pin against gravity, ask his client to
            // capture the deck point. Wins over any (older) record hold registered above.
            HOLDS.put(player.getUniqueID(), Hold.on(shipId, subX, subY, subZ));
        } else {
            // Not on a managed ship (e.g. the craft was disassembled meanwhile): stand him at
            // the seat block itself, plain world frame.
            player.setPositionAndUpdate(
                    seatPos.getX() + 0.5, seatPos.getY() + 1.0, seatPos.getZ() + 0.5);
        }
        // Delayed past the join flood — sent immediately it is overwritten before he reads it.
        zmaster587.advancedRocketry.util.DelayedActionBar.send(player,
                new net.minecraft.util.text.TextComponentTranslation(
                        "msg.pilotseat.taken", occupant.getName()), 20);
    }

    /**
     * The dummy bound to {@code seatPos} OTHER than {@code exclude}, or {@code null}. A whole-world
     * scan rather than a positional box: the freshly re-spawned duplicate still sits at its SAVED
     * coordinates (it has not ticked its seat glue yet), the resident at the seat's live world
     * position — no single box reliably covers both on a ship that moved while the pilot slept.
     * Runs once per login, only for a player who came back seated on a bound dummy.
     */
    private static zmaster587.advancedRocketry.entity.EntityDummy otherBoundDummy(
            net.minecraft.world.World world,
            zmaster587.advancedRocketry.entity.EntityDummy exclude,
            net.minecraft.util.math.BlockPos seatPos) {
        for (net.minecraft.entity.Entity e : world.loadedEntityList) {
            if (e instanceof zmaster587.advancedRocketry.entity.EntityDummy && e != exclude
                    && !e.isDead
                    && seatPos.equals(((zmaster587.advancedRocketry.entity.EntityDummy) e)
                            .getSeatPos())) {
                return (zmaster587.advancedRocketry.entity.EntityDummy) e;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player != null) {
            HOLDS.remove(event.player.getUniqueID());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || event.side != net.minecraftforge.fml.relauncher.Side.SERVER
                || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Hold hold = HOLDS.get(player.getUniqueID());
        if (hold == null) {
            return;
        }
        // The server-side follow has taken the body - but that is the SERVER's own capture, and it
        // says nothing about the client, which owns this body's movement. Concluding here before the
        // client has even been asked to capture is what let its first-contact capture keep the
        // position AND VELOCITY vanilla restored, so a crew member who logged out walking skated on
        // across his own deck. The hold therefore may not end until at least one capture request has
        // gone out; the client's own pending slot then survives its ship streaming in.
        // ...and it must be a follow onto THE SHIP THIS HOLD IS FOR. `isResolving` answers whether
        // SOME craft holds this body and can never say which, so a first-contact capture taken by a
        // neighbouring hull satisfied it and ended the hold on somebody else's deck: measured on the
        // hyperspace arrival, where the crossing named one craft, put the body on its deck point,
        // and the hold then let go with the capture anchored on a hull that merely overlapped it.
        // Everything downstream — the deck frame, the carry, the camera — was then the wrong ship's.
        if (hold.seedSent && hold.shipId != null
                && hold.shipId.equals(ShipFrameTravel.capturedShipId(player))) {
            HOLDS.remove(player.getUniqueID());
            return;
        }
        // An excluded state - riding, elytra, creative flight, water/ladder, levitation - owns its
        // own movement and ends any capture; the seed would refuse anyway.
        if (ShipFrameTravel.isExcludedFromCapture(player)) {
            HOLDS.remove(player.getUniqueID());
            return;
        }
        if (--hold.ticksLeft <= 0) {
            giveUp(player, hold);
            return;
        }
        // A returning body is a FRESH entity, and the physics mod arms its own per-entity drag
        // anchor the moment that body first touches the ship. Nothing has told it about the deck
        // capture yet, so what it holds is a stale point - and its world-tick mover then pulls the
        // body toward it, steadily, past the resolver. Measured on the walking-relog e2e: the body
        // sat exactly still for ten ticks and then slid ~0.04 blocks per tick along the walk it
        // logged out on, with an external [FF-TRACE/MOVE] of (0, -0.76, +0.51) on the server. The
        // resolved commit disarms this every tick for a body it owns (the same call); a body still
        // being handed back needs it too, or the hold's own pin is what it fights.
        VSIntegration.suppressShipDrag(player);
        double[] world = hold.resolved() ? VSIntegration.toWorldFrameFor(
                player.world, hold.shipId, hold.subX, hold.subY, hold.subZ) : null;
        if (world == null) {
            // The ship is not loaded (yet), or has not been found: hold the body still where it is
            // so gravity cannot ratchet it off the deck spot while the ship streams in.
            player.setPositionAndUpdate(player.posX, player.posY, player.posZ);
            player.motionX = 0.0;
            player.motionY = 0.0;
            player.motionZ = 0.0;
            player.fallDistance = 0.0f;
            return;
        }
        // Pin to the CURRENT world image of the persisted deck point (the ship may sit at any
        // attitude now) and ask the owning client to capture, exactly like the dismount hold:
        // the deck point travels as a SUBSPACE triple; the client maps it through its OWN
        // transform and seeds once; re-sends no-op after that.
        player.setPositionAndUpdate(world[0], world[1], world[2]);
        player.motionX = 0.0;
        player.motionY = 0.0;
        player.motionZ = 0.0;
        player.fallDistance = 0.0f;
        // A RESTORE seed: it re-establishes what the durable record says, so it outranks whatever
        // capture the returning client made for itself out of the position and velocity vanilla
        // handed it. A dismount seed deliberately does not (contract note on PacketDeckCapture).
        zmaster587.libVulpes.network.PacketHandler.sendToPlayer(
                new zmaster587.advancedRocketry.network.PacketDeckCapture(
                        hold.shipId, hold.subX, hold.subY, hold.subZ, true),
                player);
        hold.seedSent = true;
    }

    /**
     * End a hold that NOTHING ended — and say so, on both channels.
     *
     * <p>What this branch does is hand the body to vanilla gravity, on a deck that may be at any
     * attitude. That is the exact fall the hold exists to prevent, so it is a DEGRADATION and it may
     * not be indistinguishable from the hold having worked. It used to be a bare map removal with a
     * comment: no log, no event, nothing the player could see, and a crew member who ended up in the
     * air below his ship had no way to learn that anything had been decided about him.</p>
     *
     * <p>Both channels on purpose. The log names what was waited for so the failure is diagnosable
     * from a server the player is not on; the action bar tells the person it happened to, because he
     * is the one about to fall and "my ship vanished under me" is otherwise the whole report.</p>
     */
    private static void giveUp(EntityPlayerMP player, Hold hold) {
        HOLDS.remove(player.getUniqueID());
        LOGGER.error("[SPACE] gave up holding {} on a deck after {} ticks: {}. He is handed to "
                        + "vanilla movement where he stands, which on a tilted or inverted deck is a "
                        + "fall. Treat this as a bug report.",
                player.getName(), HOLD_WINDOW_TICKS,
                hold.durableShipId != null && !hold.resolved()
                        ? "ship " + hold.durableShipId + " never became loaded and steppable, so no"
                                + " load event for it ever arrived"
                        : hold.resolved()
                                ? "ship " + hold.shipId + " is loaded, but no capture on it ever took"
                                        + " (the client was asked " + (hold.seedSent ? "and did not"
                                        + " seed" : "for nothing yet") + ")"
                                : "the hold was a bare pin with no ship, and the crossing that should"
                                        + " have replaced it never boarded him");
        zmaster587.advancedRocketry.util.DelayedActionBar.send(player,
                new net.minecraft.util.text.TextComponentTranslation("msg.deckhold.lost"), 20);
    }

    /**
     * Arm a hold that starts WITHOUT a ship: try once, and if the craft is not up yet, ask for it to
     * be loaded and then WAIT FOR THE EVENT ({@link #onShipLoaded}).
     *
     * <p>This replaces a five-tick poll, and the distinction worth keeping is that the poll was not
     * only observing — it also CAUSED, calling {@code loadAllShips} on every pass. So the honest
     * event-driven shape is not "subscribe instead": it is <b>cause once, then observe</b>. The
     * one-shot attempt before subscribing is not belt-and-braces either, it closes a real race:
     * {@code ShipLoadedEvent} is an EDGE, so a hold armed after its craft was already loaded would
     * wait for a transition that has been and gone.</p>
     */
    private static void armDurable(EntityPlayerMP player, Hold hold) {
        if (hold.durableShipId == null) {
            return;
        }
        resolve(player, hold);
        if (!hold.resolved()) {
            // A headless server (or one whose returning player has not streamed the ship's chunks
            // yet) keeps a ship in the registry without ticking it, and an unloaded ship carries no
            // loaded tile entities to find — and posts no load event either. Asking once is what
            // makes the event we then wait for possible at all.
            VSIntegration.loadAllShips(player.world);
        }
    }

    /**
     * A craft this world has been waiting for is now loaded and steppable: resolve every hold that
     * names it.
     *
     * <p>The event carries the DURABLE id, which is exactly the key a hold starts life with — the
     * substrate's own id is re-minted per assembly and could not be matched against a record written
     * before the re-assembly. Holds are few (one per returning or carried crew member) and the event
     * is rare (once per ship per load), so this walks them rather than keeping a second index whose
     * only job would be to disagree with the first one.</p>
     */
    @SubscribeEvent
    public void onShipLoaded(zmaster587.advancedRocketry.api.event.ShipEvent.ShipLoadedEvent event) {
        if (event.world == null || event.world.isRemote || event.shipId == null || HOLDS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Hold> entry : HOLDS.entrySet()) {
            Hold hold = entry.getValue();
            if (hold.resolved() || hold.durableShipId == null
                    || !event.shipId.equals(hold.durableShipId.toString())) {
                continue;
            }
            net.minecraft.entity.player.EntityPlayer player =
                    event.world.getPlayerEntityByUUID(entry.getKey());
            if (player instanceof EntityPlayerMP) {
                resolve((EntityPlayerMP) player, hold);
            }
        }
    }

    /**
     * The craft a hold is waiting for has been DESTROYED: end the hold now, saying so, instead of
     * pinning the body for the rest of the window against a ship that is never coming.
     *
     * <p>This is the half {@link #onShipLoaded} could not cover and the reason the give-up clock used
     * to be the only answer for it. It does not remove the clock — a world that stops ticking
     * announces nothing, which {@link ShipLoadedAnnouncer} states on its own side — but it turns the
     * common case from "waited ten seconds for no stated reason" into "the ship was destroyed", which
     * is a sentence the player and the log can both act on.</p>
     *
     * <p><b>A DEPARTURE is deliberately not subscribed to here, and that is the whole point of the
     * two events being separate.</b> A crossing cuts its source out of the world, which drops it from
     * the registry exactly as a destruction does — and the crew holding onto that craft's deck are
     * precisely the people the crossing is carrying. Ending their holds there, with "your ship was
     * destroyed", would break the mechanic this class exists to serve, on its commonest path. The
     * hold is SUPPOSED to survive a departure: the arrival re-arms it on the far side.</p>
     */
    @SubscribeEvent
    public void onShipGone(zmaster587.advancedRocketry.api.event.ShipEvent.ShipGoneEvent event) {
        if (event.world == null || event.world.isRemote || HOLDS.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<UUID, Hold>> it = HOLDS.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Hold> entry = it.next();
            Hold hold = entry.getValue();
            // Either identity may be the one this hold knows: a hold that never resolved is still
            // holding a DURABLE name, a resolved one is pinned to the substrate's id, and the event
            // carries both. Matching on only one of them would miss exactly half the holds.
            boolean waitingForIt = event.shipId != null && hold.durableShipId != null
                    && event.shipId.equals(hold.durableShipId.toString());
            boolean pinnedToIt = event.substrateId != null
                    && event.substrateId.equals(hold.shipId);
            if (!waitingForIt && !pinnedToIt) {
                continue;
            }
            it.remove();
            net.minecraft.entity.player.EntityPlayer player =
                    event.world.getPlayerEntityByUUID(entry.getKey());
            if (player instanceof EntityPlayerMP) {
                announceLostShip((EntityPlayerMP) player,
                        event.shipId == null ? event.substrateId : event.shipId);
            }
        }
    }

    /** Tell the log and the person what happened, for a hold whose craft was destroyed under it. */
    private static void announceLostShip(EntityPlayerMP player, String ship) {
        LOGGER.error("[SPACE] the ship {} a deck hold was keeping {} on has been DESTROYED; he is "
                        + "handed to vanilla movement where he stands. Treat this as a bug report if "
                        + "nothing in this world was supposed to destroy it.", ship, player.getName());
        zmaster587.advancedRocketry.util.DelayedActionBar.send(player,
                new net.minecraft.util.text.TextComponentTranslation("msg.deckhold.shipgone"), 20);
    }

    /**
     * Turn a durable hold into a live one: find the flight computer carrying the recorded ship id,
     * and express the record's computer-relative deck point as a subspace triple on the ship that
     * computer belongs to.
     *
     * <p>Called at arm time and again when the craft's load event arrives — never on a cadence.</p>
     */
    private static void resolve(EntityPlayerMP player, Hold hold) {
        if (hold.durableShipId == null) {
            return;
        }
        net.minecraft.util.math.BlockPos afcPos = zmaster587.advancedRocketry.space
                .ShipRelativePoint.flightComputerOfDurableShip(player.world, hold.durableShipId);
        if (afcPos == null) {
            return;
        }
        String shipId = VSIntegration.shipIdManagingBlock(player.world, afcPos);
        double[] sub = zmaster587.advancedRocketry.space.ShipRelativePoint.subspacePointOf(
                afcPos, hold.dx, hold.dy, hold.dz);
        if (shipId != null && sub != null) {
            hold.resolveOn(shipId, sub[0], sub[1], sub[2]);
        }
    }
}
