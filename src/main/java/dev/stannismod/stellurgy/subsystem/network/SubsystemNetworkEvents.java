package dev.stannismod.stellurgy.subsystem.network;

import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import dev.stannismod.stellurgy.api.Constants;

/**
 * When the running server's {@link SubsystemNetworkManager} works: once per world tick, and once when
 * one of its worlds unloads. Everything it does, it does on that server's manager.
 */
@Mod.EventBusSubscriber(modid = Constants.modId)
public final class SubsystemNetworkEvents {

    private SubsystemNetworkEvents() {
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        World world = event.world;
        if (world == null || world.isRemote) {
            return;
        }
        SubsystemNetworkManager.of(world).tick(world);
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        SubsystemNetworkManager.of(world).releaseWorld(world);
    }
}
