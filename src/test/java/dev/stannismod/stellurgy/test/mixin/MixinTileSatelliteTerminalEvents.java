package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.satellite.TerminalReadout;
import dev.stannismod.stellurgy.tile.satellite.TileSatelliteTerminal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every try a satellite terminal settles, as the event {@code terminal_try}: the terminal's place
 * ({@code dim}, {@code x}, {@code y}, {@code z}), the {@code outcome} it now shows, the space clock's
 * {@code spaceTick} at the try, and what its last contact brought — {@code received}, {@code lostAboard},
 * {@code partsFailed}, {@code readings}, {@code missed} — with the points in the terminal's own buffer
 * ({@code terminalData}) and the rows it holds ({@code rows}).
 *
 * <p>Injected at the TAIL of {@code TileSatelliteTerminal#settle}, the one place every outcome passes —
 * a poll, a player's Download, a chip taken out — so the record is what the screen now says. The contact
 * fields describe the LAST CONTACT, which is this try only when {@code outcome} is {@code RECEIVED} or
 * {@code NOTHING_NEW}; on any other outcome they are the contact before.</p>
 *
 * <p>SILENT about: the client's copy and its screen (the record is the server's decision), and a terminal
 * whose chunk does not tick — it makes no try, so it records nothing.</p>
 */
@Mixin(TileSatelliteTerminal.class)
public abstract class MixinTileSatelliteTerminalEvents {

    private static final String INSTRUMENT = "terminal_try";

    @Inject(method = "settle", at = @At("TAIL"), require = 1)
    private void stellurgyTest$terminalTry(TileSatelliteTerminal.Outcome next, CallbackInfo ci) {
        TileSatelliteTerminal self = (TileSatelliteTerminal) (Object) this;
        TestTrace.instrument(self.getWorld(), INSTRUMENT);
        TerminalReadout.Contact last = self.readout().last();
        TestTrace.record(self.getWorld(), INSTRUMENT, "\"dim\":" + self.getWorld().provider.getDimension()
                + ",\"x\":" + self.getPos().getX() + ",\"y\":" + self.getPos().getY() + ",\"z\":" + self.getPos().getZ()
                + ",\"outcome\":\"" + self.outcome().name() + "\""
                + ",\"spaceTick\":" + SpaceSubsystem.spaceClock()
                + ",\"received\":" + last.received + ",\"lostAboard\":" + last.lostAboard
                + ",\"partsFailed\":" + last.partsFailed + ",\"readings\":" + last.readings
                + ",\"missed\":" + last.missed
                + ",\"terminalData\":" + self.getDataObject().getData()
                + ",\"rows\":" + self.readout().rows().size());
    }
}
