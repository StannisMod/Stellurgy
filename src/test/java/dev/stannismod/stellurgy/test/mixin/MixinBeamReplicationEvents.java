package dev.stannismod.stellurgy.test.mixin;

import java.util.List;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.projectile.BeamReplication;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A gun's beam being ANNOUNCED to the players around it, as an event: {@code turret_beam_announced}.
 *
 * <p>At the call of {@code ProximityBroadcast.sendNearSegment} inside {@code Channel#update} — reached
 * only once the channel's own decision ({@code offer}) has said this tick's state goes out. Carries
 * the gun's {@code pos} and {@code lit}: the channel's {@code announcedLit} as {@code offer} just set
 * it, i.e. exactly the state the packet carries. Server log. Read by
 * {@code ABeamIsHeldNotThrownE2ETest#clearingTheTargetPutsTheBeamOut}.</p>
 *
 * <p>Records the decision to announce, not who received it: a server with no player in range still
 * records it, because the radius filter is the broadcaster's and not the channel's. SILENT about a
 * tick the channel decided to stay quiet on (a dark gun already announced dark, a steady beam between
 * heartbeats).</p>
 */
@Mixin(BeamReplication.Channel.class)
public abstract class MixinBeamReplicationEvents {

    private static final String INSTRUMENT = "turret_beam_announce_events";

    @Shadow
    private boolean announcedLit;

    @Inject(method = "update", at = @At(value = "INVOKE",
            target = "Ldev/stannismod/stellurgy/projectile/ProximityBroadcast;sendNearSegment(Lnet/minecraft/world/World;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;ILjava/util/function/Supplier;)V"),
            require = 1)
    private void stellurgyTest$announced(World world, BlockPos gun, List<Vec3d> path, boolean lit,
                                         CallbackInfo ci) {
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "turret_beam_announced", "\"pos\":\"" + gun.getX() + "," + gun.getY()
                + "," + gun.getZ() + "\",\"lit\":" + announcedLit);
    }
}
