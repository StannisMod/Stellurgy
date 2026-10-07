package dev.stannismod.stellurgy.tile.multiblock.machine;

import dev.stannismod.stellurgy.tile.heat.TileWasteHeatMachine;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.interfaces.IRecipe;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleProgress;

import javax.annotation.Nonnull;
import java.util.List;

public class TilePrecisionLaserEtcher extends TileWasteHeatMachine implements IModularInventory {

    /** Effectively final, process lifetime: built once at class initialisation. */
    public static final Object[][][] structure = {
            {{"slab", "slab", "slab"},
                    {Blocks.AIR, "slab", Blocks.AIR},
                    {"slab", "slab", "slab"}},

            {{StellurgyBlocks.blockStructureTower, Blocks.AIR, LibVulpesBlocks.blockStructureBlock},
                    {Blocks.AIR, StellurgyBlocks.blockVacuumLaser, LibVulpesBlocks.blockStructureBlock},
                    {StellurgyBlocks.blockStructureTower, Blocks.AIR, LibVulpesBlocks.blockStructureBlock}},

            {{LibVulpesBlocks.blockStructureBlock, 'c', 'I'},
                    {'P', LibVulpesBlocks.motors, 'O'},
                    {'P', LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock}},
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
    protected float getTimeMultiplierForRecipe(IRecipe recipe) {
        return super.getTimeMultiplierForRecipe(recipe);
    }

    /** The etching lens is the machine's tool: a recipe needs one present and keeps it. */
    @Override
    protected boolean consumesIngredient(@Nonnull ItemStack ingredient) {
        return !isLensItem(ingredient);
    }

    @Override
    @Nonnull
    public AxisAlignedBB getRenderBoundingBox() {

        return new AxisAlignedBB(pos.add(-3, -2, -3), pos.add(3, 2, 3));
    }

    private boolean isLensItem(@Nonnull ItemStack stack) {
        int[] oreIds = OreDictionary.getOreIDs(stack);
        for (int oreId : oreIds) {
            if (OreDictionary.getOreName(oreId).contains("lensPrecisionLaserEtcher")) {
                return true;
            }
        }
        return false;
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
        return StellurgyBlocks.blockPrecisionLaserEngraver.getLocalizedName();
    }
}
