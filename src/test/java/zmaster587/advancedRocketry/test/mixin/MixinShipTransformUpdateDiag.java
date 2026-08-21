package zmaster587.advancedRocketry.test.mixin;

import javax.annotation.Nonnull;

import net.minecraft.util.math.AxisAlignedBB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.interpolation.SimpleEMATransformInterpolator;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;

import zmaster587.advancedRocketry.command.test.MotionTrace;

/**
 * Counts the pose packets a client actually receives for a ship.
 *
 * <h2>Why this seam and no other</h2>
 *
 * <p>A new transform packet is the ONLY way a client learns where a ship is; the pose the pilot sees
 * is a filter chasing it. So "the ship moved unevenly" and "its pose ARRIVED unevenly" are different
 * claims, and only a count taken at the arrival separates them — a smoothness test that cannot do
 * that is measuring the network and calling it the physics.</p>
 *
 * <h2>Why the interpolator and not the packet handler</h2>
 *
 * <p>The handler applies the packet from inside an anonymous {@code Runnable} it hands to the client
 * thread, so its code lives in {@code ShipTransformUpdateMessageHandler$1} and a mixin on the handler
 * would match nothing — silently, which is the failure mode this whole line of work exists to remove.
 * The interpolator's method is a named seam on a named class and receives exactly one call per
 * arriving pose.</p>
 *
 * <p>Until 2026-08-21 this was a line of AR's diagnostics living inside the vendored Valkyrien Skies
 * handler — in a shipped game, on the client's packet path, for a reader that only exists in a test.
 * The vendored tree is ours to edit, which is why the line could be removed rather than worked
 * around.</p>
 */
@Mixin(value = SimpleEMATransformInterpolator.class, remap = false)
public abstract class MixinShipTransformUpdateDiag {

    @Inject(method = "onNewTransformPacket", at = @At("HEAD"))
    private void arTest$countTransformArrival(@Nonnull ShipTransform newTransform,
                                              @Nonnull AxisAlignedBB newAABB, CallbackInfo ci) {
        MotionTrace.clientShipTransformUpdates++;
    }
}
