package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.RocketInfo;
import dev.stannismod.stellurgy.test.RocketList;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;

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

    /**
     * FIRST link and the build, in one call: the volume this craft is built in is EMPTY, and a
     * failure names what was in it.
     *
     * <p>The site stands in open air, so this ASSERTS rather than digs. It still warms the chunks —
     * the fill inside it force-loads every chunk in the box — so nothing downstream lost a
     * guarantee it had.</p>
     */
    private int[] buildFixture(FixtureSite site) throws Exception {
        return RocketFixture.placeAt(site, cmd -> String.join("\n", client().execute(cmd)),
                "simple", 2, 10, "the craft is built and worn in this volume");
    }

    private int assembleAndGetId(int[] builderPos) throws Exception {
        String assemble = String.join("\n", client().execute(
                "stellurgytest rocket assemble 0 " + builderPos[0] + " " + builderPos[1] + " " + builderPos[2]));
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());
        String list = String.join("\n", client().execute("stellurgytest rocket list 0"));
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
                "stellurgytest wear get 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ));
        assertTrue("motor must host wear cap: " + engine, Reply.of(engine).bool("registered"));

        String tank = String.join("\n", client().execute(
                "stellurgytest wear get 0 " + rocketX + " " + (rocketY + 1) + " " + rocketZ));
        assertTrue("fuel tank must host wear cap: " + tank, Reply.of(tank).bool("registered"));

        String seat = String.join("\n", client().execute(
                "stellurgytest wear get 0 " + rocketX + " " + (rocketY + 4) + " " + rocketZ));
        assertTrue("seat must host wear cap: " + seat, Reply.of(seat).bool("registered"));
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
        client().execute("stellurgytest wear set 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ + " 10");
        int worn = assembleAndGetId(builder);
        assertTrue("a fully-worn motor must raise the breaking probability",
                breakingProbOf(worn) > 0);
    }

    @Test
    public void wornTankAndSeatSurfaceForLaunchGate() throws Exception {
        final FixtureSite bSite = FixtureSite.openAir(0, 2960, 3080);
        final int bx = bSite.x, by = bSite.y, bz = bSite.z;
        int[] builder = buildFixture(bSite);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;
        client().execute("stellurgytest wear set 0 " + rocketX + " " + (rocketY + 1) + " " + rocketZ + " 8");  // a fuel tank
        client().execute("stellurgytest wear set 0 " + rocketX + " " + (rocketY + 4) + " " + rocketZ + " 10"); // the seat
        int rocketId = assembleAndGetId(builder);

        String status = String.join("\n", client().execute("stellurgytest wear rocket-status " + rocketId + " 0.7"));
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
        client().execute("stellurgytest wear set 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ + " 10");
        client().execute("stellurgytest wear set 0 " + (rocketX + 1) + " " + rocketY + " " + rocketZ + " 10");
        int wornThrust = thrustOf(assembleAndGetId(wornBuilder));

        assertTrue("worn rocket must still have some thrust: " + wornThrust, wornThrust > 0);
        assertTrue("fully-worn motors must produce less thrust than pristine ("
                        + wornThrust + " vs " + pristineThrust + ")",
                wornThrust < pristineThrust);
    }
}
