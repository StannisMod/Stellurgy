package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * Where the CLIENT puts a pilot-seat rider, and where it shows his ship, in the same tick.
 *
 * <p>{@code seat_carried} — RETURN of {@code Entity.updatePassenger}, client side only, for a vehicle
 * that is a pilot-seat {@link EntityDummy} bound to a seat block. That call places the rider on his
 * seat after the seat has been glued to its ship this tick ({@code EntityDummy.onUpdate}, which is
 * the decision under observation). It carries the three heights a "the rider travels with his ship"
 * claim compares — the rider's, the seat's, and the ship's world position as this side holds it
 * ({@code VSIntegration.getShipWorldPosition}, keyed by the seat block) — read in one call, so the
 * relation between them is a reading of one tick and not of two reads a round trip apart.</p>
 *
 * <p>Taken on {@code Entity}, not on {@code EntityDummy}: the dummy does not override
 * {@code updatePassenger}, so vanilla's body is the one that places the rider, and the vehicle's
 * type is the filter.</p>
 *
 * <p>The question it answers was asked, until it existed, by a read of the SERVER's ship and a read
 * of the CLIENT's rider a fixed number of client ticks after a key was released: a latency claim
 * written as a wait, whose verdict moved with the speed of the box.</p>
 *
 * <p>Silent about: the SERVER (nothing here runs there); a seat no ship manages ({@code ship} and
 * {@code shipY} are then JSON null); whether the client's ship pose agrees with the server's — that is
 * pose replication, and {@code client_deck_pose_tick} is its record; and a rider the client stopped
 * carrying altogether, which writes nothing — a reader asks the window's {@code mount}/{@code dismount}
 * records for that, never this record's absence.</p>
 *
 * <p>Once per tick per seated rider: the ring is 256 deep, about thirteen seconds of one pilot.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityDummyCarryEvents {

    private static final String INSTRUMENT = "seat_carry_events";

    /** Read by {@link dev.stannismod.stellurgy.test.SeatCarry}. */
    @Inject(method = "updatePassenger(Lnet/minecraft/entity/Entity;)V", at = @At("RETURN"))
    private void stellurgyTest$carried(Entity passenger, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof EntityDummy) || self.world == null || !self.world.isRemote
                || passenger == null) {
            return;
        }
        TestTrace.instrument(passenger, INSTRUMENT);
        BlockPos seatPos = ((EntityDummy) self).getSeatPos();
        String shipId = seatPos == null ? null : VSIntegration.shipIdManagingBlock(self.world, seatPos);
        double[] ship = seatPos == null ? null : VSIntegration.getShipWorldPosition(self.world, seatPos);
        TestTrace.record(passenger, "seat_carried", "\"e\":" + passenger.getEntityId()
                + ",\"seat\":" + self.getEntityId()
                + ",\"ship\":" + (shipId == null ? "null" : "\"" + TestTrace.json(shipId) + "\"")
                + ",\"riderY\":" + exact(passenger.posY)
                + ",\"seatY\":" + exact(self.posY)
                + ",\"shipY\":" + (ship == null ? "null" : exact(ship[1])));
    }

    /**
     * A height at the double's own precision, NOT {@link TestTrace#fmt}'s six significant figures.
     * The readers subtract these from each other, and at the heights the extreme-coordinate legs fly
     * (1.6e7) six figures is a hundred-block step — measured 2026-10-05: every height of one climb
     * moved in lockstep by exactly 100.0, so the record described its formatter, not the rider.
     * {@code Double.toString} of a finite value is a valid JSON number; every value here is finite
     * (an entity position, or a ship position the bridge answered).
     */
    private static String exact(double v) {
        return Double.toString(v);
    }
}
