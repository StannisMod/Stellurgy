package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

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

    /**
     * A velocity commanded through the ship's own flight computer moves it; a raw substrate setpoint
     * does not (the control).
     */
    @RedWitness(verdict = "translates", member = "TileAdvancedFlightComputer#onPhysicsTick",
            at = "force.set(command.force()).mul(k);",
            replacedBy = "force.set(command.force()).mul(0.0D);",
            fails = "needing more than", on = "2026-10-18")
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
