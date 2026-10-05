package dev.stannismod.stellurgy.test.server;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.WarShip;
import dev.stannismod.stellurgy.test.Weapons;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A gun bolted to a ship, which is the configuration every other turret test cannot reach.
 *
 * <h3>The whole difficulty in one sentence</h3>
 * <p>A turret on a ship stands in the SHIPYARD — a fixed address millions of blocks from where its
 * hull visibly is — while the target it is given, and the round it fires, belong to the world. So
 * the gun holds its bearing in the ship's frame and converts at the muzzle: the point through
 * {@code toWorldFrameFor}, the direction through {@code rotateToWorldFrameFor}, plus the hull's own
 * velocity. None of those three announces itself when it is wrong; the failure is simply a round
 * that appears somewhere nobody can see.</p>
 *
 * <h3>What makes this evidence</h3>
 * <p>The round the gun fired is located BY ITS ID after the shot. If any leg of the conversion were
 * missing it would be in the shipyard, five million blocks out — and every other assertion here (the
 * gun assembled, it was charged, it fired) would still pass. That distance is the discriminator, and
 * it is asserted explicitly rather than inferred from a hit. The class used to carry an
 * {@code Assume} on a {@code vs available} verb that no longer exists, so it had been SKIPPED on every
 * run since that verb was removed.</p>
 */
public class TurretOnAShipE2ETest extends AbstractSharedServerTest {

    /** This class's own build site and destination, clear of the other ship scenarios. */
    private static final int SRC_X = 6800, SRC_Z = 6800;
    private static final int FAR_X = 6800, FAR_Y = 150, FAR_Z = 9200;

    /** Anything past this is a shipyard address rather than a place in the world. */
    private static final double SHIPYARD_THRESHOLD = 1_000_000.0D;
    /** The fixture spans about twenty blocks; a point further than this from the hull is not on it. */
    private static final double ON_THE_HULL = 64.0D;
    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;
    /** Barrel sections stacked straight up from the controller — the build's furthest part. */
    private static final int BARRELS = 4;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** A craft left behind goes on ticking in the world the next scenario runs in. */
    @Before
    public void disposeOfEarlierCraft() throws Exception {
        System.out.println("[reset] craft cleared: " + ShipReadiness.clearCraftFrom(this::exec, 0));
    }

