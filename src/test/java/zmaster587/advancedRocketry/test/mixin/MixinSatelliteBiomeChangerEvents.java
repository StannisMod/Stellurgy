package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.api.satellite.SatelliteProperties;
import zmaster587.advancedRocketry.satellite.SatelliteBiomeChanger;
import zmaster587.libVulpes.util.HashedBlockPosition;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A biome-changer satellite being ASKED to terraform around a point, as an event.
 *
 * <p>The satellite does nothing at once: {@code performAction} only fills its private work list with
 * the columns to change (a noisy disc of radius 12 around the target), and the actual terraform is
 * paid for column by column in {@code tickEntity}, ten per tick while the battery holds. So the
 * production fact worth observing at the seam is not "the biome changed" but "the request was
 * QUEUED, and this much is now waiting" — which is what {@code biome_change_queued} carries:
 * {@code satellite} (the long id the registry knows it by), {@code dim} (the satellite's own
 * dimension, the world {@code tickEntity} will terraform in), {@code x, z} of the requested centre,
 * {@code queued} (the size of the work list AFTER this call — cumulative, capped by production's
 * {@code MAX_SIZE}, so two requests in a row show the second's total, not its delta), {@code who}
 * (the requesting player, or {@code "null"} for a terminal pull with no player) and {@code remote}.</p>
 *
 * <h2>The seam</h2>
 *
 * <p>{@code SatelliteBiomeChanger.performAction(EntityPlayer, World, BlockPos)} at RETURN. The method
 * has two exits: an early {@code return false} on a remote world, before anything is queued, and the
 * final {@code return false} after the loop. Both are recorded — the early one with
 * {@code remote:true} and an unchanged {@code queued} — because "asked on the wrong side" and "never
 * asked" must stay distinguishable. Neither exit declares a local the handler needs (only the
 * arguments and the shadowed list are read), so RETURN is safe across both. The work list is read
 * through {@code @Shadow} of the private {@code toChangeList}; nothing is re-derived.</p>
 *
 * <p>The target returns a boolean, so the handler takes {@code CallbackInfoReturnable<Boolean>}. The
 * return value itself is always {@code false} in production and is not part of the payload.</p>
 *
 * <h2>Side</h2>
 *
 * <p>The mixin sits in the common list. Every production caller ({@code ItemBiomeChanger} on
 * right-click, {@code TileSatelliteTerminal}'s player action and auto-pull) already runs behind a
 * {@code !world.isRemote} guard, so the expected record is a SERVER record, routed by
 * {@code recordHere} off the calling thread's effective side. A client-thread call — should one ever
 * appear — lands in the client log with {@code remote:true}.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p>Whether any column was actually terraformed: the drain in {@code tickEntity} and
 * {@code BiomeHandler.terraform} are not observed here, nor is the battery that gates them. A request
 * that arrived with an empty battery is still {@code biome_change_queued}. Which columns the noisy
 * edge admitted is not recorded either ({@code isAdd} rolls a fresh {@code Random} per cell), only
 * how many are waiting in total. And a request refused BEFORE the seam — a satellite in another
 * dimension, which {@code ItemBiomeChanger} declines without calling {@code performAction} — leaves
 * no record at all.</p>
 */
@Mixin(SatelliteBiomeChanger.class)
public abstract class MixinSatelliteBiomeChangerEvents {

    private static final String INSTRUMENT = "satellite_biome_changer_events";

    @Shadow private List<HashedBlockPosition> toChangeList;

    @Inject(method = "performAction", at = @At("RETURN"))
    private void arTest$biomeChangeQueued(EntityPlayer player, World world, BlockPos pos,
                                          CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        SatelliteBiomeChanger self = (SatelliteBiomeChanger) (Object) this;
        SatelliteProperties properties = self.getProperties();
        // getId() dereferences the properties; a satellite built bare (no chip) has none yet.
        String satellite = properties == null ? "\"null\"" : String.valueOf(properties.getId());
        boolean remote = world != null && world.isRemote;
        TestTrace.recordHere("biome_change_queued", "\"satellite\":" + satellite
                + ",\"dim\":" + self.getDimensionId()
                + ",\"x\":" + (pos == null ? "\"null\"" : String.valueOf(pos.getX()))
                + ",\"z\":" + (pos == null ? "\"null\"" : String.valueOf(pos.getZ()))
                + ",\"queued\":" + (toChangeList == null ? 0 : toChangeList.size())
                + ",\"who\":\"" + (player == null ? "null" : TestTrace.json(player.getName()))
                + "\",\"remote\":" + remote);
    }
}
