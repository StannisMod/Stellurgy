package dev.stannismod.stellurgy.test.trace;

import java.util.List;

/**
 * One armed hand-over of a mod packet into the window a client respawn opens — see
 * {@code MixinPacketThreadUtilRespawnWindow} for where that window is and why it is the one that
 * matters.
 *
 * <p>A window the scenario opens; the NEXT respawn the client queues spends it, so exactly one packet
 * is handed over per arming and a respawn some other scenario causes finds nothing armed. Spent on the
 * client's network thread and read on its client thread, so the one field is guarded.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class RespawnPacketArming implements TraceWindow {

    private boolean armed = true;

    private RespawnPacketArming() {
    }

    /** Arm one hand-over; answers the window's handle. */
    public static int open() {
        return SideTrace.client().open(new RespawnPacketArming());
    }

    /** End the window; answers {@code 1} when a respawn spent it, {@code 0} when none came. */
    public static int close(int handle) {
        RespawnPacketArming closed = SideTrace.client().close(handle, RespawnPacketArming.class);
        synchronized (closed) {
            return closed.armed ? 0 : 1;
        }
    }

    /** Spend the first armed window on this client; whether there was one. */
    public static boolean take() {
        List<RespawnPacketArming> open = SideTrace.client().windows(RespawnPacketArming.class);
        for (int i = 0; i < open.size(); i++) {
            RespawnPacketArming window = open.get(i);
            synchronized (window) {
                if (window.armed) {
                    window.armed = false;
                    return true;
                }
            }
        }
        return false;
    }
}
