package zmaster587.advancedRocketry.test.mixin;

import javax.annotation.Nonnull;

import net.minecraft.util.math.AxisAlignedBB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.interpolation.DeclaredMotionTransformInterpolator;
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
 *
 * <h2>IT COUNTED A CLASS NOBODY INSTALLS, and said nothing about it</h2>
 *
 * <p>This targeted {@code SimpleEMATransformInterpolator} until 2026-09-21. On 2026-08-25
 * {@code PhysicsObject} was changed to install {@link DeclaredMotionTransformInterpolator} on the
 * client instead — the EMA filter is "still present and still working", so the mixin kept applying
 * cleanly to a class that is never constructed, and the counter stayed at 0 for ever.</p>
 *
 * <p><b>Zero is exactly the reading this counter exists to make impossible.</b> Its whole job is to
 * separate "the pose ARRIVED unevenly" from "the pose was APPLIED unevenly", and a permanent zero
 * answers the first question with "no poses at all" — which is indistinguishable, at the call site,
 * from a client that genuinely received none. Found while chasing a pilot's view that freezes for
 * a tick and then covers two ticks of ground, where that separation was the next question and
 * could not be asked. A seam chosen by NAME needs the name checked against what the game
 * constructs, not against what the package contains.</p>
 */
@Mixin(value = DeclaredMotionTransformInterpolator.class, remap = false)
public abstract class MixinShipTransformUpdateDiag {

    @Inject(method = "onNewTransformPacket(Lorg/valkyrienskies/mod/common/ships/ship_transform/"
            + "ShipTransform;Lnet/minecraft/util/math/AxisAlignedBB;)V", at = @At("HEAD"))
    private void arTest$countTransformArrival(@Nonnull ShipTransform newTransform,
                                              @Nonnull AxisAlignedBB newAABB, CallbackInfo ci) {
        MotionTrace.clientShipTransformUpdates++;
    }
}
