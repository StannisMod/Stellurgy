package dev.stannismod.stellurgy.ship.control;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a ship's propellant buys, and what the body below charges for climbing out of its air and coming
 * back down (C10 STAT-25, STAT-45, STAT-46).
 *
 * <p>Two parts. The Δv aboard is the rocket equation over the propellant the live drives can burn, one
 * figure per regime. The legs — the ascent to the orbit line, the landing to the surface, and the climb
 * back out after that landing — are the flight law run forward on the physics step: the body's field and
 * air, the flight computer's velocity law, and the hull's own heave recipe and its propellant flow, scaled
 * and held inside what is aboard as the control scheme scales one axis. A bill that disagreed with the
 * flight would be a second model of the same craft, which is why the steps are the flight's and not a
 * closed form of what a rocket "should" spend.</p>
 *
 * <p>It refuses nothing. A leg that does not reach says how far it gets, and nothing reads a bill to
 * decide anything.</p>
 *
 * <p>Immutable.</p>
 */
public final class FlightBill {

    /**
     * The most physics steps one bill follows, over all its legs together — what bounds its cost on the
     * game tick that computes it (C10 STAT-46). Room for the longest climb the bill exists for: Earth's line,
     * 100 000 blocks ({@code DescentShell#orbitLineWorldY}), at the velocity full vertical throttle commands,
     * 40 blocks per engine second = 22.15 m/s, is 4 515 s, or 150 000 steps of a sixtieth of an engine
     * second; this, 2²⁰, is seven times that.
     */
    public static final int MAX_STEPS = 1_048_576;

    /** How a leg ended. */
    public enum Outcome {
        /** A climb crossed the line. */
        REACHES,
        /** A climb's propellant ran out and it turned back below the line; the altitude is its apex. */
        RUNS_DRY,
        /** A climb turned back below the line with propellant left: its thrust cannot hold its weight. */
        STALLS,
        /** The bill's step budget ran out before the leg ended; the altitude is where it had got to. */
        BEYOND_HORIZON,
        /** A landing stopped at the surface. */
        STOPS,
        /** A landing reached the surface still falling; the speed is how fast. */
        CANNOT_STOP
    }

    /** Why a bill has no legs: there is no body below to climb out of. */
    public enum NoBody {
        /** No field here — a space cell, or gravity switched off. */
        NO_FIELD,
        /** This world has no orbit line, so no climb leaves it. */
        NO_LINE,
        /** The ship's pose or hull box could not be read. */
        NO_POSE
    }

    /** The flight computer's velocity law for the vertical axis, in SI. */
    @FunctionalInterface
    public interface ControlLaw {
        /**
         * The upward acceleration, m/s², the law asks of the hull this step to bring the craft from
         * {@code velocity} to {@code commanded} (both m/s, up positive) against the field.
         */
        double wanted(double commanded, double velocity);
    }

    /** The body a bill is about, as the flight law sees it. */
    public static final class Body {
        final double gravity;
        final double retention;
        final double stepSeconds;
        final double climbCommand;
        final ControlLaw law;
        final double startY;
        final double lineY;
        final double surfaceY;

        /**
         * @param gravity      the field's magnitude, m/s², straight down
         * @param retention    the share of its velocity the air leaves a craft after one step; 1 in vacuum
         * @param stepSeconds  one physics step, SI seconds
         * @param climbCommand the vertical velocity full upward throttle commands, m/s
         * @param law          the flight computer's velocity law
         * @param startY       where the craft's pose stands now, at rest
         * @param lineY        the altitude at which a climbing craft leaves the body
         * @param surfaceY     the pose altitude at which the craft rests on the ground below it
         */
        public Body(double gravity, double retention, double stepSeconds, double climbCommand, ControlLaw law,
                    double startY, double lineY, double surfaceY) {
            if (!(gravity > 0) || !(retention > 0) || retention > 1 || !(stepSeconds > 0) || !(climbCommand > 0)) {
                throw new IllegalArgumentException("a body bill needs a field, an air between none and all, a step and"
                        + " a climb: g=" + gravity + " retention=" + retention + " step=" + stepSeconds
                        + " climb=" + climbCommand);
            }
            this.gravity = gravity;
            this.retention = retention;
            this.stepSeconds = stepSeconds;
            this.climbCommand = climbCommand;
            this.law = law;
            this.startY = startY;
            this.lineY = lineY;
            this.surfaceY = surfaceY;
        }
    }

