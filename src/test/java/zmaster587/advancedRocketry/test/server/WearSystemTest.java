package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Foundation coverage for the parts-wear rework:
 *
 * <ul>
 *   <li>motors, fuel tanks and seats host the wear capability in the world;</li>
 *   <li>{@code wear get/set} round-trips a stage;</li>
 *   <li>worn motors produce less thrust after assembly (graduated consequence).</li>
 * </ul>
 *
 * <p>The launch-time consequences (tank leak / explosion / seat-block) need a
 * pilot or stochastic launch and are covered later; this pins the data model
 * and the thrust contract that feeds TWR.</p>
 */
public class WearSystemTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";

    /**
     * FIRST link: the volume this craft is built in is EMPTY, and a failure names what was in it.
     *
     * <p>The site stands in open air, so this ASSERTS rather than digs. It still warms the chunks -
     * the fill inside it force-loads every chunk in the box - so nothing downstream lost a
     * guarantee it had.</p>
     */
    private void requireClearSite(FixtureSite site) throws Exception {
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft is built and worn in this volume");
    }

    private int[] buildFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        requireClearSite(site);
        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture build failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("no builderPos: " + fixture, bp != null);
        return new int[]{bp[0], bp[1], bp[2]};
    }

    private int assembleAndGetId(int[] builderPos) throws Exception {
        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + builderPos[0] + " " + builderPos[1] + " " + builderPos[2]));
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());
        String list = String.join("\n", client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket id after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    private int thrustOf(int entityId) throws Exception {
        return rocketInfo(entityId).thrust;
    }

    /** What the server says about one craft, read through the verb's own reader. */
    private RocketInfo rocketInfo(int id) throws Exception {
        return RocketInfo.byId(cmd -> String.join("\n", client().execute(cmd)), id);
    }

    @Test
    public void motorTankSeatHostWearCapability() throws Exception {
        final FixtureSite bSite = FixtureSite.openAir(0, 2900, 2900);
        final int bx = bSite.x, by = bSite.y, bz = bSite.z;
        buildFixture(bSite);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;

        // Engine, fuel tank, seat positions (see fixture builder).
        String engine = String.join("\n", client().execute(
                "artest wear get 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ));
        assertTrue("motor must host wear cap: " + engine, Reply.of(engine).bool("registered"));

        String tank = String.join("\n", client().execute(
                "artest wear get 0 " + rocketX + " " + (rocketY + 1) + " " + rocketZ));
        assertTrue("fuel tank must host wear cap: " + tank, Reply.of(tank).bool("registered"));

        String seat = String.join("\n", client().execute(
                "artest wear get 0 " + rocketX + " " + (rocketY + 4) + " " + rocketZ));
        assertTrue("seat must host wear cap: " + seat, Reply.of(seat).bool("registered"));
    }

    @Test
    public void wearStageRoundTripsThroughCapability() throws Exception {
        final FixtureSite bSite = FixtureSite.openAir(0, 2960, 2900);
        final int bx = bSite.x, by = bSite.y, bz = bSite.z;
        buildFixture(bSite);
        int ex = bx + 3 - 1, ey = by + 1, ez = bz + 3;

        String set = String.join("\n", client().execute("artest wear set 0 " + ex + " " + ey + " " + ez + " 7"));
        assertTrue("wear set failed: " + set, Reply.of(set).ok());

        String get = String.join("\n", client().execute("artest wear get 0 " + ex + " " + ey + " " + ez));
        Reply mReply = Reply.of(get);
        assertTrue("no stage in get: " + get, mReply.has("stage"));
        assertEquals("wear stage must persist", 7, mReply.integer("stage"));
    }

    private double breakingProbOf(int entityId) throws Exception {
        return rocketInfo(entityId).breakingProb;
    }

    @Test
    public void wornMotorRaisesBreakingProbability() throws Exception {
        // Pristine rocket: zero failure probability.
        final FixtureSite aSite = FixtureSite.openAir(0, 2900, 3020);
        final int ax = aSite.x, ay = aSite.y, az = aSite.z;
        int pristine = assembleAndGetId(buildFixture(aSite));
        assertEquals("pristine rocket must have zero breaking probability",
                0.0, breakingProbOf(pristine), 1e-6);

        // Max out one engine's wear before assembly -> breaking probability rises.
        final FixtureSite bSite = FixtureSite.openAir(0, 2960, 3020);
        final int bx = bSite.x, by = bSite.y, bz = bSite.z;
        int[] builder = buildFixture(bSite);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;
        client().execute("artest wear set 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ + " 10");
        int worn = assembleAndGetId(builder);
        assertTrue("a fully-worn motor must raise the breaking probability",
                breakingProbOf(worn) > 0);
    }

    @Test
    public void standaloneRepairResetsMotorWear() throws Exception {
        final FixtureSite bSite = FixtureSite.openAir(0, 2900, 3080);
        final int bx = bSite.x, by = bSite.y, bz = bSite.z;
        int[] builder = buildFixture(bSite);
        int rocketId = assembleAndGetId(builder);
        // Wear one motor to stage 5 (no PrecisionAssembler nearby -> standalone path).
        String inject = String.join("\n", client().execute("artest infra inject-broken-part " + rocketId + " 5"));
        assertTrue("inject-broken-part failed: " + inject, Reply.of(inject).ok());
        assertTrue("worn motor must give a non-zero breaking probability",
                breakingProbOf(rocketId) > 0);

        // Service station off to the side, with its own clear pocket + redstone power.
        int sx = bx - 4, sy = by + 1, sz = bz;
        client().execute("artest fill 0 " + (sx - 1) + " " + sy + " " + (sz - 1)
                + " " + (sx + 1) + " " + (sy + 2) + " " + (sz + 1) + " minecraft:air");
        String place = String.join("\n", client().execute(
                "artest place 0 " + sx + " " + sy + " " + sz + " advancedrocketry:serviceStation"));
        assertTrue("service station place failed: " + place, Reply.of(place).bool("placed"));
        // Redstone power — performFunction requires getEquivalentPower=true.
        client().execute("artest place 0 " + sx + " " + (sy + 1) + " " + sz + " minecraft:redstone_block");

        String link = String.join("\n", client().execute(
                "artest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("link failed: " + link, Reply.of(link).ok());

        // Load the stage-5 repair recipe's non-part materials (ingot + plate),
        // each well above the x3 standalone multiplier.
        String load0 = String.join("\n", client().execute(
                "artest wear station-load 0 " + sx + " " + sy + " " + sz + " 0 ore:ingotTitaniumIridium 16"));
        assertTrue("station-load ingot failed: " + load0, Reply.of(load0).ok());
        String load1 = String.join("\n", client().execute(
                "artest wear station-load 0 " + sx + " " + sy + " " + sz + " 1 ore:plateTitaniumAluminide 16"));
        assertTrue("station-load plate failed: " + load1, Reply.of(load1).ok());

        // Drive performFunction directly (no assembler -> standalone repair branch).
        client().execute("artest infra service-perform-function 0 " + sx + " " + sy + " " + sz);
        client().execute("artest infra service-perform-function 0 " + sx + " " + sy + " " + sz);

        assertEquals("standalone repair must reset the worn motor (breaking prob back to 0)",
                0.0, breakingProbOf(rocketId), 1e-6);
    }

    @Test
    public void wornTankAndSeatSurfaceForLaunchGate() throws Exception {
        final FixtureSite bSite = FixtureSite.openAir(0, 2960, 3080);
        final int bx = bSite.x, by = bSite.y, bz = bSite.z;
        int[] builder = buildFixture(bSite);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;
        client().execute("artest wear set 0 " + rocketX + " " + (rocketY + 1) + " " + rocketZ + " 8");  // a fuel tank
        client().execute("artest wear set 0 " + rocketX + " " + (rocketY + 4) + " " + rocketZ + " 10"); // the seat
        int rocketId = assembleAndGetId(builder);

        String status = String.join("\n", client().execute("artest wear rocket-status " + rocketId + " 0.7"));
        assertTrue("rocket-status must find the rocket: " + status, Reply.of(status).bool("found"));

        Reply tanksReply = Reply.of(status);
        assertTrue("no wornTankCount: " + status, tanksReply.has("wornTankCount"));
        assertTrue("a worn fuel tank must be surfaced for the launch gate: " + status,
                tanksReply.integer("wornTankCount") >= 1);
        assertTrue("a critically-worn seat must be detected: " + status,
                Reply.of(status).bool("hasCriticallyWornSeat"));
    }

    @Test
    public void wornMotorsProduceLessThrust() throws Exception {
        // Pristine reference rocket.
        int[] pristineBuilder = {0, 0, 0};
        final FixtureSite aSite = FixtureSite.openAir(0, 2900, 2960);
        final int ax = aSite.x, ay = aSite.y, az = aSite.z;
        pristineBuilder = buildFixture(aSite);
        int pristineThrust = thrustOf(assembleAndGetId(pristineBuilder));
        assertTrue("pristine thrust must be positive", pristineThrust > 0);

        // Worn rocket: max out both engine wear stages before assembly.
        final FixtureSite bSite = FixtureSite.openAir(0, 2960, 2960);
        final int bx = bSite.x, by = bSite.y, bz = bSite.z;
        int[] wornBuilder = buildFixture(bSite);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;
        client().execute("artest wear set 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ + " 10");
        client().execute("artest wear set 0 " + (rocketX + 1) + " " + rocketY + " " + rocketZ + " 10");
        int wornThrust = thrustOf(assembleAndGetId(wornBuilder));

        assertTrue("worn rocket must still have some thrust: " + wornThrust, wornThrust > 0);
        assertTrue("fully-worn motors must produce less thrust than pristine ("
                        + wornThrust + " vs " + pristineThrust + ")",
                wornThrust < pristineThrust);
    }
}
