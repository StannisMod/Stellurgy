package dev.stannismod.stellurgy.libvulpes.cap;

import net.minecraftforge.energy.IEnergyStorage;
import dev.stannismod.stellurgy.libvulpes.api.IUniversalEnergy;

public class ForgePowerCapability implements IEnergyStorage {

	IUniversalEnergy energy;
	
	public ForgePowerCapability(IUniversalEnergy energy) {
		this.energy = energy;
	}
	
	/**
	 * The two transfers are the world's way in, and they answer only in the directions the store
	 * declares. The store's own methods stay unguarded because its owner moves energy through them
	 * the other way: a machine draws from its input plugs, a generator fills its output plugs.
	 */
	@Override
	public int receiveEnergy(int paramInt, boolean paramBoolean) {
		if(!energy.canReceive())
			return 0;
		return energy.acceptEnergy(paramInt, paramBoolean);
	}

	@Override
	public int extractEnergy(int paramInt, boolean paramBoolean) {
		if(!energy.canExtract())
			return 0;
		return energy.extractEnergy(paramInt, paramBoolean);
	}

	@Override
	public int getEnergyStored() {
		return energy.getUniversalEnergyStored();
	}

	@Override
	public int getMaxEnergyStored() {
		return energy.getMaxEnergyStored();
	}

	@Override
	public boolean canExtract() {
		return energy.canExtract();
	}

	@Override
	public boolean canReceive() {
		return energy.canReceive();
	}

}
