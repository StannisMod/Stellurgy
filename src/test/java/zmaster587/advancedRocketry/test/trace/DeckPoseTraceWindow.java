package zmaster587.advancedRocketry.test.trace;

import java.util.List;

/**
 * The per-tick deck-pose trace, armed for a number of client ticks by the scenario that wants it.
 *
 * <p>Recording is off by default because it is one record per craft per tick: left on, every
 * scenario with a ship in it would fill the log's ring. A scenario opens a window with a budget of
 * craft-ticks, and {@code MixinClientDeckPoseTickTrace} writes {@code client_deck_pose_tick} while any
 * open window has budget left. The budget used to be one static int on the mixin, armed through the
 * bridge by name and shared by every scenario that ever armed it.</p>
 *
 * <p>Client thread only. Test source set: absent from a released jar.</p>
 */
public final class DeckPoseTraceWindow implements TraceWindow {

    private int ticksLeft;
    private int recorded;

    private DeckPoseTraceWindow(int ticks) {
        this.ticksLeft = ticks;
    }

    /** Arm the trace for {@code ticks} craft-ticks; answers the window's handle. */
    public static int open(int ticks) {
        return SideTrace.client().open(new DeckPoseTraceWindow(ticks));
    }

    /** End the window; answers how many craft-ticks it recorded. */
    public static int close(int handle) {
        return SideTrace.client().close(handle, DeckPoseTraceWindow.class).recorded;
    }

    /**
     * Whether this craft-tick is to be recorded, spending one tick of budget from every open window
     * that still has some. One record serves every window that asked for it.
     */
    public static boolean spend() {
        List<DeckPoseTraceWindow> open = SideTrace.client().windows(DeckPoseTraceWindow.class);
        boolean any = false;
        for (int i = 0; i < open.size(); i++) {
            DeckPoseTraceWindow w = open.get(i);
            if (w.ticksLeft > 0) {
                w.ticksLeft--;
                w.recorded++;
                any = true;
            }
        }
        return any;
    }
}
