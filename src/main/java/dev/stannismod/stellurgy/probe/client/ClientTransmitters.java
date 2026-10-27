package dev.stannismod.stellurgy.probe.client;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The transmitters a client world has been told about: what a radio held in it hears. Held by the client
 * world ({@code ClientWorldDrawings}); born empty with it, replaced whole by each list the server sends.
 */
public final class ClientTransmitters {

    private final List<BlockPos> positions = new ArrayList<>();

    /** The server's list of this world's transmitters, replacing the last. */
    public void told(List<BlockPos> transmitters) {
        positions.clear();
        positions.addAll(transmitters);
    }

    public List<BlockPos> positions() {
        return Collections.unmodifiableList(positions);
    }
}
