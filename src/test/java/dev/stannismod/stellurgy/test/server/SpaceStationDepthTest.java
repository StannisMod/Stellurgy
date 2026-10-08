package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.StationInfo;
import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Space station registry and fuel:
 *   - create registers a station that list and info reflect
 *   - the centre grid cell resolves to no station
 *   - multiple stations coexisting in the same orbit have distinct ids
 *   - fuel set / add / use are accounted (respect max capacity for add,
 *     clamp at zero for use)
 *   - fuelAmount survives across a station-info round trip
 */
public class SpaceStationDepthTest extends AbstractSharedServerTest {

    private static final String ID_PATTERN = "id";
    private static final String AFTER_PATTERN = "after";
    private static final String MAX_PATTERN = "max";
    private static final String RETURNED_PATTERN = "returned";

    private int createStation(int orbitingDim) throws Exception {
        String resp = String.join("\n", client().execute("stellurgytest station create " + orbitingDim));
        assertTrue("station create failed: " + resp, Reply.of(resp).ok());
        Reply mReply = Reply.of(resp);
        assertTrue("could not parse station id from create response: " + resp, mReply.has(ID_PATTERN));
        return Integer.parseInt(mReply.text(ID_PATTERN));
    }

    private static int parseGroup(String field, String resp, String label) {
        Reply reply = Reply.of(resp);
        assertTrue("could not parse " + label + ": " + resp, reply.has(field));
        return reply.integer(field);
    }

    /**
     * Pins INV-STN-02 (distinct stations in one orbit get distinct ids).
     * Pins INV-STN-22 (a new station registers an object with a unique id that appears in the manager list).
     */
    @Test
    public void multipleStationsCoexistWithDistinctIds() throws Exception {
        int a = createStation(0);
        int b = createStation(0);
        int c = createStation(0);
        assertNotEquals("station ids must be unique within the same orbit", a, b);
        assertNotEquals(b, c);
        assertNotEquals(a, c);

        String list = String.join("\n", client().execute("stellurgytest station list"));
        Reply.of(list).element("stations", "id", String.valueOf(a));
        Reply.of(list).element("stations", "id", String.valueOf(b));
        Reply.of(list).element("stations", "id", String.valueOf(c));
    }

    @Test
    public void fuelSetUpdatesPersistsAndIsObservableViaInfo() throws Exception {
        int id = createStation(0);

        String setResp = String.join("\n",
                client().execute("stellurgytest station fuel " + id + " set 500"));
        int max = parseGroup(MAX_PATTERN, setResp, "max");
        int after = parseGroup(AFTER_PATTERN, setResp, "after");
        // setFuelAmount clamps to [0, max]; if max < 500 the after value will
        // be max, not 500. Assert the relationship rather than a literal.
        int expected = Math.min(500, max);
        assertEquals("fuel set did not produce expected after value", expected, after);

        StationInfo info = station(id);
        assertEquals("info must reflect the fuel amount we just set: " + info.raw(),
                expected, info.fuelAmount());
    }

    /** Pins INV-STN-04 (addFuel clamps at MAX_FUEL and returns the amount actually added). */
    @Test
    public void fuelAddRespectsMaxCapacity() throws Exception {
        int id = createStation(0);
        // Drain to zero first so we have a known baseline.
        client().execute("stellurgytest station fuel " + id + " set 0");

        // SpaceStationObject.addFuel semantics: returns the amount actually
        // consumed (= inserted) AFTER the clamp to MAX_FUEL. Overshoot is
        // dropped silently. Surface this contract explicitly so the
        // "returned == clamp room" relationship is pinned.
        String addResp = String.join("\n",
                client().execute("stellurgytest station fuel " + id + " add 999999"));
        int max = parseGroup(MAX_PATTERN, addResp, "max");
        int after = parseGroup(AFTER_PATTERN, addResp, "after");
        int returned = parseGroup(RETURNED_PATTERN, addResp, "returned");

        assertEquals("after-add fuel must clamp at max", max, after);
        assertEquals("addFuel must return the amount actually added (= clamp room)",
                max, returned);
    }

    /** Pins INV-STN-03 (useFuel consumes nothing when the stock is short and drains exactly otherwise). */
    @Test
    public void fuelUseAllOrNothingWhenInsufficient() throws Exception {
        int id = createStation(0);
        client().execute("stellurgytest station fuel " + id + " set 100");

        // SpaceStationObject.useFuel semantics: if amt > current, it returns
        // 0 WITHOUT consuming anything. Pin this contract — it's
        // non-obvious and a "convenience" rewrite that clamps to current
        // available fuel would silently change rocket-launch fuel maths.
        String useResp = String.join("\n",
                client().execute("stellurgytest station fuel " + id + " use 999999"));
        int after = parseGroup(AFTER_PATTERN, useResp, "after");
        int returned = parseGroup(RETURNED_PATTERN, useResp, "returned");

        assertEquals("useFuel on insufficient stock must not consume anything",
                100, after);
        assertEquals("useFuel on insufficient stock must return 0",
                0, returned);
    }

