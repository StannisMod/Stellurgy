package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IFuelTank;
import dev.stannismod.stellurgy.api.IRocketEngine;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.libvulpes.block.BlockFullyRotatable;
import dev.stannismod.stellurgy.tile.TileFuelTank;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class BlockFuelTank extends BlockFullyRotatable implements IFuelTank {

    /** Effectively final, process lifetime: built once at class initialisation. */
    public final static PropertyEnum<TankStates> TANKSTATES = PropertyEnum.create("tankstates", TankStates.class);

    public BlockFuelTank(Material mat) {
        super(mat);
        this.setDefaultState(this.getDefaultState().withProperty(TANKSTATES, TankStates.MIDDLE).withProperty(FACING, EnumFacing.DOWN));
    }

    @Override
    @Nonnull
    public IBlockState getStateFromMeta(int meta) {
        int tankstate = meta%3;
        int rotationstate = meta/3;

        IBlockState state;
        state = this.getDefaultState().withProperty(TANKSTATES, TankStates.values()[tankstate]);
        if (rotationstate == 0) {state = state.withProperty(FACING, EnumFacing.UP);}
        if (rotationstate == 1) {state = state.withProperty(FACING, EnumFacing.SOUTH);}
        if (rotationstate == 2) {state = state.withProperty(FACING, EnumFacing.EAST);}
        //System.out.println("####");
        //System.out.println("rotation:"+rotationstate);
        //System.out.println("meta"+meta);
        //System.out.println("----");
        return state;
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int i = 0;
        if (state.getValue(FACING) == EnumFacing.UP) {i=0;}
        if (state.getValue(FACING) == EnumFacing.SOUTH) {i=1;}
        if (state.getValue(FACING) == EnumFacing.EAST) {i=2;}

        int v = state.getValue(TANKSTATES).ordinal() + i*3;
                //System.out.println("v:"+v);

        return v;
    }

    @Override
    @Nonnull
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, TANKSTATES, FACING);
    }

    @Override
    @Nonnull
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world,
                                      BlockPos pos) {





        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.DOWN)
            state = state.withProperty(FACING, EnumFacing.UP);
        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.NORTH)
            state = state.withProperty(FACING, EnumFacing.SOUTH);
        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.WEST)
            state = state.withProperty(FACING, EnumFacing.EAST);

        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.DOWN || world.getBlockState(pos).getValue(FACING) == EnumFacing.UP) {
            int i = (world.getBlockState(pos.add(0, 1, 0)).getBlock() == this && world.getBlockState(pos.add(0, 1, 0)).getValue(FACING) == EnumFacing.UP) ? 1 : 0;
            i += (world.getBlockState(pos.add(0, -1, 0)).getBlock() == this && world.getBlockState(pos.add(0, -1, 0)).getValue(FACING) == EnumFacing.UP) || world.getBlockState(pos.add(0, -1, 0)).getBlock() instanceof IRocketEngine ? 2 : 0;

            //If there is no tank below this one and no engine below
            if (i == 1) {
                return state.withProperty(TANKSTATES, TankStates.BOTTOM);
            }
            //If there is no tank above this one
            else if (i == 2) {
                return state.withProperty(TANKSTATES, TankStates.TOP);
            }
            //If there is a tank above and below this one
            else {
                return state.withProperty(TANKSTATES, TankStates.MIDDLE);
            }
        }

        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.EAST || world.getBlockState(pos).getValue(FACING) == EnumFacing.WEST) {
            int i = (world.getBlockState(pos.add(1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(1, 0, 0)).getValue(FACING) == EnumFacing.EAST) || world.getBlockState(pos.add(1, 0, 0)).getBlock() instanceof IRocketEngine ? 1 : 0;
            i += (world.getBlockState(pos.add(-1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(-1, 0, 0)).getValue(FACING) == EnumFacing.EAST) || world.getBlockState(pos.add(-1, 0, 0)).getBlock() instanceof IRocketEngine ? 2 : 0;

            //If there is no tank below this one and no engine below
            if (i == 1) {
                return state.withProperty(TANKSTATES, TankStates.BOTTOM);
            }
            //If there is no tank above this one
            else if (i == 2) {
                return state.withProperty(TANKSTATES, TankStates.TOP);
            }
            //If there is a tank above and below this one
            else {
                return state.withProperty(TANKSTATES, TankStates.MIDDLE);
            }
        }

        if (world.getBlockState(pos).getValue(FACING) == EnumFacing.NORTH || world.getBlockState(pos).getValue(FACING) == EnumFacing.SOUTH) {
            int i = (world.getBlockState(pos.add(0, 0, 1)).getBlock() == this && world.getBlockState(pos.add(0, 0, 1)).getValue(FACING) == EnumFacing.SOUTH) || world.getBlockState(pos.add(0, 0, 1)).getBlock() instanceof IRocketEngine ? 1 : 0;
            i += (world.getBlockState(pos.add(0, 0, -1)).getBlock() == this && world.getBlockState(pos.add(0, 0, -1)).getValue(FACING) == EnumFacing.SOUTH) || world.getBlockState(pos.add(0, 0, -1)).getBlock() instanceof IRocketEngine ? 2 : 0;

            //If there is no tank below this one and no engine below
            if (i == 1) {
                return state.withProperty(TANKSTATES, TankStates.BOTTOM);
            }
            //If there is no tank above this one
            else if (i == 2) {
                return state.withProperty(TANKSTATES, TankStates.TOP);
            }
            //If there is a tank above and below this one
            else {
                return state.withProperty(TANKSTATES, TankStates.MIDDLE);
            }
        }

        return state;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, @Nonnull ItemStack stack) {
        if (Math.abs(placer.rotationPitch) > 50.0F) {
            world.setBlockState(pos, state.withProperty(FACING, placer.rotationPitch > 0.0F ? EnumFacing.UP : EnumFacing.DOWN), 2);
        } else {
            world.setBlockState(pos, state.withProperty(FACING, placer.getHorizontalFacing().getOpposite()), 2);
        }
        state = world.getBlockState(pos);

        if (state.getValue(FACING) == EnumFacing.DOWN)
            world.setBlockState(pos,state.withProperty(FACING, EnumFacing.UP));
        if (state.getValue(FACING) == EnumFacing.NORTH)
            world.setBlockState(pos,state.withProperty(FACING, EnumFacing.SOUTH));
        if (state.getValue(FACING) == EnumFacing.WEST)
            world.setBlockState(pos,state.withProperty(FACING, EnumFacing.EAST));


        List<EnumFacing> nextblocks = new LinkedList<>();
        if (world.getBlockState(pos.add(0, 1, 0)).getBlock() == this && world.getBlockState(pos.add(0, 1, 0)).getValue(FACING) == EnumFacing.UP)
            nextblocks.add (world.getBlockState(pos.add(0, 1, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(0, -1, 0)).getBlock() == this  && world.getBlockState(pos.add(0, -1, 0)).getValue(FACING) == EnumFacing.UP)
            nextblocks.add (world.getBlockState(pos.add(0, -1, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(-1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(-1, 0, 0)).getValue(FACING) == EnumFacing.EAST)
            nextblocks.add (world.getBlockState(pos.add(-1, 0, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(1, 0, 0)).getBlock() == this && world.getBlockState(pos.add(1, 0, 0)).getValue(FACING) == EnumFacing.EAST)
            nextblocks.add (world.getBlockState(pos.add(1, 0, 0)).getValue(FACING));
        if (world.getBlockState(pos.add(0, 0, 1)).getBlock() == this && world.getBlockState(pos.add(0, 0, 1)).getValue(FACING) == EnumFacing.SOUTH)
            nextblocks.add (world.getBlockState(pos.add(0, 0, 1)).getValue(FACING));
        if (world.getBlockState(pos.add(0, 0, -1)).getBlock() == this && world.getBlockState(pos.add(0, 0, -1)).getValue(FACING) == EnumFacing.SOUTH)
            nextblocks.add (world.getBlockState(pos.add(0, 0, -1)).getValue(FACING));


        if (!nextblocks.isEmpty()){
            boolean rotate = true;
            EnumFacing targetfacing =null;
            targetfacing = nextblocks.get(0);

            for (int i = 0; i < nextblocks.size(); i++) {
                if (nextblocks.get(i) != targetfacing)
                    rotate = false;
            }
            if (rotate)
                world.setBlockState(pos,state.withProperty(FACING, targetfacing));
        }

        net.minecraft.tileentity.TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileFuelTank) {
            ((TileFuelTank) te).restore(carriedFluid(stack));
        }
    }

    /** The item-stack key under which a broken tank carries what it held. */
    private static final String CARRIED_KEY = "carriedFluid";

    @Nullable
    private static net.minecraftforge.fluids.FluidStack carriedFluid(ItemStack stack) {
        net.minecraft.nbt.NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey(CARRIED_KEY)
                ? net.minecraftforge.fluids.FluidStack.loadFluidStackFromNBT(tag.getCompoundTag(CARRIED_KEY)) : null;
    }

    // Removal is held back until harvestBlock so the drop can still read the tile's fluid; the vanilla
    // order removes the block, and with it the tile, before the drops are made.
    @Override
    public boolean removedByPlayer(@Nonnull IBlockState state, World world, @Nonnull BlockPos pos,
                                   @Nonnull EntityPlayer player, boolean willHarvest) {
        return willHarvest || super.removedByPlayer(state, world, pos, player, false);
    }

    @Override
    public void harvestBlock(@Nonnull World world, EntityPlayer player, @Nonnull BlockPos pos, @Nonnull IBlockState state,
                             @Nullable net.minecraft.tileentity.TileEntity te, ItemStack tool) {
        super.harvestBlock(world, player, pos, state, te, tool);
        world.setBlockToAir(pos);
    }

    @Override
    public void getDrops(@Nonnull net.minecraft.util.NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos,
                         @Nonnull IBlockState state, int fortune) {
        super.getDrops(drops, world, pos, state, fortune);
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(pos);
        net.minecraftforge.fluids.FluidStack held = te instanceof TileFuelTank ? ((TileFuelTank) te).contents() : null;
        if (held == null) {
            return;
        }
        for (ItemStack drop : drops) {
            if (net.minecraft.block.Block.getBlockFromItem(drop.getItem()) == this) {
                net.minecraft.nbt.NBTTagCompound tag = drop.hasTagCompound()
                        ? drop.getTagCompound() : new net.minecraft.nbt.NBTTagCompound();
                tag.setTag(CARRIED_KEY, held.writeToNBT(new net.minecraft.nbt.NBTTagCompound()));
                drop.setTagCompound(tag);
                return;
            }
        }
    }

    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               net.minecraft.client.util.ITooltipFlag flag) {
        super.addInformation(stack, world, tooltip, flag);
        net.minecraftforge.fluids.FluidStack carried = carriedFluid(stack);
        if (carried != null) {
            tooltip.add(net.minecraft.client.resources.I18n.format("tooltip.stellurgy.fueltank.carried",
                    carried.amount, carried.getLocalizedName()));
        }
    }


    /** What every rocket fuel tank holds, millibuckets: one cubic metre of its role's fluid. */
    public static final int CAPACITY_MB = 1000;

    @Override
    public int getMaxFill(World world, BlockPos pos, IBlockState state) {
        return CAPACITY_MB;
    }

    /** The fuel registry type of the fluid this tank holds: a monopropellant. */
    public FuelRegistry.FuelType role() {
        return FuelRegistry.FuelType.LIQUID_MONOPROPELLANT;
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public net.minecraft.tileentity.TileEntity createTileEntity(World worldIn, IBlockState state) {
        // Wear lives only in the tile (no meta overload, no breaking render):
        // a worn tank may leak/explode on launch; replacing the block resets wear.
        return new TileFuelTank(role(), CAPACITY_MB,
                10, (float) dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player, EnumHand hand,
                                    EnumFacing side, float hitX, float hitY, float hitZ) {
        if (FluidUtil.interactWithFluidHandler(player, hand, world, pos, side)) {
            return true;
        }
        return super.onBlockActivated(world, pos, state, player, hand, side, hitX, hitY, hitZ);
    }

    public enum TankStates implements IStringSerializable {
        TOP,
        BOTTOM,
        MIDDLE;

        @Override
        public String getName() {
            return name().toLowerCase(Locale.ENGLISH);
        }

    }
}
