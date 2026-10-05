package org.valkyrienskies.mod.common.ships.interpolation;

import net.minecraft.util.math.AxisAlignedBB;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.collision.Polygon;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import valkyrienwarfare.api.TransformType;

import javax.annotation.Nonnull;

/**
 * The client's pose for a craft, ADVANCED from the state the craft declared rather than filtered
 * toward it.
 *
 * <h2>A pose is shown at the tick it is VALID at (2026-10-05)</h2>
 *
 * <p>Every pose arrives stamped with the server tick it was built on. A pose for a tick already shown
 * by prediction corrects that prediction as a residual; a pose for a tick not yet shown moves the
 * shown tick up to its own, and the residual turns any jump into ordinary movement; a shown tick
 * predicting more than two ahead of the stream comes back the same way. So the shown pose is never
 * further from the newest pose than prediction puts it, and never moves by a step the craft did not
 * take. Until then a pose was taken as valid at the tick it ARRIVED, and under load that was wrong
 * exactly when it mattered: measured over six parallel clients, two poses landed in one client tick
 * and none in the next, and the shown pose stepped 4.0, then 2.0 by prediction, then 0.0 when the
 * predicted pose arrived — a seated pilot's "freeze and catch up", three of six runs red.</p>
 *
 * <h2>WIRED 2026-08-25 (maintainer's call, on the numbers below)</h2>
 *
 * <p>Measured on a craft slewing at 2 rad/s with the rotational trace at full precision: the shown
 * orientation matches the declared one on every tick ({@code behindAngle = 0.00000}, step/declared
 * median 1.00), and its step IS the declared rate ({@code 0.10000} rad a tick) where the filter it
 * replaces alternates {@code 0.1333} / {@code 0.0667} around the same mean — smaller than the truth
 * on the tick a pose arrives, larger on the tick after. There is no lurch and no lag. Green:
 * {@code testClient --tests '*VS*'} 77/77, {@code testServer} 657/657, unit + integration.</p>
 *
 * <p><b>What used to stand here was a body-carry defect, and it was neither this class's nor the
 * carry's.</b> A body aboard was left standing on the pose its ship held a TICK AGO, because ship
 * poses advance at tick phase END, after the world's entities have already moved. That is a
 * property of the tick ORDER: it was present behind the filter at exactly the same one tick — the
 * identical 0.1974 blocks per tick at 2 rad/s and 1.974 blocks of arm — and the filter was hiding
 * nothing. A body is now put back on its deck point once the poses are current, and the same
 * scenario measures a seat miss of zero behind EITHER pose source.</p>
 *
 * <p><b>The reading that kept this class out was wrong in its arithmetic.</b> It claimed 1.6 blocks
 * of slip per tick as "one tick of that rotation at the body's radius (~16 blocks)". The body stands
 * 1.974 blocks off the roll axis, measured; one tick of that rotation at that arm is 0.197. The 1.6
 * is real but is a pose SNAP — 0.81 rad arriving in one tick — and it happens on some runs and not
 * others, which is why the scenario passed at 0.201 as often as it failed at 1.38.</p>
 *
 * <p><b>An earlier reading of the same failure claimed a "rotational lurch" and was wrong.</b> It
 * came from a trace whose formatter rendered every value to one decimal place, which turned an angle
 * measured in hundredths of a radian into a column of identical numbers. The instrument's PRECISION
 * was the finding; the lurch was not there.</p>
 *
 * <h2>Three reds that were never about this mechanism (2026-08-24)</h2>
 *
 * <p>This was measured against the crew suite three times before it was wired, and each attempt
 * traded one red for another — a body walking 1.17 blocks across a rolling deck, then a capture
 * churning six times, then four scenarios at once. Every one was read as evidence about the SMOOTHING
 * POLICY, three policies were written to answer them, and the policy was never the fault. A per-tick
 * trace across a packet boundary — a moving pose beside a zero carry, on the same line — named all
 * three:</p>
 *
 * <ul>
 *   <li><b>The velocity reported after an arriving packet was ZERO.</b> A pose lands the moment it
 *       arrives, so "the pose before this tick" was already the new one and the step came out empty.
 *       A body lost its whole carry on one tick in six; the capture guard, whose allowance is three
 *       times that carry, fell to its bare epsilon while the deck stepped half a block. Fixed by
 *       differencing two poses captured at TICK ENDS, which no arrival can fall between.</li>
 *   <li><b>A residual cap stated in RADIANS.</b> An angle knows nothing about the lever arm it acts
 *       through: 0.025 rad became a 1.6-block step at the body's radius, identically in two unrelated
 *       scenarios — the same fifteen digits in both, which is how a constant announces itself where a
 *       measurement belongs. The rotational residual now retires by fraction alone.</li>
 *   <li><b>The capture guard built its allowance from the tighter of two known readings.</b> A body's
 *       carry is what the deck DID; the allowance now takes the larger of that and what the craft
 *       DECLARES, because a guard's false positive costs a dropped body and its false negative costs
 *       nothing the next tick will not catch.</li>
 * </ul>
 *
 * <p><b>What the constants still owe.</b> {@link #RESIDUAL_SURVIVES_PER_TICK} is reasoned rather than
 * measured: the e2e that changes a craft's acceleration mid-interval — the one case this mechanism
 * cannot handle by construction — does not exist yet, and until it does that number is a starting
 * value with its argument written beside it.</p>
 *
 * <h2>Why not smooth toward the last packet</h2>
 *
 * <p>The filter this replaces moved the shown pose half-way to the newest one each tick. That is a
 * permanent lag: the pose a body stands on trails the pose the craft reports, by about a packet, for
 * as long as the craft keeps moving. It also makes the shown pose a quantity nobody declared — its
 * rate is the filter's, not the craft's — so a body carried by the DECLARED velocity drifts against
 * the deck it is standing on. Measured on a driven climb before this existed: the craft declared
 * 1.5333 blocks/tick of carry at the body's point while the filtered pose advanced at 1.4700.</p>
 *
 * <p>Here the craft's own motion drives the pose. A packet states where the craft is and how it is
 * moving; between packets the pose advances by exactly that motion, so the pose and the carry are
 * the same statement and a standing body does not slide. When the next packet arrives the prediction
 * is already where it says, except for whatever the craft did that could not be predicted.</p>
 *
 * <h2>The residual, and why it is not simply snapped away</h2>
 *
 * <p>A prediction is only as good as the assumption that the motion held. When a craft's
 * acceleration CHANGES between packets the prediction is off by that change, and adopting the new
 * pose outright would show that error as a jerk — the thing a pilot feels and a standing body is
 * displaced by. So the error is kept as a RESIDUAL added to the shown pose and retired over the
 * following ticks: the craft's state is always the declared one, and only the leftover of a wrong
 * prediction fades.</p>
 *
 * <p><b>A large residual is not a prediction error and is not faded.</b> A teleport, a jump arrival,
 * a ship load — those are discontinuities, and blending across one would drag a body over whatever
 * lies between. The bound scales with what the craft itself declares it can cover, plus a floor, so
 * it does not have to be re-tuned per craft.</p>
 */
