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
     * The most the retirement of a residual may add to a single tick's movement, in blocks.
     *
     * <p>Retiring an error is itself movement of the deck, and a big enough one is movement the
     * craft's own motion cannot explain — which is precisely what the body-capture guard is built to
     * notice. Measured: after a 12-tick gap the prediction had drifted 2.72 blocks, half of that
     * went into one tick, and the guard dropped the capture three times over a climb it used to ride
     * cleanly. So the fraction is a rate LIMIT, not a rate: a large error takes more ticks to retire
     * and never arrives as a lurch.</p>
     *
     * <p>The number is the capture guard's own static epsilon (0.2 blocks), deliberately rather than
     * a new invented one: below it the guard cannot tell this retirement from noise, which is the
     * exact property wanted.</p>
     */
    private static final double RESIDUAL_MAX_RETIRED_BLOCKS_PER_TICK = 0.2;


    /**
     * Residual beyond which the pose is adopted outright, in blocks, added to the distance the
     * craft's own declared speed covers in {@link #DISCONTINUITY_TICKS} ticks.
     */
    private static final double DISCONTINUITY_FLOOR_BLOCKS = 4.0;
    private static final double DISCONTINUITY_TICKS = 4.0;

    /** Diagnostic only — nothing branches on these; a test reads them to see what it produced. The
     *  two tick counters answer the question the design turns on: how often a pose actually arrives
     *  for the tick that shows it, which is what decides whether prediction is bridging a gap or
     *  betting against information already in hand. */
    public static volatile double lastResidualBlocks;
    public static volatile double maxResidualBlocks;
    public static volatile long discontinuitiesAdopted;
    public static volatile long ticksShownFromPacket;
    public static volatile long ticksExtrapolated;
    public static volatile double maxShownStepBlocks;

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

    /** Whether a pose arrived for the tick about to be shown, and whether anything was PREDICTED
     *  since the last one — the second is what decides whether a difference on arrival is a
     *  prediction error to retire or simply the craft's own movement. */
    private boolean packetSinceLastTick;
    private boolean extrapolatedSinceLastPacket;

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

    @Override
    public void onNewTransformPacket(@Nonnull ShipTransform newTransform, @Nonnull AxisAlignedBB newAABB,
                                     double linearX, double linearY, double linearZ,
                                     double angularX, double angularY, double angularZ) {
        // How wrong the prediction was: what is being SHOWN, against what the craft says is true.
        final double dx = curTickTransform.getPosX() - newTransform.getPosX();
        final double dy = curTickTransform.getPosY() - newTransform.getPosY();
        final double dz = curTickTransform.getPosZ() - newTransform.getPosZ();
        final double error = Math.sqrt(dx * dx + dy * dy + dz * dz);

        final double declaredSpeed = Math.sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ);
        final double discontinuityAbove =
                DISCONTINUITY_FLOOR_BLOCKS + declaredSpeed * SECONDS_PER_TICK * DISCONTINUITY_TICKS;

        if (!extrapolatedSinceLastPacket) {
            // Nothing was predicted, so there is no prediction error: the previous pose was declared
            // too, and the step between them is simply what the craft did. Taking the difference as
            // a residual and fading it would smear a real movement over the following ticks — and
            // measured, that is not a small effect: with a packet every tick the shown pose wobbled
            // around the true one and a body standing on a rolling deck walked 1.17 blocks across it.
            residualPos.zero();
            residualRot.identity();
        } else if (error > discontinuityAbove) {
            // Not a mispredicted tick — the craft is somewhere else entirely (a teleport, a jump
            // arrival, a load). Fading across that would sweep the deck, and anything standing on
            // it, through the space between.
            residualPos.zero();
            residualRot.identity();
            discontinuitiesAdopted++;
        } else {
            residualPos.set(dx, dy, dz);
            final Quaterniondc curRot = curTickTransform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            final Quaterniondc newRot = newTransform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            // residual = cur * new^-1, left-multiplied, so it composes onto the declared rotation
            residualRot.set(newRot).invert().premul(curRot).normalize();
        }
        lastResidualBlocks = error;
        if (error > maxResidualBlocks) {
            maxResidualBlocks = error;
        }

        this.declaredTransform = newTransform;
        this.latestReceivedTransform = newTransform;
        this.latestReceivedAABB = newAABB;
        this.linearVelocity.set(linearX, linearY, linearZ);
        this.angularVelocity.set(angularX, angularY, angularZ);
        this.extrapolatedSinceLastPacket = false;
        this.packetSinceLastTick = true;
        // The shown pose does not move on arrival: declared + residual is exactly where it already
        // was. That identity is what makes a packet unfelt.
        this.curTickTransform = composeShown();
    }

    @Override
    public void tickTransformInterpolator() {
        // A packet arrived for this tick: its pose IS the craft's state and there is nothing to
        // predict. Extrapolation is for the ticks a packet did not come — a chosen cadence, a late
        // packet, a stalled server — and running it when one did means betting against information
        // already in hand.
        if (packetSinceLastTick) {
            packetSinceLastTick = false;
            ticksShownFromPacket++;
            retireResidual();
            curTickTransform = composeShown();
            captureTickEnd();
            noteShownStep();
            return;
        }
        extrapolatedSinceLastPacket = true;
        ticksExtrapolated++;

        // Nothing came, so the craft keeps doing what it last said it was doing.
        final Vector3d advancedPos = new Vector3d(
                declaredTransform.getPosX() + linearVelocity.x * SECONDS_PER_TICK,
                declaredTransform.getPosY() + linearVelocity.y * SECONDS_PER_TICK,
                declaredTransform.getPosZ() + linearVelocity.z * SECONDS_PER_TICK);
        final Quaterniondc declaredRot = declaredTransform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        final Quaterniond advancedRot = new Quaterniond(declaredRot);
        final double angle = angularVelocity.length() * SECONDS_PER_TICK;
        if (angle > 1.0E-9) {
            final Vector3d axis = new Vector3d(angularVelocity).normalize();
            // World-frame rotation rate, so it composes on the LEFT of the craft's orientation.
            advancedRot.premul(new Quaterniond().fromAxisAngleRad(axis.x, axis.y, axis.z, angle)).normalize();
        }
        declaredTransform = new ShipTransform(advancedPos, advancedRot, declaredTransform.getCenterCoord());

        // And the leftover of a wrong prediction fades rather than being applied as a step.
        retireResidual();

        // Remember where the pose stood, so the step about to be taken can be reported afterwards.
        curTickTransform = composeShown();
        captureTickEnd();
        noteShownStep();
    }

    /**
     * Retire part of the residual — a FRACTION of it, but never more than the cap in one tick.
     *
     * <p>The fraction is what makes a small error vanish quickly; the cap is what stops a large one
     * from arriving as a lurch the craft's own motion cannot account for. Both are needed: without
     * the fraction a tiny residual would take forever, without the cap a 2.72-block one put over a
     * block into a single tick and dropped a body's capture.</p>
     */
    private void retireResidual() {
        final double length = residualPos.length();
        if (length > 1.0E-9) {
            final double retire = Math.min(length * (1.0 - RESIDUAL_SURVIVES_PER_TICK),
                    RESIDUAL_MAX_RETIRED_BLOCKS_PER_TICK);
            residualPos.mul(Math.max(0.0, 1.0 - retire / length));
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

    /** How far the shown pose just moved — the number a body standing on it has to be carried by. */
    private void noteShownStep() {
        final double dx = tickEndPos.x - prevTickEndPos.x;
        final double dy = tickEndPos.y - prevTickEndPos.y;
        final double dz = tickEndPos.z - prevTickEndPos.z;
        final double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (step > maxShownStepBlocks) {
            maxShownStepBlocks = step;
        }
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
