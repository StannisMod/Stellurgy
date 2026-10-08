package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.RocketInfo;
import dev.stannismod.stellurgy.test.RocketList;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.MachineInfo;
import dev.stannismod.stellurgy.test.Plot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two assemblers diverge: distinct tile classes, and distinct entity classes out of
 * {@code assembleRocket()}.
 *
 * <p>The two assemblers spawn different entity types from
 * {@code assembleRocket()}:</p>
 *
 * <ul>
 *   <li>{@link dev.stannismod.stellurgy.tile.TileRocketAssemblingMachine#assembleRocket}
 *       &rarr; {@code new EntityRocket(...)} (ascending, crewed, orbit-capable).</li>
 *   <li>{@link dev.stannismod.stellurgy.tile.TileUnmannedVehicleAssembler#assembleRocket}
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
 * <p>Test uses the new {@code /stellurgytest fixture uv-rocket} probe (which
 * builds a minimal UV-compatible geometry) and the existing
 * {@code /stellurgytest fixture rocket simple} probe in two distinct positions
 * (same dim, X-isolated). Both assemble paths run through
 * {@code /stellurgytest rocket assemble} which is polymorphic on the controller
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

    /** Pins INV-RASM-04 (The pad assembler spawns EntityRocket (never EntityStationDeployedRocket)). */
    @Test
    public void rocketAssemblerProducesEntityRocketNotStationDeployed() throws Exception {
        // FIRST link, ASSERTING where the pair it replaces DUG — and the comment that stood here
        // said out loud what it was doing: "the existing rocket fixture's buildAndAssemble helper
        // does this; replicate inline because we don't want that helper's package coupling". The
        // copy came over and the reason stayed behind, which is how the pit reached two dozen
        // files. There is a shared builder now and no package to couple to.
        String assemble = dev.stannismod.stellurgy.test.RocketFixture.assembleAt(
                dev.stannismod.stellurgy.test.FixtureSite.openAir(0, CX_ROCKET, CZ),
                cmd -> exec(cmd), "simple", 2, 10,
                "the craft whose assembled entity class this scenario reads");
        assertTrue("rocket assemble must succeed: " + assemble,
                Reply.of(assemble).ok());

        int entityId = lastRocketId();
        // The reader REFUSES a report with no entityClass, which is what the null check asserted.
        String entityClass = RocketInfo.byId(this::exec, entityId).entityClass;
        assertTrue("rocket assembler must spawn EntityRocket "
                        + "(not EntityStationDeployedRocket); got " + entityClass,
                entityClass.endsWith(".EntityRocket"));
        assertFalse("rocket assembler must NOT collapse to UV's output class; got "
                        + entityClass,
                entityClass.contains("StationDeployedRocket"));
    }

    /** Pins INV-RASM-04 (The pad assembler spawns EntityRocket (never EntityStationDeployedRocket)). */
    @Test
    public void uvAssemblerProducesEntityStationDeployedRocket() throws Exception {
        String fixture = exec("stellurgytest fixture uv-rocket 0 " + CX_UV + " " + CY + " " + CZ);
        assertTrue("uv-rocket fixture must build: " + fixture,
                Reply.of(fixture).ok());
        int[] builder = parseBuilder(fixture);

        String assemble = exec("stellurgytest rocket assemble 0 " + builder[0] + " "
                + builder[1] + " " + builder[2]);
        assertTrue("UV assemble must succeed: " + assemble,
                Reply.of(assemble).ok());

        int entityId = lastRocketId();
        String entityClass = RocketInfo.byId(this::exec, entityId).entityClass;
        assertTrue("UV assembler must spawn EntityStationDeployedRocket; got "
                        + entityClass,
                entityClass.endsWith(".EntityStationDeployedRocket"));
    }

    /**
     * The two assembler blocks register DIFFERENT tile classes at one probe surface, and placing the
     * UV one beside the rocket one leaves the rocket one what it was. A change that consolidates
     * them onto one class fires this.
     */
    @Test
    public void rocketBuilderAndDeployableRocketBuilderReportDistinctTileClasses() throws Exception {
        FixtureSite rocket = plot().siteAt(Plot.FIXTURE_INSET, Plot.FIXTURE_INSET);
        FixtureSite uv = plot().siteAt(Plot.FIXTURE_INSET + 10, Plot.FIXTURE_INSET);
        String rocketAt = " 0 " + rocket.x + " " + rocket.y + " " + rocket.z;
        String uvAt = " 0 " + uv.x + " " + uv.y + " " + uv.z;

        String placeRocket = exec("stellurgytest place" + rocketAt + " stellurgy:rocketBuilder");
        assertTrue("rocketBuilder place failed: " + placeRocket, Reply.of(placeRocket).bool("placed"));
        String rocketInfo = exec("stellurgytest machine info" + rocketAt);
        assertEquals("rocketBuilder must report TileRocketAssemblingMachine: " + rocketInfo,
                "TileRocketAssemblingMachine", MachineInfo.of(rocketInfo).tileSimpleName());

        String placeUv = exec("stellurgytest place" + uvAt + " stellurgy:deployableRocketBuilder");
        assertTrue("deployableRocketBuilder place failed: " + placeUv, Reply.of(placeUv).bool("placed"));
        String uvInfo = exec("stellurgytest machine info" + uvAt);
        assertEquals("deployableRocketBuilder must report TileUnmannedVehicleAssembler: " + uvInfo,
                "TileUnmannedVehicleAssembler", MachineInfo.of(uvInfo).tileSimpleName());

        // `stellurgytest machine info` reports a flat object with no `ok` field, so the reader is asked
        // for the field by name and refuses a reply without it — two missing classes would be EQUAL.
        String rocketClass = Reply.of("stellurgytest machine info", rocketInfo).text("tileClass");
        String uvClass = Reply.of("stellurgytest machine info", uvInfo).text("tileClass");
        assertNotEquals("rocket assembler and UV assembler must report different "
                        + "tile classes; rocketInfo=" + rocketInfo + " uvInfo=" + uvInfo,
                rocketClass, uvClass);

        String rocketRefetch = exec("stellurgytest machine info" + rocketAt);
        assertEquals("rocketBuilder must remain itself after UV placement: " + rocketRefetch,
                "TileRocketAssemblingMachine", MachineInfo.of(rocketRefetch).tileSimpleName());
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

    private int lastRocketId() throws Exception {
        String list = exec("stellurgytest rocket list 0");
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket ids in list: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }
}