    /** One regime's Δv aboard. */
    public static final class Regime {
        private final String propellant;
        private final double exhaustVelocity;
        private final double usableKg;
        private final double deltaV;

        Regime(String propellant, double exhaustVelocity, double usableKg, double deltaV) {
            this.propellant = propellant;
            this.exhaustVelocity = exhaustVelocity;
            this.usableKg = usableKg;
            this.deltaV = deltaV;
        }

        public String propellant() {
            return propellant;
        }

        /** m/s, in vacuum. */
        public double exhaustVelocity() {
            return exhaustVelocity;
        }

        /** The most of this regime's propellant the pool supplies at its shares, kg. */
        public double usableKg() {
            return usableKg;
        }

        /** m/s. */
        public double deltaV() {
            return deltaV;
        }
    }

    /** One leg of the bill. */
    public static final class Leg {
        private final Outcome outcome;
        private final double deltaV;
        private final double propellantKg;
        private final double altitude;
        private final double speed;

        Leg(Outcome outcome, double deltaV, double propellantKg, double altitude, double speed) {
            this.outcome = outcome;
            this.deltaV = deltaV;
            this.propellantKg = propellantKg;
            this.altitude = altitude;
            this.speed = speed;
        }

        public Outcome outcome() {
            return outcome;
        }

        /** The Δv the leg's thrust delivered, m/s. */
        public double deltaV() {
            return deltaV;
        }

        /** What it ejected, kg. */
        public double propellantKg() {
            return propellantKg;
        }

        /**
         * Where it ended: the line for {@link Outcome#REACHES}, the apex for a climb that turned back,
         * where the craft stopped for {@link Outcome#STOPS}, the surface for {@link Outcome#CANNOT_STOP}.
         */
        public double altitude() {
            return altitude;
        }

        /** How fast it struck the surface, m/s, for {@link Outcome#CANNOT_STOP}; zero otherwise. */
        public double speed() {
            return speed;
        }
    }

    private final boolean metered;
    private final List<Regime> regimes;
    private final NoBody noBody;
    private final double startY;
    private final double lineY;
    private final double surfaceY;
    private final Leg ascent;
    private final Leg landing;
    private final Leg climbBack;

    private FlightBill(boolean metered, List<Regime> regimes, NoBody noBody, double startY, double lineY,
                       double surfaceY, Leg ascent, Leg landing, Leg climbBack) {
        this.metered = metered;
        this.regimes = Collections.unmodifiableList(new ArrayList<>(regimes));
        this.noBody = noBody;
        this.startY = startY;
        this.lineY = lineY;
        this.surfaceY = surfaceY;
        this.ascent = ascent;
        this.landing = landing;
        this.climbBack = climbBack;
    }

    /**
     * The bill of a craft whose LIVE capability is {@code live}, carrying {@code poolKg} (kg per fluid,
     * the pool the drives draw from; {@code null} when consumption is off), over {@code body}.
     */
    public static FlightBill over(ShipCapability live, Map<String, Double> poolKg, Body body) {
        int[] budget = {MAX_STEPS};
        Leg ascent = climb(new Craft(live, poolKg), body, body.startY, budget);
        Craft landed = new Craft(live, poolKg);
        Leg landing = land(landed, body, budget);
        Leg climbBack;
        if (landing.outcome != Outcome.STOPS) {
            climbBack = null;
        } else if (body.startY <= body.surfaceY) {
            climbBack = ascent;
        } else {
            climbBack = climb(landed, body, body.surfaceY, budget);
        }
        return new FlightBill(poolKg != null, regimes(live, poolKg), null, body.startY, body.lineY, body.surfaceY,
                ascent, landing, climbBack);
    }

