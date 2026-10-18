package dev.stannismod.stellurgy.tile;

import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A rocket fuel tank's tile: the tank's wear, and the fluid it holds — only fluids of its ROLE, the fuel
 * registry's type the tank block stands for (monopropellant, a pair's fuel, oxidizer or a nuclear working
 * fluid). Exposed as the Forge fluid capability on every face, so a pipe or a bucket fills it, and the
 * mass frame weighs what it holds; on a tier-2 ship that makes it a holder of the propellant pool.
 *
 * <p>A tier-1 rocket does not burn from it: a rocket's propellant is its stats', loaded at the pad's
 * fuelling station, and the assemblers refuse a rocket whose tanks hold fluid.</p>
 */
public class TileFuelTank extends TileWearable {

    private static final String ROLE_KEY = "role";
    private static final String TANK_KEY = "tank";
    private static final String CAPACITY_KEY = "capacity";

    @Nullable
    private FuelType role;

    private final FluidTank tank = new FluidTank(0) {
        @Override
        public boolean canFillFluidType(FluidStack fluid) {
            return fluid != null && accepts(fluid.getFluid());
        }

        @Override
        protected void onContentsChanged() {
            TileFuelTank.this.markDirty();
        }
    };

    /** The form the tile registry builds before reading a saved tank; its role and capacity come from NBT. */
    public TileFuelTank() {
        super();
        tank.setTileEntity(this);
    }

    public TileFuelTank(FuelType role, int capacityMb, int maxStage, float transitionProb) {
        super(maxStage, transitionProb);
        this.role = role;
        tank.setCapacity(capacityMb);
        tank.setTileEntity(this);
    }

    /** The fuel registry type this tank holds, or {@code null} for a tile not yet read from NBT. */
    @Nullable
    public FuelType role() {
        return role;
    }

    /** Whether {@code fluid} is one this tank's role accepts. */
    public boolean accepts(@Nullable Fluid fluid) {
        return role != null && fluid != null && role.isFuel(fluid);
    }

    /** What the tank holds, or {@code null} when empty. */
    @Nullable
    public FluidStack contents() {
        FluidStack in = tank.getFluid();
        return in == null || in.amount <= 0 ? null : in.copy();
    }

    public int capacityMb() {
        return tank.getCapacity();
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(tank);
        }
        return super.getCapability(capability, facing);
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(final NBTTagCompound compound) {
        if (role != null) {
            compound.setString(ROLE_KEY, role.name());
        }
        compound.setInteger(CAPACITY_KEY, tank.getCapacity());
        compound.setTag(TANK_KEY, tank.writeToNBT(new NBTTagCompound()));
        return super.writeToNBT(compound);
    }

    @Override
    public void readFromNBT(@Nonnull final NBTTagCompound compound) {
        super.readFromNBT(compound);
        role = compound.hasKey(ROLE_KEY) ? FuelType.valueOf(compound.getString(ROLE_KEY)) : null;
        tank.setCapacity(compound.getInteger(CAPACITY_KEY));
        tank.readFromNBT(compound.getCompoundTag(TANK_KEY));
    }
}
