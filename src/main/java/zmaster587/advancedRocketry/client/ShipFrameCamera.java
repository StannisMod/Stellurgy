package zmaster587.advancedRocketry.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * The client's view of the ship an entity is aboard: the attitude that levels the camera with the
 * deck, the offset that puts the eye where the head actually is, and the rotation that draws the body
 * standing on the deck rather than floating upright beside it.
 *
 * <p>Vanilla assumes a body's up is the world's up. It adds the eye height along world {@code +Y}
 * ({@code EntityRenderer.orientCamera}) and rotates a model by yaw alone
 * ({@code RenderLivingBase.applyRotations}). On an inverted ship the first puts the pilot's eye inside
 * the deck above his seat, so nothing renders at all; the second leaves the crew standing sideways out
 * of the hull.</p>
 *
 * <p>The physics mod corrects both, but only for entities it considers <em>mounted</em> to a ship - its
 * own seat concept, which AR's pilot dummy is not. So AR supplies them.</p>
 */
@SideOnly(Side.CLIENT)
public final class ShipFrameCamera {

    private ShipFrameCamera() {}

    // ---- No telemetry statics here, and no seam that exists only to be watched ----------------
    //
    // This class used to keep fourteen `public static volatile` fields describing what the camera
    // had been handed, plus a `recordFrameInterp` whose body was EMPTY - a method kept in shipping
    // code purely as an injection point. Nothing in production read any of it: every reader was a
    // client e2e on the far side of the harness socket.
    //
    // All of it is observed where it HAPPENS now. Those values were never this class's to hold -
    // they are locals and arguments of the render paths that compute them, and a test mixin
    // injected into those paths sees the same numbers at the same instant, holds them in the test
    // source set, and can window and reset them, which a cumulative static could not. What remains
    // below is the camera arithmetic itself, which production calls and which is therefore real.

    /**
     * The attitude of the ship {@code view} is aboard, smoothed across the frame, or {@code null} when
     * it is aboard none. A piloting local player uses the per-tick attitude samples the input path
     * already keeps, slerped by {@code partialTicks} - stepping at 20 Hz instead is the tier-2 jitter.
     */
    public static FreeFlightPhysics.Quat viewShipQuat(Entity view, float partialTicks) {
        if (view == null || view.world == null) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (TilePilotSeat.forShipPilot(view.getRidingEntity(), view.world) != null && view == mc.player) {
            return FreeFlightPhysics.slerp(KeyBindings.shipPrevQuat(), KeyBindings.shipQuat(), partialTicks);
        }
        // The LOCAL player's eye/camera/model gate on the MOVEMENT truth - resolved ABOARD a deck -
        // never on containment: a body is aboard when a deck carries its movement, not when it
        // merely sits inside the hull's volume. Containment overlaps a large air volume around the
        // hull (the fly-through hijack), and a HULL-STAND body (standing on the OUTER hull, which
        // keeps world-frame semantics) is inside it too while owning a world-frame view.
        if (view == mc.player) {
            if (!zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.isResolvingAboard(view)) {
                return null;
            }
            // Slerp the per-tick attitude samples across the frame, exactly as the pilot path
            // above does - the raw attitude steps at 20 Hz and a station-keeping ship's hunting
            // then shows as jitter at any frame rate.
            FreeFlightPhysics.Quat slerped = DeckLook.slerpedShipQuat(partialTicks);
            if (slerped != null) {
                return slerped;
            }
            return VSIntegration.shipAttitudeFor(view);
        }
        // A REMOTE body has no capture state on this side (the client resolves only its own
        // player's movement), so the movement truth is unavailable and containment is not a
        // substitute: it is true across the whole air volume around the hull, which drew any mob
        // merely standing on the ground beside a tilted ship lying on its side. Ask the same
        // SPATIAL question first contact asks instead - standing support in the ship's subspace -
        // which needs only the ship's blocks, and those this side has.
        String supporting = recentlySupportingShipId(view);
        return supporting == null ? null
                : VSIntegration.shipAttitudeForId(view.world, supporting);
    }

    /** How long a remote body keeps its supporting ship after standing support is last measured.
     *  A jump on the deck is unsupported for its whole arc, and a probe with no memory would pop
     *  the model upright mid-jump; the window only has to outlast a jump, not a walk-off. */
    private static final int SUPPORT_MEMORY_TICKS = 20;

    /** Per-body memory of the last ship measured to carry it, with the tick it was measured on.
     *  Weak keys: an entity that despawns must not be held alive by this. */
    private static final java.util.Map<EntityLivingBase, SupportMemo> SUPPORT_MEMO =
            new java.util.WeakHashMap<EntityLivingBase, SupportMemo>();

    private static final class SupportMemo {
        String shipId;
        long probedTick;
        long supportedTick;
    }