    /** The Δv aboard alone, for a craft with no body below: {@code noBody} says why. */
    public static FlightBill withoutBody(ShipCapability live, Map<String, Double> poolKg, NoBody noBody) {
        return new FlightBill(poolKg != null, regimes(live, poolKg), noBody, 0, 0, 0, null, null, null);
    }

    /**
     * One row per distinct regime the live drives burn (C10 STAT-45): its exhaust velocity is its
     * full-throttle force in vacuum over its full-throttle flow; its usable propellant is the most of its
     * mixture the pool supplies; its Δv is {@code v_e·ln(m0/(m0 − usable))} with {@code m0} the frame's
     * whole mass. Empty when consumption is off: nothing is spent, so no figure is owed.
     */
    private static List<Regime> regimes(ShipCapability live, Map<String, Double> poolKg) {
        if (poolKg == null) {
            return Collections.emptyList();
        }
        double m0 = live.mass().getTotalMass();
        Map<String, Regime> rows = new TreeMap<>();
        for (Actuator a : live.actuators()) {
            for (PropellantDemand d : a.demands()) {
                double flow = d.massFlow(1);
                if (!(flow > 0)) {
                    continue;
                }
                double ve = d.forceAt(0) / flow;
                double usable = Double.POSITIVE_INFINITY;
                for (Map.Entry<String, Double> share : d.shares().entrySet()) {
                    usable = Math.min(usable, poolKg.getOrDefault(share.getKey(), 0.0D) / share.getValue());
                }
                String key = d.propellant() + '@' + ve;
                if (!rows.containsKey(key)) {
                    rows.put(key, new Regime(d.propellant(), ve, usable, ve * Math.log(m0 / (m0 - usable))));
                }
            }
        }
        return new ArrayList<>(rows.values());
    }

    /**
     * A climb from rest at {@code fromY}: full upward throttle until a coast would carry the craft to the
     * line, then the coast — the least a climb on the climb command spends. Each step the air takes its
     * share of the velocity, the law asks for what brings it to the climb command, the hull delivers what
     * its heave and its propellant allow, and the field pulls — the order the physics step applies them in.
     * Followed at full throttle first; the earliest step from which a coast reaches the line is then found
     * among the steps that crossed, and the burn to it flown again for what it cost.
     */
    private static Leg climb(Craft craft, Body body, double fromY, int[] budget) {
        if (fromY >= body.lineY) {
            return new Leg(Outcome.REACHES, 0, 0, body.lineY, 0);
        }
        Craft start = craft.copy();
        double startKg = craft.ejectedKg;
        double[] ys = new double[1024];
        double[] vs = new double[1024];
        int steps = 0;
        double y = fromY;
        double v = 0;
        double apex = fromY;
        double spent = 0;
        while (budget[0] > 0) {
            budget[0]--;
            double carried = v * body.retention;
            double a = craft.thrust(body.law.wanted(body.climbCommand, carried), body.stepSeconds);
            spent += a * body.stepSeconds;
            v = carried + (a - body.gravity) * body.stepSeconds;
            y += v * body.stepSeconds;
            apex = Math.max(apex, y);
            if (steps == ys.length) {
                ys = java.util.Arrays.copyOf(ys, 2 * steps);
                vs = java.util.Arrays.copyOf(vs, 2 * steps);
            }
            ys[steps] = y;
            vs[steps] = v;
            steps++;
            if (y >= body.lineY) {
                int burnt = steps;
                int early = 0;
                while (burnt - early > 1) {
                    int mid = (early + burnt) >>> 1;
                    if (coastReaches(ys[mid - 1], vs[mid - 1], body, budget)) {
                        burnt = mid;
                    } else {
                        early = mid;
                    }
                }
                return burnFor(start, body, burnt, budget);
            }
            if (v <= 0) {
                return new Leg(craft.dry ? Outcome.RUNS_DRY : Outcome.STALLS, spent, craft.ejectedKg - startKg,
                        apex, 0);
            }
        }
        return new Leg(Outcome.BEYOND_HORIZON, spent, craft.ejectedKg - startKg, apex, 0);
    }

