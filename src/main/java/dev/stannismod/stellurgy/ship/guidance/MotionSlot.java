package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.api.FreeFlightPhysics;
import dev.stannismod.stellurgy.integration.vs.PhysicsUnits;
import dev.stannismod.stellurgy.ship.control.ControlAxis;
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.FlightCommand;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.space.GalacticCoord;
import net.minecraft.nbt.NBTTagCompound;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A craft's MOTION slot: at most one {@link MotionIntent}, and the guidance that flies it.
 *
 * <p>Guidance turns the intent into the same {@link FlightCommand} every other producer on a flight
 * computer publishes, and stops there: the flight law below that seam reads the hull's live authority,
 * so a weak hull is flown weakly without guidance having to know why. What guidance does need to know
 * about the hull is which way it pushes hardest and how hard it can BRAKE, because an order to arrive
 * is an order to stop, and both are read from the same live capability the law allocates over.</p>
 *
 * <h2>What the slot holds</h2>
 *
 * <ul>
 *   <li><b>EMPTY</b> — nothing to fly; the craft is its pilot's, or holds as it would unattended.</li>
 *   <li><b>ACTIVE</b> — an intent is being flown. It stays after the craft gets there: a GO_TO that has
 *       arrived keeps the craft there — alongside its ship, or resting on the ground it landed on —
 *       until somebody decides otherwise.</li>
 *   <li><b>REFUSED</b> — the intent could not be flown and the slot SAYS so, holding the intent and the
 *       reason until the next decision, instead of emptying as if it had been done. A refused craft
 *       hovers where it is, at rest, in the attitude it was refused in, until its pilot takes it back.</li>
 * </ul>
 *
 * <p>A pilot taking the stick empties the slot ({@link #reclaim}); nothing in it resumes afterwards.</p>
 *
 * <h2>Pointing the hull: its strong axis carries the thrust</h2>
 *
 * <p>No order here holds attitude, so guidance chooses it: the hull is pointed so that the signed axis
 * with the most authority along the way the plan needs to push lies along that way — toward the aim
 * while closing, away from it while braking. A hull with no reverse thrust therefore turns over and
 * brakes on its main engine, and the turn's time is part of the braking plan. In space every one of the
 * six signed axes may be turned onto the way; in a field the hull keeps one axis pointing up against
 * the field (the one up now, unless that one cannot hold the weight) and is turned about the vertical
 * only, so it flies level.</p>
 *
 * <h2>Leaning: only when level cannot deliver</h2>
 *
 * <p>Level is the default in a field, because a level hull never comes down nose-first. But a hull whose
 * only strong axis is the one holding it up (heave-only engines) delivers nothing level along a way
 * across the vertical. Such a hull — and only such a hull: one for which no level attitude can close
 * on the aim, or stop — LEANS: the axis holding it up is tilted toward the thrust the plan needs, so its
 * one push both holds the weight and carries it along. The lean is chosen afresh on every tick from the
 * acceleration the craft lacks, and is never steeper than the angle at which that axis, pushing its
 * hardest, still holds the weight. Swinging the lean from closing to braking is a turn like a
 * turnover's, and is planned the same way.</p>
 *
 * <h2>Frames</h2>
 *
 * <p>Every position, velocity and acceleration here is in the WORLD frame of the world the craft flies
 * in, blocks and engine seconds — the frame of the craft's pose and of {@link FlightCommand}'s velocity.
 * The target is placed into that frame on every tick by {@link GuidanceTarget#placeIn}. The hull's
 * authority is stated in its own frame (its capability's control frame) and is carried into the world
 * through the attitude guidance commands, never the one the craft happens to have.</p>
 *
 * <p>Not thread-safe: owned by one flight computer and used on the server game thread only.</p>
 */
public final class MotionSlot {

    /** What the slot holds. */
    public enum State {
        EMPTY,
        ACTIVE,
        REFUSED
    }

    /** Why an intent was refused. */
    public enum Refusal {
        /** In a field, no attitude guidance can give the hull lets it hold its own weight. */
        CANNOT_HOLD_ITS_WEIGHT,
        /** No attitude guidance can give the hull delivers thrust toward the aim. */
        NO_APPROACH_AUTHORITY,
        /** No attitude guidance can give the hull, and turn it into, delivers thrust that stops it. */
        NO_STOPPING_AUTHORITY,
        /** The target is in another world: reaching it takes a crossing — an entry, a descent, a jump. */
        NEEDS_A_CROSSING,
        /** No craft by the target's name is in this world, in space, or in any loaded world. */
        TARGET_NOT_FOUND,
        /** The target names this craft. */
        TARGET_IS_THIS_CRAFT,
        /** The craft is in space but the target cannot be placed against its cell's frame. */
        TARGET_HAS_NO_FRAME,
        /** The craft's own hull could not be measured, so no distance outside it can be derived. */
        CRAFT_NOT_MEASURED
    }

    /** What one tick of guidance did that somebody outside should hear about. */
    public enum Event {
        NONE,
        /** The craft reached what its intent wants, at rest: posted once per intent. */
        ARRIVED,
        /** The intent was refused on this tick; the slot now reads REFUSED. */
        REFUSED
    }

    /**
     * Where a craft counts as arrived: within this many blocks of what its intent wants. The arrival a
     * player is promised, stated by the maintainer as the acceptance of a GO_TO (2026-10-21): "ends
     * within 2 blocks of it at below 0.2 blocks/s". It is also the clearance a ship target's standoff
     * keeps past the two hulls, so that an arrival at the near edge of the radius still leaves them
     * apart (ruled the same day: the standoff is the two hulls' extents plus the arrival radius).
     */
    static final double ARRIVAL_RADIUS = 2.0D;

    /** The speed below which a craft inside {@link #ARRIVAL_RADIUS} counts as at rest, blocks per engine
     *  second — the other half of the same statement. */
    static final double ARRIVAL_SPEED = 0.2D;

    /**
     * How fast the craft closes the last of the distance: commanded speed per block still to go, per
     * engine second. A bandwidth, not a physical quantity, and its one bound is the rate guidance
     * publishes at — once per game tick, 1/20 s. A first-order position loop sampled at that period is
     * stable only while gain × period stays well under one; at 2/s it is 0.1, so each publication
     * corrects a tenth of the remaining error and the approach neither rings nor stalls. The time
     * constant it buys (0.5 s) is what settling from the arrival radius to rest costs.
     */
    static final double POSITION_GAIN = 2.0D;

    /**
     * How near the height of its aim a LANDING craft must be to count as down, blocks: the farthest from
     * its aim a craft flying the close-in law can be while slower than {@link #ARRIVAL_SPEED} — the law
     * commands {@code POSITION_GAIN} blocks/s per block left. A landing is released onto the ground when
     * it arrives, so the arrival radius a hover is judged by would drop a craft from as high as that
     * radius. Across, a landing is judged by the arrival radius like any arrival.
     */
    static final double TOUCHDOWN = ARRIVAL_SPEED / POSITION_GAIN;

    /**
     * The share of the hull's braking authority the approach is planned on. A design margin, with no
     * derivation: the law below spends the same authority holding the craft against gravity and
     * correcting whatever the plan did not foresee, and the attitude the plan assumes may still be
     * settling. Half leaves the stop as much in hand as it uses.
     */
    static final double STOP_MARGIN = 0.5D;

    /**
     * When a turn counts as done for the braking plan: the strong axis within this angle of the way it
     * must push, radians. From there on it delivers at least cos 15° = 97 % of its authority along that
     * way, so the rest of the turn costs the stop less than a tenth of the {@link #STOP_MARGIN} it has in
     * hand. A design choice under that bound, with no other derivation.
     */
    static final double TURNED_WITHIN = Math.PI / 12.0D;

    /** Steps of the midpoint sum that integrates a turn's time; the integrand is monotone and smooth. */
    private static final int TURN_STEPS = 64;

    /**
     * How long, in guidance ticks, an intent may stay unflyable before it is refused — the "sustained"
     * of "authority short for a sustained window", which one physics step's shortfall is not.
     *
     * <p>Its floor is the longest a craft that CAN fly the intent may read short for reasons that pass
     * on their own: a hull change the survey coalesces over {@code SURVEY_MIN_TICKS} (10,
     * {@code TileAdvancedFlightComputer}) before the model holds the new authority, and the few ticks
     * a crossing leaves a craft — or a target ship — between its cut and its settle. Shortness is judged
     * over every attitude the plan may fly in, not the one the craft happens to have, so no turn has to
     * be waited out. A design choice above that floor, with no derivation: two seconds. Its ceiling is
     * the player's: an unflyable order is reported within the ten seconds the maintainer stated
     * (2026-10-21).</p>
     *
     * <p>Public because it is a promise to whoever acts on a refusal: an intent that cannot be flown
     * is reported this many ticks after it stopped being flyable, and not before.</p>
     */
    public static final int REFUSAL_WINDOW_TICKS = 40;

    /** Below this a component of a unit direction along an axis counts as none. */
    private static final double AXIS_EPSILON = 1.0e-9D;

    /** Two reaches this close, relative, are the same reach: the hull is symmetric there. */
    private static final double TIE = 1.0e-9D;

    private static final String NBT_STATE = "state";
    private static final String NBT_REFUSAL = "refusal";
    private static final String NBT_ARRIVED = "arrived";
    private static final String NBT_INTENT = "intent";

    private MotionIntent intent = null;
    private State state = State.EMPTY;
    private Refusal refusal = null;
    private boolean arrived = false;
    /** Consecutive guidance ticks the intent has been unflyable. Not persisted: a reload restarts the
     *  window, which delays a refusal by at most one window and never invents one. */
    private int shortTicks = 0;
    /** The attitude a refused craft hovers in, or a landed one rests in. Not persisted: a reload takes
     *  the attitude the craft has then, which is the one it was holding. */
    private FreeFlightPhysics.Quat held = null;

    /** Hold {@code intent}, replacing whatever the slot held, and start flying it. */
    public void issue(MotionIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("issue an intent, or reclaim the slot to empty it");
        }
        this.intent = intent;
        this.state = State.ACTIVE;
        this.refusal = null;
        this.arrived = false;
        this.shortTicks = 0;
        this.held = null;
    }

    /**
     * The pilot takes the craft back: the slot empties, whatever it held, and nothing in it resumes.
     *
     * @return whether the slot held anything
     */
    public boolean reclaim() {
        boolean had = state != State.EMPTY;
        intent = null;
        state = State.EMPTY;
        refusal = null;
        arrived = false;
        shortTicks = 0;
        held = null;
        return had;
    }

    /**
     * Whether guidance flies the craft: the slot holds an intent, being flown or refused — a refused
     * craft is flown too, in a hover, until its pilot takes it back.
     */
    public boolean holdsAnIntent() {
        return state != State.EMPTY;
    }

    /** What the slot holds right now. */
    public Reading reading() {
        return new Reading(state, intent, refusal, arrived);
    }

    /**
     * One tick of guidance: the command that flies the intent from where the craft is, and what
     * happened that somebody should hear about.
     *
     * <p>An empty slot answers no command and no event. A refused slot answers a hover: zero velocity,
     * the attitude it was refused in. A landed GO_TO answers a rest: no velocity command at all, so the
     * craft lies on the ground under its own weight, in the attitude it landed in. A tick on which the
     * intent cannot be flown — the target cannot be placed in the craft's world, or no attitude lets the
     * hull hold its weight, approach or stop — commands the same hover in the attitude the craft has, and
     * after {@link #REFUSAL_WINDOW_TICKS} such ticks in a row refuses the intent with the REFUSED event
     * and the reason of the tick that refused it.</p>
     *
     * @param craft  the craft, in its own world's frame
     * @param around what guidance may ask of the world around the craft
     */
    public Step fly(Craft craft, Surroundings around) {
        if (state == State.EMPTY) {
            return new Step(null, Event.NONE);
        }
        if (state == State.REFUSED) {
            return new Step(hover(heldOr(craft.attitude)), Event.NONE);
        }
        if (arrived && intent.isLanding()) {
            return new Step(rest(heldOr(craft.attitude)), Event.NONE);
        }
        GuidanceTarget.Placement placed = intent.target().placeIn(craft, around);
        Refusal shortBy = null;
        FlightCommand command = null;
        double distance = Double.POSITIVE_INFINITY;
        // A landing's arrival is judged on two distances, not one: how far the craft still is above its
        // aim, and whether it is let down at all (see below). Infinite until a landing is placed.
        double above = Double.POSITIVE_INFINITY;
        boolean heldOver = false;
        if (placed.unplaced() != null) {
            shortBy = refusalFor(placed.unplaced());
        } else {
            // With no hull measured there is no shell to aim at, for a GO_TO or a HOLD: the craft makes for
            // the placed point itself, which is how it comes to share the target's world.
            double[] aim = placed.provisional() ? placed.position()
                    : intent.aimFrom(craft.position, placed, ARRIVAL_RADIUS);
            double[] over = placed.over();
            if (over != null) {
                // A landing is let down only over its column — the column's own frame: X and Z across, Y
                // up. Farther across than half the arrival radius the craft is held at the point over the
                // column, clear of the ground; nearer, it is let down, with the other half of the radius
                // for whatever it drifts while it comes down. A landing counts as there anywhere within
                // the radius, as any arrival does, so its touchdown is judged on height alone.
                above = Math.abs(craft.position[1] - aim[1]);
                if (Math.hypot(craft.position[0] - aim[0], craft.position[2] - aim[2]) > ARRIVAL_RADIUS / 2.0D) {
                    aim = over;
                    heldOver = true;
                }
            }
            double ex = aim[0] - craft.position[0];
            double ey = aim[1] - craft.position[1];
            double ez = aim[2] - craft.position[2];
            distance = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (distance <= ARRIVAL_RADIUS) {
                command = settle(craft, new double[]{ex, ey, ez}, distance);
            } else {
                double[] u = {ex / distance, ey / distance, ez / distance};
                Plan plan = Plan.approach(craft, u);
                shortBy = plan.refusal;
                if (shortBy == null) {
                    command = plan.command(craft, u, distance);
                }
            }
        }
        if (shortBy != null) {
            shortTicks++;
            if (shortTicks >= REFUSAL_WINDOW_TICKS) {
                state = State.REFUSED;
                refusal = shortBy;
                held = craft.attitude;
                return new Step(hover(held), Event.REFUSED);
            }
            return new Step(hover(craft.attitude), Event.NONE);
        }
        shortTicks = 0;
        boolean there = intent.isLanding() ? !heldOver && above <= TOUCHDOWN : distance <= ARRIVAL_RADIUS;
        // Against a provisional placement no hull was measured, so nothing says where alongside it is:
        // reaching the aim is not arriving.
        if (!arrived && !placed.provisional() && there && magnitude(craft.velocity) <= ARRIVAL_SPEED) {
            arrived = true;
            if (intent.isLanding()) {
                held = craft.attitude;
                return new Step(rest(held), Event.ARRIVED);
            }
            return new Step(command, Event.ARRIVED);
        }
        return new Step(command, Event.NONE);
    }

    private FreeFlightPhysics.Quat heldOr(FreeFlightPhysics.Quat now) {
        if (held == null) {
            held = now;
        }
        return held;
    }

    /**
     * The command inside the arrival radius, along {@code e} (craft to aim, {@code distance} long). No
     * turn is asked for — the attitude law only brakes whatever spin is left — unless the attitude the
     * craft has cannot push toward the aim at all and the hull can lean: then it leans, as on the
     * approach.
     */
    private static FlightCommand settle(Craft craft, double[] e, double distance) {
        Lean lean = distance > 0.0D ? Lean.of(craft) : null;
        if (lean != null && !(reach(craft.live, craft.attitude,
                new double[]{e[0] / distance, e[1] / distance, e[2] / distance}, craft.gravity) > 0.0D)) {
            double[] u = {e[0] / distance, e[1] / distance, e[2] / distance};
            double speed = Math.min(craft.topSpeed, lean.positionGain * distance);
            double[] v = scaled(u, speed);
            return FlightCommand.of(v, zeroRate(), quatArray(lean.toward(lean.wanted(craft, v))));
        }
        double speed = Math.min(craft.topSpeed, POSITION_GAIN * distance);
        double s = distance > 0.0D ? speed / distance : 0.0D;
        return FlightCommand.of(new double[]{e[0] * s, e[1] * s, e[2] * s}, zeroRate(), quatArray(craft.attitude));
    }

    private static Refusal refusalFor(GuidanceTarget.Unplaced why) {
        switch (why) {
            case NEEDS_A_CROSSING:
                return Refusal.NEEDS_A_CROSSING;
            case NOT_FOUND:
                return Refusal.TARGET_NOT_FOUND;
            case IS_THE_CRAFT:
                return Refusal.TARGET_IS_THIS_CRAFT;
            case NO_FRAME:
                return Refusal.TARGET_HAS_NO_FRAME;
            default:
                return Refusal.CRAFT_NOT_MEASURED;
        }
    }

    /**
     * The approach along {@code u} toward an aim beyond the arrival radius: which attitude the hull
     * closes in and which it brakes in, what each delivers along the way, and how long the turn between
     * them takes — or why the hull cannot fly it at all.
     */
    private static final class Plan {

        final Heading close;
        final Heading brake;
        /** Engine seconds to turn from {@link #close} to {@link #brake}. */
        final double turn;
        final Refusal refusal;
        /** How the hull leans on this approach; {@code null} for a level (or, in space, free) one. */
        final Lean lean;

        private Plan(Heading close, Heading brake, double turn, Refusal refusal, Lean lean) {
            this.close = close;
            this.brake = brake;
            this.turn = turn;
            this.refusal = refusal;
            this.lean = lean;
        }

        /** The level approach when there is one; else a leaning one, when the hull can lean; else why not. */
        static Plan approach(Craft craft, double[] u) {
            Plan level = of(craft, u);
            if (level.refusal == null || level.refusal == Refusal.CANNOT_HOLD_ITS_WEIGHT) {
                return level;
            }
            Lean lean = Lean.of(craft);
            Plan leaning = lean == null ? null : leaning(craft, u, lean);
            return leaning != null ? leaning : level;
        }

        /** The approach in the candidates of {@link Heading#best}: free in space, level in a field. */
        private static Plan of(Craft craft, double[] u) {
            if (craft.live == null || !(craft.live.mass().getTotalMass() > 0.0D)) {
                return refused(Refusal.NO_APPROACH_AUTHORITY);
            }
            Heading close = Heading.best(craft, u);
            Heading brake = Heading.best(craft, negate(u));
            if (close == null || brake == null) {
                return refused(Refusal.CANNOT_HOLD_ITS_WEIGHT);
            }
            if (!(close.reach > 0.0D)) {
                return refused(Refusal.NO_APPROACH_AUTHORITY);
            }
            double turn = brake.reach > 0.0D ? turnTime(craft, close.attitude, brake.attitude)
                    : Double.POSITIVE_INFINITY;
            if (Double.isInfinite(turn)) {
                // The strong braking attitude is one this hull cannot turn into: brake in the attitude it
                // closes in, if that brakes at all.
                double inPlace = reach(craft.live, close.attitude, negate(u), craft.gravity);
                if (!(inPlace > 0.0D)) {
                    return refused(Refusal.NO_STOPPING_AUTHORITY);
                }
                brake = new Heading(close.attitude, inPlace);
                turn = 0.0D;
            }
            return new Plan(close, brake, turn, null, null);
        }

        /**
         * The approach of a hull that leans: it closes leaned toward the aim at the most the lean gives,
         * and brakes leaned away from it at the {@link #STOP_MARGIN} share the plan brakes on; the swing
         * between the two is timed like a turnover. {@code null} when leaning cannot close, cannot stop,
         * or is a turn the hull cannot make.
         */
        private static Plan leaning(Craft craft, double[] u, Lean lean) {
            double closing = lean.reach(u);
            double braking = lean.reach(negate(u));
            if (!(closing > 0.0D) || !(braking > 0.0D)) {
                return null;
            }
            FreeFlightPhysics.Quat closeIn = lean.toward(scaled(u, closing));
            FreeFlightPhysics.Quat brakeIn = lean.toward(scaled(u, -STOP_MARGIN * braking));
            if (Double.isInfinite(turnTime(craft, craft.attitude, closeIn))) {
                return null;
            }
            double turn = turnTime(craft, closeIn, brakeIn);
            if (Double.isInfinite(turn)) {
                return null;
            }
            return new Plan(new Heading(closeIn, closing), new Heading(brakeIn, braking), turn, null, lean);
        }

        private static Plan refused(Refusal why) {
            return new Plan(null, null, 0.0D, why, null);
        }

        /**
         * The command for this tick. The braking plan is {@code a = STOP_MARGIN × brake reach}. A craft
         * closing at {@code v} that must first turn for {@code T} seconds, coasting, and then brake at
         * {@code a} needs {@code v·T + v²/(2a)} blocks to stop; the fastest speed that fits in the
         * distance {@code d} left is therefore {@code v_T(d) = √((a·T)² + 2·a·d) − a·T}, and with no
         * turn {@code v_0(d) = √(2·a·d)}. While the craft closes slower than {@code v_T(d)} it is pointed
         * to close and commanded the least of its top speed, {@code v_T(d)} and the close-in
         * {@code POSITION_GAIN·d}; from the moment it reaches {@code v_T(d)} it is pointed to brake and
         * commanded the least of its top speed, {@code v_0(d)}, the close-in and the speed it already
         * has — so the turn is coasted, never thrust through, and the stop begins where the plan allowed
         * for it.
         *
         * <p>A leaning plan commands the same speeds, with the close-in gain its lean loop allows
         * ({@link Lean#positionGain}), and in place of a fixed attitude the lean toward the acceleration
         * the craft lacks for that speed ({@link Lean#wanted}).</p>
         */
        FlightCommand command(Craft craft, double[] u, double d) {
            double a = STOP_MARGIN * brake.reach;
            double turned = Math.sqrt(a * turn * a * turn + 2.0D * a * d) - a * turn;
            double direct = Math.sqrt(2.0D * a * d);
            double closing = dot(craft.velocity, u);
            double closeIn = (lean == null ? POSITION_GAIN : lean.positionGain) * d;
            double speed;
            FreeFlightPhysics.Quat attitude;
            if (closing >= turned) {
                speed = Math.min(Math.min(craft.topSpeed, direct), Math.min(closeIn, closing));
                attitude = brake.attitude;
            } else {
                speed = Math.min(craft.topSpeed, Math.min(turned, closeIn));
                attitude = close.attitude;
            }
            double[] v = {u[0] * speed, u[1] * speed, u[2] * speed};
            if (lean != null) {
                attitude = lean.toward(lean.wanted(craft, v));
            }
            return FlightCommand.of(v, zeroRate(), quatArray(attitude));
        }
    }

    /** An attitude, and the acceleration it lets the hull add along one way while holding its weight. */
    private static final class Heading {

        final FreeFlightPhysics.Quat attitude;
        final double reach;

        Heading(FreeFlightPhysics.Quat attitude, double reach) {
            this.attitude = attitude;
            this.reach = reach;
        }

        /**
         * The attitude in which the hull's strongest signed axis carries thrust along {@code w}, and what
         * it delivers there; {@code null} in a field where no attitude guidance may give the hull holds
         * its weight. Candidates: in space, each signed translation axis turned onto {@code w} by the
         * shortest arc; in a field, the level attitude ({@link #level}) turned about the vertical so that
         * each axis across the vertical lies along the horizontal part of {@code w}, or the level attitude
         * alone when {@code w} is vertical. Only a candidate the hull can turn into from the attitude it
         * has counts; with none, that attitude itself is the one candidate. The largest reach wins; equal
         * reaches go to the smaller turn from the attitude the craft has, then to the earlier axis.
         */
        static Heading best(Craft craft, double[] w) {
            double g = magnitude(craft.gravity);
            List<FreeFlightPhysics.Quat> candidates = new ArrayList<>();
            if (g == 0.0D) {
                for (ControlDirection s : translations()) {
                    double[] axis = axisIn(craft.live, craft.attitude, s);
                    candidates.add(turnOnto(craft.attitude, axis, w, perpendicularTo(craft, axis)));
                }
            } else {
                double[] up = {-craft.gravity[0] / g, -craft.gravity[1] / g, -craft.gravity[2] / g};
                FreeFlightPhysics.Quat level = level(craft, up);
                if (level == null) {
                    return null;
                }
                double vertical = dot(w, up);
                double[] across = {w[0] - vertical * up[0], w[1] - vertical * up[1], w[2] - vertical * up[2]};
                double acrossLength = magnitude(across);
                if (acrossLength < 1.0e-6D) {
                    candidates.add(level);
                } else {
                    double[] h = {across[0] / acrossLength, across[1] / acrossLength, across[2] / acrossLength};
                    for (ControlDirection s : translations()) {
                        double[] axis = axisIn(craft.live, level, s);
                        if (Math.abs(dot(axis, up)) > 0.5D) {
                            continue;
                        }
                        candidates.add(turnOnto(level, axis, h, up));
                    }
                }
            }
            // A candidate the hull cannot turn into is no candidate; a hull that can turn into none flies
            // in the attitude it has.
            List<FreeFlightPhysics.Quat> reachable = new ArrayList<>();
            for (FreeFlightPhysics.Quat q : candidates) {
                if (!Double.isInfinite(turnTime(craft, craft.attitude, q))) {
                    reachable.add(q);
                }
            }
            if (reachable.isEmpty()) {
                reachable.add(craft.attitude);
            }
            Heading best = null;
            double bestTurn = Double.POSITIVE_INFINITY;
            for (FreeFlightPhysics.Quat q : reachable) {
                double r = reach(craft.live, q, w, craft.gravity);
                double turn = angleBetween(craft.attitude, q);
                boolean better = best == null || r > best.reach * (1.0D + TIE) + TIE
                        || (r >= best.reach * (1.0D - TIE) - TIE && turn < bestTurn - 1.0e-9D);
                if (better) {
                    best = new Heading(q, r);
                    bestTurn = turn;
                }
            }
            return best;
        }
    }

    /**
     * How a hull in a field leans: the level attitude ({@link #level}), the signed axis it holds up — the
     * LIFTING axis — and what that axis can push, for a hull whose lifting axis can push harder than its
     * weight (else it cannot lean at all and keep it).
     *
     * <p>Thrust per unit mass along the lifting axis, {@code f}, must equal the acceleration wanted plus
     * the field's pull cancelled: {@code f = a + g·up}. So the lifting axis is laid along {@code f}, and
     * never further from the vertical than {@code acos(g / lift)} — past that, even its hardest push no
     * longer holds the weight.</p>
     */
    private static final class Lean {

        final FreeFlightPhysics.Quat level;
        final double[] up;
        /** The field's strength, engine blocks/s². */
        final double g;
        /** The lifting axis's sustained authority, engine blocks/s². */
        final double lift;
        /**
         * Acceleration asked per unit of velocity error, 1/s. The lean is an outer loop on the attitude
         * law: an error {@code e} asks a lean of about {@code K·e/g}, the attitude law reaches it as a
         * first-order lag of gain {@code k} (its attitude gain), and a lean of {@code θ} accelerates the
         * craft at about {@code g·θ}. So {@code e'' + k·e' + k·K·e = 0}, critically damped at
         * {@code K = k/4}.
         */
        final double velocityGain;
        /**
         * The close-in gain a leaning craft is flown with, 1/s. A position loop of gain {@code P} over that
         * velocity loop gives {@code x''' + k·x'' + k·K·x' + k·K·P·x = 0}; with {@code K = k/4} and
         * {@code s = k·σ} that is {@code σ(σ + 1/2)² + P/(4k) = 0}, whose roots are all real — no overshoot
         * past the aim — only while {@code P/(4k)} is at most the largest value of {@code −σ(σ + 1/2)²}
         * between its roots, {@code 1/54} at {@code σ = −1/6}. So {@code P = 2k/27}, the fastest close-in
         * that does not overshoot. ({@link #POSITION_GAIN} is {@code k} itself, on the stability edge
         * {@code P < k}; measured 2026-10-22 at {@code P = K}, a heave-only hull overshot its column by
         * three blocks.) It is the gain along the vertical too, though the lift moves the craft up and
         * down unleaned: how hard the lift pushes is what a lean turns into push across, so a fast vertical
         * loop drives the one across — measured the same day with the vertical at {@code POSITION_GAIN}, the
         * same hull cycled a block and a half either side of its column without settling.
         */
        final double positionGain;

        private Lean(FreeFlightPhysics.Quat level, double[] up, double g, double lift, double attitudeGain) {
            this.level = level;
            this.up = up;
            this.g = g;
            this.lift = lift;
            this.velocityGain = attitudeGain / 4.0D;
            this.positionGain = 2.0D * attitudeGain / 27.0D;
        }

        /** How {@code craft} leans; {@code null} in space, or for a hull that cannot lean and hold its weight. */
        static Lean of(Craft craft) {
            double g = magnitude(craft.gravity);
            if (g == 0.0D || craft.live == null || !(craft.attitudeGain > 0.0D)) {
                return null;
            }
            double mass = craft.live.mass().getTotalMass();
            if (!(mass > 0.0D)) {
                return null;
            }
            double[] up = {-craft.gravity[0] / g, -craft.gravity[1] / g, -craft.gravity[2] / g};
            FreeFlightPhysics.Quat level = level(craft, up);
            if (level == null) {
                return null;
            }
            ControlDirection lifting = null;
            double mostUp = Double.NEGATIVE_INFINITY;
            for (ControlDirection s : translations()) {
                double along = dot(axisIn(craft.live, level, s), up);
                if (along > mostUp) {
                    mostUp = along;
                    lifting = s;
                }
            }
            double lift = authority(craft.live, lifting, mass);
            return lift > g ? new Lean(level, up, g, lift, craft.attitudeGain) : null;
        }

        /**
         * The largest acceleration, engine blocks/s², leaning lets the hull add along the unit direction
         * {@code w} while holding its weight: the largest {@code a} with {@code |a·w + g·up| ≤ lift} whose
         * lean is no steeper than {@code acos(g / lift)}.
         */
        double reach(double[] w) {
            double wv = dot(w, up);
            double wh = Math.sqrt(Math.max(0.0D, 1.0D - wv * wv));
            double byLift = -g * wv + Math.sqrt(Math.max(0.0D, g * g * wv * wv - g * g + lift * lift));
            double steepest = Math.sqrt(lift * lift - g * g) / g;
            double slope = wh - wv * steepest;
            double byLean = slope > 0.0D ? g * steepest / slope : Double.POSITIVE_INFINITY;
            return Math.max(0.0D, Math.min(byLift, byLean));
        }

        /** The acceleration asked of a craft flying at its velocity now when {@code v} is wanted. */
        double[] wanted(Craft craft, double[] v) {
            return new double[]{(v[0] - craft.velocity[0]) * velocityGain, (v[1] - craft.velocity[1]) * velocityGain,
                    (v[2] - craft.velocity[2]) * velocityGain};
        }

        /**
         * The level attitude leaned so the lifting axis lies along the thrust that adds {@code want} while
         * holding the weight — turned about the horizontal axis square to the lean, so its heading about
         * the vertical is the level one's — no steeper than {@code acos(g / lift)}. Level itself when
         * nothing is wanted across the vertical, or when what is wanted is a fall the lift cannot help.
         */
        FreeFlightPhysics.Quat toward(double[] want) {
            double wantUp = dot(want, up);
            double fv = wantUp + g;
            double[] across = {want[0] - wantUp * up[0], want[1] - wantUp * up[1], want[2] - wantUp * up[2]};
            double fh = magnitude(across);
            if (!(fv > 0.0D) || fh < 1.0e-9D) {
                return level;
            }
            double angle = Math.min(Math.atan2(fh, fv), Math.acos(g / lift));
            double[] h = {across[0] / fh, across[1] / fh, across[2] / fh};
            // Turning up toward h is a turn about up × h.
            double[] axis = {up[1] * h[2] - up[2] * h[1], up[2] * h[0] - up[0] * h[2], up[0] * h[1] - up[1] * h[0]};
            double s = Math.sin(angle / 2.0D);
            return new FreeFlightPhysics.Quat(Math.cos(angle / 2.0D), axis[0] * s, axis[1] * s, axis[2] * s)
                    .mul(level).normalized();
        }
    }

    /**
     * In a field, the attitude the craft flies level in: the signed translation axis pointing most
     * nearly up now, turned exactly up by the shortest arc — or, when that one cannot hold the hull's
     * weight, whichever other axis turned up can, by the smallest turn. {@code null} when none can.
     */
    private static FreeFlightPhysics.Quat level(Craft craft, double[] up) {
        ControlDirection upNow = null;
        double mostUp = Double.NEGATIVE_INFINITY;
        for (ControlDirection s : translations()) {
            double along = dot(axisIn(craft.live, craft.attitude, s), up);
            if (along > mostUp) {
                mostUp = along;
                upNow = s;
            }
        }
        double[] upAxis = axisIn(craft.live, craft.attitude, upNow);
        FreeFlightPhysics.Quat kept = turnOnto(craft.attitude, upAxis, up, perpendicularTo(craft, upAxis));
        if (weightFits(craft.live, kept, craft.gravity)) {
            return kept;
        }
        FreeFlightPhysics.Quat best = null;
        double bestTurn = Double.POSITIVE_INFINITY;
        for (ControlDirection s : translations()) {
            double[] axis = axisIn(craft.live, craft.attitude, s);
            FreeFlightPhysics.Quat q = turnOnto(craft.attitude, axis, up, perpendicularTo(craft, axis));
            double turn = angleBetween(craft.attitude, q);
            if (weightFits(craft.live, q, craft.gravity) && turn < bestTurn) {
                best = q;
                bestTurn = turn;
            }
        }
        return best;
    }

    /**
     * Engine seconds the attitude law takes to turn the hull from {@code from} to {@code to} until it is
     * within {@link #TURNED_WITHIN} of it; zero for a turn already that small, infinite for one about an
     * axis the hull has no authority to turn about.
     *
     * <p>The law it plans on is the flight computer's: desired rate {@code r(φ) = min(Ω, k·φ, √(2·α·φ))}
     * with {@code φ} the angle left, {@code Ω} its slew cap, {@code k} its attitude gain and {@code α} the
     * hull's burst angular authority about the turn's axis — the same authority that law caps the slew
     * with. The time is {@code r(θ)/α}, the most a spin-up from rest can lose against flying {@code r}
     * from the start, plus {@code ∫ dφ / r(φ)} from {@link #TURNED_WITHIN} to the whole angle {@code θ}.</p>
     */
    private static double turnTime(Craft craft, FreeFlightPhysics.Quat from, FreeFlightPhysics.Quat to) {
        FreeFlightPhysics.Quat rel = to.mul(conjugate(from)).normalized();
        double theta = 2.0D * Math.acos(Math.min(1.0D, Math.abs(rel.w)));
        if (theta <= TURNED_WITHIN) {
            return 0.0D;
        }
        double sign = rel.w < 0.0D ? -1.0D : 1.0D;
        double[] axis = {rel.x * sign, rel.y * sign, rel.z * sign};
        double n = magnitude(axis);
        axis = new double[]{axis[0] / n, axis[1] / n, axis[2] / n};
        double alpha = angularAuthority(craft.live, from, axis);
        if (!(alpha > 0.0D) || !(craft.maxSlewRate > 0.0D) || !(craft.attitudeGain > 0.0D)) {
            return Double.POSITIVE_INFINITY;
        }
        double t = slewRate(craft, alpha, theta) / alpha;
        double step = (theta - TURNED_WITHIN) / TURN_STEPS;
        for (int i = 0; i < TURN_STEPS; i++) {
            t += step / slewRate(craft, alpha, TURNED_WITHIN + (i + 0.5D) * step);
        }
        return t;
    }

    private static double slewRate(Craft craft, double alpha, double angle) {
        return Math.min(craft.maxSlewRate, Math.min(craft.attitudeGain * angle, Math.sqrt(2.0D * alpha * angle)));
    }

    /**
     * The hull's burst angular acceleration about the world axis {@code axis}, engine rad/s², flying at
     * {@code attitude}: per rotation axis of its frame the share it must turn, each within its signed
     * authority. Zero when some share has no authority.
     */
    private static double angularAuthority(ShipCapability live, FreeFlightPhysics.Quat attitude, double[] axis) {
        double[] k = conjugate(attitude).rotate(axis[0], axis[1], axis[2]);
        double alpha = Double.POSITIVE_INFINITY;
        for (ControlAxis c : ControlAxis.values()) {
            if (!c.isRotation()) {
                continue;
            }
            Vector3dc n = live.frame().axis(c);
            double part = k[0] * n.x() + k[1] * n.y() + k[2] * n.z();
            if (Math.abs(part) < 1.0e-6D) {
                continue;
            }
            double authority = live.authority(ControlDirection.of(c, part > 0.0D), Endurance.BURST);
            alpha = Math.min(alpha, authority / Math.abs(part));
        }
        return Double.isInfinite(alpha) ? 0.0D : alpha * PhysicsUnits.ACCELERATION;
    }

    /**
     * The largest acceleration, engine blocks/s², the hull can add along the world direction
     * {@code along} while holding its weight in {@code gravity}, flying at {@code attitude} — zero when
     * it cannot hold its weight there at all, or has no authority with a component along the direction.
     *
     * <p>Per axis the thrust needed is {@code a·along − gravity}, and each must fit inside that axis's
     * signed sustained authority; the answer is the largest {@code a} every axis allows. That is the
     * composition the control scheme performs, axis by axis (a direction it cannot deliver is not
     * delivered, and the rest is not bent toward it), so a direction the hull can only approximate by
     * leaning on its other axes reads as the shortfall it is.</p>
     */
    private static double reach(ShipCapability live, FreeFlightPhysics.Quat attitude, double[] along,
                                double[] gravity) {
        if (!weightFits(live, attitude, gravity)) {
            return 0.0D;
        }
        double mass = live.mass().getTotalMass();
        FreeFlightPhysics.Quat toShip = conjugate(attitude);
        double[] dirShip = toShip.rotate(along[0], along[1], along[2]);
        double[] gShip = toShip.rotate(gravity[0], gravity[1], gravity[2]);
        double best = Double.POSITIVE_INFINITY;
        for (ControlAxis axis : ControlAxis.values()) {
            if (axis.isRotation()) {
                continue;
            }
            Vector3dc n = live.frame().axis(axis);
            double w = dirShip[0] * n.x() + dirShip[1] * n.y() + dirShip[2] * n.z();
            double g = gShip[0] * n.x() + gShip[1] * n.y() + gShip[2] * n.z();
            double up = authority(live, ControlDirection.of(axis, true), mass);
            double down = authority(live, ControlDirection.of(axis, false), mass);
            if (w > AXIS_EPSILON) {
                best = Math.min(best, (up + g) / w);
            } else if (w < -AXIS_EPSILON) {
                best = Math.min(best, (down - g) / -w);
            }
        }
        return Double.isInfinite(best) ? 0.0D : Math.max(0.0D, best);
    }

    /** Whether the hull, flying at {@code attitude}, holds its weight in {@code gravity} on every axis. */
    private static boolean weightFits(ShipCapability live, FreeFlightPhysics.Quat attitude, double[] gravity) {
        if (live == null) {
            return false;
        }
        double mass = live.mass().getTotalMass();
        if (!(mass > 0.0D)) {
            return false;
        }
        double[] gShip = conjugate(attitude).rotate(gravity[0], gravity[1], gravity[2]);
        double field = magnitude(gravity);
        for (ControlAxis axis : ControlAxis.values()) {
            if (axis.isRotation()) {
                continue;
            }
            Vector3dc n = live.frame().axis(axis);
            double g = gShip[0] * n.x() + gShip[1] * n.y() + gShip[2] * n.z();
            // A share of the field this small is the rounding of the rotation into the hull's frame, not a
            // pull: an axis with no authority at all must not read as failing to hold it.
            if (Math.abs(g) <= AXIS_EPSILON * field) {
                continue;
            }
            // Holding the weight alone needs thrust -g on this axis.
            if (-g > authority(live, ControlDirection.of(axis, true), mass)
                    || g > authority(live, ControlDirection.of(axis, false), mass)) {
                return false;
            }
        }
        return true;
    }

    private static double authority(ShipCapability live, ControlDirection d, double mass) {
        return live.authority(d, Endurance.SUSTAINED) / mass * PhysicsUnits.ACCELERATION;
    }

    private static ControlDirection[] translations() {
        return new ControlDirection[]{ControlDirection.SURGE_POSITIVE, ControlDirection.SURGE_NEGATIVE,
                ControlDirection.SWAY_POSITIVE, ControlDirection.SWAY_NEGATIVE,
                ControlDirection.HEAVE_POSITIVE, ControlDirection.HEAVE_NEGATIVE};
    }

    /** The world direction the signed axis {@code s} pushes the hull in, flying at {@code attitude}. */
    private static double[] axisIn(ShipCapability live, FreeFlightPhysics.Quat attitude, ControlDirection s) {
        Vector3dc n = live.frame().axis(s.axis());
        double sign = s.isPositive() ? 1.0D : -1.0D;
        return attitude.rotate(n.x() * sign, n.y() * sign, n.z() * sign);
    }

    /** Of the craft's own axes now, the world direction least aligned with {@code v}. */
    private static double[] perpendicularTo(Craft craft, double[] v) {
        double[] best = null;
        double least = Double.POSITIVE_INFINITY;
        for (ControlDirection s : translations()) {
            if (!s.isPositive()) {
                continue;
            }
            double[] a = axisIn(craft.live, craft.attitude, s);
            double along = Math.abs(dot(a, v));
            if (along < least) {
                least = along;
                best = a;
            }
        }
        return best;
    }

    /**
     * {@code attitude} turned by the shortest arc that carries the world direction {@code from} onto
     * {@code to}; antiparallel, the half turn is taken about {@code flip} made perpendicular to
     * {@code from}.
     */
    private static FreeFlightPhysics.Quat turnOnto(FreeFlightPhysics.Quat attitude, double[] from, double[] to,
                                                   double[] flip) {
        double d = dot(from, to);
        FreeFlightPhysics.Quat turn;
        if (d < -1.0D + 1.0e-9D) {
            double along = dot(flip, from);
            double[] p = {flip[0] - along * from[0], flip[1] - along * from[1], flip[2] - along * from[2]};
            double n = magnitude(p);
            turn = new FreeFlightPhysics.Quat(0.0D, p[0] / n, p[1] / n, p[2] / n);
        } else {
            turn = new FreeFlightPhysics.Quat(1.0D + d,
                    from[1] * to[2] - from[2] * to[1],
                    from[2] * to[0] - from[0] * to[2],
                    from[0] * to[1] - from[1] * to[0]).normalized();
        }
        return turn.mul(attitude).normalized();
    }

    private static double angleBetween(FreeFlightPhysics.Quat a, FreeFlightPhysics.Quat b) {
        double d = Math.abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z);
        return 2.0D * Math.acos(Math.min(1.0D, d));
    }

    private static FreeFlightPhysics.Quat conjugate(FreeFlightPhysics.Quat q) {
        return new FreeFlightPhysics.Quat(q.w, -q.x, -q.y, -q.z);
    }

    private static FlightCommand hover(FreeFlightPhysics.Quat attitude) {
        return FlightCommand.of(new double[]{0.0D, 0.0D, 0.0D}, zeroRate(), quatArray(attitude));
    }

    private static FlightCommand rest(FreeFlightPhysics.Quat attitude) {
        return FlightCommand.of(null, zeroRate(), quatArray(attitude));
    }

    private static double[] zeroRate() {
        return new double[]{0.0D, 0.0D, 0.0D};
    }

    private static double[] quatArray(FreeFlightPhysics.Quat q) {
        return new double[]{q.w, q.x, q.y, q.z};
    }

    private static double[] negate(double[] v) {
        return new double[]{-v[0], -v[1], -v[2]};
    }

    private static double[] scaled(double[] v, double k) {
        return new double[]{v[0] * k, v[1] * k, v[2] * k};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double magnitude(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    /** Write the slot into {@code nbt}; an empty slot writes its state alone. */
    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setString(NBT_STATE, state.name());
        nbt.setBoolean(NBT_ARRIVED, arrived);
        if (refusal != null) {
            nbt.setString(NBT_REFUSAL, refusal.name());
        }
        if (intent != null) {
            NBTTagCompound i = new NBTTagCompound();
            intent.writeToNBT(i);
            nbt.setTag(NBT_INTENT, i);
        }
    }

    /**
     * Restore the slot from {@code nbt}, written by {@link #writeToNBT}. An absent tag is an empty slot.
     *
     * @return {@code false} when {@code nbt} held a slot that could not be read back — the slot is then
     *         EMPTY, and the caller says so, since an order silently dropped reads as one carried out
     */
    public boolean readFromNBT(NBTTagCompound nbt) {
        reclaim();
        State stored;
        try {
            stored = State.valueOf(nbt.getString(NBT_STATE));
        } catch (IllegalArgumentException unknown) {
            return !nbt.hasKey(NBT_STATE);
        }
        if (stored == State.EMPTY) {
            return true;
        }
        MotionIntent read = nbt.hasKey(NBT_INTENT) ? MotionIntent.readFromNBT(nbt.getCompoundTag(NBT_INTENT))
                : null;
        Refusal why = null;
        if (stored == State.REFUSED) {
            try {
                why = Refusal.valueOf(nbt.getString(NBT_REFUSAL));
            } catch (IllegalArgumentException unknown) {
                return false;
            }
        }
        if (read == null) {
            return false;
        }
        intent = read;
        state = stored;
        refusal = why;
        arrived = nbt.getBoolean(NBT_ARRIVED);
        return true;
    }

    /** The craft as guidance reads it on one tick. Every vector is in the frame of the craft's world. */
    public static final class Craft {

        final UUID id;
        final int dimension;
        final GalacticCoord cell;
        final double[] position;
        final double[] velocity;
        final FreeFlightPhysics.Quat attitude;
        final ShipCapability live;
        final double[] gravity;
        final double topSpeed;
        final double hullRadius;
        final double hullDepthBelow;
        final double attitudeGain;
        final double maxSlewRate;

        /**
         * @param id             the craft's durable name
         * @param dimension      the world the craft is in
         * @param cell           the space cell that world realizes, {@code null} on a planet
         * @param position       the craft's pose, world frame, blocks
         * @param velocity       world frame, blocks per engine second
         * @param attitude       body to world
         * @param live           what the hull's working actuators can do now; {@code null} when unknown
         * @param gravity        the field the craft is in, world frame, engine blocks/s²
         * @param topSpeed       the fastest guidance may fly the craft, blocks per engine second
         * @param hullRadius     radius of the smallest sphere about the pose holding the whole hull,
         *                       blocks; NaN when the hull cannot be measured
         * @param hullDepthBelow how far the hull's lowest point lies below the pose now, blocks; NaN when
         *                       unknown
         * @param attitudeGain   the flight law's attitude gain: desired rate per radian of error, 1/s
         * @param maxSlewRate    the flight law's cap on that desired rate, engine rad/s
         */
        public Craft(UUID id, int dimension, GalacticCoord cell, double[] position, double[] velocity,
                     FreeFlightPhysics.Quat attitude, ShipCapability live, double[] gravity, double topSpeed,
                     double hullRadius, double hullDepthBelow, double attitudeGain, double maxSlewRate) {
            this.id = id;
            this.dimension = dimension;
            this.cell = cell;
            this.position = position.clone();
            this.velocity = velocity.clone();
            this.attitude = attitude;
            this.live = live;
            this.gravity = gravity.clone();
            this.topSpeed = topSpeed;
            this.hullRadius = hullRadius;
            this.hullDepthBelow = hullDepthBelow;
            this.attitudeGain = attitudeGain;
            this.maxSlewRate = maxSlewRate;
        }
    }

    /** One tick's outcome: the command to publish ({@code null} for none) and what happened. */
    public static final class Step {

        private final FlightCommand command;
        private final Event event;

        Step(FlightCommand command, Event event) {
            this.command = command;
            this.event = event;
        }

        public FlightCommand command() {
            return command;
        }

        public Event event() {
            return event;
        }
    }

    /** What a slot holds at one moment. Immutable. */
    public static final class Reading {

        private final State state;
        private final MotionIntent intent;
        private final Refusal refusal;
        private final boolean arrived;

        Reading(State state, MotionIntent intent, Refusal refusal, boolean arrived) {
            this.state = state;
            this.intent = intent;
            this.refusal = refusal;
            this.arrived = arrived;
        }

        public State state() {
            return state;
        }

        /** The intent held, {@code null} for an empty slot. */
        public MotionIntent intent() {
            return intent;
        }

        /** Why the intent was refused; {@code null} unless the slot reads REFUSED. */
        public Refusal refusal() {
            return refusal;
        }

        /** Whether the intent held has reached what it wants at rest at least once. */
        public boolean arrived() {
            return arrived;
        }
    }
}
