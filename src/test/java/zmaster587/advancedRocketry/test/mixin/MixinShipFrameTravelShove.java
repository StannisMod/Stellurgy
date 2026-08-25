package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Reproduces the DRIVER behind a body that declares a position no input could produce: AR's own
 * client-side travel committing a step it should not have.
 *
 * <h2>Why the driver and not the condition</h2>
 *
 * <p>A test cannot stage this from outside. While AR holds a deck capture it writes the body's
 * position from the deck point every tick, so a body shoved by anything else is put back before its
 * client sends anything — measured: a forty-block client-side shove produced a movement packet
 * identical to standing still. The only thing that can make a captured body declare an impossible
 * position is the code that owns its position, which is exactly what happened in play: a client
 * derived a wrong deck velocity and climbed thirty blocks a tick on it, and the server accepted
 * every one of them.</p>
 *
 * <p>So this adds a one-shot step to the committed position, on the client, from inside the same
 * method the real defect came out of. The arithmetic that produced the wrong number is fixed; the
 * server's refusal to ratify one is a separate promise, and it needs a subject to refuse.</p>
 *
 * <p>Armed through the harness's static-invoke bridge, one shove per arming, so a test can say
 * exactly when it happens. Test source set: absent from a released jar.</p>
 */
@Mixin(targets = "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel", remap = false)
public abstract class MixinShipFrameTravelShove {

    /** Blocks to add to the next committed position, once. Zero when not armed. */
    private static int arTest$pendingShoveBlocks;

    /** Arm one shove of {@code blocks} on the next travel commit. Returns what it was armed with,
     *  so a caller can tell an arming that reached the client from one that did not.
     *
     *  <p>PRIVATE because a mixin may not carry a non-private static — it is merged into the target
     *  and called there, by name, through the harness's static-invoke bridge (which reflects with
     *  {@code setAccessible}). So a caller names {@code ShipFrameTravel}, not this class: this class
     *  does not exist at runtime.</p> */
    private static int arTest$armShove(int blocks) {
        arTest$pendingShoveBlocks = blocks;
        return blocks;
    }

    @Inject(method = "travel", at = @At("RETURN"), remap = false)
    private static void arTest$shoveAfterTravel(EntityLivingBase entity, float strafe, float vertical,
                                                float forward, float jumpMovementFactor,
                                                CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere("ship_frame_travel_shove");
        if (arTest$pendingShoveBlocks == 0 || entity == null || entity.world == null
                || !entity.world.isRemote || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        final int blocks = arTest$pendingShoveBlocks;
        arTest$pendingShoveBlocks = 0;
        entity.setPosition(entity.posX, entity.posY + blocks, entity.posZ);
        // The MOTION as well as the position, because that is what the defect looked like and what
        // gets past everything upstream. Vanilla's own speed check does not compare a step against
        // a constant — it compares it against what the player's OWN declared motion would explain,
        // so a position that moves without a velocity to account for it is refused before AR's bound
        // is ever consulted (measured: a step of 40 with no motion produced a packet the server saw
        // as 0.125 blocks). A body climbing on a wrong carry HAS the velocity, which is exactly why
        // the server ratified thirty blocks a tick of it.
        entity.motionY = blocks;
        TestTrace.recordHere("ship_frame_travel_shove",
                "\"blocks\":" + blocks + ",\"toY\":" + TestTrace.fmt(entity.posY)
                        + ",\"motionY\":" + TestTrace.fmt(entity.motionY));
    }
}
