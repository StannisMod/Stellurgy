package dev.stannismod.stellurgy.subsystem.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.stannismod.stellurgy.Stellurgy;

/**
 * One server's subsystem networks: which nodes each domain has, and each world's solved topology.
 * <p>
 * <b>Owned by the SERVER, held in its {@code ServerState}.</b> Both tables describe the blocks of the
 * running server's worlds, so they live exactly as long as it does: a fresh object is built with the
 * server's state when a server is about to start — before any world loads, because a tile registers
 * itself the moment its chunk does — and dropped with it when the server has stopped. A single-player player opening a second save therefore
 * starts from nothing instead of inheriting the first save's graph under the same dimension ids.
 * <p>
 * There is no setter and no test seam. {@link SubsystemNetworkRegistry} and
 * {@link SubsystemNetworkManager} keep their static API and reach the tables through
 * {@link #current()}, which is the only route to them.
 */
public final class SubsystemNetworks {

    /** Guarded by {@link SubsystemNetworkRegistry}'s class lock: tiles register off the tick that reads. */
    final Map<SubsystemNetworkDomain, Set<ISubsystemNetworkNode>> nodes = new HashMap<>();
    /** Touched only from the server thread's world tick and world unload. */
    final Map<SubsystemNetworkDomain, Map<Integer, SubsystemNetworkManager.WorldState>> worldStates =
            new HashMap<>();

    private SubsystemNetworks() {
    }

    /** A fresh, empty set of networks for one server; built with that server's state. */
    public static SubsystemNetworks forServer() {
        return new SubsystemNetworks();
    }

    /**
     * The running server's networks.
     *
     * @throws IllegalStateException when no server is running: nothing that registers or solves a
     *                               network is meant to run outside one, so reaching here is a
     *                               lifecycle defect, reported where it happens rather than absorbed
     */
    static SubsystemNetworks current() {
        SubsystemNetworks held = Stellurgy.subsystemNetworks();
        if (held == null) {
            throw new IllegalStateException("subsystem networks were used with no server running");
        }
        return held;
    }
}
