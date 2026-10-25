package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.FreeFlightInput;
import dev.stannismod.stellurgy.api.FreeFlightPhysics;
import dev.stannismod.stellurgy.integration.vs.PhysicsUnits;
import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.FlightCommand;
import dev.stannismod.stellurgy.ship.control.ShipCapability;
import dev.stannismod.stellurgy.ship.guidance.GuidanceTarget;
import dev.stannismod.stellurgy.ship.guidance.MotionIntent;
import dev.stannismod.stellurgy.ship.guidance.MotionSlot;
import dev.stannismod.stellurgy.ship.guidance.Surroundings;
import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipEntryController;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.ShipMotionCases;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;
import org.joml.Vector3dc;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The LAWS of ship-guidance, one class for the owner: what a motion slot commands from where the craft
 * is (INV-GDN-04), when an intent it cannot fly is refused and what a refused craft is commanded
 * (INV-GDN-05), where a target puts the aim (INV-GDN-11), which leg a target in another world is flown as
 * (INV-GDN-19), and the flight computer's own decision that a
 * pilot's input takes the craft back (C9 SHIPCTL-21).
 *
 * <p>Every hull here is built from production's own devices through {@link ShipMotionCases}, and
 * solved by production's capability solve — the hull is the experiment's input, guidance's choice is
 * the subject. The expected values come from the hulls' authorities, the declared masses, the inputs
 * and the clauses, never from guidance's own arithmetic. The world around the craft is a
 * {@link Surroundings} the test builds: a ship at a stated place with a stated hull radius, ground at a
 * stated height — the inputs the clauses are written over.</p>
 *
 * <p>What this class does NOT see: the flight computer's tick (which slot runs before which branch),
 * the physics thread flying the command, the world reading a ship or the ground ({@code
 * WorldSurroundings}), a crossing carrying the slot, and the events and messages a refusal sends. Those
 * are the mechanics tests' (ship-guidance INV-GDN-01, -03, -07..10).</p>
 */
public class MotionGuidanceLawsTest {

    /** A constant: the seed of the INV-GDN-04 sweep, so a red reproduces. An input, not a fact. */
    private static final long SEED = 402L;

    /** A constant: cases in the INV-GDN-04 sweep. An input; the region minimum below is derived from it. */
    private static final int CASES = 4000;

    /**
     * A constant: the fewest cases each region of INV-GDN-04 must be visited by. The rarest region is
     * drawn with a probability of about a fortieth (the no-reverse hull, two sevenths of the hulls, closing
     * fast — a third of its cases — and so turned over to brake, or nearly at rest with the turn
     * binding), so 4000 cases give some 100; forty is that with room for the draw.
     */
    private static final int REGION_MINIMUM = 40;

    /** A constant: the fastest a craft may be flown in the sweep's inputs, blocks per engine second. */
    private static final double TOP_SPEED_MAX = 400.0D;

    /** A constant: how far an aim may be put, blocks; an input of the sweep. */
    private static final double DISTANCE_MAX = 5000.0D;

    /**
     * A constant: the radius inside which a craft counts as arrived and guidance asks no turn — the
     * arrival the maintainer stated (2026-10-21), "within 2 blocks". Every sweep aim lies beyond it, and
     * a ship target's standoff is the two hulls plus it (ruled the same day).
     */
    private static final double ARRIVAL_RADIUS = 2.0D;

    /** A constant: relative slack on a comparison of two doubles each built by a few products. */
    private static final double RELATIVE = 1.0e-9D;

    /** A constant: the world the targets of this class are in; an input. */
    private static final int PLANET_DIM = 0;

    /** A constant: the flight law's attitude gain the crafts here are handed, 1/s; an input. */
    private static final double ATTITUDE_GAIN = 2.0D;

    /** A constant: the flight law's slew cap the crafts here are handed, rad/s; an input. */
    private static final double SLEW_CAP = 2.0D;

    /** A constant: every craft's hull radius in this class, and the sweep's target's, blocks; an input. */
    private static final double HULL = 0.5D;

    /** A constant: the craft's own name. Immutable. */
    private static final UUID SELF = new UUID(402L, 1L);

    /** A constant: the target ship's name. Immutable. */
    private static final UUID OTHER = new UUID(402L, 2L);

    @Test
    @Pins("INV-GDN-04")
    @Regions(of = "INV-GDN-04", value = {"closing", "braking after a turnover", "the turn binds",
            "no thrust", "in a field"})
    @RedWitness(verdict = "commands the craft", member = "MotionSlot#fly",
            at = "return new Step(command, Event.NONE);", replacedBy = "return new Step(null, Event.NONE);",
            fails = "INV-GDN-04: a fresh GO_TO", on = "2026-10-21")
    @RedWitness(verdict = "top speed", member = "Plan#command",
            at = "speed = Math.min(craft.topSpeed, Math.min(turned, closeIn));",
            replacedBy = "speed = Math.min(2.0D * craft.topSpeed, Math.min(turned, closeIn));",
            fails = "INV-GDN-04 top speed", on = "2026-10-22")
    @RedWitness(verdict = "direction", member = "Plan#command",
            at = "u[1] * speed, u[2] * speed}", replacedBy = "u[2] * speed, u[1] * speed}",
            fails = "INV-GDN-04 direction", on = "2026-10-21")
    @RedWitness(verdict = "level", member = "Heading#best",
            at = "candidates.add(turnOnto(level, axis, h, up));",
            replacedBy = "candidates.add(turnOnto(craft.attitude, axis, h, up));",
            fails = "INV-GDN-04 level", on = "2026-10-21")
    @RedWitness(verdict = "no thrust", member = "MotionSlot#fly",
            at = "return new Step(hover(craft.attitude), Event.NONE);",
            replacedBy = "return new Step(hover(FreeFlightPhysics.Quat.IDENTITY), Event.NONE);",
            fails = "INV-GDN-04 no thrust", on = "2026-10-21")
    @RedWitness(verdict = "stopping speed", member = "Plan#command",
            at = "double a = STOP_MARGIN * brake.reach;", replacedBy = "double a = 8.0D * brake.reach;",
            fails = "INV-GDN-04 stopping speed", on = "2026-10-21")
    @RedWitness(verdict = "strong axis", member = "Heading#best",
            at = "r > best.reach * (1.0D + TIE) + TIE", replacedBy = "r < best.reach * (1.0D + TIE) + TIE",
            fails = "INV-GDN-04 strong axis", on = "2026-10-21")
    @RedWitness(verdict = "flown", member = "Plan#command",
            at = "speed = Math.min(craft.topSpeed, Math.min(turned, closeIn));",
            replacedBy = "speed = 0.0D;", fails = "INV-GDN-04 flown", on = "2026-10-22")
    @RedWitness(verdict = "turnover", member = "Plan#of",
            at = "turnTime(craft, close.attitude, brake.attitude)", replacedBy = "0.0D",
            fails = "INV-GDN-04 turnover", on = "2026-10-21")
    public void theApproachIsOneTheHullCanStopFromOnItsStrongAxis() {
        Random rng = new Random(SEED);
        ShipMotionCases.Hull symmetric = ShipMotionCases.symmetricHull();
        ShipCapability symmetricLive = solve(symmetric);
        ShipMotionCases.Hull noReverse = ShipMotionCases.noReverseHull();
        ShipCapability noReverseLive = solve(noReverse);
        ShipMotionCases.Hull bare = ShipMotionCases.bareHull();
        ShipCapability bareLive = solve(bare);
        int closing = 0;
        int turnedOver = 0;
        int turnBinds = 0;
        int noThrust = 0;
        int inField = 0;
        for (int i = 0; i < CASES; i++) {
            int pick = rng.nextInt(7);
            ShipMotionCases.Hull hull;
            ShipCapability live;
            if (pick < 2) {
                hull = symmetric;
                live = symmetricLive;
            } else if (pick < 4) {
                hull = noReverse;
                live = noReverseLive;
            } else if (pick == 4) {
                hull = bare;
                live = bareLive;
            } else {
                hull = ShipMotionCases.randomHull(rng, "random hull " + i);
                live = solve(hull);
            }
            boolean field = (pick == 0 || pick > 4) && rng.nextBoolean();
            double[] gravity = field ? new double[]{0.0D, -PhysicsUnits.ENGINE_GRAVITY, 0.0D}
                    : new double[]{0.0D, 0.0D, 0.0D};
            FreeFlightPhysics.Quat attitude = randomAttitude(rng);
            double[] position = {1000.0D * (rng.nextDouble() - 0.5D), 1000.0D * (rng.nextDouble() - 0.5D),
                    1000.0D * (rng.nextDouble() - 0.5D)};
            double[] u = randomUnit(rng);
            double d = (ARRIVAL_RADIUS + 0.5D) * Math.pow(DISTANCE_MAX / (ARRIVAL_RADIUS + 0.5D),
                    rng.nextDouble());
            double standoff = 2.0D * HULL + ARRIVAL_RADIUS;
            double[] target = {position[0] + u[0] * (d + standoff), position[1] + u[1] * (d + standoff),
                    position[2] + u[2] * (d + standoff)};
            double topSpeed = 10.0D + (TOP_SPEED_MAX - 10.0D) * rng.nextDouble();
            boolean fast = rng.nextInt(3) == 0;
            double[] velocity = fast ? new double[]{u[0] * topSpeed, u[1] * topSpeed, u[2] * topSpeed}
                    : new double[]{rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()};
            String input = hull.name + (field ? " in a field" : " in space") + ", aim " + d + " blocks off"
                    + ", top speed " + topSpeed + (fast ? ", closing at top speed" : ", nearly at rest")
                    + ", attitude " + attitude.w + "," + attitude.x + "," + attitude.y + "," + attitude.z;

            MotionSlot slot = new MotionSlot();
            slot.issue(MotionIntent.goTo(GuidanceTarget.ship(OTHER)));
            FlightCommand command = slot.fly(craft(live, position, velocity, attitude, gravity, topSpeed),
                    new Fixed(target, HULL, Double.NaN)).command();
            assertNotNull("INV-GDN-04: a fresh GO_TO beyond the arrival radius commands the craft: " + input,
                    command);
            double[] v = command.velocity();
            double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            double[] q = command.attitude();
            double strongestForce = strongestTranslation(live);
            if (!field && strongestForce == 0.0D) {
                noThrust++;
                assertTrue("INV-GDN-04 no thrust: a hull with no translational authority was not told to hold still"
                        + " in the attitude it has: commanded " + Arrays.toString(v) + " at " + Arrays.toString(q)
                        + " — " + input, speed == 0.0D
                        && Arrays.equals(q, new double[]{attitude.w, attitude.x, attitude.y, attitude.z}));
                continue;
            }
            assertTrue("INV-GDN-04 top speed: commanded " + speed + " above the craft's " + topSpeed
                    + " — " + input, speed <= topSpeed * (1.0D + RELATIVE));
            if (speed > 0.0D) {
                double cos = (v[0] * u[0] + v[1] * u[1] + v[2] * u[2]) / speed;
                assertTrue("INV-GDN-04 direction: the commanded velocity is " + Math.acos(Math.min(1.0D, cos))
                        + " rad off the way to the aim — " + input, cos >= 1.0D - RELATIVE);
            }
            FreeFlightPhysics.Quat commanded = new FreeFlightPhysics.Quat(q[0], q[1], q[2], q[3]).normalized();
            double mass = hull.declaredMass();

            if (field) {
                boolean canStandUp = strongestForce / mass * PhysicsUnits.ACCELERATION > PhysicsUnits.ENGINE_GRAVITY
                        && turnsAboutEveryAxis(live);
                boolean heldWhereItIs = speed == 0.0D
                        && Arrays.equals(q, new double[]{attitude.w, attitude.x, attitude.y, attitude.z});
                // Level is owed only where it delivers: a hull that pushes both ways along every axis does,
                // whichever way the aim lies. One that does not may lean (INV-GDN-12).
                if (!canStandUp || heldWhereItIs || !pushesBothWaysAlongEveryAxis(live)) {
                    continue;
                }
                inField++;
                assertTrue("INV-GDN-04 level: in a field the commanded attitude keeps one of the hull's axes"
                        + " straight up — " + input, keepsAnAxisUp(live, commanded));
                continue;
            }
            double combined = combinedTranslation(live) / mass * PhysicsUnits.ACCELERATION;
            assertTrue("INV-GDN-04 stopping speed: commanded " + speed + ", but all the hull's axes together"
                    + " brake at no more than " + combined + ", which stops from at most "
                    + Math.sqrt(2.0D * combined * d) + " in " + d + " blocks — " + input,
                    speed <= Math.sqrt(2.0D * combined * d) * (1.0D + RELATIVE));
            if (!turnsAboutEveryAxis(live)) {
                continue;
            }
            double alongAim = authorityAlong(live, commanded, u);
            double againstAim = authorityAlong(live, commanded, negate(u));
            boolean closes = alongAim >= strongestForce * (1.0D - RELATIVE);
            boolean brakes = againstAim >= strongestForce * (1.0D - RELATIVE);
            assertTrue("INV-GDN-04 strong axis: the commanded attitude lays none of the hull's strongest axes"
                    + " (" + strongestForce + " N) along the way, toward the aim (" + alongAim + " N) or away"
                    + " from it (" + againstAim + " N) — " + input, closes || brakes);
            if (closes) {
                closing++;
                assertTrue("INV-GDN-04 flown: a hull that can push toward the aim and stop was commanded to"
                        + " stand still while closing — " + input, speed > 0.0D);
            }
            if (live == noReverseLive) {
                double forward = live.authority(ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED) / mass
                        * PhysicsUnits.ACCELERATION;
                // Braking needs the nose turned round, through at least a right angle at no more than
                // the slew cap — the least a turnover can take, whatever else the law does.
                double turn = (Math.PI / 2.0D) / SLEW_CAP;
                double canTurnAndStop = Math.sqrt(forward * turn * forward * turn + 2.0D * forward * d)
                        - forward * turn;
                if (closes && !brakes) {
                    if (canTurnAndStop < Math.min(topSpeed, 2.0D * d)) {
                        turnBinds++;
                    }
                    assertTrue("INV-GDN-04 turnover: commanded " + speed + " nose-first, but a hull that must"
                            + " turn round before it brakes at " + forward + " can stop from at most "
                            + canTurnAndStop + " in " + d + " blocks — " + input,
                            speed <= canTurnAndStop * (1.0D + RELATIVE));
                } else if (brakes && !closes) {
                    turnedOver++;
                }
            }
        }
        requireArranged("the sweep must visit `closing` at least " + REGION_MINIMUM + " times; it did " + closing,
                closing >= REGION_MINIMUM);
        requireArranged("the sweep must visit `braking after a turnover` at least " + REGION_MINIMUM
                + " times; it did " + turnedOver, turnedOver >= REGION_MINIMUM);
        requireArranged("the sweep must visit `the turn binds` at least " + REGION_MINIMUM + " times; it did "
                + turnBinds, turnBinds >= REGION_MINIMUM);
        requireArranged("the sweep must visit `no thrust` at least " + REGION_MINIMUM + " times; it did "
                + noThrust, noThrust >= REGION_MINIMUM);
        requireArranged("the sweep must visit `in a field` at least " + REGION_MINIMUM + " times; it did "
                + inField, inField >= REGION_MINIMUM);
    }

