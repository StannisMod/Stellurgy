package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.space.ShipAboardTag;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The durable aboard record's two writes as events: a player is STAMPED aboard a ship (or his record
 * refreshed), and his record is CLEARED.
 *
 * <p>The record is what a crossing captures a standing crew member by and what a login restore reads,
 * so "why was he carried" and "why was he sent to spawn" both end at these two calls. The
 * reconciler's own log says {@code stamped} / {@code refreshed} / {@code dropped}; these records are
 * the same facts at the write itself, which the tests can await and order — a stamp that lands
 * AFTER a server-side teleport off the hull is how a crew member 60 blocks from his ship is still
 * carried by its jump.</p>
 */
@Mixin(ShipAboardTag.class)
public abstract class MixinShipAboardTagEvents {

    private static final String INSTRUMENT = "aboard_record_events";

    @Inject(method = "stamp", at = @At("HEAD"))
    private static void arTest$stamped(EntityPlayer player, ShipAboardTag.Aboard aboard, CallbackInfo ci) {
        TestTrace.instrument(player, INSTRUMENT);
        if (player == null || player.world == null || aboard == null) {
            return;
        }
        TestTrace.record(player, "aboard_record_stamped", "\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"ship\":\"" + aboard.shipId + "\",\"posture\":\"" + aboard.posture
                + "\",\"cell\":\"" + (aboard.coord == null ? "none" : aboard.coord.cellKey())
                + "\",\"inTransit\":" + aboard.inTransit + ",\"y\":" + TestTrace.fmt(player.posY));
    }

    @Inject(method = "clear(Lnet/minecraft/entity/player/EntityPlayer;)V", at = @At("HEAD"))
    private static void arTest$cleared(EntityPlayer player, CallbackInfo ci) {
        TestTrace.instrument(player, INSTRUMENT);
        if (player == null || player.world == null) {
            return;
        }
        ShipAboardTag.Aboard was = ShipAboardTag.of(player);
        TestTrace.record(player, "aboard_record_cleared", "\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"had\":" + (was != null) + ",\"ship\":\"" + (was == null ? "none" : was.shipId)
                + "\",\"y\":" + TestTrace.fmt(player.posY));
    }
}
