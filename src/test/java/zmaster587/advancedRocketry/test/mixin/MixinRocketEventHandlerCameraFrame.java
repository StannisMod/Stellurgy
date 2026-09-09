package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.EntityViewRenderEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.client.ShipFrameCamera;
import zmaster587.advancedRocketry.event.RocketEventHandler;
import zmaster587.advancedRocketry.test.trace.DeckCameraState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Everything a test knows about what the client's camera was handed this frame, taken from the
 * frame that hands it.
 *
 * <h2>Why it is here and not in production</h2>
 *
 * <p>{@code ShipFrameCamera} used to carry fourteen {@code public static volatile} fields and a set
 * of {@code record*} methods that filled them; the camera-setup handler poked those fields on every
 * rendered frame, and one of the methods had an EMPTY body — shipping code whose only reason to
 * exist was that a mixin could inject into it. No production path read a single one of the values.
 * They were a test's question, asked at 120 Hz inside a class that ships to players.</p>
 *
 * <h2>Local capture was tried here and does NOT survive — measured 2026-09-09</h2>
 *
 * <p>The first cut of this class took the attitude and the ship's up as locals
 * ({@code e}, {@code cq}/{@code shipQ}, {@code shipUp}) with
 * {@code LocalCapture.CAPTURE_FAILSOFT}. Both hooks were silently DROPPED: the mixin wove, the
 * non-capturing injections in this same class ran — the crosshair scenario passed and the
 * instrument was listed as live — while every scenario reading an attitude got the field's default.
 * That is what failsoft does, and it is why capturing a local is the LAST thing to reach for: an
 * argument, a return value, or a field on the target all resolve by name and cannot quietly stop
 * matching the way a local's slot can.</p>
 *
 * <h2>And a copy of production is worse than either</h2>
 *
 * <p>None of it is needed, but the replacement has to be a READ or a call into production's own
 * code — never a copy of production's expression. An observation that recomputes what production
 * "would have" computed stops measuring production: on the day production breaks, the copy still
 * evaluates the old, correct formula and the scenario passes green over the bug. The second cut of
 * the pilot hook did exactly that and is described at that hook.</p>
 *
 * <p>What is admissible here: the attitude is on the {@code event} — the method's own ARGUMENT —
 * because these hooks sit AFTER the three setters that push it, so
 * {@code getYaw()}/{@code getPitch()}/{@code getRoll()} return exactly what the renderer was handed
 * (yaw carries the vanilla camera convention's {@code +180}, which is removed here so the stored
 * number is the ship's own heading, as production stored it). The walking branch's ship up is one
 * call to {@code ShipFrameCamera.shipUpFor}, which IS the {@code viewShipQuat(...).rotate(0,1,0)}
 * production's local held — the same function, the same question. The pilot branch's comes off a
 * redirect of the call that carries the quaternion, for the reason given there.</p>
 *
 * <h2>Which points, and why those</h2>
 *
 * <p>The engagement hooks sit AFTER {@code setRoll} — the last of the three pushes — at the two
 * ordinals that correspond to the branches production used to record from: ordinal 1 is the seated
 * tier-2 pilot, ordinal 2 the walking crew member. Ordinal 0 is the tier-1 rocket branch, which
 * never recorded a ship-frame camera and still does not.</p>
 *
 * <p>The release hooks sit at the two RETURNs that ARE production's decision that no ship-frame
 * camera applies (a body not resolved aboard; a ship whose attitude will not resolve). Ordinals into
 * our own method are brittle by nature — but they are brittle in the safe direction: a drift makes
 * the injection miss, and {@link TestTrace#instrumentHere} then reports an instrument that never
 * ran, rather than a number that is quietly wrong. That is not a hypothetical: it is exactly how the
 * dropped captures above were caught.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 *   <li>Frames that decide nothing — no view entity, a rocket pilot, the hold-last-roll return —
 *       leave {@code active} standing. That was production's rule and it is kept, so a reader sees
 *       the same value it always saw.</li>
 *   <li>The crosshair is sampled only on the walking path, after the two pilot branches have
 *       returned, exactly where production sampled it.</li>
 * </ul>
 *
 * <p>Client render thread. Test source set.</p>
 */
@Mixin(RocketEventHandler.class)
public abstract class MixinRocketEventHandlerCameraFrame {

    /** The historic name of this observation. It is asserted on by scenarios that need to know
     *  somebody was LOOKING before they read an empty log, so it outlives the class that used to
     *  carry it. */
    private static final String INSTRUMENT = "deck_camera_events";

    /** The render stage ran, with the client world's entity population from the same frame. */
    @Inject(method = "onFreeFlightCameraSetup", at = @At("HEAD"), remap = false)
    private void arTest$renderStage(EntityViewRenderEvent.CameraSetup event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        Minecraft mc = Minecraft.getMinecraft();
        DeckCameraState.noteRenderStage(mc.world == null ? -1 : mc.world.loadedEntityList.size());
    }

    /**
     * The ship attitude the seated-pilot branch is about to push, taken from the call that CARRIES
     * it rather than recomputed.
     *
     * <p>The first cut of this hook wrote
     * {@code slerp(KeyBindings.shipPrevQuat(), KeyBindings.shipQuat(), p).rotate(0,1,0)} — a copy of
     * production's own expression for {@code cq}, which is the worst available answer: it agrees
     * with production today, keeps agreeing after production changes the expression, and goes green
     * whenever both copies are wrong together. Asking {@code ShipFrameCamera.shipUpFor} instead
     * would not be a copy but WOULD be a different question: it gates the pilot path on
     * {@code forShipPilot}, which is {@code forRider + isLinked + isManagedByShip}, while this branch
     * gates on {@code forRider + isLinked} alone.</p>
     *
     * <p>So neither reconstruction is admissible, and none is needed: {@code eulerFromQuat(cq)} is a
     * call taking {@code cq} as its argument, one instruction earlier. Redirecting it hands the
     * quaternion over and performs the original call, so production is unchanged and the test holds
     * the very object production held.</p>
     */
    @Redirect(method = "onFreeFlightCameraSetup",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/api/FreeFlightPhysics;"
                            + "eulerFromQuat(Lzmaster587/advancedRocketry/api/FreeFlightPhysics$Quat;)[F",
                    ordinal = 1),
            remap = false)
    private float[] arTest$pilotShipQuat(FreeFlightPhysics.Quat cq) {
        arTest$pilotUp = cq == null ? null : cq.rotate(0.0, 1.0, 0.0);
        return FreeFlightPhysics.eulerFromQuat(cq);
    }

    /** The ship up the redirect above took from the branch's own quaternion, held for the few
     *  instructions between it and the push. Client render thread, one frame, single-threaded. */
    private static double[] arTest$pilotUp = null;

    /** The seated tier-2 pilot's camera: the ship attitude, pushed whole. */
    @Inject(method = "onFreeFlightCameraSetup",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/client/event/EntityViewRenderEvent$CameraSetup;"
                            + "setRoll(F)V",
                    ordinal = 1, shift = At.Shift.AFTER),
            remap = false)
    private void arTest$pilotCamera(EntityViewRenderEvent.CameraSetup event, CallbackInfo ci) {
        arTest$engaged(event, arTest$pilotUp, "pilot");
    }

    /** The walking crew member's camera: the ship attitude composed with his held deck look. */
    @Inject(method = "onFreeFlightCameraSetup",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/client/event/EntityViewRenderEvent$CameraSetup;"
                            + "setRoll(F)V",
                    ordinal = 2, shift = At.Shift.AFTER),
            remap = false)
    private void arTest$walkingCamera(EntityViewRenderEvent.CameraSetup event, CallbackInfo ci) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        float p = (float) event.getRenderPartialTicks();
        arTest$engaged(event, view == null ? null : ShipFrameCamera.shipUpFor(view, p), "walking");
    }

    /** The two branches that decide NO ship-frame camera applies to this body. */
    @Inject(method = "onFreeFlightCameraSetup",
            at = {@At(value = "RETURN", ordinal = 4), @At(value = "RETURN", ordinal = 5)},
            remap = false)
    private void arTest$cameraReleased(EntityViewRenderEvent.CameraSetup event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        boolean wasActive = DeckCameraState.active;
        DeckCameraState.noteDisengaged();
        if (wasActive) {
            TestTrace.recordHere("deck_camera_changed", "\"active\":false"
                    + ",\"roll\":" + TestTrace.fmt(DeckCameraState.roll)
                    + ",\"upY\":" + TestTrace.fmt(DeckCameraState.shipUpY)
                    + ",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
        }
    }

    /**
     * The crosshair this frame: what it resolved, and where its ray started.
     *
     * <p>Anchored on the test-mode query that opens the {@code [FF-TRACE/CAM]} block — the first
     * production call after the two pilot branches have returned, so this is the walking path only,
     * which is where production sampled it. The query itself runs on every frame (it is the first
     * operand of the {@code &&}); what it GATES is only the log line.</p>
     */
    @Inject(method = "onFreeFlightCameraSetup",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/command/test/"
                            + "TestProbeCommandRegistration;isTestMode()Z"),
            remap = false)
    private void arTest$crosshair(EntityViewRenderEvent.CameraSetup event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) {
            return;
        }
        float p = (float) event.getRenderPartialTicks();
        net.minecraft.util.math.RayTraceResult over = Minecraft.getMinecraft().objectMouseOver;
        net.minecraft.util.math.Vec3d rayEye = view.getPositionEyes(p);
        DeckCameraState.noteCrosshair(
                over == null || over.typeOfHit != net.minecraft.util.math.RayTraceResult.Type.BLOCK
                        ? "" : over.getBlockPos().getX() + "," + over.getBlockPos().getY() + ","
                                + over.getBlockPos().getZ(),
                rayEye.x, rayEye.y, rayEye.z);
    }

    /**
     * One engagement, with the edge recorded when {@code active} actually changes. A per-frame
     * record would turn the ring over in seconds; the edge is the event.
     *
     * <p>The attitude comes off the event AFTER the three setters, so it is what the renderer got.
     * The {@code -180} undoes the vanilla camera-yaw convention production applies on the way in,
     * leaving the ship's own heading — the number the production field used to hold.</p>
     */
    private static void arTest$engaged(EntityViewRenderEvent.CameraSetup event, double[] shipUp,
                                       String branch) {
        TestTrace.instrumentHere(INSTRUMENT);
        boolean wasActive = DeckCameraState.active;
        double roll = event.getRoll();
        DeckCameraState.noteEngaged(event.getYaw() - 180.0F, event.getPitch(), roll, shipUp);
        if (!wasActive) {
            TestTrace.recordHere("deck_camera_changed", "\"active\":true"
                    + ",\"roll\":" + TestTrace.fmt(roll)
                    + ",\"upY\":" + TestTrace.fmt(DeckCameraState.shipUpY)
                    + ",\"caller\":\"" + branch + "\"");
        }
    }
}
