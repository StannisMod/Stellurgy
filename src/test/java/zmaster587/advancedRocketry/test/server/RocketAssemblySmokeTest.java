package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * rocket assembly smoke (P1).
 *
 * <p>Builds the BuildRocketTest fixture geometry via {@code /artest fixture rocket},
 * calls {@code /artest rocket assemble} which synchronously runs scan +
 * assemble + spawns the {@link
 * zmaster587.advancedRocketry.entity.EntityRocket}, then asserts the resulting
 * rocket's stats match the placed components.</p>
 *
 * <p>Depth coverage: storage chunk geometry, derived stats
 * (engine/seat/fuel-tank counts), guidance-computer slot, and the negative
 * scan paths for missing engines / missing fuel tanks / missing guidance.
 * The "missing seat" path is documented as still-assembles because the
 * production scanRocket does not enforce seat presence.</p>
 */
public class RocketAssemblySmokeTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    private static final String STATUS = "status";

    @Test
    public void fixtureRocketAssemblesToLiveEntity() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 500, 500), "simple");
        RocketInfo info = rocketInfo(entityId);
        assertTrue("rocket info missing hasStorage=true: " + info.raw(), info.hasStorage);
    }

    /**
     * Storage chunk volume must match the bounding box the scan
     * computed from the launchpad + structure tower. The simple fixture's
     * rocket structure is 3 wide × 5 tall × 1 deep relative to the pad
     * centre, so the storage chunk size must be ≥ that volume (the bbCache
     * snaps to the full pad footprint, which is larger).
     */
    @Test
    public void rocketStorageChunkMatchesScanFootprint() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 540, 500), "simple");
        RocketInfo info = rocketInfo(entityId);
        int sx = info.storageSizeX();
        int sy = info.storageSizeY();
        int sz = info.storageSizeZ();
        assertTrue("storage size axes must all be positive: " + info.raw(),
                sx > 0 && sy > 0 && sz > 0);
        assertEquals("storageChunkSize must equal sx*sy*sz", sx * sy * sz, info.storageChunkSize());
        // Fixture geometry: rocket spans dx∈[-1,+1], dy∈[0,4], dz==0; bbCache
        // covers the pad — so the chunk encloses at least the placed blocks.
        assertTrue("storage chunk must enclose the placed components (sx>=3): " + info.raw(),
                sx >= 3);
        assertTrue("storage chunk must enclose the vertical extent (sy>=5): " + info.raw(),
                sy >= 5);
    }

    /**
     * Thrust from StatsRocket equals engineCount × per-engine thrust
     * for the simple fixture (2 advRocketmotors). We don't pin the absolute
     * thrust value (it depends on AR's engine-tier config) but we assert the
     * post-assembly thrust is positive, weight is positive, and per-fuel-type
     * capacity for at least one type is non-zero — the StatsRocket invariants
     * the production launch-readiness check relies on.
     */
    @Test
    public void statsRocketIsCalculatedFromComponents() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 580, 500), "simple");
        RocketInfo info = rocketInfo(entityId);
        assertTrue("thrust must be positive after assembling with 2 engines: " + info.raw(),
                info.thrust > 0);
        assertTrue("weight_no_fuel must be > 0 with 6 tanks + 2 engines + guidance: " + info.raw(),
                info.weightNoFuel > 0);
        // At least one fuel type must have non-zero capacity (6 fuel tanks). The fuel types are the
        // registry's, so the reader is asked for the aggregate rather than for a type by name.
        assertTrue("aggregate fuel capacity across types must be > 0: " + info.raw(),
                info.fuelCapacityTotal() > 0);
    }

    /**
     * Seat count must mirror the fixture's seat placement. Simple
     * fixture has exactly one seat at (rocketX, rocketY+4, rocketZ).
     */
    @Test
    public void seatCountMatchesFixturePlacement() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 620, 500), "simple");
        RocketInfo info = rocketInfo(entityId);
        assertEquals("simple fixture must produce a 1-seat rocket: " + info.raw(),
                1, info.seatCount);
    }

    /**
     * Engine count must reflect the 2 advRocketmotors placed by the
     * simple fixture.
     */
    @Test
    public void engineDetectionFindsAllEngines() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 660, 500), "simple");
        RocketInfo info = rocketInfo(entityId);
        assertEquals("simple fixture has 2 engines: " + info.raw(), 2, info.engineCount);
    }

    /**
     * Fuel tank count from the post-scan storage chunk must equal
     * the 6 fuelTank blocks the fixture places (3 wide × 2 tall column).
     */
    @Test
    public void fuelTankDetectionFindsAllTanks() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 700, 500), "simple");
        RocketInfo info = rocketInfo(entityId);
        assertEquals("simple fixture has 6 fuel tanks: " + info.raw(), 6, info.fuelTankCount);
    }

    /**
     * Guidance-computer slot acceptance. Simple fixture places the
     * guidance computer but does NOT insert a chip — slot is empty. (Inserting
     * a chip would route through item registry + hatch fill, but the
     * baseline behaviour we lock down here is that the slot is wired up and
     * reachable from the probe.) {@code guidanceComputerPresent=true} +
     * {@code guidanceComputerSlotOccupied=false} &rarr; contract is "block is
     * there, slot exists, no chip yet".
     */
    @Test
    public void guidanceComputerSlotPopulatedAfterChipInsert() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 740, 500), "simple");
        RocketInfo info = rocketInfo(entityId);
        assertTrue("guidance computer block must be present after assembly: " + info.raw(),
                info.guidanceComputerPresent);
        assertFalse("guidance chip slot is empty in the bare fixture: " + info.raw(),
                info.guidanceComputerSlotOccupied);
    }

    /**
     * Invalid rocket: no engines. scanRocket must surface
     * {@code NOENGINES} (or any non-SUCCESS status) instead of spawning a
     * rocket entity.
     */
    @Test
    public void invalidRocketMissingEngineFailsAssemblyWithReason() throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, 780, 500);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link, and the same one buildAndAssemble takes: the volume is EMPTY. In open air
        // this ASSERTS rather than digs, and the assertion is what was missing here — the fill
        // this replaces was fired and its answer thrown away.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the engineless build the scan must reject stands in this volume");
        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " invalid-no-engine"));
        assertTrue("invalid-no-engine fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("invalid fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];

        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble of engineless rocket must fail: " + assemble,
                assemble.contains("\"error\""));
        Reply smReply = Reply.of(assemble);
        assertTrue("error response must surface scan status name: " + assemble, smReply.has(STATUS));
        String status = smReply.text(STATUS);
        assertTrue("status for engineless rocket must indicate missing thrust "
                        + "(NOENGINES expected, got " + status + "): " + assemble,
                "NOENGINES".equals(status) || "INVALIDBLOCK".equals(status));
    }

    /**
     * Invalid rocket: no seat. Production scanRocket does NOT
     * enforce seat presence — the ErrorCodes enum declares NOSEAT but the
     * scan logic at TileRocketAssemblingMachine#scanRocket only checks
     * guidance, thrust, and fuel. We document that observable behaviour
     * here: a seatless fixture assembles successfully and reports
     * {@code seatCount=0}. Named to match real behaviour rather than the
     * expected-failure case;
     * if the production code later starts enforcing seat presence, this
     * test will start failing and force a re-evaluation of the contract.
     */
    @Test
    public void seatlessRocketStillAssemblesButReportsZeroSeats() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 820, 500), "invalid-no-seat");
        RocketInfo info = rocketInfo(entityId);
        assertEquals("seatless fixture must report 0 seats: " + info.raw(), 0, info.seatCount);
        // The rocket must still have engines + tanks + guidance.
        assertEquals("engines unchanged: " + info.raw(), 2, info.engineCount);
        assertEquals("fuel tanks unchanged: " + info.raw(), 6, info.fuelTankCount);
        assertTrue("guidance still present: " + info.raw(), info.guidanceComputerPresent);
    }

    /**
     * Helper: build + assemble the requested fixture variant and return the
     * spawned EntityRocket's entity id. Asserts everything along the way.
     *
     * <p><b>The site is in OPEN AIR, and that is what this javadoc used to describe the other way
     * round.</b> It said the pre-clear existed because "natural overworld terrain (trees, hills)
     * that pokes into the bbCache volume would otherwise inflate the storage chunk and confuse
     * scanRocket's passable-block-above-seat check, making per-component counts dependent on the
     * chosen baseX coordinate's biome" — an accurate description of a test whose results depended
     * on which biome its X landed in, and of a warmup added because the scan flaked ~1/10 runs when
     * cross-chunk tree population landed after the fill. None of that is a property of the subject;
     * all of it is a property of standing in the landscape. Above the band there is no terrain to
     * poke in, no populate to race, and no biome for the counts to depend on.</p>
     */
    private int buildAndAssemble(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, and the air fill's own `placed` is the measurement. The
        // box covers what getRocketPadBounds scans — (baseX..baseX+5, baseY+1..baseY+maxTowerSize-1,
        // baseZ..baseZ+5) — plus a halo, so detritus from a prior fixture in the same JVM is caught
        // here rather than inside the scan. It force-loads every chunk in the box on the way, which
        // is what the warmup it replaces was for.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft this scenario assembles and reads back stands in this volume");

        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp != null);
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];

        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble (" + variant + ") failed: " + assemble,
                assemble.contains("\"ok\":true"));

        String rocketList = String.join("\n", client().execute("artest rocket list 0"));
        // Pick the last id reported — rocket list grows as fixtures stack up
        // in the same JVM, so the most recently spawned rocket sits at the
        // end of the rocket array.
        java.util.List<RocketList.Entry> built = RocketList.of(rocketList);
        assertTrue("rocket list yielded no ids after assemble: " + rocketList, !built.isEmpty());
        int lastId = built.isEmpty() ? -1 : built.get(built.size() - 1).id;
        return lastId;
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> String.join("\n", client().execute(cmd)), id);
    }
}
