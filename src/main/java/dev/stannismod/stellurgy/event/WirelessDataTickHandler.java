package dev.stannismod.stellurgy.event;

import dev.stannismod.stellurgy.Stellurgy;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class WirelessDataTickHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Stellurgy.serverState().tickWirelessNetworks();
    }
}
