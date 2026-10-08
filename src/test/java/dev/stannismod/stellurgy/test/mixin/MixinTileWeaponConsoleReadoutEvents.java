package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;

/**
 * The weapon console's open screen being given its readout, as an event: {@code client_console_readout}.
 *
 * <p>HEAD of {@code showReadout}, the one door from a readout into the lines the screen draws,
 * carrying the console's {@code pos} and what the readout says: {@code status} (the network status
 * as its machine token — the lang key's last segment, the same token the server's
 * {@code weaponconsole read} probe answers with), {@code holding} and {@code guns}. Client log. Read
 * by {@code WeaponGuiButtonsReachTheServerTest#theConsolesScreenShowsTheServersNetwork}.</p>
 *
 * <p>SILENT about a screen that was never given a readout — its lines are blank then, and that
 * absence is exactly what a reader waiting on this record is asking about.</p>
 */
@Mixin(TileWeaponConsole.class)
public abstract class MixinTileWeaponConsoleReadoutEvents {

    private static final String INSTRUMENT = "client_console_readout_events";

    @Inject(method = "showReadout", at = @At("HEAD"), require = 1)
    private void stellurgyTest$shown(NBTTagCompound readout, CallbackInfo ci) {
        BlockPos pos = ((TileWeaponConsole) (Object) this).getPos();
        String status = readout.getString("status");
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("client_console_readout",
                "\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\""
                        + ",\"status\":\"" + status.substring(status.lastIndexOf('.') + 1) + "\""
                        + ",\"holding\":" + readout.getBoolean("holding")
                        + ",\"guns\":" + readout.getInteger("guns"));
    }
}
