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
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.ControlFrame;
import dev.stannismod.stellurgy.ship.control.ControlScheme;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.MomentumStore;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.MassContributor.Kind;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a hull can do is decided by where its devices are, and these pin the promises that follow:
 * a hull is as strong in a direction as it can be CLEANLY, a sign is not a symmetry, a wheel turns
 * but does not push and cannot hold forever, rotation is an acceleration on this hull's inertia, a
 * combined command never asks a device for more than it has, and the same hull gives the same
 * answer however its devices were listed.
 *
 * <p>The frame throughout: the helm faces +Z, up is +Y, so right is -X. Thrust {@code T} is an
 * arbitrary round number; every verdict is a relationship between figures of one hull.</p>
 */
public class ShipCapabilityTest {

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
}
