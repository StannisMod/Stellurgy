package dev.stannismod.stellurgy.test.trace;

import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * One record per rider of a seat bound to a craft in {@code world}: the rider's position and, in the
 * same read, the seat block's image through the craft's CURRENT pose on this side
 * ({@code VSIntegration.getSeatWorldPosition}), with the vertical offset vanilla seats a rider at
 * ({@code getMountedYOffset() + getYOffset()}) and the rider's own tick count.
 *
 * <p>Taken at the HEAD of a tick, by {@code MixinSeatRiderShownTrace} on the client
 * ({@code seat_rider_shown}) and {@code MixinSeatRiderPlacedTrace} on the server
 * ({@code seat_rider_placed}): the state the previous tick left, after everything that places a rider
 * and before anything of the next tick does.</p>
 *
 * <p>Silent about a seat no craft manages ({@code seatFound:false}, no seat fields), and about whether
 * the seat's image itself is right — it is computed by the same function the seat is glued with, so a
 * reader sees WHEN and by WHOM the rider is placed, never the frame mathematics. Test source set.</p>
 */
public final class SeatRiderTrace {

    private SeatRiderTrace() {
    }

    /** Record every rider of every bound seat in {@code world} as {@code type}, instrumented as {@code instrument}. */
    public static void recordRiders(World world, String type, String instrument) {
        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntityDummy) || entity.isDead) {
                continue;
            }
            BlockPos seatPos = ((EntityDummy) entity).getSeatPos();
            if (seatPos == null) {
                continue;
            }
            double[] seat = VSIntegration.getSeatWorldPosition(world, seatPos);
            for (Entity rider : entity.getPassengers()) {
                TestTrace.instrument(rider, instrument);
                TestTrace.record(rider, type, "\"e\":" + rider.getEntityId()
                        + ",\"mount\":" + entity.getEntityId()
                        + ",\"riderTicks\":" + rider.ticksExisted
                        + ",\"riderX\":" + rider.posX + ",\"riderY\":" + rider.posY + ",\"riderZ\":" + rider.posZ
                        + ",\"seatFound\":" + (seat != null)
                        + (seat == null ? "" : ",\"seatX\":" + seat[0] + ",\"seatY\":" + seat[1] + ",\"seatZ\":" + seat[2])
                        + ",\"offsetY\":" + (entity.getMountedYOffset() + rider.getYOffset()));
            }
        }
    }
}
