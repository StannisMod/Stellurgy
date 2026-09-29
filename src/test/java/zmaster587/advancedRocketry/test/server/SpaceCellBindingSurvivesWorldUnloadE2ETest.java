package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.MaterializedCell;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Server e2e for the contract that makes a cell&rarr;slot binding usable: <b>while a cell is bound to
 * a slot, that slot has a world</b>. Every consumer that needs a ship's dimension derives it from the
 * binding, so a binding whose world has gone hands out a dimension id with nothing behind it, and the
 * caller reads that as "the ship is not there" — which is how a jump into a cell the ship had left
 * earlier could fail its arrival two hundred times in silence.
 *
 * <p>A cell with no occupant stays bound on purpose (eviction is lazy, so a revisit is cheap), and on
 * 1.12.2 Forge removes a player-less, chunk-less dimension at tick end without any call through the
 * pool's own load/unload seam. Two defences, one leg each:</p>
 *
 * <ol>
 *   <li><b>The pool holds its worlds</b> — Forge cannot take a slot a cell is bound to.</li>
 *   <li><b>The controller repairs a binding anyway</b> — if the world goes by any other route, the next
 *       materialize re-initialises the slot against the same cell instead of handing out a dead id.</li>
 * </ol>
 *
 * <p>Leg order matters. The unheld leg runs FIRST and its world really does disappear: that is the
 * positive control which makes the held leg's "the world is still there" a measurement rather than a
 * sentence that would also pass if Forge's sweep were absent, or if this test's stimulus never reached
 * it. This is also deliberately the only space probe sequence that spans TICKS — the others collapse
 * into one synchronous call so the sweep cannot intervene, which is why none of them can see it.</p>
 */
public class SpaceCellBindingSurvivesWorldUnloadE2ETest extends AbstractSharedServerTest {

    /** Cells no other test occupies, so the pool pressure here is only this test's. */
    private static final String UNHELD_CELL = "910 4 910";
    private static final String HELD_CELL = "911 4 911";

    /** Bounded per the probe-authoring wall-time rule; Forge's sweep needs a handful of ticks. */
    /** World Forge's unload sweep is given to collect an unheld slot - the old 10 000 ms. */
    private static final int SWEEP_BUDGET_TICKS = 200;

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void aBoundCellKeepsItsWorldAndARevisitRepairsOneThatWentAnyway() throws Exception {
        // ── Leg 1: the control. With the pool's hold cleared, Forge's sweep takes the world. ──
        MaterializedCell occupied = MaterializedCell.at(this::exec, UNHELD_CELL)
                .requireMaterialized("the cell must materialize");
        assertTrue("a freshly materialized cell must have a world: " + occupied.raw(),
                occupied.worldLoaded());

        // Marked before the hold is dropped, which is what lets the sweep take the world.
        long unheldMark = events.mark();
        String dropped = exec("artest space release " + UNHELD_CELL + " drop-hold");
        assertTrue("release must clear the hold: " + dropped, Reply.of(dropped).bool("holdDropped"));

        String gone = awaitWorldGone(unheldMark, UNHELD_CELL, occupied.slotDim());
        assertTrue("the manager must still count the released cell as loaded — a cell with no occupant "
                        + "stays bound so a revisit is cheap: " + gone,
                Reply.of(gone).bool("managerLoaded"));

        // ── Leg 2: the repair. A binding whose world went away is live again on the next visit. ──
        MaterializedCell revisit = MaterializedCell.at(this::exec, UNHELD_CELL)
                .requireMaterialized("the revisit must materialize the cell again");
        assertTrue("materializing a cell must leave it live in a world, whatever happened to the slot "
                        + "while nobody was occupying it: " + revisit.raw(),
                revisit.worldLoaded());

        // ── Leg 3: the hold. Same sequence, hold left in place: the sweep must not get this one. ──
        MaterializedCell held = MaterializedCell.at(this::exec, HELD_CELL)
                .requireMaterialized("the second cell must materialize");
        assertTrue("and it must be live in a world: " + held.raw(), held.worldLoaded());
        long heldMark = events.mark();
        exec("artest space release " + HELD_CELL);

        String unloads = unloadRecordsOver(heldMark);
        assertEquals("a cell still bound to its slot must keep that slot's world for the whole"
                        + " stretch that removed the unheld one — not merely be loaded again at the"
                        + " end of it. `world_unloaded` from seq " + heldMark + " across "
                        + SWEEP_BUDGET_TICKS + " server ticks: " + unloads,
                0, Events.countRecords(unloads, "dim", String.valueOf(held.slotDim())));
        String stillThere = exec("artest space cell-slot " + HELD_CELL);
        assertTrue("a cell still bound to its slot must keep that slot's world, even with no occupant, "
                        + "no player and no chunks — leg 1 proves the sweep would otherwise take it: "
                        + stillThere,
                Reply.of(stillThere).bool("worldLoaded"));
    }

    /**
     * Wait for Forge's sweep to take this cell's slot world, on the record it publishes.
     *
     * <p>{@code WorldEvent.Unload} is posted from {@code DimensionManager.unloadWorlds} after the
     * world is saved and dropped, so the record IS the unload rather than a sample that happened to
     * be taken after it. The poll this replaced asked "is the slot still loaded" one reading at a
     * time and needed a budget to say how long it was willing to keep asking.</p>
     *
     * <p>The mark is taken by the CALLER, before the act that releases the hold: an unload is
     * announced once.</p>
     *
     * @return the cell-slot reading taken after the unload, for the caller's own assertions
     */
    private String awaitWorldGone(long mark, String cell, int slotDim) throws Exception {
        events.awaitField(mark, "world_unloaded", "dim", slotDim,
                "Forge must unload the unheld cell's slot world, or this run's control leg"
                        + " exercised nothing and the held leg below would pass on any build",
                SWEEP_BUDGET_TICKS);
        return exec("artest space cell-slot " + cell);
    }

    /**
     * Every {@code world_unloaded} from {@code mark} to the end of a stretch as long as the whole
     * budget leg 1's sweep was allowed — and so at least as long as that sweep actually took.
     *
     * <p>A window, not a wait, and the difference is the whole leg: the claim is that nothing took
     * this world, which is a statement about a stretch of time rather than about one moment. The
     * version this replaces spent the budget polling and then asserted on the LAST reading — which
     * is green for a world that was unloaded and re-materialized inside the window.</p>
     */
    private String unloadRecordsOver(long mark) throws Exception {
        // WINDOW: the log is read from the caller's mark to the end of this stretch, and the caller
        // asserts over everything in between, naming both ends. Overshoot only widens the stretch
        // the sweep had to take the held world in, which can turn a green red and never the reverse.
        GameTicks.advance(client(), GameTicks.server(), SWEEP_BUDGET_TICKS);
        return events.since(mark, "world_unloaded");
    }
}