    /**
     * The ship carrying {@code view} by STANDING support in its subspace, kept for
     * {@link #SUPPORT_MEMORY_TICKS} after support was last seen; {@code null} when no loaded ship
     * has carried it recently.
     *
     * <p>The probe runs at most once per body per TICK - {@code applyRotations} asks twice per body
     * per FRAME (rotation and deck yaw), and a collision query at frame rate for every rendered mob
     * is not a cost this may pay.</p>
     */
    private static String recentlySupportingShipId(Entity view) {
        if (!(view instanceof EntityLivingBase) || view.world == null) {
            return null;
        }
        EntityLivingBase body = (EntityLivingBase) view;
        long tick = view.world.getTotalWorldTime();
        SupportMemo memo = SUPPORT_MEMO.get(body);
        if (memo == null) {
            memo = new SupportMemo();
            memo.supportedTick = Long.MIN_VALUE;
            SUPPORT_MEMO.put(body, memo);
        }
        if (memo.probedTick != tick) {
            memo.probedTick = tick;
            String carrying =
                    zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.standingOnShipIdFor(body);
            if (carrying != null) {
                memo.shipId = carrying;
                memo.supportedTick = tick;
            }
        }
        if (memo.shipId == null || tick - memo.supportedTick > SUPPORT_MEMORY_TICKS) {
            return null;
        }
        return memo.shipId;
    }

    /** The ship's local up in world coordinates for {@code view}, or {@code null} when not aboard. */
    public static double[] shipUpFor(Entity view, float partialTicks) {
        FreeFlightPhysics.Quat q = viewShipQuat(view, partialTicks);
        return q == null ? null : q.rotate(0.0, 1.0, 0.0);
    }

    /**
     * The camera attitude for a body standing on a deck whose look this client does NOT hold in the
     * deck frame (spectating an aboard body): its own world look, levelled to the ship's horizon.
     * Only the roll degree of freedom is added - yaw and pitch come back unchanged - so the view
     * still points exactly where that body aims. The LOCAL player's walking camera does not use
     * this any more: his look is held deck-frame ({@link DeckLook}) and the camera composes the
     * ship attitude with it directly, which has no singular attitude - this levelling is
     * undefined when the deck goes vertical (returns {@code null} along the deck normal).
     *
     * @return {yaw, pitch, roll} in degrees, or {@code null} to leave the camera alone
     */
    public static float[] deckLevelledCameraEuler(double[] shipUp, float yawDeg, float pitchDeg) {
        if (shipUp == null) {
            return null;
        }
        double[] forward = lookVec(yawDeg, pitchDeg);
        FreeFlightPhysics.Quat cam = FreeFlightPhysics.deckLevelledCameraQuat(forward, shipUp);
        return cam == null ? null : FreeFlightPhysics.eulerFromQuat(cam);
    }

    /**
     * The model rotation that stands {@code entity} on its deck: the ship attitude as an axis-angle
     * {@code {degrees, ax, ay, az}}, or {@code null} when it is aboard no ship (or the ship is upright,
     * where the rotation is the identity and pushing it would be pure cost).
     */
    public static double[] modelRotationFor(EntityLivingBase entity, float partialTicks) {
        FreeFlightPhysics.Quat q = viewShipQuat(entity, partialTicks);
        return axisAngleOf(q);
    }

    /** {@code q} as {@code {degrees, ax, ay, az}}, or {@code null} for no/identity rotation
     *  (an upright ship: pushing it would be pure cost). */
    private static double[] axisAngleOf(FreeFlightPhysics.Quat q) {
        if (q == null) {
            return null;
        }
        double w = q.w;
        if (w > 1.0) w = 1.0;
        if (w < -1.0) w = -1.0;
        double angle = 2.0 * Math.acos(w);
        double s = Math.sqrt(1.0 - w * w);
        if (Double.isNaN(angle) || angle < 1.0E-4 || s < 1.0E-9) {
            return null;
        }
        return new double[]{Math.toDegrees(angle), q.x / s, q.y / s, q.z / s};
    }


    /**
     * A world yaw, re-expressed in the ship's frame. The model's own yaw is a world heading; once the
     * ship rotation is applied around it, the yaw vanilla adds must be the deck-plane heading instead,
     * or the body's facing is counted in two frames at once.
     */
    public static float deckYawDeg(Entity entity, float worldYawDeg, float partialTicks) {
        FreeFlightPhysics.Quat q = viewShipQuat(entity, partialTicks);
        if (q == null) {
            return worldYawDeg;
        }
        double[] forward = lookVec(worldYawDeg, 0f);
        // world -> ship is the inverse rotation; for a unit quaternion that is its conjugate.
        FreeFlightPhysics.Quat inv = new FreeFlightPhysics.Quat(q.w, -q.x, -q.y, -q.z);
        double[] deckForward = inv.rotate(forward[0], forward[1], forward[2]);
        return FreeFlightPhysics.yawFromForwardDeg(deckForward[0], deckForward[1], deckForward[2]);
    }

    /** Minecraft's look vector for a yaw/pitch pair (degrees). */
    private static double[] lookVec(float yawDeg, float pitchDeg) {
        float yaw = yawDeg * 0.017453292F;
        float pitch = pitchDeg * 0.017453292F;
        float cosPitch = MathHelper.cos(pitch);
        return new double[]{
                -MathHelper.sin(yaw) * cosPitch,
                -MathHelper.sin(pitch),
                MathHelper.cos(yaw) * cosPitch
        };
    }
}
