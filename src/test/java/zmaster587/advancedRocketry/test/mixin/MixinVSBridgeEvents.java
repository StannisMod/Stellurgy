package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A ship's world-frame POSE was rewritten in one rigid write — the physics mod was told where the
 * ship now is, rotation kept, subspace untouched — as an event ({@code ship_pose_teleported}).
 *
 * <p>Every relocation the bridge performs for a crossing, a hyperspace arrival, a login restore or a
 * probe teleport funnels through one private static method, {@code VSBridge.teleportShip}: it is
 * where the durable {@code ShipData} transform, its previous-tick twin and the ship's world AABB are
 * all replaced, and where a loaded physics object is told to ADOPT the game-side transform instead
 * of re-integrating its own. The two public entry points (nearest-ship and by-uuid) only choose the
 * ship; the write is here, so this is the seam.</p>
 *
 * <p>The record is taken at the method's RETURN and carries production's own verdict: {@code ok} is
 * the boolean it returned — {@code false} exactly when no ship was resolved, in which case
 * {@code vsShip} reads {@code "null"} and the write did not happen. {@code toY} is the requested
 * destination height. {@code fromY} is the ship's world-frame Y BEFORE the write; the method
 * overwrites the transform before it returns, so that value is snapshotted at HEAD into a private
 * static field and read back at RETURN (the target is static and runs on the server thread, which
 * is why one field suffices). On the no-ship return there is no source pose, and the field is
 * absent rather than stood in for.</p>
 *
 * <p>An absent {@code fromY} therefore has exactly two readings, and {@code ok} tells them apart. On
 * {@code ok:false} it is the honest one: no ship was resolved and there was no pose to read. On
 * {@code ok:true} it is a DEFECT REPORT about this instrument, not about the bridge — production
 * dereferences the source transform on every path that returns true, so a successful write always
 * had a source Y, and its absence means the HEAD snapshot did not run (a missed injection is silent
 * by default in this config). Read it that way rather than as "the ship came from nowhere".</p>
 *
 * <p>Routed by the effective side of the calling thread: the bridge asks for the SERVER ship
 * manager, so in practice this lands in the server log; a client-thread call, should one ever
 * exist, would honestly land in the client log rather than be mislabelled.</p>
 *
 * <p>SILENT about: whether the physics object was loaded and adopted the pose (the branch is
 * try/caught and its outcome is not observable from the return value); whether the VS Y-limits
 * were widened; the X/Z of either pose (the pin asks for Y — the axis every crossing and descent
 * argues about); and whether the ship was PARKED across the write, which is the caller's duty,
 * not this method's.</p>
 *
 * <p>The target class is package-private, so it is named by string; it is AR's own code with no
 * SRG mapping, and the string-targeted mixins on this package already weave with
 * {@code remap = false}.</p>
 */
@Mixin(targets = "zmaster587.advancedRocketry.integration.vs.VSBridge", remap = false)
public abstract class MixinVSBridgeEvents {

    private static final String INSTRUMENT = "vs_bridge_events";

    /**
     * World-frame Y of the ship as it stood when the current {@code teleportShip} call entered, or
     * {@code NaN} when there was no ship (or no pose) to read. Written at HEAD, consumed at RETURN.
     */
    private static double arTest$fromY = Double.NaN;

    // CallbackInfoReturnable even at HEAD: the target returns a boolean, and mixin requires the
    // returnable form for ANY injection into such a method.
    @Inject(method = "teleportShip", at = @At("HEAD"), remap = false)
    private static void arTest$teleportEntered(World world, ShipData ship,
                                               double dstX, double dstY, double dstZ,
                                               CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        ShipTransform before = ship == null ? null : ship.getShipTransform();
        arTest$fromY = before == null ? Double.NaN : before.getPosY();
    }

    // RETURN fires at BOTH returns: the early `ship == null -> false` and the final `true`. The
    // return value is read, never assumed, and tells them apart.
    @Inject(method = "teleportShip", at = @At("RETURN"), remap = false)
    private static void arTest$teleported(World world, ShipData ship,
                                          double dstX, double dstY, double dstZ,
                                          CallbackInfoReturnable<Boolean> cir) {
        // Instrumented at BOTH ends, under one name: the entered-set is a set, so the second call
        // costs nothing, and the handler that actually writes the record is the one that must be
        // able to say it ran.
        TestTrace.instrumentHere(INSTRUMENT);
        double fromY = arTest$fromY;
        arTest$fromY = Double.NaN;
        boolean ok = cir.getReturnValueZ();
        StringBuilder payload = new StringBuilder()
                .append("\"vsShip\":\"").append(ship == null ? "null" : ship.getUuid()).append('"');
        if (!Double.isNaN(fromY)) {
            payload.append(",\"fromY\":").append(TestTrace.fmt(fromY));
        }
        payload.append(",\"toY\":").append(TestTrace.fmt(dstY))
                .append(",\"ok\":").append(ok);
        TestTrace.recordHere("ship_pose_teleported", payload.toString());
    }
}
