package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipReadiness;

import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Asking for a ship that is already being loaded must load it once, never take the server down.
 *
 * <p>Two independent things can want the same unloaded ship loaded on the same tick: the world's own
 * loading pass, which queues a permanently-loaded ship for a BACKGROUND load while no player is near it,
 * and any explicit request for an immediate load. The immediate load runs first and completes; the
 * background pass then finds the ship already loaded. That is a satisfied request, not an error, but it
 * is raised as an exception from the world tick, with nothing between it and the server loop, so the
 * whole dedicated server dies.</p>
 *
 * <p><b>Why this is deterministic rather than a race.</b> The loading pass runs immediately before the
 * load queues are drained, every tick, so the background entry is minted on the very tick the immediate
 * load is served. The ordering inside that tick is fixed; there is no window to miss.</p>
 *
 * <p><b>The arrangement is the whole difficulty.</b> The ship has to be REGISTERED and NOT LOADED at the
 * moment the load is requested, which is why it is built first (a ship is created loaded), then left
 * alone until the world unloads it again, and only then is permanent loading switched on. A ship that
 * never unloaded would make both requests collapse into one and the test would measure nothing, so the
 * unloaded state is asserted, not assumed.</p>
 *
 * <p><b>Its own server, per method.</b> While the defect is live this test does not fail an assertion:
 * it kills the server process. On a shared harness that would take every later method in the class with
 * it and read as several unrelated failures.</p>
 *
 * <p><b>The survival assertion comes first, and the ship-is-loaded assertion is its control.</b> "The
 * server is still up" passes trivially on a build where the load request did nothing at all, so the
 * second assertion is what proves the request was actually served.</p>
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSDoubleQueuedShipLoadDoesNotKillTheServerE2ETest extends AbstractHeadlessServerTest {

    /**
     * The craft's base, and the altitude every pose question in this class is asked against.
     * The Y was a bare 80 with no reason attached until 2026-09-21 — neither ground nor band,
     * simply a number — and it is the band now, which is the one answer that is the same on
     * every seed.
     */
    private static final int BASE_X = 8200, BASE_Z = 8200,
            BUILD_Y = FixtureSite.OPEN_AIR_Y;

    /** How far the ship's own pose may sit from the anchor it was assembled on. */
    private static final double POSE_TOLERANCE = 64.0;

    /**
     * Budgets in SERVER TICKS — 200 is the ten seconds the old {@code 40 x 250 ms} meant on an idle
     * box. On the server's clock: what is waited for here is the tick loop SERVING two queued loads,
     * which is the subject of the whole test.
     */
    private static final int WAIT_TICKS = 200;

    /** The one pause that stays in milliseconds, and {@link #settle()} says why. */
    private static final long SETTLE_MS = 3000L;

    @Test
    public void anImmediateLoadOfAPermanentlyLoadedShipDoesNotKillTheServer() throws Exception {

        // This scenario is the one that needs BOTH states, in this order, and it says so rather than
        // inheriting either. First the ship must be allowed to unload — a test server holds its ships
        // loaded by default, and under that default the arrangement below can never be reached.
        ShipReadiness.letShipsUnload(this::exec,
                "the ship has to UNLOAD before anything can ask for it twice");

        // Marked BEFORE the build, because the unload this scenario needs happens INSIDE it —
        // measured on the gate of 2026-09-22, where a mark taken afterwards saw no `ship_unloaded`
        // for 200 ticks while the counters already read `loaded=0 registry=1`. The hull is loaded on
        // the assemble and lets go again a tick or two later, and there is no second unload to wait
        // for.
        long unloadMark = events.mark();
        buildShip();

        // The enabling condition: registered, with nothing loaded behind it. Without it the immediate
        // load and the background pass cannot both want this ship, and the test measures nothing.
        //
        // TWO STATEMENTS, because "it happened" is not "it holds" and this arrangement needs the
        // second. The LINK is the substrate's own `unload()`, named by THIS hull's physics id — the
        // poll it replaces asked "no loaded ship stands at this position any more", which a hull
        // that MOVED satisfies exactly as readily as one that unloaded. The READ is the standing
        // fact, and it is what keeps the early mark honest: `awaitField` asks whether the window
        // CONTAINS the record, so an unload from the middle of a load/unload/load sequence inside
        // the build would satisfy the link alone — and the read then says the hull is loaded now and
        // fails, instead of letting the scenario proceed with its premise false.
        events.awaitField(unloadMark, "ship_unloaded", "vsShip", shipId,
                "the ship never unloaded, so nothing here could ask for it twice: " + counters(),
                WAIT_TICKS);
        assertEquals("the ship unloaded and was loaded again, so it is NOT the unloaded-source"
                + " arrangement this scenario needs: " + counters(), 0, loadedShips());
        assertTrue("the ship left the registry as well as the loaded set, so there is nothing to load: "
                + counters(), queryableShips() >= 1);

        // ...and now the other half: permanent loading is what makes the world's own pass queue a
        // BACKGROUND load every tick this ship is unloaded, which is the second wanter.
        ShipReadiness.holdShipsLoaded(this::exec,
                "the world's pass only queues the background load while the ship is permanently"
                + " loaded and unloaded — that is the second of the two wanters");

        // Now both wanters exist: permanent loading makes the world's pass queue a background load every
        // tick this ship is unloaded, and the explicit request queues the immediate one.
        exec("artest vs load-ships 0");
        settle();

        assertTrue("asking for an immediate load of a ship the world was already loading in the "
                        + "background took the dedicated server down. A satisfied request - the ship is "
                        + "loaded, which is what both wanted - is being raised as an exception out of the "
                        + "world tick, and nothing above it catches.",
                client().isAlive());

        assertTrue("the server survived, but the ship was never loaded, so its survival says nothing "
                        + "about serving two load requests at once: " + counters(), isLoaded());
    }

    // --- arrangement --------------------------------------------------------------------------------

    private void buildShip() throws Exception {
        // Marked before the assemble: the registry add is made inside it and is announced once.
        long buildMark = events.mark();
        String coords = placeFixture(FixtureSite.openAir(0, BASE_X, BASE_Z), "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));
        // The registry's own add, naming THIS craft. The count comparison it replaces was a
        // statement about the dimension and could be satisfied by any other scenario's hull.
        events.awaitField(buildMark, "ship_spawned", "arShip",
                ShipIdentity.nameFromAssembly(asm),
                "the ship never entered the registry: " + counters(), WAIT_TICKS);
        // Taken while the craft is still loaded: the durable->physics bridge repairs its index by
        // reading flight computers, which force-loads the ship it is asked about — resolving the id
        // after the unload would undo the very arrangement it is needed for.
        shipId = ShipIdentity.physicsIdOf(this::exec, 0, ShipIdentity.nameFromAssembly(asm));
    }

    /** This hull's physics id, captured while it is loaded and used to await its unload. */
    private String shipId;

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    // --- observation --------------------------------------------------------------------------------

    private int loadedShips() throws Exception {
        return extractInt(exec("artest vs ship-count 0"), "count");
    }

    private int queryableShips() throws Exception {
        return extractInt(exec("artest vs ship-count-all 0"), "count");
    }

    private String counters() throws Exception {
        return "[loaded=" + loadedShips() + " registry=" + queryableShips() + "]";
    }

    // The pose lookup, the registry-count read and the positional unload poll that stood here are all
    // gone together: this class never cared WHERE the hull was, only that it had been registered and
    // then unloaded, and both of those are records the substrate writes about the hull by name.

    /**
     * Is a ship loaded here? A READ. {@code spawnNewShips()} and {@code loadAndUnloadShips()} run in
     * the SAME {@code tick()} invocation, so a freshly assembled craft is registered and loaded on
     * one world tick — and permanent loading is already on, so nothing drops it again.
     */
    private boolean isLoaded() throws Exception {
        return loadedShips() >= 1;
    }

    /**
     * A bounded pause for the world ticks that serve the two queued loads.
     *
     * <p><b>Deliberately WALL-CLOCK, and the only one in this sweep.</b> Everywhere else a pause in
     * seconds is the defect; here it is the requirement. This test's subject is the server DYING, and
     * a tick-budgeted pause has to ask the server what time it is — so on the build where the defect
     * is live it would throw its own "the clock stopped" out of {@code settle()}, before the test
     * reached {@code client().isAlive()} and could say what actually happened. A sleep cannot fail,
     * which is exactly why it belongs here: the instrument must outlive its subject.</p>
     */
    private void settle() throws Exception {
        Thread.sleep(SETTLE_MS);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair — a {@code clearArea} running a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} laying the blocks. The fill DUG and
     * threw away its own answer; the shared builder ASSERTS, and its fill force-loads the same
     * chunks the warmup did.</p>
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft whose load is queued twice stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
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
}
