package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.MaterializedCell;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

        String dropped = exec("artest space release " + UNHELD_CELL + " drop-hold");
        assertTrue("release must clear the hold: " + dropped, Reply.of(dropped).bool("holdDropped"));

        String gone = awaitWorld(UNHELD_CELL, false);
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
        exec("artest space release " + HELD_CELL);

        String stillThere = awaitWorld(HELD_CELL, true);
        assertTrue("a cell still bound to its slot must keep that slot's world, even with no occupant, "
                        + "no player and no chunks — leg 1 proves the sweep would otherwise take it: "
                        + stillThere,
                Reply.of(stillThere).bool("worldLoaded"));
    }

    /**
     * Poll the cell's slot for the whole sweep budget. With {@code expectLoaded} false this returns as
     * soon as the world is gone and fails if it never goes; with it true the budget is spent in full and
     * the last response is returned, so "still there" means still there after the same wait that removed
     * the unheld one.
     */
    private String awaitWorld(String cell, boolean expectLoaded) throws Exception {
        // Forge's unload sweep runs ON the server tick, so the budget is that tick's world - a
        // wall-clock ceiling gave the sweep fewer chances to run exactly when the box was busy.
        final String[] last = {""};
        boolean unloaded = GameTicks.until(client(), GameTicks.server(), SWEEP_BUDGET_TICKS, () -> {
            last[0] = exec("artest space cell-slot " + cell);
            // absence is the answer: this is a WAIT for the sweep to drop the world, and a
            // cell whose slot is gone answers no `worldLoaded` at all.
            return !expectLoaded && (!Reply.of(last[0]).boolOr("worldLoaded", false));
        });
        if (unloaded) {
            return last[0];
        }
        if (!expectLoaded) {
            fail("Forge never unloaded the unheld cell's slot world within " + SWEEP_BUDGET_TICKS
                    + " ticks, so this run's control leg exercised nothing and the held leg below "
                    + "would pass on any build. Last: " + last[0]);
        }
        return last[0];
    }
}
