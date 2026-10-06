package dev.stannismod.stellurgy.test.mixin;

import java.util.Set;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkNode;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A node leaving the server's network registry, as an event: {@code subsystem_node_unregistered}.
 *
 * <p>The RETURN of the registry's {@code unregister}, which a node's tile calls when it is broken or
 * its chunk unloads — the moment it stops being one of the nodes "loaded in" its world. Carries the
 * domain's {@code getName()}, the node's {@code dim} and {@code x}/{@code y}/{@code z}, and
 * {@code released}: whether the node is absent from the domain's set once the call returns, read off
 * the set itself. A reader that unloads one node names it by position and waits for its record,
 * rather than for any rebuild of the world's network. Server log. Read by
 * {@code ShieldTwoBlockFloorTest}.</p>
 *
 * <p>SILENT about {@code clearWorld}, which drops a whole world's nodes when that world goes away and
 * names none of them.</p>
 */
@Mixin(targets = "dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkRegistry")
public abstract class MixinSubsystemNodeReleaseEvents {

    private static final String INSTRUMENT = "subsystem_node_release_events";

    @Shadow
    abstract Set<ISubsystemNetworkNode> snapshot(SubsystemNetworkDomain domain);

    @Inject(method = "unregister", at = @At("RETURN"), require = 1)
    private void stellurgyTest$unregistered(ISubsystemNetworkNode node, CallbackInfo ci) {
        SubsystemNetworkDomain domain = node == null ? null : node.getNetworkDomain();
        World world = node == null ? null : node.getNodeWorld();
        BlockPos pos = node == null ? null : node.getNodePos();
        if (domain == null || world == null || world.isRemote || pos == null) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "subsystem_node_unregistered", "\"domain\":\"" + domain.getName() + "\""
                + ",\"dim\":" + world.provider.getDimension()
                + ",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ()
                + ",\"released\":" + !snapshot(domain).contains(node));
    }
}
