package dev.stannismod.stellurgy.integration.jei;

import dev.stannismod.stellurgy.client.ServerView;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Rebuilds JEI's gas-giant recipes once the connected server's galaxy has changed and JEI is up. */
public class JeiClientTickHandler {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ServerView view = ServerView.currentOrNull();
        if (view == null || !view.recipeViewsStale()) return;

        if (StellurgyJeiPlugin.refreshGasGiantRecipes()) {
            view.recipeViewsRebuilt();
        }
    }
}
