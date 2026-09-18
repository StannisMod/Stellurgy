package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.EnergyStore;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.StationInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Black-Hole-Generator powered cycle on a station
 * orbiting a black-hole star.
 *
 * <p>Production contract ({@code TileBlackHoleGenerator.isAroundBlackHole()}):
 * the BHG produces power only when ALL hold:</p>
 *
 * <ul>
 *   <li>controller sits in the space dim ({@code spaceDimId}, default -2);</li>
 *   <li>a {@code SpaceObject} (station) occupies that block coord region;</li>
 *   <li>the station's parent dim is a star ({@code planetId >=
 *       STAR_ID_OFFSET = 10000});</li>
 *   <li>that star's {@code StellarBody.isBlackHole()} is true.</li>
 * </ul>
 *
 * <p>The three test methods pin the positive path + two counter-branches.
 * Setup builds a fresh station per test orbiting the default Sol star
 * (id 0, dim id {@code STAR_ID_OFFSET + 0 = 10000}), flips its black-hole
 * flag, then sends the BHG fixture there. {@code @After} restores the
 * Sol flag and deletes the station so subsequent methods (and other
 * test classes that share the harness via {@link AbstractSharedServerTest})
 * see a pristine star registry.</p>
 *
 * <p>Production produces 500 RF/tick × {@code blackHolePowerMultiplier}
 * for {@code defaultItemTimeBlackHole} (default 500) ticks per consumed
 * item — so a single dirt block in the input hatch + ~600 force-ticks
 * is enough to assert "energy buffer grew" without pinning an exact RF
 * amount.</p>
 */
public class BlackHoleGeneratorPoweredCycleTest extends AbstractSharedServerTest {

    /** AR planet ID offset for star dims —
     *  {@link zmaster587.advancedRocketry.api.Constants#STAR_ID_OFFSET}. */
    private static final int STAR_ID_OFFSET = 10000;
    private static final int SOL_DIM = STAR_ID_OFFSET; // star id 0

    /** {@code ARConfiguration.spaceDimId} default. */
    private static final int SPACE_DIM = -2;

    /** Per-test position offset on overworld for the counter-test that
     *  builds BHG on a non-space dim. */
    private static final int OVERWORLD_DIM = 0;
    private static final int OVERWORLD_CX = 5000;
    private static final int OVERWORLD_CY = 128;
    private static final int OVERWORLD_CZ = 5000;

    private static final String STATION_ID = "id";
    private static final String CTRL_POS = "controllerPos";
    private static final String POWER_OUT_POS = "powerOutPos";
    private static final String ITEM_IN_POS = "itemInputPos";
    private static final String STAR_BLACKHOLE = "isBlackHole";

    private boolean originalSolBlackHole;
    private int stationId = -1;

    @Before
    public void snapshotAndPrepare() throws Exception {
        // Snapshot Sol's black-hole flag so we can restore in @After.
        String solInfo = exec("artest star get 0");
        Reply sol = Reply.of("artest star get", solInfo);
        assertTrue("could not read Sol's black-hole flag: " + solInfo, sol.has(STAR_BLACKHOLE));
        originalSolBlackHole = sol.bool(STAR_BLACKHOLE, false);

        // Load the space dim — BHG production checks
        // world.provider.getDimension() == spaceDimId.
        String load = exec("artest dim load " + SPACE_DIM);
        assertTrue("space dim load failed: " + load,
                Reply.of(load).bool("loaded", false) || Reply.of(load).ok());
    }

    @After
    public void restore() throws Exception {
        if (stationId != -1) {
            // Stations don't have a public "delete" probe, but harness
            // teardown reclaims them. Just unset our handle.
            stationId = -1;
        }
        // Restore Sol's flag unconditionally — even if we never flipped
        // it (e.g. the assertion before the flip threw).
        exec("artest star set-blackhole 0 " + originalSolBlackHole);
    }

