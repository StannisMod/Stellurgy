package dev.stannismod.stellurgy.test.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.Test;

import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorCommand;
import dev.stannismod.stellurgy.ship.control.ActuatorId;
import dev.stannismod.stellurgy.ship.control.ChemicalMotor;
import dev.stannismod.stellurgy.ship.control.ControlAxis;
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.ControlFrame;
import dev.stannismod.stellurgy.ship.control.ControlScheme;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.MomentumStore;
import dev.stannismod.stellurgy.ship.control.ReactionWheel;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.ship.control.ShipFlightModel;
import dev.stannismod.stellurgy.ship.control.ShipReadout;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration: the ship motion kernel as the flight computer wires it — a hull's mass frame and its
 * actuators solved into a {@link ShipFlightModel}, the model's readout, and the clean-axis scheme
 * allocating a command physics step after physics step against ONE {@link MomentumStore}, which is the
 * state the steps share.
 *
 * <p>The central law is checked on every step of every scenario, and it is the contract a pilot flies
 * by: <b>a command is delivered exactly, or less and said so — never along an axis nobody asked for,
 * never the wrong way, never more.</b> A translation asked alone does not turn the hull; a rotation
 * asked alone does not push it; every throttle stays in its device's range and every wheel inside its
 * capacity. See {@link #requireHonest}.</p>
 *
 * <p>The devices are production's own — {@link ChemicalMotor#at}, {@link ReactionWheel#at} — and every
 * model is solved in {@link ControlFrame#HELM}. What this class does NOT see: what the world tells a
 * device (a motor's nozzle facing and wear, read by its block), how a hull is weighed
 * ({@code ShipHullMass} walks the world; the mass here is built through {@link ShipMassFrameBuilder}
 * with a block at its centre, the convention that walk uses), the flight law that turns pilot input
 * into the wanted accelerations and the unit conversion around the scheme
 * ({@code TileAdvancedFlightComputer#onPhysicsTick}), and the solver that integrates the wrench (the
 * physics substrate).</p>
 */
public class ShipMotionKernelTest {

    /**
     * An advanced motor's thrust, N: the figure the in-world readout reports for it (the decked
     * fixture's surge, 4 905 000 N from two of them, measured 2026-09-30).
     */
    private static final double THRUST = 2_452_500.0D;

    /** A solid block's mass, kg: what the hull weighing gives an iron block (measured 2026-09-30). */
    private static final double BLOCK_KG = 5_000.0D;

    /**
     * One physics step, s: the physics substrate's step as the in-world window measured it — 559
     * controller steps in 9.3 engine seconds, 2026-10-07.
     */
    private static final double DT = 1.0D / 60.0D;

    /** The overworld's field, m/s². */
    private static final double G = 9.81D;

    /**
     * The residual a clean recipe may leave on an axis it does not name, as a fraction of the largest
     * force or torque aboard — the bound the capability solve itself enforces (INV-SFM-01; measured
     * worst 1.9e-15). A delivered command is a positive sum of such recipes, so it inherits the bound.
     */
    private static final double RESIDUAL = 1.0e-6D;

    /**
     * The helm frame every flight model is solved in — production's own.
     *
     * <p>A constant: a {@code ControlFrame} copies its axes and hands them out read-only.</p>
     */
    private static final ControlFrame HELM = ControlFrame.HELM;

    // ---- 1. the law, everywhere -------------------------------------------------------------------

    /** How many generated hulls the sweep solves. */
    private static final int HULLS = 300;

    /** How many commands each hull is given, each from its own wheel state. */
    private static final int COMMANDS = 12;

    /** How many physics steps each command is held: long enough to carry a wheel across its range. */
    private static final int HOLD_STEPS = 90;

    /** A multiple of an axis's authority for a generated command to ask for: under, at, or past it. */
    private static double demand(Random rng) {
        switch (rng.nextInt(4)) {
            case 0:
                return 0.3D;
            case 1:
                return 1.0D;
            case 2:
                return 1.5D;
            default:
                return 4.0D;
        }
    }

    /**
     * Every hull, every wheel state, every command, every step: the command is delivered cleanly, or
     * less and said so.
     *
     * <p>Generated hulls (seeded) carry 1 to 14 motors anywhere on a 5×3×5 frame, facing any way, and
     * usually a wheel. Each command asks for one to three axes at once, at a multiple of the axis's
     * authority from {@link #demand}, and is held for {@link #HOLD_STEPS} steps from a wheel state that
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
        int sliverStarts = 0;
        int wheelSteps = 0;
        for (int h = 0; h < HULLS; h++) {
            Hull hull = randomHull(rng, "generated hull #" + h);
            ShipCapability cap = ShipCapability.solve(hull.actuators, hull.mass, HELM);
            for (int k = 0; k < COMMANDS; k++) {
                MomentumStore momentum = new MomentumStore();
                sliverStarts += randomWheelState(rng, cap, momentum);
                Vector3d lin = new Vector3d();
                Vector3d ang = new Vector3d();
                String asked = randomCommand(rng, cap, lin, ang);
                for (int s = 0; s < HOLD_STEPS; s++) {
                    String before = wheels(cap, momentum);
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, momentum, DT);
                    try {
                        requireHonest(hull.name + ", command " + k + " (" + asked + "), step " + s,
                                cap, lin, ang, c, momentum);
                    } catch (AssertionError broken) {
                        throw new AssertionError(broken.getMessage() + " | saturated " + c.isSaturated()
                                + " | wheels before the step: " + before + " | after: " + wheels(cap, momentum)
                                + " | wheel throttles: " + wheelThrottles(cap, c), broken);
                    }
                    steps++;
                    if (c.isSaturated()) {
                        saturatedSteps++;
                    }
                    if (usesAWheel(cap, c)) {
                        wheelSteps++;
                    }
                }
            }
        }
        System.out.println("[kernel] steps " + steps + ", saturated " + saturatedSteps
                + ", using a wheel " + wheelSteps + ", commands started a sliver short of full "
                + sliverStarts);
        // The law above holds trivially on a sweep that never saturated, never used a wheel, or never
        // started a wheel inside its last step.
        requireArranged("the sweep must visit the states the law is about — saturated " + saturatedSteps
                        + ", wheel steps " + wheelSteps + ", sliver starts " + sliverStarts + " of "
                        + steps + " steps",
                saturatedSteps > 0 && saturatedSteps < steps && wheelSteps > 0 && sliverStarts > 0);
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
        Hull hull = burstHull();
        ShipCapability cap = ShipCapability.solve(hull.actuators, hull.mass, HELM);
        ControlScheme scheme = ControlScheme.cleanAxes();
        MomentumStore momentum = new MomentumStore();
        double mass = cap.mass().getTotalMass();
        double sustained = cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED);
        double burst = cap.authority(ControlDirection.SURGE_POSITIVE, Endurance.BURST);
        double seconds = cap.burstSeconds(ControlDirection.SURGE_POSITIVE);
        requireArranged("the hull must hold a burst above its sustained surge for a finite time — "
                        + "sustained " + sustained + " N, burst " + burst + " N, " + seconds + " s",
                sustained > 0.0D && burst > sustained * 1.01D && seconds > 10.0D * DT
                        && !Double.isInfinite(seconds));
        double tol = RESIDUAL * forceScale(cap);
        Vector3d surge = new Vector3d(HELM.axis(ControlAxis.SURGE)).mul(1.5D * burst / mass);
        Vector3d none = new Vector3d();

        // The FIRST unbroken run of burst steps: once spent, a wheel the couple partly buys back may
        // lend a later step again, which is the scheme working and not the burst lasting longer.
        int burstSteps = 0;
        boolean running = true;
        int phase1 = (int) Math.ceil(seconds / DT) + 60;
        for (int s = 0; s < phase1; s++) {
            ActuatorCommand c = scheme.allocate(cap, surge, none, momentum, DT);
            requireHonest(hull.name + ", the first burst, step " + s, cap, surge, none, c, momentum);
            double got = c.force().dot(HELM.axis(ControlAxis.SURGE));
            assertTrue("step " + s + ": an over-demand never gets less than the sustained figure "
                    + sustained + " N, got " + got, got >= sustained - tol);
            if (running && Math.abs(got - burst) <= tol) {
                burstSteps++;
            } else {
                running = false;
            }
        }
        assertEquals("the burst lasts the seconds the capability reports for it (" + seconds + " s): "
                + burstSteps + " steps of " + DT + " s", seconds, burstSteps * DT, DT);

        int phase2 = (int) Math.ceil(30.0D / DT);
        double held = wheelMomentum(cap, momentum);
        requireArranged("the burst must have left the wheel holding momentum: " + held, held > 0.0D);
        for (int s = 0; s < phase2 && held > 0.0D; s++) {
            ActuatorCommand c = scheme.allocate(cap, none, none, momentum, DT);
            requireHonest(hull.name + ", the idle wheel, step " + s, cap, none, none, c, momentum);
            double now = wheelMomentum(cap, momentum);
            assertTrue("step " + s + ": an idle wheel is only ever given back: " + held + " -> " + now,
                    now <= held);
            held = now;
        }
        assertEquals("an idle wheel is given back completely within 30 s", 0.0D, held, 0.0D);

        ActuatorCommand again = scheme.allocate(cap, surge, none, momentum, DT);
        requireHonest(hull.name + ", the second burst", cap, surge, none, again, momentum);
        assertEquals("with the wheel bought back the burst is there again", burst,
                again.force().dot(HELM.axis(ControlAxis.SURGE)), tol);
    }

    // ---- 3. the readout is what flies -------------------------------------------------------------

    /**
     * What the readout tells a pilot is what the scheme delivers: commanding exactly a direction's
     * readout acceleration — sustained, or burst from a fresh wheel — gets exactly that, not saturated;
     * a direction the readout calls NO_AUTHORITY delivers nothing; and a craft with a broken motor is
     * never stronger in any direction than as built.
     *
     * <p>Over the hand-built hulls and fifty generated ones, each solved as the flight computer solves
     * it: one model, DESIGN over every device and LIVE over the working ones (the first motor taken out
     * as broken), read in the overworld's field.</p>
     *
     * <p>Contract: this fails if a readout figure and the delivered motion part company, or a working
     * set is credited with more than the full one.</p>
     */
    @Test
    public void theReadoutIsWhatTheSchemeDelivers() {
        List<Hull> hulls = new ArrayList<>();
        hulls.add(burstHull());
        hulls.add(symmetricHull());
        hulls.add(wheelOnlyHull());
        Random rng = new Random(0xF1E1DL);
        for (int h = 0; h < 50; h++) {
            hulls.add(randomHull(rng, "generated hull #" + h));
        }
        ControlScheme scheme = ControlScheme.cleanAxes();
        int compared = 0;
        for (Hull hull : hulls) {
            List<Actuator> live = new ArrayList<>(hull.actuators);
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i).isSustained()) {
                    live.remove(i);
                    break;
                }
            }
            ShipFlightModel model = ShipFlightModel.solve(1L, hull.mass, hull.actuators, live, HELM);
            ShipReadout readout = model.readout(G);
            ShipCapability cap = model.live();
            double mass = cap.mass().getTotalMass();
            double tol = RESIDUAL * forceScale(cap);
            for (ControlDirection d : ControlDirection.values()) {
                for (Endurance e : Endurance.values()) {
                    double design = readout.authority(ShipReadout.View.DESIGN, d, e);
                    double working = readout.authority(ShipReadout.View.LIVE, d, e);
                    assertTrue(hull.name + ": a broken motor never makes " + d + " " + e
                                    + " stronger — design " + design + ", live " + working,
                            working <= design * (1.0D + RESIDUAL) + tol);

                    // Inside the figure by the solve's own residual: the readout divides by the mass
                    // and the scheme multiplies back, and exactly at the figure the last bit decides
                    // whether the axis reads as clipped. Measured 2026-10-07: delivered to 1e-16, and
                    // flagged saturated.
                    double figure = readout.acceleration(ShipReadout.View.LIVE, d, e) * (1.0D - RESIDUAL);
                    Vector3d lin = new Vector3d();
                    Vector3d ang = new Vector3d();
                    Vector3d axis = new Vector3d(HELM.axis(d.axis())).mul(d.isPositive() ? 1.0D : -1.0D);
                    (d.axis().isRotation() ? ang : lin).set(axis.mul(figure));
                    MomentumStore fresh = new MomentumStore();
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, fresh, DT);
                    String where = hull.name + ", " + d + " at its live " + e + " figure " + figure;
                    requireHonest(where, cap, lin, ang, c, fresh);
                    if (figure > 0.0D) {
                        assertTrue(where + ": the readout's own figure is delivered without saturating"
                                        + " — force " + c.force() + ", torque " + c.torque() + ", mass " + mass
                                        + ", authority " + cap.authority(d, e) + ", asked " + lin + " / " + ang
                                        + ", wheel throttles " + wheelThrottles(cap, c),
                                !c.isSaturated());
                        compared++;
                    }
                }
                if (readout.warningFor(ShipReadout.View.LIVE, d) == ShipReadout.Warning.NO_AUTHORITY) {
                    Vector3d lin = new Vector3d();
                    Vector3d ang = new Vector3d();
                    Vector3d axis = new Vector3d(HELM.axis(d.axis())).mul(d.isPositive() ? 1.0D : -1.0D);
                    (d.axis().isRotation() ? ang : lin).set(axis);
                    ActuatorCommand c = scheme.allocate(cap, lin, ang, new MomentumStore(), DT);
                    double got = d.axis().isRotation() ? c.torque().length() : c.force().length();
                    assertEquals(hull.name + ": " + d + " has NO_AUTHORITY on the readout, so asking for it"
                            + " delivers nothing", 0.0D, got, RESIDUAL * torqueScale(cap));
                    assertTrue(hull.name + ": and says so", c.isSaturated() || mass <= 0.0D);
                }
            }
        }
        requireArranged("some readout figure must have been compared: " + compared, compared > 0);
    }

    // ---- 4. cargo ----------------------------------------------------------------------------------

    /**
     * Cargo stowed at the centre of mass changes how fast the hull accelerates and nothing else about
     * its pushing: every translational authority keeps its newtons, and the readout's acceleration
     * falls by exactly the ratio of the masses.
     *
     * <p>Contract: this fails if content mass enters the force a hull can make, or the readout divides
     * by anything but the whole mass.</p>
     */
    @Test
    public void cargoChangesAccelerationNotForce() {
        Hull empty = symmetricHull();
        Vector3dc centre = empty.mass.getCentreOfMass();
        ShipMassFrameBuilder laden = new ShipMassFrameBuilder();
        laden.addAll(empty.contributors);
        double cargo = 64 * BLOCK_KG;
        laden.add(MassContributor.of(centre.x(), centre.y(), centre.z(), cargo, MassContributor.BLOCK_EXTENT,
                MassContributor.Kind.CONTENT));
        ShipReadout before = ShipFlightModel.solve(1L, empty.mass, empty.actuators, empty.actuators, HELM)
                .readout(G);
        ShipReadout after = ShipFlightModel.solve(2L, laden.build(), empty.actuators, empty.actuators, HELM)
                .readout(G);
        requireArranged("the cargo must be weighed as content: " + after.contentMass(),
                Math.abs(after.contentMass() - cargo) <= cargo * RESIDUAL);
        double ratio = before.totalMass() / after.totalMass();
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation()) {
                continue;
            }
            double f0 = before.authority(ShipReadout.View.LIVE, d, Endurance.SUSTAINED);
            double f1 = after.authority(ShipReadout.View.LIVE, d, Endurance.SUSTAINED);
            assertEquals(d + ": cargo keeps the force", f0, f1, f0 * RESIDUAL);
            assertEquals(d + ": and lowers the acceleration by the mass ratio " + ratio,
                    before.acceleration(ShipReadout.View.LIVE, d, Endurance.SUSTAINED) * ratio,
                    after.acceleration(ShipReadout.View.LIVE, d, Endurance.SUSTAINED),
                    f0 * RESIDUAL / after.totalMass());
        }
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

        Hull bare = bareHull();
        ShipCapability bareCap = ShipCapability.solve(bare.actuators, bare.mass, HELM);
        for (ControlDirection d : ControlDirection.values()) {
            Vector3d lin = new Vector3d();
            Vector3d ang = new Vector3d();
            (d.axis().isRotation() ? ang : lin).set(HELM.axis(d.axis())).mul(d.isPositive() ? 1.0D : -1.0D);
            ActuatorCommand c = scheme.allocate(bareCap, lin, ang, new MomentumStore(), DT);
            assertEquals(bare.name + ": " + d + " pushes nothing", 0.0D, c.force().length(), 0.0D);
            assertEquals(bare.name + ": " + d + " turns nothing", 0.0D, c.torque().length(), 0.0D);
            assertTrue(bare.name + ": " + d + " says it delivered less", c.isSaturated());
        }

        Hull wheel = wheelOnlyHull();
        ShipCapability wheelCap = ShipCapability.solve(wheel.actuators, wheel.mass, HELM);
        ShipReadout readout = ShipFlightModel.solve(1L, wheel.mass, wheel.actuators, wheel.actuators, HELM)
                .readout(G);
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
                Vector3d ang = new Vector3d(HELM.axis(axis)).mul(positive ? 1.0D : -1.0D)
                        .mul(wheelCap.authority(ControlDirection.of(axis, positive), Endurance.BURST));
                Vector3d lin = new Vector3d();
                ActuatorCommand c = scheme.allocate(wheelCap, lin, ang, full, DT);
                requireHonest(wheel.name + ", " + axis + (positive ? "+" : "-") + " from a full wheel",
                        wheelCap, lin, ang, c, full);
                delivered = Math.max(delivered, c.torque().length());
            }
            assertTrue(wheel.name + ": a wheel full in one sense still turns " + axis + " the other way",
                    delivered > 0.0D);
        }
    }

    // ---- the law ------------------------------------------------------------------------------------

    /**
     * The law every step obeys. Per pilot axis, comparing what was asked with what the wrench does to
     * this hull (force over mass along a translation, inverse inertia times torque along a rotation):
     * an axis not asked for gets nothing; an axis asked for gets its own sign and no more than asked;
     * an unsaturated command gets exactly what it asked. Every throttle is in its device's range and
     * every wheel inside its capacity.
     */
    private static void requireHonest(String where, ShipCapability cap, Vector3dc lin, Vector3dc ang,
                                      ActuatorCommand c, MomentumStore momentum) {
        List<Actuator> actuators = cap.actuators();
        for (int i = 0; i < actuators.size(); i++) {
            Actuator a = actuators.get(i);
            double u = c.throttle(i);
            assertTrue(where + ": " + a.id() + " throttle " + u + " out of [" + a.minThrottle() + ", 1]",
                    u >= a.minThrottle() - 1.0e-12D && u <= 1.0D + 1.0e-12D);
            if (!a.isSustained()) {
                double held = momentum.given(a.id());
                assertTrue(where + ": " + a.id() + " holds " + held + " past its capacity "
                        + a.momentumCapacity(), Math.abs(held) <= a.momentumCapacity() * (1.0D + 1.0e-12D));
            }
        }
        double mass = cap.mass().getTotalMass();
        double forceTol = RESIDUAL * forceScale(cap);
        double torqueTol = RESIDUAL * torqueScale(cap);
        if (mass > 0.0D) {
            Vector3d a = new Vector3d(c.force()).div(mass);
            for (ControlAxis axis : ControlAxis.values()) {
                if (!axis.isRotation()) {
                    Vector3dc e = HELM.axis(axis);
                    axisVerdict(where, axis, lin.dot(e), a.dot(e), forceTol / mass, c.isSaturated());
                }
            }
            Matrix3d inverse = new Matrix3d(cap.mass().getInertia()).invert();
            Vector3d alpha = inverse.transform(new Vector3d(c.torque()));
            double alphaTol = torqueTol * frobenius(inverse);
            for (ControlAxis axis : ControlAxis.values()) {
                if (axis.isRotation()) {
                    Vector3dc e = HELM.axis(axis);
                    axisVerdict(where, axis, ang.dot(e), alpha.dot(e), alphaTol, c.isSaturated());
                }
            }
        } else {
            assertEquals(where + ": a massless hull is pushed by nothing", 0.0D, c.force().length(), forceTol);
            assertEquals(where + ": and turned by nothing", 0.0D, c.torque().length(), torqueTol);
        }
    }

    private static void axisVerdict(String where, ControlAxis axis, double want, double got, double tol,
                                    boolean saturated) {
        if (want == 0.0D) {
            assertEquals(where + ": " + axis + " was not asked for and moved anyway", 0.0D, got, tol);
            return;
        }
        double sense = Math.signum(want);
        assertTrue(where + ": " + axis + " delivered the wrong way — asked " + want + ", got " + got,
                got * sense >= -tol);
        assertTrue(where + ": " + axis + " delivered more than asked — asked " + want + ", got " + got,
                Math.abs(got) <= Math.abs(want) + tol);
        if (!saturated) {
            assertEquals(where + ": " + axis + " delivered less than asked without saying so",
                    want, got, tol);
        }
    }

    // ---- reading a command --------------------------------------------------------------------------

    private static boolean usesAWheel(ShipCapability cap, ActuatorCommand c) {
        for (int i = 0; i < cap.actuators().size(); i++) {
            if (!cap.actuators().get(i).isSustained() && c.throttle(i) != 0.0D) {
                return true;
            }
        }
        return false;
    }

    /** Each wheel axis as {@code index:held/capacity}, the store's own numbers. */
    private static String wheels(ShipCapability cap, MomentumStore momentum) {
        StringBuilder out = new StringBuilder();
        for (Actuator a : cap.actuators()) {
            if (a.isSustained()) {
                continue;
            }
            out.append(a.id().index()).append(':').append(momentum.given(a.id())).append('/')
                    .append(a.momentumCapacity()).append(' ');
        }
        return out.length() == 0 ? "none" : out.toString().trim();
    }

    private static String wheelThrottles(ShipCapability cap, ActuatorCommand c) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cap.actuators().size(); i++) {
            if (!cap.actuators().get(i).isSustained()) {
                out.append(cap.actuators().get(i).id().index()).append(':').append(c.throttle(i)).append(' ');
            }
        }
        return out.length() == 0 ? "none" : out.toString().trim();
    }

    private static double wheelMomentum(ShipCapability cap, MomentumStore momentum) {
        double total = 0.0D;
        for (Actuator a : cap.actuators()) {
            if (!a.isSustained()) {
                total += Math.abs(momentum.given(a.id()));
            }
        }
        return total;
    }

    /** The largest force any device aboard makes, N. */
    private static double forceScale(ShipCapability cap) {
        double s = 0.0D;
        for (Actuator a : cap.actuators()) {
            s = Math.max(s, a.maxForce().length());
        }
        return Math.max(s, 1.0D);
    }

    /** The largest torque any device aboard makes about the centre of mass, N·m. */
    private static double torqueScale(ShipCapability cap) {
        Vector3dc centre = cap.mass().getCentreOfMass();
        double s = 0.0D;
        for (Actuator a : cap.actuators()) {
            ActuatorId id = a.id();
            double lever = new Vector3d(id.x() + 0.5D, id.y() + 0.5D, id.z() + 0.5D).sub(centre).length();
            s = Math.max(s, Math.max(a.maxTorque().length(), a.maxForce().length() * lever));
        }
        return Math.max(s, 1.0D);
    }

    private static double frobenius(Matrix3d m) {
        return Math.sqrt(m.m00 * m.m00 + m.m01 * m.m01 + m.m02 * m.m02 + m.m10 * m.m10 + m.m11 * m.m11
                + m.m12 * m.m12 + m.m20 * m.m20 + m.m21 * m.m21 + m.m22 * m.m22);
    }

    // ---- generated commands and wheel states --------------------------------------------------------

    /**
     * One to three distinct axes, each at a multiple of its authority from {@link #demand} in a random
     * sense; an axis with no authority is asked for one unit anyway. Answers a description.
     */
    private static String randomCommand(Random rng, ShipCapability cap, Vector3d lin, Vector3d ang) {
        ControlAxis[] axes = ControlAxis.values();
        int count = 1 + rng.nextInt(3);
        boolean[] taken = new boolean[axes.length];
        StringBuilder out = new StringBuilder();
        double mass = cap.mass().getTotalMass();
        for (int n = 0; n < count; n++) {
            int pick;
            do {
                pick = rng.nextInt(axes.length);
            } while (taken[pick]);
            taken[pick] = true;
            ControlAxis axis = axes[pick];
            boolean positive = rng.nextBoolean();
            ControlDirection d = ControlDirection.of(axis, positive);
            double authority = cap.authority(d, Endurance.BURST);
            double factor = demand(rng);
            double amount;
            if (axis.isRotation()) {
                amount = authority > 0.0D ? authority * factor : 1.0D;
            } else {
                amount = authority > 0.0D && mass > 0.0D ? authority * factor / mass : 1.0D;
            }
            Vector3d e = new Vector3d(HELM.axis(axis)).mul(positive ? amount : -amount);
            (axis.isRotation() ? ang : lin).add(e);
            out.append(d).append('×').append(factor).append(' ');
        }
        return out.toString().trim();
    }

    /**
     * Put every wheel axis in one of six states: empty, full either way, short of full either way by
     * less than one full-throttle step, or anywhere between. Answers how many axes started a sliver
     * short of full.
     */
    private static int randomWheelState(Random rng, ShipCapability cap, MomentumStore momentum) {
        int slivers = 0;
        for (Actuator a : cap.actuators()) {
            if (a.isSustained()) {
                continue;
            }
            double capacity = a.momentumCapacity();
            double step = a.maxTorque().length() * DT;
            double held;
            switch (rng.nextInt(6)) {
                case 0:
                    held = 0.0D;
                    break;
                case 1:
                    held = capacity;
                    break;
                case 2:
                    held = -capacity;
                    break;
                case 3:
                    held = capacity - step * rng.nextDouble();
                    slivers++;
                    break;
                case 4:
                    held = -capacity + step * rng.nextDouble();
                    slivers++;
                    break;
                default:
                    held = capacity * (2.0D * rng.nextDouble() - 1.0D);
                    break;
            }
            momentum.restore(a.id(), held);
        }
        return slivers;
    }

    // ---- hulls ----------------------------------------------------------------------------------------

    /** A hull: its devices and its mass, and the contributors the mass was built from. */
    private static final class Hull {
        final String name;
        final List<Actuator> actuators;
        final List<MassContributor> contributors;
        final ShipMassFrame mass;

        Hull(String name, List<Actuator> actuators, List<MassContributor> contributors) {
            this.name = name;
            this.actuators = actuators;
            this.contributors = contributors;
            ShipMassFrameBuilder b = new ShipMassFrameBuilder();
            b.addAll(contributors);
            this.mass = contributors.isEmpty() ? ShipMassFrame.empty() : b.build();
        }
    }

    /** A motor at block (x,y,z) that pushes along the unit block direction (px,py,pz). */
    private static Actuator motor(int x, int y, int z, int px, int py, int pz, double thrust) {
        return ChemicalMotor.at(x, y, z, -px, -py, -pz, thrust);
    }

    /** A 5×5 deck at y=0 plus a block under every device that stands off it. */
    private static List<MassContributor> deckUnder(List<Actuator> actuators) {
        List<MassContributor> out = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                out.add(MassContributor.ofBlock(x + 0.5D, 0.5D, z + 0.5D, BLOCK_KG, MassContributor.Kind.STRUCTURAL));
            }
        }
        List<String> seen = new ArrayList<>();
        for (Actuator a : actuators) {
            ActuatorId id = a.id();
            String key = id.x() + "," + id.y() + "," + id.z();
            if (id.y() == 0 && Math.abs(id.x()) <= 2 && Math.abs(id.z()) <= 2 || seen.contains(key)) {
                continue;
            }
            seen.add(key);
            out.add(MassContributor.ofBlock(id.x() + 0.5D, id.y() + 0.5D, id.z() + 0.5D, BLOCK_KG,
                    MassContributor.Kind.STRUCTURAL));
        }
        return out;
    }

    /**
     * A centred and an off-centre forward motor, a weak yaw couple each way that can null only part of
     * the off-centre motor's moment, and a wheel: sustained surge is a fraction of the two motors, the
     * burst is both.
     */
    private static Hull burstHull() {
        List<Actuator> a = new ArrayList<>();
        a.add(motor(0, 0, -2, 0, 0, 1, THRUST));
        a.add(motor(2, 0, -2, 0, 0, 1, THRUST));
        // The yaw couples: a sideways push at either end, opposite ways. The lever about the vertical
        // is the Z distance alone, so the X they stand at is free.
        double weak = THRUST / 10.0D;
        a.add(motor(-2, 0, 2, 1, 0, 0, weak));
        a.add(motor(-2, 0, -2, -1, 0, 0, weak));
        a.add(motor(1, 0, -2, 1, 0, 0, weak));
        a.add(motor(1, 0, 2, -1, 0, 0, weak));
        // In the deck, so the centre of mass stays at the motors' height: a wheel above the deck would
        // put every forward push below the centre and give it a pitch nothing here can hold.
        a.addAll(ReactionWheel.at(0, 0, 0));
        return new Hull("burst hull", a, deckUnder(a));
    }

    /** A motor at each end of each axis, in opposed pairs on both sides of the centre, and a wheel. */
    private static Hull symmetricHull() {
        List<Actuator> a = new ArrayList<>();
        for (int s = -1; s <= 1; s += 2) {
            a.add(motor(s, 0, -2, 0, 0, 1, THRUST));
            a.add(motor(s, 0, 2, 0, 0, -1, THRUST));
            a.add(motor(-2, 0, s, 1, 0, 0, THRUST));
            a.add(motor(2, 0, s, -1, 0, 0, THRUST));
            a.add(motor(s, -1, s, 0, 1, 0, THRUST));
            a.add(motor(-s, -1, s, 0, 1, 0, THRUST));
            a.add(motor(s, 1, s, 0, -1, 0, THRUST));
            a.add(motor(-s, 1, s, 0, -1, 0, THRUST));
        }
        a.addAll(ReactionWheel.at(0, 1, 0));
        return new Hull("symmetric hull", a, deckUnder(a));
    }

    private static Hull wheelOnlyHull() {
        List<Actuator> a = new ArrayList<>(ReactionWheel.at(0, 1, 0));
        return new Hull("wheel-only hull", a, deckUnder(a));
    }

    private static Hull bareHull() {
        List<Actuator> a = new ArrayList<>();
        return new Hull("bare hull", a, deckUnder(a));
    }

    /** 1 to 14 motors anywhere on a 5×3×5 frame, facing any way, at 0.5-1.5 of a motor; usually a wheel. */
    private static Hull randomHull(Random rng, String name) {
        List<Actuator> a = new ArrayList<>();
        List<String> used = new ArrayList<>();
        int motors = 1 + rng.nextInt(14);
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (a.size() < motors) {
            int x = rng.nextInt(5) - 2;
            int y = rng.nextInt(3) - 1;
            int z = rng.nextInt(5) - 2;
            String key = x + "," + y + "," + z;
            if (used.contains(key)) {
                continue;
            }
            used.add(key);
            int[] f = faces[rng.nextInt(faces.length)];
            double thrust = THRUST * (0.5D + rng.nextDouble());
            a.add(motor(x, y, z, f[0], f[1], f[2], thrust));
        }
        if (rng.nextInt(10) < 7) {
            int x;
            int z;
            do {
                x = rng.nextInt(5) - 2;
                z = rng.nextInt(5) - 2;
            } while (used.contains(x + ",2," + z));
            a.addAll(ReactionWheel.at(x, 2, z));
        }
        return new Hull(name, a, deckUnder(a));
    }
}
