package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.multiblock.TilePlanetSelector;

/**
 * The planet selector's one server-side act as an event: a selection the client sent was APPLIED
 * ({@code selector_selection_set}).
 *
 * <p>Clicking a planet in the selector's GUI is decided on the client and travels as one machine
 * packet; the server's copy of the tile takes it in {@code useNetworkData} with packet id 0 and
 * stores it. Recorded at RETURN, on the server branch only, so the record means the selection is
 * already in the tile a probe reads next. Payload: {@code pos}, {@code dim} (the id the packet
 * carried).</p>
 *
 * <p>SILENT about: a packet that never reached the server, and the client's own half — the
 * selection shown on screen before the packet leaves. Id 0 is also what a focus change sends, so a
 * record is "a selection was applied", never "the player CONFIRMED one".</p>
 */
@Mixin(TilePlanetSelector.class)
public abstract class MixinTilePlanetSelectorEvents {

    private static final String INSTRUMENT = "planet_selector_events";

    @Inject(method = "useNetworkData", at = @At("RETURN"), remap = false)
    private void arTest$selectionSet(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                     CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (id != 0 || side == null || !side.isServer()) {
            return;
        }
        TilePlanetSelector self = (TilePlanetSelector) (Object) this;
        String pos = self.getPos() == null ? "null"
                : self.getPos().getX() + "," + self.getPos().getY() + "," + self.getPos().getZ();
        TestTrace.recordHere("selector_selection_set", "\"pos\":\"" + pos + "\",\"dim\":"
                + (nbt == null ? "null" : String.valueOf(nbt.getInteger("id"))));
    }
}