    /** Happy path: station orbits a black-hole Sol, BHG is on the space
     *  dim, input hatch has an item, force-ticks ≥ defaultItemTimeBlackHole
     *  &rarr; output energy buffer accumulates a non-zero amount. */
    @Test
    public void bhgOnStationAroundBlackHoleProducesEnergy() throws Exception {
        flipSolBlackHole(true);
        int[] origin = createStationAndQuerySpawn();

        String fixture = buildFixture(SPACE_DIM, origin[0], origin[1], origin[2]);
        feedInputHatch(SPACE_DIM, fixture);
        enableMachine(SPACE_DIM, origin[0], origin[1], origin[2]);

        long outputBefore = readEnergyStored(SPACE_DIM, powerOutPosFrom(fixture));
        forceTick(SPACE_DIM, origin[0], origin[1], origin[2], 600);
        long outputAfter = readEnergyStored(SPACE_DIM, powerOutPosFrom(fixture));

        assertTrue("BHG around black-hole produced no energy"
                        + " (outputBefore=" + outputBefore + " outputAfter=" + outputAfter + ")",
                outputAfter > outputBefore);
    }

    /** Counter-test: Sol not a black hole &rarr; isAroundBlackHole() false &rarr;
     *  attemptFire skips &rarr; producePower never called &rarr; no energy. */
    @Test
    public void bhgWithoutBlackHoleStarDoesNotProduce() throws Exception {
        flipSolBlackHole(false);
        int[] origin = createStationAndQuerySpawn();

        String fixture = buildFixture(SPACE_DIM, origin[0], origin[1], origin[2]);
        feedInputHatch(SPACE_DIM, fixture);
        enableMachine(SPACE_DIM, origin[0], origin[1], origin[2]);

        long outputBefore = readEnergyStored(SPACE_DIM, powerOutPosFrom(fixture));
        forceTick(SPACE_DIM, origin[0], origin[1], origin[2], 600);
        long outputAfter = readEnergyStored(SPACE_DIM, powerOutPosFrom(fixture));

        assertEquals("BHG without black-hole star produced energy anyway"
                        + " (outputBefore=" + outputBefore + " outputAfter=" + outputAfter + ")",
                outputBefore, outputAfter);
    }

