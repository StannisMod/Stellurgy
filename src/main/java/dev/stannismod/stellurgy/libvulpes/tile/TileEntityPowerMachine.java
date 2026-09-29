package dev.stannismod.stellurgy.libvulpes.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import dev.stannismod.stellurgy.libvulpes.api.IUniversalEnergy;
import dev.stannismod.stellurgy.libvulpes.cap.ForgePowerCapability;
import dev.stannismod.stellurgy.libvulpes.cap.TeslaHandler;
import dev.stannismod.stellurgy.libvulpes.energy.IPower;
import dev.stannismod.stellurgy.libvulpes.util.UniversalBattery;

public abstract class TileEntityPowerMachine extends TileEntityMachine implements IPower, IUniversalEnergy {

	protected UniversalBattery energy;
	
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		energy.writeToNBT(nbt);
		return nbt;
	}
	
	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {

		return capability == CapabilityEnergy.ENERGY || TeslaHandler.hasTeslaCapability(this, capability);
	}

	@Override
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {

		if(capability == CapabilityEnergy.ENERGY )
			return (T)(new ForgePowerCapability(this));
		else if(TeslaHandler.hasTeslaCapability(this, capability))
			return (T)(TeslaHandler.getHandler(this));
		
		return super.getCapability(capability, facing);
	}
	
	@Override
	public NBTTagCompound serializeNBT() {
		return new NBTTagCompound();
	}
	@Override
	public void deserializeNBT(NBTTagCompound nbt) {
		
	}
	
	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		energy.readFromNBT(nbt);
	}
	
	@Override
	public int receiveEnergy(EnumFacing from, int maxReceive,
			boolean simulate) {
		return energy.acceptEnergy(maxReceive, simulate);
	}

	@Override
	public int extractEnergy(EnumFacing from, int maxExtract,
			boolean simulate) {
		return 0;
	}

	@Override
	public int getEnergyStored(EnumFacing from) {
		return energy.getUniversalEnergyStored();
	}

	@Override
	public int getMaxEnergyStored(EnumFacing from) {
		return energy.getMaxEnergyStored();
	}
	
	public boolean hasEnergy() { return energy.getUniversalEnergyStored() > 0; }

	@Override
	public void setEnergyStored(int value) {
		energy.setEnergyStored(value);
	}

	
	public void removePower(int amt) {
		energy.extractEnergy(amt, false);
	}
	
	
	public int getPower() {
		return energy.getUniversalEnergyStored();
	}

	@Override
	public boolean canConnectEnergy(EnumFacing arg0) {
		return true;
	}
}
