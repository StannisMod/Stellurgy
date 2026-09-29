package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.integration.vs.DeckHeld;

/**
 * Gives every entity a slot for the deck episode that holds it, and tells that episode when
 * somebody writes the entity's position.
 */
@Mixin(Entity.class)
public abstract class MixinEntityDeckHeld implements DeckHeld {

    @Unique
    private DeckFrameTick.Episode stellurgy$deckEpisode;

    @Override
    public DeckFrameTick.Episode stellurgy$deckEpisode() {
        return stellurgy$deckEpisode;
    }

    @Override
    public void stellurgy$setDeckEpisode(DeckFrameTick.Episode episode) {
        stellurgy$deckEpisode = episode;
    }

    @Inject(method = "setPosition", at = @At("HEAD"))
    private void stellurgy$notePositionWrite(double x, double y, double z, CallbackInfo ci) {
        if (stellurgy$deckEpisode != null) {
            DeckFrameTick.noteWrite((Entity) (Object) this);
        }
    }
}