    /** Whether a craft at {@code y} rising at {@code v} with nothing pushing it reaches the line. */
    private static boolean coastReaches(double y, double v, Body body, int[] budget) {
        while (v > 0 && budget[0] > 0) {
            budget[0]--;
            v = v * body.retention - body.gravity * body.stepSeconds;
            y += v * body.stepSeconds;
            if (y >= body.lineY) {
                return true;
            }
        }
        return false;
    }

    /** The first {@code steps} steps of the climb, flown again for the Δv and the propellant they cost. */
    private static Leg burnFor(Craft craft, Body body, int steps, int[] budget) {
        double startKg = craft.ejectedKg;
        double v = 0;
        double spent = 0;
        for (int n = 0; n < steps; n++) {
            budget[0]--;
            double carried = v * body.retention;
            double a = craft.thrust(body.law.wanted(body.climbCommand, carried), body.stepSeconds);
            spent += a * body.stepSeconds;
            v = carried + (a - body.gravity) * body.stepSeconds;
        }
        return new Leg(Outcome.REACHES, spent, craft.ejectedKg - startKg, body.lineY, 0);
    }

    /**
     * A landing from rest at the body's start, as descents from rest repeated until one makes no headway: a
     * stop above the surface is a hover, and the craft goes on down from there on what it has left — so a
     * craft whose propellant stops it high up is not landed by having stopped. The last descent ends within
     * a step's free fall of the surface, or on it, still falling. {@code craft} is left as the landing left it.
     */
    private static Leg land(Craft craft, Body body, int[] budget) {
        double startKg = craft.ejectedKg;
        double y = body.startY;
        double spent = 0;
        while (y > body.surfaceY) {
            Leg leg = descend(craft, body, y, budget);
            spent += leg.deltaV;
            if (leg.outcome != Outcome.STOPS || leg.altitude >= y) {
                return new Leg(leg.outcome, spent, craft.ejectedKg - startKg, leg.altitude, leg.speed);
            }
            y = leg.altitude;
        }
        return new Leg(Outcome.STOPS, spent, craft.ejectedKg - startKg, y, 0);
    }

    /**
     * One descent from rest at {@code fromY}: a free fall with no linear command, then a brake — the law
     * asked for zero velocity — lit at the LATEST step of the fall from which it still stops at or above the
     * surface. When no step after the first stops, the surface is within that step's reach — or the
     * propellant is: a brake lit one step down that strikes for want of it is the descent the craft has.
     */
    private static Leg descend(Craft craft, Body body, double fromY, int[] budget) {
        List<double[]> fall = new ArrayList<>();
        double y = fromY;
        double v = 0;
        fall.add(new double[] {y, v});
        while (y > body.surfaceY) {
            if (budget[0] <= 0) {
                return new Leg(Outcome.BEYOND_HORIZON, 0, 0, y, 0);
            }
            budget[0]--;
            v = v * body.retention - body.gravity * body.stepSeconds;
            y += v * body.stepSeconds;
            fall.add(new double[] {y, v});
        }
        int lit = 0;
        int late = fall.size() - 1;
        Leg fromRest = brakeFrom(craft.copy(), body, fall.get(lit), budget);
        boolean bisect = fromRest.outcome == Outcome.STOPS;
        while (bisect && late - lit > 1) {
            int mid = (lit + late) >>> 1;
            Leg trial = brakeFrom(craft.copy(), body, fall.get(mid), budget);
            if (trial.outcome == Outcome.STOPS) {
                lit = mid;
            } else {
                late = mid;
            }
        }
        if (bisect && lit == 0 && late == 1 && fall.size() > 2) {
            Craft stepDown = craft.copy();
            Leg next = brakeFrom(stepDown, body, fall.get(1), budget);
            if (next.outcome == Outcome.CANNOT_STOP && stepDown.dry) {
                return brakeFrom(craft, body, fall.get(1), budget);
            }
        }
        return brakeFrom(craft, body, fall.get(lit), budget);
    }

