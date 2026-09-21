package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.After;
import org.junit.Test;


import zmaster587.advancedRocketry.api.FreeFlightPhysics;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

/**
 * A pilot seat's mount faces where its SHIP faces.
 *
 * <p>The dummy a pilot rides is bound to a seat block that lives in the ship's subspace while the
 * ship itself flies around the world, and it is snapped onto the seat's live world position every
 * tick. Its POSITION therefore tracks the ship — but position is only half of a mount. Anything that
 * asks the thing the player is riding <em>which way am I pointing</em> reads its ROTATION, and a
 * rotation nobody writes is a constant: it answers "due south" on every ship, on every heading, for
 * ever. That is not a rendering nicety — it is a mount that lies about the ship it is glued to.
 *
 * <p><b>The arrangement has to be able to fail.</b> A freshly assembled ship sits on the axis, where
 * a stuck-at-zero rotation and the true heading are the same number. So the ship is TURNED first and
 * the turn is read back off the ship itself; the assertion only means something after that gate, and
 * the gate is a hard failure rather than a skip.
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSSeatDummyFacesTheShipE2ETest extends AbstractSharedServerTest {


    private static final int SRC_X = 8800, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 8800;

    /** Quaternion for a ~90-degree yaw about world +Y: far from the fixture's own axis-aligned heading. */
    private static final double TURN_QW = 0.70711, TURN_QY = 0.70711;

    /**
     * Budgets in SERVER TICKS, neither fork-scaled: 200 is the ten seconds the old 40 x 250 ms meant
     * on an idle box, for a slew that runs on the attitude controller's own tick and for a ship
     * becoming loadable.
     */
    private static final int SLEW_TICKS = 200;

    /** Degrees. Generous: what is under test is that the mount TURNS WITH the ship, not the controller's
     *  settling error, and a hovering attitude hold parks within a couple of degrees. */
    private static final double YAW_TOLERANCE_DEG = 6.0;

    @Test
    public void theSeatMountTurnsWithItsShip() throws Exception {

        String coords = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("the pilot-seat build must route to a ship, not a rocket: " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));
        assertTrue("the source ship never assembled/loaded", loadedShips(0) >= 1);

        // The craft this scenario built, by the name its assembler minted, and the physics id that
        // name maps to. The build site is in a world every server-tier class shares.
        String durableId = ShipIdentity.nameFromAssembly(asm);
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableId);

        PilotSeat seat = PilotSeat.byId(this::exec, 0, shipId)
                .requireFound("the pilot seat must be found in the assembled ship, or nothing below is measured");
        int seatX = seat.seatX, seatY = seat.seatY, seatZ = seat.seatZ;

        String mountAt = exec("artest vs seat-mount-at 0 " + seatX + " " + seatY + " " + seatZ);
        assertTrue("the seat's mount dummy must spawn: " + mountAt, Reply.of(mountAt).ok());

        // Where the ship points BEFORE the turn, and where its mount thinks it points. Asked by the
        // id resolved above: a lookup at the build site was defended as "the one positional lookup
        // this scenario can defend — the ship has not moved", but not having moved is a fact about
        // THIS craft and says nothing about how many others are standing there.
        double shipYawBefore = shipYawOf(ShipInfo.byId(this::exec, 0, shipId));
        double mountYawBefore = mountYaw(seatX, seatY, seatZ);

        // ── TURN THE SHIP ───────────────────────────────────────────────────────────────────────
        // Commanded on an UNMANNED ship: a seated pilot's own input would overwrite the attitude
        // target every tick. The ship hovers while the controller slews it round.
        assertTrue("the attitude hold must accept the yaw command",
                Reply.of(exec("artest vs point-by-id 0 " + shipId
                        + " " + TURN_QW + " 0.0 " + TURN_QY + " 0.0")).bool("commanded"));

        // The slew runs on the attitude controller's tick, so the budget is that controller's world.
        final double[] yaw = {shipYawBefore};
        GameTicks.until(client(), GameTicks.server(), SLEW_TICKS, () -> {
            yaw[0] = shipYawOf(ShipInfo.byId(this::exec, 0, shipId));
            return Math.abs(wrapDegrees(yaw[0] - shipYawBefore)) > 45.0;
        });
        double shipYawAfter = yaw[0];

        // The gate: unless the SHIP really turned, "the mount agrees with the ship" is a comparison
        // of two zeroes and would be green on a build where nothing writes the mount at all.
        double shipTurned = Math.abs(wrapDegrees(shipYawAfter - shipYawBefore));
        assertTrue("the ship itself must have turned well away from its assembled heading, or the "
                        + "assertion below cannot fail (ship yaw " + shipYawBefore + " -> " + shipYawAfter
                        + ", turned " + shipTurned + " deg)",
                shipTurned > 45.0);

        // ── THE SUBJECT ─────────────────────────────────────────────────────────────────────────
        double mountYawAfter = mountYaw(seatX, seatY, seatZ);
        assertTrue("the mount must have turned WITH its ship - a mount whose rotation never moves is "
                        + "a mount that reports the wrong heading to everything that asks it (mount yaw "
                        + mountYawBefore + " -> " + mountYawAfter + " while the ship turned " + shipTurned
                        + " deg)",
                Math.abs(wrapDegrees(mountYawAfter - mountYawBefore)) > 45.0);
        assertTrue("the mount must face where its ship faces (mount yaw " + mountYawAfter
                        + " vs ship yaw " + shipYawAfter + ")",
                Math.abs(wrapDegrees(mountYawAfter - shipYawAfter)) <= YAW_TOLERANCE_DEG);
    }

    @After
    public void cleanup() throws Exception {
    }

    // --- observation --------------------------------------------------------------------------------

    /** The mount's own yaw, off the seat block it is bound to. */
    private double mountYaw(int seatX, int seatY, int seatZ) throws Exception {
        String status = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        assertTrue("the seat's bound mount must be found for its rotation to be read: " + status,
                Reply.of(status).bool("dummyFound"));
        return extractDouble(status, "dummyYaw");
    }

    /** The ship's own heading, out of the attitude quaternion VS reports for it. */
    private double shipYawOf(ShipInfo ship) {
        FreeFlightPhysics.Quat q = new FreeFlightPhysics.Quat(ship.qw, ship.qx, ship.qy, ship.qz);
        return FreeFlightPhysics.eulerFromQuat(q)[0];
    }

    private static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    // --- arrangement --------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }


    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static String extractString(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).textOr(key, null);
    }

    private static int extractInt(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, 0.0);
    }
}