public class DeclaredMotionTransformInterpolator implements ITransformInterpolator {

    private static final double SECONDS_PER_TICK = 0.05;

    /**
     * The fraction of the residual that SURVIVES each tick.
     *
     * <p>It sets how long a mispredicted tick stays visible: at 0.5 a residual is a quarter of
     * itself after two ticks and under a tenth after four, so an acceleration change is absorbed
     * within the fifth of a second a player cannot resolve, while never being applied as a step.
     * Faster than this and the retirement becomes the jerk it exists to avoid; slower and the shown
     * pose lags a real change in the craft's motion.</p>
     */
    private static final double RESIDUAL_SURVIVES_PER_TICK = 0.5;


    /**
     * Residual beyond which the pose is adopted outright, in blocks, added to the distance the
     * craft's own declared speed covers in {@link #DISCONTINUITY_TICKS} ticks.
     */
    private static final double DISCONTINUITY_FLOOR_BLOCKS = 4.0;
    private static final double DISCONTINUITY_TICKS = 4.0;

    /** The craft's declared pose, advanced by its declared motion between packets. */
    @Nonnull
    private ShipTransform declaredTransform;
    /** The pose exactly as it last arrived — the reference the received AABB is expressed in. */
    @Nonnull
    private ShipTransform latestReceivedTransform;
    @Nonnull
    private AxisAlignedBB latestReceivedAABB;
    /** What is actually shown: the declared pose plus the residual of a wrong prediction. */
    @Nonnull
    private ShipTransform curTickTransform;

