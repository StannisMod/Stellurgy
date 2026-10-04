package dev.stannismod.stellurgy.event;

import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import dev.stannismod.stellurgy.Stellurgy;

public class WirelessNetworkRegistryHandler {

    /** The server's wireless networks come up with its overworld, whose saved data they are. */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.getWorld().isRemote && event.getWorld().provider.getDimension() == 0) {
            Stellurgy.serverState().wirelessNetworks(event.getWorld());
        }
    }
}