    @Test
    @Pins("INV-GDN-05")
    @RedWitness(verdict = "never sooner", member = "MotionSlot#fly",
            at = "shortTicks >= REFUSAL_WINDOW_TICKS", replacedBy = "shortTicks >= 1",
            fails = "INV-GDN-05 never sooner", on = "2026-10-21")
    @RedWitness(verdict = "hovers while short", member = "MotionSlot#fly",
            at = "return new Step(hover(craft.attitude), Event.NONE);", replacedBy = "return new Step(null, Event.NONE);",
            fails = "INV-GDN-05 hovers while short", on = "2026-10-21")
    @RedWitness(verdict = "restarts", member = "MotionSlot#fly",
            at = "shortTicks = 0;", replacedBy = "", fails = "INV-GDN-05 restarts", on = "2026-10-21")
    @RedWitness(verdict = "exactly", member = "MotionSlot#fly",
            at = "shortTicks >= REFUSAL_WINDOW_TICKS", replacedBy = "shortTicks > REFUSAL_WINDOW_TICKS",
            fails = "INV-GDN-05 exactly", on = "2026-10-21")
    @RedWitness(verdict = "the refusing tick hovers", member = "MotionSlot#fly",
            at = "return new Step(hover(held), Event.REFUSED);", replacedBy = "return new Step(null, Event.REFUSED);",
            fails = "INV-GDN-05 exactly: the refusing tick hovers", on = "2026-10-21")
    @RedWitness(verdict = "reads refused", member = "MotionSlot#fly",
            at = "state = State.REFUSED;", replacedBy = "state = State.ACTIVE;",
            fails = "INV-GDN-05 exactly: the slot reads refused", on = "2026-10-21")
    @RedWitness(verdict = "reason", member = "Plan#of",
            at = "if (!(close.reach > 0.0D)) {", replacedBy = "if (false) {",
            fails = "INV-GDN-05 exactly: for want of thrust", on = "2026-10-21")
    @RedWitness(verdict = "still holds its intent", member = "MotionSlot#fly",
            at = "refusal = shortBy;", replacedBy = "refusal = shortBy; intent = null;",
            fails = "INV-GDN-05 exactly: the refused slot still holds its intent", on = "2026-10-21")
    @RedWitness(verdict = "hovers when refused", member = "MotionSlot#fly",
            at = "if (state == State.REFUSED) {", replacedBy = "if (state == State.EMPTY) {",
            fails = "INV-GDN-05 hovers when refused", on = "2026-10-21")
    public void aRefusalTakesASustainedWindowAndTheRefusedCraftHovers() {
        ShipCapability flyable = solve(ShipMotionCases.symmetricHull());
        ShipCapability unflyable = solve(ShipMotionCases.bareHull());
        Fixed around = new Fixed(new double[]{100.0D, 0.0D, 0.0D}, HULL, Double.NaN);
        MotionSlot slot = new MotionSlot();
        slot.issue(MotionIntent.goTo(GuidanceTarget.ship(OTHER)));
        int window = MotionSlot.REFUSAL_WINDOW_TICKS;
        FreeFlightPhysics.Quat tilted = new FreeFlightPhysics.Quat(0.9D, 0.1D, 0.3D, -0.2D).normalized();

        for (int tick = 1; tick < window; tick++) {
            MotionSlot.Step step = slot.fly(atRest(unflyable, tilted), around);
            assertEquals("INV-GDN-05 never sooner: short tick " + tick + " of a " + window + "-tick window",
                    MotionSlot.Event.NONE, step.event());
            assertHover("INV-GDN-05 hovers while short: tick " + tick, step.command(), tilted);
        }
        MotionSlot.Step flown = slot.fly(atRest(flyable, FreeFlightPhysics.Quat.IDENTITY), around);
        requireArranged("the arrangement: the flyable tick between two short runs is flown, not refused",
                flown.event() == MotionSlot.Event.NONE);
        for (int tick = 1; tick < window; tick++) {
            MotionSlot.Step step = slot.fly(atRest(unflyable, tilted), around);
            assertEquals("INV-GDN-05 restarts: short tick " + tick + " after a flyable one refused the intent"
                    + " although the window started over", MotionSlot.Event.NONE, step.event());
        }
        MotionSlot.Step refusing = slot.fly(atRest(unflyable, tilted), around);
        assertEquals("INV-GDN-05 exactly: the " + window + "th short tick in a row refuses",
                MotionSlot.Event.REFUSED, refusing.event());
        assertHover("INV-GDN-05 exactly: the refusing tick hovers", refusing.command(), tilted);
        MotionSlot.Reading held = slot.reading();
        assertEquals("INV-GDN-05 exactly: the slot reads refused", MotionSlot.State.REFUSED, held.state());
        assertEquals("INV-GDN-05 exactly: for want of thrust toward the aim",
                MotionSlot.Refusal.NO_APPROACH_AUTHORITY, held.refusal());
        assertNotNull("INV-GDN-05 exactly: the refused slot still holds its intent", held.intent());

        // The refused craft is turned and set moving by something else, on a hull that could fly the
        // order: it is still told to hover, in the attitude it was refused in — not flown at the target.
        MotionSlot.Step after = slot.fly(craft(flyable, new double[]{0.0D, 0.0D, 0.0D},
                new double[]{3.0D, 0.0D, 0.0D}, FreeFlightPhysics.Quat.IDENTITY, new double[]{0.0D, 0.0D, 0.0D},
                40.0D), around);
        assertHover("INV-GDN-05 hovers when refused: the tick after the refusal", after.command(), tilted);
    }

    @Test
    @Pins("INV-GDN-11")
    @RedWitness(verdict = "arrived on the standoff", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "placed.keepOut() + 3.0D * clearance",
            fails = "INV-GDN-11 standoff: at rest on", on = "2026-10-21")
    @RedWitness(verdict = "inside it", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "placed.keepOut()",
            fails = "INV-GDN-11 standoff: half a block inside", on = "2026-10-21")
    @RedWitness(verdict = "outside it", member = "MotionIntent#aimFrom",
            at = "placed.keepOut() + clearance", replacedBy = "placed.keepOut() + 2.0D * clearance",
            fails = "INV-GDN-11 standoff: half a block outside", on = "2026-10-21")
    public void aGoToAShipAimsAtTheStandoffOutsideBothHulls() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double[] target = {200.0D, 30.0D, -40.0D};
        double targetHull = 3.25D;
        double standoff = HULL + targetHull + ARRIVAL_RADIUS;
        double[] u = {0.6D, 0.0D, 0.8D};
        Fixed around = new Fixed(target, targetHull, Double.NaN);