    /** World frame, blocks and radians per SECOND, as declared. */
    private final Vector3d linearVelocity = new Vector3d();
    private final Vector3d angularVelocity = new Vector3d();

    private final Vector3d residualPos = new Vector3d();
    private final Quaterniond residualRot = new Quaterniond();

    /**
     * The pose as it stood at the END of the last two ticks — what {@link #getShownVelocity}
     * differences.
     *
     * <p><b>Both are captured at a tick's end, and that is the whole of the fix they carry.</b> The
     * first version remembered "the pose before this tick's compose", which is not the same thing:
     * an arriving packet updates the shown pose the moment it lands, so on a tick following an
     * arrival the "previous" pose was already the new one and the reported step came out ZERO. What
     * that did downstream is worth remembering — a body lost its whole carry on one tick in six, the
     * capture guard's allowance (three times that carry) fell to its bare epsilon while the deck
     * stepped half a block, and the capture churned. It was read as "the smoothing policy is wrong"
     * and cost three rewritten policies before a per-tick trace showed a moving pose beside a zero
     * carry on the same line.</p>
     */
    private final Vector3d tickEndPos = new Vector3d();
    private final Quaterniond tickEndRot = new Quaterniond();
    private final Vector3d prevTickEndPos = new Vector3d();
    private final Quaterniond prevTickEndRot = new Quaterniond();
    private boolean haveShownStep;

    /**
     * The server tick the shown pose stands for. It advances by exactly ONE each client tick,
     * whatever arrives: a pose is shown at the tick it is valid at (its stamp), never at the tick it
     * happens to land in.
     *
     * <p>Measured 2026-10-05 under six parallel clients, before poses carried a stamp: two poses landed
     * inside one client tick and none in the next, and an interpolator taking each arrival as "now"
     * jumped to the newer of the two (a step of 4.0 blocks at a 2.0 cruise), predicted the next tick
     * on top of it, and then stood still for a tick when the pose it had predicted arrived — the
     * "2.0, 2.0, 0.0, 4.0" a seated pilot sees as the craft freezing and catching up.</p>
     */
    private long shownTick;
    /** The tick {@link #declaredTransform} stands for. */
    private long declaredTick;
    /** The stamp of the newest pose taken in or waiting; a pose not newer says nothing new. */
    private long newestPoseTick;
    /** Whether any pose has arrived yet — the first one places the shown tick. */
    private boolean aligned;
    /** The newest pose that arrived for a tick not yet shown, or {@code null}. */
    private Pose pending;
    /** Whether the pose for the tick already shown by prediction arrived since the last tick. */
    private boolean lateForShownTick;
    /** Whether the tick just shown is a pose exactly as it arrived (diagnostic — a test reads it). */
    private boolean shownFromPose;

    /**
     * How many ticks the shown pose may run AHEAD of the newest pose before the client is ahead of the
     * stream rather than merely predicting across a late one, and brings its shown tick back —
     * continuously, through the residual. Measured 2026-10-05 under six parallel clients: at most
     * three poses arrived inside one client tick, so a prediction two ticks deep is unevenness.
     */
    private static final long MAX_TICKS_FROM_STREAM = 2;

    /** One arrived pose, with its motion and the tick it is valid at. */
    private static final class Pose {
        final ShipTransform transform;
        final AxisAlignedBB aabb;
        final double linearX, linearY, linearZ, angularX, angularY, angularZ;
        final long tick;

        Pose(ShipTransform transform, AxisAlignedBB aabb, double linearX, double linearY, double linearZ,
             double angularX, double angularY, double angularZ, long tick) {
            this.transform = transform;
            this.aabb = aabb;
            this.linearX = linearX;
            this.linearY = linearY;
            this.linearZ = linearZ;
            this.angularX = angularX;
            this.angularY = angularY;
            this.angularZ = angularZ;
            this.tick = tick;
        }
    }

    private static final double DOUBLE_EQUALS_THRESHOLD = 1e-6;

    public DeclaredMotionTransformInterpolator(@Nonnull ShipTransform initial, @Nonnull AxisAlignedBB initialAABB) {
        this.declaredTransform = initial;
        this.latestReceivedTransform = initial;
        this.latestReceivedAABB = initialAABB;
        this.curTickTransform = initial;
    }

