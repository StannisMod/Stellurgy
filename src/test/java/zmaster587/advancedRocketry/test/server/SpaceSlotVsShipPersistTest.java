package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;

import org.junit.Test;


import static org.junit.Assert.assertTrue;

/**
 * Layer-1b: does a VS ship's DATA survive our synchronous slot unload/reload?
 *
 * <p>Assembles a small VS ship in a pool slot, confirms it enters VS's per-world queryable ship
 * registry, then unloads the slot (which must save VS's ship data) and reloads it bound to the same
 * cell — the ship must still be in the registry. This proves our {@code setWorld(null)} unload +
 * reinit does not nuke VS state and that VS's per-world save round-trips through it (the risky
 * physics-thread interaction). The full pilotable-after-rebind check needs a client and lives at the
 * client tier. Run with; skipped otherwise.</p>
 */
public class SpaceSlotVsShipPersistTest extends AbstractSharedServerTest {

    /** Ticks each of a caller's "tries" is worth - the old 500 ms per attempt. */
    /**
     * How long a DEAD substrate is given before the wait gives up — not how long the add is
     * expected to take. The registry add is what the wait links on, so an expiry here means the
     * hull never reached the registry at all, which is news about the subject.
     */
    private static final int REGISTER_TICKS = 200;

    private static final String SLOT = "slot";
    private static final String COUNT = "count";
    private static final String COUNT_AFTER = "countAfterReload";

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /**
     * Wait for the substrate's registry to take a ship, on the registry's own add.
     *
     * <p>{@code ship_spawned} is written from {@code QueryableShipData.addShip} — the very call the
     * count this replaced was counting the result of. The poll asked "how many are in there yet"
     * once per step and needed a budget to say how long it would keep asking; the record is the
     * add, so the wait ends when the registry took the hull.</p>
     *
     * <p>The mark is the CALLER's and precedes the assemble: the add is announced once.</p>
     *
     * @return the registry count afterwards, which is what the caller asserts on
     */
    private int awaitRegistered(long mark, int dim) throws Exception {
        events.await(mark, "ship_spawned",
                "a VS ship must enter the pool world's registry", REGISTER_TICKS);
        Reply reply = Reply.of(exec("artest space vs-count " + dim));
        return reply.has(COUNT) ? Integer.parseInt(reply.text(COUNT)) : -1;
    }

    @Test
    public void vsShipDataSurvivesSlotUnloadReload() throws Exception {

        // Marked before the assemble, because the registry add it waits on happens inside it.
        long assembleMark = events.mark();
        String asm = exec("artest space vs-assemble deep");
        Reply mReply = Reply.of(asm);
        assertTrue("vs-assemble must report a slot dim: " + asm, mReply.has(SLOT));
        int slot = Integer.parseInt(mReply.text(SLOT));

        int before = awaitRegistered(assembleMark, slot);
        assertTrue("a VS ship must enter the pool world's registry (count=" + before + ")", before >= 1);

        // Synchronous unload (saves VS ship data) + reload the same cell; the count is read inside
        // the same probe call (before any auto-unload), so it is not masked by the keepLoaded=false
        // world unloading between calls.
        String reload = exec("artest space reload " + slot + " deep");
        assertTrue("slot must reload after a VS-ship unload: " + reload, Reply.of(reload).bool("present"));
        Reply rmReply = Reply.of(reload);
        int after = rmReply.has(COUNT_AFTER) ? Integer.parseInt(rmReply.text(COUNT_AFTER)) : -99;
        assertTrue("the VS ship's data must survive the slot unload/reload: " + reload, after >= 1);
    }
}
