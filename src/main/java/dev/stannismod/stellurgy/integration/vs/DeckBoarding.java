package dev.stannismod.stellurgy.integration.vs;

import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.api.FreeFlightPhysics;

/**
 * Who a deck may hold, where a deck point the server sends puts a body, and which craft's deck holds
 * one now.
 *
 * <p>The holding itself is {@link DeckFrameTick}'s: a body on a deck is updated in the deck's own
 * frame by its unmodified vanilla code. This class answers the questions around that hold - the
 * states that keep a body on world-frame semantics, whether a deck may go on holding a body where it
 * is, the enclosed interior a body may be claimed in without standing support, the client's pending
 * deck point after a dismount or a relog, and the deck heading the client steers by. A body no deck
 * holds is the physics substrate's, the outer hull of a craft included.</p>
 */
public final class DeckBoarding {

    private DeckBoarding() {}

    /** Whether {@code entity} is in a state that keeps it on world-frame semantics and so can never
     *  be held by a deck — the public face of {@link #excludedStateOf} for seed SENDERS (the dismount
     *  deck-hold), which should stop re-sending a seed the receiving side will refuse. */
    public static boolean isExcludedFromCapture(EntityLivingBase entity) {
        return entity == null || excludedStateOf(entity) != null;
    }

    /** The excluded state keeping this body on world-frame semantics, or {@code null} when none. ONE
     *  predicate for every consumer — the deck frame's admission and the seeds, which must REFUSE an
     *  excluded body: a seed that ignored creative flight once snapped a flying player to the deck
     *  point every window tick, freezing him mid-air while the holder released him right back each
     *  tick — a per-tick war. */
    private static String excludedStateOf(EntityLivingBase entity) {
        if (entity.hasNoGravity() || entity.isRiding() || entity.isElytraFlying()) {
            return "excludedState";
        }
        if (entity.isInWater() || entity.isInLava() || entity.isOnLadder()) {
            return "excludedMedium";
        }
        if (entity.isPotionActive(MobEffects.LEVITATION)) {
            return "excludedLevitation";
        }
        if (entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying) {
            // Creative flight is not excluded for a body a deck already holds (its flight then runs on
            // DECK axes) or for a flyer the deck may CLAIM right now - standing contact or an enclosed
            // interior. A flyer anywhere else (open airspace, flown away from his seat, over terrain)
            // keeps world-frame flight untouched.
            if (!DeckFrameTick.holds(entity) && !flyCaptureEligible(entity)) {
                return "creativeFlight";
            }
        }
        return null;
    }

    /** Whether a creative FLYER is the deck's to claim right now: supported by a ship's blocks in
     *  its own frame (standing contact) or inside an enclosed interior - and not on world terrain.
     *  Everything else keeps world-frame flight. */
    private static boolean flyCaptureEligible(EntityLivingBase entity) {
        if (isSupportedByWorldTerrain(entity)) {
            return false;
        }
        return firstContactCandidate(entity) != null || interiorCandidate(entity) != null;
    }

    /** The ship this body is standing on RIGHT NOW, chosen among every loaded ship whose grown world
     *  box contains it by testing deck support in each candidate's OWN frame - not by first-match
     *  containment, which flips between overlapping parked ships. Null when no candidate supports it. */
    private static String firstContactCandidate(EntityLivingBase entity) {
        // A DECLARED ship outranks a spatial guess. An arrival, a relog or a displaced pilot has
        // already established which craft this body belongs to and put it on that craft's deck
        // point; the sweep below only knows where the body is STANDING, and where two hulls overlap
        // that is not the same question. Testing support first keeps the two answers honest: a
        // declaration for a ship with no deck under these feet is not a capture anyone wants, so it
        // falls through to the sweep rather than forcing a hold onto geometry that cannot carry it.
        // A hold has no client half: a client body declares nothing.
        String declared = entity.world.isRemote ? null
                : dev.stannismod.stellurgy.Stellurgy.serverState().deckHolds.heldShipId(entity);
        if (declared != null && shipSupportObstacleCountFor(entity, declared) > 0) {
            return declared;
        }
        for (String shipId : VSIntegration.shipIdsAt(
                entity.world, entity.posX, entity.posY, entity.posZ)) {
            if (shipSupportObstacleCountFor(entity, shipId) > 0) {
                return shipId;
            }
        }
        return null;
    }

    /**
     * Seam: what became of a queued seed — still waiting for its ship on this side, or how it ended.
     *
     * <p>A dismount whose seed never lands hands the body to vanilla's world-frame dismount spot,
     * which on a non-upright ship maps OFF the deck; a seed that lands after its body was put
     * somewhere else snaps it back. So the outcome is the fact worth keeping, and the body it
     * concerns is the half that decides whose it was. It needs a method because the outcomes happen
     * inside a method whose queue slot is a local of a private type; the body is empty and its
     * parameters are exactly those locals: it computes nothing, allocates nothing and stores
     * nothing.</p>
     *
     * @param outcome {@code pending-not-loaded} (it waits on), or how it ended: {@code applied},
     *                {@code refused-by-deck}, {@code expired}, {@code already-seeded},
     *                {@code kept-preexisting}, {@code void-teleported}, {@code void-mounted}
     */
    private static void noteSeedOutcome(Entity entity, String shipId, String outcome) {
    }

    /**
     * The SUBSPACE point a deck holds {@code entity} at, or {@code null} when no deck holds it.
     *
     * <p>This is the durable record's view of an aboard body: the point is the deck's own rather than
     * re-derived from the entity's world position, so the position that survives a logout is exactly
     * the one the deck held — no second conversion to disagree with. Pair it with
     * {@link #aboardShipId} for the ship the point is expressed against.</p>
     */
    public static double[] aboardShipFramePoint(Entity entity) {
        return entity == null ? null : DeckFrameTick.heldDeckPoint(entity);
    }

