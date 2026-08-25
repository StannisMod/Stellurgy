package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.util.math.AxisAlignedBB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;

import zmaster587.advancedRocketry.test.trace.DeckPoseTraceState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A pose ARRIVING for a craft on the client — one record per packet, paired with the per-tick record
 * from {@link MixinClientDeckPoseTickTrace} by tick number.
 *
 * <h2>The question the pair exists to answer</h2>
 *
 * <p>Whatever drives the client's pose between packets — a filter chasing the newest one, a
 * prediction advancing from a declared velocity — its behaviour only differs where a pose does NOT
 * arrive for a tick. Those ticks are rare (12 in 298 on a loaded client) and everything downstream
 * reports averages, so three separate mechanisms were tried against whole-scenario verdicts and each
 * traded one regression for another without anyone seeing a gap boundary.</p>
 *
 * <p>This says, tick by tick: did a pose arrive, what did it say, what was shown, how far the shown
 * pose stepped, and what the capture guard made of that step. A boundary is then read rather than
 * inferred.</p>
 *
 * <p>Targets both pose sources so the same trace works whichever is wired — they share the method,
 * not their fields, so nothing here is shadowed. Test source set: absent from a released jar.</p>
 */
@Mixin(targets = {
        "org.valkyrienskies.mod.common.ships.interpolation.SimpleEMATransformInterpolator",
        "org.valkyrienskies.mod.common.ships.interpolation.DeclaredMotionTransformInterpolator"
}, remap = false)
public abstract class MixinClientDeckPoseTrace {


    @Inject(method = "onNewTransformPacket(Lorg/valkyrienskies/mod/common/ships/ship_transform/ShipTransform;"
            + "Lnet/minecraft/util/math/AxisAlignedBB;DDDDDD)V", at = @At("HEAD"), remap = false)
    private void arTest$notePoseArrival(ShipTransform newTransform, AxisAlignedBB newAABB,
                                        double linearX, double linearY, double linearZ,
                                        double angularX, double angularY, double angularZ,
                                        CallbackInfo ci) {
        TestTrace.instrumentHere("client_deck_pose");
        // Keyed on THIS interpolator: one message carries every craft a player watches, so a flag
        // shared between them would let one craft's pose answer for another's silence.
        final org.joml.Quaterniondc q = newTransform.rotationQuaternion(
                valkyrienwarfare.api.TransformType.SUBSPACE_TO_GLOBAL);
        final double omega = Math.sqrt(angularX * angularX + angularY * angularY + angularZ * angularZ);
        DeckPoseTraceState.noteArrival(this, newTransform.getPosY(), linearY,
                q.w(), q.x(), q.y(), q.z(), omega);
    }
}
