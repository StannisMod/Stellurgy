package zmaster587.advancedRocketry.test.trace;

import java.lang.ref.WeakReference;

import net.minecraft.world.World;

/**
 * The last atmosphere readout the CLIENT recorded, and the world it was composed in — the client's,
 * kept in its {@link SideTrace}. The HUD recomposes the readout every frame, so only a change is an
 * edge; and a readout in a world this memo has not seen (a dimension change, a relog) is an edge
 * whatever the previous world's last one was, or a test awaiting the first readout after a reconnect
 * would wait for a record that was filtered against a session that has ended.
 *
 * <p>The world is held weakly, so a disconnected world is still collected.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class AtmosphereReadoutMemory {

    private WeakReference<World> world;
    private String readout;

    /** The client's memory. */
    public static AtmosphereReadoutMemory client() {
        return SideTrace.client().memory(AtmosphereReadoutMemory.class, AtmosphereReadoutMemory::new);
    }

    /** Whether {@code payload}, composed in {@code w}, is an edge worth recording; remembers it. */
    public boolean isEdge(World w, String payload) {
        World memo = world == null ? null : world.get();
        if (memo != w) {
            world = new WeakReference<>(w);
        } else if (payload.equals(readout)) {
            return false;
        }
        readout = payload;
        return true;
    }
}
