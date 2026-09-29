package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;

/**
 * Runs the update of an entity a deck holds in that deck's frame.
 *
 * <p>The call redirected is the one place the world ticks an entity that is not riding, so an entity
 * a deck holds is updated there by its own, unmodified code - only the frame it runs in differs.</p>
 */
@Mixin(World.class)
public abstract class MixinWorldDeckFrameTick {

    @Redirect(method = "updateEntityWithOptionalForce",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;onUpdate()V"))
    private void stellurgy$updateInDeckFrame(Entity entity) {
        if (!DeckFrameTick.update(entity)) {
            entity.onUpdate();
        }
    }
}
