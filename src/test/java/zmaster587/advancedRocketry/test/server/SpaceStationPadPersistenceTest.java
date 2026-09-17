package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import zmaster587.advancedRocketry.test.StationPads;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * multi-boot harness for station landing-pad
 * persistence.
 *
 * Companion to {@link PersistenceRestartSmokeTest} (which covers station
 * id + orbiting body + satellite + atmosphere density across restart).
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
 *   <li>Boot 2 (same workDir): verify all 3 pads survived, B is
 *       still occupied + auto-land=true, A and C are still free +
 *       auto-land=false. Then undock B and dock again — must reclaim B.</li>
 * </ol>
 *
 * Why this matters: without per-pad occupied flags surviving save/load,
 * a server restart would lose the dock state of every in-orbit rocket
 * — modpack players would log back in to find their docked rockets
 * had vanished from their station's tracking even though the rocket
 * entity itself persists in the world.
 */
public class SpaceStationPadPersistenceTest {

    /** The station's own id. The regex this replaces anchored on the NEXT field so as not
     *  to match some other `id`; reading by name needs no such anchor. */
    private static final String STATION_ID = "id";

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-pad-persistence-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    @Test
    public void padSetAndPerPadStateSurviveRestart() throws Exception {
        long stationId;

        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        // --- Boot 1: create station with three pads, lock auto-land + dock B
        String createStation = String.join("\n",
                firstBoot.client().execute("artest station create 0"));
        Reply created = Reply.of("artest station create", createStation);
        assertTrue("could not extract station id: " + createStation, created.has(STATION_ID));
        stationId = created.integer(STATION_ID);

        ok(firstBoot, "artest station add-pad " + stationId + " 100 100 padA");
        ok(firstBoot, "artest station add-pad " + stationId + " 200 200 padB");
        ok(firstBoot, "artest station add-pad " + stationId + " 300 300 padC");
        ok(firstBoot, "artest station set-autoland " + stationId + " 200 200 true");

        // Dock must consume B (the only auto-land pad).
        String dock = String.join("\n",
                firstBoot.client().execute("artest station dock " + stationId));
        assertTrue("boot1 dock must claim padB: " + dock,
                dock.contains("\"ok\":true") && dock.contains("\"x\":200"));

        // Sanity dump before restart.
        StationPads padsBefore = pads(firstBoot, stationId);
        assertTrue("padA must be in boot1 dump: " + padsBefore.raw(), padsBefore.has(100, 100));
        assertTrue("padB must be in boot1 dump: " + padsBefore.raw(), padsBefore.has(200, 200));
        assertTrue("padC must be in boot1 dump: " + padsBefore.raw(), padsBefore.has(300, 300));

        // /save-all to force the world to flush before close — same as the
        // existing PersistenceRestartSmokeTest pattern.
        firstBoot.client().execute("save-all flush");
        firstBoot.close();
        firstBoot = null;

        // --- Boot 2 on the same workDir — every pad-level state must restore.
        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String stations = String.join("\n",
                secondBoot.client().execute("artest station list"));
        assertTrue("station " + stationId + " did NOT survive restart: " + stations,
                stations.contains("\"id\":" + stationId));

        StationPads padsAfter = pads(secondBoot, stationId);
        assertTrue("padA must survive restart: " + padsAfter.raw(), padsAfter.has(100, 100));
        assertTrue("padB must survive restart: " + padsAfter.raw(), padsAfter.has(200, 200));
        assertTrue("padC must survive restart: " + padsAfter.raw(), padsAfter.has(300, 300));

        // Per-pad state, asked of each pad BY POSITION. What stood here walked the reply's braces
        // by hand to slice out the object containing `"x":100` — a JSON parser written inside a
        // test, and one that would have sliced the wrong pad the moment a pad's NAME held the
        // marker text.
        StationPads.Pad padA = padsAfter.at(100, 100);
        StationPads.Pad padB = padsAfter.at(200, 200);
        StationPads.Pad padC = padsAfter.at(300, 300);

        // occupied: padB is the only one that should be true (we docked it
        // pre-restart). A and C stay free.
        assertTrue("padB's occupied=true must survive restart: " + padB.raw(), padB.occupied);
        assertFalse("padA must restore to occupied=false: " + padA.raw(), padA.occupied);
        assertFalse("padC must restore to occupied=false: " + padC.raw(), padC.occupied);

        // pad name field — writeToNBT.setString("name", …) + readFromNbt
        // reads it back via tag.getString("name"). All three names must
        // survive verbatim.
        assertEquals("padA name must survive restart: " + padA.raw(), "padA", padA.name());
        assertEquals("padB name must survive restart: " + padB.raw(), "padB", padB.name());
        assertEquals("padC name must survive restart: " + padC.raw(), "padC", padC.name());

        // -- allowAutoLand: surface the known bug at
        //    SpaceStationObject.java:801. The write side correctly writes
        //    `tag.setBoolean("autoLand", pos.getAllowedForAutoLand())`,
        //    but the read side reads from the WRONG KEY:
        //      loc.setAllowedForAutoLand(!tag.hasKey("occupied")
        //                                  || tag.getBoolean("occupied"));
        //    This collapses allowAutoLand to "is the pad occupied?" plus
        //    a weird hasKey defaults-to-true fallback. The result:
        //    - padB (occupied=true) -> allowAutoLand reads as true (lucky)
        //    - padA / padC (occupied=false) -> allowAutoLand reads as
        //                                     false ALWAYS, regardless of
        //                                     what was written.
        // Our boot1 set padB autoLand=true and padA/C never opted in
        // (default false), so the OBSERVED outcomes happen to all match
        // what we want — but ONLY because of the collision between the
        // semantic of occupied-on-padB and the read-key bug. If the
        // boot1 sequence opted padA into autoLand WITHOUT docking it,
        // the bug would surface. Pin both observations explicitly so a
        // future read-side fix is forced to update this test.
        assertTrue("padB allowAutoLand reads true after restart (lucky path "
                        + "— SpaceStationObject:801 reads from \"occupied\" "
                        + "key, and padB IS occupied): " + padB.raw(),
                padB.allowAutoLand);
        assertFalse("padA allowAutoLand reads FALSE after restart (whatever "
                        + "the original write was — read side ignores the "
                        + "\"autoLand\" key, SpaceStationObject:801 bug): "
                        + padA.raw(),
                padA.allowAutoLand);

        // Behavioural check: undock B -> next dock must reclaim B again.
        String undock = String.join("\n", secondBoot.client().execute(
                "artest station undock " + stationId + " 200 200"));
        assertTrue("post-restart undock must succeed: " + undock,
                undock.contains("\"ok\":true"));
        String dock2 = String.join("\n", secondBoot.client().execute(
                "artest station dock " + stationId));
        assertTrue("post-restart dock must reclaim padB: " + dock2,
                dock2.contains("\"ok\":true") && dock2.contains("\"x\":200"));
    }

