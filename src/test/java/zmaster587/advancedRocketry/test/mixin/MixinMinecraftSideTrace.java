package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import zmaster587.advancedRocketry.test.trace.SideTrace;
import zmaster587.advancedRocketry.test.trace.SideTraceOwner;

/**
 * Gives the CLIENT its {@link SideTrace}: an instance field of the client object, created with it and
 * released with it. The client-side instruments reach it through {@link SideTrace#client()}.
 *
 * <p>Client. Test source set.</p>
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftSideTrace implements SideTraceOwner {

    @Unique
    private final SideTrace arTest$sideTrace = new SideTrace("client");

    @Override
    public SideTrace arTest$sideTrace() {
        return arTest$sideTrace;
    }
}
