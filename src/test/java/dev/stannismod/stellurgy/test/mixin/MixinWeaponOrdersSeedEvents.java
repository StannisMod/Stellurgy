package dev.stannismod.stellurgy.test.mixin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkController;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;
import dev.stannismod.stellurgy.weapon.WeaponNetworkDomain;
import dev.stannismod.stellurgy.weapon.WeaponNetworkState;

/**
 * A weapon network taking its orders from its consoles, as an event: {@code weapon_orders_seeded}.
 *
 * <p>The RETURN of the domain's {@code seedFromLatestOrders}, which a rebuild runs once for every
 * component that has at least one console — the one place the "last order wins" decision is made.
 * Carries {@code consoles}, the positions of the component's consoles sorted by x, then y, then z
 * and joined as {@code "x,y,z|x,y,z"}, so a reader names the exact component it built; and
 * {@code stamp}, the stamp of the orders the network holds once the decision is taken. Server
 * log. Read by {@code WeaponConsoleE2ETest}.</p>
 *
 * <p>SILENT about a component with no console, which never asks the question, and about a client
 * world, which has no networks.</p>
 */
@Mixin(WeaponNetworkDomain.class)
public abstract class MixinWeaponOrdersSeedEvents {

    private static final String INSTRUMENT = "weapon_orders_seed_events";

    @Inject(method = "seedFromLatestOrders", at = @At("RETURN"), require = 1)
    private static void stellurgyTest$seeded(WeaponNetworkState weapons,
                                             List<ISubsystemNetworkController> controllers,
                                             CallbackInfo ci) {
        World world = null;
        List<BlockPos> consoles = new ArrayList<>();
        for (ISubsystemNetworkController controller : controllers) {
            if (controller instanceof TileWeaponConsole) {
                TileWeaponConsole console = (TileWeaponConsole) controller;
                consoles.add(console.getPos());
                world = console.getWorld();
            }
        }
        if (world == null || world.isRemote) {
            return;
        }
        consoles.sort(Comparator.comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        StringBuilder joined = new StringBuilder();
        for (BlockPos pos : consoles) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "weapon_orders_seeded", "\"consoles\":\"" + joined + "\""
                + ",\"stamp\":" + weapons.getOrdersStamp());
    }
}
