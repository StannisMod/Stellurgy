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
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleProgress;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine;

import java.util.List;

public class TileLathe extends TileMultiblockMachine implements IModularInventory {

    public static final Object[][][] structure = {
            {{'c', LibVulpesBlocks.motors, Blocks.AIR, 'I'}},
            {{'P', LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, 'O'}},
    };

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        return true;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {

        return new AxisAlignedBB(pos.add(-3, -2, -3), pos.add(3, 2, 3));
    }

    @Override
    public SoundEvent getSound() {
        return AudioRegistry.lathe;
    }

    @Override
    public int getSoundDuration() {
        return 30;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        modules.add(new ModuleProgress(100, 40, 0, TextureResources.latheProgressBar, this));
        return modules;
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockLathe.getLocalizedName();
    }
}