    // ---- Pending dismount seed (client main thread only). --------------------------------------
    //
    // A seat-dismount deck point is a BOARDING INTENT, not a fire-and-forget packet. Applied directly
    // it loses a race it must not be racing: the client's isRiding lingers a few ticks after the
    // dismount, so an immediate seed is refused as an excluded state. The pending slot waits for the
    // exclusion to clear and then applies EXACTLY ONCE, superseding any hold taken during its window
    // (within the dismount window the seat's deck point is BY DEFINITION where a dismount puts the
    // body). A hold that PREDATES the slot is respected and the slot dissolves. If the exclusion
    // outlives the TTL (a pilot who dismounted straight into creative flight and left), the slot
    // expires and the body is never snapped - the one-shot application is what keeps a per-tick
    // teleport war impossible. A slot is made for the body where it was when the slot was made: a
    // teleport applied since, or a mount it was not on then, voids it. Every end is reported through
    // noteSeedOutcome.

    /** How long a pending seed waits for the body to become capturable. Comfortably above the
     *  riding-flag tail and a ship's client-side streaming delay; re-sent packets refresh it. */
    private static final int PENDING_SEED_TTL_TICKS = 40;

    private static final class PendingSeed {
        final java.lang.ref.WeakReference<Entity> body;
        final String shipId;
        final double subX, subY, subZ;
        /** The body's hold stamp at slot creation: holds with a LARGER stamp were opened during this
         *  seed's window and are superseded by it. */
        final long epoch;
        /** A RESTORE seed re-establishes a recorded state and outranks any hold this side made on
         *  its own; see {@code PacketDeckCapture#restore}. */
        final boolean restore;
        /** The body's teleport count when the slot was made: a different count voids it. */
        final int teleports;
        /** The id of what the body rode when the slot was made, or -1: riding anything else voids it
         *  (the dismount's own riding tail is the seat it is leaving). */
        final int ridingAtInstall;
        /** Where the body was when the slot was made - where a restore seed keeps it while it waits. */
        final double atX, atY, atZ;
        int ticksLeft;
        /** Whether this intent has ended (taken, refused, void, superseded): its slot then only
         *  absorbs re-sends of it until they stop. */
        boolean ended;

        PendingSeed(Entity body, String shipId, double subX, double subY, double subZ,
                    boolean restore) {
            this.body = new java.lang.ref.WeakReference<Entity>(body);
            this.shipId = shipId;
            this.subX = subX;
            this.subY = subY;
            this.subZ = subZ;
            this.epoch = ((DeckHeld) body).stellurgy$holdStamp();
            this.restore = restore;
            this.teleports = ((DeckHeld) body).stellurgy$teleports();
            this.ridingAtInstall = body.getRidingEntity() == null ? -1 : body.getRidingEntity().getEntityId();
            this.atX = body.posX;
            this.atY = body.posY;
            this.atZ = body.posZ;
            this.ticksLeft = PENDING_SEED_TTL_TICKS;
        }

        /** Why this slot no longer stands for the body, or {@code null} while it does. */
        String voidedBy(Entity body) {
            if (((DeckHeld) body).stellurgy$teleports() != teleports) {
                return "void-teleported";
            }
            Entity riding = body.getRidingEntity();
            if (riding != null && riding.getEntityId() != ridingAtInstall) {
                return "void-mounted";
            }
            return null;
        }
    }

    /** The pending seeds of the bodies in one client world, as that world's part
     *  ({@link dev.stannismod.stellurgy.world.WorldRuntime}): a seed places a body in that world and
     *  goes with it. Weak keys, so a seed never keeps a body alive. Client main thread only. */
    private static final class PendingSeeds {
        final Map<Entity, PendingSeed> byBody = new java.util.WeakHashMap<Entity, PendingSeed>();
    }

    private static Map<Entity, PendingSeed> pendingSeedsIn(World world) {
        return dev.stannismod.stellurgy.world.WorldRuntime.of(world, PendingSeeds.class, PendingSeeds::new).byBody;
    }

    /** What a pending seed should do this tick. Pure - pinned by unit tests. */
    public enum PendingSeedDecision { WAIT, EXPIRE, VOID, ALREADY_SEEDED, KEEP_PREEXISTING, APPLY }

    /**
     * The pending seed's state machine, as a pure function of the observable facts: a seed that
     * already took is done; a seed whose body has been put somewhere since, or has mounted something,
     * is void; a hold that predates the slot wins over the seed; an expired slot dissolves without
     * ever snapping; an excluded body is waited for; otherwise the seed applies (superseding a
     * window-opened hold, if any).
     *
     * <p>{@code restore} distinguishes a seed that RE-ESTABLISHES a recorded state from one that
     * opens a new episode, and only one rule differs: "a hold that predates the slot wins" is right
     * for a dismount — the body was already legitimately held and the seat point is merely the deck
     * spot a dismount is defined to deliver — and wrong for a restore, where every hold on this side
     * is younger than the player entity itself and the durable record is the authority. Applied to a
     * login, that rule handed the body to a first-contact capture holding the position AND VELOCITY
     * vanilla had just restored, and the crew member skated off along the walk he logged out on.</p>
     */
    public static PendingSeedDecision pendingSeedDecision(boolean excluded, int ticksLeft,
                                                          boolean captureExists,
                                                          boolean captureIsThisSeed,
                                                          boolean capturePredatesSlot,
                                                          boolean restore,
                                                          boolean voided) {
        if (captureExists && captureIsThisSeed) {
            return PendingSeedDecision.ALREADY_SEEDED;
        }
        if (voided) {
            return PendingSeedDecision.VOID;
        }
        if (captureExists && capturePredatesSlot && !restore) {
            return PendingSeedDecision.KEEP_PREEXISTING;
        }
        if (ticksLeft <= 0) {
            return PendingSeedDecision.EXPIRE;
        }
        if (excluded) {
            return PendingSeedDecision.WAIT;
        }
        return PendingSeedDecision.APPLY;
    }

