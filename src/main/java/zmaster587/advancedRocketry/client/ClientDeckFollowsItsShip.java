package zmaster587.advancedRocketry.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

/**
 * The client half of {@code DeckFollowsItsShip} — the same re-seat, hung on the event the client
 * actually ticks its ships from.
 *
 * <p>Forge fires no {@code WorldTickEvent} on the client, so the physics substrate advances its
 * client-side poses on {@code ClientTickEvent} phase END instead; this runs at {@code LOWEST} so it
 * lands after that. It matters more here than on the server: this is the side that DRAWS the deck,
 * and a body left on last tick's pose renders a whole tick behind the floor it is standing on.</p>
 */
@SideOnly(Side.CLIENT)
public final class ClientDeckFollowsItsShip {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterTheShipsHaveMoved(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !Minecraft.getMinecraft().isGamePaused()) {
            ShipFrameTravel.followShipPoses(Minecraft.getMinecraft().world);
        }
    }
}
