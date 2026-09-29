package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import zmaster587.advancedRocketry.test.trace.SideTrace;
import zmaster587.advancedRocketry.test.trace.SideTraceOwner;

/**
 * Gives the SERVER its {@link SideTrace}: an instance field of the server object, created with it and
 * released with it — so a second server in one JVM gets a trace of its own, and nothing a server
 * remembered outlives it. Server-side instruments reach it through {@link SideTrace#of} or
 * {@link SideTrace#server}.
 *
 * <p>Both sides (an integrated server is a {@code MinecraftServer} too). Test source set.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerSideTrace implements SideTraceOwner {

    @Unique
    private final SideTrace arTest$sideTrace = new SideTrace("server");

    @Override
    public SideTrace arTest$sideTrace() {
        return arTest$sideTrace;
    }
}
