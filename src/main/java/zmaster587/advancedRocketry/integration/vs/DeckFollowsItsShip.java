package zmaster587.advancedRocketry.integration.vs;

import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Puts the crew back on the deck once the deck has finished moving, every tick, on the server.
 *
 * <p>The physics substrate advances its ships' poses at {@code WorldTickEvent} phase END — after
 * the world's entities have already moved. A body aboard therefore computes its world position
 * against the pose of the PREVIOUS tick and then stands there while the ship steps out from under
 * it. {@link ShipFrameTravel#followShipPoses} re-images the body's held deck point through the pose
 * that now stands; this class is only the "once the ships have moved" part of that sentence.</p>
 *
 * <p><b>The priority is the mechanism.</b> {@code LOWEST} is what places this handler after the
 * substrate's own END handler, which runs at {@code HIGHEST}. Nothing else orders the two, and a
 * pass that runs before the poses advance re-seats every body at the pose it already had — a no-op
 * that looks exactly like a fix, because the number it publishes ({@code lastReseatStep}) would
 * read zero for the right reason and the wrong one alike.</p>
 *
 * <p>The client's counterpart is {@code ClientDeckFollowsItsShip}: the client ticks its ships on
 * {@code ClientTickEvent} instead, because the world tick event is never fired client-side.</p>
 */
public final class DeckFollowsItsShip {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterTheShipsHaveMoved(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ShipFrameTravel.followShipPoses(event.world);
        }
    }
}
