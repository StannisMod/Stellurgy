package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import dev.stannismod.stellurgy.test.trace.SideTrace;
import dev.stannismod.stellurgy.test.trace.SideTraceOwner;

/**
 * Gives the CLIENT its {@link SideTrace}: an instance field of the client object, created with it and
 * released with it. The client-side instruments reach it through {@link SideTrace#client()}.
 *
 * <p>Client. Test source set.</p>
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftSideTrace implements SideTraceOwner {

    @Unique
    private final SideTrace stellurgyTest$sideTrace = SideTrace.forClient();

    @Override
    public SideTrace stellurgyTest$sideTrace() {
        return stellurgyTest$sideTrace;
    }
}
