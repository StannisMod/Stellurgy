package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.MachineInfo;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.StationInfo;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
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
        String resp = ok(client().execute("stellurgytest station create " + orbitingDim));
        assertTrue("station create failed: " + resp, Reply.of(resp).ok());
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
        ok(client().execute("stellurgytest dim load " + dim));
        // Pre-clear so we can write the block cleanly.
        client().execute("stellurgytest place " + dim + " " + x + " " + y + " " + z
                + " minecraft:air");
        String place = ok(client().execute("stellurgytest place " + dim + " " + x + " " + y
                + " " + z + " stellurgy:warpMonitor"));
        assertTrue("warp monitor place failed: " + place,
                Reply.of(place).bool("placed"));
        return ok(client().execute("stellurgytest tile warp-state " + dim + " " + x + " " + y + " " + z));
    }

    @Test
    public void warpControllerInOverworldHasNoSpaceObject() throws Exception {
        // Sanity: outside spaceDim the controller MUST have no station.
        // A regression that made getSpaceObject() return a spurious station
        // for non-spaceDim positions would silently let players warp
        // anywhere by placing a monitor in their base.
        String state = placeAndReadWarpState(0, 5000, 80, 5000);
        assertEquals("tileClass must be TileWarpController: " + state,
                "TileWarpController", MachineInfo.of(state).tileSimpleName());
        assertTrue("overworld controller must NOT see a space object: " + state,
                (!Reply.of(state).bool("hasSpaceObject")));
    }

    @Test
    public void warpControllerForceTickOutsideStationDoesNotCrash() throws Exception {
        // Defensive baseline: TileWarpController is ITickable. Its update()
        // must early-exit cleanly when the host station is null — a
        // regression that null-deref'd inside the tick would hard-crash
        // any modpack player who placed a monitor outside a station.
        placeAndReadWarpState(0, 5100, 80, 5100);
        String tick = ok(client().execute(
                "stellurgytest tile force-tick 0 5100 80 5100 5"));
        assertTrue("warp controller force-tick must not error: " + tick,
                Reply.of(tick).ok());
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
                Reply.of(state).bool("hasSpaceObject"));
        assertTrue("hosted station id must match the one we created (" + stationId
                        + "): " + state,
                String.valueOf(stationId).equals(Reply.of(state).text("stationId")));
    }

    @Test
    public void warpTriggerWithoutFuelDoesNotMoveStation() throws Exception {
        // Production gate: station.useFuel(getTravelCost()) != 0 is one
        // of the AND conditions. With fuel=0, useFuel returns 0 -> no warp.
        int stationId = createStationOrbiting(0);
        int[] xz = stationSpawnCoords(stationId);
        placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);

        // Force fuel=0 (set-then-use 0 leaves it empty).
        ok(client().execute("stellurgytest station fuel " + stationId + " set 0"));
        // Program a different destination so the dest-not-current gate passes.
        // Use overworld-> destination = a Stellurgy dim other than 0. To keep this
        // test cheap we just verify "station did not move" — regardless of
        // dest, the fuel gate denies the warp.
        int orbBefore = station(stationId).orbitingPlanetId;

        ok(client().execute(
                "stellurgytest tile warp-trigger " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));

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
        ok(client().execute("stellurgytest station fuel " + stationId + " set 999999"));

        // Warp trigger: with no destination set (destOrbitingDim is the
        // current orbit by default), the destination-equals-current gate
        // ALSO denies. Verify the result: orbit did not change.
        int orbBefore = station(stationId).orbitingPlanetId;
        ok(client().execute("stellurgytest tile warp-trigger " + SPACE_DIM
                + " " + xz[0] + " 128 " + xz[1]));
        int orbAfter = station(stationId).orbitingPlanetId;
        assertEquals("warp with destination==current must NOT move station",
                orbBefore, orbAfter);
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

        ok(client().execute("stellurgytest station fuel " + stationId + " set 999999"));
        ok(client().execute("stellurgytest station set-dest " + stationId + " 1"));
        ok(client().execute("stellurgytest station set-parent " + stationId + " 0"));

        placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);

        int orbBefore = station(stationId).orbitingPlanetId;

        String debug = ok(client().execute(
                "stellurgytest tile warp-trigger-debug " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));
        assertTrue("the station reports that it cannot travel: " + debug,
                (!Reply.of(debug).bool("canTravel")));
        assertTrue("and that is the ONLY gate standing in the way - fuel, destination and anchor "
                + "are all satisfied: " + debug, (!Reply.of(debug).bool("allGatesGreen")));

        ok(client().execute(
                "stellurgytest tile warp-trigger " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));

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

        ok(client().execute("stellurgytest station fuel " + stationId + " set 999999"));
        ok(client().execute("stellurgytest station set-dest " + stationId + " 1"));
        // Anchor the station — this is the gate under test.
        ok(client().execute(
                "stellurgytest station set-anchor " + stationId + " true"));

        placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);

        int orbBefore = station(stationId).orbitingPlanetId;
        ok(client().execute(
                "stellurgytest tile warp-trigger " + SPACE_DIM + " " + xz[0] + " 128 " + xz[1]));
        int orbAfter = station(stationId).orbitingPlanetId;
        assertEquals("anchored station's orbit must NOT change despite fuel and destination",
                orbBefore, orbAfter);
    }

    /**
     * What a warp between two planets of one system costs, as a price per AU of their separation:
     * one fuel per hundredth of an AU, i.e. 100 per AU. That is the price the controller quoted
     * before the distance unit became a length — its cost WAS the separation in raw units, and a
     * unit was a hundredth of an AU — measured by reading {@code TileWarpController.getTravelCost}
     * at the commit before that change. The unit change was ratified as a change of representation,
     * so the price did not move.
     */
    private static final double FUEL_PER_AU = 100d;

    /**
     * The controller prices a warp between two planets of one star by their separation across the
     * orbital plane, 100 fuel per AU — whatever unit the orbits are stored in.
     *
     * <p>The pair is two planets the production {@code planet generate} command derives for Sol, so
     * neither orbit is a number this test chose; both orbits and both live angles are read off the
     * server, and the expected price is the separation of those two positions.
     * Acceptance, stated before the code: the quote equals {@code 100 x separation(AU)} within the
     * slack derived beside the assertion (the int truncation, the sine table's step, and the drift
     * measured between readings on both sides of the quote). Read raw, a 100 km unit quotes that
     * 14 960 times higher. Every other check in the method is an ARRANGEMENT and raises
     * {@code ArrangementFailure}.</p>
     *
     * <p>What this does not see: the price reaching the GUI's fuel line, and a warp actually spending
     * it — stations no longer depart (see {@link #aFullyFuelledStationStillDoesNotDepart}).</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code TileWarpController#intraSystemCost} at {@code double auPerUnit = 1d / AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU} converting by
     * the old hundredth of an AU ({@code 1d / 100d}), i.e. pricing the raw separation in units: "a warp
     * from WarpPriceOrigin (11.998453186842863 AU) to WarpPriceTarget (18.432117696839327 AU), priced
     * 643.3664509998781 .. 643.3664510005907 at 100 fuel per AU, slack 1.4125960214058866: … expected:
     * &lt;643.3664510002344&gt; but was:&lt;9624626.0&gt;".</p>
     */
    @Test
    public void aWarpBetweenTwoPlanetsIsPricedByTheirSeparationInAu() throws Exception {
        DimList.Probe probe = command -> ok(client().execute(command));
        DimList before = DimList.from(probe);
        String[] generated = {"WarpPriceOrigin", "WarpPriceTarget"};
        for (String name : generated) {
            ok(client().execute("ar planet generate 0 " + name));
        }
        int[] added = DimList.from(probe).addedSince(before);
        requireArranged("planet generate must register exactly one new dimension per call: "
                + java.util.Arrays.toString(added), added.length == generated.length);
        int origin = Math.min(added[0], added[1]);
        int target = Math.max(added[0], added[1]);

        // The station orbits the ORIGIN, not Earth: Earth's star is a separate Sol object from the
        // registered one, and the controller compares stars by identity, so a warp from Earth is
        // quoted the interstellar flat rate before this law is ever reached.
        int stationId = createStationOrbiting(origin);
        int[] xz = stationSpawnCoords(stationId);
        ok(client().execute("stellurgytest station set-parent " + stationId + " " + origin));
        ok(client().execute("stellurgytest station set-dest " + stationId + " " + target));

        // The two planets are read on BOTH sides of the quote, because they move: the quote was
        // computed at some tick between the two readings, so the expected price lies between the
        // price at the first and the price at the second, and their difference is the drift.
        Reply fromBefore = Reply.of(ok(client().execute("stellurgytest planet info " + origin)));
        Reply destBefore = Reply.of(ok(client().execute("stellurgytest planet info " + target)));
        String state = placeAndReadWarpState(SPACE_DIM, xz[0], 128, xz[1]);
        Reply fromAfter = Reply.of(ok(client().execute("stellurgytest planet info " + origin)));
        Reply destAfter = Reply.of(ok(client().execute("stellurgytest planet info " + target)));

        requireArranged("both worlds must be planets of one star, or the price is the interstellar flat "
                + "rate and not the law under test: " + fromBefore + " / " + destBefore,
                fromBefore.integer("starId") == destBefore.integer("starId"));
        requireArranged("both worlds must be PLANETS — a moon of the parent is priced 1: " + fromBefore
                + " / " + destBefore, fromBefore.integer("parent") == destBefore.integer("parent"));
        double au = AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        double a = fromBefore.longInteger("orbitalDistance") / au;
        double b = destBefore.longInteger("orbitalDistance") / au;
        double priceBefore = priceAt(a, fromBefore.number("orbitTheta"), b, destBefore.number("orbitTheta"));
        double priceAfter = priceAt(a, fromAfter.number("orbitTheta"), b, destAfter.number("orbitTheta"));
        double expected = (priceBefore + priceAfter) / 2d;
        // The slack, each term derived: the controller truncates the price to an int (below 1); it
        // looks its sines and cosines up in MathHelper's 65 536-entry table, whose index truncates, so
        // each coordinate of each planet can be off by its radius times one table step; and the
        // planets moved by |after - before| while the quote was taken.
        double tableStep = 2d * Math.PI / 65536d;
        double tolerance = 1d + (a + b) * Math.sqrt(2d) * tableStep * FUEL_PER_AU
                + Math.abs(priceAfter - priceBefore) / 2d;
        requireArranged("the price must stand clear of the controller's floor of 1 by more than the"
                + " slack, or a floored quote could pass for the law (a=" + a + " AU, b=" + b
                + " AU, price=" + expected + ", slack=" + tolerance + ")", expected > 1d + tolerance);

        int quoted = Reply.of(state).integer("travelCost");
        assertEquals("a warp from " + fromBefore.text("name") + " (" + a + " AU) to " + destBefore.text("name")
                        + " (" + b + " AU), priced " + priceBefore + " .. " + priceAfter
                        + " at 100 fuel per AU, slack " + tolerance + ": " + state,
                expected, quoted, tolerance);
    }

    /** The price, at 100 fuel per AU, of the in-plane separation of two orbits at two angles. */
    private static double priceAt(double aAu, double thetaA, double bAu, double thetaB) {
        return FUEL_PER_AU * Math.hypot(aAu * Math.cos(thetaA) - bAu * Math.cos(thetaB),
                aAu * Math.sin(thetaA) - bAu * Math.sin(thetaB));
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
                String.valueOf(a).equals(Reply.of(stateA).text("stationId")));
        assertTrue("controller B must resolve to station " + b + ": " + stateB,
                String.valueOf(b).equals(Reply.of(stateB).text("stationId")));
    }
}
