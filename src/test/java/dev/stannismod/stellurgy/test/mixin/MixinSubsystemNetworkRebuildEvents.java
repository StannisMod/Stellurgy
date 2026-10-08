package dev.stannismod.stellurgy.test.mixin;

import java.util.List;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A subsystem network's topology being rebuilt, as an event: {@code subsystem_network_rebuilt}.
 *
 * <p>The RETURN of the per-world state's {@code rebuild}, which the network's world tick runs once
 * after anything marked the domain dirty — a node registering, unregistering, a block placed or
 * broken — and which is the only place a network's membership changes. Carries the domain's
 * {@code getName()}, the {@code dim}, and how many connected {@code components} the rebuild produced.
 * A reader waits for it after the change it made, then reads the standing state once. Server log.
 * Read by {@code WeaponConsoleTest}.</p>
 *
 * <p>SILENT about the per-tick max-flow solve, which runs every tick against the cached topology and
 * changes no membership.</p>
 */
@Mixin(targets = "dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager$WorldState")
public abstract class MixinSubsystemNetworkRebuildEvents {

    private static final String INSTRUMENT = "subsystem_network_rebuild_events";

    @Shadow
    @Final
    private List<?> components;

    @Inject(method = "rebuild", at = @At("RETURN"), require = 1)
    private void stellurgyTest$rebuilt(SubsystemNetworkDomain domain, World world, CallbackInfo ci) {
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "subsystem_network_rebuilt", "\"domain\":\""
                + (domain == null ? "null" : domain.getName()) + "\""
                + ",\"dim\":" + world.provider.getDimension()
                + ",\"components\":" + components.size());
    }
}
