package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * IMiningDrill stat aggregation during rocket assembly.
 *
 * <p>{@link zmaster587.advancedRocketry.block.BlockMiningDrill} is a
 * cargo-component block (no TileEntity, no tick) consumed by the rocket
 * assembler's scan loop. Both {@code TileRocketAssemblingMachine.scanRocket}
 * (line 394) and the production-side {@code StorageChunk.recalculateStats}
 * (line 230) walk the storage chunk, sum every {@code
 * IMiningDrill.getMiningSpeed(world, pos)}, and stash the total in
 * {@code stats.setDrillingPower(sum)}.</p>
 *
 * <p>The stat then feeds {@link
 * zmaster587.advancedRocketry.entity.EntityRocket#getMissionFromInfrastructure}
 * (line 1434) and {@link
 * zmaster587.advancedRocketry.mission.MissionOreMining} — a non-zero
 * drillingPower is the player-visible "this rocket can mine ore" flag, and
 * the magnitude shapes the mission's duration formula.</p>
 *
 * <p>Contract pinned: a rocket assembled with one
 * {@code advancedrocketry:drill} block in its cargo column shows
 * {@code drillingPower > 0} on the resulting EntityRocket's StatsRocket;
 * a rocket assembled from the same fixture WITHOUT the drill block shows
 * {@code drillingPower = 0}. Both polarities pinned in one test so the
 * delta isolates the IMiningDrill scan branch.</p>
 *
 * <p>Rejected sub-pins: exact drillingPower magnitude (= 0.02f for one
 * sky-exposed drill) is an impl detail — the contract is the polarity
 * (zero vs positive). The mission-duration formula in
 * {@code EntityRocket} is impl-side magnitude algebra, not a separate
 * contract here.</p>
 */
public class RocketAssemblerMiningDrillStatTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    /** drillingPower is serialised as a float — accept "drillingPower":0.0,
     *  "drillingPower":0.02, etc. */
    private static final String DRILLING_POWER = "drillingPower";

    @Test
    public void rocketWithMiningDrillBlockAccumulatesDrillingPower() throws Exception {
        // Baseline — same fixture geometry minus the drill block. Pin
        // drillingPower == 0 so the with-drill assertion below isn't
        // attributable to some other latent stat source on the chassis.
        int baselineId = buildAndAssemble(FixtureSite.openAir(0, 1500, 500), "simple");
        String baselineInfo = String.join("\n",
                client().execute("artest rocket info " + baselineId));
        double baselineDp = extractDouble(baselineInfo, DRILLING_POWER);
        assertEquals("simple fixture must produce drillingPower=0: " + baselineInfo,
                0.0, baselineDp, 0.0);

        // With drill — should flip to > 0.
        int withDrillId = buildAndAssemble(FixtureSite.openAir(0, 1600, 500), "with-mining-drill");
        String drillInfo = String.join("\n",
                client().execute("artest rocket info " + withDrillId));
        double drillDp = extractDouble(drillInfo, DRILLING_POWER);
        assertTrue("with-mining-drill fixture must produce drillingPower > 0: "
                        + drillInfo, drillDp > 0.0);
    }

    /** Mirror of RocketAssemblySmokeTest#buildAndAssemble — warmup chunks,
     *  pre-clear the bbCache volume with air, run fixture + assemble,
     *  return the spawned entity id. */
    private int buildAndAssemble(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        String warmup = String.join("\n", client().execute(
                "artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + warmup, warmup.contains("\"ok\":true"));

        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");

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
        java.util.List<RocketList.Entry> built = RocketList.of(rocketList);
        assertTrue("rocket list yielded no ids after assemble: " + rocketList, !built.isEmpty());
        int lastId = built.isEmpty() ? -1 : built.get(built.size() - 1).id;
        return lastId;
    }

    private static double extractDouble(String haystack, String field) {
        double value = Reply.of(haystack).number(field);
        assertTrue("field `" + field + "` not found in: " + haystack, !Double.isNaN(value));
        return value;
    }
}
