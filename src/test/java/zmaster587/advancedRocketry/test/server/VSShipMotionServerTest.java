package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.After;
import org.junit.Test;


import static org.junit.Assert.assertTrue;

/**
 * Substrate checkpoint — proves Valkyrien Skies physics is LIVE at the SERVER tier: a bare
 * AR-assembled tier-2 ship, commanded through its own flight computer, actually translates through
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
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @After
    public void cleanup() throws Exception {
    }

    @Test
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
        String assemble = assembleFixture(SITE, VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));

        // 1) The ship must be in the queryable registry — READ, not waited for.
        //
        // The registration IS deferred: `queueShipSpawn` only adds to a spawn queue, which the
        // world drains on its next tick. But nothing here can observe the pre-drain state, because
        // every probe command is itself drained on the server thread and the server holds the task
        // queue's monitor across a whole drain — so two consecutive commands are separated by a
        // complete pass by construction. The wait that used to stand here spent its budget only
        // when the answer was going to be no anyway.
        //
        // (The comment this replaces said VS relocates the blocks "on its own thread". It does not:
        // `queueShipSpawn` calls `enforceGameThread`.)
        int all = shipCount("ship-count-all");
        assertTrue("assembly must create a VS ship in the queryable registry (all=" + all + ")",
                all >= 1);

        // 2) A headless server has no player near the ship to auto-load it, so it stays
        //    unloaded/dormant. Force it loaded + physics-enabled (a nearby client does
        //    this itself in real play).
        //
        //    The assertion is that the ship IS loaded afterwards, not that a load was REQUESTED.
        //    Those came apart on 2026-09-16: a test server now holds its ships loaded, so nothing
        //    needs requesting and `requested:0` is the honest answer — which the old pin read as a
        //    failure. `requested` says what this call had to do; `alreadyLoaded` says what it found
        //    done. Either way the postcondition is one ship loaded in this world.
        String load = exec("artest vs load-ships 0");
        assertTrue("load-ships must account for the ship, either as a queued load or as one already"
                + " loaded: " + load,
                (Reply.of(load).integer("requested") == 1) || (Reply.of(load).integer("alreadyLoaded") == 1));

        // 3) The ship's NAME, from the assembler that minted it, and then its position asked BY that
        //    name. The identity used to be re-derived here from a bounded lookup at the build spot,
        //    defended as "provably the only one there" — a premise about this fixture rather than
        //    about the lookup, and the build site is in a world every server-tier class shares.
        final String[] shipId = {ShipIdentity.physicsIdOf(this::exec, 0,
                ShipIdentity.nameFromAssembly(assemble))};
        // Linked on the physics object's own load, which is what "the ship became loaded" MEANS:
        // `ship_loaded` is written from the PhysicsObject constructor and carries the substrate's
        // id, so this names THIS hull rather than counting how many are loaded in the dimension.
        // The poll it replaces read a count, then a pose, then checked the pose was a number — three
        // readings that had to agree, taken at whatever moments the loop happened to take them.
        events.awaitField(loadMark, "ship_loaded", "vsShip", shipId[0],
                "the ship must become loaded after the force-load", LOAD_TICKS);
        String loadedInfo = exec("artest vs ship-info 0 id " + shipId[0]);
        double zBefore = ShipInfo.of(loadedInfo).z;
        assertTrue("the substrate announced this hull loaded, so it must have a pose to report: "
                        + loadedInfo + ", all=" + all,
                !Double.isNaN(zBefore));

        // CONTROL, and the reason this class was rewritten: a raw velocity SETPOINT does not move a
        // ship. Measured 2026-08-22 on the very run that re-enabled the class — 25 setpoints of
        // 10 b/s applied a tick apart left the craft where it was (zBefore=2203.0, zAfter=2202.4).
        // The substrate recomputes velocity from forces every physics step and overwrites what was
        // written, so the write is not a command; the probe surface says as much in one line beside
        // `force-vel-by-id` ("a velocity setpoint alone does nothing"). Asserting it here, rather
        // than deleting it, is what stops the class quietly going back to the setpoint.
        String setpoint = exec("artest vs push-ship-by-id 0 " + shipId[0] + " 0 0 " + COMMANDED_VZ);
        assertTrue("push-ship-by-id must find the ship: " + setpoint, Reply.of(setpoint).bool("pushed"));
        // WINDOW: z is read before the setpoint and after this stretch, and the control is an UPPER
        // bound on the difference — overshoot gives a working setpoint longer to show itself, so it
        // can only turn this red.
        GameTicks.advance(client(), GameTicks.server(), DRIVE_TICKS);
        double zAfterSetpoint = ShipInfo.byId(this::exec, 0, shipId[0]).z;
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
        String drive = exec("artest vs force-vel-by-id 0 " + shipId[0] + " 0 0 " + COMMANDED_VZ);
        assertTrue("the command must reach THIS ship's own flight computer: " + drive,
                Reply.of(drive).bool("afcResolved"));
        // WINDOW: z is read just before the command (zAfterSetpoint) and after this stretch, and the
        // claim is a LOWER bound on the difference — the direction in which overshoot is silent,
        // because a longer stretch lets a drive too weak to pass on time pass anyway. So the bar is
        // scaled by the ticks this box actually delivered: the rate it demands does not change.
        long driven = GameTicks.advanceObserved(client(), GameTicks.server(), DRIVE_TICKS);
        double zAfter = ShipInfo.byId(this::exec, 0, shipId[0]).z;
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

    private int shipCount(String sub) throws Exception {
        Reply mReply = Reply.of(exec("artest vs " + sub + " 0"));
        return mReply.has("count") ? mReply.integer("count") : -1;
    }

    /** Place the fixture on a pad and run scan+assemble; returns the raw assemble JSON. */
    private String assembleFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and DRIVEN in is EMPTY. The site stands in open
        // air, so this ASSERTS rather than digs, and it still force-loads every chunk in the box on
        // the way through — so the warmup this replaces lost nothing. The height covers the hull
        // plus the lane it is commanded along, not the pad.
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 2, 10,
                "the craft is built here and then driven horizontally out of this volume");
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];
        String assemble = exec("artest rocket assemble 0 " + bx + " " + by + " " + bz);
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());
        return assemble;
    }
}
