package dev.stannismod.stellurgy.test.unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.joml.Vector3d;
import org.junit.Test;

import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorCommand;
import dev.stannismod.stellurgy.ship.control.ActuatorId;
import dev.stannismod.stellurgy.ship.control.ControlAxis;
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.ControlFrame;
import dev.stannismod.stellurgy.ship.control.ControlScheme;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.MomentumStore;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.ship.control.ShipFlightModel;
import dev.stannismod.stellurgy.ship.control.ShipReadout;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.MassContributor.Kind;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;
import dev.stannismod.stellurgy.test.CleanCommandLaw;
import dev.stannismod.stellurgy.test.ShipMotionCases;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The LAWS of ship-flight-model's kernel, one class for the owner (the contract levels and grouping
 * of the fast-tier rules): each method pins one contract, however much of the kernel checking it
 * needs wired. Its JOINTS with the readout and the mass frame are in
 * {@code integration/ShipMotionJointsTest}.
 *
 * <p>The capability solve's laws come first, on hand-placed hulls in this class's own frame; the
 * scheme's laws after, on {@link ShipMotionCases} hulls built from production's devices.</p>
 *
 * <p>What a hull can do is decided by where its devices are, and these pin the promises that follow:
 * a hull is as strong in a direction as it can be CLEANLY, a sign is not a symmetry, a wheel turns
 * but does not push and cannot hold forever, rotation is an acceleration on this hull's inertia, a
 * combined command never asks a device for more than it has, and the same hull gives the same
 * answer however its devices were listed.
 *
 * <p>The frame throughout: the helm faces +Z, up is +Y, so right is -X. Thrust {@code T} is an
 * arbitrary round number; every verdict is a relationship between figures of one hull.</p>
 */
public class ShipMotionLawsTest {

    private static final double T = 100_000.0D;
    /**
     * Measured 2026-09-30 with this set to zero: 7 of 11 methods pass exactly, and the first verdict
     * to differ in each of the other four is off by 1.5e-16 relative (200000.00000000003 against
     * 200000). Verdicts after a method's first were not reached by that run. So this bar is slack for
     * rounding, seven orders below any error a verdict here exists to catch.
     */
    private static final double EPS = 1.0e-9D;

    /** A constant: a {@code ControlFrame} copies its axes and hands them out read-only. */
    private static final ControlFrame HELM =
            ControlFrame.of(new Vector3d(0, 0, 1), new Vector3d(-1, 0, 0), new Vector3d(0, 1, 0));

    private static ActuatorId id(int x, int y, int z) {
        return new ActuatorId(x, y, z, 0);
    }

