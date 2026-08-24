package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
