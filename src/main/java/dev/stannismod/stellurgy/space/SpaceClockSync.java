package dev.stannismod.stellurgy.space;

/**
 * A client's copy of the space clock.
 *
 * <p>The space clock is the SERVER's counter — the space subsystem's own, advanced once per server
 * tick and persisted with the subsystem's saved state. A client has no access to it and no way to
 * derive it: the only counters it is told about are its worlds' own total times, and Advanced
 * Rocketry gives every non-overworld dimension a clock that only advances while that dimension
 * ticks. So a client asking "what time is it, for the space subsystem" through any world it can see
 * gets an answer that belongs to a different clock — which is precisely the defect this class
 * exists to make unnecessary. Since the server's counter stopped being any world's, that is no
 * longer even approximately recoverable client-side; this class is the only route.</p>
 *
 * <p><b>Baseline plus local advance, not a packet per tick.</b> The clock is monotonic at a fixed
 * rate, so the whole of it is one number plus elapsed ticks: a sync sets the baseline, and every
 * client tick after it adds one. Re-syncs are periodic and exist only to bound the drift between
 * the client's tick rate and a server that is running behind.</p>
 *
 * <p><b>Drift budget.</b> A moon travels about half a block per tick, and the descent trigger is
 * hundreds of blocks wide, so hundreds of ticks of drift are harmless here. A re-sync MAY correct
 * the value BACKWARDS — the server's counter is the truth and a client that ran ahead is simply
 * wrong. Nothing may assume this value never decreases.</p>
 *
 * <p>Before the first sync arrives the answer is {@code 0}; {@link #hasSync()} distinguishes "not told
 * yet" from "told it is tick zero".</p>
 *
 * <p>One per connection: the client's view of the server it is connected to owns it, so the next
 * server's counter starts from "not told yet" rather than from this one's baseline. Plain state, no
 * Minecraft types, so it loads on a dedicated server like any other common class.</p>
 */
public final class SpaceClockSync {

    /** No baseline has been received. Distinguishable from a legitimate tick 0. */
    private static final long NO_SYNC = Long.MIN_VALUE;

    private long baseTick = NO_SYNC;
    private long baseLocal;
    private long localTicks;

    /** Take a fresh baseline from the server. */
    public void accept(long serverTick) {
        baseTick = serverTick;
        baseLocal = localTicks;
    }

    /** One client tick elapsed. */
    public void onClientTick() {
        localTicks++;
    }

    /** Whether a baseline has ever arrived. */
    public boolean hasSync() {
        return baseTick != NO_SYNC;
    }

    /** The space clock as this client currently believes it, or {@code 0} before the first sync. */
    public long now() {
        return baseTick == NO_SYNC ? 0L : baseTick + (localTicks - baseLocal);
    }
}