    /**
     * red-witnessed: with {@code TurretFireControl#muzzleOf} at {@code worldMuzzle = new Vec3d(point[0], point[1], point[2]);} leaving the muzzle in the ship's frame,
     * this fails with "the round is at x=1.9200042387784544E7, which is a shipyard address"; with the
     * same line offsetting the mapped muzzle 200 blocks along Z, it fails with "the round is
     * 204.62225954356157 blocks from the hull that fired it, more than the hull's reach (64.0) plus the
     * 25.32623553276062 blocks it has flown". 2026-09-30. (That bound was the fixture's unmeasured
     * reach; it has since been replaced by the derived one below.)
     *
     * <p>red-witnessed, 2026-09-30: with {@code TurretFireControl#fire} at {@code spec.getProjectileMass(), spec.getLifetimeTicks(), spec.getImpactEnergy(),} admitting the round with a
     * lifetime of 0, this fails at "the round this gun just fired is no longer in the air:
     * {...present:false,ended:EXPIRED...}"; with {@code TurretFireControl#muzzleOf} at {@code worldMuzzle = new Vec3d(point[0], point[1], point[2]);} placing the mapped
     * muzzle 20 blocks along Z, at "the round is 25.707357260698444 blocks from the gun that fired it,
     * more than the gun's standoff (5.5) plus the most it can have flown in 3 steps (10.98)".</p>
     *
     * <p>The bound, measured on the healthy run the same day: the round stood 34.345 blocks from the
     * gun's centre against a bound of 35.38 at age 8 (muzzle 3.6 a step, gravity 0.03). A straight
     * flight predicts 5.5 + 8 * 3.6 = 34.3, and the 0.045 over it is the fall gravity bends the path by;
     * the 1.03 of margin is the bound's own gravity term, 0.03 * 8 * 9 / 2 = 1.08, which is the most a
     * fall can lengthen the path, not the displacement it adds.</p>
     */
    @Test
    public void aGunOnAShipFiresIntoTheWorldRatherThanIntoTheShipyard() throws Exception {
        ask("stellurgytest shot clear 0").requireOk("clear the air");

        WarShip ship = WarShip.build(events, this::exec, FixtureSite.openAir(0, SRC_X, SRC_Z), null,
                "the craft the gun is bolted to");
        ship.parkAt(FAR_X, FAR_Y, FAR_Z, ON_THE_HULL);

        // A block of this ship whose SUBSPACE address we know: its pilot seat. The gun goes beside it.
        int[] seat = ship.seat();
        requireArranged("the seat is not at a shipyard address (" + seat[0] + "), so this is not the"
                + " case the test is about", Math.abs(seat[0]) > SHIPYARD_THRESHOLD);

        int gunX = seat[0] + 3, gunY = seat[1], gunZ = seat[2];
        long built = events.markInstrumented();
        buildGun(gunX, gunY, gunZ);
        Weapons.awaitAssembled(events, built, gunX, gunY, gunZ, PARTS,
                "a gun aboard a named ship never assembled — it is being treated as if the ship were unnamed");

        // Where the hull actually is, this tick.
        Reply info = ship.info();
        double worldX = info.number("posX"), worldY = info.number("posY"), worldZ = info.number("posZ");

        ask("stellurgytest turret charge 0 " + gunX + " " + gunY + " " + gunZ).requireOk("charge the gun");
        // A target in the WORLD, well clear of the hull.
        long aimed = events.mark();
        ask("stellurgytest turret target 0 " + gunX + " " + gunY + " " + gunZ + " " + (worldX + 60.0D) + " "
                + worldY + " " + worldZ).requireOk("aim the gun");
        String fired = Weapons.awaitFired(events, aimed, gunX, gunY, gunZ, "a gun aboard a ship never fired");
        long shotId = (long) Events.number(fired, "shot");

        // THE assertion: the round is in the world, near the hull — not at the shipyard address the
        // gun's own BlockPos would have given it. Read by the round's own id: a global list would
        // answer about every sibling's round as well.
        Reply read = ask("stellurgytest shot read 0 " + shotId).requireOk("read the round");
        // The round was admitted a probe round trip ago with a lifetime of hundreds of ticks, aimed
        // into open air: it is still flying, and one that is not has already failed the contract.
        assertTrue("the round this gun just fired is no longer in the air: " + read, read.bool("present"));
        Reply shot = Reply.of("the round", read.object("shot"));
        double x = shot.number("x"), y = shot.number("y"), z = shot.number("z");
        assertTrue("the round is at x=" + x + ", which is a shipyard address: the muzzle point was never"
                + " mapped out of the ship's frame, so the gun is shelling a place no player can reach: "
                + read, Math.abs(x) < SHIPYARD_THRESHOLD);
        // How far it can honestly be from THIS gun, derived rather than tuned. The round is born at the
        // gun's centre plus its standoff along the bore, `reach + 1.5` (TurretFireControl.muzzleOf),
        // mapped rigidly into the world — so exactly the standoff away from the gun's own centre as
        // the world sees it. `reach` is the furthest part along the axes (GunAssembly.scan): the top
        // barrel section, BARRELS blocks above the controller in the build below. Since then it has
        // taken `age` steps (ShotSubstrate.step: one move per age), each no longer than its velocity
        // at that step, which gravity alone changes by `gravity` a step on a parked hull — so its path
        // is at most age * muzzleSpeed + gravity * age * (age + 1) / 2.
        Reply gunNow = ask("stellurgytest turret read 0 " + gunX + " " + gunY + " " + gunZ).requireOk("read the gun");
        Reply mapped = ask("stellurgytest vs to-world 0 id " + ship.vsShip + " " + (gunX + 0.5D) + " "
                + (gunY + 0.5D) + " " + (gunZ + 0.5D)).requireOk("map the gun's centre into the world");
        double[] centre = new double[]{mapped.number("worldX"), mapped.number("worldY"), mapped.number("worldZ")};
        int age = shot.integer("age");
        double muzzle = gunNow.number("muzzleSpeed");
        double standoff = BARRELS + 1.5D;
        double pathAtMost = age * muzzle + shot.number("gravity") * age * (age + 1) / 2.0D;
        double fromGun = Math.sqrt(sq(x - centre[0]) + sq(y - centre[1]) + sq(z - centre[2]));
        System.out.println("[measure] round " + fromGun + " blocks from the gun's centre, bound "
                + (standoff + pathAtMost) + " (standoff " + standoff + ", path at most " + pathAtMost
                + " over age " + age + "); margin " + (standoff + pathAtMost - fromGun));
        assertTrue("the round is " + fromGun + " blocks from the gun that fired it, more than the gun's"
                + " standoff (" + standoff + ") plus the most it can have flown in " + age + " steps ("
                + pathAtMost + ") — it is in the world, but not where this gun is: " + read,
                fromGun <= standoff + pathAtMost + 1.0E-6D);
    }

