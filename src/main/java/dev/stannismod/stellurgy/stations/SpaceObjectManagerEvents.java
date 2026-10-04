package dev.stannismod.stellurgy.stations;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Which events reach the station manager, and when. Everything it does it does through the manager
 * of the side the event runs on.
 */
public final class SpaceObjectManagerEvents {

    private SpaceObjectManagerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        SpaceObjectManager.confinePlayerInSpace(event);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        SpaceObjectManager.getSpaceManager().tickTransitions();
    }
}
