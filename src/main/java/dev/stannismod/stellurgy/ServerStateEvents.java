package dev.stannismod.stellurgy;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** The Forge events that drive {@link ServerState}; everything they do, they do to the running server's. */
public final class ServerStateEvents {

    private ServerStateEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Stellurgy.serverState().actionBar.tick(FMLCommonHandler.instance().getMinecraftServerInstance());
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote) {
            return;
        }
        Stellurgy.serverState().ingameTests.onWorldTick(event.world);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player.world.isRemote) {
            return;
        }
        Stellurgy.serverState().playerLoggedOut(event.player.getUniqueID());
    }
}
