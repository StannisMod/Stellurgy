package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.StationInfo;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * station-controller tick behaviour contracts.
 *
 * <p>{@link StationControllersSmokeTest} already pins "block places, tile
 * ticks without crashing" for the three station controllers. This suite
 * pins the next layer: the player-visible "set target &rarr; station walks
 * toward target" loop.</p>
 *
 * <p>Setup per test:</p>
 * <ol>
 *   <li>Load the space dim ({@code StellurgyConfiguration.spaceDimId}, default
 *       {@code -2}) — the controllers' update() short-circuits when
 *       {@code world.provider} is not a {@code WorldProviderSpace}.</li>
 *   <li>Create a station orbiting overworld (uses the existing
 *       {@code stellurgytest station create} probe; spawn coords are reported
 *       via {@code stellurgytest station info}).</li>
 *   <li>Place the controller block at the station's spawn coords.</li>
 *   <li>Set the controller's target via the new
 *       {@code stellurgytest station controller-set-target} probe.</li>
 *   <li>Force-tick the controller.</li>
 *   <li>Read the station's actual orbital distance / gravity / rotation
 *       via the extended {@code stellurgytest station info}; assert it has
 *       moved from baseline toward target.</li>
 * </ol>
 *
 * <p>Loose-bound pins — production walks the value at a fixed
 * accel/tick rate. Contract
 * is "moves at all in the right direction", not "moves by exactly
 * 0.02 units/tick".</p>
 */
public class StationControllersTickContractTest extends AbstractSharedServerTest {

    private static final int SPACE_DIM = -2;

    private static final String STATION_ID = "id";

    /**
     * Pin: altitude controller, given a target via the probe and
     * force-ticked, makes the station's actual orbital distance walk
     * toward that target.
     *
     * <p>Production formula (per
     * {@link dev.stannismod.stellurgy.tile.station.TileStationAltitudeController#update}):
     * {@code finalVel = angVel ± min(|difference|, acc)} with
     * {@code acc = 0.02}. Pin doesn't assert exact 0.02/tick — that's
     * impl — only that the walk is non-zero in the right direction.</p>
     * Pins INV-STN-10 (the altitude controller walks the station's orbital distance toward its target).
     */
    @Pins("INV-STN-10")
    @RedWitness(member = "TileStationAltitudeController#update", at = "double next = current +",
            replacedBy = "double next = current -",
            fails = "station's orbitalDistance must move toward the target", on = "2026-10-19")
    @Test
    public void altitudeControllerWalksStationOrbitalDistanceTowardTarget()
            throws Exception {
        arrange("stellurgytest dim load " + SPACE_DIM);

        int stationId = createStation();
        int[] origin = stationSpawn(stationId);
        int cx = origin[0], cy = 128, cz = origin[2];

        // Pre-load chunk at the station's coords + place the controller.
        arrange("stellurgytest fill " + SPACE_DIM + " " + (cx - 1) + " " + cy + " " + (cz - 1)
                + " " + (cx + 1) + " " + cy + " " + (cz + 1) + " minecraft:air").raw();
        String place = arrange("stellurgytest place " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " stellurgy:altitudeController").raw();
        assertTrue("altitude controller must place: " + place,
                Reply.of(place).bool("placed"));

        // Snapshot pre-tick orbital distance.
        double preDist = station(stationId).orbitalDistance;

        // Set target via the new probe — pick a value definitely
        // different from the current orbital distance.
        int target = (int) (preDist + 50);
        String setTarget = act("stellurgytest station controller-set-target "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz + " 0 " + target).raw();
        assertTrue("controller-set-target must succeed: " + setTarget,
                Reply.of(setTarget).ok());

        // Sanity: station info now reports the target.
        int actualTarget = station(stationId).targetOrbitalDistance();
        assertTrue("station's targetOrbitalDistance must reflect the "
                        + "controller-set-target write; target=" + target
                        + " actualTarget=" + actualTarget,
                actualTarget == target);

        // Force-tick the controller. Production walks 0.02 per tick, so
        // 200 ticks -> ≤4.0 movement. Generous budget so even a slow
        // harness sees a non-zero delta.
        act("stellurgytest tile force-tick " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " 200").raw();

        double postDist = station(stationId).orbitalDistance;

        assertNotEquals("station's actual orbitalDistance must have moved "
                        + "from baseline after 200 controller ticks "
                        + "(player-visible 'altitude controller does "
                        + "something' contract); preDist=" + preDist
                        + " postDist=" + postDist + " target=" + target,
                preDist, postDist, 1e-9);
        // Direction check: post moved TOWARD target (preDist < target ->
        // postDist > preDist).
        assertTrue("station's orbitalDistance must move toward the target "
                        + "(not away from it); preDist=" + preDist
                        + " postDist=" + postDist + " target=" + target,
                Math.abs(postDist - target) < Math.abs(preDist - target));
    }

