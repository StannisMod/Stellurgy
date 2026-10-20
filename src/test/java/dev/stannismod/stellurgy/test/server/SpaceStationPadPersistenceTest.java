package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.AbstractMechanicsTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerRoot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.StationPads;
import dev.stannismod.stellurgy.test.contract.OwnBoot;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * multi-boot harness for station landing-pad
 * persistence.
 *
 * This test focuses on the LANDING-PAD state: a station's pad set, each
 * pad's occupied flag, each pad's auto-land allow-list — all of which
 * are NBT-serialised in {@code SpaceStationObject.writeToNBT}'s
 * spawnLocations branch.
 *
 * Sequence:
 *
 * <ol>
 *   <li>Boot 1: create station, add 3 pads (A, B, C), enable auto-land on
 *       B only, dock once (must claim B and mark it occupied).</li>
 *   <li>Boot 2 (same workDir): undock B and dock again — the loaded
 *       station must reclaim B.</li>
 * </ol>
 *
 * Why this matters: without per-pad occupied flags surviving save/load,
 * a server restart would lose the dock state of every in-orbit rocket
 * — modpack players would log back in to find their docked rockets
 * had vanished from their station's tracking even though the rocket
 * entity itself persists in the world.
 *
 * <p>SEPARATE-BOOT: a server restart that is the subject. Every scenario here boots a server, stops
 * it, and boots a second one over the same world directory to read what the save carried across;
 * a shared, running server cannot be stopped under its siblings.</p>
 */
@OwnBoot(reason = OwnBoot.Reason.SERVER_RESTART)
public class SpaceStationPadPersistenceTest extends AbstractMechanicsTest {

    /** The pad this scenario claims, before and after the restart. The arrangement's own X. */
    private static final int PAD_B_X = 200;

    /** The station's own id. The regex this replaces anchored on the NEXT field so as not
     *  to match some other `id`; reading by name needs no such anchor. */
    private static final String STATION_ID = "id";

    private ServerRoot workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = ServerRoot.fresh("forge-server-pad-persistence-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    /** Pins INV-STN-06 (pads survive a save and reload with occupied, name and auto-land intact). */
    @Pins("INV-STN-06")
    @Test
    public void padSetAndPerPadStateSurviveRestart() throws Exception {
        long stationId;

        firstBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/false);

        // --- Boot 1: create station with three pads, lock auto-land + dock B
        String createStation = String.join("\n",
                firstBoot.client().arrange("stellurgytest station create 0"));
        Reply created = Reply.of("stellurgytest station create", createStation);
        assertTrue("could not extract station id: " + createStation, created.has(STATION_ID));
        stationId = created.integer(STATION_ID);

        ok(firstBoot, "stellurgytest station add-pad " + stationId + " 100 100 padA");
        ok(firstBoot, "stellurgytest station add-pad " + stationId + " 200 200 padB");
        ok(firstBoot, "stellurgytest station add-pad " + stationId + " 300 300 padC");
        ok(firstBoot, "stellurgytest station set-autoland " + stationId + " 200 200 true");

        // Dock must consume B (the only auto-land pad).
        String dock = String.join("\n",
                firstBoot.client().act("stellurgytest station dock " + stationId));
        assertTrue("boot1 dock must claim padB: " + dock,
                Reply.of(dock).ok() && (Reply.of(dock).integer("x") == PAD_B_X));

        // Sanity dump before restart.
        StationPads padsBefore = pads(firstBoot, stationId);
        assertTrue("padA must be in boot1 dump: " + padsBefore.raw(), padsBefore.has(100, 100));
        assertTrue("padB must be in boot1 dump: " + padsBefore.raw(), padsBefore.has(200, 200));
        assertTrue("padC must be in boot1 dump: " + padsBefore.raw(), padsBefore.has(300, 300));

        // /save-all to force the world to flush before close.
        firstBoot.client().act("save-all flush");
        firstBoot.close();
        firstBoot = null;

        // --- Boot 2 on the same workDir.
        secondBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/true);

        // Behavioural check: undock B -> next dock must reclaim B again.
        String undock = String.join("\n", secondBoot.client().arrange(
                "stellurgytest station undock " + stationId + " 200 200"));
        assertTrue("post-restart undock must succeed: " + undock,
                Reply.of(undock).ok());
        String dock2 = String.join("\n", secondBoot.client().act(
                "stellurgytest station dock " + stationId));
        assertTrue("post-restart dock must reclaim padB: " + dock2,
                Reply.of(dock2).ok() && (Reply.of(dock2).integer("x") == PAD_B_X));
    }

    /** Every landing pad the station holds on one of the two boots, addressable by position. */
    private static StationPads pads(RealDedicatedServerHarness boot, long stationId)
            throws Exception {
        return StationPads.byId(cmd -> String.join("\n", boot.client().read(cmd)),
                (int) stationId);
    }

    private static void ok(RealDedicatedServerHarness harness, String cmd) throws Exception {
        String resp = String.join("\n", harness.client().arrange(cmd));
        assertEquals("probe " + cmd + " did not return ok: " + resp,
                true, Reply.of(resp).ok());
    }
}
