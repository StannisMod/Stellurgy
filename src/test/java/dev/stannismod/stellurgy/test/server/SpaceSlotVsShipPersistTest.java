package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.RedWitness;
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

    /**
     * Server ticks after an immediate load request within which both wanters have been served: the
     * world's loading pass runs immediately before the load queues are drained, every tick, so the first
     * whole tick after the request queues the background load and drains both. Two, because the request
     * may arrive inside a tick already past its loading pass.
     */
    private static final int BOTH_WANTERS_SERVED_TICKS = 2;

    private static final String DIED = "asking for an immediate load of a ship the world was already loading"
            + " in the background took the dedicated server down. A satisfied request - the ship is loaded,"
            + " which is what both wanted - is being raised as an exception out of the world tick, and nothing"
            + " above it catches.";

    private static final String SLOT = "slot";

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    @Test
    public void vsShipDataSurvivesSlotUnloadReload() throws Exception {

        // Marked before the assemble, because the registry add it waits on happens inside it.
        long assembleMark = events.mark();
        String asm = arrange("stellurgytest space vs-assemble deep").raw();
        Reply mReply = Reply.of(asm);
        assertTrue("vs-assemble must report a slot dim: " + asm, mReply.has(SLOT));
        int slot = Integer.parseInt(mReply.text(SLOT));
        String ship = Reply.of("stellurgytest space vs-assemble", asm).text("shipUuid");
        shipToDispose = ship;
        dimToDispose = slot;
        // The registry's own add of THIS hull ({@code ship_spawned}, written from
        // {@code QueryableShipData.addShip}), keyed by the id the assembly handed back.
        events.awaitField(assembleMark, "ship_spawned", "vsShip", ship,
                "a VS ship must enter the pool world's registry", REGISTER_TICKS);

        // Synchronous unload (saves VS ship data) + reload the same cell; whether the reloaded
        // world registers THIS ship is read inside the same probe call (before any auto-unload),
        // so it is not masked by the keepLoaded=false world unloading between calls.
        String reload = arrange("stellurgytest space reload " + slot + " deep " + ship).raw();
        Reply reloaded = Reply.of("stellurgytest space reload", reload);
        assertTrue("slot must reload after a VS-ship unload: " + reload, reloaded.bool("present"));
        assertTrue("the VS ship's data must survive the slot unload/reload: " + reload,
                reloaded.bool("shipRegistered"));
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
    @RedWitness(verdict = "the server survives both wanters",
            member = "WorldServerShipManager#loadAndUnloadShips",
            at = "if (loadedShips.containsKey(toLoadID)) { continue; }",
            replacedBy = "if (loadedShips.containsKey(toLoadID)) { throw new IllegalStateException(\"already loaded\"); }",
            fails = "asking for an immediate load of a ship the world was already loading in the background took"
                    + " the dedicated server down",
            on = "2026-10-12")
    public void anImmediateLoadOfAPermanentlyLoadedShipDoesNotKillTheServer() throws Exception {
        final int baseX = 8200, baseZ = 8200;
        ShipReadiness.letShipsUnload(c -> arrange(c).raw(), "the ship has to UNLOAD before anything can ask for it twice");
        try {
            // Marked BEFORE the build, for both the registry add and the unload: the hull is loaded on
            // the assemble and lets go again a tick or two later, and there is no second unload to wait
            // for (measured on the gate of 2026-09-22).
            long buildMark = events.mark();
            int[] bp = RocketFixture.placeAt(FixtureSite.openAir(0, baseX, baseZ), c -> arrange(c).raw(), "with-pilot-seat",
                    4, 12, "the craft whose load is queued twice stands in this volume");
            String asm = act("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]).raw();
            ShipBirth birth = ShipBirth.ofPress(events, buildMark, 0, bp, asm, REGISTER_TICKS,
                    "the craft whose load is queued twice");
            shipToDispose = birth.physicsId;
            dimToDispose = 0;
            assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + birth,
                    birth.rocketsBorn.isEmpty());
            String shipId = birth.physicsId;

            // Registered, with nothing loaded behind it: the LINK is the substrate's own unload of THIS
            // hull, and the READ that follows is the standing fact — the early mark could otherwise be
            // satisfied by an unload the build then undid.
            events.awaitField(buildMark, "ship_unloaded", "vsShip", shipId,
                    "the ship never unloaded, so nothing here could ask for it twice: " + standing(shipId),
                    REGISTER_TICKS);
            assertTrue("the ship unloaded and was loaded again, so it is NOT the arrangement this scenario"
                    + " needs: " + standing(shipId), !ShipInfo.loadedIn(c -> read(c).raw(), 0, shipId));
            assertTrue("the ship left the registry as well as the loaded set, so there is nothing to load: "
                    + standing(shipId), registered(shipId));

            // The second wanter: permanent loading makes the world's pass queue a background load every
            // tick this ship is unloaded; the explicit request queues the immediate one.
            ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(),
                    "the world's pass only queues the background load while the ship is permanently"
                    + " loaded and unloaded — that is the second of the two wanters");
            // The window: two reads of the server's own clock around the tick on which both wanters meet.
            // The server dying inside it ends the window early, and that IS the verdict, so a clock that
            // stops answering is reported as the subject, with what it threw.
            long requested = serverTick();
            arrange("stellurgytest vs load-ship 0 " + shipId);
            long served;
            try {
                GameTicks.advance(client(), GameTicks.server(), BOTH_WANTERS_SERVED_TICKS);
                served = serverTick();
            } catch (Exception | AssertionError stopped) {
                throw new AssertionError(DIED + " The server stopped answering within "
                        + BOTH_WANTERS_SERVED_TICKS + " ticks of the request (process alive: "
                        + client().isAlive() + ").", stopped);
            }
            assertTrue("the window must span the world's loading pass after the request: server ticks "
                    + requested + " -> " + served, served - requested >= BOTH_WANTERS_SERVED_TICKS);
            assertTrue(DIED, client().isAlive());
            assertTrue("the server survived, but the ship was never loaded, so its survival says nothing "
                    + "about serving two load requests at once: " + standing(shipId),
                    ShipInfo.loadedIn(c -> read(c).raw(), 0, shipId));
        } catch (Exception | AssertionError verdict) {
            // The opt-out's end is sent to the server this scenario may have just killed; its failure then
            // rides on the verdict instead of replacing it.
            try {
                ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "this scenario's opt-out ends with it");
            } catch (Exception cleanup) {
                verdict.addSuppressed(cleanup);
            }
            throw verdict;
        }
        ShipReadiness.holdShipsLoaded(c -> arrange(c).raw(), "this scenario's opt-out ends with it");
    }

    /** The craft this scenario built, by the id its assembly named, and its world; disposed of when it ends. */
    private String shipToDispose;
    private int dimToDispose;

    @org.junit.After
    public void disposeOfTheCraft() throws Exception {
        if (shipToDispose != null) {
            Disposal.ship(c -> arrange(c).raw(), events, dimToDispose, shipToDispose);
            shipToDispose = null;
        }
    }

    private boolean registered(String shipId) throws Exception {
        return read("stellurgytest vs ship-registered 0 " + shipId).requireOk("ask the registry for " + shipId)
                .bool("registered");
    }

    /** This craft's two standings together — loaded, registered: the pair is the diagnosis. */
    private String standing(String shipId) throws Exception {
        return "[" + shipId + " loaded=" + ShipInfo.loadedIn(c -> read(c).raw(), 0, shipId)
                + " registered=" + registered(shipId) + "]";
    }
}