        // At rest exactly on the standoff the GO_TO has arrived; half a block nearer it is sent out,
        // half a block farther it is sent in.
        MotionSlot onIt = goToShip();
        MotionSlot.Step there = onIt.fly(atRestAt(live, back(target, u, standoff)), around);
        assertEquals("INV-GDN-11 standoff: at rest on the two hulls plus the arrival radius, the GO_TO arrived",
                MotionSlot.Event.ARRIVED, there.event());
        assertPointsAlong("INV-GDN-11 standoff: half a block inside it", goToShip().fly(
                atRestAt(live, back(target, u, standoff - 0.5D)), around).command(), negate(u));
        assertPointsAlong("INV-GDN-11 standoff: half a block outside it", goToShip().fly(
                atRestAt(live, back(target, u, standoff + 0.5D)), around).command(), u);
    }

    @Test
    @Pins("INV-GDN-11")
    @RedWitness(verdict = "arrived in the shell", member = "MotionIntent#aimFrom",
            at = "double outer = kind == Kind.GO_TO ? inner : Math.max(inner, range);",
            replacedBy = "double outer = inner;", fails = "INV-GDN-11 hold: inside the shell the craft has arrived",
            on = "2026-10-21")
    @RedWitness(verdict = "not moved", member = "MotionIntent#aimFrom",
            at = "return position.clone();", replacedBy = "return new double[]{position[0] + 1.0D, position[1], position[2]};",
            fails = "INV-GDN-11 hold: inside the shell the craft is not moved", on = "2026-10-21")
    @RedWitness(verdict = "inside the standoff", member = "MotionIntent#aimFrom",
            at = "if (d >= inner && d <= outer) {", replacedBy = "if (d <= outer) {",
            fails = "INV-GDN-11 hold: a block and a half inside", on = "2026-10-21")
    @RedWitness(verdict = "past the range", member = "MotionIntent#aimFrom",
            at = "if (d >= inner && d <= outer) {", replacedBy = "if (d >= inner) {",
            fails = "INV-GDN-11 hold: a block and a half past", on = "2026-10-21")
    public void aHoldKeepsTheCraftInItsShell() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double[] target = {200.0D, 30.0D, -40.0D};
        double targetHull = 3.25D;
        double standoff = HULL + targetHull + ARRIVAL_RADIUS;
        double[] u = {0.6D, 0.0D, 0.8D};
        Fixed around = new Fixed(target, targetHull, Double.NaN);

        // A HOLD leaves the craft anywhere in the shell between the standoff and its range, and pushes a
        // craft inside the standoff back out and one past the range back in.
        MotionSlot hold = holdShip(standoff + 20.0D);
        MotionSlot.Step shell = hold.fly(atRestAt(live, back(target, u, standoff + 10.0D)), around);
        assertEquals("INV-GDN-11 hold: inside the shell the craft has arrived", MotionSlot.Event.ARRIVED,
                shell.event());
        assertArrayEquals("INV-GDN-11 hold: inside the shell the craft is not moved",
                new double[]{0.0D, 0.0D, 0.0D}, shell.command().velocity(), 0.0D);
        assertPointsAlong("INV-GDN-11 hold: a block and a half inside the standoff", holdShip(standoff + 20.0D)
                .fly(atRestAt(live, back(target, u, standoff - 1.5D)), around).command(), negate(u));
        assertPointsAlong("INV-GDN-11 hold: a block and a half past the range", holdShip(standoff + 20.0D)
                .fly(atRestAt(live, back(target, u, standoff + 21.5D)), around).command(), u);
    }

    @Test
    @Pins("INV-GDN-11")
    @RedWitness(verdict = "landed", member = "GuidanceTarget#placeIn",
            at = "ground + craft.hullDepthBelow - MotionSlot.TOUCHDOWN", replacedBy = "ground - MotionSlot.TOUCHDOWN",
            fails = "INV-GDN-11 surface: at rest with its lowest point", on = "2026-10-21")
    @RedWitness(verdict = "released", member = "MotionSlot#fly",
            at = "return new Step(rest(held), Event.ARRIVED);", replacedBy = "return new Step(command, Event.ARRIVED);",
            fails = "INV-GDN-11 surface: a landed craft is released", on = "2026-10-21")
    @RedWitness(verdict = "stays released", member = "MotionSlot#fly",
            at = "if (arrived && intent.isLanding()) {", replacedBy = "if (false) {",
            fails = "INV-GDN-11 surface: and stays released", on = "2026-10-21")
    public void aGoToASurfacePointAimsAtTheGroundAndLetsTheCraftDown() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        // A surface point: the craft's pose where its lowest point rests on the ground there.
        double ground = 71.0D;
        double depth = 2.75D;
        double[] landed = {12.5D, ground + depth, -7.5D};
        Fixed overGround = new Fixed(new double[]{200.0D, 30.0D, -40.0D}, HULL, ground);
        MotionSlot land = landAt(landed);
        MotionSlot.Step down = land.fly(onSurface(live, landed, depth, PLANET_DIM, null), overGround);
        assertEquals("INV-GDN-11 surface: at rest with its lowest point on the ground there, it has landed",
                MotionSlot.Event.ARRIVED, down.event());
        assertNull("INV-GDN-11 surface: a landed craft is released onto the ground, not held up",
                down.command().velocity());
        assertNull("INV-GDN-11 surface: and stays released on the next tick",
                land.fly(onSurface(live, landed, depth, PLANET_DIM, null), overGround).command().velocity());
    }

    @Test
    @Pins("INV-GDN-11")
    @RedWitness(verdict = "over its column", member = "MotionSlot#fly",
            at = "aim = over;", replacedBy = "",
            fails = "INV-GDN-11 over its column", on = "2026-10-22")
    @RedWitness(verdict = "not landed held over", member = "MotionSlot#fly",
            at = "heldOver = true;", replacedBy = "",
            fails = "INV-GDN-11 over its column: not landed", on = "2026-10-22")
    @RedWitness(verdict = "landed across", member = "MotionSlot#fly",
            at = "!heldOver && above <= TOUCHDOWN", replacedBy = "!heldOver && distance <= TOUCHDOWN",
            fails = "INV-GDN-11 landed across", on = "2026-10-22")
    public void aLandingIsLetDownOnlyOverItsColumn() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double ground = 71.0D;
        double depth = 2.75D;
        double[] column = {12.5D, ground + depth, -7.5D};
        Fixed overGround = new Fixed(new double[]{200.0D, 30.0D, -40.0D}, HULL, ground);
        // Its hull reaches 3.25 blocks from its position, which is at least how far its lowest point lies below.
        double reach = 3.25D;
        // Resting on the ground a block and a half across from its column — past half the arrival radius:
        // sent up and across, to the point over the column where its hull clears the ground, never along
        // the ground or into it; and not landed, though it is on the ground within the arrival radius.
        double[] across = {column[0] + 0.75D * ARRIVAL_RADIUS, column[1], column[2]};
        MotionSlot.Step held = landAt(column).fly(landingCraft(live, across, reach, depth), overGround);
        double[] overColumn = {column[0], ground + reach, column[2]};
        double[] way = {overColumn[0] - across[0], overColumn[1] - across[1], overColumn[2] - across[2]};
        double n = Math.sqrt(way[0] * way[0] + way[1] * way[1] + way[2] * way[2]);
        assertEquals("INV-GDN-11 over its column: not landed a block and a half across", MotionSlot.Event.NONE,
                held.event());
        assertPointsAlong("INV-GDN-11 over its column: a block and a half across from it, on the ground",
                held.command(), new double[]{way[0] / n, way[1] / n, way[2] / n});
        // Resting on the ground half a block across — within half the arrival radius: it has landed.
        double[] near = {column[0] + 0.25D * ARRIVAL_RADIUS, column[1], column[2]};
        assertEquals("INV-GDN-11 landed across: at rest on the ground half a block from its column",
                MotionSlot.Event.ARRIVED, landAt(column).fly(landingCraft(live, near, reach, depth), overGround).event());
    }

    /**
     * INV-GDN-16: on its body's surface an orbit is climbed STRAIGHT UP, never arrived at, refused while
     * the column above is blocked or the world has no orbit line (from another planet it is the climb of
     * INV-GDN-19); in space it is the sphere about the body — a craft on it at rest has arrived, one far off
     * is sent toward the body — and a body nobody can find is refused as such. The world is an
     * {@link OrbitWorld}: the line, the solid block and the sphere are the experiment's inputs.
     */
    @Test
    @Pins("INV-GDN-16")
    @RedWitness(verdict = "straight up", member = "GuidanceTarget#climb",
            at = "new double[]{craft.position[0], top, craft.position[2]}", replacedBy = "new double[]{0.0D, top, 0.0D}",
            fails = "INV-GDN-16 straight up", on = "2026-10-24")
    @RedWitness(verdict = "never arrived", member = "GuidanceTarget#climb",
            at = "Placement.beforeACrossing(new double[]{craft.position[0], top, craft.position[2]},",
            replacedBy = "true ? Placement.at(new double[]{craft.position[0], top, craft.position[2]}, 0.0D) : Placement.beforeACrossing(new double[]{craft.position[0], top, craft.position[2]},",
            fails = "INV-GDN-16 never arrived", on = "2026-10-24")
    @RedWitness(verdict = "blocked", member = "GuidanceTarget#climb",
            at = "if (!around.openAbove(", replacedBy = "if (false && !around.openAbove(",
            fails = "INV-GDN-16 blocked", on = "2026-10-24")
    @RedWitness(verdict = "no orbit line", member = "GuidanceTarget#climb",
            at = "return Placement.unplaced(Unplaced.NO_ORBIT_LINE);",
            replacedBy = "return Placement.unplaced(Unplaced.NOT_FOUND);",
            fails = "INV-GDN-16 no orbit line", on = "2026-10-24")
    @RedWitness(verdict = "on the sphere", member = "GuidanceTarget#orbitIn",
            at = "return Placement.sphere(seen.centre(), seen.radius());",
            replacedBy = "return Placement.sphere(seen.centre(), 2.0D * seen.radius());",
            fails = "INV-GDN-16 on the sphere", on = "2026-10-23")
    @RedWitness(verdict = "toward the body", member = "GuidanceTarget#orbitIn",
            at = "return Placement.sphere(seen.centre(), seen.radius());",
            replacedBy = "return Placement.sphere(new double[]{0.0D, seen.centre()[1], 0.0D}, seen.radius());",
            fails = "INV-GDN-16 toward the body", on = "2026-10-23")
    @RedWitness(verdict = "not found", member = "GuidanceTarget#orbitIn",
            at = "return Placement.unplaced(seen.unplaced());",
            replacedBy = "return Placement.unplaced(Unplaced.NO_FRAME);",
            fails = "INV-GDN-16 not found", on = "2026-10-23")
    public void anOrbitIsClimbedStraightUpFromItsBodyAndIsTheSphereAboutItInSpace() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double line = 200.0D;
        double depth = 2.75D;
        double[] grounded = {12.5D, 70.0D, -7.5D};
        GuidanceTarget orbit = GuidanceTarget.orbit(PLANET_DIM);
        OrbitWorld clear = new OrbitWorld(line, Double.NaN);

        MotionSlot climbing = new MotionSlot();
        climbing.issue(MotionIntent.goTo(orbit));
        MotionSlot.Step first = climbing.fly(onSurface(live, grounded, depth, PLANET_DIM, null), clear);
        assertPointsAlong("INV-GDN-16 straight up: a grounded craft told to orbit climbs", first.command(),
                new double[]{0.0D, 1.0D, 0.0D});

        // At rest at every height from the line to the top of the room the clamp leaves above it — the aim
        // lies among them — and not one of them is an arrival: the crossing takes the craft on first.
        for (int above = 0; above <= ShipEntryController.PHYSICS_CLAMP_ENTRY_MARGIN; above++) {
            MotionSlot at = new MotionSlot();
            at.issue(MotionIntent.goTo(orbit));
            double[] there = {grounded[0], line + above, grounded[2]};
            assertEquals("INV-GDN-16 never arrived: at rest " + above + " blocks above the line",
                    MotionSlot.Event.NONE, at.fly(onSurface(live, there, depth, PLANET_DIM, null), clear).event());
        }

        // The column blocked above the craft refuses the climb; solid ground under it does not.
        assertRefusedFor("INV-GDN-16 blocked: a solid block in the column overhead", orbit,
                onSurface(live, grounded, depth, PLANET_DIM, null), new OrbitWorld(line, 150.0D),
                MotionSlot.Refusal.PATH_BLOCKED);
        MotionSlot underfoot = new MotionSlot();
        underfoot.issue(MotionIntent.goTo(orbit));
        for (int tick = 0; tick < MotionSlot.REFUSAL_WINDOW_TICKS; tick++) {
            underfoot.fly(onSurface(live, grounded, depth, PLANET_DIM, null), new OrbitWorld(line, 50.0D));
        }
        requireArranged("the control: solid ground under the craft blocks no climb",
                underfoot.reading().state() == MotionSlot.State.ACTIVE);
        assertRefusedFor("INV-GDN-16 no orbit line: a world no climb leaves", orbit,
                onSurface(live, grounded, depth, PLANET_DIM, null), new OrbitWorld(Double.NaN, Double.NaN),
                MotionSlot.Refusal.NO_ORBIT_LINE);

        // In space: the sphere of the ring about the body.
        GalacticCoord cell = GalacticCoord.ofSectorLocal(7L, 0L, 0L, 0L, 0L, 0L);
        double ring = 1024.0D;
        double[] body = {300.0D, -40.0D, 500.0D};
        OrbitWorld space = new OrbitWorld(body, ring);
        double[] u = unit(new double[]{0.6D, 0.0D, -0.8D});
        MotionSlot farOff = new MotionSlot();
        farOff.issue(MotionIntent.goTo(orbit));
        assertPointsAlong("INV-GDN-16 toward the body: from ten rings off the craft is sent at the body",
                farOff.fly(inCell(live, back(body, u, 10.0D * ring), cell), space).command(), u);
        MotionSlot onIt = new MotionSlot();
        onIt.issue(MotionIntent.goTo(orbit));
        assertEquals("INV-GDN-16 on the sphere: at rest on the ring about the body is there",
                MotionSlot.Event.ARRIVED, onIt.fly(inCell(live, back(body, u, ring), cell), space).event());
        assertRefusedFor("INV-GDN-16 not found: a body nobody can find", orbit,
                inCell(live, back(body, u, 10.0D * ring), cell), new OrbitWorld(GuidanceTarget.Unplaced.NOT_FOUND),
                MotionSlot.Refusal.TARGET_NOT_FOUND);
    }

    private static MotionSlot.Craft landingCraft(ShipCapability live, double[] position, double reach, double depth) {
        return new MotionSlot.Craft(SELF, PLANET_DIM, null, position, new double[]{0.0D, 0.0D, 0.0D},
                FreeFlightPhysics.Quat.IDENTITY, live, new double[]{0.0D, -PhysicsUnits.ENGINE_GRAVITY, 0.0D}, 40.0D,
                reach, depth, ATTITUDE_GAIN, SLEW_CAP);
    }

    @Test
    @Pins("INV-GDN-11")
    @RedWitness(verdict = "itself", member = "GuidanceTarget#placeIn",
            at = "if (ship.equals(craft.id)) {", replacedBy = "if (false) {",
            fails = "INV-GDN-11 itself", on = "2026-10-21")
    public void theCraftItselfIsRefusedAsItsOwnTarget() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double[] target = {200.0D, 30.0D, -40.0D};
        double[] u = {0.6D, 0.0D, 0.8D};
        assertRefusedFor("INV-GDN-11 itself: the craft named as its own target", GuidanceTarget.ship(SELF),
                atRestAt(live, back(target, u, 60.0D)), new Fixed(target, HULL, Double.NaN),
                MotionSlot.Refusal.TARGET_IS_THIS_CRAFT);
    }

    /**
     * INV-GDN-19's refusing half: a target that cannot be flown to is refused for what it is — one of
     * another system for the jump it needs (a ship the world says is there; a surface point of a body the
     * world says is of another system, asked from a planet and from space), and a ship mid-jump for being
     * in hyperspace. Whether a place IS of another system is the world's answer ({@code WorldSurroundings}),
     * an input here; it is pinned in a running game by INV-GDN-09.
     */
    @Test
    @Pins("INV-GDN-19")
    @RedWitness(verdict = "a ship of another system", member = "MotionSlot#refusalFor",
            at = "return Refusal.NEEDS_A_JUMP;", replacedBy = "return Refusal.TARGET_NOT_FOUND;",
            fails = "INV-GDN-19 another system: a ship", on = "2026-10-24")
    @RedWitness(verdict = "a ship in hyperspace", member = "MotionSlot#refusalFor",
            at = "return Refusal.TARGET_IN_HYPERSPACE;", replacedBy = "return Refusal.NEEDS_A_JUMP;",
            fails = "INV-GDN-19 in hyperspace", on = "2026-10-24")
    @RedWitness(verdict = "from a planet", member = "GuidanceTarget#outOfThisWorldFor",
            at = "return why != null ? Placement.unplaced(why) : climb(craft, around);",
            replacedBy = "return climb(craft, around);",
            fails = "INV-GDN-19 another system: a surface point asked from a planet", on = "2026-10-24")
    @RedWitness(verdict = "from space", member = "GuidanceTarget#intoTheShell",
            at = "return Placement.unplaced(shell.unplaced());",
            replacedBy = "return Placement.unplaced(Unplaced.NOT_FOUND);",
            fails = "INV-GDN-19 another system: a surface point asked from space", on = "2026-10-24")
    public void aTargetInAnotherSystemOrInHyperspaceIsRefusedForWhatItIs() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double[] away = back(new double[]{200.0D, 30.0D, -40.0D}, new double[]{0.6D, 0.0D, 0.8D}, 60.0D);
        double depth = 2.75D;
        double[] landed = {12.5D, 71.0D + depth, -7.5D};
        GuidanceTarget afar = GuidanceTarget.surface(PLANET_DIM + 1, landed[0], landed[2]);
        Elsewhere ofAnotherSystem = Elsewhere.withBody(200.0D, null, Double.NaN, GuidanceTarget.Unplaced.NEEDS_A_JUMP);

        assertRefusedFor("INV-GDN-19 another system: a ship the world says is of another system",
                GuidanceTarget.ship(OTHER), atRestAt(live, away),
                new Fixed(GuidanceTarget.Unplaced.NEEDS_A_JUMP), MotionSlot.Refusal.NEEDS_A_JUMP);
        assertRefusedFor("INV-GDN-19 in hyperspace: a ship the world says is mid-jump",
                GuidanceTarget.ship(OTHER), atRestAt(live, away),
                new Fixed(GuidanceTarget.Unplaced.IN_HYPERSPACE), MotionSlot.Refusal.TARGET_IN_HYPERSPACE);
        assertRefusedFor("INV-GDN-19 another system: a surface point asked from a planet", afar,
                onSurface(live, landed, depth, PLANET_DIM, null), ofAnotherSystem, MotionSlot.Refusal.NEEDS_A_JUMP);
        assertRefusedFor("INV-GDN-19 another system: a surface point asked from space", afar,
                inCell(live, away, GalacticCoord.ofSectorLocal(7L, 0L, 0L, 0L, 0L, 0L)), ofAnotherSystem,
                MotionSlot.Refusal.NEEDS_A_JUMP);
    }

    /**
     * INV-GDN-19's climb: from a planet every target in another world of the craft's system — a ship in
     * space, a ship on another planet, a surface point or the orbit of another body — sends the craft
     * straight up, reads CLIMB_TO_SPACE, is flown for longer than the refusal window without being refused,
     * and is never arrived at, at rest anywhere from the line to the room the clamp leaves above it. A ship
     * in the craft's own world reads TO_THE_TARGET. The world is an {@link Elsewhere}: where the target
     * stands and the line are inputs.
     */
    @Test
    @Pins("INV-GDN-19")
    @RedWitness(verdict = "a ship in space", member = "GuidanceTarget#placeIn",
            at = "if (seen.isInSpace()) {",
            replacedBy = "if (seen.isInSpace()) { return Placement.unplaced(Unplaced.NOT_FOUND); } if (false) {",
            fails = "INV-GDN-19 climbs: a ship in space", on = "2026-10-24")
    @RedWitness(verdict = "a ship on another planet", member = "GuidanceTarget#placeIn",
            at = "craft.cell != null ? intoTheShell(seen.planet(), craft, around) : climb(craft, around)",
            replacedBy = "Placement.unplaced(Unplaced.NOT_FOUND)",
            fails = "INV-GDN-19 climbs: a ship on another planet", on = "2026-10-24")
    @RedWitness(verdict = "a surface point of another body", member = "GuidanceTarget#placeIn",
            at = "return outOfThisWorldFor(dimension, craft, around);",
            replacedBy = "return Placement.unplaced(Unplaced.NOT_FOUND);",
            fails = "INV-GDN-19 climbs: a surface point of another body", on = "2026-10-24")
    @RedWitness(verdict = "the orbit of another body", member = "GuidanceTarget#orbitIn",
            at = "return outOfThisWorldFor(dimension, craft, around);",
            replacedBy = "return Placement.unplaced(Unplaced.NOT_FOUND);",
            fails = "INV-GDN-19 climbs: the orbit of another body", on = "2026-10-24")
    @RedWitness(verdict = "never arrived on the climb", member = "MotionSlot#fly",
            at = "!placed.provisional() && ", replacedBy = "",
            fails = "INV-GDN-19 never arrived on the climb", on = "2026-10-24")
    @RedWitness(verdict = "the leg read", member = "MotionSlot#fly",
            at = "leg = placed.leg();", replacedBy = "",
            fails = "INV-GDN-19 climbs: a ship in space: leg", on = "2026-10-24")
    @RedWitness(verdict = "a ship in this world", member = "GuidanceTarget#placeIn",
            at = "return Placement.at(seen.position(), craft.hullRadius + seen.hullRadius());",
            replacedBy = "return Placement.beforeACrossing(seen.position(), MotionSlot.Leg.CLIMB_TO_SPACE);",
            fails = "INV-GDN-19 leg: a ship in the craft's world", on = "2026-10-24")
    public void aTargetInAnotherWorldOfTheSystemIsClimbedToFromAPlanet() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double depth = 2.75D;
        double line = 200.0D;
        double[] grounded = {12.5D, 70.0D, -7.5D};
        double[] up = {0.0D, 1.0D, 0.0D};

        String[] names = {"a ship in space", "a ship on another planet", "a surface point of another body",
                "the orbit of another body"};
        GuidanceTarget[] targets = {GuidanceTarget.ship(OTHER), GuidanceTarget.ship(OTHER),
                GuidanceTarget.surface(PLANET_DIM + 1, 30.0D, 40.0D), GuidanceTarget.orbit(PLANET_DIM + 1)};
        Elsewhere[] worlds = {Elsewhere.withShip(line, Surroundings.Sighting.inSpace()),
                Elsewhere.withShip(line, Surroundings.Sighting.onPlanet(PLANET_DIM + 1)),
                Elsewhere.withBody(line, null, Double.NaN, null), Elsewhere.withBody(line, null, Double.NaN, null)};
        for (int i = 0; i < targets.length; i++) {
            String what = "INV-GDN-19 climbs: " + names[i];
            MotionSlot slot = goTo(targets[i]);
            MotionSlot.Step step = null;
            for (int tick = 0; tick <= MotionSlot.REFUSAL_WINDOW_TICKS; tick++) {
                step = slot.fly(onSurface(live, grounded, depth, PLANET_DIM, null), worlds[i]);
            }
            assertEquals(what + ": still flown after the refusal window", MotionSlot.State.ACTIVE,
                    slot.reading().state());
            assertPointsAlong(what, step.command(), up);
            assertEquals(what + ": leg", MotionSlot.Leg.CLIMB_TO_SPACE, slot.reading().leg());
            // At rest at every height from the line to the top of the room the clamp leaves above it.
            for (int over = 0; over <= ShipEntryController.PHYSICS_CLAMP_ENTRY_MARGIN; over++) {
                double[] there = {grounded[0], line + over, grounded[2]};
                assertEquals("INV-GDN-19 never arrived on the climb: " + names[i] + " at rest " + over
                                + " blocks above the line", MotionSlot.Event.NONE,
                        goTo(targets[i]).fly(onSurface(live, there, depth, PLANET_DIM, null), worlds[i]).event());
            }
        }

        // A ship in the craft's own world is the target itself.
        double[] near = {200.0D, 30.0D, -40.0D};
        MotionSlot here = goToShip();
        here.fly(atRestAt(live, back(near, unit(new double[]{0.6D, 0.0D, -0.8D}), 60.0D)),
                new Fixed(near, HULL, Double.NaN));
        assertEquals("INV-GDN-19 leg: a ship in the craft's world", MotionSlot.Leg.TO_THE_TARGET, here.reading().leg());
    }

    /**
     * INV-GDN-19's way into the shell: from space a surface point, or a ship on a planet, sends the craft
     * at the body from ten shells off and from just outside the shell; a craft resting deeper inside than
     * the clause's depth (the arrival radius plus two blocks) is held — commanded no speed — and is not
     * arrived, nor is one resting at that depth; each reads INTO_THE_SHELL. The world is an
     * {@link Elsewhere}: where the target stands and the body's shell are inputs.
     */
    @Test
    @Pins("INV-GDN-19")
    @RedWitness(verdict = "toward the body", member = "GuidanceTarget#placeIn",
            at = "return intoTheShell(dimension, craft, around);",
            replacedBy = "return Placement.unplaced(Unplaced.NOT_FOUND);",
            fails = "INV-GDN-19 into the shell: a surface point", on = "2026-10-24")
    @RedWitness(verdict = "a ship on the planet", member = "GuidanceTarget#placeIn",
            at = "craft.cell != null ? intoTheShell(seen.planet(), craft, around) : climb(craft, around)",
            replacedBy = "craft.cell != null ? Placement.unplaced(Unplaced.NOT_FOUND) : climb(craft, around)",
            fails = "INV-GDN-19 into the shell: a ship on the planet from ten shells off", on = "2026-10-24")
    @RedWitness(verdict = "inward from the shell", member = "GuidanceTarget#intoTheShell",
            at = "double inside = Math.max(0.0D, shell.radius() - INTO_THE_SHELL);",
            replacedBy = "double inside = Math.max(0.0D, shell.radius() + INTO_THE_SHELL);",
            fails = "INV-GDN-19 inward from the shell", on = "2026-10-24")
    @RedWitness(verdict = "held deep inside", member = "GuidanceTarget#intoTheShell",
            at = "double[] aim = d <= inside ? craft.position.clone()", replacedBy = "double[] aim = false ? craft.position.clone()",
            fails = "INV-GDN-19 held deep inside", on = "2026-10-24")
    @RedWitness(verdict = "never arrived in the shell", member = "MotionSlot#fly",
            at = "!placed.provisional() && ", replacedBy = "",
            fails = "INV-GDN-19 never arrived in the shell", on = "2026-10-24")
    @RedWitness(verdict = "the leg read", member = "MotionSlot#fly",
            at = "leg = placed.leg();", replacedBy = "",
            fails = "INV-GDN-19 into the shell: a surface point: leg", on = "2026-10-24")
    public void aTargetOnAPlanetIsFlownIntoItsBodysShellFromSpace() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        // About a body whose shell is 1 000 blocks round.
        GalacticCoord cell = GalacticCoord.ofSectorLocal(7L, 0L, 0L, 0L, 0L, 0L);
        double[] body = {300.0D, -40.0D, 500.0D};
        double shell = 1000.0D;
        double clauseDepth = ARRIVAL_RADIUS + 2.0D;
        double[] u = unit(new double[]{0.6D, 0.0D, -0.8D});
        String[] spaceNames = {"a surface point", "a ship on the planet"};
        GuidanceTarget[] down = {GuidanceTarget.surface(PLANET_DIM, 30.0D, 40.0D), GuidanceTarget.ship(OTHER)};
        Elsewhere[] spaceWorlds = {Elsewhere.withBody(Double.NaN, body, shell, null),
                Elsewhere.withShipAndBody(Surroundings.Sighting.onPlanet(PLANET_DIM), body, shell)};
        for (int i = 0; i < down.length; i++) {
            String what = "INV-GDN-19 into the shell: " + spaceNames[i];
            MotionSlot far = goTo(down[i]);
            assertPointsAlong(what + " from ten shells off",
                    far.fly(inCell(live, back(body, u, 10.0D * shell), cell), spaceWorlds[i]).command(), u);
            assertEquals(what + ": leg", MotionSlot.Leg.INTO_THE_SHELL, far.reading().leg());
            assertPointsAlong("INV-GDN-19 inward from the shell: " + spaceNames[i] + " just outside it",
                    goTo(down[i]).fly(inCell(live, back(body, u, shell + 1.0D), cell), spaceWorlds[i]).command(), u);
            MotionSlot.Step deep = goTo(down[i]).fly(inCell(live, back(body, u, shell - clauseDepth - 1.0D), cell),
                    spaceWorlds[i]);
            assertEquals("INV-GDN-19 held deep inside: " + spaceNames[i] + " commanded no speed", 0.0D,
                    speedOf(deep.command()), 0.0D);
            assertEquals("INV-GDN-19 never arrived in the shell: " + spaceNames[i] + " at rest deep inside",
                    MotionSlot.Event.NONE, deep.event());
            assertEquals("INV-GDN-19 never arrived in the shell: " + spaceNames[i] + " at rest at its depth",
                    MotionSlot.Event.NONE, goTo(down[i]).fly(inCell(live, back(body, u, shell - clauseDepth), cell),
                            spaceWorlds[i]).event());
        }
    }

    private static MotionSlot goTo(GuidanceTarget target) {
        MotionSlot slot = new MotionSlot();
        slot.issue(MotionIntent.goTo(target));
        return slot;
    }

    @Test
    @Pins("INV-GDN-12")
    @RedWitness(verdict = "level while it delivers", member = "Plan#approach",
            at = "if (level.refusal == null || level.refusal == Refusal.CANNOT_HOLD_ITS_WEIGHT) {",
            replacedBy = "if (level.refusal == Refusal.CANNOT_HOLD_ITS_WEIGHT) {",
            fails = "INV-GDN-12 level while it delivers", on = "2026-10-22")
    @RedWitness(verdict = "flown, not refused", member = "Plan#approach",
            at = "Lean lean = Lean.of(craft);", replacedBy = "Lean lean = null;",
            fails = "INV-GDN-12 flown, not refused", on = "2026-10-22")
    @RedWitness(verdict = "flown, not refused", member = "MotionSlot#weightFits",
            at = "if (Math.abs(g) <= AXIS_EPSILON * field) {", replacedBy = "if (false) {",
            fails = "INV-GDN-12 flown, not refused", on = "2026-10-22")
    @RedWitness(verdict = "leans toward the aim", member = "Lean#toward",
            at = "double[] h = {across[0] / fh, across[1] / fh, across[2] / fh};",
            replacedBy = "double[] h = {-across[0] / fh, -across[1] / fh, -across[2] / fh};",
            fails = "INV-GDN-12 leans toward the aim", on = "2026-10-22")
    @RedWitness(verdict = "never steeper", member = "Lean#toward",
            at = "double angle = Math.min(Math.atan2(fh, fv), Math.acos(g / lift));",
            replacedBy = "double angle = Math.atan2(fh, fv);",
            fails = "INV-GDN-12 never steeper", on = "2026-10-23")
    public void inAFieldTheHullStaysLevelWhileLevelDeliversAndLeansOnlyWhenItCannot() {
        double[] gravity = {0.0D, -PhysicsUnits.ENGINE_GRAVITY, 0.0D};
        double[] start = {0.0D, 64.0D, 0.0D};
        double[] u = {1.0D, 0.0D, 0.0D};
        Fixed around = new Fixed(new double[]{400.0D, 64.0D, 0.0D}, HULL, Double.NaN);
        double[] still = {0.0D, 0.0D, 0.0D};

        // Level delivers something across the vertical: the hull stays level, though leaning its heave
        // would push it many times harder.
        for (ShipMotionCases.Hull hull : new ShipMotionCases.Hull[]{ShipMotionCases.weakSwayHull(),
                ShipMotionCases.symmetricHull()}) {
            ShipCapability live = solve(hull);
            requireArranged("the arrangement: the " + hull.name + " pushes both ways across the vertical",
                    live.authority(ControlDirection.SWAY_POSITIVE, Endurance.SUSTAINED) > 0.0D
                            && live.authority(ControlDirection.SWAY_NEGATIVE, Endurance.SUSTAINED) > 0.0D);
            FlightCommand levelled = goToShip().fly(craft(live, start, still, FreeFlightPhysics.Quat.IDENTITY,
                    gravity, 40.0D), around).command();
            assertTrue("INV-GDN-12 level while it delivers: the " + hull.name + " was commanded "
                    + Arrays.toString(levelled.attitude()) + ", with no axis straight up",
                    keepsAnAxisUp(live, quat(levelled)));
        }

        // Level delivers nothing across the vertical: the heave-only hull leans.
        ShipMotionCases.Hull heaveOnly = ShipMotionCases.heaveOnlyHull();
        ShipCapability live = solve(heaveOnly);
        requireArranged("the arrangement: the heave-only hull pushes along heave alone",
                strongestAcross(live) == 0.0D
                        && live.authority(ControlDirection.HEAVE_POSITIVE, Endurance.SUSTAINED) > 0.0D);
        double lift = live.authority(ControlDirection.HEAVE_POSITIVE, Endurance.SUSTAINED) / heaveOnly.declaredMass()
                * PhysicsUnits.ACCELERATION;
        double steepest = Math.acos(PhysicsUnits.ENGINE_GRAVITY / lift);
        // Off level to start with, about a skew axis, as a craft in the world always is a little: the level
        // attitude it is turned back to then carries the rounding of that turn.
        FreeFlightPhysics.Quat offLevel = new FreeFlightPhysics.Quat(0.99D, 0.07D, 0.05D, -0.06D).normalized();
        MotionSlot slot = goToShip();
        MotionSlot.Step step = null;
        for (int tick = 0; tick <= MotionSlot.REFUSAL_WINDOW_TICKS; tick++) {
            step = slot.fly(craft(live, start, still, offLevel, gravity, 40.0D), around);
            assertEquals("INV-GDN-12 flown, not refused: tick " + tick + " of a hull that can lean",
                    MotionSlot.Event.NONE, step.event());
        }
        assertPointsAlong("INV-GDN-12 flown, not refused: sent toward the aim", step.command(), u);
        double[] heave = axisOf(live, quat(step.command()), ControlDirection.HEAVE_POSITIVE);
        double across = Math.hypot(heave[0], heave[2]);
        assertTrue("INV-GDN-12 leans toward the aim: the heave axis lies along " + Arrays.toString(heave),
                across > 0.0D && heave[0] / across >= 1.0D - RELATIVE);
        assertTrue("INV-GDN-12 never steeper: leaned " + Math.acos(heave[1]) + " rad from the vertical at rest, past "
                + steepest, Math.acos(heave[1]) <= steepest * (1.0D + RELATIVE));

        // Whatever the craft is doing — here flying away from the aim at fifty times its top speed, so it
        // lacks the most acceleration it ever could — it never leans past the angle its lift holds its weight at.
        double[] fleeing = {-2000.0D, 0.0D, 0.0D};
        FlightCommand recovering = goToShip().fly(craft(live, start, fleeing, FreeFlightPhysics.Quat.IDENTITY,
                gravity, 40.0D), around).command();
        double[] leaned = axisOf(live, quat(recovering), ControlDirection.HEAVE_POSITIVE);
        assertTrue("INV-GDN-12 never steeper: leaned " + Math.acos(leaned[1]) + " rad from the vertical while fleeing,"
                + " past " + steepest, Math.acos(leaned[1]) <= steepest * (1.0D + RELATIVE));
    }

    @Test
    @Pins("INV-GDN-12")
    @RedWitness(verdict = "the swing is planned", member = "Plan#leaning",
            at = "double turn = turnTime(craft, closeIn, brakeIn);", replacedBy = "double turn = 0.0D;",
            fails = "INV-GDN-12 the swing is planned", on = "2026-10-22")
    public void swingingTheLeanFromClosingToBrakingIsPlannedLikeATurnover() {
        // Two heave-only crafts alike in all but how fast their attitude law may slew: at rest, far from an
        // aim to the side, with top speed to spare. A craft that swings its lean back slower needs longer
        // before it can brake, so it may be flown no faster than the quicker one, and is flown slower.
        ShipCapability live = solve(ShipMotionCases.heaveOnlyHull());
        double[] gravity = {0.0D, -PhysicsUnits.ENGINE_GRAVITY, 0.0D};
        double[] start = {0.0D, 64.0D, 0.0D};
        Fixed around = new Fixed(new double[]{20000.0D, 64.0D, 0.0D}, HULL, Double.NaN);
        double topSpeed = 4000.0D;
        double quick = speedOf(goToShip().fly(new MotionSlot.Craft(SELF, PLANET_DIM, null, start,
                new double[]{0.0D, 0.0D, 0.0D}, FreeFlightPhysics.Quat.IDENTITY, live, gravity, topSpeed, HULL, HULL,
                ATTITUDE_GAIN, SLEW_CAP), around).command());
        double slow = speedOf(goToShip().fly(new MotionSlot.Craft(SELF, PLANET_DIM, null, start,
                new double[]{0.0D, 0.0D, 0.0D}, FreeFlightPhysics.Quat.IDENTITY, live, gravity, topSpeed, HULL, HULL,
                ATTITUDE_GAIN, SLEW_CAP / 10.0D), around).command());
        requireArranged("the arrangement: the quick craft is flown, below its top speed: " + quick,
                quick > 0.0D && quick < topSpeed);
        assertTrue("INV-GDN-12 the swing is planned: a craft slewing at a tenth of the rate was commanded " + slow
                + ", not slower than the quick one's " + quick, slow < quick);
    }

    @Test
    @Pins("INV-GDN-13")
    @RedWitness(verdict = "approached", member = "GuidanceTarget#placeIn",
            at = "if (!seen.measured()) {",
            replacedBy = "if (!seen.measured()) { return Placement.unplaced(Unplaced.NOT_MEASURED); } if (false) {",
            fails = "INV-GDN-13 approached", on = "2026-10-22")
    @RedWitness(verdict = "made for itself", member = "MotionSlot#fly",
            at = "double[] aim = placed.provisional() ? placed.position()",
            replacedBy = "double[] aim = false ? placed.position()",
            fails = "INV-GDN-13 made for itself", on = "2026-10-22")
    @RedWitness(verdict = "never arrived", member = "MotionSlot#fly",
            at = "!placed.provisional() && ", replacedBy = "",
            fails = "INV-GDN-13 never arrived", on = "2026-10-22")
    @RedWitness(verdict = "measured", member = "GuidanceTarget#placeIn",
            at = "if (!seen.measured()) {", replacedBy = "if (true) {",
            fails = "INV-GDN-13 measured", on = "2026-10-22")
    @RedWitness(verdict = "gone", member = "MotionSlot#refusalFor",
            at = "return Refusal.TARGET_NOT_FOUND;", replacedBy = "return Refusal.NEEDS_A_JUMP;",
            fails = "INV-GDN-13 gone", on = "2026-10-22")
    public void aShipWhoseHullCannotBeMeasuredIsMadeForNotRefusedAndNeverArrivedAt() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        double[] target = {2000.0D, 30.0D, -40.0D};
        double[] u = {0.6D, 0.0D, 0.8D};
        Fixed noHull = new Fixed(target, null);
        // Where a measured hull of this class would be arrived at: the two hulls plus the arrival radius.
        double standoff = HULL + HULL + ARRIVAL_RADIUS;

        // Far off: flown toward it for longer than the refusal window, never refused.
        MotionSlot far = goToShip();
        MotionSlot.Step step = null;
        for (int tick = 0; tick <= MotionSlot.REFUSAL_WINDOW_TICKS; tick++) {
            step = far.fly(atRestAt(live, back(target, u, 1000.0D)), noHull);
            assertEquals("INV-GDN-13 approached: tick " + tick + " toward a ship whose hull nobody measured",
                    MotionSlot.Event.NONE, step.event());
        }
        assertEquals("INV-GDN-13 approached: the slot still flies the order", MotionSlot.State.ACTIVE,
                far.reading().state());
        assertPointsAlong("INV-GDN-13 approached: sent toward the ship", step.command(), u);

        // At rest ON the point it has not arrived; at rest on the standoff of the same ship MEASURED, it has.
        assertEquals("INV-GDN-13 never arrived: at rest on the point of a ship nobody measured",
                MotionSlot.Event.NONE, goToShip().fly(atRestAt(live, target), noHull).event());
        assertEquals("INV-GDN-13 measured: at rest on the measured hull's standoff",
                MotionSlot.Event.ARRIVED, goToShip().fly(atRestAt(live, back(target, u, standoff)),
                        new Fixed(target, HULL, Double.NaN)).event());

        // No shell is made up for a hull nobody measured: a HOLD whose range the craft is already inside is
        // still sent on to the point itself, where a measured hull's shell would leave it where it is.
        double range = 50.0D;
        double[] inRange = back(target, u, range / 2.0D);
        requireArranged("the arrangement: a measured hull's HOLD leaves a craft this far in where it is",
                speedOf(holdShip(range).fly(atRestAt(live, inRange), new Fixed(target, HULL, Double.NaN))
                        .command()) == 0.0D);
        assertPointsAlong("INV-GDN-13 made for itself: a HOLD inside its range of a ship nobody measured",
                holdShip(range).fly(atRestAt(live, inRange), noHull).command(), u);

        // A ship that no longer exists anywhere is refused, for that.
        assertRefusedFor("INV-GDN-13 gone: a ship the world finds nowhere", GuidanceTarget.ship(OTHER),
                atRestAt(live, back(target, u, 1000.0D)),
                new Fixed(GuidanceTarget.Unplaced.NOT_FOUND), MotionSlot.Refusal.TARGET_NOT_FOUND);
    }

    @Test
    @Pins("INV-GDN-15")
    @RedWitness(verdict = "sent across", member = "GuidanceTarget#throughTheSeam",
            at = "aim[i] = Math.max(aim[i], FIRST_CARRIED);", replacedBy = "",
            fails = "INV-GDN-15 sent across", on = "2026-10-22")
    @RedWitness(verdict = "sent back across", member = "GuidanceTarget#throughTheSeam",
            at = "aim[i] = Math.min(aim[i], -FIRST_CARRIED);", replacedBy = "",
            fails = "INV-GDN-15 sent back across", on = "2026-10-22")
    @RedWitness(verdict = "beyond the line", member = "GuidanceTarget#throughTheSeam",
            at = "aim[i] = Math.max(aim[i], FIRST_CARRIED);", replacedBy = "aim[i] = FIRST_CARRIED;",
            fails = "INV-GDN-15 beyond the line", on = "2026-10-22")
    public void aShipInTheNeighbouringCellIsMadeForThroughTheSeam() {
        ShipCapability live = solve(ShipMotionCases.symmetricHull());
        GalacticCoord here = GalacticCoord.ofSectorLocal(5L, 0L, 0L, 0L, 0L, 0L);
        GalacticCoord east = GalacticCoord.ofSectorLocal(6L, 0L, 0L, 0L, 0L, 0L);
        GalacticCoord west = GalacticCoord.ofSectorLocal(4L, 0L, 0L, 0L, 0L, 0L);
        // The seam's own line, from production: a pose rounded to the block is carried strictly past it.
        double line = GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN;
        double carriedFrom = line + 1.0D;

        // A ship of the east cell parked just past the face, inside the band in which the seam does not carry
        // a craft yet. A craft at rest on its very point, and one on the line itself, are both sent on east
        // to the first offset the seam carries from, the ship's own height and depth kept.
        double[] parked = {GalacticCoord.HALF_CELL + 5.0D, 30.0D, -40.0D};
        Fixed inEast = new Fixed(parked, east);
        requireArranged("the arrangement: the parked ship's point is one the seam does not carry a craft from",
                !CellSeam.shouldCarry(parked[0], parked[1], parked[2]));
        for (double x : new double[]{parked[0], line}) {
            double[] at = {x, 0.0D, 0.0D};
            double[] way = unit(new double[]{carriedFrom - x, parked[1], parked[2]});
            MotionSlot.Step step = goToShip().fly(inCell(live, at, here), inEast);
            assertEquals("INV-GDN-15 sent across: at " + x + " the craft has not arrived", MotionSlot.Event.NONE,
                    step.event());
            assertPointsAlong("INV-GDN-15 sent across: a craft at " + x, step.command(), way);
        }
        requireArranged("the arrangement: the point it is sent to is one the seam carries a craft from",
                CellSeam.shouldCarry(carriedFrom, parked[1], parked[2]));

        // The same ship seen from the east cell's other neighbour, mirrored: sent back across the west face.
        double[] mirrored = {-parked[0], parked[1], parked[2]};
        assertPointsAlong("INV-GDN-15 sent back across: a craft at rest on a west ship's point",
                goToShip().fly(inCell(live, mirrored, here), new Fixed(mirrored, west)).command(),
                unit(new double[]{-carriedFrom - mirrored[0], 0.0D, 0.0D}));

        // A ship already past the line is made for where it is.
        double[] far = {line + 5000.0D, 30.0D, -40.0D};
        double[] from = {GalacticCoord.HALF_CELL - 100.0D, 0.0D, 0.0D};
        assertPointsAlong("INV-GDN-15 beyond the line: a ship past the carry line",
                goToShip().fly(inCell(live, from, here), new Fixed(far, east)).command(),
                unit(new double[]{far[0] - from[0], far[1] - from[1], far[2] - from[2]}));
    }

    @Test
    @Pins("SHIPCTL-21")
    @RedWitness(verdict = "leaving", member = "TileAdvancedFlightComputer#setPilotInput",
            at = "input != null && motion.reclaim()", replacedBy = "input == null && motion.reclaim()",
            fails = "SHIPCTL-21 leaving", on = "2026-10-21")
    @RedWitness(verdict = "arriving", member = "TileAdvancedFlightComputer#setPilotInput",
            at = "input != null && motion.reclaim()", replacedBy = "input != null && motion.holdsAnIntent()",
            fails = "SHIPCTL-21 arriving", on = "2026-10-21")
    @RedWitness(verdict = "nothing to resume", member = "MotionSlot#reclaim",
            at = "intent = null;", replacedBy = "",
            fails = "SHIPCTL-21 nothing to resume", on = "2026-10-21")
    public void aPilotsInputTakesTheCraftBackAndNothingResumes() {
        MinecraftBootstrap.ensure();
        TileAdvancedFlightComputer computer = new TileAdvancedFlightComputer();
        computer.issueMotion(MotionIntent.goTo(GuidanceTarget.ship(OTHER)));
        requireArranged("the arrangement: the slot flies the order it was given",
                computer.motionReading().state() == MotionSlot.State.ACTIVE);

        computer.setPilotInput(null);
        assertEquals("SHIPCTL-21 leaving: a pilot leaving the seat took the craft back",
                MotionSlot.State.ACTIVE, computer.motionReading().state());

        computer.setPilotInput(FreeFlightInput.zero());
        assertEquals("SHIPCTL-21 arriving: a pilot's input reached the computer and the slot still flies",
                MotionSlot.State.EMPTY, computer.motionReading().state());
        assertNull("SHIPCTL-21 nothing to resume: the emptied slot still holds an order",
                computer.motionReading().intent());
    }

    /** The world as these laws are written over: one ship at a stated place, and ground at a stated height. */
    private static final class Fixed implements Surroundings {

        private final double[] ship;
        private final double shipHull;
        private final boolean measured;
        private final GalacticCoord cell;
        private final double ground;
        private final GuidanceTarget.Unplaced unplaced;

        /** A ship at {@code ship} in the craft's own world, its hull measured at {@code shipHull}. */
        Fixed(double[] ship, double shipHull, double ground) {
            this.ship = ship;
            this.shipHull = shipHull;
            this.measured = true;
            this.cell = null;
            this.ground = ground;
            this.unplaced = null;
        }

        /**
         * A ship at {@code ship} whose hull nobody here can measure: in the world of the space cell
         * {@code cell}, or, with {@code cell} null, in the craft's own world with no blocks in its record.
         */
        Fixed(double[] ship, GalacticCoord cell) {
            this.ship = ship;
            this.shipHull = Double.NaN;
            this.measured = false;
            this.cell = cell;
            this.ground = Double.NaN;
            this.unplaced = null;
        }

        Fixed(GuidanceTarget.Unplaced unplaced) {
            this.ship = null;
            this.shipHull = HULL;
            this.measured = false;
            this.cell = null;
            this.ground = Double.NaN;
            this.unplaced = unplaced;
        }

        @Override
        public Sighting ship(UUID shipId) {
            if (unplaced != null) {
                return Sighting.unplaced(unplaced);
            }
            if (!OTHER.equals(shipId)) {
                return Sighting.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
            }
            if (measured) {
                return Sighting.at(ship, shipHull);
            }
            return cell == null ? Sighting.withoutBlocks(ship) : Sighting.inAnotherCell(ship, cell);
        }

        @Override
        public double groundTop(double x, double z, double radius) {
            return ground;
        }

        // This world states nothing about orbits: no clause it serves asks it, and an answer here would be
        // one nobody wrote down.
        @Override
        public double orbitLine() {
            throw new UnsupportedOperationException("this world states no orbit line");
        }

        @Override
        public boolean openAbove(double x, double z, double fromY, double toY) {
            throw new UnsupportedOperationException("this world states no column");
        }

        @Override
        public Sphere orbit(int dimension) {
            throw new UnsupportedOperationException("this world states no body");
        }

        @Override
        public Sphere shell(int dimension) {
            throw new UnsupportedOperationException("this world states no body");
        }

        @Override
        public GuidanceTarget.Unplaced unreachable(int dimension) {
            throw new UnsupportedOperationException("this world states no body");
        }
    }

    /**
     * The world as INV-GDN-19 is written over: where a target ship stands when it is not in the craft's
     * world (in space, on another planet), the craft's world's orbit line with its column open, and, for
     * the body of any planet world asked, either why it cannot be reached ({@code body}) or — seen from
     * space — its descent shell about {@code centre}. Each an input.
     */
    private static final class Elsewhere implements Surroundings {

        private final Sighting sighting;
        private final double line;
        private final double[] centre;
        private final double shellRadius;
        private final GuidanceTarget.Unplaced body;

        private Elsewhere(Sighting sighting, double line, double[] centre, double shellRadius,
                          GuidanceTarget.Unplaced body) {
            this.sighting = sighting;
            this.line = line;
            this.centre = centre;
            this.shellRadius = shellRadius;
            this.body = body;
        }

        /** A planet world whose orbit line is {@code line}, the target ship seen as {@code sighting}. */
        static Elsewhere withShip(double line, Sighting sighting) {
            return new Elsewhere(sighting, line, null, Double.NaN, null);
        }

        /** Every body's shell about {@code centre} ({@code null}: none asked), or unreachable for {@code body}. */
        static Elsewhere withBody(double line, double[] centre, double shellRadius, GuidanceTarget.Unplaced body) {
            return new Elsewhere(Sighting.unplaced(GuidanceTarget.Unplaced.NOT_FOUND), line, centre, shellRadius, body);
        }

        /** Space: the target ship seen as {@code sighting}, every body's shell about {@code centre}. */
        static Elsewhere withShipAndBody(Sighting sighting, double[] centre, double shellRadius) {
            return new Elsewhere(sighting, Double.NaN, centre, shellRadius, null);
        }

        @Override
        public Sighting ship(UUID shipId) {
            return OTHER.equals(shipId) ? sighting : Sighting.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
        }

        @Override
        public double groundTop(double x, double z, double radius) {
            throw new UnsupportedOperationException("this world states no ground");
        }

        @Override
        public double orbitLine() {
            return line;
        }

        @Override
        public boolean openAbove(double x, double z, double fromY, double toY) {
            return true;
        }

        @Override
        public Sphere orbit(int dimension) {
            if (body != null) {
                return Sphere.unplaced(body);
            }
            throw new UnsupportedOperationException("this world states no orbit");
        }

        @Override
        public Sphere shell(int dimension) {
            if (body != null) {
                return Sphere.unplaced(body);
            }
            if (centre == null) {
                throw new UnsupportedOperationException("this world states no shell");
            }
            return Sphere.about(centre, shellRadius);
        }

        @Override
        public GuidanceTarget.Unplaced unreachable(int dimension) {
            return body;
        }
    }

    /**
     * The world as INV-GDN-16 is written over: a planet world with an orbit line (or none) and, maybe, one
     * solid block somewhere in every column at a stated height; and, seen from space, the sphere about the
     * body of {@link #PLANET_DIM} (or why it cannot be placed).
     */
    private static final class OrbitWorld implements Surroundings {

        private final double line;
        private final double solidAt;
        private final double[] centre;
        private final double radius;
        private final GuidanceTarget.Unplaced unplaced;

        /** A planet world whose orbit line is {@code line} (NaN: none), solid at height {@code solidAt} (NaN: nowhere). */
        OrbitWorld(double line, double solidAt) {
            this.line = line;
            this.solidAt = solidAt;
            this.centre = null;
            this.radius = Double.NaN;
            this.unplaced = null;
        }

        /** Space, where the body's orbit is the sphere of {@code radius} about {@code centre}. */
        OrbitWorld(double[] centre, double radius) {
            this.line = Double.NaN;
            this.solidAt = Double.NaN;
            this.centre = centre;
            this.radius = radius;
            this.unplaced = null;
        }

        /** Space, where the body's orbit cannot be placed, for {@code why}. */
        OrbitWorld(GuidanceTarget.Unplaced why) {
            this.line = Double.NaN;
            this.solidAt = Double.NaN;
            this.centre = null;
            this.radius = Double.NaN;
            this.unplaced = why;
        }

        @Override
        public Sighting ship(UUID shipId) {
            return Sighting.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
        }

        @Override
        public double groundTop(double x, double z, double radius) {
            throw new UnsupportedOperationException("this world states no ground");
        }

        @Override
        public double orbitLine() {
            return line;
        }

        @Override
        public boolean openAbove(double x, double z, double fromY, double toY) {
            return Double.isNaN(solidAt) || solidAt < fromY || solidAt > toY;
        }

        @Override
        public Sphere orbit(int dimension) {
            if (dimension != PLANET_DIM) {
                return Sphere.unplaced(GuidanceTarget.Unplaced.NOT_FOUND);
            }
            return unplaced != null ? Sphere.unplaced(unplaced) : Sphere.about(centre, radius);
        }

        @Override
        public Sphere shell(int dimension) {
            throw new UnsupportedOperationException("this world states no shell");
        }

        @Override
        public GuidanceTarget.Unplaced unreachable(int dimension) {
            throw new UnsupportedOperationException("this world states no other body");
        }
    }

    private static MotionSlot goToShip() {
        MotionSlot slot = new MotionSlot();
        slot.issue(MotionIntent.goTo(GuidanceTarget.ship(OTHER)));
        return slot;
    }

    private static MotionSlot holdShip(double range) {
        MotionSlot slot = new MotionSlot();
        slot.issue(MotionIntent.hold(GuidanceTarget.ship(OTHER), range));
        return slot;
    }

    private static MotionSlot landAt(double[] point) {
        MotionSlot slot = new MotionSlot();
        slot.issue(MotionIntent.goTo(GuidanceTarget.surface(PLANET_DIM, point[0], point[2])));
        return slot;
    }

    /** The point {@code distance} back from {@code target} along {@code -u}. */
    private static double[] back(double[] target, double[] u, double distance) {
        return new double[]{target[0] - u[0] * distance, target[1] - u[1] * distance, target[2] - u[2] * distance};
    }

    private static void assertRefusedFor(String what, GuidanceTarget target, MotionSlot.Craft craft,
                                         Surroundings around, MotionSlot.Refusal expected) {
        MotionSlot slot = new MotionSlot();
        slot.issue(MotionIntent.goTo(target));
        MotionSlot.Step step = null;
        for (int tick = 0; tick < MotionSlot.REFUSAL_WINDOW_TICKS; tick++) {
            step = slot.fly(craft, around);
        }
        assertEquals(what + ": refused when the window ran out", MotionSlot.Event.REFUSED, step.event());
        assertEquals(what + ": for the reason the clause names", expected, slot.reading().refusal());
    }

    private static void assertPointsAlong(String what, FlightCommand command, double[] way) {
        double[] v = command.velocity();
        double vn = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double cos = vn == 0.0D ? 0.0D : (v[0] * way[0] + v[1] * way[1] + v[2] * way[2]) / vn;
        assertTrue(what + ": the craft is sent along " + Arrays.toString(v) + ", not along "
                + Arrays.toString(way) + " (cosine " + cos + ")", cos >= 1.0D - RELATIVE);
    }

    private static void assertHover(String what, FlightCommand command, FreeFlightPhysics.Quat attitude) {
        assertNotNull(what + ": a command", command);
        assertArrayEquals(what + ": zero velocity", new double[]{0.0D, 0.0D, 0.0D}, command.velocity(), 0.0D);
        assertArrayEquals(what + ": in the attitude it was refused in",
                new double[]{attitude.w, attitude.x, attitude.y, attitude.z}, command.attitude(), 0.0D);
    }

    private static ShipCapability solve(ShipMotionCases.Hull hull) {
        return ShipCapability.solve(hull.actuators, hull.mass, ShipMotionCases.HELM);
    }

    /** The most force any one signed translation of the hull delivers, N. */
    private static double strongestTranslation(ShipCapability live) {
        double most = 0.0D;
        for (ControlDirection d : ControlDirection.values()) {
            if (!d.axis().isRotation()) {
                most = Math.max(most, live.authority(d, Endurance.SUSTAINED));
            }
        }
        return most;
    }

    /**
     * The most force the hull's translations deliver together, N: each axis at the larger of its two
     * senses, summed as orthogonal components — no direction gets more out of them.
     */
    private static double combinedTranslation(ShipCapability live) {
        double sum = 0.0D;
        for (ControlDirection d : ControlDirection.values()) {
            if (!d.axis().isRotation() && d.isPositive()) {
                double most = Math.max(live.authority(d, Endurance.SUSTAINED),
                        live.authority(ControlDirection.of(d.axis(), false), Endurance.SUSTAINED));
                sum += most * most;
            }
        }
        return Math.sqrt(sum);
    }

    /** The most force any signed surge or sway delivers, N. */
    private static double strongestAcross(ShipCapability live) {
        double most = 0.0D;
        for (ControlDirection d : ControlDirection.values()) {
            if (!d.axis().isRotation() && d != ControlDirection.HEAVE_POSITIVE && d != ControlDirection.HEAVE_NEGATIVE) {
                most = Math.max(most, live.authority(d, Endurance.SUSTAINED));
            }
        }
        return most;
    }

    private static FreeFlightPhysics.Quat quat(FlightCommand command) {
        double[] q = command.attitude();
        return new FreeFlightPhysics.Quat(q[0], q[1], q[2], q[3]).normalized();
    }

    private static double speedOf(FlightCommand command) {
        double[] v = command.velocity();
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /** Whether the hull has sustained authority both ways along each of its translation axes. */
    private static boolean pushesBothWaysAlongEveryAxis(ShipCapability live) {
        for (ControlDirection d : ControlDirection.values()) {
            if (!d.axis().isRotation() && !(live.authority(d, Endurance.SUSTAINED) > 0.0D)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the hull has angular authority both ways about each of its axes. */
    private static boolean turnsAboutEveryAxis(ShipCapability live) {
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation() && !(live.authority(d, Endurance.BURST) > 0.0D)) {
                return false;
            }
        }
        return true;
    }

    /** The authority, N, of the signed axis the hull at {@code attitude} lays along {@code w}; 0 if none. */
    private static double authorityAlong(ShipCapability live, FreeFlightPhysics.Quat attitude, double[] w) {
        for (ControlDirection d : ControlDirection.values()) {
            if (d.axis().isRotation()) {
                continue;
            }
            double[] a = axisOf(live, attitude, d);
            if (a[0] * w[0] + a[1] * w[1] + a[2] * w[2] >= 1.0D - 1.0e-6D) {
                return live.authority(d, Endurance.SUSTAINED);
            }
        }
        return 0.0D;
    }

    /** Whether the hull at {@code attitude} has one of its axes pointing straight up. */
    private static boolean keepsAnAxisUp(ShipCapability live, FreeFlightPhysics.Quat attitude) {
        for (ControlDirection d : ControlDirection.values()) {
            if (!d.axis().isRotation() && axisOf(live, attitude, d)[1] >= 1.0D - 1.0e-6D) {
                return true;
            }
        }
        return false;
    }

    private static double[] axisOf(ShipCapability live, FreeFlightPhysics.Quat attitude, ControlDirection d) {
        Vector3dc n = live.frame().axis(d.axis());
        double sign = d.isPositive() ? 1.0D : -1.0D;
        return attitude.rotate(n.x() * sign, n.y() * sign, n.z() * sign);
    }

    private static MotionSlot.Craft craft(ShipCapability live, double[] position, double[] velocity,
                                          FreeFlightPhysics.Quat attitude, double[] gravity, double topSpeed) {
        return new MotionSlot.Craft(SELF, PLANET_DIM, null, position, velocity, attitude, live, gravity, topSpeed,
                HULL, HULL, ATTITUDE_GAIN, SLEW_CAP);
    }

    private static MotionSlot.Craft atRest(ShipCapability live, FreeFlightPhysics.Quat attitude) {
        return craft(live, new double[]{0.0D, 0.0D, 0.0D}, new double[]{0.0D, 0.0D, 0.0D}, attitude,
                new double[]{0.0D, 0.0D, 0.0D}, 40.0D);
    }

    private static MotionSlot.Craft atRestAt(ShipCapability live, double[] position) {
        return craft(live, position, new double[]{0.0D, 0.0D, 0.0D}, FreeFlightPhysics.Quat.IDENTITY,
                new double[]{0.0D, 0.0D, 0.0D}, 40.0D);
    }

    /** A craft at rest at {@code position} in the world of the space cell {@code cell}, in no field. */
    private static MotionSlot.Craft inCell(ShipCapability live, double[] position, GalacticCoord cell) {
        return new MotionSlot.Craft(SELF, PLANET_DIM, cell, position, new double[]{0.0D, 0.0D, 0.0D},
                FreeFlightPhysics.Quat.IDENTITY, live, new double[]{0.0D, 0.0D, 0.0D}, 40.0D, HULL, HULL,
                ATTITUDE_GAIN, SLEW_CAP);
    }

    private static double[] unit(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    private static MotionSlot.Craft onSurface(ShipCapability live, double[] position, double depth, int dim,
                                              GalacticCoord cell) {
        return new MotionSlot.Craft(SELF, dim, cell, position, new double[]{0.0D, 0.0D, 0.0D},
                FreeFlightPhysics.Quat.IDENTITY, live, new double[]{0.0D, -PhysicsUnits.ENGINE_GRAVITY, 0.0D},
                40.0D, HULL, depth, ATTITUDE_GAIN, SLEW_CAP);
    }

    private static FreeFlightPhysics.Quat randomAttitude(Random rng) {
        return new FreeFlightPhysics.Quat(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian(),
                rng.nextGaussian()).normalized();
    }

    private static double[] randomUnit(Random rng) {
        double x = rng.nextGaussian();
        double y = rng.nextGaussian();
        double z = rng.nextGaussian();
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }

    private static double[] negate(double[] v) {
        return new double[]{-v[0], -v[1], -v[2]};
    }
}
