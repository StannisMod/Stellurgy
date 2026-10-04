package dev.stannismod.stellurgy.tile.multiblock.machine;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.block.BlockMeta;
import dev.stannismod.stellurgy.libvulpes.client.util.ProgressBarImage;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiBlock;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine;
import dev.stannismod.stellurgy.libvulpes.util.IconResource;

import java.util.List;

public class TilePrecisionAssembler extends TileMultiblockMachine implements IModularInventory, IProgressBar {

    /** Effectively final, process lifetime: built once at class initialisation. */
    public static final Object[][][] structure = new Object[][][]{{{LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock},
            {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock},
            {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock}},

            {{LibVulpesBlocks.blockStructureBlock, Blocks.GLASS, Blocks.GLASS, LibVulpesBlocks.blockStructureBlock},
                    {LibVulpesBlocks.blockStructureBlock, Blocks.AIR, Blocks.AIR, LibVulpesBlocks.blockStructureBlock},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock}},

            {{'c', '*', '*', '*'},
                    {'*', "blockCoil", "blockCoil", '*'},
                    {'*', LibVulpesBlocks.motors, LibVulpesBlocks.motors, '*'}}};

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public List<BlockMeta> getAllowableWildCardBlocks() {
        List<BlockMeta> list = super.getAllowableWildCardBlocks();

        list.add(new BlockMeta(LibVulpesBlocks.blockStructureBlock, BlockMeta.WILDCARD));
        list.addAll(TileMultiBlock.getMapping('O'));
        list.addAll(TileMultiBlock.getMapping('I'));
        list.addAll(TileMultiBlock.getMapping('P'));
        list.addAll(TileMultiBlock.getMapping('l'));
        list.addAll(TileMultiBlock.getMapping('L'));

        return list;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {

        return new AxisAlignedBB(pos.add(-4, -4, -4), pos.add(4, 4, 4));
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        int yOffset = 16;
        int xOffset = 65;

        if (world.isRemote)
            modules.add(new ModuleImage(xOffset, yOffset, new IconResource(132, 0, 53, 66, TextureResources.progressBars)));
        modules.add(new ModuleProgress(xOffset + 35, yOffset + 22, 1, new ProgressBarImage(167, 22, 13, 15, 54, 42, 13, 15, EnumFacing.DOWN, TextureResources.progressBars), this));
        modules.add(new ModuleProgress(xOffset + 36, yOffset + 41, 2, new ProgressBarImage(168, 41, 11, 15, 67, 42, 11, 15, EnumFacing.DOWN, TextureResources.progressBars), this));
        modules.add(new ModuleProgress(xOffset + 31, yOffset + 62, 3, new ProgressBarImage(163, 62, 21, 3, 90, 42, 21, 3, EnumFacing.EAST, TextureResources.progressBars), this));

        return modules;
    }

    @Override
    public int getProgress(int id) {
        if (id == 0) {
            return super.getProgress(id);
        }
        if (id == 1) {
            return Math.min(currentTime, completionTime / 3);
        } else if (id == 2) {
            int relativeTime = currentTime - (completionTime / 3);
            return relativeTime >= 0 ? Math.min(relativeTime, completionTime / 3) : 0;
        }

        int relativeTime = currentTime - (2 * completionTime / 3);

        return relativeTime >= 0 ? Math.min(relativeTime, completionTime / 3) : 0;
    }

    @Override
    public int getTotalProgress(int id) {
        if (id == 0)
            return super.getTotalProgress(id);
        return completionTime / 3;
    }

    @Override
    public SoundEvent getSound() {
        return AudioRegistry.precAss;
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        TileEntity tileEntity = world.getTileEntity(pos);
        return tileEntity instanceof TilePrecisionAssembler;
    }

    @Override
    public void setTotalProgress(int id, int progress) {
        if (id == 0)
            super.setTotalProgress(id, progress);
    }

    @Override
    public void setProgress(int id, int progress) {
        if (id == 0)
            super.setProgress(id, progress);
    }

    @Override
    public float getNormallizedProgress(int id) {
        return getProgress(id) / (float) getTotalProgress(id);
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockPrecisionAssembler.getLocalizedName();
    }
}