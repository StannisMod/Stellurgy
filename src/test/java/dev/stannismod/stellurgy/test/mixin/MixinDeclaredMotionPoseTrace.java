package dev.stannismod.stellurgy.test.mixin;

import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.interpolation.DeclaredMotionTransformInterpolator;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;

import net.minecraft.util.math.AxisAlignedBB;

import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * One record per client tick of a craft's shown pose: {@code pose_tick}, with how the tick was shown
 * (from a pose that arrived, or advanced by the declared motion), how many poses arrived since the
 * last tick, the step the shown pose took, and the residual it carries. Read by
 * {@code VSFlightSmoothnessAcrossJumpE2ETest} beside its per-tick pilot channel.
 *
 * <p>The seat a pilot rides is glued to its block through this pose every tick, so a pilot tick that
 * covers no ground and a next one that covers two is this pose standing and then catching up — and
 * whether that happens on the ticks a pose arrived late, or two arrived together, is the whole
 * question. Test source set: absent from a released jar.</p>
 */
@Mixin(value = DeclaredMotionTransformInterpolator.class, remap = false)
public abstract class MixinDeclaredMotionPoseTrace {

    @Shadow
    private boolean shownFromPose;
    @Shadow
    private long shownTick;
    @Shadow
    private long newestPoseTick;
    @Shadow
    private ShipTransform curTickTransform;
    @Shadow
    @Final
    private Vector3d residualPos;
    @Shadow
    @Final
    private Vector3d linearVelocity;

    @Unique
    private int stellurgyTest$arrivals;
    @Unique
    private boolean stellurgyTest$haveShown;
    @Unique
    private double stellurgyTest$shownX, stellurgyTest$shownY, stellurgyTest$shownZ;

    @Inject(method = "onNewTransformPacket(Lorg/valkyrienskies/mod/common/ships/ship_transform/ShipTransform;"
            + "Lnet/minecraft/util/math/AxisAlignedBB;DDDDDDJ)V", at = @At("HEAD"))
    private void stellurgyTest$arrived(ShipTransform newTransform, AxisAlignedBB newAABB, double lx, double ly,
                                       double lz, double ax, double ay, double az, long serverTick,
                                       CallbackInfo ci) {
        stellurgyTest$arrivals++;
        stellurgyTest$arrivalShownTick = shownTick;
        stellurgyTest$arrivalNewest = newestPoseTick;
    }

    @Unique
    private long stellurgyTest$arrivalShownTick, stellurgyTest$arrivalNewest;

    /** One record per arrived pose ({@code pose_arrival}): its stamp against the shown tick and the
     *  newest stamp before it, and the residual it left — which branch it took is read off those. */
    @Inject(method = "onNewTransformPacket(Lorg/valkyrienskies/mod/common/ships/ship_transform/ShipTransform;"
            + "Lnet/minecraft/util/math/AxisAlignedBB;DDDDDDJ)V", at = @At("RETURN"))
    private void stellurgyTest$arrivedReturn(ShipTransform newTransform, AxisAlignedBB newAABB, double lx,
                                             double ly, double lz, double ax, double ay, double az,
                                             long serverTick, CallbackInfo ci) {
        TestTrace.instrumentHere("pose_arrival_events");
        TestTrace.recordHere("pose_arrival", "\"pose\":" + System.identityHashCode(this)
                + ",\"stamp\":" + serverTick
                + ",\"shownTickBefore\":" + stellurgyTest$arrivalShownTick
                + ",\"newestBefore\":" + stellurgyTest$arrivalNewest
                + ",\"posY\":" + newTransform.getPosY()
                + ",\"shownY\":" + curTickTransform.getPosY()
                + ",\"residual\":" + residualPos.length()
                + ",\"vy\":" + ly
                + ",\"nanos\":" + System.nanoTime());
    }

    @Inject(method = "tickTransformInterpolator", at = @At("RETURN"))
    private void stellurgyTest$tickReturn(CallbackInfo ci) {
        TestTrace.instrumentHere("pose_tick_events");
        double x = curTickTransform.getPosX(), y = curTickTransform.getPosY(), z = curTickTransform.getPosZ();
        double dx = stellurgyTest$haveShown ? x - stellurgyTest$shownX : 0.0;
        double dy = stellurgyTest$haveShown ? y - stellurgyTest$shownY : 0.0;
        double dz = stellurgyTest$haveShown ? z - stellurgyTest$shownZ : 0.0;
        TestTrace.recordHere("pose_tick", "\"pose\":" + System.identityHashCode(this)
                + ",\"from\":\"" + (shownFromPose ? "packet" : "extrapolated") + "\""
                + ",\"arrivals\":" + stellurgyTest$arrivals
                + ",\"shownTick\":" + shownTick
                + ",\"newestPoseTick\":" + newestPoseTick
                + ",\"step\":" + Math.sqrt(dx * dx + dy * dy + dz * dz)
                + ",\"stepY\":" + dy
                + ",\"residual\":" + residualPos.length()
                + ",\"declaredVy\":" + linearVelocity.y
                + ",\"nanos\":" + System.nanoTime());
        stellurgyTest$arrivals = 0;
        stellurgyTest$haveShown = true;
        stellurgyTest$shownX = x;
        stellurgyTest$shownY = y;
        stellurgyTest$shownZ = z;
    }
}
