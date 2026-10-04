package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.test.trace.EntityTrace;
import dev.stannismod.stellurgy.test.trace.SideTrace;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.test.trace.TravelPassMemory;

/**
 * The deck frame's two episode edges, in the same vocabulary and on the same instrument as the
 * travel resolver's: {@code deck_entered} and {@code deck_released} on {@code deck_capture_events}.
 *
 * <p>"The deck took this body" and "the deck let it go" are what a scenario asks, whichever mechanism
 * holds it; a reader that had to know which one would be pinning an implementation. So the deck
 * frame answers in the same records, marked {@code "by":"deckFrame"} for a reader that does want to
 * know.</p>
 *
 * <p>An edge is a change of HOLDER: taking over a body the travel resolver already held on the same
 * craft changes nothing a scenario can see and records nothing, exactly as the resolver's own
 * re-install onto its current anchor records nothing. A different craft, or none, is an edge and
 * carries {@code from}.</p>
 */
@Mixin(value = DeckFrameTick.class, remap = false)
public abstract class MixinDeckFrameTickEvents {

    private static final String INSTRUMENT = "deck_capture_events";
    private static final String INSTRUMENT_MODE = "deck_mode_events";

    @Inject(method = "noteEntered", at = @At("HEAD"))
    private static void stellurgyTest$entered(Entity entity, String shipId, String from, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        TestTrace.instrument(entity, INSTRUMENT_MODE);
        if (entity == null || entity.world == null) {
            return;
        }
        // The deck frame holds a body in one mode only - on the deck - so every opening commits that
        // mode, the same craft included: taking over a body the resolver held on this craft's OUTER
        // hull is a change of mode a scenario can see.
        TestTrace.record(entity, "deck_mode_committed", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId) + "\",\"mode\":\"aboard\",\"by\":\"deckFrame\"");
        if (shipId != null && shipId.equals(from)) {
            return;
        }
        TestTrace.record(entity, "deck_entered", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId)
                + "\",\"from\":" + (from == null ? "null" : "\"" + TestTrace.json(from) + "\"")
                + ",\"worldY\":" + TestTrace.fmt(entity.posY) + ",\"by\":\"deckFrame\"");
    }

    /**
     * Every tick the deck frame holds a body: the travel resolver's two per-tick records, in its
     * vocabulary, so a reader of "the deck carried this body" or "it came down on this deck" need not
     * know which mechanism held it. {@code deck_carry} on every held tick, with the deck's own velocity
     * at the body's point; {@code deck_contact} on the EDGE only - grounded now, not at the previous
     * held tick - and through the same per-body memory the resolver's edge uses, so a hand-over between
     * the two is not read as a second landing.
     */
    @Inject(method = "noteHeldTick", at = @At("HEAD"))
    private static void stellurgyTest$heldTick(Entity entity, String shipId, double localX, double localY,
                                               double localZ, double carryX, double carryY, double carryZ,
                                               boolean grounded, CallbackInfo ci) {
        TestTrace.instrument(entity, "deck_carry_events");
        TestTrace.instrument(entity, "deck_contact_events");
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.record(entity, "deck_carry", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId)
                + "\",\"carry\":\"" + TestTrace.fmt(carryX) + "," + TestTrace.fmt(carryY) + ","
                + TestTrace.fmt(carryZ)
                + "\",\"local\":\"" + TestTrace.fmt(localX) + "," + TestTrace.fmt(localY) + ","
                + TestTrace.fmt(localZ) + "\",\"by\":\"deckFrame\"");
        dev.stannismod.stellurgy.test.trace.EntityTrace.DeckGate memory = dev.stannismod.stellurgy.test.trace
                .EntityTrace.memory(entity, dev.stannismod.stellurgy.test.trace.EntityTrace.DeckGate.class,
                        dev.stannismod.stellurgy.test.trace.EntityTrace.DeckGate::new);
        Boolean before = memory.groundedAtLastTravel;
        memory.groundedAtLastTravel = grounded;
        if (!grounded || (before != null && before)) {
            return;
        }
        TestTrace.record(entity, "deck_contact", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"ship\":\""
                + TestTrace.json(shipId) + "\",\"y\":" + TestTrace.fmt(entity.posY) + ",\"by\":\"deckFrame\"");
    }

    /** Where the body was in the world when the deck frame took up its update — read back below as
     *  the point it was FOUND at, which the travel resolver's per-tick record carries. */
    @Inject(method = "runInDeckFrame", at = @At("HEAD"))
    private static void stellurgyTest$updateStart(Entity entity, Runnable ownUpdate,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (entity == null) {
            return;
        }
        EntityTrace.DeckFrameUpdate start =
                EntityTrace.memory(entity, EntityTrace.DeckFrameUpdate.class, EntityTrace.DeckFrameUpdate::new);
        start.startX = entity.posX;
        start.startY = entity.posY;
        start.startZ = entity.posZ;
    }

    /**
     * The travel resolver's per-tick record ({@code ship_frame_tick}), written for the player this
     * client plays when the DECK FRAME resolved his tick — {@code path:"d"}, {@code "by":"deckFrame"},
     * on the same per-side tick counter, so a reader windowing on {@code resolvedTick} sees one clock
     * across a hand-over. The fields are the ones this mechanism can answer honestly: where he was
     * FOUND (his world position at the update's start, through the craft's pose), the deck point the
     * update left him at ({@code held*}), the walk input the update ran on, and whether it ended on
     * something. The resolver's sweep fields (obstacles, per-axis clips, motion) are not written: the
     * deck frame does not sweep, and an absent field reads as absent rather than as a zero.
     *
     * <p>The client player only: on the server the deck frame holds mobs and items too, and a test
     * reading the resolver's records for a mob as "the resolver stood down" must keep reading
     * silence.</p>
     */
    @Inject(method = "noteHeldTick", at = @At("HEAD"))
    private static void stellurgyTest$heldTickLine(Entity entity, String shipId, double localX, double localY,
                                                   double localZ, double carryX, double carryY, double carryZ,
                                                   boolean grounded, CallbackInfo ci) {
        if (!(entity instanceof EntityPlayer) || entity.world == null || !entity.world.isRemote) {
            return;
        }
        TestTrace.instrument(entity, "ship_frame_tick_events");
        EntityTrace.DeckFrameUpdate start =
                EntityTrace.memory(entity, EntityTrace.DeckFrameUpdate.class, EntityTrace.DeckFrameUpdate::new);
        double[] found = VSIntegration.toShipFrameFor(entity.world, shipId, start.startX, start.startY, start.startZ);
        long ticks = ++TravelPassMemory.of(SideTrace.here()).resolvedTicks;
        EntityLivingBase body = (EntityLivingBase) entity;
        TestTrace.record(entity, "ship_frame_tick",
                "\"e\":" + entity.getEntityId()
                        + ",\"resolvedTick\":" + ticks
                        + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\""
                        + ",\"path\":\"d\""
                        + ",\"onDeck\":" + grounded
                        + (found == null ? ""
                                : ",\"bodyLocalX\":" + found[0] + ",\"bodyLocalY\":" + found[1]
                                        + ",\"bodyLocalZ\":" + found[2])
                        + ",\"heldX\":" + localX + ",\"heldY\":" + localY + ",\"heldZ\":" + localZ
                        + ",\"carryX\":" + carryX + ",\"carryY\":" + carryY + ",\"carryZ\":" + carryZ
                        + ",\"inStrafe\":" + body.moveStrafing + ",\"inForward\":" + body.moveForward
                        + ",\"by\":\"deckFrame\"");
    }

    /**
     * Render-vs-collision pose skew for a body the deck frame holds, in the travel resolver's record
     * ({@code render_pose_skew}, mode {@code aboard}): the deck point the held update left it at, and
     * the world position the deck frame has just mapped that point to through the GAME-TICK pose,
     * against the same point through the client's interpolated RENDER pose. Without it a scenario
     * asking how far the drawn deck is from the deck stood on was blind for every body the resolver no
     * longer holds. Client only, as the resolver's is: a dedicated server has no render pose.
     */
    @Inject(method = "noteHeldTick", at = @At("HEAD"))
    private static void stellurgyTest$heldPoseSkew(Entity entity, String shipId, double localX, double localY,
                                                   double localZ, double carryX, double carryY, double carryZ,
                                                   boolean grounded, CallbackInfo ci) {
        TestTrace.instrument(entity, "render_pose_skew_events");
        if (entity == null || entity.world == null || !entity.world.isRemote) {
            return;
        }
        double[] drawn = dev.stannismod.stellurgy.integration.vs.VSIntegration.renderToWorldFrameFor(
                entity.world, shipId, localX, localY, localZ);
        String skew = "";
        if (drawn != null) {
            double dx = entity.posX - drawn[0];
            double dy = entity.posY - drawn[1];
            double dz = entity.posZ - drawn[2];
            skew = ",\"skew\":" + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        TestTrace.record(entity, "render_pose_skew",
                "\"e\":" + entity.getEntityId()
                        + ",\"ship\":\"" + TestTrace.json(shipId) + "\""
                        + ",\"mode\":\"aboard\""
                        + ",\"drawn\":" + (drawn != null)
                        + skew
                        + ",\"subX\":" + localX + ",\"subY\":" + localY + ",\"subZ\":" + localZ
                        + ",\"commitX\":" + entity.posX
                        + ",\"commitY\":" + entity.posY
                        + ",\"commitZ\":" + entity.posZ
                        + ",\"by\":\"deckFrame\"");
    }

    @Inject(method = "noteReleased", at = @At("HEAD"))
    private static void stellurgyTest$released(Entity entity, String reason, CallbackInfo ci) {
        TestTrace.instrument(entity, INSTRUMENT);
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.record(entity, "deck_released", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\",\"reason\":\""
                + TestTrace.json(reason) + "\",\"mode\":\"deckFrame\",\"y\":" + TestTrace.fmt(entity.posY)
                + ",\"by\":\"deckFrame\"");
    }
}
