package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.test.trace.SideTrace;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.test.trace.TravelPassMemory;

/**
 * The deck frame's end-of-tick re-image, published as the travel resolver's pass is
 * ({@code deck_reseat_pass}, marked {@code "by":"deckFrame"}): how many bodies it put back on their
 * deck points and the largest step it carried one — the deck's own motion out from under a body
 * standing on it.
 *
 * <p>A scenario asking whether "the mechanism that keeps him in step ran during this roll" was blind
 * for every body the deck frame holds: only the resolver's pass announced itself. Measured 2026-10-04,
 * a crew member standing through a roll under the deck frame read {@code reseated=0}.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
@Mixin(value = DeckFrameTick.class, remap = false)
public abstract class MixinDeckFrameReseatPass {

    @Redirect(method = "followShipPoses",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;setPosition(DDD)V"))
    private static void stellurgyTest$reseat(Entity entity, double x, double y, double z) {
        if (entity.world != null) {
            TravelPassMemory pass = TravelPassMemory.of(SideTrace.of(entity.world));
            pass.noteReseat(x, y, z, entity.posX, entity.posY, entity.posZ);
            pass.countDeckFrameReseat();
        }
        entity.setPosition(x, y, z);
    }

    @Inject(method = "followShipPoses", at = @At("RETURN"))
    private static void stellurgyTest$reseatPass(World world, CallbackInfo ci) {
        if (world == null) {
            return;
        }
        TestTrace.instrument(world, "deck_reseat_pass_events");
        TravelPassMemory pass = TravelPassMemory.of(SideTrace.of(world));
        double maxStep = pass.takeReseatPassMax();
        int bodies = pass.takeDeckFrameReseats();
        if (bodies <= 0) {
            return;
        }
        TestTrace.record(world, "deck_reseat_pass",
                "\"remote\":" + world.isRemote + ",\"bodies\":" + bodies
                        + ",\"maxStep\":" + maxStep + ",\"by\":\"deckFrame\"");
    }
}
