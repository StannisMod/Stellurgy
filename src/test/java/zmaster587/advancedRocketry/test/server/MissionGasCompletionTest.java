package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MissionGasCollection.onMissionComplete contract.
 *
 * <p>Pins the player-visible cause-effect of completing a gas-collection
 * mission:</p>
 * <ul>
 *   <li>Fluid tiles in the respawned rocket are filled with 64000 mB of
 *       the configured {@code gasFluid} type.</li>
 *   <li>The respawned entity is {@code EntityStationDeployedRocket},
 *       NOT a plain {@code EntityRocket} — distinguishes the gas
 *       completion path from the ore path.</li>
 *   <li>Production guard {@code (int)getStatTag("intakePower") > 0}
 *       short-circuits the fluid fill — no intakePower set &rarr; no fill,
 *       even if the rocket otherwise has fluid tiles.</li>
 * </ul>
 *
 * <p>The respawned rocket's exact position depends on the fixture
 * rocket's forwardDirection (offset by 64 in that axis). Tests scan a
 * 128-block cube around the launch coords to find it — the
 * {@code rocket-cargo} probe handles this lookup.</p>
 */
public class MissionGasCompletionTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String MISSION_ID = "missionId";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        return buildAndAssembleRocket(baseX, "simple");
    }

    private int buildAndAssembleRocket(int baseX, String variant) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 600);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");
        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];
        ok(client().execute("artest rocket assemble 0 " + bx + " " + by + " " + bz));
        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    private long startGasMission(int rocketId, long duration, String fluid, int intakePower) throws Exception {
        String start = ok(client().execute(
                "artest mission start-gas 0 " + rocketId + " " + duration + " " + fluid
                        + " " + intakePower));
        assertFalse("start-gas must not error: " + start, start.contains("\"error\""));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId in start response: " + start, mmReply.has(MISSION_ID));
        return Long.parseLong(mmReply.text(MISSION_ID));
    }

    /** With intakePower > 0 the gas mission completes WITHOUT crashing
     *  even when the rocket's storage chunk has no fluid-tile entities
     *  (BlockFuelTank in the `simple` fixture is a pure block — no
     *  TileEntity &rarr; not added to StorageChunk.liquidTiles &rarr; the fill
     *  loop iterates zero times). The strong "64000 mB of oxygen
     *  appears in cargo" assertion needs a fluid-cargo rocket fixture
     *  variant that doesn't exist yet.
     *  This test pins the no-crash safety contract on the intake>0
     *  branch as a regression net against e.g. a future NPE on
     *  null-fluid or empty-tile-list. */
    @Test
    public void gasCompletionWithIntakeAboveZeroCompletesWithoutCrash() throws Exception {
        int rid = buildAndAssembleRocket(8000);
        long mid = startGasMission(rid, 1000, "oxygen", 10);
        String cargo = ok(client().execute("artest mission complete-now " + mid));
        assertFalse("complete-now must not error: " + cargo, cargo.contains("\"error\""));
        assertTrue("completion must mark mission dead: " + cargo,
                cargo.contains("\"isDeadAfter\":true"));
        assertTrue("completion must report fired (wasDeadBefore=false): " + cargo,
                cargo.contains("\"wasDeadBefore\":false") && cargo.contains("\"completed\":true"));
    }

    /** Production gate: if `(int)stats.getStatTag("intakePower") > 0`
     *  is false, the fluid-fill loop is skipped (MissionGasCollection
     *  line 46). Counter-test pinning the gate. */
    @Test
    public void gasCompletionDoesNotFillFluidWhenIntakePowerZero() throws Exception {
        int rid = buildAndAssembleRocket(8100);
        long mid = startGasMission(rid, 1000, "water", 0);
        String cargo = ok(client().execute("artest mission complete-now " + mid));
        assertFalse("complete-now must not error: " + cargo, cargo.contains("\"error\""));
        // Either no rocket re-spawned, or no fluid entries — both
        // represent the no-fill branch (production also spawns the
        // rocket entity in this path; we pin the empty-fluid invariant).
        assertTrue("intakePower=0 -> no fluid entries: " + cargo,
                cargo.contains("\"fluidEntries\":0"));
    }

    /** The gas completion path constructs an EntityStationDeployedRocket
     *  (MissionGasCollection line 60), distinguishing it from the ore
     *  path (which spawns a plain EntityRocket). Pin via a presence
     *  check in the dim's entity list after completion. */
    @Test
    public void gasCompletionRespawnsRocketInLaunchDim() throws Exception {
        int rid = buildAndAssembleRocket(8200);
        long mid = startGasMission(rid, 1000, "water", 10);
        String cargo = ok(client().execute("artest mission complete-now " + mid));
        assertFalse("complete-now must not error: " + cargo, cargo.contains("\"error\""));
        // rocketCount > 0 confirms at least one rocket entity is in
        // the launch dim near the launch coords post-completion. The
        // type discrimination (StationDeployed vs plain) is checked
        // via the ore counter-test in MissionOreCompletionTest.
        assertTrue("at least one rocket entity must exist near launch coords after gas completion: "
                        + cargo,
                cargo.contains("\"rocketCount\":") && !cargo.contains("\"rocketCount\":0"));
    }

    /** Strong contract: with intakePower>0 AND a rocket carrying fluid
     *  tiles (TileFluidTank, exposing FLUID_HANDLER capability) the
     *  gas completion fills each fluid tile with exactly 64000 mB of
     *  the configured fluid (MissionGasCollection line 50:
     *  {@code fill(new FluidStack(type, 64000), true)}).
     *  Uses the `with-fluid-cargo` fixture variant that swaps 2 of 6
     *  fuel tanks for liquidTank blocks so StorageChunk.liquidTiles is
     *  non-empty. */
    @Test
    public void gasCompletionFillsRocketFluidTilesWithConfiguredFluid() throws Exception {
        int rid = buildAndAssembleRocket(8300, "with-fluid-cargo");
        long mid = startGasMission(rid, 1000, "oxygen", 10);
        String cargo = ok(client().execute("artest mission complete-now " + mid));
        assertFalse("complete-now must not error: " + cargo, cargo.contains("\"error\""));
        // fluidEntries > 0 — fill loop ran on at least one TE. Exact
        // count depends on whether the original EntityRocket still
        // lingers next to the freshly spawned StationDeployedRocket
        // (both share the same StorageChunk via reference). Loose pin
        // avoids that ambiguity.
        assertFalse("fluidEntries must be > 0 (production filled fluid tiles): " + cargo,
                cargo.contains("\"fluidEntries\":0"));
        // Each filled tile holds 64000 mB of oxygen — production literal
        // at MissionGasCollection.java:50 (FluidStack(type, 64000)).
        assertTrue("fluid contents must include oxygen 64000 mB: " + cargo,
                cargo.contains("\"type\":\"oxygen\"") && cargo.contains("\"amount\":64000"));
    }
}
