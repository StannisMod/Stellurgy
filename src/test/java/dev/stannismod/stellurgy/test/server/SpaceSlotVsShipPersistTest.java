package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipReadiness;

import org.junit.Test;


import static org.junit.Assert.assertEquals;
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
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

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
        Reply reply = Reply.of(exec("stellurgytest space vs-count " + dim));
        return reply.has(COUNT) ? Integer.parseInt(reply.text(COUNT)) : -1;
    }

    @Test
    public void vsShipDataSurvivesSlotUnloadReload() throws Exception {

        // Marked before the assemble, because the registry add it waits on happens inside it.
        long assembleMark = events.mark();
        String asm = exec("stellurgytest space vs-assemble deep");
        Reply mReply = Reply.of(asm);
        assertTrue("vs-assemble must report a slot dim: " + asm, mReply.has(SLOT));
        int slot = Integer.parseInt(mReply.text(SLOT));

        int before = awaitRegistered(assembleMark, slot);
        assertTrue("a VS ship must enter the pool world's registry (count=" + before + ")", before >= 1);

        // Synchronous unload (saves VS ship data) + reload the same cell; the count is read inside
        // the same probe call (before any auto-unload), so it is not masked by the keepLoaded=false
        // world unloading between calls.
        String reload = exec("stellurgytest space reload " + slot + " deep");
        assertTrue("slot must reload after a VS-ship unload: " + reload, Reply.of(reload).bool("present"));
        Reply rmReply = Reply.of(reload);
        int after = rmReply.has(COUNT_AFTER) ? Integer.parseInt(rmReply.text(COUNT_AFTER)) : -99;
        assertTrue("the VS ship's data must survive the slot unload/reload: " + reload, after >= 1);
    }

    /** The overworld craft a scenario built goes with it; the slot scenario's craft lives in its own world. */
    @org.junit.After
    public void clearOverworldCraft() throws Exception {
        ShipReadiness.clearCraftFrom(this::exec, 0);
    }

    /**
     * Asking for an immediate load of a ship the world is already loading in the background loads it
     * once, and does not take the server down.
     *
     * <p>Two things can want the same unloaded ship loaded on one tick: the world's own loading pass,
     * which queues a permanently-loaded ship for a BACKGROUND load while no player is near it, and an
     * explicit request for an immediate load. The immediate load completes first; the background pass
     * then finds the ship already loaded — a satisfied request, which used to be raised as an exception
     * out of the world tick and kill the dedicated server. Deterministic, not a race: the loading pass
     * runs immediately before the load queues are drained, every tick.</p>
     *
     * <p>The ship has to be REGISTERED and NOT LOADED when the load is asked for, so it is built with
     * permanent loading off, left until the world unloads it, and only then is permanent loading put
     * back. While the defect is live this does not fail an assertion: it kills the server, and the
     * slot scenario beside it reds with the same cause — one cause, read as one.</p>
     *
     * <p>The survival assertion comes first, and the ship-is-loaded assertion is its control: "the
     * server is still up" passes trivially on a build where the load request did nothing.</p>
     */
    @Test
    public void anImmediateLoadOfAPermanentlyLoadedShipDoesNotKillTheServer() throws Exception {
        final int baseX = 8200, baseZ = 8200;
        ShipReadiness.letShipsUnload(this::exec, "the ship has to UNLOAD before anything can ask for it twice");
        try {
            // Marked BEFORE the build, for both the registry add and the unload: the hull is loaded on
            // the assemble and lets go again a tick or two later, and there is no second unload to wait
            // for (measured on the gate of 2026-09-22).
            long buildMark = events.mark();
            int[] bp = RocketFixture.placeAt(FixtureSite.openAir(0, baseX, baseZ), this::exec, "with-pilot-seat",
                    4, 12, "the craft whose load is queued twice stands in this volume");
            String asm = exec("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
            assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                    (Reply.of(asm).integer("rocketCount") == 0));
            String durable = ShipIdentity.nameFromAssembly(asm);
            events.awaitField(buildMark, "ship_spawned", "stellurgyShip", durable,
                    "the ship never entered the registry: " + overworldCounters(), REGISTER_TICKS);
            // Taken while the craft is still loaded: resolving the physics id force-loads the ship.
            String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durable);

            // Registered, with nothing loaded behind it: the LINK is the substrate's own unload of THIS
            // hull, and the READ that follows is the standing fact — the early mark could otherwise be
            // satisfied by an unload the build then undid.
            events.awaitField(buildMark, "ship_unloaded", "vsShip", shipId,
                    "the ship never unloaded, so nothing here could ask for it twice: " + overworldCounters(),
                    REGISTER_TICKS);
            assertEquals("the ship unloaded and was loaded again, so it is NOT the arrangement this scenario"
                    + " needs: " + overworldCounters(), 0, ShipReadiness.loadedCount(this::exec, 0));
            assertTrue("the ship left the registry as well as the loaded set, so there is nothing to load: "
                    + overworldCounters(), ShipReadiness.registeredCount(this::exec, 0) >= 1);

            // The second wanter: permanent loading makes the world's pass queue a background load every
            // tick this ship is unloaded; the explicit request queues the immediate one.
            ShipReadiness.holdShipsLoaded(this::exec,
                    "the world's pass only queues the background load while the ship is permanently"
                    + " loaded and unloaded — that is the second of the two wanters");
            exec("stellurgytest vs load-ships 0");
            // WALL-CLOCK on purpose, the one pause that is: the subject is the server DYING, and a
            // tick-budgeted pause asks the server what time it is, so on the defect it would throw its
            // own "the clock stopped" before isAlive could say what happened. The instrument must
            // outlive its subject.
            Thread.sleep(3000L);

            assertTrue("asking for an immediate load of a ship the world was already loading in the "
                            + "background took the dedicated server down. A satisfied request - the ship is "
                            + "loaded, which is what both wanted - is being raised as an exception out of the "
                            + "world tick, and nothing above it catches.",
                    client().isAlive());
            assertTrue("the server survived, but the ship was never loaded, so its survival says nothing "
                    + "about serving two load requests at once: " + overworldCounters(),
                    ShipReadiness.loadedCount(this::exec, 0) >= 1);
        } finally {
            ShipReadiness.holdShipsLoaded(this::exec, "this scenario's opt-out ends with it");
        }
    }

    /** Both overworld counters together: the pair is the diagnosis. */
    private String overworldCounters() throws Exception {
        return "[loaded=" + ShipReadiness.loadedCount(this::exec, 0)
                + " registry=" + ShipReadiness.registeredCount(this::exec, 0) + "]";
    }
}
