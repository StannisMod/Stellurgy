package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.StellurgyTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertTrue;

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
 * <p>Complements {@code VSShipTransitTest} (the LIVE hyperspace crossing) and the deterministic
 * {@code ShipTransitManagerTest} (the restored state machine with a fake crosser). Gated on the server's
 * real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSShipTransitPersistTest extends AbstractSharedServerTest {

    private static final int ARRIVAL_TICKS = 400;

    @Test
    public void aRestoredInFlightJumpRebuildsItsShipByPastingItsSnapshotIntoTheTargetCell() throws Exception {

        // Headless: pin ships loaded so a freshly assembled ship does not auto-unload between probe calls.

        // Build a real craft in a fresh origin cell (a pool slot world) + the whole transit stack.
        buildMark = events.mark();
        TransitSetup setup = TransitSetup.piloted(c -> arrange(c).raw());
        builtDurable = setup.requireDurableId();
        int originDim = setup.originDim;
        int ax = setup.anchorX, ay = setup.anchorY, az = setup.anchorZ;
        ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events,
                originDim, setup.requireDurableId(), USABLE_TICKS));

        // Depart into hyperspace. We deliberately do NOT tick the transit yet: it stays parked in hyperspace
        // while we re-cut its snapshot (the save-point cut is of a PARKED ship).
        // Marked BEFORE the command whose effect is awaited.
        long transitMark = events.mark();
        String begin = act("stellurgytest space transit-begin " + originDim + " " + ax + " " + ay + " " + az
                + " " + HYPERSPACE_JUMP_SPEED).raw();
        assertTrue("transit did not begin (departure crossing failed): " + begin, Reply.of(begin).bool("began"));

        // Re-cut the parked ship's block snapshot, ONCE.
        //
        // Read the REFRESH's own count, not the export's hasSnapshot. Every transit carries a
        // snapshot from the instant it departs — the floor cut of the source ship, taken before the
        // departure crossing so that a save in the pre-assembly window is never snapshot-less — so
        // hasSnapshot is true whether or not hyperspace was ever read, and asserting on it would
        // make the claim that the PARKED ship was re-cut a statement about a cut that never
        // happened.
        //
        // This used to be a retry loop, described as waiting for "the async hyperspace assembly".
        // It was not an observation: every iteration PERFORMED the refresh, so the loop retried an
        // operation rather than waiting for one — and the operation succeeds on its first attempt
        // (measured across this tier at one fork and at six). A retry around a synchronous command
        // turns "it failed" into "it failed several times", which is the same verdict later and with
        // less to say about why.
        String lastRefresh = act("stellurgytest space transit-refresh").raw();
        assertTrue("the parked hyperspace ship was never re-cut into a persisted snapshot; last="
                + lastRefresh, extractInt(lastRefresh, "refreshed") >= 1);

        String lastExport = read("stellurgytest space transit-export").raw();
        assertTrue("the durable record must carry a block snapshot, or the restore below has no ship to "
                + "paste: " + lastExport, Reply.of(lastExport).bool("hasSnapshot"));

        // Simulate a restart in-process: rebuild the transit from the exported record alone (the live
        // manager, and its parked hyperspace ship, are thrown away). Note: this reuses the in-memory record;
        // the on-disk NBT round-trip is unit-pinned separately (ShipLedgerDataTest + TransitRecordTest).
        String restore = arrange("stellurgytest space transit-restore").raw();
        assertTrue("restore did not recreate the in-flight transit: " + restore,
                (Reply.of(restore).integer("inTransit") == 1));

        // The RESTORED transit arrives on the server's own tick, like any other -- no pump. With no
        // live hyperspace ship it can only get there by pasting its snapshot into the target cell,
        // so the arrival production announces IS the proof that path ran.
        String arrived = events.awaitRecordWithFields(transitMark, "ship_transit_ended",
                "the restored jump never completed; the durable record now reads "
                        + read("stellurgytest space transit-export").raw(),
                ARRIVAL_TICKS, "ship", setup.requireDurableId(), "route", "HYPERSPACE");
        int targetDim = extractInt(arrived, "dim");
        assertTrue("the arrival was announced but names no dimension: " + arrived, targetDim >= 0);

        // The snapshot-restored ship must load + be VS-managed in the TARGET cell. Restored arrivals paste in
        // the negative-X band (disjoint from live arrivals); the first lands near -64,200,0. This is reachable
        // ONLY through the persisted snapshot — the live ship was discarded.
        // Named rather than approached: THIS craft, by the durable name the restored jump carried,
        // linked on its own announcement that it is usable in the target cell.
        String arrivedId = ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), events, targetDim,
                setup.requireDurableId(), USABLE_TICKS);
        assertTrue("the restored ship is not VS-managed in the target cell (snapshot paste/assembly"
                + " failed); id=" + arrivedId,
                ShipInfo.loadedIn(c -> read(c).raw(), targetDim, arrivedId));
    }

    /** Server ticks for the craft to become usable where it was built or has arrived. */
    private static final int USABLE_TICKS = 200;

    /** The mark taken before this scenario's setup, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
    }

    // --- helpers (mirror VSShipTransitTest) ------------------------------------------------------

    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());

    private static int extractInt(String json, String key) {
        return Reply.of(json).integer(key);
    }
}