    /** Counter-test: BHG placed on overworld (dim 0, not spaceDimId) &rarr;
     *  isAroundBlackHole() short-circuits to false on the first guard
     *  ({@code world.provider.getDimension() == spaceDimId}). Pins the
     *  dim-gate even when a black-hole star exists. */
    @Test
    public void bhgOnOverworldDoesNotProduceEvenWithBlackHoleStar() throws Exception {
        flipSolBlackHole(true);

        String fixture = buildFixture(OVERWORLD_DIM, OVERWORLD_CX, OVERWORLD_CY, OVERWORLD_CZ);
        feedInputHatch(OVERWORLD_DIM, fixture);
        enableMachine(OVERWORLD_DIM, OVERWORLD_CX, OVERWORLD_CY, OVERWORLD_CZ);

        long outputBefore = readEnergyStored(OVERWORLD_DIM, powerOutPosFrom(fixture));
        forceTick(OVERWORLD_DIM, OVERWORLD_CX, OVERWORLD_CY, OVERWORLD_CZ, 600);
        long outputAfter = readEnergyStored(OVERWORLD_DIM, powerOutPosFrom(fixture));

        assertEquals("BHG on overworld produced energy anyway"
                        + " — spaceDim gate leaked through"
                        + " (outputBefore=" + outputBefore + " outputAfter=" + outputAfter + ")",
                outputBefore, outputAfter);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private void flipSolBlackHole(boolean value) throws Exception {
        String resp = exec("artest star set-blackhole 0 " + value);
        assertTrue("Sol black-hole flip failed: " + resp,
                Reply.of(resp).ok() && String.valueOf(value).equals(Reply.of(resp).text("after")));
    }

    /** Creates a station orbiting Sol (dim {@link #SOL_DIM}), returns its
     *  spawn coords as {@code [x, y, z]} in the space dim. */
    private int[] createStationAndQuerySpawn() throws Exception {
        String create = exec("artest station create " + SOL_DIM);
        assertTrue("station create failed: " + create,
                Reply.of(create).ok());
        Reply created = Reply.of("artest station create", create);
        assertTrue("no station id in create response: " + create, created.has(STATION_ID));
        stationId = created.integer(STATION_ID);

        // The reader refuses a station the manager does not hold, and its spawn accessors refuse a
        // station that has no spawn — which is what the two-field has-check stood for.
        StationInfo station = StationInfo.byId(WorldCommandFixtures::exec, stationId);
        return new int[]{station.spawnX(), 128, station.spawnZ()};
    }

    private String buildFixture(int dim, int cx, int cy, int cz) throws Exception {
        String fixture = exec("artest fixture multiblock blackhole-gen "
                + dim + " " + cx + " " + cy + " " + cz);
        assertTrue("BHG fixture build failed: " + fixture,
                Reply.of(fixture).ok());
        assertTrue("no controllerPos in fixture response: " + fixture,
                Reply.of("artest fixture multiblock", fixture).blockPos(CTRL_POS) != null);
        // Try-complete: BHG's onInventoryUpdated runs attemptFire which
        // requires isComplete; the fixture only places, doesn't call
        // attemptCompleteStructure. The first force-tick call below
        // triggers BHG.update()'s lazy completeStructure check, but we
        // also explicitly run try-complete here so the diagnostic surface
        // is clean.
        String tryComplete = exec("artest machine try-complete "
                + dim + " " + cx + " " + cy + " " + cz);
        assertTrue("BHG structure failed to complete: " + tryComplete,
                Reply.of(tryComplete).bool("isComplete", false));
        return fixture;
    }

    private void feedInputHatch(int dim, String fixture) throws Exception {
        int[] inputPos = parseTriple(fixture, ITEM_IN_POS);
        // Stuff 64 dirt blocks into slot 0; BHG.consumeItem() decrements
        // the first non-empty stack each fire. defaultItemTimeBlackHole
        // is 500 ticks per fire, so 64 items = up to 64 fires of 500
        // ticks each.
        String resp = exec("artest hatch fill " + dim + " "
                + inputPos[0] + " " + inputPos[1] + " " + inputPos[2]
                + " 0 minecraft:dirt 64 0");
        assertTrue("hatch fill failed: " + resp,
                Reply.of(resp).ok() || (Reply.of(resp).integerOr("count", Integer.MIN_VALUE) == 64));
    }

    private void enableMachine(int dim, int cx, int cy, int cz) throws Exception {
        String resp = exec("artest machine set-enabled " + dim + " "
                + cx + " " + cy + " " + cz + " true");
        assertTrue("machine set-enabled failed: " + resp,
                Reply.of(resp).bool("enabled", false));
    }

    private void forceTick(int dim, int cx, int cy, int cz, int ticks) throws Exception {
        String resp = exec("artest tile force-tick " + dim + " "
                + cx + " " + cy + " " + cz + " " + ticks);
        assertTrue("force-tick errored: " + resp, Reply.of(resp).ok());
    }

    private int[] powerOutPosFrom(String fixture) {
        return parseTriple(fixture, POWER_OUT_POS);
    }

    private long readEnergyStored(int dim, int[] pos) throws Exception {
        return EnergyStore.at(WorldCommandFixtures::exec, dim, pos[0], pos[1], pos[2])
                .requireEnergy("the plug must expose a store, or its reading is an absence")
                .stored();
    }

    private static int[] parseTriple(String src, String field) {
        int[] pos = Reply.of(src).blockPos(field);
        assertTrue("`" + field + "` is not an [x, y, z] in: " + src, pos != null);
        return pos;
    }
}
