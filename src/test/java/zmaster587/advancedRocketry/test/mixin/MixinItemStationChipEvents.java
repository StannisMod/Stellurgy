package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.item.ItemStationChip;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A station chip's GUI button REACHED the item — as an event, on whichever side the packet was
 * handed to the item.
 *
 * <p>The chip's landing-location screen is a modular GUI whose buttons travel as an item-modification
 * packet: the client sends the button id, and the packet's execute path hands it to the held item's
 * {@code useNetworkData(player, side, id, nbt, stack)}. That method is where production DECIDES what
 * a button means — a selection ({@code id >= 5}), a delete, a clear, an add — and where it re-opens
 * the screen afterwards. The test that used to watch this polled the open GUI's class name for ten
 * seconds; a name can say the screen changed, not that the press arrived nor which press it was.</p>
 *
 * <p>{@code chip_button_received} is taken at the method's HEAD, before the side guard inside it, so
 * it says the packet was DELIVERED to the item — not that it was acted on. Payload: {@code id} (the
 * raw button byte, offset included), {@code who} (the player's name), {@code item} (the held stack's
 * item registry name, or {@code "null"} if the item has none). Routed by the player's world, so a
 * server-side delivery lands in the server log and a client-side one in the client log; the packet
 * dispatches both, and the item itself only acts on the server.</p>
 *
 * <p>SILENT about: whether the button did anything (the method's server-only branch, the NBT it
 * rewrote), the re-opened GUI (a client fact, read off the harness's screen report), a packet that
 * arrived while the player held something other than an {@code INetworkItem} (the dispatcher drops
 * it before this seam), and the chip's own station id.</p>
 */
@Mixin(ItemStationChip.class)
public abstract class MixinItemStationChipEvents {

    private static final String INSTRUMENT = "station_chip_events";

    @Inject(method = "useNetworkData", at = @At("HEAD"))
    private void arTest$buttonReceived(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                       ItemStack stack, CallbackInfo ci) {
        TestTrace.instrument(player, INSTRUMENT);
        if (player == null || player.world == null) {
            return;
        }
        String item = "null";
        if (stack != null && !stack.isEmpty() && stack.getItem().getRegistryName() != null) {
            item = stack.getItem().getRegistryName().toString();
        }
        TestTrace.record(player, "chip_button_received", "\"id\":" + id
                + ",\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"item\":\"" + TestTrace.json(item) + "\"");
    }
}