    /** The moving-hull scenario's own build site and flight height, clear of this class's other one. */
    private static final int MOVING_SRC_X = 7600, MOVING_SRC_Z = 7600;
    private static final int MOVING_X = 7600, MOVING_Y = 170, MOVING_Z = 9800;
    /** What the hull's flight computer is told to fly, blocks per SECOND along +X — its own unit. */
    private static final double COMMANDED_SPEED = 10.0D;
    /** World given to the computer to bring the hull up to its commanded speed. */
    private static final int SETTLE_TICKS = 80;
    /** The window the hull's motion is measured over, immediately before the shot. */
    private static final int WINDOW_TICKS = 20;

    /**
     * A round fired from a hull that is MOVING leaves with the hull's motion added to its own — the
     * motion the hull really has, in the unit the round flies in.
     *
     * <p>The scenario above parks its hull, so the inherited motion is zero there and any scale on it
     * passes. Here the hull is flown by its own flight computer across the gun's line of fire, and the
     * motion production hands the round ({@code turret_muzzle}, read at the seam where it is decided) is
     * compared with the hull's own displacement per tick, measured from its position over a window just
     * before the shot.</p>
     *
     * <p>red-witnessed: with {@code VSBridge#shipVelocityAtPointFor} at
     * {@code (vLin.x() + (w.y() * rz - w.z() * ry)) * SECONDS_PER_TICK,} answering the X component per
     * second again, this fails at "a round from a moving hull does not carry the hull's motion: it
     * carries 10.0 blocks a tick along X while the hull was measured doing 0.5634920634930884 (ratio
     * 17.746478873207156)" (2026-10-05).</p>
     */
    @Test
    public void aRoundFromAMovingHullCarriesTheHullsMotionPerTick() throws Exception {
        ask("stellurgytest shot clear 0").requireOk("clear the air");

        WarShip ship = WarShip.build(events, this::exec, FixtureSite.openAir(0, MOVING_SRC_X, MOVING_SRC_Z),
                null, "the hull the gun is bolted to");
        ship.parkAt(MOVING_X, MOVING_Y, MOVING_Z, ON_THE_HULL);

        int[] seat = ship.seat();
        int gunX = seat[0] + 3, gunY = seat[1], gunZ = seat[2];
        long built = events.markInstrumented();
        buildGun(gunX, gunY, gunZ);
        Weapons.awaitAssembled(events, built, gunX, gunY, gunZ, PARTS, "the gun aboard the hull never assembled");
        ask("stellurgytest turret charge 0 " + gunX + " " + gunY + " " + gunZ).requireOk("charge the gun");

        // Fly it: the computer realizes the command as force every physics tick, for as long as it stands.
        ask("stellurgytest vs unpark-by-id 0 " + ship.vsShip).requireOk("give the hull its physics back");
        Reply drive = ask("stellurgytest vs force-vel-by-id 0 " + ship.vsShip + " " + COMMANDED_SPEED + " 0 0");
        requireArranged("the command never reached this hull's own flight computer: " + drive,
                drive.bool("afcResolved"));
        GameTicks.advanceObserved(client(), GameTicks.server(), SETTLE_TICKS);

        // WINDOW: the hull's own displacement over ticks the box actually delivered, read twice.
        Reply before = ship.info();
        long elapsed = GameTicks.advanceObserved(client(), GameTicks.server(), WINDOW_TICKS);
        Reply after = ship.info();
        double hullPerTick = (after.number("posX") - before.number("posX")) / elapsed;
        System.out.println("[measure] hull moved " + (after.number("posX") - before.number("posX"))
                + " blocks along X in " + elapsed + " ticks = " + hullPerTick + " a tick");
        requireArranged("the hull is not flying along X at anything like its commanded "
                        + COMMANDED_SPEED + " blocks a second (" + hullPerTick + " a tick measured), so"
                        + " there is no motion here for a round to inherit: " + before + " -> " + after,
                hullPerTick > COMMANDED_SPEED / 20.0D * 0.5D);

        // Across the course, well clear of the hull: the round's own motion is along Z, so whatever
        // it carries along X is the hull's.
        long aimed = events.markInstrumented();
        ask("stellurgytest turret target 0 " + gunX + " " + gunY + " " + gunZ + " "
                + after.number("posX") + " " + after.number("posY") + " " + (after.number("posZ") + 80.0D))
                .requireOk("aim the gun across the hull's course");
        Weapons.awaitFired(events, aimed, gunX, gunY, gunZ, "the gun aboard the moving hull never fired");

        String muzzles = events.since(aimed, "turret_muzzle");
        Events.assertInstrumentRan(muzzles, "turret_muzzle_events", "the gun's muzzle was or was not resolved");
        List<String> ours = Events.recordsWhereAll(muzzles, "x", String.valueOf(gunX),
                "y", String.valueOf(gunY), "z", String.valueOf(gunZ));
        assertTrue("the gun fired and no muzzle of it was recorded: " + muzzles, !ours.isEmpty());
        String muzzle = ours.get(ours.size() - 1);
        double carriedX = Events.number(muzzle, "cx");
        double ratio = carriedX / hullPerTick;
        System.out.println("[measure] round carries " + carriedX + " a tick along X against the hull's "
                + hullPerTick + " (ratio " + ratio + ")");
        // The band is wide on purpose: the hull's speed is measured over a window, not at the instant,
        // and a computer still trimming its course moves it a little. A unit error is a factor of
        // twenty and cannot hide in it.
        assertTrue("a round from a moving hull does not carry the hull's motion: it carries " + carriedX
                        + " blocks a tick along X while the hull was measured doing " + hullPerTick
                        + " (ratio " + ratio + ") — " + muzzle,
                ratio > 0.5D && ratio < 2.0D);
    }

