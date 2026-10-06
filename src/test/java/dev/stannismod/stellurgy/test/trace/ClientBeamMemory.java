package dev.stannismod.stellurgy.test.trace;

import java.util.HashMap;
import java.util.Map;

/**
 * What the client beam recorder last said about each gun: the number of points of the path it last
 * reported as drawn, keyed by the gun's packed position. Absent = not drawn as far as the recorder
 * has said. One per client, in its {@link SideTrace}: the tracker it shadows is the client's own.
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ClientBeamMemory {

    public final Map<Long, Integer> drawnPoints = new HashMap<>();

    /** The client's memory. Only ever called on the client thread. */
    public static ClientBeamMemory get() {
        return SideTrace.client().memory(ClientBeamMemory.class, ClientBeamMemory::new);
    }
}
