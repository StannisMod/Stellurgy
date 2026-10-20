package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.api.satellite.SatelliteProperties;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.network.PacketSatellite;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.TreeSet;

/**
 * The client APPLYING a satellite packet — {@code client_satellite_packet_applied}, written when
 * {@code PacketSatellite#executeClient} returns, by any of its returns: the satellite the packet carried
 * ({@code satellite}, its id) and every dimension of the client's galaxy whose satellites hold that id at
 * that moment ({@code heldIn}, ascending; empty when none does).
 *
 * <p>Read at the return and not later because a satellite reaches the client by a second road as well:
 * the dimension's periodic update creates any satellite the client lacks. A read taken afterwards
 * answers for whichever road arrived first; this one answers for the packet that just ran, since the
 * client applies its packets one after another on one thread. And it asks each DIMENSION rather than the
 * satellite where it lives: a satellite's own dimension field arrives in the packet, so it would name the
 * right dimension whichever dimension the satellite had been put in.</p>
 *
 * <p>The dimensions asked are the galaxy's registered ones and the overworld, which the galaxy keeps
 * apart from them; each through production's {@code DimensionManager#getDimensionProperties}. The id is
 * read out of the satellite's {@code properties} tag by production's own
 * {@link SatelliteProperties#readFromNBT} — the name of that tag is the one piece of the format this
 * knows. The point announces itself on entry ({@code client_satellite_packet}) before it knows whether
 * the tag is readable. Records and changes nothing the game decides.</p>
 */
@Mixin(value = PacketSatellite.class, remap = false)
public abstract class MixinPacketSatelliteEvents {

    private static final String INSTRUMENT = "client_satellite_packet";

    @Shadow
    NBTTagCompound nbt;

    @Inject(method = "executeClient", at = @At("HEAD"))
    private void stellurgytest$entered(EntityPlayer player, CallbackInfo ci) {
        TestTrace.instrument(player, INSTRUMENT);
    }

    @Inject(method = "executeClient", at = @At("RETURN"))
    private void stellurgytest$applied(EntityPlayer player, CallbackInfo ci) {
        if (player == null || nbt == null) {
            return;
        }
        SatelliteProperties carried = new SatelliteProperties();
        carried.readFromNBT(nbt.getCompoundTag("properties"));
        long id = carried.getId();
        DimensionManager galaxy = DimensionManager.getInstance();
        TreeSet<Integer> asked = new TreeSet<>(Arrays.asList(galaxy.getRegisteredDimensions()));
        asked.add(0);
        StringBuilder heldIn = new StringBuilder();
        for (int dim : asked) {
            if (galaxy.getDimensionProperties(dim).getSatellite(id) != null) {
                heldIn.append(heldIn.length() == 0 ? "" : ",").append(dim);
            }
        }
        TestTrace.record(player, "client_satellite_packet_applied", "\"satellite\":\"" + id + "\""
                + ",\"heldIn\":[" + heldIn + "]");
    }
}
