package dev.stannismod.stellurgy.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Vector3d;

import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ActuatorId;
import dev.stannismod.stellurgy.ship.control.ChemicalMotor;
import dev.stannismod.stellurgy.ship.control.ControlAxis;
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.ControlFrame;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.MomentumStore;
import dev.stannismod.stellurgy.ship.control.ReactionWheel;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.ship.mass.MassContributor;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder;

/**
 * Instrument: the cases the ship motion kernel's fast tests are run over — hulls built from production's
 * own devices ({@link ChemicalMotor#at}, {@link ReactionWheel#at}) on a deck weighed the way the hull walk
 * weighs one, generated hulls, wheel states, commands, and the figures every model is solved with
 * ({@link ControlFrame#HELM}). Shared by the owner's laws class and its joints class, so neither copies
 * the other's arrangement.
 */
public final class ShipMotionCases {

    private ShipMotionCases() {
    }

    /**
     * An advanced motor's thrust, N: the figure the in-world readout reports for it (the decked
     * fixture's surge, 4 905 000 N from two of them, measured 2026-09-30).
     */
    public static final double THRUST = 2_452_500.0D;

    /** A solid block's mass, kg: what the hull weighing gives an iron block (measured 2026-09-30). */
    public static final double BLOCK_KG = 5_000.0D;

    /**
     * One physics step, s: the physics substrate's step as the in-world window measured it — 559
     * controller steps in 9.3 engine seconds, 2026-10-07.
     */
    public static final double DT = 1.0D / 60.0D;

    /** The overworld's field, m/s². */
    public static final double G = 9.81D;

    /**
     * The helm frame every flight model is solved in — production's own.
     *
     * <p>A constant: a {@code ControlFrame} copies its axes and hands them out read-only.</p>
     */
    public static final ControlFrame HELM = ControlFrame.HELM;

    /** A multiple of an axis's authority for a generated command to ask for: under, at, or past it. */
    public static double demand(Random rng) {
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

    /** Whether some wheel axis, as the scheme is about to see it, has room for less than one full step. */
    public static boolean aWheelIsInsideItsLastStep(ShipCapability cap, MomentumStore momentum) {
        for (Actuator a : cap.actuators()) {
            if (a.isSustained()) {
                continue;
            }
            double room = a.momentumCapacity() - Math.abs(momentum.given(a.id()));
            if (room > 0.0D && room < a.maxTorque().length() * DT) {
                return true;
            }
        }
        return false;
    }

    /**
     * The symmetric hull's clean authority along a translation, from its geometry alone: the motors that
     * push that way, at {@link #THRUST} each — two per sign of surge and sway, four per sign of heave.
     * Not asked of the scheme: this is the oracle the scheme is judged by.
     */
    public static double symmetricAuthority(ControlDirection d) {
        return (d.axis() == ControlAxis.HEAVE ? 4.0D : 2.0D) * THRUST;
    }

    // ---- generated commands and wheel states --------------------------------------------------------

    /**
     * One to three distinct axes, each at a multiple of its authority from {@link #demand} in a random
     * sense; an axis with no authority is asked for one unit anyway. Answers a description.
     */
    public static String randomCommand(Random rng, ShipCapability cap, Vector3d lin, Vector3d ang) {
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
    public static int randomWheelState(Random rng, ShipCapability cap, MomentumStore momentum) {
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
    public static final class Hull {
        public final String name;
        public final List<Actuator> actuators;
        public final List<MassContributor> contributors;
        public final ShipMassFrame mass;

        Hull(String name, List<Actuator> actuators, List<MassContributor> contributors) {
            this.name = name;
            this.actuators = actuators;
            this.contributors = contributors;
            ShipMassFrameBuilder b = new ShipMassFrameBuilder();
            b.addAll(contributors);
            this.mass = contributors.isEmpty() ? ShipMassFrame.empty() : b.build();
        }

        /**
         * The hull's mass as the experiment declares it, kg: the sum of its contributors, read off the
         * inputs rather than off the frame the mass builder made of them — so a verdict that divides by
         * it does not share a computation with the code under test.
         */
        public double declaredMass() {
            double total = 0.0D;
            for (MassContributor c : contributors) {
                total += Math.max(0.0D, c.getMass());
            }
            return total;
        }
    }

    /** A motor at block (x,y,z) that pushes along the unit block direction (px,py,pz). */
    public static Actuator motor(int x, int y, int z, int px, int py, int pz, double thrust) {
        return ChemicalMotor.at(x, y, z, -px, -py, -pz, thrust);
    }

    /** A 5×5 deck at y=0 plus a block under every device that stands off it. */
    public static List<MassContributor> deckUnder(List<Actuator> actuators) {
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
    public static Hull burstHull() {
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
    public static Hull symmetricHull() {
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
        // In the deck, so every block is mirrored about the deck's centre and so is the mass: that is
        // what makes this hull's translational authority a closed form (see symmetricAuthority).
        a.addAll(ReactionWheel.at(0, 0, 0));
        return new Hull("symmetric hull", a, deckUnder(a));
    }

    public static Hull wheelOnlyHull() {
        List<Actuator> a = new ArrayList<>(ReactionWheel.at(0, 1, 0));
        return new Hull("wheel-only hull", a, deckUnder(a));
    }

    public static Hull bareHull() {
        List<Actuator> a = new ArrayList<>();
        return new Hull("bare hull", a, deckUnder(a));
    }

    /** 1 to 14 motors anywhere on a 5×3×5 frame, facing any way, at 0.5-1.5 of a motor; usually a wheel. */
    public static Hull randomHull(Random rng, String name) {
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
