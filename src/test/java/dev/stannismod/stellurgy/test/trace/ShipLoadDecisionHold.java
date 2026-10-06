package dev.stannismod.stellurgy.test.trace;

import java.util.List;

import org.valkyrienskies.mod.common.ships.ship_world.IPhysObjectWorld;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

/**
 * A window in which the CLIENT's ship manager takes no load or unload decision — the state of a client
 * that has fallen behind its server, held on purpose for as long as a scenario needs it.
 *
 * <h2>Why a hold, and not a wait for load</h2>
 *
 * <p>The server sends a ship's unload and its later reload as two messages on two different ticks, and
 * the client queues each one when it arrives and decides on the queue once per tick of its ship
 * manager. Whether both messages reach the client before one decision depends on how far the client
 * is behind, which is a property of the box. A scenario whose subject is the decision taken on BOTH at
 * once therefore has to put the client in that state, or its verdict is a statement about machine
 * load. This window holds the decision pass and nothing else: messages still arrive and are queued,
 * chunks still arrive, loaded ships still tick.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li>{@link #open} — every decision pass of the client's ship manager is skipped from here on;</li>
 *   <li>{@link #release} — the next pass runs, and at its RETURN it writes
 *       {@code ship_load_decisions_resumed} once: the link that the decision on everything queued
 *       while held has been TAKEN, with what was loaded after it;</li>
 *   <li>{@link #close} — the window is gone. A window a failed scenario left open is dropped by
 *       {@link SideTrace#discardAll} before the next scenario, so a hold never outlives its scenario.</li>
 * </ul>
 *
 * <p>SILENT about: which messages were queued (that is {@code client_ship_load_queued}'s); whether a
 * pass would have done anything had it run; and the server, which this does not touch.</p>
 */
public final class ShipLoadDecisionHold implements TraceWindow {

    private boolean released;
    private boolean resumeReported;
    private int deferredPasses;

    private ShipLoadDecisionHold() {}

    /** Hold the client's decisions from now on; answers the window's handle. */
    public static int open() {
        ShipLoadDecisionHold w = new ShipLoadDecisionHold();
        int handle = SideTrace.client().open(w);
        w.summary("open");
        return handle;
    }

    /** Let the next decision pass run; answers how many passes were skipped while held. */
    public static int release(int handle) {
        ShipLoadDecisionHold w = SideTrace.client().window(handle, ShipLoadDecisionHold.class);
        w.released = true;
        return w.summary("release");
    }

    /** End the window; answers how many passes it skipped. */
    public static int close(int handle) {
        return SideTrace.client().close(handle, ShipLoadDecisionHold.class).summary("close");
    }

    private int summary(String phase) {
        // NO-READER-YET: read by VSDeckCaptureAndDismountTest's lagging-client reload scenario, written
        // in the same change as this window; the phase records are its failure context.
        TestTrace.recordHere("ship_load_decision_hold", "\"phase\":\"" + phase + "\",\"released\":"
                + released + ",\"deferredPasses\":" + deferredPasses);
        return deferredPasses;
    }

    /**
     * Asked at the HEAD of the client manager's decision pass: should this pass be skipped? True while
     * any window on the client is open and not yet released; each such window counts the skip.
     */
    public static boolean deferPass() {
        boolean held = false;
        List<ShipLoadDecisionHold> open = SideTrace.client().windows(ShipLoadDecisionHold.class);
        for (int i = 0; i < open.size(); i++) {
            ShipLoadDecisionHold w = open.get(i);
            if (!w.released) {
                w.deferredPasses++;
                held = true;
            }
        }
        return held;
    }

    /**
     * Called at the RETURN of a decision pass that ran: the first one after a release writes
     * {@code ship_load_decisions_resumed}, naming every ship the client holds loaded once it is done.
     */
    public static void passRan(IPhysObjectWorld manager) {
        List<ShipLoadDecisionHold> open = SideTrace.client().windows(ShipLoadDecisionHold.class);
        for (int i = 0; i < open.size(); i++) {
            ShipLoadDecisionHold w = open.get(i);
            if (w.released && !w.resumeReported) {
                w.resumeReported = true;
                // NO-READER-YET: the verdict of VSDeckCaptureAndDismountTest's lagging-client reload
                // scenario, written in the same change as this window.
                TestTrace.recordHere("ship_load_decisions_resumed", "\"deferredPasses\":"
                        + w.deferredPasses + "," + loadedIds(manager));
            }
        }
    }

    /**
     * {@code loaded}: the physics uuids, comma-separated in ONE string field — the log's readers take
     * primitives by name, and an array would read as an absent field. {@code loadedCount} beside it,
     * so an empty string is told apart from an unwritten one.
     */
    private static String loadedIds(IPhysObjectWorld manager) {
        StringBuilder ids = new StringBuilder();
        int count = 0;
        for (PhysicsObject ship : manager.getAllLoadedPhysObj()) {
            if (count++ > 0) {
                ids.append(',');
            }
            ids.append(ship.getShipData().getUuid());
        }
        return "\"loadedCount\":" + count + ",\"loaded\":\"" + ids + "\"";
    }
}
