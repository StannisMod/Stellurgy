package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import dev.stannismod.stellurgy.libvulpes.util.JetpackKeys;

/**
 * Gives every player the state of its own jetpack thrust key, so it lives and dies with the player
 * object it describes.
 */
@Mixin(EntityPlayer.class)
public abstract class MixinEntityPlayerJetpackKeys implements JetpackKeys {

    @Unique
    private boolean stellurgy$spaceDown;

    @Override
    public boolean stellurgy$isSpaceDown() {
        return stellurgy$spaceDown;
    }

    @Override
    public void stellurgy$setSpaceDown(boolean down) {
        stellurgy$spaceDown = down;
    }
}
