package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.contract.Pins;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

import static org.junit.Assert.assertTrue;

/**
 * Substrate checkpoint — proves Valkyrien Skies physics is LIVE at the SERVER tier: a bare
 * Stellurgy-assembled tier-2 ship, commanded through its own flight computer, actually translates through
 * VS's physics loop. This is the load-bearing precondition for every pilotable-ship test — if a
 * command did not move the ship here, an e2e built on it would be false-green for reasons nothing
 * else in the suite would report.
 *
 * <p>The command is horizontal (+Z) to keep gravity out of the measured delta. This craft's identity
 * comes from the NAME the assembler minted it with, never from a position: everything after asks by
 * id, so a shared server carrying another test's ship cannot start answering for it.</p>
 *
 * <p><b>Two things this class was wrong about until 2026-08-22, both measured rather than argued.</b>
 * It was disabled for eight weeks on the premise that "a VS ship assembled on a HEADLESS server
 * never becomes loaded", with the note that it should be re-enabled once a server-side force-load
 * existed. That probe ({@code vs permaload}) was added eight days after the class was parked, and
 * nobody came back: the ship loads, and the scenario runs. And the behaviour it pinned —
 * flight-control "model A", a direct {@code ShipPhysicsData} velocity setpoint — does NOT move a
 * ship, which is why the drive below goes through the flight computer instead. The dead model is
 * kept as the control leg, because a checkpoint that cannot tell a working drive from a broken one
 * is not a checkpoint.</p>
 *
 * <p>The group is a ship's MOTION and what it hands to what leaves it: a second scenario throws an
 * item out of a moving hull's ejection port and reads the motion the item was given against the
 * hull's own, as the substrate holds it. Neither scenario sees a client; both are decisions the
 * server makes.</p>
 */
public class VSShipMotionServerTest extends AbstractSharedServerTest {


    private static final String VARIANT = "with-advanced-flight-computer";

    /**
     * A constant: a {@code FixtureSite} is immutable: final ints, a final string and a final, itself
     * immutable, {@code Plot}.
     */
    // Horizontally distinct from AdvancedFlightComputerTierGateTest's sites (1200 / 1600): the
    // server is shared, and two craft assembled in one another's working volume interfere whatever
    // each test then asks by id.
    private static final FixtureSite SITE = FixtureSite.openAir(0, 2200, 2200);

    /**
     * Budgets in SERVER TICKS. The assembly relocation and the force-load are both driven by the
     * server's own tick loop, so that is the clock they are asked for - and none of these carries a
     * fork multiplier, because how much of the machine this test shares says nothing about how many
     * ticks the work needs.
     */
    private static final int LOAD_TICKS = 400;

    /** The commanded speed, in blocks/second, and how long it is given to move the craft. */
    private static final double COMMANDED_VZ = 10.0;
    private static final int DRIVE_TICKS = 25;

    /**
     * The displacement the drive must beat over DRIVE_TICKS, in blocks, read as a rate so a longer
     * stretch asks for proportionally more.
     *
     * <p>It is the CONTROL's number, and that is what it measures: the setpoint control above moves
     * the craft less than a block over the same stretch, so this separates "the drive moved it" from
     * "nothing drove it". It does NOT pin how much of the commanded speed arrives — ten b/s would
     * cover about twelve blocks, and a drive delivering a tenth of that still passes. That is a
     * claim about the flight computer's gain, and this test does not make it.</p>
     */
    private static final double MIN_DISPLACEMENT_PER_DRIVE = 1.0;

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** The physics id of the craft this scenario built; driving it keeps the id. */
    private String builtShip;

    @After
    public void disposeOfTheCraft() throws Exception {
        if (builtShip != null) {
            Disposal.ship(c -> arrange(c).raw(), events, 0, builtShip);
            builtShip = null;
        }
    }

    /** Ticks in one second of game time — Minecraft's fixed 50 ms server tick. */
    private static final double TICKS_PER_SECOND = 20.0;


