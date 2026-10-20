package dev.stannismod.stellurgy.libvulpes.tile.energy;

import dev.stannismod.stellurgy.libvulpes.compat.GTEnergyCapability;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;

public class TilePlugInputGregTech extends TileForgePowerOutput {

	public TilePlugInputGregTech() {
		super();
	}
	boolean tickedOnce = false;
	@Override
	public String getModularInventoryName() {
		return "tile.GTPlug.name";
	}

	@Override
	@Nullable
	public String getName() {
		return null;
	}
	
	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
		if( capability == gregtech.api.capability.GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER)
			return true;
		return super.hasCapability(capability, facing);
	}
	
	@Override
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
		if( capability == gregtech.api.capability.GregtechCapabilities.CAPABILITY_ENERGY_CONTAINER)
			return (T) new GTEnergyCapability(storage, this);
		return super.getCapability(capability, facing);
	}

	/** The world tick {@link #amperesUsed} counts for. */
	private long amperesTick = Long.MIN_VALUE;
	/** Amperes GT's network has delivered into this plug during {@link #amperesTick}. */
	private long amperesUsed;

	/** How many of {@code perTick} amperes GT may still deliver into this plug this tick. */
	public long amperesLeftThisTick(long perTick) {
		if (world.getTotalWorldTime() != amperesTick)
			return perTick;
		return Math.max(0, perTick - amperesUsed);
	}

	public void useAmperes(long amperes) {
		long now = world.getTotalWorldTime();
		if (now != amperesTick) {
			amperesTick = now;
			amperesUsed = 0;
		}
		amperesUsed += amperes;
	}

	@Override
	public int acceptEnergy(int amt, boolean simulate) {
		return 0;
	}

	@Override
	public boolean canReceive() {
		return false;
	}

	@Override
	public boolean canExtract() {
		return true;
	}
	

	@Override
	public void update() {
		if(!world.isRemote) {
			for(EnumFacing facing : EnumFacing.VALUES) {
				TileEntity tile = world.getTileEntity(this.getPos().offset(facing));

				if(tile != null && tile.hasCapability(CapabilityEnergy.ENERGY, facing.getOpposite())) {
					IEnergyStorage storage = tile.getCapability(CapabilityEnergy.ENERGY,  facing.getOpposite());
					if(storage != null)
						this.extractEnergy(storage.receiveEnergy(getUniversalEnergyStored(), false),false);
				}
			}
		}
	}
}

