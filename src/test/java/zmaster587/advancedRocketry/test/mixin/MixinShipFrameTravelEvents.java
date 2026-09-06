package zmaster587.advancedRocketry.test.mixin;

import java.util.Map;

import com.google.common.collect.MapMaker;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The deck capture's life as events, on whichever side it happens: a body is TAKEN by a ship's
 * deck, its MODE is committed (aboard vs hull-stand), it is RELEASED — with the gate that released
 * it, in production's own words — and the per-tick decisions around it are readable as records.
 *
 * <h2>The events, each taken where production already answers</h2>
 *
 * <ul>
 *   <li>{@code deck_captured} — TAIL of {@code captureState}: the anchored capture state as
 *       production just installed it. Carries the deck point, the world Y and the {@code carry}
 *       triple production bound as the deck velocity the body's motion contains. <b>Not an
 *       edge</b>: {@code captureState} is also the per-tick COMMIT — every resolved tick goes
 *       through {@code remember}, which rebuilds the state — so a body held by a deck emits this
 *       once a tick and fills its own 256-deep ring in about thirteen seconds. A first contact, a
 *       seed and a routine commit are not distinguishable here; the {@code carry} triple and the
 *       deck point are, which is what the value under test is.</li>
 *   <li>{@code deck_released} — HEAD of {@code release}, gated on {@code isResolving}: only a body
 *       that IS tracked has an episode to end, and {@code release} no-ops for an untracked one. The
 *       gate is {@code isResolving} and NOT {@code aboardShipId}, because a HULL-STAND body answers
 *       null to the latter and its release used to go unrecorded — a "churn == 0" read on it could
 *       not fail. {@code mode} says which semantics the body was under when it was let go.</li>
 *   <li>{@code deck_mode_committed} — HEAD of {@code logCapture}, the one private method every
 *       mode transition calls after it has set the state (hull→aboard, aboard→hull, first contact
 *       once {@code hullStand} is set, the externalMove re-capture). HEAD runs before production's
 *       own {@code isTestMode} gate, so the record does not depend on the log line being enabled.
 *       {@code mode} is read from {@code aboardShipId}: {@code "hull"} when null, else
 *       {@code "aboard"}.</li>
 *   <li>{@code interior_claimed} — RETURN of {@code interiorCandidate}: the ship whose ENCLOSED
 *       interior contains an UNTRACKED body (deck below, roof above, in that ship's own frame).
 *       Recorded only for a non-null answer on a body {@code isResolving} does not already hold;
 *       one tick may ask several times (the capture gate's own probe, and the creative-flight
 *       eligibility test, each on every {@code handles} call of that tick) and so may record
 *       several times before the capture lands — after which the {@code isResolving} filter
 *       silences it.</li>
 *   <li>{@code deck_seed_decided} — RETURN of the SIX-arg {@code pendingSeedDecision} (the five-arg
 *       form delegates to it): the pure verdict of the pending-seed state machine, with every fact it
 *       was given. Pure and entity-less, so it is routed by the calling thread's side
 *       ({@code recordHere}); on the client that is the pending-seed tick.</li>
 *   <li>{@code deck_gate_decided} — RETURN of {@code handles}, players only: whether this tick's
 *       movement is the ship frame's to resolve, and whether the body is tracked after the gate ran.
 *       Once per body per world TICK — not per call: three consumers ask {@code handles} in one tick
 *       ({@code travel}, {@code jump}, {@code GravityHandler}), and recording each would turn its
 *       256-deep ring over in a few seconds. A mid-tick change of the answer is still recorded, so
 *       the newest record is always the gate's current answer rather than its last flip.</li>
 *   <li>{@code deck_contact} — RETURN of {@code travel}, only when the resolver handled the tick
 *       ({@code true}) AND {@code onGround} flipped false→true since the previous {@code travel}
 *       return for that body. A landing on the deck (or the hull) as an edge, not a state. This
 *       RETURN already carries a second injector ({@code MixinShipFrameTravelWrites}, client list,
 *       {@code ship_frame_motion}); two mixins may share one injection point, and the two handlers
 *       are independent — neither can cancel or reorder the other.</li>
 * </ul>
 *
 * <p>Routed by the body's own world, so the client's resolver writes the client log and the
 * server's the server log — the two never see each other's captures. {@code deck_seed_decided} has
 * no body and takes the calling thread's side instead.</p>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not say WHY a gate answered as it did beyond the reason production names in
 * {@code release}; it does not see the seed packet's arrival, only the decision taken on it; and
 * {@code deck_contact} records nothing for a body that walked onto the deck already grounded — there
 * is no edge to record. Order between the events is measured on a run, never assumed here.</p>
 *
 * <p>Four blind spots worth naming one by one:</p>
 *
 * <ul>
 *   <li>{@code deck_mode_committed} reads the mode from the state AFTER production set it, which is
 *       true at every caller but one: the hull-stand travel path re-captures through
 *       {@code remember} and only THEN restores {@code hullStand}, so a re-capture taken on that
 *       path records {@code "aboard"} for a body that finishes the tick in hull-stand. The mode is
 *       not readable at that instant by anything, production's own log line included.</li>
 *   <li>{@code deck_released} sees what {@code release} removes, and only that. A path that dropped
 *       the capture state without going through {@code release} would end an episode silently here —
 *       today there is none, which is the property this event assumes and cannot itself check.</li>
 *   <li>{@code deck_gate_decided} cannot be used to COUNT gate calls: it records a body's verdict
 *       when that verdict CHANGES and otherwise at most once every five seconds (above), so the
 *       number of records says nothing about how many consumers asked. What it proves is that the
 *       frame was asked about this body at all, and what it last answered — and any window longer
 *       than the heartbeat is guaranteed to contain one record for a body that exists.</li>
 *   <li>{@code deck_seed_decided} has no entity and is routed by the calling thread's side. The
 *       pending-seed pass runs on the side that received the packet, so a decision taken on a
 *       server thread lands in the server log even when the body it concerns is a client one.</li>
 * </ul>
 */
@Mixin(ShipFrameTravel.class)
public abstract class MixinShipFrameTravelEvents {

    private static final String INSTRUMENT = "deck_capture_events";
    private static final String INSTRUMENT_MODE = "deck_mode_events";
    private static final String INSTRUMENT_INTERIOR = "interior_claim_events";
    private static final String INSTRUMENT_SEED = "deck_seed_events";
    private static final String INSTRUMENT_GATE = "deck_gate_events";
    private static final String INSTRUMENT_CONTACT = "deck_contact_events";

    /**
     * {@code onGround} as of each body's previous {@code travel} return. Private static (allowed).
     *
     * <p>IDENTITY keys, and that is not a preference: {@code Entity.equals}/{@code hashCode} are
     * the ENTITY ID, so on an integrated server — every client test — the client's copy of a player
     * and the server's copy are EQUAL and share one slot in a hash map. The two sides then eat each
     * other's edges: the side that returns from {@code travel} second sees the other's
     * {@code grounded} as "before" and stays silent on a landing that really happened. Production's
     * own capture map hit this and answers it the same way ({@code ShipFrameTravel.STATE}, built
     * with {@code MapMaker().weakKeys()}); {@code weakKeys()} switches comparison to {@code ==} and
     * makes the collision unrepresentable. Concurrent too, so the two sides' threads need no
     * external synchronization.</p>
     */
    private static final Map<Entity, Boolean> arTest$groundedAtLastTravel =
            new MapMaker().weakKeys().<Entity, Boolean>makeMap();

    /** How long a body's unchanged gate verdict may go unrecorded. Five seconds: short enough that
     *  any test window worth calling a window contains one record, long enough that a stable body
     *  costs a hundredth of what the per-tick form cost. See {@code arTest$gateDecided}. */
    private static final long ARTEST_GATE_HEARTBEAT_TICKS = 100L;

    /** The last {@code deck_gate_decided} written per body: the world time in the high bits and
     *  {@code handled | tracked} in the low two — see {@code arTest$gateDecided} for why the gate is
     *  deduplicated at all. Identity keys for the same reason as the map above. */
    private static final Map<Entity, Long> arTest$lastGateRecord =
            new MapMaker().weakKeys().<Entity, Long>makeMap();

    @Inject(method = "captureState", at = @At("TAIL"))
    private static void arTest$captured(Entity entity, String shipId, double localX, double localY,
                                        double localZ, double worldX, double worldY, double worldZ,
                                        double carryX, double carryY, double carryZ, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.record(entity, "deck_captured", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId)
                + "\",\"local\":\"" + TestTrace.fmt(localX) + "," + TestTrace.fmt(localY) + ","
                + TestTrace.fmt(localZ) + "\",\"worldY\":" + TestTrace.fmt(worldY)
                + ",\"carry\":\"" + TestTrace.fmt(carryX) + "," + TestTrace.fmt(carryY) + ","
                + TestTrace.fmt(carryZ) + "\"");
    }

    @Inject(method = "release", at = @At("HEAD"))
    private static void arTest$released(Entity entity, String reason, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        if (entity == null || entity.world == null) {
            return;
        }
        // HEAD, before the state is removed: only a tracked body has an episode to end, and `release`
        // no-ops for an untracked one. `isResolving` is production's own containment test — NOT
        // `aboardShipId`, which is null for a hull-stand body and hid every hull-stand release.
        if (!ShipFrameTravel.isResolving(entity)) {
            return;
        }
        TestTrace.record(entity, "deck_released", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"reason\":\""
                + TestTrace.json(reason) + "\",\"mode\":\"" + arTest$mode(entity)
                + "\",\"y\":" + TestTrace.fmt(entity.posY));
    }

    @Inject(method = "logCapture", at = @At("HEAD"))
    private static void arTest$modeCommitted(Entity entity, String shipId, double localX,
                                             double localY, double localZ, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT_MODE);
        if (entity == null || entity.world == null) {
            return;
        }
        // Every mode TRANSITION sets `hullStand` before calling here, so the mode read now is the
        // one just committed — the same read the deck camera and the durable record will make. The
        // one exception is the hull-stand travel path, which re-captures through `remember` and
        // restores `hullStand` after it returns: a re-capture taken there reads "aboard" for a body
        // that stays in hull-stand — production's log line is emitted from the same place and names
        // no mode at all, so nothing else here can tell that case apart either.
        TestTrace.record(entity, "deck_mode_committed", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId) + "\",\"mode\":\"" + arTest$mode(entity)
                + "\",\"local\":\"" + TestTrace.fmt(localX) + "," + TestTrace.fmt(localY) + ","
                + TestTrace.fmt(localZ) + "\"");
    }

    @Inject(method = "interiorCandidate", at = @At("RETURN"))
    private static void arTest$interiorClaimed(EntityLivingBase entity,
                                               CallbackInfoReturnable<String> cir) {
        TestTrace.instrument(entity, INSTRUMENT_INTERIOR);
        if (entity == null || entity.world == null) {
            return;
        }
        // RETURN fires at the null return too; only a claim is an event. And only for a body not
        // already held — the exclusion probe asks this for a flyer every tick.
        String shipId = cir.getReturnValue();
        if (shipId == null || ShipFrameTravel.isResolving(entity)) {
            return;
        }
        TestTrace.record(entity, "interior_claimed", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId) + "\"");
    }

    // The SIX-arg form only: the five-arg overload delegates to it, so a bare "pendingSeedDecision"
    // selector would match both and record one decision twice. RETURN fires at every `return` of the
    // pure function; no locals are read. This is the file's ONE descriptor-qualified selector — the
    // descriptor (six primitives, the nested enum as the return type) is verified against the
    // target's source, but only a run proves it selects; every other seam here is by method name.
    @Inject(method = "pendingSeedDecision(ZIZZZZ)Lzmaster587/advancedRocketry/integration/vs/"
            + "ShipFrameTravel$PendingSeedDecision;", at = @At("RETURN"))
    private static void arTest$seedDecided(boolean excluded, int ticksLeft, boolean captureExists,
                                           boolean captureIsThisSeed, boolean capturePredatesSlot,
                                           boolean restore,
                                           CallbackInfoReturnable<ShipFrameTravel.PendingSeedDecision> cir) {
        TestTrace.instrumentHere(INSTRUMENT_SEED);
        ShipFrameTravel.PendingSeedDecision decision = cir.getReturnValue();
        TestTrace.recordHere("deck_seed_decided", "\"decision\":\""
                + (decision == null ? "" : decision.name()) + "\""
                + ",\"restore\":" + restore
                + ",\"excluded\":" + excluded
                + ",\"ticksLeft\":" + ticksLeft
                + ",\"captureExists\":" + captureExists
                + ",\"captureIsThisSeed\":" + captureIsThisSeed
                + ",\"capturePredatesSlot\":" + capturePredatesSlot);
    }

    @Inject(method = "handles", at = @At("RETURN"))
    private static void arTest$gateDecided(EntityLivingBase entity, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrument(entity, INSTRUMENT_GATE);
        if (!(entity instanceof EntityPlayer) || entity.world == null) {
            return;
        }
        boolean handled = cir.getReturnValueZ();
        // After the gate ran: `tracked` is whether a capture survives (or was installed) this tick.
        boolean tracked = ShipFrameTravel.isResolving(entity);
        // ONE record per body per world tick, plus any answer that CHANGES inside a tick.
        //
        // `handles` is not called once a tick: `travel` asks it (:1687), `jump` asks it again
        // (:2231) and `GravityHandler` asks it a third time (:62), each per body per tick per side.
        //
        // A CHANGE, or a heartbeat — and both halves were paid for in a gate.
        //
        // It deduplicated on the TICK first, so the newest record would be the gate's CURRENT answer
        // rather than its last flip: 3888 records of this one type DROPPED in a single scenario, and
        // every failure message in the suite carrying `deck_gate_decided` as most of its text.
        // Recording only the CHANGE fixed that and broke the one consumer instead: it asks whether
        // the frame was asked about a body AT ALL (a positive precondition for a negative contract,
        // `countRecords(...) > 0`), and a body whose verdict is stable — which is exactly what a
        // walker beside a parked ship is — had its only record written before that test's mark
        // existed. An edge-only recorder cannot answer a question asked through a window that does
        // not contain the edge; the memory of what was last recorded outlives the log it was
        // recorded into.
        //
        // So: on a CHANGE, and otherwise at most once per HEARTBEAT. Any window longer than the
        // heartbeat contains at least one record whatever the body is doing, while a stable body
        // costs one record every five seconds instead of twenty a second — a hundredfold less than
        // the per-tick form that flooded. Stamp and time share one long: time in the high bits,
        // the two verdict bits at the bottom.
        long now = entity.world.getTotalWorldTime();
        long stamp = (handled ? 2L : 0L) | (tracked ? 1L : 0L);
        Long last = arTest$lastGateRecord.get(entity);
        if (last != null && (last & 3L) == stamp && now - (last >>> 2) < ARTEST_GATE_HEARTBEAT_TICKS) {
            return;
        }
        arTest$lastGateRecord.put(entity, (now << 2) | stamp);
        TestTrace.record(entity, "deck_gate_decided", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName())
                + "\",\"handled\":" + handled
                + ",\"tracked\":" + tracked);
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private static void arTest$contact(EntityLivingBase entity, float strafe, float vertical,
                                       float forward, float jumpMovementFactor,
                                       CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrument(entity, INSTRUMENT_CONTACT);
        if (entity == null || entity.world == null) {
            return;
        }
        boolean grounded = entity.onGround;
        Boolean before = arTest$groundedAtLastTravel.put(entity, grounded);
        // An EDGE, not a state: the resolver owned this tick's move AND put the body on a surface it
        // was not on at the previous travel return. A body already grounded when captured has no edge.
        if (!cir.getReturnValueZ() || !grounded || (before != null && before)) {
            return;
        }
        String ship = ShipFrameTravel.aboardShipId(entity);
        TestTrace.record(entity, "deck_contact", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + (ship == null ? "hull" : TestTrace.json(ship))
                + "\",\"y\":" + TestTrace.fmt(entity.posY));
    }

    /** Production's own distinction: a tracked body that {@code aboardShipId} answers null for is
     *  held in HULL-STAND mode (world semantics); any other tracked body is ABOARD. */
    private static String arTest$mode(Entity entity) {
        return ShipFrameTravel.aboardShipId(entity) == null ? "hull" : "aboard";
    }
}