    /** A 5x1x5 slab of equal blocks centred on the origin: a hull with an honest inertia. */
    private static ShipMassFrame slab() {
        ShipMassFrameBuilder b = new ShipMassFrameBuilder();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                b.add(MassContributor.ofBlock(x, 0, z, 500.0D, Kind.STRUCTURAL));
            }
        }
        return b.build();
    }

    private static Actuator engine(int x, int y, int z, double fx, double fy, double fz) {
        return Actuator.pointForce(id(x, y, z), new Vector3d(x, y, z), new Vector3d(fx, fy, fz));
    }

    /** A sideways engine sharing a corner with a forward one: a second device in the same block. */
    private static Actuator sideEngine(int x, int z, double fx) {
        return Actuator.pointForce(new ActuatorId(x, 0, z, 1), new Vector3d(x, 0, z), new Vector3d(fx, 0, 0));
    }

    private static List<Actuator> wheel(int x, int y, int z, double torque, double capacity) {
        List<Actuator> out = new ArrayList<>();
        out.add(Actuator.pureTorque(new ActuatorId(x, y, z, 0), new Vector3d(torque, 0, 0), capacity));
        out.add(Actuator.pureTorque(new ActuatorId(x, y, z, 1), new Vector3d(0, torque, 0), capacity));
        out.add(Actuator.pureTorque(new ActuatorId(x, y, z, 2), new Vector3d(0, 0, torque), capacity));
        return out;
    }

    /**
     * The maintainer's own example, and the reason there are twelve directions: two engines aft and
     * one forward, all on the centre line, push forward twice as hard as they brake.
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipCapability#solveOne} at {@code java.util.Arrays.fill(upper, 1.0D);} with every
     * throttle bounded at 0.5 fails "forward is twice" with 100000 against 200000;
     * {@code ShipCapability#solveOne} at {@code if (!direction.isPositive())} with the direction's sign dropped (both senses solve the positive
     * one) fails "braking is the one forward engine" with 200000 against 100000.</p>
     */
    @Test
    public void aSignIsNotASymmetry() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(engine(-1, 0, -2, 0, 0, T));
        hull.add(engine(1, 0, -2, 0, 0, T));
        hull.add(engine(0, 0, 2, 0, 0, -T));
        ShipCapability cap = ShipCapability.solve(hull, slab(), HELM);
        assertEquals("forward is twice the one braking engine: both aft engines, balanced about the centre line",
                2.0D * T, cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), T * EPS);
        assertEquals("braking is the one forward engine",
                T, cap.authority(ControlDirection.SURGE_NEGATIVE, Endurance.SUSTAINED), T * EPS);
    }

    /**
     * Bad geometry is weak, never spinny: an engine off the centre line makes torque with every
     * newton, and with nothing to balance it the hull may not use it for clean forward flight. Raw
     * thrust is two engines; clean thrust is the one on the line.
     *
     * <p>red-witnessed: 2026-09-30 — {@code ShipCapability#solveOne} at {@code a[k][j] = w[k] / (k < 3 ? forceScale : torqueScale);} with the torque rows zeroed in the
     * tableau and {@code ShipCapability#solveOne} at {@code if (forceResidual > AllocationTolerances.FORCE_RESIDUAL * forceScale}'s residual check skipped fails with 200000 against 100000 — the
     * off-centre engine counted as if it pushed straight.</p>
     */
    @Test
    public void anOffCentreEngineIsNotCleanThrust() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(engine(0, 0, -2, 0, 0, T));
        hull.add(engine(2, 0, -2, 0, 0, T));
        ShipCapability cap = ShipCapability.solve(hull, slab(), HELM);
        assertEquals("only the centred engine delivers clean forward thrust",
                T, cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), T * EPS);
    }

    /**
     * A hull's handling follows its cargo. Two engines abreast balance about a centred mass; load the
     * right side and the centre of mass moves toward it, the left engine's lever arm grows, and clean
     * forward thrust falls to the pair's new balance: {@code u_L · 1.5 = u_R · 0.5}, so 4/3 T.
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipCapability#solveOne} at {@code java.util.Arrays.fill(upper, 1.0D);} with every
     * throttle bounded at 0.5 fails "centred, both engines push fully" with 100000 against 200000;
     * {@code Actuator#wrenchAbout} at {@code Vector3d arm = new Vector3d(position).sub(centre);} taking the lever arm about the origin instead of the centre of mass fails
     * "loaded right" with 200000 against 133333.</p>
     */
    @Test
    public void loadingOneSideMovesTheBalance() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(engine(1, 0, -2, 0, 0, T));  // left of the helm: +X is left when right is -X
        hull.add(engine(-1, 0, -2, 0, 0, T)); // right
        ShipMassFrame centred = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0, 0, 0, 1000.0D, Kind.STRUCTURAL)).build();
        ShipMassFrame loadedRight = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0, 0, 0, 1000.0D, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(-1, 0, 0, 1000.0D, Kind.CONTENT)).build();
        assertEquals("centred, both engines push fully",
                2.0D * T, ShipCapability.solve(hull, centred, HELM)
                        .authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), T * EPS);
        assertEquals("loaded right, the balance is u_L*1.5 = u_R*0.5",
                4.0D / 3.0D * T, ShipCapability.solve(hull, loadedRight, HELM)
                        .authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), T * EPS);
    }

    /**
     * A reaction wheel turns the hull and pushes it nowhere, and it cannot hold a turn forever: its
     * authority is BURST, with an endurance of exactly capacity over torque.
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code Actuator#wrenchAbout} at {@code Vector3d torque = arm.cross(maxForce, new Vector3d()).add(maxTorque);} turning a wheel's
     * torque into a force at its position fails "a wheel pushes nowhere" on SURGE_POSITIVE with
     * 5000; {@code Actuator#isSustained} at {@code return Double.isInfinite(momentumCapacity);} marking every device sustained fails "a wheel alone holds nothing
     * forever" on ROLL_POSITIVE with 0.18; {@code ShipCapability#solve} at {@code : solveOne(fixed, mass, frame, d, false);} solving the burst figure over
     * sustained devices only fails "but it turns the hull" on ROLL_POSITIVE; {@code ShipCapability#solveOne} at {@code double rate = Math.abs(throttles[i]) * act.maxTorque().length();}
     * taking the endurance from the throttle alone fails the seconds with 20000 against 4.</p>
     */
    @Test
    public void aWheelTurnsButDoesNotPush() {
        double torque = 5_000.0D;
        double capacity = 20_000.0D;
        ShipCapability cap = ShipCapability.solve(wheel(0, 1, 0, torque, capacity), slab(), HELM);
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation()) {
                assertEquals(d + ": a wheel alone holds nothing forever",
                        0.0D, cap.authority(d, Endurance.SUSTAINED), 0.0D);
                assertTrue(d + ": but it turns the hull for a while",
                        cap.authority(d, Endurance.BURST) > 0.0D);
            } else {
                assertEquals(d + ": a wheel pushes nowhere",
                        0.0D, cap.authority(d, Endurance.BURST), 0.0D);
            }
        }
        // the slab is symmetric about Y, so pure yaw uses the Y wheel alone, at full torque
        assertEquals("yaw endurance is capacity / torque",
                capacity / torque, cap.burstSeconds(ControlDirection.YAW_POSITIVE), EPS);
    }

    /**
     * The wheel's one job in translation: an off-centre engine plus a wheel to null its torque gives
     * clean forward thrust — for the seconds the wheel can absorb the moment, and not in general.
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipCapability#solve} at {@code : solveOne(fixed, mass, frame, d, true);} solving the
     * sustained figure over every device fails "none of it is sustained" with 100000;
     * {@code ShipCapability#solve} at {@code : solveOne(fixed, mass, frame, d, false);} solving the burst figure over sustained devices only fails "a wheel
     * buys the full engine" with 0.0; {@code ShipCapability#solveOne} at {@code double rate = Math.abs(throttles[i]) * act.maxTorque().length();} taking the endurance from the
     * throttle alone fails the seconds with 2000000 against 5.</p>
     */
    @Test
    public void aWheelBuysCleanThrustForAWhile() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(engine(2, 0, -2, 0, 0, T)); // off the centre line: torque about Y of 2T
        hull.addAll(wheel(0, 1, 0, 4.0D * T, 10.0D * T));
        ShipCapability cap = ShipCapability.solve(hull, slab(), HELM);
        assertEquals("none of it is sustained",
                0.0D, cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), 0.0D);
        assertEquals("a wheel buys the full engine for a while",
                T, cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.BURST), T * EPS);
        assertEquals("for capacity over the moment it nulls: 10T / 2T",
                5.0D, cap.burstSeconds(ControlDirection.SURGE_POSITIVE), EPS);
    }

    /**
     * Rotation is an acceleration on THIS hull: the same devices turn a hull with twice the inertia
     * half as fast, while the torque behind it is the same.
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipCapability#solveOne} at {@code Vector3d t = mass.getInertia().transform(new Vector3d(axis));} using the bare
     * axis instead of {@code I · axis} fails "half as fast" with 5000 against 2500;
     * {@code ShipCapability#torque} at {@code return authority(direction, endurance) * inertiaAlong(frame.axis(direction.axis())).length();} reporting the torque without the inertia fails "the same torque"
     * with 0.46 against 0.92.</p>
     */
    @Test
    public void rotationIsAnAccelerationOnThisHull() {
        List<Actuator> wheels = wheel(0, 0, 0, 5_000.0D, 1.0e9D);
        ShipMassFrameBuilder light = new ShipMassFrameBuilder();
        ShipMassFrameBuilder heavy = new ShipMassFrameBuilder();
        for (int x = -2; x <= 2; x++) {
            light.add(MassContributor.ofBlock(x, 0, 0, 500.0D, Kind.STRUCTURAL));
            heavy.add(MassContributor.ofBlock(x, 0, 0, 1000.0D, Kind.STRUCTURAL));
        }
        ShipCapability l = ShipCapability.solve(wheels, light.build(), HELM);
        ShipCapability h = ShipCapability.solve(wheels, heavy.build(), HELM);
        assertEquals("twice the inertia turns half as fast",
                l.authority(ControlDirection.YAW_POSITIVE, Endurance.BURST) / 2.0D,
                h.authority(ControlDirection.YAW_POSITIVE, Endurance.BURST), EPS);
        assertEquals("behind the same torque",
                l.torque(ControlDirection.YAW_POSITIVE, Endurance.BURST),
                h.torque(ControlDirection.YAW_POSITIVE, Endurance.BURST), EPS * 5_000.0D);
    }

    /**
     * Forward, right and yaw together, each within its own authority but more than the shared
     * engines can give at once: no device is driven past full, and what the hull delivers keeps the
     * proportions asked for.
     *
     * <p>The hull is four corner engines pushing forward and four pushing right, so forward and right
     * share nothing but yaw shares everything. Asked for 80% forward, 80% right, 80% yaw, the recipes
     * sum past one and the whole command is scaled.</p>
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — "no device past full" is held by TWO
     * mechanisms — {@code CleanAxisScheme#allocate} at {@code if (lambda < 1.0D)}'s global factor and {@code MomentumStore#liveRange} at {@code range[i][1] = 1.0D;}'s live
     * range — and only both broken fail it, with 1.6; {@code CleanAxisScheme#allocate} at {@code if (lambda < 1.0D)} with the saturation
     * flag not raised fails "reports saturation"; {@code CleanAxisScheme#allocate} at {@code if (lambda < 1.0D)} with the global factor
     * skipped (the live range then clips) fails "the proportions asked for" with 3.2 against 2.0, and
     * so does {@code CleanAxisScheme#allocate} at {@code u[i] *= lambda;} clipping each over-full device instead of scaling the command.</p>
     */
    @Test
    public void aCombinedCommandIsScaledNotClipped() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(engine(2, 0, -2, 0, 0, T));
        hull.add(engine(-2, 0, -2, 0, 0, T));
        hull.add(engine(2, 0, 2, 0, 0, T));
        hull.add(engine(-2, 0, 2, 0, 0, T));
        hull.add(sideEngine(2, 2, -T));
        hull.add(sideEngine(2, -2, -T));
        hull.add(sideEngine(-2, 2, T));
        hull.add(sideEngine(-2, -2, T));
        ShipMassFrame mass = slab();
        ShipCapability cap = ShipCapability.solve(hull, mass, HELM);
        double m = mass.getTotalMass();
        double surge = cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED);
        double sway = cap.authority(ControlDirection.SWAY_POSITIVE, Endurance.SUSTAINED);
        double yaw = cap.authority(ControlDirection.YAW_POSITIVE, Endurance.SUSTAINED);
        Vector3d accel = new Vector3d(0, 0, 0.8D * surge / m).add(new Vector3d(-0.8D * sway / m, 0, 0));
        Vector3d alpha = new Vector3d(0, 0.8D * yaw, 0);
        ActuatorCommand cmd = ControlScheme.cleanAxes().allocate(cap, accel, alpha, new MomentumStore(), 0.05D);
        for (int i = 0; i < hull.size(); i++) {
            assertTrue("no device past full: #" + i + " at " + cmd.throttle(i), cmd.throttle(i) <= 1.0D + EPS);
        }
        assertTrue("the command was more than the hull had, so it reports saturation", cmd.isSaturated());
        double deliveredSurge = cmd.force().z();
        double deliveredSway = -cmd.force().x();
        assertEquals("the proportions asked for: forward over right as asked",
                surge / sway, deliveredSurge / deliveredSway, EPS);
    }

    /**
     * A wheel that has given all the momentum it can hold gives no more in that sense, and is free
     * in the other. Conservation, booked per device.
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code MomentumStore#liveRange} at {@code range[i][1] = Math.max(0.0D, Math.min(1.0D, (cap - now) / rate));} closing the
     * positive sense outright fails "fresh, the wheel gives its full torque" with 0.0;
     * {@code MomentumStore#liveRange} at {@code double now = given(a.id());} ignoring the booked momentum fails "a full wheel turns nothing more"
     * with 5000; {@code CleanAxisScheme#allocate} at {@code if (u[i] > live[i][1])} not raising saturation on the live clip fails "says it
     * delivered less"; {@code MomentumStore#liveRange} at {@code range[i][0] = Math.min(0.0D, Math.max(a.minThrottle(), (-cap - now) / rate));} closing the negative sense fails "but it can unwind"
     * with 0.0.</p>
     */
    @Test
    public void aFullWheelTurnsNothingMore() {
        double torque = 5_000.0D;
        double capacity = 1_000.0D; // 0.2 s of full torque
        ShipCapability cap = ShipCapability.solve(wheel(0, 0, 0, torque, capacity), slab(), HELM);
        MomentumStore store = new MomentumStore();
        ControlScheme scheme = ControlScheme.cleanAxes();
        Vector3d wantYaw = new Vector3d(0, cap.authority(ControlDirection.YAW_POSITIVE, Endurance.BURST), 0);
        ActuatorCommand first = scheme.allocate(cap, new Vector3d(), wantYaw, store, 0.1D);
        assertEquals("fresh, the wheel gives its full torque", torque, first.torque().y(), torque * EPS);
        scheme.allocate(cap, new Vector3d(), wantYaw, store, 0.1D); // now full: 0.2 s at 5000
        ActuatorCommand full = scheme.allocate(cap, new Vector3d(), wantYaw, store, 0.1D);
        assertEquals("a full wheel turns nothing more", 0.0D, full.torque().y(), torque * EPS);
        assertTrue("and says it delivered less than asked", full.isSaturated());
        ActuatorCommand back = scheme.allocate(cap, new Vector3d(), wantYaw.negate(new Vector3d()), store, 0.1D);
        assertEquals("but it can unwind", -torque, back.torque().y(), torque * EPS);
    }

    /**
     * The answer is a function of the hull, not of the order its devices were found in: shuffling
     * the list gives the identical recipes, to the last bit. Otherwise a relog, or the other side of
     * the connection, would fly a different ship.
     *
     * <p>red-witnessed: 2026-09-30 — {@code ShipCapability#solve} at {@code sorted.sort((a, b) -> a.id().compareTo(b.id()));} with the sort removed fails on the
     * first shuffle, SURGE_POSITIVE BURST, with a different set of throttles.</p>
     */
    @Test
    public void theAnswerDoesNotDependOnListingOrder() {
        Random rng = new Random(7);
        List<Actuator> hull = randomHull(rng, 10, true);
        ShipMassFrame mass = slab();
        ShipCapability reference = ShipCapability.solve(hull, mass, HELM);
        for (int shuffle = 0; shuffle < 5; shuffle++) {
            List<Actuator> copy = new ArrayList<>(hull);
            Collections.shuffle(copy, new Random(shuffle));
            ShipCapability again = ShipCapability.solve(copy, mass, HELM);
            for (ControlDirection d : ControlDirection.values()) {
                for (Endurance e : Endurance.values()) {
                    assertEquals(d + " " + e + " after shuffle " + shuffle,
                            reference.explain(d, e), again.explain(d, e));
                }
            }
        }
    }

    /**
     * The constraints are the contract, over hulls nobody designed: on 500 random layouts every
     * direction's recipe keeps every throttle in its range and does ONLY what it names — the
     * wrench it produces, read back through a combined command, has no component off its axis.
     *
     * <p>Read through the public scheme on purpose: a one-axis demand at exactly the axis's
     * authority is the recipe itself, so this checks the delivered wrench and not the solver's own
     * bookkeeping.</p>
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code ShipCapability#solveOne} at {@code return new Recipe(throttles, s, seconds);} negating the
     * authority fails "never negative"; "throttle in range" is held by the solver's bound
     * ({@code ShipCapability#solveOne} at {@code java.util.Arrays.fill(upper, 1.0D);}, {@code ShipCapability#solveOne} at {@code double x = Math.max(0.0D, Math.min(1.0D, result.x[j]));}), the scheme's global factor ({@code CleanAxisScheme#allocate} at {@code u[i] *= lambda;})
     * and the live range ({@code MomentumStore#liveRange} at {@code range[i][1] = 1.0D;}) — all four broken fail it with 2.0 on layout 0;
     * {@code ShipCapability#solveOne} at {@code a[k][j] = w[k] / (k < 3 ? forceScale : torqueScale);} dropping the sideways-force row with {@code ShipCapability#solveOne} at {@code if (forceResidual > AllocationTolerances.FORCE_RESIDUAL * forceScale}'s check skipped
     * fails "nothing off its axis, force" with 4.3e4 on layout 1; the same two lines zeroing the torque
     * rows fail "nothing off its axis, torque" with 2.5e5 on layout 0; {@code ShipCapability#solve} at {@code boolean massless = !(mass.getTotalMass() > AllocationTolerances.MASS_EPSILON);}
     * treating every hull as massless (zero authority everywhere, so every check above is skipped)
     * fails "must not be an empty sample" with 0 of 6000. Healthy, measured 2026-09-30: 3470 recipes
     * checked.</p>
     */
    @Test
    public void everyRecipeStaysInRangeAndDoesOnlyWhatItNames() {
        Random rng = new Random(20260930L);
        ShipMassFrame mass = slab();
        double worstForce = 0.0D;
        double worstTorque = 0.0D;
        int checked = 0;
        for (int layout = 0; layout < 500; layout++) {
            List<Actuator> hull = randomHull(rng, 1 + rng.nextInt(14), rng.nextBoolean());
            ShipCapability cap = ShipCapability.solve(hull, mass, HELM);
            for (ControlDirection d : ControlDirection.values()) {
                double authority = cap.authority(d, Endurance.BURST);
                assertTrue(d + " authority is never negative", authority >= 0.0D);
                if (authority == 0.0D) {
                    continue;
                }
                checked++;
                Vector3d axis = new Vector3d(HELM.axis(d.axis()));
                if (!d.isPositive()) {
                    axis.negate();
                }
                Vector3d accel = new Vector3d();
                Vector3d alpha = new Vector3d();
                if (d.axis().isRotation()) {
                    alpha.set(axis).mul(authority);
                } else {
                    accel.set(axis).mul(authority / mass.getTotalMass());
                }
                MomentumStore unlimited = new MomentumStore();
                ActuatorCommand cmd = ControlScheme.cleanAxes().allocate(cap, accel, alpha, unlimited, 0.0D);
                for (int i = 0; i < cap.actuators().size(); i++) {
                    double u = cmd.throttle(i);
                    double min = cap.actuators().get(i).minThrottle();
                    assertTrue("layout " + layout + " " + d + ": throttle in range, got " + u,
                            u >= min - EPS && u <= 1.0D + EPS);
                }
                Vector3d offForce;
                Vector3d offTorque;
                if (d.axis().isRotation()) {
                    offForce = new Vector3d(cmd.force());
                    Vector3d wanted = mass.getInertia().transform(new Vector3d(axis)).mul(authority);
                    offTorque = new Vector3d(cmd.torque()).sub(wanted);
                } else {
                    offForce = new Vector3d(cmd.force()).sub(new Vector3d(axis).mul(authority));
                    offTorque = new Vector3d(cmd.torque());
                }
                // The bars are production's own tolerance (AllocationTolerances, 1e-6 of the largest
                // column) carried into these units: forces are bounded by the largest device figure,
                // and a torque column by that figure times the longest lever arm on this slab, under
                // 4 m — hence the x2 and x8 slack. Measured 2026-09-30 over these 500 layouts: worst
                // residual 1.9e-15 (force) and 6.6e-16 (torque) of that scale.
                double scale = largestForce(hull);
                worstForce = Math.max(worstForce, offForce.length() / scale);
                worstTorque = Math.max(worstTorque, offTorque.length() / (scale * 4.0D));
                assertTrue("layout " + layout + " " + d + ": nothing off its axis, force residual "
                        + offForce.length(), offForce.length() <= 1.0e-6D * scale * 2.0D);
                assertTrue("layout " + layout + " " + d + ": nothing off its axis, torque residual "
                        + offTorque.length(), offTorque.length() <= 1.0e-6D * scale * 8.0D);
            }
        }
        System.out.println("[capability] worst relative residual over 500 layouts: force "
                + worstForce + ", torque " + worstTorque + ", recipes checked " + checked);
        // Every verdict above is skipped for a direction the hull cannot deliver, so an allocator
        // that answered zero everywhere would pass them all. The sample must not be empty.
        assertTrue("the recipes checked must not be an empty sample: " + checked
                + " of " + (500 * ControlDirection.values().length), checked > 0);
    }

    private static double largestForce(List<Actuator> hull) {
        double s = 0.0D;
        for (Actuator a : hull) {
            s = Math.max(s, Math.max(a.maxForce().length(), a.maxTorque().length()));
        }
        return s;
    }

    /** Engines on the slab's footprint, each pushing along one of the six block faces; maybe a wheel. */
    private static List<Actuator> randomHull(Random rng, int engines, boolean withWheel) {
        List<Actuator> hull = new ArrayList<>();
        List<ActuatorId> used = new ArrayList<>();
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (hull.size() < engines) {
            int x = rng.nextInt(5) - 2;
            int y = rng.nextInt(3) - 1;
            int z = rng.nextInt(5) - 2;
            ActuatorId aid = id(x, y, z);
            if (used.contains(aid)) {
                continue;
            }
            used.add(aid);
            int[] f = faces[rng.nextInt(6)];
            double thrust = T * (0.5D + rng.nextDouble());
            hull.add(Actuator.pointForce(aid, new Vector3d(x, y, z),
                    new Vector3d(f[0] * thrust, f[1] * thrust, f[2] * thrust)));
        }
        if (withWheel) {
            hull.addAll(wheel(0, 2, 0, T * rng.nextDouble(), 1.0e6D));
        }
        return hull;
    }

    /**
     * A spent wheel does not leave the hull tumbling: while the wheel can absorb the moment, the burst
     * recipe fires the off-centre engine too and pushes 2T cleanly; once the wheel is full in the
     * sense that recipe needs, the command falls back to the sustained recipe — the centred engine
     * alone, T, still clean — instead of firing the off-centre engine with nothing nulling its moment.
     *
     * <p>Found 2026-09-30 by the e2e pass: a laden craft commanded forward past its sustained figure
     * tumbled (ω to 1.7 rad/s) once its wheel had been spent.</p>
     *
     * <p>red-witnessed: one break per verdict, 2026-09-30 — {@code CleanAxisScheme#allocate} at {@code recipe = burstIfItCanDeliver(capability, direction, recipe, amount, u, live);} (taken on the form that passed {@code momentum, dt}, before the share check of 2026-10-07) taking the burst
     * recipe unconditionally (the form before the fix) fails "a spent wheel leaves the push clean" with
     * a torque of 2e5; {@code CleanAxisScheme#burstIfItCanDeliver} at {@code return sustained;} falling back to no recipe at all fails "delivers the
     * sustained figure"; {@code CleanAxisScheme#allocate} at {@code fraction = 1.0D; saturated = true;} not raising saturation on the per-axis clip fails
     * "says it delivered less".</p>
     */
    @Test
    public void aSpentWheelFallsBackToTheCleanRecipe() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(engine(0, 0, -2, 0, 0, T));
        hull.add(engine(2, 0, -2, 0, 0, T)); // off-centre: a moment of 2T about Y the wheel must null
        hull.addAll(wheel(0, 1, 0, 4.0D * T, 10.0D * T));
        ShipMassFrame mass = slab();
        ShipCapability cap = ShipCapability.solve(hull, mass, HELM);
        double want = 2.0D * T / mass.getTotalMass();
        ControlScheme scheme = ControlScheme.cleanAxes();

        ActuatorCommand fresh = scheme.allocate(cap, new Vector3d(0, 0, want), new Vector3d(), new MomentumStore(), 0.05D);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the control leg: a fresh wheel lets the burst recipe push 2T (got " + fresh.force().z() + ")",
                Math.abs(fresh.force().z() - 2.0D * T) < T * EPS && fresh.torque().length() < T * EPS);

        MomentumStore full = new MomentumStore();
        for (int i = 0; i < cap.actuators().size(); i++) {
            Actuator a = cap.actuators().get(i);
            if (!a.isSustained()) {
                full.restore(a.id(), a.momentumCapacity()); // full in the positive sense on every axis
            }
        }
        ActuatorCommand spent = scheme.allocate(cap, new Vector3d(0, 0, want), new Vector3d(), full, 0.05D);
        assertEquals("a spent wheel leaves the push clean", 0.0D, spent.torque().length(), T * EPS);
        assertEquals("and delivers the sustained figure: the centred engine alone",
                cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED), spent.force().z(), T * EPS);
        assertTrue("and says it delivered less than asked", spent.isSaturated());
    }

    /**
     * Side engines at the four corners, pushing across the hull: a clean couple about yaw in both
     * senses, 4T of sustained torque each way, and no clean push at all. The hull the desaturation
     * scenarios unload a wheel on.
     */
    private static List<Actuator> yawCouple() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(sideEngine(2, 2, -T));
        hull.add(sideEngine(2, -2, -T));
        hull.add(sideEngine(-2, 2, T));
        hull.add(sideEngine(-2, -2, T));
        return hull;
    }

    /** The index, in {@code cap}'s allocation order, of the device named {@code id}. */
    private static int indexOf(ShipCapability cap, ActuatorId id) {
        for (int i = 0; i < cap.actuators().size(); i++) {
            if (cap.actuators().get(i).id().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("no device " + id + " in " + cap.actuators());
    }

    /**
     * An idle wheel is given back its momentum by the thrusters, and the hull feels none of it: with
     * nothing commanded, a yaw wheel holding 2.53 s of its own torque is run back to empty — every step
     * nearer, never past — while the net force and torque on the hull stay at zero. The sustained
     * couple cancels what the unwinding wheel would do to the hull.
     *
     * <p>The negative half, in the same method: on a hull with NOTHING but wheels — no sustained
     * torque to cancel the unwinding with — the wheel keeps what it holds, because unloading it would
     * turn the hull.</p>
     *
     * <p>red-witnessed: one break per verdict, 2026-10-05, after a healthy run of the class —
     * {@code CleanAxisScheme#allocate} at {@code desaturate(capability, u, momentum, dt);} removed fails
     * "step 0: every step brings it nearer empty: 505999.99999999994 -> 505999.99999999994";
     * {@code CleanAxisScheme#desaturate} at {@code delta[k] += holding[k];} dropped (the wheel unwinds
     * with nothing cancelling it) fails "step 0: and turned about nothing" with 200000;
     * {@code CleanAxisScheme#desaturate} at {@code Math.min(1.0D, -held / rate))} unwinding at full
     * torque whatever is left fails "step 50: the wheel never unloads past empty: -4000";
     * {@code CleanAxisScheme#desaturate} at {@code if (holding == null)} unloading with nothing to
     * cancel it fails "a wheel nothing can hold the hull against keeps what it holds" with 495999.99
     * against 505999.99.</p>
     */
    @Test
    public void anIdleWheelIsGivenBackByTheThrustersWithoutTurningTheHull() {
        double torque = 2.0D * T; // inside the couple's 4T, so the thrusters can cancel all of it
        List<Actuator> hull = yawCouple();
        hull.addAll(wheel(0, 1, 0, torque, 10.0D * T));
        ShipCapability cap = ShipCapability.solve(hull, slab(), HELM);
        ActuatorId yawWheel = new ActuatorId(0, 1, 0, 1);
        // 2.53 s of full torque, well inside capacity — and not a whole number of 0.05 s steps, so the
        // last step must stop at empty rather than land on it by arithmetic.
        double held = 2.53D * torque;
        MomentumStore store = new MomentumStore();
        store.restore(yawWheel, held);
        ControlScheme scheme = ControlScheme.cleanAxes();

        double before = held;
        boolean emptied = false;
        for (int step = 0; step < 1000 && !emptied; step++) {
            ActuatorCommand idle = scheme.allocate(cap, new Vector3d(), new Vector3d(), store, 0.05D);
            double now = store.given(yawWheel);
            assertEquals("step " + step + ": the hull is pushed nowhere while the wheel unloads",
                    0.0D, idle.force().length(), T * EPS);
            assertEquals("step " + step + ": and turned about nothing",
                    0.0D, idle.torque().length(), T * EPS);
            assertTrue("step " + step + ": the wheel never unloads past empty: " + now, now >= 0.0D);
            assertTrue("step " + step + ": every step brings it nearer empty: " + before + " -> " + now,
                    now < before);
            before = now;
            emptied = now == 0.0D;
        }
        assertTrue("the idle wheel is emptied, ending at " + before, emptied);

        // NEGATIVE HALF: nothing aboard can cancel the unwinding, so the wheel keeps its momentum.
        ShipCapability wheelsOnly = ShipCapability.solve(wheel(0, 1, 0, torque, 10.0D * T), slab(), HELM);
        MomentumStore kept = new MomentumStore();
        kept.restore(yawWheel, held);
        ActuatorCommand alone = scheme.allocate(wheelsOnly, new Vector3d(), new Vector3d(), kept, 0.05D);
        assertEquals("a wheel nothing can hold the hull against keeps what it holds",
                held, kept.given(yawWheel), 0.0D);
        assertEquals("and the hull is left alone", 0.0D, alone.torque().length(), 0.0D);
    }

    /**
     * A wheel the command is using is not unloaded under it: a yaw command past the couple's
     * sustained figure leans on the wheel, and the throttles it gets are the same whether the wheel
     * starts empty or half full — unloading does not fight a burst.
     *
     * <p>The positive half, in the same method: the same half-full wheel with NOTHING commanded is
     * unloaded, so the equality above is not a hull on which nothing ever unloads.</p>
     *
     * <p>red-witnessed: 2026-10-05 — {@code CleanAxisScheme#desaturate} at
     * {@code if (wheel.isSustained() || u[i] != 0.0D)} unloading a wheel the command uses fails "device
     * #1 gets the same throttle whatever the wheel already holds" with 1.0 against 0.9; with
     * {@code CleanAxisScheme#allocate} at {@code desaturate(capability, u, momentum, dt);} removed the
     * positive half fails "with nothing commanded the same wheel is unloaded: 500000.0".</p>
     */
    @Test
    public void aWheelInUseIsNotUnloadedUnderTheCommand() {
        double torque = 2.0D * T;
        List<Actuator> hull = yawCouple();
        hull.addAll(wheel(0, 1, 0, torque, 10.0D * T));
        ShipCapability cap = ShipCapability.solve(hull, slab(), HELM);
        ActuatorId yawWheel = new ActuatorId(0, 1, 0, 1);
        int wheelIndex = indexOf(cap, yawWheel);
        double half = 5.0D * T;
        ControlScheme scheme = ControlScheme.cleanAxes();
        Vector3d burstYaw = new Vector3d(0,
                0.9D * cap.authority(ControlDirection.YAW_POSITIVE, Endurance.BURST), 0);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the command must be past the sustained yaw, or it does not use the wheel",
                burstYaw.y > cap.authority(ControlDirection.YAW_POSITIVE, Endurance.SUSTAINED));

        ActuatorCommand fromEmpty = scheme.allocate(cap, new Vector3d(), burstYaw, new MomentumStore(), 0.05D);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the burst must lean on the yaw wheel", fromEmpty.throttle(wheelIndex) != 0.0D);
        MomentumStore halfFull = new MomentumStore();
        halfFull.restore(yawWheel, half);
        ActuatorCommand fromHalf = scheme.allocate(cap, new Vector3d(), burstYaw, halfFull, 0.05D);
        for (int i = 0; i < cap.actuators().size(); i++) {
            assertEquals("device #" + i + " gets the same throttle whatever the wheel already holds",
                    fromEmpty.throttle(i), fromHalf.throttle(i), EPS);
        }

        // POSITIVE HALF: the same half-full wheel, nothing commanded, IS unloaded.
        MomentumStore idle = new MomentumStore();
        idle.restore(yawWheel, half);
        scheme.allocate(cap, new Vector3d(), new Vector3d(), idle, 0.05D);
        assertTrue("with nothing commanded the same wheel is unloaded: " + idle.given(yawWheel),
                idle.given(yawWheel) < half);
    }

    /**
     * A hull with no mass is not a hull that can be given an acceleration.
     *
     * <p>red-witnessed: 2026-09-30 — {@code ShipCapability#solve} at {@code boolean massless = !(mass.getTotalMass() > AllocationTolerances.MASS_EPSILON);} with the massless guard removed fails
     * "no mass, no authority".</p>
     *
     * <p>The control's own witness: with {@code ShipCapability#solve} at {@code boolean massless = !(mass.getTotalMass() > AllocationTolerances.MASS_EPSILON);}
     * answering massless for every hull, the scenario stops at "the same engine on a hull with mass
     * must have authority" — the verdict alone would have stayed green — 2026-10-04.</p>
     */
    @Test
    public void aMasslessHullHasNoAuthority() {
        List<Actuator> hull = new ArrayList<>();
        hull.add(engine(0, 0, -2, 0, 0, T));
        // CONTROL, same engine: on a hull with mass it pushes. Without this an allocator that answered
        // nothing for every hull would pass the verdict below.
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the same engine on a hull with mass must have authority",
                ShipCapability.solve(hull, slab(), HELM)
                        .authority(ControlDirection.SURGE_POSITIVE, Endurance.BURST) > 0.0D);
        ShipCapability cap = ShipCapability.solve(hull, ShipMassFrame.empty(), HELM);
        assertFalse("no mass, no authority",
                cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.BURST) > 0.0D);
    }

    // ---- 1. the law, everywhere -------------------------------------------------------------------

    /** How many generated hulls the sweep solves. */
    private static final int HULLS = 300;

    /** How many commands each hull is given, each from its own wheel state. */
    private static final int COMMANDS = 12;

    /** How many physics steps each command is held: long enough to carry a wheel across its range. */
    private static final int HOLD_STEPS = 90;

    /**
     * Every hull, every wheel state, every command, every step: the command is delivered cleanly, or
     * less and said so.
     *
     * <p>Generated hulls (seeded) carry 1 to 14 motors anywhere on a 5×3×5 frame, facing any way, and
     * usually a wheel. Each command asks for one to three axes at once, at a multiple of the axis's
     * authority from {@link ShipMotionCases#demand}, and is held for {@link #HOLD_STEPS} steps from a wheel state that
     * is empty, full in either sense, short of full by LESS than one step's worth, or anywhere between —
     * the in-between is where a burst recipe's wheel runs out mid-step, which is the state an in-world
     * kick of a laden craft came from (2026-10-07) and the one an endpoint-only test never visits.</p>
     *
     * <p>Contract: this fails if the clean-axis scheme ever delivers a component nobody asked for, more
     * than was asked, the wrong sign, less without saying so, a throttle out of range or a wheel past
     * its capacity.</p>
     *
     * <p>red-witnessed: 2026-10-07, on the form before the share check — {@code
     * CleanAxisScheme#burstIfItCanDeliver} refusing a burst only for a wheel with NO room — fails
     * "generated hull #1, command 0 (SURGE_NEGATIVE×4.0 SWAY_POSITIVE×0.3 YAW_NEGATIVE×1.5), step 1:
     * ROLL was not asked for and moved anyway … 0.0823" (the wheel had 5.5e-4 of a step left).</p>
     * <p>red-witnessed: 2026-10-07 — {@code CleanAxisScheme#burstIfItCanDeliver} at {@code double next
     * = committed[i] + t;} reading {@code t} alone (each axis checked against the wheel as if no other
     * axis had taken from it) fails "generated hull #3, command 3 (YAW_NEGATIVE×1.0 PITCH_NEGATIVE×1.5
     * HEAVE_POSITIVE×1.0), step 14: PITCH delivered the wrong way — asked -7.935, got 0.0095".</p>
     */
    @Test
    public void everyCommandIsDeliveredCleanlyOrLessAndSaidSo() {
        Random rng = new Random(0x5EED609L);
        ControlScheme scheme = ControlScheme.cleanAxes();
        int steps = 0;
        int saturatedSteps = 0;
        int sliverCalls = 0;
        int sharedWheelCalls = 0;
        int wheelSteps = 0;
        for (int h = 0; h < HULLS; h++) {
            ShipMotionCases.Hull hull = ShipMotionCases.randomHull(rng, "generated hull #" + h);
            ShipCapability cap = ShipCapability.solve(hull.actuators, hull.mass, ShipMotionCases.HELM);
            for (int k = 0; k < COMMANDS; k++) {
                MomentumStore momentum = new MomentumStore();
                ShipMotionCases.randomWheelState(rng, cap, momentum);
                Vector3d lin = new Vector3d();
                Vector3d ang = new Vector3d();
                String asked = ShipMotionCases.randomCommand(rng, cap, lin, ang);
                int axesAsked = asked.split(" ").length;
                for (int s = 0; s < HOLD_STEPS; s++) {
                    String before = CleanCommandLaw.wheels(cap, momentum);
                    boolean inLastStep = ShipMotionCases.aWheelIsInsideItsLastStep(cap, momentum);
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, momentum, ShipMotionCases.DT);
                    try {
                        CleanCommandLaw.requireHonest(hull.name + ", command " + k + " (" + asked + "), step " + s,
                                cap, lin, ang, c, momentum);
                    } catch (AssertionError broken) {
                        throw new AssertionError(broken.getMessage() + " | saturated " + c.isSaturated()
                                + " | wheels before the step: " + before + " | after: " + CleanCommandLaw.wheels(cap, momentum)
                                + " | wheel throttles: " + CleanCommandLaw.wheelThrottles(cap, c), broken);
                    }
                    steps++;
                    if (c.isSaturated()) {
                        saturatedSteps++;
                    }
                    if (CleanCommandLaw.usesAWheel(cap, c)) {
                        wheelSteps++;
                        if (inLastStep) {
                            sliverCalls++;
                        }
                        if (axesAsked > 1) {
                            sharedWheelCalls++;
                        }
                    }
                }
            }
        }
        System.out.println("[kernel] steps " + steps + ", saturated " + saturatedSteps
                + ", using a wheel " + wheelSteps + ", of them inside a wheel's last step " + sliverCalls
                + ", with more than one axis asked " + sharedWheelCalls);
        // The law above holds trivially on a sweep that never saturated, never used a wheel, never
        // drew on a wheel inside its last step, or never had two axes sharing one — counted from the
        // state the scheme was called in, not from what the generator meant to draw.
        requireArranged("the sweep must visit the states the law is about — saturated " + saturatedSteps
                        + " of " + steps + ", wheel calls " + wheelSteps + ", of them inside a wheel's last step "
                        + sliverCalls + " (at least " + MIN_REGION_CALLS + "), sharing a wheel between axes "
                        + sharedWheelCalls + " (at least " + MIN_REGION_CALLS + ")",
                saturatedSteps > 0 && saturatedSteps < steps && sliverCalls >= MIN_REGION_CALLS
                        && sharedWheelCalls >= MIN_REGION_CALLS);
    }

    /**
     * The fewest calls the sweep must make in each of its two hard regions — a wheel drawn on inside its
     * last step, and a wheel shared by several asked axes. One per hull on average: about 70% of hulls
     * carry a wheel, each gets {@value #COMMANDS} commands, and each wheel axis starts inside its last
     * step with probability 1/3 ({@link ShipMotionCases#randomWheelState}), so starts alone are expected near
     * 300 × 0.7 × 12 × (1 − (2/3)³) ≈ 1 770; a floor of {@value #HULLS} is under a fifth of that.
     */
    private static final int MIN_REGION_CALLS = HULLS;

    // ---- 1b. the liveness twin ---------------------------------------------------------------------

    /** EXPERIMENT: commands per translation direction in the liveness sweep, each from its own wheel state. */
    private static final int LIVENESS_COMMANDS = 40;

    /** EXPERIMENT: steps each liveness command is held. */
    private static final int LIVENESS_HOLD_STEPS = 30;

    /**
     * What the hull can hold is delivered — exactly, and without a saturation flag — from any wheel
     * state: the liveness twin of {@link #everyCommandIsDeliveredCleanlyOrLessAndSaidSo}, without which a
     * scheme that answers "nothing, saturated" to everything would satisfy the law.
     *
     * <p>The judge of "can hold" is NOT the scheme's own figure: it is the symmetric hull's geometry
     * ({@link ShipMotionCases#symmetricAuthority}) — every block mirrored about the deck's centre, so each translation
     * is pushed by exactly the motors facing that way, through the centre of mass, with nothing to null.
     * Commands along one translation axis at a quarter, half and all (less the solve's residual) of that
     * figure, from wheel states drawn as in the sweep, held {@value #LIVENESS_HOLD_STEPS} steps.</p>
     *
     * <p>Contract: ship-flight-model INV-SFM-12, its "delivered exactly" half — this fails if the
     * scheme delivers less than a feasible command, or flags a feasible command saturated.</p>
     *
     * <p>red-witnessed: 2026-10-07 — {@code CleanAxisScheme#allocate} at {@code if (!(recipe.authority >
     * 0.0D))} taken for every axis (each treated as one the hull cannot deliver: nothing is pushed, and
     * every call says so, so the safety law alone stays satisfied) fails "symmetric hull, SURGE_POSITIVE at
     * 0.25 of the geometric 4905000.0 N, command 0, step 0: a command the hull can hold was flagged
     * saturated — force (0, 0, 0)".</p>
     */
    @Test
    public void whatTheHullCanHoldIsDeliveredExactlyFromAnyWheelState() {
        ShipMotionCases.Hull hull = ShipMotionCases.symmetricHull();
        ShipCapability cap = ShipCapability.solve(hull.actuators, hull.mass, ShipMotionCases.HELM);
        ControlScheme scheme = ControlScheme.cleanAxes();
        Random rng = new Random(0x11FE609L);
        double mass = cap.mass().getTotalMass();
        double[] fractions = {0.25D, 0.5D, 1.0D - CleanCommandLaw.RESIDUAL};
        int exact = 0;
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation()) {
                continue;
            }
            double newtons = ShipMotionCases.symmetricAuthority(d);
            for (int k = 0; k < LIVENESS_COMMANDS; k++) {
                MomentumStore momentum = new MomentumStore();
                ShipMotionCases.randomWheelState(rng, cap, momentum);
                double fraction = fractions[k % fractions.length];
                Vector3d lin = new Vector3d(ShipMotionCases.HELM.axis(d.axis()))
                        .mul((d.isPositive() ? 1.0D : -1.0D) * fraction * newtons / mass);
                Vector3d ang = new Vector3d();
                for (int s = 0; s < LIVENESS_HOLD_STEPS; s++) {
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, momentum, ShipMotionCases.DT);
                    String where = hull.name + ", " + d + " at " + fraction + " of the geometric "
                            + newtons + " N, command " + k + ", step " + s;
                    CleanCommandLaw.requireHonest(where, cap, lin, ang, c, momentum);
                    assertTrue(where + ": a command the hull can hold was flagged saturated — force "
                            + c.force(), !c.isSaturated());
                    exact++;
                }
            }
        }
        requireArranged("the liveness sweep must have delivered something: " + exact, exact > 0);
    }

    // ---- 2. a burst, in time ----------------------------------------------------------------------

    /**
     * A burst lasts what the readout says, then the sustained figure holds; and a wheel left idle is
     * bought back by the thrusters, after which the burst is there again.
     *
     * <p>The hull: a centred and an off-centre forward motor, whose moment a small yaw couple can null
     * only in part — so the sustained surge is a fraction of the two motors and the burst is both,
     * the wheel nulling the rest. Surge is asked past the burst figure and held; then nothing is asked
     * until the wheel is empty; then surge is asked again.</p>
     *
     * <p>Contract: this fails if the scheme stops delivering the burst for the seconds the readout
     * promises, delivers less than the sustained figure once it is spent, fails to give an idle wheel
     * back, or does not offer the burst again once it has.</p>
     *
     * <p>red-witnessed: 2026-10-07, on the form before the share check — {@code
     * CleanAxisScheme#burstIfItCanDeliver} refusing a burst only for a wheel with NO room — fails
     * "burst hull, the first burst, step 53: YAW was not asked for and moved anyway … -1.152", the step
     * the wheel fills in.</p>
     */
    @Test
    public void aBurstLastsItsReadoutSecondsThenTheSustainedFigureHoldsAndAnIdleWheelBuysItBack() {
        ShipMotionCases.Hull hull = ShipMotionCases.burstHull();
        ShipCapability cap = ShipCapability.solve(hull.actuators, hull.mass, ShipMotionCases.HELM);
        ControlScheme scheme = ControlScheme.cleanAxes();
        MomentumStore momentum = new MomentumStore();
        double mass = cap.mass().getTotalMass();
        double sustained = cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED);
        double burst = cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.BURST);
        double seconds = cap.burstSeconds(ControlDirection.SURGE_POSITIVE);
        requireArranged("the hull must hold a burst above its sustained surge for a finite time — "
                        + "sustained " + sustained + " N, burst " + burst + " N, " + seconds + " s",
                sustained > 0.0D && burst > sustained * 1.01D && seconds > 10.0D * ShipMotionCases.DT
                        && !Double.isInfinite(seconds));
        double tol = CleanCommandLaw.RESIDUAL * CleanCommandLaw.forceScale(cap);
        Vector3d surge = new Vector3d(ShipMotionCases.HELM.axis(ControlAxis.SURGE)).mul(1.5D * burst / mass);
        Vector3d none = new Vector3d();

        // The FIRST unbroken run of burst steps: once spent, a wheel the couple partly buys back may
        // lend a later step again, which is the scheme working and not the burst lasting longer.
        int burstSteps = 0;
        boolean running = true;
        int phase1 = (int) Math.ceil(seconds / ShipMotionCases.DT) + 60;
        for (int s = 0; s < phase1; s++) {
            ActuatorCommand c = scheme.allocate(cap, surge, none, momentum, ShipMotionCases.DT);
            CleanCommandLaw.requireHonest(hull.name + ", the first burst, step " + s, cap, surge, none, c, momentum);
            double got = c.force().dot(ShipMotionCases.HELM.axis(ControlAxis.SURGE));
            assertTrue("step " + s + ": an over-demand never gets less than the sustained figure "
                    + sustained + " N, got " + got, got >= sustained - tol);
            if (running && Math.abs(got - burst) <= tol) {
                burstSteps++;
            } else {
                running = false;
            }
        }
        assertEquals("the burst lasts the seconds the capability reports for it (" + seconds + " s): "
                + burstSteps + " steps of " + ShipMotionCases.DT + " s", seconds, burstSteps * ShipMotionCases.DT, ShipMotionCases.DT);

        int phase2 = (int) Math.ceil(30.0D / ShipMotionCases.DT);
        double held = CleanCommandLaw.wheelMomentum(cap, momentum);
        requireArranged("the burst must have left the wheel holding momentum: " + held, held > 0.0D);
        for (int s = 0; s < phase2 && held > 0.0D; s++) {
            ActuatorCommand c = scheme.allocate(cap, none, none, momentum, ShipMotionCases.DT);
            CleanCommandLaw.requireHonest(hull.name + ", the idle wheel, step " + s, cap, none, none, c, momentum);
            double now = CleanCommandLaw.wheelMomentum(cap, momentum);
            assertTrue("step " + s + ": an idle wheel is only ever given back: " + held + " -> " + now,
                    now <= held);
            held = now;
        }
        assertEquals("an idle wheel is given back completely within 30 s", 0.0D, held, 0.0D);

        ActuatorCommand again = scheme.allocate(cap, surge, none, momentum, ShipMotionCases.DT);
        CleanCommandLaw.requireHonest(hull.name + ", the second burst", cap, surge, none, again, momentum);
        assertEquals("with the wheel bought back the burst is there again", burst,
                again.force().dot(ShipMotionCases.HELM.axis(ControlAxis.SURGE)), tol);
    }

    // ---- 5. hulls that cannot ---------------------------------------------------------------------

    /**
     * A hull with nothing that pushes delivers nothing and says so; a hull whose only device is a wheel
     * turns for its wheel's seconds and never pushes; and a full wheel still turns the other way.
     *
     * <p>Contract: this fails if a hull without the means is credited with motion, a wheel produces a
     * force, or a wheel full in one sense is refused the sense that unwinds it.</p>
     */
    @Test
    public void aHullWithoutTheMeansDeliversNothingAndSaysSo() {
        ControlScheme scheme = ControlScheme.cleanAxes();

        ShipMotionCases.Hull bare = ShipMotionCases.bareHull();
        ShipCapability bareCap = ShipCapability.solve(bare.actuators, bare.mass, ShipMotionCases.HELM);
        for (ControlDirection d : ControlDirection.values()) {
            Vector3d lin = new Vector3d();
            Vector3d ang = new Vector3d();
            (d.axis().isRotation() ? ang : lin).set(ShipMotionCases.HELM.axis(d.axis())).mul(d.isPositive() ? 1.0D : -1.0D);
            ActuatorCommand c = scheme.allocate(bareCap, lin, ang, new MomentumStore(), ShipMotionCases.DT);
            assertEquals(bare.name + ": " + d + " pushes nothing", 0.0D, c.force().length(), 0.0D);
            assertEquals(bare.name + ": " + d + " turns nothing", 0.0D, c.torque().length(), 0.0D);
            assertTrue(bare.name + ": " + d + " says it delivered less", c.isSaturated());
        }

        ShipMotionCases.Hull wheel = ShipMotionCases.wheelOnlyHull();
        ShipCapability wheelCap = ShipCapability.solve(wheel.actuators, wheel.mass, ShipMotionCases.HELM);
        ShipReadout readout = ShipFlightModel.solve(1L, wheel.mass, wheel.actuators, wheel.actuators, ShipMotionCases.HELM)
                .readout(ShipMotionCases.G);
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation()) {
                assertEquals(wheel.name + ": " + d + " is a burst only", ShipReadout.Warning.BURST_ONLY,
                        readout.warningFor(ShipReadout.View.LIVE, d));
                assertTrue(wheel.name + ": " + d + " lasts a finite time",
                        !Double.isInfinite(wheelCap.burstSeconds(d)));
            } else {
                assertEquals(wheel.name + ": " + d + " has no authority", ShipReadout.Warning.NO_AUTHORITY,
                        readout.warningFor(ShipReadout.View.LIVE, d));
            }
        }

        // A wheel full in one sense: the other sense still delivers, and the full one delivers nothing.
        for (ControlAxis axis : ControlAxis.values()) {
            if (!axis.isRotation()) {
                continue;
            }
            double delivered = 0.0D;
            for (boolean positive : new boolean[]{true, false}) {
                MomentumStore full = new MomentumStore();
                for (Actuator a : wheelCap.actuators()) {
                    full.restore(a.id(), a.momentumCapacity());
                }
                Vector3d ang = new Vector3d(ShipMotionCases.HELM.axis(axis)).mul(positive ? 1.0D : -1.0D)
                        .mul(wheelCap.authority(ControlDirection.of(axis, positive), Endurance.BURST));
                Vector3d lin = new Vector3d();
                ActuatorCommand c = scheme.allocate(wheelCap, lin, ang, full, ShipMotionCases.DT);
                CleanCommandLaw.requireHonest(wheel.name + ", " + axis + (positive ? "+" : "-") + " from a full wheel",
                        wheelCap, lin, ang, c, full);
                delivered = Math.max(delivered, c.torque().length());
            }
            assertTrue(wheel.name + ": a wheel full in one sense still turns " + axis + " the other way",
                    delivered > 0.0D);
        }
    }
}
