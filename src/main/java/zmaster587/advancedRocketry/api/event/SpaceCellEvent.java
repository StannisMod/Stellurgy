package zmaster587.advancedRocketry.api.event;

import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Events about a SPACE CELL — a region of the galaxy the subsystem keeps on disk between visits.
 *
 * <h2>Server side</h2>
 *
 * <p>Posted on the server only. A cell's store is server state; a client knows a cell by the slot
 * world it is currently bound to, which is a different and shorter-lived thing.</p>
 *
 * <h2>The base is concrete, and Forge requires that</h2>
 *
 * <p>1.12.2's {@code EventBus.register} INSTANTIATES the event class to reach its listener list, so
 * subscribing to an abstract event throws {@code InstantiationException} — and takes every other
 * handler on the registering object down with it. The constructor is package-private, so nothing
 * outside this package can post a bare base; subscribing to one is the point.</p>
 */
public class SpaceCellEvent extends Event {

    /** The cell, by the key its store and its ledger rows are filed under. */
    public final String cellKey;

    SpaceCellEvent(String cellKey) {
        this.cellKey = cellKey;
    }

    /** Why the collector picked this cell. */
    public enum Reason {
        /** Nothing has visited it for longer than the configured maximum age. */
        AGE,
        /** The store holds more cells than the configured maximum, and this is the oldest candidate. */
        COUNT
    }

    /**
     * A stored cell is ABOUT TO BE DELETED from disk. Cancel to keep it.
     *
     * <p><b>There is deliberately no {@code Post}.</b> After the delete there is nothing left to act
     * on and nothing a listener could do about it: the cell's contents are gone, and a notification
     * whose only honest use is logging is not worth an API. The moment that carries any control is
     * this one.</p>
     *
     * <p><b>What a cancel means, precisely.</b> The cell is not collected on THIS pass. It stays a
     * candidate: an age-driven sweep will consider it again next time, and a count-driven one will
     * move on to the next oldest rather than deleting nothing — so a listener that vetoes
     * unconditionally is holding the store above its configured ceiling, on purpose, and should
     * expect the pressure to surface elsewhere.</p>
     *
     * <p><b>What the collector already refuses to touch, so a listener need not.</b> A cell that is
     * loaded, or claimed by a parked ship, is never a candidate at all — this event fires only for
     * cells the subsystem believes nobody is using. That belief is the interesting part: what it
     * cannot see is whether a player INVESTED in the cell as opposed to merely having been there,
     * because a cell is marked dirty by mining an asteroid exactly as it is by building a base. A
     * listener that knows the difference is what this event exists for.</p>
     */
    @Cancelable
    public static class CollectPre extends SpaceCellEvent {
        /** Why the collector picked it. */
        public final Reason reason;
        /**
         * Ticks of the SPACE clock since anything last visited, or {@link Long#MIN_VALUE} when the
         * collector did not compute one — a count-driven sweep picks by relative age and never needs
         * the absolute figure.
         */
        public final long ticksSinceVisit;

        public CollectPre(String cellKey, Reason reason, long ticksSinceVisit) {
            super(cellKey);
            this.reason = reason;
            this.ticksSinceVisit = ticksSinceVisit;
        }
    }
}
