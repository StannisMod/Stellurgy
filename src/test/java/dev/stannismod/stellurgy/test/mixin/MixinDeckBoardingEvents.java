package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.integration.vs.DeckBoarding;
import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The questions around a deck's hold, as records, on whichever side they are answered.
 *
 * <ul>
 *   <li>{@code interior_claimed} — RETURN of {@code interiorCandidate}: the ship whose ENCLOSED
 *       interior contains a body no deck holds (deck below, roof above, in that ship's own frame).
 *       Recorded only for a non-null answer; one tick may ask several times (the deck frame's
 *       admission and the creative-flight exclusion), so it may record several times before the
 *       deck takes the body — after which the "no deck holds it" filter silences it.</li>
 *   <li>{@code deck_seed_decided} — RETURN of {@code pendingSeedDecision}: the pure verdict of the
 *       pending-seed state machine, with every fact it was given. Pure and entity-less, so it is routed by the calling thread's side
 *       ({@code recordHere}); on the client that is the pending-seed tick.</li>
 *   <li>{@code subspace_census} — HEAD of {@code clientCensusTick}: the census TAKEN here, at the
 *       client tick production offers it, for a body a deck holds.</li>
 *   <li>{@code ship_frame_seed} — HEAD of {@code noteSeedOutcome}: a seed waiting for its ship, or how
 *       it ended (applied, refused, expired, voided by a teleport or a mount, already seeded, or kept
 *       out by an older hold).</li>
 * </ul>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not see the seed packet's arrival, only the decisions taken on it; and the census says
 * nothing about a body no deck holds — production's census has no ship to measure in for one, and the
 * absence is not a record.</p>
 *
 * <p>A mixin from the tests into the product; test source set, absent from a released jar.</p>
 */
@Mixin(value = DeckBoarding.class, remap = false)
public abstract class MixinDeckBoardingEvents {

    private static final String INSTRUMENT_INTERIOR = "interior_claim_events";
    private static final String INSTRUMENT_SEED = "deck_seed_events";

    @Inject(method = "interiorCandidate", at = @At("RETURN"))
    private static void stellurgyTest$interiorClaimed(EntityLivingBase entity,
                                                      CallbackInfoReturnable<String> cir) {
        TestTrace.instrument(entity, INSTRUMENT_INTERIOR);
        if (entity == null || entity.world == null) {
            return;
        }
        // RETURN fires at the null return too; only a claim is an event. And only for a body no deck
        // holds yet — the flight exclusion asks this for a flyer every tick.
        String shipId = cir.getReturnValue();
        if (shipId == null || DeckFrameTick.holds(entity)) {
            return;
        }
        TestTrace.record(entity, "interior_claimed", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId) + "\"");
    }

    @Inject(method = "pendingSeedDecision(ZIZZZZZ)Ldev/stannismod/stellurgy/integration/vs/"
            + "DeckBoarding$PendingSeedDecision;", at = @At("RETURN"), require = 1)
    private static void stellurgyTest$seedDecided(boolean excluded, int ticksLeft, boolean captureExists,
                                                  boolean captureIsThisSeed, boolean capturePredatesSlot,
                                                  boolean restore, boolean voided,
                                                  CallbackInfoReturnable<DeckBoarding.PendingSeedDecision> cir) {
        TestTrace.instrumentHere(INSTRUMENT_SEED);
        DeckBoarding.PendingSeedDecision decision = cir.getReturnValue();
        TestTrace.recordHere("deck_seed_decided", "\"decision\":\""
                + (decision == null ? "" : decision.name()) + "\""
                + ",\"restore\":" + restore
                + ",\"voided\":" + voided
                + ",\"excluded\":" + excluded
                + ",\"ticksLeft\":" + ticksLeft
                + ",\"captureExists\":" + captureExists
                + ",\"captureIsThisSeed\":" + captureIsThisSeed
                + ",\"capturePredatesSlot\":" + capturePredatesSlot);
    }

    /**
     * The subspace census, TAKEN here rather than read from production.
     *
     * <p>Does this side's world actually hold the ship's blocks at the subspace coordinates every deck
     * probe and interior gate reads? A side whose world never received those chunks answers every one
     * of those reads with "air", and the mechanics degrade silently. The census separates that
     * world-content failure ({@code chunkLoaded:false} / {@code nonAir:0}) from a probe defect
     * (blocks present, collision boxes still not found).</p>
     *
     * <p>One record per sample, so a reader takes the LATEST rather than a value every scenario in a
     * shared client wrote over. Its own 256-deep ring turns over in about thirteen seconds, which is
     * far longer than the "what does the client hold right now" question needs.</p>
     */
    @Inject(method = "clientCensusTick", at = @At("HEAD"))
    private static void stellurgyTest$census(EntityLivingBase entity, CallbackInfo ci) {
        if (entity == null || entity.world == null || !entity.world.isRemote) {
            return;
        }
        TestTrace.instrument(entity, "subspace_census_events");
        java.util.Map<String, Object> m = DeckBoarding.subspaceCensusFor(entity);
        if (m == null) {
            return; // no deck holds this body on this side — not a reading, an absence
        }
        // The body's point on the census ship, UNFLOORED. `subPos` is the block the census scans
        // around, so a distance read from two of them is quantised to a whole block. Fixed decimals,
        // not TestTrace.fmt: a subspace X of 19 200 001 keeps no fraction at six significant figures.
        double[] exact = VSIntegration.toShipFrameFor(entity.world, String.valueOf(m.get("shipId")),
                entity.posX, entity.posY, entity.posZ);
        String subExact = exact == null ? ""
                : String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f", exact[0], exact[1], exact[2]);
        TestTrace.record(entity, "subspace_census",
                "\"e\":" + entity.getEntityId()
                        + ",\"ship\":\"" + TestTrace.json(String.valueOf(m.get("shipId"))) + "\""
                        + ",\"tracked\":" + Boolean.TRUE.equals(m.get("tracked"))
                        + ",\"heldBy\":\"" + TestTrace.json(String.valueOf(m.get("heldBy"))) + "\""
                        + ",\"subPos\":\"" + TestTrace.json(String.valueOf(m.get("subPos"))) + "\""
                        + ",\"subExact\":\"" + subExact + "\""
                        // The client's own flight flag, which vanilla also clears by itself the tick
                        // a flyer touches ground (EntityPlayerSP#onLivingUpdate) - so a scenario that
                        // toggles flight by key can read whether it is still on before it does.
                        + ",\"flying\":" + (entity instanceof net.minecraft.entity.player.EntityPlayer
                                && ((net.minecraft.entity.player.EntityPlayer) entity).capabilities.isFlying)
                        + ",\"chunkLoaded\":" + Boolean.TRUE.equals(m.get("chunkLoaded"))
                        + ",\"nonAir\":" + m.get("nonAir")
                        + ",\"collisionBoxes\":" + m.get("collisionBoxes")
                        + ",\"region\":\"" + TestTrace.json(String.valueOf(m.get("region"))) + "\""
                        + ",\"regionNonAir\":" + m.get("regionNonAir"));
    }

    /**
     * How a seed attempt ended, for the body it was for: a dismount whose seed never lands hands the
     * body to vanilla's world-frame dismount spot, which on a non-upright ship maps off the deck — so
     * the outcome is the fact, and WHICH body it was for is what makes it this scenario's.
     */
    @Inject(method = "noteSeedOutcome", at = @At("HEAD"))
    private static void stellurgyTest$seedOutcome(Entity entity, String shipId, String outcome,
                                                  CallbackInfo ci) {
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.instrument(entity, "ship_frame_seed_events");
        TestTrace.record(entity, "ship_frame_seed",
                "\"e\":" + entity.getEntityId()
                        + ",\"ship\":\"" + TestTrace.json(String.valueOf(shipId)) + "\""
                        + ",\"outcome\":\"" + TestTrace.json(String.valueOf(outcome)) + "\"");
    }
}
