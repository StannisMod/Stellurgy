package dev.stannismod.stellurgy.subsystem.network;

import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which nodes exist, per domain. A tile registers itself when it joins the world and unregisters
 * when it leaves; the manager reads a snapshot when it rebuilds.
 * <p>
 * Keyed by domain so the graphs stay apart; a node names its own domain, so registering one into
 * the wrong graph is not expressible. Synchronized because tiles are created and invalidated off
 * the tick that reads them.
 * <p>
 * One per {@link SubsystemNetworkManager}, which is one per running server: the nodes of one server
 * session are never in the set another session reads.
 */
final class SubsystemNetworkRegistry {

    private final Map<SubsystemNetworkDomain, Set<ISubsystemNetworkNode>> nodes = new HashMap<>();

    SubsystemNetworkRegistry() {
    }

    synchronized void register(ISubsystemNetworkNode node) {
        SubsystemNetworkDomain domain = node == null ? null : node.getNetworkDomain();
        if (domain == null) {
            return;
        }
        nodesOf(domain).add(node);
        log(domain, "register", node);
    }

    synchronized void unregister(ISubsystemNetworkNode node) {
        SubsystemNetworkDomain domain = node == null ? null : node.getNetworkDomain();
        if (domain == null) {
            return;
        }
        nodesOf(domain).remove(node);
        log(domain, "unregister", node);
    }

    synchronized Set<ISubsystemNetworkNode> snapshot(SubsystemNetworkDomain domain) {
        if (domain == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(nodesOf(domain)));
    }

    /** The nodes of this domain in this world that are a {@code type}, as a list the caller owns. */
    synchronized <T> List<T> nodesIn(SubsystemNetworkDomain domain, World world, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (domain == null || world == null) {
            return found;
        }
        for (ISubsystemNetworkNode node : nodesOf(domain)) {
            if (type.isInstance(node) && node.getNodeWorld() == world) {
                found.add(type.cast(node));
            }
        }
        return found;
    }

    /** Every domain that has registered a node on this server — what the manager ticks. */
    synchronized Set<SubsystemNetworkDomain> domains() {
        return new LinkedHashSet<>(nodes.keySet());
    }

    synchronized void clearWorld(SubsystemNetworkDomain domain, World world) {
        if (domain == null || world == null) {
            return;
        }
        int dim = world.provider.getDimension();
        Set<ISubsystemNetworkNode> ofDomain = nodesOf(domain);
        int before = ofDomain.size();
        ofDomain.removeIf(node -> node != null && matchesDimension(node.getNodeWorld(), dim));
        if (before != ofDomain.size() && domain.getLogger() != null) {
            domain.getLogger().info("[{}Network] clearWorld dim={} removed={} remaining={}",
                    domain.getName(), dim, before - ofDomain.size(), ofDomain.size());
        }
    }

    private Set<ISubsystemNetworkNode> nodesOf(SubsystemNetworkDomain domain) {
        return nodes.computeIfAbsent(domain, key -> new HashSet<>());
    }

    private void log(SubsystemNetworkDomain domain, String action, ISubsystemNetworkNode node) {
        if (domain.getLogger() == null) {
            return;
        }
        String worldInfo = node.getNodeWorld() == null
                ? "null"
                : "dim=" + node.getNodeWorld().provider.getDimension();
        domain.getLogger().info("[{}NetworkRegistry] {} {} pos={} {} total={}",
                domain.getName(), action, node.getClass().getSimpleName(), node.getNodePos(),
                worldInfo, nodesOf(domain).size());
    }

    private static boolean matchesDimension(World nodeWorld, int dimension) {
        return nodeWorld != null && nodeWorld.provider.getDimension() == dimension;
    }
}