    /**
     * The speed commanded along +X, in blocks per second. Chosen so that what arrives clears the
     * floor the verdict needs (the hull at more than twice the port's push, see the arrangement in
     * the method) with room for a drive that delivers well under its command.
     */
    private static final double EJECTING_HULL_VX = 30.0;

    /**
     * The WINDOW the drive is given before the port is loaded, in server ticks: a drive realizes its
     * command as force, so the hull is still accelerating when the command lands. Nothing records
     * "the cruise is reached" — the hold never decides it has arrived — so the speed the release
     * actually happened at is read off the release's own record and checked there.
     */
    private static final int SPIN_UP_TICKS = 40;

    /**
     * The DEADLINE for the release, in server ticks: five of the port's duty cycles at its shipped
     * interval ({@code StellurgyConfiguration#jettisonPortIntervalTicks}, 20). A port that holds a
     * stack and has a clear mouth fires on its next cycle, so this only ever runs out on a port that
     * is not firing.
     */
    private static final int RELEASE_DEADLINE_TICKS = 100;

    /**
     * An item thrown out of the ejection port of a MOVING hull leaves with the hull's own motion at
     * the port — per TICK, as every motion of an item is — plus the port's push. Not twenty times
     * that motion (the substrate's per-SECOND velocity taken as per-tick), and not without it.
     *
     * <p>The judge is the substrate's own state at the moment of release, recorded beside the item
     * ({@code ejection_released}): its linear velocity, angular velocity and centre, turned into the
     * velocity of the release point per tick. That is the hull's motion as physics holds it, not the
     * carry reader the port itself calls, so a reader converting wrongly cannot also be the
     * instrument that judges it. The verdict is the item's motion ALONG the hull's, as a multiple of
     * the hull's: about 1 when it is carried right, about 20 when the per-second value is used as
     * per-tick, about 0 when it is not carried at all — each blurred by at most the port's push over
     * the hull's speed, which the arrangement keeps under a half.</p>
     */
    @RedWitness(verdict = "per tick, not per second", member = "EjectionPort#eject",
            at = "double carryX = carry == null ? 0.0D : carry[0];",
            replacedBy = "double carryX = carry == null ? 0.0D : 20 * carry[0];",
            fails = "the item must leave with the hull's motion per TICK, not twenty times it",
            on = "2026-10-15")
    @RedWitness(verdict = "carried at all", member = "EjectionPort#eject",
            at = "double carryX = carry == null ? 0.0D : carry[0];",
            replacedBy = "double carryX = carry == null ? 0.0D : 0 * carry[0];",
            fails = "the item must leave WITH the hull's motion, or it is back inside the hull a tick later",
            on = "2026-10-15")
    @Pins("INV-ATM-25")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), SPIN_UP_TICKS);",
            why = "see SPIN_UP_TICKS: nothing records that the cruise is reached. Its far end is not read; the"
                    + " release record carries the speed the stack actually left at.")
    @Test
    public void anItemEjectedFromAMovingHullCarriesTheHullsMotionPerTick() throws Exception {
        FixtureSite site = site();
        // The craft is the pilot-seat build: a seat is what `find-seat` locates the hull's flight
        // computer by, and the port's subspace address is taken from that computer's.
        // HEIGHT 7: the fixture's own structure tower reaches site.y+6, the tallest thing laid here;
        // the port and its exit lane sit at site.y+4, inside the pad's footprint, so no halo.
        int[] builder = RocketFixture.placeAt(site, c -> arrange(c).raw(), "with-pilot-seat", 0, 7,
                "the craft, its jettison port and the lane the port throws into");
        // The port goes in the cell above the +X fuel-tank column — (rocketX+1, rocketY+3, rocketZ)
        // with the craft rooted at (site.x+3, site.y+1, site.z+3) — so the assembly's flood fill
        // takes it with the tank it rests on. Facing NORTH (meta 2): its exit lane runs along -Z,
        // across the +X course the hull is driven on, so the port's push says little about the
        // component of the release's motion the verdict reads.
        int portX = site.x + 4, portY = site.y + 4, portZ = site.z + 3;
        arrange("stellurgytest place " + site.dim + " " + portX + " " + portY + " " + portZ
                + " stellurgy:jettisonPort 2");

        long loadMark = events.mark();
        String assemble = RocketFixture.assembleBuilt(site, c -> act(c).raw(), builder);
        requireArranged("with VS, the pilot-seat build must route to a ship (no rocket): " + assemble,
                RocketFixture.rocketsBornAt(events, loadMark, builder).isEmpty());
        String shipId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(),
                events, 0, ShipIdentity.nameFromAssembly(assemble), LOAD_TICKS));
        builtShip = shipId;

        Reply seat = read("stellurgytest vs find-seat 0 id " + shipId);
        requireArranged("the craft's flight computer must be found in ITS shipyard: " + seat,
                seat.bool("seatFound") && seat.has("afcX"));
        // The flight computer stood at (rocketX-1, rocketY+3, rocketZ) and the port two cells along +X
        // from it; the assembly moves the hull's blocks together, so the same offset holds in the
        // shipyard — and the port's own reply below is what says it does.
        int subX = seat.integer("afcX") + 2, subY = seat.integer("afcY"), subZ = seat.integer("afcZ");
        Reply port = read("stellurgytest jettison info 0 " + subX + " " + subY + " " + subZ);
        requireArranged("the jettison port must have ridden into the shipyard beside the flight"
                + " computer, with a clear mouth: " + port, port.ok() && port.integer("obstruction") == 0);

        Reply drive = arrange("stellurgytest vs force-vel-by-id 0 " + shipId + " " + EJECTING_HULL_VX + " 0 0");
        requireArranged("the drive must reach THIS craft's own flight computer: " + drive,
                drive.bool("afcResolved"));
        GameTicks.advance(client(), GameTicks.server(), SPIN_UP_TICKS);

        long releaseMark = events.markInstrumented();
        arrange("stellurgytest jettison load 0 " + subX + " " + subY + " " + subZ + " minecraft:cobblestone 1");
        // A link: the release is the port's own duty cycle; what the stack left WITH is the verdict.
        String released = ArrangementFailure.arranged(() -> events.awaitRecordWithField(releaseMark,
                "ejection_released", "ship", shipId,
                "the loaded port aboard the moving hull must throw its stack out", RELEASE_DEADLINE_TICKS));

        // The release point's velocity per tick, from the substrate's own state at that instant:
        // v + omega x r, per second, over the ticks in a second.
        double rx = Events.number(released, "x") - Events.number(released, "hullX");
        double ry = Events.number(released, "y") - Events.number(released, "hullY");
        double rz = Events.number(released, "z") - Events.number(released, "hullZ");
        double wx = Events.number(released, "hullOmegaX");
        double wy = Events.number(released, "hullOmegaY");
        double wz = Events.number(released, "hullOmegaZ");
        double hullX = (Events.number(released, "hullVelX") + (wy * rz - wz * ry)) / TICKS_PER_SECOND;
        double hullY = (Events.number(released, "hullVelY") + (wz * rx - wx * rz)) / TICKS_PER_SECOND;
        double hullZ = (Events.number(released, "hullVelZ") + (wx * ry - wy * rx)) / TICKS_PER_SECOND;
        double hullSpeed = Math.sqrt(hullX * hullX + hullY * hullY + hullZ * hullZ);
        // How hard the port pushes a stack out of its own mouth, blocks per tick: production's own
        // constant, published on the release record. The release's motion is the hull's motion at the
        // port PLUS this push, so it is the margin the two predictions below are separated by.
        double push = Events.number(released, "push");
        requireArranged("the hull must be moving at more than twice the port's push when it releases"
                + " (" + hullSpeed + " blocks/tick against " + (2 * push) + "), or the"
                + " push alone could carry the release across either bound: " + released,
                hullSpeed > 2 * push);

        double along = (Events.number(released, "motionX") * hullX
                + Events.number(released, "motionY") * hullY
                + Events.number(released, "motionZ") * hullZ) / hullSpeed;
        double carried = along / hullSpeed;
        System.out.println("[ejection] hull " + hullSpeed + " blocks/tick, item along it " + along
                + ", carried " + carried + "x :: " + released);
        // Between the predictions 1 (carried per tick) and 20 (per second taken as per tick), at
        // their geometric middle.
        assertTrue("the item must leave with the hull's motion per TICK, not twenty times it: it"
                        + " carried " + carried + "x the hull's " + hullSpeed + " blocks/tick along its"
                        + " course: " + released,
                carried < Math.sqrt(TICKS_PER_SECOND));
        // Between the predictions 0 (not carried) and 1, at their middle.
        assertTrue("the item must leave WITH the hull's motion, or it is back inside the hull a tick"
                        + " later: it carried " + carried + "x the hull's " + hullSpeed
                        + " blocks/tick along its course: " + released,
                carried > 0.5);
    }

    /**
     * A velocity commanded through the ship's own flight computer moves it; a raw substrate setpoint
     * does not (the control).
     */
    @LegacyWitness(prose = "with {@code MixinTileAdvancedFlightComputer#onPhysicsTick} at"
            + " {@code fx = a[0] * mass; fy = a[1] * mass; fz = a[2] * mass}, the linear force, multiplied by"
            + " {@code 0.0}, this fails at the subject with \"it moved -0.506 blocks in 25 server ticks, needing"
            + " more than 1.0\", the setpoint control still green — 2026-09-28.")
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), DRIVE_TICKS);",
            why = "z is read before the setpoint and after this stretch, and the control is an UPPER bound on the"
                    + " difference — overshoot gives a working setpoint longer to show itself, so it can only"
                    + " turn this red.")
    @Wait(shape = Wait.Shape.WINDOW, at = "long driven = GameTicks.advanceObserved(client(), GameTicks.server(),"
            + " DRIVE_TICKS);",
            why = "z is read just before the command (zAfterSetpoint) and after this stretch, and the claim is a"
                    + " LOWER bound on the difference — the direction in which overshoot is silent, because a"
                    + " longer stretch lets a drive too weak to pass on time pass anyway. So the bar is scaled"
                    + " by the ticks this box actually delivered: the rate it demands does not change.")
    public void aCommandedVelocityTranslatesTheShipWhileARawSetpointDoesNot() throws Exception {

        // A headless server has nobody standing near this craft, and an unattended ship UNLOADS
        // again between probe calls — a one-shot load request is not enough, because the ship is
        // gone from the loaded set by the time the next call asks about it. This is what the class
        // was disabled for; the probe that holds a ship loaded server-side has existed since
        // 2026-07-13.

        // Assemble the tier-2 ship — with VS this routes to a ship (no rocket) and
        // queues an async VS relocation.
        // MARKED BEFORE THE ASSEMBLE, not before the force-load below, and the difference is the
        // whole conversion. `ship_loaded` is written once, from the physics object's constructor —
        // and this server holds its ships loaded, so the hull may well become loaded during the
        // assemble itself, before anything asks for it. A mark taken at the force-load would then
        // open a window the record had already passed through, and the wait would expire on a ship
        // that had been loaded for seconds.
        long loadMark = events.mark();
        ShipBirth birth = assembleFixture(SITE, VARIANT, loadMark);
        builtShip = birth.physicsId;
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());

        // 1) The ship must be in the queryable registry: its ASSEMBLED naming (awaited by the
        //    assembly above) names it, and the registry is asked about THAT id.
        String registered = read("stellurgytest vs ship-registered 0 " + birth.physicsId).raw();
        assertTrue("assembly must create a VS ship in the queryable registry: " + registered,
                Reply.of(registered).bool("registered"));

        // 2) A headless server has no player near the ship to auto-load it, so it stays
        //    unloaded/dormant. Force THIS ship loaded + physics-enabled (a nearby client does
        //    this itself in real play).
        //
        //    The assertion is that the ship IS loaded afterwards, not that a load was REQUESTED: a
        //    test server holds its ships loaded, so nothing may need requesting. `found` says the
        //    world registers the ship the load was asked for.
        String load = arrange("stellurgytest vs load-ship 0 " + birth.physicsId).raw();
        assertTrue("load-ship must find the ship this assembly named: " + load,
                Reply.of(load).bool("found"));

        // 3) The ship's physics id, as its own naming announced it, and its position asked BY that id.
        final String[] shipId = {birth.physicsId};
        // Linked on the physics object's own load, which is what "the ship became loaded" MEANS:
        // `ship_loaded` is written from the PhysicsObject constructor and carries the substrate's
        // id, so this names THIS hull rather than counting how many are loaded in the dimension.
        // The poll it replaces read a count, then a pose, then checked the pose was a number — three
        // readings that had to agree, taken at whatever moments the loop happened to take them.
        events.awaitField(loadMark, "ship_loaded", "vsShip", shipId[0],
                "the ship must become loaded after the force-load", LOAD_TICKS);
        String loadedInfo = read("stellurgytest vs ship-info 0 id " + shipId[0]).raw();
        double zBefore = ShipInfo.of(loadedInfo).z;
        assertTrue("the substrate announced this hull loaded, so it must have a pose to report: "
                        + loadedInfo,
                !Double.isNaN(zBefore));

        // CONTROL, and the reason this class was rewritten: a raw velocity SETPOINT does not move a
        // ship. Measured 2026-08-22 on the very run that re-enabled the class — 25 setpoints of
        // 10 b/s applied a tick apart left the craft where it was (zBefore=2203.0, zAfter=2202.4).
        // The substrate recomputes velocity from forces every physics step and overwrites what was
        // written, so the write is not a command; the probe surface says as much in one line beside
        // `force-vel-by-id` ("a velocity setpoint alone does nothing"). Asserting it here, rather
        // than deleting it, is what stops the class quietly going back to the setpoint.
        String setpoint = arrange("stellurgytest vs push-ship-by-id 0 " + shipId[0] + " 0 0 " + COMMANDED_VZ).raw();
        assertTrue("push-ship-by-id must find the ship: " + setpoint, Reply.of(setpoint).bool("pushed"));
        GameTicks.advance(client(), GameTicks.server(), DRIVE_TICKS);
        double zAfterSetpoint = ShipInfo.byId(c -> read(c).raw(), 0, shipId[0]).z;
        assertTrue("a raw velocity setpoint must NOT be mistaken for a working drive: the ship moved "
                        + (zAfterSetpoint - zBefore) + " blocks (z " + zBefore + " -> "
                        + zAfterSetpoint + ") on a bare setpoint, which means this"
                        + " control has stopped controlling and the test below no longer proves the"
                        + " CONTROLLER moved anything",
                Math.abs(zAfterSetpoint - zBefore) < 1.0);

        // THE SUBJECT: the path production actually flies. `force-vel-by-id` commands a world-frame
        // velocity that the ship's OWN flight computer realizes as force, once per physics tick, for
        // as long as it stands — so this is one command and then time, not a setpoint re-written
        // every tick against a substrate that keeps discarding it.
        String drive = arrange("stellurgytest vs force-vel-by-id 0 " + shipId[0] + " 0 0 " + COMMANDED_VZ).raw();
        assertTrue("the command must reach THIS ship's own flight computer: " + drive,
                Reply.of(drive).bool("afcResolved"));
        long driven = GameTicks.advanceObserved(client(), GameTicks.server(), DRIVE_TICKS);
        double zAfter = ShipInfo.byId(c -> read(c).raw(), 0, shipId[0]).z;
        double requiredDisplacement = MIN_DISPLACEMENT_PER_DRIVE * driven / DRIVE_TICKS;

        // A strict displacement, not merely "changed": it pins that VS integrated the commanded
        // motion into position. A substrate that ignored the command, or damped it to zero, would
        // leave the ship put — and the control above proves that outcome is reachable here.
        assertTrue("a commanded +Z velocity must translate the ship through VS physics: it moved "
                        + (zAfter - zAfterSetpoint) + " blocks in " + driven + " server ticks,"
                        + " needing more than " + requiredDisplacement
                        + " (zBefore=" + zBefore + " zAfterSetpoint=" + zAfterSetpoint
                        + " zAfter=" + zAfter + ")",
                zAfter - zAfterSetpoint > requiredDisplacement);
    }

    /**
     * The roll rate the slow-turn scenario commands, rad/s. Its one requirement is to be SLOW: well under
     * 0.0316 rad/s, the fastest rate a "squared length below 1e-3" guard in the integrator throws away —
     * the defect class this scenario exists for — and well over the flight computer's floor for a raw
     * rate command ({@code AR_ANGULAR_CMD_EPSILON}, 1e-4), below which the command is not flown at all.
     */
    private static final double SLOW_ROLL = 0.01;

    /** The fastest rate the region this scenario is about reaches, rad/s: {@code sqrt(1e-3)}. */
    private static final double SLOW_BAND_TOP = 0.0316;

    /**
     * The lift off the pad, blocks/s along +Y, and the ticks it is held. A roll of a hull still resting
     * on its pad presses one edge into the pad, and the collision answers with a turn of its own; four
     * blocks a second for a second clears the pad by several blocks, which the scenario then measures.
     */
    private static final double LIFT_VY = 4.0;
    private static final int LIFT_TICKS = 20;

    /** How far the lift must have carried the hull, blocks, before the turn is measured. */
    private static final double LIFTED_AT_LEAST = 2.0;

    /**
     * The settle after a command, in server ticks: the rate law deadbeats the hull's angular velocity
     * onto its command in one physics step where its authority allows, and three of them run per tick.
     * The window that follows re-reads the rate at both ends, so a settle that was too short is an
     * arrangement failure saying so, never a verdict.
     */
    private static final int SETTLE_TICKS = 10;

    /** The window over which the turn is measured, in server ticks: about three hundred physics steps. */
    private static final int TURN_WINDOW_TICKS = 100;

    /**
     * How near its command the hull's angular velocity must be at both ends of the window for the window
     * to be the one the contract speaks of: a fraction of {@link #SLOW_ROLL}.
     */
    private static final double RATE_HELD_WITHIN = 0.2;

    /**
     * The band, as a fraction of the turn the contract predicts, that the measured turn must fall in. Its
     * lower edge separates the contract from a slow rate thrown away (no turn at all); its upper edge from
     * a step integrated at the wrong time scale (a game tick taken for a physics step turns three times
     * as far). Between them it carries the rate's own spread over the window ({@link #RATE_HELD_WITHIN})
     * and the skew of each attitude read against its clock stamp — the pose is the one published at the
     * last game tick, up to three physics steps behind the stamp, against some three hundred in the span.
     */
    private static final double TURN_BAND = 0.5;

    /**
     * A slow rotation turns the hull. The flight computer is told to roll the hovering hull at
     * {@link #SLOW_ROLL} about world Z — a rate the integrator once discarded whole — and the hull's roll
     * is read off two of its own poses, stamped with the hull's PHYSICS clock: the physics loop runs on a
     * thread of its own, so how many of its steps fall in a game tick is a fact about the box, and the turn
     * is measured per step. The prediction is the rate times the seconds a step simulates, published by
     * the same reply as the clock.
     *
     * <p>The CONTROL is the same hull holding its attitude: it must not turn at the rate the verdict looks
     * for, or a turn measured afterwards could be the hull's own drift.</p>
     *
     * <p>What it does not see: rates of other magnitudes and axes than this one (the law is one sentence
     * for all of them, and a pure law is pinned where it is cheap once it lives in a pure function — it
     * still sits inside the world-bound integrator, so this world scenario is its only pin until it moves);
     * a hull touching anything; a ship with no flight computer, whose clock this cannot read.</p>
     */
    @RedWitness(member = "PhysicsCalculations#integrateAngularVelocity",
            at = "angularVelocity.lengthSquared() == 0.0D",
            replacedBy = "angularVelocity.lengthSquared() < 0.001",
            fails = "a hull turning at 0.01 rad/s must roll by that rate times each physics step's",
            on = "2026-10-23")
    @Pins("INV-VSK-14")
    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), LIFT_TICKS);",
            why = "the lift is a dose: held for LIFT_TICKS, after which the height it reached is read and"
                    + " required, so a short dose fails as an arrangement saying how high the hull got.")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advance(client(), GameTicks.server(), SETTLE_TICKS);",
            why = "a settle before a window whose two reads each require the rate the command asked for;"
                    + " nothing records that a rate command has been reached.")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<ShipInfo> held = Window.over(",
            why = "the control: the roll is a value that changes continuously, judged by its change over the"
                    + " physics steps stamped at both reads.")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<ShipInfo> turned = Window.over(",
            why = "the subject: the roll is a value that changes continuously, judged by its change over the"
                    + " physics steps stamped at both reads.")
    @Test
    public void aSlowRotationTurnsTheHull() throws Exception {
        FixtureSite site = site();
        // HEIGHT 14: the fixture's tower reaches site.y+6, and the hull is lifted some four blocks above
        // where it was built.
        int[] builder = RocketFixture.placeAt(site, c -> arrange(c).raw(), VARIANT, 2, 14,
                "the craft is built here, lifted off its pad and turned in place");
        long loadMark = events.mark();
        String assemble = RocketFixture.assembleBuilt(site, c -> act(c).raw(), builder);
        ShipBirth birth = ShipBirth.ofPress(events, loadMark, site.dim, builder, assemble, LOAD_TICKS,
                "the craft that is turned slowly");
        builtShip = birth.physicsId;
        requireArranged("with VS, the AFC build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());
        String shipId = birth.awaitUsable(c -> read(c).raw(), events, LOAD_TICKS);
        builtShip = shipId;

        double builtY = ShipInfo.byId(c -> read(c).raw(), site.dim, shipId).y;
        Reply lift = arrange("stellurgytest vs force-vel-by-id " + site.dim + " " + shipId + " 0 " + LIFT_VY + " 0");
        requireArranged("the lift must reach THIS craft's own flight computer: " + lift, lift.bool("afcResolved"));
        GameTicks.advance(client(), GameTicks.server(), LIFT_TICKS);
        ShipInfo lifted = ShipInfo.byId(c -> read(c).raw(), site.dim, shipId);
        requireArranged("the hull must be lifted clear of its pad before it is turned (built at y=" + builtY
                + "): " + lifted, lifted.y - builtY >= LIFTED_AT_LEAST);

        Reply hold = arrange("stellurgytest vs point-by-id " + site.dim + " " + shipId + " " + lifted.qw + " "
                + lifted.qx + " " + lifted.qy + " " + lifted.qz);
        requireArranged("the hold must reach THIS craft's own flight computer: " + hold, hold.bool("afcResolved"));
        Reply steps = read("stellurgytest vs physics-steps-by-id " + site.dim + " " + shipId);
        requireArranged("the craft's physics clock must be readable: " + steps, steps.bool("afcResolved"));
        double stepSeconds = steps.number("stepSeconds");
        double predicted = SLOW_ROLL * stepSeconds;
        GameTicks.Clock physics = GameTicks.physicsSteps(site.dim, shipId);
        Events.Step serverTicks = ticks -> GameTicks.advance(client(), GameTicks.server(), ticks);

        Window<ShipInfo> held = Window.over(() -> ShipInfo.byId(c -> read(c).raw(), site.dim, shipId),
                client(), physics, serverTicks, TURN_WINDOW_TICKS);
        double heldRate = held.rate(VSShipMotionServerTest::roll);
        requireArranged("the hull holding its attitude must not roll at the rate the verdict looks for ("
                        + heldRate + " rad/step against " + predicted + " predicted for the turn): " + held,
                Math.abs(heldRate) < TURN_BAND * predicted);

        Reply turn = arrange("stellurgytest vs force-rot-by-id " + site.dim + " " + shipId + " 0 0 " + SLOW_ROLL);
        requireArranged("the turn must reach THIS craft's own flight computer: " + turn, turn.bool("afcResolved"));
        GameTicks.advance(client(), GameTicks.server(), SETTLE_TICKS);
        // The window is the one the contract speaks of only while the hull carries the slow rate it was
        // commanded — the quantity a discarding guard compares — so that rate is read just before the
        // window opens and just after it closes. A window offers no single read of its own; these two
        // bracket it, and the rate law holds the rate continuously between them.
        ShipInfo before = ShipInfo.byId(c -> read(c).raw(), site.dim, shipId);
        requireArranged("the hull must carry the slow roll it was commanded when the window opens: " + before,
                carriesTheSlowRoll(before));
        Window<ShipInfo> turned = Window.over(() -> ShipInfo.byId(c -> read(c).raw(), site.dim, shipId),
                client(), physics, serverTicks, TURN_WINDOW_TICKS);
        ShipInfo after = ShipInfo.byId(c -> read(c).raw(), site.dim, shipId);
        requireArranged("the hull must still carry the slow roll it was commanded when the window closes: "
                + after, carriesTheSlowRoll(after));

        double rate = turned.rate(VSShipMotionServerTest::roll);
        System.out.println("[slow-turn] predicted " + predicted + " rad/step, measured " + rate + ", held "
                + heldRate + " :: " + turned + " :: before " + before + " :: after " + after);
        assertTrue("a hull turning at " + SLOW_ROLL + " rad/s must roll by that rate times each physics step's "
                        + stepSeconds + " s: predicted " + predicted + " rad/step, measured " + rate + ": "
                        + turned,
                rate > (1.0D - TURN_BAND) * predicted && rate < (1.0D + TURN_BAND) * predicted);
    }

    /**
     * Whether {@code info}'s angular velocity is the slow roll commanded: about world Z within
     * {@link #RATE_HELD_WITHIN} of {@link #SLOW_ROLL}, and as a whole inside the slow band.
     */
    private static boolean carriesTheSlowRoll(ShipInfo info) {
        return Math.abs(info.omegaZ - SLOW_ROLL) <= RATE_HELD_WITHIN * SLOW_ROLL && info.omega < SLOW_BAND_TOP;
    }

    /**
     * The hull's roll about world Z, radians: the angle its own up axis has been turned through about Z,
     * from the body-to-world attitude of one read.
     */
    private static double roll(ShipInfo info) {
        double upX = 2.0D * (info.qx * info.qy - info.qw * info.qz);
        double upY = 1.0D - 2.0D * (info.qx * info.qx + info.qz * info.qz);
        return Math.atan2(-upX, upY);
    }

    /**
     * Place the fixture on a pad and run scan+assemble; answers the ship the press made, read off its
     * records since {@code mark} (taken BEFORE this call).
     */
    private ShipBirth assembleFixture(FixtureSite site, String variant, long mark) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and DRIVEN in is EMPTY. The site stands in open
        // air, so this ASSERTS rather than digs, and it still force-loads every chunk in the box on
        // the way through — so the warmup this replaces lost nothing. The height covers the hull
        // plus the lane it is commanded along, not the pad.
        int[] bp = RocketFixture.placeAt(site, c -> arrange(c).raw(), variant, 2, 10,
                "the craft is built here and then driven horizontally out of this volume");
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];
        String assemble = act("stellurgytest rocket assemble 0 " + bx + " " + by + " " + bz).raw();
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());
        return ShipBirth.ofPress(events, mark, site.dim, bp, assemble, LOAD_TICKS,
                "the craft whose drive is commanded");
    }
}