    /** Pins INV-STN-03 (useFuel consumes nothing when the stock is short and drains exactly otherwise). */
    @Test
    public void fuelUseExactAmountDrains() throws Exception {
        int id = createStation(0);
        client().execute("stellurgytest station fuel " + id + " set 100");

        String useResp = String.join("\n",
                client().execute("stellurgytest station fuel " + id + " use 60"));
        int after = parseGroup(AFTER_PATTERN, useResp, "after");
        int returned = parseGroup(RETURNED_PATTERN, useResp, "returned");

        assertEquals("useFuel(60) on 100 stock must leave 40", 40, after);
        assertEquals("useFuel(60) must return 60 consumed", 60, returned);

        StationInfo info = station(id);
        assertEquals("info must reflect the partial drain: " + info.raw(), 40, info.fuelAmount());
    }

    /**
     * Create registers a real {@link dev.stannismod.stellurgy.stations.SpaceStationObject}: its id
     * was not in the list before and is after, and its info reports the orbit it was created for and
     * an empty tank. Read as a DELTA — sibling scenarios on this server create stations too.
     * Pins INV-STN-05 (a freshly created station holds no fuel and reports the planet it orbits).
     * Pins INV-STN-22 (a new station registers an object with a unique id that appears in the manager list).
     */
    @Test
    public void stationCreateRegistersAndPersistsForList() throws Exception {
        String before = String.join("\n", client().execute("stellurgytest station list"));
        int id = createStation(0);
        assertFalse("the new station's id " + id + " was already listed before create: " + before,
                Reply.of("stellurgytest station list", before).holdsElement("stations", "id", String.valueOf(id)));

        String listAfter = String.join("\n", client().execute("stellurgytest station list"));
        Reply.of(listAfter).element("stations", "id", String.valueOf(id));

        // Read as NUMBERS: the substring form was a prefix, so `"orbitingPlanetId":0` was also
        // satisfied by a station orbiting dim 9701 and `"fuelAmount":0` by one holding 1000.
        StationInfo info = station(id);
        assertEquals("station info wrong orbitingPlanetId: " + info.raw(), 0, info.orbitingPlanetId);
        assertEquals("station info wrong default fuelAmount: " + info.raw(), 0, info.fuelAmount());
    }

    /**
     * The reverse index's radius-0 case in {@code SpaceObjectManager.getSpaceStationFromBlockCoords}.
     *
     * <p>The spiral index formula {@code (2*radius-1)^2 + x + radius} evaluates to {@code 1} at
     * {@code radius == 0}, so the centre grid cell (0,0) collided with grid (-1,-1) on spiral index 1
     * and a position in the central inter-station void falsely resolved to station 1. Station id 0
     * is never allocated ({@code getNextStationId} starts at 1), so the central cell must map to no
     * station. Station 1 exists whatever order this class runs in: this scenario creates a station
     * itself, and ids start at 1.</p>
     */
    @Test
    public void centreGridCellResolvesToNoStationNotFalselyStationOne() throws Exception {
        int stationId = createStation(0);
        StationInfo info = station(stationId);

        // Control: the station's own spawn resolves back to it — the reverse map still finds real
        // on-station positions after the radius-0 fix.
        String atSpawn = String.join("\n", client().execute(
                "stellurgytest station at " + info.spawnX() + " " + info.spawnY() + " " + info.spawnZ()));
        assertTrue("control: the station spawn must resolve to its own station id " + stationId
                        + " (spawn=" + info.spawnX() + "," + info.spawnZ() + "): " + atSpawn,
                String.valueOf(stationId).equals(Reply.of(atSpawn).text("stationAtPos")));

        // (100,·,100) reverse-maps to grid (0,0).
        String atCentre = String.join("\n", client().execute("stellurgytest station at 100 64 100"));
        assertTrue("the central grid cell must resolve to no station (radius-0 index fix) — it "
                        + "collided with grid (-1,-1) on index 1 = station 1 via (2*0-1)^2. Got: " + atCentre,
                // `has` answers false for an ABSENT field and for one whose value is JSON null.
                !Reply.of("stellurgytest station at", atCentre).has("stationAtPos"));
    }

    /** What the server says about one station. */
    private StationInfo station(int stationId) throws Exception {
        return StationInfo.byId(cmd -> String.join("\n", client().execute(cmd)), stationId);
    }
}
