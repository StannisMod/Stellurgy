package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.awaitEnteredSpace;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * E2E: does the tier-2 PLANET DESCENT take a ship in space across into a real planet dimension through the
 * built crossing? This composes on the entry on-ramp: a {@code with-pilot-seat} ship is assembled and
 * ENTERED into a slot cell (the proven entry path settles it in the {@code ShipLedger}), then the
 * production {@code DescentController.requestDescent} is driven for that settled ship toward the overworld
 * (dim 0 — a guaranteed real dimension with terrain). The descent runs the SAME generalized
 * {@code ShipCrossingService} the entry uses: it cuts the ship out of its space cell (releasing it from the
 * ledger) and pastes it, terrain-aware, into the destination dim, where it re-assembles.
 *
 * <p>Witnesses: BEFORE descent the overworld has no VS ship (entry cut the source out); AFTER descent a VS
 * ship is loaded in the overworld and the ship has left the ledger. The proximity TRIGGER predicate is
 * pinned separately + deterministically by {@code DescentControllerTest}; this e2e is the "the crossing
 * physically moves a settled ship into a planet dim" acceptance. Gated on the server's real VS presence
 * (run with); skips cleanly otherwise.</p>
 */
public class VSShipDescentTest extends AbstractSharedServerTest {


    /** A loaded overworld region distinct from the entry e2e's, well clear of other tests. */
    private static final int SRC_X = 6400, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 6400;
    /** The descent target: the overworld — always registered, terrain-generated. */
    private static final int TARGET_DIM = 0;

    /**
     * Budgets in SERVER TICKS, none of them fork-scaled: 600 is the thirty seconds the old
     * 120 x 250 ms meant on an idle box, 200 the ten seconds of 40 x 250 ms.
     */
    private static final int SETTLE_TICKS = 600;
    private static final int FIND_AFC_TICKS = 200;

