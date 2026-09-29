package dev.stannismod.stellurgy.tile.multiblock.machine;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleProgress;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine;

import java.util.List;

public class TileCrystallizer extends TileMultiblockMachine implements IModularInventory {


    public static final Object[][][] structure = {{{StellurgyBlocks.blockQuartzCrucible, StellurgyBlocks.blockQuartzCrucible, StellurgyBlocks.blockQuartzCrucible},
            {StellurgyBlocks.blockQuartzCrucible, StellurgyBlocks.blockQuartzCrucible, StellurgyBlocks.blockQuartzCrucible}},

            {{'O', 'c', 'I'},
                    {'l', 'P', 'L'}},

    };

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos2, IBlockState tile) {
        return true;
    }

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public SoundEvent getSound() {
        return AudioRegistry.crystallizer;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(pos.add(-2, -2, -2), pos.add(2, 2, 2));
    }

    public boolean isGravityWithinBounds() {
        if (!(StellurgyConfiguration.getCurrentConfig().crystalliserMaximumGravity == 0)) {
            return StellurgyConfiguration.getCurrentConfig().crystalliserMaximumGravity > DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension()).gravitationalMultiplier;
        }
        return true;
    }

    @Override
    protected void onRunningPoweredTick() {
        if (isGravityWithinBounds()) {
            super.onRunningPoweredTick();
        }

    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        modules.add(new ModuleProgress(100, 4, 0, TextureResources.crystallizerProgressBar, this));
        if (!isGravityWithinBounds()) {
            modules.add(new ModuleText(10, 75, LibVulpes.proxy.getLocalizedString("msg.crystalliser.gravityTooHigh"), 0xFF1b1b));
        }
        return modules;
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockCrystallizer.getLocalizedName();
    }
}
