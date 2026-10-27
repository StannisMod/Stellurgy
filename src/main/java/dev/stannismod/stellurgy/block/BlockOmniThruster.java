package dev.stannismod.stellurgy.block;

import dev.stannismod.stellurgy.api.IShipActuatorBlock;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.OmniThruster;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * An omnidirectional manoeuvring thruster: mounted by one face on a block of the ship, with a nozzle on
 * each of the other five. What a nozzle gives is {@link OmniThruster}'s; this block says which face it is
 * mounted on and which nozzles the world leaves free to fire — those whose neighbouring cell is air.
 */
public class BlockOmniThruster extends Block implements IShipActuatorBlock {

    /**
     * The face the thruster is mounted on: the side of it that touches the block it was placed against.
     * Effectively final, process lifetime: built once at class initialisation.
     */
    public static final PropertyEnum<EnumFacing> MOUNT = PropertyEnum.create("mount", EnumFacing.class);

    public BlockOmniThruster(Material material) {
        super(material);
        setDefaultState(blockState.getBaseState().withProperty(MOUNT, EnumFacing.DOWN));
    }

    @Override
    public void addActuators(World world, BlockPos pos, IBlockState state, List<Actuator> out) {
        EnumFacing mount = state.getValue(MOUNT);
        Set<EnumFacing> free = EnumSet.noneOf(EnumFacing.class);
        for (EnumFacing nozzle : EnumFacing.VALUES) {
            if (nozzle != mount && world.isAirBlock(pos.offset(nozzle))) {
                free.add(nozzle);
            }
        }
        out.addAll(OmniThruster.at(pos.getX(), pos.getY(), pos.getZ(), mount, free));
    }

    /** Nothing wears a thruster out yet; it works while it is aboard. */
    @Override
    public boolean isWorking(World world, BlockPos pos, IBlockState state) {
        return true;
    }

    /** Placed against a block's {@code facing} side, it is mounted by the opposite face of its own. */
    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState().withProperty(MOUNT, facing.getOpposite());
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, MOUNT);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(MOUNT).getIndex();
    }

    @Override
    @SuppressWarnings("deprecation")
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(MOUNT, EnumFacing.values()[meta % EnumFacing.values().length]);
    }

    @Override
    @SuppressWarnings("deprecation")
    public IBlockState withRotation(IBlockState state, Rotation rotation) {
        return state.withProperty(MOUNT, rotation.rotate(state.getValue(MOUNT)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public IBlockState withMirror(IBlockState state, Mirror mirror) {
        return state.withRotation(mirror.toRotation(state.getValue(MOUNT)));
    }
}
