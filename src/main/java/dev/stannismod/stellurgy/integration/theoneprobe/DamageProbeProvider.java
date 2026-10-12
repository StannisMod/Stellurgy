package dev.stannismod.stellurgy.integration.theoneprobe;

import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.damage.BlockDamageSavedData;
import dev.stannismod.stellurgy.damage.DamageState;
import dev.stannismod.stellurgy.damage.RepairCost;

/**
 * A block's structural damage, on the probe line a player already looks at.
 *
 * <p>Read from the damage record of the POSITION, not from the block standing there: the record is
 * what a repair works from, so a block put back over it by anything that does not clear it shows
 * what the record still says. Nothing is shown for a position with no record — most blocks are never
 * touched, and a line on every one of them would be noise.</p>
 *
 * <p>Three different facts, said apart because they are repaired apart: a STAGE out of the block's
 * maximum (welded one stage at a time); a recorded DESTRUCTION (a hole the record remembers, rebuilt
 * from what it names, never welded); and a block nothing crafts, which the welder refuses outright —
 * said here so the player does not learn it by failing.</p>
 *
 * <p>Silent about a hole itself. A destroyed position is air and the probe cannot be pointed at air,
 * so the destruction line appears only where a block stands on a record that outlived the hole — a
 * player's own placement there clears the record and shows nothing. The probe runs on the server,
 * which holds the record; the client holds no copy of it.</p>
 */
public class DamageProbeProvider implements IProbeInfoProvider {

    @Override
    public String getID() {
        return "stellurgy:damage";
    }

    @Override
    public void addProbeInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world,
                             IBlockState blockState, IProbeHitData hitData) {
        BlockPos pos = hitData.getPos();
        String recorded = world.isRemote ? null : BlockDamageSavedData.get(world).getDestroyedBlockName(pos);
        int stage = DamageState.getStage(world, pos);
        if (recorded == null && stage <= 0) {
            return;
        }
        if (recorded != null) {
            probeInfo.text(TextFormatting.RED + localized("msg.top.stellurgy.damage.destroyed") + " "
                    + nameOf(recorded) + TextFormatting.RESET);
        } else {
            probeInfo.text(TextFormatting.GOLD + localized("msg.top.stellurgy.damage.stage") + " "
                    + stage + "/" + DamageState.getMaxStage(world, pos) + TextFormatting.RESET);
        }
        if (RepairCost.perStage(world, pos) == null) {
            probeInfo.text(TextFormatting.GRAY + localized("msg.top.stellurgy.damage.noRecipe")
                    + TextFormatting.RESET);
        }
    }

    /** The recorded block's own name, translated on the client; its registry name when this install
     *  has no such block. */
    private static String nameOf(String registryName) {
        Block block = BlockDamageSavedData.blockFromName(registryName);
        return block == null ? registryName : localized(block.getUnlocalizedName() + ".name");
    }

    /** A key the CLIENT translates — the probe line is built here, on the server. */
    private static String localized(String key) {
        return IProbeInfo.STARTLOC + key + IProbeInfo.ENDLOC;
    }
}
