package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.entity.Entity;
import net.minecraft.world.WorldServer;

import dev.stannismod.stellurgy.entity.EntityDummy;
import dev.stannismod.stellurgy.space.ArrivalSpawn;
import dev.stannismod.stellurgy.test.trace.MountRefusalArming;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The world's answer to an arriving seat's mount, made to be NO while a scenario has armed a
 * {@link MountRefusalArming} — and every refusal it hands out, as a {@code seat_mount_refused} record.
 *
 * <p>The seam is the world's own {@code spawnEntity} call inside {@link ArrivalSpawn#at}: everything
 * production does with the answer — its own error line, and whatever the caller decides about a mount
 * that never entered a world — runs exactly as it does when a real world refuses. Only a seat dummy is
 * refused; cargo and every other body take the world's real answer.</p>
 *
 * <p>SILENT about: refusals the world makes on its own (they are not this mixin's), and any spawn not
 * routed through {@link ArrivalSpawn#at}.</p>
 */
@Mixin(ArrivalSpawn.class)
public abstract class MixinArrivalSpawnMountRefusal {

    private static final String INSTRUMENT = "arrival_mount_refusal";

    @Redirect(method = "at",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldServer;spawnEntity(Lnet/minecraft/entity/Entity;)Z",
                    remap = false),
            remap = false, require = 1)
    private static boolean stellurgyTest$mountSpawn(WorldServer world, Entity body) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (body instanceof EntityDummy && MountRefusalArming.take(world)) {
            TestTrace.recordServer("seat_mount_refused", "\"dim\":" + world.provider.getDimension()
                    + ",\"seat\":\"" + ((EntityDummy) body).getSeatPos() + "\"");
            return false;
        }
        return world.spawnEntity(body);
    }
}