    /**
     * Brake from {@code from} ({@code {y, v}}): stopped on the first step the hull delivers all the law asked,
     * struck if the surface comes first.
     */
    private static Leg brakeFrom(Craft craft, Body body, double[] from, int[] budget) {
        double startKg = craft.ejectedKg;
        double y = from[0];
        double v = from[1];
        double spent = 0;
        while (budget[0] > 0) {
            budget[0]--;
            double carried = v * body.retention;
            double a = craft.thrust(body.law.wanted(0, carried), body.stepSeconds);
            spent += a * body.stepSeconds;
            v = carried + (a - body.gravity) * body.stepSeconds;
            y += v * body.stepSeconds;
            if (y <= body.surfaceY && v < 0) {
                return new Leg(Outcome.CANNOT_STOP, spent, craft.ejectedKg - startKg, body.surfaceY, -v);
            }
            if (!craft.saturated) {
                return new Leg(Outcome.STOPS, spent, craft.ejectedKg - startKg, Math.max(y, body.surfaceY), 0);
            }
        }
        return new Leg(Outcome.BEYOND_HORIZON, spent, craft.ejectedKg - startKg, y, 0);
    }

    /**
     * The craft as a leg flies it: its mass, what is left in its pool, and the one axis the bill drives —
     * the sustained heave recipe's force and its flow per fluid, both linear in the fraction of the recipe
     * a step uses, which is how the control scheme delivers one axis (C10 STAT-18, STAT-37, STAT-39).
     */
    private static final class Craft {
        private final double force;
        private final double[] flowKgPerSecond;
        /** kg left per fluid of {@link #flowKgPerSecond}; {@code null} when consumption is off. */
        private final double[] pool;
        private double mass;
        private double ejectedKg;
        private boolean dry;
        private boolean saturated;

        Craft(ShipCapability live, Map<String, Double> poolKg) {
            Recipe heave = live.recipe(ControlDirection.HEAVE_POSITIVE, Endurance.SUSTAINED);
            this.force = heave.authority;
            Map<String, Double> flow = PropellantBudget.ejected(live.actuators(), heave.throttles, 1.0D);
            this.flowKgPerSecond = new double[flow.size()];
            this.pool = poolKg == null ? null : new double[flow.size()];
            int i = 0;
            for (Map.Entry<String, Double> fluid : flow.entrySet()) {
                flowKgPerSecond[i] = fluid.getValue();
                if (pool != null) {
                    pool[i] = poolKg.getOrDefault(fluid.getKey(), 0.0D);
                }
                i++;
            }
            this.mass = live.mass().getTotalMass();
        }

        private Craft(Craft of) {
            this.force = of.force;
            this.flowKgPerSecond = of.flowKgPerSecond;
            this.pool = of.pool == null ? null : of.pool.clone();
            this.mass = of.mass;
            this.ejectedKg = of.ejectedKg;
            this.dry = of.dry;
        }

        Craft copy() {
            return new Craft(this);
        }

        /**
         * One step at {@code want} m/s² up: the fraction of the recipe that delivers it, at most one; then
         * held inside what the pool still has, by the one factor the scarcest fluid allows; then ejected.
         *
         * @return the upward acceleration delivered, m/s², on the mass the step began with
         */
        double thrust(double want, double seconds) {
            double asked = force > 0 && want > 0 ? mass * want / force : 0;
            double fraction = Math.min(1.0D, asked);
            saturated = asked > 1.0D;
            if (pool != null && fraction > 0) {
                double lambda = 1.0D;
                for (int i = 0; i < pool.length; i++) {
                    double requested = fraction * flowKgPerSecond[i] * seconds;
                    if (requested > 0) {
                        lambda = Math.min(lambda, Math.max(0.0D, pool[i]) / requested);
                    }
                }
                if (lambda < 1.0D) {
                    fraction *= lambda;
                    saturated = true;
                    dry = true;
                }
            }
            double a = fraction * force / mass;
            if (pool != null) {
                for (int i = 0; i < pool.length; i++) {
                    double kg = fraction * flowKgPerSecond[i] * seconds;
                    pool[i] -= kg;
                    mass -= kg;
                    ejectedKg += kg;
                }
            }
            return a;
        }
    }

