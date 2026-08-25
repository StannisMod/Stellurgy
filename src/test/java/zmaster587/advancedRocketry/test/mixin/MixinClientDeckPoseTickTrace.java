package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import zmaster587.advancedRocketry.test.trace.DeckPoseTraceState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * One record per client tick per craft: what the client's pose did, and what the capture guard made
 * of it.
 *
 * <p>The other half of the pair described in {@link MixinClientDeckPoseTrace}. Read together they
 * show a packet-gap boundary directly — the tick a pose did not arrive, and the tick after it —
 * instead of leaving it to be inferred from a scenario's verdict.</p>
 *
 * <p>Fields, and why each is here rather than derived later:</p>
 * <ul>
 *   <li>{@code arrived} — whether a pose came for THIS tick. The whole distinction rests on it.</li>
 *   <li>{@code arrivedY} / {@code arrivedVelY} — what the craft said, so the shown pose can be
 *       compared against the truth rather than against its own past.</li>
 *   <li>{@code shownY} and {@code stepY} — where the client put the deck and how far it moved it.</li>
 *   <li>{@code guardStep} / {@code guardAllowed} / {@code guardCarry} — what the body-capture guard
 *       measured in the same tick. A step and the allowance it was judged against, side by side, is
 *       the comparison whose absence made three tuning attempts guesswork.</li>
 * </ul>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
@Mixin(value = PhysicsObject.class, remap = false)
public abstract class MixinClientDeckPoseTickTrace {

    /** The shown pose of the previous tick, per craft: {@code [y, qw, qx, qy, qz]}.
     *
     *  <p>The orientation is here because the fault this trace was extended for is an ANGLE. The
     *  first version recorded the vertical only — and a rotational lurch is invisible in a column of
     *  Y values, which is how two green classes were nearly read as readiness to ship.</p> */
    private static final java.util.Map<Object, double[]> arTest$prevShown =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, double[]>());

    /** Whether to record at all — a 200-tick window per craft would otherwise fill the log on every
     *  scenario that has a ship in it. Armed by a test through the harness's static-invoke bridge. */
    private static int arTest$traceTicksLeft;

    private static int arTest$armPoseTrace(int ticks) {
        arTest$traceTicksLeft = ticks;
        return ticks;
    }


    /** The angle between two orientations, radians — the shortest arc, so a quaternion and its
     *  negation read as the same attitude rather than as half a turn apart. */
    private static double angleBetween(double aw, double ax, double ay, double az,
                                       double bw, double bx, double by, double bz) {
        double dot = Math.abs(aw * bw + ax * bx + ay * by + az * bz);
        return 2.0 * Math.acos(Math.min(1.0, dot));
    }
    @Inject(method = "onTick", at = @At("RETURN"), remap = false)
    private void arTest$recordPoseTick(CallbackInfo ci) {
        final PhysicsObject self = (PhysicsObject) (Object) this;
        if (self.getWorld() == null || !self.getWorld().isRemote) {
            return;
        }
        TestTrace.instrumentHere("client_deck_pose_tick");
        // Ask for THIS craft's arrival and clear it in the same step — the interpolator is the key
        // the pose handler recorded under.
        final double[] arrival = DeckPoseTraceState.takeArrival(self.getTransformInterpolator());
        final boolean arrived = arrival[0] > 0d;
        if (arTest$traceTicksLeft <= 0) {
            return;
        }
        arTest$traceTicksLeft--;

        final org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform shown =
                self.getShipData().getShipTransform();
        final double shownY = shown.getPosY();
        final org.joml.Quaterniondc shownQ =
                shown.rotationQuaternion(valkyrienwarfare.api.TransformType.SUBSPACE_TO_GLOBAL);
        final double[] prev = arTest$prevShown.put(self,
                new double[]{shownY, shownQ.w(), shownQ.x(), shownQ.y(), shownQ.z()});
        final double stepY = prev == null ? 0.0 : shownY - prev[0];
        // The ANGLE the shown pose turned through this tick, and how far its orientation stands from
        // the one last declared. Both in radians: a lurch is a step several times the craft's own
        // rate, and a lag is a standing difference — the column says which.
        final double stepAngle = prev == null ? 0.0
                : angleBetween(shownQ.w(), shownQ.x(), shownQ.y(), shownQ.z(),
                        prev[1], prev[2], prev[3], prev[4]);
        final double behindAngle = angleBetween(shownQ.w(), shownQ.x(), shownQ.y(), shownQ.z(),
                arrival[3], arrival[4], arrival[5], arrival[6]);

        TestTrace.recordHere("client_deck_pose_tick",
                "\"arrived\":" + arrived
                        + ",\"arrivedY\":" + TestTrace.fmt(arrival[1])
                        + ",\"arrivedVelY\":" + TestTrace.fmt(arrival[2])
                        + ",\"shownY\":" + TestTrace.fmt(shownY)
                        + ",\"stepY\":" + TestTrace.fmt(stepY)
                        + ",\"stepAngle\":" + TestTrace.fmt(stepAngle)
                        + ",\"behindAngle\":" + TestTrace.fmt(behindAngle)
                        + ",\"declaredOmega\":" + TestTrace.fmt(arrival[7])
                        + ",\"behind\":" + TestTrace.fmt(arrival[1] - shownY)
                        + ",\"guardStep\":" + TestTrace.fmt(
                                zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.lastGuardFrameStep)
                        + ",\"guardAllowed\":" + TestTrace.fmt(
                                zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.lastGuardAllowed)
                        + ",\"guardCarry\":" + TestTrace.fmt(
                                zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.lastGuardCarry));
    }
}
