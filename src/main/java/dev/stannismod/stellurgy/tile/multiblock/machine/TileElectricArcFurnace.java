package dev.stannismod.stellurgy.tile.multiblock.machine;

import dev.stannismod.stellurgy.tile.heat.TileWasteHeatMachine;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundEvent;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.libvulpes.block.BlockMeta;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleProgress;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiBlock;

import java.util.List;

public class TileElectricArcFurnace extends TileWasteHeatMachine implements IModularInventory {


    public static final Object[][][] structure = {
            {{null, null, null, null, null},
                    {null, 'P', StellurgyBlocks.blockBlastBrick, 'P', null},
                    {null, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, null},
                    {null, StellurgyBlocks.blockBlastBrick, 'P', StellurgyBlocks.blockBlastBrick, null},
                    {null, null, null, null, null},
            },

            {{null, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, null},
                    {StellurgyBlocks.blockBlastBrick, "blockCoil", Blocks.AIR, "blockCoil", StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, Blocks.AIR, Blocks.AIR, Blocks.AIR, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, Blocks.AIR, "blockCoil", Blocks.AIR, StellurgyBlocks.blockBlastBrick},
                    {null, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, null},
            },

            {{StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, Blocks.AIR, Blocks.AIR, Blocks.AIR, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, Blocks.AIR, Blocks.AIR, Blocks.AIR, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, Blocks.AIR, Blocks.AIR, Blocks.AIR, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick},
            },

            {{StellurgyBlocks.blockBlastBrick, '*', 'c', '*', StellurgyBlocks.blockBlastBrick},
                    {'*', StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, '*'},
                    {'*', StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, '*'},
                    {'*', StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, '*'},
                    {StellurgyBlocks.blockBlastBrick, '*', '*', '*', StellurgyBlocks.blockBlastBrick},
            },

            {{StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick},
                    {StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick, StellurgyBlocks.blockBlastBrick},
            }

    };

    @Override
    public List<BlockMeta> getAllowableWildCardBlocks() {
        List<BlockMeta> list = super.getAllowableWildCardBlocks();
        list.addAll(TileMultiBlock.getMapping('O'));
        list.addAll(TileMultiBlock.getMapping('I'));
        list.addAll(TileMultiBlock.getMapping('l'));
        list.addAll(TileMultiBlock.getMapping('L'));
        list.add(new BlockMeta(StellurgyBlocks.blockBlastBrick, -1));
        return list;
    }

    @Override
    protected void integrateTile(TileEntity tile) {
        super.integrateTile(tile);
    }

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public SoundEvent getSound() {
        return AudioRegistry.electricArcFurnace;
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockArcFurnace.getLocalizedName();
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        modules.add(new ModuleProgress(80, 20, 0, TextureResources.arcFurnaceProgressBar, this));
        return modules;
    }
}