    /**
     * <em>DOCUMENTS KNOWN PRODUCTION BUG</em> at
     * {@code SpaceStationObject.java:801}:
     *
     * <pre>
     * tag.setBoolean("autoLand", pos.getAllowedForAutoLand());  // write
     * ...
     * loc.setAllowedForAutoLand(
     *     !tag.hasKey("occupied") || tag.getBoolean("occupied"));  // read
     * </pre>
     *
     * the read now uses the "autoLand" key
     * that the write side writes. allowAutoLand survives restart even
     * for pads that weren't docked at save time.
     */
    @Test
    public void autoLandFlagWithoutDockSurvivesRestart() throws Exception {
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        String createStation = String.join("\n",
                firstBoot.client().execute("artest station create 0"));
        Reply created = Reply.of("artest station create", createStation);
        assertTrue("could not extract station id: " + createStation, created.has(STATION_ID));
        long stationId = created.integer(STATION_ID);

        // Add ONE pad and enable auto-land — but DO NOT dock it. occupied
        // stays false; the read-side bug forces allowAutoLand to false too.
        ok(firstBoot, "artest station add-pad " + stationId + " 999 999 lonely");
        ok(firstBoot, "artest station set-autoland " + stationId + " 999 999 true");

        // Sanity in boot1: the in-memory state correctly reports both flags. Asked of the lonely
        // pad itself — this station holds exactly one, and the substring form would have been
        // satisfied by any pad in a station that held more.
        StationPads.Pad lonelyBefore = pads(firstBoot, stationId).at(999, 999);
        assertTrue("boot1 padA must report allowAutoLand=true in memory: " + lonelyBefore.raw(),
                lonelyBefore.allowAutoLand);
        assertFalse("boot1 padA must report occupied=false: " + lonelyBefore.raw(),
                lonelyBefore.occupied);

        firstBoot.client().execute("save-all flush");
        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        StationPads.Pad lonelyAfter = pads(secondBoot, stationId).at(999, 999);
        assertTrue("padA allowAutoLand must be true after restart — "
                        + "SpaceStationObject:801 now reads from the same "
                        + "\"autoLand\" key the write side writes. pads dump: "
                        + lonelyAfter.raw(),
                lonelyAfter.allowAutoLand);
    }

    /** Every landing pad the station holds on one of the two boots, addressable by position. */
    private static StationPads pads(RealDedicatedServerHarness boot, long stationId)
            throws Exception {
        return StationPads.byId(cmd -> String.join("\n", boot.client().execute(cmd)),
                (int) stationId);
    }

    private static void ok(RealDedicatedServerHarness harness, String cmd) throws Exception {
        String resp = String.join("\n", harness.client().execute(cmd));
        assertEquals("probe " + cmd + " did not return ok: " + resp,
                true, resp.contains("\"ok\":true"));
    }
}
