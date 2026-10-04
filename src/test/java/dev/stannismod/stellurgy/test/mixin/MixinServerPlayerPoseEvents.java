package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The SERVER's idea of a player's pose, as an event: {@code player_pose_received}, with {@code who},
 * {@code yaw}, {@code pitch} and {@code sneaking} as the server holds them.
 *
 * <p>A click travels in a packet of its own, and the packet carries neither the look nor the sneak:
 * the server decides it on whatever pose it last received. So a test that turns the client's head or
 * presses sneak and then clicks has to know the server has caught up, and nothing in production says
 * so. Read at the RETURN of the two handlers that change the pose — {@code processPlayer} (the look)
 * and {@code processEntityAction} (the sneak) — so it is the state those handlers left behind.</p>
 *
 * <p>EDGE-ONLY per connection on the whole payload: a movement packet arrives every tick or so, and
 * the record is the change, not the stream. SILENT about position. Read by
 * {@code ALinkerNamesTheBatteryItsTargetE2ETest}.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinServerPlayerPoseEvents {

    private static final String INSTRUMENT = "player_pose_events";

    @Shadow
    public EntityPlayerMP player;

    /** The last pose this connection recorded; null before its first. */
    @Unique
    private String stellurgyTest$lastPose = null;

    @Inject(method = "processPlayer", at = @At("RETURN"), require = 1)
    private void stellurgyTest$poseAfterMove(CPacketPlayer packet, CallbackInfo ci) {
        stellurgyTest$recordPose();
    }

    @Inject(method = "processEntityAction", at = @At("RETURN"), require = 1)
    private void stellurgyTest$poseAfterAction(CPacketEntityAction packet, CallbackInfo ci) {
        stellurgyTest$recordPose();
    }

    @Unique
    private void stellurgyTest$recordPose() {
        EntityPlayerMP self = this.player;
        if (self == null) {
            return;
        }
        TestTrace.instrument(self, INSTRUMENT);
        String pose = "\"who\":\"" + TestTrace.json(self.getName()) + "\""
                + ",\"yaw\":" + self.rotationYaw
                + ",\"pitch\":" + self.rotationPitch
                + ",\"sneaking\":" + self.isSneaking();
        if (pose.equals(stellurgyTest$lastPose)) {
            return;
        }
        stellurgyTest$lastPose = pose;
        TestTrace.record(self, "player_pose_received", pose);
    }
}