    /**
     * Pin: the gravity controller, given a target through its slider, makes the station's actual
     * gravity walk toward that target.
     *
     * <p>The controller's redstone button is set OFF first, as a player does in its screen: with the
     * button ON (its default) and no signal, every tick rewrites the target to the minimum, which is
     * where a fresh station's gravity already stands — so nothing could move, and "gravity is below
     * one" held with no controller at all. The target is read back from the station after the slider
     * write, and the start is read before the ticks, so the verdict compares the walk against a
     * target that differs from where it began.</p>
     * Pins INV-STN-11 (the gravity controller walks the station's gravity toward its target).
     */
    @Pins("INV-STN-11")
    @RedWitness(member = "TileStationGravityController#update",
            at = "spaceObject.getProperties().setGravitationalMultiplier((float) finalVel);", replacedBy = "",
            fails = "station's gravity must move toward the controller's target", on = "2026-10-19")
    @Test
    public void gravityControllerWalksStationGravityTowardTarget() throws Exception {
        arrange("stellurgytest dim load " + SPACE_DIM);

        int stationId = createStation();
        int[] origin = stationSpawn(stationId);
        int cx = origin[0], cy = 130, cz = origin[2];

        arrange("stellurgytest fill " + SPACE_DIM + " " + (cx - 1) + " " + cy + " " + (cz - 1)
                + " " + (cx + 1) + " " + cy + " " + (cz + 1) + " minecraft:air");
        String place = arrange("stellurgytest place " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " stellurgy:gravityController").raw();
        assertTrue("gravity controller must place: " + place,
                Reply.of(place).bool("placed"));

        Reply redstone = arrange("stellurgytest station controller-set-redstone "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz + " OFF");
        assertTrue("controller-set-redstone OFF must succeed: " + redstone, redstone.ok());
        Reply setTarget = act("stellurgytest station controller-set-target "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz + " 0 50");
        assertTrue("controller-set-target must succeed: " + setTarget, setTarget.ok());

        StationInfo before = station(stationId);
        double preGravity = before.gravity();
        double targetGravity = before.targetGravityPercent() / 100D;
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the slider's target must differ from where the station's gravity starts: "
                        + before.raw(), Math.abs(targetGravity - preGravity) > 0.01);

        act("stellurgytest tile force-tick " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " 200").raw();

        StationInfo after = station(stationId);
        double postGravity = after.gravity();
        assertTrue("station's gravity must move toward the controller's target; pre=" + preGravity
                        + " post=" + postGravity + " target=" + targetGravity + " info=" + after.raw(),
                Math.abs(postGravity - targetGravity) < Math.abs(preGravity - targetGravity));
    }

    /**
     * Pin: orientation controller writes target rotations-per-hour into
     * the station and tick walks the station's rotation toward it.
     *
     * <p>Production: {@code setProgress(id, val)} writes
     * {@code targetRotationsPerHour[id] = val - 60} (60 = getTotalProgress/2).
     * The update() loop walks {@code deltaRotation} toward
     * {@code targetRPH/72000}.</p>
     * Pins INV-STN-12 (the orientation controller walks the station's rotation toward its target).
     */
    @Pins("INV-STN-12")
    @RedWitness(member = "TileStationOrientationController#update", at = "spaceObject.setDeltaRotation(finalVel, dirs[i]);",
            replacedBy = "", fails = "station's rotation around EAST must move from baseline", on = "2026-10-19")
    @Test
    public void orientationControllerWalksStationRotationTowardTarget()
            throws Exception {
        arrange("stellurgytest dim load " + SPACE_DIM);

        int stationId = createStation();
        int[] origin = stationSpawn(stationId);
        int cx = origin[0], cy = 132, cz = origin[2];

        arrange("stellurgytest fill " + SPACE_DIM + " " + (cx - 1) + " " + cy + " " + (cz - 1)
                + " " + (cx + 1) + " " + cy + " " + (cz + 1) + " minecraft:air");
        String place = arrange("stellurgytest place " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " stellurgy:orientationController").raw();
        assertTrue("orientation controller must place: " + place,
                Reply.of(place).bool("placed"));

        double preRotEast = station(stationId).rotationEast();

        // setProgress(0, 100) -> targetRotationsPerHour[0] = 100 - 60 = 40
        // -> angular velocity target = 40/72000 ~ 5.5e-4. Default ~0 ->
        // walk at acc per tick.
        int progress = 100;
        String setTarget = act("stellurgytest station controller-set-target "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz + " 0 " + progress).raw();
        assertTrue("controller-set-target must succeed: " + setTarget,
                Reply.of(setTarget).ok());

        int actualTargetRph0 = station(stationId).targetRotationsPerHour(0);
        // targetRotationsPerHour[0] = progress - 60 = 40.
        assertTrue("station's targetRPH0 must reflect controller-set-target "
                        + "write; progress=" + progress
                        + " actualTargetRph0=" + actualTargetRph0,
                actualTargetRph0 == progress - 60);

        // Force-tick. acc = getMaxRotationalAcceleration() — small but
        // non-zero; 400 ticks should produce a measurable delta.
        act("stellurgytest tile force-tick " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " 400").raw();

        double postRotEast = station(stationId).rotationEast();

        assertNotEquals("station's rotation around EAST must move from "
                        + "baseline after 400 controller ticks; preRotEast="
                        + preRotEast + " postRotEast=" + postRotEast,
                preRotEast, postRotEast, 1e-12);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private int createStation() throws Exception {
        String create = arrange("stellurgytest station create 0").raw();
        assertTrue("station create failed: " + create,
                Reply.of(create).ok());
        Reply mReply = Reply.of(create);
        assertTrue("no station id in create response: " + create, mReply.has(STATION_ID));
        return Integer.parseInt(mReply.text(STATION_ID));
    }

    /** What the server says about one station. */
    private StationInfo station(int stationId) throws Exception {
        return StationInfo.byId(c -> read(c).raw(), stationId);
    }

    private int[] stationSpawn(int stationId) throws Exception {
        StationInfo info = station(stationId);
        return new int[]{info.spawnX(), 128, info.spawnZ()};
    }
}
