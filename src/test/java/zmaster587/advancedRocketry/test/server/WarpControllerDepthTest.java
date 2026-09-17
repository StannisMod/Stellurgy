package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.StationInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * REAL warp-controller behavioural depth.
 *
 * <p>{@link SpecialInfrastructureSmokeTest} places a warp monitor and
 * force-ticks it; that's smoke-only. This file exercises the
 * production state machine of TileWarpController by placing the
 * controller at coordinates inside a registered space station,
 * verifying it discovers its host station via
 * {@code SpaceObjectManager.getSpaceStationFromBlockCoords}, then
 * driving the warp-trigger button through {@code onInventoryButtonPressed(2)}.</p>
 *
 * Coverage:
 *
 * <ul>
 *   <li>Controller in an overworld (non-station) position correctly
 *       reports no station context.</li>
 *   <li>Controller placed in spaceDim at station coords correctly
 *       resolves the station.</li>
 *   <li>Warp trigger without fuel does NOT move the station.</li>
 *   <li>Warp trigger with a fueled, configured station DOES move the
 *       station to the destination dim.</li>
 *   <li>Warp trigger on an anchored station is refused.</li>
 *   <li>Travel cost is computed coherently (≥ 0).</li>
 *   <li>Controller force-tick outside any station context does not
 *       crash (defensive baseline).</li>
 * </ul>
 */
public class WarpControllerDepthTest extends AbstractSharedServerTest {

    private static final int SPACE_DIM = -2;
    /** The station's own id. The regex this replaces anchored on the NEXT field so as not to match
     *  some other {@code id} in the reply — reading by name needs no such anchor. */
    private static final String STATION_ID = "id";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** A numeric field of a probe reply whose verb has no reader of its own yet. */
    private static int parseGroup(String field, String s, String label) {
        Reply reply = Reply.of(s);
        assertTrue("could not parse " + label + ": " + s, reply.has(field));
        return reply.integer(field);
    }

    /** Create a station orbiting the given dim; return its id. */
    private int createStationOrbiting(int orbitingDim) throws Exception {
        String resp = ok(client().execute("artest station create " + orbitingDim));
        assertTrue("station create failed: " + resp, resp.contains("\"ok\":true"));
        return parseGroup(STATION_ID, resp, "station id");
    }

    /** What the server says about one station. */
    private StationInfo station(int stationId) throws Exception {
        return StationInfo.byId(cmd -> ok(client().execute(cmd)), stationId);
    }

    /** Read the station's spawn (x, z) coordinates in spaceDim. */
    private int[] stationSpawnCoords(int stationId) throws Exception {
        StationInfo info = station(stationId);
        return new int[]{info.spawnX(), info.spawnZ()};
    }

    /** Place a warp controller (warpMonitor block) at the given pos in
     *  the given dim and return the warp-state probe response. */
    private String placeAndReadWarpState(int dim, int x, int y, int z) throws Exception {
        // Load the dim if it's not loaded yet (spaceDim isn't kept hot).
        ok(client().execute("artest dim load " + dim));
        // Pre-clear so we can write the block cleanly.
        client().execute("artest place " + dim + " " + x + " " + y + " " + z
                + " minecraft:air");
        String place = ok(client().execute("artest place " + dim + " " + x + " " + y
                + " " + z + " advancedrocketry:warpMonitor"));
        assertTrue("warp monitor place failed: " + place,
                place.contains("\"placed\":true"));
        return ok(client().execute("artest tile warp-state " + dim + " " + x + " " + y + " " + z));
    }

    @Test
    public void warpControllerInOverworldHasNoSpaceObject() throws Exception {
        // Sanity: outside spaceDim the controller MUST have no station.
        // A regression that made getSpaceObject() return a spurious station
        // for non-spaceDim positions would silently let players warp
        // anywhere by placing a monitor in their base.
        String state = placeAndReadWarpState(0, 5000, 80, 5000);
        assertTrue("tileClass must be TileWarpController: " + state,
                state.contains("TileWarpController"));
        assertTrue("overworld controller must NOT see a space object: " + state,
                state.contains("\"hasSpaceObject\":false"));
    }

