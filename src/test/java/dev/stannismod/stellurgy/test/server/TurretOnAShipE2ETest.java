package dev.stannismod.stellurgy.test.server;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.WarShip;
import dev.stannismod.stellurgy.test.Weapons;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
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


    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
