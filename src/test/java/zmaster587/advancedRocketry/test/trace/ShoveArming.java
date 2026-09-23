package zmaster587.advancedRocketry.test.trace;

import java.util.List;

/**
 * One armed shove of a captured body, added inside the client's own travel commit — see
 * {@code MixinShipFrameTravelShove} for why it has to be applied there and nowhere else.
 *
 * <p>A window the scenario opens with the number of blocks, consumed by the NEXT commit and then
 * spent: one shove per arming, so a test can say exactly when it happens. The pending shove was a
 * static int on the mixin until 2026-09-23, armed by name through the bridge and left armed for
 * whichever scenario next reached a commit if the one that armed it never did.</p>
 *
 * <p>Client thread only. Test source set: absent from a released jar.</p>
 */
public final class ShoveArming implements TraceWindow {

    private int pendingBlocks;

    private ShoveArming(int blocks) {
        this.pendingBlocks = blocks;
    }

    /** Arm one shove of {@code blocks}; answers the window's handle. */
    public static int open(int blocks) {
        return SideTrace.client().open(new ShoveArming(blocks));
    }

    /** End the window; answers the blocks it still had pending (0 once the shove was taken). */
    public static int close(int handle) {
        return SideTrace.client().close(handle, ShoveArming.class).pendingBlocks;
    }

    /** The blocks of the first pending shove on this client, spending it; 0 when none is armed. */
    public static int take() {
        List<ShoveArming> open = SideTrace.client().windows(ShoveArming.class);
        for (int i = 0; i < open.size(); i++) {
            ShoveArming w = open.get(i);
            if (w.pendingBlocks != 0) {
                int blocks = w.pendingBlocks;
                w.pendingBlocks = 0;
                return blocks;
            }
        }
        return 0;
    }
}
