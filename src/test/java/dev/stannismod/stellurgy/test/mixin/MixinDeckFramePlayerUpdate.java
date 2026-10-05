package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.test.trace.EntityTrace;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * One record per server tick in which a deck ran a player's update in its frame:
 * {@code deck_player_update}, with what the update was handed and what it left. Read by
 * {@code VSCrewRidesRollingDeckE2ETest} beside its ground claims.
 *
 * <p>The server's {@code onGround} for a held player has two writers — his client's claim, in the
 * movement packet, and this update's own move against the deck's blocks — and a single read of it
 * cannot say which one it saw. The deck frame's per-tick record ({@code deck_carry}) is written from
 * the update of a body the server moves itself and never for a player, so the replay of HIS update
 * was the one held tick with no record at all.</p>
 *
 * <p>Fields are deck-frame: {@code in*} the deck point his claim mapped to and the velocity rotated
 * in, {@code out*} where his update left him and with what velocity; {@code groundIn} is whatever
 * wrote {@code onGround} last before the update (a packet's claim or the previous update),
 * {@code groundOut} the update's own verdict. Test source set: absent from a released jar.</p>
 */
@Mixin(value = DeckFrameTick.class, remap = false)
public abstract class MixinDeckFramePlayerUpdate {

    private static final String INSTRUMENT = "deck_player_update_events";

    /** A held player's movement packet replayed against the deck ({@code moveHeld}): its world
     *  velocity at the head, then around the {@code move} itself, recorded as {@code deck_player_step}. */
    @Inject(method = "moveHeld", require = 1, at = @At("HEAD"))
    private static void stellurgyTest$stepHead(Entity entity, MoverType type, double dx, double dy, double dz,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof EntityPlayerMP)) {
            return;
        }
        EntityTrace.DeckPlayerUpdate s =
                EntityTrace.memory(entity, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        s.stepHeadMotionX = entity.motionX;
        s.stepHeadMotionY = entity.motionY;
        s.stepHeadMotionZ = entity.motionZ;
        s.stepMoved = false;
    }

    @Inject(method = "moveHeld", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;move(Lnet/minecraft/entity/MoverType;DDD)V"))
    private static void stellurgyTest$stepPre(Entity entity, MoverType type, double dx, double dy, double dz,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof EntityPlayerMP)) {
            return;
        }
        EntityTrace.DeckPlayerUpdate s =
                EntityTrace.memory(entity, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        s.stepPreMotionX = entity.motionX;
        s.stepPreMotionY = entity.motionY;
        s.stepPreMotionZ = entity.motionZ;
        s.stepPreX = entity.posX;
        s.stepPreY = entity.posY;
        s.stepPreZ = entity.posZ;
    }

    @Inject(method = "moveHeld", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;move(Lnet/minecraft/entity/MoverType;DDD)V",
            shift = At.Shift.AFTER))
    private static void stellurgyTest$stepPost(Entity entity, MoverType type, double dx, double dy, double dz,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof EntityPlayerMP)) {
            return;
        }
        EntityTrace.DeckPlayerUpdate s =
                EntityTrace.memory(entity, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        s.stepPostMotionX = entity.motionX;
        s.stepPostMotionY = entity.motionY;
        s.stepPostMotionZ = entity.motionZ;
        s.stepPostX = entity.posX;
        s.stepPostY = entity.posY;
        s.stepPostZ = entity.posZ;
        s.stepMoved = true;
    }

    @Inject(method = "moveHeld", require = 1, at = @At("RETURN"))
    private static void stellurgyTest$stepReturn(Entity entity, MoverType type, double dx, double dy, double dz,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof EntityPlayerMP)) {
            return;
        }
        TestTrace.instrument(entity, "deck_player_step_events");
        EntityTrace.DeckPlayerUpdate s =
                EntityTrace.memory(entity, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        TestTrace.record(entity, "deck_player_step", "\"e\":" + entity.getEntityId()
                + ",\"handled\":" + cir.getReturnValue()
                + ",\"moved\":" + s.stepMoved
                + ",\"worldStepX\":" + dx + ",\"worldStepY\":" + dy + ",\"worldStepZ\":" + dz
                + ",\"headMotionX\":" + s.stepHeadMotionX + ",\"headMotionY\":" + s.stepHeadMotionY
                + ",\"headMotionZ\":" + s.stepHeadMotionZ
                + ",\"preMotionX\":" + s.stepPreMotionX + ",\"preMotionY\":" + s.stepPreMotionY
                + ",\"preMotionZ\":" + s.stepPreMotionZ
                + ",\"postMotionX\":" + s.stepPostMotionX + ",\"postMotionY\":" + s.stepPostMotionY
                + ",\"postMotionZ\":" + s.stepPostMotionZ
                + ",\"deckStepX\":" + (s.stepPostX - s.stepPreX) + ",\"deckStepY\":" + (s.stepPostY - s.stepPreY)
                + ",\"deckStepZ\":" + (s.stepPostZ - s.stepPreZ)
                + ",\"returnMotionX\":" + entity.motionX + ",\"returnMotionY\":" + entity.motionY
                + ",\"returnMotionZ\":" + entity.motionZ
                + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    @Inject(method = "updatePlayer", require = 1, at = @At("HEAD"))
    private static void stellurgyTest$updateHead(EntityPlayerMP player, CallbackInfoReturnable<Boolean> cir) {
        EntityTrace.DeckPlayerUpdate in =
                EntityTrace.memory(player, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        in.worldMotionX = player.motionX;
        in.worldMotionY = player.motionY;
        in.worldMotionZ = player.motionZ;
    }

    @Inject(method = "updatePlayer", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;onUpdateEntity()V"))
    private static void stellurgyTest$updateIn(EntityPlayerMP player, CallbackInfoReturnable<Boolean> cir) {
        EntityTrace.DeckPlayerUpdate in =
                EntityTrace.memory(player, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        in.inX = player.posX;
        in.inY = player.posY;
        in.inZ = player.posZ;
        in.inMotionX = player.motionX;
        in.inMotionY = player.motionY;
        in.inMotionZ = player.motionZ;
        in.groundIn = player.onGround;
    }

    @Inject(method = "updatePlayer", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;onUpdateEntity()V", shift = At.Shift.AFTER))
    private static void stellurgyTest$updateOut(EntityPlayerMP player, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrument(player, INSTRUMENT);
        EntityTrace.DeckPlayerUpdate in =
                EntityTrace.memory(player, EntityTrace.DeckPlayerUpdate.class, EntityTrace.DeckPlayerUpdate::new);
        TestTrace.record(player, "deck_player_update", "\"e\":" + player.getEntityId()
                + ",\"ship\":\"" + TestTrace.json(DeckFrameTick.heldShipId(player)) + "\""
                + ",\"worldMotionX\":" + in.worldMotionX + ",\"worldMotionY\":" + in.worldMotionY
                + ",\"worldMotionZ\":" + in.worldMotionZ
                + ",\"inX\":" + in.inX + ",\"inY\":" + in.inY + ",\"inZ\":" + in.inZ
                + ",\"inMotionX\":" + in.inMotionX + ",\"inMotionY\":" + in.inMotionY
                + ",\"inMotionZ\":" + in.inMotionZ
                + ",\"groundIn\":" + in.groundIn
                + ",\"outX\":" + player.posX + ",\"outY\":" + player.posY + ",\"outZ\":" + player.posZ
                + ",\"outMotionX\":" + player.motionX + ",\"outMotionY\":" + player.motionY
                + ",\"outMotionZ\":" + player.motionZ
                + ",\"groundOut\":" + player.onGround
                + ",\"collidedV\":" + player.collidedVertically);
    }
}
