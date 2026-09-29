package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Multi-boot persistence smoke for
 * MissionGasCollection and MissionOreMining.
 *
 * <p>A mission, once started, is registered on its target
 * {@link zmaster587.advancedRocketry.dimension.DimensionProperties} as
 * a tickable {@link zmaster587.advancedRocketry.api.satellite.SatelliteBase},
 * which is serialised through the dim's NBT save (same path as the
 * station / satellite persistence already covered by
 * {@link PersistenceRestartSmokeTest}).</p>
 *
 * <p>The contract under test:</p>
 * <ul>
 *   <li>{@code writeToNBT} captures: {@code startWorldTime, duration,
 *       worldId, launchDimension, x/y/z, rocketStats, rocketStorage,
 *       persist, infrastructure} (all in {@link
 *       zmaster587.advancedRocketry.mission.MissionResourceCollection#writeToNBT}).</li>
 *   <li>{@code MissionGasCollection} adds the {@code "gas"} key with
 *       the fluid registry name.</li>
 *   <li>{@code readFromNBT} restores the mission so its
 *       {@code getMissionId()}, {@code duration}, gas-fluid type, and
 *       type-distinguishing class are recoverable on the second boot.</li>
 * </ul>
 *
 * <p>This is the "gold-standard" reboot roundtrip — the completion
 * tests already exercise the in-memory mission state, but only a real
 * shutdown + boot proves the NBT path survives.</p>
 *
 * <p>Does NOT use {@link AbstractSharedServerTest} because it needs a
 * fresh workDir per test and an explicit two-boot lifecycle (same
 * reason {@link PersistenceRestartSmokeTest} stays on its own
 * harness).</p>
 */
public class MissionPersistenceRestartTest {

    private static final String MISSION_ID = "missionId";
    private static final String DURATION = "duration";

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-mission-persistence-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(RealDedicatedServerHarness boot, int baseX) throws Exception {
        final FixtureSite site = FixtureSite.openAir(0, baseX, 600);
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built in is EMPTY. The site stands in open air, so
        // this ASSERTS rather than digs, and the fill inside it force-loads every chunk in the box.
        RocketFixture.assembleAt(site, cmd -> ok(boot.client().execute(cmd)), "simple", 2, 10,
                "the craft whose mission must survive the restart is built in this volume");
        String list = ok(boot.client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("no rocket after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    @Test
    public void gasMissionSurvivesServerRestart() throws Exception {
        long missionId;
        long expectedDuration = 5000;
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        int rid = buildAndAssembleRocket(firstBoot, 9500);
        String start = ok(firstBoot.client().execute(
                "artest mission start-gas 0 " + rid + " " + expectedDuration + " oxygen 10"));
        assertFalse("start-gas failed in boot1: " + start, Reply.of(start).has("error"));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId in start response: " + start, mmReply.has(MISSION_ID));
        missionId = Long.parseLong(mmReply.text(MISSION_ID));

        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String state = ok(secondBoot.client().execute("artest mission state " + missionId));
        assertFalse("state probe failed after reboot — mission lost: " + state,
                Reply.of(state).has("error"));
        assertTrue("mission type must be gas after reboot: " + state,
                "gas".equals(Reply.of(state).text("type")));
        Reply dmReply = Reply.of(state);
        assertTrue("missing duration in restored state: " + state, dmReply.has(DURATION));
        // MissionGasCollection ctor multiplies duration by gasCollectionMult
        // (config default 1.0 in test env). Pin against the value the mission
        // actually stored — pull it via state probe from boot 1 was already
        // computed; here we just assert it's nonzero and stable across reboot.
        long restoredDuration = Long.parseLong(dmReply.text(DURATION));
        assertTrue("restored duration must be > 0: " + state, restoredDuration > 0);
        assertEquals("restored duration must equal configured (gasCollectionMult=1 in test env)",
                expectedDuration, restoredDuration);
        assertTrue("mission must not be dead after reboot: " + state,
                (!Reply.of(state).bool("isDead")));
    }

    @Test
    public void oreMissionSurvivesServerRestart() throws Exception {
        long missionId;
        long expectedDuration = 5000;
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        int rid = buildAndAssembleRocket(firstBoot, 9600);
        String start = ok(firstBoot.client().execute(
                "artest mission start-ore 0 " + rid + " " + expectedDuration + " 1.0"));
        assertFalse("start-ore failed in boot1: " + start, Reply.of(start).has("error"));
        Reply mmReply = Reply.of(start);
        assertTrue("missing missionId in start response: " + start, mmReply.has(MISSION_ID));
        missionId = Long.parseLong(mmReply.text(MISSION_ID));

        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String state = ok(secondBoot.client().execute("artest mission state " + missionId));
        assertFalse("state probe failed after reboot — mission lost: " + state,
                Reply.of(state).has("error"));
        assertTrue("mission type must be ore after reboot: " + state,
                "ore".equals(Reply.of(state).text("type")));
        Reply dmReply = Reply.of(state);
        assertTrue("missing duration in restored state: " + state, dmReply.has(DURATION));
        assertEquals("restored ore duration must equal configured",
                expectedDuration, Long.parseLong(dmReply.text(DURATION)));
        assertTrue("mission must not be dead after reboot: " + state,
                (!Reply.of(state).bool("isDead")));
    }
}