    @Override
    public void onNewTransformPacket(@Nonnull ShipTransform newTransform, @Nonnull AxisAlignedBB newAABB) {
        onNewTransformPacket(newTransform, newAABB, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /** A pose with no stated tick is taken as the tick after the newest — what every pose was taken
     *  as before poses carried one. The pose packet states its tick; this is the interface's
     *  stamp-less form. */
    @Override
    public void onNewTransformPacket(@Nonnull ShipTransform newTransform, @Nonnull AxisAlignedBB newAABB,
                                     double linearX, double linearY, double linearZ,
                                     double angularX, double angularY, double angularZ) {
        onNewTransformPacket(newTransform, newAABB, linearX, linearY, linearZ, angularX, angularY, angularZ,
                aligned ? newestPoseTick + 1 : 0L);
    }

    @Override
    public void onNewTransformPacket(@Nonnull ShipTransform newTransform, @Nonnull AxisAlignedBB newAABB,
                                     double linearX, double linearY, double linearZ,
                                     double angularX, double angularY, double angularZ, long serverTick) {
        final Pose pose = new Pose(newTransform, newAABB, linearX, linearY, linearZ,
                angularX, angularY, angularZ, serverTick);
        if (!aligned) {
            // The first pose places the clock: it is shown on the tick this client is about to run,
            // which is the tick it arrived in — the same latency every pose had before it was stamped.
            aligned = true;
            take(pose);
            shownTick = serverTick - 1;
            residualPos.zero();
            residualRot.identity();
            curTickTransform = composeShown();
            return;
        }
        if (serverTick <= newestPoseTick) {
            // Not newer than a pose already taken in or waiting: it says nothing new.
            return;
        }
        newestPoseTick = serverTick;
        if (serverTick > shownTick) {
            // Its tick has not been shown yet: the next tick shows it (or, if it is further ahead,
            // catches up to it continuously). Only the newest is kept — an older one in between
            // says nothing the newest does not.
            pending = pose;
            return;
        }
        // LATE: a pose for a tick already shown, by prediction. From now on the shown pose is derived
        // from it, and the shown pose does not move on arrival — what the prediction got wrong is a
        // residual, retired over the following ticks.
        final ShipTransform shown = curTickTransform;
        take(pose);
        lateForShownTick = serverTick == shownTick;
        if (shownTick - serverTick > MAX_TICKS_FROM_STREAM) {
            // Not a late pose but a client running ahead of the stream: its shown tick comes back,
            // and the residual carries the difference, so nothing visible moves.
            shownTick = serverTick + MAX_TICKS_FROM_STREAM;
        }
        advanceDeclaredTo(shownTick);
        residualAgainst(shown);
        curTickTransform = composeShown();
    }

    @Override
    public void tickTransformInterpolator() {
        if (!aligned) {
            // Nothing has arrived: the pose this craft was loaded at stands.
            captureTickEnd();
            return;
        }
        final ShipTransform before = curTickTransform;
        // The leftover of a wrong prediction fades rather than being applied as a step.
        retireResidual();

        final Pose due = pending;
        pending = null;
        final boolean realign = due == null && lateForShownTick;
        lateForShownTick = false;
        long skipped = 0;
        if (due != null) {
            // A pose newer than the shown tick is in hand: show ITS tick. One tick on is the ordinary
            // case; further is a burst, caught up to now, the residual turning the jump into ordinary
            // movement. Holding it for its own tick instead was measured 2026-10-05 to cost a tick of
            // latency on every other tick when poses arrive in pairs (a seated pilot 3.9 blocks behind
            // his climbing ship, against a 3.0 bound), and a queue that never drained after a stall.
            skipped = due.tick - shownTick - 1;
            shownTick = due.tick;
            take(due);
        } else if (!realign) {
            // Nothing arrived: the craft keeps doing what it last said it was doing.
            shownTick++;
        }
        // REALIGN: the pose for the tick already shown by prediction arrived this tick, and nothing
        // newer. Predicting a further tick would leave the client permanently a tick ahead of the
        // stream, where every change of the craft's motion is an overshoot — measured 2026-10-05: a
        // craft that had stopped was shown 2.77 blocks past where it stood, and the residual took
        // thirteen ticks to retire while its pilot's next climb was measured against it. So the shown
        // tick stays on that pose, and the residual carries the tick of movement the eye expects.
        advanceDeclaredTo(shownTick);
        shownFromPose = due != null && skipped == 0;
        if (skipped > 0 || realign) {
            residualAgainst(advance(before, 1));
        }

        curTickTransform = composeShown();
        captureTickEnd();
    }

    /** Take an arrived pose as the craft's declared state at its own tick. */
    private void take(Pose pose) {
        declaredTransform = pose.transform;
        declaredTick = pose.tick;
        newestPoseTick = Math.max(newestPoseTick, pose.tick);
        latestReceivedTransform = pose.transform;
        latestReceivedAABB = pose.aabb;
        linearVelocity.set(pose.linearX, pose.linearY, pose.linearZ);
        angularVelocity.set(pose.angularX, pose.angularY, pose.angularZ);
    }

    /** Carry the declared pose forward by its declared motion to {@code tick}. */
    private void advanceDeclaredTo(long tick) {
        if (tick > declaredTick) {
            declaredTransform = advance(declaredTransform, tick - declaredTick);
            declaredTick = tick;
        }
    }

    /** {@code transform} moved on by {@code ticks} of the declared motion. */
    @Nonnull
    private ShipTransform advance(@Nonnull ShipTransform transform, long ticks) {
        final double seconds = ticks * SECONDS_PER_TICK;
        final Vector3d advancedPos = new Vector3d(
                transform.getPosX() + linearVelocity.x * seconds,
                transform.getPosY() + linearVelocity.y * seconds,
                transform.getPosZ() + linearVelocity.z * seconds);
        final Quaterniond advancedRot = new Quaterniond(transform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL));
        final double angle = angularVelocity.length() * seconds;
        if (angle > 1.0E-9) {
            final Vector3d axis = new Vector3d(angularVelocity).normalize();
            // World-frame rotation rate, so it composes on the LEFT of the craft's orientation.
            advancedRot.premul(new Quaterniond().fromAxisAngleRad(axis.x, axis.y, axis.z, angle)).normalize();
        }
        return new ShipTransform(advancedPos, advancedRot, transform.getCenterCoord());
    }

    /**
     * Set the residual so the shown pose is {@code shown} — what the declared pose got wrong about
     * it, to be retired — unless the difference is a discontinuity, which is adopted outright.
     */
    private void residualAgainst(@Nonnull ShipTransform shown) {
        final double dx = shown.getPosX() - declaredTransform.getPosX();
        final double dy = shown.getPosY() - declaredTransform.getPosY();
        final double dz = shown.getPosZ() - declaredTransform.getPosZ();
        final double error = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double discontinuityAbove = DISCONTINUITY_FLOOR_BLOCKS
                + linearVelocity.length() * SECONDS_PER_TICK * DISCONTINUITY_TICKS;
        if (error > discontinuityAbove) {
            // Not a mispredicted tick — the craft is somewhere else entirely (a teleport, a jump
            // arrival, a load). Fading across that would sweep the deck, and anything standing on
            // it, through the space between.
            residualPos.zero();
            residualRot.identity();
        } else {
            residualPos.set(dx, dy, dz);
            final Quaterniondc shownRot = shown.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            final Quaterniondc declaredRot = declaredTransform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            // residual = shown * declared^-1, left-multiplied, so it composes onto the declared rotation
            residualRot.set(declaredRot).invert().premul(shownRot).normalize();
        }
    }

    /**
     * Retire a FRACTION of the residual each tick, and nothing caps it.
     *
     * <p>A cap of 0.2 blocks a tick stood here, taken from the body-capture guard's epsilon so the
     * guard could not see a retirement; that guard was deleted on 2026-09-16, and the cap outlived its
     * reason. What it did then, measured 2026-10-05: under load the server's physics fell behind the
     * game tick (poses advancing 0.67–1.33 blocks a stamp against a declared 2.0), every prediction
     * overshot by about a block, and a residual retired at 0.2 grew without bound — 11.7 blocks, the
     * shown craft running away from the craft; and a craft that stopped was shown 2.77 blocks past it
     * for thirteen ticks. A fraction converges on any steady error and retires a stop's overshoot in a
     * few ticks.</p>
     */
    private void retireResidual() {
        final double length = residualPos.length();
        if (length > 1.0E-9) {
            residualPos.mul(RESIDUAL_SURVIVES_PER_TICK);
        } else {
            residualPos.zero();
        }

        // The ROTATIONAL residual retires by fraction alone — no fixed-angle limit.
        //
        // A cap in radians knows nothing about the LEVER ARM it acts through, and a deck point far
        // from the craft's centre turns that constant into a constant DISTANCE per tick. The one
        // tried here (0.025 rad) produced a 1.6-block step at the body's radius, identically in two
        // unrelated scenarios — the same fifteen digits in both, which is how a constant announces
        // itself where a measurement should be. A fraction cannot do that: it can never move a point
        // further than the error already displaced it.
        final double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(residualRot.w)));
        if (angle > 1.0E-9) {
            residualRot.slerp(new Quaterniond(), 1.0 - RESIDUAL_SURVIVES_PER_TICK).normalize();
        } else {
            residualRot.identity();
        }
    }

    /** Roll the tick-end pair forward: what was this tick's end becomes the previous one's, and the
     *  pose now shown becomes this tick's. Called once per tick, AFTER composing, so a packet that
     *  lands mid-tick cannot make the pair describe the same instant twice. */
    private void captureTickEnd() {
        prevTickEndPos.set(haveShownStep ? tickEndPos : new Vector3d(
                curTickTransform.getPosX(), curTickTransform.getPosY(), curTickTransform.getPosZ()));
        prevTickEndRot.set(haveShownStep ? tickEndRot
                : curTickTransform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL));
        tickEndPos.set(curTickTransform.getPosX(), curTickTransform.getPosY(), curTickTransform.getPosZ());
        tickEndRot.set(curTickTransform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL));
        haveShownStep = true;
    }

    @Override
    public void getShownVelocity(@Nonnull Vector3d outLinear, @Nonnull Vector3d outAngular) {
        // The step the shown pose JUST TOOK, over a tick — not the one it is about to take.
        //
        // Both consumers ask about the past: a body standing on the deck has to be moved by what the
        // deck moved, and the capture guard compares the step that already happened against the
        // carry it is allowed. Answering with the NEXT step instead is only the same number while
        // the craft's motion is steady, and this craft's drive is not: measured on a hard climb, the
        // shown pose stepped 1.10 blocks in a tick while the predicted-next carry read 0.26, and the
        // guard called the difference a teleport.
        //
        // It is still not a reconstruction: this is the interpolator's own record of what it did,
        // exact and known, rather than a rate inferred from watching something move.
        if (!haveShownStep) {
            outLinear.set(linearVelocity);
            outAngular.set(angularVelocity);
            return;
        }
        final double perSecond = 1.0 / SECONDS_PER_TICK;
        outLinear.set((tickEndPos.x - prevTickEndPos.x) * perSecond,
                (tickEndPos.y - prevTickEndPos.y) * perSecond,
                (tickEndPos.z - prevTickEndPos.z) * perSecond);

        final Quaterniond step = new Quaterniond(tickEndRot).mul(new Quaterniond(prevTickEndRot).invert()).normalize();
        final double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(step.w)));
        final double sinHalf = Math.sqrt(Math.max(0.0, 1.0 - step.w * step.w));
        if (angle > 1.0E-9 && sinHalf > 1.0E-12) {
            final double sign = step.w < 0 ? -1.0 : 1.0;
            final double k = sign * angle * perSecond / sinHalf;
            outAngular.set(step.x * k, step.y * k, step.z * k);
        } else {
            outAngular.set(0.0, 0.0, 0.0);
        }
    }

    /** The declared pose carrying whatever is left of the last mispredicted tick. */
    @Nonnull
    private ShipTransform composeShown() {
        final Vector3dc shownPos = new Vector3d(
                declaredTransform.getPosX() + residualPos.x,
                declaredTransform.getPosY() + residualPos.y,
                declaredTransform.getPosZ() + residualPos.z);
        final Quaterniondc declaredRot = declaredTransform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        final Quaterniond shownRot = new Quaterniond(residualRot).mul(declaredRot).normalize();
        return new ShipTransform(shownPos, shownRot, declaredTransform.getCenterCoord());
    }

    @Override
    @Nonnull
    public ShipTransform getCurrentTickTransform() {
        return curTickTransform;
    }

    @Override
    @Nonnull
    public AxisAlignedBB getCurrentAABB() {
        // The received box, carried into the pose actually being shown.
        final Matrix4dc latestToCurrent = curTickTransform.getSubspaceToGlobal()
                .mul(latestReceivedTransform.getGlobalToSubspace(), new Matrix4d());
        return new Polygon(latestReceivedAABB, latestToCurrent).getEnclosedAABB();
    }
}
