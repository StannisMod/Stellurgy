package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;

import dev.stannismod.stellurgy.network.PacketShipReadout;
import dev.stannismod.stellurgy.ship.control.ShipReadout;

/**
 * A ship readout ARRIVED at this client — {@code client_ship_readout_received}.
 *
 * <p>The seam is the packet's own client-side handler: the one place a readout the server addressed
 * to this player becomes something the client holds. Recorded as it is entered, before the handler
 * looks for the tile it is addressed to, because the question a test asks here is who the server
 * SENDS to — whether the client can then find the tile is a different contract.</p>
 *
 * <p>{@code afcX/Y/Z} are the flight computer the readout is for (a subspace address on an assembled
 * ship), {@code revision} that computer's model revision, {@code totalKg} the mass it was solved
 * for. Client-only: this handler exists only in a client JVM, so the record goes straight to the
 * client's log.</p>
 */
@Mixin(value = PacketShipReadout.class, remap = false)
public abstract class MixinPacketShipReadoutEvents {

    @Shadow
    private BlockPos flightComputer;

    @Shadow
    private ShipReadout readout;

    @Inject(method = "executeClient", at = @At("HEAD"), remap = false, require = 1)
    private void stellurgyTest$readoutReceived(EntityPlayer player, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered("client_ship_readout_received");
        if (flightComputer == null || readout == null) {
            return;
        }
        ForgeTestClientBootstrap.recordEvent("client_ship_readout_received",
                "\"afcX\":" + flightComputer.getX() + ",\"afcY\":" + flightComputer.getY()
                        + ",\"afcZ\":" + flightComputer.getZ()
                        + ",\"revision\":" + readout.revision()
                        + ",\"totalKg\":" + readout.totalMass());
    }
}
