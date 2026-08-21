package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * E2E: can a restored in-flight tier-2 jump rebuild its ship by PASTING a block snapshot into the target
 * cell? A ship departs into hyperspace; its {@code StorageChunk} snapshot is re-cut from the parked
 * hyperspace ship by the periodic refresh (driven on demand here — it is deliberately NOT on the save
 * path, where a physics-world call once cost a whole fleet). We then simulate a restart in-process: the
 * live transit manager is discarded (its parked
 * hyperspace ship is orphaned and can never arrive) and the transit is rebuilt from the exported record
 * alone. The restored transit holds no hyperspace ship, so it can only complete through
 * {@code VSShipCrosser.completeRestored} / {@code VSIntegration.pasteAndAssemble} — the snapshot NBT is read
 * back into a StorageChunk, pasted into the target cell, and re-VSed. If that paste/assembly path were
 * broken, no VS-managed ship would appear in the target cell and this test would fail.
 *
 * <p><b>Scope (honest boundary):</b> this exercises the restore + snapshot-PASTE path in real VS worlds, but
 * it is an IN-PROCESS simulation — it hands the exported records straight back to the manager. It does NOT
 * go through the store's on-disk NBT (that serialization is unit-pinned by {@code ShipLedgerDataTest} +
 * {@code TransitRecordTest}), and the JVM does not actually restart (so hyperspace is not truly wiped). A
 * genuine two-boot restart driving the production {@code onServerStarted}/{@code onWorldSave} wiring needs
 * the harness's test-mode standdown lifted, which is a later transit-persistence increment.</p>
 *
 * <p>Complements {@code VSShipTransitE2ETest} (the LIVE hyperspace crossing) and the deterministic
 * {@code ShipTransitManagerTest} (the restored state machine with a fake crosser). Gated on the server's
 * real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSShipTransitPersistE2ETest extends AbstractSharedServerTest {

    /**
     * Budgets in SERVER TICKS — the ten and twenty seconds the old {@code 40}/{@code 80 x 250 ms}
     * meant on an idle box, with no fork multiplier: a re-cut and an arrival need world, and how much
     * of the machine this test is sharing does not change how much.
     */
    private static final int REFRESH_TICKS = 200;
    private static final int ARRIVAL_TICKS = 400;
    private static final int LOAD_TICKS = 200;

    @Test
    public void aRestoredInFlightJumpRebuildsItsShipByPastingItsSnapshotIntoTheTargetCell() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        // Headless: pin ships loaded so a freshly assembled ship does not auto-unload between probe calls.
        exec("artest vs permaload true");

        // Build a VS ship in a fresh origin cell (a pool slot world) + the whole transit stack.
        String setup = exec("artest space transit-setup");
        assertTrue("transit setup failed: " + setup, setup.contains("\"ok\":true"));
        int originDim = extractInt(setup, "originDim");
        int ax = extractInt(setup, "anchorX"), ay = extractInt(setup, "anchorY"), az = extractInt(setup, "anchorZ");
        assertTrue("origin ship never assembled/loaded in the pool-slot cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Depart into hyperspace. We deliberately do NOT tick the transit yet: it stays parked in hyperspace
        // while we re-cut its snapshot (the save-point cut is of a PARKED ship).
        String begin = exec("artest space transit-begin " + originDim + " " + ax + " " + ay + " " + az
                + " " + HYPERSPACE_JUMP_SPEED);
        assertTrue("transit did not begin (departure crossing failed): " + begin, begin.contains("\"began\":true"));

        // Re-cut the parked ship's block snapshot; retry while the async hyperspace assembly completes
        // (snapshotShipAt needs the ship's subspace shipyard to be up).
        //
        // Poll the REFRESH's own count, not the export's hasSnapshot. Every transit carries a snapshot
        // from the instant it departs — the floor cut of the source ship, taken before the departure
        // crossing so that a save in the pre-assembly window is never snapshot-less — so hasSnapshot is
        // true on the first iteration whether or not hyperspace was ever read. Waiting on it is waiting
        // on a condition that is already met: it exits immediately and the assertion that the PARKED
        // ship was re-cut becomes a statement about a cut that never happened.
        final String[] lastRefresh = {""};
        boolean snapshotCut = GameTicks.until(client(), GameTicks.server(), REFRESH_TICKS, () -> {
            lastRefresh[0] = exec("artest space transit-refresh");
            return extractInt(lastRefresh[0], "refreshed") >= 1;
        });
        assertTrue("the parked hyperspace ship was never re-cut into a persisted snapshot; last="
                + lastRefresh[0], snapshotCut);

        String lastExport = exec("artest space transit-export");
        assertTrue("the durable record must carry a block snapshot, or the restore below has no ship to "
                + "paste: " + lastExport, lastExport.contains("\"hasSnapshot\":true"));

        // Simulate a restart in-process: rebuild the transit from the exported record alone (the live
        // manager, and its parked hyperspace ship, are thrown away). Note: this reuses the in-memory record;
        // the on-disk NBT round-trip is unit-pinned separately (ShipLedgerDataTest + TransitRecordTest).
        String restore = exec("artest space transit-restore");
        assertTrue("restore did not recreate the in-flight transit: " + restore,
                restore.contains("\"inTransit\":1"));

        // Advance the RESTORED transit. With no live hyperspace ship it can only arrive by pasting its
        // snapshot into the target cell.
        final String[] lastTick = {""};
        boolean arrived = GameTicks.until(client(), GameTicks.server(), ARRIVAL_TICKS, () -> {
            lastTick[0] = exec("artest space transit-tick 10");
            return extractInt(lastTick[0], "inTransit") == 0;
        });
        int targetDim = arrived ? extractInt(lastTick[0], "targetDim") : -1;
        assertTrue("the restored jump never completed (still in transit after " + ARRIVAL_TICKS
                + " ticks of world); last tick=" + lastTick[0], targetDim >= 0);

        // The snapshot-restored ship must load + be VS-managed in the TARGET cell. Restored arrivals paste in
        // the negative-X band (disjoint from live arrivals); the first lands near -64,200,0. This is reachable
        // ONLY through the persisted snapshot — the live ship was discarded.
        assertTrue("the snapshot-restored ship never (re)loaded in the target cell (dim " + targetDim
                + "); countAll=" + exec("artest vs ship-count-all " + targetDim), waitForLoadedShip(targetDim) >= 1);
        // The assumption this positional read rests on, CHECKED rather than stated: a slot cell
        // holds exactly one ship, so "nearest to any point" IS that ship. A second craft here would
        // make the reply below indistinguishable from a correct one.
        assertEquals("a slot cell must hold exactly ONE loaded ship for a positional read to name it",
                1, extractInt(exec("artest vs ship-count " + targetDim), "count"));
        String dstInfo = exec("artest vs ship-info " + targetDim + " -64 200 0");
        assertTrue("the restored ship is not VS-managed in the target cell (snapshot paste/assembly failed): "
                + dstInfo, dstInfo.contains("\"managed\":true"));
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
    }

    // --- helpers (mirror VSShipTransitE2ETest) ------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /**
     * Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces a load).
     *
     * <p>On the SERVER's clock, not {@code dim}'s: the world asked about is the one that may not have
     * started ticking, and budgeting against it would measure the wait with what it waits for.</p>
     */
    private int waitForLoadedShip(int dim) throws Exception {
        final int[] loaded = {0};
        GameTicks.until(client(), GameTicks.server(), LOAD_TICKS, () -> {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") < 1) {
                return false;
            }
            exec("artest vs load-ships " + dim);
            loaded[0] = extractInt(exec("artest vs ship-count " + dim), "count");
            return loaded[0] >= 1;
        });
        return loaded[0];
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