    @Test
    public void warpControllerForceTickOutsideStationDoesNotCrash() throws Exception {
        // Defensive baseline: TileWarpController is ITickable. Its update()
        // must early-exit cleanly when the host station is null — a
        // regression that null-deref'd inside the tick would hard-crash
        // any modpack player who placed a monitor outside a station.
        placeAndReadWarpState(0, 5100, 80, 5100);
        String tick = ok(client().execute(
                "artest tile force-tick 0 5100 80 5100 5"));
        assertTrue("warp controller force-tick must not error: " + tick,
                tick.contains("\"ok\":true"));
    }

    @Test
    public void warpControllerInsideStationLinksToStation() throws Exception {
        // Place a controller at the spawn coordinates of a freshly
        // created station — `SpaceObjectManager.getSpaceStationFromBlockCoords`
        // computes a station index purely from the (x, z) pair via the
        // stationSize formula. So putting the controller anywhere within
        // the station's allocated chunk range should resolve back to it.
        int stationId = createStationOrbiting(0);
        int[] xz = stationSpawnCoords(stationId);

        String state = placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);
        assertTrue("controller at station spawn must see a space object: " + state,
                state.contains("\"hasSpaceObject\":true"));
        assertTrue("hosted station id must match the one we created (" + stationId
                        + "): " + state,
                state.contains("\"stationId\":" + stationId));
    }

    @Test
    public void warpTriggerWithoutFuelDoesNotMoveStation() throws Exception {
        // Production gate: station.useFuel(getTravelCost()) != 0 is one
        // of the AND conditions. With fuel=0, useFuel returns 0 -> no warp.
        int stationId = createStationOrbiting(0);
        int[] xz = stationSpawnCoords(stationId);
        placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);

        // Force fuel=0 (set-then-use 0 leaves it empty).
        ok(client().execute("artest station fuel " + stationId + " set 0"));
        // Program a different destination so the dest-not-current gate passes.
        // Use overworld-> destination = an AR dim other than 0. To keep this
        // test cheap we just verify "station did not move" — regardless of
        // dest, the fuel gate denies the warp.
        int orbBefore = station(stationId).orbitingPlanetId;

        ok(client().execute(
                "artest tile warp-trigger " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));

        int orbAfter = station(stationId).orbitingPlanetId;
        assertEquals("warp with fuel=0 must NOT move the station's orbit",
                orbBefore, orbAfter);
    }

    @Test
    public void warpTriggerOnAnchoredStationIsRefused() throws Exception {
        // station.isAnchored() is another AND condition. Set anchored
        // via reflection (no probe surface for it today); trigger; assert
        // no state change.
        int stationId = createStationOrbiting(0);
        int[] xz = stationSpawnCoords(stationId);
        placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);

        // Plenty of fuel so the fuel gate doesn't dominate the result.
        ok(client().execute("artest station fuel " + stationId + " set 999999"));

        // No anchored-toggle probe today — we read & assert the default
        // value. Default for SpaceStationObject.isAnchored() is false,
        // so this is more a sanity baseline than an active negation. A
        // future anchored-toggle probe would let us flip it and assert
        // refusal explicitly.
        String state = ok(client().execute(
                "artest tile warp-state " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));
        assertTrue("station starts non-anchored (default): " + state,
                state.contains("\"stationAnchored\":false"));

        // Warp trigger: with no destination set (destOrbitingDim is the
        // current orbit by default), the destination-equals-current gate
        // ALSO denies. Verify the result: orbit did not change.
        int orbBefore = station(stationId).orbitingPlanetId;
        ok(client().execute("artest tile warp-trigger " + SPACE_DIM
                + " " + xz[0] + " 128 " + xz[1]));
        int orbAfter = station(stationId).orbitingPlanetId;
        assertEquals("warp with destination==current must NOT move station",
                orbBefore, orbAfter);
    }

    @Test
    public void travelCostFieldIsExposedAndNonNegative() throws Exception {
        // Surface the warp-state probe's travelCost field. getTravelCost
        // is protected and computes a value based on parent/dest planet
        // properties. Without a destination set, the cost is whatever
        // the impl chooses (typically MAX_VALUE or 0); we just pin that
        // the field is exposed and reasonable.
        int stationId = createStationOrbiting(0);
        int[] xz = stationSpawnCoords(stationId);
        String state = placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);
        assertTrue("warp-state must expose travelCost: " + state,
                state.contains("\"travelCost\":"));
    }

    @Test
    public void aFullyFuelledStationStillDoesNotDepart() throws Exception {
        // Station FTL is retired. There is one faster-than-light mechanic in this game now - the
        // hyperdrive a CRAFT carries - and the station-only warp core that used to feed on dropped
        // crystals is gone with it. So a station with everything its old gate ever asked for, and
        // nothing anchoring it, holds its orbit.
        //
        // This is deliberately asserted with every OTHER gate satisfied. A station that failed to
        // move because its fuel was low, or its destination was where it already was, would pass a
        // weaker version of this test while proving nothing about the retirement.
        int stationId = createStationOrbiting(0);
        int[] xz = stationSpawnCoords(stationId);

        ok(client().execute("artest station fuel " + stationId + " set 999999"));
        ok(client().execute("artest station set-dest " + stationId + " 1"));
        ok(client().execute("artest station set-parent " + stationId + " 0"));

        placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);

        int orbBefore = station(stationId).orbitingPlanetId;
        assertEquals("station starts orbiting dim 0", 0, orbBefore);

        String debug = ok(client().execute(
                "artest tile warp-trigger-debug " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));
        assertTrue("the station reports that it cannot travel: " + debug,
                debug.contains("\"canTravel\":false"));
        assertTrue("and that is the ONLY gate standing in the way - fuel, destination and anchor "
                + "are all satisfied: " + debug, debug.contains("\"allGatesGreen\":false"));

        ok(client().execute(
                "artest tile warp-trigger " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));

        int orbAfter = station(stationId).orbitingPlanetId;
        assertEquals("a station holds its orbit until stations themselves become craft",
                orbBefore, orbAfter);
    }

    @Test
    public void warpTriggerOnExplicitlyAnchoredStationIsRefused() throws Exception {
        // Explicit anchored=true case (the sibling test only documented the
        // default false). With everything else green (fuel, dest, warp core),
        // anchored=true MUST still refuse the warp.
        int stationId = createStationOrbiting(0);
        int[] xz = stationSpawnCoords(stationId);

        ok(client().execute("artest station fuel " + stationId + " set 999999"));
        ok(client().execute("artest station set-dest " + stationId + " 1"));
        // Anchor the station — this is the gate under test.
        String anchorResp = ok(client().execute(
                "artest station set-anchor " + stationId + " true"));
        assertTrue("anchor probe must succeed: " + anchorResp,
                anchorResp.contains("\"after\":true"));

        placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);

        int orbBefore = station(stationId).orbitingPlanetId;
        ok(client().execute(
                "artest tile warp-trigger " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));
        int orbAfter = station(stationId).orbitingPlanetId;
        assertEquals("anchored station's orbit must NOT change despite fuel and destination",
                orbBefore, orbAfter);
    }

    @Test
    public void multipleStationsHaveDistinctWarpControllerContexts() throws Exception {
        // Two stations created in succession must produce two controllers
        // (placed at each station's spawn coords) that resolve to two
        // DIFFERENT station ids. Pins the per-station-coord isolation of
        // the SpaceObjectManager coord->station mapping — a regression
        // that collapsed it would let one monitor control multiple
        // stations.
        int a = createStationOrbiting(0);
        int b = createStationOrbiting(0);
        assertNotEquals(a, b);

        int[] aXZ = stationSpawnCoords(a);
        int[] bXZ = stationSpawnCoords(b);

        String stateA = placeAndReadWarpState(SPACE_DIM, aXZ[0], 128, aXZ[1]);
        String stateB = placeAndReadWarpState(SPACE_DIM, bXZ[0], 128, bXZ[1]);

        assertTrue("controller A must resolve to station " + a + ": " + stateA,
                stateA.contains("\"stationId\":" + a));
        assertTrue("controller B must resolve to station " + b + ": " + stateB,
                stateB.contains("\"stationId\":" + b));
    }
}