    /** The engagement scenarios' build sites and the altitude both hulls are parked at. */
    /**
     * Assembly leaves the build site's launch pad behind, so each engagement scenario builds on its
     * own pair of sites, a lane apart along Z: {@code lane} 0 and 1.
     */
    private static final int SHOOTER_SRC_X = 8400, QUARRY_SRC_X = 8800, SRC_LANE_Z = 7600, LANE_STEP = 400;
    private static final int ENGAGE_X = 8400, ENGAGE_Y = 170, ENGAGE_Z = 9800;
    /** How far along X the quarry is parked from the shooter: clear of both hulls' own extents. */
    private static final int QUARRY_OFFSET = 80;
    /** How far along Z the quarry is moved, to see the battery follow it. */
    private static final int QUARRY_MOVE = 120;
    /**
     * A point this close to a parked hull's own position is ON that hull: the with-pilot-seat fixture
     * spans about twenty blocks, so its world bounds' middle lies within half of that of the position.
     */
    private static final double ON_THAT_HULL = 16.0D;

    /** A battery aboard one hull, a second hull for it to engage, and a console on the battery. */
    private static final class Engagement {
        final WarShip shooter;
        final WarShip quarry;
        final int gunX, gunY, gunZ;
        final int consoleX;

        Engagement(WarShip shooter, WarShip quarry, int gunX, int gunY, int gunZ, int consoleX) {
            this.shooter = shooter;
            this.quarry = quarry;
            this.gunX = gunX;
            this.gunY = gunY;
            this.gunZ = gunZ;
            this.consoleX = consoleX;
        }

        String console() {
            return "0 " + consoleX + " " + gunY + " " + gunZ;
        }

        String gun() {
            return "0 " + gunX + " " + gunY + " " + gunZ;
        }
    }

