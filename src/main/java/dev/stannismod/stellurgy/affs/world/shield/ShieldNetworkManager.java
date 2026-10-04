package dev.stannismod.stellurgy.affs.world.shield;

import dev.stannismod.stellurgy.affs.AdvancedForceFieldSystem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.Logger;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkController;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkNode;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkState;

import java.util.List;

/**
 * The shield domain. Everything structural — the connected-component graph, the max-flow solve, the
 * priority tiers, the per-tick statistics — lives in the shared subsystem-network primitive; what is
 * genuinely shield-specific is the resistance bias a console sets, and that is all this adds.
 * <p>
 * This class subscribes to nothing. It used to carry the world/tick handlers itself, and with them a
 * {@code @Mod.EventBusSubscriber} that had to name the owning container rather than the vendored
 * guest modid; both moved to the shared primitive when the bridge layer collapsed, and the modid
 * question moved with them.
 * <p>Every static field of this type is effectively final, process lifetime: built once at class initialisation, and holds an immutable value.</p>
 */
public final class ShieldNetworkManager {

    /** The domain handle. Shield nodes register under it; nothing else joins these graphs. */
    public static final SubsystemNetworkDomain DOMAIN = new SubsystemNetworkDomain("Shield") {
        @Override
        public SubsystemNetworkState newState() {
            return new ShieldNetworkState();
        }

        @Override
        public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers,
                                       List<ISubsystemNetworkNode> members) {
            if (!(state instanceof ShieldNetworkState) || controllers == null || controllers.isEmpty()) {
                return;
            }
            for (ISubsystemNetworkController controller : controllers) {
                if (controller instanceof IShieldNetworkController) {
                    ((ShieldNetworkState) state).setShieldEnergyResistanceBias(
                            ((IShieldNetworkController) controller).getShieldEnergyResistanceBias());
                    return;
                }
            }
        }

        @Override
        public Logger getLogger() {
            return AdvancedForceFieldSystem.LOG;
        }
    };

    private ShieldNetworkManager() {
    }

    /**
     * The shield network at this position, as the shield's own state type.
     * <p>
     * Kept where a plain domain-supplying forwarder was not: this one narrows the shared state to
     * the subclass that carries the resistance bias, so every caller does not repeat the same cast.
     * Callers that only need to name the domain — marking the topology dirty, registering a node —
     * say {@link #DOMAIN} at the call site instead, exactly as the ventilation domain does.
     */
    public static ShieldNetworkState getState(World world, BlockPos pos) {
        SubsystemNetworkState state = SubsystemNetworkManager.getState(DOMAIN, world, pos);
        return state instanceof ShieldNetworkState ? (ShieldNetworkState) state : null;
    }

    public static void setShieldEnergyResistanceBias(World world, BlockPos pos, double bias) {
        if (world == null || world.isRemote) {
            return;
        }
        ShieldNetworkState state = getState(world, pos);
        if (state != null) {
            state.setShieldEnergyResistanceBias(bias);
        }
    }
}
