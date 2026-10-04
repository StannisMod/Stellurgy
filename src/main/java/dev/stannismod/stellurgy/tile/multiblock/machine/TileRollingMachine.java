package dev.stannismod.stellurgy.tile.multiblock.machine;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleProgress;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine;

import java.util.List;

public class TileRollingMachine extends TileMultiblockMachine {

    /** Effectively final, process lifetime: built once at class initialisation. */
    public static final Object[][][] structure = new Object[][][]{
            {{Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR, Blocks.AIR},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, "blockSteel", LibVulpesBlocks.blockStructureBlock}},

            {{'P', 'c', 'I', Blocks.AIR, Blocks.AIR},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.motors, LibVulpesBlocks.motors, "blockSteel", LibVulpesBlocks.blockStructureBlock},
                    {LibVulpesBlocks.blockStructureBlock, 'L', 'O', "blockSteel", LibVulpesBlocks.blockStructureBlock}}
    };

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        modules.add(new ModuleProgress(70, 20, 0, TextureResources.rollingMachineProgressBar, this));
        return modules;
    }


    @Override
    public SoundEvent getSound() {
        return AudioRegistry.rollingMachine;
    }

    @Override
    public int getSoundDuration() {
        return 30;
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        return true;
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockRollingMachine.getLocalizedName();
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {

        return new AxisAlignedBB(pos.add(-4, -4, -4), pos.add(4, 4, 4));
    }

}