    /**
     * Two hulls parked side by side, a charged gun aboard the first with a console touching it — one
     * weapons network — and the network on the rule given.
     */
    private Engagement arrangeEngagement(int lane, String allegiance) throws Exception {
        ask("stellurgytest shot clear 0").requireOk("clear the air");
        int srcZ = SRC_LANE_Z + lane * LANE_STEP;
        WarShip shooter = WarShip.build(events, this::exec, FixtureSite.openAir(0, SHOOTER_SRC_X, srcZ),
                null, "the hull the battery stands on");
        shooter.parkAt(ENGAGE_X, ENGAGE_Y, ENGAGE_Z, ON_THE_HULL);
        WarShip quarry = WarShip.build(events, this::exec, FixtureSite.openAir(0, QUARRY_SRC_X, srcZ),
                null, "the hull the battery is told to engage");
        quarry.parkAt(ENGAGE_X + QUARRY_OFFSET, ENGAGE_Y, ENGAGE_Z, ON_THE_HULL);

        int[] seat = shooter.seat();
        int gunX = seat[0] + 3, gunY = seat[1], gunZ = seat[2];
        long built = events.markInstrumented();
        buildGun(gunX, gunY, gunZ);
        Weapons.awaitAssembled(events, built, gunX, gunY, gunZ, PARTS, "the battery's gun never assembled");
        ask("stellurgytest turret charge 0 " + gunX + " " + gunY + " " + gunZ).requireOk("charge the gun");

        int consoleX = gunX + 1;
        long placed = events.mark();
        place("stellurgy:weaponConsole", consoleX, gunY, gunZ);
        events.awaitRecordWithFields(placed, "subsystem_network_rebuilt",
                "the weapons network never rebuilt after the console was placed", Weapons.ARRANGEMENT_TICKS,
                "domain", "Weapon", "dim", "0");
        Engagement engagement = new Engagement(shooter, quarry, gunX, gunY, gunZ, consoleX);
        Reply network = ask("stellurgytest weaponconsole read " + engagement.console()).requireOk("read the console");
        requireArranged("the console touching the gun is not commanding it: " + network,
                network.bool("network") && network.integer("guns") == 1);
        requireArranged("the console would not take the rule " + allegiance,
                ask("stellurgytest weaponconsole allegiance " + engagement.console() + " " + allegiance)
                        .requireOk("set the rule").bool("applied"));
        return engagement;
    }

    /** Where the gun is pointing this tick and how far that is from the quarry's own position. */
    private double targetFromQuarry(Engagement engagement) throws Exception {
        Reply gun = ask("stellurgytest turret read " + engagement.gun()).requireOk("read the gun");
        requireArranged("the gun holds no target at all: " + gun, gun.bool("hasTarget"));
        Reply quarry = engagement.quarry.info();
        return Math.sqrt(sq(gun.number("targetX") - quarry.number("posX"))
                + sq(gun.number("targetY") - quarry.number("posY"))
                + sq(gun.number("targetZ") - quarry.number("posZ")));
    }

    /**
     * A battery told to engage a SHIP aims at that hull — wherever the hull is now, not where it was
     * when the order was given — and fires on it.
     *
     * <p>red-witnessed: with {@code TileTurret#shipIntercept} at
     * {@code net.minecraft.util.math.AxisAlignedBB hull = VSIntegration.shipWorldBoundsOf(world, uuid);}
     * reading the hull's SHIPYARD bounds instead, this fails at "the battery is aimed
     * 1.9191751436964057E7 blocks from the ship it was told to engage — not at its hull" (2026-10-05).</p>
     */
    @Test
    public void aBatteryToldToEngageAShipAimsAtItsHullWhereverItIs() throws Exception {
        Engagement engagement = arrangeEngagement(0, "NONE");

        long ordered = events.markInstrumented();
        requireArranged("the console would not take a ship order",
                ask("stellurgytest weaponconsole ship " + engagement.console() + " " + engagement.quarry.vsShip)
                        .requireOk("order the battery onto the quarry").bool("applied"));
        Weapons.awaitFired(events, ordered, engagement.gunX, engagement.gunY, engagement.gunZ,
                "a battery ordered onto a ship never fired on it");
        double before = targetFromQuarry(engagement);
        assertTrue("the battery is aimed " + before + " blocks from the ship it was told to engage — not"
                + " at its hull", before < ON_THAT_HULL);

        engagement.quarry.parkAt(ENGAGE_X + QUARRY_OFFSET, ENGAGE_Y, ENGAGE_Z + QUARRY_MOVE, ON_THE_HULL);
        double after = targetFromQuarry(engagement);
        assertTrue("the ship moved " + QUARRY_MOVE + " blocks and the battery is aimed " + after
                + " blocks from where it is now — it kept the point the order was given at", after < ON_THAT_HULL);
    }