    /**
     * Install (or refresh) the pending seed for {@code entity} - the client half of
     * {@code PacketDeckCapture}. A re-send for the same target refreshes the TTL and keeps the
     * ORIGINAL epoch (the window is one logical dismount); a seed that has already taken no-ops.
     */
    public static void installPendingSeed(Entity entity, String shipId,
                                          double subX, double subY, double subZ) {
        installPendingSeed(entity, shipId, subX, subY, subZ, false);
    }

    /** As above; {@code restore} marks a seed that re-establishes a recorded state (a login) rather
     *  than opening a new episode (a dismount). See {@link #pendingSeedDecision}. */
    public static void installPendingSeed(Entity entity, String shipId,
                                          double subX, double subY, double subZ, boolean restore) {
        if (entity == null || shipId == null || entity.world == null || !entity.world.isRemote) {
            return;
        }
        DeckFrameTick.Episode deck = DeckFrameTick.episodeOf(entity);
        if (deck != null && deck.seedAnchored && shipId.equals(deck.shipId)) {
            return; // the seed already took; a re-send must not teleport the body again
        }
        Map<Entity, PendingSeed> seeds = pendingSeedsIn(entity.world);
        PendingSeed slot = seeds.get(entity);
        // The same intent re-sent: same craft, same kind, and the body still where the slot was made
        // for it. A new intent always comes with a teleport of its own (a dismount, a hold's one
        // teleport), so it never matches a slot made before it and replaces it.
        if (slot != null && slot.shipId.equals(shipId) && slot.restore == restore
                && slot.voidedBy(entity) == null) {
            slot.ticksLeft = PENDING_SEED_TTL_TICKS;
        } else {
            seeds.put(entity, new PendingSeed(entity, shipId, subX, subY, subZ, restore));
        }
        tryApplyPendingSeed(entity); // zero-tick fast path when nothing blocks
    }

    /** Per-client-tick driver for {@code player}'s pending seed; a no-op when none is pending. */
    public static void clientTickPendingSeed(Entity player) {
        if (player == null || player.world == null) {
            return;
        }
        Map<Entity, PendingSeed> seeds = pendingSeedsIn(player.world);
        PendingSeed slot = seeds.get(player);
        if (slot == null) {
            return;
        }
        if (player.isDead) {
            seeds.remove(player);
            return;
        }
        slot.ticksLeft--;
        tryApplyPendingSeed(player);
    }

    private static void tryApplyPendingSeed(Entity body) {
        if (body.world == null) {
            return;
        }
        Map<Entity, PendingSeed> seeds = pendingSeedsIn(body.world);
        PendingSeed slot = seeds.get(body);
        if (slot == null) {
            return;
        }
        if (body.isDead) {
            seeds.remove(body);
            return;
        }
        // An intent that has ended keeps its slot while its sender is still re-sending it, so a
        // re-send is recognised as the same intent and absorbed instead of applied a second time;
        // once nothing has re-sent it for a whole TTL, the slot goes.
        if (slot.ended) {
            if (slot.ticksLeft <= 0) {
                seeds.remove(body);
            }
            return;
        }
        DeckFrameTick.Episode deck = DeckFrameTick.episodeOf(body);
        boolean excluded = body instanceof EntityLivingBase
                && excludedStateOf((EntityLivingBase) body) != null;
        boolean captureExists = deck != null;
        boolean captureIsThisSeed = deck != null && deck.seedAnchored && slot.shipId.equals(deck.shipId);
        boolean capturePredatesSlot = deck != null && deck.installEpoch <= slot.epoch;
        String voidedBy = slot.voidedBy(body);
        PendingSeedDecision decision = pendingSeedDecision(excluded, slot.ticksLeft,
                captureExists, captureIsThisSeed, capturePredatesSlot, slot.restore, voidedBy != null);
        switch (decision) {
            case WAIT:
                return;
            case VOID:
                slot.ended = true;
                noteSeedOutcome(body, slot.shipId, voidedBy);
                return;
            case EXPIRE:
                seeds.remove(body);
                noteSeedOutcome(body, slot.shipId, "expired");
                return;
            case ALREADY_SEEDED:
                slot.ended = true;
                noteSeedOutcome(body, slot.shipId, "already-seeded");
                return;
            case KEEP_PREEXISTING:
                slot.ended = true;
                noteSeedOutcome(body, slot.shipId, "kept-preexisting");
                return;
            case APPLY:
            default:
                double[] world = VSIntegration.toWorldFrameFor(
                        body.world, slot.shipId, slot.subX, slot.subY, slot.subZ);
                if (world == null) {
                    noteSeedOutcome(body, slot.shipId, "pending-not-loaded");
                    return; // the ship is not on this side yet: stay pending, retry next tick
                }
                if (dev.stannismod.stellurgy.command.test.TestProbeCommandRegistration.isTestMode()) {
                    dev.stannismod.stellurgy.Stellurgy.logger.info("[FF-TRACE/CAP] pending "
                            + "seed applied ship=" + slot.shipId + " superseded=" + captureExists
                            + " world=(" + world[0] + "," + world[1] + "," + world[2] + ")");
                }
                // A deck point for the player this client plays is the deck frame's to hold: a body it
                // refuses is in a state no deck holds, so the seed is spent either way and the
                // server's window decides whether to send another.
                boolean taken = DeckFrameTick.holdSeeded(body, slot.shipId, slot.subX, slot.subY, slot.subZ, world);
                slot.ended = true;
                noteSeedOutcome(body, slot.shipId, taken ? "applied" : "refused-by-deck");
        }
    }