    /** Whether consumption is metered — when not, {@link #regimes()} is empty and nothing is spent. */
    public boolean metered() {
        return metered;
    }

    /** The Δv aboard, one row per regime the live drives burn, sorted by propellant. */
    public List<Regime> regimes() {
        return regimes;
    }

    /** Why there are no legs, or {@code null} when there are. */
    public NoBody noBody() {
        return noBody;
    }

    public double startY() {
        return startY;
    }

    public double lineY() {
        return lineY;
    }

    public double surfaceY() {
        return surfaceY;
    }

    /** From here, at rest, to the line; {@code null} with {@link #noBody()}. */
    public Leg ascent() {
        return ascent;
    }

    /** From here, at rest, to the surface below; {@code null} with {@link #noBody()}. */
    public Leg landing() {
        return landing;
    }

    /**
     * After that landing, from the surface to the line on what is left — the ascent itself for a craft
     * already on the surface; {@code null} when the landing does not stop, or with {@link #noBody()}.
     */
    public Leg climbBack() {
        return climbBack;
    }

    /** Whether a craft that lands here can climb back out: the landing stops and the climb back reaches. */
    public boolean canClimbBackOut() {
        return climbBack != null && climbBack.outcome == Outcome.REACHES;
    }

    public void write(DataOutput out) throws IOException {
        out.writeBoolean(metered);
        out.writeInt(regimes.size());
        for (Regime r : regimes) {
            out.writeUTF(r.propellant);
            out.writeDouble(r.exhaustVelocity);
            out.writeDouble(r.usableKg);
            out.writeDouble(r.deltaV);
        }
        out.writeByte(noBody == null ? -1 : noBody.ordinal());
        if (noBody != null) {
            return;
        }
        out.writeDouble(startY);
        out.writeDouble(lineY);
        out.writeDouble(surfaceY);
        writeLeg(ascent, out);
        writeLeg(landing, out);
        out.writeBoolean(climbBack != null);
        if (climbBack != null) {
            writeLeg(climbBack, out);
        }
    }

    public static FlightBill read(DataInput in) throws IOException {
        boolean metered = in.readBoolean();
        List<Regime> regimes = new ArrayList<>();
        for (int i = in.readInt(); i > 0; i--) {
            regimes.add(new Regime(in.readUTF(), in.readDouble(), in.readDouble(), in.readDouble()));
        }
        int absent = in.readByte();
        if (absent >= 0) {
            return new FlightBill(metered, regimes, NoBody.values()[absent], 0, 0, 0, null, null, null);
        }
        double startY = in.readDouble();
        double lineY = in.readDouble();
        double surfaceY = in.readDouble();
        Leg ascent = readLeg(in);
        Leg landing = readLeg(in);
        Leg climbBack = in.readBoolean() ? readLeg(in) : null;
        return new FlightBill(metered, regimes, null, startY, lineY, surfaceY, ascent, landing, climbBack);
    }

    private static void writeLeg(Leg leg, DataOutput out) throws IOException {
        out.writeByte(leg.outcome.ordinal());
        out.writeDouble(leg.deltaV);
        out.writeDouble(leg.propellantKg);
        out.writeDouble(leg.altitude);
        out.writeDouble(leg.speed);
    }

    private static Leg readLeg(DataInput in) throws IOException {
        return new Leg(Outcome.values()[in.readByte()], in.readDouble(), in.readDouble(), in.readDouble(),
                in.readDouble());
    }
}