    /**
     * Under "a ship whose weapons carry our code is a friend", a hull with a console set to our code
     * is not fired on; the same hull under "no ship is a friend" is. One battery, one quarry, the rule
     * switched between the two verdicts.
     *
     * <p>red-witnessed: with {@code HullAllegianceRule#isFriend} at {@code if (ours.isEmpty() || shipId == null) {}
     * in {@code CODE_ON_WEAPONS} inverted to refuse every named ship, this fails at "the battery never
     * decided about the hull its code vouches for — no `turret_fire_decided` carrying … friendly = true"
     * (2026-10-05).</p>
     *
     * <p>red-witnessed: with {@code HullAllegianceRule#isFriend} at {@code return false;} in {@code NONE}
     * answering {@code return true;}, this fails at
     * "under \"no ship is a friend\" the battery never decided about the same hull again — no
     * `turret_fire_decided` carrying … friendly = false" (2026-10-05).</p>
     */
    @Test
    public void aHullWhoseWeaponsCarryOurCodeIsSparedOnlyUnderThatRule() throws Exception {
        Engagement engagement = arrangeEngagement(1, "CODE_ON_WEAPONS");
        ask("stellurgytest weaponconsole code " + engagement.console() + " ALPHA").requireOk("our code");
        // The quarry's own console, set to the same code: its weapons vouch for it.
        int[] quarrySeat = engagement.quarry.seat();
        long placed = events.mark();
        place("stellurgy:weaponConsole", quarrySeat[0] + 2, quarrySeat[1], quarrySeat[2]);
        events.awaitRecordWithFields(placed, "subsystem_network_rebuilt",
                "the quarry's console never joined a network", Weapons.ARRANGEMENT_TICKS,
                "domain", "Weapon", "dim", "0");
        String quarryConsole = "0 " + (quarrySeat[0] + 2) + " " + quarrySeat[1] + " " + quarrySeat[2];
        requireArranged("the quarry's console took no code",
                ask("stellurgytest weaponconsole code " + quarryConsole + " ALPHA").requireOk("their code")
                        .bool("applied"));

        long ordered = events.markInstrumented();
        ask("stellurgytest weaponconsole ship " + engagement.console() + " " + engagement.quarry.vsShip)
                .requireOk("order the battery onto the quarry");
        String spared = events.awaitRecordWithFields(ordered, "turret_fire_decided",
                "the battery never decided about the hull its code vouches for", Weapons.SUBJECT_TICKS,
                "pos", Weapons.at(engagement.gunX, engagement.gunY, engagement.gunZ), "friendly", "true");
        assertEquals("the battery was PERMITTED to fire on a hull whose weapons carry its own code: " + spared,
                "false", Events.text(spared, "permitted"));

        long switched = events.markInstrumented();
        ask("stellurgytest weaponconsole allegiance " + engagement.console() + " NONE").requireOk("no friends");
        String engaged = events.awaitRecordWithFields(switched, "turret_fire_decided",
                "under \"no ship is a friend\" the battery never decided about the same hull again",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(engagement.gunX, engagement.gunY, engagement.gunZ),
                "friendly", "false");
        assertEquals("under \"no ship is a friend\" the battery still would not fire on the hull: " + engaged,
                "true", Events.text(engaged, "permitted"));
    }

    /** The same reference gun the ground tests use, placed at SUBSPACE coordinates. */
    private void buildGun(int gx, int gy, int gz) throws Exception {
        place("stellurgy:turret", gx, gy, gz);
        for (int i = 1; i <= BARRELS; i++) {
            place("stellurgy:gunBarrel", gx, gy + i, gz);
        }
        place("stellurgy:gunCooling", gx, gy, gz + 1);
        place("stellurgy:gunCooling", gx, gy, gz - 1);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + placed,
                placed.bool("placed"));
    }

    private static double sq(double v) {
        return v * v;
    }
}