    /**
     * Keep the player this client plays where a pending RESTORE seed found him, while it waits.
     *
     * <p>A restore seed is the client half of a server hold that puts a returning or arriving body
     * on its deck point with one teleport and then waits for this client to take it. Until the craft
     * streams in here, nothing holds the body: vanilla would drop it off a deck the client cannot see
     * yet. So for those ticks the body is still - its own movement is not run, its velocity and fall
     * are zero - and everything else about the player (his clicks, his item use, his screens) goes on.
     * A dismount seed is not held this way: its body is standing on a deck the client already has.</p>
     *
     * @return whether the body is being held still this tick, so its own movement must not run
     */
    public static boolean holdStillForRestore(Entity body) {
        if (body == null || body.world == null) {
            return false;
        }
        PendingSeed slot = pendingSeedsIn(body.world).get(body);
        if (slot == null || slot.ended || !slot.restore || slot.voidedBy(body) != null
                || DeckFrameTick.episodeOf(body) != null
                || (body instanceof EntityLivingBase && excludedStateOf((EntityLivingBase) body) != null)) {
            return false;
        }
        body.setPosition(slot.atX, slot.atY, slot.atZ);
        body.motionX = 0.0;
        body.motionY = 0.0;
        body.motionZ = 0.0;
        body.fallDistance = 0.0f;
        return true;
    }

    /** Whether a deck holds {@code entity} — the gate for the deck-levelled camera, the deck mouse
     *  basis and every other "this body lives in the deck's frame" consumer. */
    public static boolean isResolvingAboard(Entity entity) {
        return entity != null && DeckFrameTick.holds(entity);
    }

    /**
     * The id of the ship {@code entity} has STANDING support from, measured spatially in that
     * ship's subspace, or {@code null} when no loaded ship carries it.
     *
     * <p>The question a consumer asks about a body whose movement THIS side never decides - on a
     * client that is every body but the local player - and so has no hold to read: without it,
     * "is that other body standing on a ship" falls back to world-AABB containment, which is true
     * across a large air volume around the hull. The probe needs only the ship's blocks in subspace,
     * which both sides hold.</p>
     *
     * <p>Answers about SUPPORT, not about aboard-ness: a body in mid-jump over the deck is
     * momentarily unsupported and comes back {@code null}. A consumer that must not flicker
     * across a jump is responsible for its own hysteresis.</p>
     */
    public static String standingOnShipIdFor(EntityLivingBase entity) {
        if (entity == null || entity.world == null) {
            return null;
        }
        return firstContactCandidate(entity);
    }

    /** The ship whose deck holds {@code entity}, or {@code null} when no deck holds it. The
     *  deck-frame look derives the crew member's world aim through THIS ship, never by re-picking a
     *  ship from world-AABB containment mid-episode. */
    public static String aboardShipId(Entity entity) {
        return entity == null ? null : DeckFrameTick.heldShipId(entity);
    }

