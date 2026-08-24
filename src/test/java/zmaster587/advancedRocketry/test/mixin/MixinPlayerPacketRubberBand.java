package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Records the moment the server turns a client/server DISAGREEMENT about where a body is into a
 * VELOCITY.
 *
 * <p>The substrate's movement-packet hook rewrites the packet's coordinates and then sets
 * {@code player.motionX/Y/Z = packet − firstGood} — by its own comment, "to tell NetHandlerPlayServer
 * that the player is allowed to move this fast". That expression is not a velocity: {@code firstGood}
 * is the last position the server ACCEPTED, so the difference is how far the two sides have drifted
 * apart. Vanilla then integrates it as though it were momentum.</p>
 *
 * <p>Which makes the two numbers behind it the whole question, and neither is reachable from a test:
 * the packet's coordinate and {@code firstGoodY} are what decide whether a launch is a physical
 * impulse or two machines disagreeing. Measured 2026-08-23, a body meeting an inverted hull left with
 * {@code motionY} between +28 and +54 on otherwise identical runs — a spread that already argues for
 * a difference of positions rather than a force, and that this records instead of inferring.</p>
 *
 * <p>Injected at the RETURN of {@code processPlayer}, so it reads what the packet path actually left
 * behind rather than racing the hook that writes it. Test-only: test source set, queued by the
 * harness coremod, absent from a released jar.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinPlayerPacketRubberBand {

    /** Change in one motion component that is worth a record; below this it is ordinary walking. */
    private static final double RUBBER_BAND_REPORT = 4.0;

    @Shadow
    public EntityPlayerMP player;

    @Shadow
    private double firstGoodX;

    @Shadow
    private double firstGoodY;

    @Shadow
    private double firstGoodZ;

    @Inject(method = "processPlayer", at = @At("RETURN"))
    private void arTest$recordRubberBand(CPacketPlayer packet, CallbackInfo ci) {
        EntityPlayerMP self = this.player;
        if (self == null || Math.abs(self.motionY) <= RUBBER_BAND_REPORT) {
            return;
        }
        TestTrace.record(self, "packet_rubber_band",
                "\"e\":" + self.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(self.getName()) + "\""
                        + ",\"motionY\":" + TestTrace.fmt(self.motionY)
                        + ",\"serverY\":" + TestTrace.fmt(self.posY)
                        + ",\"firstGoodX\":" + TestTrace.fmt(this.firstGoodX)
                        + ",\"firstGoodY\":" + TestTrace.fmt(this.firstGoodY)
                        + ",\"firstGoodZ\":" + TestTrace.fmt(this.firstGoodZ)
                        + ",\"packetY\":" + TestTrace.fmt(packet.getY(Double.NaN)));
    }
}
