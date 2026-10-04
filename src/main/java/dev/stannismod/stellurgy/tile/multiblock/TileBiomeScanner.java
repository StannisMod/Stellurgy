package dev.stannismod.stellurgy.tile.multiblock;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeManager.BiomeEntry;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleContainerPan;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleImage;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiPowerConsumer;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;

public class TileBiomeScanner extends TileMultiPowerConsumer {

    /** Effectively final, process lifetime: built once at class initialisation. */
    private static final Object[][][] structure = new Object[][][]{

            {{null, null, null, null, null},
                    {null, null, null, null, null},
                    {null, null, 'c', null, null},
                    {null, null, null, null, null},
                    {null, null, null, null, null}},

            {{null, null, null, null, null},
                    {null, null, null, null, null},
                    {null, null, LibVulpesBlocks.motors, null, null},
                    {null, null, null, null, null},
                    {null, null, null, null, null}},

            {{null, "blockAluminum", "blockAluminum", "blockAluminum", null},
                    {"blockAluminum", "blockAluminum", StellurgyBlocks.blockStructureTower, "blockAluminum", "blockAluminum"},
                    {"blockAluminum", StellurgyBlocks.blockStructureTower, LibVulpesBlocks.blockStructureBlock, StellurgyBlocks.blockStructureTower, "blockAluminum"},
                    {"blockAluminum", "blockAluminum", StellurgyBlocks.blockStructureTower, "blockAluminum", "blockAluminum"},
                    {null, "blockAluminum", "blockAluminum", "blockAluminum", null}},

            {{Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR},
                    {Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR},
                    {Blocks.AIR, Blocks.AIR, Blocks.REDSTONE_BLOCK, Blocks.AIR, Blocks.AIR},
                    {Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR},
                    {Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR}}};


    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> list = new LinkedList<>();//super.getModules(ID, player);

        boolean suitable = true;
        for (int y = this.getPos().getY() - 4; y > 0; y--) {
            if (!world.isAirBlock(new BlockPos(this.getPos().getX(), y, this.getPos().getZ()))) {
                suitable = false;
                break;
            }
        }

        if (world.isRemote) {
            list.add(new ModuleImage(24, 14, dev.stannismod.stellurgy.inventory.TextureResources.earthCandyIcon));


            ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
            // C172: getSpaceStationFromBlockCoords is null when the scanner is not on a
            // registered space station (off-station, or space-dim coords over an empty
            // grid cell); guard it so opening the GUI there does not NPE the client.
            if (suitable && spaceObject != null && SpaceObjectManager.WARPDIMID != spaceObject.getOrbitingPlanetId()) {

                DimensionProperties properties = DimensionManager.getInstance().getDimensionProperties(spaceObject.getOrbitingPlanetId());
                List<ModuleBase> list2 = new LinkedList<>();
                if (properties.isGasGiant()) {
                    list2.add(new ModuleText(32, 16, LibVulpes.proxy.getLocalizedString("msg.biomescanner.gas"), 0x202020));
                } else if (properties.isStar()) {
                    list2.add(new ModuleText(32, 16, LibVulpes.proxy.getLocalizedString("msg.biomescanner.star"), 0x202020));
                } else {


                    int i = 0;
                    if (properties.getId() == 0) {
                        for (Biome biome : Biome.REGISTRY) {
                            if (biome != null)
                                list2.add(new ModuleText(32, 16 + 12 * (i++), Stellurgy.proxy.getNameFromBiome(biome), 0x202020));
                        }
                    } else {
                        for (BiomeEntry biome : properties.getBiomes()) {
                            list2.add(new ModuleText(32, 16 + 12 * (i++), Stellurgy.proxy.getNameFromBiome(biome.biome), 0x202020));
                        }
                    }
                }
                //Relying on a bug, is this safe?
                ModuleContainerPan pan = new ModuleContainerPan(4, 16, list2, new LinkedList<>(), null, 160, 110, 0, -64, 0, 1000);
                list.add(pan);
            } else
                list.add(new ModuleText(32, 16, ChatFormatting.OBFUSCATED + "Foxes, that is all", 0x202020));
        }

        return list;
    }

    @Override
    @Nonnull
    public AxisAlignedBB getRenderBoundingBox() {

        return new AxisAlignedBB(pos.add(-5, -3, -5), pos.add(5, 3, 5));
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockBiomeScanner.getLocalizedName();
    }
}
