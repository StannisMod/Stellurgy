package dev.stannismod.stellurgy.integration.jei;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class JeiClientTickHandler {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!StellurgyJeiPlugin.hasQueuedGasGiantRefresh()) return;

        StellurgyJeiPlugin.tryApplyQueuedGasGiantRefresh();
    }
}