    @Test
    public void aSettledShipDescendsIntoAPlanetDimViaTheCrossing() throws Exception {

        String setup = arrange("stellurgytest space entry-setup 2").raw();
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        // --- Phase 1: ENTER a ship so it is settled in a slot cell (the proven entry path). ---
        int[] bp = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-seat");
        buildMark = events.mark();
        String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, bp, asm, FIND_AFC_TICKS,
                "the craft that enters space and descends");
        builtDurable = birth.durable;
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());

        // The ship's own name, from the assembler that minted it — and from there its physics id, once
        // its own announcement says it is usable.
        String shipId = birth.durable;
        String vsId = ArrangementFailure.arranged(
                () -> birth.awaitUsable(c -> read(c).raw(), events, FIND_AFC_TICKS));

        ShipInfo src = ShipInfo.byId(c -> read(c).raw(), 0, vsId);
        double sx = src.x, sy = src.y, sz = src.z;

        // A held throttle on THIS ship's own flight computer => a pilot is flying.
        String held = arrange("stellurgytest vs ff-input-by-id 0 " + vsId + " 0 1 0 0 0 0").raw();
        assertTrue("the held input must reach this ship's flight computer: " + held,
                Reply.of(held).bool("afcResolved"));
        String tp = arrange("stellurgytest vs teleport-ship-by-id 0 " + vsId + " "
                + (int) sx + " " + OrbitLine.of(c -> read(c).raw(), 0).aboveEntryCeiling() + " " + (int) sz).raw();
        assertTrue("climb teleport failed: " + tp, Reply.of(tp).ok());
        // Marked before the unpark, which is what lets the entry start: the arrival is announced
        // once, and a mark taken after it would wait for a second entry.
        long entryMark = events.mark();
        arrange("stellurgytest vs unpark-by-id 0 " + vsId);

        // Linked on the record the entry publishes at its settle, rather than reading the ledger row
        // over and over until it agrees.
        awaitEnteredSpace(events, entryMark, shipId,
                "precondition: the ship must enter space, or there is nothing to descend from",
                SETTLE_TICKS);
        EntryStatus entryStatus = EntryStatus.forShip(c -> read(c).raw(), shipId).requireFound(
                "the arrival was announced, so the ledger must hold this craft's row");
        int slotDim = entryStatus.slotDim;
        assertTrue("settled slot dim not reported: " + entryStatus.raw(),
                slotDim > Integer.MIN_VALUE);

        // --- Phase 2: DESCEND that settled ship into the overworld. ---
        // CONTROL: this craft is not registered in the overworld now (the entry cut it out), so its
        // arrival there below is the descent's.
        String inTarget = read("stellurgytest vs ship-uuid " + TARGET_DIM + " " + shipId).raw();
        assertTrue("witness sensitivity control — this craft must not be in the overworld before the"
                + " descent: " + inTarget, !Reply.of(inTarget).bool("found"));

        // Nothing to stop feeding: the pilot input lives on the SHIP's own flight computer, and that
        // tile was cut out of dim 0 by the entry above. This used to clear a world-wide static that
        // would otherwise have followed the ship into space and every later scenario with it.

        // Ensure the settled ship is loaded in its slot, then locate its flight computer. The ship's
        // blocks (incl. the AFC tile entity) live in the slot world's far subspace shipyard; they enter
        // loadedTileEntityList only once VS loads the ship, so force-load + poll (async load).
        // Linked on this craft's own announcement that it is usable in its slot, then asked for by
        // its id. By id: this scenario knows which ship it flew up, and "the first settled ship in the
        // slot" is a different question that happens to have the same answer today.
        String slotVsId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(),
                events, slotDim, shipId, FIND_AFC_TICKS));
        arrange("stellurgytest vs load-ship " + slotDim + " " + slotVsId).requireOk("ask for the settled ship");
        String afc = read("stellurgytest space find-afc " + slotDim + " " + shipId).raw();
        // `found` is missing only from the probe's "world or ledger not ready" reply, and
        // absence is the answer: not located, failing here with that reply.
        assertTrue("could not locate the ship's flight computer in the slot " + slotDim
                        + " although the ship is usable there as " + slotVsId + ": " + afc,
                Reply.of(afc).boolOr("found", false));
        int ax = extractInt(afc, "x"), ay = extractInt(afc, "y"), az = extractInt(afc, "z");

        // Marked BEFORE the command whose effect is awaited.
        long descentMark = events.mark();
        String begin = act("stellurgytest space descent-begin " + slotDim + " " + ax + " " + ay + " " + az
                + " " + shipId + " " + TARGET_DIM).raw();
        assertTrue("descent did not start: " + begin, Reply.of(begin).bool("started"));

        // The cut dropped the ship from the ledger at once (it has left the subsystem).
        EntryStatus afterCut = EntryStatus.forShip(c -> read(c).raw(), shipId);
        assertTrue("the descending ship leaves the ledger on the cut: " + afterCut, !afterCut.found);

        // The crossing re-assembles the ship in the planet's world asynchronously, and production
        // announces when it has: this waits for THAT, by id, instead of sampling the loaded count
        // until it happens to be non-zero. A count cannot tell "it never arrived" from "it arrived
        // and was unloaded again before this read"; the record can, and on failure it prints the
        // chain instead of a number. The `descent-status` poll this replaces was a pure read of a
        // counter -- diagnostic only, so nothing is lost by dropping it.
        events.awaitField(descentMark, "ship_entered_planet","ship", shipId,
                "the ship never crossed into the planet's dimension via the descent",
                SETTLE_TICKS);
    }

    /** The mark taken before this scenario's build, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    /** The craft goes first, wherever its latest naming put it; the entry stack after it. */
    @After
    public void cleanup() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
        arrange("stellurgytest space entry-clear");
    }

    // --- helpers (mirror VSShipEntryTest) --------------------------------------------------------

    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());



    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private int[] placeFixture(FixtureSite site, String variant) throws Exception {
        return RocketFixture.placeAt(site, c -> arrange(c).raw(), variant, 4, 12,
                "the craft this scenario builds stands in this volume");
    }

    private static int extractInt(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, 0.0);
    }

    private static String extractString(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).textOr(key, null);
    }
}
