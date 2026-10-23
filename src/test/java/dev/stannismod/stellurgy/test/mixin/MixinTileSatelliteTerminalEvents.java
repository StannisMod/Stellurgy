package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.test.trace.TerminalState;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteTerminal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every try a satellite terminal settles, as the event {@code terminal_try}: the terminal's place
 * ({@code dim}, {@code x}, {@code y}, {@code z}), the space clock's {@code spaceTick} at the try, and the
 * terminal's state as {@link TerminalState#fields} gives it — the {@code outcome} it now shows, what its last
 * contact brought, the points in its own buffer and the rows it holds.
 *
 * <p>Injected at the TAIL of {@code TileSatelliteTerminal#settle}, the one place every outcome passes —
 * a poll, a player's Download, a chip taken out — so the record is what the screen now says. The contact
 * fields describe the LAST CONTACT, which is this try only when {@code outcome} is {@code RECEIVED} or
 * {@code NOTHING_NEW}; on any other outcome they are the contact before.</p>
 *
 * <p>SILENT about: the client's copy and its screen (the record is the server's decision; the client's copy
 * is {@code terminal_copy_applied}), and a terminal whose chunk does not tick — it makes no try, so it records
 * nothing.</p>
 *
 * <p>Also {@code terminal_update_sent}, the other end of the copy's hand-off — see its seam.</p>
 */
@Mixin(TileSatelliteTerminal.class)
public abstract class MixinTileSatelliteTerminalEvents {

    private static final String INSTRUMENT = "terminal_try";
    private static final String SENT = "terminal_update_sent";

    @Inject(method = "settle", at = @At("TAIL"), require = 1)
    private void stellurgyTest$terminalTry(TileSatelliteTerminal.Outcome next, CallbackInfo ci) {
        TileSatelliteTerminal self = (TileSatelliteTerminal) (Object) this;
        TestTrace.instrument(self.getWorld(), INSTRUMENT);
        TestTrace.record(self.getWorld(), INSTRUMENT, place(self)
                + ",\"spaceTick\":" + SpaceSubsystem.spaceClock()
                + "," + TerminalState.fields(self));
    }

    /**
     * The server building the update packet for the clients watching this terminal, as {@code
     * terminal_update_sent}: its place and the SERVER terminal's state at that instant — read off the terminal,
     * never off the packet, so the record says what the client ought to receive whatever the packet carries.
     * Vanilla builds it only to send it ({@code PlayerChunkMapEntry#sendBlockEntity}), once per change, to every
     * watcher at once.
     */
    @Inject(method = "getUpdatePacket", at = @At("TAIL"), require = 1)
    private void stellurgyTest$updateSent(CallbackInfoReturnable<SPacketUpdateTileEntity> cir) {
        TileSatelliteTerminal self = (TileSatelliteTerminal) (Object) this;
        TestTrace.instrument(self.getWorld(), SENT);
        TestTrace.record(self.getWorld(), SENT, place(self) + "," + TerminalState.fields(self));
    }

    private static String place(TileSatelliteTerminal self) {
        return "\"dim\":" + self.getWorld().provider.getDimension()
                + ",\"x\":" + self.getPos().getX() + ",\"y\":" + self.getPos().getY() + ",\"z\":" + self.getPos().getZ();
    }
}
