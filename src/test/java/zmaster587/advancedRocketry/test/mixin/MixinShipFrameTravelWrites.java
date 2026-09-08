package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Watches AR's own ship-frame travel, which is where a crew member's velocity is composed.
 *
 * <h2>Why this method</h2>
 *
 * <p>The phase recorder placed the launch BETWEEN ticks — after one {@code Entity.move} returned and
 * before the next began — and named the caller that followed it: {@code EntityLivingBase.travel}.
 * AR's {@code ShipFrameTravel.travel} is invoked from exactly there, ahead of {@code move}, so a
 * velocity it writes lands in that window and shows up as "between-ticks" with vanilla's travel
 * beneath it. Only the public entry is instrumented: its two branches are private and take a package-private
 * state type, which an injector's signature would have to name, and the entry already answers the
 * question being asked — did AR's ship-frame travel leave this velocity behind.</p>
 *
 * <p>It announces itself on ENTRY, before any threshold, so that a silence is readable rather than
 * merely empty — a body this method never handled and one it handled without writing are different
 * answers, and only the registry tells them apart. The {@code handled} field carries the return
 * value, which says whether AR took the body at all.</p>
 *
 * <p>A mixin from the tests into the product, which is the allowed direction; test source set,
 * queued by the harness coremod, absent from a released jar.</p>
 */
@Mixin(value = ShipFrameTravel.class, remap = false)
public abstract class MixinShipFrameTravelWrites {

    /** Motion magnitude worth a record. A crew member riding a deck sits far below this. */
    private static final double MOTION_REPORT = 4.0;

    /**
     * The body whose tick is being resolved right now, per thread.
     *
     * <p>{@code noteTickHistory} is entity-less — which is exactly why production used to accumulate
     * its per-tick line into one JVM-global ring and why nothing could tell one body's story out of
     * it. Its three callers all take the entity as their first parameter, so it is captured at their
     * HEADs and read back a few frames later at the seam that has the numbers. Per THREAD because
     * this class resolves on the client and the server both, and an integrated game runs the two in
     * one JVM.</p>
     *
     * <p>A private static on a mixin is allowed; a non-private one is not, and this is why the field
     * is not simply shared.</p>
     */
    private static final ThreadLocal<EntityLivingBase> ARTEST$RESOLVING = new ThreadLocal<>();

    @Inject(method = "travel", at = @At("HEAD"))
    private static void arTest$enterTravel(EntityLivingBase entity, float strafe, float vertical,
                                           float forward, float jumpMovementFactor, CallbackInfoReturnable<Boolean> cir) {
        ARTEST$RESOLVING.set(entity);
    }

    /**
     * The per-tick line, recorded where production used to append it to a ring.
     *
     * <p>Everything the line carried arrives here as a PARAMETER — the path character, the committed
     * ship-frame point, the carry and whether the body ended the tick on its deck — so no local
     * capture is involved, which is the injection form this project treats as a child-JVM fatal when
     * it is wrong.</p>
     *
     * <p>Recorded per resolved tick, so it turns its own 256-deep ring over in about thirteen seconds
     * — a reader takes a mark and asks for the window it cares about, which is what the global ring
     * could not offer.</p>
     */
    @Inject(method = "noteTickHistory", at = @At("HEAD"))
    private static void arTest$tickLine(char path, double heldX, double heldY, double heldZ,
                                        double carryX, double carryY, double carryZ,
                                        boolean onDeck, CallbackInfo ci) {
        EntityLivingBase entity = ARTEST$RESOLVING.get();
        if (entity == null) {
            return;
        }
        TestTrace.instrument(entity, "ship_frame_tick_events");
        // The SAME line production used to append to its ring, byte for byte: eighteen readers across
        // two test classes parse this format, and changing it and them in one step would have been a
        // rewrite of the parsing layer on top of a move. What changed is WHO builds it and where it
        // goes — a record attributed to this body, in the ring the reader can take a mark in, instead
        // of one JVM-global string that held every body at once.
        String line = String.format(java.util.Locale.ROOT,
                "%d%c|B=%.3f,%.3f,%.3f|H=%.3f,%.3f,%.3f|m=%.4f,%.4f,%.4f|c=%.4f|in=%.1f/%.1f|d=%d"
                        + "|s=%d%d/%d|w=%d",
                resolvedTicks, path,
                lastBodyLocalX, lastBodyLocalY, lastBodyLocalZ, heldX, heldY, heldZ,
                lastMotionShipX, lastMotionShipY, lastMotionShipZ,
                Math.sqrt(carryX * carryX + carryY * carryY + carryZ * carryZ),
                lastInStrafe, lastInForward, onDeck ? 1 : 0,
                lastSweepCollidedX ? 1 : 0, lastSweepCollidedZ ? 1 : 0, lastObstacleCount,
                lastCommitWorldTime);
        TestTrace.record(entity, "ship_frame_tick",
                "\"e\":" + entity.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\""
                        + ",\"line\":\"" + TestTrace.json(line) + "\"");
    }

    // The values the line carries that are not parameters of this seam. SHADOWED rather than read
    // through an accessor: they are production's own intermediate state for one tick, and the
    // appendix names a shadowed field as a legitimate source for a record. Production no longer
    // publishes any of them — the public ones that remain are read by other tests and go with their
    // own slices.
    @Shadow private static volatile long resolvedTicks;
    @Shadow private static volatile double lastBodyLocalX;
    @Shadow private static volatile double lastBodyLocalY;
    @Shadow private static volatile double lastBodyLocalZ;
    @Shadow private static volatile double lastMotionShipX;
    @Shadow private static volatile double lastMotionShipY;
    @Shadow private static volatile double lastMotionShipZ;
    @Shadow private static volatile float lastInStrafe;
    @Shadow private static volatile float lastInForward;
    @Shadow private static volatile boolean lastSweepCollidedX;
    @Shadow private static volatile boolean lastSweepCollidedZ;
    @Shadow private static volatile int lastObstacleCount;
    @Shadow private static volatile long lastCommitWorldTime;

    @Inject(method = "travel", at = @At("RETURN"))
    private static void arTest$afterTravel(EntityLivingBase entity, float strafe, float vertical,
                                           float forward, float jumpMovementFactor,
                                           CallbackInfoReturnable<Boolean> cir) {
        arTest$note(entity, "ship_frame_travel", cir.getReturnValue());
    }

    private static void arTest$note(EntityLivingBase entity, String instrument, Object handled) {
        if (entity == null) {
            return;
        }
        TestTrace.instrument(entity, instrument);
        if (Math.abs(entity.motionY) <= MOTION_REPORT) {
            return;
        }
        TestTrace.record(entity, "ship_frame_motion",
                "\"e\":" + entity.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\""
                        + ",\"where\":\"" + instrument + "\""
                        + ",\"handled\":" + handled
                        + ",\"motionY\":" + TestTrace.fmt(entity.motionY)
                        + ",\"atY\":" + TestTrace.fmt(entity.posY)
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }
}