    /**
     * A read-only account of whether a deck holds {@code entity} and, when none does, whether one
     * could take it where it stands — every gate, the ship-frame support obstacle count under the
     * feet, and the verdict. Fed to the {@code /stellurgytest vs deck-capture} probe so a live
     * playtest can see WHY a body standing on a deck is or is not held: not aboard at all,
     * aboard-by-containment but with no solid block under the feet in the ship's subspace, or held.
     */
    public static Map<String, Object> explainDeckCapture(EntityLivingBase entity) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (entity == null || entity.world == null) {
            m.put("verdict", false);
            m.put("reason", "no entity/world");
            return m;
        }
        m.put("isRemote", entity.world.isRemote);
        m.put("isServerWorld", entity.isServerWorld());
        m.put("canPassengerSteer", entity.canPassengerSteer());
        m.put("isRiding", entity.isRiding());
        m.put("isElytraFlying", entity.isElytraFlying());
        m.put("isFlying", entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying);
        boolean aboard = VSIntegration.shipAttitudeAt(
                entity.world, entity.posX, entity.posY, entity.posZ) != null;
        m.put("aboardByContainment", aboard);
        // WHICH hulls, and how many. `aboardByContainment` is a boolean about "a ship", and the
        // unnamed support reading below resolves the frame by containment and takes the first match.
        // Two hulls may occupy the same space, so a list is the honest answer and its SIZE is the
        // part a caller has to look at: one entry means the unnamed reading beside it is unambiguous,
        // two mean it is a coin toss that will read as a clean number either way.
        m.put("containingShipIds", VSIntegration.shipIdsAt(
                entity.world, entity.posX, entity.posY, entity.posZ));
        boolean terrain = isSupportedByWorldTerrain(entity);
        String deckHeld = DeckFrameTick.heldShipId(entity);
        if (deckHeld != null) {
            double[] point = DeckFrameTick.heldDeckPoint(entity);
            double[] live = VSIntegration.toShipFrameFor(entity.world, deckHeld,
                    entity.posX, entity.posY, entity.posZ);
            m.put("alreadyTracked", true);
            m.put("anchorShipId", deckHeld);
            m.put("heldBy", "deckFrame");
            // The deck's own held point, beside the BODY's live position mapped into the same frame:
            // the held point only changes when the deck commits one, so a reader watching it alone
            // sees "perfectly still" for a body something else is carrying around.
            m.put("shipFrameX", point == null ? null : point[0]);
            m.put("shipFrameY", point == null ? null : point[1]);
            m.put("shipFrameZ", point == null ? null : point[2]);
            m.put("bodyShipFrameX", live == null ? null : live[0]);
            m.put("bodyShipFrameY", live == null ? null : live[1]);
            m.put("bodyShipFrameZ", live == null ? null : live[2]);
            m.put("supportedByWorldTerrain", terrain);
            int shipObstacles = shipSupportObstacleCountFor(entity, deckHeld);
            m.put("shipFrameResolved", shipObstacles >= 0);
            m.put("shipSupportObstacles", shipObstacles);
            m.put("supportedByShip", shipObstacles > 0);
            m.put("verdict", true);
            return m;
        }
        m.put("alreadyTracked", false);
        m.put("anchorShipId", null);
        m.put("heldBy", null);
        m.put("supportedByWorldTerrain", terrain);
        // Whether a deck could take him where he stands: an excluded state never, terrain under the
        // feet never, otherwise the ship that gives him standing support in its own frame.
        boolean excluded = excludedStateOf(entity) != null;
        String candidate = excluded || terrain ? null : firstContactCandidate(entity);
        m.put("firstContactCandidate", candidate);
        int shipObstacles = candidate != null
                ? shipSupportObstacleCountFor(entity, candidate)
                : shipSupportObstacleCount(entity);
        m.put("shipFrameResolved", shipObstacles >= 0);
        m.put("shipSupportObstacles", shipObstacles);
        m.put("supportedByShip", shipObstacles > 0);
        m.put("verdict", candidate != null);
        return m;
    }

    // ---- Subspace census: does THIS side's world actually CONTAIN the ship's subspace blocks? ----
    //
    // Every deck probe and interior gate reads block states at the ship's SUBSPACE coordinates (the
    // shipyard region, far from the ship's world position). A side whose world never received those
    // chunks answers every one of those reads with "air", and crew mechanics silently degrade. The
    // census tells that WORLD-CONTENT failure (chunkLoaded=false / nonAir=0) apart from a probe
    // defect (blocks present, collision boxes still not found). The CLIENT takes it at the seam below
    // (the measurement itself is on the test side); the server answers the same census on demand
    // through the `/stellurgytest vs subspace-census` probe as the control side.

    /**
     * Seam: a client tick at which the subspace census COULD be taken for {@code entity}.
     *
     * <p>A SEAM, and nothing else — and this one used to do real work. It ran
     * {@link #subspaceCensusFor} on EVERY client tick near a ship (a 7x7x7 block scan plus a
     * collision-box query) purely to refresh nine statics that only tests read, in a class that
     * ships to players. The census is read-only and public, so the test side takes it here instead:
     * the same measurement, at the same cadence, in the runs that ask for it.</p>
     */
    public static void clientCensusTick(EntityLivingBase entity) {
    }

    /**
     * The census itself, side-neutral and READ-ONLY: resolve the entity's position into the subspace
     * of the ship whose deck holds it and measure what this side's world holds there. {@code null}
     * when no deck holds it on this side or its craft's transform is unavailable.
     */
    public static Map<String, Object> subspaceCensusFor(EntityLivingBase entity) {
        return subspaceCensusFor(entity, false);
    }

    /**
     * @param deep also sweep the region grown by 8 for iron blocks ({@code ironNear}) - locates
     *             fixture deck blocks that were relocated OUTSIDE the ship's reported bounds.
     *             Probe-only cost; the per-tick client sampler passes false.
     */
    public static Map<String, Object> subspaceCensusFor(EntityLivingBase entity, boolean deep) {
        if (entity == null || entity.world == null) {
            return null;
        }
        // THE SHIP IS THE ONE WHOSE DECK HOLDS THIS BODY, or there is no census.
        //
        // A positional fallback stood here until 2026-09-14: when nothing had declared a ship it took
        // `shipIdsAt(...)` and used `ids.get(0)`. That is a first-match taken as an IDENTITY: two hulls
        // may occupy the same space, and every number below is computed in one ship's frame, so a
        // census built on the wrong hull is not approximately right; it is a well-formed report about
        // a craft the caller never asked about. Absence is the honest answer instead.
        String shipId = aboardShipId(entity);
        if (shipId == null) {
            return null;
        }
        double[] sub = VSIntegration.toShipFrameFor(
                entity.world, shipId, entity.posX, entity.posY, entity.posZ);
        if (sub == null) {
            return null;
        }
        World world = entity.world;
        BlockPos feet = new BlockPos(
                MathHelper.floor(sub[0]), MathHelper.floor(sub[1]), MathHelper.floor(sub[2]));
        int nonAir = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (!world.isAirBlock(feet.add(dx, dy, dz))) {
                        nonAir++;
                    }
                }
            }
        }
        double halfWidth = entity.width / 2.0;
        AxisAlignedBB feetBox = new AxisAlignedBB(
                sub[0] - halfWidth, sub[1], sub[2] - halfWidth,
                sub[0] + halfWidth, sub[1] + entity.height, sub[2] + halfWidth)
                .expand(0.0, -1.0, 0.0);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("isRemote", world.isRemote);
        m.put("shipId", shipId);
        // Always true by now - a body no deck holds returned null above; kept for its readers.
        m.put("tracked", true);
        m.put("heldBy", "deckFrame");
        m.put("subPos", feet.getX() + "," + feet.getY() + "," + feet.getZ());
        m.put("chunkLoaded", world.isBlockLoaded(feet));
        m.put("nonAir", nonAir);
        m.put("collisionBoxes", world.getCollisionBoxes(null, feetBox).size());
        // The fixed-point half of the census: the whole ship block region, independent of where the
        // body is. Content vanishing HERE means the ship's blocks left this side's world; a zero in
        // the body-relative counts alone only means the body wandered into air.
        m.put("shipBlocks", VSIntegration.shipBlockCount(world, shipId));
        AxisAlignedBB region = VSIntegration.subspaceStayRegion(world, shipId, 0.0);
        if (region == null) {
            m.put("region", "");
            m.put("regionNonAir", -1);
        } else {
            BlockPos lo = new BlockPos(MathHelper.floor(region.minX),
                    MathHelper.floor(region.minY), MathHelper.floor(region.minZ));
            BlockPos hi = new BlockPos(MathHelper.ceil(region.maxX),
                    MathHelper.ceil(region.maxY), MathHelper.ceil(region.maxZ));
            long volume = (long) (hi.getX() - lo.getX() + 1) * (hi.getY() - lo.getY() + 1)
                    * (hi.getZ() - lo.getZ() + 1);
            int regionNonAir = -1;
            if (volume > 0 && volume <= 8192) {
                regionNonAir = 0;
                for (BlockPos p : BlockPos.getAllInBoxMutable(lo, hi)) {
                    if (!world.isAirBlock(p)) {
                        regionNonAir++;
                    }
                }
            }
            m.put("region", lo.getX() + "," + lo.getY() + "," + lo.getZ()
                    + ".." + hi.getX() + "," + hi.getY() + "," + hi.getZ());
            m.put("regionNonAir", regionNonAir);
            if (deep) {
                // Iron blocks in the grown neighbourhood: fixture deck blocks that DID reach the
                // shipyard but landed outside the ship's reported bounds show up here.
                BlockPos glo = lo.add(-8, -8, -8);
                BlockPos ghi = hi.add(8, 8, 8);
                int ironNear = 0;
                int ilx = Integer.MAX_VALUE, ily = Integer.MAX_VALUE, ilz = Integer.MAX_VALUE;
                int ihx = Integer.MIN_VALUE, ihy = Integer.MIN_VALUE, ihz = Integer.MIN_VALUE;
                for (BlockPos p : BlockPos.getAllInBoxMutable(glo, ghi)) {
                    if (world.getBlockState(p).getBlock() == net.minecraft.init.Blocks.IRON_BLOCK) {
                        ironNear++;
                        if (p.getX() < ilx) ilx = p.getX();
                        if (p.getY() < ily) ily = p.getY();
                        if (p.getZ() < ilz) ilz = p.getZ();
                        if (p.getX() > ihx) ihx = p.getX();
                        if (p.getY() > ihy) ihy = p.getY();
                        if (p.getZ() > ihz) ihz = p.getZ();
                    }
                }
                m.put("ironNear", ironNear);
                m.put("ironBox", ironNear == 0 ? "" : ilx + "," + ily + "," + ilz
                        + ".." + ihx + "," + ihy + "," + ihz);
            }
        }
        return m;
    }

    /** {@link #shipSupportObstacleCount}, resolved through the ship {@code shipId} instead of a
     *  containment lookup. {@code -1} when that ship is not loaded on this side. */
    private static int shipSupportObstacleCountFor(Entity entity, String shipId) {
        return shipSupportObstacleCountAt(entity, shipId, VSIntegration.toShipFrameFor(
                entity.world, shipId, entity.posX, entity.posY, entity.posZ));
    }

    /**
     * As above, at an explicit ship-frame point; {@code -1} for a null point, which is the same
     * "ship not loaded" answer the derivation would have produced.
     *
     * <p>Counts only STANDING support - boxes whose TOP face is at/below the feet. A body that
     * punched INTO the hull from outside (the world-top of an inverted ship - outer hull, and so
     * world-frame territory) intersects the probe with boxes whose top is ABOVE its feet; counting
     * those as "support" claimed the hull-top stander for a deck that can never seat him (no floor
     * under him in subspace) - the #49 thrash. The probe reaches further for a fast faller so it is
     * caught before it can tunnel through a thin deck in one tick.</p>
     */
    private static int shipSupportObstacleCountAt(Entity entity, String shipId, double[] local) {
        if (local == null) {
            return -1;
        }
        double reach = SUPPORT_PROBE;
        double[] motion = VSIntegration.rotateToShipFrameFor(entity.world, shipId,
                entity.motionX, entity.motionY, entity.motionZ);
        if (motion != null && motion[1] < 0.0) {
            reach += -motion[1];
        }
        double half = entity.width / 2.0;
        AxisAlignedBB underFeet = new AxisAlignedBB(
                local[0] - half, local[1] - reach, local[2] - half,
                local[0] + half, local[1], local[2] + half);
        int standing = 0;
        for (AxisAlignedBB box : entity.world.getCollisionBoxes(entity, underFeet)) {
            if (box.maxY <= local[1] + STANDING_TOLERANCE) {
                standing++;
            }
        }
        return standing;
    }

    /** A support box's top may sit this far above the mapped feet and still count as STANDING on it
     *  - absorbs the ~1e-8 world<->subspace round-trip noise plus a de-penetration hair. Anything
     *  higher is the body INTERSECTING geometry, not standing on it. */
    private static final double STANDING_TOLERANCE = 0.05;
    /** How far below the feet (in the ship frame) a floor must exist for a deck to keep a body.
     *  Comfortably above a jump apex (~1.25) and interior drops; a body with NO floor within this
     *  reach can never be seated on a deck by deck gravity - it is on the outer hull or past the
     *  underside, which is the world's. */
    private static final double FLOOR_PROBE_DEPTH = 6.0;

    /** Whether ANY standing floor exists within {@link #FLOOR_PROBE_DEPTH} below the body's feet in
     *  craft {@code shipId}'s frame. Returns true on a failed lookup: an unloaded craft is the deck
     *  frame's own release, not this test's. */
    private static boolean hasDeckBelowFor(Entity entity, String shipId) {
        return hasDeckBelowAt(entity, shipId, VSIntegration.toShipFrameFor(
                entity.world, shipId, entity.posX, entity.posY, entity.posZ));
    }

    /**
     * Whether a deck may go on holding {@code entity} at ship-frame point {@code local} of craft
     * {@code shipId}: a floor within reach below it, or inside the craft's own block region under
     * its roof. A body off the deck's edge, or out on the outer hull, is the world's, not the deck's.
     */
    static boolean deckMayKeep(Entity entity, String shipId, double[] local) {
        if (hasDeckBelowAt(entity, shipId, local)) {
            return true;
        }
        AxisAlignedBB own = VSIntegration.subspaceStayRegion(entity.world, shipId, 0.0);
        return own != null && local != null
                && own.contains(new net.minecraft.util.math.Vec3d(local[0], local[1], local[2]))
                && hasRoofAboveAt(entity, shipId, local);
    }

    /** As above, at an explicit ship-frame point. */
    private static boolean hasDeckBelowAt(Entity entity, String shipId, double[] local) {
        if (local == null) {
            return true;
        }
        double half = entity.width / 2.0;
        AxisAlignedBB column = new AxisAlignedBB(
                local[0] - half, local[1] - FLOOR_PROBE_DEPTH, local[2] - half,
                local[0] + half, local[1] + STANDING_TOLERANCE, local[2] + half);
        for (AxisAlignedBB box : entity.world.getCollisionBoxes(entity, column)) {
            if (box.maxY <= local[1] + STANDING_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** The ship whose ENCLOSED interior contains the body - inside the margin-0 block region,
     *  with a deck below AND a roof above in that ship's frame - or null. The interior-boarding
     *  candidate (a hatch entry, an inverted cockpit): such a body is the deck's to claim even
     *  without standing support, because deck gravity can seat it. */
    static String interiorCandidate(EntityLivingBase entity) {
        for (String shipId : VSIntegration.shipIdsAt(
                entity.world, entity.posX, entity.posY, entity.posZ)) {
            double[] sub = VSIntegration.toShipFrameFor(
                    entity.world, shipId, entity.posX, entity.posY, entity.posZ);
            if (sub == null) {
                continue;
            }
            AxisAlignedBB region = VSIntegration.subspaceStayRegion(entity.world, shipId, 0.0);
            if (region != null
                    && region.contains(new net.minecraft.util.math.Vec3d(sub[0], sub[1], sub[2]))
                    && hasDeckBelowFor(entity, shipId)
                    // Enclosure, not just containment: the block-bounds region includes the
                    // OPEN air over a deck, and claiming that hijacked a body merely flying
                    // through the ship's airspace. A roof overhead is what makes "inside".
                    && hasRoofAboveFor(entity, shipId)) {
                return shipId;
            }
        }
        return null;
    }

    /** Whether SHIP blocks sit anywhere ABOVE the body's head in {@code shipId}'s frame, up to the
     *  top of the ship's block region - the "enclosed" half of the interior test. A ship's margin-0
     *  block-bounds region over-covers: it includes the OPEN air between a deck and the ship's
     *  topmost blocks, and "inside the region + deck below" alone therefore claimed a body merely
     *  flying through that airspace - the fly-through hijack a bystander must never suffer. A roof
     *  overhead is what distinguishes a hull CAVITY - a hatch entry, an inverted cockpit - from
     *  open air over a deck. */
    private static boolean hasRoofAboveFor(Entity entity, String shipId) {
        return hasRoofAboveAt(entity, shipId, VSIntegration.toShipFrameFor(
                entity.world, shipId, entity.posX, entity.posY, entity.posZ));
    }

    /** As above, at an explicit ship-frame point. */
    private static boolean hasRoofAboveAt(Entity entity, String shipId, double[] local) {
        if (local == null) {
            return false;
        }
        AxisAlignedBB region = VSIntegration.subspaceStayRegion(entity.world, shipId, 0.0);
        if (region == null) {
            return false;
        }
        double top = local[1] + entity.height;
        if (top >= region.maxY) {
            return false;
        }
        double half = entity.width / 2.0;
        AxisAlignedBB column = new AxisAlignedBB(
                local[0] - half, top, local[2] - half,
                local[0] + half, region.maxY, local[2] + half);
        return !entity.world.getCollisionBoxes(entity, column).isEmpty();
    }

    /** How many SHIP-frame collision boxes sit directly beneath the entity's feet, resolved by
     *  world-AABB CONTAINMENT (first match) - or {@code -1} when the entity maps to no loaded ship
     *  frame at all. DIAGNOSTIC-ONLY: every decision goes through
     *  {@link #shipSupportObstacleCountFor}; this remains for the deck-capture probe, where "no ship
     *  here" ({@code -1}) vs "ship here but nothing under the feet" ({@code 0}) vs "supported"
     *  ({@code > 0}) localises a failure. */
    private static int shipSupportObstacleCount(Entity entity) {
        double[] local = VSIntegration.toShipFrame(entity, entity.posX, entity.posY, entity.posZ);
        if (local == null) {
            return -1;
        }
        double reach = SUPPORT_PROBE;
        double[] motion = VSIntegration.rotateToShipFrame(entity,
                entity.motionX, entity.motionY, entity.motionZ);
        if (motion != null && motion[1] < 0.0) {
            reach += -motion[1];
        }
        double half = entity.width / 2.0;
        AxisAlignedBB underFeet = new AxisAlignedBB(
                local[0] - half, local[1] - reach, local[2] - half,
                local[0] + half, local[1], local[2] + half);
        return entity.world.getCollisionBoxes(entity, underFeet).size();
    }

    /** Whether solid WORLD collision sits directly beneath the entity's feet - it stands on real
     *  ground, which no deck claims. */
    private static boolean isSupportedByWorldTerrain(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        AxisAlignedBB underFeet = new AxisAlignedBB(
                box.minX, box.minY - SUPPORT_PROBE, box.minZ,
                box.maxX, box.minY, box.maxZ);
        return !entity.world.getCollisionBoxes(entity, underFeet).isEmpty();
    }

    /** How far below the feet to look for a supporting block. Small, so a body genuinely airborne over a
     *  deck is not read as standing on it; extended by the fall speed for a fast faller. */
    private static final double SUPPORT_PROBE = 0.30;

    /**
     * The client's answer about a body whose LOOK this side owns - the question aboard movement must
     * ask the client and cannot answer itself.
     *
     * <p>The boundary is real: the deck frame holds bodies on both sides, while the deck look is
     * client-only state that a dedicated server never has. So the client hands its answer DOWN
     * through this port; nothing here reaches up into a client class.</p>
     *
     * <p>{@code null} is the answer for a body this client does not own (a mob, a remote player),
     * which is the caller's signal to fall back to what the world frame can tell it. That is a
     * per-BODY answer, and it is the only meaning {@code null} carries once the port itself is
     * installed - see {@link #installClientLookSource}.</p>
     */
    public interface ClientLookSource {

        /** The held DECK-frame heading (degrees) for {@code entity}, or {@code null} when this
         *  client does not hold that body's look. */
        Float deckYaw(EntityLivingBase entity);
    }

    /** The installed client port, or {@code null} on a dedicated server, which has no client look
     *  and where a player's flight is client-authoritative anyway. Written only by
     *  {@link #installClientLookSource}.
     *
     * Effectively final, process lifetime: written only by DeckBoarding.installClientLookSource.
     */
    private static volatile ClientLookSource clientLookSource = null;

    /**
     * Install the client's look port. Called once, from the client's own init - NOT from a static
     * initialiser of the class that implements it.
     *
     * <p>The distinction is the whole point of the method. Under a static initialiser the port
     * appeared whenever something happened to class-load the implementor, so a {@code null} port on
     * a CLIENT meant "nobody has touched that class yet" and aboard movement silently fell back to
     * the world-frame projection - a degradation indistinguishable from success, at exactly the
     * moment (the first aboard tick after login) it was most likely. Installed from a lifecycle hook
     * that runs before any world exists, {@code null} means "dedicated server" and nothing else.</p>
     *
     * @throws IllegalStateException if a port is already installed - a second install over a live
     *         one is a lifecycle bug, and overwriting silently would hide it
     */
    public static void installClientLookSource(ClientLookSource source) {
        if (source == null) {
            throw new IllegalArgumentException("client look source must not be null");
        }
        if (clientLookSource != null) {
            throw new IllegalStateException("a client look source is already installed ("
                    + clientLookSource.getClass().getName() + "); a second install is a lifecycle bug");
        }
        clientLookSource = source;
    }

    /** The entity's facing, as a yaw in the ship frame: the held deck heading when this client
     *  owns the look, else his world heading rotated into that frame.
     *  YAW-ONLY (look pitch zeroed), exactly as the render body-yaw path does
     *  ({@code ShipFrameCamera.deckYawDeg}). A walk basis must not swing with look pitch: on a tilted deck
     *  the FULL look vector's ship-frame XZ heading DOES depend on pitch (world {@code +Y} leaks into ship
     *  X/Z under the rotation), so using {@code getLookVec()} the basis swung as the crew looked up/down and
     *  collapsed to one fixed heading when he looked along the deck normal - the natural pose walking an
     *  inverted deck, which read as inverted/rotated WASD. Vanilla walks by yaw alone for the same reason. */
    static float deckYawDeg(EntityLivingBase entity, String shipId) {
        // One transform for input, aim and movement: when this client HOLDS the body's look in
        // the deck frame, that stored deck yaw IS the heading the player steers by. The derived
        // world yaw is only a projection of it - skewed on a rolled ship, and DEGENERATE when
        // the deck goes vertical (the world look is near the pole, its yaw frozen or swinging),
        // where mapping it back decoupled walking from the keys entirely.
        ClientLookSource client = clientLookSource;
        if (client != null) {
            Float deckYaw = client.deckYaw(entity);
            if (deckYaw != null) {
                return deckYaw;
            }
        }
        float yawRad = entity.rotationYaw * 0.017453292F;
        double fx = -MathHelper.sin(yawRad);
        double fz = MathHelper.cos(yawRad);
        double[] deckLook = VSIntegration.rotateToShipFrameFor(entity.world, shipId, fx, 0.0, fz);
        if (deckLook == null) {
            return entity.rotationYaw;
        }
        return FreeFlightPhysics.yawFromForwardDeg(deckLook[0], deckLook[1], deckLook[2]);
    }
}
