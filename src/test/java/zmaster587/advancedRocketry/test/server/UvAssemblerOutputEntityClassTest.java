package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * output entity-class delta between the two assemblers.
 *
 * <p>The two assemblers spawn different entity types from
 * {@code assembleRocket()}:</p>
 *
 * <ul>
 *   <li>{@link zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine#assembleRocket}
 *       &rarr; {@code new EntityRocket(...)} (ascending, crewed, orbit-capable).</li>
 *   <li>{@link zmaster587.advancedRocketry.tile.TileUnmannedVehicleAssembler#assembleRocket}
 *       &rarr; {@code new EntityStationDeployedRocket(...)} (descending, station-
 *       deployed, cargo-only).</li>
 * </ul>
 *
 * <p>Pinning the entity-class delta is the most player-visible UV contract:
 * the spawned entity's behaviour (initial launch direction, flight model,
 * passenger eligibility surface, completion path) is entirely determined by
 * which subclass instance the assembler creates. A regression that swaps
 * the {@code new} expressions — or that consolidates the two assemblers
 * onto a single {@code assembleRocket} — would either disable UV
 * altogether or break the rocket-assembler's crewed launch path.</p>
 *
 * <p>Test uses the new {@code /artest fixture uv-rocket} probe (which
 * builds a minimal UV-compatible geometry) and the existing
 * {@code /artest fixture rocket simple} probe in two distinct positions
 * (same dim, X-isolated). Both assemble paths run through
 * {@code /artest rocket assemble} which is polymorphic on the controller
 * tile's class.</p>
 */
public class UvAssemblerOutputEntityClassTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";

    /** Rocket-assembler fixture at x=5500; UV-assembler fixture at x=5700.
     *  Far enough apart to avoid scan-volume overlap (rocket bb ~6 wide × 8
     *  tall; UV bb 5×6×4). */
    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 5500;
    private static final int CX_ROCKET = 5500;
    private static final int CX_UV     = 5700;

    @Test
    public void rocketAssemblerProducesEntityRocketNotStationDeployed() throws Exception {
        // FIRST link, ASSERTING where the pair it replaces DUG — and the comment that stood here
        // said out loud what it was doing: "the existing rocket fixture's buildAndAssemble helper
        // does this; replicate inline because we don't want that helper's package coupling". The
        // copy came over and the reason stayed behind, which is how the pit reached two dozen
        // files. There is a shared builder now and no package to couple to.
        String assemble = zmaster587.advancedRocketry.test.RocketFixture.assembleAt(
                zmaster587.advancedRocketry.test.FixtureSite.openAir(0, CX_ROCKET, CZ),
                cmd -> exec(cmd), "simple", 2, 10,
                "the craft whose assembled entity class this scenario reads");
        assertTrue("rocket assemble must succeed: " + assemble,
                Reply.of(assemble).ok());

        int entityId = lastRocketId();
        // The reader REFUSES a report with no entityClass, which is what the null check asserted.
        String entityClass = RocketInfo.byId(WorldCommandFixtures::exec, entityId).entityClass;
        assertTrue("rocket assembler must spawn EntityRocket "
                        + "(not EntityStationDeployedRocket); got " + entityClass,
                entityClass.endsWith(".EntityRocket"));
        assertFalse("rocket assembler must NOT collapse to UV's output class; got "
                        + entityClass,
                entityClass.contains("StationDeployedRocket"));
    }

    @Test
    public void uvAssemblerProducesEntityStationDeployedRocket() throws Exception {
        String fixture = exec("artest fixture uv-rocket 0 " + CX_UV + " " + CY + " " + CZ);
        assertTrue("uv-rocket fixture must build: " + fixture,
                Reply.of(fixture).ok());
        int[] builder = parseBuilder(fixture);

        String assemble = exec("artest rocket assemble 0 " + builder[0] + " "
                + builder[1] + " " + builder[2]);
        assertTrue("UV assemble must succeed: " + assemble,
                Reply.of(assemble).ok());

        int entityId = lastRocketId();
        String entityClass = RocketInfo.byId(WorldCommandFixtures::exec, entityId).entityClass;
        assertTrue("UV assembler must spawn EntityStationDeployedRocket; got "
                        + entityClass,
                entityClass.endsWith(".EntityStationDeployedRocket"));
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static int[] parseBuilder(String fixture) {
        int[] m = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, m != null);
        return new int[]{
                m[0],
                m[1],
                m[2]};
    }

    private static int lastRocketId() throws Exception {
        String list = exec("artest rocket list 0");
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket ids in list: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }
}
