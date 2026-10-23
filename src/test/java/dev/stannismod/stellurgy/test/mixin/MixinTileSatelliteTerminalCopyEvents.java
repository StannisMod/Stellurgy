package dev.stannismod.stellurgy.test.mixin;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;
import dev.stannismod.stellurgy.test.trace.TerminalState;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteTerminal;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The CLIENT's copy of a satellite terminal after the server's update packet was applied to it, as the client
 * event {@code terminal_copy_applied}: the place the packet was applied at ({@code dim}, {@code x}, {@code y},
 * {@code z}) and
 * the copy's state as {@link TerminalState#fields} gives it — the same fields the server's {@code terminal_try}
 * carries, read off the client's own terminal object.
 *
 * <p>Injected at the TAIL of {@code TileSatelliteTerminal#onDataPacket}, the instant the copy the screen is
 * drawn from holds what the packet brought; on a client that is the client thread, where vanilla applies a
 * tile's update packet.</p>
 *
 * <p>SILENT about: the state a client takes from a chunk it is sent whole (the update TAG, read by
 * {@code handleUpdateTag}, not this packet), and what the screen draws from the copy.</p>
 */
@Mixin(TileSatelliteTerminal.class)
public abstract class MixinTileSatelliteTerminalCopyEvents {

    private static final String INSTRUMENT = "terminal_copy_applied";

    @Inject(method = "onDataPacket", at = @At("TAIL"), remap = false, require = 1)
    private void stellurgyTest$copyApplied(NetworkManager net, SPacketUpdateTileEntity pkt, CallbackInfo ci) {
        TileSatelliteTerminal self = (TileSatelliteTerminal) (Object) this;
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        // The place is the packet's, where the client applied it: the copy's own position is read back out of
        // the packet's tag, so a tag that lost it would move the record off the place it was sent for.
        ForgeTestClientBootstrap.recordEvent(INSTRUMENT, "\"dim\":" + self.getWorld().provider.getDimension()
                + ",\"x\":" + pkt.getPos().getX() + ",\"y\":" + pkt.getPos().getY() + ",\"z\":" + pkt.getPos().getZ()
                + "," + TerminalState.fields(self));
    }
}
