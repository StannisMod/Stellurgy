package dev.stannismod.stellurgy.affs.world.shield;

import dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem;
import dev.stannismod.stellurgy.world.WorldRuntime;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The shield network nodes loaded in one world. Owned by that world ({@link WorldRuntime}), so it
 * goes with the world: a server stop unloads worlds without unloading their chunks, and nothing of
 * one world's network can reach a later world that reuses its dimension number.
 */
public final class ShieldNetworkRegistry {

    private final Set<IShieldNetworkNode> nodes = new HashSet<>();

    private ShieldNetworkRegistry() {
    }

    /** {@code world}'s registry. */
    public static ShieldNetworkRegistry of(World world) {
        return WorldRuntime.of(world, ShieldNetworkRegistry.class, ShieldNetworkRegistry::new);
    }

    public synchronized void register(IShieldNetworkNode node) {
        if (node != null) {
            nodes.add(node);
            log("register", node);
        }
    }

    public synchronized void unregister(IShieldNetworkNode node) {
        if (node != null) {
            nodes.remove(node);
            log("unregister", node);
        }
    }

    public synchronized Set<IShieldNetworkNode> snapshot() {
        return Collections.unmodifiableSet(new HashSet<>(nodes));
    }

    private void log(String action, IShieldNetworkNode node) {
        String worldInfo = node.getNodeWorld() == null ? "null" : "dim=" + node.getNodeWorld().provider.getDimension();
        logMessage(action + " " + node.getClass().getSimpleName() + " pos=" + node.getNodePos() + " " + worldInfo + " total=" + nodes.size());
    }

    private static void logMessage(String message) {
        if (AdvancedForceFieldSystem.LOG != null) {
            AdvancedForceFieldSystem.LOG.info("[ShieldNetworkRegistry] {}", message);
        }
    }
}
