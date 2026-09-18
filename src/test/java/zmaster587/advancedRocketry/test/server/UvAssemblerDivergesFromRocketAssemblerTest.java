package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * UV assembler diverges from the rocket
 * assembler.
 *
 * <p>{@code TileUnmannedVehicleAssembler} extends
 * {@code TileRocketAssemblingMachine} and overrides several methods
 * ({@code getRocketPadBounds}, {@code scanRocket}, {@code verifyScan},
 * {@code getNeededFuel}, {@code assembleRocket}). The behavioural deltas —
 * larger {@code MAX_SIZE} (17 vs 16), scanning UP from the launcher (not DOWN
 * from a pad), and producing {@code EntityStationDeployedRocket} instead of
 * {@code EntityRocket} — are gameplay-critical: a regression that collapses
 * UV onto the rocket-builder code path would either disable UV altogether or
 * cause UV-launched rockets to behave like crewed ones.</p>
 *
 * <p>The full behavioural delta (rocket bounds, scanRocket output,
 * EntityStationDeployedRocket creation) is not directly observable through
 * the existing {@code /artest} probe surface — exposing it would require new
 * probe verbs that read into the assembler's internal scan state. This test
 * pins the strongest delta we CAN assert today: the two blocks register
 * <strong>different tile classes</strong> at the same probe surface. If a
 * future change consolidates them to the same class, this guard fires.</p>
 *
 * <p>Deeper UV-vs-rocket behavioural pinning (pad bounds, fuel requirement,
 * spawned entity type) is left as a follow-up that adds an
 * {@code /artest assembler bounds} verb.</p>
 */
public class UvAssemblerDivergesFromRocketAssemblerTest extends AbstractHeadlessServerTest {

    /**
     * Same-y coordinates, different x — both blocks placed on a single horizontal
     * row far from other test patches. Position-isolated from MachineDomainSmokeSuite
     * (x ≥ 700, peaking at 2200).
     */
    private static final int Y = FixtureSite.OPEN_AIR_Y;
    private static final int Z_ROCKET = 2600;
    private static final int Z_UV     = 2600;
    private static final int X_ROCKET = 2500;
    private static final int X_UV     = 2510;

    @Test
    public void rocketBuilderAndDeployableRocketBuilderReportDistinctTileClasses() throws Exception {
        // ─── Place the regular rocket assembler ──────────────────────────
        String placeRocket = join(client().execute(
                "artest place 0 " + X_ROCKET + " " + Y + " " + Z_ROCKET
                        + " advancedrocketry:rocketBuilder"));
        assertTrue("rocketBuilder place failed: " + placeRocket,
                Reply.of(placeRocket).bool("placed", false));

        String rocketInfo = join(client().execute(
                "artest machine info 0 " + X_ROCKET + " " + Y + " " + Z_ROCKET));
        // TileRocketAssemblingMachine is the production target — pin it.
        assertTrue("rocketBuilder must report TileRocketAssemblingMachine: " + rocketInfo,
                rocketInfo.contains("TileRocketAssemblingMachine"));
        // It must NOT report the UV class.
        assertTrue("rocketBuilder unexpectedly reported the UV class: " + rocketInfo,
                !rocketInfo.contains("TileUnmannedVehicleAssembler"));

        // ─── Place the UV / deployable rocket assembler ──────────────────
        String placeUv = join(client().execute(
                "artest place 0 " + X_UV + " " + Y + " " + Z_UV
                        + " advancedrocketry:deployableRocketBuilder"));
        assertTrue("deployableRocketBuilder place failed: " + placeUv,
                Reply.of(placeUv).bool("placed", false));

        String uvInfo = join(client().execute(
                "artest machine info 0 " + X_UV + " " + Y + " " + Z_UV));
        assertTrue("deployableRocketBuilder must report TileUnmannedVehicleAssembler: " + uvInfo,
                uvInfo.contains("TileUnmannedVehicleAssembler"));

        // ─── Class-identity pin ──────────────────────────────────────────
        // Extract the tileClass JSON values and assert they differ. A
        // regression that merges the two blocks onto a single class flips
        // this assertion.
        String rocketClass = extractTileClass(rocketInfo);
        String uvClass = extractTileClass(uvInfo);
        assertNotEquals("rocket assembler and UV assembler must report different "
                        + "tile classes; rocketInfo=" + rocketInfo + " uvInfo=" + uvInfo,
                rocketClass, uvClass);

        // Sanity: each tile class is queryable individually (i.e. each block
        // is a real, independent tile-entity — not a shared-state pun).
        String rocketRefetch = join(client().execute(
                "artest machine info 0 " + X_ROCKET + " " + Y + " " + Z_ROCKET));
        assertTrue("rocketBuilder must remain queryable after UV placement: " + rocketRefetch,
                rocketRefetch.contains("TileRocketAssemblingMachine"));
        assertTrue("rocketBuilder must NOT be mutated by UV placement: " + rocketRefetch,
                !rocketRefetch.contains("TileUnmannedVehicleAssembler"));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /**
     * The {@code tileClass} an {@code artest machine info} reply reports, refusing when it carries
     * none.
     *
     * <p>It used to slice the reply by index off the needle {@code "\"tileClass\":\""} and answer
     * {@code ""} on a miss. Two classes being "different" is this test's whole claim, and two
     * empty strings are EQUAL — so a reply that stopped carrying the field at all would have made
     * the assertion fail for a reason that has nothing to do with the two blocks, while printing
     * both replies and looking like a real verdict.</p>
     */
    private static String extractTileClass(String response) {
        // No `requireOk` here, and that is not an omission: `artest machine info` reports a flat
        // object with no `ok` field at all, so demanding one refuses every healthy reply. Measured
        // — the first version of this line did exactly that.
        return Reply.of("artest machine info", response).text("tileClass");
    }
}
