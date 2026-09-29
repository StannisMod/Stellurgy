package dev.stannismod.stellurgy.event;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import dev.stannismod.stellurgy.wirelessdata.HandlerDataNetwork;
import dev.stannismod.stellurgy.wirelessdata.NetworkRegistry;

public class WirelessDataTickHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        HandlerDataNetwork nets = NetworkRegistry.dataNetwork();
        if (nets != null) {
            nets.tickAllNetworks();
        }
    }
}